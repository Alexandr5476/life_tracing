@file:Suppress(
    "FunctionNaming",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LongParameterList",
    "MatchingDeclarationName",
)

package com.alexandr5476.lifetracing.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.CustomFieldType

/** One publication owns actions; each native input can advance its own cursor between frames. */
internal class ActivityCorrectionAdmission(
    private val executionId: ActivityExecutionId,
    private val publication: ActivityHistoryMutationState,
    private val current: () -> ActivityHistoryMutationState,
    private val ownsRoute: () -> Boolean,
    private val dispatch: (ActivityHistoryMutationAction) -> Unit,
) {
    var attached = true

    fun canFocus(): Boolean = attached && ownsRoute()

    fun back(block: () -> Unit): () -> Unit =
        {
            val state = current()
            if (canFocus() && state === publication && !state.isMutating) block()
        }

    fun action(action: ActivityHistoryMutationAction): () -> Unit =
        {
            if (accepts(publication, action)) dispatch(action)
        }

    fun input(action: (String) -> ActivityHistoryMutationAction): (String) -> Unit {
        var expected = publication
        return { text ->
            val edit = action(text)
            if (accepts(expected, edit)) {
                dispatch(edit)
                expected = current()
            }
        }
    }

    private fun accepts(
        expected: ActivityHistoryMutationState,
        action: ActivityHistoryMutationAction,
    ): Boolean {
        val state = current()
        if (!canFocus() || state !== expected || state.isMutating) return false
        if (action == ActivityHistoryMutationAction.Retry) {
            return state.load is HistoryDetailLoad.Failure || state.load is HistoryDetailLoad.Unavailable
        }
        val detail = (state.load as? HistoryDetailLoad.Content)?.value ?: return false
        if (detail.root.executionId != executionId || state.deleted) return false
        // Deletion keeps its existing command and confirmation policy, sharing only presentation ownership.
        when (action) {
            ActivityHistoryMutationAction.RequestDelete ->
                return state.draft == null &&
                    !state.overlapWarning &&
                    !state.deleteConfirmation
            ActivityHistoryMutationAction.ConfirmDelete -> return state.deleteConfirmation
            ActivityHistoryMutationAction.Cancel -> return state.draft != null || state.deleteConfirmation
            else -> Unit
        }
        if (detail.root.planEntryId != null || state.deleteConfirmation) return false
        if (action == ActivityHistoryMutationAction.BeginCorrection) return state.draft == null && !state.overlapWarning
        if (state.draft?.executionId != executionId) return false
        if (action == ActivityHistoryMutationAction.ProceedOverlap ||
            action == ActivityHistoryMutationAction.CancelOverlap
        ) {
            return state.overlapWarning
        }
        if (state.overlapWarning) return false
        val draft = requireNotNull(state.draft)
        return when (action) {
            is ActivityHistoryMutationAction.EditStarted -> draft.startedText != null
            is ActivityHistoryMutationAction.SelectStartedOffset -> action.offset in draft.startedOffsets
            is ActivityHistoryMutationAction.SelectCompletedOffset -> action.offset in draft.completedOffsets
            is ActivityHistoryMutationAction.EditNumber -> draft.values[action.id]?.type == CustomFieldType.NUMBER
            is ActivityHistoryMutationAction.EditText -> draft.values[action.id]?.type == CustomFieldType.TEXT
            is ActivityHistoryMutationAction.SetMissing -> action.id in draft.values
            is ActivityHistoryMutationAction.SelectCategory ->
                detail.fields.any { field ->
                    field.id == action.id &&
                        field.type == CustomFieldType.CATEGORY &&
                        field.categoryOptions.any { it.id == action.optionId }
                }
            is ActivityHistoryMutationAction.EditCompleted,
            is ActivityHistoryMutationAction.EditComment,
            ActivityHistoryMutationAction.Save,
            -> true
            else -> false
        }
    }
}

internal val LocalActivityCorrectionAdmission =
    staticCompositionLocalOf<ActivityCorrectionAdmission> {
        error("Activity correction action owner missing")
    }

@Composable
internal fun rememberActivityCorrectionAdmission(
    executionId: ActivityExecutionId,
    state: ActivityHistoryMutationState,
    current: () -> ActivityHistoryMutationState,
    ownsRoute: () -> Boolean,
    visible: Boolean,
    dispatch: (ActivityHistoryMutationAction) -> Unit,
): ActivityCorrectionAdmission {
    val read by rememberUpdatedState(current)
    val owner by rememberUpdatedState(ownsRoute)
    val send by rememberUpdatedState(dispatch)
    val admission =
        remember(ActivityCorrectionPublication(state), visible) {
            ActivityCorrectionAdmission(executionId, state, { read() }, { visible && owner() }, { send(it) })
        }
    DisposableEffect(admission) { onDispose { admission.attached = false } }
    return admission
}

private class ActivityCorrectionPublication(
    val state: ActivityHistoryMutationState,
) {
    override fun equals(other: Any?): Boolean = other is ActivityCorrectionPublication && state === other.state

    override fun hashCode(): Int = System.identityHashCode(state)
}
