/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.indexer

import androidx.sqlite.SQLiteConnection
import com.blitzfiles.search.data.database.IndexDatabase
import com.blitzfiles.search.data.repository.SQLiteIndexRepository
import com.blitzfiles.search.domain.indexer.IndexFileMetadata
import com.blitzfiles.search.domain.indexer.IndexFileSystem
import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexExclusion
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.IndexingMode
import com.blitzfiles.search.domain.model.IndexingRequest
import com.blitzfiles.search.domain.model.IndexingState
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultFileIndexerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: IndexDatabase
    private lateinit var repository: SQLiteIndexRepository
    private lateinit var root: IndexRoot

    @Before
    fun setUp() = runBlocking {
        database = IndexDatabase(
            File(temporaryFolder.newFolder("database"), "index.db")
        )
        repository = SQLiteIndexRepository(database)
        val rootId = repository.upsertRoot(
            IndexRoot(
                path = "/root",
                displayName = "Root",
                accessMode = IndexAccessMode.STANDARD,
                includeHidden = false,
                followSymbolicLinks = true,
                createdAtMillis = 1
            )
        )
        root = repository.getRoots().single { it.id == rootId }
    }

    @After
    fun tearDown() = runBlocking {
        repository.close()
    }

    @Test
    fun fullScanHonorsExclusionsAndHiddenFilesAndStopsSymbolicLinkCycles() = runBlocking {
        repository.upsertExclusion(
            IndexExclusion(rootId = root.id, pathPrefix = "/root/cache")
        )
        repository.upsertEntries(listOf(record("/root/ghost", generation = 0)))
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/file.txt" to metadata("/root/file.txt", inode = 2),
                "/root/.secret" to metadata("/root/.secret", inode = 3, hidden = true),
                "/root/cache" to metadata("/root/cache", directory = true, inode = 4),
                "/root/loop" to metadata(
                    "/root/loop",
                    directory = true,
                    inode = 1,
                    symbolicLink = true
                )
            ),
            children = mapOf(
                "/root" to listOf(
                    "/root/file.txt",
                    "/root/.secret",
                    "/root/cache",
                    "/root/loop"
                ),
                "/root/loop" to listOf("/root")
            )
        )

        val result = DefaultFileIndexer(repository, fileSystem, batchSize = 2).run(
            IndexingRequest(setOf(root.id!!), IndexingMode.FULL)
        )

        assertEquals(setOf("/root", "/root/file.txt", "/root/loop"), indexedPaths())
        assertEquals(3L, result.indexedEntryCount)
        assertEquals(0L, result.recoverableErrorCount)
        assertTrue(result.skippedEntryCount >= 3)
        assertEquals(IndexScanStatus.COMPLETED, repository.getRoots().single().lastScanStatus)
    }

    @Test
    fun symbolicLinkWithoutReadableTargetIsStillIndexed() = runBlocking {
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/link" to metadata(
                    "/root/link",
                    inode = 2,
                    symbolicLink = true
                ).copy(symbolicLinkTarget = null)
            ),
            children = mapOf("/root" to listOf("/root/link"))
        )

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(root.id!!), IndexingMode.FULL)
        )

        assertEquals(setOf("/root", "/root/link"), indexedPaths())
        assertEquals(0L, result.recoverableErrorCount)
        assertEquals(IndexScanStatus.COMPLETED, repository.getRoots().single().lastScanStatus)
    }

    @Test
    fun followedSymbolicLinkCannotBypassProtectedPathExclusions() = runBlocking {
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/processes" to metadata(
                    "/root/processes",
                    directory = true,
                    inode = 2,
                    symbolicLink = true
                ).copy(symbolicLinkTarget = "/proc"),
                "/root/processes/1" to metadata(
                    "/root/processes/1",
                    directory = true,
                    inode = 3
                )
            ),
            children = mapOf(
                "/root" to listOf("/root/processes"),
                "/root/processes" to listOf("/root/processes/1")
            )
        )

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(root.id!!), IndexingMode.FULL)
        )

        assertEquals(setOf("/root", "/root/processes"), indexedPaths())
        assertEquals(1L, result.skippedEntryCount)
        assertEquals(0L, result.recoverableErrorCount)
        assertEquals(IndexScanStatus.COMPLETED, repository.getRoots().single().lastScanStatus)
    }

    @Test
    fun fullRootScanAlwaysSkipsVirtualSystemTrees() = runBlocking {
        val filesystemRootId = repository.upsertRoot(
            IndexRoot(
                path = "/",
                displayName = "Filesystem",
                accessMode = IndexAccessMode.ROOT,
                createdAtMillis = 1
            )
        )
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/" to metadata("/", directory = true, inode = 10),
                "/data" to metadata("/data", directory = true, inode = 11),
                "/data/file.txt" to metadata("/data/file.txt", inode = 12),
                "/data/media" to metadata("/data/media", directory = true, inode = 15),
                "/data/media/photo.jpg" to metadata("/data/media/photo.jpg", inode = 16),
                "/data_mirror" to metadata("/data_mirror", directory = true, inode = 17),
                "/data_mirror/data.txt" to metadata("/data_mirror/data.txt", inode = 18),
                "/proc" to metadata("/proc", directory = true, inode = 13),
                "/proc/1" to metadata("/proc/1", directory = true, inode = 14),
                "/storage" to metadata("/storage", directory = true, inode = 19),
                "/storage/photo.jpg" to metadata("/storage/photo.jpg", inode = 20)
            ),
            children = mapOf(
                "/" to listOf("/data", "/data_mirror", "/proc", "/storage"),
                "/data" to listOf("/data/file.txt", "/data/media"),
                "/data/media" to listOf("/data/media/photo.jpg"),
                "/data_mirror" to listOf("/data_mirror/data.txt"),
                "/proc" to listOf("/proc/1"),
                "/storage" to listOf("/storage/photo.jpg")
            )
        )

        DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(filesystemRootId), IndexingMode.FULL)
        )

        assertEquals(
            setOf("/", "/data", "/data/file.txt", "/storage", "/storage/photo.jpg"),
            indexedPaths(filesystemRootId)
        )
    }

    @Test
    fun overlappingRootsAssignEveryPathToTheDeepestEnabledRoot() = runBlocking {
        val filesystemRootId = repository.upsertRoot(
            IndexRoot(
                path = "/",
                displayName = "Filesystem",
                accessMode = IndexAccessMode.ROOT,
                createdAtMillis = 1
            )
        )
        val storageRootId = repository.upsertRoot(
            IndexRoot(
                path = "/storage/emulated/0",
                displayName = "Internal storage",
                accessMode = IndexAccessMode.ROOT,
                createdAtMillis = 1
            )
        )
        repository.upsertEntries(
            listOf(
                record("/storage/emulated/0/stale.txt", generation = 0).copy(
                    rootId = filesystemRootId,
                    requiresRoot = true
                )
            )
        )
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/" to metadata("/", directory = true, inode = 10),
                "/system" to metadata("/system", directory = true, inode = 11),
                "/system/build.prop" to metadata("/system/build.prop", inode = 12),
                "/storage" to metadata("/storage", directory = true, inode = 13),
                "/storage/emulated" to metadata(
                    "/storage/emulated",
                    directory = true,
                    inode = 14
                ),
                "/storage/emulated/0" to metadata(
                    "/storage/emulated/0",
                    directory = true,
                    inode = 15
                ),
                "/storage/emulated/0/DCIM" to metadata(
                    "/storage/emulated/0/DCIM",
                    directory = true,
                    inode = 16
                ),
                "/storage/emulated/0/DCIM/photo.jpg" to metadata(
                    "/storage/emulated/0/DCIM/photo.jpg",
                    inode = 17
                )
            ),
            children = mapOf(
                "/" to listOf("/system", "/storage"),
                "/system" to listOf("/system/build.prop"),
                "/storage" to listOf("/storage/emulated"),
                "/storage/emulated" to listOf("/storage/emulated/0"),
                "/storage/emulated/0" to listOf("/storage/emulated/0/DCIM"),
                "/storage/emulated/0/DCIM" to listOf(
                    "/storage/emulated/0/DCIM/photo.jpg"
                )
            )
        )

        DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(
                linkedSetOf(storageRootId, filesystemRootId),
                IndexingMode.FULL
            )
        )

        assertEquals(
            setOf("/", "/storage", "/storage/emulated", "/system", "/system/build.prop"),
            indexedPaths(filesystemRootId)
        )
        assertEquals(
            setOf(
                "/storage/emulated/0",
                "/storage/emulated/0/DCIM",
                "/storage/emulated/0/DCIM/photo.jpg"
            ),
            indexedPaths(storageRootId)
        )
        assertEquals(
            mapOf(
                "/" to IndexedOwnership(filesystemRootId, requiresRoot = true),
                "/storage" to IndexedOwnership(filesystemRootId, requiresRoot = true),
                "/storage/emulated" to IndexedOwnership(
                    filesystemRootId,
                    requiresRoot = true
                ),
                "/system" to IndexedOwnership(filesystemRootId, requiresRoot = true),
                "/system/build.prop" to IndexedOwnership(
                    filesystemRootId,
                    requiresRoot = true
                ),
                "/storage/emulated/0" to IndexedOwnership(
                    storageRootId,
                    requiresRoot = true
                ),
                "/storage/emulated/0/DCIM" to IndexedOwnership(
                    storageRootId,
                    requiresRoot = true
                ),
                "/storage/emulated/0/DCIM/photo.jpg" to IndexedOwnership(
                    storageRootId,
                    requiresRoot = true
                )
            ),
            indexedOwnership()
        )
    }

    @Test
    fun disabledDescendantRootDoesNotCarveAGapInItsEnabledAncestor() = runBlocking {
        val filesystemRootId = repository.upsertRoot(
            IndexRoot(
                path = "/",
                displayName = "Filesystem",
                accessMode = IndexAccessMode.ROOT,
                createdAtMillis = 1
            )
        )
        val disabledStorageRootId = repository.upsertRoot(
            IndexRoot(
                path = "/storage/emulated/0",
                displayName = "Internal storage",
                accessMode = IndexAccessMode.STANDARD,
                isEnabled = false,
                createdAtMillis = 1
            )
        )
        repository.upsertEntries(
            listOf(
                record("/storage/emulated/0/file.txt", generation = 0).copy(
                    rootId = disabledStorageRootId
                )
            )
        )
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/" to metadata("/", directory = true, inode = 20),
                "/storage" to metadata("/storage", directory = true, inode = 21),
                "/storage/emulated" to metadata(
                    "/storage/emulated",
                    directory = true,
                    inode = 22
                ),
                "/storage/emulated/0" to metadata(
                    "/storage/emulated/0",
                    directory = true,
                    inode = 23
                ),
                "/storage/emulated/0/file.txt" to metadata(
                    "/storage/emulated/0/file.txt",
                    inode = 24
                )
            ),
            children = mapOf(
                "/" to listOf("/storage"),
                "/storage" to listOf("/storage/emulated"),
                "/storage/emulated" to listOf("/storage/emulated/0"),
                "/storage/emulated/0" to listOf("/storage/emulated/0/file.txt")
            )
        )

        DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(filesystemRootId), IndexingMode.FULL)
        )

        assertEquals(
            setOf(
                "/",
                "/storage",
                "/storage/emulated",
                "/storage/emulated/0",
                "/storage/emulated/0/file.txt"
            ),
            indexedPaths(filesystemRootId)
        )
        assertTrue(indexedPaths(disabledStorageRootId).isEmpty())
    }

    @Test
    fun bindMountAliasesAreNotTraversedTwiceWhenSymbolicLinksAreNotFollowed() = runBlocking {
        repository.upsertRoot(root.copy(followSymbolicLinks = false))
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/canonical" to metadata("/root/canonical", directory = true, inode = 2),
                "/root/canonical/file.txt" to metadata("/root/canonical/file.txt", inode = 3),
                "/root/alias" to metadata("/root/alias", directory = true, inode = 2),
                "/root/alias/file.txt" to metadata("/root/alias/file.txt", inode = 3)
            ),
            children = mapOf(
                // The traversal is LIFO, so canonical is deliberately visited before alias.
                "/root" to listOf("/root/alias", "/root/canonical"),
                "/root/canonical" to listOf("/root/canonical/file.txt"),
                "/root/alias" to listOf("/root/alias/file.txt")
            )
        )

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(root.id!!), IndexingMode.FULL)
        )

        assertEquals(
            setOf("/root", "/root/alias", "/root/canonical", "/root/canonical/file.txt"),
            indexedPaths()
        )
        assertEquals(4L, result.indexedEntryCount)
        assertEquals(1L, result.skippedEntryCount)
    }

    @Test
    fun inaccessibleBindMountAliasDoesNotBlockAnAccessibleAlias() = runBlocking {
        repository.upsertRoot(root.copy(followSymbolicLinks = false))
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/canonical" to metadata("/root/canonical", directory = true, inode = 2),
                "/root/canonical/file.txt" to metadata("/root/canonical/file.txt", inode = 3),
                "/root/alias" to metadata("/root/alias", directory = true, inode = 2)
            ),
            children = mapOf(
                // Traversal is LIFO, so the failing alias is deliberately attempted first.
                "/root" to listOf("/root/canonical", "/root/alias"),
                "/root/canonical" to listOf("/root/canonical/file.txt")
            ),
            unreadableDirectories = setOf("/root/alias")
        )

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(root.id!!), IndexingMode.FULL)
        )

        assertEquals(
            setOf("/root", "/root/alias", "/root/canonical", "/root/canonical/file.txt"),
            indexedPaths()
        )
        assertEquals(1L, result.recoverableErrorCount)
        assertEquals(
            IndexScanStatus.COMPLETED_WITH_ERRORS,
            repository.getRoots().single().lastScanStatus
        )
    }

    @Test
    fun ancestorExclusionBlocksAndClearsNestedRoot() = runBlocking {
        repository.upsertExclusion(IndexExclusion(pathPrefix = "/"))
        repository.upsertEntries(listOf(record("/root/old.txt", generation = 0)))
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf("/root" to metadata("/root", directory = true, inode = 1)),
            children = emptyMap()
        )

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(root.id!!), IndexingMode.FULL)
        )

        assertTrue(indexedPaths().isEmpty())
        assertEquals(1L, result.removedEntryCount)
        assertEquals(1L, result.skippedEntryCount)
    }

    @Test
    fun targetedIncrementalScanDeletesOnlyTheMissingChangedSubtree() = runBlocking {
        repository.upsertEntries(
            listOf(
                record("/root/remove/old.txt", generation = 0),
                record("/root/keep.txt", generation = 0)
            )
        )
        val fileSystem = FakeIndexFileSystem(emptyMap(), emptyMap())

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(
                rootIds = setOf(root.id!!),
                mode = IndexingMode.INCREMENTAL,
                pathHints = mapOf(root.id!! to setOf("/root/remove"))
            )
        )

        assertEquals(setOf("/root/keep.txt"), indexedPaths())
        assertEquals(1L, result.removedEntryCount)
        assertEquals(IndexScanStatus.COMPLETED, repository.getRoots().single().lastScanStatus)
    }

    @Test
    fun targetedIncrementalScanTreatsDeletedConfiguredRootAsSuccessfulCleanup() = runBlocking {
        repository.upsertEntries(
            listOf(
                record("/root", generation = 0),
                record("/root/old.txt", generation = 0)
            )
        )
        val fileSystem = FakeIndexFileSystem(emptyMap(), emptyMap())

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(
                rootIds = setOf(root.id!!),
                mode = IndexingMode.INCREMENTAL,
                pathHints = mapOf(root.id!! to setOf("/root")),
                treatMissingRootsAsDeleted = true
            )
        )

        assertTrue(indexedPaths().isEmpty())
        assertEquals(2L, result.removedEntryCount)
        assertEquals(0L, result.recoverableErrorCount)
        assertEquals(IndexScanStatus.COMPLETED, repository.getRoots().single().lastScanStatus)
    }

    @Test
    fun ordinaryIncrementalScanPreservesTemporarilyMissingConfiguredRoot() = runBlocking {
        repository.upsertEntries(listOf(record("/root/old.txt", generation = 0)))
        val fileSystem = FakeIndexFileSystem(emptyMap(), emptyMap())

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(
                rootIds = setOf(root.id!!),
                mode = IndexingMode.INCREMENTAL,
                pathHints = mapOf(root.id!! to setOf("/root"))
            )
        )

        assertEquals(setOf("/root/old.txt"), indexedPaths())
        assertEquals(0L, result.removedEntryCount)
        assertEquals(1L, result.recoverableErrorCount)
        assertEquals(
            IndexScanStatus.COMPLETED_WITH_ERRORS,
            repository.getRoots().single().lastScanStatus
        )
    }

    @Test
    fun targetedIncrementalAccessFailurePreservesExistingSubtree() = runBlocking {
        repository.upsertEntries(
            listOf(record("/root/protected/old.txt", generation = 0))
        )
        val fileSystem = FakeIndexFileSystem(
            metadata = emptyMap(),
            children = emptyMap(),
            unreadableEntries = setOf("/root/protected")
        )

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(
                rootIds = setOf(root.id!!),
                mode = IndexingMode.INCREMENTAL,
                pathHints = mapOf(root.id!! to setOf("/root/protected"))
            )
        )

        assertTrue("/root/protected/old.txt" in indexedPaths())
        assertEquals(0L, result.removedEntryCount)
        assertEquals(1L, result.recoverableErrorCount)
        assertEquals(
            IndexScanStatus.COMPLETED_WITH_ERRORS,
            repository.getRoots().single().lastScanStatus
        )
        assertTrue(repository.getRoots().single().lastScanError!!.contains("/root/protected"))
    }

    @Test
    fun accessFailurePreservesPreviouslyIndexedSubtree() = runBlocking {
        repository.upsertEntries(listOf(record("/root/protected/old.txt", generation = 0)))
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/protected" to metadata("/root/protected", directory = true, inode = 2)
            ),
            children = mapOf("/root" to listOf("/root/protected")),
            unreadableDirectories = setOf("/root/protected")
        )

        val result = DefaultFileIndexer(repository, fileSystem).run(
            IndexingRequest(setOf(root.id!!), IndexingMode.FULL)
        )

        assertTrue("/root/protected/old.txt" in indexedPaths())
        assertEquals(1L, result.recoverableErrorCount)
        assertEquals(
            IndexScanStatus.COMPLETED_WITH_ERRORS,
            repository.getRoots().single().lastScanStatus
        )
    }

    @Test
    fun pauseAndResumeAreObservableAndScanCompletes() = runBlocking {
        val enteredDirectory = CountDownLatch(1)
        val releaseDirectory = CountDownLatch(1)
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/file.txt" to metadata("/root/file.txt", inode = 2)
            ),
            children = mapOf("/root" to listOf("/root/file.txt")),
            beforeListing = {
                enteredDirectory.countDown()
                check(releaseDirectory.await(30, TimeUnit.SECONDS))
            }
        )
        val indexer = DefaultFileIndexer(repository, fileSystem)
        val indexingJob: Job = launch(Dispatchers.Default) {
            indexer.run(IndexingRequest(setOf(root.id!!), IndexingMode.FULL))
        }
        assertTrue(enteredDirectory.await(10, TimeUnit.SECONDS))

        indexer.pause()
        assertTrue(indexer.state.value is IndexingState.Paused)
        releaseDirectory.countDown()
        indexer.resume()
        indexingJob.join()

        assertTrue(indexer.state.value is IndexingState.Completed)
        assertEquals(IndexScanStatus.COMPLETED, repository.getRoots().single().lastScanStatus)
    }

    @Test
    fun cancelStopsTraversalAndPersistsCancelledState() = runBlocking {
        val enteredDirectory = CountDownLatch(1)
        val releaseDirectory = CountDownLatch(1)
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/file.txt" to metadata("/root/file.txt", inode = 2)
            ),
            children = mapOf("/root" to listOf("/root/file.txt")),
            beforeListing = {
                enteredDirectory.countDown()
                check(releaseDirectory.await(30, TimeUnit.SECONDS))
            }
        )
        val indexer = DefaultFileIndexer(repository, fileSystem)
        val indexingJob = launch(Dispatchers.Default) {
            try {
                indexer.run(IndexingRequest(setOf(root.id!!), IndexingMode.FULL))
            } catch (_: CancellationException) {
                // Expected.
            }
        }
        assertTrue(enteredDirectory.await(10, TimeUnit.SECONDS))

        indexer.cancel()
        releaseDirectory.countDown()
        indexingJob.join()

        assertTrue(indexer.state.value is IndexingState.Cancelled)
        assertEquals(IndexScanStatus.CANCELLED, repository.getRoots().single().lastScanStatus)
        assertFalse("/root/file.txt" in indexedPaths())
    }

    @Test
    fun revokedRuntimeAccessStopsTraversalAndPersistsFailedState() = runBlocking {
        var hasRequiredStorageAccess = true
        val metadataReads = mutableListOf<String>()
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf(
                "/root" to metadata("/root", directory = true, inode = 1),
                "/root/file.txt" to metadata("/root/file.txt", inode = 2)
            ),
            children = mapOf("/root" to listOf("/root/file.txt")),
            beforeListing = {
                hasRequiredStorageAccess = false
            },
            beforeMetadataRead = metadataReads::add
        )
        val indexer = DefaultFileIndexer(
            repository = repository,
            fileSystem = fileSystem,
            canContinue = { hasRequiredStorageAccess }
        )

        val error = runCatching {
            indexer.run(IndexingRequest(setOf(root.id!!), IndexingMode.FULL))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(listOf("/root"), metadataReads)
        assertTrue(indexedPaths().isEmpty())
        assertTrue(indexer.state.value is IndexingState.Failed)
        assertEquals(IndexScanStatus.FAILED, repository.getRoots().single().lastScanStatus)
    }

    @Test
    fun unsupportedMandatoryFileSystemOperationFailsInsteadOfSkippingEntries() = runBlocking {
        val fileSystem = FakeIndexFileSystem(
            metadata = mapOf("/root" to metadata("/root", directory = true, inode = 1)),
            children = emptyMap(),
            unsupportedEntries = setOf("/root")
        )
        val indexer = DefaultFileIndexer(repository, fileSystem)

        try {
            indexer.run(IndexingRequest(setOf(root.id!!), IndexingMode.FULL))
            throw AssertionError("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Required filesystem capabilities must not be reported as transient access errors.
        }

        assertTrue(indexer.state.value is IndexingState.Failed)
        assertEquals(IndexScanStatus.FAILED, repository.getRoots().single().lastScanStatus)
    }

    private suspend fun indexedPaths(): Set<String> =
        indexedPaths(root.id!!)

    private suspend fun indexedPaths(rootId: Long): Set<String> =
        database.read { connection ->
            connection.prepare(
                "SELECT path FROM indexed_files WHERE root_id = ? ORDER BY path"
            ).use { statement ->
                statement.bindLong(1, rootId)
                buildSet {
                    while (statement.step()) {
                        add(statement.getText(0))
                    }
                }
            }
        }

    private suspend fun indexedOwnership(): Map<String, IndexedOwnership> =
        database.read { connection ->
            connection.prepare(
                """
                SELECT path, root_id, requires_root
                FROM indexed_files
                ORDER BY path
                """.trimIndent()
            ).use { statement ->
                buildMap {
                    while (statement.step()) {
                        put(
                            statement.getText(0),
                            IndexedOwnership(
                                rootId = statement.getLong(1),
                                requiresRoot = statement.getLong(2) != 0L
                            )
                        )
                    }
                }
            }
        }

    private fun record(path: String, generation: Long) =
        IndexedFileRecord(
            rootId = root.id!!,
            path = path,
            parentPath = path.substringBeforeLast('/', ""),
            name = path.substringAfterLast('/'),
            sizeBytes = 1,
            modifiedAtMillis = 1,
            indexedAtMillis = 1,
            isDirectory = false,
            scanGeneration = generation
        )
}

private data class IndexedOwnership(
    val rootId: Long,
    val requiresRoot: Boolean
)

private class FakeIndexFileSystem(
    private val metadata: Map<String, IndexFileMetadata>,
    private val children: Map<String, List<String>>,
    private val unreadableEntries: Set<String> = emptySet(),
    private val unreadableDirectories: Set<String> = emptySet(),
    private val unsupportedEntries: Set<String> = emptySet(),
    private val beforeListing: (() -> Unit)? = null,
    private val beforeMetadataRead: ((String) -> Unit)? = null
) : IndexFileSystem {
    override fun normalize(path: String, accessMode: IndexAccessMode): String =
        if (path.length > 1) path.trimEnd('/') else path

    override fun readMetadata(
        path: String,
        accessMode: IndexAccessMode,
        followSymbolicLinks: Boolean
    ): IndexFileMetadata? {
        beforeMetadataRead?.invoke(path)
        if (path in unsupportedEntries) {
            throw UnsupportedOperationException("Required metadata is unsupported")
        }
        if (path in unreadableEntries) {
            throw IOException("Access denied")
        }
        return metadata[path]
    }

    override fun visitChildren(
        directoryPath: String,
        accessMode: IndexAccessMode,
        visitor: (String) -> Unit
    ) {
        beforeListing?.invoke()
        if (directoryPath in unreadableDirectories) {
            throw IOException("Access denied")
        }
        children[directoryPath].orEmpty().forEach(visitor)
    }
}

private fun metadata(
    path: String,
    directory: Boolean = false,
    inode: Long?,
    hidden: Boolean = false,
    symbolicLink: Boolean = false
) = IndexFileMetadata(
    path = path,
    parentPath = path.substringBeforeLast('/', ""),
    name = path.substringAfterLast('/').ifEmpty { "/" },
    extension = null,
    mimeType = null,
    sizeBytes = if (directory) 0 else 1,
    modifiedAtMillis = 1,
    createdAtMillis = 1,
    isDirectory = directory,
    isSymbolicLink = symbolicLink,
    isHidden = hidden,
    symbolicLinkTarget = if (symbolicLink) "/root" else null,
    deviceId = 1,
    inode = inode
)
