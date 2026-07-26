/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.model

enum class IndexAccessMode {
    STANDARD,
    ROOT
}

enum class IndexScanStatus {
    NEVER_RUN,
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED
}

/**
 * A configured local filesystem root.
 *
 * Paths are kept as opaque strings here because normalization depends on the filesystem provider.
 */
data class IndexRoot(
    val id: Long? = null,
    val path: String,
    val displayName: String,
    val accessMode: IndexAccessMode,
    val isEnabled: Boolean = true,
    val includeHidden: Boolean = true,
    val followSymbolicLinks: Boolean = false,
    val createdAtMillis: Long,
    val lastScanStartedAtMillis: Long? = null,
    val lastScanCompletedAtMillis: Long? = null,
    val lastScanStatus: IndexScanStatus = IndexScanStatus.NEVER_RUN,
    val lastScanError: String? = null,
    val scanGeneration: Long = 0
) {
    init {
        require(id == null || id > 0) { "Root ID must be positive" }
        require(path.isNotBlank()) { "Root path must not be blank" }
        require('\u0000' !in path) { "Root path must not contain NUL" }
        require(displayName.isNotBlank()) { "Root display name must not be blank" }
        require(createdAtMillis >= 0) { "Creation time must not be negative" }
        require(lastScanStartedAtMillis == null || lastScanStartedAtMillis >= 0) {
            "Last scan start time must not be negative"
        }
        require(lastScanCompletedAtMillis == null || lastScanCompletedAtMillis >= 0) {
            "Last scan time must not be negative"
        }
        require(lastScanError == null || '\u0000' !in lastScanError) {
            "Last scan error must not contain NUL"
        }
        require(scanGeneration >= 0) { "Scan generation must not be negative" }
    }
}
