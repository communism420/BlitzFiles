/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable first-launch indexing orchestration without Android UI dependencies.
 *
 * Every state transition that authorizes a side effect is persisted before that side effect. This
 * makes process death safe and guarantees that primary shared storage is configured before the
 * first scan starts.
 */
internal class InitialIndexingWorkflow(
    private val stateStore: InitialIndexingStateStore,
    private val gateway: InitialIndexingGateway
) {
    private val operationMutex = Mutex()

    /**
     * Compatibility entry point for callers that do not coordinate Android permission UI.
     * Required storage access is checked before root discovery, and the root strategy is selected
     * before storage roots are created.
     */
    suspend fun prepare(): InitialIndexingAction {
        val rootAction = prepareRoot()
        if (rootAction == InitialIndexingAction.Idle) {
            prepareOrdinary()
        }
        return rootAction
    }

    /**
     * Performs root discovery only after required storage access has been granted.
     *
     * Root candidate detection deliberately happens after the access-information UI and before
     * storage discovery. No storage root is persisted until root is unavailable, accepted, or
     * explicitly declined.
     */
    suspend fun prepareRoot(): InitialIndexingAction = operationMutex.withLock {
        if (!stateStore.isEligible) {
            return InitialIndexingAction.Idle
        }
        if (!gateway.hasRequiredStorageAccess()) {
            return InitialIndexingAction.AwaitStorageAccess
        }
        if (
            stateStore.rootDecision == InitialRootIndexingDecision.ACCEPTING &&
            stateStore.ordinaryState == InitialOrdinaryIndexingState.COMPLETE
        ) {
            // Recover the durable gap left by older builds that committed COMPLETE before
            // ACCEPTED. Completion is authoritative even if the user has since removed a root.
            stateStore.rootDecision = InitialRootIndexingDecision.ACCEPTED
            finishIfComplete()
            return InitialIndexingAction.Idle
        }

        val roots = gateway.getRoots()
        val existingSystemRoot = roots.firstOrNull {
            it.path == SYSTEM_ROOT_PATH &&
                it.accessMode == IndexAccessMode.ROOT
        }
        if (
            existingSystemRoot != null &&
            stateStore.rootDecision == InitialRootIndexingDecision.ACCEPTING &&
            stateStore.managedRootId == null
        ) {
            // Recover the durable gap after saveRoot() if the process died before the returned ID
            // could be persisted. ACCEPTING proves that this root belongs to onboarding.
            stateStore.managedRootId = checkNotNull(existingSystemRoot.id)
            migrateSkippedStorageState()
        }
        if (disableOnboardingForUnexpectedConfiguration(roots)) {
            return InitialIndexingAction.Idle
        }

        val hasRootCandidate =
            existingSystemRoot == null &&
                stateStore.rootDecision == InitialRootIndexingDecision.UNKNOWN &&
                gateway.hasRootCandidate()

        if (existingSystemRoot != null) {
            val existingRootId = checkNotNull(existingSystemRoot.id)
            val managedRootId = stateStore.managedRootId
            if (managedRootId != null && managedRootId != existingRootId) {
                // The onboarding root was removed and a new one was later created manually.
                // Treat the replacement as user-owned and never change or automatically scan it.
                stateStore.rootDecision = InitialRootIndexingDecision.ACCEPTED
                stateStore.ordinaryState = InitialOrdinaryIndexingState.COMPLETE
                finishIfComplete()
                return InitialIndexingAction.Idle
            }
            if (managedRootId == null) {
                stateStore.managedRootId = existingRootId
            }
            when (stateStore.rootDecision) {
                InitialRootIndexingDecision.UNKNOWN,
                InitialRootIndexingDecision.ACCEPTING,
                InitialRootIndexingDecision.ACCEPTED -> {
                    removeManagedStorageRootsWithDifferentModeLocked(
                        roots,
                        IndexAccessMode.ROOT
                    )
                    migrateSkippedStorageState()
                    if (stateStore.rootDecision == InitialRootIndexingDecision.UNKNOWN) {
                        stateStore.rootDecision = InitialRootIndexingDecision.ACCEPTING
                    }
                }
                InitialRootIndexingDecision.DECLINED,
                InitialRootIndexingDecision.UNAVAILABLE -> Unit
            }
            finishIfComplete()
            return InitialIndexingAction.Idle
        }

        when (stateStore.rootDecision) {
            InitialRootIndexingDecision.ACCEPTING -> {
                if (stateStore.managedRootId == null) {
                    acceptRootLocked()
                } else {
                    // A previously configured onboarding root is now absent, so the user removed
                    // it. Keep it absent; primary shared storage remains in the selected ROOT
                    // mode and is reconciled by prepareOrdinary().
                    migrateSkippedStorageState()
                }
                InitialIndexingAction.Idle
            }
            InitialRootIndexingDecision.UNKNOWN -> {
                if (hasRootCandidate) {
                    InitialIndexingAction.OfferRoot
                } else {
                    stateStore.rootDecision = InitialRootIndexingDecision.UNAVAILABLE
                    finishIfComplete()
                    InitialIndexingAction.Idle
                }
            }
            InitialRootIndexingDecision.DECLINED,
            InitialRootIndexingDecision.UNAVAILABLE -> {
                finishIfComplete()
                InitialIndexingAction.Idle
            }
            InitialRootIndexingDecision.ACCEPTED -> {
                // The user may have removed "/" while storage reconciliation was still pending.
                // Never silently restore it.
                migrateSkippedStorageState()
                finishIfComplete()
                InitialIndexingAction.Idle
            }
        }
    }

    /**
     * Persists and scans storage roots after the access strategy has been selected.
     *
     * STANDARD mode includes every mounted ordinary volume. ROOT mode includes primary shared
     * storage as a dedicated descendant of "/" so it cannot be hidden by virtual/backing-path
     * safety exclusions. All selected roots are persisted before one combined scan is queued.
     */
    suspend fun prepareOrdinary() = operationMutex.withLock {
        if (!stateStore.isEligible) {
            return@withLock
        }
        if (!gateway.hasRequiredStorageAccess()) {
            return@withLock
        }
        val accessMode: IndexAccessMode
        val primaryOnly: Boolean
        when (stateStore.rootDecision) {
            InitialRootIndexingDecision.ACCEPTING,
            InitialRootIndexingDecision.ACCEPTED -> {
                accessMode = IndexAccessMode.ROOT
                primaryOnly = true
                migrateSkippedStorageState()
            }
            InitialRootIndexingDecision.UNKNOWN -> {
                // Root capability is still undecided; do not race ahead with storage roots.
                return@withLock
            }
            InitialRootIndexingDecision.DECLINED,
            InitialRootIndexingDecision.UNAVAILABLE -> {
                accessMode = IndexAccessMode.STANDARD
                primaryOnly = false
            }
        }
        val roots = gateway.getRoots()
        if (disableOnboardingForUnexpectedConfiguration(roots)) {
            return@withLock
        }
        val preparation = prepareStorageRootsLocked(
            currentRoots = roots,
            accessMode = accessMode,
            primaryOnly = primaryOnly
        )
        if (
            stateStore.isEligible &&
            stateStore.ordinaryState == InitialOrdinaryIndexingState.IN_PROGRESS
        ) {
            val selectedRootIds = linkedSetOf<Long>().apply {
                addAll(preparation.enabledRootIds)
                if (accessMode == IndexAccessMode.ROOT) {
                    preparation.roots.firstOrNull { root ->
                        root.id == stateStore.managedRootId &&
                            root.path == SYSTEM_ROOT_PATH &&
                            root.accessMode == IndexAccessMode.ROOT &&
                            root.isEnabled
                    }?.id?.let(::add)
                }
            }
            val rootsById = preparation.roots.associateBy(IndexRoot::id)
            val selectedRoots = selectedRootIds.map { rootId ->
                checkNotNull(rootsById[rootId]) {
                    "Selected index root was not persisted: $rootId"
                }
            }
            val scanWasCancelled = selectedRoots.any {
                it.lastScanStatus == IndexScanStatus.CANCELLED
            }
            val unfinishedRootIds = selectedRoots
                .filterNot(IndexRoot::hasFinishedInitialScan)
                .mapTo(linkedSetOf()) { checkNotNull(it.id) }
            if (
                selectedRoots.isEmpty() ||
                scanWasCancelled ||
                unfinishedRootIds.isEmpty()
            ) {
                if (accessMode == IndexAccessMode.ROOT) {
                    stateStore.rootDecision = InitialRootIndexingDecision.ACCEPTED
                }
                // Persist the strategy decision first. If the process dies before COMPLETE is
                // committed, the recoverable ACCEPTED + IN_PROGRESS state is reconciled again.
                stateStore.ordinaryState = InitialOrdinaryIndexingState.COMPLETE
                finishIfComplete()
            } else {
                // Keep command identity stable while the combined scan is active. A shrinking
                // request (for example "/" after primary storage has completed) is distinct to
                // the FIFO and would schedule an unnecessary second full scan.
                if (!gateway.startFullScan(selectedRootIds)) {
                    // Access may be revoked between UI confirmation and service scheduling.
                    // Leave IN_PROGRESS durable so the same roots are retried after access returns.
                    return@withLock
                }
            }
        }
    }

    suspend fun beginRootAcceptance() = operationMutex.withLock {
        if (!stateStore.isEligible) {
            return@withLock
        }
        check(stateStore.rootDecision == InitialRootIndexingDecision.UNKNOWN) {
            "Root indexing has already been decided"
        }
        stateStore.rootDecision = InitialRootIndexingDecision.ACCEPTING
    }

    suspend fun completeRootAcceptance() = operationMutex.withLock {
        if (
            !stateStore.isEligible ||
            stateStore.rootDecision != InitialRootIndexingDecision.ACCEPTING
        ) {
            return@withLock
        }
        check(gateway.hasRequiredStorageAccess()) {
            "All files access is required before requesting root access"
        }
        acceptRootLocked()
    }

    suspend fun declineRoot() = operationMutex.withLock {
        if (!stateStore.isEligible) {
            return@withLock
        }
        if (stateStore.rootDecision == InitialRootIndexingDecision.UNKNOWN) {
            stateStore.rootDecision = InitialRootIndexingDecision.DECLINED
            resetStandardFallback()
            finishIfComplete()
        }
    }

    suspend fun handleRootAcceptanceFailure() = operationMutex.withLock {
        if (stateStore.rootDecision != InitialRootIndexingDecision.ACCEPTING) {
            return@withLock
        }
        val existingRoot = gateway.getRoots().firstOrNull {
            it.path == SYSTEM_ROOT_PATH &&
                it.accessMode == IndexAccessMode.ROOT
        }
        if (existingRoot != null) {
            stateStore.managedRootId = checkNotNull(existingRoot.id)
            migrateSkippedStorageState()
            return@withLock
        }
        // Root permission was denied or verification failed before configuration. Do not invoke
        // the system root prompt again automatically on every subsequent app launch.
        stateStore.rootDecision = InitialRootIndexingDecision.DECLINED
        // A failed root transition may have already removed some legacy managed roots. Forgetting
        // the old ownership markers lets STANDARD fallback recreate every mounted volume.
        stateStore.managedOrdinaryPaths = emptySet()
        resetStandardFallback()
        finishIfComplete()
    }

    suspend fun deferRootAcceptanceForMissingStorageAccess() = operationMutex.withLock {
        if (
            stateStore.rootDecision == InitialRootIndexingDecision.ACCEPTING &&
            stateStore.managedRootId == null
        ) {
            stateStore.rootDecision = InitialRootIndexingDecision.UNKNOWN
        }
    }

    private suspend fun acceptRootLocked() {
        check(gateway.hasRequiredStorageAccess()) {
            "All files access is required before requesting root access"
        }
        gateway.verifyRootAccess()
        removeManagedStorageRootsWithDifferentModeLocked(
            gateway.getRoots(),
            IndexAccessMode.ROOT
        )
        check(gateway.hasRequiredStorageAccess()) {
            "All files access was revoked while enabling root indexing"
        }
        val rootId = gateway.saveRoot()
        stateStore.managedRootId = rootId
        migrateSkippedStorageState()
        // Keep ACCEPTING until the storage phase has persisted both roots and observed their
        // combined scan completion.
    }

    /**
     * Removes roots created by a previous onboarding strategy only when their provider conflicts
     * with the newly selected strategy.
     *
     * A managed ROOT primary-storage child must survive every later prepareRoot() call.
     */
    private suspend fun removeManagedStorageRootsWithDifferentModeLocked(
        roots: List<IndexRoot>,
        desiredMode: IndexAccessMode
    ) {
        var managedPaths = stateStore.managedOrdinaryPaths
        managedPaths.toList().forEach { path ->
            val managedRoot = roots.firstOrNull { it.path == path }
            if (managedRoot != null && managedRoot.accessMode != desiredMode) {
                gateway.removeRoot(checkNotNull(managedRoot.id))
                managedPaths = managedPaths - path
                stateStore.managedOrdinaryPaths = managedPaths
            }
        }
    }

    private fun migrateSkippedStorageState() {
        if (stateStore.ordinaryState == InitialOrdinaryIndexingState.SKIPPED) {
            stateStore.ordinaryState = InitialOrdinaryIndexingState.PENDING
        }
    }

    private fun resetStandardFallback() {
        if (stateStore.ordinaryState == InitialOrdinaryIndexingState.SKIPPED) {
            stateStore.ordinaryState = InitialOrdinaryIndexingState.PENDING
        }
    }

    private fun disableOnboardingForUnexpectedConfiguration(roots: List<IndexRoot>): Boolean {
        if (stateStore.ordinaryState != InitialOrdinaryIndexingState.PENDING) {
            return false
        }
        val managedRootId = stateStore.managedRootId
        if (roots.none { root -> root.id != managedRootId }) {
            return false
        }
        // A restored preference file or an app update must never silently replace a user's
        // existing indexing configuration.
        stateStore.isEligible = false
        stateStore.ordinaryState = InitialOrdinaryIndexingState.SKIPPED
        return true
    }

    private suspend fun prepareStorageRootsLocked(
        currentRoots: List<IndexRoot>,
        accessMode: IndexAccessMode,
        primaryOnly: Boolean
    ): OrdinaryRootPreparation {
        if (stateStore.ordinaryState == InitialOrdinaryIndexingState.PENDING) {
            stateStore.ordinaryState = InitialOrdinaryIndexingState.IN_PROGRESS
        }
        if (stateStore.ordinaryState != InitialOrdinaryIndexingState.IN_PROGRESS) {
            return OrdinaryRootPreparation(currentRoots, emptySet())
        }

        val availableLocations = gateway.getOrdinaryLocations()
        val primaryLocation = availableLocations.firstOrNull(InitialIndexLocation::isPrimary)
        checkNotNull(primaryLocation) { "Primary shared storage is not available" }
        val locations = if (primaryOnly) listOf(primaryLocation) else availableLocations
        var managedPaths = stateStore.managedOrdinaryPaths
        val rootsByPath = currentRoots.associateBy(IndexRoot::path)
        val enabledRootIds = linkedSetOf<Long>()
        locations.forEach { location ->
            val existing = rootsByPath[location.path]
            when {
                existing != null -> {
                    check(existing.accessMode == accessMode) {
                        "Managed storage root ${location.path} uses ${existing.accessMode}, " +
                            "expected $accessMode"
                    }
                    if (location.path !in managedPaths) {
                        managedPaths = managedPaths + location.path
                        stateStore.managedOrdinaryPaths = managedPaths
                    }
                    if (existing.isEnabled) {
                        enabledRootIds += checkNotNull(existing.id)
                    }
                }
                location.path in managedPaths -> {
                    // A root that was successfully created and is now absent was explicitly
                    // removed by the user. Do not recreate it during completion observation.
                }
                else -> {
                    val rootId = gateway.saveStorageRoot(location, accessMode)
                    managedPaths = managedPaths + location.path
                    stateStore.managedOrdinaryPaths = managedPaths
                    enabledRootIds += rootId
                }
            }
        }
        val roots = gateway.getRoots()
        return OrdinaryRootPreparation(roots, enabledRootIds)
    }

    private fun finishIfComplete() {
        val selectedModeFinished = when (stateStore.rootDecision) {
            InitialRootIndexingDecision.ACCEPTED ->
                stateStore.ordinaryState == InitialOrdinaryIndexingState.COMPLETE
            InitialRootIndexingDecision.DECLINED,
            InitialRootIndexingDecision.UNAVAILABLE ->
                stateStore.ordinaryState == InitialOrdinaryIndexingState.COMPLETE
            InitialRootIndexingDecision.UNKNOWN,
            InitialRootIndexingDecision.ACCEPTING -> false
        }
        if (selectedModeFinished) {
            stateStore.isEligible = false
        }
    }

    private companion object {
        const val SYSTEM_ROOT_PATH = "/"
    }

    private data class OrdinaryRootPreparation(
        val roots: List<IndexRoot>,
        val enabledRootIds: Set<Long>
    )
}

private fun IndexRoot.hasFinishedInitialScan(): Boolean =
    when (lastScanStatus) {
        IndexScanStatus.COMPLETED,
        IndexScanStatus.COMPLETED_WITH_ERRORS,
        IndexScanStatus.CANCELLED -> true
        IndexScanStatus.NEVER_RUN,
        IndexScanStatus.RUNNING,
        IndexScanStatus.PAUSED,
        IndexScanStatus.FAILED -> false
    }

internal data class InitialIndexLocation(
    val path: String,
    val displayName: String,
    val isPrimary: Boolean
)

internal sealed interface InitialIndexingAction {
    data object Idle : InitialIndexingAction
    data object AwaitStorageAccess : InitialIndexingAction
    data object OfferRoot : InitialIndexingAction
}

internal enum class InitialOrdinaryIndexingState {
    PENDING,
    IN_PROGRESS,
    COMPLETE,
    SKIPPED
}

internal enum class InitialRootIndexingDecision {
    UNKNOWN,
    ACCEPTING,
    ACCEPTED,
    DECLINED,
    UNAVAILABLE
}

internal interface InitialIndexingStateStore {
    var isEligible: Boolean
    var ordinaryState: InitialOrdinaryIndexingState
    var rootDecision: InitialRootIndexingDecision
    var managedOrdinaryPaths: Set<String>
    var managedRootId: Long?
}

internal interface InitialIndexingGateway {
    fun hasRequiredStorageAccess(): Boolean
    suspend fun getRoots(): List<IndexRoot>
    suspend fun getOrdinaryLocations(): List<InitialIndexLocation>
    suspend fun saveStorageRoot(
        location: InitialIndexLocation,
        accessMode: IndexAccessMode
    ): Long
    suspend fun hasRootCandidate(): Boolean
    suspend fun verifyRootAccess()
    suspend fun saveRoot(): Long
    suspend fun removeRoot(rootId: Long)
    fun startFullScan(rootIds: Set<Long>): Boolean
}
