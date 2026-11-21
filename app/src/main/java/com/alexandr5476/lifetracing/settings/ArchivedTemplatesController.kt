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
) {
    private val mutableState = MutableStateFlow(ArchivedTemplatesState())
    val state: StateFlow<ArchivedTemplatesState> = mutableState

    init {
        scope.launch { loadCanonical() }
    }

    fun reload() {
        val current = mutableState.value
        if (current.loading || current.pending != null) return
        if (!mutableState.compareAndSet(current, current.copy(loading = true, readFailed = false))) return
        scope.launch { loadCanonical() }
    }

    fun restore(id: LibraryTemplateId) {
        val current = mutableState.value
        if (!current.canRestore || current.items?.none { it.id == id } != false) return
        val requested = current.copy(pending = id, failedRestore = null, reconcileTarget = id)
        if (!mutableState.compareAndSet(current, requested)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Enter the non-cancellable region at acceptance, before disposal can cancel a queued launch.
            // The transaction, retained Library refresh and reconciliation all belong to the accepted work.
            withContext(NonCancellable) {
                var committed = false
                try {
                    restoreTemplate(id)
                    committed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A competing actor may have restored this identity. Reload decides whether it remains archived.
                }
                mutableState.value = mutableState.value.copy(refreshWhenAbsent = !committed)
                if (committed) onRestored()
                loadCanonical()
            }
        }
    }

    fun retryRestore() {
        mutableState.value.failedRestore?.let(::restore)
    }

    private suspend fun loadCanonical() {
        try {
            val items = readArchived()
            val current = mutableState.value
            val target = current.reconcileTarget
            val remainsArchived = target != null && items.any { it.id == target }
            mutableState.value =
                current.copy(
                    items = items,
                    loading = false,
                    readFailed = false,
                    pending = null,
                    failedRestore = target?.takeIf { remainsArchived },
                    reconcileTarget = null,
                    refreshWhenAbsent = false,
                    readGeneration = current.readGeneration + 1,
                )
            if (target != null && !remainsArchived && current.refreshWhenAbsent) onRestored()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val current = mutableState.value
            mutableState.value =
                current.copy(
                    loading = false,
                    pending = null,
                    readFailed = true,
                    readGeneration = current.readGeneration + 1,
                )
        }
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
