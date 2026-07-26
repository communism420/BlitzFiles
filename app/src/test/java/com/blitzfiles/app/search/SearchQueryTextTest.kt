/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryTextTest {
    @Test
    fun addingBoundaryWhitespaceKeepsTheSameEffectiveQuery() {
        assertEquals(
            "zapret".toEffectiveSearchQuery(),
            "zapret ".toEffectiveSearchQuery()
        )
    }

    @Test
    fun boundaryWhitespaceIsIgnoredWithoutChangingInternalWhitespace() {
        assertEquals("annual report", " \tannual report\n".toEffectiveSearchQuery())
    }

    @Test
    fun whitespaceOnlyQueryBecomesEmpty() {
        assertEquals("", " \t\n".toEffectiveSearchQuery())
    }
}
