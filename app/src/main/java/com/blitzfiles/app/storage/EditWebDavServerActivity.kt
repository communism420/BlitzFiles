/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.storage

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import com.blitzfiles.app.app.AppActivity
import com.blitzfiles.app.util.args
import com.blitzfiles.app.util.putArgs

class EditWebDavServerActivity : AppActivity() {
    private val args by args<EditWebDavServerFragment.Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = EditWebDavServerFragment().putArgs(args)
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        }
    }
}
