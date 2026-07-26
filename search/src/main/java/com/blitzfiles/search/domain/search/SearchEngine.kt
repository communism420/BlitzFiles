/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.search

import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

/**
 * Platform-independent boundary for paged and as-you-type index queries.
 */
interface SearchEngine {
    suspend fun search(request: SearchRequest): SearchPage

    /**
     * Debounces input and cancels delivery of obsolete searches when a newer request arrives.
     *
     * A null request clears the current result after the same debounce interval.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun searchAsYouType(
        requests: Flow<SearchRequest?>,
        debounceMillis: Long = DEFAULT_SEARCH_DEBOUNCE_MILLIS
    ): Flow<SearchPage?> {
        require(debounceMillis in 0..MAX_SEARCH_DEBOUNCE_MILLIS) {
            "Search debounce must be between 0 and $MAX_SEARCH_DEBOUNCE_MILLIS milliseconds"
        }
        return requests
            .debounce(debounceMillis)
            .distinctUntilChanged()
            .mapLatest { request -> request?.let { search(it) } }
    }

    suspend fun close()

    companion object {
        const val DEFAULT_SEARCH_DEBOUNCE_MILLIS = 16L
        const val MAX_SEARCH_DEBOUNCE_MILLIS = 5_000L
    }
}
