/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.content.Intent
import android.os.Bundle
import java8.nio.file.Path
import com.blitzfiles.app.app.AppActivity
import com.blitzfiles.app.app.application
import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.file.asMimeTypeOrNull
import com.blitzfiles.app.file.fileProviderUri
import com.blitzfiles.app.filejob.FileJobService
import com.blitzfiles.app.provider.archive.isArchivePath
import com.blitzfiles.app.util.createViewIntent
import com.blitzfiles.app.util.extraPath
import com.blitzfiles.app.util.startActivitySafe

class OpenFileActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val path = intent.extraPath
        val mimeType = intent.type?.asMimeTypeOrNull()
        if (path != null && mimeType != null) {
            openFile(path, mimeType)
        }
        finish()
    }

    private fun openFile(path: Path, mimeType: MimeType) {
        if (path.isArchivePath) {
            FileJobService.open(path, mimeType, false, this)
        } else {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply { extraPath = path }
            startActivitySafe(intent)
        }
    }

    companion object {
        private const val ACTION_OPEN_FILE = "com.blitzfiles.app.intent.action.OPEN_FILE"

        fun createIntent(path: Path, mimeType: MimeType): Intent =
            Intent(ACTION_OPEN_FILE)
                .setPackage(application.packageName)
                .setType(mimeType.value)
                .apply { extraPath = path }
    }
}
