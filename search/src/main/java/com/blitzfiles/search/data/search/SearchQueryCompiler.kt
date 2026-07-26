/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.search

import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.model.SearchQueryMode
import com.blitzfiles.search.domain.model.SearchSortOrder

/**
 * Compiles the small BlitzFiles query language into a parameterized SQLite statement.
 *
 * In pattern mode, whitespace-separated terms are combined with AND, asterisks match zero or more
 * characters and question marks match exactly one character. Literal-substring mode treats the
 * complete query as one filename fragment. SQL wildcards and FTS5 operators are always escaped.
 */
internal object SearchQueryCompiler {
    fun compile(request: SearchRequest): CompiledSearchQuery {
        val analysis = request.toQueryAnalysis()
        return if (request.sortOrder == SearchSortOrder.RELEVANCE) {
            compileBoundedRelevance(request, analysis)
        } else {
            compileExhaustive(request, analysis)
        }
    }

    /**
     * Compiles a tiny necessary-condition probe for queries backed by trigram FTS.
     *
     * A missing trigram candidate proves that the complete query has no result, regardless of
     * sorting or metadata filters. Running this before the relevance tiers avoids their filename
     * prefix scans for the common no-match case.
     */
    fun compileNoMatchProbe(request: SearchRequest): CompiledSearchQuery? {
        val analysis = request.toQueryAnalysis()
        if (!analysis.usesTrigramIndex) {
            return null
        }
        val usesAbbreviatedProbe = analysis.trigramSegments.any { segment ->
            segment.codePointLength() > MAX_NO_MATCH_PROBE_LITERAL_LENGTH
        }
        val probeSegments = if (usesAbbreviatedProbe) {
            analysis.trigramSegments
                .flatMap { segment -> segment.toNoMatchProbeSegments() }
                .distinct()
        } else {
            analysis.trigramSegments
        }
        val arguments = mutableListOf<SearchSqlArgument>(
            SearchSqlArgument.Text(
                probeSegments.toFtsAndQuery().withFtsRootScope(request.rootIds)
            )
        )
        if (usesAbbreviatedProbe) {
            arguments += SearchSqlArgument.Integer(analysis.minimumNameCodePointLength.toLong())
        }
        return CompiledSearchQuery(
            sql = if (usesAbbreviatedProbe) {
                """
                SELECT 1
                FROM $TRIGRAM_TABLE
                JOIN indexed_files AS f ON f.id = $TRIGRAM_TABLE.rowid
                WHERE $TRIGRAM_TABLE MATCH ?
                    AND length(f.name) >= ?
                LIMIT 1
                """.trimIndent()
            } else {
                """
                SELECT 1
                FROM $TRIGRAM_TABLE
                WHERE $TRIGRAM_TABLE MATCH ?
                LIMIT 1
                """.trimIndent()
            },
            arguments = arguments
        )
    }

    /**
     * Compiles only the higher-ranked indexed tiers for a plain query.
     *
     * When this query has a look-ahead row, exact, filename-prefix and word-prefix matches already
     * fill the requested page. Lower-ranked substring matches cannot affect that page, so the
     * engine can avoid the fallback scan entirely.
     */
    fun compileIndexedPreflight(request: SearchRequest): CompiledSearchQuery? {
        if (request.sortOrder != SearchSortOrder.RELEVANCE) {
            return null
        }
        val analysis = request.toQueryAnalysis()
        if (analysis.hasWildcard) {
            return null
        }

        val arguments = mutableListOf<SearchSqlArgument>()
        val filters = request.toSqlFilters()
        val useRootLeadingIndexes = request.rootIds.size == 1
        val candidateLimit = request.offset + request.limit.toLong() + 1
        val tiers = buildIndexedCandidateTiers(
            analysis = analysis,
            filters = filters,
            rootIds = request.rootIds,
            useRootLeadingIndexes = useRootLeadingIndexes,
            candidateLimit = candidateLimit,
            arguments = arguments
        )
        return compileCandidateTiers(request, tiers, arguments)
    }

    /**
     * Builds a relevance query whose only temporary sort is bounded by the requested page.
     *
     * Exact and filename-prefix tiers use the regular filename index. Word-prefix and substring
     * tiers follow stable FTS row-id order instead of calculating BM25 for every match. Short
     * queries retain substring semantics with an early-exit table scan.
     */
    private fun compileBoundedRelevance(
        request: SearchRequest,
        analysis: QueryAnalysis
    ): CompiledSearchQuery {
        val arguments = mutableListOf<SearchSqlArgument>()
        val filters = request.toSqlFilters()
        val useRootLeadingIndexes = request.rootIds.size == 1
        val candidateLimit = request.offset + request.limit.toLong() + 1
        val tiers = buildIndexedCandidateTiers(
            analysis = analysis,
            filters = filters,
            rootIds = request.rootIds,
            useRootLeadingIndexes = useRootLeadingIndexes,
            candidateLimit = candidateLimit,
            arguments = arguments
        )
        val hasExactAndPrefixTiers = !analysis.hasWildcard

        if (analysis.usesTrigramIndex) {
            val trigramMatchRank =
                if (analysis.usesWordPrefixIndex || !hasExactAndPrefixTiers) 3 else 2
            val trigramPredicates = mutableListOf("$TRIGRAM_TABLE MATCH ?")
            arguments += SearchSqlArgument.Text(
                analysis.trigramSegments
                    .toFtsAndQuery()
                    .withFtsRootScope(request.rootIds)
            )
            analysis.tokens.filter(QueryToken::requiresLikeFilter).forEach { token ->
                trigramPredicates += "f.name COLLATE NOCASE LIKE ? ESCAPE '\\'"
                arguments += SearchSqlArgument.Text(token.likePattern)
            }
            if (hasExactAndPrefixTiers) {
                trigramPredicates +=
                    "NOT (f.name COLLATE NOCASE LIKE ? ESCAPE '\\')"
                arguments +=
                    SearchSqlArgument.Text(analysis.query.toLiteralPrefixLikePattern())
            }
            trigramPredicates.append(filters, arguments)
            arguments += SearchSqlArgument.Integer(candidateLimit)
            tiers += CandidateTier(
                name = "trigram_candidates",
                sql =
                    """
                    SELECT
                        f.id,
                        $trigramMatchRank AS match_rank
                    FROM $TRIGRAM_TABLE
                    JOIN indexed_files AS f ON f.id = $TRIGRAM_TABLE.rowid
                    WHERE ${trigramPredicates.joinToString("\n    AND ")}
                    ORDER BY $TRIGRAM_TABLE.rowid ASC
                    LIMIT ?
                    """.trimIndent()
            )
        } else {
            val scanMatchRank =
                if (analysis.usesWordPrefixIndex || !hasExactAndPrefixTiers) 3 else 2
            val shortNameScanIndex =
                if (useRootLeadingIndexes) {
                    ROOT_SHORT_NAME_SCAN_INDEX
                } else {
                    SHORT_NAME_SCAN_INDEX
                }
            val scanPredicates = mutableListOf<String>()
            analysis.tokens.forEach { token ->
                scanPredicates += "f.name COLLATE NOCASE LIKE ? ESCAPE '\\'"
                arguments += SearchSqlArgument.Text(token.likePattern)
            }
            if (hasExactAndPrefixTiers) {
                scanPredicates +=
                    "NOT (f.name COLLATE NOCASE LIKE ? ESCAPE '\\')"
                arguments +=
                    SearchSqlArgument.Text(analysis.query.toLiteralPrefixLikePattern())
            }
            scanPredicates.append(filters, arguments)
            arguments += SearchSqlArgument.Integer(candidateLimit)
            tiers += CandidateTier(
                name = "scan_candidates",
                sql =
                    """
                    SELECT
                        f.id,
                        $scanMatchRank AS match_rank
                    FROM indexed_files AS f INDEXED BY $shortNameScanIndex
                    WHERE ${scanPredicates.joinToString("\n    AND ")}
                    ORDER BY f.id ASC
                    LIMIT ?
                    """.trimIndent()
            )
        }

        check(tiers.isNotEmpty()) { "Bounded search requires at least one candidate tier" }
        return compileCandidateTiers(request, tiers, arguments)
    }

    private fun buildIndexedCandidateTiers(
        analysis: QueryAnalysis,
        filters: List<SqlFilter>,
        rootIds: Set<Long>,
        useRootLeadingIndexes: Boolean,
        candidateLimit: Long,
        arguments: MutableList<SearchSqlArgument>
    ): MutableList<CandidateTier> {
        val tiers = mutableListOf<CandidateTier>()
        val nameIndex = if (useRootLeadingIndexes) ROOT_NAME_INDEX else NAME_INDEX
        if (!analysis.hasWildcard) {
            val exactPredicates = mutableListOf("f.name COLLATE NOCASE = ?")
            arguments += SearchSqlArgument.Text(analysis.query)
            exactPredicates.append(filters, arguments)
            arguments += SearchSqlArgument.Integer(candidateLimit)
            tiers += CandidateTier(
                name = "exact_candidates",
                sql =
                    """
                    SELECT f.id, 0 AS match_rank
                    FROM indexed_files AS f INDEXED BY $nameIndex
                    WHERE ${exactPredicates.joinToString("\n    AND ")}
                    ORDER BY
                        f.name COLLATE NOCASE ASC,
                        f.path COLLATE BINARY ASC
                    LIMIT ?
                    """.trimIndent()
            )

            val prefixPredicates = mutableListOf(
                "f.name COLLATE NOCASE LIKE ? ESCAPE '\\'",
                "f.name COLLATE NOCASE <> ?"
            )
            arguments += SearchSqlArgument.Text(analysis.query.toLiteralPrefixLikePattern())
            arguments += SearchSqlArgument.Text(analysis.query)
            prefixPredicates.append(filters, arguments)
            arguments += SearchSqlArgument.Integer(candidateLimit)
            tiers += CandidateTier(
                name = "prefix_candidates",
                sql =
                    """
                    SELECT f.id, 1 AS match_rank
                    FROM indexed_files AS f INDEXED BY $nameIndex
                    WHERE ${prefixPredicates.joinToString("\n    AND ")}
                    ORDER BY
                        f.name COLLATE NOCASE ASC,
                        f.path COLLATE BINARY ASC
                    LIMIT ?
                    """.trimIndent()
            )
        }

        if (analysis.usesWordPrefixIndex) {
            val wordPrefixPredicates = mutableListOf("$WORD_FTS_TABLE MATCH ?")
            arguments += SearchSqlArgument.Text(
                analysis.tokens
                    .toWordPrefixFtsQuery()
                    .withFtsRootScope(rootIds)
            )
            analysis.tokens.forEach { token ->
                // unicode61 removes diacritics and punctuation. Retain literal substring
                // semantics before promoting a row to the word-prefix relevance tier.
                wordPrefixPredicates += "f.name COLLATE NOCASE LIKE ? ESCAPE '\\'"
                arguments += SearchSqlArgument.Text(token.likePattern)
            }
            wordPrefixPredicates +=
                "NOT (f.name COLLATE NOCASE LIKE ? ESCAPE '\\')"
            arguments += SearchSqlArgument.Text(analysis.query.toLiteralPrefixLikePattern())
            wordPrefixPredicates.append(filters, arguments)
            arguments += SearchSqlArgument.Integer(candidateLimit)
            tiers += CandidateTier(
                name = "word_prefix_candidates",
                sql =
                    """
                    SELECT f.id, 2 AS match_rank
                    FROM $WORD_FTS_TABLE
                    JOIN indexed_files AS f ON f.id = $WORD_FTS_TABLE.rowid
                    WHERE ${wordPrefixPredicates.joinToString("\n    AND ")}
                    ORDER BY $WORD_FTS_TABLE.rowid ASC
                    LIMIT ?
                    """.trimIndent()
            )
        }
        return tiers
    }

    private fun compileCandidateTiers(
        request: SearchRequest,
        tiers: List<CandidateTier>,
        arguments: MutableList<SearchSqlArgument>
    ): CompiledSearchQuery {
        check(tiers.isNotEmpty()) { "Candidate search requires at least one tier" }
        val candidateUnion = tiers.joinToString("\nUNION ALL\n") { tier ->
            "SELECT id, match_rank FROM ${tier.name}"
        }
        val sql =
            """
            WITH
            ${tiers.joinToString(",\n") { tier -> "${tier.name} AS (\n${tier.sql.indent()}\n)" }},
            candidate_rows AS (
            ${candidateUnion.indent()}
            ),
            candidate_ids AS (
                SELECT id, MIN(match_rank) AS match_rank
                FROM candidate_rows
                GROUP BY id
            )
            SELECT
                f.id,
                f.root_id,
                f.path,
                f.parent_path,
                f.name,
                f.extension,
                f.mime_type,
                f.size_bytes,
                f.modified_at_ms,
                f.created_at_ms,
                f.indexed_at_ms,
                f.is_directory,
                f.is_symbolic_link,
                f.is_hidden,
                f.requires_root,
                f.symbolic_link_target,
                f.device_id,
                f.inode,
                f.scan_generation,
                candidate_ids.match_rank AS match_rank,
                0.0 AS fts_rank
            FROM candidate_ids
            JOIN indexed_files AS f ON f.id = candidate_ids.id
            ORDER BY
                candidate_ids.match_rank ASC,
                CASE WHEN candidate_ids.match_rank < 2 THEN f.name END
                    COLLATE NOCASE ASC,
                CASE WHEN candidate_ids.match_rank < 2 THEN f.path END
                    COLLATE BINARY ASC,
                f.id ASC
            LIMIT ? OFFSET ?
            """.trimIndent()
        arguments += SearchSqlArgument.Integer(request.limit.toLong() + 1)
        arguments += SearchSqlArgument.Integer(request.offset)
        return CompiledSearchQuery(sql, arguments)
    }

    private fun compileExhaustive(
        request: SearchRequest,
        analysis: QueryAnalysis
    ): CompiledSearchQuery {
        val arguments = mutableListOf<SearchSqlArgument>()
        val predicates = mutableListOf<String>()

        if (analysis.usesTrigramIndex) {
            predicates += "$TRIGRAM_TABLE MATCH ?"
            arguments += SearchSqlArgument.Text(
                analysis.trigramSegments
                    .toFtsAndQuery()
                    .withFtsRootScope(request.rootIds)
            )
        }
        analysis.tokens.filter(QueryToken::requiresLikeFilter).forEach { token ->
            predicates += "f.name COLLATE NOCASE LIKE ? ESCAPE '\\'"
            arguments += SearchSqlArgument.Text(token.likePattern)
        }
        predicates.append(request.toSqlFilters(), arguments)

        val fromClause = if (analysis.usesTrigramIndex) {
            """
            $TRIGRAM_TABLE
            JOIN indexed_files AS f ON f.id = $TRIGRAM_TABLE.rowid
            """.trimIndent()
        } else {
            "indexed_files AS f"
        }
        val sql =
            """
            SELECT
                f.id,
                f.root_id,
                f.path,
                f.parent_path,
                f.name,
                f.extension,
                f.mime_type,
                f.size_bytes,
                f.modified_at_ms,
                f.created_at_ms,
                f.indexed_at_ms,
                f.is_directory,
                f.is_symbolic_link,
                f.is_hidden,
                f.requires_root,
                f.symbolic_link_target,
                f.device_id,
                f.inode,
                f.scan_generation,
                3 AS match_rank,
                0.0 AS fts_rank
            FROM $fromClause
            WHERE ${predicates.joinToString(separator = "\n    AND ")}
            ORDER BY ${request.sortOrder.toOrderByClause()}
            LIMIT ? OFFSET ?
            """.trimIndent()
        arguments += SearchSqlArgument.Integer(request.limit.toLong() + 1)
        arguments += SearchSqlArgument.Integer(request.offset)
        return CompiledSearchQuery(sql, arguments)
    }

    private fun SearchRequest.toSqlFilters(): List<SqlFilter> =
        buildList {
            if (rootIds.isNotEmpty()) {
                val sortedRootIds = rootIds.sorted()
                add(
                    SqlFilter(
                        sql = "f.root_id IN (${sortedRootIds.joinToString { "?" }})",
                        arguments = sortedRootIds.map(SearchSqlArgument::Integer)
                    )
                )
            }
            if (!includeFiles) {
                add(SqlFilter("f.is_directory = 1"))
            } else if (!includeDirectories) {
                add(SqlFilter("f.is_directory = 0"))
            }
            requiresRoot?.let { requiresRoot ->
                add(
                    SqlFilter(
                        sql = "f.requires_root = ?",
                        arguments = listOf(
                            SearchSqlArgument.Integer(if (requiresRoot) 1 else 0)
                        )
                    )
                )
            }
        }

    private fun MutableList<String>.append(
        filters: List<SqlFilter>,
        arguments: MutableList<SearchSqlArgument>
    ) {
        filters.forEach { filter ->
            add(filter.sql)
            arguments += filter.arguments
        }
    }

    private fun SearchSortOrder.toOrderByClause(): String =
        when (this) {
            SearchSortOrder.RELEVANCE ->
                """
                match_rank ASC,
                fts_rank ASC,
                length(f.name) ASC,
                f.name COLLATE NOCASE ASC,
                f.path COLLATE BINARY ASC
                """.trimIndent()
            SearchSortOrder.NAME_ASCENDING ->
                "f.name COLLATE NOCASE ASC, f.path COLLATE BINARY ASC"
            SearchSortOrder.NAME_DESCENDING ->
                "f.name COLLATE NOCASE DESC, f.path COLLATE BINARY ASC"
            SearchSortOrder.SIZE_ASCENDING ->
                "f.size_bytes ASC, f.name COLLATE NOCASE ASC, f.path COLLATE BINARY ASC"
            SearchSortOrder.SIZE_DESCENDING ->
                "f.size_bytes DESC, f.name COLLATE NOCASE ASC, f.path COLLATE BINARY ASC"
            SearchSortOrder.MODIFIED_ASCENDING ->
                "f.modified_at_ms ASC, f.name COLLATE NOCASE ASC, f.path COLLATE BINARY ASC"
            SearchSortOrder.MODIFIED_DESCENDING ->
                "f.modified_at_ms DESC, f.name COLLATE NOCASE ASC, f.path COLLATE BINARY ASC"
        }

    private data class CandidateTier(
        val name: String,
        val sql: String
    )

    private data class SqlFilter(
        val sql: String,
        val arguments: List<SearchSqlArgument> = emptyList()
    )

    private fun SearchRequest.toQueryAnalysis(): QueryAnalysis {
        val interpretsPattern = queryMode == SearchQueryMode.PATTERN
        return QueryAnalysis(
            query = if (interpretsPattern) query.trim() else query,
            interpretsPattern = interpretsPattern
        )
    }

    private class QueryAnalysis(
        val query: String,
        interpretsPattern: Boolean
    ) {
        val tokens = if (interpretsPattern) {
            query.split(WHITESPACE_REGEX).map { token -> QueryToken(token, true) }
        } else {
            listOf(QueryToken(query, false))
        }
        val trigramSegments = tokens
            .flatMap(QueryToken::indexableLiteralSegments)
            .distinct()
        val usesTrigramIndex = trigramSegments.isNotEmpty()
        val hasWildcard = tokens.any(QueryToken::hasWildcard)
        val usesWordPrefixIndex =
            !hasWildcard && tokens.all(QueryToken::isWordPrefixCompatible)
        val minimumNameCodePointLength =
            tokens.maxOf(QueryToken::minimumMatchCodePointLength)
    }

    private class QueryToken(
        val raw: String,
        private val interpretsWildcards: Boolean
    ) {
        val hasWildcard =
            interpretsWildcards && raw.any { character -> character == '*' || character == '?' }
        val isWordPrefixCompatible = raw.all(Char::isLetterOrDigit)
        val indexableLiteralSegments =
            (if (interpretsWildcards) raw.split('*', '?') else listOf(raw))
            .filter { segment -> segment.codePointLength() >= MIN_TRIGRAM_LENGTH }
        val requiresLikeFilter =
            hasWildcard || raw.codePointLength() < MIN_TRIGRAM_LENGTH
        val likePattern = raw.toSubstringLikePattern(interpretsWildcards)
        val minimumMatchCodePointLength =
            raw.codePointLength() -
                if (interpretsWildcards) raw.count { character -> character == '*' } else 0
    }

    private fun List<String>.toFtsAndQuery(): String =
        joinToString(" AND ") { segment -> segment.toFtsPhrase() }

    private fun List<QueryToken>.toWordPrefixFtsQuery(): String =
        joinToString(" AND ") { token -> "${token.raw.toFtsPhrase()}*" }

    /**
     * Restricts every FTS posting-list intersection to filename content and, when requested, to
     * the configured root scopes. Qualifying the filename column is mandatory because root_scope
     * is indexed in the same FTS table and must never be interpreted as user-visible text.
     */
    private fun String.withFtsRootScope(rootIds: Set<Long>): String {
        val filenameQuery = "$FTS_NAME_COLUMN : ($this)"
        if (rootIds.isEmpty()) {
            return filenameQuery
        }
        val rootQuery = rootIds
            .sorted()
            .joinToString(" OR ") { rootId -> rootId.toRootScope().toFtsPhrase() }
        return "$filenameQuery AND $FTS_ROOT_SCOPE_COLUMN : ($rootQuery)"
    }

    private fun Long.toRootScope(): String {
        check(this > 0) { "Root ID must be positive" }
        return "r${toString().padStart(ROOT_SCOPE_DIGITS, '0')}"
    }

    private fun String.toFtsPhrase(): String =
        "\"${replace("\"", "\"\"")}\""

    private fun String.toSubstringLikePattern(interpretsWildcards: Boolean): String =
        buildString(length + 2) {
            append('%')
            this@toSubstringLikePattern.forEach { character ->
                when (character) {
                    '*' -> if (interpretsWildcards) append('%') else append('*')
                    '?' -> if (interpretsWildcards) append('_') else append('?')
                    '%', '_', '\\' -> {
                        append('\\')
                        append(character)
                    }
                    else -> append(character)
                }
            }
            append('%')
        }

    private fun String.toLiteralPrefixLikePattern(): String =
        buildString(length + 1) {
            this@toLiteralPrefixLikePattern.forEach { character ->
                if (character == '%' || character == '_' || character == '\\') {
                    append('\\')
                }
                append(character)
            }
            append('%')
        }

    private fun String.codePointLength(): Int =
        codePointCount(0, length)

    private fun String.toNoMatchProbeSegments(): List<String> {
        val codePointLength = codePointLength()
        if (codePointLength <= MAX_NO_MATCH_PROBE_LITERAL_LENGTH) {
            return listOf(this)
        }
        val lastStart = codePointLength - MIN_TRIGRAM_LENGTH
        return listOf(0, lastStart / 2, lastStart)
            .map { startCodePoint ->
                val startIndex = offsetByCodePoints(0, startCodePoint)
                val endIndex = offsetByCodePoints(startIndex, MIN_TRIGRAM_LENGTH)
                substring(startIndex, endIndex)
            }
            .distinct()
    }

    private fun String.indent(): String =
        prependIndent("    ")

    private const val WORD_FTS_TABLE = "indexed_files_fts"
    private const val TRIGRAM_TABLE = "indexed_file_names_trigram_fts"
    private const val NAME_INDEX = "indexed_files_name"
    private const val ROOT_NAME_INDEX = "indexed_files_root_name"
    private const val SHORT_NAME_SCAN_INDEX = "indexed_files_short_name_scan"
    private const val ROOT_SHORT_NAME_SCAN_INDEX = "indexed_files_root_short_name_scan"
    private const val FTS_NAME_COLUMN = "name"
    private const val FTS_ROOT_SCOPE_COLUMN = "root_scope"
    private const val ROOT_SCOPE_DIGITS = 19
    private const val MIN_TRIGRAM_LENGTH = 3
    private const val MAX_NO_MATCH_PROBE_LITERAL_LENGTH = 64
    private val WHITESPACE_REGEX = Regex("\\s+")
}

internal data class CompiledSearchQuery(
    val sql: String,
    val arguments: List<SearchSqlArgument>
)

internal sealed interface SearchSqlArgument {
    data class Text(val value: String) : SearchSqlArgument

    data class Integer(val value: Long) : SearchSqlArgument
}
