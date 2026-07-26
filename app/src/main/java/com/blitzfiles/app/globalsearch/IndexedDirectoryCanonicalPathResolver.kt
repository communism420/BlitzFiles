/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import android.os.Environment

/**
 * Resolves directory aliases without touching the filesystem.
 *
 * In particular, this must not call `toRealPath()`: resolving Android storage symlinks that way
 * may block, require root access, or fail while an otherwise usable index is available.
 */
internal fun interface IndexedDirectoryCanonicalPathResolver {
    fun resolve(path: String): String?
}

internal class AndroidIndexedDirectoryCanonicalPathResolver(
    primarySharedStoragePath: String = defaultPrimarySharedStoragePath()
) : IndexedDirectoryCanonicalPathResolver {
    private val canonicalPrimarySharedStoragePath =
        normalizeAbsolutePathOrNull(primarySharedStoragePath)

    override fun resolve(path: String): String? {
        val normalizedPath = normalizeAbsolutePathOrNull(path) ?: return null
        return when (normalizedPath) {
            SDCARD_ALIAS_PATH,
            SELF_PRIMARY_ALIAS_PATH -> canonicalPrimarySharedStoragePath
            else -> normalizedPath
        }
    }

    private companion object {
        const val SDCARD_ALIAS_PATH = "/sdcard"
        const val SELF_PRIMARY_ALIAS_PATH = "/storage/self/primary"

        @Suppress("DEPRECATION")
        fun defaultPrimarySharedStoragePath(): String =
            Environment.getExternalStorageDirectory().absolutePath

        fun normalizeAbsolutePathOrNull(path: String): String? {
            if (!path.startsWith('/') || '\u0000' in path) {
                return null
            }
            val normalizedSegments = mutableListOf<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (normalizedSegments.isNotEmpty()) {
                        normalizedSegments.removeAt(normalizedSegments.lastIndex)
                    }
                    else -> normalizedSegments += segment
                }
            }
            return normalizedSegments.joinToString(separator = "/", prefix = "/")
        }
    }
}
