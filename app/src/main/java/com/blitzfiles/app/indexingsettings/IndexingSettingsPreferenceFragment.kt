/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexingsettings

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.blitzfiles.app.R
import com.blitzfiles.app.databinding.IndexExclusionDialogBinding
import com.blitzfiles.app.databinding.IndexRootDialogBinding
import com.blitzfiles.app.file.asFileSize
import com.blitzfiles.app.file.formatShort
import com.blitzfiles.app.indexing.FileIndexingController
import com.blitzfiles.app.indexing.InitialIndexingCoordinator
import com.blitzfiles.app.indexing.IndexRootAccessPolicy
import com.blitzfiles.app.ui.PreferenceFragmentCompat
import com.blitzfiles.app.util.hideTextInputLayoutErrorOnTextChange
import com.blitzfiles.app.util.layoutInflater
import com.blitzfiles.app.util.showToast
import com.blitzfiles.search.domain.indexer.IndexSafetyPolicy
import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexExclusion
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java8.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IndexingSettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var statusPreference: Preference
    private lateinit var statisticsPreference: Preference
    private lateinit var startPreference: Preference
    private lateinit var pausePreference: Preference
    private lateinit var resumePreference: Preference
    private lateinit var cancelPreference: Preference
    private lateinit var rootsCategory: PreferenceCategory
    private lateinit var exclusionsCategory: PreferenceCategory

    private var latestSnapshot: FileIndexingController.Snapshot? = null
    private var refreshJob: Job? = null
    private var operationInProgress = false

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        val statusCategory = PreferenceCategory(context).apply {
            title = getString(R.string.indexing_status_category)
        }
        screen.addPreference(statusCategory)
        statusPreference = Preference(context).apply {
            key = KEY_STATUS
            title = getString(R.string.indexing_status_loading)
            isSelectable = false
        }
        statusCategory.addPreference(statusPreference)
        statisticsPreference = Preference(context).apply {
            key = KEY_STATISTICS
            title = getString(R.string.indexing_statistics_details_title)
            isSelectable = false
            isVisible = false
        }
        statusCategory.addPreference(statisticsPreference)

        val controlsCategory = PreferenceCategory(context).apply {
            title = getString(R.string.indexing_controls_category)
        }
        screen.addPreference(controlsCategory)
        startPreference = actionPreference(
            context,
            KEY_START,
            R.string.indexing_start_full_scan,
            R.string.indexing_start_full_scan_summary,
            ::startFullScan
        )
        pausePreference = actionPreference(
            context,
            KEY_PAUSE,
            R.string.indexing_pause_scan,
            action = { runServiceCommand(FileIndexingController::pause) }
        )
        resumePreference = actionPreference(
            context,
            KEY_RESUME,
            R.string.indexing_resume_scan,
            action = { runIndexingLaunchCommand(FileIndexingController::resume) }
        )
        cancelPreference = actionPreference(
            context,
            KEY_CANCEL,
            R.string.indexing_cancel_scan,
            action = { runServiceCommand(FileIndexingController::cancel) }
        )
        controlsCategory.addPreference(startPreference)
        controlsCategory.addPreference(pausePreference)
        controlsCategory.addPreference(resumePreference)
        controlsCategory.addPreference(cancelPreference)

        rootsCategory = PreferenceCategory(context).apply {
            title = getString(R.string.indexing_roots_category)
        }
        screen.addPreference(rootsCategory)
        exclusionsCategory = PreferenceCategory(context).apply {
            title = getString(R.string.indexing_exclusions_category)
        }
        screen.addPreference(exclusionsCategory)
        renderSnapshot(null)
    }

    override fun onStart() {
        super.onStart()
        startRefreshing()
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun actionPreference(
        context: Context,
        key: String,
        titleRes: Int,
        summaryRes: Int? = null,
        action: () -> Unit
    ): Preference =
        Preference(context).apply {
            this.key = key
            isPersistent = false
            title = getString(titleRes)
            summaryRes?.let { summary = getString(it) }
            setOnPreferenceClickListener {
                action()
                true
            }
        }

    private fun startRefreshing() {
        if (refreshJob?.isActive == true) {
            return
        }
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshSnapshot()
                val isActiveScan = latestSnapshot?.roots.orEmpty().any { it.isScanActive() }
                delay(if (isActiveScan) ACTIVE_REFRESH_MILLIS else IDLE_REFRESH_MILLIS)
            }
        }
    }

    private suspend fun refreshSnapshot() {
        val context = requireContext().applicationContext
        try {
            val snapshot = withContext(Dispatchers.IO) {
                FileIndexingController.getSnapshot(context)
            }
            latestSnapshot = snapshot
            renderSnapshot(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (latestSnapshot == null) {
                statusPreference.title = getString(R.string.indexing_status_error)
                statusPreference.summary = null
            }
        }
    }

    private fun renderSnapshot(snapshot: FileIndexingController.Snapshot?) {
        val roots = snapshot?.roots.orEmpty()
        val exclusions = snapshot?.exclusions.orEmpty()
        val running = roots.any { it.lastScanStatus == IndexScanStatus.RUNNING }
        val paused = roots.any { it.lastScanStatus == IndexScanStatus.PAUSED }
        val active = running || paused

        startPreference.isEnabled = !operationInProgress && !active &&
            roots.any(IndexRoot::isEnabled)
        pausePreference.isVisible = running
        pausePreference.isEnabled = !operationInProgress
        resumePreference.isVisible = paused && !running
        resumePreference.isEnabled = !operationInProgress
        cancelPreference.isVisible = active
        cancelPreference.isEnabled = !operationInProgress

        renderStatus(snapshot)
        renderRoots(roots, active)
        renderExclusions(roots, exclusions, active)
    }

    private fun renderStatus(snapshot: FileIndexingController.Snapshot?) {
        if (snapshot == null) {
            statusPreference.title = getString(R.string.indexing_status_loading)
            statusPreference.summary = null
            statisticsPreference.isVisible = false
            return
        }
        val statistics = snapshot.statistics
        statisticsPreference.isVisible = true
        statisticsPreference.summary = listOf(
            getString(
                R.string.indexing_statistics_coverage_format,
                statistics.enabledRootCount,
                statistics.rootCount,
                statistics.exclusionCount
            ),
            getString(
                R.string.indexing_statistics_entries_format,
                statistics.rootRequiredEntryCount,
                statistics.hiddenEntryCount,
                statistics.symbolicLinkCount
            ),
            getString(
                R.string.indexing_statistics_storage_format,
                statistics.databaseSizeBytes.asFileSize().formatHumanReadable(requireContext())
            )
        ).joinToString(separator = "\n")
        statusPreference.title = if (statistics.entryCount == 0L) {
            getString(R.string.indexing_status_empty)
        } else {
            getString(
                R.string.indexing_status_format,
                statistics.entryCount,
                statistics.fileCount,
                statistics.directoryCount,
                statistics.totalFileSizeBytes.asFileSize().formatHumanReadable(requireContext())
            )
        }
        val scanStatus = snapshot.roots
            .firstOrNull { it.lastScanStatus == IndexScanStatus.RUNNING }
            ?.lastScanStatus
            ?: snapshot.roots.firstOrNull { it.lastScanStatus == IndexScanStatus.PAUSED }
                ?.lastScanStatus
            ?: snapshot.roots.maxByOrNull { it.lastScanStartedAtMillis ?: 0L }
                ?.lastScanStatus
        statusPreference.summary = listOfNotNull(
            statistics.lastIndexedAtMillis?.let {
                getString(
                    R.string.indexing_status_last_update_format,
                    Instant.ofEpochMilli(it).formatShort(requireContext())
                )
            },
            scanStatus?.let {
                getString(R.string.indexing_scan_status_format, getScanStatusText(it))
            }
        ).joinToString(separator = "\n").ifEmpty { null }
    }

    private fun renderRoots(roots: List<IndexRoot>, scanActive: Boolean) {
        val context = requireContext()
        rootsCategory.removeAll()
        rootsCategory.addPreference(
            actionPreference(
                context,
                KEY_ADD_ROOT,
                R.string.indexing_add_root,
                action = { showRootDialog(null) }
            ).apply {
                isEnabled = !operationInProgress && !scanActive
            }
        )
        if (roots.isEmpty()) {
            rootsCategory.addPreference(
                messagePreference(context, KEY_NO_ROOTS, R.string.indexing_no_roots)
            )
            return
        }
        roots.forEach { root ->
            rootsCategory.addPreference(
                Preference(context).apply {
                    key = "index_root_${root.id ?: root.path.hashCode()}"
                    isPersistent = false
                    title = root.displayName
                    summary = listOfNotNull(
                        getString(
                            R.string.indexing_root_summary_format,
                            root.path,
                            getString(
                                if (root.accessMode == IndexAccessMode.ROOT) {
                                    R.string.indexing_access_root
                                } else {
                                    R.string.indexing_access_standard
                                }
                            ),
                            getString(
                                if (root.isEnabled) {
                                    R.string.indexing_enabled
                                } else {
                                    R.string.indexing_disabled
                                }
                            )
                        ),
                        getString(
                            R.string.indexing_scan_status_format,
                            getScanStatusText(root.lastScanStatus)
                        ),
                        root.lastScanError?.take(MAX_DISPLAYED_ERROR_LENGTH)
                    ).joinToString(separator = "\n")
                    isEnabled = !operationInProgress && !scanActive
                    setOnPreferenceClickListener {
                        showRootDialog(root)
                        true
                    }
                }
            )
        }
    }

    private fun renderExclusions(
        roots: List<IndexRoot>,
        exclusions: List<IndexExclusion>,
        scanActive: Boolean
    ) {
        val context = requireContext()
        val rootNames = roots.associate { it.id to it.displayName }
        exclusionsCategory.removeAll()
        exclusionsCategory.addPreference(
            actionPreference(
                context,
                KEY_ADD_EXCLUSION,
                R.string.indexing_add_exclusion,
                action = { showExclusionDialog(roots) }
            ).apply {
                isEnabled = !operationInProgress && !scanActive && roots.isNotEmpty()
            }
        )
        IndexSafetyPolicy.protectedPathPrefixes.forEach { path ->
            exclusionsCategory.addPreference(
                Preference(context).apply {
                    key = "index_protected_${path.hashCode()}"
                    title = path
                    summary = getString(R.string.indexing_built_in_exclusion)
                    isEnabled = false
                    isSelectable = false
                }
            )
        }
        if (exclusions.isEmpty()) {
            exclusionsCategory.addPreference(
                messagePreference(
                    context,
                    KEY_NO_EXCLUSIONS,
                    R.string.indexing_no_exclusions
                )
            )
        }
        exclusions.forEach { exclusion ->
            exclusionsCategory.addPreference(
                Preference(context).apply {
                    key = "index_exclusion_${exclusion.id ?: exclusion.pathPrefix.hashCode()}"
                    isPersistent = false
                    title = exclusion.pathPrefix
                    summary = exclusion.rootId?.let { rootId ->
                        getString(
                            R.string.indexing_exclusion_summary_root_format,
                            rootNames[rootId] ?: rootId.toString()
                        )
                    } ?: getString(R.string.indexing_exclusion_summary_all)
                    isEnabled = !operationInProgress && !scanActive
                    setOnPreferenceClickListener {
                        confirmRemoveExclusion(exclusion)
                        true
                    }
                }
            )
        }
    }

    private fun messagePreference(
        context: Context,
        key: String,
        titleRes: Int
    ): Preference =
        Preference(context).apply {
            this.key = key
            title = getString(titleRes)
            isEnabled = false
            isSelectable = false
        }

    private fun startFullScan() {
        val roots = latestSnapshot?.roots.orEmpty()
        if (roots.none(IndexRoot::isEnabled)) {
            showToast(getString(R.string.indexing_no_enabled_roots))
            return
        }
        runIndexingLaunchCommand { context -> FileIndexingController.startFull(context) }
    }

    private fun runServiceCommand(command: (Context) -> Unit) {
        val context = requireContext().applicationContext
        runCatching { command(context) }
            .onFailure(::showOperationError)
        refreshSoon()
    }

    private fun runIndexingLaunchCommand(command: (Context) -> Boolean) {
        val context = requireContext().applicationContext
        runCatching { command(context) }
            .onSuccess { started ->
                if (!started) {
                    showToast(R.string.indexing_all_files_access_required)
                }
            }
            .onFailure(::showOperationError)
        refreshSoon()
    }

    private fun showRootDialog(root: IndexRoot?) {
        val binding = IndexRootDialogBinding.inflate(requireContext().layoutInflater)
        binding.rootNameEdit.setText(
            root?.displayName ?: getString(R.string.indexing_default_internal_storage)
        )
        @Suppress("DEPRECATION")
        binding.rootPathEdit.setText(
            root?.path ?: Environment.getExternalStorageDirectory().absolutePath
        )
        binding.rootPathEdit.isEnabled = root == null
        binding.rootPathLockedText.isVisible = root != null
        val automaticAccessMode = InitialIndexingCoordinator.automaticAccessMode()
        binding.rootAccessModeText.setText(
            if (automaticAccessMode == IndexAccessMode.ROOT) {
                R.string.indexing_access_root
            } else {
                R.string.indexing_access_standard
            }
        )
        binding.rootEnabledCheck.isChecked = root?.isEnabled ?: true
        binding.rootIncludeHiddenCheck.isChecked = root?.includeHidden ?: true
        binding.rootFollowSymlinksCheck.isChecked = root?.followSymbolicLinks ?: false
        binding.rootNameEdit.hideTextInputLayoutErrorOnTextChange(binding.rootNameLayout)
        binding.rootPathEdit.hideTextInputLayoutErrorOnTextChange(binding.rootPathLayout)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (root == null) {
                    R.string.indexing_root_add_title
                } else {
                    R.string.indexing_root_edit_title
                }
            )
            .setView(binding.root)
            .setPositiveButton(R.string.indexing_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (root != null) {
                    setNeutralButton(R.string.indexing_remove) { _, _ ->
                        confirmRemoveRoot(root)
                    }
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val displayName = binding.rootNameEdit.text?.toString().orEmpty().trim()
                val path = binding.rootPathEdit.text?.toString().orEmpty().trim()
                val normalizedPath = path.normalizedAbsolutePathOrNull()
                var valid = true
                if (displayName.isEmpty()) {
                    binding.rootNameLayout.error = getString(R.string.indexing_field_required)
                    valid = false
                }
                if (normalizedPath == null) {
                    binding.rootPathLayout.error =
                        getString(R.string.indexing_invalid_absolute_path)
                    valid = false
                } else if (root == null && IndexSafetyPolicy.isProtected(normalizedPath)) {
                    binding.rootPathLayout.error =
                        getString(R.string.indexing_protected_path_error)
                    valid = false
                } else if (
                    IndexRootAccessPolicy.requiresRoot(normalizedPath) &&
                    automaticAccessMode != IndexAccessMode.ROOT
                ) {
                    binding.rootPathLayout.error =
                        getString(R.string.indexing_root_requires_root_error)
                    valid = false
                }
                if (!valid) {
                    return@setOnClickListener
                }
                val accessMode = IndexRootAccessPolicy.resolve(
                    checkNotNull(normalizedPath),
                    automaticAccessMode
                )
                val isEnabled = binding.rootEnabledCheck.isChecked
                val includeHidden = binding.rootIncludeHiddenCheck.isChecked
                val followSymbolicLinks = binding.rootFollowSymlinksCheck.isChecked
                launchMutation { context ->
                    FileIndexingController.saveRoot(
                        context = context,
                        path = checkNotNull(normalizedPath),
                        displayName = displayName,
                        accessMode = accessMode,
                        isEnabled = isEnabled,
                        includeHidden = includeHidden,
                        followSymbolicLinks = followSymbolicLinks
                    )
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmRemoveRoot(root: IndexRoot) {
        val rootId = root.id ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.indexing_delete_root)
            .setMessage(getString(R.string.indexing_delete_root_confirmation, root.displayName))
            .setPositiveButton(R.string.indexing_remove) { _, _ ->
                launchMutation { context ->
                    FileIndexingController.removeRoot(context, rootId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showExclusionDialog(roots: List<IndexRoot>) {
        val selectableRoots = roots.filter { it.id != null }
        if (selectableRoots.isEmpty()) {
            showToast(getString(R.string.indexing_no_enabled_roots))
            return
        }
        val binding = IndexExclusionDialogBinding.inflate(requireContext().layoutInflater)
        val scopeLabels = listOf(getString(R.string.indexing_exclusion_all_roots)) +
            selectableRoots.map(IndexRoot::displayName)
        binding.exclusionScopeSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            scopeLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.exclusionPathEdit.hideTextInputLayoutErrorOnTextChange(
            binding.exclusionPathLayout
        )
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.indexing_exclusion_add_title)
            .setView(binding.root)
            .setPositiveButton(R.string.indexing_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val path = binding.exclusionPathEdit.text?.toString().orEmpty().trim()
                val normalizedPath = path.normalizedAbsolutePathOrNull()
                if (normalizedPath == null) {
                    binding.exclusionPathLayout.error =
                        getString(R.string.indexing_invalid_absolute_path)
                    return@setOnClickListener
                }
                if (IndexSafetyPolicy.isProtected(normalizedPath)) {
                    binding.exclusionPathLayout.error =
                        getString(R.string.indexing_protected_exclusion_exists)
                    return@setOnClickListener
                }
                val selectedPosition = binding.exclusionScopeSpinner.selectedItemPosition
                val selectedRoot = if (selectedPosition == 0) {
                    null
                } else {
                    selectableRoots[selectedPosition - 1]
                }
                if (selectedRoot != null &&
                    !Paths.get(normalizedPath).startsWith(Paths.get(selectedRoot.path))) {
                    binding.exclusionPathLayout.error =
                        getString(R.string.indexing_exclusion_outside_root)
                    return@setOnClickListener
                }
                launchMutation { context ->
                    FileIndexingController.saveExclusion(
                        context,
                        normalizedPath,
                        selectedRoot?.id
                    )
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmRemoveExclusion(exclusion: IndexExclusion) {
        val exclusionId = exclusion.id ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.indexing_delete_exclusion_title)
            .setMessage(
                getString(
                    R.string.indexing_delete_exclusion_confirmation,
                    exclusion.pathPrefix
                )
            )
            .setPositiveButton(R.string.indexing_remove) { _, _ ->
                launchMutation { context ->
                    FileIndexingController.removeExclusion(context, exclusionId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchMutation(operation: suspend (Context) -> Unit) {
        if (operationInProgress) {
            return
        }
        operationInProgress = true
        renderSnapshot(latestSnapshot)
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    operation(context)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    error
                }
            }
            operationInProgress = false
            error?.let(::showOperationError)
            refreshSnapshot()
        }
    }

    private fun refreshSoon() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(COMMAND_REFRESH_DELAY_MILLIS)
            refreshSnapshot()
        }
    }

    private fun showOperationError(error: Throwable) {
        showToast(
            getString(
                R.string.indexing_operation_error_format,
                error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            )
        )
    }

    private fun getScanStatusText(status: IndexScanStatus): String =
        getString(
            when (status) {
                IndexScanStatus.NEVER_RUN -> R.string.indexing_scan_never_run
                IndexScanStatus.RUNNING -> R.string.indexing_scan_running
                IndexScanStatus.PAUSED -> R.string.indexing_scan_paused
                IndexScanStatus.COMPLETED -> R.string.indexing_scan_completed
                IndexScanStatus.COMPLETED_WITH_ERRORS ->
                    R.string.indexing_scan_completed_with_errors
                IndexScanStatus.FAILED -> R.string.indexing_scan_failed
                IndexScanStatus.CANCELLED -> R.string.indexing_scan_cancelled
            }
        )

    private fun String.normalizedAbsolutePathOrNull(): String? =
        takeIf(String::isNotEmpty)?.let { path ->
            runCatching {
                Paths.get(path).takeIf { it.isAbsolute }?.normalize()?.toString()
            }.getOrNull()
        }

    private fun IndexRoot.isScanActive(): Boolean =
        lastScanStatus == IndexScanStatus.RUNNING ||
            lastScanStatus == IndexScanStatus.PAUSED

    companion object {
        private const val KEY_STATUS = "index_status"
        private const val KEY_STATISTICS = "index_statistics"
        private const val KEY_START = "index_start"
        private const val KEY_PAUSE = "index_pause"
        private const val KEY_RESUME = "index_resume"
        private const val KEY_CANCEL = "index_cancel"
        private const val KEY_ADD_ROOT = "index_add_root"
        private const val KEY_NO_ROOTS = "index_no_roots"
        private const val KEY_ADD_EXCLUSION = "index_add_exclusion"
        private const val KEY_NO_EXCLUSIONS = "index_no_exclusions"
        private const val ACTIVE_REFRESH_MILLIS = 1_000L
        private const val IDLE_REFRESH_MILLIS = 5_000L
        private const val COMMAND_REFRESH_DELAY_MILLIS = 250L
        private const val MAX_DISPLAYED_ERROR_LENGTH = 300
    }
}
