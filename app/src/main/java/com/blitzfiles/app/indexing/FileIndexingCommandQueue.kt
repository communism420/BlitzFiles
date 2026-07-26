/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexingMode
import java.util.ArrayDeque

/**
 * Immutable description of one indexing service request.
 *
 * A null [rootIds] means that enabled roots should be resolved when the command starts. Keeping
 * this distinction lets a queued "scan all" command include configuration changes made while an
 * earlier scan was running.
 */
internal data class FileIndexingCommand(
    val mode: IndexingMode,
    val rootIds: Set<Long>?,
    val pathHints: Map<Long, Set<String>> = emptyMap(),
    val deletionProtectionToken: Long? = null
) {
    init {
        require(pathHints.keys.all { rootIds == null || it in rootIds }) {
            "Path hints must belong to requested roots"
        }
        require(deletionProtectionToken == null || deletionProtectionToken > 0) {
            "Deletion protection token must be positive"
        }
    }
}

/**
 * In-memory FIFO for indexing service commands.
 *
 * Android can deliver another start intent while a foreground service is already working. The
 * queue retains distinct requests instead of dropping them and also treats the active command as a
 * duplicate. All methods are expected to be called from the service's main thread.
 */
internal class FileIndexingCommandQueue {
    private val pendingCommands = ArrayDeque<FileIndexingCommand>()
    private var activeCommand: FileIndexingCommand? = null

    val hasPendingCommands: Boolean
        get() = pendingCommands.isNotEmpty()

    /**
     * Adds [command] unless an equal command is active or already pending.
     *
     * @return true when the command was added.
     */
    fun enqueue(command: FileIndexingCommand): Boolean {
        if (command == activeCommand || pendingCommands.contains(command)) {
            return false
        }
        pendingCommands.addLast(command)
        return true
    }

    /**
     * Marks and returns the next command, or null while another command is active.
     */
    fun startNext(): FileIndexingCommand? {
        if (activeCommand != null) {
            return null
        }
        return pendingCommands.pollFirst()?.also { activeCommand = it }
    }

    /**
     * Marks [command] complete. Passing a stale command is a programming error.
     */
    fun complete(command: FileIndexingCommand) {
        check(activeCommand == command) { "Completed command is not active" }
        activeCommand = null
    }

    /**
     * Removes commands that have not started. The active command remains tracked until it exits.
     */
    fun clearPending(): List<FileIndexingCommand> {
        val removedCommands = pendingCommands.toList()
        pendingCommands.clear()
        return removedCommands
    }

    /**
     * Removes ordinary queued scans while retaining deletion reconciliation commands.
     *
     * A user cancelling a long full scan must not discard correctness work queued after a physical
     * deletion.
     */
    fun clearPendingUnprotected() {
        val iterator = pendingCommands.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().deletionProtectionToken == null) {
                iterator.remove()
            }
        }
    }

    /**
     * Drops all state when the owning service is being destroyed.
     */
    fun reset(): List<FileIndexingCommand> {
        val removedCommands = buildList {
            activeCommand?.let(::add)
            addAll(pendingCommands)
        }
        pendingCommands.clear()
        activeCommand = null
        return removedCommands
    }
}
