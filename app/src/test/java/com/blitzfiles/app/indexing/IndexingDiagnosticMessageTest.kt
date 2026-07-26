/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.search.domain.model.IndexAccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexingDiagnosticMessageTest {
    @Test
    fun interruptedAliasesShareOneDiagnostic() {
        assertEquals(
            IndexingDiagnosticMessage.Interrupted,
            parseIndexingDiagnosticMessage("Indexing was interrupted before completion")
        )
        assertEquals(
            IndexingDiagnosticMessage.Interrupted,
            parseIndexingDiagnosticMessage(
                "Indexing stopped before producing a final result"
            )
        )
    }

    @Test
    fun storageAccessAliasesShareOneDiagnostic() {
        listOf(
            "All files access is required for indexing",
            "Required storage access was revoked during indexing",
            "All files access is required before detecting root access",
            "All files access is required before requesting root access",
            "All files access was revoked while enabling root indexing",
            "The All files access introduction must finish before requesting root access"
        ).forEach { rawMessage ->
            assertEquals(
                IndexingDiagnosticMessage.AllFilesAccessRequired,
                parseIndexingDiagnosticMessage(rawMessage)
            )
        }
    }

    @Test
    fun unavailableRootAliasesShareOneDiagnostic() {
        listOf(
            "The filesystem root cannot be indexed without root access",
            "Root directory is not accessible",
            "Root isn't available"
        ).forEach { rawMessage ->
            assertEquals(
                IndexingDiagnosticMessage.FilesystemRootRequiresRootAccess,
                parseIndexingDiagnosticMessage(rawMessage)
            )
        }
    }

    @Test
    fun missingRootAliasesPreserveTheirReference() {
        assertEquals(
            IndexingDiagnosticMessage.RootNotFound("/storage/emulated/0"),
            parseIndexingDiagnosticMessage(
                "Index root does not exist: /storage/emulated/0"
            )
        )
        assertEquals(
            IndexingDiagnosticMessage.RootNotFound("42"),
            parseIndexingDiagnosticMessage("Index root was not found: 42")
        )
        assertEquals(
            IndexingDiagnosticMessage.RootNotFound("43"),
            parseIndexingDiagnosticMessage("Enabled index root was not found: 43")
        )
        assertEquals(
            IndexingDiagnosticMessage.RootNotFound("44"),
            parseIndexingDiagnosticMessage(
                "Index root was not found after scan start: 44"
            )
        )
    }

    @Test
    fun entriesUnreadableIsRecognized() {
        assertEquals(
            IndexingDiagnosticMessage.EntriesUnreadable,
            parseIndexingDiagnosticMessage("Some entries could not be read")
        )
    }

    @Test
    fun recoverableErrorCompositeRecursivelyParsesItsCause() {
        val innerRaw = "Some entries could not be read (2 recoverable errors)"
        val inner = IndexingDiagnosticMessage.RecoverableErrors(
            cause = IndexingDiagnosticMessage.EntriesUnreadable,
            rawCause = "Some entries could not be read",
            count = 2
        )

        assertEquals(
            IndexingDiagnosticMessage.RecoverableErrors(
                cause = inner,
                rawCause = innerRaw,
                count = 3
            ),
            parseIndexingDiagnosticMessage("$innerRaw (3 recoverable errors)")
        )
    }

    @Test
    fun recoverableErrorCompositePreservesAnUnknownCause() {
        assertEquals(
            IndexingDiagnosticMessage.RecoverableErrors(
                cause = null,
                rawCause = "/vendor: Permission denied",
                count = 12
            ),
            parseIndexingDiagnosticMessage(
                "/vendor: Permission denied (12 recoverable errors)"
            )
        )
    }

    @Test
    fun disabledRootIsRecognized() {
        assertEquals(
            IndexingDiagnosticMessage.RootDisabled("7"),
            parseIndexingDiagnosticMessage("Index root is disabled: 7")
        )
    }

    @Test
    fun incrementalPathOutsideRootIsRecognized() {
        assertEquals(
            IndexingDiagnosticMessage.IncrementalPathOutsideRoot("/storage/emulated/0"),
            parseIndexingDiagnosticMessage(
                "Every incremental path must be inside root /storage/emulated/0"
            )
        )
    }

    @Test
    fun protectedSystemPathIsRecognized() {
        assertEquals(
            IndexingDiagnosticMessage.ProtectedSystemPath("/proc"),
            parseIndexingDiagnosticMessage(
                "Protected system paths cannot be indexed directly: /proc"
            )
        )
    }

    @Test
    fun accessModeChangeWhileScanningIsRecognized() {
        assertEquals(
            IndexingDiagnosticMessage.AccessModeChangeWhileScanning,
            parseIndexingDiagnosticMessage(
                "Access mode cannot be changed while this location is being scanned"
            )
        )
    }

    @Test
    fun mixedAccessModesRecognizeBothModeNames() {
        assertEquals(
            IndexingDiagnosticMessage.MixedAccessModes(
                path = "/storage/emulated/0",
                accessMode = IndexAccessMode.STANDARD
            ),
            parseIndexingDiagnosticMessage(
                "Every indexed location must use the same access mode; " +
                    "/storage/emulated/0 uses STANDARD"
            )
        )
        assertEquals(
            IndexingDiagnosticMessage.MixedAccessModes(
                path = "/mnt/vendor uses legacy",
                accessMode = IndexAccessMode.ROOT
            ),
            parseIndexingDiagnosticMessage(
                "Every indexed location must use the same access mode; " +
                    "/mnt/vendor uses legacy uses ROOT"
            )
        )
    }

    @Test
    fun unknownDiagnosticsReturnNullForVerbatimPassthrough() {
        assertNull(parseIndexingDiagnosticMessage("/vendor: Permission denied"))
        assertNull(
            parseIndexingDiagnosticMessage(
                "Every indexed location must use the same access mode; /data uses UNKNOWN"
            )
        )
        assertNull(
            parseIndexingDiagnosticMessage(
                "Some entries could not be read (999999999999999999999 recoverable errors)"
            )
        )
    }
}
