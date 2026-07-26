/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java8.nio.file.Path
import com.blitzfiles.app.R
import com.blitzfiles.app.app.AppActivity
import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.filejob.FileDeletionRecovery
import com.blitzfiles.app.indexing.FileIndexingProgress
import com.blitzfiles.app.indexing.FileIndexingProgressStore
import com.blitzfiles.app.indexing.FileIndexingStorageAccess
import com.blitzfiles.app.indexing.InitialIndexingAction
import com.blitzfiles.app.indexing.InitialIndexingCoordinator
import com.blitzfiles.app.indexing.localizeIndexingDiagnosticMessage
import com.blitzfiles.app.util.createIntent
import com.blitzfiles.app.util.extraPath
import com.blitzfiles.app.util.putArgs
import com.blitzfiles.app.util.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FileListActivity : AppActivity(), RootIndexingOfferDialogFragment.Listener {
    private lateinit var fragment: FileListFragment
    private var initialIndexingJob: Job? = null
    private var initialIndexingReconciliationJob: Job? = null
    private var isInitialRootPhaseSettled = false
    private var isRootOfferPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            fragment = FileListFragment().putArgs(FileListFragment.Args(intent))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as FileListFragment
        }
        isInitialRootPhaseSettled =
            !isLauncherIntent() || !InitialIndexingCoordinator.isPending()
        observeIndexingCompletion()
    }

    override fun onPostResume() {
        super.onPostResume()
        FileDeletionRecovery.retry(this)
        if (isRootOfferPending) {
            showRootIndexingOffer()
        }
        if (isInitialRootPhaseSettled) {
            maybeStartInitialOrdinaryIndexing()
            fragment.continueInitialPermissionOrchestration()
        } else if (isInitialStorageReadyForRoot()) {
            maybePrepareInitialRoot()
        } else {
            fragment.continueInitialPermissionOrchestration()
        }
    }

    internal fun onPermissionOrchestrationSettled() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return
        }
        if (isInitialRootPhaseSettled) {
            maybeStartInitialOrdinaryIndexing()
            fragment.continueInitialPermissionOrchestration()
        } else if (isInitialStorageReadyForRoot()) {
            maybePrepareInitialRoot()
        }
    }

    internal fun shouldDeferInitialPermissionOrchestration(): Boolean =
        isLauncherIntent() &&
            InitialIndexingCoordinator.isPending() &&
            isInitialStorageReadyForRoot() &&
            !isInitialRootPhaseSettled

    override fun onRootIndexingOfferResult(accepted: Boolean) {
        isRootOfferPending = false
        val precedingJob = initialIndexingJob?.takeIf(Job::isActive)
        initialIndexingJob = lifecycleScope.launch {
            precedingJob?.join()
            try {
                if (accepted) {
                    // Persist consent before a root provider can display its own permission UI.
                    InitialIndexingCoordinator.beginRootAcceptance()
                    InitialIndexingCoordinator.completeRootAcceptance()
                } else {
                    InitialIndexingCoordinator.declineRoot()
                }
                settleInitialRootPhase()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.printStackTrace()
                if (accepted && !FileIndexingStorageAccess.isGranted(this@FileListActivity)) {
                    try {
                        InitialIndexingCoordinator
                            .deferRootAcceptanceForMissingStorageAccess()
                    } catch (stateError: Throwable) {
                        stateError.printStackTrace()
                    }
                    waitForInitialStorageAccess()
                    return@launch
                }
                if (accepted) {
                    try {
                        InitialIndexingCoordinator.handleRootAcceptanceFailure()
                    } catch (stateError: Throwable) {
                        stateError.printStackTrace()
                    }
                }
                settleInitialRootPhase()
                showToast(
                    getString(
                        R.string.initial_indexing_root_error_format,
                        localizeIndexingDiagnosticMessage(
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                )
            }
        }
    }

    private fun maybePrepareInitialRoot() {
        if (
            !isLauncherIntent() ||
            isInitialRootPhaseSettled ||
            isRootOfferPending ||
            supportFragmentManager.findFragmentByTag(
                RootIndexingOfferDialogFragment.TAG
            ) != null ||
            !InitialIndexingCoordinator.isPending() ||
            !isInitialStorageReadyForRoot() ||
            initialIndexingJob?.isActive == true
        ) {
            return
        }
        initialIndexingJob = lifecycleScope.launch {
            try {
                when (InitialIndexingCoordinator.prepareRoot()) {
                    InitialIndexingAction.AwaitStorageAccess ->
                        waitForInitialStorageAccess()
                    InitialIndexingAction.Idle -> settleInitialRootPhase()
                    InitialIndexingAction.OfferRoot -> {
                        isRootOfferPending = true
                        showRootIndexingOffer()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.printStackTrace()
                if (!FileIndexingStorageAccess.isGranted(this@FileListActivity)) {
                    waitForInitialStorageAccess()
                    return@launch
                }
                try {
                    // prepareRoot() resumes a consented ACCEPTING transition after process death.
                    // If that retry fails, persist the STANDARD fallback just as the original
                    // acceptance callback does; otherwise onboarding remains stuck in ACCEPTING
                    // and ordinary indexing is skipped on every subsequent launch.
                    InitialIndexingCoordinator.handleRootAcceptanceFailure()
                } catch (stateError: Throwable) {
                    stateError.printStackTrace()
                }
                settleInitialRootPhase()
                showToast(R.string.initial_indexing_setup_error)
            }
        }
    }

    private fun maybeStartInitialOrdinaryIndexing() {
        if (
            !isLauncherIntent() ||
            !isInitialRootPhaseSettled ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            fragment.isStorageAccessRequestInProgress() ||
            !InitialIndexingCoordinator.isPending() ||
            !FileIndexingStorageAccess.isGranted(this) ||
            initialIndexingJob?.isActive == true
        ) {
            return
        }
        initialIndexingJob = lifecycleScope.launch {
            try {
                InitialIndexingCoordinator.prepareOrdinary()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.printStackTrace()
                showToast(R.string.initial_indexing_setup_error)
            }
        }
    }

    private fun observeIndexingCompletion() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FileIndexingProgressStore.state.collectLatest { progress ->
                    if (
                        progress is FileIndexingProgress.Completed ||
                        progress is FileIndexingProgress.Cancelled
                    ) {
                        reconcileInitialIndexingAfterScan()
                    }
                }
            }
        }
    }

    private fun reconcileInitialIndexingAfterScan() {
        if (
            !isLauncherIntent() ||
            !InitialIndexingCoordinator.isPending() ||
            !FileIndexingStorageAccess.isGranted(this) ||
            initialIndexingReconciliationJob?.isActive == true
        ) {
            return
        }
        val precedingJob = initialIndexingJob?.takeIf(Job::isActive)
        initialIndexingReconciliationJob = lifecycleScope.launch {
            // A very small or empty scan can finish before the job that scheduled it returns.
            // Wait for that durable transition instead of dropping this one-shot terminal event.
            precedingJob?.join()
            if (
                !isInitialRootPhaseSettled ||
                !InitialIndexingCoordinator.isPending()
            ) {
                return@launch
            }
            try {
                when (InitialIndexingCoordinator.prepareRoot()) {
                    InitialIndexingAction.AwaitStorageAccess -> {
                        isInitialRootPhaseSettled = false
                        waitForInitialStorageAccess()
                    }
                    InitialIndexingAction.OfferRoot -> {
                        isInitialRootPhaseSettled = false
                        isRootOfferPending = true
                        showRootIndexingOffer()
                    }
                    InitialIndexingAction.Idle -> {
                        InitialIndexingCoordinator.prepareOrdinary()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.printStackTrace()
                showToast(R.string.initial_indexing_setup_error)
            }
        }
    }

    private fun settleInitialRootPhase() {
        // This method is called from the currently tracked root job. Clear the reference before
        // continuing so an already-granted ordinary-storage permission can start its scan now.
        initialIndexingJob = null
        isInitialRootPhaseSettled = true
        isRootOfferPending = false
        // Queue the required scan before an optional notification permission activity can pause
        // this activity. POST_NOTIFICATIONS controls visibility only and must never gate indexing.
        maybeStartInitialOrdinaryIndexing()
        fragment.continueInitialPermissionOrchestration()
    }

    private fun waitForInitialStorageAccess() {
        initialIndexingJob = null
        isInitialRootPhaseSettled = false
        isRootOfferPending = false
        fragment.continueInitialPermissionOrchestration()
    }

    private fun isInitialStorageReadyForRoot(): Boolean =
        FileIndexingStorageAccess.isGranted(this) &&
            !InitialIndexingCoordinator.shouldShowAllFilesAccessInformation()

    private fun showRootIndexingOffer() {
        if (
            supportFragmentManager.findFragmentByTag(
                RootIndexingOfferDialogFragment.TAG
            ) != null
        ) {
            return
        }
        if (
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            supportFragmentManager.isStateSaved
        ) {
            return
        }
        RootIndexingOfferDialogFragment().show(
            supportFragmentManager,
            RootIndexingOfferDialogFragment.TAG
        )
    }

    private fun isLauncherIntent(): Boolean {
        if (intent.action != Intent.ACTION_MAIN) {
            return false
        }
        val categories = intent.categories.orEmpty()
        return Intent.CATEGORY_LAUNCHER in categories ||
            Intent.CATEGORY_LEANBACK_LAUNCHER in categories
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (fragment.onKeyShortcut(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        fun createViewIntent(path: Path): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_VIEW)
                .apply { extraPath = path }
    }

    class OpenFileContract : ActivityResultContract<List<MimeType>, Path?>() {
        override fun createIntent(context: Context, input: List<MimeType>): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, input.map { it.value }.toTypedArray())

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class CreateFileContract : ActivityResultContract<Triple<MimeType, String?, Path?>, Path?>() {
        override fun createIntent(
            context: Context,
            input: Triple<MimeType, String?, Path?>
        ): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.first.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply {
                    input.second?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    input.third?.let { extraPath = it }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class OpenDirectoryContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
