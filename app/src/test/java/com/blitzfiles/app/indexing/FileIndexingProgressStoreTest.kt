/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexingResult
import com.blitzfiles.search.domain.model.IndexingState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIndexingProgressStoreTest {
    @Test
    fun scheduledStateIsVisibleBeforePreparing() {
        val stateMachine = FileIndexingProgressStateMachine()

        assertSame(FileIndexingProgress.Scheduled, stateMachine.markScheduled())
        assertSame(FileIndexingProgress.Preparing, stateMachine.markPreparing())
    }

    @Test
    fun schedulingQueuedCommandDoesNotReplaceLiveProgress() {
        val stateMachine = FileIndexingProgressStateMachine()
        stateMachine.markScheduled()
        stateMachine.markPreparing()
        val running = stateMachine.publishIndexerState(
            IndexingState.Running(
                rootId = 1,
                currentPath = "/storage/emulated/0/Pictures",
                scannedEntryCount = 15,
                indexedEntryCount = 12
            )
        )

        assertEquals(running, stateMachine.markScheduled())
    }

    @Test
    fun terminalStateIsDeferredAndCountsAccumulateAcrossFifoCommands() {
        val stateMachine = FileIndexingProgressStateMachine()
        stateMachine.markScheduled()
        stateMachine.markPreparing()
        val firstRunning = stateMachine.publishIndexerState(
            IndexingState.Running(
                rootId = 1,
                currentPath = "/storage/emulated/0",
                scannedEntryCount = 10,
                indexedEntryCount = 10
            )
        )

        assertSame(firstRunning, stateMachine.publishIndexerState(resultState(indexed = 10)))
        assertSame(
            FileIndexingProgress.Preparing,
            stateMachine.finishCommand(resultState(indexed = 10), hasPendingCommand = true)
        )

        stateMachine.markPreparing()
        assertEquals(
            FileIndexingProgress.Running(
                indexedEntryCount = 15,
                currentPath = "/data/local"
            ),
            stateMachine.publishIndexerState(
                IndexingState.Running(
                    rootId = 2,
                    currentPath = "/data/local",
                    scannedEntryCount = 6,
                    indexedEntryCount = 5
                )
            )
        )

        val completed = stateMachine.finishCommand(
            resultState(indexed = 20, errors = 2),
            hasPendingCommand = false
        ) as FileIndexingProgress.Completed
        assertEquals(30, completed.result.indexedEntryCount)
        assertEquals(2, completed.result.recoverableErrorCount)
        assertEquals(30, completed.result.scannedEntryCount)
    }

    @Test
    fun failureFromEarlierCommandIsReportedAfterFifoDrains() {
        val stateMachine = FileIndexingProgressStateMachine()
        stateMachine.markScheduled()
        stateMachine.markPreparing()

        assertSame(
            FileIndexingProgress.Preparing,
            stateMachine.finishCommand(
                IndexingState.Failed(IOException("Root scan failed")),
                hasPendingCommand = true
            )
        )
        stateMachine.markPreparing()

        val finalState = stateMachine.finishCommand(
            resultState(indexed = 8),
            hasPendingCommand = false
        )
        assertEquals(
            FileIndexingProgress.Failed("Root scan failed"),
            finalState
        )
    }

    @Test
    fun unexpectedServiceStopCancelsActiveSessionWithLatestCounts() {
        val stateMachine = FileIndexingProgressStateMachine()
        stateMachine.markScheduled()
        stateMachine.markPreparing()
        stateMachine.publishIndexerState(
            IndexingState.Running(
                rootId = 1,
                currentPath = "/storage",
                scannedEntryCount = 25,
                indexedEntryCount = 22
            )
        )

        assertEquals(
            FileIndexingProgress.Cancelled(
                scannedEntryCount = 25,
                indexedEntryCount = 22
            ),
            stateMachine.markServiceStopped()
        )
    }

    @Test
    fun terminalStateCanBeConsumedWithoutClearingANewerSession() {
        val stateMachine = FileIndexingProgressStateMachine()
        stateMachine.markScheduled()
        stateMachine.markPreparing()
        val completed = stateMachine.finishCommand(
            resultState(indexed = 4),
            hasPendingCommand = false
        )

        assertTrue(completed is FileIndexingProgress.Completed)
        assertEquals(
            completed,
            stateMachine.publishIndexerState(IndexingState.Failed(IOException("late state")))
        )
        assertEquals(completed, stateMachine.markServiceStopped())
        assertSame(FileIndexingProgress.Idle, stateMachine.clearTerminal(completed))

        stateMachine.markScheduled()
        assertSame(
            FileIndexingProgress.Scheduled,
            stateMachine.clearTerminal(completed)
        )
        assertSame(FileIndexingProgress.Scheduled, stateMachine.markScheduled())
    }

    @Test
    fun launchFailureReplacesScheduledState() {
        val stateMachine = FileIndexingProgressStateMachine()
        stateMachine.markScheduled()

        assertEquals(
            FileIndexingProgress.Failed("Foreground service launch failed"),
            stateMachine.markLaunchFailed(IOException("Foreground service launch failed"))
        )
    }

    @Test
    fun accessLossBeforeQueuedCommandStartsReplacesPreparingState() {
        val stateMachine = FileIndexingProgressStateMachine()
        stateMachine.markScheduled()
        stateMachine.markPreparing()

        assertEquals(
            FileIndexingProgress.Failed("All files access is required for indexing"),
            stateMachine.markLaunchFailed(
                SecurityException("All files access is required for indexing")
            )
        )
    }

    private fun resultState(
        indexed: Long,
        errors: Long = 0
    ) = IndexingState.Completed(
        IndexingResult(
            scannedEntryCount = indexed,
            indexedEntryCount = indexed,
            removedEntryCount = 0,
            skippedEntryCount = 0,
            recoverableErrorCount = errors,
            durationMillis = indexed * 10
        )
    )
}
