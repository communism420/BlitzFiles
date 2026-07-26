/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filejob

import android.content.Context
import com.blitzfiles.app.indexing.FileIndexingController
import com.blitzfiles.app.indexing.FileIndexingStorageAccess
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reconciles deletion fences restored after Android recreated the app process.
 *
 * The direct database delete runs first and makes SQLite/FTS correct even when starting the
 * foreground service is temporarily disallowed. Tombstones remain persisted until every targeted
 * scan starts and completes successfully.
 */
internal object FileDeletionRecovery {
    private val isScheduled = AtomicBoolean()
    private val retryRequested = AtomicBoolean()
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        FileDeletionStore.initialize(applicationContext)
        retry(applicationContext)
    }

    fun retry(context: Context) {
        val applicationContext = context.applicationContext
        if (FileDeletionStore.pendingRecords().isEmpty()) {
            return
        }
        if (!isScheduled.compareAndSet(false, true)) {
            retryRequested.set(true)
            return
        }
        recoveryScope.launch {
            var shouldRetry = false
            try {
                shouldRetry = reconcile(applicationContext)
            } finally {
                isScheduled.set(false)
                val wasRetryRequested = retryRequested.getAndSet(false)
                if (
                    wasRetryRequested &&
                    (shouldRetry || FileIndexingStorageAccess.isGranted(applicationContext))
                ) {
                    retry(applicationContext)
                }
            }
        }
    }

    /**
     * @return true when foreground state may make a later launch attempt succeed.
     */
    private suspend fun reconcile(context: Context): Boolean {
        val pendingRecords = FileDeletionStore.pendingRecords()
        if (pendingRecords.isEmpty()) {
            return false
        }
        val recordIds = pendingRecords.keys
        val pathPrefixes = compactPathPrefixes(
            pendingRecords.values.flatMap { paths -> paths.indexPathPrefixes }
        )
        if (pathPrefixes.isEmpty()) {
            FileDeletionStore.releaseRecordsWithoutVerification(recordIds)
            return false
        }
        return try {
            val reconciliation = FileIndexingController.reconcileDeletedPaths(
                context,
                pathPrefixes
            )
            FileDeletionStore.recordIndexReconciliationFinished()
            if (!FileIndexingStorageAccess.isGranted(context)) {
                // Direct SQLite/FTS cleanup is safe without filesystem access. Keep the durable
                // fence retryable and postpone the targeted filesystem scan until permission is
                // granted.
                return false
            }
            val protectedBatches = FileIndexingController
                .chunkPathHints(reconciliation.incrementalPathHints)
                .mapNotNull { pathHints ->
                    FileDeletionStore.createIndexProtection(recordIds)?.let { token ->
                        RecoveryPathHints(pathHints, token)
                    }
                }
            if (protectedBatches.isEmpty()) {
                FileDeletionStore.releaseRecordsWithoutVerification(recordIds)
            }
            var launchFailed = false
            protectedBatches.forEach { protectedBatch ->
                try {
                    val started = FileIndexingController.startIncremental(
                        context = context,
                        rootIds = protectedBatch.pathHints.keys,
                        changedPaths = protectedBatch.pathHints,
                        deletionProtectionToken = protectedBatch.protectionToken
                    )
                    if (!started) {
                        FileDeletionStore.completeIndexProtection(
                            protectedBatch.protectionToken,
                            succeeded = false
                        )
                        launchFailed = true
                    }
                } catch (error: RuntimeException) {
                    FileDeletionStore.completeIndexProtection(
                        protectedBatch.protectionToken,
                        succeeded = false
                    )
                    launchFailed = true
                    error.printStackTrace()
                }
            }
            launchFailed
        } catch (error: Exception) {
            // Keep the persisted fence. A later process start retries the database reconciliation.
            error.printStackTrace()
            FileDeletionStore.recordIndexReconciliationFinished()
            true
        }
    }
}

private data class RecoveryPathHints(
    val pathHints: Map<Long, Set<String>>,
    val protectionToken: Long
)
