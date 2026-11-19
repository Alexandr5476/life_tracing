@file:Suppress("CyclomaticComplexMethod", "ReturnCount")

package com.alexandr5476.lifetracing.history

import androidx.compose.runtime.staticCompositionLocalOf
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId

/** A lazy occurrence survives independently of each of its historical native focus targets. */
internal data class SequenceHistoryFocusIdentity(
    val occurrenceId: SequenceOccurrenceId,
    val element: SequenceHistoryFocusElement,
)

internal sealed interface SequenceHistoryFocusElement {
    data object Summary : SequenceHistoryFocusElement

    data object Tombstone : SequenceHistoryFocusElement

    data class Child(
        val executionId: ActivityExecutionId,
    ) : SequenceHistoryFocusElement

    data class ChildField(
        val executionId: ActivityExecutionId,
        val fieldId: ActivitySnapshotFieldId,
    ) : SequenceHistoryFocusElement

    data class ConfiguredField(
        val snapshotId: ActivitySnapshotId,
        val fieldId: ActivitySnapshotFieldId,
    ) : SequenceHistoryFocusElement

    data class DeleteChild(
        val executionId: ActivityExecutionId,
    ) : SequenceHistoryFocusElement

    data class RemoveOccurrence(
        val executionId: ActivityExecutionId,
    ) : SequenceHistoryFocusElement
}

internal val LocalSequenceHistoryFocusIdentity = staticCompositionLocalOf<SequenceHistoryFocusIdentity?> { null }

/** Called only for the focused identity on canonical replacement, never for row composition/scroll. */
internal fun SequenceHistoryDetail.represents(
    identity: SequenceHistoryFocusIdentity,
    canDelete: Boolean,
    canRemove: Boolean,
): Boolean {
    val occurrence = occurrences.firstOrNull { it.occurrenceId == identity.occurrenceId } ?: return false
    val child = occurrence.child.takeUnless { occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION }
    return when (val element = identity.element) {
        SequenceHistoryFocusElement.Summary -> true
        SequenceHistoryFocusElement.Tombstone -> occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION
        is SequenceHistoryFocusElement.Child -> child?.executionId == element.executionId
        is SequenceHistoryFocusElement.ChildField ->
            child?.executionId == element.executionId && child.fields.any { it.id == element.fieldId }
        is SequenceHistoryFocusElement.ConfiguredField ->
            child == null &&
                occurrence.activitySnapshotId == element.snapshotId &&
                occurrence.activity.mainValue?.id == element.fieldId
        is SequenceHistoryFocusElement.DeleteChild ->
            canDelete &&
                !occurrence.isDeletedFromHistory &&
                occurrence.status == RuntimeOccurrenceStatus.COMPLETED &&
                occurrence.childMutationFacts?.executionId == element.executionId
        is SequenceHistoryFocusElement.RemoveOccurrence ->
            canRemove &&
                occurrence.canRemoveOccurrence() &&
                occurrence.status in
                setOf(
                    RuntimeOccurrenceStatus.COMPLETED,
                    RuntimeOccurrenceStatus.DELETED_EXECUTION,
                ) &&
                occurrence.childMutationFacts?.executionId == element.executionId
    }
}
