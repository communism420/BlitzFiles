/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import com.blitzfiles.app.file.FileItem
import com.blitzfiles.app.filejob.DeletedPathPrefixes
import com.blitzfiles.app.filejob.toDeletionUriKey
import com.blitzfiles.app.util.Failure
import com.blitzfiles.app.util.Loading
import com.blitzfiles.app.util.Stateful
import com.blitzfiles.app.util.Success

internal fun Stateful<List<FileItem>>.withoutDeletedPaths(
    deletedPaths: DeletedPathPrefixes
): Stateful<List<FileItem>> {
    if (deletedPaths.uriPrefixes.isEmpty()) {
        return this
    }
    val currentFiles = value ?: return this
    val filteredFiles = currentFiles.filterNot { file ->
        deletedPaths.containsUri(file.path.toDeletionUriKey())
    }
    if (filteredFiles.size == currentFiles.size) {
        return this
    }
    return when (this) {
        is Loading -> Loading(filteredFiles)
        is Failure -> Failure(filteredFiles, throwable)
        is Success -> Success(filteredFiles)
    }
}
