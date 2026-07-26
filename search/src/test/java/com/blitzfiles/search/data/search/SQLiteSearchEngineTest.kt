/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.data.search

import com.blitzfiles.search.data.database.IndexDatabase
import com.blitzfiles.search.data.repository.SQLiteIndexRepository
import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.model.SearchQueryMode
import com.blitzfiles.search.domain.model.SearchSortOrder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SQLiteSearchEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: IndexDatabase
    private lateinit var repository: SQLiteIndexRepository
    private lateinit var searchEngine: SQLiteSearchEngine

    @Before
    fun setUp() {
        val databaseFile = File(temporaryFolder.newFolder("database"), "index.db")
        database = IndexDatabase(databaseFile)
        repository = SQLiteIndexRepository(database)
        searchEngine = SQLiteSearchEngine(database)
    }

    @After
    fun tearDown() = runBlocking {
        searchEngine.close()
    }

    @Test
    fun ranksExactFilenamePrefixWordPrefixAndSubstringInThatOrder() = runBlocking {
        val rootId = createRoot("/storage")
        repository.upsertEntries(
            listOf(
                entry(rootId, "report", size = 1, modifiedAt = 1),
                entry(rootId, "report-final.txt", size = 2, modifiedAt = 2),
                entry(rootId, "annual report.txt", size = 3, modifiedAt = 3),
                entry(rootId, "myreportbackup.bin", size = 4, modifiedAt = 4),
                entry(rootId, "unrelated.txt", size = 5, modifiedAt = 5)
            )
        )

        val page = searchEngine.search(SearchRequest(query = "report"))

        assertEquals(
            listOf("report", "report-final.txt", "annual report.txt", "myreportbackup.bin"),
            page.hits.map { hit -> hit.entry.name }
        )
        assertTrue(page.hits[0].relevance > page.hits[1].relevance)
        assertTrue(page.hits[1].relevance > page.hits[2].relevance)
        assertTrue(page.hits[2].relevance > page.hits[3].relevance)
        assertNull(page.nextOffset)
        assertNull(page.totalCount)
    }

    @Test
    fun matchesUnicodeSubstringsWildcardsAndLiteralSqlMetacharacters() = runBlocking {
        val rootId = createRoot("/storage")
        repository.upsertEntries(
            listOf(
                entry(rootId, "Résumé Été.txt"),
                entry(rootId, "report-final.txt"),
                entry(rootId, "report-final.log"),
                entry(rootId, "100%_done.txt"),
                entry(rootId, "\" OR report")
            )
        )

        assertEquals(
            listOf("Résumé Été.txt"),
            searchEngine.search(SearchRequest(query = "été")).names()
        )
        assertEquals(
            listOf("report-final.txt"),
            searchEngine.search(SearchRequest(query = "rep*final.?xt")).names()
        )
        assertEquals(
            listOf("100%_done.txt"),
            searchEngine.search(SearchRequest(query = "%_d")).names()
        )
        assertEquals(
            listOf("\" OR report"),
            searchEngine.search(SearchRequest(query = "\" OR report")).names()
        )
        assertEquals(
            listOf("report-final.txt", "report-final.log"),
            searchEngine.search(SearchRequest(query = "*a*")).names()
        )
    }

    @Test
    fun literalSubstringModeDoesNotInterpretSpacesOrWildcards() = runBlocking {
        val rootId = createRoot("/storage")
        repository.upsertEntries(
            listOf(
                entry(rootId, "a*b.txt"),
                entry(rootId, "axxxb.txt"),
                entry(rootId, "foo bar.txt"),
                entry(rootId, "foo-middle-bar.txt")
            )
        )

        assertEquals(
            listOf("a*b.txt"),
            searchEngine.search(
                SearchRequest(
                    query = "a*b",
                    queryMode = SearchQueryMode.LITERAL_SUBSTRING
                )
            ).names()
        )
        assertEquals(
            listOf("foo bar.txt"),
            searchEngine.search(
                SearchRequest(
                    query = "foo bar",
                    queryMode = SearchQueryMode.LITERAL_SUBSTRING
                )
            ).names()
        )
    }

    @Test
    fun shortPlainQueriesKeepSubstringSemanticsWithBoundedCandidateTiers() = runBlocking {
        val rootId = createRoot("/storage")
        repository.upsertEntries(
            listOf(
                entry(rootId, "my-image.jpg"),
                entry(rootId, "IMAX.mov"),
                entry(rootId, "image.png"),
                entry(rootId, "im")
            )
        )

        assertEquals(
            listOf("im", "image.png", "IMAX.mov", "my-image.jpg"),
            searchEngine.search(SearchRequest(query = "im")).names()
        )
        assertEquals(
            listOf("IMAX.mov"),
            searchEngine.search(SearchRequest(query = "x")).names()
        )

        val compiled = SearchQueryCompiler.compile(SearchRequest(query = "im"))
        assertFalse("Two characters cannot use trigram FTS", "trigram_fts" in compiled.sql)
        assertTrue(
            compiled.arguments.any { argument ->
                argument == SearchSqlArgument.Text("im%")
            }
        )
        assertTrue(
            compiled.arguments.any { argument ->
                argument == SearchSqlArgument.Text("%im%")
            }
        )
        val plan = explain(compiled)
        assertTrue(
            "Expected a filename-index range search, got: $plan",
            plan.any { step ->
                "indexed_files_name" in step && "name>?" in step && "name<?" in step
            }
        )
        assertTrue(
            "Short substring fallback must scan the compact covering index: $plan",
            plan.any { step ->
                "indexed_files_short_name_scan" in step && "COVERING INDEX" in step
            }
        )
    }

    @Test
    fun boundedRelevanceAppliesFiltersAcrossEveryCandidateTier() = runBlocking {
        val selectedRootId = createRoot("/selected")
        val otherRootId = createRoot("/other")
        repository.upsertEntries(
            listOf(
                entry(selectedRootId, "needle"),
                entry(selectedRootId, "needle-directory", isDirectory = true),
                entry(selectedRootId, "x-needle-substring"),
                entry(otherRootId, "needle"),
                entry(otherRootId, "needle-prefix")
            )
        )

        assertEquals(
            listOf("needle", "x-needle-substring"),
            searchEngine.search(
                SearchRequest(
                    query = "needle",
                    rootIds = setOf(selectedRootId),
                    includeDirectories = false,
                    requiresRoot = false
                )
            ).names()
        )
        assertEquals(
            listOf("needle-directory"),
            searchEngine.search(
                SearchRequest(
                    query = "needle",
                    rootIds = setOf(selectedRootId),
                    includeFiles = false
                )
            ).names()
        )

        val shortQuery = SearchQueryCompiler.compile(
            SearchRequest(query = "ne", rootIds = setOf(selectedRootId))
        )
        val shortQueryPlan = explain(shortQuery)
        assertTrue(
            "Root-filtered exact and prefix tiers must seek the root/name index: $shortQueryPlan",
            shortQueryPlan.any { step ->
                "indexed_files_root_name" in step &&
                    "root_id=?" in step &&
                    "name" in step
            }
        )
        assertTrue(
            "Root-filtered substring tier must seek the root-local covering index: $shortQueryPlan",
            shortQueryPlan.any { step ->
                "indexed_files_root_short_name_scan" in step &&
                    "root_id=?" in step
            }
        )
        assertTrue(
            "Short substring tier must use the root-local row-ID-ordered covering index",
            "INDEXED BY indexed_files_root_short_name_scan" in shortQuery.sql
        )
        assertFalse(
            "A single-root query must not scan the global short-name index",
            "INDEXED BY indexed_files_short_name_scan" in shortQuery.sql
        )
    }

    @Test
    fun shortPlainQueriesExposeAnIndexedOnlyPreflight() {
        val request = SearchRequest(query = "im", limit = 32)
        val compiled = SearchQueryCompiler.compileIndexedPreflight(request)
            ?: error("Expected an indexed short-query preflight")

        assertTrue("Expected exact candidates", "exact_candidates" in compiled.sql)
        assertTrue("Expected filename-prefix candidates", "prefix_candidates" in compiled.sql)
        assertTrue("Expected word-prefix candidates", "word_prefix_candidates" in compiled.sql)
        assertFalse("Preflight must not scan the metadata table", "scan_candidates" in compiled.sql)
        assertFalse(
            "Preflight must not use the trigram table for two characters",
            "trigram_candidates" in compiled.sql
        )
        val longQueryPreflight = SearchQueryCompiler.compileIndexedPreflight(
            request.copy(query = "image")
        )
        assertTrue(
            "Long plain queries may also skip lower-ranked trigram matches",
            longQueryPreflight != null && "trigram_candidates" !in longQueryPreflight.sql
        )
        assertNull(
            SearchQueryCompiler.compileIndexedPreflight(
                request.copy(query = "i*")
            )
        )
        assertNull(
            SearchQueryCompiler.compileIndexedPreflight(
                request.copy(sortOrder = SearchSortOrder.NAME_ASCENDING)
            )
        )
    }

    @Test
    fun trigramQueriesExposeAnIndexedNoMatchProbe() {
        val numericProbe = SearchQueryCompiler.compileNoMatchProbe(
            SearchRequest(query = "999999999999999")
        )
        assertNotNull(numericProbe)
        assertTrue("Probe must use trigram FTS", "trigram_fts MATCH ?" in numericProbe!!.sql)
        assertEquals(
            listOf(SearchSqlArgument.Text("name : (\"999999999999999\")")),
            numericProbe.arguments
        )

        val wildcardProbe = SearchQueryCompiler.compileNoMatchProbe(
            SearchRequest(
                query = "missing*segment",
                sortOrder = SearchSortOrder.SIZE_DESCENDING
            )
        )
        assertNotNull(wildcardProbe)
        assertEquals(
            listOf(
                SearchSqlArgument.Text(
                    "name : (\"missing\" AND \"segment\")"
                )
            ),
            wildcardProbe!!.arguments
        )

        val scopedProbe = SearchQueryCompiler.compileNoMatchProbe(
            SearchRequest(
                query = "missing",
                rootIds = linkedSetOf(9L, 2L)
            )
        )
        assertNotNull(scopedProbe)
        assertEquals(
            SearchSqlArgument.Text(
                "name : (\"missing\") AND root_scope : (" +
                    "\"r0000000000000000002\" OR \"r0000000000000000009\")"
            ),
            scopedProbe!!.arguments.single()
        )

        assertNull(SearchQueryCompiler.compileNoMatchProbe(SearchRequest(query = "qx")))
        assertNull(SearchQueryCompiler.compileNoMatchProbe(SearchRequest(query = "?*")))

        val maximumLengthProbe = SearchQueryCompiler.compileNoMatchProbe(
            SearchRequest(query = "0".repeat(SearchRequest.MAX_QUERY_LENGTH))
        )
        assertNotNull(maximumLengthProbe)
        assertTrue(
            "Long probes must guard on filename length",
            "length(f.name) >= ?" in maximumLengthProbe!!.sql
        )
        assertEquals(
            listOf(
                SearchSqlArgument.Text("name : (\"000\")"),
                SearchSqlArgument.Integer(SearchRequest.MAX_QUERY_LENGTH.toLong())
            ),
            maximumLengthProbe.arguments
        )
    }

    @Test
    fun returnsAnAuthoritativeEmptyPageWhenTheTrigramProbeHasNoCandidate() = runBlocking {
        val rootId = createRoot("/storage")
        repository.upsertEntries(
            listOf(
                entry(rootId, "report.txt"),
                entry(rootId, "image.png")
            )
        )

        val page = searchEngine.search(SearchRequest(query = "999999999999999"))

        assertTrue(page.hits.isEmpty())
        assertNull(page.nextOffset)
        assertEquals(0L, page.totalCount)
    }

    @Test
    fun rootScopedProbeReturnsAuthoritativeEmptyWhenMatchExistsOnlyInAnotherRoot() =
        runBlocking {
            val selectedRootId = createRoot("/selected")
            val otherRootId = createRoot("/other")
            repository.upsertEntries(
                listOf(
                    entry(selectedRootId, "selected-file.txt"),
                    entry(otherRootId, "outside-only-match.txt")
                )
            )

            val page = searchEngine.search(
                SearchRequest(
                    query = "outside-only",
                    rootIds = setOf(selectedRootId)
                )
            )

            assertTrue(page.hits.isEmpty())
            assertNull(page.nextOffset)
            assertEquals(
                "A root-aware no-match probe must prove the selected partition is empty",
                0L,
                page.totalCount
            )
        }

    @Test
    fun rootScopedFtsSupportsMultipleRootsWithoutLeakingAnotherPartition() = runBlocking {
        val firstRootId = createRoot("/first")
        val secondRootId = createRoot("/second")
        val excludedRootId = createRoot("/excluded")
        repository.upsertEntries(
            listOf(
                entry(firstRootId, "x-shared-scope-first.txt"),
                entry(secondRootId, "x-shared-scope-second.txt"),
                entry(excludedRootId, "x-shared-scope-excluded.txt")
            )
        )

        val page = searchEngine.search(
            SearchRequest(
                query = "shared-scope",
                rootIds = linkedSetOf(secondRootId, firstRootId)
            )
        )

        assertEquals(
            setOf(
                "x-shared-scope-first.txt",
                "x-shared-scope-second.txt"
            ),
            page.names().toSet()
        )
    }

    @Test
    fun userQueryCannotMatchTheInternalRootScopeColumn() = runBlocking {
        val rootId = createRoot("/selected")
        repository.upsertEntries(listOf(entry(rootId, "ordinary-file.txt")))

        val page = searchEngine.search(
            SearchRequest(query = "r${rootId.toString().padStart(19, '0')}")
        )

        assertTrue(page.hits.isEmpty())
        assertEquals(0L, page.totalCount)
    }

    @Test
    fun punctuationDoesNotExpandIntoAnOverbroadWordPrefixQuery() {
        val compiled = SearchQueryCompiler.compile(SearchRequest(query = "c++"))

        assertFalse(
            "unicode61 would reduce c++ to an overbroad c* word-prefix query",
            "word_prefix_candidates" in compiled.sql
        )
        assertTrue("Expected the literal trigram index", "trigram_candidates" in compiled.sql)
    }

    @Test
    fun appliesRootTypeAndRootAccessFilters() = runBlocking {
        val standardRootId = createRoot("/standard")
        val rootRootId = createRoot("/root", IndexAccessMode.ROOT)
        repository.upsertEntries(
            listOf(
                entry(standardRootId, "alpha-item", size = 20, modifiedAt = 300),
                entry(
                    standardRootId,
                    "beta-item",
                    size = 0,
                    modifiedAt = 200,
                    isDirectory = true
                ),
                entry(
                    rootRootId,
                    "gamma-item",
                    size = 10,
                    modifiedAt = 100,
                    requiresRoot = true
                )
            )
        )

        assertEquals(
            listOf("alpha-item"),
            searchEngine.search(
                SearchRequest(
                    query = "item",
                    rootIds = setOf(standardRootId),
                    includeDirectories = false
                )
            ).names()
        )
        assertEquals(
            listOf("gamma-item"),
            searchEngine.search(
                SearchRequest(query = "item", requiresRoot = true)
            ).names()
        )
    }

    @Test
    fun supportsAllRequestedSortFamilies() = runBlocking {
        val rootId = createRoot("/storage")
        repository.upsertEntries(
            listOf(
                entry(rootId, "charlie-item", size = 20, modifiedAt = 200),
                entry(rootId, "alpha-item", size = 30, modifiedAt = 100),
                entry(rootId, "bravo-item", size = 10, modifiedAt = 300)
            )
        )

        assertEquals(
            listOf("alpha-item", "bravo-item", "charlie-item"),
            search(SearchSortOrder.NAME_ASCENDING)
        )
        assertEquals(
            listOf("charlie-item", "bravo-item", "alpha-item"),
            search(SearchSortOrder.NAME_DESCENDING)
        )
        assertEquals(
            listOf("bravo-item", "charlie-item", "alpha-item"),
            search(SearchSortOrder.SIZE_ASCENDING)
        )
        assertEquals(
            listOf("alpha-item", "charlie-item", "bravo-item"),
            search(SearchSortOrder.SIZE_DESCENDING)
        )
        assertEquals(
            listOf("alpha-item", "charlie-item", "bravo-item"),
            search(SearchSortOrder.MODIFIED_ASCENDING)
        )
        assertEquals(
            listOf("bravo-item", "charlie-item", "alpha-item"),
            search(SearchSortOrder.MODIFIED_DESCENDING)
        )
    }

    @Test
    fun pagesWithStableOffsetsAndOneRowLookAhead() = runBlocking {
        val rootId = createRoot("/storage")
        repository.upsertEntries(
            ('a'..'e').mapIndexed { index, suffix ->
                entry(rootId, "file-$suffix", size = index.toLong())
            }
        )

        val first = searchEngine.search(
            SearchRequest(
                query = "file",
                sortOrder = SearchSortOrder.NAME_ASCENDING,
                limit = 2
            )
        )
        val second = searchEngine.search(
            SearchRequest(
                query = "file",
                sortOrder = SearchSortOrder.NAME_ASCENDING,
                limit = 2,
                offset = requireNotNull(first.nextOffset)
            )
        )
        val third = searchEngine.search(
            SearchRequest(
                query = "file",
                sortOrder = SearchSortOrder.NAME_ASCENDING,
                limit = 2,
                offset = requireNotNull(second.nextOffset)
            )
        )

        assertEquals(listOf("file-a", "file-b"), first.names())
        assertEquals(2L, first.nextOffset)
        assertEquals(listOf("file-c", "file-d"), second.names())
        assertEquals(4L, second.nextOffset)
        assertEquals(listOf("file-e"), third.names())
        assertNull(third.nextOffset)
    }

    @Test
    fun boundedCandidateTiersRemainStableAcrossDeepPages() = runBlocking {
        val rootId = createRoot("/storage")
        val prefixNames = (0 until 90)
            .map { index -> "file-${index.toString().padStart(3, '0')}" }
            .reversed()
        val substringNames = (0 until 90)
            .map { index -> "x-${index.toString().padStart(3, '0')}-file" }
            .reversed()
        repository.upsertEntries(
            (prefixNames + substringNames).map { name -> entry(rootId, name) }
        )

        val collectedNames = mutableListOf<String>()
        var offset = 0L
        do {
            val page = searchEngine.search(
                SearchRequest(
                    query = "file",
                    limit = 23,
                    offset = offset
                )
            )
            collectedNames += page.names()
            offset = page.nextOffset ?: break
        } while (true)

        assertEquals(prefixNames.sorted() + substringNames, collectedNames)
        assertEquals(collectedNames.size, collectedNames.toSet().size)
    }

    @Test
    fun shortQueryPreflightAndFallbackRemainStableAcrossPages() = runBlocking {
        val rootId = createRoot("/storage")
        val prefixNames = (0 until 80).map { index ->
            "im-${index.toString().padStart(3, '0')}"
        }
        val substringNames = (0 until 20).map { index ->
            "xximxx-${index.toString().padStart(3, '0')}"
        }
        repository.upsertEntries(
            (listOf("im") + prefixNames + substringNames).map { name ->
                entry(rootId, name)
            }
        )

        val collectedNames = mutableListOf<String>()
        var offset = 0L
        do {
            val page = searchEngine.search(
                SearchRequest(query = "im", limit = 17, offset = offset)
            )
            collectedNames += page.names()
            offset = page.nextOffset ?: break
        } while (true)

        assertEquals(listOf("im") + prefixNames + substringNames, collectedNames)
        assertEquals(collectedNames.size, collectedNames.toSet().size)
    }

    @Test
    fun indexableSubstringAndWordPrefixUseBoundedFtsPlans() = runBlocking {
        val compiled = SearchQueryCompiler.compile(SearchRequest(query = "report"))
        val plan = explain(compiled)

        assertTrue(
            "Expected a trigram FTS5 virtual-table plan, got: $plan",
            plan.any { step ->
                "indexed_file_names_trigram_fts" in step &&
                    "VIRTUAL TABLE INDEX" in step
            }
        )
        assertFalse("Fast relevance query must not calculate FTS rank", ".rank" in compiled.sql)
        assertTrue(
            "Expected a bounded word-prefix candidate tier",
            "word_prefix_candidates" in compiled.sql &&
                "FROM indexed_files_fts" in compiled.sql
        )
    }

    @Test
    fun explicitSortsKeepTheExhaustiveGlobalOrderingPath() {
        val compiled = SearchQueryCompiler.compile(
            SearchRequest(
                query = "report",
                sortOrder = SearchSortOrder.NAME_ASCENDING
            )
        )

        assertFalse(
            "Explicit sorting must not truncate bounded candidate tiers",
            "candidate_ids" in compiled.sql
        )
        assertTrue("Expected global name ordering", "f.name COLLATE NOCASE ASC" in compiled.sql)
        assertFalse(
            "Explicit sorting must not calculate the unused word-prefix rank",
            "FROM indexed_files_fts" in compiled.sql
        )
        assertFalse(
            "Explicit sorting must not calculate the unused FTS rank",
            ".rank" in compiled.sql
        )
    }

    @Test
    fun rejectsOffsetsThatCannotFitTheBoundedLookAhead() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchRequest(
                query = "file",
                offset = Long.MAX_VALUE - SearchRequest.MAX_LIMIT
            )
        }
    }

    private suspend fun createRoot(
        path: String,
        accessMode: IndexAccessMode = IndexAccessMode.STANDARD
    ): Long =
        repository.upsertRoot(
            IndexRoot(
                path = path,
                displayName = path,
                accessMode = accessMode,
                createdAtMillis = 1
            )
        )

    private suspend fun search(sortOrder: SearchSortOrder): List<String> =
        searchEngine.search(
            SearchRequest(query = "item", sortOrder = sortOrder)
        ).names()

    private suspend fun explain(compiled: CompiledSearchQuery): List<String> =
        database.read { connection ->
            connection.prepare("EXPLAIN QUERY PLAN ${compiled.sql}").use { statement ->
                compiled.arguments.forEachIndexed { index, argument ->
                    when (argument) {
                        is SearchSqlArgument.Integer -> {
                            statement.bindLong(index + 1, argument.value)
                        }
                        is SearchSqlArgument.Text -> {
                            statement.bindText(index + 1, argument.value)
                        }
                    }
                }
                buildList {
                    while (statement.step()) {
                        add(statement.getText(3))
                    }
                }
            }
        }

    private fun entry(
        rootId: Long,
        name: String,
        size: Long = 0,
        modifiedAt: Long = 0,
        isDirectory: Boolean = false,
        requiresRoot: Boolean = false
    ): IndexedFileRecord =
        IndexedFileRecord(
            rootId = rootId,
            path = "/root-$rootId/$name",
            parentPath = "/root-$rootId",
            name = name,
            extension = name.substringAfterLast('.', "").ifEmpty { null },
            sizeBytes = size,
            modifiedAtMillis = modifiedAt,
            indexedAtMillis = 1,
            isDirectory = isDirectory,
            requiresRoot = requiresRoot,
            scanGeneration = 1
        )

    private fun com.blitzfiles.search.domain.model.SearchPage.names(): List<String> =
        hits.map { hit -> hit.entry.name }
}
