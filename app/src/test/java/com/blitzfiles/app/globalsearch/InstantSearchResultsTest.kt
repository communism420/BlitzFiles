/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.model.SearchSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class InstantSearchResultsTest {
    @Test
    fun cachesFirstPagesAndEvictsTheLeastRecentlyUsedQuery() {
        val cache = InstantSearchResults(maximumPages = 2)
        val firstRequest = SearchRequest(query = "first")
        val secondRequest = SearchRequest(query = "second")
        val thirdRequest = SearchRequest(query = "third")
        val firstPage = page("first")
        val secondPage = page("second")
        val thirdPage = page("third")

        cache.put(firstRequest, firstPage)
        cache.put(secondRequest, secondPage)
        assertSame(firstPage, cache[firstRequest])
        cache.put(thirdRequest, thirdPage)

        assertSame(firstPage, cache[firstRequest])
        assertNull(cache[secondRequest])
        assertSame(thirdPage, cache[thirdRequest])

        cache.clear()
        assertNull(cache[firstRequest])
        assertNull(cache[thirdRequest])
    }

    @Test
    fun ignoresPaginationPages() {
        val cache = InstantSearchResults()
        val request = SearchRequest(query = "file", offset = 64)

        cache.put(request, page("file"))

        assertNull(cache[request])
    }

    @Test
    fun provesPlainExtensionsOfKnownEmptyQueriesWithoutAnotherDatabaseResult() {
        val cache = InstantSearchResults()
        val emptyRequest = SearchRequest(query = "qxj")
        val emptyPage = SearchPage(emptyList(), nextOffset = null, totalCount = 0)
        cache.put(emptyRequest, emptyPage)

        assertSame(emptyPage, cache.getReadyPage(emptyRequest))
        val derivedPage = cache.getReadyPage(emptyRequest.copy(query = "qxjz"))
        assertNotNull(derivedPage)
        assertEquals(emptyList<SearchHit>(), derivedPage!!.hits)
        assertNull(derivedPage.nextOffset)
        assertEquals(0L, derivedPage.totalCount)

        cache.clear()
        assertNull(cache.getReadyPage(emptyRequest.copy(query = "qxjzk")))
    }

    @Test
    fun doesNotInferEmptyResultsAcrossWildcardsOrDifferentSearchOptions() {
        val cache = InstantSearchResults()
        val emptyRequest = SearchRequest(query = "qxj")
        cache.put(
            emptyRequest,
            SearchPage(emptyList(), nextOffset = null, totalCount = 0)
        )

        assertNull(cache.getReadyPage(emptyRequest.copy(query = "qxj*")))
        assertNull(
            cache.getReadyPage(
                emptyRequest.copy(
                    query = "qxjz",
                    sortOrder = SearchSortOrder.NAME_ASCENDING
                )
            )
        )
    }

    @Test
    fun provisionallyFiltersAnExtendedPlainQuery() {
        val previous = SearchRequest(query = "im")
        val next = previous.copy(query = "ima")
        val hits = listOf(hit("image.png"), hit("IMAX.mov"), hit("time.txt"))

        assertEquals(
            listOf("image.png", "IMAX.mov"),
            filterProvisionalHits(previous, next, hits)?.map { it.entry.name }
        )
    }

    @Test
    fun refusesUnsafeProvisionalFiltering() {
        val previous = SearchRequest(query = "image")
        val hits = listOf(hit("image.png"))

        assertNull(filterProvisionalHits(previous, previous.copy(query = "imag"), hits))
        assertNull(filterProvisionalHits(previous, previous.copy(query = "image*"), hits))
        assertNull(
            filterProvisionalHits(
                previous,
                previous.copy(
                    query = "images",
                    sortOrder = SearchSortOrder.NAME_ASCENDING
                ),
                hits
            )
        )
    }

    @Test
    fun appendsPaginationWithoutDuplicateStableIds() {
        val first = hit("first")
        val duplicate = hit("duplicate")
        val last = hit("last")

        assertEquals(
            listOf("first", "duplicate", "last"),
            appendUniqueHits(
                currentHits = listOf(first, duplicate),
                pageHits = listOf(duplicate, last)
            ).map { hit -> hit.entry.name }
        )
    }

    private fun page(name: String): SearchPage =
        SearchPage(listOf(hit(name)), nextOffset = null, totalCount = null)

    private fun hit(name: String): SearchHit =
        SearchHit(
            entry = IndexedFileRecord(
                id = name.hashCode().toLong().let { if (it > 0) it else 1L - it },
                rootId = 1,
                path = "/storage/$name",
                parentPath = "/storage",
                name = name,
                sizeBytes = 0,
                modifiedAtMillis = 0,
                indexedAtMillis = 1,
                isDirectory = false,
                scanGeneration = 1
            ),
            relevance = 0.0
        )
}
