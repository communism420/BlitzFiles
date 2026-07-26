/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.app.filejob.DeletedPathPrefixes
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchPage
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSearchDeletionTest {
    @Test
    fun filtersDeletedSubtreeWithoutRemovingSiblingPrefix() {
        val page = SearchPage(
            hits = listOf(
                hit(1, "/storage/a/file.txt"),
                hit(2, "/storage/ab/file.txt"),
                hit(3, "/storage/a/directory/child.txt")
            ),
            nextOffset = 64,
            totalCount = 3
        )
        val deletedPaths = DeletedPathPrefixes(
            uriPrefixes = emptySet(),
            indexPathPrefixes = setOf("/storage/a")
        )

        val filtered = page.withoutDeletedPaths(deletedPaths)

        assertEquals(listOf("/storage/ab/file.txt"), filtered.hits.map { it.entry.path })
        assertEquals(64L, filtered.nextOffset)
        assertEquals(1L, filtered.totalCount)
    }

    private fun hit(id: Long, path: String) = SearchHit(
        entry = IndexedFileRecord(
            id = id,
            rootId = 1,
            path = path,
            parentPath = path.substringBeforeLast('/'),
            name = path.substringAfterLast('/'),
            sizeBytes = 1,
            modifiedAtMillis = 1,
            indexedAtMillis = 1,
            isDirectory = false,
            scanGeneration = 1
        ),
        relevance = 1.0
    )
}
