/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.search

import android.content.Context
import androidx.sqlite.SQLiteStatement
import com.blitzfiles.search.data.database.IndexDatabase
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchPage
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.search.SearchEngine
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SQLiteSearchEngine internal constructor(
    private val database: IndexDatabase
) : SearchEngine {
    override suspend fun search(request: SearchRequest): SearchPage {
        val context = currentCoroutineContext()
        val noMatchProbe = SearchQueryCompiler.compileNoMatchProbe(request)
        if (noMatchProbe != null && !hasAnyRow(noMatchProbe, context)) {
            return SearchPage(
                hits = emptyList(),
                nextOffset = null,
                totalCount = 0L
            )
        }
        val fastQuery = SearchQueryCompiler.compileIndexedPreflight(request)
        if (fastQuery != null) {
            val fastPage = execute(request, fastQuery, context)
            if (fastPage.nextOffset != null) {
                return fastPage
            }
        }
        return execute(request, SearchQueryCompiler.compile(request), context)
    }

    private suspend fun hasAnyRow(
        query: CompiledSearchQuery,
        context: CoroutineContext
    ): Boolean =
        database.read { connection ->
            context.ensureActive()
            connection.prepare(query.sql).use { statement ->
                statement.bind(query.arguments)
                val hasRow = statement.step()
                context.ensureActive()
                hasRow
            }
        }

    /**
     * Opens and primes the long-lived read connection before the user enters the first query.
     */
    suspend fun warmUp() {
        val context = currentCoroutineContext()
        database.read { connection ->
            context.ensureActive()
            WARM_UP_QUERIES.forEach { sql ->
                connection.prepare(sql).use { statement ->
                    statement.step()
                }
                context.ensureActive()
            }
        }
    }

    private suspend fun execute(
        request: SearchRequest,
        query: CompiledSearchQuery,
        context: CoroutineContext
    ): SearchPage =
        database.read { connection ->
            context.ensureActive()
            connection.prepare(query.sql).use { statement ->
                statement.bind(query.arguments)
                val hits = buildList(request.limit + 1) {
                    while (statement.step()) {
                        context.ensureActive()
                        add(statement.readSearchHit())
                    }
                    context.ensureActive()
                }
                val hasMore = hits.size > request.limit
                SearchPage(
                    hits = if (hasMore) hits.dropLast(1) else hits,
                    nextOffset = if (hasMore) request.offset + request.limit else null,
                    totalCount = null
                )
            }
        }

    override suspend fun close() {
        database.close()
    }

    companion object {
        private val WARM_UP_QUERIES = listOf(
            "SELECT id FROM indexed_files INDEXED BY indexed_files_name LIMIT 1",
            "SELECT id FROM indexed_files INDEXED BY indexed_files_short_name_scan LIMIT 1",
            "SELECT rowid FROM indexed_file_names_trigram_fts LIMIT 1"
        )

        @JvmStatic
        fun create(context: Context): SQLiteSearchEngine =
            SQLiteSearchEngine(IndexDatabase.create(context))
    }
}

private fun SQLiteStatement.bind(arguments: List<SearchSqlArgument>) {
    arguments.forEachIndexed { index, argument ->
        when (argument) {
            is SearchSqlArgument.Integer -> bindLong(index + 1, argument.value)
            is SearchSqlArgument.Text -> bindText(index + 1, argument.value)
        }
    }
}

private fun SQLiteStatement.readSearchHit(): SearchHit {
    val matchRank = getLong(19)
    check(matchRank in 0..3) { "Unexpected search match rank: $matchRank" }
    val ftsRank = getDouble(20)
    val entry = IndexedFileRecord(
        id = getLong(0),
        rootId = getLong(1),
        path = getText(2),
        parentPath = getText(3),
        name = getText(4),
        extension = getNullableText(5),
        mimeType = getNullableText(6),
        sizeBytes = getLong(7),
        modifiedAtMillis = getLong(8),
        createdAtMillis = getNullableLong(9),
        indexedAtMillis = getLong(10),
        isDirectory = getLong(11) != 0L,
        isSymbolicLink = getLong(12) != 0L,
        isHidden = getLong(13) != 0L,
        requiresRoot = getLong(14) != 0L,
        symbolicLinkTarget = getNullableText(15),
        deviceId = getNullableLong(16),
        inode = getNullableLong(17),
        scanGeneration = getLong(18)
    )
    return SearchHit(
        entry = entry,
        relevance = (MAX_MATCH_RANK - matchRank) * MATCH_RANK_BUCKET - ftsRank
    )
}

private fun SQLiteStatement.getNullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun SQLiteStatement.getNullableText(index: Int): String? =
    if (isNull(index)) null else getText(index)

private const val MAX_MATCH_RANK = 4
private const val MATCH_RANK_BUCKET = 1_000.0
