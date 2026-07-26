/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.util

import android.os.Handler

class DebouncedRunnable(
    private val handler: Handler,
    private val defaultIntervalMillis: Long,
    block: () -> Unit
) : () -> Unit {
    private val lock = Any()

    private val runnable = Runnable(block)

    override operator fun invoke() = invoke(defaultIntervalMillis)

    operator fun invoke(intervalMillis: Long) {
        require(intervalMillis >= 0) { "Debounce interval must not be negative" }
        synchronized(lock) {
            handler.removeCallbacks(runnable)
            handler.postDelayed(runnable, intervalMillis)
        }
    }

    fun cancel() {
        synchronized(lock) { handler.removeCallbacks(runnable) }
    }
}
