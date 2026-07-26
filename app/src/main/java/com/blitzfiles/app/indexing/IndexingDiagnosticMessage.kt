/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import android.content.Context
import com.blitzfiles.app.R
import com.blitzfiles.search.domain.model.IndexAccessMode

/**
 * A diagnostic emitted by BlitzFiles indexing code that is safe to translate at the UI boundary.
 *
 * Filesystem and operating-system diagnostics are intentionally not represented here. Unknown
 * messages must remain verbatim because translating or rewriting them could hide useful details.
 */
internal sealed interface IndexingDiagnosticMessage {
    data object Interrupted : IndexingDiagnosticMessage

    data object AllFilesAccessRequired : IndexingDiagnosticMessage

    data class RootNotFound(val reference: String) : IndexingDiagnosticMessage

    data object EntriesUnreadable : IndexingDiagnosticMessage

    data class RecoverableErrors(
        val cause: IndexingDiagnosticMessage?,
        val rawCause: String,
        val count: Long
    ) : IndexingDiagnosticMessage

    data class RootDisabled(val reference: String) : IndexingDiagnosticMessage

    data class IncrementalPathOutsideRoot(val rootPath: String) : IndexingDiagnosticMessage

    data class ProtectedSystemPath(val path: String) : IndexingDiagnosticMessage

    data object AccessModeChangeWhileScanning : IndexingDiagnosticMessage

    data object FilesystemRootRequiresRootAccess : IndexingDiagnosticMessage

    data class MixedAccessModes(
        val path: String,
        val accessMode: IndexAccessMode
    ) : IndexingDiagnosticMessage
}

/**
 * Parses only diagnostics authored by BlitzFiles.
 *
 * Returning `null` is deliberate: callers preserve an unknown message exactly as received.
 */
internal fun parseIndexingDiagnosticMessage(rawMessage: String): IndexingDiagnosticMessage? {
    return parseIndexingDiagnosticMessage(rawMessage, compositeDepth = 0)
}

private fun parseIndexingDiagnosticMessage(
    rawMessage: String,
    compositeDepth: Int
): IndexingDiagnosticMessage? {
    if (compositeDepth < MAX_COMPOSITE_DEPTH) {
        RECOVERABLE_ERRORS_REGEX.matchEntire(rawMessage)?.let { match ->
            val count = match.groupValues[2].toLongOrNull() ?: return null
            val rawCause = match.groupValues[1]
            return IndexingDiagnosticMessage.RecoverableErrors(
                cause = parseIndexingDiagnosticMessage(rawCause, compositeDepth + 1),
                rawCause = rawCause,
                count = count
            )
        }
    }

    return when (rawMessage) {
        INDEXING_INTERRUPTED,
        INDEXING_STOPPED_WITHOUT_RESULT -> IndexingDiagnosticMessage.Interrupted
        ALL_FILES_ACCESS_REQUIRED,
        STORAGE_ACCESS_REVOKED,
        ALL_FILES_ACCESS_REQUIRED_BEFORE_DETECTING_ROOT,
        ALL_FILES_ACCESS_REQUIRED_BEFORE_REQUESTING_ROOT,
        ALL_FILES_ACCESS_REVOKED_WHILE_ENABLING_ROOT,
        ALL_FILES_ACCESS_INTRODUCTION_REQUIRED ->
            IndexingDiagnosticMessage.AllFilesAccessRequired
        SOME_ENTRIES_UNREADABLE -> IndexingDiagnosticMessage.EntriesUnreadable
        ACCESS_MODE_CHANGE_WHILE_SCANNING ->
            IndexingDiagnosticMessage.AccessModeChangeWhileScanning
        FILESYSTEM_ROOT_REQUIRES_ROOT_ACCESS,
        ROOT_DIRECTORY_NOT_ACCESSIBLE,
        ROOT_IS_NOT_AVAILABLE ->
            IndexingDiagnosticMessage.FilesystemRootRequiresRootAccess
        else -> parseParameterizedIndexingDiagnostic(rawMessage)
    }
}

private fun parseParameterizedIndexingDiagnostic(
    rawMessage: String
): IndexingDiagnosticMessage? {
    ROOT_DOES_NOT_EXIST_REGEX.matchEntire(rawMessage)?.let { match ->
        return IndexingDiagnosticMessage.RootNotFound(match.groupValues[1])
    }
    ROOT_NOT_FOUND_REGEX.matchEntire(rawMessage)?.let { match ->
        return IndexingDiagnosticMessage.RootNotFound(match.groupValues[1])
    }
    ROOT_DISABLED_REGEX.matchEntire(rawMessage)?.let { match ->
        return IndexingDiagnosticMessage.RootDisabled(match.groupValues[1])
    }
    INCREMENTAL_PATH_OUTSIDE_ROOT_REGEX.matchEntire(rawMessage)?.let { match ->
        return IndexingDiagnosticMessage.IncrementalPathOutsideRoot(match.groupValues[1])
    }
    PROTECTED_SYSTEM_PATH_REGEX.matchEntire(rawMessage)?.let { match ->
        return IndexingDiagnosticMessage.ProtectedSystemPath(match.groupValues[1])
    }
    MIXED_ACCESS_MODES_REGEX.matchEntire(rawMessage)?.let { match ->
        val accessMode = when (match.groupValues[2]) {
            IndexAccessMode.STANDARD.name -> IndexAccessMode.STANDARD
            IndexAccessMode.ROOT.name -> IndexAccessMode.ROOT
            else -> return null
        }
        return IndexingDiagnosticMessage.MixedAccessModes(
            path = match.groupValues[1],
            accessMode = accessMode
        )
    }
    return null
}

internal fun Context.renderIndexingDiagnosticMessage(
    message: IndexingDiagnosticMessage
): String =
    when (message) {
        IndexingDiagnosticMessage.Interrupted ->
            getString(R.string.indexing_diagnostic_interrupted)
        IndexingDiagnosticMessage.AllFilesAccessRequired ->
            getString(R.string.indexing_all_files_access_required)
        is IndexingDiagnosticMessage.RootNotFound ->
            getString(R.string.indexing_diagnostic_root_not_found_format, message.reference)
        IndexingDiagnosticMessage.EntriesUnreadable ->
            getString(R.string.indexing_diagnostic_entries_unreadable)
        is IndexingDiagnosticMessage.RecoverableErrors -> {
            val cause = message.cause?.let { renderIndexingDiagnosticMessage(it) }
                ?: message.rawCause
            resources.getQuantityString(
                R.plurals.indexing_diagnostic_recoverable_errors_format,
                message.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                cause,
                message.count
            )
        }
        is IndexingDiagnosticMessage.RootDisabled ->
            getString(R.string.indexing_diagnostic_root_disabled_format, message.reference)
        is IndexingDiagnosticMessage.IncrementalPathOutsideRoot ->
            getString(
                R.string.indexing_diagnostic_incremental_path_outside_root_format,
                message.rootPath
            )
        is IndexingDiagnosticMessage.ProtectedSystemPath ->
            getString(R.string.indexing_protected_path_error)
        IndexingDiagnosticMessage.AccessModeChangeWhileScanning ->
            getString(R.string.indexing_diagnostic_access_mode_change_while_scanning)
        IndexingDiagnosticMessage.FilesystemRootRequiresRootAccess ->
            getString(R.string.indexing_root_requires_root_error)
        is IndexingDiagnosticMessage.MixedAccessModes -> {
            val accessMode = getString(
                when (message.accessMode) {
                    IndexAccessMode.STANDARD -> R.string.indexing_access_standard
                    IndexAccessMode.ROOT -> R.string.indexing_access_root
                }
            )
            getString(
                R.string.indexing_diagnostic_mixed_access_modes_format,
                message.path,
                accessMode
            )
        }
    }

internal fun Context.localizeIndexingDiagnosticMessage(rawMessage: String): String =
    parseIndexingDiagnosticMessage(rawMessage)
        ?.let { renderIndexingDiagnosticMessage(it) }
        ?: rawMessage

private const val INDEXING_INTERRUPTED = "Indexing was interrupted before completion"
private const val INDEXING_STOPPED_WITHOUT_RESULT =
    "Indexing stopped before producing a final result"
private const val ALL_FILES_ACCESS_REQUIRED = "All files access is required for indexing"
private const val STORAGE_ACCESS_REVOKED =
    "Required storage access was revoked during indexing"
private const val ALL_FILES_ACCESS_REQUIRED_BEFORE_DETECTING_ROOT =
    "All files access is required before detecting root access"
private const val ALL_FILES_ACCESS_REQUIRED_BEFORE_REQUESTING_ROOT =
    "All files access is required before requesting root access"
private const val ALL_FILES_ACCESS_REVOKED_WHILE_ENABLING_ROOT =
    "All files access was revoked while enabling root indexing"
private const val ALL_FILES_ACCESS_INTRODUCTION_REQUIRED =
    "The All files access introduction must finish before requesting root access"
private const val SOME_ENTRIES_UNREADABLE = "Some entries could not be read"
private const val ACCESS_MODE_CHANGE_WHILE_SCANNING =
    "Access mode cannot be changed while this location is being scanned"
private const val FILESYSTEM_ROOT_REQUIRES_ROOT_ACCESS =
    "The filesystem root cannot be indexed without root access"
private const val ROOT_DIRECTORY_NOT_ACCESSIBLE = "Root directory is not accessible"
private const val ROOT_IS_NOT_AVAILABLE = "Root isn't available"
private const val MAX_COMPOSITE_DEPTH = 16

private val RECOVERABLE_ERRORS_REGEX =
    Regex("""(.+) \(([0-9]+) recoverable errors?\)""", RegexOption.DOT_MATCHES_ALL)
private val ROOT_DOES_NOT_EXIST_REGEX =
    Regex("""Index root does not exist: (.+)""", RegexOption.DOT_MATCHES_ALL)
private val ROOT_NOT_FOUND_REGEX = Regex(
    """(?:Index root was not found|Enabled index root was not found|""" +
        """Index root was not found after scan start): (.+)""",
    RegexOption.DOT_MATCHES_ALL
)
private val ROOT_DISABLED_REGEX =
    Regex("""Index root is disabled: (.+)""", RegexOption.DOT_MATCHES_ALL)
private val INCREMENTAL_PATH_OUTSIDE_ROOT_REGEX =
    Regex(
        """Every incremental path must be inside root (.+)""",
        RegexOption.DOT_MATCHES_ALL
    )
private val PROTECTED_SYSTEM_PATH_REGEX =
    Regex(
        """Protected system paths cannot be indexed directly: (.+)""",
        RegexOption.DOT_MATCHES_ALL
    )
private val MIXED_ACCESS_MODES_REGEX =
    Regex(
        """Every indexed location must use the same access mode; (.+) uses (STANDARD|ROOT)""",
        RegexOption.DOT_MATCHES_ALL
    )
