/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.search.domain.model

/**
 * A directory subtree excluded from all roots, or from one root when [rootId] is set.
 */
data class IndexExclusion(
    val id: Long? = null,
    val rootId: Long? = null,
    val pathPrefix: String,
    val isEnabled: Boolean = true
) {
    init {
        require(id == null || id > 0) { "Exclusion ID must be positive" }
        require(rootId == null || rootId > 0) { "Root ID must be positive" }
        require(pathPrefix.isNotBlank()) { "Excluded path must not be blank" }
        require('\u0000' !in pathPrefix) { "Excluded path must not contain NUL" }
    }
}
