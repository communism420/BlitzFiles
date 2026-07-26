/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.repository

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.blitzfiles.search.data.database.IndexDatabase
import com.blitzfiles.search.data.database.queryLong
import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexExclusion
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.IndexStatistics
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.repository.IndexRepository

class SQLiteIndexRepository internal constructor(
    private val database: IndexDatabase
) : IndexRepository {

    override suspend fun upsertRoot(root: IndexRoot): Long =
        database.transaction { connection ->
            val existingAccessMode = connection.prepare(
                "SELECT access_mode FROM index_roots WHERE path = ?"
            ).use { statement ->
                statement.bindText(1, root.path)
                if (statement.step()) statement.getInt(0) else null
            }
            val accessModeChanged =
                existingAccessMode != null &&
                    existingAccessMode != root.accessMode.databaseValue
            val persistedRoot = if (accessModeChanged) {
                root.copy(
                    lastScanCompletedAtMillis = null,
                    lastScanStartedAtMillis = null,
                    lastScanStatus = IndexScanStatus.NEVER_RUN,
                    lastScanError = null,
                    scanGeneration = 0
                )
            } else {
                root
            }
            connection.prepare(UPSERT_ROOT_SQL).use { statement ->
                statement.bindText(1, persistedRoot.path)
                statement.bindText(2, persistedRoot.displayName)
                statement.bindLong(3, persistedRoot.accessMode.databaseValue.toLong())
                statement.bindBoolean(4, persistedRoot.isEnabled)
                statement.bindBoolean(5, persistedRoot.includeHidden)
                statement.bindBoolean(6, persistedRoot.followSymbolicLinks)
                statement.bindLong(7, persistedRoot.createdAtMillis)
                statement.bindNullableLong(8, persistedRoot.lastScanCompletedAtMillis)
                statement.bindLong(9, persistedRoot.scanGeneration)
                statement.bindNullableLong(10, persistedRoot.lastScanStartedAtMillis)
                statement.bindLong(11, persistedRoot.lastScanStatus.databaseValue.toLong())
                statement.bindNullableText(12, persistedRoot.lastScanError)
                statement.step()
            }
            val rootId = connection.prepare(
                "SELECT id FROM index_roots WHERE path = ?"
            ).use { statement ->
                statement.bindText(1, persistedRoot.path)
                check(statement.step()) {
                    "Upserted root was not found: ${persistedRoot.path}"
                }
                statement.getLong(0)
            }
            if (accessModeChanged) {
                // Entries carry provider-dependent ownership metadata. Keeping them after an
                // access-mode change would expose a seemingly complete but internally mixed
                // index until the next full scan.
                connection.prepare(
                    "DELETE FROM indexed_files WHERE root_id = ?"
                ).use { statement ->
                    statement.bindLong(1, rootId)
                    statement.step()
                }
            }
            rootId
        }

    override suspend fun getRoots(): List<IndexRoot> =
        database.read { connection ->
            connection.prepare(GET_ROOTS_SQL).use { statement ->
                buildList {
                    while (statement.step()) {
                        add(
                            IndexRoot(
                                id = statement.getLong(0),
                                path = statement.getText(1),
                                displayName = statement.getText(2),
                                accessMode = indexAccessModeFromDatabaseValue(
                                    statement.getInt(3)
                                ),
                                isEnabled = statement.getBoolean(4),
                                includeHidden = statement.getBoolean(5),
                                followSymbolicLinks = statement.getBoolean(6),
                                createdAtMillis = statement.getLong(7),
                                lastScanCompletedAtMillis = statement.getNullableLong(8),
                                scanGeneration = statement.getLong(9),
                                lastScanStartedAtMillis = statement.getNullableLong(10),
                                lastScanStatus = indexScanStatusFromDatabaseValue(
                                    statement.getInt(11)
                                ),
                                lastScanError = statement.getNullableText(12)
                            )
                        )
                    }
                }
            }
        }

    override suspend fun deleteRoot(rootId: Long): Boolean {
        require(rootId > 0) { "Root ID must be positive" }
        return database.transaction { connection ->
            connection.prepare("DELETE FROM index_roots WHERE id = ?").use { statement ->
                statement.bindLong(1, rootId)
                statement.step()
            }
            connection.changedRowCount() > 0
        }
    }

    override suspend fun upsertExclusion(exclusion: IndexExclusion): Long =
        database.transaction { connection ->
            val existingId = connection.findExclusionId(exclusion.rootId, exclusion.pathPrefix)
            if (existingId != null) {
                connection.prepare(
                    "UPDATE index_exclusions SET is_enabled = ? WHERE id = ?"
                ).use { statement ->
                    statement.bindBoolean(1, exclusion.isEnabled)
                    statement.bindLong(2, existingId)
                    statement.step()
                }
                existingId
            } else {
                connection.prepare(
                    """
                    INSERT INTO index_exclusions(root_id, path_prefix, is_enabled)
                    VALUES (?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.bindNullableLong(1, exclusion.rootId)
                    statement.bindText(2, exclusion.pathPrefix)
                    statement.bindBoolean(3, exclusion.isEnabled)
                    statement.step()
                }
                connection.queryLong("SELECT last_insert_rowid()")
            }
        }

    override suspend fun getExclusions(): List<IndexExclusion> =
        database.read { connection ->
            connection.prepare(
                """
                SELECT id, root_id, path_prefix, is_enabled
                FROM index_exclusions
                ORDER BY root_id, path_prefix COLLATE BINARY
                """.trimIndent()
            ).use { statement ->
                buildList {
                    while (statement.step()) {
                        add(
                            IndexExclusion(
                                id = statement.getLong(0),
                                rootId = statement.getNullableLong(1),
                                pathPrefix = statement.getText(2),
                                isEnabled = statement.getBoolean(3)
                            )
                        )
                    }
                }
            }
        }

    override suspend fun deleteExclusion(exclusionId: Long): Boolean {
        require(exclusionId > 0) { "Exclusion ID must be positive" }
        return database.transaction { connection ->
            connection.prepare("DELETE FROM index_exclusions WHERE id = ?").use { statement ->
                statement.bindLong(1, exclusionId)
                statement.step()
            }
            connection.changedRowCount() > 0
        }
    }

    override suspend fun upsertEntries(entries: Collection<IndexedFileRecord>) {
        if (entries.isEmpty()) {
            return
        }
        database.transaction { connection ->
            connection.prepare(UPSERT_ENTRY_SQL).use { statement ->
                entries.forEach { entry ->
                    statement.bindEntry(entry)
                    statement.step()
                    statement.reset()
                    statement.clearBindings()
                }
            }
        }
    }

    override suspend fun deleteEntry(path: String): Boolean {
        require(path.isNotBlank()) { "Entry path must not be blank" }
        return database.transaction { connection ->
            connection.prepare("DELETE FROM indexed_files WHERE path = ?").use { statement ->
                statement.bindText(1, path)
                statement.step()
            }
            connection.changedRowCount() > 0
        }
    }

    override suspend fun beginScan(rootId: Long, startedAtMillis: Long): Long {
        require(rootId > 0) { "Root ID must be positive" }
        require(startedAtMillis >= 0) { "Scan start time must not be negative" }
        return database.transaction { connection ->
            connection.prepare(
                """
                UPDATE index_roots
                SET scan_generation = scan_generation + 1,
                    last_scan_started_at_ms = ?,
                    last_scan_status = ?,
                    last_scan_error = NULL
                WHERE id = ? AND is_enabled = 1
                """.trimIndent()
            ).use { statement ->
                statement.bindLong(1, startedAtMillis)
                statement.bindLong(2, IndexScanStatus.RUNNING.databaseValue.toLong())
                statement.bindLong(3, rootId)
                statement.step()
            }
            check(connection.changedRowCount() == 1L) {
                "Enabled index root was not found: $rootId"
            }
            connection.prepare(
                "SELECT scan_generation FROM index_roots WHERE id = ?"
            ).use { statement ->
                statement.bindLong(1, rootId)
                check(statement.step()) { "Index root was not found after scan start: $rootId" }
                statement.getLong(0)
            }
        }
    }

    override suspend fun updateScanStatus(
        rootId: Long,
        scanGeneration: Long,
        status: IndexScanStatus,
        completedAtMillis: Long?,
        errorMessage: String?
    ) {
        require(rootId > 0) { "Root ID must be positive" }
        require(scanGeneration > 0) { "Scan generation must be positive" }
        require(completedAtMillis == null || completedAtMillis >= 0) {
            "Scan completion time must not be negative"
        }
        require(errorMessage == null || '\u0000' !in errorMessage) {
            "Scan error must not contain NUL"
        }
        database.transaction { connection ->
            connection.prepare(
                """
                UPDATE index_roots
                SET last_scan_status = ?,
                    last_scan_completed_at_ms =
                        CASE WHEN ? IS NULL THEN last_scan_completed_at_ms ELSE ? END,
                    last_scan_error = ?
                WHERE id = ? AND scan_generation = ?
                    AND (
                        ? NOT IN (1, 2)
                        OR (? = 2 AND last_scan_status = 1)
                        OR (? = 1 AND last_scan_status = 2)
                    )
                """.trimIndent()
            ).use { statement ->
                statement.bindLong(1, status.databaseValue.toLong())
                statement.bindNullableLong(2, completedAtMillis)
                statement.bindNullableLong(3, completedAtMillis)
                statement.bindNullableText(4, errorMessage)
                statement.bindLong(5, rootId)
                statement.bindLong(6, scanGeneration)
                statement.bindLong(7, status.databaseValue.toLong())
                statement.bindLong(8, status.databaseValue.toLong())
                statement.bindLong(9, status.databaseValue.toLong())
                statement.step()
            }
        }
    }

    override suspend fun recoverInterruptedScans(
        completedAtMillis: Long,
        errorMessage: String
    ): Long {
        require(completedAtMillis >= 0) { "Scan completion time must not be negative" }
        require(errorMessage.isNotBlank() && '\u0000' !in errorMessage) {
            "Recovery error must be non-blank and must not contain NUL"
        }
        return database.transaction { connection ->
            connection.prepare(
                """
                UPDATE index_roots
                SET last_scan_status = ?,
                    last_scan_completed_at_ms = ?,
                    last_scan_error = ?
                WHERE last_scan_status IN (?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.bindLong(1, IndexScanStatus.FAILED.databaseValue.toLong())
                statement.bindLong(2, completedAtMillis)
                statement.bindText(3, errorMessage)
                statement.bindLong(4, IndexScanStatus.RUNNING.databaseValue.toLong())
                statement.bindLong(5, IndexScanStatus.PAUSED.databaseValue.toLong())
                statement.step()
            }
            connection.changedRowCount()
        }
    }

    override suspend fun deleteStaleEntries(
        rootId: Long,
        activeScanGeneration: Long
    ): Long {
        require(rootId > 0) { "Root ID must be positive" }
        require(activeScanGeneration >= 0) { "Scan generation must not be negative" }
        return database.transaction { connection ->
            connection.prepare(
                """
                DELETE FROM indexed_files
                WHERE root_id = ? AND scan_generation != ?
                """.trimIndent()
            ).use { statement ->
                statement.bindLong(1, rootId)
                statement.bindLong(2, activeScanGeneration)
                statement.step()
            }
            connection.changedRowCount()
        }
    }

    override suspend fun deleteStaleEntriesUnder(
        rootId: Long,
        activeScanGeneration: Long,
        pathPrefix: String
    ): Long {
        require(rootId > 0) { "Root ID must be positive" }
        require(activeScanGeneration >= 0) { "Scan generation must not be negative" }
        requireValidPathPrefix(pathPrefix)
        return database.transaction { connection ->
            connection.prepare(
                """
                DELETE FROM indexed_files
                WHERE root_id = ?
                    AND scan_generation != ?
                    AND (
                        path = ?
                        OR (? = '/' AND substr(path, 1, 1) = '/')
                        OR (? != '/' AND substr(path, 1, length(?) + 1) = ? || '/')
                    )
                """.trimIndent()
            ).use { statement ->
                statement.bindLong(1, rootId)
                statement.bindLong(2, activeScanGeneration)
                statement.bindPathPrefix(3, pathPrefix)
                statement.step()
            }
            connection.changedRowCount()
        }
    }

    override suspend fun deleteEntriesUnder(rootId: Long, pathPrefix: String): Long =
        deleteEntriesUnder(rootId, listOf(pathPrefix))

    override suspend fun deleteEntriesUnder(
        rootId: Long,
        pathPrefixes: Collection<String>
    ): Long {
        require(rootId > 0) { "Root ID must be positive" }
        pathPrefixes.forEach(::requireValidPathPrefix)
        if (pathPrefixes.isEmpty()) {
            return 0
        }
        return database.transaction { connection ->
            connection.prepare(
                """
                DELETE FROM indexed_files
                WHERE root_id = ?
                    AND (
                        path = ?
                        OR (? = '/' AND substr(path, 1, 1) = '/')
                        OR (? != '/' AND substr(path, 1, length(?) + 1) = ? || '/')
                    )
                """.trimIndent()
            ).use { statement ->
                var removedCount = 0L
                pathPrefixes.forEach { pathPrefix ->
                    statement.bindLong(1, rootId)
                    statement.bindPathPrefix(2, pathPrefix)
                    statement.step()
                    removedCount += connection.changedRowCount()
                    statement.reset()
                    statement.clearBindings()
                }
                removedCount
            }
            }
        }

    override suspend fun deleteEntriesAtOrUnder(pathPrefixes: Collection<String>): Long {
        pathPrefixes.forEach(::requireValidPathPrefix)
        if (pathPrefixes.isEmpty()) {
            return 0
        }
        return database.transaction { connection ->
            connection.prepare(
                """
                DELETE FROM indexed_files
                WHERE path = ?
                    OR (? = '/' AND substr(path, 1, 1) = '/')
                    OR (? != '/' AND substr(path, 1, length(?) + 1) = ? || '/')
                """.trimIndent()
            ).use { statement ->
                var removedCount = 0L
                pathPrefixes.forEach { pathPrefix ->
                    statement.bindPathPrefix(1, pathPrefix)
                    statement.step()
                    removedCount += connection.changedRowCount()
                    statement.reset()
                    statement.clearBindings()
                }
                removedCount
            }
        }
    }

    override suspend fun clearRoot(rootId: Long): Long {
        require(rootId > 0) { "Root ID must be positive" }
        return database.transaction { connection ->
            connection.prepare("DELETE FROM indexed_files WHERE root_id = ?").use { statement ->
                statement.bindLong(1, rootId)
                statement.step()
            }
            connection.changedRowCount()
        }
    }

    override suspend fun getStatistics(): IndexStatistics =
        database.read { connection ->
            val statistics = connection.prepare(GET_STATISTICS_SQL).use { statement ->
                check(statement.step()) { "Statistics query returned no rows" }
                IndexStatistics(
                    rootCount = statement.getLong(0),
                    enabledRootCount = statement.getLong(1),
                    exclusionCount = statement.getLong(2),
                    entryCount = statement.getLong(3),
                    fileCount = statement.getLong(4),
                    directoryCount = statement.getLong(5),
                    rootRequiredEntryCount = statement.getLong(6),
                    hiddenEntryCount = statement.getLong(7),
                    symbolicLinkCount = statement.getLong(8),
                    totalFileSizeBytes = statement.getLong(9),
                    databaseSizeBytes = 0,
                    lastIndexedAtMillis = statement.getNullableLong(10)
                )
            }
            val pageCount = connection.queryLong("PRAGMA page_count")
            val pageSize = connection.queryLong("PRAGMA page_size")
            statistics.copy(databaseSizeBytes = pageCount.saturatingMultiply(pageSize))
        }

    override suspend fun close() {
        database.close()
    }

    companion object {
        @JvmStatic
        fun create(context: Context): SQLiteIndexRepository =
            SQLiteIndexRepository(IndexDatabase.create(context))

        private val UPSERT_ROOT_SQL =
            """
            INSERT INTO index_roots(
                path,
                display_name,
                access_mode,
                is_enabled,
                include_hidden,
                follow_symbolic_links,
                created_at_ms,
                last_scan_completed_at_ms,
                scan_generation,
                last_scan_started_at_ms,
                last_scan_status,
                last_scan_error
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                display_name = excluded.display_name,
                access_mode = excluded.access_mode,
                is_enabled = excluded.is_enabled,
                include_hidden = excluded.include_hidden,
                follow_symbolic_links = excluded.follow_symbolic_links,
                last_scan_started_at_ms = excluded.last_scan_started_at_ms,
                last_scan_completed_at_ms = excluded.last_scan_completed_at_ms,
                last_scan_status = excluded.last_scan_status,
                last_scan_error = excluded.last_scan_error,
                scan_generation = excluded.scan_generation
            """.trimIndent()

        private val GET_ROOTS_SQL =
            """
            SELECT
                id,
                path,
                display_name,
                access_mode,
                is_enabled,
                include_hidden,
                follow_symbolic_links,
                created_at_ms,
                last_scan_completed_at_ms,
                scan_generation,
                last_scan_started_at_ms,
                last_scan_status,
                last_scan_error
            FROM index_roots
            ORDER BY id
            """.trimIndent()

        private val UPSERT_ENTRY_SQL =
            """
            INSERT INTO indexed_files(
                root_id,
                path,
                parent_path,
                name,
                extension,
                mime_type,
                size_bytes,
                modified_at_ms,
                created_at_ms,
                indexed_at_ms,
                is_directory,
                is_symbolic_link,
                is_hidden,
                requires_root,
                symbolic_link_target,
                device_id,
                inode,
                scan_generation
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                root_id = excluded.root_id,
                parent_path = excluded.parent_path,
                name = excluded.name,
                extension = excluded.extension,
                mime_type = excluded.mime_type,
                size_bytes = excluded.size_bytes,
                modified_at_ms = excluded.modified_at_ms,
                created_at_ms = excluded.created_at_ms,
                indexed_at_ms = excluded.indexed_at_ms,
                is_directory = excluded.is_directory,
                is_symbolic_link = excluded.is_symbolic_link,
                is_hidden = excluded.is_hidden,
                requires_root = excluded.requires_root,
                symbolic_link_target = excluded.symbolic_link_target,
                device_id = excluded.device_id,
                inode = excluded.inode,
                scan_generation = excluded.scan_generation
            """.trimIndent()

        private val GET_STATISTICS_SQL =
            """
            SELECT
                (SELECT COUNT(*) FROM index_roots),
                (SELECT COUNT(*) FROM index_roots WHERE is_enabled = 1),
                (SELECT COUNT(*) FROM index_exclusions WHERE is_enabled = 1),
                entry_count,
                file_count,
                directory_count,
                root_required_entry_count,
                hidden_entry_count,
                symbolic_link_count,
                total_file_size_bytes,
                last_indexed_at_ms
            FROM index_statistics
            WHERE id = 1
            """.trimIndent()
    }
}

private val IndexAccessMode.databaseValue: Int
    get() = when (this) {
        IndexAccessMode.STANDARD -> 0
        IndexAccessMode.ROOT -> 1
    }

private fun indexAccessModeFromDatabaseValue(value: Int): IndexAccessMode =
    when (value) {
        0 -> IndexAccessMode.STANDARD
        1 -> IndexAccessMode.ROOT
        else -> error("Unknown index access mode: $value")
    }

private val IndexScanStatus.databaseValue: Int
    get() = when (this) {
        IndexScanStatus.NEVER_RUN -> 0
        IndexScanStatus.RUNNING -> 1
        IndexScanStatus.PAUSED -> 2
        IndexScanStatus.COMPLETED -> 3
        IndexScanStatus.COMPLETED_WITH_ERRORS -> 4
        IndexScanStatus.FAILED -> 5
        IndexScanStatus.CANCELLED -> 6
    }

private fun indexScanStatusFromDatabaseValue(value: Int): IndexScanStatus =
    when (value) {
        0 -> IndexScanStatus.NEVER_RUN
        1 -> IndexScanStatus.RUNNING
        2 -> IndexScanStatus.PAUSED
        3 -> IndexScanStatus.COMPLETED
        4 -> IndexScanStatus.COMPLETED_WITH_ERRORS
        5 -> IndexScanStatus.FAILED
        6 -> IndexScanStatus.CANCELLED
        else -> error("Unknown index scan status: $value")
    }

private fun SQLiteConnection.findExclusionId(rootId: Long?, pathPrefix: String): Long? =
    prepare(
        """
        SELECT id
        FROM index_exclusions
        WHERE root_id IS ? AND path_prefix = ?
        """.trimIndent()
    ).use { statement ->
        statement.bindNullableLong(1, rootId)
        statement.bindText(2, pathPrefix)
        if (statement.step()) statement.getLong(0) else null
    }

private fun SQLiteConnection.changedRowCount(): Long =
    queryLong("SELECT changes()")

private fun SQLiteStatement.bindEntry(entry: IndexedFileRecord) {
    bindLong(1, entry.rootId)
    bindText(2, entry.path)
    bindText(3, entry.parentPath)
    bindText(4, entry.name)
    bindNullableText(5, entry.extension)
    bindNullableText(6, entry.mimeType)
    bindLong(7, entry.sizeBytes)
    bindLong(8, entry.modifiedAtMillis)
    bindNullableLong(9, entry.createdAtMillis)
    bindLong(10, entry.indexedAtMillis)
    bindBoolean(11, entry.isDirectory)
    bindBoolean(12, entry.isSymbolicLink)
    bindBoolean(13, entry.isHidden)
    bindBoolean(14, entry.requiresRoot)
    bindNullableText(15, entry.symbolicLinkTarget)
    bindNullableLong(16, entry.deviceId)
    bindNullableLong(17, entry.inode)
    bindLong(18, entry.scanGeneration)
}

private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
    if (value != null) {
        bindLong(index, value)
    } else {
        bindNull(index)
    }
}

private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
    if (value != null) {
        bindText(index, value)
    } else {
        bindNull(index)
    }
}

private fun SQLiteStatement.bindPathPrefix(firstIndex: Int, pathPrefix: String) {
    repeat(5) { offset ->
        bindText(firstIndex + offset, pathPrefix)
    }
}

private fun SQLiteStatement.getNullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun SQLiteStatement.getNullableText(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun requireValidPathPrefix(pathPrefix: String) {
    require(pathPrefix.isNotBlank()) { "Path prefix must not be blank" }
    require('\u0000' !in pathPrefix) { "Path prefix must not contain NUL" }
}

private fun Long.saturatingMultiply(other: Long): Long =
    if (this == 0L || other <= Long.MAX_VALUE / this) this * other else Long.MAX_VALUE
