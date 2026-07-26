/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.blitzfiles.search.data.repository.SQLiteIndexRepository
import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexedFileRecord
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexSchemaTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: IndexDatabase
    private lateinit var repository: SQLiteIndexRepository

    @Before
    fun setUp() {
        val databaseFile = File(temporaryFolder.newFolder("database"), "index.db")
        database = IndexDatabase(databaseFile)
        repository = SQLiteIndexRepository(database)
    }

    @After
    fun tearDown() = runBlocking {
        repository.close()
    }

    @Test
    fun createsCurrentSchemaAndBothFtsIndexes() = runBlocking {
        database.read { connection ->
            assertEquals(IndexSchema.VERSION.toLong(), connection.queryLong("PRAGMA user_version"))
            assertSchemaObjectExists(connection, "index_roots", "table")
            assertSchemaObjectExists(connection, "index_exclusions", "table")
            assertSchemaObjectExists(connection, "indexed_files", "table")
            assertSchemaObjectExists(connection, "index_statistics", "table")
            assertSchemaObjectExists(connection, "indexed_files_fts", "table")
            assertSchemaObjectExists(connection, "indexed_file_names_trigram_fts", "table")
            assertSchemaObjectExists(connection, "indexed_files_short_name_scan", "index")
            assertSchemaObjectExists(connection, "indexed_files_root_short_name_scan", "index")
            assertSchemaObjectExists(connection, "indexed_files_root_name", "index")
            assertSchemaObjectExists(connection, "indexed_files_after_insert", "trigger")
            assertSchemaObjectExists(connection, "indexed_files_after_delete", "trigger")
            assertSchemaObjectExists(connection, "indexed_files_after_name_update", "trigger")
            assertSchemaObjectExists(
                connection,
                "indexed_files_statistics_after_insert",
                "trigger"
            )
            assertSchemaObjectExists(
                connection,
                "indexed_files_statistics_after_delete",
                "trigger"
            )
            assertSchemaObjectExists(
                connection,
                "indexed_files_statistics_after_update",
                "trigger"
            )
            val fileColumns = connection.prepare(
                "PRAGMA table_xinfo(indexed_files)"
            ).use { statement ->
                buildSet {
                    while (statement.step()) {
                        add(statement.getText(1))
                    }
                }
            }
            assertTrue("root_scope" in fileColumns)
            assertEquals("ok", connection.queryText("PRAGMA integrity_check"))
        }
    }

    @Test
    fun triggersKeepWordAndTrigramIndexesSynchronized() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/storage/emulated/0",
                displayName = "Internal storage",
                accessMode = IndexAccessMode.STANDARD,
                createdAtMillis = 1
            )
        )
        val otherRootId = repository.upsertRoot(
            IndexRoot(
                path = "/other",
                displayName = "Other",
                accessMode = IndexAccessMode.STANDARD,
                createdAtMillis = 1
            )
        )
        val entry = IndexedFileRecord(
            rootId = rootId,
            path = "/storage/emulated/0/Documents/QuarterlyReport.pdf",
            parentPath = "/storage/emulated/0/Documents",
            name = "QuarterlyReport.pdf",
            extension = "pdf",
            mimeType = "application/pdf",
            sizeBytes = 42,
            modifiedAtMillis = 2,
            indexedAtMillis = 3,
            isDirectory = false,
            scanGeneration = 1
        )

        repository.upsertEntries(listOf(entry))
        database.read { connection ->
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_files_fts " +
                        "WHERE indexed_files_fts MATCH ?",
                    "Quarterly*"
                )
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE name LIKE ?",
                    "%report%"
                )
            )
        }

        repository.upsertEntries(listOf(entry.copy(name = "QuarterlySummary.pdf")))
        database.read { connection ->
            assertEquals(
                0L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE name LIKE ?",
                    "%report%"
                )
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE name LIKE ?",
                    "%summary%"
                )
            )
        }

        repository.upsertEntries(
            listOf(
                entry.copy(
                    rootId = otherRootId,
                    name = "QuarterlySummary.pdf"
                )
            )
        )
        database.read { connection ->
            assertEquals(
                0L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    scopedFtsQuery("summary", rootId)
                )
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    scopedFtsQuery("summary", otherRootId)
                )
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_files_fts " +
                        "WHERE indexed_files_fts MATCH ?",
                    scopedWordPrefixQuery("QuarterlySummary", otherRootId)
                )
            )
        }

        assertTrue(repository.deleteEntry(entry.path))
        database.read { connection ->
            assertEquals(0L, connection.queryLong("SELECT COUNT(*) FROM indexed_files_fts"))
            assertEquals(
                0L,
                connection.queryLong("SELECT COUNT(*) FROM indexed_file_names_trigram_fts")
            )
            connection.execSQL(
                "INSERT INTO indexed_files_fts(indexed_files_fts) VALUES ('integrity-check')"
            )
            connection.execSQL(
                """
                INSERT INTO indexed_file_names_trigram_fts(
                    indexed_file_names_trigram_fts,
                    rank
                ) VALUES ('integrity-check', 1)
                """.trimIndent()
            )
        }
    }

    @Test
    fun migratesVersionOneRootScanStateWithoutDroppingConfiguration() {
        val migrationFile = File(temporaryFolder.newFolder("migration"), "version-one.db")
        BundledSQLiteDriver().open(migrationFile.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE index_roots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL COLLATE BINARY UNIQUE,
                    display_name TEXT NOT NULL,
                    access_mode INTEGER NOT NULL CHECK (access_mode IN (0, 1)),
                    is_enabled INTEGER NOT NULL CHECK (is_enabled IN (0, 1)),
                    include_hidden INTEGER NOT NULL CHECK (include_hidden IN (0, 1)),
                    follow_symbolic_links INTEGER NOT NULL CHECK (
                        follow_symbolic_links IN (0, 1)
                    ),
                    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
                    last_scan_completed_at_ms INTEGER,
                    scan_generation INTEGER NOT NULL DEFAULT 0 CHECK (scan_generation >= 0)
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO index_roots(
                    path, display_name, access_mode, is_enabled, include_hidden,
                    follow_symbolic_links, created_at_ms, scan_generation
                ) VALUES ('/data', 'Data', 0, 1, 1, 0, 1, 4)
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TABLE indexed_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    root_id INTEGER NOT NULL DEFAULT 1,
                    path TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    indexed_at_ms INTEGER NOT NULL,
                    is_directory INTEGER NOT NULL,
                    is_symbolic_link INTEGER NOT NULL,
                    is_hidden INTEGER NOT NULL,
                    requires_root INTEGER NOT NULL
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO indexed_files(
                    path, name, size_bytes, indexed_at_ms, is_directory, is_symbolic_link,
                    is_hidden, requires_root
                ) VALUES ('/data/file.txt', 'file.txt', 12, 5, 0, 0, 1, 1)
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE indexed_files_fts USING fts5(
                    name,
                    content = 'indexed_files',
                    content_rowid = 'id',
                    tokenize = 'unicode61 remove_diacritics 2',
                    prefix = '2 3 4'
                )
                """.trimIndent()
            )
            connection.execSQL(
                "INSERT INTO indexed_files_fts(indexed_files_fts) VALUES ('rebuild')"
            )
            connection.execSQL("PRAGMA user_version = 1")

            IndexSchema.initialize(connection)

            assertEquals(IndexSchema.VERSION.toLong(), connection.queryLong("PRAGMA user_version"))
            val columns = connection.prepare("PRAGMA table_info(index_roots)").use { statement ->
                buildSet {
                    while (statement.step()) {
                        add(statement.getText(1))
                    }
                }
            }
            assertTrue("last_scan_started_at_ms" in columns)
            assertTrue("last_scan_status" in columns)
            assertTrue("last_scan_error" in columns)
            assertEquals(4L, connection.queryLong("SELECT scan_generation FROM index_roots"))
            assertEquals(1L, connection.queryLong("SELECT COUNT(*) FROM index_statistics"))
            assertEquals(1L, connection.queryLong(
                "SELECT entry_count FROM index_statistics WHERE id = 1"
            ))
            assertEquals(12L, connection.queryLong(
                "SELECT total_file_size_bytes FROM index_statistics WHERE id = 1"
            ))
        }
    }

    @Test
    fun migratesVersionThreeWithoutTrigramAndReplacesLegacyFtsTriggers() {
        val migrationFile = File(temporaryFolder.newFolder("migration-v3"), "version-three.db")
        BundledSQLiteDriver().open(migrationFile.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE indexed_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    root_id INTEGER NOT NULL DEFAULT 1,
                    path TEXT NOT NULL UNIQUE,
                    is_directory INTEGER NOT NULL DEFAULT 0,
                    requires_root INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE indexed_files_fts USING fts5(
                    name,
                    content = 'indexed_files',
                    content_rowid = 'id',
                    tokenize = 'unicode61 remove_diacritics 2',
                    prefix = '2 3 4'
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO indexed_files(name, path)
                VALUES ('QuarterlyReport.pdf', '/QuarterlyReport.pdf')
                """.trimIndent()
            )
            connection.execSQL(
                "INSERT INTO indexed_files_fts(indexed_files_fts) VALUES ('rebuild')"
            )
            connection.execSQL(
                """
                CREATE TRIGGER indexed_files_after_insert
                AFTER INSERT ON indexed_files
                BEGIN
                    INSERT INTO indexed_files_fts(rowid, name) VALUES (new.id, new.name);
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER indexed_files_after_delete
                AFTER DELETE ON indexed_files
                BEGIN
                    INSERT INTO indexed_files_fts(indexed_files_fts, rowid, name)
                        VALUES ('delete', old.id, old.name);
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER indexed_files_after_name_update
                AFTER UPDATE OF name ON indexed_files
                WHEN old.name IS NOT new.name
                BEGIN
                    INSERT INTO indexed_files_fts(indexed_files_fts, rowid, name)
                        VALUES ('delete', old.id, old.name);
                    INSERT INTO indexed_files_fts(rowid, name) VALUES (new.id, new.name);
                END
                """.trimIndent()
            )
            connection.execSQL("PRAGMA user_version = 3")

            IndexSchema.initialize(connection)

            assertEquals(IndexSchema.VERSION.toLong(), connection.queryLong("PRAGMA user_version"))
            assertSchemaObjectExists(
                connection,
                "indexed_file_names_trigram_fts",
                "table"
            )
            assertSchemaObjectExists(
                connection,
                "indexed_files_short_name_scan",
                "index"
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    "\"report\""
                )
            )

            connection.execSQL(
                """
                INSERT INTO indexed_files(name, path)
                VALUES ('SecondReport.txt', '/SecondReport.txt')
                """.trimIndent()
            )
            assertEquals(
                2L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    "\"report\""
                )
            )
            connection.execSQL(
                "UPDATE indexed_files SET name = 'SecondSummary.txt' WHERE id = 2"
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    "\"report\""
                )
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    "\"summary\""
                )
            )
            connection.execSQL("DELETE FROM indexed_files WHERE id = 1")
            assertEquals(
                0L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    "\"report\""
                )
            )
        }
    }

    @Test
    fun versionSevenRepairsAnInconsistentExistingTrigramIndex() {
        val migrationFile = File(temporaryFolder.newFolder("migration-v3-existing"), "index.db")
        BundledSQLiteDriver().open(migrationFile.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE indexed_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    root_id INTEGER NOT NULL DEFAULT 1,
                    path TEXT NOT NULL UNIQUE,
                    is_directory INTEGER NOT NULL DEFAULT 0,
                    requires_root INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE indexed_files_fts USING fts5(
                    name,
                    content = 'indexed_files',
                    content_rowid = 'id',
                    tokenize = 'unicode61 remove_diacritics 2',
                    prefix = '2 3 4'
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE indexed_file_names_trigram_fts USING fts5(
                    name,
                    content = 'indexed_files',
                    content_rowid = 'id',
                    tokenize = 'trigram case_sensitive 0'
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO indexed_files(name, path)
                VALUES ('ContentName', '/ContentName')
                """.trimIndent()
            )
            connection.execSQL(
                "INSERT INTO indexed_files_fts(indexed_files_fts) VALUES ('rebuild')"
            )
            // The posting intentionally differs from external content. Version seven must detect
            // this legacy state before authoritative no-match probes are enabled.
            connection.execSQL(
                """
                INSERT INTO indexed_file_names_trigram_fts(rowid, name)
                VALUES (1, 'MigrationProbe')
                """.trimIndent()
            )
            connection.execSQL("PRAGMA user_version = 3")

            IndexSchema.initialize(connection)

            assertEquals(IndexSchema.VERSION.toLong(), connection.queryLong("PRAGMA user_version"))
            assertEquals(
                0L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    "\"probe\""
                )
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    "\"content\""
                )
            )
        }
    }

    @Test
    fun versionEightRebuildsRootScopedFtsAndTracksRootOwnershipChanges() {
        val migrationFile = File(temporaryFolder.newFolder("migration-v7"), "version-seven.db")
        BundledSQLiteDriver().open(migrationFile.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE indexed_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    root_id INTEGER NOT NULL,
                    path TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    is_directory INTEGER NOT NULL DEFAULT 0,
                    requires_root INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO indexed_files(root_id, path, name)
                VALUES
                    (1, '/selected/report.txt', 'report.txt'),
                    (2, '/other/summary.txt', 'summary.txt')
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE indexed_files_fts USING fts5(
                    name,
                    content = 'indexed_files',
                    content_rowid = 'id',
                    tokenize = 'unicode61 remove_diacritics 2',
                    prefix = '2 3 4'
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE indexed_file_names_trigram_fts USING fts5(
                    name,
                    content = 'indexed_files',
                    content_rowid = 'id',
                    tokenize = 'trigram case_sensitive 0'
                )
                """.trimIndent()
            )
            connection.execSQL(
                "INSERT INTO indexed_files_fts(indexed_files_fts) VALUES ('rebuild')"
            )
            connection.execSQL(
                """
                INSERT INTO indexed_file_names_trigram_fts(
                    indexed_file_names_trigram_fts
                ) VALUES ('rebuild')
                """.trimIndent()
            )
            connection.execSQL("PRAGMA user_version = 7")

            IndexSchema.initialize(connection)

            assertEquals(IndexSchema.VERSION.toLong(), connection.queryLong("PRAGMA user_version"))
            assertSchemaObjectExists(
                connection,
                "indexed_files_root_short_name_scan",
                "index"
            )
            assertSchemaObjectExists(connection, "indexed_files_root_name", "index")
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    scopedFtsQuery("report", 1L)
                )
            )
            assertEquals(
                0L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    scopedFtsQuery("report", 2L)
                )
            )

            connection.execSQL("UPDATE indexed_files SET root_id = 2 WHERE id = 1")

            assertEquals(
                0L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    scopedFtsQuery("report", 1L)
                )
            )
            assertEquals(
                1L,
                connection.queryCount(
                    "SELECT COUNT(*) FROM indexed_file_names_trigram_fts " +
                        "WHERE indexed_file_names_trigram_fts MATCH ?",
                    scopedFtsQuery("report", 2L)
                )
            )
            connection.execSQL(
                """
                INSERT INTO indexed_file_names_trigram_fts(
                    indexed_file_names_trigram_fts,
                    rank
                ) VALUES ('integrity-check', 1)
                """.trimIndent()
            )
        }
    }

    @Test
    fun versionSixRepairsOnlyStandardAccessSystemRoot() {
        val migrationFile = File(temporaryFolder.newFolder("migration-v5"), "version-five.db")
        BundledSQLiteDriver().open(migrationFile.absolutePath).use { connection ->
            connection.execSQL(
                """
                CREATE TABLE index_roots (
                    id INTEGER PRIMARY KEY,
                    path TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    access_mode INTEGER NOT NULL,
                    last_scan_started_at_ms INTEGER,
                    last_scan_completed_at_ms INTEGER,
                    last_scan_status INTEGER NOT NULL,
                    last_scan_error TEXT
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TABLE indexed_files (
                    id INTEGER PRIMARY KEY,
                    root_id INTEGER NOT NULL,
                    path TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    is_directory INTEGER NOT NULL,
                    requires_root INTEGER NOT NULL
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO index_roots(
                    id, path, display_name, access_mode, last_scan_started_at_ms,
                    last_scan_completed_at_ms, last_scan_status, last_scan_error
                ) VALUES
                    (1, '/', 'Root', 0, 10, 11, 4, 'opendir: Permission denied'),
                    (2, '/storage/emulated/0', 'Internal storage', 0, 20, 21, 3, NULL),
                    (3, '/data', 'Data', 1, 30, 31, 3, NULL)
                """.trimIndent()
            )
            connection.execSQL(
                """
                INSERT INTO indexed_files(
                    id, root_id, path, name, is_directory, requires_root
                ) VALUES
                    (1, 1, '/root-entry', 'root-entry', 0, 0),
                    (2, 2, '/storage-entry', 'storage-entry', 0, 0),
                    (3, 3, '/data-entry', 'data-entry', 0, 1)
                """.trimIndent()
            )
            connection.execSQL("PRAGMA user_version = 5")

            IndexSchema.initialize(connection)

            assertEquals(IndexSchema.VERSION.toLong(), connection.queryLong("PRAGMA user_version"))
            assertEquals(
                1L,
                connection.queryLong("SELECT access_mode FROM index_roots WHERE id = 1")
            )
            assertEquals(
                0L,
                connection.queryLong("SELECT last_scan_status FROM index_roots WHERE id = 1")
            )
            assertEquals(
                1L,
                connection.queryLong(
                    "SELECT last_scan_error IS NULL FROM index_roots WHERE id = 1"
                )
            )
            assertEquals(
                1L,
                connection.queryLong("SELECT requires_root FROM indexed_files WHERE id = 1")
            )
            assertEquals(
                0L,
                connection.queryLong("SELECT access_mode FROM index_roots WHERE id = 2")
            )
            assertEquals(
                3L,
                connection.queryLong("SELECT last_scan_status FROM index_roots WHERE id = 2")
            )
            assertEquals(
                1L,
                connection.queryLong("SELECT access_mode FROM index_roots WHERE id = 3")
            )
        }
    }

    private fun assertSchemaObjectExists(
        connection: SQLiteConnection,
        name: String,
        type: String
    ) {
        val count = connection.prepare(
            "SELECT COUNT(*) FROM sqlite_schema WHERE name = ? AND type = ?"
        ).use { statement ->
            statement.bindText(1, name)
            statement.bindText(2, type)
            check(statement.step())
            statement.getLong(0)
        }
        assertEquals("Missing $type $name", 1L, count)
    }
}

private fun SQLiteConnection.queryCount(sql: String, argument: String): Long =
    prepare(sql).use { statement ->
        statement.bindText(1, argument)
        check(statement.step())
        statement.getLong(0)
    }

private fun SQLiteConnection.queryText(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getText(0)
    }

private fun scopedFtsQuery(term: String, rootId: Long): String =
    "name : (\"$term\") AND root_scope : (\"${rootId.toRootScope()}\")"

private fun scopedWordPrefixQuery(term: String, rootId: Long): String =
    "name : (\"$term\"*) AND root_scope : (\"${rootId.toRootScope()}\")"

private fun Long.toRootScope(): String =
    "r${toString().padStart(19, '0')}"
