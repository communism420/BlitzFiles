/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import org.junit.Assert.assertEquals
import org.junit.Test

class FileDeletionIndexHintsTest {
    @Test
    fun deepestEnabledRootOwnsADeletedPath() {
        val hints = createDeletedPathHints(
            roots = listOf(
                root(1, "/", enabled = true),
                root(2, "/storage/emulated/0", enabled = true),
                root(3, "/storage/emulated/0/Download", enabled = false)
            ),
            deletedPathPrefixes = listOf("/storage/emulated/0/Download/file.zip")
        )

        assertEquals(
            mapOf(2L to setOf("/storage/emulated/0/Download/file.zip")),
            hints
        )
    }

    @Test
    fun deletingAncestorInvalidatesEveryContainedRoot() {
        val hints = createDeletedPathHints(
            roots = listOf(
                root(1, "/"),
                root(2, "/storage/emulated/0"),
                root(3, "/storage/emulated/0/Download"),
                root(4, "/data")
            ),
            deletedPathPrefixes = listOf("/storage")
        )

        assertEquals(
            mapOf(
                2L to setOf("/storage/emulated/0"),
                3L to setOf("/storage/emulated/0/Download"),
                1L to setOf("/storage")
            ),
            hints
        )
    }

    @Test
    fun pathHintsAreSplitWithoutDroppingEntries() {
        val paths = (0 until 300).mapTo(linkedSetOf()) { index -> "/storage/file-$index" }

        val batches = FileIndexingController.chunkPathHints(mapOf(7L to paths))

        assertEquals(listOf(256, 44), batches.map { batch -> batch.values.sumOf { it.size } })
        assertEquals(paths, batches.flatMapTo(linkedSetOf()) { it.getValue(7L) })
    }

    private fun root(
        id: Long,
        path: String,
        enabled: Boolean = true
    ) = IndexRoot(
        id = id,
        path = path,
        displayName = path,
        accessMode = IndexAccessMode.ROOT,
        isEnabled = enabled,
        createdAtMillis = 1
    )
}
