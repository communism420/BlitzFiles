/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import com.blitzfiles.app.file.FileItem
import com.blitzfiles.app.file.loadFileItem
import com.blitzfiles.app.filejob.DeletedPathPrefixes
import com.blitzfiles.app.provider.common.newDirectoryStream
import com.blitzfiles.app.util.CloseableLiveData
import com.blitzfiles.app.util.Failure
import com.blitzfiles.app.util.Loading
import com.blitzfiles.app.util.Stateful
import com.blitzfiles.app.util.Success
import com.blitzfiles.app.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null
    private val loadGeneration = AtomicLong()
    @Volatile
    private var deletedPaths = DeletedPathPrefixes.EMPTY

    private val observer: PathObserver

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        val generation = loadGeneration.incrementAndGet()
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value = try {
                path.newDirectoryStream().use { directoryStream ->
                    val fileList = mutableListOf<FileItem>()
                    for (path in directoryStream) {
                        try {
                            fileList.add(path.loadFileItem())
                        } catch (e: DirectoryIteratorException) {
                            // TODO: Ignoring such a file can be misleading and we need to support
                            //  files without information.
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    Success(fileList as List<FileItem>)
                }
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            MAIN_HANDLER.post {
                if (loadGeneration.get() == generation) {
                    this.value = value.withoutDeletedPaths(deletedPaths)
                }
            }
        }
    }

    internal fun updateDeletedPaths(deletedPaths: DeletedPathPrefixes) {
        if (this.deletedPaths == deletedPaths) {
            return
        }
        this.deletedPaths = deletedPaths
        value = value?.withoutDeletedPaths(deletedPaths)
        future?.cancel(true)
        if (hasActiveObservers()) {
            loadValue()
        } else {
            loadGeneration.incrementAndGet()
            isChangedWhileInactive = true
        }
    }

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        observer.close()
        loadGeneration.incrementAndGet()
        future?.cancel(true)
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
