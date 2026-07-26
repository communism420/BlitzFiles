/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blitzfiles.app.filejob.DeletedPathPrefixes
import com.blitzfiles.app.filejob.FileDeletionRecovery
import com.blitzfiles.app.filejob.FileDeletionState
import com.blitzfiles.app.filejob.FileDeletionStore
import com.blitzfiles.app.indexing.FileIndexingController
import com.blitzfiles.app.search.toEffectiveSearchQuery
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.model.SearchSortOrder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GlobalSearchUiState(
    val query: String = "",
    val sortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
    val hits: List<SearchHit> = emptyList(),
    val nextOffset: Long? = null,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val indexSnapshot: FileIndexingController.Snapshot? = null,
    val indexStatusError: Boolean = false
)

class GlobalSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = GlobalSearchRuntime.get(application)
    private val paginationSearchEngine = runtime.paginationSearch
    private val indexStatusRepository = runtime.indexStatusRepository
    private val mutableState = MutableStateFlow(GlobalSearchUiState())
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val requestGeneration = AtomicLong()
    private val instantResults = InstantSearchResults()

    val state: StateFlow<GlobalSearchUiState> = mutableState.asStateFlow()
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private var activeRequest: SearchRequest? = null
    private var interactiveSearchJob: Job? = null
    private var paginationJob: Job? = null
    private var indexStatusJob: Job? = null
    private var stableIndexRevision: StableIndexRevision? = null
    private var observedActiveIndexing = false
    private var readyPageVerificationGeneration: Long? = null
    private var deletedPaths = FileDeletionStore.state.value.deletedPaths
    private var observedFileSystemDeletionRevision = 0L
    private var observedIndexDeletionRevision = 0L

    init {
        FileDeletionRecovery.retry(application)
        viewModelScope.launch {
            // Opening and priming SQLite while the keyboard animates removes cold-start work from
            // the first character entered by the user.
            try {
                runtime.warmUpInteractiveSearch()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The real search reports a useful error if the database is genuinely unavailable.
            }
        }
        viewModelScope.launch {
            FileDeletionStore.state.collect(::onFileDeletionStateChanged)
        }
    }

    fun setQuery(query: String) {
        if (mutableState.value.query == query) {
            return
        }
        replaceRequest(query, mutableState.value.sortOrder)
    }

    fun setSortOrder(sortOrder: SearchSortOrder) {
        val currentState = mutableState.value
        if (currentState.sortOrder == sortOrder) {
            return
        }
        replaceRequest(currentState.query, sortOrder)
    }

    private fun replaceRequest(query: String, sortOrder: SearchSortOrder) {
        val safeQuery = query.toSafeSearchQuery()
        val normalizedQuery = safeQuery.toEffectiveSearchQuery()
        val previousRequest = activeRequest
        val request = normalizedQuery.takeIf(String::isNotEmpty)?.let {
            SearchRequest(
                query = it,
                sortOrder = sortOrder,
                limit = PAGE_SIZE
            )
        }
        if (request == activeRequest) {
            // Whitespace-only edits keep the same normalized request. MutableStateFlow suppresses
            // equal values, so clearing the results here would leave the UI waiting forever.
            mutableState.update {
                it.copy(query = safeQuery, sortOrder = sortOrder)
            }
            return
        }

        val generation = requestGeneration.incrementAndGet()
        readyPageVerificationGeneration = null
        interactiveSearchJob?.cancel()
        interactiveSearchJob = null
        paginationJob?.cancel()
        paginationJob = null
        val cachedPage = request?.let(instantResults::getReadyPage)
        val immediateHits = when {
            request == null -> emptyList()
            cachedPage != null -> cachedPage.withoutDeletedPaths(deletedPaths).hits
            else -> filterProvisionalHits(previousRequest, request, mutableState.value.hits)
                ?: emptyList()
        }
        mutableState.update {
            it.copy(
                query = safeQuery,
                sortOrder = sortOrder,
                hits = immediateHits,
                nextOffset = cachedPage?.nextOffset,
                isSearching = request != null && cachedPage == null,
                isLoadingMore = false,
                errorMessage = null
            )
        }
        activeRequest = request
        if (request != null) {
            // A ready page is displayed as final immediately. This silent verification preserves
            // correctness if indexing changed before the status observer noticed its new revision.
            launchInteractiveSearch(
                request = request,
                generation = generation,
                isReadyPageVerification = cachedPage != null
            )
        }
    }

    fun retrySearch() {
        val request = activeRequest ?: return
        refreshActiveSearch(request, clearVisibleHits = true)
    }

    private fun refreshActiveSearch(
        request: SearchRequest,
        clearVisibleHits: Boolean = false
    ) {
        val generation = requestGeneration.incrementAndGet()
        readyPageVerificationGeneration = null
        paginationJob?.cancel()
        paginationJob = null
        mutableState.update {
            it.copy(
                hits = if (clearVisibleHits) emptyList() else it.hits,
                nextOffset = null,
                isSearching = true,
                isLoadingMore = false,
                errorMessage = null
            )
        }
        launchInteractiveSearch(request, generation)
    }

    /**
     * Starts the newest request without joining a cancelled native SQLite call.
     *
     * The reader pool gives the new query the first available connection. Generation checks prevent
     * a late result from an uninterruptible statement from reaching the UI.
     */
    private fun launchInteractiveSearch(
        request: SearchRequest,
        generation: Long,
        isReadyPageVerification: Boolean = false
    ) {
        interactiveSearchJob?.cancel()
        if (isReadyPageVerification) {
            readyPageVerificationGeneration = generation
        }
        interactiveSearchJob = viewModelScope.launch {
            try {
                val page = runtime.searchInteractively(request)
                    .withoutDeletedPaths(deletedPaths)
                if (
                    generation != requestGeneration.get() ||
                    activeRequest != request
                ) {
                    return@launch
                }
                paginationJob?.cancel()
                paginationJob = null
                instantResults.put(request, page)
                mutableState.update { current ->
                    if (!current.matches(request)) {
                        current
                    } else {
                        current.copy(
                            hits = page.hits,
                            nextOffset = page.nextOffset,
                            isSearching = false,
                            isLoadingMore = false,
                            errorMessage = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    generation == requestGeneration.get() &&
                    activeRequest == request
                ) {
                    if (isReadyPageVerification) {
                        mutableMessages.tryEmit(error.safeMessage())
                    } else {
                        mutableState.update {
                            it.copy(
                                hits = emptyList(),
                                nextOffset = null,
                                isSearching = false,
                                isLoadingMore = false,
                                errorMessage = error.safeMessage()
                            )
                        }
                    }
                }
            } finally {
                if (readyPageVerificationGeneration == generation) {
                    readyPageVerificationGeneration = null
                }
            }
        }
    }

    fun loadNextPage() {
        val currentState = mutableState.value
        val nextOffset = currentState.nextOffset ?: return
        if (
            currentState.isSearching ||
            currentState.isLoadingMore ||
            readyPageVerificationGeneration != null ||
            paginationJob?.isActive == true
        ) {
            return
        }
        val request = activeRequest ?: return
        val generation = requestGeneration.get()
        mutableState.update { it.copy(isLoadingMore = true) }
        paginationJob = viewModelScope.launch {
            try {
                val page = paginationSearchEngine.search(request.copy(offset = nextOffset))
                    .withoutDeletedPaths(deletedPaths)
                if (generation != requestGeneration.get() || activeRequest != request) {
                    return@launch
                }
                mutableState.update { state ->
                    state.copy(
                        hits = appendUniqueHits(state.hits, page.hits),
                        nextOffset = page.nextOffset,
                        isLoadingMore = false
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == requestGeneration.get()) {
                    mutableState.update { it.copy(isLoadingMore = false) }
                    mutableMessages.tryEmit(error.safeMessage())
                }
            }
        }
    }

    private fun onFileDeletionStateChanged(deletionState: FileDeletionState) {
        val previousDeletedPaths = deletedPaths
        deletedPaths = deletionState.deletedPaths
        if (deletionState.fileSystemRevision != observedFileSystemDeletionRevision) {
            observedFileSystemDeletionRevision = deletionState.fileSystemRevision
            val indexedPathsChanged =
                previousDeletedPaths.indexPathPrefixes != deletedPaths.indexPathPrefixes
            if (indexedPathsChanged) {
                val releasedIndexedPath = previousDeletedPaths.indexPathPrefixes.any { path ->
                    !deletedPaths.containsIndexPath(path)
                }
                requestGeneration.incrementAndGet()
                readyPageVerificationGeneration = null
                interactiveSearchJob?.cancel()
                interactiveSearchJob = null
                paginationJob?.cancel()
                paginationJob = null
                instantResults.clear()
                mutableState.update { current ->
                    current.copy(
                        hits = current.hits.withoutDeletedPaths(deletedPaths),
                        nextOffset = null,
                        isSearching = false,
                        isLoadingMore = false
                    )
                }
                if (
                    releasedIndexedPath &&
                    deletionState.indexRevision == observedIndexDeletionRevision
                ) {
                    activeRequest?.let { request ->
                        launchInteractiveSearch(
                            request = request,
                            generation = requestGeneration.get(),
                            isReadyPageVerification = true
                        )
                    }
                }
            }
        }
        if (deletionState.indexRevision != observedIndexDeletionRevision) {
            observedIndexDeletionRevision = deletionState.indexRevision
            val request = activeRequest ?: return
            val generation = requestGeneration.incrementAndGet()
            readyPageVerificationGeneration = null
            interactiveSearchJob?.cancel()
            interactiveSearchJob = null
            paginationJob?.cancel()
            paginationJob = null
            instantResults.clear()
            mutableState.update { current ->
                current.copy(
                    hits = current.hits.withoutDeletedPaths(deletedPaths),
                    nextOffset = null,
                    isSearching = false,
                    isLoadingMore = false,
                    errorMessage = null
                )
            }
            // SQLite/FTS has committed the deletion. Refill the first page silently so removing a
            // row never leaves a pagination gap or flashes the progress indicator.
            launchInteractiveSearch(
                request = request,
                generation = generation,
                isReadyPageVerification = true
            )
        }
    }

    fun startIndexStatusUpdates() {
        if (indexStatusJob?.isActive == true) {
            return
        }
        indexStatusJob = viewModelScope.launch {
            delay(INITIAL_STATUS_DELAY_MILLIS)
            while (isActive) {
                val snapshot = try {
                    FileIndexingController.getSnapshot(indexStatusRepository)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                mutableState.update {
                    it.copy(
                        indexSnapshot = snapshot ?: it.indexSnapshot,
                        indexStatusError = snapshot == null
                    )
                }
                val isIndexing = snapshot?.roots.orEmpty().any { root ->
                    root.lastScanStatus == IndexScanStatus.RUNNING ||
                        root.lastScanStatus == IndexScanStatus.PAUSED
                }
                if (isIndexing) {
                    observedActiveIndexing = true
                }
                if (snapshot != null && !isIndexing) {
                    val revision = snapshot.stableRevision()
                    val previousRevision = stableIndexRevision
                    stableIndexRevision = revision
                    val shouldRefreshSearch =
                        observedActiveIndexing ||
                            previousRevision != null && previousRevision != revision
                    observedActiveIndexing = false
                    if (shouldRefreshSearch) {
                        instantResults.clear()
                        activeRequest?.let { request ->
                            refreshActiveSearch(request)
                        }
                    }
                }
                delay(if (isIndexing) ACTIVE_STATUS_REFRESH_MILLIS else IDLE_STATUS_REFRESH_MILLIS)
            }
        }
    }

    fun stopIndexStatusUpdates() {
        indexStatusJob?.cancel()
        indexStatusJob = null
    }

    private fun GlobalSearchUiState.matches(request: SearchRequest): Boolean =
        query.toEffectiveSearchQuery() == request.query && sortOrder == request.sortOrder

    private fun Throwable.safeMessage(): String =
        message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    companion object {
        private const val PAGE_SIZE = 64
        private const val INITIAL_STATUS_DELAY_MILLIS = 200L
        private const val ACTIVE_STATUS_REFRESH_MILLIS = 1_000L
        private const val IDLE_STATUS_REFRESH_MILLIS = 2_000L
    }
}

private data class StableIndexRevision(
    val lastIndexedAtMillis: Long?,
    val roots: List<StableRootRevision>
)

private data class StableRootRevision(
    val id: Long?,
    val scanGeneration: Long,
    val lastScanCompletedAtMillis: Long?,
    val isEnabled: Boolean
)

private fun FileIndexingController.Snapshot.stableRevision(): StableIndexRevision =
    StableIndexRevision(
        lastIndexedAtMillis = statistics.lastIndexedAtMillis,
        roots = roots
            .sortedBy { root -> root.id }
            .map { root ->
                StableRootRevision(
                    id = root.id,
                    scanGeneration = root.scanGeneration,
                    lastScanCompletedAtMillis = root.lastScanCompletedAtMillis,
                    isEnabled = root.isEnabled
                )
            }
    )

private fun String.toSafeSearchQuery(): String {
    val withoutNul = if ('\u0000' in this) replace("\u0000", "") else this
    if (withoutNul.length <= SearchRequest.MAX_QUERY_LENGTH) {
        return withoutNul
    }

    var endIndex = SearchRequest.MAX_QUERY_LENGTH
    // Do not leave an unpaired surrogate when defensive truncation handles a non-UI caller.
    if (
        Character.isHighSurrogate(withoutNul[endIndex - 1]) &&
        Character.isLowSurrogate(withoutNul[endIndex])
    ) {
        --endIndex
    }
    return withoutNul.substring(0, endIndex)
}
