/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import android.content.Context
import com.blitzfiles.search.data.repository.SQLiteIndexRepository
import com.blitzfiles.search.data.search.SQLiteSearchEngine
import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest

/**
 * Process-scoped read runtime for interactive search.
 *
 * Keeping these WAL readers alive avoids reopening, reconfiguring and warming SQLite whenever the
 * search screen is revisited. Pagination has its own connection so a cancelled deep-page query can
 * never delay the next as-you-type first page.
 */
internal object GlobalSearchRuntime {
    @Volatile
    private var instance: Connections? = null

    fun get(context: Context): Connections =
        instance ?: synchronized(this) {
            instance ?: Connections(context.applicationContext).also { instance = it }
        }

    class Connections internal constructor(context: Context) {
        private val interactiveSearch = List(INTERACTIVE_READER_COUNT) {
            SQLiteSearchEngine.create(context)
        }
        private val interactiveSearchPool = AvailableSearchEnginePool(interactiveSearch)

        val paginationSearch = SQLiteSearchEngine.create(context)
        val indexStatusRepository = SQLiteIndexRepository.create(context)
        private val indexedRootDirectorySearch = IndexedRootDirectorySearch(
            loadRoots = indexStatusRepository::getRoots,
            searchPage = ::searchInteractively,
            canonicalPathResolver = AndroidIndexedDirectoryCanonicalPathResolver()
        )

        suspend fun searchInteractively(request: SearchRequest): SearchPage =
            interactiveSearchPool.search(request)

        suspend fun searchIndexedRootDirectory(
            directoryPath: String,
            query: String,
            maxResults: Int,
            onTruncated: () -> Unit,
            onPathBatch: (List<String>) -> Unit
        ): Boolean =
            indexedRootDirectorySearch.trySearch(
                directoryPath = directoryPath,
                query = query,
                maxResults = maxResults,
                onTruncated = onTruncated,
                onPathBatch = onPathBatch
            )

        suspend fun warmUpInteractiveSearch() {
            // Warm readers sequentially so one connection always remains available to the user.
            repeat(INTERACTIVE_READER_COUNT) {
                interactiveSearchPool.withEngine { engine -> engine.warmUp() }
            }
        }
    }

    private const val INTERACTIVE_READER_COUNT = 2
}
