/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchQueryMode
import com.blitzfiles.search.domain.model.SearchRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexedRootDirectorySearchTest {
    @Test
    fun emptyCompletedWithErrorsRootSearchFinishesWithoutFilesystemFallback() = runBlocking {
        var request: SearchRequest? = null
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(readyRoot(IndexScanStatus.COMPLETED_WITH_ERRORS))
            },
            searchPage = {
                request = it
                SearchPage(emptyList(), nextOffset = null, totalCount = 0)
            }
        )
        val batches = mutableListOf<List<String>>()

        val usedIndex = search.trySearch(
            directoryPath = "/",
            query = "definitely-absent",
            onPathBatch = batches::add
        )

        assertTrue(usedIndex)
        assertTrue(batches.isEmpty())
        assertEquals(
            SearchRequest(
                query = "definitely-absent",
                queryMode = SearchQueryMode.LITERAL_SUBSTRING,
                rootIds = setOf(ROOT_ID),
                limit = SearchRequest.DEFAULT_LIMIT
            ),
            request
        )
    }

    @Test
    fun completedRootSearchStreamsEveryIndexedPage() = runBlocking {
        val requests = mutableListOf<SearchRequest>()
        val search = IndexedRootDirectorySearch(
            loadRoots = { listOf(readyRoot(IndexScanStatus.COMPLETED)) },
            searchPage = { request ->
                requests += request
                when (request.offset) {
                    0L -> SearchPage(
                        hits = listOf(hit("/"), hit("/first-result")),
                        nextOffset = SearchRequest.DEFAULT_LIMIT.toLong(),
                        totalCount = null
                    )
                    SearchRequest.DEFAULT_LIMIT.toLong() -> SearchPage(
                        hits = listOf(hit("/second-result")),
                        nextOffset = null,
                        totalCount = null
                    )
                    else -> error("Unexpected offset: ${request.offset}")
                }
            }
        )
        val batches = mutableListOf<List<String>>()

        val usedIndex = search.trySearch(
            directoryPath = "/",
            query = "result",
            onPathBatch = batches::add
        )

        assertTrue(usedIndex)
        assertEquals(
            listOf(listOf("/first-result"), listOf("/second-result")),
            batches
        )
        assertEquals(listOf(0L, SearchRequest.DEFAULT_LIMIT.toLong()), requests.map { it.offset })
        assertTrue(requests.all { it.rootIds == setOf(ROOT_ID) })
        assertTrue(requests.all { it.queryMode == SearchQueryMode.LITERAL_SUBSTRING })
    }

    @Test
    fun completedPartitionSearchIncludesEveryEnabledChildRoot() = runBlocking {
        var request: SearchRequest? = null
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(IndexScanStatus.COMPLETED),
                    readyRoot(
                        status = IndexScanStatus.COMPLETED_WITH_ERRORS,
                        id = STORAGE_ROOT_ID,
                        path = "/storage/emulated/0",
                        accessMode = IndexAccessMode.STANDARD,
                        generation = 4
                    ),
                    readyRoot(
                        status = IndexScanStatus.RUNNING,
                        id = DISABLED_ROOT_ID,
                        path = "/data/local",
                        isEnabled = false
                    )
                )
            },
            searchPage = {
                request = it
                SearchPage(
                    hits = listOf(
                        hit("/system-result"),
                        hit("/storage/emulated/0/storage-result", STORAGE_ROOT_ID)
                    ),
                    nextOffset = null,
                    totalCount = 2
                )
            }
        )
        val batches = mutableListOf<List<String>>()

        val usedIndex = search.trySearch(
            directoryPath = "/",
            query = "result",
            onPathBatch = batches::add
        )

        assertTrue(usedIndex)
        assertEquals(setOf(ROOT_ID, STORAGE_ROOT_ID), request?.rootIds)
        assertEquals(
            listOf(
                listOf(
                    "/system-result",
                    "/storage/emulated/0/storage-result"
                )
            ),
            batches
        )
    }

    @Test
    fun completedInternalStorageRootUsesItsIndexWithoutFilesystemFallback() = runBlocking {
        var request: SearchRequest? = null
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(IndexScanStatus.RUNNING),
                    readyRoot(
                        status = IndexScanStatus.COMPLETED,
                        id = STORAGE_ROOT_ID,
                        path = INTERNAL_STORAGE_PATH,
                        accessMode = IndexAccessMode.STANDARD,
                        generation = 4
                    )
                )
            },
            searchPage = {
                request = it
                SearchPage(
                    hits = listOf(
                        hit(INTERNAL_STORAGE_PATH, STORAGE_ROOT_ID),
                        hit("$INTERNAL_STORAGE_PATH/DCIM/photo.jpg", STORAGE_ROOT_ID)
                    ),
                    nextOffset = null,
                    totalCount = 2
                )
            }
        )
        val batches = mutableListOf<List<String>>()

        val usedIndex = search.trySearch(
            directoryPath = INTERNAL_STORAGE_PATH,
            query = "photo",
            onPathBatch = batches::add
        )

        assertTrue(usedIndex)
        assertEquals(setOf(STORAGE_ROOT_ID), request?.rootIds)
        assertEquals(
            listOf(listOf("$INTERNAL_STORAGE_PATH/DCIM/photo.jpg")),
            batches
        )
    }

    @Test
    fun internalStorageAliasesUseCanonicalConfiguredRoot() = runBlocking {
        val requests = mutableListOf<SearchRequest>()
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(
                        status = IndexScanStatus.COMPLETED,
                        id = STORAGE_ROOT_ID,
                        path = INTERNAL_STORAGE_PATH,
                        accessMode = IndexAccessMode.STANDARD
                    )
                )
            },
            searchPage = { request ->
                requests += request
                SearchPage(
                    hits = listOf(
                        hit(INTERNAL_STORAGE_PATH, STORAGE_ROOT_ID),
                        hit("$INTERNAL_STORAGE_PATH/DCIM/photo.jpg", STORAGE_ROOT_ID)
                    ),
                    nextOffset = null,
                    totalCount = 2
                )
            },
            canonicalPathResolver =
                AndroidIndexedDirectoryCanonicalPathResolver(INTERNAL_STORAGE_PATH)
        )

        for (aliasPath in listOf(SDCARD_ALIAS_PATH, SELF_PRIMARY_ALIAS_PATH)) {
            val batches = mutableListOf<List<String>>()

            val usedIndex = search.trySearch(
                directoryPath = aliasPath,
                query = "photo",
                onPathBatch = batches::add
            )

            assertTrue(usedIndex)
            assertEquals(
                listOf(listOf("$INTERNAL_STORAGE_PATH/DCIM/photo.jpg")),
                batches
            )
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { request -> request.rootIds == setOf(STORAGE_ROOT_ID) })
    }

    @Test
    fun completedNestedRootsAreIncludedInInternalStoragePartition() = runBlocking {
        var request: SearchRequest? = null
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(IndexScanStatus.RUNNING),
                    readyRoot(
                        status = IndexScanStatus.COMPLETED,
                        id = STORAGE_ROOT_ID,
                        path = INTERNAL_STORAGE_PATH,
                        accessMode = IndexAccessMode.STANDARD
                    ),
                    readyRoot(
                        status = IndexScanStatus.COMPLETED_WITH_ERRORS,
                        id = NESTED_ROOT_ID,
                        path = "$INTERNAL_STORAGE_PATH/Documents",
                        accessMode = IndexAccessMode.STANDARD
                    )
                )
            },
            searchPage = {
                request = it
                SearchPage(emptyList(), nextOffset = null, totalCount = 0)
            }
        )

        val usedIndex = search.trySearch(INTERNAL_STORAGE_PATH, "query") {}

        assertTrue(usedIndex)
        assertEquals(setOf(STORAGE_ROOT_ID, NESTED_ROOT_ID), request?.rootIds)
    }

    @Test
    fun unreadyNestedRootMakesInternalStoragePartitionNonAuthoritative() = runBlocking {
        var searchCalls = 0
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(
                        status = IndexScanStatus.COMPLETED,
                        id = STORAGE_ROOT_ID,
                        path = INTERNAL_STORAGE_PATH,
                        accessMode = IndexAccessMode.STANDARD
                    ),
                    readyRoot(
                        status = IndexScanStatus.RUNNING,
                        id = NESTED_ROOT_ID,
                        path = "$INTERNAL_STORAGE_PATH/Documents",
                        accessMode = IndexAccessMode.STANDARD
                    )
                )
            },
            searchPage = {
                ++searchCalls
                SearchPage(emptyList(), nextOffset = null, totalCount = 0)
            }
        )

        val usedIndex = search.trySearch(INTERNAL_STORAGE_PATH, "query") {
            error("An incomplete nested partition must not publish results")
        }

        assertFalse(usedIndex)
        assertEquals(0, searchCalls)
    }

    @Test
    fun runningInternalStorageRootFallsBackToLiveFilesystemSearch() = runBlocking {
        var searchCalls = 0
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(IndexScanStatus.COMPLETED),
                    readyRoot(
                        status = IndexScanStatus.RUNNING,
                        id = STORAGE_ROOT_ID,
                        path = INTERNAL_STORAGE_PATH
                    )
                )
            },
            searchPage = {
                ++searchCalls
                SearchPage(emptyList(), null, 0)
            }
        )

        val usedIndex = search.trySearch(INTERNAL_STORAGE_PATH, "query") {
            error("An incomplete storage index must not publish results")
        }

        assertFalse(usedIndex)
        assertEquals(0, searchCalls)
    }

    @Test
    fun unreadyEnabledChildMakesPartitionIndexNonAuthoritative() = runBlocking {
        var searchCalls = 0
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(IndexScanStatus.COMPLETED),
                    readyRoot(
                        status = IndexScanStatus.RUNNING,
                        id = STORAGE_ROOT_ID,
                        path = "/storage/emulated/0",
                        accessMode = IndexAccessMode.STANDARD
                    )
                )
            },
            searchPage = {
                ++searchCalls
                SearchPage(emptyList(), null, 0)
            }
        )

        val usedIndex = search.trySearch("/", "query") {
            error("An incomplete partition must not publish results")
        }

        assertFalse(usedIndex)
        assertEquals(0, searchCalls)
    }

    @Test
    fun childGenerationChangeStopsPaginationBeforeMixingPartitions() = runBlocking {
        var rootsLoadCount = 0
        val requests = mutableListOf<SearchRequest>()
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                ++rootsLoadCount
                listOf(
                    readyRoot(IndexScanStatus.COMPLETED),
                    readyRoot(
                        status = IndexScanStatus.COMPLETED,
                        id = STORAGE_ROOT_ID,
                        path = "/storage/emulated/0",
                        accessMode = IndexAccessMode.STANDARD,
                        generation = if (rootsLoadCount == 1) 3 else 4
                    )
                )
            },
            searchPage = { request ->
                requests += request
                SearchPage(
                    hits = listOf(hit("/first-result")),
                    nextOffset = SearchRequest.DEFAULT_LIMIT.toLong(),
                    totalCount = null
                )
            }
        )
        val batches = mutableListOf<List<String>>()

        val usedIndex = search.trySearch(
            directoryPath = "/",
            query = "result",
            onPathBatch = batches::add
        )

        assertTrue(usedIndex)
        assertEquals(listOf(listOf("/first-result")), batches)
        assertEquals(1, requests.size)
        assertEquals(setOf(ROOT_ID, STORAGE_ROOT_ID), requests.single().rootIds)
        assertEquals(2, rootsLoadCount)
    }

    @Test
    fun enabledPartitionMembershipChangeStopsPagination() = runBlocking {
        var rootsLoadCount = 0
        var searchCalls = 0
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                ++rootsLoadCount
                buildList {
                    add(readyRoot(IndexScanStatus.COMPLETED))
                    if (rootsLoadCount > 1) {
                        add(
                            readyRoot(
                                status = IndexScanStatus.COMPLETED,
                                id = STORAGE_ROOT_ID,
                                path = "/storage/emulated/0",
                                accessMode = IndexAccessMode.STANDARD
                            )
                        )
                    }
                }
            },
            searchPage = {
                ++searchCalls
                SearchPage(
                    hits = listOf(hit("/first-result")),
                    nextOffset = SearchRequest.DEFAULT_LIMIT.toLong(),
                    totalCount = null
                )
            }
        )

        val usedIndex = search.trySearch("/", "result") {}

        assertTrue(usedIndex)
        assertEquals(1, searchCalls)
        assertEquals(2, rootsLoadCount)
    }

    @Test
    fun boundedDirectorySearchReportsThatMoreIndexedResultsExist() = runBlocking {
        val requests = mutableListOf<SearchRequest>()
        var truncated = false
        val search = IndexedRootDirectorySearch(
            loadRoots = { listOf(readyRoot(IndexScanStatus.COMPLETED)) },
            searchPage = { request ->
                requests += request
                SearchPage(
                    hits = listOf(hit("/first")),
                    nextOffset = 1,
                    totalCount = null
                )
            }
        )
        val batches = mutableListOf<List<String>>()

        val usedIndex = search.trySearch(
            directoryPath = "/",
            query = "i",
            maxResults = 1,
            onTruncated = { truncated = true },
            onPathBatch = batches::add
        )

        assertTrue(usedIndex)
        assertTrue(truncated)
        assertEquals(listOf(listOf("/first")), batches)
        assertEquals(1, requests.single().limit)
    }

    @Test
    fun runningRootIndexFallsBackToLiveFilesystemSearch() = runBlocking {
        var searchCalls = 0
        val search = IndexedRootDirectorySearch(
            loadRoots = { listOf(readyRoot(IndexScanStatus.RUNNING)) },
            searchPage = {
                ++searchCalls
                SearchPage(emptyList(), null, 0)
            }
        )

        val usedIndex = search.trySearch("/", "query") {
            error("An incomplete index must not publish results")
        }

        assertFalse(usedIndex)
        assertEquals(0, searchCalls)
    }

    @Test
    fun standardAccessRootIndexFallsBackToLiveFilesystemSearch() = runBlocking {
        var searchCalls = 0
        val search = IndexedRootDirectorySearch(
            loadRoots = {
                listOf(
                    readyRoot(
                        status = IndexScanStatus.COMPLETED,
                        accessMode = IndexAccessMode.STANDARD
                    )
                )
            },
            searchPage = {
                ++searchCalls
                SearchPage(emptyList(), nextOffset = null, totalCount = 0)
            }
        )

        val usedIndex = search.trySearch("/", "query") {
            error("A standard-access '/' index must not publish root-wide results")
        }

        assertFalse(usedIndex)
        assertEquals(0, searchCalls)
    }

    @Test
    fun invalidOverlongQueryFailsBeforeAnyFilesystemFallbackCanBeRequested() {
        var searchCalls = 0
        val search = IndexedRootDirectorySearch(
            loadRoots = { listOf(readyRoot(IndexScanStatus.COMPLETED)) },
            searchPage = {
                ++searchCalls
                SearchPage(emptyList(), null, 0)
            }
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                search.trySearch(
                    directoryPath = "/",
                    query = "x".repeat(SearchRequest.MAX_QUERY_LENGTH + 1),
                    onPathBatch = {}
                )
            }
        }
        assertEquals(0, searchCalls)
    }

    @Test
    fun arbitrarySubdirectoryWithoutAnExactConfiguredRootUsesRegularDirectorySearch() =
        runBlocking {
            var rootsLoads = 0
            val search = IndexedRootDirectorySearch(
                loadRoots = {
                    ++rootsLoads
                    listOf(
                        readyRoot(
                            status = IndexScanStatus.COMPLETED,
                            id = STORAGE_ROOT_ID,
                            path = INTERNAL_STORAGE_PATH,
                            accessMode = IndexAccessMode.STANDARD
                        )
                    )
                },
                searchPage = {
                    error("An unconfigured directory must use regular directory search")
                }
            )

            val usedIndex = search.trySearch("$INTERNAL_STORAGE_PATH/DCIM", "query") {
                error("An unconfigured directory must not publish root-wide results")
            }

            assertFalse(usedIndex)
            assertEquals(1, rootsLoads)
        }

    private fun readyRoot(
        status: IndexScanStatus,
        id: Long = ROOT_ID,
        path: String = "/",
        accessMode: IndexAccessMode = IndexAccessMode.ROOT,
        isEnabled: Boolean = true,
        generation: Long = 1
    ): IndexRoot =
        IndexRoot(
            id = id,
            path = path,
            displayName = path,
            accessMode = accessMode,
            isEnabled = isEnabled,
            createdAtMillis = 1,
            lastScanCompletedAtMillis = if (
                status == IndexScanStatus.COMPLETED ||
                status == IndexScanStatus.COMPLETED_WITH_ERRORS
            ) {
                2
            } else {
                null
            },
            lastScanStatus = status,
            scanGeneration = generation
        )

    private fun hit(path: String, rootId: Long = ROOT_ID): SearchHit =
        SearchHit(
            entry = IndexedFileRecord(
                id = path.hashCode().toLong().let { if (it > 0) it else 1 },
                rootId = rootId,
                path = path,
                parentPath = "/",
                name = path.substringAfterLast('/').ifEmpty { path },
                sizeBytes = 0,
                modifiedAtMillis = 0,
                indexedAtMillis = 1,
                isDirectory = false,
                requiresRoot = rootId == ROOT_ID,
                scanGeneration = 1
            ),
            relevance = 1.0
        )

    private companion object {
        const val ROOT_ID = 7L
        const val STORAGE_ROOT_ID = 8L
        const val DISABLED_ROOT_ID = 9L
        const val NESTED_ROOT_ID = 10L
        const val INTERNAL_STORAGE_PATH = "/storage/emulated/0"
        const val SDCARD_ALIAS_PATH = "/sdcard"
        const val SELF_PRIMARY_ALIAS_PATH = "/storage/self/primary"
    }
}
