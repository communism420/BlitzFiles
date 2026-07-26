/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.storage

import com.blitzfiles.app.provider.smb.client.Authenticator
import com.blitzfiles.app.provider.smb.client.Authority
import com.blitzfiles.app.settings.Settings
import com.blitzfiles.app.util.valueCompat

object SmbServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<SmbServer>()

    override fun getPassword(authority: Authority): String? {
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is SmbServer && it.authority == authority
        } as SmbServer?
        return server?.password
    }

    fun addTransientServer(server: SmbServer) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: SmbServer) {
        synchronized(transientServers) { transientServers -= server }
    }
}
