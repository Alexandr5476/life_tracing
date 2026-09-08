@file:Suppress(
    "CyclomaticComplexMethod",
    "LongParameterList",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.library

import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryRoot
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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

data class LibraryOrganization(
    val folders: List<Folder>,
    val tags: List<Tag>,
)

data class LibraryPresentationState(
    val browse: LibraryLoad<LibraryBrowse> = LibraryLoad.Loading,
    val folderId: FolderId? = null,
    val query: String = "",
    val filter: LibraryKindFilter = LibraryKindFilter.ALL,
    val search: LibraryLoad<List<LibraryTrackable>>? = null,
    val organization: LibraryLoad<LibraryOrganization> = LibraryLoad.Loading,
    val mutationFailure: String? = null,
    val isMutating: Boolean = false,
)

sealed interface LibraryAction {
    data object Retry : LibraryAction

    data object Refresh : LibraryAction

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

    data class CreateFolder(
        val name: String,
    ) : LibraryAction

    data class RenameFolder(
        val id: FolderId,
        val name: String,
    ) : LibraryAction

    data class MoveFolder(
        val id: FolderId,
        val parentId: FolderId?,
    ) : LibraryAction

    data class MoveTemplate(
        val id: LibraryTemplateId,
        val folderId: FolderId?,
    ) : LibraryAction

    data class CreateAndAssignTag(
        val templateId: LibraryTemplateId,
        val name: String,
    ) : LibraryAction

    data class AssignTag(
        val templateId: LibraryTemplateId,
        val tagId: TagId,
    ) : LibraryAction

    data class UnassignTag(
        val templateId: LibraryTemplateId,
        val tagId: TagId,
    ) : LibraryAction

    data class SetPinned(
        val templateId: LibraryTemplateId,
        val pinned: Boolean,
    ) : LibraryAction

    data class ReorderPinned(
        val ids: List<LibraryTemplateId>,
    ) : LibraryAction
}

sealed interface LibraryMutation {
    data class CreateFolder(
        val id: FolderId,
        val name: String,
        val parentId: FolderId?,
        val at: Instant,
    ) : LibraryMutation

    data class RenameFolder(
        val id: FolderId,
        val name: String,
        val at: Instant,
    ) : LibraryMutation

    data class MoveFolder(
        val id: FolderId,
        val parentId: FolderId?,
        val at: Instant,
    ) : LibraryMutation

    data class MoveTemplate(
        val id: LibraryTemplateId,
        val folderId: FolderId?,
        val at: Instant,
    ) : LibraryMutation

    data class CreateAndAssignTag(
        val id: TagId,
        val name: String,
        val templateId: LibraryTemplateId,
        val at: Instant,
    ) : LibraryMutation

    data class AssignTag(
        val templateId: LibraryTemplateId,
        val tagId: TagId,
    ) : LibraryMutation

    data class UnassignTag(
        val templateId: LibraryTemplateId,
        val tagId: TagId,
    ) : LibraryMutation

    data class SetPinned(
        val templateId: LibraryTemplateId,
        val pinned: Boolean,
    ) : LibraryMutation

    data class ReorderPinned(
        val ids: List<LibraryTemplateId>,
    ) : LibraryMutation
}

class LibraryController internal constructor(
    private val scope: CoroutineScope,
    private val readRoot: suspend () -> LibraryRoot,
    private val readFolderContents: suspend (FolderId) -> LibraryContents,
    private val readFolderPath: suspend (FolderId) -> List<Folder>,
    private val searchLibrary: suspend (String, LibraryKindFilter) -> List<LibraryTrackable>,
    private val readOrganization: suspend () -> LibraryOrganization = { LibraryOrganization(emptyList(), emptyList()) },
    private val mutateLibrary: suspend (LibraryMutation) -> Unit = { error("Library mutations are unavailable") },
    private val now: () -> Instant = Instant::now,
    private val nextFolderId: () -> FolderId = { FolderId(UUID.randomUUID().toString()) },
    private val nextTagId: () -> TagId = { TagId(UUID.randomUUID().toString()) },
) {
    private val browseGeneration = AtomicLong()
    private val organizationGeneration = AtomicLong()
    private val searchGeneration = AtomicLong()
    private val mutationInFlight = AtomicBoolean()
    private val mutableState = MutableStateFlow(LibraryPresentationState())
    val state: StateFlow<LibraryPresentationState> = mutableState

    @Volatile
    private var closed = false

    init {
        loadRoot()
        loadOrganization()
    }

    fun dispatch(action: LibraryAction) {
        when (action) {
            LibraryAction.Retry -> retry()
            LibraryAction.Refresh -> refresh()
            LibraryAction.Back -> back()
            is LibraryAction.OpenFolder -> loadFolder(action.id)
            is LibraryAction.Search -> search(action.query)
            is LibraryAction.SetFilter -> setFilter(action.filter)
            is LibraryAction.CreateFolder ->
                mutate(LibraryMutation.CreateFolder(nextFolderId(), action.name, mutableState.value.folderId, now()))
            is LibraryAction.RenameFolder -> mutate(LibraryMutation.RenameFolder(action.id, action.name, now()))
            is LibraryAction.MoveFolder -> mutate(LibraryMutation.MoveFolder(action.id, action.parentId, now()))
            is LibraryAction.MoveTemplate -> mutate(LibraryMutation.MoveTemplate(action.id, action.folderId, now()))
            is LibraryAction.CreateAndAssignTag ->
                mutate(LibraryMutation.CreateAndAssignTag(nextTagId(), action.name, action.templateId, now()))
            is LibraryAction.AssignTag -> mutate(LibraryMutation.AssignTag(action.templateId, action.tagId))
            is LibraryAction.UnassignTag -> mutate(LibraryMutation.UnassignTag(action.templateId, action.tagId))
            is LibraryAction.SetPinned -> mutate(LibraryMutation.SetPinned(action.templateId, action.pinned))
            is LibraryAction.ReorderPinned -> mutate(LibraryMutation.ReorderPinned(action.ids))
        }
    }

    fun close() {
        closed = true
        browseGeneration.incrementAndGet()
        organizationGeneration.incrementAndGet()
        searchGeneration.incrementAndGet()
    }

    private fun retry() {
        mutableState.value.folderId?.let(::loadFolder) ?: loadRoot()
        if (mutableState.value.search is LibraryLoad.Failure) search(mutableState.value.query)
        if (mutableState.value.organization is LibraryLoad.Failure) loadOrganization()
    }

    private fun refresh() {
        mutableState.value.folderId?.let(::loadFolder) ?: loadRoot()
        mutableState.value.query
            .takeIf(String::isNotBlank)
            ?.let(::search)
        loadOrganization()
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

    private fun loadOrganization() {
        val generation = organizationGeneration.incrementAndGet()
        mutableState.update { it.copy(organization = LibraryLoad.Loading) }
        scope.launch {
            try {
                val organization = readOrganization()
                if (!closed && generation == organizationGeneration.get()) {
                    mutableState.update { it.copy(organization = LibraryLoad.Content(organization)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && generation == organizationGeneration.get()) {
                    mutableState.update {
                        it.copy(organization = LibraryLoad.Failure(failure.message ?: "Unknown error"))
                    }
                }
            }
        }
    }

    private fun mutate(mutation: LibraryMutation) {
        if (closed || !mutationInFlight.compareAndSet(false, true)) return
        mutableState.update { it.copy(isMutating = true, mutationFailure = null) }
        scope.launch {
            try {
                mutateLibrary(mutation)
                refreshAfterMutation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) mutableState.update { it.copy(mutationFailure = failure.message ?: "Unknown error") }
            } finally {
                mutationInFlight.set(false)
                if (!closed) mutableState.update { it.copy(isMutating = false) }
            }
        }
    }

    private suspend fun refreshAfterMutation() {
        val snapshot = mutableState.value
        val browseGeneration = browseGeneration.incrementAndGet()
        val organizationGeneration = organizationGeneration.incrementAndGet()
        val searchGeneration = searchGeneration.incrementAndGet()
        val browse =
            if (snapshot.folderId == null) {
                readRoot().toBrowse()
            } else {
                LibraryBrowse(
                    snapshot.folderId,
                    readFolderPath(snapshot.folderId),
                    emptyList(),
                    readFolderContents(snapshot.folderId),
                )
            }
        val organization = readOrganization()
        val search =
            snapshot.query.takeIf(String::isNotBlank)?.let {
                searchLibrary(it, snapshot.filter).filterNot(LibraryTrackable::isArchived)
            }
        if (!closed) {
            mutableState.update { current ->
                current.copy(
                    browse =
                        if (current.folderId == snapshot.folderId && browseGeneration == this.browseGeneration.get()) {
                            LibraryLoad.Content(browse.activeOnly())
                        } else {
                            current.browse
                        },
                    organization =
                        if (organizationGeneration == this.organizationGeneration.get()) {
                            LibraryLoad.Content(organization)
                        } else {
                            current.organization
                        },
                    search =
                        if (
                            current.query == snapshot.query &&
                            current.filter == snapshot.filter &&
                            searchGeneration == this.searchGeneration.get()
                        ) {
                            search?.let { LibraryLoad.Content(it) }
                        } else {
                            current.search
                        },
                )
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
