/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import com.blitzfiles.app.search.toEffectiveSearchQuery

/**
 * Immediately narrows the currently visible candidates while the authoritative background search
 * is being replaced. The candidate cap keeps this main-thread preview bounded even after a very
 * broad one-character query; the real search is not capped by this function.
 */
internal fun <T> filterIncrementalSearchPreview(
    candidates: List<T>,
    query: String,
    maxCandidates: Int = MAX_INCREMENTAL_PREVIEW_CANDIDATES,
    name: (T) -> String
): List<T> {
    val effectiveQuery = query.toEffectiveSearchQuery()
    if (effectiveQuery.isEmpty() || maxCandidates <= 0) {
        return emptyList()
    }
    return candidates.asSequence()
        .take(maxCandidates)
        .filter { candidate -> name(candidate).contains(effectiveQuery, ignoreCase = true) }
        .toList()
}

private const val MAX_INCREMENTAL_PREVIEW_CANDIDATES = 1_000
