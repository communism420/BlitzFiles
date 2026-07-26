/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexingResult
import com.blitzfiles.search.domain.model.IndexingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-visible progress for the current in-process indexing session.
 *
 * Unlike [IndexingState], this state also covers the short interval between scheduling a service
 * command and the indexer beginning filesystem traversal. Counts are accumulated across commands
 * that the service executes in one FIFO session, so adding root indexing while ordinary storage is
 * still being scanned never makes the displayed count jump backwards.
 */
sealed interface FileIndexingProgress {
    data object Idle : FileIndexingProgress

    data object Scheduled : FileIndexingProgress

    data object Preparing : FileIndexingProgress

    data class Running(
        val indexedEntryCount: Long,
        val currentPath: String?
    ) : FileIndexingProgress

    data class Paused(
        val indexedEntryCount: Long
    ) : FileIndexingProgress

    data class Completed(
        val result: IndexingResult
    ) : FileIndexingProgress

    data class Cancelled(
        val scannedEntryCount: Long,
        val indexedEntryCount: Long
    ) : FileIndexingProgress

    data class Failed(
        val message: String
    ) : FileIndexingProgress
}

/**
 * Process-local source of truth for the indexing banner.
 *
 * All mutations are serialized because scan commands may be submitted from any application
 * thread, while indexer states normally arrive on the service main thread.
 */
internal object FileIndexingProgressStore {
    private val stateMachine = FileIndexingProgressStateMachine()
    private val mutableState = MutableStateFlow<FileIndexingProgress>(FileIndexingProgress.Idle)

    val state: StateFlow<FileIndexingProgress> = mutableState.asStateFlow()

    fun markScheduled() {
        update { markScheduled() }
    }

    fun markPreparing() {
        update { markPreparing() }
    }

    /**
     * Publishes only live indexer states. Terminal states are deliberately deferred until the
     * service knows whether another FIFO command is waiting.
     */
    fun publishIndexerState(state: IndexingState) {
        update { publishIndexerState(state) }
    }

    fun finishCommand(state: IndexingState, hasPendingCommand: Boolean) {
        update { finishCommand(state, hasPendingCommand) }
    }

    fun markLaunchFailed(error: Throwable) {
        update { markLaunchFailed(error) }
    }

    fun markServiceStopped() {
        update { markServiceStopped() }
    }

    /**
     * Consumes a terminal banner after it has remained visible long enough for the user to read.
     *
     * The expected-state check prevents a delayed UI callback from clearing a newer scan session.
     */
    fun clearTerminal(expected: FileIndexingProgress) {
        update { clearTerminal(expected) }
    }

    private inline fun update(
        transition: FileIndexingProgressStateMachine.() -> FileIndexingProgress
    ) {
        synchronized(stateMachine) {
            mutableState.value = stateMachine.transition()
        }
    }
}

/**
 * Pure state machine kept separate from Android service code for deterministic unit testing.
 */
internal class FileIndexingProgressStateMachine {
    private var current: FileIndexingProgress = FileIndexingProgress.Idle
    private var completedResult = EMPTY_RESULT
    private var currentCommandScannedEntryCount = 0L
    private var currentCommandIndexedEntryCount = 0L
    private var pendingFailureMessage: String? = null

    fun markScheduled(): FileIndexingProgress {
        if (current.isTerminalOrIdle()) {
            resetSession()
            current = FileIndexingProgress.Scheduled
        }
        return current
    }

    fun markPreparing(): FileIndexingProgress {
        currentCommandScannedEntryCount = 0
        currentCommandIndexedEntryCount = 0
        current = FileIndexingProgress.Preparing
        return current
    }

    fun publishIndexerState(state: IndexingState): FileIndexingProgress {
        when (state) {
            is IndexingState.Running -> {
                currentCommandScannedEntryCount = state.scannedEntryCount
                currentCommandIndexedEntryCount = state.indexedEntryCount
                current = FileIndexingProgress.Running(
                    indexedEntryCount = completedResult.indexedEntryCount.safeAdd(
                        state.indexedEntryCount
                    ),
                    currentPath = state.currentPath
                )
            }
            is IndexingState.Paused -> {
                currentCommandScannedEntryCount = state.scannedEntryCount
                currentCommandIndexedEntryCount = state.indexedEntryCount
                current = FileIndexingProgress.Paused(
                    indexedEntryCount = completedResult.indexedEntryCount.safeAdd(
                        state.indexedEntryCount
                    )
                )
            }
            IndexingState.Idle,
            is IndexingState.Completed,
            is IndexingState.Cancelled,
            is IndexingState.Failed -> Unit
        }
        return current
    }

    fun finishCommand(
        state: IndexingState,
        hasPendingCommand: Boolean
    ): FileIndexingProgress {
        when (state) {
            is IndexingState.Completed -> {
                completedResult += state.result
                currentCommandScannedEntryCount = 0
                currentCommandIndexedEntryCount = 0
            }
            is IndexingState.Cancelled -> {
                currentCommandScannedEntryCount = state.scannedEntryCount
                currentCommandIndexedEntryCount = state.indexedEntryCount
            }
            is IndexingState.Failed -> {
                pendingFailureMessage = state.error.safeProgressMessage()
            }
            IndexingState.Idle,
            is IndexingState.Running,
            is IndexingState.Paused -> {
                pendingFailureMessage = "Indexing stopped before producing a final result"
            }
        }

        if (hasPendingCommand) {
            current = FileIndexingProgress.Preparing
            return current
        }

        current = pendingFailureMessage?.let(FileIndexingProgress::Failed)
            ?: when (state) {
                is IndexingState.Completed -> FileIndexingProgress.Completed(completedResult)
                is IndexingState.Cancelled -> FileIndexingProgress.Cancelled(
                    scannedEntryCount = completedResult.scannedEntryCount.safeAdd(
                        state.scannedEntryCount
                    ),
                    indexedEntryCount = completedResult.indexedEntryCount.safeAdd(
                        state.indexedEntryCount
                    )
                )
                is IndexingState.Failed -> FileIndexingProgress.Failed(
                    state.error.safeProgressMessage()
                )
                IndexingState.Idle,
                is IndexingState.Running,
                is IndexingState.Paused -> FileIndexingProgress.Failed(
                    "Indexing stopped before producing a final result"
                )
            }
        return current
    }

    fun markLaunchFailed(error: Throwable): FileIndexingProgress {
        if (
            current !is FileIndexingProgress.Running &&
            current !is FileIndexingProgress.Paused
        ) {
            current = FileIndexingProgress.Failed(error.safeProgressMessage())
        }
        return current
    }

    fun markServiceStopped(): FileIndexingProgress {
        if (!current.isTerminalOrIdle()) {
            current = FileIndexingProgress.Cancelled(
                scannedEntryCount = completedResult.scannedEntryCount.safeAdd(
                    currentCommandScannedEntryCount
                ),
                indexedEntryCount = completedResult.indexedEntryCount.safeAdd(
                    currentCommandIndexedEntryCount
                )
            )
        }
        return current
    }

    fun clearTerminal(expected: FileIndexingProgress): FileIndexingProgress {
        if (current == expected && current.isTerminal()) {
            current = FileIndexingProgress.Idle
        }
        return current
    }

    private fun resetSession() {
        completedResult = EMPTY_RESULT
        currentCommandScannedEntryCount = 0
        currentCommandIndexedEntryCount = 0
        pendingFailureMessage = null
    }
}

private fun FileIndexingProgress.isTerminalOrIdle(): Boolean =
    this is FileIndexingProgress.Idle ||
        isTerminal()

private fun FileIndexingProgress.isTerminal(): Boolean =
    this is FileIndexingProgress.Completed ||
        this is FileIndexingProgress.Cancelled ||
        this is FileIndexingProgress.Failed

private operator fun IndexingResult.plus(other: IndexingResult): IndexingResult =
    IndexingResult(
        scannedEntryCount = scannedEntryCount.safeAdd(other.scannedEntryCount),
        indexedEntryCount = indexedEntryCount.safeAdd(other.indexedEntryCount),
        removedEntryCount = removedEntryCount.safeAdd(other.removedEntryCount),
        skippedEntryCount = skippedEntryCount.safeAdd(other.skippedEntryCount),
        recoverableErrorCount = recoverableErrorCount.safeAdd(other.recoverableErrorCount),
        durationMillis = durationMillis.safeAdd(other.durationMillis)
    )

private fun Long.safeAdd(other: Long): Long =
    if (other > 0 && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Throwable.safeProgressMessage(): String =
    (message?.takeIf(String::isNotBlank) ?: javaClass.simpleName).take(MAX_ERROR_MESSAGE_LENGTH)

private const val MAX_ERROR_MESSAGE_LENGTH = 512

private val EMPTY_RESULT = IndexingResult(
    scannedEntryCount = 0,
    indexedEntryCount = 0,
    removedEntryCount = 0,
    skippedEntryCount = 0,
    recoverableErrorCount = 0,
    durationMillis = 0
)
