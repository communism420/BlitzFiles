/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.fileproperties

import android.os.AsyncTask
import java8.nio.file.Path
import com.blitzfiles.app.file.FileItem
import com.blitzfiles.app.file.loadFileItem
import com.blitzfiles.app.util.Failure
import com.blitzfiles.app.util.Loading
import com.blitzfiles.app.util.Stateful
import com.blitzfiles.app.util.Success
import com.blitzfiles.app.util.valueCompat

class FileLiveData private constructor(
    path: Path,
    file: FileItem?
) : PathObserverLiveData<Stateful<FileItem>>(path) {
    constructor(path: Path) : this(path, null)

    constructor(file: FileItem) : this(file.path, file)

    init {
        if (file != null) {
            value = Success(file)
        } else {
            loadValue()
        }
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val file = path.loadFileItem()
                Success(file)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
