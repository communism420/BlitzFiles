/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.model

/**
 * Search metadata for a local filesystem entry.
 *
 * The index intentionally stores only metadata needed for search and result presentation. Volatile
 * attributes such as permissions and SELinux context remain owned by the filesystem layer.
 */
data class IndexedFileRecord(
    val id: Long? = null,
    val rootId: Long,
    val path: String,
    val parentPath: String,
    val name: String,
    val extension: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val createdAtMillis: Long? = null,
    val indexedAtMillis: Long,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean = false,
    val isHidden: Boolean = false,
    val requiresRoot: Boolean = false,
    val symbolicLinkTarget: String? = null,
    val deviceId: Long? = null,
    val inode: Long? = null,
    val scanGeneration: Long
) {
    init {
        require(id == null || id > 0) { "Entry ID must be positive" }
        require(rootId > 0) { "Root ID must be positive" }
        require(path.isNotBlank()) { "Entry path must not be blank" }
        require('\u0000' !in path) { "Entry path must not contain NUL" }
        require('\u0000' !in parentPath) { "Parent path must not contain NUL" }
        require(name.isNotEmpty()) { "Entry name must not be empty" }
        require('\u0000' !in name) { "Entry name must not contain NUL" }
        require(sizeBytes >= 0) { "Entry size must not be negative" }
        require(modifiedAtMillis >= 0) { "Modification time must not be negative" }
        require(createdAtMillis == null || createdAtMillis >= 0) {
            "Creation time must not be negative"
        }
        require(indexedAtMillis >= 0) { "Index time must not be negative" }
        require(scanGeneration >= 0) { "Scan generation must not be negative" }
    }
}
