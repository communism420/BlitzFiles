/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.blitzfiles.app.compat.checkSelfPermissionCompat

/**
 * Single source of truth for the storage permission required by every filesystem scan.
 *
 * Root access is deliberately irrelevant here. On Android 11 and newer indexing is forbidden
 * until the user grants "All files access", even when the selected provider can read files via
 * root. Older Android versions use the strongest storage permission available on that platform.
 */
internal object FileIndexingStorageAccess {
    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermissionCompat(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
}
