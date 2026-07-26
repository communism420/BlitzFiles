/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.blitzfiles.app.filelist

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.blitzfiles.app.app.application
import com.blitzfiles.app.file.FileItem
import com.blitzfiles.app.file.loadFileItem
import com.blitzfiles.app.filejob.DeletedPathPrefixes
import com.blitzfiles.app.filejob.toDeletionUriKey
import com.blitzfiles.app.globalsearch.GlobalSearchRuntime
import com.blitzfiles.app.provider.common.search
import com.blitzfiles.app.provider.linux.isLinuxPath
import com.blitzfiles.app.search.toEffectiveSearchQuery
import com.blitzfiles.app.util.CloseableLiveData
import com.blitzfiles.app.util.Failure
import com.blitzfiles.app.util.Loading
import com.blitzfiles.app.util.Stateful
import com.blitzfiles.app.util.Success
import com.blitzfiles.search.domain.model.SearchRequest
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class SearchFileListLiveData(
    private val path: Path,
    query: String,
    private val initialFiles: List<FileItem> = emptyList(),
    private val initialDebounceLiveWalk: Boolean = true
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private val query = query.toEffectiveSearchQuery()
    private var future: Future<Unit>? = null
    @Volatile
    private var deletedPaths = DeletedPathPrefixes.EMPTY
    private val loadGeneration = AtomicLong()
    private var hasStartedLoad = false
    private var restartWhenActive = false
    @Volatile
    var isResultTruncated = false
        private set

    init {
        loadValue(initialDebounceLiveWalk)
    }

    fun loadValue(debounceLiveWalk: Boolean = true) {
        val generation = loadGeneration.incrementAndGet()
        future?.cancel(true)
        isResultTruncated = false
        if (query.isBlank()) {
            value = Success(emptyList())
            future = null
            return
        }
        val loadingFiles = if (hasStartedLoad) {
            value?.value.orEmpty()
        } else {
            initialFiles
        }
        hasStartedLoad = true
        value = Loading(loadingFiles)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> task@ {
            val fileList = mutableListOf<FileItem>()
            try {
                require(query.length <= SearchRequest.MAX_QUERY_LENGTH) {
                    "Search query must not exceed ${SearchRequest.MAX_QUERY_LENGTH} UTF-16 code units"
                }
                require('\u0000' !in query) { "Search query must not contain NUL" }
                val consumePaths = consumePaths@ { paths: List<Path> ->
                    if (loadGeneration.get() != generation) {
                        return@consumePaths
                    }
                    for (path in paths) {
                        if (loadGeneration.get() != generation) {
                            return@consumePaths
                        }
                        throwIfInterrupted()
                        if (deletedPaths.containsUri(path.toDeletionUriKey())) {
                            continue
                        }
                        val fileItem = try {
                            path.loadFileItem()
                        } catch (e: InterruptedIOException) {
                            throw e
                        } catch (e: IOException) {
                            throwIfInterrupted(e)
                            e.printStackTrace()
                            // TODO: Support file without information.
                            continue
                        }
                        if (loadGeneration.get() != generation) {
                            return@consumePaths
                        }
                        fileList.add(fileItem)
                    }
                    postCurrentValue(generation, Loading(fileList.toList()))
                }
                val usedIndex = trySearchConfiguredRootIndex(consumePaths)
                if (!usedIndex) {
                    if (debounceLiveWalk) {
                        // Delay only the expensive live traversal. Replacing this LiveData on the
                        // next keystroke interrupts the wait immediately, so stale walks never
                        // continue while the user is typing.
                        Thread.sleep(LIVE_SEARCH_DEBOUNCE_MILLIS)
                    }
                    throwIfInterrupted()
                    path.search(query, INTERVAL_MILLIS, consumePaths)
                }
                postCurrentValue(generation, Success(fileList))
            } catch (_: CancellationException) {
                return@task
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@task
            } catch (_: InterruptedIOException) {
                Thread.currentThread().interrupt()
                return@task
            } catch (e: Exception) {
                postCurrentValue(generation, Failure(fileList.toList(), e))
            }
        }
    }

    private fun trySearchConfiguredRootIndex(consumePaths: (List<Path>) -> Unit): Boolean {
        if (!path.isLinuxPath) {
            return false
        }
        val directoryPath = path.toAbsolutePath().normalize().toString()
        var publishedBatch = false
        return try {
            runBlocking {
                GlobalSearchRuntime.get(application).searchIndexedRootDirectory(
                    directoryPath = directoryPath,
                    query = query,
                    maxResults = MAX_ROOT_DIRECTORY_RESULTS,
                    onTruncated = { isResultTruncated = true }
                ) { indexedPaths ->
                    publishedBatch = true
                    consumePaths(
                        indexedPaths.map { indexedPath ->
                            path.fileSystem.getPath(indexedPath)
                        }
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Invalid indexed queries must fail fast instead of starting a full live walk of "/".
            throw e
        } catch (e: Exception) {
            if (publishedBatch) {
                // Falling back after a partial indexed page would duplicate already visible rows.
                throw e
            }
            // A missing or temporarily unavailable index must not break legacy directory search.
            e.printStackTrace()
            false
        }
    }

    private fun postCurrentValue(generation: Long, state: Stateful<List<FileItem>>) {
        MAIN_HANDLER.post {
            if (loadGeneration.get() == generation) {
                value = state.withoutDeletedPaths(deletedPaths)
            }
        }
    }

    internal fun updateDeletedPaths(deletedPaths: DeletedPathPrefixes) {
        if (this.deletedPaths == deletedPaths) {
            return
        }
        this.deletedPaths = deletedPaths
        value = value?.withoutDeletedPaths(deletedPaths)
        if (hasActiveObservers()) {
            loadValue(debounceLiveWalk = false)
        } else {
            restartWhenActive = true
            cancelCurrentWork()
        }
    }

    override fun onActive() {
        super.onActive()
        if (restartWhenActive) {
            restartWhenActive = false
            loadValue()
        }
    }

    override fun onInactive() {
        val runningFuture = future
        if (runningFuture != null && !runningFuture.isDone) {
            restartWhenActive = true
            cancelCurrentWork()
        }
        super.onInactive()
    }

    override fun close() {
        restartWhenActive = false
        cancelCurrentWork()
    }

    private fun cancelCurrentWork() {
        loadGeneration.incrementAndGet()
        future?.cancel(true)
        future = null
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L
        private const val MAX_ROOT_DIRECTORY_RESULTS = SearchRequest.MAX_LIMIT
        private const val LIVE_SEARCH_DEBOUNCE_MILLIS = 1_000L
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}

@Throws(InterruptedIOException::class)
private fun throwIfInterrupted(cause: Throwable? = null) {
    if (Thread.currentThread().isInterrupted) {
        throw InterruptedIOException("Directory search was interrupted").apply {
            if (cause != null) {
                initCause(cause)
            }
        }
    }
}
