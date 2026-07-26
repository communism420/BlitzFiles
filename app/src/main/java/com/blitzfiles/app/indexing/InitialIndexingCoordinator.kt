/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.blitzfiles.app.R
import com.blitzfiles.app.app.application
import com.blitzfiles.app.app.storageManager
import com.blitzfiles.app.compat.directoryCompat
import com.blitzfiles.app.compat.getDescriptionCompat
import com.blitzfiles.app.compat.isPrimaryCompat
import com.blitzfiles.app.compat.storageVolumesCompat
import com.blitzfiles.app.provider.root.LibSuFileServiceLauncher
import com.blitzfiles.app.provider.root.SuiFileServiceLauncher
import com.blitzfiles.search.domain.model.IndexAccessMode
import java.io.File
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android integration for the durable first-launch indexing workflow.
 */
internal object InitialIndexingCoordinator {
    private val stateStore: SharedPreferencesInitialIndexingStateStore by lazy {
        SharedPreferencesInitialIndexingStateStore(
            application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    }
    private val workflow: InitialIndexingWorkflow by lazy {
        InitialIndexingWorkflow(stateStore, AndroidInitialIndexingGateway())
    }

    /**
     * Must be called before the default preferences are initialized for a new install.
     *
     * Existing installations are deliberately ineligible so an update cannot silently add roots,
     * rebuild an established index, or override a user's previously removed locations.
     */
    fun initializeInstallEligibility(isFreshInstall: Boolean) {
        val installationMarker = File(application.noBackupFilesDir, INSTALLATION_MARKER_FILE)
        val isNewInstallationState = !installationMarker.exists()
        stateStore.initializeEligibility(
            isFreshInstall = isFreshInstall,
            resetRestoredState = isNewInstallationState
        )
        if (isNewInstallationState) {
            installationMarker.parentFile?.mkdirs()
            check(installationMarker.createNewFile() || installationMarker.exists()) {
                "Unable to create initial indexing installation marker"
            }
        }
    }

    fun isPending(): Boolean = stateStore.isEligible

    /**
     * Access mode selected by first-run capability detection and user consent.
     *
     * Indexing settings use the same decision so manually added directories cannot reintroduce a
     * mixed ROOT/STANDARD configuration.
     */
    fun automaticAccessMode(): IndexAccessMode =
        when (stateStore.rootDecision) {
            InitialRootIndexingDecision.ACCEPTING,
            InitialRootIndexingDecision.ACCEPTED -> IndexAccessMode.ROOT
            InitialRootIndexingDecision.UNKNOWN,
            InitialRootIndexingDecision.DECLINED,
            InitialRootIndexingDecision.UNAVAILABLE -> IndexAccessMode.STANDARD
        }

    fun shouldShowAllFilesAccessInformation(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            stateStore.isEligible &&
            !stateStore.wasAllFilesAccessInformationShown

    fun markAllFilesAccessInformationShown() {
        if (stateStore.isEligible && !stateStore.wasAllFilesAccessInformationShown) {
            stateStore.wasAllFilesAccessInformationShown = true
        }
    }

    suspend fun prepare(): InitialIndexingAction =
        withContext(Dispatchers.IO) { workflow.prepare() }

    suspend fun prepareRoot(): InitialIndexingAction =
        withContext(Dispatchers.IO) { workflow.prepareRoot() }

    suspend fun prepareOrdinary() =
        withContext(Dispatchers.IO) { workflow.prepareOrdinary() }

    suspend fun beginRootAcceptance() =
        withContext(Dispatchers.IO) { workflow.beginRootAcceptance() }

    suspend fun completeRootAcceptance() =
        withContext(Dispatchers.IO) { workflow.completeRootAcceptance() }

    suspend fun declineRoot() =
        withContext(Dispatchers.IO) { workflow.declineRoot() }

    suspend fun handleRootAcceptanceFailure() =
        withContext(Dispatchers.IO) { workflow.handleRootAcceptanceFailure() }

    suspend fun deferRootAcceptanceForMissingStorageAccess() =
        withContext(Dispatchers.IO) {
            workflow.deferRootAcceptanceForMissingStorageAccess()
        }

    private const val PREFERENCES_NAME = "initial_indexing_v1"
    private const val INSTALLATION_MARKER_FILE = "initial_indexing_installation"
}

private class AndroidInitialIndexingGateway : InitialIndexingGateway {
    override fun hasRequiredStorageAccess(): Boolean =
        FileIndexingStorageAccess.isGranted(application)

    override suspend fun getRoots() = FileIndexingController.getRoots(application)

    override suspend fun getOrdinaryLocations(): List<InitialIndexLocation> {
        return storageManager.storageVolumesCompat.asSequence()
            .mapNotNull { volume ->
                val directory = volume.directoryCompat ?: return@mapNotNull null
                val normalizedPath = Paths.get(directory.absolutePath)
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                InitialIndexLocation(
                    path = normalizedPath,
                    displayName = volume.getDescriptionCompat(application),
                    isPrimary = volume.isPrimaryCompat
                )
            }
            .distinctBy(InitialIndexLocation::path)
            .sortedByDescending(InitialIndexLocation::isPrimary)
            .take(MAX_ORDINARY_ROOTS)
            .toList()
    }

    override suspend fun saveStorageRoot(
        location: InitialIndexLocation,
        accessMode: IndexAccessMode
    ): Long =
        FileIndexingController.saveRoot(
            context = application,
            path = location.path,
            displayName = location.displayName,
            accessMode = accessMode
        )

    override suspend fun hasRootCandidate(): Boolean {
        check(FileIndexingStorageAccess.isGranted(application)) {
            "All files access is required before detecting root access"
        }
        return SuiFileServiceLauncher.isSuiAvailable() ||
            LibSuFileServiceLauncher.isSuAvailable()
    }

    override suspend fun verifyRootAccess() {
        check(FileIndexingStorageAccess.isGranted(application)) {
            "All files access is required before requesting root access"
        }
        val metadata = MaterialFilesIndexFileSystem().readMetadata(
            path = SYSTEM_ROOT_PATH,
            accessMode = IndexAccessMode.ROOT,
            followSymbolicLinks = false
        )
        check(metadata?.isDirectory == true) { "Root directory is not accessible" }
    }

    override suspend fun saveRoot(): Long =
        FileIndexingController.saveRoot(
            context = application,
            path = SYSTEM_ROOT_PATH,
            displayName = application.getString(R.string.storage_file_system_root_title),
            accessMode = IndexAccessMode.ROOT
        )

    override suspend fun removeRoot(rootId: Long) {
        check(FileIndexingController.removeRoot(application, rootId)) {
            "Unable to remove onboarding index root: $rootId"
        }
    }

    override fun startFullScan(rootIds: Set<Long>): Boolean =
        FileIndexingController.startFull(application, rootIds)

    private companion object {
        const val SYSTEM_ROOT_PATH = "/"
        const val MAX_ORDINARY_ROOTS = 255
    }
}

private class SharedPreferencesInitialIndexingStateStore(
    private val preferences: SharedPreferences
) : InitialIndexingStateStore {
    override var isEligible: Boolean
        get() = preferences.getBoolean(KEY_ELIGIBLE, false)
        set(value) {
            persist { putBoolean(KEY_ELIGIBLE, value) }
        }

    override var ordinaryState: InitialOrdinaryIndexingState
        get() = preferences.enumValue(
            KEY_ORDINARY_STATE,
            InitialOrdinaryIndexingState.PENDING
        )
        set(value) {
            persist { putString(KEY_ORDINARY_STATE, value.name) }
        }

    override var rootDecision: InitialRootIndexingDecision
        get() = preferences.enumValue(
            KEY_ROOT_DECISION,
            InitialRootIndexingDecision.UNKNOWN
        )
        set(value) {
            persist { putString(KEY_ROOT_DECISION, value.name) }
        }

    override var managedOrdinaryPaths: Set<String>
        get() = preferences.getStringSet(KEY_MANAGED_ORDINARY_PATHS, emptySet())
            .orEmpty()
            .toSet()
        set(value) {
            persist { putStringSet(KEY_MANAGED_ORDINARY_PATHS, value.toSet()) }
        }

    override var managedRootId: Long?
        get() = if (preferences.contains(KEY_MANAGED_ROOT_ID)) {
            preferences.getLong(KEY_MANAGED_ROOT_ID, 0L).takeIf { it > 0 }
        } else {
            null
        }
        set(value) {
            persist {
                if (value == null) {
                    remove(KEY_MANAGED_ROOT_ID)
                } else {
                    putLong(KEY_MANAGED_ROOT_ID, value)
                }
            }
        }

    var wasAllFilesAccessInformationShown: Boolean
        get() = preferences.getBoolean(KEY_ALL_FILES_ACCESS_INFORMATION_SHOWN, false)
        set(value) {
            persist { putBoolean(KEY_ALL_FILES_ACCESS_INFORMATION_SHOWN, value) }
        }

    fun initializeEligibility(
        isFreshInstall: Boolean,
        resetRestoredState: Boolean
    ) {
        if (!resetRestoredState && preferences.contains(KEY_INITIALIZED)) {
            return
        }
        persist {
            putBoolean(KEY_INITIALIZED, true)
            putBoolean(KEY_ELIGIBLE, isFreshInstall)
            putString(KEY_ORDINARY_STATE, InitialOrdinaryIndexingState.PENDING.name)
            putString(KEY_ROOT_DECISION, InitialRootIndexingDecision.UNKNOWN.name)
            putBoolean(KEY_ALL_FILES_ACCESS_INFORMATION_SHOWN, false)
            remove(KEY_MANAGED_ORDINARY_PATHS)
            remove(KEY_MANAGED_ROOT_ID)
        }
    }

    private fun persist(block: SharedPreferences.Editor.() -> Unit) {
        val committed = preferences.edit().apply(block).commit()
        check(committed) { "Unable to persist initial indexing state" }
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(
        key: String,
        defaultValue: T
    ): T {
        val value = getString(key, null) ?: return defaultValue
        return enumValues<T>().firstOrNull { it.name == value } ?: defaultValue
    }

    private companion object {
        const val KEY_INITIALIZED = "initialized"
        const val KEY_ELIGIBLE = "eligible"
        const val KEY_ORDINARY_STATE = "ordinary_state"
        const val KEY_ROOT_DECISION = "root_decision"
        const val KEY_ALL_FILES_ACCESS_INFORMATION_SHOWN =
            "all_files_access_information_shown"
        const val KEY_MANAGED_ORDINARY_PATHS = "managed_ordinary_paths"
        const val KEY_MANAGED_ROOT_ID = "managed_root_id"
    }
}
