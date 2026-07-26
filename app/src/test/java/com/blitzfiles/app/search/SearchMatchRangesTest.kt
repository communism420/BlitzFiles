/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.search

import com.blitzfiles.search.domain.model.SearchQueryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchRangesTest {
    @Test
    fun literalSearchHighlightsEverySingleCharacterMatch() {
        assertEquals(
            listOf(
                SearchMatchRange(1, 2),
                SearchMatchRange(3, 4),
                SearchMatchRange(5, 6)
            ),
            findSearchMatchRanges("Banana", "a", SearchQueryMode.LITERAL_SUBSTRING)
        )
    }

    @Test
    fun literalSearchIsCaseInsensitiveForUnicodeText() {
        assertEquals(
            listOf(SearchMatchRange(0, 5), SearchMatchRange(6, 11)),
            findSearchMatchRanges("Отчёт-ОТЧЁТ", "отчёт", SearchQueryMode.LITERAL_SUBSTRING)
        )
    }

    @Test
    fun literalSearchIgnoresBoundaryWhitespaceButPreservesInternalWhitespace() {
        assertEquals(
            listOf(SearchMatchRange(0, 13)),
            findSearchMatchRanges(
                text = "annual report.pdf",
                query = "  annual report  ",
                mode = SearchQueryMode.LITERAL_SUBSTRING
            )
        )
    }

    @Test
    fun patternSearchHighlightsLiteralFragmentsBetweenWildcardsAndTerms() {
        assertEquals(
            listOf(
                SearchMatchRange(0, 3),
                SearchMatchRange(7, 13),
                SearchMatchRange(14, 16),
                SearchMatchRange(17, 22)
            ),
            findSearchMatchRanges(
                text = "Report-final.txt-notes",
                query = "rep*final.?xt notes",
                mode = SearchQueryMode.PATTERN
            )
        )
    }

    @Test
    fun overlappingAndAdjacentPatternFragmentsAreMerged() {
        assertEquals(
            listOf(SearchMatchRange(0, 3)),
            findSearchMatchRanges("abc", "ab bc", SearchQueryMode.PATTERN)
        )
    }

    @Test
    fun literalSearchTreatsWildcardCharactersAsText() {
        assertEquals(
            listOf(SearchMatchRange(1, 4)),
            findSearchMatchRanges("a*b?c", "*b?", SearchQueryMode.LITERAL_SUBSTRING)
        )
    }

    @Test
    fun wildcardOnlyPatternHasNoCertainCharactersToHighlight() {
        assertTrue(findSearchMatchRanges("anything", "*??*", SearchQueryMode.PATTERN).isEmpty())
    }

    @Test
    fun supplementaryCharactersKeepTheirUtf16SpanBoundaries() {
        assertEquals(
            listOf(SearchMatchRange(1, 3), SearchMatchRange(4, 6)),
            findSearchMatchRanges("a😀b😀", "😀", SearchQueryMode.LITERAL_SUBSTRING)
        )
    }

    @Test
    fun supplementaryLettersUseCodePointCaseFolding() {
        assertEquals(
            listOf(SearchMatchRange(0, 2)),
            findSearchMatchRanges(
                text = "𐐨-file",
                query = "𐐀",
                mode = SearchQueryMode.LITERAL_SUBSTRING
            )
        )
    }

    @Test
    fun patternWhitespaceMatchesTheSearchCompilerSemantics() {
        assertEquals(
            listOf(SearchMatchRange(0, 3)),
            findSearchMatchRanges(
                text = "a\u00A0b",
                query = "a\u00A0b",
                mode = SearchQueryMode.PATTERN
            )
        )
    }
}
