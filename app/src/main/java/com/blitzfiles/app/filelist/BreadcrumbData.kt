/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.content.Context
import java8.nio.file.Path

data class BreadcrumbData(
    val paths: List<Path>,
    val nameProducers: List<(Context) -> String>,
    val selectedIndex: Int
)

/**
 * Breadcrumb data is populated lazily when its LiveData becomes active. Treat the short interval
 * before its first value as the root level so startup observers can safely query navigation state.
 */
internal val BreadcrumbData?.hasNavigableParent: Boolean
    get() = this != null && selectedIndex > 0
