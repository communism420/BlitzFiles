/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.app.provider.common.ByteStringPath
import com.blitzfiles.app.provider.common.toByteString
import com.blitzfiles.app.provider.linux.LinuxFileSystemProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialFilesIndexFileSystemTest {
    @Test
    fun absoluteOpaqueTargetIsResolvedWithoutCallingItsPathOperations() {
        val link = LinuxFileSystemProvider.fileSystem.getPath(
            "/apex/com.android.hardware.biometrics.face.virtual@2/lib64/libc++.so"
        )
        val opaqueTarget = ByteStringPath("/system/lib64/libc++.so".toByteString())

        val target = resolveSymbolicLinkTarget(link, opaqueTarget)

        assertEquals("/system/lib64/libc++.so", target)
    }

    @Test
    fun relativeOpaqueTargetIsResolvedAgainstLinkParent() {
        val link = LinuxFileSystemProvider.fileSystem.getPath(
            "/apex/com.example/lib64/libexample.so"
        )
        val opaqueTarget = ByteStringPath("../lib/libexample.so".toByteString())

        val target = resolveSymbolicLinkTarget(link, opaqueTarget)

        assertEquals("/apex/com.example/lib/libexample.so", target)
    }
}
