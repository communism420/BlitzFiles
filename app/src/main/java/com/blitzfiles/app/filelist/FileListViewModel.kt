/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.os.Parcelable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import java8.nio.file.Path
import com.blitzfiles.app.app.application
import com.blitzfiles.app.file.FileItem
import com.blitzfiles.app.filejob.DeletedPathPrefixes
import com.blitzfiles.app.filejob.FileDeletionRecovery
import com.blitzfiles.app.filejob.FileDeletionStore
import com.blitzfiles.app.filelist.FileSortOptions.By
import com.blitzfiles.app.filelist.FileSortOptions.Order
import com.blitzfiles.app.provider.archive.archiveRefresh
import com.blitzfiles.app.provider.archive.isArchivePath
import com.blitzfiles.app.search.toEffectiveSearchQuery
import com.blitzfiles.app.util.CloseableLiveData
import com.blitzfiles.app.util.Stateful
import com.blitzfiles.app.util.valueCompat
import java.io.Closeable
import kotlinx.coroutines.launch

// TODO: Use SavedStateHandle to save state.
class FileListViewModel : ViewModel() {
    private val trailLiveData = TrailLiveData()
    val hasTrail: Boolean
        get() = trailLiveData.value != null
    val pendingState: Parcelable?
        get() = trailLiveData.valueCompat.pendingState

    fun navigateTo(lastState: Parcelable, path: Path) = trailLiveData.navigateTo(lastState, path)

    fun resetTo(path: Path) = trailLiveData.resetTo(path)

    fun navigateUp(): Boolean = trailLiveData.navigateUp()

    val currentPathLiveData = trailLiveData.map { it.currentPath }
    val currentPath: Path
        get() = currentPathLiveData.valueCompat

    val fileManagerPlusHomeVisibleLiveData = MutableLiveData(false)
    var isFileManagerPlusHomeVisible: Boolean
        get() = fileManagerPlusHomeVisibleLiveData.valueCompat
        set(value) {
            if (fileManagerPlusHomeVisibleLiveData.valueCompat != value) {
                fileManagerPlusHomeVisibleLiveData.value = value
            }
        }

    /**
     * The path at which Back returns to the File Manager Plus home instead of walking above the
     * storage or shortcut selected on that home screen.
     */
    var fileManagerPlusNavigationRoot: Path? = null

    private val _searchStateLiveData = MutableLiveData(SearchState(false, ""))
    val searchStateLiveData: LiveData<SearchState> = _searchStateLiveData
    val searchState: SearchState
        get() = _searchStateLiveData.valueCompat

    fun search(query: String) {
        val effectiveQuery = query.toEffectiveSearchQuery()
        if (effectiveQuery.isEmpty()) {
            stopSearching()
            return
        }
        val searchState = _searchStateLiveData.valueCompat
        if (searchState.isSearching && searchState.query == effectiveQuery) {
            return
        }
        _searchStateLiveData.value = SearchState(true, effectiveQuery)
    }

    fun submitSearch(query: String) {
        val effectiveQuery = query.toEffectiveSearchQuery()
        if (effectiveQuery.isEmpty()) {
            stopSearching()
            return
        }
        val searchState = _searchStateLiveData.valueCompat
        if (searchState.isSearching && searchState.query == effectiveQuery) {
            _fileListLiveData.searchNow()
            return
        }
        _searchStateLiveData.value = SearchState(
            isSearching = true,
            query = effectiveQuery,
            debounceLiveWalk = false
        )
    }

    fun stopSearching() {
        val searchState = _searchStateLiveData.valueCompat
        if (!searchState.isSearching) {
            return
        }
        _searchStateLiveData.value = SearchState(false, "")
    }

    private val _fileListLiveData =
        FileListSwitchMapLiveData(currentPathLiveData, _searchStateLiveData)

    init {
        FileDeletionRecovery.retry(application)
        _fileListLiveData.updateDeletedPaths(FileDeletionStore.state.value.deletedPaths)
        viewModelScope.launch {
            FileDeletionStore.state.collect { deletionState ->
                _fileListLiveData.updateDeletedPaths(deletionState.deletedPaths)
            }
        }
    }

    val fileListLiveData: LiveData<Stateful<List<FileItem>>>
        get() = _fileListLiveData
    val fileListStateful: Stateful<List<FileItem>>
        get() = _fileListLiveData.valueCompat
    val isSearchResultTruncated: Boolean
        get() = _fileListLiveData.isSearchResultTruncated

    fun reload() {
        val path = currentPath
        if (path.isArchivePath) {
            path.archiveRefresh()
        }
        _fileListLiveData.reload()
    }

    val searchViewExpandedLiveData = MutableLiveData(false)
    var isSearchViewExpanded: Boolean
        get() = searchViewExpandedLiveData.valueCompat
        set(value) {
            if (searchViewExpandedLiveData.valueCompat == value) {
                return
            }
            searchViewExpandedLiveData.value = value
        }

    private val _searchViewQueryLiveData = MutableLiveData("")
    var searchViewQuery: String
        get() = _searchViewQueryLiveData.valueCompat
        set(value) {
            if (_searchViewQueryLiveData.valueCompat == value) {
                return
            }
            _searchViewQueryLiveData.value = value
        }

    val breadcrumbLiveData: LiveData<BreadcrumbData> = BreadcrumbLiveData(trailLiveData)
    val canNavigateUpBreadcrumb: Boolean
        get() = breadcrumbLiveData.value.hasNavigableParent

    private val _viewTypeLiveData = FileViewTypeLiveData(currentPathLiveData)
    val viewTypeLiveData: LiveData<FileViewType> = _viewTypeLiveData
    var viewType: FileViewType
        get() = _viewTypeLiveData.valueCompat
        set(value) {
            _viewTypeLiveData.putValue(value)
        }

    private val _sortOptionsLiveData = FileSortOptionsLiveData(currentPathLiveData)
    val sortOptionsLiveData: LiveData<FileSortOptions> = _sortOptionsLiveData
    val sortOptions: FileSortOptions
        get() = _sortOptionsLiveData.valueCompat

    fun setSortBy(by: By) = _sortOptionsLiveData.putBy(by)

    fun setSortOrder(order: Order) = _sortOptionsLiveData.putOrder(order)

    fun setSortDirectoriesFirst(isDirectoriesFirst: Boolean) =
        _sortOptionsLiveData.putIsDirectoriesFirst(isDirectoriesFirst)

    private val _viewSortPathSpecificLiveData =
        FileViewSortPathSpecificLiveData(currentPathLiveData)
    val viewSortPathSpecificLiveData: LiveData<Boolean>
        get() = _viewSortPathSpecificLiveData
    var isViewSortPathSpecific: Boolean
        get() = _viewSortPathSpecificLiveData.valueCompat
        set(value) {
            _viewSortPathSpecificLiveData.putValue(value)
        }

    private val _pickOptionsLiveData = MutableLiveData<PickOptions?>()
    val pickOptionsLiveData: LiveData<PickOptions?>
        get() = _pickOptionsLiveData
    var pickOptions: PickOptions?
        get() = _pickOptionsLiveData.value
        set(value) {
            _pickOptionsLiveData.value = value
        }

    var isCreateFileNameEditInitialized: Boolean = false

    private val _selectedFilesLiveData = MutableLiveData(fileItemSetOf())
    val selectedFilesLiveData: LiveData<FileItemSet>
        get() = _selectedFilesLiveData
    val selectedFiles: FileItemSet
        get() = _selectedFilesLiveData.valueCompat

    fun selectFile(file: FileItem, selected: Boolean) {
        selectFiles(fileItemSetOf(file), selected)
    }

    fun selectFiles(files: FileItemSet, selected: Boolean) {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles === files) {
            if (!selected && selectedFiles.isNotEmpty()) {
                selectedFiles.clear()
                _selectedFilesLiveData.value = selectedFiles
            }
            return
        }
        var changed = false
        for (file in files) {
            changed = changed or if (selected) {
                selectedFiles.add(file)
            } else {
                selectedFiles.remove(file)
            }
        }
        if (changed) {
            _selectedFilesLiveData.value = selectedFiles
        }
    }

    fun replaceSelectedFiles(files: FileItemSet) {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles == files) {
            return
        }
        selectedFiles.clear()
        selectedFiles.addAll(files)
        _selectedFilesLiveData.value = selectedFiles
    }

    fun clearSelectedFiles() {
        val selectedFiles = _selectedFilesLiveData.valueCompat
        if (selectedFiles.isEmpty()) {
            return
        }
        selectedFiles.clear()
        _selectedFilesLiveData.value = selectedFiles
    }

    val pasteStateLiveData: LiveData<PasteState> = _pasteStateLiveData
    val pasteState: PasteState
        get() = _pasteStateLiveData.valueCompat

    fun addToPasteState(copy: Boolean, files: FileItemSet) {
        val pasteState = _pasteStateLiveData.valueCompat
        var changed = false
        if (pasteState.copy != copy) {
            changed = pasteState.files.isNotEmpty()
            pasteState.files.clear()
            pasteState.copy = copy
        }
        changed = changed or pasteState.files.addAll(files)
        if (changed) {
            _pasteStateLiveData.value = pasteState
        }
    }

    fun clearPasteState() {
        val pasteState = _pasteStateLiveData.valueCompat
        if (pasteState.files.isEmpty()) {
            return
        }
        pasteState.files.clear()
        _pasteStateLiveData.value = pasteState
    }

    private val _isRequestingStorageAccessLiveData = MutableLiveData(false)
    var isStorageAccessRequested: Boolean
        get() = _isRequestingStorageAccessLiveData.valueCompat
        set(value) {
            _isRequestingStorageAccessLiveData.value = value
        }

    private val _isRequestingNotificationPermissionLiveData = MutableLiveData(false)
    var isNotificationPermissionRequested: Boolean
        get() = _isRequestingNotificationPermissionLiveData.valueCompat
        set(value) {
            _isRequestingNotificationPermissionLiveData.value = value
        }
    var isNotificationPermissionHandled = false

    override fun onCleared() {
        _fileListLiveData.close()
    }

    companion object {
        private val _pasteStateLiveData = MutableLiveData(PasteState())
    }

    private class FileListSwitchMapLiveData(
        private val pathLiveData: LiveData<Path>,
        private val searchStateLiveData: LiveData<SearchState>
    ) : MediatorLiveData<Stateful<List<FileItem>>>(), Closeable {
        private var liveData: CloseableLiveData<Stateful<List<FileItem>>>? = null
        private var liveDataPath: Path? = null
        private var deletedPaths = DeletedPathPrefixes.EMPTY

        init {
            addSource(pathLiveData) { updateSource(forceDebounceLiveWalk = true) }
            addSource(searchStateLiveData) { updateSource() }
        }

        private fun updateSource(forceDebounceLiveWalk: Boolean = false) {
            val previousPath = liveDataPath
            val previousFiles = liveData?.value?.value ?: value?.value
            liveData?.let {
                removeSource(it)
                it.close()
            }
            val path = pathLiveData.valueCompat
            val searchState = searchStateLiveData.valueCompat
            val liveData = if (searchState.isSearching) {
                val provisionalFiles = if (previousPath == path && previousFiles != null) {
                    filterIncrementalSearchPreview(previousFiles, searchState.query) { file ->
                        file.name
                    }
                } else {
                    emptyList()
                }
                SearchFileListLiveData(
                    path = path,
                    query = searchState.query,
                    initialFiles = provisionalFiles,
                    initialDebounceLiveWalk =
                        forceDebounceLiveWalk || searchState.debounceLiveWalk
                )
            } else {
                FileListLiveData(path)
            }
            when (liveData) {
                is FileListLiveData -> liveData.updateDeletedPaths(deletedPaths)
                is SearchFileListLiveData -> liveData.updateDeletedPaths(deletedPaths)
            }
            this.liveData = liveData
            liveDataPath = path
            addSource(liveData) { value = it }
        }

        fun reload() {
            when (val liveData = liveData) {
                is FileListLiveData -> liveData.loadValue()
                is SearchFileListLiveData -> liveData.loadValue(debounceLiveWalk = false)
            }
        }

        fun searchNow() {
            (liveData as? SearchFileListLiveData)?.loadValue(debounceLiveWalk = false)
        }

        fun updateDeletedPaths(deletedPaths: DeletedPathPrefixes) {
            this.deletedPaths = deletedPaths
            when (val liveData = liveData) {
                is FileListLiveData -> liveData.updateDeletedPaths(deletedPaths)
                is SearchFileListLiveData -> liveData.updateDeletedPaths(deletedPaths)
            }
            value = value?.withoutDeletedPaths(deletedPaths)
        }

        val isSearchResultTruncated: Boolean
            get() = (liveData as? SearchFileListLiveData)?.isResultTruncated == true

        override fun close() {
            liveData?.let {
                removeSource(it)
                it.close()
                this.liveData = null
                liveDataPath = null
            }
        }
    }
}
