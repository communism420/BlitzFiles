/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.repository

import androidx.sqlite.SQLiteConnection
import com.blitzfiles.search.data.database.IndexDatabase
import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexExclusion
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.IndexedFileRecord
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SQLiteIndexRepositoryTest {
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
    fun persistsRootConfigurationAndScopedExclusions() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/data",
                displayName = "Root data",
                accessMode = IndexAccessMode.ROOT,
                includeHidden = false,
                followSymbolicLinks = true,
                createdAtMillis = 10,
                lastScanCompletedAtMillis = 20,
                scanGeneration = 3
            )
        )
        val globalExclusionId = repository.upsertExclusion(
            IndexExclusion(pathPrefix = "/proc")
        )
        val rootExclusionId = repository.upsertExclusion(
            IndexExclusion(rootId = rootId, pathPrefix = "/data/cache", isEnabled = false)
        )

        val root = repository.getRoots().single()
        assertEquals(rootId, root.id)
        assertEquals(IndexAccessMode.ROOT, root.accessMode)
        assertFalse(root.includeHidden)
        assertTrue(root.followSymbolicLinks)
        assertEquals(3L, root.scanGeneration)

        val exclusions = repository.getExclusions()
        assertEquals(2, exclusions.size)
        assertNull(exclusions.first { it.id == globalExclusionId }.rootId)
        assertFalse(exclusions.first { it.id == rootExclusionId }.isEnabled)
        assertTrue(repository.deleteExclusion(globalExclusionId))
        assertFalse(repository.deleteExclusion(globalExclusionId))
        assertTrue(repository.deleteRoot(rootId))
        assertTrue(repository.getRoots().isEmpty())
        assertTrue(repository.getExclusions().isEmpty())
        assertFalse(repository.deleteRoot(rootId))
    }

    @Test
    fun changingRootAccessModeAtomicallyInvalidatesItsPreviousIndex() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/storage/emulated/0",
                displayName = "Internal storage",
                accessMode = IndexAccessMode.STANDARD,
                createdAtMillis = 1,
                lastScanStartedAtMillis = 10,
                lastScanCompletedAtMillis = 20,
                lastScanStatus = IndexScanStatus.COMPLETED,
                scanGeneration = 3
            )
        )
        repository.upsertEntries(
            listOf(
                entry(
                    rootId = rootId,
                    path = "/storage/emulated/0/file.txt",
                    name = "file.txt",
                    sizeBytes = 1,
                    isDirectory = false,
                    generation = 3
                )
            )
        )

        repository.upsertRoot(
            repository.getRoots().single().copy(accessMode = IndexAccessMode.ROOT)
        )

        val updatedRoot = repository.getRoots().single()
        assertEquals(IndexAccessMode.ROOT, updatedRoot.accessMode)
        assertEquals(IndexScanStatus.NEVER_RUN, updatedRoot.lastScanStatus)
        assertEquals(0L, updatedRoot.scanGeneration)
        assertNull(updatedRoot.lastScanStartedAtMillis)
        assertNull(updatedRoot.lastScanCompletedAtMillis)
        assertNull(updatedRoot.lastScanError)
        assertEquals(0L, repository.getStatistics().entryCount)
    }

    @Test
    fun storesMetadataAndRemovesEntriesFromOldScanGenerations() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/storage/emulated/0",
                displayName = "Internal storage",
                accessMode = IndexAccessMode.STANDARD,
                createdAtMillis = 1
            )
        )
        repository.upsertEntries(
            listOf(
                entry(
                    rootId = rootId,
                    path = "/storage/emulated/0/Music",
                    name = "Music",
                    sizeBytes = 0,
                    isDirectory = true,
                    generation = 2
                ),
                entry(
                    rootId = rootId,
                    path = "/storage/emulated/0/Music/song.flac",
                    name = "song.flac",
                    sizeBytes = 1_024,
                    isDirectory = false,
                    generation = 1
                )
            )
        )

        val beforeCleanup = repository.getStatistics()
        assertEquals(1L, beforeCleanup.rootCount)
        assertEquals(1L, beforeCleanup.enabledRootCount)
        assertEquals(0L, beforeCleanup.exclusionCount)
        assertEquals(2L, beforeCleanup.entryCount)
        assertEquals(1L, beforeCleanup.fileCount)
        assertEquals(1L, beforeCleanup.directoryCount)
        assertEquals(1_024L, beforeCleanup.totalFileSizeBytes)
        assertEquals(0L, beforeCleanup.rootRequiredEntryCount)
        assertEquals(0L, beforeCleanup.hiddenEntryCount)
        assertEquals(0L, beforeCleanup.symbolicLinkCount)
        assertTrue(beforeCleanup.databaseSizeBytes > 0)
        assertEquals(30L, beforeCleanup.lastIndexedAtMillis)

        repository.upsertEntries(
            listOf(
                entry(
                    rootId = rootId,
                    path = "/storage/emulated/0/Music/song.flac",
                    name = "song.flac",
                    sizeBytes = 2_048,
                    isDirectory = false,
                    generation = 1
                ).copy(
                    requiresRoot = true,
                    isHidden = true,
                    isSymbolicLink = true,
                    indexedAtMillis = 40
                )
            )
        )
        val afterUpdate = repository.getStatistics()
        assertEquals(2_048L, afterUpdate.totalFileSizeBytes)
        assertEquals(1L, afterUpdate.rootRequiredEntryCount)
        assertEquals(1L, afterUpdate.hiddenEntryCount)
        assertEquals(1L, afterUpdate.symbolicLinkCount)
        assertEquals(40L, afterUpdate.lastIndexedAtMillis)

        assertEquals(1L, repository.deleteStaleEntries(rootId, activeScanGeneration = 2))
        val afterCleanup = repository.getStatistics()
        assertEquals(1L, afterCleanup.entryCount)
        assertEquals(0L, afterCleanup.fileCount)
        assertEquals(1L, afterCleanup.directoryCount)
        assertEquals(1L, repository.clearRoot(rootId))
        assertEquals(0L, repository.getStatistics().entryCount)
    }

    @Test
    fun tracksScanLifecycleAndCleansOnlyRequestedSubtree() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/data",
                displayName = "Data",
                accessMode = IndexAccessMode.STANDARD,
                createdAtMillis = 1
            )
        )
        repository.upsertEntries(
            listOf(
                entry(rootId, "/data/changed/old.txt", "old.txt", 1, false, 0),
                entry(rootId, "/data/unchanged.txt", "unchanged.txt", 1, false, 0)
            )
        )

        val generation = repository.beginScan(rootId, startedAtMillis = 10)
        assertEquals(1L, generation)
        assertEquals(IndexScanStatus.RUNNING, repository.getRoots().single().lastScanStatus)
        assertEquals(
            1L,
            repository.deleteStaleEntriesUnder(rootId, generation, "/data/changed")
        )
        assertEquals(1L, repository.getStatistics().entryCount)

        repository.updateScanStatus(
            rootId,
            generation,
            IndexScanStatus.COMPLETED,
            completedAtMillis = 20
        )
        val root = repository.getRoots().single()
        assertEquals(IndexScanStatus.COMPLETED, root.lastScanStatus)
        assertEquals(10L, root.lastScanStartedAtMillis)
        assertEquals(20L, root.lastScanCompletedAtMillis)
    }

    @Test
    fun recoversScanStateLeftActiveByProcessTermination() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/data",
                displayName = "Data",
                accessMode = IndexAccessMode.ROOT,
                createdAtMillis = 1
            )
        )
        repository.beginScan(rootId, startedAtMillis = 10)

        assertEquals(
            1L,
            repository.recoverInterruptedScans(
                completedAtMillis = 20,
                errorMessage = "Process stopped"
            )
        )

        val recoveredRoot = repository.getRoots().single()
        assertEquals(IndexScanStatus.FAILED, recoveredRoot.lastScanStatus)
        assertEquals(20L, recoveredRoot.lastScanCompletedAtMillis)
        assertEquals("Process stopped", recoveredRoot.lastScanError)
        assertEquals(
            0L,
            repository.recoverInterruptedScans(
                completedAtMillis = 30,
                errorMessage = "Process stopped"
            )
        )
    }

    @Test
    fun removesMultipleExcludedSubtreesInOneRepositoryCall() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/",
                displayName = "Root",
                accessMode = IndexAccessMode.ROOT,
                createdAtMillis = 1
            )
        )
        repository.upsertEntries(
            listOf(
                entry(rootId, "/proc/process", "process", 0, true, 0),
                entry(rootId, "/sys/kernel", "kernel", 0, true, 0),
                entry(rootId, "/storage/file.txt", "file.txt", 1, false, 0)
            )
        )

        assertEquals(
            2L,
            repository.deleteEntriesUnder(rootId, listOf("/proc", "/sys"))
        )
        assertEquals(1L, repository.getStatistics().entryCount)
        assertEquals(0L, repository.deleteEntriesUnder(rootId, emptyList()))
    }

    @Test
    fun removesDeletedSubtreesAcrossRootsWithoutMatchingSiblingPrefixes() = runBlocking {
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/",
                displayName = "Root",
                accessMode = IndexAccessMode.ROOT,
                createdAtMillis = 1
            )
        )
        val storageRootId = repository.upsertRoot(
            IndexRoot(
                path = "/storage/emulated/0",
                displayName = "Internal storage",
                accessMode = IndexAccessMode.STANDARD,
                createdAtMillis = 2
            )
        )
        repository.upsertEntries(
            listOf(
                entry(rootId, "/data/remove", "remove", 0, true, 0),
                entry(rootId, "/data/remove/file.txt", "file.txt", 4, false, 0),
                entry(rootId, "/data/remove-sibling.txt", "remove-sibling.txt", 5, false, 0),
                entry(
                    storageRootId,
                    "/storage/emulated/0/Download/remove.zip",
                    "remove.zip",
                    6,
                    false,
                    0
                ),
                entry(
                    storageRootId,
                    "/storage/emulated/0/Download/remaining.zip",
                    "remaining.zip",
                    7,
                    false,
                    0
                )
            )
        )

        assertEquals(
            3L,
            repository.deleteEntriesAtOrUnder(
                listOf("/data/remove", "/storage/emulated/0/Download/remove.zip")
            )
        )

        val statistics = repository.getStatistics()
        assertEquals(2L, statistics.entryCount)
        assertEquals(2L, statistics.fileCount)
        assertEquals(12L, statistics.totalFileSizeBytes)
        database.read { connection ->
            assertEquals(2L, connection.countRows("indexed_files_fts"))
            assertEquals(2L, connection.countRows("indexed_file_names_trigram_fts"))
        }
        assertEquals(0L, repository.deleteEntriesAtOrUnder(emptyList()))
    }

    private fun entry(
        rootId: Long,
        path: String,
        name: String,
        sizeBytes: Long,
        isDirectory: Boolean,
        generation: Long
    ) = IndexedFileRecord(
        rootId = rootId,
        path = path,
        parentPath = path.substringBeforeLast('/', ""),
        name = name,
        extension = name.substringAfterLast('.', "").ifEmpty { null },
        sizeBytes = sizeBytes,
        modifiedAtMillis = 20,
        indexedAtMillis = 30,
        isDirectory = isDirectory,
        deviceId = 1,
        inode = path.hashCode().toLong(),
        scanGeneration = generation
    )
}

private fun SQLiteConnection.countRows(table: String): Long {
    require(table == "indexed_files_fts" || table == "indexed_file_names_trigram_fts")
    return prepare("SELECT COUNT(*) FROM $table").use { statement ->
        check(statement.step())
        statement.getLong(0)
    }
}
