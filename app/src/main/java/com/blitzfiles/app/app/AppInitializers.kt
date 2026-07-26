/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.app

import android.os.AsyncTask
import android.os.Build
import android.webkit.WebView
import jcifs.context.SingletonContext
import com.blitzfiles.app.BuildConfig
import com.blitzfiles.app.coil.initializeCoil
import com.blitzfiles.app.filejob.FileDeletionRecovery
import com.blitzfiles.app.filejob.fileJobNotificationTemplate
import com.blitzfiles.app.ftpserver.ftpServerServiceNotificationTemplate
import com.blitzfiles.app.indexing.fileIndexingNotificationTemplate
import com.blitzfiles.app.hiddenapi.HiddenApi
import com.blitzfiles.app.provider.FileSystemProviders
import com.blitzfiles.app.settings.Settings
import com.blitzfiles.app.storage.FtpServerAuthenticator
import com.blitzfiles.app.storage.SftpServerAuthenticator
import com.blitzfiles.app.storage.SmbServerAuthenticator
import com.blitzfiles.app.storage.StorageVolumeListLiveData
import com.blitzfiles.app.storage.WebDavServerAuthenticator
import com.blitzfiles.app.theme.custom.CustomThemeHelper
import com.blitzfiles.app.theme.night.NightModeHelper
import java.util.Properties
import com.blitzfiles.app.provider.ftp.client.Client as FtpClient
import com.blitzfiles.app.provider.sftp.client.Client as SftpClient
import com.blitzfiles.app.provider.smb.client.Client as SmbClient
import com.blitzfiles.app.provider.webdav.client.Client as WebDavClient

val appInitializers = listOf(
    ::disableHiddenApiChecks,
    ::initializeWebViewDebugging,
    ::initializeCoil,
    ::initializeFileSystemProviders,
    ::upgradeApp,
    ::initializeLiveDataObjects,
    ::initializeCustomTheme,
    ::initializeNightMode,
    ::createNotificationChannels,
    ::initializeFileDeletionRecovery
)

private fun disableHiddenApiChecks() {
    HiddenApi.disableHiddenApiChecks()
}

private fun initializeWebViewDebugging() {
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

private fun initializeFileSystemProviders() {
    FileSystemProviders.install()
    FileSystemProviders.overflowWatchEvents = true
    // SingletonContext.init() calls NameServiceClientImpl.initCache() which connects to network.
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
        SingletonContext.init(
            Properties().apply {
                setProperty("jcifs.netbios.cachePolicy", "0")
                setProperty("jcifs.smb.client.maxVersion", "SMB1")
            }
        )
    }
    FtpClient.authenticator = FtpServerAuthenticator
    SftpClient.authenticator = SftpServerAuthenticator
    SmbClient.authenticator = SmbServerAuthenticator
    WebDavClient.authenticator = WebDavServerAuthenticator
}

private fun initializeFileDeletionRecovery() {
    FileDeletionRecovery.initialize(application)
}

private fun initializeLiveDataObjects() {
    // Force initialization of LiveData objects so that it won't happen on a background thread.
    StorageVolumeListLiveData.value
    Settings.FILE_LIST_DEFAULT_DIRECTORY.value
}

private fun initializeCustomTheme() {
    CustomThemeHelper.initialize(application)
}

private fun initializeNightMode() {
    NightModeHelper.initialize(application)
}

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannels(
            listOf(
                backgroundActivityStartNotificationTemplate.channelTemplate,
                fileJobNotificationTemplate.channelTemplate,
                fileIndexingNotificationTemplate.channelTemplate,
                ftpServerServiceNotificationTemplate.channelTemplate
            ).map { it.create(application) }
        )
    }
}
