/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputFilter
import android.text.TextUtils
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.leinardi.android.speeddial.SpeedDialView
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.parcelize.Parcelize
import com.blitzfiles.app.R
import com.blitzfiles.app.app.application
import com.blitzfiles.app.app.clipboardManager
import com.blitzfiles.app.compat.EnvironmentCompat2
import com.blitzfiles.app.compat.checkSelfPermissionCompat
import com.blitzfiles.app.compat.getDescriptionCompat
import com.blitzfiles.app.compat.isPrimaryCompat
import com.blitzfiles.app.compat.pathCompat
import com.blitzfiles.app.compat.setGroupDividerEnabledCompat
import com.blitzfiles.app.databinding.FileListFragmentAppBarIncludeBinding
import com.blitzfiles.app.databinding.FileListFragmentBinding
import com.blitzfiles.app.databinding.FileListFragmentBottomBarIncludeBinding
import com.blitzfiles.app.databinding.FileListFragmentContentIncludeBinding
import com.blitzfiles.app.databinding.FileListFragmentIncludeBinding
import com.blitzfiles.app.databinding.FileListFragmentSpeedDialIncludeBinding
import com.blitzfiles.app.file.FileItem
import com.blitzfiles.app.file.JavaFile
import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.file.asFileSize
import com.blitzfiles.app.file.asMimeTypeOrNull
import com.blitzfiles.app.file.extension
import com.blitzfiles.app.file.fileProviderUri
import com.blitzfiles.app.file.isApk
import com.blitzfiles.app.file.isImage
import com.blitzfiles.app.filejob.FileJobService
import com.blitzfiles.app.filelist.FileSortOptions.By
import com.blitzfiles.app.filelist.FileSortOptions.Order
import com.blitzfiles.app.fileproperties.FilePropertiesDialogFragment
import com.blitzfiles.app.ftpserver.FtpServerActivity
import com.blitzfiles.app.globalsearch.GlobalSearchActivity
import com.blitzfiles.app.indexing.FileIndexingProgress
import com.blitzfiles.app.indexing.FileIndexingProgressStore
import com.blitzfiles.app.indexing.FileIndexingStorageAccess
import com.blitzfiles.app.indexing.InitialIndexingCoordinator
import com.blitzfiles.app.indexing.localizeIndexingDiagnosticMessage
import com.blitzfiles.app.indexingsettings.IndexingSettingsActivity
import com.blitzfiles.app.navigation.BookmarkDirectories
import com.blitzfiles.app.navigation.BookmarkDirectory
import com.blitzfiles.app.navigation.NavigationFragment
import com.blitzfiles.app.navigation.NavigationRootMapLiveData
import com.blitzfiles.app.navigation.getExternalStorageDirectory
import com.blitzfiles.app.navigation.standardDirectories
import com.blitzfiles.app.provider.archive.createArchiveRootPath
import com.blitzfiles.app.provider.archive.isArchivePath
import com.blitzfiles.app.provider.linux.isLinuxPath
import com.blitzfiles.app.search.toEffectiveSearchQuery
import com.blitzfiles.app.settings.InterfaceStyle
import com.blitzfiles.app.settings.Settings
import com.blitzfiles.app.settings.SettingsActivity
import com.blitzfiles.app.storage.AddStorageDialogActivity
import com.blitzfiles.app.storage.FileSystemRoot
import com.blitzfiles.app.storage.Storage
import com.blitzfiles.app.storage.StorageVolumeListLiveData
import com.blitzfiles.app.terminal.Terminal
import com.blitzfiles.app.ui.AppBarLayoutExpandHackListener
import com.blitzfiles.app.ui.CoordinatorAppBarLayout
import com.blitzfiles.app.ui.DrawerLayoutOnBackPressedCallback
import com.blitzfiles.app.ui.FixQueryChangeSearchView
import com.blitzfiles.app.ui.OverlayToolbarActionMode
import com.blitzfiles.app.ui.PersistentBarLayout
import com.blitzfiles.app.ui.PersistentBarLayoutToolbarActionMode
import com.blitzfiles.app.ui.PersistentDrawerLayout
import com.blitzfiles.app.ui.ScrollingViewOnApplyWindowInsetsListener
import com.blitzfiles.app.ui.SpeedDialViewOnBackPressedCallback
import com.blitzfiles.app.ui.ThemedFastScroller
import com.blitzfiles.app.ui.ToolbarActionMode
import com.blitzfiles.app.util.Failure
import com.blitzfiles.app.util.Loading
import com.blitzfiles.app.util.ParcelableArgs
import com.blitzfiles.app.util.Stateful
import com.blitzfiles.app.util.Success
import com.blitzfiles.app.util.addOnBackPressedCallback
import com.blitzfiles.app.util.args
import com.blitzfiles.app.util.asFileName
import com.blitzfiles.app.util.asFileNameOrNull
import com.blitzfiles.app.util.checkSelfPermission
import com.blitzfiles.app.util.copyText
import com.blitzfiles.app.util.create
import com.blitzfiles.app.util.createInstallPackageIntent
import com.blitzfiles.app.util.createIntent
import com.blitzfiles.app.util.createManageAppAllFilesAccessPermissionIntent
import com.blitzfiles.app.util.createSendStreamIntent
import com.blitzfiles.app.util.createViewIntent
import com.blitzfiles.app.util.extraPath
import com.blitzfiles.app.util.extraPathList
import com.blitzfiles.app.util.fadeToVisibilityUnsafe
import com.blitzfiles.app.util.getDimensionDp
import com.blitzfiles.app.util.getQuantityString
import com.blitzfiles.app.util.hasSw600Dp
import com.blitzfiles.app.util.isOrientationLandscape
import com.blitzfiles.app.util.isMounted
import com.blitzfiles.app.util.putArgs
import com.blitzfiles.app.util.setOnEditorConfirmActionListener
import com.blitzfiles.app.util.showToast
import com.blitzfiles.app.util.startActivitySafe
import com.blitzfiles.app.util.supportsExternalStorageManager
import com.blitzfiles.app.util.takeIfNotEmpty
import com.blitzfiles.search.domain.model.SearchRequest
import com.blitzfiles.app.util.valueCompat
import com.blitzfiles.app.util.viewModels
import com.blitzfiles.app.util.withChooser
import com.blitzfiles.app.viewer.image.ImageViewerActivity
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FileListFragment : Fragment(), BreadcrumbLayout.Listener, FileListAdapter.Listener,
    ConfirmReplaceFileDialogFragment.Listener, OpenApkDialogFragment.Listener,
    ConfirmDeleteFilesDialogFragment.Listener, CreateArchiveDialogFragment.Listener,
    RenameFileDialogFragment.Listener, CreateFileDialogFragment.Listener,
    CreateDirectoryDialogFragment.Listener, NavigateToPathDialogFragment.Listener,
    NavigationFragment.Listener, ShowRequestAllFilesAccessRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionInSettingsRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionInSettingsRationaleDialogFragment.Listener {
    private val requestAllFilesAccessLauncher = registerForActivityResult(
        RequestAllFilesAccessContract(), this::onRequestAllFilesAccessResult
    )
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(), this::onRequestStoragePermissionResult
    )
    private val requestStoragePermissionInSettingsLauncher = registerForActivityResult(
        RequestPermissionInSettingsContract(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
        this::onRequestStoragePermissionInSettingsResult
    )
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(), this::onRequestNotificationPermissionResult
    )
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestNotificationPermissionInSettingsLauncher = registerForActivityResult(
        RequestPermissionInSettingsContract(android.Manifest.permission.POST_NOTIFICATIONS),
        this::onRequestNotificationPermissionInSettingsResult
    )

    private val args by args<Args>()
    private val argsPath by lazy { args.intent.extraPath }

    private val viewModel by viewModels { { FileListViewModel() } }

    private lateinit var binding: Binding

    private lateinit var navigationFragment: NavigationFragment

    private lateinit var menuBinding: MenuBinding

    private lateinit var overlayActionMode: ToolbarActionMode

    private lateinit var bottomActionMode: ToolbarActionMode

    private lateinit var layoutManager: GridLayoutManager

    private lateinit var adapter: FileListAdapter

    private lateinit var fileManagerPlusHomeAdapter: FileManagerPlusHomeAdapter

    private lateinit var navigationBackCallback: OnBackPressedCallback

    private var appliedInterfaceStyle: InterfaceStyle? = null

    private var renderedFileManagerPlusHome: Boolean? = null

    private var fileListItemAnimator: RecyclerView.ItemAnimator? = null

    private var isFileListItemAnimatorSuppressedForSearch = false

    private val isFileManagerPlusHomeEligible: Boolean by lazy {
        val intent = args.intent
        intent.action == Intent.ACTION_MAIN &&
            intent.categories.orEmpty().any {
                it == Intent.CATEGORY_LAUNCHER || it == Intent.CATEGORY_LEANBACK_LAUNCHER
            }
    }

    private val isFileManagerPlusHomeDisplayed: Boolean
        get() =
            appliedInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS &&
                isFileManagerPlusHomeEligible &&
                viewModel.isFileManagerPlusHomeVisible &&
                viewModel.pickOptions == null &&
                !viewModel.isSearchViewExpanded

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        Binding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (savedInstanceState == null) {
            navigationFragment = NavigationFragment()
            childFragmentManager.commit { add(R.id.navigationFragment, navigationFragment) }
        } else {
            navigationFragment = childFragmentManager.findFragmentById(R.id.navigationFragment)
                as NavigationFragment
        }
        navigationFragment.listener = this
        val activity = requireActivity() as AppCompatActivity
        activity.setTitle(R.string.file_list_title)
        activity.setSupportActionBar(binding.toolbar)
        overlayActionMode = OverlayToolbarActionMode(binding.overlayToolbar)
        bottomActionMode = PersistentBarLayoutToolbarActionMode(
            binding.persistentBarLayout, binding.bottomBarLayout, binding.bottomToolbar
        )
        val contentLayoutInitialPaddingBottom = binding.contentLayout.paddingBottom
        binding.appBarLayout.addOnOffsetChangedListener { _, verticalOffset ->
            binding.contentLayout.updatePaddingRelative(
                bottom = contentLayoutInitialPaddingBottom +
                    binding.appBarLayout.totalScrollRange + verticalOffset
            )
        }
        binding.appBarLayout.syncBackgroundColorTo(binding.overlayToolbar)
        binding.breadcrumbLayout.setListener(this)
        binding.indexingProgressBanner.setOnClickListener {
            startActivitySafe(IndexingSettingsActivity::class.createIntent())
        }
        observeIndexingProgress()
        if (!(activity.hasSw600Dp && activity.isOrientationLandscape)) {
            binding.swipeRefreshLayout.setProgressViewEndTarget(
                true, binding.swipeRefreshLayout.progressViewEndOffset
            )
        }
        binding.swipeRefreshLayout.setOnRefreshListener { this.refresh() }
        layoutManager = GridLayoutManager(activity, 1)
        binding.recyclerView.layoutManager = layoutManager
        adapter = FileListAdapter(this)
        binding.recyclerView.adapter = adapter
        val initialInterfaceStyle = Settings.INTERFACE_STYLE.valueCompat
        appliedInterfaceStyle = initialInterfaceStyle
        adapter.interfaceStyle = initialInterfaceStyle
        fileManagerPlusHomeAdapter =
            FileManagerPlusHomeAdapter(requireContext(), ::openFileManagerPlusHomeItem)
        binding.fileManagerPlusHomeRecyclerView.layoutManager = GridLayoutManager(
            activity,
            FILE_MANAGER_PLUS_HOME_GRID_SPAN_COUNT
        ).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    fileManagerPlusHomeAdapter.getSpanSize(
                        position,
                        getFileManagerPlusHomeAvailableWidthPx(),
                        FILE_MANAGER_PLUS_HOME_GRID_SPAN_COUNT
                    )
            }
        }
        binding.fileManagerPlusHomeRecyclerView.adapter = fileManagerPlusHomeAdapter
        binding.fileManagerPlusHomeRecyclerView.addOnLayoutChangeListener {
            _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                updateFileManagerPlusHomeGrid()
            }
        }
        binding.fileManagerPlusHomeRecyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.fileManagerPlusHomeRecyclerView)
        )
        fileListItemAnimator = binding.recyclerView.itemAnimator
        isFileListItemAnimatorSuppressedForSearch = false
        val fastScroller = ThemedFastScroller.create(binding.recyclerView)
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView, fastScroller)
        )
        binding.speedDialView.inflate(R.menu.file_list_speed_dial)
        binding.speedDialView.setOnActionSelectedListener {
            when (it.id) {
                R.id.action_create_file -> showCreateFileDialog()
                R.id.action_create_directory -> showCreateDirectoryDialog()
            }
            // Returning false causes the speed dial to close without animation.
            //return false
            binding.speedDialView.close()
            true
        }

        val viewLifecycleOwner = viewLifecycleOwner
        navigationBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (shouldReturnToFileManagerPlusHome()) {
                    navigateHome()
                } else {
                    viewModel.navigateUp()
                }
            }
        }
        addOnBackPressedCallback(navigationBackCallback)
        addOnBackPressedCallback(overlayActionMode.onBackPressedCallback)
        addOnBackPressedCallback(SpeedDialViewOnBackPressedCallback(binding.speedDialView))
        binding.drawerLayout?.let {
            addOnBackPressedCallback(DrawerLayoutOnBackPressedCallback(it))
        }

        if (!viewModel.hasTrail) {
            var path = argsPath
            val intent = args.intent
            var pickOptions: PickOptions? = null
            when (val action = intent.action) {
                Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT,
                Intent.ACTION_CREATE_DOCUMENT -> {
                    val mode = if (action == Intent.ACTION_CREATE_DOCUMENT) {
                        PickOptions.Mode.CREATE_FILE
                    } else {
                        PickOptions.Mode.OPEN_FILE
                    }
                    val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
                    val fileName = if (mode == PickOptions.Mode.CREATE_FILE) {
                        intent.getStringExtra(Intent.EXTRA_TITLE)?.asFileNameOrNull()?.value
                            ?: mimeType.extension?.let { "file.$it" } ?: "file"
                    } else {
                        null
                    }
                    val readOnly = action == Intent.ACTION_GET_CONTENT
                    val extraMimeTypes = if (mode == PickOptions.Mode.OPEN_FILE) {
                        intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                            ?.mapNotNull { it.asMimeTypeOrNull() }?.takeIfNotEmpty()
                    } else {
                        null
                    }
                    val mimeTypes = extraMimeTypes ?: listOf(mimeType)
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    val allowMultiple = mode != PickOptions.Mode.CREATE_FILE &&
                        intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    pickOptions =
                        PickOptions(mode, fileName, readOnly, mimeTypes, localOnly, allowMultiple)
                }
                Intent.ACTION_OPEN_DOCUMENT_TREE -> {
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    pickOptions = PickOptions(
                        PickOptions.Mode.OPEN_DIRECTORY, null, false, emptyList(), localOnly, false
                    )
                }
                ACTION_VIEW_DOWNLOADS ->
                    path = Paths.get(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ).path
                    )
                else ->
                    if (path != null) {
                        val mimeType = intent.type?.asMimeTypeOrNull()
                        if (mimeType != null && path.isArchiveFile(mimeType)) {
                            path = path.createArchiveRootPath()
                        }
                    }
            }
            if (path == null) {
                path = Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
            }
            viewModel.resetTo(path)
            viewModel.fileManagerPlusNavigationRoot = null
            viewModel.isFileManagerPlusHomeVisible =
                initialInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS &&
                    isFileManagerPlusHomeEligible &&
                    pickOptions == null
            if (pickOptions != null) {
                viewModel.pickOptions = pickOptions
            }
        }
        viewModel.currentPathLiveData.observe(viewLifecycleOwner) { onCurrentPathChanged(it) }
        viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) {
            binding.breadcrumbLayout.setData(it)
            updateNavigationBackCallback()
        }
        viewModel.viewTypeLiveData.observe(viewLifecycleOwner) { onViewTypeChanged(it) }
        // Live data only calls observeForever() on its sources when it is active, so we have to
        // make view type live data active first (so that it can load its initial value) before we
        // register another observer that needs to get the view type.
        if (binding.persistentDrawerLayout != null) {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.observe(viewLifecycleOwner) {
                onPersistentDrawerOpenChanged(it)
            }
        }
        viewModel.sortOptionsLiveData.observe(viewLifecycleOwner) { onSortOptionsChanged(it) }
        viewModel.viewSortPathSpecificLiveData.observe(viewLifecycleOwner) {
            onViewSortPathSpecificChanged(it)
        }
        Settings.FILE_NAME_ELLIPSIZE.observe(viewLifecycleOwner) {
            onFileNameEllipsizeChanged(it)
        }
        viewModel.pickOptionsLiveData.observe(viewLifecycleOwner) { onPickOptionsChanged(it) }
        viewModel.selectedFilesLiveData.observe(viewLifecycleOwner) { onSelectedFilesChanged(it) }
        viewModel.pasteStateLiveData.observe(viewLifecycleOwner) { onPasteStateChanged(it) }
        Settings.INTERFACE_STYLE.observe(viewLifecycleOwner) { onInterfaceStyleChanged(it) }
        viewModel.searchViewExpandedLiveData.observe(viewLifecycleOwner) {
            onSearchViewExpandedChanged(it)
        }
        viewModel.fileManagerPlusHomeVisibleLiveData.observe(viewLifecycleOwner) {
            renderInterfaceState()
        }
        viewModel.fileListLiveData.observe(viewLifecycleOwner) { onFileListChanged(it) }
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.observe(viewLifecycleOwner) {
            onShowHiddenFilesChanged(it)
        }
        Settings.STORAGES.observe(viewLifecycleOwner) { updateFileManagerPlusHomeItems() }
        StorageVolumeListLiveData.observe(viewLifecycleOwner) {
            updateFileManagerPlusHomeItems()
        }
        Settings.STANDARD_DIRECTORY_SETTINGS.observe(viewLifecycleOwner) {
            updateFileManagerPlusHomeItems()
        }
        Settings.BOOKMARK_DIRECTORIES.observe(viewLifecycleOwner) {
            updateFileManagerPlusHomeItems()
        }
    }

    private fun observeIndexingProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                FileIndexingProgressStore.state.collectLatest { progress ->
                    renderIndexingProgress(progress)
                }
            }
        }
    }

    private suspend fun renderIndexingProgress(progress: FileIndexingProgress) {
        if (progress is FileIndexingProgress.Idle) {
            binding.indexingProgressBanner.isVisible = false
            return
        }

        val (title, detail, showIndicator) = when (progress) {
            FileIndexingProgress.Idle -> error("Idle progress was handled above")
            FileIndexingProgress.Scheduled -> Triple(
                getString(R.string.file_indexing_banner_scheduled),
                getString(R.string.file_indexing_banner_details_hint),
                true
            )
            FileIndexingProgress.Preparing -> Triple(
                getString(R.string.file_indexing_banner_preparing),
                getString(R.string.file_indexing_banner_details_hint),
                true
            )
            is FileIndexingProgress.Running -> Triple(
                getString(
                    R.string.file_indexing_banner_running_format,
                    progress.indexedEntryCount
                ),
                progress.currentPath
                    ?: getString(R.string.file_indexing_banner_details_hint),
                true
            )
            is FileIndexingProgress.Paused -> Triple(
                getString(
                    R.string.file_indexing_banner_paused_format,
                    progress.indexedEntryCount
                ),
                getString(R.string.file_indexing_banner_details_hint),
                false
            )
            is FileIndexingProgress.Completed -> Triple(
                if (progress.result.recoverableErrorCount == 0L) {
                    getString(
                        R.string.file_indexing_banner_completed_format,
                        progress.result.indexedEntryCount
                    )
                } else {
                    getString(
                        R.string.file_indexing_banner_completed_with_errors_format,
                        progress.result.indexedEntryCount,
                        progress.result.recoverableErrorCount
                    )
                },
                getString(R.string.file_indexing_banner_details_hint),
                false
            )
            is FileIndexingProgress.Cancelled -> Triple(
                getString(
                    R.string.file_indexing_banner_cancelled_format,
                    progress.indexedEntryCount
                ),
                getString(R.string.file_indexing_banner_details_hint),
                false
            )
            is FileIndexingProgress.Failed -> Triple(
                getString(R.string.file_indexing_banner_failed),
                requireContext().localizeIndexingDiagnosticMessage(progress.message),
                false
            )
        }
        binding.indexingProgressText.text = title
        binding.indexingProgressPath.text = detail
        binding.indexingProgressPath.isVisible = detail.isNotBlank()
        binding.indexingProgressIndicator.isVisible = showIndicator
        binding.indexingProgressBanner.contentDescription =
            listOf(title, detail).filter(String::isNotBlank).joinToString(separator = ". ")
        binding.indexingProgressBanner.isVisible = true

        val isTerminal =
            progress is FileIndexingProgress.Completed ||
                progress is FileIndexingProgress.Cancelled ||
                progress is FileIndexingProgress.Failed
        if (isTerminal) {
            delay(INDEXING_TERMINAL_BANNER_MILLIS)
            if (FileIndexingProgressStore.state.value == progress) {
                FileIndexingProgressStore.clearTerminal(progress)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            (activity as? FileListActivity)
                ?.shouldDeferInitialPermissionOrchestration() != true
        ) {
            continueInitialPermissionOrchestration()
        }
        val pendingQuery = viewModel.searchViewQuery
        val effectivePendingQuery = pendingQuery.toEffectiveSearchQuery()
        val searchState = viewModel.searchState
        if (
            viewModel.isSearchViewExpanded &&
            effectivePendingQuery.isNotEmpty() &&
            (!searchState.isSearching || searchState.query != effectivePendingQuery)
        ) {
            viewModel.search(pendingQuery)
        }
    }

    internal fun continueInitialPermissionOrchestration() {
        if (
            !isAdded ||
            !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            return
        }
        if (!viewModel.isNotificationPermissionRequested) {
            ensureStorageAccess()
        }
        if (!viewModel.isStorageAccessRequested) {
            ensureNotificationPermission()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
        menuBinding.viewSortItem.subMenu!!.setGroupDividerEnabledCompat(true)
        setUpSearchView()
    }

    private fun setUpSearchView() {
        val searchView = menuBinding.searchItem.actionView as FixQueryChangeSearchView
        val searchText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
        searchText.filters = arrayOf(
            *searchText.filters,
            NO_NUL_INPUT_FILTER,
            InputFilter.LengthFilter(SearchRequest.MAX_QUERY_LENGTH)
        )
        val restoredQuery = viewModel.searchViewQuery.toValidSearchQuery()
        if (restoredQuery != viewModel.searchViewQuery) {
            viewModel.searchViewQuery = restoredQuery
        }
        // MenuItem.OnActionExpandListener.onMenuItemActionExpand() is called before SearchView
        // resets the query.
        searchView.setOnSearchClickListener {
            viewModel.isSearchViewExpanded = true
            searchView.setQuery(viewModel.searchViewQuery, false)
            val query = viewModel.searchViewQuery
            if (query.isNotBlank()) {
                viewModel.search(query)
            }
        }
        // SearchView.OnCloseListener.onClose() is not always called.
        menuBinding.searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.isSearchViewExpanded = false
                viewModel.stopSearching()
                return true
            }
        })
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (query.isBlank()) {
                    viewModel.stopSearching()
                } else {
                    viewModel.submitSearch(query)
                }
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                if (searchView.shouldIgnoreQueryChange) {
                    return false
                }
                viewModel.searchViewQuery = query
                if (query.isBlank()) {
                    viewModel.stopSearching()
                } else {
                    // Publish a provisional refinement and cancel the previous worker immediately.
                    // SearchFileListLiveData debounces only an expensive unindexed live walk.
                    viewModel.search(query)
                }
                return false
            }
        })
        if (viewModel.isSearchViewExpanded) {
            menuBinding.searchItem.expandActionView()
        }
    }

    private fun collapseSearchView() {
        if (this::menuBinding.isInitialized && menuBinding.searchItem.isActionViewExpanded) {
            menuBinding.searchItem.collapseActionView()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateFileManagerPlusHomeMenuItems()
        if (isFileManagerPlusHomeDisplayed) {
            return
        }
        updateViewSortMenuItems()
        updateSelectAllMenuItem()
        updateShowHiddenFilesMenuItem()
    }

    private fun updateFileManagerPlusHomeMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val visible = !isFileManagerPlusHomeDisplayed
        for (index in 0 until menuBinding.menu.size()) {
            menuBinding.menu.getItem(index).isVisible = visible
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                binding.drawerLayout?.openDrawer(GravityCompat.START)
                if (binding.persistentDrawerLayout != null) {
                    Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(
                        !Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
                    )
                }
                true
            }
            R.id.action_view_list -> {
                viewModel.viewType = FileViewType.LIST
                true
            }
            R.id.action_view_grid -> {
                viewModel.viewType = FileViewType.GRID
                true
            }
            R.id.action_sort_by_name -> {
                viewModel.setSortBy(By.NAME)
                true
            }
            R.id.action_sort_by_type -> {
                viewModel.setSortBy(By.TYPE)
                true
            }
            R.id.action_sort_by_size -> {
                viewModel.setSortBy(By.SIZE)
                true
            }
            R.id.action_sort_by_last_modified -> {
                viewModel.setSortBy(By.LAST_MODIFIED)
                true
            }
            R.id.action_sort_order_ascending -> {
                viewModel.setSortOrder(
                    if (!menuBinding.sortOrderAscendingItem.isChecked) {
                        Order.ASCENDING
                    } else {
                        Order.DESCENDING
                    }
                )
                true
            }
            R.id.action_sort_directories_first -> {
                viewModel.setSortDirectoriesFirst(!menuBinding.sortDirectoriesFirstItem.isChecked)
                true
            }
            R.id.action_view_sort_path_specific -> {
                viewModel.isViewSortPathSpecific = !menuBinding.viewSortPathSpecificItem.isChecked
                true
            }
            R.id.action_new_task -> {
                newTask()
                true
            }
            R.id.action_navigate_up -> {
                navigateUp()
                true
            }
            R.id.action_navigate_to -> {
                showNavigateToPathDialog()
                true
            }
            R.id.action_refresh -> {
                refresh()
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            R.id.action_show_hidden_files -> {
                setShowHiddenFiles(!menuBinding.showHiddenFilesItem.isChecked)
                true
            }
            R.id.action_share -> {
                share()
                true
            }
            R.id.action_copy_path -> {
                copyPath()
                true
            }
            R.id.action_open_in_terminal -> {
                openInTerminal()
                true
            }
            R.id.action_add_bookmark -> {
                addBookmark()
                true
            }
            R.id.action_create_shortcut -> {
                createShortcut()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (!isFileManagerPlusHomeDisplayed && bottomActionMode.isActive) {
            val menu = bottomActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        if (!isFileManagerPlusHomeDisplayed && overlayActionMode.isActive) {
            val menu = overlayActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        return false
    }

    private fun onPersistentDrawerOpenChanged(open: Boolean) {
        binding.persistentDrawerLayout?.let {
            if (open) {
                it.openDrawer(GravityCompat.START)
            } else {
                it.closeDrawer(GravityCompat.START)
            }
        }
        updateSpanCount()
        updateFileManagerPlusHomeGrid()
    }

    private fun onCurrentPathChanged(path: Path) {
        updateOverlayToolbar()
        updateBottomToolbar()
        updateToolbarTitleForInterface()
        updateNavigationBackCallback()
    }

    private fun onSearchViewExpandedChanged(expanded: Boolean) {
        renderInterfaceState()
        updateViewSortMenuItems()
    }

    private fun onFileListChanged(stateful: Stateful<List<FileItem>>) {
        if (isFileManagerPlusHomeDisplayed) {
            val files = stateful.value
            if (files != null) {
                updateAdapterFileList()
            } else {
                replaceAdapterFileList(emptyList())
            }
            return
        }
        renderFileListState(stateful)
    }

    private fun renderFileListState(stateful: Stateful<List<FileItem>>) {
        val files = stateful.value
        val isSearching = viewModel.searchState.isSearching
        when {
            stateful is Failure -> binding.toolbar.setSubtitle(R.string.error)
            stateful is Loading && !isSearching -> binding.toolbar.setSubtitle(R.string.loading)
            stateful is Success && viewModel.isSearchResultTruncated ->
                binding.toolbar.subtitle = getString(
                    R.string.file_list_search_result_limit_format,
                    files!!.size
                )
            else -> binding.toolbar.subtitle = getSubtitle(files!!)
        }
        val hasFiles = !files.isNullOrEmpty()
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading && (hasFiles || isSearching)
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && !(hasFiles || isSearching))
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && !hasFiles)
        val throwable = (stateful as? Failure)?.throwable
        if (throwable != null) {
            throwable.printStackTrace()
            val error = throwable.toString()
            if (hasFiles) {
                showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        binding.emptyView.fadeToVisibilityUnsafe(stateful is Success && !hasFiles)
        if (files != null) {
            updateAdapterFileList()
        } else {
            replaceAdapterFileList(emptyList())
        }
        if (stateful is Success) {
            viewModel.pendingState?.let { layoutManager.onRestoreInstanceState(it) }
        }
    }

    private fun onInterfaceStyleChanged(interfaceStyle: InterfaceStyle) {
        val previousInterfaceStyle = appliedInterfaceStyle
        appliedInterfaceStyle = interfaceStyle
        adapter.interfaceStyle = interfaceStyle
        if (interfaceStyle == InterfaceStyle.CLASSIC) {
            viewModel.fileManagerPlusNavigationRoot = null
            viewModel.isFileManagerPlusHomeVisible = false
        } else if (
            previousInterfaceStyle != InterfaceStyle.FILE_MANAGER_PLUS &&
            isFileManagerPlusHomeEligible &&
            viewModel.pickOptions == null
        ) {
            viewModel.clearSelectedFiles()
            viewModel.fileManagerPlusNavigationRoot = null
            viewModel.isFileManagerPlusHomeVisible = true
        }
        updateFileManagerPlusHomeGrid()
        updateSpanCount()
        updateFileManagerPlusHomeItems()
        renderInterfaceState()
    }

    private fun renderInterfaceState() {
        if (!this::adapter.isInitialized || !this::fileManagerPlusHomeAdapter.isInitialized) {
            return
        }
        val showHome = isFileManagerPlusHomeDisplayed
        val previouslyShowedHome = renderedFileManagerPlusHome
        binding.fileManagerPlusHomeRecyclerView.isVisible = showHome
        binding.swipeRefreshLayout.isVisible = !showHome
        binding.breadcrumbLayout.isVisible = !showHome
        binding.speedDialView.isVisible = !showHome
        binding.bottomBarLayout.isVisible = !showHome && bottomActionMode.isActive
        binding.appBarLayout.setLiftOnScrollTargetViewId(
            if (showHome) {
                R.id.fileManagerPlusHomeRecyclerView
            } else {
                R.id.recyclerView
            }
        )
        if (showHome) {
            binding.appBarLayout.setExpanded(true)
            binding.progress.isVisible = false
            binding.errorText.isVisible = false
            binding.emptyView.isVisible = false
            binding.swipeRefreshLayout.isRefreshing = false
            binding.toolbar.subtitle = null
            updateFileManagerPlusHomeItems()
        } else if (previouslyShowedHome == true) {
            viewModel.fileListLiveData.value?.let(::renderFileListState)
        }
        renderedFileManagerPlusHome = showHome
        updateToolbarTitleForInterface()
        updateNavigationBackCallback()
        requireActivity().invalidateOptionsMenu()
    }

    private fun updateToolbarTitleForInterface() {
        if (viewModel.pickOptions != null) {
            return
        }
        val title = when {
            isFileManagerPlusHomeDisplayed -> getString(R.string.app_name)
            appliedInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS -> {
                val displayRoot =
                    viewModel.fileManagerPlusNavigationRoot ?: viewModel.currentPath
                NavigationRootMapLiveData.valueCompat[displayRoot]?.getName(requireContext())
                    ?: displayRoot.name.takeIf { it.isNotEmpty() }
                    ?: displayRoot.toUserFriendlyString()
            }
            else -> getString(R.string.file_list_title)
        }
        binding.toolbar.title = title
    }

    private fun getFileManagerPlusHomeAvailableWidthPx(): Int {
        val recyclerView = binding.fileManagerPlusHomeRecyclerView
        val measuredWidth =
            recyclerView.width - recyclerView.paddingStart - recyclerView.paddingEnd
        if (measuredWidth > 0) {
            return measuredWidth
        }
        var widthDp = resources.configuration.screenWidthDp
        if (binding.persistentDrawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
            widthDp -= getDimensionDp(R.dimen.navigation_max_width).roundToInt()
        }
        return (
            widthDp * resources.displayMetrics.density -
                recyclerView.paddingStart -
                recyclerView.paddingEnd
            )
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun updateFileManagerPlusHomeGrid() {
        val layoutManager =
            binding.fileManagerPlusHomeRecyclerView.layoutManager as? GridLayoutManager ?: return
        layoutManager.spanSizeLookup.invalidateSpanIndexCache()
        binding.fileManagerPlusHomeRecyclerView.requestLayout()
    }

    private fun updateFileManagerPlusHomeItems() {
        if (!this::fileManagerPlusHomeAdapter.isInitialized) {
            return
        }
        val context = requireContext()
        val storageItems = mutableListOf<FileManagerPlusHomeItem>()
        val shortcutItems = mutableListOf<FileManagerPlusHomeItem>()
        val bookmarkItems = mutableListOf<FileManagerPlusHomeItem>()
        val actionItems = mutableListOf<FileManagerPlusHomeItem>()
        Settings.STORAGES.valueCompat
            .asSequence()
            .filter(Storage::isVisible)
            .sortedWith(
                compareBy<Storage> {
                    it.path != Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
                }.thenBy { it.getName(context) }
            )
            .forEach { storage ->
                val destination = storage.path
                    ?.let(FileManagerPlusHomeItem.Destination::FilePath)
                    ?: storage.createIntent()
                        ?.let(FileManagerPlusHomeItem.Destination::ActivityIntent)
                    ?: return@forEach
                storageItems += FileManagerPlusHomeItem(
                    id = stableFileManagerPlusHomeId("storage:${storage.id}"),
                    iconRes = storage.iconRes,
                    title = storage.getName(context),
                    subtitle = storage.linuxPath?.let(::getStorageUsageSubtitle),
                    role = FileManagerPlusHomeItem.Role.STORAGE,
                    destination = destination
                )
            }
        if (Environment::class.supportsExternalStorageManager()) {
            StorageVolumeListLiveData.valueCompat
                .asSequence()
                .filter { !it.isPrimaryCompat && it.isMounted }
                .forEach { storageVolume ->
                    val linuxPath = storageVolume.pathCompat
                    storageItems += FileManagerPlusHomeItem(
                        id = stableFileManagerPlusHomeId(
                            "storage-volume:${storageVolume.hashCode()}"
                        ),
                        iconRes = R.drawable.sd_card_icon_white_24dp,
                        title = storageVolume.getDescriptionCompat(context),
                        subtitle = getStorageUsageSubtitle(linuxPath),
                        role = FileManagerPlusHomeItem.Role.STORAGE,
                        destination = FileManagerPlusHomeItem.Destination.FilePath(
                            Paths.get(linuxPath)
                        )
                    )
                }
        }

        val standardDirectoryOrder = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DCIM
        )
        val standardDirectoryMap = standardDirectories.associateBy { it.relativePath }
        standardDirectoryOrder.mapNotNull(standardDirectoryMap::get).forEach { directory ->
            shortcutItems += FileManagerPlusHomeItem(
                id = stableFileManagerPlusHomeId("directory:${directory.relativePath}"),
                iconRes = directory.iconRes,
                title = directory.getTitle(context),
                subtitle = null,
                role = FileManagerPlusHomeItem.Role.SHORTCUT,
                destination = FileManagerPlusHomeItem.Destination.FilePath(
                    Paths.get(getExternalStorageDirectory(directory.relativePath))
                )
            )
        }

        shortcutItems += FileManagerPlusHomeItem(
            id = stableFileManagerPlusHomeId("apk"),
            iconRes = R.drawable.file_apk_icon,
            title = getString(R.string.file_properties_apk),
            subtitle = null,
            role = FileManagerPlusHomeItem.Role.SHORTCUT,
            destination = FileManagerPlusHomeItem.Destination.ActivityIntent(
                GlobalSearchActivity.createIntent(context, "*.apk")
            )
        )
        shortcutItems += FileManagerPlusHomeItem(
            id = stableFileManagerPlusHomeId("global-search"),
            iconRes = R.drawable.global_search_icon_white_24dp,
            title = getString(R.string.navigation_global_search),
            subtitle = null,
            role = FileManagerPlusHomeItem.Role.SHORTCUT,
            destination = FileManagerPlusHomeItem.Destination.ActivityIntent(
                GlobalSearchActivity.createIntent(context)
            )
        )
        val screenshotsPath = Paths.get(
            getExternalStorageDirectory(Environment.DIRECTORY_PICTURES),
            EnvironmentCompat2.DIRECTORY_SCREENSHOTS
        )
        Settings.BOOKMARK_DIRECTORIES.valueCompat.forEach { bookmark ->
            val isScreenshotsShortcut = bookmark.path == screenshotsPath
            val targetItems = if (isScreenshotsShortcut) shortcutItems else bookmarkItems
            targetItems += FileManagerPlusHomeItem(
                id = stableFileManagerPlusHomeId("bookmark:${bookmark.id}"),
                iconRes = R.drawable.directory_icon_white_24dp,
                title = bookmark.name,
                subtitle = null,
                role = if (isScreenshotsShortcut) {
                    FileManagerPlusHomeItem.Role.SHORTCUT
                } else {
                    FileManagerPlusHomeItem.Role.BOOKMARK
                },
                destination = FileManagerPlusHomeItem.Destination.FilePath(bookmark.path)
            )
        }
        actionItems += FileManagerPlusHomeItem(
            id = stableFileManagerPlusHomeId("add-storage"),
            iconRes = R.drawable.add_icon_white_24dp,
            title = getString(R.string.storage_add_storage_title),
            subtitle = null,
            role = FileManagerPlusHomeItem.Role.ACTION,
            destination = FileManagerPlusHomeItem.Destination.ActivityIntent(
                AddStorageDialogActivity::class.createIntent()
            )
        )
        actionItems += FileManagerPlusHomeItem(
            id = stableFileManagerPlusHomeId("ftp-server"),
            iconRes = R.drawable.shared_directory_icon_white_24dp,
            title = getString(R.string.navigation_ftp_server),
            subtitle = null,
            role = FileManagerPlusHomeItem.Role.ACTION,
            destination = FileManagerPlusHomeItem.Destination.ActivityIntent(
                FtpServerActivity::class.createIntent()
            )
        )
        actionItems += FileManagerPlusHomeItem(
            id = stableFileManagerPlusHomeId("settings"),
            iconRes = R.drawable.settings_icon_white_24dp,
            title = getString(R.string.navigation_settings),
            subtitle = null,
            role = FileManagerPlusHomeItem.Role.ACTION,
            destination = FileManagerPlusHomeItem.Destination.ActivityIntent(
                SettingsActivity::class.createIntent()
            )
        )
        val rows = mutableListOf<FileManagerPlusHomeRow>()
        fun appendSection(key: String, sectionItems: List<FileManagerPlusHomeItem>) {
            if (sectionItems.isEmpty()) {
                return
            }
            if (rows.isNotEmpty()) {
                rows += FileManagerPlusHomeRow.SectionGap(
                    stableFileManagerPlusHomeId("section-gap:$key")
                )
            }
            rows += sectionItems.map { FileManagerPlusHomeRow.Tile(it) }
        }
        appendSection("storage", storageItems)
        appendSection("shortcut", shortcutItems)
        appendSection("bookmark", bookmarkItems)
        appendSection("action", actionItems)
        fileManagerPlusHomeAdapter.replace(rows)
        updateFileManagerPlusHomeGrid()
    }

    private fun getStorageUsageSubtitle(linuxPath: String): String? {
        var totalSpace = JavaFile.getTotalSpace(linuxPath)
        val freeSpace = when {
            totalSpace != 0L -> JavaFile.getFreeSpace(linuxPath)
            linuxPath == FileSystemRoot.LINUX_PATH -> {
                val systemPath = Environment.getRootDirectory().path
                totalSpace = JavaFile.getTotalSpace(systemPath)
                JavaFile.getFreeSpace(systemPath)
            }
            else -> 0L
        }
        if (totalSpace == 0L) {
            return null
        }
        return getString(
            R.string.navigation_storage_subtitle_format,
            freeSpace.asFileSize().formatHumanReadable(requireContext()),
            totalSpace.asFileSize().formatHumanReadable(requireContext())
        )
    }

    private fun stableFileManagerPlusHomeId(key: String): Long =
        "FileManagerPlusHome:$key".hashCode().toLong()

    private fun openFileManagerPlusHomeItem(item: FileManagerPlusHomeItem) {
        when (val destination = item.destination) {
            is FileManagerPlusHomeItem.Destination.FilePath -> {
                collapseSearchView()
                viewModel.clearSelectedFiles()
                viewModel.fileManagerPlusNavigationRoot = destination.path
                viewModel.isFileManagerPlusHomeVisible = false
                viewModel.resetTo(destination.path)
            }
            is FileManagerPlusHomeItem.Destination.ActivityIntent ->
                startActivitySafe(destination.intent)
        }
    }

    private fun shouldReturnToFileManagerPlusHome(): Boolean {
        if (
            appliedInterfaceStyle != InterfaceStyle.FILE_MANAGER_PLUS ||
            !isFileManagerPlusHomeEligible ||
            isFileManagerPlusHomeDisplayed
        ) {
            return false
        }
        val navigationRoot = viewModel.fileManagerPlusNavigationRoot
        return navigationRoot == viewModel.currentPath || !viewModel.canNavigateUpBreadcrumb
    }

    private fun updateNavigationBackCallback() {
        if (!this::navigationBackCallback.isInitialized) {
            return
        }
        navigationBackCallback.isEnabled = when {
            isFileManagerPlusHomeDisplayed -> false
            appliedInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS &&
                isFileManagerPlusHomeEligible -> true
            else -> viewModel.canNavigateUpBreadcrumb
        }
    }

    private fun getSubtitle(files: List<FileItem>): String {
        val directoryCount = files.count { it.attributes.isDirectory }
        val fileCount = files.size - directoryCount
        val directoryCountText = if (directoryCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_directory_count_format, directoryCount, directoryCount
            )
        } else {
            null
        }
        val fileCountText = if (fileCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_file_count_format, fileCount, fileCount
            )
        } else {
            null
        }
        return when {
            !directoryCountText.isNullOrEmpty() && !fileCountText.isNullOrEmpty() ->
                (directoryCountText + getString(R.string.file_list_subtitle_separator)
                    + fileCountText)
            !directoryCountText.isNullOrEmpty() -> directoryCountText
            !fileCountText.isNullOrEmpty() -> fileCountText
            else -> getString(R.string.empty)
        }
    }

    private fun onViewTypeChanged(viewType: FileViewType) {
        updateSpanCount()
        adapter.viewType = viewType
        updateViewSortMenuItems()
    }

    private fun updateSpanCount() {
        if (appliedInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS) {
            layoutManager.spanCount = 1
            return
        }
        layoutManager.spanCount = when (viewModel.viewType) {
            FileViewType.LIST -> 1
            FileViewType.GRID -> {
                var widthDp = resources.configuration.screenWidthDp
                val persistentDrawerLayout = binding.persistentDrawerLayout
                if (persistentDrawerLayout != null &&
                    persistentDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                    widthDp -= getDimensionDp(R.dimen.navigation_max_width).roundToInt()
                }
                (widthDp / 180).coerceAtLeast(2)
            }
        }
    }

    private fun onSortOptionsChanged(sortOptions: FileSortOptions) {
        adapter.sortOptions = sortOptions
        updateViewSortMenuItems()
    }

    private fun onViewSortPathSpecificChanged(pathSpecific: Boolean) {
        updateViewSortMenuItems()
    }

    private fun updateViewSortMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        if (isFileManagerPlusHomeDisplayed) {
            menuBinding.viewSortItem.isVisible = false
            return
        }
        val searchViewExpanded = viewModel.isSearchViewExpanded
        menuBinding.viewSortItem.isVisible = !searchViewExpanded
        if (searchViewExpanded) {
            return
        }
        val isFileManagerPlus = appliedInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS
        menuBinding.viewListItem.isVisible = !isFileManagerPlus
        menuBinding.viewGridItem.isVisible = !isFileManagerPlus
        val viewType = if (isFileManagerPlus) FileViewType.LIST else viewModel.viewType
        val checkedViewTypeItem = when (viewType) {
            FileViewType.LIST -> menuBinding.viewListItem
            FileViewType.GRID -> menuBinding.viewGridItem
        }
        checkedViewTypeItem.isChecked = true
        val sortOptions = viewModel.sortOptions
        val checkedSortByItem = when (sortOptions.by) {
            By.NAME -> menuBinding.sortByNameItem
            By.TYPE -> menuBinding.sortByTypeItem
            By.SIZE -> menuBinding.sortBySizeItem
            By.LAST_MODIFIED -> menuBinding.sortByLastModifiedItem
        }
        checkedSortByItem.isChecked = true
        menuBinding.sortOrderAscendingItem.isChecked = sortOptions.order == Order.ASCENDING
        menuBinding.sortDirectoriesFirstItem.isChecked = sortOptions.isDirectoriesFirst
        menuBinding.viewSortPathSpecificItem.isChecked = viewModel.isViewSortPathSpecific
    }

    private fun navigateUp() {
        collapseSearchView()
        if (shouldReturnToFileManagerPlusHome()) {
            navigateHome()
        } else {
            viewModel.navigateUp()
        }
    }

    private fun showNavigateToPathDialog() {
        NavigateToPathDialogFragment.show(currentPath, this)
    }

    private fun newTask() {
        openInNewTask(currentPath)
    }

    private fun refresh() {
        viewModel.reload()
    }

    private fun setShowHiddenFiles(showHiddenFiles: Boolean) {
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(showHiddenFiles)
    }

    private fun onShowHiddenFilesChanged(showHiddenFiles: Boolean) {
        updateAdapterFileList()
        updateShowHiddenFilesMenuItem()
    }

    private fun updateAdapterFileList() {
        var files = viewModel.fileListStateful.value ?: return
        if (!Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat) {
            files = files.filterNot { it.isHidden }
        }
        replaceAdapterFileList(files)
    }

    private fun replaceAdapterFileList(files: List<FileItem>) {
        val isSearching = viewModel.searchState.isSearching
        if (isSearching && !isFileListItemAnimatorSuppressedForSearch) {
            // Query changes replace the visible result set. Disable RecyclerView's default
            // remove/add fades as well as the adapter animation so typing remains visually stable.
            binding.recyclerView.itemAnimator = null
            isFileListItemAnimatorSuppressedForSearch = true
        }
        val highlightQuery = if (isSearching) viewModel.searchState.query else ""
        adapter.replaceListAndIsSearching(
            files,
            isSearching,
            highlightQuery
        )
        if (!isSearching && isFileListItemAnimatorSuppressedForSearch) {
            // Restore normal file-operation animations only after replacing the search result.
            binding.recyclerView.itemAnimator = fileListItemAnimator
            isFileListItemAnimatorSuppressedForSearch = false
        }
    }

    private fun updateShowHiddenFilesMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        menuBinding.showHiddenFilesItem.isChecked = showHiddenFiles
    }

    private fun share() {
        shareFile(currentPath, MimeType.DIRECTORY)
    }

    private fun copyPath() {
        copyPath(currentPath)
    }

    private fun openInTerminal() {
        val path = currentPath
        if (path.isLinuxPath) {
            Terminal.open(path.toFile().path, requireContext())
        } else {
            // TODO
        }
    }

    override fun navigateTo(path: Path) {
        collapseSearchView()
        if (
            appliedInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS &&
            isFileManagerPlusHomeEligible &&
            viewModel.isFileManagerPlusHomeVisible
        ) {
            viewModel.fileManagerPlusNavigationRoot = path
            viewModel.isFileManagerPlusHomeVisible = false
        }
        val state = layoutManager.onSaveInstanceState()
        viewModel.navigateTo(state!!, path)
    }

    override fun copyPath(path: Path) {
        clipboardManager.copyText(path.toUserFriendlyString(), requireContext())
    }

    override fun openInNewTask(path: Path) {
        val intent = FileListActivity.createViewIntent(path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivitySafe(intent)
    }

    private fun onPickOptionsChanged(pickOptions: PickOptions?) {
        val title = if (pickOptions == null) {
            getString(R.string.file_list_title)
        } else {
            val count = if (pickOptions.allowMultiple) Int.MAX_VALUE else 1
            when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE ->
                    getQuantityString(R.plurals.file_list_title_open_file, count)
                PickOptions.Mode.CREATE_FILE -> getString(R.string.file_list_title_create_file)
                PickOptions.Mode.OPEN_DIRECTORY ->
                    getQuantityString(R.plurals.file_list_title_open_directory, count)
            }
        }
        requireActivity().title = title
        updateSelectAllMenuItem()
        updateOverlayToolbar()
        updateBottomToolbar()
        adapter.pickOptions = pickOptions
        renderInterfaceState()
    }

    private fun updateSelectAllMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val pickOptions = viewModel.pickOptions
        menuBinding.selectAllItem.isVisible = pickOptions == null || pickOptions.allowMultiple
    }

    private fun pickFiles(files: FileItemSet) {
        pickPaths(files.mapTo(linkedSetOf()) { it.path })
    }

    private fun pickPaths(paths: LinkedHashSet<Path>) {
        val intent = Intent().apply {
            val pickOptions = viewModel.pickOptions!!
            if (paths.size == 1) {
                val path = paths.single()
                data = path.fileProviderUri
                extraPath = path
            } else {
                val mimeTypes = pickOptions.mimeTypes.map { it.value }
                val items = paths.map { ClipData.Item(it.fileProviderUri) }
                clipData = ClipData::class.create(null, mimeTypes, items)
                extraPathList = paths.toList()
            }
            var flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (!pickOptions.readOnly) {
                flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            if (pickOptions.mode == PickOptions.Mode.OPEN_DIRECTORY) {
                flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            }
            addFlags(flags)
        }
        requireActivity().run {
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    private fun onSelectedFilesChanged(files: FileItemSet) {
        updateOverlayToolbar()
        adapter.replaceSelectedFiles(files)
    }

    private fun updateOverlayToolbar() {
        val files = viewModel.selectedFiles
        if (files.isEmpty()) {
            if (overlayActionMode.isActive) {
                overlayActionMode.finish()
            }
            return
        }
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_pick)
            val menu = overlayActionMode.menu
            val isOpen = when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE, PickOptions.Mode.OPEN_DIRECTORY -> true
                PickOptions.Mode.CREATE_FILE -> false
            }
            menu.findItem(R.id.action_open).isVisible = isOpen
            menu.findItem(R.id.action_create).isVisible = !isOpen
            menu.findItem(R.id.action_select_all).isVisible = pickOptions.allowMultiple
        } else {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_select)
            val menu = overlayActionMode.menu
            val isAnyFileReadOnly = files.any { it.path.fileSystem.isReadOnly }
            menu.findItem(R.id.action_cut).isVisible = !isAnyFileReadOnly
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            menu.findItem(R.id.action_copy)
                .setIcon(
                    if (areAllFilesArchivePaths) {
                        R.drawable.extract_icon_control_normal_24dp
                    } else {
                        R.drawable.copy_icon_control_normal_24dp
                    }
                )
                .setTitle(
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_select_action_extract
                    } else {
                        R.string.copy
                    }
                )
            menu.findItem(R.id.action_delete).isVisible = !isAnyFileReadOnly
            val areAllFilesArchiveFiles = files.all { it.isArchiveFile }
            menu.findItem(R.id.action_extract).isVisible = areAllFilesArchiveFiles
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            menu.findItem(R.id.action_archive).isVisible = !isCurrentPathReadOnly
        }
        if (!overlayActionMode.isActive) {
            binding.appBarLayout.setExpanded(true)
            binding.appBarLayout.addOnOffsetChangedListener(
                AppBarLayoutExpandHackListener(binding.recyclerView)
            )
            overlayActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onOverlayActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onOverlayActionModeFinished()
                }
            })
        }
    }

    private fun onOverlayActionModeMenuItemClicked(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_open -> {
                pickFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_create -> {
                confirmReplaceFile(viewModel.selectedFiles.single())
                true
            }
            R.id.action_cut -> {
                cutFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_copy -> {
                copyFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_delete -> {
                confirmDeleteFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_extract -> {
                extractFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_archive -> {
                showCreateArchiveDialog(viewModel.selectedFiles)
                true
            }
            R.id.action_share -> {
                shareFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            else -> false
        }

    private fun onOverlayActionModeFinished() {
        viewModel.clearSelectedFiles()
    }

    private fun confirmReplaceFile(file: FileItem, setFileName: Boolean = true) {
        if (setFileName) {
            val fileName = file.name
            binding.bottomCreateFileNameEdit.setText(fileName)
            binding.bottomCreateFileNameEdit.setSelection(
                0, fileName.asFileName().baseName.length
            )
        }
        ConfirmReplaceFileDialogFragment.show(file, this)
    }

    override fun replaceFile(file: FileItem) {
        pickFiles(fileItemSetOf(file))
    }

    private fun cutFiles(files: FileItemSet) {
        viewModel.addToPasteState(false, files)
        viewModel.selectFiles(files, false)
    }

    private fun copyFiles(files: FileItemSet) {
        viewModel.addToPasteState(true, files)
        viewModel.selectFiles(files, false)
    }

    private fun confirmDeleteFiles(files: FileItemSet) {
        ConfirmDeleteFilesDialogFragment.show(files, this)
    }

    override fun deleteFiles(files: FileItemSet) {
        FileJobService.delete(makePathListForJob(files), requireContext())
        viewModel.selectFiles(files, false)
    }

    private fun extractFiles(files: FileItemSet) {
        copyFiles(files.mapTo(fileItemSetOf()) { it.createDummyArchiveRoot() })
        viewModel.selectFiles(files, false)
    }

    private fun showCreateArchiveDialog(files: FileItemSet) {
        CreateArchiveDialogFragment.show(files, this)
    }

    override fun archive(
        files: FileItemSet,
        name: String,
        format: Int,
        filter: Int,
        password: String?
    ) {
        val archiveFile = viewModel.currentPath.resolve(name)
        FileJobService.archive(
            makePathListForJob(files), archiveFile, format, filter, password, requireContext()
        )
        viewModel.selectFiles(files, false)
    }

    private fun shareFiles(files: FileItemSet) {
        shareFiles(files.map { it.path }, files.map { it.mimeType })
        viewModel.selectFiles(files, false)
    }

    private fun selectAllFiles() {
        adapter.selectAllFiles()
    }

    private fun onPasteStateChanged(pasteState: PasteState) {
        updateBottomToolbar()
    }

    private fun updateBottomToolbar() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            bottomActionMode.setMenuResource(R.menu.file_list_pick_bottom)
            val menu = bottomActionMode.menu
            when (pickOptions.mode) {
                PickOptions.Mode.CREATE_FILE -> {
                    bottomActionMode.title = null
                    binding.bottomCreateFileNameEdit.isVisible = true
                    val createMenuItem = menu.findItem(R.id.action_create)
                    binding.bottomCreateFileNameEdit.setOnEditorConfirmActionListener {
                        onBottomActionModeMenuItemClicked(createMenuItem)
                    }
                    if (!viewModel.isCreateFileNameEditInitialized) {
                        val fileName = pickOptions.fileName!!
                        binding.bottomCreateFileNameEdit.setText(fileName)
                        binding.bottomCreateFileNameEdit.setSelection(
                            0, fileName.asFileName().baseName.length
                        )
                        binding.bottomCreateFileNameEdit.requestFocus()
                        viewModel.isCreateFileNameEditInitialized = true
                    }
                    menu.findItem(R.id.action_open).isVisible = false
                    createMenuItem.isVisible = true
                }
                PickOptions.Mode.OPEN_DIRECTORY -> {
                    val path = viewModel.currentPath
                    val navigationRoot = NavigationRootMapLiveData.valueCompat[path]
                    val name = navigationRoot?.getName(requireContext()) ?: path.name
                    bottomActionMode.title =
                        getString(R.string.file_list_open_current_directory_format, name)
                    binding.bottomCreateFileNameEdit.isVisible = false
                    menu.findItem(R.id.action_open).isVisible = true
                    menu.findItem(R.id.action_create).isVisible = false
                }
                else -> {
                    if (bottomActionMode.isActive) {
                        bottomActionMode.finish()
                    }
                    return
                }
            }
        } else {
            val pasteState = viewModel.pasteState
            val files = pasteState.files
            if (files.isEmpty()) {
                if (bottomActionMode.isActive) {
                    bottomActionMode.finish()
                }
                return
            }
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            bottomActionMode.title = getString(
                if (pasteState.copy) {
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_paste_extract_title_format
                    } else {
                        R.string.file_list_paste_copy_title_format
                    }
                } else {
                    R.string.file_list_paste_move_title_format
                }, files.size
            )
            binding.bottomCreateFileNameEdit.isVisible = false
            bottomActionMode.setMenuResource(R.menu.file_list_paste)
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            bottomActionMode.menu.findItem(R.id.action_paste)
                .setTitle(
                    if (areAllFilesArchivePaths) R.string.file_list_paste_action_extract_here else R.string.paste
                )
                .isEnabled = !isCurrentPathReadOnly
        }
        if (!bottomActionMode.isActive) {
            bottomActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarNavigationIconClicked(toolbarActionMode: ToolbarActionMode) {
                    onBottomToolbarNavigationIconClicked()
                }

                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onBottomActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onBottomActionModeFinished()
                }
            })
        }
        if (isFileManagerPlusHomeDisplayed) {
            // Keep the clipboard state, but never expose a paste action whose destination is the
            // hidden file list behind the home dashboard.
            binding.bottomBarLayout.isVisible = false
        }
    }

    private fun onBottomToolbarNavigationIconClicked() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            requireActivity().finish()
        } else {
            bottomActionMode.finish()
        }
    }

    private fun onBottomActionModeMenuItemClicked(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_open -> {
                pickPaths(linkedSetOf(viewModel.currentPath))
                true
            }
            R.id.action_create -> {
                val fileName = binding.bottomCreateFileNameEdit.text.toString()
                if (fileName.isEmpty()) {
                    showToast(R.string.file_list_create_file_name_error_empty)
                } else if (fileName.asFileNameOrNull() == null) {
                    showToast(R.string.file_list_create_file_name_error_invalid)
                } else {
                    val file = getFileWithName(fileName)
                    if (file != null) {
                        confirmReplaceFile(file, false)
                    } else {
                        val path = viewModel.currentPath.resolve(fileName)
                        pickPaths(linkedSetOf(path))
                    }
                }
                true
            }
            R.id.action_paste -> {
                pasteFiles(currentPath)
                true
            }
            else -> false
        }

    private fun onBottomActionModeFinished() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions == null) {
            viewModel.clearPasteState()
        }
    }

    private fun pasteFiles(targetDirectory: Path) {
        val pasteState = viewModel.pasteState
        if (viewModel.pasteState.copy) {
            FileJobService.copy(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        } else {
            FileJobService.move(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        }
        viewModel.clearPasteState()
    }

    private fun makePathListForJob(files: FileItemSet): List<Path> =
        files.map { it.path }.sortedBy { it.toUri() }

    private fun onFileNameEllipsizeChanged(fileNameEllipsize: TextUtils.TruncateAt) {
        adapter.nameEllipsize = fileNameEllipsize
    }

    override fun clearSelectedFiles() {
        viewModel.clearSelectedFiles()
    }

    override fun selectFile(file: FileItem, selected: Boolean) {
        viewModel.selectFile(file, selected)
    }

    override fun selectFiles(files: FileItemSet, selected: Boolean) {
        viewModel.selectFiles(files, selected)
    }

    override fun openFile(file: FileItem) {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            if (file.attributes.isDirectory) {
                navigateTo(file.path)
            } else {
                when (pickOptions.mode) {
                    PickOptions.Mode.OPEN_FILE -> pickFiles(fileItemSetOf(file))
                    PickOptions.Mode.CREATE_FILE -> confirmReplaceFile(file)
                    PickOptions.Mode.OPEN_DIRECTORY -> {}
                }
            }
            return
        }
        if (file.mimeType.isApk) {
            openApk(file)
            return
        }
        if (file.isListable) {
            navigateTo(file.listablePath)
            return
        }
        openFileWithIntent(file, false)
    }

    private fun openApk(file: FileItem) {
        if (!file.isListable) {
            installApk(file)
            return
        }
        when (Settings.OPEN_APK_DEFAULT_ACTION.valueCompat) {
            OpenApkDefaultAction.INSTALL -> installApk(file)
            OpenApkDefaultAction.VIEW -> viewApk(file)
            OpenApkDefaultAction.ASK -> OpenApkDialogFragment.show(file, this)
        }
    }

    override fun installApk(file: FileItem) {
        val path = file.path
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!path.isArchivePath) path.fileProviderUri else null
        } else {
            // PackageInstaller only supports file URI before N.
            if (path.isLinuxPath) Uri.fromFile(path.toFile()) else null
        }
        if (uri != null) {
            startActivitySafe(uri.createInstallPackageIntent())
        } else {
            FileJobService.installApk(path, requireContext())
        }
    }

    override fun viewApk(file: FileItem) {
        navigateTo(file.listablePath)
    }

    override fun openFileWith(file: FileItem) {
        openFileWithIntent(file, true)
    }

    private fun openFileWithIntent(file: FileItem, withChooser: Boolean) {
        val path = file.path
        val mimeType = file.mimeType
        if (path.isArchivePath) {
            FileJobService.open(path, mimeType, withChooser, requireContext())
        } else {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply {
                    extraPath = path
                    maybeAddImageViewerActivityExtras(this, path, mimeType)
                }
                .let {
                    if (withChooser) {
                        it.withChooser(
                            EditFileActivity::class.createIntent()
                                .putArgs(EditFileActivity.Args(path, mimeType)),
                            OpenFileAsDialogActivity::class.createIntent()
                                .putArgs(OpenFileAsDialogFragment.Args(path))
                        )
                    } else {
                        it
                    }
                }
            startActivitySafe(intent)
        }
    }

    private fun maybeAddImageViewerActivityExtras(intent: Intent, path: Path, mimeType: MimeType) {
        if (!mimeType.isImage) {
            return
        }
        var paths = mutableListOf<Path>()
        // We need the ordered list from our adapter instead of the list from FileListLiveData.
        for (index in 0..<adapter.itemCount) {
            val file = adapter.getItem(index)
            val filePath = file.path
            if (file.mimeType.isImage || filePath == path) {
                paths.add(filePath)
            }
        }
        var position = paths.indexOf(path)
        if (position == -1) {
            return
        }
        // HACK: Don't send too many paths to avoid TransactionTooLargeException.
        if (paths.size > IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX) {
            val start = (position - IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX / 2)
                .coerceIn(0, paths.size - IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX)
            paths = paths.subList(start, start + IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX)
            position -= start
        }
        ImageViewerActivity.putExtras(intent, paths, position)
    }

    override fun cutFile(file: FileItem) {
        cutFiles(fileItemSetOf(file))
    }

    override fun copyFile(file: FileItem) {
        copyFiles(fileItemSetOf(file))
    }

    override fun confirmDeleteFile(file: FileItem) {
        confirmDeleteFiles(fileItemSetOf(file))
    }

    override fun showRenameFileDialog(file: FileItem) {
        RenameFileDialogFragment.show(file, this)
    }

    override fun hasFileWithName(name: String): Boolean = getFileWithName(name) != null

    private fun getFileWithName(name: String): FileItem? {
        val fileListData = viewModel.fileListStateful
        if (fileListData !is Success) {
            return null
        }
        return fileListData.value.find { it.name == name }
    }

    override fun renameFile(file: FileItem, newName: String) {
        FileJobService.rename(file.path, newName, requireContext())
        viewModel.selectFile(file, false)
    }

    override fun extractFile(file: FileItem) {
        copyFile(file.createDummyArchiveRoot())
    }

    override fun showCreateArchiveDialog(file: FileItem) {
        showCreateArchiveDialog(fileItemSetOf(file))
    }

    override fun shareFile(file: FileItem) {
        shareFile(file.path, file.mimeType)
    }

    private fun shareFile(path: Path, mimeType: MimeType) {
        shareFiles(listOf(path), listOf(mimeType))
    }

    private fun shareFiles(paths: List<Path>, mimeTypes: List<MimeType>) {
        val uris = paths.map { it.fileProviderUri }
        val intent = uris.createSendStreamIntent(mimeTypes)
            .withChooser()
        startActivitySafe(intent)
    }

    override fun copyPath(file: FileItem) {
        copyPath(file.path)
    }

    override fun addBookmark(file: FileItem) {
        addBookmark(file.path)
    }

    private fun addBookmark() {
        addBookmark(currentPath)
    }

    private fun addBookmark(path: Path) {
        BookmarkDirectories.add(BookmarkDirectory(null, path))
        showToast(R.string.file_add_bookmark_success)
    }

    override fun createShortcut(file: FileItem) {
        createShortcut(file.path, file.mimeType)
    }

    private fun createShortcut() {
        createShortcut(currentPath, MimeType.DIRECTORY)
    }

    private fun createShortcut(path: Path, mimeType: MimeType) {
        val context = requireContext()
        val isDirectory = mimeType == MimeType.DIRECTORY
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path.toString())
            .setShortLabel(path.name)
            .setIntent(
                if (isDirectory) {
                    FileListActivity.createViewIntent(path)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                } else {
                    OpenFileActivity.createIntent(path, mimeType)
                }
            )
            .setIcon(
                IconCompat.createWithResource(
                    context, if (isDirectory) {
                        R.mipmap.directory_shortcut_icon
                    } else {
                        R.mipmap.file_shortcut_icon
                    }
                )
            )
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.shortcut_created)
        }
    }

    override fun showPropertiesDialog(file: FileItem) {
        FilePropertiesDialogFragment.show(file, this)
    }

    private fun showCreateFileDialog() {
        CreateFileDialogFragment.show(this)
    }

    override fun createFile(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, false, requireContext())
    }

    private fun showCreateDirectoryDialog() {
        CreateDirectoryDialogFragment.show(this)
    }

    override fun createDirectory(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, true, requireContext())
    }

    override val currentPath: Path
        get() = viewModel.currentPath

    override val isFileManagerPlusHomeAvailable: Boolean
        get() =
            Settings.INTERFACE_STYLE.valueCompat == InterfaceStyle.FILE_MANAGER_PLUS &&
                isFileManagerPlusHomeEligible &&
                viewModel.pickOptions == null

    override val isFileManagerPlusHomeVisible: Boolean
        get() = isFileManagerPlusHomeDisplayed

    override fun navigateHome() {
        if (
            appliedInterfaceStyle != InterfaceStyle.FILE_MANAGER_PLUS ||
            !isFileManagerPlusHomeEligible ||
            viewModel.pickOptions != null
        ) {
            return
        }
        collapseSearchView()
        viewModel.stopSearching()
        viewModel.clearSelectedFiles()
        viewModel.fileManagerPlusNavigationRoot = null
        viewModel.isFileManagerPlusHomeVisible = true
    }

    override fun navigateToRoot(path: Path) {
        collapseSearchView()
        viewModel.clearSelectedFiles()
        if (
            appliedInterfaceStyle == InterfaceStyle.FILE_MANAGER_PLUS &&
            isFileManagerPlusHomeEligible
        ) {
            viewModel.fileManagerPlusNavigationRoot = path
            viewModel.isFileManagerPlusHomeVisible = false
        }
        viewModel.resetTo(path)
    }

    override fun navigateToDefaultRoot() {
        navigateToRoot(Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat)
    }

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {
        viewModel.currentPathLiveData.observe(owner, observer)
    }

    override fun observeFileManagerPlusHomeVisibility(
        owner: LifecycleOwner,
        observer: (Boolean) -> Unit
    ) {
        viewModel.fileManagerPlusHomeVisibleLiveData.observe(owner, observer)
    }

    override fun closeNavigationDrawer() {
        binding.drawerLayout?.closeDrawer(GravityCompat.START)
    }

    private fun ensureStorageAccess() {
        if (viewModel.isStorageAccessRequested) {
            return
        }
        if (isAllFilesAccessInformationVisible()) {
            // FragmentManager may have restored the dialog after process death while the
            // ViewModel request flag returned to its default value.
            viewModel.isStorageAccessRequested = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (
                InitialIndexingCoordinator.shouldShowAllFilesAccessInformation() ||
                !FileIndexingStorageAccess.isGranted(requireContext())
            ) {
                viewModel.isStorageAccessRequested = true
                ShowRequestAllFilesAccessRationaleDialogFragment.show(this)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                if (shouldShowRequestPermissionRationale(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )) {
                    ShowRequestStoragePermissionRationaleDialogFragment.show(this)
                } else {
                    requestStoragePermission()
                }
                viewModel.isStorageAccessRequested = true
            }
        }
    }

    internal fun isStorageAccessRequestInProgress(): Boolean =
        viewModel.isStorageAccessRequested

    private fun isPermissionRequestInProgress(): Boolean =
        viewModel.isStorageAccessRequested ||
            viewModel.isNotificationPermissionRequested

    private fun isAllFilesAccessInformationVisible(): Boolean =
        childFragmentManager.findFragmentByTag(
            ShowRequestAllFilesAccessRationaleDialogFragment.TAG
        ) != null

    private fun notifyPermissionOrchestrationSettled() {
        if (!isPermissionRequestInProgress()) {
            (activity as? FileListActivity)?.onPermissionOrchestrationSettled()
        }
    }

    override fun onShowRequestAllFilesAccessRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            if (FileIndexingStorageAccess.isGranted(requireContext())) {
                viewModel.isStorageAccessRequested = false
            } else {
                requestAllFilesAccess()
            }
        } else {
            viewModel.isStorageAccessRequested = false
        }
    }

    override fun onShowRequestAllFilesAccessRationaleDismissed() {
        // Persist this only after the window is actually gone. Root admission reads the same
        // durable flag, so even pre-granted access cannot surface a root prompt underneath it.
        InitialIndexingCoordinator.markAllFilesAccessInformationShown()
        viewModel.isStorageAccessRequested = false
        if (FileIndexingStorageAccess.isGranted(requireContext())) {
            refresh()
        }
        notifyPermissionOrchestrationSettled()
    }

    private fun requestAllFilesAccess() {
        requestAllFilesAccessLauncher.launch(Unit)
    }

    private fun onRequestAllFilesAccessResult(isGranted: Boolean) {
        viewModel.isStorageAccessRequested = false
        if (isGranted) {
            refresh()
        }
        notifyPermissionOrchestrationSettled()
    }

    override fun onShowRequestStoragePermissionRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestStoragePermission()
        } else {
            viewModel.isStorageAccessRequested = false
            notifyPermissionOrchestrationSettled()
        }
    }

    private fun requestStoragePermission() {
        requestStoragePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun onRequestStoragePermissionResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isStorageAccessRequested = false
            refresh()
            ensureNotificationPermission()
            notifyPermissionOrchestrationSettled()
        } else if (shouldShowRequestPermissionRationale(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )) {
            ShowRequestStoragePermissionRationaleDialogFragment.show(this)
        } else {
            ShowRequestStoragePermissionInSettingsRationaleDialogFragment.show(this)
        }
    }

    override fun onShowRequestStoragePermissionInSettingsRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestStoragePermissionInSettings()
        } else {
            viewModel.isStorageAccessRequested = false
            notifyPermissionOrchestrationSettled()
        }
    }

    private fun requestStoragePermissionInSettings() {
        requestStoragePermissionInSettingsLauncher.launch(Unit)
    }

    private fun onRequestStoragePermissionInSettingsResult(isGranted: Boolean) {
        viewModel.isStorageAccessRequested = false
        if (isGranted) {
            refresh()
            ensureNotificationPermission()
            notifyPermissionOrchestrationSettled()
        }
    }

    private fun ensureNotificationPermission() {
        if (
            viewModel.isNotificationPermissionRequested ||
            viewModel.isNotificationPermissionHandled
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )) {
                    ShowRequestNotificationPermissionRationaleDialogFragment.show(this)
                } else {
                    requestNotificationPermission()
                }
                viewModel.isNotificationPermissionRequested = true
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestNotificationPermission()
        } else {
            viewModel.isNotificationPermissionRequested = false
            viewModel.isNotificationPermissionHandled = true
            notifyPermissionOrchestrationSettled()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNotificationPermission() {
        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onRequestNotificationPermissionResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isNotificationPermissionRequested = false
            viewModel.isNotificationPermissionHandled = true
            notifyPermissionOrchestrationSettled()
        } else if (shouldShowRequestPermissionRationale(
            android.Manifest.permission.POST_NOTIFICATIONS
        )) {
            ShowRequestNotificationPermissionRationaleDialogFragment.show(this)
        } else {
            ShowRequestNotificationPermissionInSettingsRationaleDialogFragment.show(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionInSettingsRationaleResult(
        shouldRequest: Boolean
    ) {
        if (shouldRequest) {
            requestNotificationPermissionInSettings()
        } else {
            viewModel.isNotificationPermissionRequested = false
            viewModel.isNotificationPermissionHandled = true
            notifyPermissionOrchestrationSettled()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNotificationPermissionInSettings() {
        requestNotificationPermissionInSettingsLauncher.launch(Unit)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onRequestNotificationPermissionInSettingsResult(
        @Suppress("UNUSED_PARAMETER") isGranted: Boolean
    ) {
        viewModel.isNotificationPermissionRequested = false
        viewModel.isNotificationPermissionHandled = true
        notifyPermissionOrchestrationSettled()
    }

    companion object {
        private const val ACTION_VIEW_DOWNLOADS =
            "com.blitzfiles.app.intent.action.VIEW_DOWNLOADS"

        private const val INDEXING_TERMINAL_BANNER_MILLIS = 3_500L

        private const val IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX = 1000
        private val NO_NUL_INPUT_FILTER = InputFilter { source, start, end, _, _, _ ->
            val insertedText = source.subSequence(start, end)
            if (insertedText.none { character -> character == '\u0000' }) {
                null
            } else {
                insertedText.filterNot { character -> character == '\u0000' }
            }
        }
    }

    private class RequestAllFilesAccessContract : ActivityResultContract<Unit, Boolean>() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun createIntent(context: Context, input: Unit): Intent {
            val applicationIntent =
                Environment::class.createManageAppAllFilesAccessPermissionIntent(
                    context.packageName
                )
            return if (applicationIntent.resolveActivity(context.packageManager) != null) {
                applicationIntent
            } else {
                Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        }

        @RequiresApi(Build.VERSION_CODES.R)
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            Environment.isExternalStorageManager()
    }

    private class RequestPermissionInSettingsContract(private val permissionName: String)
        : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            application.checkSelfPermissionCompat(permissionName) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class Binding private constructor(
        val root: View,
        val drawerLayout: DrawerLayout? = null,
        val persistentDrawerLayout: PersistentDrawerLayout? = null,
        val persistentBarLayout: PersistentBarLayout,
        val appBarLayout: CoordinatorAppBarLayout,
        val toolbar: Toolbar,
        val overlayToolbar: Toolbar,
        val breadcrumbLayout: BreadcrumbLayout,
        val indexingProgressBanner: View,
        val indexingProgressIndicator: View,
        val indexingProgressText: TextView,
        val indexingProgressPath: TextView,
        val contentLayout: ViewGroup,
        val progress: ProgressBar,
        val errorText: TextView,
        val emptyView: View,
        val swipeRefreshLayout: SwipeRefreshLayout,
        val recyclerView: RecyclerView,
        val fileManagerPlusHomeRecyclerView: RecyclerView,
        val bottomBarLayout: ViewGroup,
        val bottomToolbar: Toolbar,
        val bottomCreateFileNameEdit: EditText,
        val speedDialView: SpeedDialView
    ) {
        companion object {
            fun inflate(
                inflater: LayoutInflater,
                root: ViewGroup?,
                attachToRoot: Boolean
            ): Binding {
                val binding = FileListFragmentBinding.inflate(inflater, root, attachToRoot)
                val bindingRoot = binding.root
                val includeBinding = FileListFragmentIncludeBinding.bind(bindingRoot)
                val appBarBinding = FileListFragmentAppBarIncludeBinding.bind(bindingRoot)
                val contentBinding = FileListFragmentContentIncludeBinding.bind(bindingRoot)
                val bottomBarBinding = FileListFragmentBottomBarIncludeBinding.bind(bindingRoot)
                val speedDialBinding = FileListFragmentSpeedDialIncludeBinding.bind(bindingRoot)
                return Binding(
                    bindingRoot, includeBinding.drawerLayout, includeBinding.persistentDrawerLayout,
                    includeBinding.persistentBarLayout, appBarBinding.appBarLayout,
                    appBarBinding.toolbar, appBarBinding.overlayToolbar,
                    appBarBinding.breadcrumbLayout, appBarBinding.indexingProgressBanner,
                    appBarBinding.indexingProgressIndicator, appBarBinding.indexingProgressText,
                    appBarBinding.indexingProgressPath, contentBinding.contentLayout,
                    contentBinding.progress, contentBinding.errorText, contentBinding.emptyView,
                    contentBinding.swipeRefreshLayout, contentBinding.recyclerView,
                    contentBinding.fileManagerPlusHomeRecyclerView,
                    bottomBarBinding.bottomBarLayout, bottomBarBinding.bottomToolbar,
                    bottomBarBinding.bottomCreateFileNameEdit, speedDialBinding.speedDialView
                )
            }
        }
    }

    private class MenuBinding private constructor(
        val menu: Menu,
        val searchItem: MenuItem,
        val viewSortItem: MenuItem,
        val viewListItem: MenuItem,
        val viewGridItem: MenuItem,
        val sortByNameItem: MenuItem,
        val sortByTypeItem: MenuItem,
        val sortBySizeItem: MenuItem,
        val sortByLastModifiedItem: MenuItem,
        val sortOrderAscendingItem: MenuItem,
        val sortDirectoriesFirstItem: MenuItem,
        val viewSortPathSpecificItem: MenuItem,
        val selectAllItem: MenuItem,
        val showHiddenFilesItem: MenuItem
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.file_list, menu)
                return MenuBinding(
                    menu, menu.findItem(R.id.action_search), menu.findItem(R.id.action_view_sort),
                    menu.findItem(R.id.action_view_list), menu.findItem(R.id.action_view_grid),
                    menu.findItem(R.id.action_sort_by_name),
                    menu.findItem(R.id.action_sort_by_type),
                    menu.findItem(R.id.action_sort_by_size),
                    menu.findItem(R.id.action_sort_by_last_modified),
                    menu.findItem(R.id.action_sort_order_ascending),
                    menu.findItem(R.id.action_sort_directories_first),
                    menu.findItem(R.id.action_view_sort_path_specific),
                    menu.findItem(R.id.action_select_all),
                    menu.findItem(R.id.action_show_hidden_files)
                )
            }
        }
    }
}

private fun String.toValidSearchQuery(): String {
    val sanitized = filterNot { character -> character == '\u0000' }
        .take(SearchRequest.MAX_QUERY_LENGTH)
    return if (sanitized.lastOrNull()?.isHighSurrogate() == true) {
        sanitized.dropLast(1)
    } else {
        sanitized
    }
}
