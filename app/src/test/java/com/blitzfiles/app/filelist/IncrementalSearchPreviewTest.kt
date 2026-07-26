/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalSearchPreviewTest {
    @Test
    fun previewNarrowsVisibleCandidatesImmediatelyAndIgnoresCase() {
        val candidates = listOf("Readme.md", "report.pdf", "photo.jpg")

        val preview = filterIncrementalSearchPreview(candidates, "  RE  ") { it }

        assertEquals(listOf("Readme.md", "report.pdf"), preview)
    }

    @Test
    fun previewCannotInventCandidatesMissingFromThePreviousResult() {
        val previousNarrowResult = listOf("report-final.pdf")

        val preview = filterIncrementalSearchPreview(previousNarrowResult, "rep") { it }

        assertEquals(previousNarrowResult, preview)
    }

    @Test
    fun previewWorkIsBoundedForVeryBroadPreviousResults() {
        val candidates = (0..<2_000).map { index -> "match-$index" }

        val preview = filterIncrementalSearchPreview(
            candidates = candidates,
            query = "match",
            maxCandidates = 25,
            name = { it }
        )

        assertEquals(25, preview.size)
        assertEquals("match-24", preview.last())
    }

    @Test
    fun blankQueryDoesNotCreateAProvisionalSearchResult() {
        assertTrue(filterIncrementalSearchPreview(listOf("file"), " ") { it }.isEmpty())
    }

    @Test
    fun previewPreservesWhitespaceInsideTheQuery() {
        val candidates = listOf("annual report.pdf", "annual-report.pdf")

        val preview = filterIncrementalSearchPreview(candidates, "  annual report  ") { it }

        assertEquals(listOf("annual report.pdf"), preview)
    }
}
