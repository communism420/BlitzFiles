/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.blitzfiles.search.data.repository.SQLiteIndexRepository
import com.blitzfiles.search.domain.indexer.IndexSafetyPolicy
import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexExclusion
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.IndexStatistics
import java8.nio.file.Paths
import com.blitzfiles.app.filejob.isSamePathOrDescendantOf

/**
 * Application-facing API for persistent indexing settings and service commands.
 *
 * UI code uses this API without depending directly on the SQLite implementation.
 */
object FileIndexingController {
    data class Snapshot(
        val roots: List<IndexRoot>,
        val exclusions: List<IndexExclusion>,
        val statistics: IndexStatistics
    )

    suspend fun getSnapshot(context: Context): Snapshot =
        withRepository(context, ::getSnapshot)

    /**
     * Reads repeated snapshots without reopening the large index database for every refresh.
     *
     * The caller owns [repository] and must close it when snapshot observation stops permanently.
     */
    internal suspend fun getSnapshot(repository: SQLiteIndexRepository): Snapshot {
        val roots = repository.getRoots()
        val hasInterruptedScan = roots.any {
            it.lastScanStatus == IndexScanStatus.RUNNING ||
                it.lastScanStatus == IndexScanStatus.PAUSED
        }
        val currentRoots = if (!FileIndexingService.isServiceRunning && hasInterruptedScan) {
            repository.recoverInterruptedScans(
                completedAtMillis = System.currentTimeMillis(),
                errorMessage = "Indexing was interrupted before completion"
            )
            repository.getRoots()
        } else {
            roots
        }
        return Snapshot(
            roots = currentRoots,
            exclusions = repository.getExclusions(),
            statistics = repository.getStatistics()
        )
    }

    suspend fun getRoots(context: Context): List<IndexRoot> =
        withRepository(context) { it.getRoots() }

    suspend fun saveRoot(
        context: Context,
        path: String,
        displayName: String,
        accessMode: IndexAccessMode,
        isEnabled: Boolean = true,
        includeHidden: Boolean = true,
        followSymbolicLinks: Boolean = false
    ): Long {
        val normalizedPath = normalizeConfiguredPath(path)
        val effectiveAccessMode = IndexRootAccessPolicy.resolve(normalizedPath, accessMode)
        var accessModeChanged = false
        val rootId = withRepository(context) { repository ->
            val roots = repository.getRoots()
            val existing = roots.firstOrNull { it.path == normalizedPath }
            IndexRootAccessPolicy.requireExclusiveMode(
                existingRoots = roots,
                normalizedPath = normalizedPath,
                requestedMode = effectiveAccessMode
            )
            if (existing == null) {
                require(!IndexSafetyPolicy.isProtected(normalizedPath)) {
                    "Protected system paths cannot be indexed directly: $normalizedPath"
                }
            }
            accessModeChanged = existing != null && existing.accessMode != effectiveAccessMode
            check(
                !accessModeChanged ||
                    existing?.lastScanStatus != IndexScanStatus.RUNNING &&
                    existing?.lastScanStatus != IndexScanStatus.PAUSED
            ) {
                "Access mode cannot be changed while this location is being scanned"
            }
            repository.upsertRoot(
                existing?.copy(
                    displayName = displayName,
                    accessMode = effectiveAccessMode,
                    isEnabled = isEnabled,
                    includeHidden = includeHidden,
                    followSymbolicLinks = followSymbolicLinks,
                    lastScanCompletedAtMillis =
                        if (accessModeChanged) null else existing.lastScanCompletedAtMillis,
                    lastScanStartedAtMillis =
                        if (accessModeChanged) null else existing.lastScanStartedAtMillis,
                    lastScanStatus =
                        if (accessModeChanged) {
                            IndexScanStatus.NEVER_RUN
                        } else {
                            existing.lastScanStatus
                        },
                    lastScanError = if (accessModeChanged) null else existing.lastScanError,
                    scanGeneration = if (accessModeChanged) 0 else existing.scanGeneration
                ) ?: IndexRoot(
                    path = normalizedPath,
                    displayName = displayName,
                    accessMode = effectiveAccessMode,
                    isEnabled = isEnabled,
                    includeHidden = includeHidden,
                    followSymbolicLinks = followSymbolicLinks,
                    createdAtMillis = System.currentTimeMillis()
                )
            )
        }
        if (accessModeChanged && isEnabled) {
            startFull(context.applicationContext, setOf(rootId))
        }
        return rootId
    }

    suspend fun removeRoot(context: Context, rootId: Long): Boolean =
        withRepository(context) { it.deleteRoot(rootId) }

    suspend fun getExclusions(context: Context): List<IndexExclusion> =
        withRepository(context) { it.getExclusions() }

    suspend fun getStatistics(context: Context): IndexStatistics =
        withRepository(context) { it.getStatistics() }

    suspend fun saveExclusion(
        context: Context,
        pathPrefix: String,
        rootId: Long? = null,
        isEnabled: Boolean = true
    ): Long = withRepository(context) { repository ->
        repository.upsertExclusion(
            IndexExclusion(
                rootId = rootId,
                pathPrefix = normalizeConfiguredPath(pathPrefix),
                isEnabled = isEnabled
            )
        )
    }

    suspend fun removeExclusion(context: Context, exclusionId: Long): Boolean =
        withRepository(context) { it.deleteExclusion(exclusionId) }

    /**
     * Removes physically deleted paths from SQLite/FTS and returns targeted scan hints.
     *
     * The direct delete makes subsequent searches correct immediately. The incremental command is
     * still required because an already-running full scan may have read metadata before the file
     * disappeared and can otherwise upsert that stale row after this transaction.
     */
    internal suspend fun reconcileDeletedPaths(
        context: Context,
        pathPrefixes: Collection<String>
    ): DeletedIndexReconciliation {
        val normalizedPrefixes = pathPrefixes
            .mapTo(LinkedHashSet(pathPrefixes.size)) { path ->
                normalizeConfiguredPath(path)
            }
        if (normalizedPrefixes.isEmpty()) {
            return DeletedIndexReconciliation(0, emptyMap())
        }
        return withRepository(context) { repository ->
            val roots = repository.getRoots()
            val removedEntryCount = repository.deleteEntriesAtOrUnder(normalizedPrefixes)
            DeletedIndexReconciliation(
                removedEntryCount = removedEntryCount,
                incrementalPathHints = createDeletedPathHints(roots, normalizedPrefixes)
            )
        }
    }

    fun startFull(context: Context, rootIds: Set<Long>? = null): Boolean =
        start(context, FileIndexingService.createFullIntent(context, rootIds))

    fun startIncremental(
        context: Context,
        rootIds: Set<Long>,
        changedPaths: Map<Long, Set<String>> = emptyMap()
    ): Boolean =
        startIncrementalInternal(context, rootIds, changedPaths, null)

    internal fun startIncremental(
        context: Context,
        rootIds: Set<Long>,
        changedPaths: Map<Long, Set<String>>,
        deletionProtectionToken: Long
    ): Boolean {
        require(deletionProtectionToken > 0) { "Deletion protection token must be positive" }
        return startIncrementalInternal(
            context,
            rootIds,
            changedPaths,
            deletionProtectionToken
        )
    }

    private fun startIncrementalInternal(
        context: Context,
        rootIds: Set<Long>,
        changedPaths: Map<Long, Set<String>>,
        deletionProtectionToken: Long?
    ): Boolean {
        require(rootIds.isNotEmpty()) { "At least one root is required" }
        require(changedPaths.keys.all { it in rootIds }) {
            "Changed paths must belong to requested roots"
        }
        require(changedPaths.values.sumOf { it.size } <= MAX_PATH_HINTS_PER_REQUEST) {
            "Too many changed paths in one indexing request"
        }
        return start(
            context,
            FileIndexingService.createIncrementalIntent(
                context,
                rootIds,
                changedPaths,
                deletionProtectionToken
            )
        )
    }

    fun pause(context: Context) {
        context.startService(FileIndexingService.createControlIntent(context, ACTION_PAUSE))
    }

    fun resume(context: Context): Boolean {
        if (!FileIndexingStorageAccess.isGranted(context)) {
            return false
        }
        context.startService(FileIndexingService.createControlIntent(context, ACTION_RESUME))
        return true
    }

    fun cancel(context: Context) {
        context.startService(FileIndexingService.createControlIntent(context, ACTION_CANCEL))
    }

    private fun start(context: Context, intent: Intent): Boolean {
        if (!FileIndexingStorageAccess.isGranted(context)) {
            return false
        }
        FileIndexingProgressStore.markScheduled()
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: RuntimeException) {
            FileIndexingProgressStore.markLaunchFailed(error)
            throw error
        }
        return true
    }

    private suspend fun <T> withRepository(
        context: Context,
        block: suspend (SQLiteIndexRepository) -> T
    ): T {
        val repository = SQLiteIndexRepository.create(context)
        return try {
            block(repository)
        } finally {
            repository.close()
        }
    }

    internal fun encodePathHints(pathHints: Map<Long, Set<String>>): Bundle =
        Bundle().apply {
            pathHints.forEach { (rootId, paths) ->
                putStringArrayList(rootId.toString(), ArrayList(paths))
            }
        }

    internal fun decodePathHints(bundle: Bundle?): Map<Long, Set<String>> {
        if (bundle == null) {
            return emptyMap()
        }
        return bundle.keySet().associate { key ->
            val rootId = requireNotNull(key.toLongOrNull()) { "Invalid root ID in path hints" }
            @Suppress("DEPRECATION")
            val paths = bundle.getStringArrayList(key).orEmpty().toSet()
            rootId to paths
        }
    }

    internal fun chunkPathHints(
        pathHints: Map<Long, Set<String>>
    ): List<Map<Long, Set<String>>> {
        if (pathHints.isEmpty()) {
            return emptyList()
        }
        val batches = mutableListOf<Map<Long, Set<String>>>()
        var currentBatch = linkedMapOf<Long, MutableSet<String>>()
        var currentSize = 0
        pathHints.forEach { (rootId, paths) ->
            paths.forEach { path ->
                if (currentSize == MAX_PATH_HINTS_PER_REQUEST) {
                    batches += currentBatch.mapValues { it.value.toSet() }
                    currentBatch = linkedMapOf()
                    currentSize = 0
                }
                currentBatch.getOrPut(rootId) { linkedSetOf() } += path
                ++currentSize
            }
        }
        if (currentBatch.isNotEmpty()) {
            batches += currentBatch.mapValues { it.value.toSet() }
        }
        return batches
    }

    internal const val ACTION_PAUSE = "com.blitzfiles.app.indexing.PAUSE"
    internal const val ACTION_RESUME = "com.blitzfiles.app.indexing.RESUME"
    internal const val ACTION_CANCEL = "com.blitzfiles.app.indexing.CANCEL"

    private const val MAX_PATH_HINTS_PER_REQUEST = 256
}

internal data class DeletedIndexReconciliation(
    val removedEntryCount: Long,
    val incrementalPathHints: Map<Long, Set<String>>
)

internal fun createDeletedPathHints(
    roots: List<IndexRoot>,
    deletedPathPrefixes: Collection<String>
): Map<Long, Set<String>> {
    val eligibleRoots = roots.filter { root -> root.isEnabled && root.id != null }
    val hints = linkedMapOf<Long, MutableSet<String>>()
    deletedPathPrefixes.forEach { deletedPath ->
        // A deleted ancestor invalidates every configured root nested below it, even when a wider
        // root such as "/" also owns the ancestor path.
        eligibleRoots
            .filter { root -> root.path.isSamePathOrDescendantOf(deletedPath) }
            .forEach { root ->
                hints.getOrPut(checkNotNull(root.id)) { linkedSetOf() } += root.path
            }
        val owningRoot = eligibleRoots
            .filter { root -> deletedPath.isSamePathOrDescendantOf(root.path) }
            .maxByOrNull { root -> root.path.length }
        if (owningRoot != null) {
            hints.getOrPut(checkNotNull(owningRoot.id)) { linkedSetOf() } += deletedPath
        }
    }
    return hints
}

private fun normalizeConfiguredPath(path: String): String =
    Paths.get(path).toAbsolutePath().normalize().toString()
