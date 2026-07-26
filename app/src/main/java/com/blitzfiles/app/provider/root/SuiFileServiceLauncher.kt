/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.provider.root

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import com.blitzfiles.app.BuildConfig
import com.blitzfiles.app.app.application
import com.blitzfiles.app.provider.remote.IRemoteFileService
import com.blitzfiles.app.provider.remote.RemoteFileServiceInterface
import com.blitzfiles.app.provider.remote.RemoteFileSystemException
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object SuiFileServiceLauncher {
    private val lock = Any()

    private var isSuiIntialized = false

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
    fun isSuiAvailable(): Boolean {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }
            if (!isSuiIntialized) {
                Sui.init(application.packageName)
                isSuiIntialized = true
            }
            return Sui.isSui()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(RemoteFileSystemException::class)
    fun launchService(): IRemoteFileService {
        synchronized(lock) {
            if (!isSuiAvailable()) {
                throw RemoteFileSystemException("Sui isn't available")
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                val granted = try {
                    runBlocking<Boolean> {
                        try {
                            withTimeout(RootFileService.TIMEOUT_MILLIS) {
                                suspendCancellableCoroutine { continuation ->
                                    val listener =
                                        object : Shizuku.OnRequestPermissionResultListener {
                                            override fun onRequestPermissionResult(
                                                requestCode: Int,
                                                grantResult: Int
                                            ) {
                                                Shizuku.removeRequestPermissionResultListener(this)
                                                if (continuation.isActive) {
                                                    val granted =
                                                        grantResult ==
                                                            PackageManager.PERMISSION_GRANTED
                                                    continuation.resume(granted)
                                                }
                                            }
                                        }
                                    Shizuku.addRequestPermissionResultListener(listener)
                                    continuation.invokeOnCancellation {
                                        Shizuku.removeRequestPermissionResultListener(listener)
                                    }
                                    try {
                                        Shizuku.requestPermission(listener.hashCode())
                                    } catch (exception: Exception) {
                                        Shizuku.removeRequestPermissionResultListener(listener)
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(exception)
                                        }
                                    }
                                }
                            }
                        } catch (exception: TimeoutCancellationException) {
                            throw RemoteFileSystemException(exception)
                        }
                    }
                } catch (e: InterruptedException) {
                    throw RemoteFileSystemException(e)
                } catch (e: RemoteFileSystemException) {
                    throw e
                } catch (e: Exception) {
                    throw RemoteFileSystemException(e)
                }
                if (!granted) {
                    throw RemoteFileSystemException("Sui permission isn't granted")
                }
            }
            return try {
                runBlocking {
                    try {
                        withTimeout(RootFileService.TIMEOUT_MILLIS) {
                            suspendCancellableCoroutine { continuation ->
                                val serviceArgs = Shizuku.UserServiceArgs(
                                    ComponentName(application, SuiFileServiceInterface::class.java)
                                )
                                    .debuggable(BuildConfig.DEBUG)
                                    .daemon(false)
                                    .processNameSuffix("sui")
                                    .version(BuildConfig.VERSION_CODE)
                                val connection = object : ServiceConnection {
                                    override fun onServiceConnected(
                                        name: ComponentName,
                                        service: IBinder
                                    ) {
                                        val serviceInterface =
                                            IRemoteFileService.Stub.asInterface(service)
                                        continuation.resume(serviceInterface)
                                    }

                                    override fun onServiceDisconnected(name: ComponentName) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                RemoteFileSystemException(
                                                    "Sui service disconnected"
                                                )
                                            )
                                        }
                                    }

                                    override fun onBindingDied(name: ComponentName) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                RemoteFileSystemException("Sui binding died")
                                            )
                                        }
                                    }

                                    override fun onNullBinding(name: ComponentName) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                RemoteFileSystemException("Sui binding is null")
                                            )
                                        }
                                    }
                                }
                                Shizuku.bindUserService(serviceArgs, connection)
                                continuation.invokeOnCancellation {
                                    Shizuku.unbindUserService(serviceArgs, connection, true)
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw RemoteFileSystemException(e)
                    }
                }
            } catch (e: InterruptedException) {
                throw RemoteFileSystemException(e)
            }
        }
    }
}

@Keep
@RequiresApi(Build.VERSION_CODES.M)
class SuiFileServiceInterface : RemoteFileServiceInterface() {
    init {
        RootFileService.main()
    }
}
