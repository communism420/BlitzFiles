/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import java8.nio.file.Path
import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.file.isSupportedArchive
import com.blitzfiles.app.provider.archive.archiveFile
import com.blitzfiles.app.provider.archive.isArchivePath
import com.blitzfiles.app.provider.document.isDocumentPath
import com.blitzfiles.app.provider.document.resolver.DocumentResolver
import com.blitzfiles.app.provider.linux.isLinuxPath

val Path.name: String
    get() = fileName?.toString() ?: if (isArchivePath) archiveFile.fileName.toString() else "/"

fun Path.toUserFriendlyString(): String = if (isLinuxPath) toFile().path else toUri().toString()

fun Path.isArchiveFile(mimeType: MimeType): Boolean = !isArchivePath && mimeType.isSupportedArchive

val Path.isLocalPath: Boolean
    get() =
        isLinuxPath || (isDocumentPath && DocumentResolver.isLocal(this as DocumentResolver.Path))

val Path.isRemotePath: Boolean
    get() = !isLocalPath
