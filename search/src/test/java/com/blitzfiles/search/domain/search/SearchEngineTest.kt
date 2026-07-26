/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.search

import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    @Test
    fun asYouTypeSearchDoesNotDeliverAnObsoleteResult() = runBlocking {
        val engine = RecordingSearchEngine()
        val firstRequest = SearchRequest(query = "fir")
        val secondRequest = SearchRequest(query = "second")
        val requests = flow {
            emit(firstRequest)
            delay(70)
            emit(secondRequest)
        }

        val results = engine.searchAsYouType(requests, debounceMillis = 50).toList()

        assertEquals(listOf("fir", "second"), engine.startedQueries)
        assertEquals(listOf("second".length.toLong()), results.map { it?.totalCount })
    }

    @Test
    fun asYouTypeSearchCanClearResultsAndRejectsUnsafeDebounce() = runBlocking {
        val engine = RecordingSearchEngine()

        assertEquals(
            listOf(null),
            engine.searchAsYouType(flow { emit(null) }, debounceMillis = 0).toList()
        )
        val error = runCatching {
            engine.searchAsYouType(flow { emit(null) }, debounceMillis = -1)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private class RecordingSearchEngine : SearchEngine {
        val startedQueries = mutableListOf<String>()

        override suspend fun search(request: SearchRequest): SearchPage {
            startedQueries += request.query
            delay(100)
            return SearchPage(
                hits = emptyList(),
                nextOffset = null,
                totalCount = request.query.length.toLong()
            )
        }

        override suspend fun close() = Unit
    }
}
