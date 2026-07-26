/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.os.Bundle
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import com.blitzfiles.app.app.AppActivity
import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.file.fileProviderUri
import com.blitzfiles.app.util.ParcelableArgs
import com.blitzfiles.app.util.ParcelableParceler
import com.blitzfiles.app.util.args
import com.blitzfiles.app.util.createEditIntent
import com.blitzfiles.app.util.startActivitySafe

// Use a trampoline activity so that we can have a proper icon and title.
class EditFileActivity : AppActivity() {
    private val args by args<Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivitySafe(args.path.fileProviderUri.createEditIntent(args.mimeType))
        finish()
    }

    @Parcelize
    class Args(
        val path: @WriteWith<ParcelableParceler> Path,
        val mimeType: MimeType
    ) : ParcelableArgs
}
