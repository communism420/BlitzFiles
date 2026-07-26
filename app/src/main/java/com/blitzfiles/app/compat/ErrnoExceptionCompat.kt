/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.compat

import android.system.ErrnoException
import com.blitzfiles.app.hiddenapi.RestrictedHiddenApi
import com.blitzfiles.app.util.lazyReflectedField

@RestrictedHiddenApi
private val functionNameField by lazyReflectedField(ErrnoException::class.java, "functionName")

val ErrnoException.functionNameCompat: String
    get() = functionNameField.get(this) as String
