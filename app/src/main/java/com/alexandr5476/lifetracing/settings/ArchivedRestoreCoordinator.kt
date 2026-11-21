package com.alexandr5476.lifetracing.settings

import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

internal data class ArchivedRestoreStatus(
    val pending: Set<LibraryTemplateId> = emptySet(),
    val completion: Long = 0,
) {
    fun allowsRestore(
        id: LibraryTemplateId,
        archiveCompletion: Long,
    ): Boolean = completion == archiveCompletion && id !in pending
}

/** Process-owned reservations and a completion watermark. Canonical rows stay in their controllers. */
internal class ArchivedRestoreCoordinator {
    private val mutableStatus = MutableStateFlow(ArchivedRestoreStatus())
    val status: StateFlow<ArchivedRestoreStatus> = mutableStatus

    fun reserve(
        id: LibraryTemplateId,
        archiveCompletion: Long,
    ): Boolean {
        while (true) {
            val current = mutableStatus.value
            if (!current.allowsRestore(id, archiveCompletion)) return false
            if (mutableStatus.compareAndSet(current, current.copy(pending = current.pending + id))) return true
        }
    }

    fun releaseUnaccepted(id: LibraryTemplateId) {
        mutableStatus.update { it.copy(pending = it.pending - id) }
    }

    // Release and invalidate older publications atomically, including when reconciliation failed.
    fun complete(id: LibraryTemplateId): Long =
        mutableStatus
            .updateAndGet { it.copy(pending = it.pending - id, completion = it.completion + 1) }
            .completion
}
