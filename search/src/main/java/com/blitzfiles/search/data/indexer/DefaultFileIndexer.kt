/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.indexer

import com.blitzfiles.search.domain.indexer.FileIndexer
import com.blitzfiles.search.domain.indexer.IndexFileMetadata
import com.blitzfiles.search.domain.indexer.IndexFileSystem
import com.blitzfiles.search.domain.indexer.IndexSafetyPolicy
import com.blitzfiles.search.domain.indexer.isSameOrDescendantOf
import com.blitzfiles.search.domain.indexer.withoutDescendantDuplicates
import com.blitzfiles.search.domain.model.IndexExclusion
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.IndexingMode
import com.blitzfiles.search.domain.model.IndexingRequest
import com.blitzfiles.search.domain.model.IndexingResult
import com.blitzfiles.search.domain.model.IndexingState
import com.blitzfiles.search.domain.repository.IndexRepository
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Iterative, bounded-memory file indexer.
 *
 * Database writes are batched, directory identities prevent symbolic-link cycles and duplicate
 * bind-mount traversal, and stale rows are pruned only when the corresponding traversal completed
 * without access errors.
 */
class DefaultFileIndexer(
    private val repository: IndexRepository,
    private val fileSystem: IndexFileSystem,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val canContinue: () -> Boolean = { true }
) : FileIndexer {
    private val runMutex = Mutex()
    private val pauseSignal = MutableStateFlow(false)
    private val mutableState = MutableStateFlow<IndexingState>(IndexingState.Idle)

    override val state: StateFlow<IndexingState> = mutableState.asStateFlow()

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeScan: ActiveScan? = null

    @Volatile
    private var runActive = false

    @Volatile
    private var sessionScannedEntryCount = 0L

    @Volatile
    private var sessionIndexedEntryCount = 0L

    init {
        require(batchSize > 0) { "Batch size must be positive" }
    }

    override suspend fun run(request: IndexingRequest): IndexingResult =
        runMutex.withLock {
            cancelRequested = false
            pauseSignal.value = false
            runActive = true
            sessionScannedEntryCount = 0
            sessionIndexedEntryCount = 0
            val startedAtMillis = clockMillis()
            val counters = Counters()
            try {
                val rootsById = repository.getRoots().associateBy { it.id }
                val enabledRoots = rootsById.values.filter(IndexRoot::isEnabled)
                val roots = request.rootIds.map { rootId ->
                    val root = checkNotNull(rootsById[rootId]) {
                        "Index root was not found: $rootId"
                    }
                    check(root.isEnabled) { "Index root is disabled: $rootId" }
                    root
                }
                val exclusions = repository.getExclusions().filter { it.isEnabled }
                withContext(ioDispatcher) {
                    roots.forEach { root ->
                        checkpoint()
                        indexRoot(root, enabledRoots, request, exclusions, counters)
                    }
                }
                val result = counters.toResult(clockMillis() - startedAtMillis)
                runActive = false
                mutableState.value = IndexingState.Completed(result)
                result
            } catch (error: CancellationException) {
                finishActiveScan(IndexScanStatus.CANCELLED, null)
                runActive = false
                mutableState.value = IndexingState.Cancelled(
                    counters.scannedEntryCount,
                    counters.indexedEntryCount
                )
                throw error
            } catch (error: Throwable) {
                finishActiveScan(IndexScanStatus.FAILED, error.safeMessage())
                runActive = false
                mutableState.value = IndexingState.Failed(error)
                throw error
            } finally {
                activeScan = null
                pauseSignal.value = false
                cancelRequested = false
                runActive = false
            }
        }

    override suspend fun pause() {
        if (!runActive) {
            return
        }
        pauseSignal.value = true
        val scan = activeScan
        mutableState.value = IndexingState.Paused(
            scan?.rootId,
            sessionScannedEntryCount,
            sessionIndexedEntryCount
        )
        if (scan != null) {
            repository.updateScanStatus(
                scan.rootId,
                scan.generation,
                IndexScanStatus.PAUSED
            )
        }
    }

    override suspend fun resume() {
        if (!runActive) {
            return
        }
        pauseSignal.value = false
        val scan = activeScan
        if (scan != null) {
            repository.updateScanStatus(
                scan.rootId,
                scan.generation,
                IndexScanStatus.RUNNING
            )
            publishRunning(scan, force = true)
        }
    }

    override suspend fun cancel() {
        if (!runActive) {
            return
        }
        cancelRequested = true
        pauseSignal.value = false
    }

    private suspend fun indexRoot(
        rootWithoutGeneration: IndexRoot,
        enabledRoots: List<IndexRoot>,
        request: IndexingRequest,
        allExclusions: List<IndexExclusion>,
        counters: Counters
    ) {
        val rootId = checkNotNull(rootWithoutGeneration.id)
        val generation = repository.beginScan(rootId, clockMillis())
        val root = rootWithoutGeneration.copy(scanGeneration = generation)
        activeScan = ActiveScan(rootId, generation)
        checkpoint()
        publishRunning(checkNotNull(activeScan), force = true)

        val normalizedRoot = fileSystem.normalize(root.path, root.accessMode).trimTrailingSeparators()
        val normalizedRootConfig = root.copy(path = normalizedRoot)
        val configuredExclusions = allExclusions
            .filter { it.rootId == null || it.rootId == rootId }
            .map { exclusion ->
                fileSystem.normalize(exclusion.pathPrefix, root.accessMode)
                    .trimTrailingSeparators()
            }
        // Every path belongs to its deepest enabled configured root. Excluding descendant roots
        // from their ancestors prevents overlapping scans from moving the same globally unique
        // indexed_files row between root IDs and later deleting it as stale.
        val descendantRootExclusions = enabledRoots
            .asSequence()
            .filter { candidate -> candidate.id != rootId }
            .map { candidate ->
                fileSystem.normalize(candidate.path, root.accessMode).trimTrailingSeparators()
            }
            .filter { candidatePath ->
                candidatePath != normalizedRoot &&
                    candidatePath.isSameOrDescendantOf(normalizedRoot)
            }
            .toList()
        val exclusions = IndexSafetyPolicy.effectiveExclusions(
            normalizedRoot,
            configuredExclusions + descendantRootExclusions
        )

        counters.removedEntryCount += repository.deleteEntriesUnder(rootId, exclusions)

        val requestedHints = request.pathHints[rootId].orEmpty()
        val targets = if (request.mode == IndexingMode.INCREMENTAL && requestedHints.isNotEmpty()) {
            requestedHints.map { hint ->
                fileSystem.normalize(hint, root.accessMode).trimTrailingSeparators()
            }.filter { it.isSameOrDescendantOf(normalizedRoot) }
                .also {
                    require(it.size == requestedHints.size) {
                        "Every incremental path must be inside root $normalizedRoot"
                    }
                }
                .withoutDescendantDuplicates()
        } else {
            listOf(normalizedRoot)
        }

        var rootHadRecoverableErrors = false
        var firstRootError: String? = null
        val rootErrorCountBeforeScan = counters.recoverableErrorCount
        targets.forEach { target ->
            checkpoint()
            if (exclusions.any { target.isSameOrDescendantOf(it) }) {
                counters.skippedEntryCount++
                counters.removedEntryCount += repository.deleteEntriesUnder(rootId, target)
                return@forEach
            }

            val outcome = scanTarget(normalizedRootConfig, target, exclusions, counters)
            when {
                !outcome.exists &&
                    target == normalizedRoot &&
                    !request.treatMissingRootsAsDeleted -> {
                    rootHadRecoverableErrors = true
                    firstRootError = firstRootError
                        ?: outcome.firstRecoverableError
                        ?: "Index root does not exist: $normalizedRoot"
                    if (!outcome.hadRecoverableErrors) {
                        counters.recoverableErrorCount++
                    }
                }
                outcome.hadRecoverableErrors -> {
                    rootHadRecoverableErrors = true
                    firstRootError = firstRootError ?: outcome.firstRecoverableError
                }
                !outcome.exists -> {
                    counters.removedEntryCount += repository.deleteEntriesUnder(rootId, target)
                }
                targets.size == 1 && target == normalizedRoot -> {
                    counters.removedEntryCount += repository.deleteStaleEntries(rootId, generation)
                }
                else -> {
                    counters.removedEntryCount += repository.deleteStaleEntriesUnder(
                        rootId,
                        generation,
                        target
                    )
                }
            }
        }

        val status = if (rootHadRecoverableErrors) {
            IndexScanStatus.COMPLETED_WITH_ERRORS
        } else {
            IndexScanStatus.COMPLETED
        }
        val rootErrorCount = counters.recoverableErrorCount - rootErrorCountBeforeScan
        val errorMessage = if (rootHadRecoverableErrors) {
            buildString {
                append(firstRootError ?: "Some entries could not be read")
                if (rootErrorCount > 1) {
                    append(" (")
                    append(rootErrorCount)
                    append(" recoverable errors)")
                }
            }.take(MAX_ERROR_LENGTH)
        } else {
            null
        }
        repository.updateScanStatus(
            rootId,
            generation,
            status,
            completedAtMillis = clockMillis(),
            errorMessage = errorMessage
        )
        activeScan = null
    }

    private suspend fun scanTarget(
        root: IndexRoot,
        target: String,
        exclusions: List<String>,
        counters: Counters
    ): TargetOutcome {
        val paths = ArrayDeque<String>()
        // Android exposes the same directory trees through several bind-mount aliases even when
        // symbolic links are not followed. A successful identity is traversed only once. Failed
        // identities get a small number of alternate-alias attempts because SELinux may allow one
        // mount view while rejecting another.
        val completedDirectoryIdentities = HashSet<String>()
        val failedDirectoryVisitCounts = HashMap<String, Int>()
        val batch = ArrayList<IndexedFileRecord>(batchSize)
        val exclusionMatcher = PathPrefixMatcher(exclusions)
        val indexedAtMillis = clockMillis()
        var targetExists = false
        var hadRecoverableErrors = false
        var firstRecoverableError: String? = null
        paths.add(target)

        while (paths.isNotEmpty()) {
            checkpoint()
            val path = paths.removeLast()
            if (exclusionMatcher.matches(path)) {
                counters.skippedEntryCount++
                continue
            }

            val metadata = try {
                fileSystem.readMetadata(path, root.accessMode, root.followSymbolicLinks)
            } catch (error: Throwable) {
                if (!error.isRecoverableFileError()) {
                    throw error
                }
                hadRecoverableErrors = true
                counters.recoverableErrorCount++
                firstRecoverableError = firstRecoverableError
                    ?: error.recoverableMessage(path)
                continue
            } ?: continue

            if (path == target) {
                targetExists = true
            }
            counters.scannedEntryCount++
            if (!root.includeHidden && metadata.isHidden && path != root.path) {
                counters.skippedEntryCount++
                continue
            }

            batch += metadata.toRecord(root, indexedAtMillis)
            counters.indexedEntryCount++
            updateActiveProgress(checkNotNull(root.id), path, counters)
            if (batch.size >= batchSize) {
                repository.upsertEntries(batch)
                batch.clear()
            }

            if (!metadata.isDirectory) {
                continue
            }
            val shouldSkipSymbolicLinkTraversal =
                root.followSymbolicLinks &&
                    metadata.isSymbolicLink &&
                    metadata.symbolicLinkTarget?.let(IndexSafetyPolicy::isProtected) != false
            if (shouldSkipSymbolicLinkTraversal) {
                // Keep the link in the index, but never let it bypass protected-tree exclusions.
                counters.skippedEntryCount++
                continue
            }
            val traversalIdentity = metadata.traversalIdentity
            if (
                traversalIdentity in completedDirectoryIdentities ||
                (failedDirectoryVisitCounts[traversalIdentity] ?: 0) >=
                    MAX_DIRECTORY_VISIT_ATTEMPTS
            ) {
                counters.skippedEntryCount++
                continue
            }
            try {
                fileSystem.visitChildren(metadata.path, root.accessMode) { child ->
                    ensureExecutionAllowed()
                    paths.add(child)
                }
                completedDirectoryIdentities += traversalIdentity
                failedDirectoryVisitCounts.remove(traversalIdentity)
            } catch (error: Throwable) {
                if (!error.isRecoverableFileError()) {
                    throw error
                }
                failedDirectoryVisitCounts[traversalIdentity] =
                    (failedDirectoryVisitCounts[traversalIdentity] ?: 0) + 1
                hadRecoverableErrors = true
                counters.recoverableErrorCount++
                firstRecoverableError = firstRecoverableError
                    ?: error.recoverableMessage(metadata.path)
            }
        }

        checkpoint()
        repository.upsertEntries(batch)
        return TargetOutcome(targetExists, hadRecoverableErrors, firstRecoverableError)
    }

    private suspend fun checkpoint() {
        ensureExecutionAllowed()
        if (cancelRequested) {
            throw IndexingCancelledException()
        }
        if (pauseSignal.value) {
            val scan = activeScan
            if (scan != null) {
                repository.updateScanStatus(
                    scan.rootId,
                    scan.generation,
                    IndexScanStatus.PAUSED
                )
            }
            mutableState.value = IndexingState.Paused(
                scan?.rootId,
                sessionScannedEntryCount,
                sessionIndexedEntryCount
            )
            pauseSignal.first { !it }
            if (scan != null && activeScan === scan) {
                repository.updateScanStatus(
                    scan.rootId,
                    scan.generation,
                    IndexScanStatus.RUNNING
                )
                publishRunning(scan, force = true)
            }
        }
        ensureExecutionAllowed()
        if (cancelRequested) {
            throw IndexingCancelledException()
        }
    }

    private fun ensureExecutionAllowed() {
        check(canContinue()) {
            "Required storage access was revoked during indexing"
        }
    }

    private fun updateActiveProgress(rootId: Long, path: String, counters: Counters) {
        val scan = activeScan ?: return
        if (scan.rootId != rootId) {
            return
        }
        scan.currentPath = path
        scan.scannedEntryCount = counters.scannedEntryCount
        scan.indexedEntryCount = counters.indexedEntryCount
        sessionScannedEntryCount = counters.scannedEntryCount
        sessionIndexedEntryCount = counters.indexedEntryCount
        publishRunning(scan)
    }

    private fun publishRunning(scan: ActiveScan, force: Boolean = false) {
        val nowNanos = System.nanoTime()
        if (!force && nowNanos - scan.lastProgressNanos < PROGRESS_INTERVAL_NANOS) {
            return
        }
        scan.lastProgressNanos = nowNanos
        mutableState.value = IndexingState.Running(
            scan.rootId,
            scan.currentPath,
            scan.scannedEntryCount,
            scan.indexedEntryCount
        )
    }

    private suspend fun finishActiveScan(status: IndexScanStatus, errorMessage: String?) {
        val scan = activeScan ?: return
        withContext(NonCancellable) {
            repository.updateScanStatus(
                scan.rootId,
                scan.generation,
                status,
                completedAtMillis = clockMillis(),
                errorMessage = errorMessage
            )
        }
    }

    private class ActiveScan(
        val rootId: Long,
        val generation: Long,
        @Volatile var currentPath: String? = null,
        @Volatile var scannedEntryCount: Long = 0,
        @Volatile var indexedEntryCount: Long = 0,
        var lastProgressNanos: Long = 0
    )

    private data class TargetOutcome(
        val exists: Boolean,
        val hadRecoverableErrors: Boolean,
        val firstRecoverableError: String?
    )

    private class Counters {
        var scannedEntryCount = 0L
        var indexedEntryCount = 0L
        var removedEntryCount = 0L
        var skippedEntryCount = 0L
        var recoverableErrorCount = 0L

        fun toResult(durationMillis: Long) = IndexingResult(
            scannedEntryCount,
            indexedEntryCount,
            removedEntryCount,
            skippedEntryCount,
            recoverableErrorCount,
            durationMillis.coerceAtLeast(0)
        )
    }

    private class IndexingCancelledException : CancellationException("Indexing was cancelled")

    companion object {
        private const val DEFAULT_BATCH_SIZE = 1_024
        private const val MAX_DIRECTORY_VISIT_ATTEMPTS = 3
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }
}

private class PathPrefixMatcher(prefixes: Collection<String>) {
    private val prefixSet = prefixes.toHashSet()

    fun matches(path: String): Boolean {
        var candidate = path
        while (true) {
            if (candidate in prefixSet) {
                return true
            }
            if (candidate == "/") {
                return false
            }
            candidate = candidate.substringBeforeLast('/').ifEmpty { "/" }
        }
    }
}

private fun IndexFileMetadata.toRecord(root: IndexRoot, indexedAtMillis: Long) =
    IndexedFileRecord(
        rootId = checkNotNull(root.id),
        path = path,
        parentPath = parentPath,
        name = name,
        extension = extension,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        createdAtMillis = createdAtMillis,
        indexedAtMillis = indexedAtMillis,
        isDirectory = isDirectory,
        isSymbolicLink = isSymbolicLink,
        isHidden = isHidden,
        requiresRoot = root.accessMode == com.blitzfiles.search.domain.model.IndexAccessMode.ROOT,
        symbolicLinkTarget = symbolicLinkTarget,
        deviceId = deviceId,
        inode = inode,
        scanGeneration = root.scanGeneration
    )

private fun String.trimTrailingSeparators(): String =
    if (length > 1) trimEnd('/') else this

private fun Throwable.isRecoverableFileError(): Boolean =
    this is IOException ||
        this is SecurityException

private fun Throwable.safeMessage(): String =
    (message ?: javaClass.simpleName).take(MAX_ERROR_LENGTH)

private fun Throwable.recoverableMessage(path: String): String =
    "$path: ${safeMessage()}".take(MAX_ERROR_LENGTH)

private const val MAX_ERROR_LENGTH = 2_000
