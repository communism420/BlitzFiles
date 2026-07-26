/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object IndexSchema {
    const val VERSION = 8

    fun initialize(connection: SQLiteConnection) {
        val observedVersion = connection.queryLong("PRAGMA user_version").toInt()
        checkSupportedVersion(observedVersion)
        if (observedVersion == VERSION) {
            return
        }

        connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
        try {
            // Another connection may have completed the migration while BEGIN IMMEDIATE waited.
            val currentVersion = connection.queryLong("PRAGMA user_version").toInt()
            checkSupportedVersion(currentVersion)
            if (currentVersion < 1) {
                createVersion1(connection)
            }
            if (currentVersion < 2) {
                migrateToVersion2(connection)
            }
            if (currentVersion < 3) {
                migrateToVersion3(connection)
            }
            if (currentVersion < 4) {
                migrateToVersion4(connection)
            }
            if (currentVersion < 5) {
                migrateToVersion5(connection)
            }
            if (currentVersion < 6) {
                migrateToVersion6(connection)
            }
            if (currentVersion < 7) {
                migrateToVersion7(connection)
            }
            if (currentVersion < 8) {
                migrateToVersion8(connection)
            }
            connection.execSQL("PRAGMA user_version = $VERSION")
            connection.execSQL("COMMIT")
        } catch (error: Throwable) {
            try {
                connection.execSQL("ROLLBACK")
            } catch (rollbackError: Throwable) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }
    }

    private fun checkSupportedVersion(version: Int) {
        check(version <= VERSION) {
            "Index database version $version is newer than supported version $VERSION"
        }
    }

    private fun createVersion1(connection: SQLiteConnection) {
        VERSION_1_STATEMENTS.forEach { statement ->
            connection.execSQL(statement)
        }
    }

    private fun migrateToVersion2(connection: SQLiteConnection) {
        VERSION_2_STATEMENTS.forEach { statement ->
            connection.execSQL(statement)
        }
    }

    private fun migrateToVersion3(connection: SQLiteConnection) {
        VERSION_3_STATEMENTS.forEach { statement ->
            connection.execSQL(statement)
        }
    }

    /**
     * Adds the substring index without rebuilding databases that already contain it.
     *
     * Some version-three databases were created with the trigram table while older ones were not.
     * Rebuilding an existing device-wide index can take minutes and temporarily require hundreds
     * of megabytes, so only the missing-table path is backfilled. Triggers are always replaced
     * because CREATE TRIGGER IF NOT EXISTS cannot update a legacy trigger definition.
     */
    private fun migrateToVersion4(connection: SQLiteConnection) {
        val hasTrigramIndex = connection.schemaObjectExists(
            name = "indexed_file_names_trigram_fts",
            type = "table"
        )
        if (!hasTrigramIndex) {
            connection.execSQL(VERSION_4_CREATE_TRIGRAM_INDEX)
            connection.execSQL(
                """
                INSERT INTO indexed_file_names_trigram_fts(
                    indexed_file_names_trigram_fts
                ) VALUES ('rebuild')
                """.trimIndent()
            )
        }

        VERSION_4_FTS_TRIGGER_NAMES.forEach { triggerName ->
            connection.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        }
        VERSION_4_FTS_TRIGGER_STATEMENTS.forEach { statement ->
            connection.execSQL(statement)
        }
    }

    /**
     * Adds a compact covering index for one- and two-character substring searches.
     *
     * FTS5's trigram tokenizer cannot serve terms shorter than three code points. Scanning this
     * narrow index is considerably cheaper than walking the wide metadata table while preserving
     * the required substring semantics and stable row-ID order.
     */
    private fun migrateToVersion5(connection: SQLiteConnection) {
        connection.execSQL(VERSION_5_CREATE_SHORT_NAME_SCAN_INDEX)
    }

    /**
     * Repairs the only local root that can never be enumerated as the application UID.
     *
     * Older UI versions allowed "/" to be saved with standard access. The root itself could be
     * stat'ed, but opening its directory stream always failed with EACCES. Existing entry metadata
     * is marked as root-only and the failed scan state is cleared so the next full scan starts from
     * an accurate configuration.
     */
    private fun migrateToVersion6(connection: SQLiteConnection) {
        if (
            !connection.schemaObjectExists(name = "index_roots", type = "table")
        ) {
            return
        }
        if (connection.schemaObjectExists(name = "indexed_files", type = "table")) {
            connection.execSQL(
                """
                UPDATE indexed_files
                SET requires_root = 1
                WHERE root_id IN (
                    SELECT id
                    FROM index_roots
                    WHERE path = '/' AND access_mode = 0
                )
                """.trimIndent()
            )
        }
        connection.execSQL(
            """
            UPDATE index_roots
            SET access_mode = 1,
                last_scan_started_at_ms = NULL,
                last_scan_completed_at_ms = NULL,
                last_scan_status = 0,
                last_scan_error = NULL
            WHERE path = '/' AND access_mode = 0
            """.trimIndent()
        )
    }

    /**
     * Establishes the completeness invariant required by authoritative no-match FTS probes.
     *
     * Some legacy version-three databases already contained a trigram table, so version four kept
     * it to avoid an unconditional device-wide rebuild. FTS5's rank=1 integrity check compares
     * external content with the postings. Healthy indexes are only read; inconsistent ones are
     * rebuilt once before the schema version is committed.
     */
    private fun migrateToVersion7(connection: SQLiteConnection) {
        if (
            !connection.schemaObjectExists(
                name = "indexed_file_names_trigram_fts",
                type = "table"
            ) ||
            !connection.schemaObjectExists(name = "indexed_files", type = "table")
        ) {
            return
        }

        try {
            verifyTrigramIndexCompleteness(connection)
        } catch (integrityError: Exception) {
            connection.execSQL(
                """
                INSERT INTO indexed_file_names_trigram_fts(
                    indexed_file_names_trigram_fts
                ) VALUES ('rebuild')
                """.trimIndent()
            )
            try {
                verifyTrigramIndexCompleteness(connection)
            } catch (rebuildError: Exception) {
                rebuildError.addSuppressed(integrityError)
                throw rebuildError
            }
        }
    }

    private fun verifyTrigramIndexCompleteness(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO indexed_file_names_trigram_fts(
                indexed_file_names_trigram_fts,
                rank
            ) VALUES ('integrity-check', 1)
            """.trimIndent()
        )
    }

    /**
     * Partitions filename indexes by configured root.
     *
     * B-tree indexes make one- and two-character scans seek directly into a single root. FTS5
     * cannot use a relational root_id predicate while walking a MATCH posting list, so both FTS
     * tables also index a fixed-width root scope. Rebuilding is required once because external
     * content tables cannot add an indexed FTS column in place.
     */
    private fun migrateToVersion8(connection: SQLiteConnection) {
        if (!connection.schemaObjectExists(name = "indexed_files", type = "table")) {
            return
        }

        connection.execSQL(
            """
            ALTER TABLE indexed_files
            ADD COLUMN root_scope TEXT
                GENERATED ALWAYS AS (printf('r%019d', root_id)) VIRTUAL
            """.trimIndent()
        )
        connection.execSQL(VERSION_8_CREATE_ROOT_SHORT_NAME_SCAN_INDEX)
        connection.execSQL(VERSION_8_CREATE_ROOT_NAME_INDEX)

        VERSION_4_FTS_TRIGGER_NAMES.forEach { triggerName ->
            connection.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        }
        connection.execSQL("DROP TABLE IF EXISTS indexed_files_fts")
        connection.execSQL("DROP TABLE IF EXISTS indexed_file_names_trigram_fts")
        connection.execSQL(VERSION_8_CREATE_WORD_INDEX)
        connection.execSQL(VERSION_8_CREATE_TRIGRAM_INDEX)
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
        VERSION_8_FTS_TRIGGER_STATEMENTS.forEach { statement ->
            connection.execSQL(statement)
        }
        verifyTrigramIndexCompleteness(connection)
    }

    private fun SQLiteConnection.schemaObjectExists(name: String, type: String): Boolean =
        prepare(
            "SELECT EXISTS(SELECT 1 FROM sqlite_schema WHERE name = ? AND type = ?)"
        ).use { statement ->
            statement.bindText(1, name)
            statement.bindText(2, type)
            check(statement.step())
            statement.getLong(0) != 0L
        }

    private val VERSION_4_FTS_TRIGGER_NAMES = listOf(
        "indexed_files_after_insert",
        "indexed_files_after_delete",
        "indexed_files_after_name_update"
    )

    private const val VERSION_5_CREATE_SHORT_NAME_SCAN_INDEX =
        """
        CREATE INDEX IF NOT EXISTS indexed_files_short_name_scan
        ON indexed_files(
            id,
            name COLLATE NOCASE,
            root_id,
            is_directory,
            requires_root
        )
        """

    private const val VERSION_8_CREATE_ROOT_SHORT_NAME_SCAN_INDEX =
        """
        CREATE INDEX IF NOT EXISTS indexed_files_root_short_name_scan
        ON indexed_files(
            root_id,
            id,
            name COLLATE NOCASE,
            is_directory,
            requires_root
        )
        """

    private const val VERSION_8_CREATE_ROOT_NAME_INDEX =
        """
        CREATE INDEX IF NOT EXISTS indexed_files_root_name
        ON indexed_files(
            root_id,
            name COLLATE NOCASE,
            path COLLATE BINARY,
            is_directory,
            requires_root
        )
        """

    private val VERSION_8_CREATE_WORD_INDEX =
        """
        CREATE VIRTUAL TABLE indexed_files_fts USING fts5(
            name,
            root_scope,
            content = 'indexed_files',
            content_rowid = 'id',
            tokenize = 'unicode61 remove_diacritics 2',
            prefix = '2 3 4'
        )
        """.trimIndent()

    private val VERSION_8_CREATE_TRIGRAM_INDEX =
        """
        CREATE VIRTUAL TABLE indexed_file_names_trigram_fts USING fts5(
            name,
            root_scope,
            content = 'indexed_files',
            content_rowid = 'id',
            tokenize = 'trigram case_sensitive 0'
        )
        """.trimIndent()

    private val VERSION_4_CREATE_TRIGRAM_INDEX =
        """
        CREATE VIRTUAL TABLE indexed_file_names_trigram_fts USING fts5(
            name,
            content = 'indexed_files',
            content_rowid = 'id',
            tokenize = 'trigram case_sensitive 0'
        )
        """.trimIndent()

    private val VERSION_4_FTS_TRIGGER_STATEMENTS = listOf(
        """
        CREATE TRIGGER indexed_files_after_insert
        AFTER INSERT ON indexed_files
        BEGIN
            INSERT INTO indexed_files_fts(rowid, name) VALUES (new.id, new.name);
            INSERT INTO indexed_file_names_trigram_fts(rowid, name) VALUES (new.id, new.name);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER indexed_files_after_delete
        AFTER DELETE ON indexed_files
        BEGIN
            INSERT INTO indexed_files_fts(indexed_files_fts, rowid, name)
                VALUES ('delete', old.id, old.name);
            INSERT INTO indexed_file_names_trigram_fts(
                indexed_file_names_trigram_fts, rowid, name
            ) VALUES ('delete', old.id, old.name);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER indexed_files_after_name_update
        AFTER UPDATE OF name ON indexed_files
        WHEN old.name IS NOT new.name
        BEGIN
            INSERT INTO indexed_files_fts(indexed_files_fts, rowid, name)
                VALUES ('delete', old.id, old.name);
            INSERT INTO indexed_files_fts(rowid, name) VALUES (new.id, new.name);
            INSERT INTO indexed_file_names_trigram_fts(
                indexed_file_names_trigram_fts, rowid, name
            ) VALUES ('delete', old.id, old.name);
            INSERT INTO indexed_file_names_trigram_fts(rowid, name)
                VALUES (new.id, new.name);
        END
        """.trimIndent()
    )

    private val VERSION_8_FTS_TRIGGER_STATEMENTS = listOf(
        """
        CREATE TRIGGER indexed_files_after_insert
        AFTER INSERT ON indexed_files
        BEGIN
            INSERT INTO indexed_files_fts(rowid, name, root_scope)
                VALUES (new.id, new.name, new.root_scope);
            INSERT INTO indexed_file_names_trigram_fts(rowid, name, root_scope)
                VALUES (new.id, new.name, new.root_scope);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER indexed_files_after_delete
        AFTER DELETE ON indexed_files
        BEGIN
            INSERT INTO indexed_files_fts(
                indexed_files_fts, rowid, name, root_scope
            ) VALUES ('delete', old.id, old.name, old.root_scope);
            INSERT INTO indexed_file_names_trigram_fts(
                indexed_file_names_trigram_fts, rowid, name, root_scope
            ) VALUES ('delete', old.id, old.name, old.root_scope);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER indexed_files_after_name_update
        AFTER UPDATE OF name, root_id ON indexed_files
        WHEN old.name IS NOT new.name OR old.root_id IS NOT new.root_id
        BEGIN
            INSERT INTO indexed_files_fts(
                indexed_files_fts, rowid, name, root_scope
            ) VALUES ('delete', old.id, old.name, old.root_scope);
            INSERT INTO indexed_files_fts(rowid, name, root_scope)
                VALUES (new.id, new.name, new.root_scope);
            INSERT INTO indexed_file_names_trigram_fts(
                indexed_file_names_trigram_fts, rowid, name, root_scope
            ) VALUES ('delete', old.id, old.name, old.root_scope);
            INSERT INTO indexed_file_names_trigram_fts(rowid, name, root_scope)
                VALUES (new.id, new.name, new.root_scope);
        END
        """.trimIndent()
    )

    private val VERSION_3_STATEMENTS = listOf(
        """
        CREATE TABLE index_statistics (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            entry_count INTEGER NOT NULL CHECK (entry_count >= 0),
            file_count INTEGER NOT NULL CHECK (file_count >= 0),
            directory_count INTEGER NOT NULL CHECK (directory_count >= 0),
            root_required_entry_count INTEGER NOT NULL
                CHECK (root_required_entry_count >= 0),
            hidden_entry_count INTEGER NOT NULL CHECK (hidden_entry_count >= 0),
            symbolic_link_count INTEGER NOT NULL CHECK (symbolic_link_count >= 0),
            total_file_size_bytes INTEGER NOT NULL CHECK (total_file_size_bytes >= 0),
            last_indexed_at_ms INTEGER
                CHECK (last_indexed_at_ms IS NULL OR last_indexed_at_ms >= 0)
        )
        """.trimIndent(),
        """
        INSERT INTO index_statistics(
            id,
            entry_count,
            file_count,
            directory_count,
            root_required_entry_count,
            hidden_entry_count,
            symbolic_link_count,
            total_file_size_bytes,
            last_indexed_at_ms
        )
        SELECT
            1,
            COUNT(*),
            COALESCE(SUM(CASE WHEN is_directory = 0 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN is_directory = 1 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN requires_root = 1 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN is_hidden = 1 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN is_symbolic_link = 1 THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN is_directory = 0 THEN size_bytes ELSE 0 END), 0),
            MAX(indexed_at_ms)
        FROM indexed_files
        """.trimIndent(),
        """
        CREATE TRIGGER indexed_files_statistics_after_insert
        AFTER INSERT ON indexed_files
        BEGIN
            UPDATE index_statistics
            SET entry_count = entry_count + 1,
                file_count = file_count + CASE WHEN new.is_directory = 0 THEN 1 ELSE 0 END,
                directory_count =
                    directory_count + CASE WHEN new.is_directory = 1 THEN 1 ELSE 0 END,
                root_required_entry_count =
                    root_required_entry_count +
                        CASE WHEN new.requires_root = 1 THEN 1 ELSE 0 END,
                hidden_entry_count =
                    hidden_entry_count + CASE WHEN new.is_hidden = 1 THEN 1 ELSE 0 END,
                symbolic_link_count =
                    symbolic_link_count +
                        CASE WHEN new.is_symbolic_link = 1 THEN 1 ELSE 0 END,
                total_file_size_bytes =
                    total_file_size_bytes +
                        CASE WHEN new.is_directory = 0 THEN new.size_bytes ELSE 0 END,
                last_indexed_at_ms =
                    CASE
                        WHEN last_indexed_at_ms IS NULL
                            OR new.indexed_at_ms > last_indexed_at_ms
                        THEN new.indexed_at_ms
                        ELSE last_indexed_at_ms
                    END
            WHERE id = 1;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER indexed_files_statistics_after_delete
        AFTER DELETE ON indexed_files
        BEGIN
            UPDATE index_statistics
            SET entry_count = entry_count - 1,
                file_count = file_count - CASE WHEN old.is_directory = 0 THEN 1 ELSE 0 END,
                directory_count =
                    directory_count - CASE WHEN old.is_directory = 1 THEN 1 ELSE 0 END,
                root_required_entry_count =
                    root_required_entry_count -
                        CASE WHEN old.requires_root = 1 THEN 1 ELSE 0 END,
                hidden_entry_count =
                    hidden_entry_count - CASE WHEN old.is_hidden = 1 THEN 1 ELSE 0 END,
                symbolic_link_count =
                    symbolic_link_count -
                        CASE WHEN old.is_symbolic_link = 1 THEN 1 ELSE 0 END,
                total_file_size_bytes =
                    total_file_size_bytes -
                        CASE WHEN old.is_directory = 0 THEN old.size_bytes ELSE 0 END
            WHERE id = 1;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER indexed_files_statistics_after_update
        AFTER UPDATE ON indexed_files
        BEGIN
            UPDATE index_statistics
            SET file_count =
                    file_count +
                        CASE WHEN new.is_directory = 0 THEN 1 ELSE 0 END -
                        CASE WHEN old.is_directory = 0 THEN 1 ELSE 0 END,
                directory_count =
                    directory_count +
                        CASE WHEN new.is_directory = 1 THEN 1 ELSE 0 END -
                        CASE WHEN old.is_directory = 1 THEN 1 ELSE 0 END,
                root_required_entry_count =
                    root_required_entry_count +
                        CASE WHEN new.requires_root = 1 THEN 1 ELSE 0 END -
                        CASE WHEN old.requires_root = 1 THEN 1 ELSE 0 END,
                hidden_entry_count =
                    hidden_entry_count +
                        CASE WHEN new.is_hidden = 1 THEN 1 ELSE 0 END -
                        CASE WHEN old.is_hidden = 1 THEN 1 ELSE 0 END,
                symbolic_link_count =
                    symbolic_link_count +
                        CASE WHEN new.is_symbolic_link = 1 THEN 1 ELSE 0 END -
                        CASE WHEN old.is_symbolic_link = 1 THEN 1 ELSE 0 END,
                total_file_size_bytes =
                    total_file_size_bytes +
                        CASE WHEN new.is_directory = 0 THEN new.size_bytes ELSE 0 END -
                        CASE WHEN old.is_directory = 0 THEN old.size_bytes ELSE 0 END,
                last_indexed_at_ms =
                    CASE
                        WHEN last_indexed_at_ms IS NULL
                            OR new.indexed_at_ms > last_indexed_at_ms
                        THEN new.indexed_at_ms
                        ELSE last_indexed_at_ms
                    END
            WHERE id = 1;
        END
        """.trimIndent()
    )

    private val VERSION_2_STATEMENTS = listOf(
        """
        ALTER TABLE index_roots
        ADD COLUMN last_scan_started_at_ms INTEGER
            CHECK (last_scan_started_at_ms IS NULL OR last_scan_started_at_ms >= 0)
        """.trimIndent(),
        """
        ALTER TABLE index_roots
        ADD COLUMN last_scan_status INTEGER NOT NULL DEFAULT 0
            CHECK (last_scan_status BETWEEN 0 AND 6)
        """.trimIndent(),
        """
        ALTER TABLE index_roots
        ADD COLUMN last_scan_error TEXT
        """.trimIndent()
    )

    private val VERSION_1_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS index_roots (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            path TEXT NOT NULL COLLATE BINARY UNIQUE,
            display_name TEXT NOT NULL,
            access_mode INTEGER NOT NULL CHECK (access_mode IN (0, 1)),
            is_enabled INTEGER NOT NULL CHECK (is_enabled IN (0, 1)),
            include_hidden INTEGER NOT NULL CHECK (include_hidden IN (0, 1)),
            follow_symbolic_links INTEGER NOT NULL CHECK (follow_symbolic_links IN (0, 1)),
            created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
            last_scan_completed_at_ms INTEGER
                CHECK (last_scan_completed_at_ms IS NULL OR last_scan_completed_at_ms >= 0),
            scan_generation INTEGER NOT NULL DEFAULT 0 CHECK (scan_generation >= 0)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS index_exclusions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            root_id INTEGER REFERENCES index_roots(id) ON DELETE CASCADE,
            path_prefix TEXT NOT NULL COLLATE BINARY,
            is_enabled INTEGER NOT NULL CHECK (is_enabled IN (0, 1))
        )
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS index_exclusions_scope_path
        ON index_exclusions(COALESCE(root_id, -1), path_prefix COLLATE BINARY)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS indexed_files (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            root_id INTEGER NOT NULL REFERENCES index_roots(id) ON DELETE CASCADE,
            path TEXT NOT NULL COLLATE BINARY UNIQUE,
            parent_path TEXT NOT NULL COLLATE BINARY,
            name TEXT NOT NULL,
            extension TEXT,
            mime_type TEXT,
            size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
            modified_at_ms INTEGER NOT NULL CHECK (modified_at_ms >= 0),
            created_at_ms INTEGER CHECK (created_at_ms IS NULL OR created_at_ms >= 0),
            indexed_at_ms INTEGER NOT NULL CHECK (indexed_at_ms >= 0),
            is_directory INTEGER NOT NULL CHECK (is_directory IN (0, 1)),
            is_symbolic_link INTEGER NOT NULL CHECK (is_symbolic_link IN (0, 1)),
            is_hidden INTEGER NOT NULL CHECK (is_hidden IN (0, 1)),
            requires_root INTEGER NOT NULL CHECK (requires_root IN (0, 1)),
            symbolic_link_target TEXT,
            device_id INTEGER,
            inode INTEGER,
            scan_generation INTEGER NOT NULL CHECK (scan_generation >= 0)
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS indexed_files_parent_path
        ON indexed_files(parent_path COLLATE BINARY)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS indexed_files_root_generation
        ON indexed_files(root_id, scan_generation)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS indexed_files_name
        ON indexed_files(name COLLATE NOCASE, path COLLATE BINARY)
        """.trimIndent(),
        VERSION_5_CREATE_SHORT_NAME_SCAN_INDEX.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS indexed_files_size
        ON indexed_files(size_bytes)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS indexed_files_modified
        ON indexed_files(modified_at_ms)
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS indexed_files_fts USING fts5(
            name,
            content = 'indexed_files',
            content_rowid = 'id',
            tokenize = 'unicode61 remove_diacritics 2',
            prefix = '2 3 4'
        )
        """.trimIndent(),
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS indexed_file_names_trigram_fts USING fts5(
            name,
            content = 'indexed_files',
            content_rowid = 'id',
            tokenize = 'trigram case_sensitive 0'
        )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS indexed_files_after_insert
        AFTER INSERT ON indexed_files
        BEGIN
            INSERT INTO indexed_files_fts(rowid, name) VALUES (new.id, new.name);
            INSERT INTO indexed_file_names_trigram_fts(rowid, name) VALUES (new.id, new.name);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS indexed_files_after_delete
        AFTER DELETE ON indexed_files
        BEGIN
            INSERT INTO indexed_files_fts(indexed_files_fts, rowid, name)
                VALUES ('delete', old.id, old.name);
            INSERT INTO indexed_file_names_trigram_fts(
                indexed_file_names_trigram_fts, rowid, name
            ) VALUES ('delete', old.id, old.name);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS indexed_files_after_name_update
        AFTER UPDATE OF name ON indexed_files
        WHEN old.name IS NOT new.name
        BEGIN
            INSERT INTO indexed_files_fts(indexed_files_fts, rowid, name)
                VALUES ('delete', old.id, old.name);
            INSERT INTO indexed_files_fts(rowid, name) VALUES (new.id, new.name);
            INSERT INTO indexed_file_names_trigram_fts(
                indexed_file_names_trigram_fts, rowid, name
            ) VALUES ('delete', old.id, old.name);
            INSERT INTO indexed_file_names_trigram_fts(rowid, name)
                VALUES (new.id, new.name);
        END
        """.trimIndent()
    )
}

internal fun SQLiteConnection.queryLong(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Query returned no rows: $sql" }
        statement.getLong(0)
    }
