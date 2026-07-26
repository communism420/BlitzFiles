/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filejob

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.blitzfiles.app.provider.linux.isLinuxPath
import java.io.IOException
import java.util.TreeSet
import java8.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide tombstones for physical deletions performed by [FileJobService].
 *
 * SQLite readers and filesystem searches can already be in flight when a file disappears.
 * Tombstones prevent those stale results from resurrecting a deleted row until the targeted
 * incremental scan has verified the affected path. Pending tombstones are persisted so a process
 * restart cannot expose a stale row that an older full scan wrote after the physical deletion.
 */
internal object FileDeletionStore {
    private val lock = Any()
    private val mutableState = MutableStateFlow(FileDeletionState())
    private val records = LinkedHashMap<Long, DeletedPathPrefixes>()
    private val indexProtections = LinkedHashMap<Long, Set<Long>>()
    private val failedRecordIds = LinkedHashSet<Long>()
    private val locallyOwnedRecordIds = LinkedHashSet<Long>()
    private val pendingReleaseCounts = LinkedHashMap<Long, Int>()
    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }
    private var nextRecordId = 1L
    private var nextProtectionToken = 1L
    private var preferences: SharedPreferences? = null

    val state: StateFlow<FileDeletionState> = mutableState.asStateFlow()

    /**
     * Restores deletion fences before any search ViewModel can read the persistent index.
     *
     * A restored aggregate is represented by one record. The exact pre-restart protection tokens
     * are intentionally irrelevant: [FileDeletionRecovery] creates fresh targeted commands for
     * every restored path.
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (preferences != null) {
                return
            }
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
            this.preferences = preferences
            val restoredPaths = DeletedPathPrefixes(
                uriPrefixes = compactPathPrefixes(
                    preferences.getStringSet(KEY_URI_PREFIXES, emptySet()).orEmpty()
                ),
                indexPathPrefixes = compactPathPrefixes(
                    preferences.getStringSet(KEY_INDEX_PATH_PREFIXES, emptySet()).orEmpty()
                )
            )
            if (!restoredPaths.isEmpty()) {
                val recordId = nextRecordId++
                records[recordId] = restoredPaths
                val current = mutableState.value
                mutableState.value = current.copy(
                    fileSystemRevision = current.fileSystemRevision + 1,
                    deletedPaths = rebuildDeletedPathsLocked()
                )
            }
        }
    }

    /** Returns records that are neither owned by a file job nor already being verified. */
    fun pendingRecords(): Map<Long, DeletedPathPrefixes> = synchronized(lock) {
        val activelyProtectedRecordIds = indexProtections.values.flatten().toHashSet()
        records.filterKeys { recordId ->
            recordId !in locallyOwnedRecordIds &&
                recordId !in activelyProtectedRecordIds &&
                recordId !in pendingReleaseCounts
        }
    }

    /**
     * Durably fences selected paths before their first physical mutation.
     *
     * Persisting first closes the process-death window between deleting a file and updating
     * SQLite. If the durable write fails, deletion must not begin. A startup targeted scan safely
     * resolves a prepared fence regardless of whether Android killed the process before or after
     * the filesystem mutation.
     */
    @Throws(IOException::class)
    fun prepareDeletion(paths: Collection<Path>): RecordedDeletion? {
        val deletedPaths = DeletedPathPrefixes.from(paths)
        if (deletedPaths.isEmpty()) {
            return null
        }
        return synchronized(lock) {
            val record = RecordedDeletion(nextRecordId++, deletedPaths)
            records[record.id] = record.paths
            locallyOwnedRecordIds += record.id
            if (!persistDeletedPathsLocked(synchronous = true)) {
                records.remove(record.id)
                locallyOwnedRecordIds.remove(record.id)
                throw IOException("Unable to persist deletion protection")
            }
            val current = mutableState.value
            mutableState.value = current.copy(
                fileSystemRevision = current.fileSystemRevision + 1,
                deletedPaths = rebuildDeletedPathsLocked()
            )
            record
        }
    }

    /**
     * Narrows a prepared fence to paths whose physical deletion actually succeeded.
     *
     * This is especially important after a partially successful directory deletion: surviving
     * children become visible again immediately, while deleted children remain protected.
     */
    fun confirmDeletion(recordId: Long, deletedPaths: DeletedPathPrefixes): Boolean {
        require(!deletedPaths.isEmpty()) { "Confirmed deletion paths must not be empty" }
        return synchronized(lock) {
            val preparedPaths = records[recordId] ?: return@synchronized false
            records[recordId] = deletedPaths
            if (!persistDeletedPathsLocked(synchronous = true)) {
                records[recordId] = preparedPaths
                return@synchronized false
            }
            failedRecordIds.remove(recordId)
            val current = mutableState.value
            mutableState.value = current.copy(
                fileSystemRevision = current.fileSystemRevision + 1,
                deletedPaths = rebuildDeletedPathsLocked()
            )
            true
        }
    }

    /**
     * Keeps [recordIds] hidden until one targeted indexing command reaches a successful terminal
     * state. Multiple commands may protect the same records when path hints require batching.
     */
    fun createIndexProtection(recordIds: Set<Long>): Long? = synchronized(lock) {
        val existingIds = recordIds.filterTo(linkedSetOf()) { it in records }
        if (existingIds.isEmpty()) {
            return@synchronized null
        }
        // A new reconciliation attempt supersedes failures from an older attempt. All tokens for
        // one attempt are created before any indexing command is started.
        failedRecordIds.removeAll(existingIds)
        locallyOwnedRecordIds.removeAll(existingIds)
        nextProtectionToken++.also { token ->
            indexProtections[token] = existingIds
        }
    }

    /**
     * Releases records verified by a successful targeted scan.
     *
     * Failed or cancelled scans deliberately retain their protection. The direct SQLite delete
     * remains persistent, while the tombstone guards against an older full scan re-inserting a row
     * that it read before physical deletion. Startup recovery retries retained protections.
     */
    fun completeIndexProtection(token: Long, succeeded: Boolean) {
        val recordIds = synchronized(lock) {
            val recordIds = indexProtections[token] ?: return
            indexProtections.remove(token)
            if (!succeeded) {
                failedRecordIds += recordIds
                return
            }
            recordIds
        }
        // Keep the committed tombstone visible for at least one UI collection window. StateFlow
        // may otherwise conflate a very fast add/commit/remove sequence into only the final state.
        releaseCommittedRecords(recordIds)
    }

    /** Releases records that do not need a filesystem-backed index verification. */
    fun releaseCommittedRecords(recordIds: Set<Long>) {
        if (recordIds.isEmpty()) {
            return
        }
        val existingRecordIds = synchronized(lock) {
            recordIds.filterTo(linkedSetOf()) { recordId ->
                if (recordId !in records) {
                    false
                } else {
                    pendingReleaseCounts[recordId] =
                        (pendingReleaseCounts[recordId] ?: 0) + 1
                    true
                }
            }
        }
        if (existingRecordIds.isEmpty()) {
            return
        }
        mainHandler.postDelayed(
            {
                synchronized(lock) {
                    existingRecordIds.forEach { recordId ->
                        val remainingCount = (pendingReleaseCounts[recordId] ?: 1) - 1
                        if (remainingCount > 0) {
                            pendingReleaseCounts[recordId] = remainingCount
                        } else {
                            pendingReleaseCounts.remove(recordId)
                        }
                    }
                    releaseUnprotectedRecordsLocked(existingRecordIds)
                }
            },
            COMMITTED_RELEASE_DELAY_MILLIS
        )
    }

    /**
     * Releases records after direct cleanup determined that no targeted filesystem scan is needed.
     */
    fun releaseRecordsWithoutVerification(recordIds: Set<Long>) {
        if (recordIds.isEmpty()) {
            return
        }
        synchronized(lock) {
            failedRecordIds.removeAll(recordIds)
            locallyOwnedRecordIds.removeAll(recordIds)
        }
        releaseCommittedRecords(recordIds)
    }

    /** Allows startup/foreground recovery to retry a failed local reconciliation. */
    fun markRecordsRetryable(recordIds: Set<Long>) {
        synchronized(lock) {
            locallyOwnedRecordIds.removeAll(recordIds)
        }
    }

    /** Signals that the active search must silently re-read SQLite after reconciliation. */
    fun recordIndexReconciliationFinished() {
        synchronized(lock) {
            val current = mutableState.value
            mutableState.value = current.copy(indexRevision = current.indexRevision + 1)
        }
    }

    private fun releaseUnprotectedRecordsLocked(recordIds: Set<Long>) {
        val protectedRecordIds = HashSet<Long>()
        indexProtections.values.forEach(protectedRecordIds::addAll)
        protectedRecordIds += failedRecordIds
        var changed = false
        recordIds.forEach { recordId ->
            if (recordId !in protectedRecordIds) {
                if (records.remove(recordId) != null) {
                    failedRecordIds.remove(recordId)
                    locallyOwnedRecordIds.remove(recordId)
                    pendingReleaseCounts.remove(recordId)
                    changed = true
                }
            }
        }
        if (changed) {
            publishRebuiltDeletedPathsLocked()
        }
    }

    private fun publishRebuiltDeletedPathsLocked() {
        val current = mutableState.value
        mutableState.value = current.copy(
            fileSystemRevision = current.fileSystemRevision + 1,
            deletedPaths = rebuildDeletedPathsLocked()
        )
        persistDeletedPathsLocked(synchronous = false)
    }

    private fun rebuildDeletedPathsLocked(): DeletedPathPrefixes =
        records.values.fold(DeletedPathPrefixes.EMPTY) { current, paths ->
            current.mergedWith(paths)
        }

    @SuppressLint("ApplySharedPref")
    private fun persistDeletedPathsLocked(synchronous: Boolean): Boolean {
        val preferences = preferences ?: return false
        val deletedPaths = rebuildDeletedPathsLocked()
        val editor = preferences.edit()
            .putStringSet(KEY_URI_PREFIXES, deletedPaths.uriPrefixes.toSet())
            .putStringSet(KEY_INDEX_PATH_PREFIXES, deletedPaths.indexPathPrefixes.toSet())
        return if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    private const val COMMITTED_RELEASE_DELAY_MILLIS = 1_500L
    private const val PREFERENCES_NAME = "file_deletion_store"
    private const val KEY_URI_PREFIXES = "uri_prefixes"
    private const val KEY_INDEX_PATH_PREFIXES = "index_path_prefixes"
}

internal data class RecordedDeletion(
    val id: Long,
    val paths: DeletedPathPrefixes
)

internal data class FileDeletionState(
    val fileSystemRevision: Long = 0,
    val deletedPaths: DeletedPathPrefixes = DeletedPathPrefixes.EMPTY,
    val indexRevision: Long = 0
)

internal data class DeletedPathPrefixes(
    val uriPrefixes: Set<String>,
    val indexPathPrefixes: Set<String>
) {
    fun isEmpty(): Boolean = uriPrefixes.isEmpty() && indexPathPrefixes.isEmpty()

    fun containsUri(candidate: String): Boolean =
        uriPrefixes.any { prefix -> candidate.isSamePathOrDescendantOf(prefix) }

    fun containsIndexPath(candidate: String): Boolean =
        indexPathPrefixes.any { prefix -> candidate.isSamePathOrDescendantOf(prefix) }

    fun mergedWith(other: DeletedPathPrefixes): DeletedPathPrefixes =
        DeletedPathPrefixes(
            uriPrefixes = mergePathPrefixes(uriPrefixes, other.uriPrefixes),
            indexPathPrefixes = mergePathPrefixes(indexPathPrefixes, other.indexPathPrefixes)
        )

    companion object {
        val EMPTY = DeletedPathPrefixes(emptySet(), emptySet())

        fun from(paths: Collection<Path>): DeletedPathPrefixes {
            if (paths.isEmpty()) {
                return EMPTY
            }
            val uriPrefixes = LinkedHashSet<String>(paths.size)
            val indexPathPrefixes = LinkedHashSet<String>(paths.size)
            paths.forEach { path ->
                val normalizedPath = path.toAbsolutePath().normalize()
                uriPrefixes += normalizedPath.toDeletionUriKey()
                if (normalizedPath.isLinuxPath) {
                    indexPathPrefixes += normalizedPath.toString()
                }
            }
            return DeletedPathPrefixes(
                uriPrefixes = mergePathPrefixes(emptySet(), uriPrefixes),
                indexPathPrefixes = mergePathPrefixes(emptySet(), indexPathPrefixes)
            )
        }
    }
}

/**
 * Thread-confined accumulator that keeps only the shallowest successfully deleted paths.
 *
 * Recursive deletion visits directories after their children, so adding the deleted directory
 * removes all previously collected descendants in logarithmic time plus the removed entries. A
 * hard prefix limit falls back to the durable prepared parent fence for exceptionally large flat
 * directories, keeping deletion memory bounded.
 */
internal class DeletedPathAccumulator {
    private val uriPrefixes = CompactPathPrefixSet()
    private val indexPathPrefixes = CompactPathPrefixSet()
    var isOverflowed: Boolean = false
        private set

    fun add(path: Path) {
        if (isOverflowed) {
            return
        }
        val normalizedPath = path.toAbsolutePath().normalize()
        uriPrefixes.add(normalizedPath.toDeletionUriKey())
        if (normalizedPath.isLinuxPath) {
            indexPathPrefixes.add(normalizedPath.toString())
        }
        if (
            uriPrefixes.size > MAX_TRACKED_PREFIXES_PER_KIND ||
            indexPathPrefixes.size > MAX_TRACKED_PREFIXES_PER_KIND
        ) {
            uriPrefixes.clear()
            indexPathPrefixes.clear()
            isOverflowed = true
        }
    }

    fun snapshot(): DeletedPathPrefixes = DeletedPathPrefixes(
        uriPrefixes = uriPrefixes.toSet(),
        indexPathPrefixes = indexPathPrefixes.toSet()
    )

    private companion object {
        const val MAX_TRACKED_PREFIXES_PER_KIND = 8_192
    }
}

internal fun Path.toDeletionUriKey(): String =
    toAbsolutePath().normalize().toUri().normalize().toString()

internal fun String.isSamePathOrDescendantOf(prefix: String): Boolean {
    if (this == prefix) {
        return true
    }
    if (!startsWith(prefix)) {
        return false
    }
    return prefix.endsWith('/') || getOrNull(prefix.length) == '/'
}

internal fun compactPathPrefixes(paths: Collection<String>): Set<String> =
    mergePathPrefixes(emptySet(), paths.toSet())

private fun mergePathPrefixes(
    current: Set<String>,
    additions: Set<String>
): Set<String> {
    if (additions.isEmpty()) {
        return current
    }
    return CompactPathPrefixSet().apply {
        current.forEach(::add)
        additions.forEach(::add)
    }.toSet()
}

private class CompactPathPrefixSet {
    private val paths = TreeSet<String>()
    val size: Int
        get() = paths.size

    fun add(candidate: String) {
        if (candidate in paths || hasAncestor(candidate)) {
            return
        }
        val descendantPrefix = if (candidate.endsWith('/')) candidate else "$candidate/"
        val descendants = paths.tailSet(descendantPrefix, true).iterator()
        while (descendants.hasNext()) {
            val existing = descendants.next()
            if (!existing.startsWith(descendantPrefix)) {
                break
            }
            descendants.remove()
        }
        paths += candidate
    }

    fun toSet(): Set<String> = LinkedHashSet(paths)

    fun clear() {
        paths.clear()
    }

    private fun hasAncestor(candidate: String): Boolean {
        var separatorIndex = candidate.length
        while (true) {
            separatorIndex = candidate.lastIndexOf('/', separatorIndex - 1)
            if (separatorIndex < 0) {
                return false
            }
            val withoutSeparator = if (separatorIndex == 0) {
                "/"
            } else {
                candidate.substring(0, separatorIndex)
            }
            if (withoutSeparator in paths) {
                return true
            }
            val withSeparator = candidate.substring(0, separatorIndex + 1)
            if (withSeparator in paths) {
                return true
            }
        }
    }
}
