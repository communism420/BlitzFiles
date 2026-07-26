/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.search

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import androidx.appcompat.R as AppCompatR
import androidx.core.graphics.ColorUtils
import com.blitzfiles.app.util.getColorByAttr
import com.blitzfiles.search.domain.model.SearchQueryMode

internal fun Context.highlightSearchMatches(
    text: String,
    query: String,
    mode: SearchQueryMode
): CharSequence {
    val ranges = findSearchMatchRanges(text, query, mode)
    if (ranges.isEmpty()) {
        return text
    }

    val highlightedText = SpannableString(text)
    val highlightColor = ColorUtils.setAlphaComponent(
        getColorByAttr(AppCompatR.attr.colorPrimary),
        MATCH_BACKGROUND_ALPHA
    )
    for (range in ranges) {
        highlightedText.setSpan(
            BackgroundColorSpan(highlightColor),
            range.start,
            range.endExclusive,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        highlightedText.setSpan(
            StyleSpan(Typeface.BOLD),
            range.start,
            range.endExclusive,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return highlightedText
}

private const val MATCH_BACKGROUND_ALPHA = 0x52
