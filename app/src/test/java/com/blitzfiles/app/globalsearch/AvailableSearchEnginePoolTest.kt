/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.search.SearchEngine
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailableSearchEnginePoolTest {
    @Test
    fun newestQueryUsesTheFirstReaderReleasedByCancelledNativeWork() = runBlocking {
        val firstEngine = BlockingSearchEngine()
        val secondEngine = BlockingSearchEngine()
        val pool = AvailableSearchEnginePool(listOf(firstEngine, secondEngine))
        val firstRequest = SearchRequest("first")
        val secondRequest = SearchRequest("second")
        val newestRequest = SearchRequest("newest")

        val firstSearch = async { pool.search(firstRequest) }
        assertEquals(firstRequest, firstEngine.awaitStartedRequest())
        firstSearch.cancel()

        val secondSearch = async { pool.search(secondRequest) }
        assertEquals(secondRequest, secondEngine.awaitStartedRequest())
        secondSearch.cancel()

        val newestSearch = async { pool.search(newestRequest) }
        secondEngine.complete(secondRequest)

        assertEquals(newestRequest, secondEngine.awaitStartedRequest())
        secondEngine.complete(newestRequest)
        assertEquals(EMPTY_PAGE, newestSearch.await())

        firstEngine.complete(firstRequest)
        joinAll(firstSearch, secondSearch)
    }

    @Test
    fun cancellationDuringReaderHandoffReturnsTheUndeliveredReader() = runBlocking {
        val engine = BlockingSearchEngine()
        val pool = AvailableSearchEnginePool(listOf(engine))
        val holderRequest = SearchRequest("holder")
        val cancelledRequest = SearchRequest("cancelled")
        val recoveryRequest = SearchRequest("recovery")
        val holderSearch = async { pool.search(holderRequest) }
        assertEquals(holderRequest, engine.awaitStartedRequest())

        val handoffDispatcher = ManualDispatcher()
        val cancelledSearch = async(handoffDispatcher) {
            pool.search(cancelledRequest)
        }
        handoffDispatcher.runNext()

        engine.complete(holderRequest)
        assertEquals(EMPTY_PAGE, holderSearch.await())
        // The reader has been delivered, but the receiver continuation has not run yet.
        cancelledSearch.cancel()
        handoffDispatcher.runAll()
        cancelledSearch.join()
        assertTrue(cancelledSearch.isCancelled)

        val recoverySearch = async { pool.search(recoveryRequest) }
        assertEquals(recoveryRequest, engine.awaitStartedRequest())
        engine.complete(recoveryRequest)
        assertEquals(EMPTY_PAGE, recoverySearch.await())
    }

    private class BlockingSearchEngine : SearchEngine {
        private val startedRequests = Channel<SearchRequest>(Channel.UNLIMITED)
        private val completions = mutableMapOf<SearchRequest, CompletableDeferred<SearchPage>>()

        override suspend fun search(request: SearchRequest): SearchPage =
            withContext(NonCancellable) {
                val completion = CompletableDeferred<SearchPage>()
                completions[request] = completion
                startedRequests.send(request)
                completion.await()
            }

        suspend fun awaitStartedRequest(): SearchRequest =
            withTimeout(2_000) { startedRequests.receive() }

        fun complete(request: SearchRequest) {
            checkNotNull(completions[request]).complete(EMPTY_PAGE)
        }

        override suspend fun close() = Unit
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No dispatched task is available" }
            tasks.removeFirst().run()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }

    companion object {
        private val EMPTY_PAGE = SearchPage(
            hits = emptyList(),
            nextOffset = null,
            totalCount = 0
        )
    }
}
