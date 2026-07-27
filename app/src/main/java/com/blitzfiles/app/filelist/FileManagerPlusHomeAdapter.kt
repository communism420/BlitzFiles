/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import kotlin.math.max
import com.blitzfiles.app.R
import com.blitzfiles.app.databinding.FileManagerPlusHomeItemBinding
import com.blitzfiles.app.ui.SimpleAdapter
import com.blitzfiles.app.util.layoutInflater

data class FileManagerPlusHomeItem(
    val id: Long,
    @param:DrawableRes val iconRes: Int,
    val title: CharSequence,
    val subtitle: CharSequence?,
    val role: Role,
    val destination: Destination
) {
    enum class Role {
        STORAGE,
        SHORTCUT,
        BOOKMARK,
        ACTION
    }

    sealed interface Destination {
        data class FilePath(val path: Path) : Destination
        data class ActivityIntent(val intent: Intent) : Destination
    }
}

sealed interface FileManagerPlusHomeRow {
    val id: Long

    data class Tile(val item: FileManagerPlusHomeItem) : FileManagerPlusHomeRow {
        override val id: Long
            get() = item.id
    }

    data class SectionGap(override val id: Long) : FileManagerPlusHomeRow
}

class FileManagerPlusHomeAdapter(
    context: Context,
    private val listener: Listener
) : SimpleAdapter<FileManagerPlusHomeRow, RecyclerView.ViewHolder>() {
    private val minimumTitleLayoutPaint: TextPaint
    private val subtitleLayoutPaint: TextPaint

    init {
        val sampleBinding = FileManagerPlusHomeItemBinding.inflate(
            context.layoutInflater,
            FrameLayout(context),
            false
        )
        minimumTitleLayoutPaint = TextPaint(sampleBinding.titleText.paint).apply {
            flags = flags or Paint.ANTI_ALIAS_FLAG
            textSize = context.resources.getDimension(
                R.dimen.file_manager_plus_home_title_min_text_size
            )
        }
        subtitleLayoutPaint = TextPaint(sampleBinding.subtitleText.paint).apply {
            flags = flags or Paint.ANTI_ALIAS_FLAG
        }
    }
    private val horizontalInsets = context.resources.getDimension(
        R.dimen.file_manager_plus_home_item_horizontal_insets
    )
    private val displayDensity = context.resources.displayMetrics.density
    private val minimumItemWidths = mapOf(
        FileManagerPlusHomeItem.Role.STORAGE to context.resources.getDimension(
            R.dimen.file_manager_plus_home_storage_min_width
        ),
        FileManagerPlusHomeItem.Role.SHORTCUT to context.resources.getDimension(
            R.dimen.file_manager_plus_home_shortcut_min_width
        ),
        FileManagerPlusHomeItem.Role.BOOKMARK to context.resources.getDimension(
            R.dimen.file_manager_plus_home_bookmark_min_width
        ),
        FileManagerPlusHomeItem.Role.ACTION to context.resources.getDimension(
            R.dimen.file_manager_plus_home_action_min_width
        )
    )
    private val tileMinimumHeights = mapOf(
        FileManagerPlusHomeItem.Role.STORAGE to context.resources.getDimensionPixelSize(
            R.dimen.file_manager_plus_home_storage_min_height
        ),
        FileManagerPlusHomeItem.Role.SHORTCUT to context.resources.getDimensionPixelSize(
            R.dimen.file_manager_plus_home_shortcut_min_height
        ),
        FileManagerPlusHomeItem.Role.BOOKMARK to context.resources.getDimensionPixelSize(
            R.dimen.file_manager_plus_home_secondary_min_height
        ),
        FileManagerPlusHomeItem.Role.ACTION to context.resources.getDimensionPixelSize(
            R.dimen.file_manager_plus_home_secondary_min_height
        )
    )

    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is FileManagerPlusHomeRow.Tile -> ViewType.TILE.ordinal
            is FileManagerPlusHomeRow.SectionGap -> ViewType.SECTION_GAP.ordinal
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (ViewType.entries[viewType]) {
            ViewType.TILE -> TileViewHolder(
                FileManagerPlusHomeItemBinding.inflate(
                    parent.context.layoutInflater,
                    parent,
                    false
                )
            )
            ViewType.SECTION_GAP -> SectionGapViewHolder(
                parent.context.layoutInflater.inflate(
                    R.layout.file_manager_plus_home_section_gap,
                    parent,
                    false
                )
            )
        }

    fun getSpanSize(position: Int, availableWidthPx: Int, spanCount: Int): Int {
        if (position !in 0..<itemCount) {
            return spanCount
        }
        val row = getItem(position)
        if (row is FileManagerPlusHomeRow.SectionGap) {
            return spanCount
        }
        val role = (row as FileManagerPlusHomeRow.Tile).item.role
        return calculateFileManagerPlusHomeSectionSpanSize(
            requiredItemWidthPx = getRequiredItemWidth(role),
            availableWidthPx = availableWidthPx,
            maximumColumnCount = getMaximumColumnCount(role, availableWidthPx),
            spanCount = spanCount
        )
    }

    private fun getRequiredItemWidth(role: FileManagerPlusHomeItem.Role): Float {
        var requiredWidth = minimumItemWidths.getValue(role)
        list.asSequence()
            .mapNotNull { (it as? FileManagerPlusHomeRow.Tile)?.item }
            .filter { it.role == role }
            .forEach { item ->
                val title = normalizeFileManagerPlusHomeTitle(item.title)
                requiredWidth = max(
                    requiredWidth,
                    Layout.getDesiredWidth(title, minimumTitleLayoutPaint) + horizontalInsets
                )
                if (role == FileManagerPlusHomeItem.Role.STORAGE && item.subtitle != null) {
                    val subtitle = normalizeFileManagerPlusHomeTitle(item.subtitle)
                    requiredWidth = max(
                        requiredWidth,
                        Layout.getDesiredWidth(subtitle, subtitleLayoutPaint) + horizontalInsets
                    )
                }
            }
        return requiredWidth
    }

    private fun getMaximumColumnCount(
        role: FileManagerPlusHomeItem.Role,
        availableWidthPx: Int
    ): Int {
        val availableWidthDp = availableWidthPx / displayDensity
        return when (role) {
            FileManagerPlusHomeItem.Role.STORAGE -> when {
                availableWidthDp >= 720f -> 3
                availableWidthDp >= 320f -> 2
                else -> 1
            }
            FileManagerPlusHomeItem.Role.SHORTCUT -> when {
                availableWidthDp >= 600f -> 4
                availableWidthDp >= 320f -> 3
                else -> 2
            }
            FileManagerPlusHomeItem.Role.BOOKMARK -> when {
                availableWidthDp >= 600f -> 3
                availableWidthDp >= 360f -> 2
                else -> 1
            }
            FileManagerPlusHomeItem.Role.ACTION -> when {
                availableWidthDp >= 600f -> 3
                availableWidthDp >= 320f -> 3
                else -> 2
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (row !is FileManagerPlusHomeRow.Tile) {
            return
        }
        val tileHolder = holder as TileViewHolder
        val item = row.item
        val title = normalizeFileManagerPlusHomeTitle(item.title)
        tileHolder.binding.apply {
            root.minimumHeight = tileMinimumHeights.getValue(item.role)
            iconImage.setImageResource(item.iconRes)
            titleText.ellipsize = null
            titleText.text = title
            subtitleText.ellipsize = null
            subtitleText.text = item.subtitle
            root.contentDescription = listOfNotNull(title, item.subtitle)
                .joinToString(separator = ". ")
            root.setOnClickListener { listener.openHomeItem(item) }
        }
    }

    class TileViewHolder(
        val binding: FileManagerPlusHomeItemBinding
    ) : RecyclerView.ViewHolder(binding.root)

    class SectionGapViewHolder(view: View) : RecyclerView.ViewHolder(view)

    fun interface Listener {
        fun openHomeItem(item: FileManagerPlusHomeItem)
    }

    private enum class ViewType {
        TILE,
        SECTION_GAP
    }
}
