/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchQueryMode
import com.blitzfiles.search.domain.model.SearchRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Uses the persistent filename index for searches started from a configured index root directory.
 *
 * Material Files' original directory search recursively walks the live filesystem. That is a
 * useful fallback for unindexed locations, but a negative recursive search cannot finish until
 * every readable directory has been visited. Once a configured root scan has completed, its index
 * is the authoritative and substantially faster source for this Everything-style search.
 */
internal class IndexedRootDirectorySearch(
    private val loadRoots: suspend () -> List<IndexRoot>,
    private val searchPage: suspend (SearchRequest) -> SearchPage,
    private val canonicalPathResolver: IndexedDirectoryCanonicalPathResolver =
        IndexedDirectoryCanonicalPathResolver { path -> path }
) {
    /**
     * @return `true` when the index was authoritative and the live filesystem must not be walked.
     */
    suspend fun trySearch(
        directoryPath: String,
        query: String,
        maxResults: Int = Int.MAX_VALUE,
        onTruncated: () -> Unit = {},
        onPathBatch: (List<String>) -> Unit
    ): Boolean {
        if (query.isBlank()) {
            return false
        }
        require(maxResults > 0) { "Maximum result count must be positive" }
        val canonicalDirectoryPath = resolveCanonicalPathOrNull(directoryPath) ?: return false
        val partition = loadRoots()
            .readyRootPartitionOrNull(canonicalDirectoryPath)
            ?: return false

        var offset = 0L
        var examinedResultCount = 0
        val emittedPaths = HashSet<String>()
        do {
            currentCoroutineContext().ensureActive()
            val pageLimit = minOf(
                SearchRequest.DEFAULT_LIMIT,
                maxResults - examinedResultCount
            )
            val page = searchPage(
                SearchRequest(
                    query = query,
                    queryMode = SearchQueryMode.LITERAL_SUBSTRING,
                    rootIds = partition.rootIds,
                    limit = pageLimit,
                    offset = offset
                )
            )
            val examinedHits = page.hits.take(pageLimit)
            examinedResultCount += examinedHits.size
            val paths = examinedHits
                .map { hit -> hit.entry.path }
                // The original directory search excludes the directory being searched.
                .filterNot { path ->
                    path == canonicalDirectoryPath || path == partition.configuredDirectoryPath
                }
                .filter(emittedPaths::add)
            if (paths.isNotEmpty()) {
                onPathBatch(paths)
            }
            val nextOffset = page.nextOffset ?: break
            if (examinedResultCount >= maxResults) {
                onTruncated()
                break
            }
            check(nextOffset > offset) {
                "Indexed root search returned a non-increasing offset: $nextOffset"
            }
            val currentPartition = loadRoots()
                .readyRootPartitionOrNull(canonicalDirectoryPath)
            if (currentPartition != partition) {
                // Do not combine OFFSET pages from different indexing generations.
                break
            }
            offset = nextOffset
        } while (true)
        return true
    }

    /**
     * An index root can be partitioned into itself and enabled child roots. A directory query is
     * authoritative only when the directory exactly matches a configured root and every enabled
     * partition below it is ready.
     */
    private fun List<IndexRoot>.readyRootPartitionOrNull(
        canonicalDirectoryPath: String
    ): ReadyRootPartition? {
        val canonicalRoots = filter(IndexRoot::isEnabled).map { root ->
            CanonicalRoot(
                root = root,
                path = resolveCanonicalPathOrNull(root.path) ?: return null
            )
        }
        val directoryRoot = canonicalRoots
            .filter { root -> root.path == canonicalDirectoryPath }
            .singleOrNull()
            ?.root
            ?: return null
        if (
            canonicalDirectoryPath == ROOT_PATH &&
            directoryRoot.accessMode != IndexAccessMode.ROOT
        ) {
            return null
        }
        if (!directoryRoot.isReadyIndex()) {
            return null
        }

        val partitionRoots = canonicalRoots.filter { root ->
            root.path.isSameOrDescendantOf(canonicalDirectoryPath)
        }
        if (partitionRoots.any { root -> !root.root.isReadyIndex() }) {
            return null
        }
        val generationSignature = partitionRoots
            .map { root ->
                RootGeneration(
                    rootId = checkNotNull(root.root.id),
                    scanGeneration = root.root.scanGeneration
                )
            }
            .sortedBy(RootGeneration::rootId)
        if (generationSignature.map(RootGeneration::rootId).distinct().size !=
            generationSignature.size) {
            return null
        }
        return ReadyRootPartition(
            rootIds = generationSignature.mapTo(linkedSetOf(), RootGeneration::rootId),
            generationSignature = generationSignature,
            configuredDirectoryPath = directoryRoot.path
        )
    }

    private fun resolveCanonicalPathOrNull(path: String): String? =
        try {
            canonicalPathResolver.resolve(path)
                ?.takeIf { resolvedPath -> resolvedPath.startsWith(ROOT_PATH) }
        } catch (_: RuntimeException) {
            // Resolver failures must preserve the legacy filesystem-search fallback.
            null
        }

    private fun IndexRoot.isReadyIndex(): Boolean =
        id != null &&
            isEnabled &&
            lastScanCompletedAtMillis != null &&
            (
                lastScanStatus == IndexScanStatus.COMPLETED ||
                    lastScanStatus == IndexScanStatus.COMPLETED_WITH_ERRORS
                )

    private fun String.isSameOrDescendantOf(ancestor: String): Boolean =
        this == ancestor || when (ancestor) {
            ROOT_PATH -> startsWith(ROOT_PATH)
            else -> startsWith(ancestor) && getOrNull(ancestor.length) == '/'
        }

    private data class ReadyRootPartition(
        val rootIds: Set<Long>,
        val generationSignature: List<RootGeneration>,
        val configuredDirectoryPath: String
    )

    private data class CanonicalRoot(
        val root: IndexRoot,
        val path: String
    )

    private data class RootGeneration(
        val rootId: Long,
        val scanGeneration: Long
    )

    private companion object {
        const val ROOT_PATH = "/"
    }
}
