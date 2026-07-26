/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.indexer

/**
 * Paths backed by virtual or device filesystems that must never be traversed by the indexer.
 *
 * Reading these trees can block indefinitely, expose device nodes, produce an unbounded stream of
 * process entries, or duplicate large parts of the filesystem. The policy is enforced by the
 * indexer and cannot be disabled by UI configuration.
 */
object IndexSafetyPolicy {
    val protectedPathPrefixes: List<String> = listOf(
        // Kernel-backed virtual filesystems and device endpoints.
        "/acct",
        "/config",
        "/d",
        "/debug_ramdisk",
        "/dev",
        "/proc",
        "/sys",
        // Android bind-mount mirrors and storage staging views. Canonical data remains available
        // through /data (except its public-storage backing tree), /storage, and /mnt/expand.
        "/data_mirror",
        "/data/media",
        "/mnt/androidwritable",
        "/mnt/installer",
        "/mnt/media_rw",
        "/mnt/pass_through",
        "/mnt/runtime",
        "/mnt/user"
    )

    fun isProtected(path: String): Boolean =
        protectedPathPrefixes.any { path.isSameOrDescendantOf(it) }

    /**
     * Intersects configured and protected exclusions with [rootPath].
     *
     * If an exclusion is an ancestor of the root, the complete root is excluded. This is important
     * for global exclusions such as `/storage` when a nested root points at `/storage/emulated/0`.
     */
    internal fun effectiveExclusions(
        rootPath: String,
        configuredPathPrefixes: Collection<String>
    ): List<String> =
        (configuredPathPrefixes + protectedPathPrefixes)
            .mapNotNull { exclusion ->
                when {
                    exclusion.isSameOrDescendantOf(rootPath) -> exclusion
                    rootPath.isSameOrDescendantOf(exclusion) -> rootPath
                    else -> null
                }
            }
            .withoutDescendantDuplicates()
}

internal fun String.isSameOrDescendantOf(ancestor: String): Boolean =
    this == ancestor || when (ancestor) {
        "/" -> startsWith("/")
        else -> startsWith(ancestor) && getOrNull(ancestor.length) == '/'
    }

internal fun List<String>.withoutDescendantDuplicates(): List<String> {
    val retained = mutableListOf<String>()
    for (candidate in distinct().sortedBy(String::length)) {
        if (retained.none { candidate.isSameOrDescendantOf(it) }) {
            retained += candidate
        }
    }
    return retained
}
