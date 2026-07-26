/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIndexingCommandQueueTest {
    @Test
    fun preservesFifoOrderForDistinctCommands() {
        val queue = FileIndexingCommandQueue()
        val full = command(IndexingMode.FULL, setOf(1))
        val incremental = command(
            IndexingMode.INCREMENTAL,
            setOf(2),
            mapOf(2L to setOf("/storage/photo.jpg"))
        )

        assertTrue(queue.enqueue(full))
        assertTrue(queue.enqueue(incremental))

        assertEquals(full, queue.startNext())
        assertNull(queue.startNext())
        queue.complete(full)
        assertEquals(incremental, queue.startNext())
        queue.complete(incremental)
        assertNull(queue.startNext())
    }

    @Test
    fun deduplicatesActiveAndPendingCommands() {
        val queue = FileIndexingCommandQueue()
        val active = command(IndexingMode.FULL, null)
        val pending = command(IndexingMode.INCREMENTAL, setOf(1))

        assertTrue(queue.enqueue(active))
        assertEquals(active, queue.startNext())
        assertFalse(queue.enqueue(active))
        assertTrue(queue.enqueue(pending))
        assertFalse(queue.enqueue(pending))

        queue.complete(active)
        assertEquals(pending, queue.startNext())
    }

    @Test
    fun completedCommandCanBeEnqueuedAgain() {
        val queue = FileIndexingCommandQueue()
        val command = command(IndexingMode.FULL, setOf(1))

        assertTrue(queue.enqueue(command))
        assertEquals(command, queue.startNext())
        queue.complete(command)

        assertTrue(queue.enqueue(command))
        assertEquals(command, queue.startNext())
    }

    @Test
    fun clearingPendingCommandsDoesNotLoseActiveCommand() {
        val queue = FileIndexingCommandQueue()
        val active = command(IndexingMode.FULL, setOf(1))
        val pending = command(IndexingMode.FULL, setOf(2))

        queue.enqueue(active)
        queue.startNext()
        queue.enqueue(pending)
        queue.clearPending()

        assertNull(queue.startNext())
        assertFalse(queue.enqueue(active))
        queue.complete(active)
        assertNull(queue.startNext())
        assertTrue(queue.enqueue(active))
    }

    @Test
    fun userCancellationRetainsDeletionReconciliationCommands() {
        val queue = FileIndexingCommandQueue()
        val active = command(IndexingMode.FULL, setOf(1))
        val ordinaryPending = command(IndexingMode.FULL, setOf(2))
        val protectedPending = FileIndexingCommand(
            mode = IndexingMode.INCREMENTAL,
            rootIds = setOf(1),
            pathHints = mapOf(1L to setOf("/storage/deleted.txt")),
            deletionProtectionToken = 7
        )

        queue.enqueue(active)
        queue.startNext()
        queue.enqueue(ordinaryPending)
        queue.enqueue(protectedPending)
        queue.clearPendingUnprotected()

        queue.complete(active)
        assertEquals(protectedPending, queue.startNext())
    }

    @Test
    fun resetReturnsActiveAndPendingProtectionCommandsForRecovery() {
        val queue = FileIndexingCommandQueue()
        val active = FileIndexingCommand(
            IndexingMode.INCREMENTAL,
            setOf(1),
            deletionProtectionToken = 10
        )
        val pending = FileIndexingCommand(
            IndexingMode.INCREMENTAL,
            setOf(2),
            deletionProtectionToken = 11
        )

        queue.enqueue(active)
        queue.startNext()
        queue.enqueue(pending)

        assertEquals(listOf(active, pending), queue.reset())
        assertNull(queue.startNext())
    }

    private fun command(
        mode: IndexingMode,
        rootIds: Set<Long>?,
        pathHints: Map<Long, Set<String>> = emptyMap()
    ) = FileIndexingCommand(mode, rootIds, pathHints)
}
