@file:Suppress("TooGenericExceptionCaught")

package com.alexandr5476.lifetracing.library

import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryRoot
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

sealed interface LibraryLoad<out T> {
    data object Loading : LibraryLoad<Nothing>

    data class Content<T>(
        val value: T,
    ) : LibraryLoad<T>

    data class Failure(
        val message: String,
    ) : LibraryLoad<Nothing>
}

data class LibraryBrowse(
    val folderId: FolderId?,
    val path: List<Folder>,
    val pinned: List<LibraryTrackable>,
    val contents: LibraryContents,
)

data class LibraryPresentationState(
    val browse: LibraryLoad<LibraryBrowse> = LibraryLoad.Loading,
    val folderId: FolderId? = null,
    val query: String = "",
    val filter: LibraryKindFilter = LibraryKindFilter.ALL,
    val search: LibraryLoad<List<LibraryTrackable>>? = null,
)

sealed interface LibraryAction {
    data object Retry : LibraryAction

    data object Back : LibraryAction

    data class OpenFolder(
        val id: FolderId,
    ) : LibraryAction

    data class Search(
        val query: String,
    ) : LibraryAction

    data class SetFilter(
        val filter: LibraryKindFilter,
    ) : LibraryAction
}

class LibraryController internal constructor(
    private val scope: CoroutineScope,
    private val readRoot: suspend () -> LibraryRoot,
    private val readFolderContents: suspend (FolderId) -> LibraryContents,
    private val readFolderPath: suspend (FolderId) -> List<Folder>,
    private val searchLibrary: suspend (String, LibraryKindFilter) -> List<LibraryTrackable>,
) {
    private val browseGeneration = AtomicLong()
    private val searchGeneration = AtomicLong()
    private val mutableState = MutableStateFlow(LibraryPresentationState())
    val state: StateFlow<LibraryPresentationState> = mutableState

    @Volatile
    private var closed = false

    init {
        loadRoot()
    }

    fun dispatch(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> retry()
            LibraryAction.Back -> back()
            is LibraryAction.OpenFolder -> loadFolder(action.id)
            is LibraryAction.Search -> search(action.query)
            is LibraryAction.SetFilter -> setFilter(action.filter)
        }
    }

    fun close() {
        closed = true
        browseGeneration.incrementAndGet()
        searchGeneration.incrementAndGet()
    }

    private fun retry() {
        mutableState.value.folderId?.let(::loadFolder) ?: loadRoot()
        if (mutableState.value.search is LibraryLoad.Failure) search(mutableState.value.query)
    }

    private fun back() {
        val folderId = mutableState.value.folderId ?: return
        val browse = (mutableState.value.browse as? LibraryLoad.Content)?.value
        if (browse != null) {
            browse.path
                .dropLast(1)
                .lastOrNull()
                ?.id
                ?.let(::loadFolder) ?: loadRoot()
            return
        }
        scope.launch {
            try {
                readFolderPath(folderId)
                    .dropLast(1)
                    .lastOrNull()
                    ?.id
                    ?.let(::loadFolder) ?: loadRoot()
            } catch (_: Exception) {
                loadRoot()
            }
        }
    }

    private fun loadRoot() = load(null)

    private fun loadFolder(folderId: FolderId) = load(folderId)

    private fun load(folderId: FolderId?) {
        val generation = browseGeneration.incrementAndGet()
        mutableState.update { it.copy(folderId = folderId, browse = LibraryLoad.Loading) }
        scope.launch {
            try {
                val browse =
                    if (folderId == null) {
                        readRoot().toBrowse()
                    } else {
                        LibraryBrowse(folderId, readFolderPath(folderId), emptyList(), readFolderContents(folderId))
                    }
                if (!closed && generation == browseGeneration.get()) {
                    mutableState.update { it.copy(browse = LibraryLoad.Content(browse.activeOnly())) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && generation == browseGeneration.get()) {
                    mutableState.update { it.copy(browse = LibraryLoad.Failure(failure.message ?: "Unknown error")) }
                }
            }
        }
    }

    private fun setFilter(filter: LibraryKindFilter) {
        if (mutableState.value.filter == filter) return
        mutableState.update { it.copy(filter = filter) }
        if (mutableState.value.query.isNotBlank()) search(mutableState.value.query)
    }

    private fun search(query: String) {
        val generation = searchGeneration.incrementAndGet()
        mutableState.update {
            it.copy(query = query, search = if (query.isBlank()) null else LibraryLoad.Loading)
        }
        if (query.isBlank() || closed) return
        val filter = mutableState.value.filter
        scope.launch {
            try {
                val results = searchLibrary(query, filter).filterNot(LibraryTrackable::isArchived)
                if (!closed && generation == searchGeneration.get()) {
                    mutableState.update { it.copy(search = LibraryLoad.Content(results)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && generation == searchGeneration.get()) {
                    mutableState.update { it.copy(search = LibraryLoad.Failure(failure.message ?: "Unknown error")) }
                }
            }
        }
    }
}

private fun LibraryRoot.toBrowse() = LibraryBrowse(null, emptyList(), pinned, contents)

private fun LibraryBrowse.activeOnly() =
    copy(
        pinned = pinned.filterNot(LibraryTrackable::isArchived),
        contents =
            contents.copy(
                activities = contents.activities.filterNot(LibraryTrackable::isArchived),
                sequences = contents.sequences.filterNot(LibraryTrackable::isArchived),
            ),
    )
