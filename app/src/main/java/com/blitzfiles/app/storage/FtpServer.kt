/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.storage

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import com.blitzfiles.app.R
import com.blitzfiles.app.provider.ftp.client.Authority
import com.blitzfiles.app.provider.ftp.createFtpRootPath
import com.blitzfiles.app.util.createIntent
import com.blitzfiles.app.util.putArgs
import kotlin.random.Random

@Parcelize
class FtpServer(
    override val id: Long,
    override val customName: String?,
    val authority: Authority,
    val password: String,
    val relativePath: String
) : Storage() {
    constructor(
        id: Long?,
        customName: String?,
        authority: Authority,
        password: String,
        relativePath: String
    ) : this(id ?: Random.nextLong(), customName, authority, password, relativePath)

    override val iconRes: Int
        @DrawableRes
        get() = R.drawable.computer_icon_white_24dp

    override fun getDefaultName(context: Context): String =
        if (relativePath.isNotEmpty()) "$authority/$relativePath" else authority.toString()

    override val description: String
        get() = authority.toString()

    override val path: Path
        get() = authority.createFtpRootPath().resolve(relativePath)

    override fun createEditIntent(): Intent =
        EditFtpServerActivity::class.createIntent().putArgs(EditFtpServerFragment.Args(this))
}
