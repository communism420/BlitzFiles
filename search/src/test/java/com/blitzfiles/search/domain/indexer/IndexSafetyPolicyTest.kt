/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.indexer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexSafetyPolicyTest {
    @Test
    fun recognizesProtectedSystemTreesWithoutBlockingFilesystemRoot() {
        assertTrue(IndexSafetyPolicy.isProtected("/proc"))
        assertTrue(IndexSafetyPolicy.isProtected("/proc/123/fd"))
        assertTrue(IndexSafetyPolicy.isProtected("/dev/block"))
        assertTrue(IndexSafetyPolicy.isProtected("/data_mirror/data_ce/null/0"))
        assertTrue(IndexSafetyPolicy.isProtected("/data/media/0/DCIM"))
        assertTrue(IndexSafetyPolicy.isProtected("/mnt/androidwritable/0/emulated/0"))
        assertTrue(IndexSafetyPolicy.isProtected("/mnt/runtime/default/emulated/0"))
        assertTrue(IndexSafetyPolicy.isProtected("/mnt/user/0/emulated/0"))
        assertTrue(IndexSafetyPolicy.isProtected("/mnt/installer/0/emulated/0"))
        assertTrue(IndexSafetyPolicy.isProtected("/mnt/pass_through/0/emulated/0"))
        assertTrue(IndexSafetyPolicy.isProtected("/mnt/media_rw/emulated"))
        assertFalse(IndexSafetyPolicy.isProtected("/"))
        assertFalse(IndexSafetyPolicy.isProtected("/data/mediator"))
        assertFalse(IndexSafetyPolicy.isProtected("/mnt/expand"))
        assertFalse(IndexSafetyPolicy.isProtected("/storage/emulated/0"))
        assertFalse(IndexSafetyPolicy.isProtected("/processor"))
    }

    @Test
    fun ancestorExclusionBlocksNestedRoot() {
        assertEquals(
            listOf("/storage/emulated/0"),
            IndexSafetyPolicy.effectiveExclusions(
                "/storage/emulated/0",
                listOf("/storage")
            )
        )
    }

    @Test
    fun rootScanIncludesConfiguredAndBuiltInExclusionsWithoutDuplicates() {
        val exclusions = IndexSafetyPolicy.effectiveExclusions(
            "/",
            listOf("/data/cache", "/data/cache/thumbnails", "/proc")
        )

        assertTrue("/data/cache" in exclusions)
        assertTrue("/proc" in exclusions)
        assertTrue("/data_mirror" in exclusions)
        assertTrue("/data/media" in exclusions)
        assertTrue("/mnt/androidwritable" in exclusions)
        assertTrue("/mnt/runtime" in exclusions)
        assertTrue("/mnt/user" in exclusions)
        assertTrue("/mnt/installer" in exclusions)
        assertTrue("/mnt/pass_through" in exclusions)
        assertTrue("/mnt/media_rw" in exclusions)
        assertFalse("/data/cache/thumbnails" in exclusions)
        assertEquals(exclusions.distinct(), exclusions)
    }
}
