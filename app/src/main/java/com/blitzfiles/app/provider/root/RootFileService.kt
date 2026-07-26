/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.provider.root

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.util.Log
import com.blitzfiles.app.BuildConfig
import com.blitzfiles.app.app.application
import com.blitzfiles.app.indexing.FileIndexingStorageAccess
import com.blitzfiles.app.indexing.InitialIndexingCoordinator
import com.blitzfiles.app.provider.FileSystemProviders
import com.blitzfiles.app.provider.remote.RemoteFileService
import com.blitzfiles.app.provider.remote.RemoteFileSystemException
import com.blitzfiles.app.provider.remote.RemoteInterface
import com.blitzfiles.app.util.lazyReflectedMethod

val isRunningAsRoot = Process.myUid() == 0

@SuppressLint("StaticFieldLeak")
lateinit var rootContext: Context private set

object RootFileService : RemoteFileService(
    RemoteInterface {
        if (
            !FileIndexingStorageAccess.isGranted(application) ||
            InitialIndexingCoordinator.shouldShowAllFilesAccessInformation()
        ) {
            throw RemoteFileSystemException(
                "The All files access introduction must finish before requesting root access"
            )
        }
        var suiError: RemoteFileSystemException? = null
        try {
            if (SuiFileServiceLauncher.isSuiAvailable()) {
                return@RemoteInterface SuiFileServiceLauncher.launchService()
            }
        } catch (error: Exception) {
            // A stale Sui binder can fail with unchecked Shizuku exceptions. Preserve that
            // diagnostic, but still try the independent Magisk/libsu provider.
            suiError = error.asRemoteFileSystemException()
        }
        try {
            LibSuFileServiceLauncher.launchService()
        } catch (error: Exception) {
            val libSuError = error.asRemoteFileSystemException()
            suiError?.let(libSuError::addSuppressed)
            throw libSuError
        }
    }
) {
    const val TIMEOUT_MILLIS = 15 * 1000L

    private val LOG_TAG = RootFileService::class.java.simpleName

    // Not actually restricted because there's no restriction when running as root.
    //@RestrictedHiddenApi
    private val activityThreadCurrentActivityThreadMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "currentActivityThread"
    )
    //@RestrictedHiddenApi
    private val activityThreadGetSystemContextMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "getSystemContext"
    )

    fun main() {
        Log.i(LOG_TAG, "Creating package context")
        rootContext = createPackageContext(BuildConfig.APPLICATION_ID)
        Log.i(LOG_TAG, "Installing file system providers")
        FileSystemProviders.install()
        FileSystemProviders.overflowWatchEvents = true
    }

    private fun createPackageContext(packageName: String): Context {
        val activityThread = activityThreadCurrentActivityThreadMethod.invoke(null)
        val systemContext = activityThreadGetSystemContextMethod.invoke(activityThread) as Context
        return systemContext.createPackageContext(
            packageName, Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )
    }
}

private fun Exception.asRemoteFileSystemException(): RemoteFileSystemException =
    this as? RemoteFileSystemException ?: RemoteFileSystemException(this)
