/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.indexer

import com.blitzfiles.search.domain.model.IndexAccessMode
import java.io.IOException

/**
 * Minimal blocking filesystem boundary required by the indexer.
 *
 * Implementations may use Android storage APIs, a root process, or an in-memory filesystem. The
 * indexer invokes every method on its configured I/O dispatcher.
 */
interface IndexFileSystem {
    @Throws(IOException::class, SecurityException::class)
    fun normalize(path: String, accessMode: IndexAccessMode): String

    /**
     * Returns null when the entry no longer exists. Other access failures should be reported as an
     * exception so that the indexer can preserve previously indexed records for that subtree.
     */
    @Throws(IOException::class, SecurityException::class)
    fun readMetadata(
        path: String,
        accessMode: IndexAccessMode,
        followSymbolicLinks: Boolean
    ): IndexFileMetadata?

    @Throws(IOException::class, SecurityException::class)
    fun visitChildren(
        directoryPath: String,
        accessMode: IndexAccessMode,
        visitor: (String) -> Unit
    )
}

data class IndexFileMetadata(
    val path: String,
    val parentPath: String,
    val name: String,
    val extension: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val createdAtMillis: Long?,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val isHidden: Boolean,
    val symbolicLinkTarget: String?,
    val deviceId: Long?,
    val inode: Long?
) {
    init {
        require(path.isNotBlank()) { "Path must not be blank" }
        require(name.isNotEmpty()) { "Name must not be empty" }
        require(sizeBytes >= 0) { "Size must not be negative" }
        require(modifiedAtMillis >= 0) { "Modification time must not be negative" }
        require(createdAtMillis == null || createdAtMillis >= 0) {
            "Creation time must not be negative"
        }
    }

    val traversalIdentity: String
        get() = when {
            deviceId != null && inode != null -> "inode:$deviceId:$inode"
            isSymbolicLink && symbolicLinkTarget != null -> "path:$symbolicLinkTarget"
            else -> "path:$path"
        }
}
