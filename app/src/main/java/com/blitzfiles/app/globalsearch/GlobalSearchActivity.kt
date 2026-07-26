/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.globalsearch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blitzfiles.app.R
import com.blitzfiles.app.app.AppActivity
import com.blitzfiles.app.app.clipboardManager
import com.blitzfiles.app.databinding.GlobalSearchActivityBinding
import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.file.asMimeTypeOrNull
import com.blitzfiles.app.file.fileProviderUri
import com.blitzfiles.app.file.formatShort
import com.blitzfiles.app.file.guessFromPath
import com.blitzfiles.app.filejob.FileDeletionRecovery
import com.blitzfiles.app.filelist.FileListActivity
import com.blitzfiles.app.filelist.OpenFileActivity
import com.blitzfiles.app.indexing.FileIndexingController
import com.blitzfiles.app.indexingsettings.IndexingSettingsActivity
import com.blitzfiles.app.search.toEffectiveSearchQuery
import com.blitzfiles.app.util.copyText
import com.blitzfiles.app.util.createIntent
import com.blitzfiles.app.util.createSendStreamIntent
import com.blitzfiles.app.util.showToast
import com.blitzfiles.app.util.startActivitySafe
import com.blitzfiles.app.util.withChooser
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.blitzfiles.search.domain.model.SearchHit
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.search.domain.model.SearchSortOrder
import java.time.Instant
import java8.nio.file.Paths
import kotlinx.coroutines.launch

class GlobalSearchActivity : AppActivity(), GlobalSearchAdapter.Listener {
    private val viewModel by viewModels<GlobalSearchViewModel>()

    private lateinit var binding: GlobalSearchActivityBinding
    private lateinit var adapter: GlobalSearchAdapter
    private var renderedSortOrder: SearchSortOrder? = null
    private var renderedQuery: String? = null
    private var pendingScrollToTop = false
    private var hasRenderedIndexStatus = false
    private var renderedIndexSnapshot: FileIndexingController.Snapshot? = null
    private var renderedIndexStatusError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = GlobalSearchActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        adapter = GlobalSearchAdapter(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) {
                        return
                    }
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    if (layoutManager.findLastVisibleItemPosition() >=
                        adapter.itemCount - PAGINATION_THRESHOLD) {
                        viewModel.loadNextPage()
                    }
                }
            }
        )
        binding.searchEditText.filters = arrayOf(
            *binding.searchEditText.filters,
            NO_NUL_INPUT_FILTER,
            InputFilter.LengthFilter(SearchRequest.MAX_QUERY_LENGTH)
        )
        binding.searchEditText.doAfterTextChanged { editable ->
            viewModel.setQuery(editable?.toString().orEmpty())
        }
        binding.searchEditText.setOnEditorActionListener { _, _, _ ->
            viewModel.setQuery(binding.searchEditText.text?.toString().orEmpty())
            false
        }
        binding.retryButton.setOnClickListener { viewModel.retrySearch() }
        binding.configureIndexButton.setOnClickListener { openIndexSettings() }
        binding.indexStatusLayout.setOnClickListener { openIndexSettings() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messages.collect { message -> showToast(message) }
                }
            }
        }

        if (savedInstanceState == null) {
            val initialQuery = intent.getStringExtra(EXTRA_QUERY).orEmpty()
            if (initialQuery.isNotEmpty()) {
                binding.searchEditText.setText(initialQuery)
                binding.searchEditText.setSelection(binding.searchEditText.text?.length ?: 0)
            } else {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                binding.searchEditText.requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FileDeletionRecovery.retry(this)
        viewModel.startIndexStatusUpdates()
    }

    override fun onPause() {
        viewModel.stopIndexStatusUpdates()
        super.onPause()
    }

    private fun render(state: GlobalSearchUiState) {
        val currentText = binding.searchEditText.text?.toString().orEmpty()
        if (currentText != state.query) {
            binding.searchEditText.setText(state.query)
            binding.searchEditText.setSelection(state.query.length)
        }
        val effectiveQuery = state.query.toEffectiveSearchQuery()
        if (renderedQuery != effectiveQuery) {
            renderedQuery = effectiveQuery
            pendingScrollToTop = true
        }
        adapter.submitResults(state.hits, state.query)
        if (pendingScrollToTop && state.hits.isNotEmpty()) {
            binding.recyclerView.scrollToPosition(0)
            pendingScrollToTop = false
        }
        binding.recyclerView.isVisible = state.hits.isNotEmpty()
        binding.paginationProgress.isVisible = state.isLoadingMore
        binding.initialProgress.isVisible = state.isSearching && state.hits.isEmpty()

        val hasError = state.errorMessage != null && state.hits.isEmpty()
        val showEmptyState = !state.isSearching && !hasError && state.hits.isEmpty()
        binding.stateLayout.isVisible = hasError || showEmptyState
        binding.retryButton.isVisible = hasError
        binding.configureIndexButton.isVisible = showEmptyState &&
            state.indexSnapshot?.statistics?.entryCount == 0L
        binding.stateText.text = when {
            hasError -> getString(R.string.global_search_error_format, state.errorMessage)
            state.query.isBlank() -> getString(R.string.global_search_prompt)
            state.indexSnapshot?.statistics?.entryCount == 0L ->
                getString(R.string.global_search_index_empty)
            else -> getString(R.string.global_search_no_results)
        }
        if (
            !hasRenderedIndexStatus ||
            renderedIndexSnapshot != state.indexSnapshot ||
            renderedIndexStatusError != state.indexStatusError
        ) {
            hasRenderedIndexStatus = true
            renderedIndexSnapshot = state.indexSnapshot
            renderedIndexStatusError = state.indexStatusError
            renderIndexStatus(state.indexSnapshot, state.indexStatusError)
        }
        if (renderedSortOrder != state.sortOrder) {
            renderedSortOrder = state.sortOrder
            invalidateOptionsMenu()
        }
    }

    private fun renderIndexStatus(
        snapshot: FileIndexingController.Snapshot?,
        hasError: Boolean
    ) {
        val activeRoot = snapshot?.roots.orEmpty().firstOrNull { root ->
            root.lastScanStatus == IndexScanStatus.RUNNING ||
                root.lastScanStatus == IndexScanStatus.PAUSED
        }
        binding.indexStatusProgress.isVisible =
            activeRoot?.lastScanStatus == IndexScanStatus.RUNNING
        val lastIndexedAtMillis = snapshot?.statistics?.lastIndexedAtMillis
        binding.indexStatusText.text = when {
            snapshot == null && hasError -> getString(R.string.global_search_index_status_error)
            snapshot == null -> getString(R.string.loading)
            snapshot.roots.isEmpty() -> getString(R.string.global_search_index_not_configured)
            activeRoot?.lastScanStatus == IndexScanStatus.RUNNING ->
                getString(
                    R.string.global_search_index_running_format,
                    activeRoot.displayName,
                    snapshot.statistics.entryCount
                )
            activeRoot?.lastScanStatus == IndexScanStatus.PAUSED ->
                getString(
                    R.string.global_search_index_paused_format,
                    snapshot.statistics.entryCount
                )
            lastIndexedAtMillis != null ->
                getString(
                    R.string.global_search_index_ready_format,
                    snapshot.statistics.entryCount,
                    Instant.ofEpochMilli(lastIndexedAtMillis).formatShort(this)
                )
            else -> getString(
                R.string.global_search_index_entries_format,
                snapshot.statistics.entryCount
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.global_search, menu)
        updateSortMenu(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateSortMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateSortMenu(menu: Menu) {
        val itemId = when (viewModel.state.value.sortOrder) {
            SearchSortOrder.RELEVANCE -> R.id.action_sort_relevance
            SearchSortOrder.NAME_ASCENDING -> R.id.action_sort_name_ascending
            SearchSortOrder.NAME_DESCENDING -> R.id.action_sort_name_descending
            SearchSortOrder.SIZE_ASCENDING -> R.id.action_sort_size_ascending
            SearchSortOrder.SIZE_DESCENDING -> R.id.action_sort_size_descending
            SearchSortOrder.MODIFIED_ASCENDING -> R.id.action_sort_modified_ascending
            SearchSortOrder.MODIFIED_DESCENDING -> R.id.action_sort_modified_descending
        }
        menu.findItem(itemId)?.isChecked = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val sortOrder = when (item.itemId) {
            R.id.action_sort_relevance -> SearchSortOrder.RELEVANCE
            R.id.action_sort_name_ascending -> SearchSortOrder.NAME_ASCENDING
            R.id.action_sort_name_descending -> SearchSortOrder.NAME_DESCENDING
            R.id.action_sort_size_ascending -> SearchSortOrder.SIZE_ASCENDING
            R.id.action_sort_size_descending -> SearchSortOrder.SIZE_DESCENDING
            R.id.action_sort_modified_ascending -> SearchSortOrder.MODIFIED_ASCENDING
            R.id.action_sort_modified_descending -> SearchSortOrder.MODIFIED_DESCENDING
            else -> null
        }
        if (sortOrder != null) {
            viewModel.setSortOrder(sortOrder)
            return true
        }
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_index_settings -> {
                openIndexSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun open(hit: SearchHit) {
        runPathAction {
            val entry = hit.entry
            val path = Paths.get(entry.path)
            val intent = if (entry.isDirectory) {
                FileListActivity.createViewIntent(path)
            } else {
                OpenFileActivity.createIntent(path, hit.mimeType())
            }
            startActivitySafe(intent)
        }
    }

    override fun showInFolder(hit: SearchHit) {
        runPathAction {
            val parentPath = hit.entry.parentPath
            if (parentPath.isNotEmpty()) {
                startActivitySafe(FileListActivity.createViewIntent(Paths.get(parentPath)))
            }
        }
    }

    override fun share(hit: SearchHit) {
        runPathAction {
            val path = Paths.get(hit.entry.path)
            startActivitySafe(path.fileProviderUri.createSendStreamIntent(hit.mimeType()).withChooser())
        }
    }

    override fun copyPath(hit: SearchHit) {
        clipboardManager.copyText(hit.entry.path, this)
    }

    private fun SearchHit.mimeType(): MimeType =
        entry.mimeType?.asMimeTypeOrNull() ?: MimeType.guessFromPath(entry.path)

    private inline fun runPathAction(action: () -> Unit) {
        runCatching(action).onFailure { error ->
            showToast(
                getString(
                    R.string.global_search_action_error_format,
                    error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    private fun openIndexSettings() {
        startActivitySafe(IndexingSettingsActivity::class.createIntent())
    }

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val PAGINATION_THRESHOLD = 20
        private val NO_NUL_INPUT_FILTER = InputFilter { source, start, end, _, _, _ ->
            val insertedText = source.subSequence(start, end)
            if (insertedText.none { it == '\u0000' }) {
                null
            } else {
                insertedText.filterNot { it == '\u0000' }
            }
        }

        fun createIntent(context: Context, query: String? = null): Intent =
            Intent(context, GlobalSearchActivity::class.java).apply {
                query?.let { putExtra(EXTRA_QUERY, it) }
            }
    }
}
