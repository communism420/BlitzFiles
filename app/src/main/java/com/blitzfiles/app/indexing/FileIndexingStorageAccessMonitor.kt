/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.os.Build

/**
 * Keeps a cheap, process-local view of the storage app-op for hot indexing checkpoints.
 *
 * Calling [FileIndexingStorageAccess.isGranted] for every indexed entry would add a Binder lookup
 * to the hottest traversal loop. App-ops callbacks let the indexer stop promptly when access is
 * revoked without slowing down normal scans. Admission boundaries still use [refresh] so a stale
 * callback can never start or resume work.
 */
internal class FileIndexingStorageAccessMonitor(
    context: Context,
    private val onAccessRevoked: () -> Unit = {}
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val appOpsManager = applicationContext.getSystemService(AppOpsManager::class.java)
    private val watchedOperation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AppOpsManager.permissionToOp(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            ?: MANAGE_EXTERNAL_STORAGE_APP_OP
    } else {
        checkNotNull(AppOpsManager.permissionToOp(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            "WRITE_EXTERNAL_STORAGE must map to an app-op"
        }
    }
    private val listener = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (
            operation == watchedOperation &&
            (packageName == null || packageName == applicationContext.packageName)
        ) {
            if (!refresh()) {
                onAccessRevoked()
            }
        }
    }

    @Volatile
    private var granted = FileIndexingStorageAccess.isGranted(applicationContext)

    init {
        appOpsManager.startWatchingMode(
            watchedOperation,
            applicationContext.packageName,
            listener
        )
    }

    /**
     * Performs an authoritative check. Use this at every command admission boundary.
     */
    @Synchronized
    fun refresh(): Boolean {
        val refreshed = FileIndexingStorageAccess.isGranted(applicationContext)
        granted = refreshed
        return refreshed
    }

    /**
     * Returns the callback-maintained value without a Binder call.
     */
    fun isGranted(): Boolean = granted

    override fun close() {
        appOpsManager.stopWatchingMode(listener)
    }

    private companion object {
        // Public permissionToOp() normally returns this hidden system app-op on Android 11+.
        const val MANAGE_EXTERNAL_STORAGE_APP_OP = "android:manage_external_storage"
    }
}
