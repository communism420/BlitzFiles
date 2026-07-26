/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest

/**
 * Small session cache for first pages. It makes backspace and repeated queries instantaneous while
 * the database verifies the cached result without showing a blocking progress indicator.
 */
internal class InstantSearchResults(
    private val maximumPages: Int = DEFAULT_MAXIMUM_PAGES
) {
    init {
        require(maximumPages > 0) { "Maximum cached search pages must be positive" }
    }

    private val pages = object : LinkedHashMap<SearchRequest, SearchPage>(
        maximumPages,
        LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SearchRequest, SearchPage>
        ): Boolean = size > maximumPages
    }

    operator fun get(request: SearchRequest): SearchPage? = pages[request]

    /**
     * Returns an exact cached page or a logically proven empty page.
     *
     * For plain AND/substring queries, adding characters can only narrow the result set. If a
     * cached prefix query is already known to have no matches, an extended query is also empty and
     * does not need another database pass.
     */
    fun getReadyPage(request: SearchRequest): SearchPage? {
        pages[request]?.let { return it }
        val hasKnownEmptyPrefix = pages.entries.any { (cachedRequest, cachedPage) ->
            cachedPage.hits.isEmpty() &&
                cachedPage.nextOffset == null &&
                request.isPlainExtensionOf(cachedRequest)
        }
        if (!hasKnownEmptyPrefix) {
            return null
        }

        return SearchPage(
            hits = emptyList(),
            nextOffset = null,
            totalCount = 0L
        ).also { derivedPage ->
            pages[request] = derivedPage
        }
    }

    fun put(request: SearchRequest, page: SearchPage) {
        if (request.offset == 0L) {
            pages[request] = page
        }
    }

    fun clear() {
        pages.clear()
    }

    companion object {
        private const val DEFAULT_MAXIMUM_PAGES = 24
        private const val LOAD_FACTOR = 0.75f
    }
}

private fun SearchRequest.isPlainExtensionOf(prefixRequest: SearchRequest): Boolean =
    offset == 0L &&
        prefixRequest.offset == 0L &&
        query.length > prefixRequest.query.length &&
        query.startsWith(prefixRequest.query) &&
        query.none(::isSearchWildcard) &&
        prefixRequest.query.none(::isSearchWildcard) &&
        prefixRequest.copy(query = query) == this

private fun isSearchWildcard(character: Char): Boolean =
    character == '*' || character == '?'

/**
 * Appends a page without violating RecyclerView's stable-ID contract if the index changed between
 * two OFFSET queries. Keyset paging can remove omissions as well; deduplication at least guarantees
 * that concurrent inserts never produce duplicate rows or IDs in the visible list.
 */
internal fun appendUniqueHits(
    currentHits: List<SearchHit>,
    pageHits: List<SearchHit>
): List<SearchHit> {
    if (pageHits.isEmpty()) {
        return currentHits
    }
    val seenIds = currentHits.mapNotNullTo(HashSet(currentHits.size)) { hit -> hit.entry.id }
    val seenPaths = currentHits.mapTo(HashSet(currentHits.size)) { hit -> hit.entry.path }
    val uniquePageHits = pageHits.filter { hit ->
        val id = hit.entry.id
        if (id != null) {
            seenIds.add(id)
        } else {
            seenPaths.add(hit.entry.path)
        }
    }
    return if (uniquePageHits.isEmpty()) currentHits else currentHits + uniquePageHits
}

/**
 * Immediately narrows the visible page while an extended plain-text query is running.
 *
 * The returned list is only provisional. The complete indexed result replaces it a few
 * milliseconds later, so filtering a bounded previous page cannot hide matches permanently.
 */
internal fun filterProvisionalHits(
    previousRequest: SearchRequest?,
    nextRequest: SearchRequest,
    currentHits: List<SearchHit>
): List<SearchHit>? {
    if (
        previousRequest == null ||
        nextRequest.query.length <= previousRequest.query.length ||
        !nextRequest.query.startsWith(previousRequest.query, ignoreCase = true) ||
        nextRequest.query.any { it == '*' || it == '?' } ||
        previousRequest.copy(query = nextRequest.query) != nextRequest
    ) {
        return null
    }

    val tokens = nextRequest.query.split(WHITESPACE_REGEX)
    return currentHits.filter { hit ->
        tokens.all { token -> hit.entry.name.contains(token, ignoreCase = true) }
    }
}

private val WHITESPACE_REGEX = Regex("\\s+")
