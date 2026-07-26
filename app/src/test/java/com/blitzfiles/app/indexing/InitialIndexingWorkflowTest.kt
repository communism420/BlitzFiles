/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexAccessMode
import com.blitzfiles.search.domain.model.IndexRoot
import com.blitzfiles.search.domain.model.IndexScanStatus
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialIndexingWorkflowTest {
    @Test
    fun missingAllFilesAccessBlocksEveryFirstLaunchSideEffect() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(
            rootCandidate = true,
            hasRequiredStorageAccess = false
        )
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.AwaitStorageAccess, workflow.prepare())

        assertTrue(state.isEligible)
        assertEquals(InitialRootIndexingDecision.UNKNOWN, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.PENDING, state.ordinaryState)
        assertEquals(0, gateway.rootCandidateCheckCount)
        assertEquals(0, gateway.rootVerificationCount)
        assertEquals(0, gateway.ordinaryLocationReadCount)
        assertTrue(gateway.roots.isEmpty())
        assertTrue(gateway.startedScans.isEmpty())
        assertTrue(gateway.events.isEmpty())

        gateway.hasRequiredStorageAccess = true

        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        assertEquals(1, gateway.rootCandidateCheckCount)
    }

    @Test
    fun permissionRevokedBeforeRootAcceptanceBlocksRootRequest() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        workflow.beginRootAcceptance()

        gateway.hasRequiredStorageAccess = false

        assertThrows(IllegalStateException::class.java) {
            runBlocking { workflow.completeRootAcceptance() }
        }
        assertEquals(0, gateway.rootVerificationCount)
        assertEquals(0, gateway.rootSaveCount)
        assertTrue(gateway.startedScans.isEmpty())

        workflow.deferRootAcceptanceForMissingStorageAccess()
        gateway.hasRequiredStorageAccess = true

        assertEquals(InitialRootIndexingDecision.UNKNOWN, state.rootDecision)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        assertEquals(0, gateway.rootVerificationCount)
    }

    @Test
    fun deniedScanLaunchRemainsPendingAndRetriesAfterAccessReturns() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(
            rootCandidate = false,
            scanLaunchAllowed = false
        )
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())

        workflow.prepareOrdinary()

        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertTrue(gateway.startedScans.isEmpty())

        gateway.scanLaunchAllowed = true
        workflow.prepareOrdinary()

        assertEquals(listOf(setOf(1L, 2L)), gateway.startedScans)
    }

    @Test
    fun noRootCandidateCreatesAndScansOnlyStandardStorageRoots() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = false)
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())
        assertEquals(InitialRootIndexingDecision.UNAVAILABLE, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.PENDING, state.ordinaryState)
        assertTrue(gateway.roots.isEmpty())
        assertTrue(gateway.startedScans.isEmpty())

        workflow.prepareOrdinary()

        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertEquals(
            listOf("/storage/emulated/0", "/storage/1234-5678"),
            gateway.roots.map(IndexRoot::path)
        )
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
        assertEquals(listOf(setOf(1L, 2L)), gateway.startedScans)
        assertEquals(1, gateway.rootCandidateCheckCount)
        assertEquals(1, gateway.ordinaryLocationReadCount)
        assertFalse(gateway.rootVerified)

        gateway.completeEveryRoot()
        assertEquals(InitialIndexingAction.Idle, workflow.prepare())
        assertEquals(InitialOrdinaryIndexingState.COMPLETE, state.ordinaryState)
        assertFalse(state.isEligible)
    }

    @Test
    fun transientOrdinaryDiscoveryFailureRetriesWithoutChangingStandardMode() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(
            rootCandidate = false,
            ordinaryLocationsError = IOException("storage temporarily unavailable")
        )
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())
        assertThrows(IOException::class.java) {
            runBlocking { workflow.prepareOrdinary() }
        }

        assertEquals(InitialRootIndexingDecision.UNAVAILABLE, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertTrue(gateway.roots.isEmpty())
        assertTrue(gateway.startedScans.isEmpty())

        gateway.ordinaryLocationsError = null
        workflow.prepareOrdinary()

        assertEquals(InitialRootIndexingDecision.UNAVAILABLE, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
        assertEquals(listOf(setOf(1L, 2L)), gateway.startedScans)
        assertEquals(1, gateway.rootCandidateCheckCount)
        assertEquals(2, gateway.ordinaryLocationReadCount)
    }

    @Test
    fun missingPrimaryStorageNeverCompletesOnboardingAndIsRetried() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(
            rootCandidate = false,
            primaryStorageAvailable = false
        )
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { workflow.prepareOrdinary() }
        }

        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertTrue(gateway.roots.isEmpty())
        assertTrue(gateway.startedScans.isEmpty())

        gateway.primaryStorageAvailable = true
        workflow.prepareOrdinary()

        assertTrue(gateway.roots.any { it.path == "/storage/emulated/0" })
        assertEquals(listOf(setOf(1L, 2L)), gateway.startedScans)
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
    }

    @Test
    fun rootCandidateDefersEveryConfigurationSideEffectUntilChoice() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        workflow.prepareOrdinary()

        assertEquals(InitialRootIndexingDecision.UNKNOWN, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.PENDING, state.ordinaryState)
        assertTrue(state.managedOrdinaryPaths.isEmpty())
        assertTrue(gateway.roots.isEmpty())
        assertTrue(gateway.startedScans.isEmpty())
        assertEquals(0, gateway.ordinaryLocationReadCount)
        assertEquals(0, gateway.ordinaryRootSaveCount)
        assertEquals(0, gateway.rootVerificationCount)
        assertEquals(0, gateway.rootSaveCount)
    }

    @Test
    fun acceptingRootScansSystemAndPrimaryStorageTogetherWithRootAccess() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())

        workflow.beginRootAcceptance()
        workflow.completeRootAcceptance()
        workflow.prepareOrdinary()

        assertEquals(InitialRootIndexingDecision.ACCEPTING, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertTrue(gateway.rootVerified)
        assertEquals(
            listOf("/", "/storage/emulated/0"),
            gateway.roots.map(IndexRoot::path)
        )
        gateway.assertExclusiveMode(IndexAccessMode.ROOT)
        assertEquals(listOf(listOf(2L, 1L)), gateway.startedScans.map(Set<Long>::toList))
        assertEquals(1, gateway.ordinaryLocationReadCount)
        assertEquals(
            listOf(
                "verify-root",
                "save-root",
                "get-ordinary-locations",
                "save-storage:ROOT:/storage/emulated/0",
                "start:[1, 2]"
            ),
            gateway.events.takeLast(5)
        )

        gateway.completeEveryRoot()
        assertEquals(InitialIndexingAction.Idle, workflow.prepare())
        assertEquals(InitialRootIndexingDecision.ACCEPTED, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.COMPLETE, state.ordinaryState)
        assertFalse(state.isEligible)
        assertEquals(2, gateway.ordinaryLocationReadCount)
        assertEquals(
            listOf("/", "/storage/emulated/0"),
            gateway.roots.map(IndexRoot::path)
        )
        assertTrue(gateway.removedRootIds.isEmpty())
    }

    @Test
    fun accessRevokedAfterRootPreparationDefersOrdinarySetupUntilAccessReturns() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        workflow.beginRootAcceptance()
        workflow.completeRootAcceptance()
        assertEquals(listOf("/"), gateway.roots.map(IndexRoot::path))

        gateway.hasRequiredStorageAccess = false
        val eventsBeforeDeniedPreparation = gateway.events.toList()
        workflow.prepareOrdinary()

        assertEquals(InitialOrdinaryIndexingState.PENDING, state.ordinaryState)
        assertEquals(0, gateway.ordinaryLocationReadCount)
        assertEquals(0, gateway.ordinaryRootSaveCount)
        assertTrue(gateway.startedScans.isEmpty())
        assertEquals(eventsBeforeDeniedPreparation, gateway.events)
        assertEquals(listOf("/"), gateway.roots.map(IndexRoot::path))

        gateway.hasRequiredStorageAccess = true
        workflow.prepareOrdinary()

        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertEquals(1, gateway.ordinaryLocationReadCount)
        assertEquals(1, gateway.ordinaryRootSaveCount)
        assertEquals(
            listOf("/", "/storage/emulated/0"),
            gateway.roots.map(IndexRoot::path)
        )
        gateway.assertExclusiveMode(IndexAccessMode.ROOT)
        assertEquals(listOf(listOf(2L, 1L)), gateway.startedScans.map(Set<Long>::toList))
    }

    @Test
    fun decliningRootSelectsStandardModeExclusively() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())

        workflow.declineRoot()
        workflow.prepareOrdinary()

        assertEquals(InitialRootIndexingDecision.DECLINED, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertFalse(gateway.roots.any { it.path == "/" })
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
        assertEquals(listOf(setOf(1L, 2L)), gateway.startedScans)
        assertEquals(0, gateway.rootVerificationCount)
        assertEquals(0, gateway.rootSaveCount)

        gateway.completeEveryRoot()
        workflow.prepare()
        assertFalse(state.isEligible)
    }

    @Test
    fun failedRootVerificationFallsBackToStandardModeExclusively() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(
            rootCandidate = true,
            rootVerificationError = SecurityException("denied")
        )
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        workflow.beginRootAcceptance()

        assertThrows(SecurityException::class.java) {
            runBlocking { workflow.completeRootAcceptance() }
        }
        workflow.handleRootAcceptanceFailure()
        workflow.prepareOrdinary()
        workflow.prepareRoot()

        assertEquals(InitialRootIndexingDecision.DECLINED, state.rootDecision)
        assertFalse(gateway.roots.any { it.path == "/" })
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
        assertEquals(1, gateway.rootVerificationCount)
        assertEquals(0, gateway.rootSaveCount)
        assertEquals(1, gateway.rootCandidateCheckCount)
        assertEquals(listOf(setOf(1L, 2L)), gateway.startedScans)

        gateway.completeEveryRoot()
        workflow.prepare()
        assertFalse(state.isEligible)
    }

    @Test
    fun interruptedAcceptanceResumesRootModeWithoutOfferingAgain() = runBlocking {
        val state = FakeStateStore(
            rootDecision = InitialRootIndexingDecision.ACCEPTING
        )
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())
        workflow.prepareOrdinary()

        assertEquals(InitialRootIndexingDecision.ACCEPTING, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        gateway.assertExclusiveMode(IndexAccessMode.ROOT)
        assertEquals(1, gateway.rootVerificationCount)
        assertEquals(1, gateway.rootSaveCount)
        assertEquals(listOf(listOf(2L, 1L)), gateway.startedScans.map(Set<Long>::toList))
        assertEquals(0, gateway.rootCandidateCheckCount)
        assertEquals(1, gateway.ordinaryLocationReadCount)
    }

    @Test
    fun interruptedAcceptanceFailureFallsBackToStandardMode() = runBlocking {
        val state = FakeStateStore(
            rootDecision = InitialRootIndexingDecision.ACCEPTING
        )
        val gateway = FakeGateway(
            rootCandidate = true,
            rootVerificationError = SecurityException("denied after process death")
        )
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertThrows(SecurityException::class.java) {
            runBlocking { workflow.prepareRoot() }
        }
        workflow.handleRootAcceptanceFailure()
        workflow.prepareOrdinary()

        assertEquals(InitialRootIndexingDecision.DECLINED, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertEquals(
            listOf("/storage/emulated/0", "/storage/1234-5678"),
            gateway.roots.map(IndexRoot::path)
        )
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
        assertEquals(listOf(setOf(1L, 2L)), gateway.startedScans)
        assertEquals(1, gateway.rootVerificationCount)
        assertEquals(0, gateway.rootSaveCount)
        assertEquals(0, gateway.rootCandidateCheckCount)
    }

    @Test
    fun savedRootIsAdoptedAfterProcessDeathBeforeItsIdWasPersisted() = runBlocking {
        val state = FakeStateStore(
            rootDecision = InitialRootIndexingDecision.ACCEPTING
        )
        val gateway = FakeGateway(rootCandidate = true).apply {
            roots += root(7)
        }
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())
        workflow.prepareOrdinary()

        assertEquals(7L, state.managedRootId)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertEquals(0, gateway.rootSaveCount)
        assertEquals(listOf(listOf(8L, 7L)), gateway.startedScans.map(Set<Long>::toList))
        gateway.assertExclusiveMode(IndexAccessMode.ROOT)
    }

    @Test
    fun completedSystemRootStillRequiresPrimaryStorageScan() = runBlocking {
        val state = FakeStateStore(
            ordinaryState = InitialOrdinaryIndexingState.SKIPPED,
            rootDecision = InitialRootIndexingDecision.ACCEPTING,
            managedRootId = 7
        )
        val gateway = FakeGateway(rootCandidate = true).apply {
            roots += root(7, status = IndexScanStatus.COMPLETED)
        }
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertEquals(InitialRootIndexingDecision.ACCEPTING, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertEquals(1, gateway.ordinaryLocationReadCount)
        assertEquals(listOf(listOf(8L, 7L)), gateway.startedScans.map(Set<Long>::toList))
        gateway.assertExclusiveMode(IndexAccessMode.ROOT)

        gateway.finishRoot(8L, IndexScanStatus.COMPLETED)
        workflow.prepare()

        assertEquals(InitialRootIndexingDecision.ACCEPTED, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.COMPLETE, state.ordinaryState)
        assertFalse(state.isEligible)
    }

    @Test
    fun completedPrimaryStorageStillRequiresSystemRootScan() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        workflow.beginRootAcceptance()
        workflow.completeRootAcceptance()
        workflow.prepareOrdinary()
        gateway.finishRoot(2L, IndexScanStatus.COMPLETED)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertEquals(InitialRootIndexingDecision.ACCEPTING, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertEquals(
            listOf(listOf(2L, 1L), listOf(2L, 1L)),
            gateway.startedScans.map(Set<Long>::toList)
        )

        gateway.finishRoot(1L, IndexScanStatus.COMPLETED)
        workflow.prepare()

        assertEquals(InitialRootIndexingDecision.ACCEPTED, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.COMPLETE, state.ordinaryState)
        assertFalse(state.isEligible)
    }

    @Test
    fun acceptingAndCompleteStateRecoversEvenWhenManagedRootWasRemoved() = runBlocking {
        val state = FakeStateStore(
            ordinaryState = InitialOrdinaryIndexingState.COMPLETE,
            rootDecision = InitialRootIndexingDecision.ACCEPTING,
            managedOrdinaryPaths = setOf("/storage/emulated/0"),
            managedRootId = 1
        )
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertEquals(InitialRootIndexingDecision.ACCEPTED, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.COMPLETE, state.ordinaryState)
        assertFalse(state.isEligible)
        assertTrue(gateway.startedScans.isEmpty())
    }

    @Test
    fun cancellingCombinedRootScanDoesNotRestartTheOtherRoot() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        workflow.beginRootAcceptance()
        workflow.completeRootAcceptance()
        workflow.prepareOrdinary()
        gateway.finishRoot(2L, IndexScanStatus.CANCELLED)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertEquals(InitialRootIndexingDecision.ACCEPTED, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.COMPLETE, state.ordinaryState)
        assertFalse(state.isEligible)
        assertEquals(listOf(listOf(2L, 1L)), gateway.startedScans.map(Set<Long>::toList))
    }

    @Test
    fun legacyManagedStandardRootsAreRemovedBeforeRootScan() = runBlocking {
        val state = FakeStateStore(
            ordinaryState = InitialOrdinaryIndexingState.IN_PROGRESS,
            managedOrdinaryPaths = setOf("/storage/emulated/0", "/storage/1234-5678")
        )
        val gateway = FakeGateway(rootCandidate = true).apply {
            roots += storageRoot(10, "/storage/emulated/0")
            roots += storageRoot(11, "/storage/1234-5678")
        }
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())

        workflow.beginRootAcceptance()
        workflow.completeRootAcceptance()
        workflow.prepareOrdinary()

        assertEquals(setOf("/storage/emulated/0"), state.managedOrdinaryPaths)
        assertEquals(setOf(10L, 11L), gateway.removedRootIds.toSet())
        assertEquals(
            listOf("/", "/storage/emulated/0"),
            gateway.roots.map(IndexRoot::path)
        )
        gateway.assertExclusiveMode(IndexAccessMode.ROOT)
        assertEquals(listOf(listOf(2L, 1L)), gateway.startedScans.map(Set<Long>::toList))
        val rootScanEventIndex = gateway.events.indexOf("start:[1, 2]")
        assertTrue(rootScanEventIndex >= 0)
        assertTrue(gateway.events.indexOf("remove-root:10") in 0 until rootScanEventIndex)
        assertTrue(gateway.events.indexOf("remove-root:11") in 0 until rootScanEventIndex)
        assertEquals(1, gateway.ordinaryLocationReadCount)
    }

    @Test
    fun standardSelectionResumesAfterProcessDeathWithoutAnotherRootProbe() = runBlocking {
        val state = FakeStateStore(
            ordinaryState = InitialOrdinaryIndexingState.IN_PROGRESS,
            rootDecision = InitialRootIndexingDecision.UNAVAILABLE,
            managedOrdinaryPaths = setOf("/storage/emulated/0", "/storage/1234-5678")
        )
        val gateway = FakeGateway(rootCandidate = true).apply {
            roots += storageRoot(3, "/storage/emulated/0")
            roots += storageRoot(4, "/storage/1234-5678")
        }
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())
        workflow.prepareOrdinary()

        assertEquals(0, gateway.rootCandidateCheckCount)
        assertEquals(listOf(setOf(3L, 4L)), gateway.startedScans)
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
    }

    @Test
    fun ordinaryRootRemovedBeforeCompletionObservationIsNotRecreated() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = false)
        val workflow = InitialIndexingWorkflow(state, gateway)
        workflow.prepare()
        gateway.roots.removeAll { it.path == "/storage/1234-5678" }
        gateway.completeEveryRoot()

        workflow.prepare()

        assertFalse(gateway.roots.any { it.path == "/storage/1234-5678" })
        assertEquals(InitialOrdinaryIndexingState.COMPLETE, state.ordinaryState)
        assertFalse(state.isEligible)
    }

    @Test
    fun removedManagedSystemRootKeepsPrimaryStorageInRootMode() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        workflow.prepareRoot()
        workflow.beginRootAcceptance()
        workflow.completeRootAcceptance()
        workflow.prepareOrdinary()
        gateway.roots.removeAll { it.path == "/" }

        workflow.prepareRoot()
        workflow.prepareOrdinary()

        assertFalse(gateway.roots.any { it.path == "/" })
        assertEquals(1, gateway.rootSaveCount)
        assertEquals(InitialRootIndexingDecision.ACCEPTING, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(gateway.roots.all { it.accessMode == IndexAccessMode.ROOT })
        assertEquals(listOf("/storage/emulated/0"), gateway.roots.map(IndexRoot::path))
        assertEquals(2, gateway.startedScans.size)
        assertEquals(setOf(2L), gateway.startedScans.last())
    }

    @Test
    fun disabledManagedSystemRootStillScansPrimaryStorageWithRootAccess() = runBlocking {
        val state = FakeStateStore(
            ordinaryState = InitialOrdinaryIndexingState.SKIPPED,
            rootDecision = InitialRootIndexingDecision.ACCEPTING,
            managedRootId = 7
        )
        val gateway = FakeGateway(rootCandidate = true).apply {
            roots += root(7, isEnabled = false)
        }
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepareRoot())
        workflow.prepareOrdinary()

        assertEquals(InitialRootIndexingDecision.ACCEPTING, state.rootDecision)
        assertTrue(gateway.startedScans.none { 7L in it })
        val enabledRoots = gateway.roots.filter(IndexRoot::isEnabled)
        assertEquals(listOf("/storage/emulated/0"), enabledRoots.map(IndexRoot::path))
        assertTrue(enabledRoots.all { it.accessMode == IndexAccessMode.ROOT })
        assertEquals(
            listOf(enabledRoots.mapTo(linkedSetOf()) { checkNotNull(it.id) }),
            gateway.startedScans
        )
    }

    @Test
    fun failedInitialRootScanIsRetriedOnNextPreparation() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.OfferRoot, workflow.prepareRoot())
        workflow.beginRootAcceptance()
        workflow.completeRootAcceptance()
        workflow.prepareOrdinary()
        gateway.finishEveryRoot(IndexScanStatus.FAILED)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertEquals(InitialRootIndexingDecision.ACCEPTING, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertEquals(
            listOf(listOf(2L, 1L), listOf(2L, 1L)),
            gateway.startedScans.map(Set<Long>::toList)
        )
        gateway.assertExclusiveMode(IndexAccessMode.ROOT)
    }

    @Test
    fun failedInitialStandardScanIsRetriedOnNextPreparation() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = false)
        val workflow = InitialIndexingWorkflow(state, gateway)
        assertEquals(InitialIndexingAction.Idle, workflow.prepare())
        gateway.finishEveryRoot(IndexScanStatus.FAILED)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertEquals(InitialRootIndexingDecision.UNAVAILABLE, state.rootDecision)
        assertEquals(InitialOrdinaryIndexingState.IN_PROGRESS, state.ordinaryState)
        assertTrue(state.isEligible)
        assertEquals(
            listOf(setOf(1L, 2L), setOf(1L, 2L)),
            gateway.startedScans
        )
        gateway.assertExclusiveMode(IndexAccessMode.STANDARD)
    }

    @Test
    fun existingInstallationIsNeverModified() = runBlocking {
        val state = FakeStateStore(isEligible = false)
        val gateway = FakeGateway(rootCandidate = true)
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertTrue(gateway.roots.isEmpty())
        assertTrue(gateway.startedScans.isEmpty())
        assertFalse(gateway.rootVerified)
    }

    @Test
    fun restoredExistingConfigurationDisablesPendingOnboarding() = runBlocking {
        val state = FakeStateStore()
        val gateway = FakeGateway(rootCandidate = true).apply {
            roots += storageRoot(42, "/custom")
        }
        val workflow = InitialIndexingWorkflow(state, gateway)

        assertEquals(InitialIndexingAction.Idle, workflow.prepare())

        assertFalse(state.isEligible)
        assertEquals(InitialOrdinaryIndexingState.SKIPPED, state.ordinaryState)
        assertEquals(listOf("/custom"), gateway.roots.map(IndexRoot::path))
        assertTrue(gateway.startedScans.isEmpty())
    }

    private class FakeStateStore(
        override var isEligible: Boolean = true,
        override var ordinaryState: InitialOrdinaryIndexingState =
            InitialOrdinaryIndexingState.PENDING,
        override var rootDecision: InitialRootIndexingDecision =
            InitialRootIndexingDecision.UNKNOWN,
        override var managedOrdinaryPaths: Set<String> = emptySet(),
        override var managedRootId: Long? = null
    ) : InitialIndexingStateStore

    private class FakeGateway(
        private val rootCandidate: Boolean,
        private val rootVerificationError: Throwable? = null,
        var ordinaryLocationsError: Throwable? = null,
        var primaryStorageAvailable: Boolean = true,
        var hasRequiredStorageAccess: Boolean = true,
        var scanLaunchAllowed: Boolean = true
    ) : InitialIndexingGateway {
        val roots = mutableListOf<IndexRoot>()
        val startedScans = mutableListOf<Set<Long>>()
        val events = mutableListOf<String>()
        val removedRootIds = mutableListOf<Long>()
        var rootVerified = false
        var rootCandidateCheckCount = 0
        var ordinaryLocationReadCount = 0
        var ordinaryRootSaveCount = 0
        var rootVerificationCount = 0
        var rootSaveCount = 0

        override fun hasRequiredStorageAccess(): Boolean = hasRequiredStorageAccess

        override suspend fun getRoots(): List<IndexRoot> = roots.toList()

        override suspend fun getOrdinaryLocations(): List<InitialIndexLocation> {
            events += "get-ordinary-locations"
            ++ordinaryLocationReadCount
            ordinaryLocationsError?.let { throw it }
            return listOf(
                InitialIndexLocation(
                    "/storage/emulated/0",
                    "Internal storage",
                    isPrimary = true
                ),
                InitialIndexLocation(
                    "/storage/1234-5678",
                    "SD card",
                    isPrimary = false
                )
            ).filter { primaryStorageAvailable || !it.isPrimary }
        }

        override suspend fun saveStorageRoot(
            location: InitialIndexLocation,
            accessMode: IndexAccessMode
        ): Long {
            events += "save-storage:$accessMode:${location.path}"
            ++ordinaryRootSaveCount
            val existing = roots.firstOrNull { it.path == location.path }
            if (existing != null) {
                return checkNotNull(existing.id)
            }
            val id = nextId()
            roots += storageRoot(id, location.path, location.displayName, accessMode)
            return id
        }

        override suspend fun hasRootCandidate(): Boolean {
            events += "has-root-candidate"
            ++rootCandidateCheckCount
            return rootCandidate
        }

        override suspend fun verifyRootAccess() {
            events += "verify-root"
            ++rootVerificationCount
            rootVerificationError?.let { throw it }
            rootVerified = true
        }

        override suspend fun saveRoot(): Long {
            events += "save-root"
            ++rootSaveCount
            roots.firstOrNull { it.path == "/" }?.let { return checkNotNull(it.id) }
            val id = nextId()
            roots += root(id)
            return id
        }

        override suspend fun removeRoot(rootId: Long) {
            events += "remove-root:$rootId"
            check(roots.removeAll { it.id == rootId }) { "Root not found: $rootId" }
            removedRootIds += rootId
        }

        override fun startFullScan(rootIds: Set<Long>): Boolean {
            events += "start:${rootIds.sorted()}"
            if (scanLaunchAllowed) {
                startedScans += rootIds
            }
            return scanLaunchAllowed
        }

        fun completeEveryRoot() {
            finishEveryRoot(IndexScanStatus.COMPLETED)
        }

        fun finishEveryRoot(status: IndexScanStatus) {
            roots.indices.forEach { index ->
                roots[index] = roots[index].copy(
                    lastScanCompletedAtMillis = 2,
                    lastScanStatus = status,
                    scanGeneration = 1
                )
            }
        }

        fun finishRoot(rootId: Long, status: IndexScanStatus) {
            val index = roots.indexOfFirst { it.id == rootId }
            check(index >= 0) { "Root not found: $rootId" }
            roots[index] = roots[index].copy(
                lastScanCompletedAtMillis = 2,
                lastScanStatus = status,
                scanGeneration = 1
            )
        }

        fun assertExclusiveMode(expected: IndexAccessMode) {
            assertTrue(roots.isNotEmpty())
            assertTrue(roots.all { it.accessMode == expected })
            val rootsById = roots.associateBy { checkNotNull(it.id) }
            assertTrue(
                startedScans.flatten().all { rootId ->
                    rootsById[rootId]?.accessMode == expected
                }
            )
            if (expected == IndexAccessMode.ROOT) {
                assertEquals(
                    setOf("/", "/storage/emulated/0"),
                    roots.mapTo(linkedSetOf(), IndexRoot::path)
                )
            } else {
                assertFalse(roots.any { it.path == "/" })
            }
        }

        private fun nextId(): Long =
            (roots.maxOfOrNull { checkNotNull(it.id) } ?: 0L) + 1
    }

    private companion object {
        fun storageRoot(
            id: Long,
            path: String,
            displayName: String = path,
            accessMode: IndexAccessMode = IndexAccessMode.STANDARD
        ) = IndexRoot(
            id = id,
            path = path,
            displayName = displayName,
            accessMode = accessMode,
            createdAtMillis = 1
        )

        fun root(
            id: Long,
            isEnabled: Boolean = true,
            status: IndexScanStatus = IndexScanStatus.NEVER_RUN
        ) = IndexRoot(
            id = id,
            path = "/",
            displayName = "Root",
            accessMode = IndexAccessMode.ROOT,
            isEnabled = isEnabled,
            lastScanStatus = status,
            lastScanCompletedAtMillis = if (status == IndexScanStatus.COMPLETED) 2 else null,
            scanGeneration = if (status == IndexScanStatus.COMPLETED) 1 else 0,
            createdAtMillis = 1
        )
    }
}
