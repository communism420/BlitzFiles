/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.search

/**
 * Returns the query used by search engines and match highlighting.
 *
 * The UI keeps the raw text so typing a second term does not move the cursor. Only boundary
 * whitespace is ignored; whitespace inside the query remains meaningful.
 */
internal fun String.toEffectiveSearchQuery(): String = trim()
