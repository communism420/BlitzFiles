/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

internal const val FILE_MANAGER_PLUS_HOME_GRID_SPAN_COUNT = 12

internal fun normalizeFileManagerPlusHomeTitle(title: CharSequence): String =
    title.toString()
        .replace("\r\n", " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()

/**
 * Chooses one shared column width for a home-screen section.
 *
 * Only column counts that divide [spanCount] are considered, so every item in a section has the
 * same width and rows stay aligned. If the content cannot fit at the requested minimum text size,
 * the section progressively switches to fewer columns.
 */
internal fun calculateFileManagerPlusHomeSectionSpanSize(
    requiredItemWidthPx: Float,
    availableWidthPx: Int,
    maximumColumnCount: Int,
    spanCount: Int = FILE_MANAGER_PLUS_HOME_GRID_SPAN_COUNT
): Int {
    require(spanCount > 0)
    require(maximumColumnCount > 0)
    if (availableWidthPx <= 0) {
        return spanCount
    }
    val requiredWidth = requiredItemWidthPx.coerceAtLeast(0f)
    val maximumColumns = maximumColumnCount.coerceAtMost(spanCount)
    for (columnCount in maximumColumns downTo 1) {
        if (spanCount % columnCount != 0) {
            continue
        }
        if (availableWidthPx.toFloat() / columnCount >= requiredWidth) {
            return spanCount / columnCount
        }
    }
    return spanCount
}
