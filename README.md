# BlitzFiles

BlitzFiles is an open-source Android file manager with fast, device-wide indexed search. It is
based on [Material Files](https://github.com/zhanghai/MaterialFiles) and keeps its filesystem,
archive, network storage, root, and Material Design capabilities.

Package name: `com.blitzfiles.app`

Minimum supported version: Android 6.0 (API 23).

## Features

### File management

- Local storage and Storage Access Framework support.
- Root file access through libsu.
- ZIP, TAR, 7z, RAR and other common archive formats.
- FTP, SFTP, SMB and WebDAV storage.
- Symbolic links, POSIX permissions and SELinux contexts.
- Material Design, Material You colors, light, dark and true-black themes.
- File previews, checksums, properties, bookmarks and standard directories.

### Global indexed search

- SQLite FTS5 word-prefix and trigram indexes.
- Search-as-you-type with a 50 ms UI debounce.
- Substring, prefix, `*` and `?` wildcard matching.
- Bounded relevance queries that return the first page without sorting every matching row.
- Sorting by relevance, name, size or modification date.
- Bounded result pages with incremental loading.
- Standard-storage and explicit root index roots.
- Full and targeted incremental scans.
- Persistent scan configuration and exclusions.
- Pause, resume and cancellation from the app or foreground notification.
- Direct open, containing-folder navigation, sharing and path copying from results.

## Indexing safety

Root indexing uses Material Files' root-backed Linux NIO provider directly; it does not fall back
to normal app access when a root is configured for root mode.

The following virtual or device-backed trees are always excluded:

```text
/acct
/config
/d
/debug_ramdisk
/dev
/proc
/sys
/data_mirror
/data/media
/mnt/androidwritable
/mnt/installer
/mnt/media_rw
/mnt/pass_through
/mnt/runtime
/mnt/user
```

These paths can contain device nodes, unbounded process data, kernel interfaces, or duplicate
mounts. Canonical user storage remains available through `/storage`, and adopted storage remains
available through `/mnt/expand`. The safety exclusions are enforced by the indexing engine and
cannot be removed in the UI. Additional global or root-specific exclusions can be configured in
**Settings → Search index**.

Following symbolic links is disabled by default. Directory device/inode identities are tracked for
every scan to prevent duplicate bind-mount traversal; normalized link targets also prevent cycles
when following symbolic links is enabled.

## Search architecture

The `search` Android library module is independent of the app UI and contains:

- `FileIndexer` and `IndexFileSystem` traversal boundaries;
- `IndexRepository` persistence boundary;
- `SQLiteIndexRepository` with bounded transactional writes;
- `SQLiteSearchEngine` and the FTS5 query compiler;
- indexing and search domain models.

The app module provides the Material Files filesystem adapter, root-aware foreground service,
global-search UI, and indexing settings.

Index entries are written in bounded batches. SQLite uses WAL mode, prepared statements, external
content FTS tables, and trigger-maintained O(1) statistics. A scan never retains the complete file
list in memory. Stale rows are removed only after a subtree was read successfully, so transient
permission errors do not erase previously valid results.

## Building

Requirements:

- Android Studio with JDK 21;
- Android SDK 36;
- Android Build Tools 37.0.0;
- Android NDK 28.1.13356709.

Build and test:

```shell
./gradlew :search:testDebugUnitTest :app:assembleDebug
```

Static analysis:

```shell
./gradlew :app:lintDebug
```

Release signing is configured through `signing.properties` or the `STORE_FILE`, `STORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD` environment variables.

On Windows, Android's AIDL tool may fail when the repository's absolute path contains non-ASCII
characters. Moving the checkout to an ASCII-only path avoids that toolchain limitation.

## Privacy

The search index is stored locally in the app's private data directory. File names, paths and index
metadata are not uploaded by the indexing or search engine.

Network storage is available for normal file management but is not included in the local
device-wide index.

## Upstream and license

BlitzFiles is derived from Material Files by Hai Zhang. The upstream project and its contributors
retain copyright over their work.

This project is licensed under the GNU General Public License version 3 or later. See
[LICENSE](LICENSE).
