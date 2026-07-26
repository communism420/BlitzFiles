/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot

/**
 * Enforces access-mode invariants that are specific to Android's local filesystem.
 *
 * Android applications can stat the filesystem root but cannot enumerate it as their app UID.
 * Treating "/" as a standard-access root therefore creates a misleading one-entry index followed
 * by an unavoidable EACCES error.
 */
internal object IndexRootAccessPolicy {
    fun requiresRoot(normalizedPath: String): Boolean =
        normalizedPath == SYSTEM_ROOT_PATH

    fun resolve(
        normalizedPath: String,
        requestedMode: IndexAccessMode
    ): IndexAccessMode {
        require(!requiresRoot(normalizedPath) || requestedMode == IndexAccessMode.ROOT) {
            "The filesystem root cannot be indexed without root access"
        }
        return requestedMode
    }

    fun requireExclusiveMode(
        existingRoots: Collection<IndexRoot>,
        normalizedPath: String,
        requestedMode: IndexAccessMode
    ) {
        val conflictingRoot = existingRoots.firstOrNull { root ->
            root.path != normalizedPath && root.accessMode != requestedMode
        }
        require(conflictingRoot == null) {
            "Every indexed location must use the same access mode; " +
                "${conflictingRoot?.path} uses ${conflictingRoot?.accessMode}"
        }
    }

    private const val SYSTEM_ROOT_PATH = "/"
}
