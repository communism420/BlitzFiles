/*
 * Copyright (c) 2026 BlitzFiles contributors
 * All Rights Reserved.
 */

package com.blitzfiles.app.indexing

import com.blitzfiles.app.file.MimeType
import com.blitzfiles.app.file.guessFromPath
import com.blitzfiles.app.provider.linux.LinuxFileKey
import com.blitzfiles.app.provider.linux.LinuxFileSystemProvider
import com.blitzfiles.app.provider.remote.filesAcceptAllFilter
import com.blitzfiles.search.domain.indexer.IndexFileMetadata
import com.blitzfiles.search.domain.indexer.IndexFileSystem
import com.blitzfiles.search.domain.model.IndexAccessMode
import java.io.IOException
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.InvalidPathException
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes

/**
 * Bridges the indexer to Material Files' Linux providers.
 *
 * Provider selection is explicit, so a root configured with [IndexAccessMode.ROOT] never silently
 * falls back to the process-local provider.
 */
class MaterialFilesIndexFileSystem : IndexFileSystem {
    override fun normalize(path: String, accessMode: IndexAccessMode): String =
        path.toLinuxPath().toString()

    override fun readMetadata(
        path: String,
        accessMode: IndexAccessMode,
        followSymbolicLinks: Boolean
    ): IndexFileMetadata? {
        val linuxPath = path.toLinuxPath()
        val provider = accessMode.provider
        val noFollowAttributes = try {
            provider.readAttributes(
                linuxPath,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
        } catch (_: NoSuchFileException) {
            return null
        }
        val isSymbolicLink = noFollowAttributes.isSymbolicLink
        val effectiveAttributes = if (isSymbolicLink && followSymbolicLinks) {
            try {
                provider.readAttributes(linuxPath, BasicFileAttributes::class.java)
            } catch (error: Throwable) {
                if (error !is IOException && error !is SecurityException) {
                    throw error
                }
                noFollowAttributes
            }
        } else {
            noFollowAttributes
        }
        val fileKey = effectiveAttributes.fileKey() as? LinuxFileKey
        val normalizedPath = linuxPath.toString()
        val name = linuxPath.fileName?.toString() ?: normalizedPath
        val isDirectory = effectiveAttributes.isDirectory
        val symbolicLinkTarget = if (isSymbolicLink) {
            val opaqueTarget = try {
                provider.readSymbolicLink(linuxPath)
            } catch (error: Throwable) {
                if (
                    error !is IOException &&
                    error !is SecurityException &&
                    error !is UnsupportedOperationException
                ) {
                    throw error
                }
                null
            }
            if (opaqueTarget != null) {
                try {
                    resolveSymbolicLinkTarget(linuxPath, opaqueTarget)
                } catch (_: InvalidPathException) {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
        val createdAtMillis = try {
            effectiveAttributes.creationTime().toMillis().coerceAtLeast(0)
        } catch (_: UnsupportedOperationException) {
            null
        }
        return IndexFileMetadata(
            path = normalizedPath,
            parentPath = linuxPath.parent?.toString().orEmpty(),
            name = name,
            extension = if (isDirectory) null else name.extensionOrNull(),
            mimeType = if (isDirectory) {
                MimeType.DIRECTORY.value
            } else {
                MimeType.guessFromPath(normalizedPath).value
            },
            sizeBytes = effectiveAttributes.size().coerceAtLeast(0),
            modifiedAtMillis = effectiveAttributes.lastModifiedTime().toMillis().coerceAtLeast(0),
            createdAtMillis = createdAtMillis,
            isDirectory = isDirectory,
            isSymbolicLink = isSymbolicLink,
            isHidden = name.startsWith('.'),
            symbolicLinkTarget = symbolicLinkTarget,
            deviceId = fileKey?.deviceId,
            inode = fileKey?.inodeNumber
        )
    }

    override fun visitChildren(
        directoryPath: String,
        accessMode: IndexAccessMode,
        visitor: (String) -> Unit
    ) {
        val directory = directoryPath.toLinuxPath()
        try {
            accessMode.provider.newDirectoryStream(directory, filesAcceptAllFilter).use { stream ->
                stream.forEach { child ->
                    visitor(child.toAbsolutePath().normalize().toString())
                }
            }
        } catch (error: DirectoryIteratorException) {
            throw error.cause as? IOException ?: error
        }
    }
}

private val IndexAccessMode.provider
    get() = LinuxFileSystemProvider.providerForIndexing(this == IndexAccessMode.ROOT)

/**
 * Converts the provider's opaque readlink result back into a fully functional Linux path.
 *
 * The Linux provider intentionally returns a ByteStringPath from readSymbolicLink(). That wrapper
 * only supports byte-preserving conversion to String and throws UnsupportedOperationException for
 * regular Path operations such as isAbsolute() and normalize().
 */
internal fun resolveSymbolicLinkTarget(link: Path, opaqueTarget: Path): String {
    val target = link.fileSystem.getPath(opaqueTarget.toString())
    val resolvedTarget = if (target.isAbsolute) {
        target
    } else {
        link.parent?.resolve(target) ?: target
    }
    return resolvedTarget.toAbsolutePath().normalize().toString()
}

private fun String.toLinuxPath(): Path =
    Paths.get(this).toAbsolutePath().normalize()

private fun String.extensionOrNull(): String? {
    val dotIndex = lastIndexOf('.')
    return if (dotIndex > 0 && dotIndex < lastIndex) substring(dotIndex + 1) else null
}
