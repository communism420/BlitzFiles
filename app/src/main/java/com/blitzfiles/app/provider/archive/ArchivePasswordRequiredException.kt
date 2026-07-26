/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.provider.archive

import android.content.Context
import java8.nio.file.Path
import com.blitzfiles.app.fileaction.ArchivePasswordDialogActivity
import com.blitzfiles.app.fileaction.ArchivePasswordDialogFragment
import com.blitzfiles.app.provider.common.UserAction
import com.blitzfiles.app.provider.common.UserActionRequiredException
import com.blitzfiles.app.util.createIntent
import com.blitzfiles.app.util.putArgs
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class ArchivePasswordRequiredException(
    private val file: Path,
    reason: String?
) :
    UserActionRequiredException(file.toString(), null, reason) {

    override fun getUserAction(continuation: Continuation<Boolean>, context: Context): UserAction {
        return UserAction(
            ArchivePasswordDialogActivity::class.createIntent().putArgs(
                ArchivePasswordDialogFragment.Args(file) { continuation.resume(it) }
            ), ArchivePasswordDialogFragment.getTitle(context),
            ArchivePasswordDialogFragment.getMessage(file, context)
        )
    }
}
