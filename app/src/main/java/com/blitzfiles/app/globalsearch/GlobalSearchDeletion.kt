/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.app.filejob.DeletedPathPrefixes
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchPage

internal fun List<SearchHit>.withoutDeletedPaths(
    deletedPaths: DeletedPathPrefixes
): List<SearchHit> {
    if (deletedPaths.indexPathPrefixes.isEmpty()) {
        return this
    }
    return filterNot { hit -> deletedPaths.containsIndexPath(hit.entry.path) }
}

internal fun SearchPage.withoutDeletedPaths(deletedPaths: DeletedPathPrefixes): SearchPage {
    val visibleHits = hits.withoutDeletedPaths(deletedPaths)
    if (visibleHits.size == hits.size) {
        return this
    }
    val removedCount = hits.size - visibleHits.size
    return copy(
        hits = visibleHits,
        totalCount = totalCount?.let { count -> (count - removedCount).coerceAtLeast(0L) }
    )
}
