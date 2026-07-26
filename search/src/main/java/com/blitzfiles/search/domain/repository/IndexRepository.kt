/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.repository

import com.blitzfiles.search.domain.model.IndexExclusion
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.IndexStatistics
import com.blitzfiles.search.domain.model.IndexedFileRecord

/**
 * Persistence boundary used by indexers and search implementations.
 *
 * Large scans should call [upsertEntries] in bounded batches instead of retaining a complete scan
 * in memory.
 */
interface IndexRepository {
    suspend fun upsertRoot(root: IndexRoot): Long

    suspend fun getRoots(): List<IndexRoot>

    suspend fun deleteRoot(rootId: Long): Boolean

    suspend fun upsertExclusion(exclusion: IndexExclusion): Long

    suspend fun getExclusions(): List<IndexExclusion>

    suspend fun deleteExclusion(exclusionId: Long): Boolean

    suspend fun upsertEntries(entries: Collection<IndexedFileRecord>)

    suspend fun deleteEntry(path: String): Boolean

    suspend fun beginScan(rootId: Long, startedAtMillis: Long): Long

    suspend fun updateScanStatus(
        rootId: Long,
        scanGeneration: Long,
        status: IndexScanStatus,
        completedAtMillis: Long? = null,
        errorMessage: String? = null
    )

    suspend fun recoverInterruptedScans(
        completedAtMillis: Long,
        errorMessage: String
    ): Long

    suspend fun deleteStaleEntries(rootId: Long, activeScanGeneration: Long): Long

    suspend fun deleteStaleEntriesUnder(
        rootId: Long,
        activeScanGeneration: Long,
        pathPrefix: String
    ): Long

    suspend fun deleteEntriesUnder(rootId: Long, pathPrefix: String): Long

    suspend fun deleteEntriesUnder(rootId: Long, pathPrefixes: Collection<String>): Long

    /**
     * Deletes each path and its descendants regardless of the root that currently owns the row.
     *
     * File operations know the physical path but should not have to reproduce index root ownership
     * rules. Implementations must treat path separators as boundaries (`/a` must not match `/ab`).
     */
    suspend fun deleteEntriesAtOrUnder(pathPrefixes: Collection<String>): Long

    suspend fun clearRoot(rootId: Long): Long

    suspend fun getStatistics(): IndexStatistics

    suspend fun close()
}
