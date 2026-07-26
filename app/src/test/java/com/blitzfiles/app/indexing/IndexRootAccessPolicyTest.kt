/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexRootAccessPolicyTest {
    @Test
    fun systemRootRejectsStandardAccessInsteadOfSilentlyEscalating() {
        assertTrue(IndexRootAccessPolicy.requiresRoot("/"))
        assertThrows(IllegalArgumentException::class.java) {
            IndexRootAccessPolicy.resolve("/", IndexAccessMode.STANDARD)
        }
        assertEquals(
            IndexAccessMode.ROOT,
            IndexRootAccessPolicy.resolve("/", IndexAccessMode.ROOT)
        )
    }

    @Test
    fun otherPathsKeepTheRequestedAccessMode() {
        val internalStorage = "/storage/emulated/0"

        assertFalse(IndexRootAccessPolicy.requiresRoot(internalStorage))
        assertEquals(
            IndexAccessMode.STANDARD,
            IndexRootAccessPolicy.resolve(internalStorage, IndexAccessMode.STANDARD)
        )
        assertEquals(
            IndexAccessMode.ROOT,
            IndexRootAccessPolicy.resolve(internalStorage, IndexAccessMode.ROOT)
        )
    }

    @Test
    fun rejectsMixedAccessModesAcrossConfiguredRoots() {
        val standardRoot = root("/storage/emulated/0", IndexAccessMode.STANDARD)
        val rootRoot = root("/", IndexAccessMode.ROOT)

        assertThrows(IllegalArgumentException::class.java) {
            IndexRootAccessPolicy.requireExclusiveMode(
                existingRoots = listOf(standardRoot),
                normalizedPath = "/storage/1234-5678",
                requestedMode = IndexAccessMode.ROOT
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IndexRootAccessPolicy.requireExclusiveMode(
                existingRoots = listOf(rootRoot),
                normalizedPath = "/storage/emulated/0",
                requestedMode = IndexAccessMode.STANDARD
            )
        }
    }

    @Test
    fun permitsSameModeAndAtomicReplacementOfTheOnlyRoot() {
        val standardRoot = root("/storage/emulated/0", IndexAccessMode.STANDARD)

        IndexRootAccessPolicy.requireExclusiveMode(
            existingRoots = listOf(standardRoot),
            normalizedPath = "/storage/1234-5678",
            requestedMode = IndexAccessMode.STANDARD
        )
        IndexRootAccessPolicy.requireExclusiveMode(
            existingRoots = listOf(standardRoot),
            normalizedPath = standardRoot.path,
            requestedMode = IndexAccessMode.ROOT
        )
    }

    @Test
    fun permitsSystemAndPrimaryStorageRootsWhenBothUseRootAccess() {
        val systemRoot = root("/", IndexAccessMode.ROOT)

        IndexRootAccessPolicy.requireExclusiveMode(
            existingRoots = listOf(systemRoot),
            normalizedPath = "/storage/emulated/0",
            requestedMode = IndexAccessMode.ROOT
        )
    }

    private fun root(path: String, accessMode: IndexAccessMode) =
        IndexRoot(
            path = path,
            displayName = path,
            accessMode = accessMode,
            createdAtMillis = 1
        )
}
