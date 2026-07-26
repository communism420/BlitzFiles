/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.search

import com.blitzfiles.search.domain.model.SearchQueryMode

internal data class SearchMatchRange(
    val start: Int,
    val endExclusive: Int
)

/**
 * Finds UTF-16 ranges that can be highlighted without changing the original filename.
 *
 * Keeping indices in the original string is important for Spannable and avoids the length changes
 * that locale-sensitive lowercase conversions can introduce.
 */
internal fun findSearchMatchRanges(
    text: String,
    query: String,
    mode: SearchQueryMode
): List<SearchMatchRange> {
    val effectiveQuery = query.toEffectiveSearchQuery()
    if (text.isEmpty() || effectiveQuery.isEmpty()) {
        return emptyList()
    }
    val fragments = when (mode) {
        SearchQueryMode.LITERAL_SUBSTRING -> listOf(effectiveQuery)
        SearchQueryMode.PATTERN -> effectiveQuery.toPatternLiteralFragments()
    }
    if (fragments.isEmpty()) {
        return emptyList()
    }

    val ranges = mutableListOf<SearchMatchRange>()
    for (fragment in fragments) {
        ranges += text.findLiteralMatchRanges(fragment)
    }
    if (ranges.size < 2) {
        return ranges
    }

    val sortedRanges = ranges.sortedWith(
        compareBy<SearchMatchRange> { range -> range.start }
            .thenBy { range -> range.endExclusive }
    )
    val mergedRanges = ArrayList<SearchMatchRange>(sortedRanges.size)
    var current = sortedRanges.first()
    for (index in 1..<sortedRanges.size) {
        val next = sortedRanges[index]
        current = if (next.start <= current.endExclusive) {
            SearchMatchRange(
                start = current.start,
                endExclusive = maxOf(current.endExclusive, next.endExclusive)
            )
        } else {
            mergedRanges += current
            next
        }
    }
    mergedRanges += current
    return mergedRanges
}

private fun String.toPatternLiteralFragments(): List<String> {
    val fragments = mutableListOf<String>()
    for (token in trim().split(PATTERN_WHITESPACE_REGEX)) {
        for (fragment in token.split('*', '?')) {
            if (
                fragment.isNotEmpty() &&
                fragments.none { existing -> existing.equals(fragment, ignoreCase = true) }
            ) {
                fragments += fragment
            }
        }
    }
    return fragments
}

private fun String.findLiteralMatchRanges(fragment: String): List<SearchMatchRange> {
    if (fragment.isEmpty() || fragment.length > length) {
        return emptyList()
    }
    val matches = mutableListOf<SearchMatchRange>()
    var start = 0
    while (start < length) {
        var textOffset = start
        var fragmentOffset = 0
        while (textOffset < length && fragmentOffset < fragment.length) {
            val textCodePoint = codePointAt(textOffset)
            val fragmentCodePoint = fragment.codePointAt(fragmentOffset)
            if (!textCodePoint.equalsIgnoreCase(fragmentCodePoint)) {
                break
            }
            textOffset += Character.charCount(textCodePoint)
            fragmentOffset += Character.charCount(fragmentCodePoint)
        }
        if (fragmentOffset == fragment.length) {
            matches += SearchMatchRange(start, textOffset)
        }
        start += Character.charCount(codePointAt(start))
    }
    return matches
}

private fun Int.equalsIgnoreCase(other: Int): Boolean {
    if (this == other) {
        return true
    }
    val uppercase = Character.toUpperCase(this)
    val otherUppercase = Character.toUpperCase(other)
    return uppercase == otherUppercase ||
        Character.toLowerCase(uppercase) == Character.toLowerCase(otherUppercase)
}

private val PATTERN_WHITESPACE_REGEX = Regex("\\s+")
