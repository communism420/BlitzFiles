/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileManagerPlusHomeGridTest {
    @Test
    fun titleLineBreaksAreNormalizedForSingleLineLayout() {
        assertEquals(
            "Internal shared storage",
            normalizeFileManagerPlusHomeTitle("  Internal\r\nshared\nstorage  ")
        )
    }

    @Test
    fun shortcutsUseThreeEqualColumnsOnReproductionDevice() {
        assertEquals(
            4,
            calculateFileManagerPlusHomeSectionSpanSize(
                requiredItemWidthPx = 104f,
                availableWidthPx = 448,
                maximumColumnCount = 3
            )
        )
    }

    @Test
    fun entireSectionSwitchesToTwoColumnsForLongRussianTitle() {
        val requiredWidth = 190f
        val availableWidth = 448

        val spanSize = calculateFileManagerPlusHomeSectionSpanSize(
            requiredItemWidthPx = requiredWidth,
            availableWidthPx = availableWidth,
            maximumColumnCount = 3
        )

        val allocatedWidth =
            availableWidth.toFloat() * spanSize / FILE_MANAGER_PLUS_HOME_GRID_SPAN_COUNT
        assertEquals(6, spanSize)
        assertTrue(allocatedWidth >= requiredWidth)
    }

    @Test
    fun tabletSectionUsesFourEqualColumns() {
        assertEquals(
            3,
            calculateFileManagerPlusHomeSectionSpanSize(
                requiredItemWidthPx = 180f,
                availableWidthPx = 840,
                maximumColumnCount = 4
            )
        )
    }

    @Test
    fun unsupportedFiveColumnRequestFallsBackToFourDivisibleColumns() {
        assertEquals(
            3,
            calculateFileManagerPlusHomeSectionSpanSize(
                requiredItemWidthPx = 100f,
                availableWidthPx = 600,
                maximumColumnCount = 5
            )
        )
    }

    @Test
    fun titleWiderThanWindowUsesFullRow() {
        assertEquals(
            FILE_MANAGER_PLUS_HOME_GRID_SPAN_COUNT,
            calculateFileManagerPlusHomeSectionSpanSize(
                requiredItemWidthPx = 900f,
                availableWidthPx = 448,
                maximumColumnCount = 3
            )
        )
    }

    @Test
    fun unavailableWidthFallsBackToFullRow() {
        assertEquals(
            FILE_MANAGER_PLUS_HOME_GRID_SPAN_COUNT,
            calculateFileManagerPlusHomeSectionSpanSize(
                requiredItemWidthPx = 104f,
                availableWidthPx = 0,
                maximumColumnCount = 3
            )
        )
    }
}
