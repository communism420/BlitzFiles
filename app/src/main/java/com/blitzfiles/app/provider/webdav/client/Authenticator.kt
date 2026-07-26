/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.provider.webdav.client

interface Authenticator {
    fun getAuthentication(authority: Authority): Authentication?
}
