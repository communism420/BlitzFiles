/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blitzfiles.app.R
import com.blitzfiles.app.util.NotificationChannelTemplate
import com.blitzfiles.app.util.NotificationTemplate

val fileIndexingNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "file_indexing",
            R.string.notification_channel_file_indexing_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_file_indexing_description,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        contentTitleRes = R.string.file_indexing_notification_title,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_PROGRESS,
        priority = NotificationCompat.PRIORITY_LOW
    )
