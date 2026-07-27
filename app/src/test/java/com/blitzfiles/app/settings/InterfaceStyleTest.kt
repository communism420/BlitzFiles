/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceStyleTest {
    @Test
    fun persistedOrdinalOrderIsStable() {
        assertEquals(
            listOf(InterfaceStyle.CLASSIC, InterfaceStyle.FILE_MANAGER_PLUS),
            InterfaceStyle.entries
        )
    }
}
