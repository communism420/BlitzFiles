/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.model

enum class IndexingMode {
    FULL,
    INCREMENTAL
}

data class IndexingRequest(
    val rootIds: Set<Long>,
    val mode: IndexingMode,
    /**
     * Optional changed paths grouped by root. An incremental request without hints performs a
     * complete reconciliation while retaining incremental database writes.
     */
    val pathHints: Map<Long, Set<String>> = emptyMap(),
    /**
     * Allows deletion reconciliation to clear an indexed root that was physically removed.
     *
     * Ordinary scans keep this false so a temporarily unmounted volume remains an error and its
     * existing index is preserved.
     */
    val treatMissingRootsAsDeleted: Boolean = false
) {
    init {
        require(rootIds.isNotEmpty()) { "At least one root is required" }
        require(rootIds.all { it > 0 }) { "Root IDs must be positive" }
        require(pathHints.keys.all { it in rootIds }) { "Path hints must belong to requested roots" }
        require(pathHints.values.flatten().all { it.isNotBlank() && '\u0000' !in it }) {
            "Path hints must be non-blank and must not contain NUL"
        }
        require(mode == IndexingMode.INCREMENTAL || pathHints.isEmpty()) {
            "Path hints are only supported for incremental indexing"
        }
        require(!treatMissingRootsAsDeleted || mode == IndexingMode.INCREMENTAL) {
            "Missing roots can be treated as deleted only during incremental indexing"
        }
    }
}

data class IndexingResult(
    val scannedEntryCount: Long,
    val indexedEntryCount: Long,
    val removedEntryCount: Long,
    val skippedEntryCount: Long,
    val recoverableErrorCount: Long,
    val durationMillis: Long
)

sealed class IndexingState {
    data object Idle : IndexingState()

    data class Running(
        val rootId: Long,
        val currentPath: String?,
        val scannedEntryCount: Long,
        val indexedEntryCount: Long
    ) : IndexingState()

    data class Paused(
        val rootId: Long?,
        val scannedEntryCount: Long,
        val indexedEntryCount: Long
    ) : IndexingState()

    data class Completed(val result: IndexingResult) : IndexingState()

    data class Cancelled(
        val scannedEntryCount: Long,
        val indexedEntryCount: Long
    ) : IndexingState()

    data class Failed(val error: Throwable) : IndexingState()
}
