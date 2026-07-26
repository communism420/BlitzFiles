/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.model

enum class SearchSortOrder {
    RELEVANCE,
    NAME_ASCENDING,
    NAME_DESCENDING,
    SIZE_ASCENDING,
    SIZE_DESCENDING,
    MODIFIED_ASCENDING,
    MODIFIED_DESCENDING
}

enum class SearchQueryMode {
    /**
     * BlitzFiles global-search syntax: whitespace joins terms with AND, while `*` and `?` are
     * wildcards.
     */
    PATTERN,

    /**
     * A single case-insensitive filename substring, matching the legacy directory-search contract.
     */
    LITERAL_SUBSTRING
}

data class SearchRequest(
    val query: String,
    val queryMode: SearchQueryMode = SearchQueryMode.PATTERN,
    val rootIds: Set<Long> = emptySet(),
    val includeFiles: Boolean = true,
    val includeDirectories: Boolean = true,
    val requiresRoot: Boolean? = null,
    val sortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Long = 0
) {
    init {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require('\u0000' !in query) { "Search query must not contain NUL" }
        require(query.length <= MAX_QUERY_LENGTH) {
            "Search query must not exceed $MAX_QUERY_LENGTH UTF-16 code units"
        }
        require(rootIds.all { it > 0 }) { "Root IDs must be positive" }
        require(rootIds.size <= MAX_ROOT_FILTERS) {
            "Search request must not contain more than $MAX_ROOT_FILTERS root filters"
        }
        require(includeFiles || includeDirectories) { "At least one entry type must be included" }
        require(limit in 1..MAX_LIMIT) { "Search limit must be between 1 and $MAX_LIMIT" }
        require(offset >= 0) { "Search offset must not be negative" }
        require(offset <= Long.MAX_VALUE - MAX_LIMIT - 1) {
            "Search offset is too large"
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
        const val MAX_QUERY_LENGTH = 512
        const val MAX_ROOT_FILTERS = 256
    }
}

data class SearchHit(
    val entry: IndexedFileRecord,
    /**
     * A comparable score where larger values mean a better match.
     */
    val relevance: Double
)

data class SearchPage(
    val hits: List<SearchHit>,
    val nextOffset: Long?,
    /**
     * May be null when calculating an exact count would make an interactive query slower.
     */
    val totalCount: Long?
)
