# BlitzFiles search module

This Android library owns global file-index contracts, persistence, filesystem-independent index
traversal, and the FTS5-backed query engine.

## Boundaries

- `domain/model` contains platform-independent index, indexing, and search values.
- `domain/repository/IndexRepository` is the persistence boundary shared by indexers and search
  engines.
- `domain/indexer` defines the full/incremental lifecycle and the minimal filesystem gateway.
- `domain/search/SearchEngine` defines paged queries without coupling them to FTS5.
- `data/database` owns connection serialization, schema creation, and migrations.
- `data/indexer/DefaultFileIndexer` implements iterative batched traversal, targeted incremental
  reconciliation, exclusions, pause/resume/cancel, and directory-cycle protection.
- `data/repository/SQLiteIndexRepository` is the bundled-SQLite implementation.
- `data/search/SQLiteSearchEngine` executes safe substring and wildcard queries with stable
  sorting and offset-based paging.

The app module supplies `MaterialFilesIndexFileSystem` for explicit standard/root provider access,
`FileIndexingService` for foreground execution and progress controls, and
`FileIndexingController` for persisted roots, exclusions, and scan commands. Search UI integration
intentionally belongs to Stage 5.

## Database

The database is stored under the application's no-backup directory because it is derived data and
can be rebuilt. Schema changes are versioned with `PRAGMA user_version`.

Schema version 4 contains:

- `index_roots` for standard/root scan configuration, generations, timestamps, status, and errors;
- `index_exclusions` for global or root-scoped directory exclusions;
- `indexed_files` for path, name, size, timestamps, type flags, root access, symlink metadata, and
  optional device/inode identity;
- `indexed_files_fts`, an external-content Unicode FTS5 index with prefix indexes;
- `indexed_file_names_trigram_fts`, an external-content trigram FTS5 index for substring matching;
- triggers that update both FTS indexes atomically with `indexed_files`.

The Unicode index supplies a bounded word-prefix relevance tier and supports exhaustive sorted
queries. The trigram index is kept separate so interactive substring search can stream a small,
deterministic candidate page without calculating FTS rank for every matching filename.

## Query behavior

- Whitespace-separated terms use AND semantics and match substrings by default.
- `*` matches zero or more characters and `?` matches exactly one character.
- SQL LIKE and FTS5 metacharacters have no special meaning unless they are `*` or `?`.
- Relevance favors an exact name, then a filename prefix, then a Unicode word prefix, then a
  general substring.
- Every relevance tier returns at most `offset + limit + 1` candidates. Indexed word/trigram
  matches stream in row-ID order; one- and two-character searches use an early-exit LIKE scan.
  This preserves substring semantics without globally sorting every matching row. A rare or absent
  one- or two-character query may still inspect the complete filename table.
- Name, size, and modification-time sorts are available in both directions.
- A query reads at most `limit + 1` rows to determine `nextOffset`; it deliberately omits a costly
  exact total count during interactive search.
- `SearchEngine.searchAsYouType()` defaults to a 150 ms debounce and suppresses obsolete results;
  the BlitzFiles global-search UI overrides it with a 50 ms debounce.

## Concurrency

`IndexDatabase` owns one connection. Every operation runs on `Dispatchers.IO` and is serialized by
a coroutine mutex because a bundled SQLite connection must not be used concurrently. The indexer
uses bounded batches and throttled progress updates, and checks pause/cancel between entries.
Stale records are deleted only after a complete error-free traversal of the matching root or
incremental subtree, so temporary permission and mount failures do not destroy valid index data.
