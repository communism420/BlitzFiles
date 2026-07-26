/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.search.SearchEngine
import kotlinx.coroutines.channels.Channel

/**
 * Routes each query to the first available interactive reader.
 *
 * Cancelling a coroutine cannot interrupt every native SQLite operation. A cancelled query may
 * therefore keep its reader busy briefly. Waiting on this pool lets the newest query use whichever
 * reader actually becomes free first instead of being queued behind a busy round-robin choice.
 */
internal class AvailableSearchEnginePool<T : SearchEngine>(
    engines: List<T>
) {
    private val availableEngines = Channel<T>(
        capacity = engines.size,
        onUndeliveredElement = { engine -> returnEngine(engine) }
    )

    init {
        require(engines.isNotEmpty()) { "At least one search engine is required" }
        engines.forEach { engine ->
            returnEngine(engine)
        }
    }

    suspend fun search(request: SearchRequest): SearchPage =
        withEngine { engine -> engine.search(request) }

    suspend fun <R> withEngine(block: suspend (T) -> R): R {
        var leasedEngine: T? = null
        try {
            val engine = availableEngines.receive()
            leasedEngine = engine
            return block(engine)
        } finally {
            leasedEngine?.let(::returnEngine)
        }
    }

    /**
     * Restores a reader without suspension, including prompt cancellation during Channel.receive.
     */
    private fun returnEngine(engine: T) {
        check(availableEngines.trySend(engine).isSuccess) {
            "Unable to return an interactive search engine to the pool"
        }
    }
}
