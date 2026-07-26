/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.database

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns one serialized bundled-SQLite connection.
 *
 * Every operation is dispatched away from the caller and guarded by [connectionMutex]. Statements
 * must never escape the block passed to [read] or [transaction].
 */
internal class IndexDatabase(
    private val databaseFile: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val driver: SQLiteDriver = BundledSQLiteDriver()
) {
    private val connectionMutex = Mutex()
    private var connection: SQLiteConnection? = null
    private var isClosed = false

    suspend fun <T> read(block: (SQLiteConnection) -> T): T =
        withConnection(block)

    suspend fun <T> transaction(block: (SQLiteConnection) -> T): T =
        withConnection { openConnection ->
            openConnection.execSQL("BEGIN IMMEDIATE TRANSACTION")
            try {
                val result = block(openConnection)
                openConnection.execSQL("COMMIT")
                result
            } catch (error: Throwable) {
                try {
                    openConnection.execSQL("ROLLBACK")
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            }
        }

    suspend fun close() {
        withContext(dispatcher) {
            connectionMutex.withLock {
                isClosed = true
                connection?.close()
                connection = null
            }
        }
    }

    private suspend fun <T> withConnection(block: (SQLiteConnection) -> T): T =
        withContext(dispatcher) {
            connectionMutex.withLock {
                block(openConnectionLocked())
            }
        }

    private fun openConnectionLocked(): SQLiteConnection {
        check(!isClosed) { "Index database is closed" }
        connection?.let { return it }

        val parentDirectory = databaseFile.parentFile
        if (parentDirectory != null && !parentDirectory.isDirectory) {
            check(parentDirectory.mkdirs() || parentDirectory.isDirectory) {
                "Cannot create index database directory: $parentDirectory"
            }
        }

        val newConnection = driver.open(databaseFile.absolutePath)
        try {
            configure(newConnection)
            IndexSchema.initialize(newConnection)
        } catch (error: Throwable) {
            newConnection.close()
            throw error
        }
        connection = newConnection
        return newConnection
    }

    private fun configure(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("PRAGMA busy_timeout = 5000")
        connection.prepare("PRAGMA journal_mode = WAL").use { statement ->
            check(statement.step()) { "Unable to enable WAL mode" }
        }
        connection.execSQL("PRAGMA synchronous = NORMAL")
        connection.execSQL("PRAGMA temp_store = MEMORY")
        // The filename index is intentionally much larger than SQLite's small default cache.
        // A bounded cache plus mmap lets repeated as-you-type queries reuse hot B-tree/FTS pages
        // without retaining the complete index in the managed heap.
        connection.execSQL("PRAGMA cache_size = -32768")
        connection.execSQL("PRAGMA mmap_size = 268435456")
        // Large FTS batches can temporarily grow WAL well beyond the normal autocheckpoint size.
        // Limit the retained file after a checkpoint so future cold searches read less metadata.
        connection.execSQL("PRAGMA wal_autocheckpoint = 1000")
        connection.execSQL("PRAGMA journal_size_limit = 16777216")
    }

    companion object {
        private const val DATABASE_DIRECTORY = "search-index"
        private const val DATABASE_FILE = "blitzfiles-index.db"

        fun create(context: Context): IndexDatabase {
            val applicationContext = context.applicationContext
            val databaseFile = File(
                File(applicationContext.noBackupFilesDir, DATABASE_DIRECTORY),
                DATABASE_FILE
            )
            return IndexDatabase(databaseFile)
        }
    }
}
