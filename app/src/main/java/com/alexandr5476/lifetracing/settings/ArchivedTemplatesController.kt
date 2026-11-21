package com.alexandr5476.lifetracing.settings

import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ArchivedTemplatesState(
    val items: List<LibraryTrackable>? = null,
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val pending: LibraryTemplateId? = null,
    val failedRestore: LibraryTemplateId? = null,
    val reconcileTarget: LibraryTemplateId? = null,
    val refreshWhenAbsent: Boolean = false,
    // A completed reread replaces action authority even when its rows compare equal.
    val readGeneration: Long = 0,
    // Shared Restore completions accounted for by the latest read attempt.
    val archiveCompletion: Long = 0,
) {
    val canRestore: Boolean
        get() = items != null && !loading && !readFailed && pending == null
}

/** Rows only change through canonical reloads, including rejected or raced Restore commands. */
internal class ArchivedTemplatesController(
    private val readArchived: suspend () -> List<LibraryTrackable>,
    private val restoreTemplate: suspend (LibraryTemplateId) -> Unit,
    private val onRestored: () -> Unit,
    private val scope: CoroutineScope,
    private val restores: ArchivedRestoreCoordinator,
) {
    private val mutableState = MutableStateFlow(ArchivedTemplatesState())
    val state: StateFlow<ArchivedTemplatesState> = mutableState

    init {
        scope.launch { publish(readCanonical(mutableState.value)) }
        scope.launch {
            restores.status
                .map { it.completion }
                .distinctUntilChanged()
                .collect { refreshIfStale() }
        }
    }

    fun reload() = startRead(onlyIfStale = false)

    private fun refreshIfStale() = startRead(onlyIfStale = true)

    private fun startRead(onlyIfStale: Boolean) {
        val current = mutableState.value
        if (!scope.isActive || current.loading || current.pending != null) return
        if (onlyIfStale && current.archiveCompletion == restores.status.value.completion) return
        if (mutableState.compareAndSet(current, current.copy(loading = true, readFailed = false))) {
            scope.launch { publish(readCanonical(current)) }
        }
    }

    fun restore(id: LibraryTemplateId) {
        val current = mutableState.value
        if (!scope.isActive || !current.canRestore) return
        if (current.items?.none { it.id == id } != false || !restores.reserve(id, current.archiveCompletion)) return
        val requested = current.copy(pending = id, failedRestore = null, reconcileTarget = id)
        if (mutableState.compareAndSet(current, requested)) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Enter the non-cancellable region before disposal can cancel a queued launch.
                withContext(NonCancellable) { restoreAndReconcile(id, requested) }
            }
        } else {
            restores.releaseUnaccepted(id)
        }
    }

    fun retryRestore() {
        mutableState.value.failedRestore?.let(::restore)
    }

    private suspend fun restoreAndReconcile(
        id: LibraryTemplateId,
        requested: ArchivedTemplatesState,
    ) {
        val publication: ArchivedTemplatesState
        val completion: Long
        try {
            val committed = attemptRestore(id)
            if (committed) onRestored()
            publication = readCanonical(requested.copy(refreshWhenAbsent = !committed))
        } finally {
            completion = restores.complete(id)
        }
        // This read already includes our own Restore. Other completions during the read still require refresh.
        val reconciled =
            if (publication.archiveCompletion == completion - 1) {
                publication.copy(archiveCompletion = completion)
            } else {
                publication
            }
        publish(reconciled)
    }

    private suspend fun attemptRestore(id: LibraryTemplateId): Boolean =
        try {
            restoreTemplate(id)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A competing actor may have restored this identity. Reload decides whether it remains archived.
            false
        }

    private suspend fun readCanonical(current: ArchivedTemplatesState): ArchivedTemplatesState {
        val result =
            current.copy(
                loading = false,
                pending = null,
                readGeneration = current.readGeneration + 1,
                archiveCompletion = restores.status.value.completion,
            )
        return try {
            val items = readArchived()
            val target = current.reconcileTarget ?: current.failedRestore
            val remainsArchived = target != null && items.any { it.id == target }
            val failed = if (current.refreshWhenAbsent) current.reconcileTarget else current.failedRestore
            if (current.refreshWhenAbsent && !remainsArchived) onRestored()
            result.copy(
                items = items,
                readFailed = false,
                failedRestore = failed?.takeIf { remainsArchived },
                reconcileTarget = null,
                refreshWhenAbsent = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result.copy(readFailed = true)
        }
    }

    private fun publish(publication: ArchivedTemplatesState) {
        mutableState.value = publication
        // An observer that saw the completion during this read or local Restore could not start another read.
        refreshIfStale()
    }
}

internal fun restoreArchivedTemplate(
    repository: LibraryRepository,
    id: LibraryTemplateId,
) {
    when (id) {
        is LibraryTemplateId.Activity -> repository.restoreActivityTemplate(id.id)
        is LibraryTemplateId.Sequence -> repository.restoreSequenceTemplate(id.id)
    }
}

internal fun LibraryTemplateId.archivedRowKey(): String =
    when (this) {
        is LibraryTemplateId.Activity -> "activity-$value"
        is LibraryTemplateId.Sequence -> "sequence-$value"
    }
