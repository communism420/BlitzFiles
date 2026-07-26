/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.model

data class IndexStatistics(
    val rootCount: Long,
    val enabledRootCount: Long,
    val exclusionCount: Long,
    val entryCount: Long,
    val fileCount: Long,
    val directoryCount: Long,
    val rootRequiredEntryCount: Long,
    val hiddenEntryCount: Long,
    val symbolicLinkCount: Long,
    val totalFileSizeBytes: Long,
    val databaseSizeBytes: Long,
    val lastIndexedAtMillis: Long?
)
