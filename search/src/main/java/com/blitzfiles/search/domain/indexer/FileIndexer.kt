/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.indexer

import com.blitzfiles.search.domain.model.IndexingRequest
import com.blitzfiles.search.domain.model.IndexingResult
import com.blitzfiles.search.domain.model.IndexingState
import kotlinx.coroutines.flow.StateFlow

/**
 * Filesystem traversal boundary shared by standard and root-aware implementations.
 */
interface FileIndexer {
    val state: StateFlow<IndexingState>

    suspend fun run(request: IndexingRequest): IndexingResult

    suspend fun pause()

    suspend fun resume()

    suspend fun cancel()
}
