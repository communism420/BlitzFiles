/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filejob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDeletionStoreTest {
    @Test
    fun pathMatchingUsesSeparatorBoundaries() {
        assertTrue("/a".isSamePathOrDescendantOf("/a"))
        assertTrue("/a/file.txt".isSamePathOrDescendantOf("/a"))
        assertFalse("/ab/file.txt".isSamePathOrDescendantOf("/a"))
    }

    @Test
    fun rootPrefixMatchesEveryAbsoluteDescendant() {
        assertTrue("/storage/file.txt".isSamePathOrDescendantOf("/"))
        assertFalse("relative/file.txt".isSamePathOrDescendantOf("/"))
    }

    @Test
    fun mergingParentPrefixCompactsExistingChildren() {
        val children = DeletedPathPrefixes(
            uriPrefixes = setOf("file:///a/one", "file:///a/two"),
            indexPathPrefixes = setOf("/a/one", "/a/two")
        )
        val parent = DeletedPathPrefixes(
            uriPrefixes = setOf("file:///a"),
            indexPathPrefixes = setOf("/a")
        )

        assertEquals(
            DeletedPathPrefixes(setOf("file:///a"), setOf("/a")),
            children.mergedWith(parent)
        )
    }

    @Test
    fun compactingIndexPathsKeepsParentAndBoundarySafeSibling() {
        assertEquals(
            setOf("/a", "/a-", "/ab"),
            compactPathPrefixes(listOf("/a/one", "/a-", "/ab", "/a", "/a/two"))
        )
    }
}
