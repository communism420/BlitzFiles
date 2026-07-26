/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.blitzfiles.app.R
import com.blitzfiles.app.databinding.GlobalSearchResultItemBinding
import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.file.asFileSize
import com.blitzfiles.app.file.asMimeTypeOrNull
import com.blitzfiles.app.file.formatShort
import com.blitzfiles.app.file.guessFromPath
import com.blitzfiles.app.file.iconRes
import com.blitzfiles.app.search.highlightSearchMatches
import com.blitzfiles.app.search.toEffectiveSearchQuery
import com.blitzfiles.app.util.layoutInflater
import com.blitzfiles.search.domain.model.IndexedFileRecord
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchQueryMode
import java.time.Instant

class GlobalSearchAdapter(
    private val listener: Listener
) : RecyclerView.Adapter<GlobalSearchAdapter.ViewHolder>() {
    private var items: List<SearchHit> = emptyList()
    private var searchHighlightQuery = ""
    private val presentationCache = object : LinkedHashMap<Long, CachedPresentation>(
        PRESENTATION_CACHE_SIZE,
        CACHE_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, CachedPresentation>
        ): Boolean = size > PRESENTATION_CACHE_SIZE
    }

    init {
        setHasStableIds(true)
    }

    /**
     * Replaces an interactive first page synchronously or appends pagination without DiffUtil.
     *
     * Search pages normally have little overlap and item animations are disabled, so scheduling an
     * asynchronous diff only delays the first visible row by another frame.
    */
    @SuppressLint("NotifyDataSetChanged")
    fun submitResults(newItems: List<SearchHit>, searchHighlightQuery: String) {
        val effectiveHighlightQuery = searchHighlightQuery.toEffectiveSearchQuery()
        val highlightChanged = this.searchHighlightQuery != effectiveHighlightQuery
        this.searchHighlightQuery = effectiveHighlightQuery
        if (items === newItems || items == newItems) {
            if (highlightChanged && itemCount > 0) {
                notifyItemRangeChanged(0, itemCount, PAYLOAD_SEARCH_HIGHLIGHT_CHANGED)
            }
            return
        }
        val oldItems = items
        val appendedCount = newItems.size - oldItems.size
        val isPureAppend = appendedCount > 0 && oldItems.indices.all { index ->
            oldItems[index] == newItems[index]
        }
        items = newItems
        if (isPureAppend) {
            if (highlightChanged && oldItems.isNotEmpty()) {
                notifyItemRangeChanged(
                    0,
                    oldItems.size,
                    PAYLOAD_SEARCH_HIGHLIGHT_CHANGED
                )
            }
            notifyItemRangeInserted(oldItems.size, appendedCount)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long =
        checkNotNull(items[position].entry.id) { "Indexed search result has no database ID" }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            GlobalSearchResultItemBinding.inflate(parent.context.layoutInflater, parent, false),
            listener
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val hit = items[position]
        val entry = hit.entry
        val context = holder.binding.root.context
        val id = checkNotNull(entry.id)
        val cachedPresentation = presentationCache[id]
        val presentation = if (cachedPresentation?.entry == entry) {
            cachedPresentation
        } else {
            val mimeType = if (entry.isDirectory) {
                MimeType.DIRECTORY
            } else {
                entry.mimeType?.asMimeTypeOrNull() ?: MimeType.guessFromPath(entry.path)
            }
            val modified = Instant.ofEpochMilli(entry.modifiedAtMillis).formatShort(context)
            CachedPresentation(
                entry = entry,
                iconResource = mimeType.iconRes,
                metadata = if (entry.isDirectory) {
                    context.getString(R.string.global_search_directory_metadata_format, modified)
                } else {
                    context.getString(
                        R.string.global_search_file_metadata_format,
                        entry.sizeBytes.asFileSize().formatHumanReadable(context),
                        modified
                    )
                }
            ).also { presentationCache[id] = it }
        }
        holder.bind(hit, presentation.iconResource, presentation.metadata, searchHighlightQuery)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (
            payloads.isNotEmpty() &&
            payloads.all { payload -> payload === PAYLOAD_SEARCH_HIGHLIGHT_CHANGED }
        ) {
            holder.bindName(items[position].entry.name, searchHighlightQuery)
            return
        }
        onBindViewHolder(holder, position)
    }

    class ViewHolder(
        val binding: GlobalSearchResultItemBinding,
        private val listener: Listener
    ) : RecyclerView.ViewHolder(binding.root) {
        private var boundHit: SearchHit? = null

        init {
            binding.root.setOnClickListener {
                boundHit?.let(listener::open)
            }
            binding.root.setOnLongClickListener {
                boundHit?.let(listener::showInFolder)
                boundHit != null
            }
            binding.menuButton.setOnClickListener {
                boundHit?.let(::showMenu)
            }
        }

        fun bind(
            hit: SearchHit,
            iconResource: Int,
            metadata: String,
            searchHighlightQuery: String
        ) {
            boundHit = hit
            val entry = hit.entry
            binding.iconImage.setImageResource(iconResource)
            bindName(entry.name, searchHighlightQuery)
            binding.pathText.text = entry.parentPath
            binding.rootBadgeText.isVisible = entry.requiresRoot
            binding.metadataText.text = metadata
        }

        fun bindName(name: String, query: String) {
            binding.nameText.text = binding.nameText.context.highlightSearchMatches(
                text = name,
                query = query,
                mode = SearchQueryMode.PATTERN
            )
        }

        private fun showMenu(hit: SearchHit) {
            val entry = hit.entry
            // Menu inflation is relatively expensive and most rows never open it, so create it
            // lazily instead of doing the work on every RecyclerView bind.
            PopupMenu(binding.menuButton.context, binding.menuButton).apply {
                inflate(R.menu.global_search_result)
                menu.findItem(R.id.action_share).isVisible = !entry.isDirectory
                menu.findItem(R.id.action_show_in_folder).isVisible =
                    entry.parentPath.isNotEmpty()
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_open -> listener.open(hit)
                        R.id.action_show_in_folder -> listener.showInFolder(hit)
                        R.id.action_share -> listener.share(hit)
                        R.id.action_copy_path -> listener.copyPath(hit)
                        else -> return@setOnMenuItemClickListener false
                    }
                    true
                }
                show()
            }
        }
    }

    interface Listener {
        fun open(hit: SearchHit)
        fun showInFolder(hit: SearchHit)
        fun share(hit: SearchHit)
        fun copyPath(hit: SearchHit)
    }

    companion object {
        private const val PRESENTATION_CACHE_SIZE = 256
        private const val CACHE_LOAD_FACTOR = 0.75f
        private val PAYLOAD_SEARCH_HIGHLIGHT_CHANGED = Any()
    }
}

private data class CachedPresentation(
    val entry: IndexedFileRecord,
    val iconResource: Int,
    val metadata: String
)
