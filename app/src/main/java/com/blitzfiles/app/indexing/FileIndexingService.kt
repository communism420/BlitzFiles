/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.blitzfiles.app.R
import com.blitzfiles.app.app.NotificationIds
import com.blitzfiles.app.filejob.FileDeletionRecovery
import com.blitzfiles.app.filejob.FileDeletionStore
import com.blitzfiles.search.data.indexer.DefaultFileIndexer
import com.blitzfiles.search.data.repository.SQLiteIndexRepository
import com.blitzfiles.search.domain.model.IndexingMode
import com.blitzfiles.search.domain.model.IndexingRequest
import com.blitzfiles.search.domain.model.IndexingResult
import com.blitzfiles.search.domain.model.IndexingState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Runs scans as a user-visible data-sync foreground service.
 */
class FileIndexingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: SQLiteIndexRepository
    private lateinit var indexer: DefaultFileIndexer
    private lateinit var storageAccessMonitor: FileIndexingStorageAccessMonitor
    private val commandQueue = FileIndexingCommandQueue()
    private var indexingJob: Job? = null
    private var isForeground = false
    private var isDestroying = false

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        storageAccessMonitor = FileIndexingStorageAccessMonitor(this) {
            serviceScope.launch {
                if (::indexer.isInitialized) {
                    // This also releases a paused indexer so permission revocation cannot leave
                    // a foreground service waiting indefinitely.
                    indexer.cancel()
                }
            }
        }
        repository = SQLiteIndexRepository.create(this)
        indexer = DefaultFileIndexer(
            repository,
            MaterialFilesIndexFileSystem(),
            canContinue = storageAccessMonitor::isGranted
        )
        serviceScope.launch {
            indexer.state.collectLatest { state ->
                FileIndexingProgressStore.publishIndexerState(state)
                updateNotification(state)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FULL, ACTION_INCREMENTAL -> enqueueIndexing(intent, startId)
            FileIndexingController.ACTION_PAUSE -> runControl(startId) { indexer.pause() }
            FileIndexingController.ACTION_RESUME -> {
                if (storageAccessMonitor.refresh()) {
                    runControl(startId) {
                        if (storageAccessMonitor.refresh()) {
                            indexer.resume()
                        }
                    }
                } else if (indexingJob?.isActive != true) {
                    stopSelf(startId)
                }
            }
            FileIndexingController.ACTION_CANCEL -> {
                commandQueue.clearPendingUnprotected()
                runControl(startId) { indexer.cancel() }
            }
            else -> if (indexingJob == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun runControl(startId: Int, action: suspend () -> Unit) {
        if (indexingJob?.isActive != true) {
            stopSelf(startId)
            return
        }
        serviceScope.launch { action() }
    }

    private fun enqueueIndexing(intent: Intent, startId: Int) {
        val mode = if (intent.action == ACTION_INCREMENTAL) {
            IndexingMode.INCREMENTAL
        } else {
            IndexingMode.FULL
        }
        val requestedRootIds = intent.getLongArrayExtra(EXTRA_ROOT_IDS)?.toSet()
        @Suppress("DEPRECATION")
        val hintsBundle = intent.getBundleExtra(EXTRA_PATH_HINTS)
        val command = FileIndexingCommand(
            mode = mode,
            rootIds = requestedRootIds,
            pathHints = FileIndexingController.decodePathHints(hintsBundle),
            deletionProtectionToken = intent
                .getLongExtra(EXTRA_DELETION_PROTECTION_TOKEN, 0)
                .takeIf { it > 0 }
        )
        if (!storageAccessMonitor.refresh()) {
            abandonDeletionProtection(command)
            if (indexingJob?.isActive != true) {
                FileIndexingProgressStore.markLaunchFailed(missingStorageAccessError())
                stopSelf(startId)
            }
            return
        }
        if (!commandQueue.enqueue(command)) {
            return
        }
        if (
            !startNextCommand() &&
            indexingJob?.isActive != true &&
            !commandQueue.hasPendingCommands
        ) {
            stopSelf(startId)
        }
    }

    private fun startNextCommand(): Boolean {
        if (isDestroying || indexingJob?.isActive == true) {
            return false
        }
        if (!storageAccessMonitor.refresh()) {
            val rejectedCommands = commandQueue.clearPending()
            rejectedCommands.forEach(::abandonDeletionProtection)
            if (rejectedCommands.isNotEmpty()) {
                FileIndexingProgressStore.markLaunchFailed(missingStorageAccessError())
            }
            return false
        }
        val command = commandQueue.startNext() ?: return false
        FileIndexingProgressStore.markPreparing()
        promoteToForeground(createNotification(IndexingState.Idle))
        indexingJob = serviceScope.launch {
            var terminalState: IndexingState? = null
            try {
                if (!storageAccessMonitor.refresh()) {
                    terminalState = IndexingState.Failed(missingStorageAccessError())
                    return@launch
                }
                val rootIds = command.rootIds ?: repository.getRoots()
                    .filter { it.isEnabled }
                    .mapNotNull { it.id }
                    .toSet()
                if (rootIds.isEmpty()) {
                    terminalState = IndexingState.Completed(EMPTY_RESULT)
                    return@launch
                }
                if (!storageAccessMonitor.refresh()) {
                    terminalState = IndexingState.Failed(missingStorageAccessError())
                    return@launch
                }
                val result = indexer.run(
                    IndexingRequest(
                        rootIds = rootIds,
                        mode = command.mode,
                        pathHints = command.pathHints,
                        treatMissingRootsAsDeleted =
                            command.deletionProtectionToken != null
                    )
                )
                terminalState = IndexingState.Completed(result)
            } catch (error: CancellationException) {
                // Cancellation is reflected in indexer state and persisted per root.
                terminalState = indexer.state.value as? IndexingState.Cancelled
                    ?: IndexingState.Cancelled(0, 0)
            } catch (error: Throwable) {
                // DefaultFileIndexer already exposes and persists the failure.
                terminalState = IndexingState.Failed(error)
                error.printStackTrace()
            } finally {
                finishCommand(
                    command,
                    terminalState ?: IndexingState.Cancelled(0, 0)
                )
            }
        }
        return true
    }

    private fun updateNotification(state: IndexingState) {
        if (
            !isForeground ||
            state !is IndexingState.Running && state !is IndexingState.Paused
        ) {
            return
        }
        promoteToForeground(createNotification(state))
    }

    private fun createNotification(state: IndexingState) =
        fileIndexingNotificationTemplate.createBuilder(this).apply {
            when (state) {
                IndexingState.Idle -> {
                    setContentText(getString(R.string.file_indexing_notification_preparing))
                    setProgress(0, 0, true)
                }
                is IndexingState.Running -> {
                    setContentText(
                        getString(
                            R.string.file_indexing_notification_progress_format,
                            state.indexedEntryCount
                        )
                    )
                    setSubText(state.currentPath)
                    setProgress(0, 0, true)
                    addAction(
                        android.R.drawable.ic_media_pause,
                        getString(R.string.file_indexing_action_pause),
                        controlPendingIntent(FileIndexingController.ACTION_PAUSE, REQUEST_PAUSE)
                    )
                }
                is IndexingState.Paused -> {
                    setContentText(
                        getString(
                            R.string.file_indexing_notification_paused_format,
                            state.indexedEntryCount
                        )
                    )
                    setProgress(0, 0, false)
                    addAction(
                        android.R.drawable.ic_media_play,
                        getString(R.string.file_indexing_action_resume),
                        controlPendingIntent(FileIndexingController.ACTION_RESUME, REQUEST_RESUME)
                    )
                }
                is IndexingState.Completed -> {
                    setContentText(
                        getString(
                            if (state.result.recoverableErrorCount == 0L) {
                                R.string.file_indexing_notification_completed_format
                            } else {
                                R.string.file_indexing_notification_completed_with_errors_format
                            },
                            state.result.indexedEntryCount
                        )
                    )
                    setProgress(0, 0, false)
                }
                is IndexingState.Cancelled -> {
                    setContentText(getString(R.string.file_indexing_notification_cancelled))
                    setProgress(0, 0, false)
                }
                is IndexingState.Failed -> {
                    setContentText(getString(R.string.file_indexing_notification_failed))
                    setProgress(0, 0, false)
                }
            }
            if (state is IndexingState.Running || state is IndexingState.Paused) {
                addAction(
                    R.drawable.stop_icon_white_24dp,
                    getString(android.R.string.cancel),
                    controlPendingIntent(FileIndexingController.ACTION_CANCEL, REQUEST_CANCEL)
                )
            }
        }.build()

    @SuppressLint("InlinedApi")
    private fun promoteToForeground(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            NotificationIds.FILE_INDEXING,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        isForeground = true
    }

    private fun controlPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            createControlIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun finishCommand(command: FileIndexingCommand, terminalState: IndexingState) {
        indexingJob = null
        command.deletionProtectionToken?.let { token ->
            FileDeletionStore.completeIndexProtection(
                token,
                succeeded = terminalState is IndexingState.Completed &&
                    terminalState.result.recoverableErrorCount == 0L
            )
        }
        if (isDestroying) {
            return
        }
        commandQueue.complete(command)
        FileIndexingProgressStore.finishCommand(
            terminalState,
            hasPendingCommand = commandQueue.hasPendingCommands
        )
        if (
            command.deletionProtectionToken == null &&
            terminalState is IndexingState.Completed &&
            terminalState.result.recoverableErrorCount == 0L
        ) {
            FileDeletionRecovery.retry(this)
        }
        if (startNextCommand()) {
            return
        }
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        commandQueue.clearPending().forEach(::abandonDeletionProtection)
        serviceScope.launch {
            indexer.cancel()
            indexingJob?.cancel()
            if (isForeground) {
                ServiceCompat.stopForeground(
                    this@FileIndexingService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE
                )
                isForeground = false
            }
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        isDestroying = true
        val stoppedDuringWork = indexingJob != null || commandQueue.hasPendingCommands
        if (stoppedDuringWork) {
            FileIndexingProgressStore.markServiceStopped()
        }
        commandQueue.reset().forEach(::abandonDeletionProtection)
        val jobToFinish = indexingJob
        jobToFinish?.cancel()
        storageAccessMonitor.close()
        serviceScope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            listOfNotNull(jobToFinish).joinAll()
            repository.close()
        }
        super.onDestroy()
    }

    private fun abandonDeletionProtection(command: FileIndexingCommand) {
        command.deletionProtectionToken?.let { token ->
            FileDeletionStore.completeIndexProtection(token, succeeded = false)
        }
    }

    private fun missingStorageAccessError(): SecurityException =
        SecurityException("All files access is required for indexing")

    companion object {
        @Volatile
        internal var isServiceRunning = false
            private set

        private const val ACTION_FULL = "com.blitzfiles.app.indexing.FULL"
        private const val ACTION_INCREMENTAL = "com.blitzfiles.app.indexing.INCREMENTAL"
        private const val EXTRA_ROOT_IDS = "root_ids"
        private const val EXTRA_PATH_HINTS = "path_hints"
        private const val EXTRA_DELETION_PROTECTION_TOKEN = "deletion_protection_token"
        private const val REQUEST_PAUSE = 1
        private const val REQUEST_RESUME = 2
        private const val REQUEST_CANCEL = 3

        private val EMPTY_RESULT = IndexingResult(
            scannedEntryCount = 0,
            indexedEntryCount = 0,
            removedEntryCount = 0,
            skippedEntryCount = 0,
            recoverableErrorCount = 0,
            durationMillis = 0
        )

        internal fun createFullIntent(context: Context, rootIds: Set<Long>?): Intent =
            Intent(context, FileIndexingService::class.java).apply {
                action = ACTION_FULL
                rootIds?.let { putExtra(EXTRA_ROOT_IDS, it.toLongArray()) }
            }

        internal fun createIncrementalIntent(
            context: Context,
            rootIds: Set<Long>,
            changedPaths: Map<Long, Set<String>>,
            deletionProtectionToken: Long? = null
        ): Intent =
            Intent(context, FileIndexingService::class.java).apply {
                action = ACTION_INCREMENTAL
                putExtra(EXTRA_ROOT_IDS, rootIds.toLongArray())
                putExtra(EXTRA_PATH_HINTS, FileIndexingController.encodePathHints(changedPaths))
                deletionProtectionToken?.let {
                    putExtra(EXTRA_DELETION_PROTECTION_TOKEN, it)
                }
            }

        internal fun createControlIntent(context: Context, action: String): Intent =
            Intent(context, FileIndexingService::class.java).setAction(action)
    }
}
