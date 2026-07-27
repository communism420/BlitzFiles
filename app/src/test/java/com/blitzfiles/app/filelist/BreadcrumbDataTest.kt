/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbDataTest {
    @Test
    fun missingInitialValueHasNoNavigableParent() {
        assertFalse(null.hasNavigableParent)
    }

    @Test
    fun selectedRootHasNoNavigableParent() {
        assertFalse(BreadcrumbData(emptyList(), emptyList(), 0).hasNavigableParent)
    }

    @Test
    fun selectedChildHasNavigableParent() {
        assertTrue(BreadcrumbData(emptyList(), emptyList(), 1).hasNavigableParent)
    }
}
