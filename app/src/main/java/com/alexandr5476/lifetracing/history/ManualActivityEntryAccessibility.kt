@file:Suppress("FunctionNaming", "CyclomaticComplexMethod", "ComplexCondition", "MatchingDeclarationName")

package com.alexandr5476.lifetracing.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/** Revocable presentation ownership. Repeated text events may advance their own draft cursor. */
internal class ManualEntryAdmission(
    private val publication: ManualActivityEntryState,
    private val current: () -> ManualActivityEntryState,
    private val ownsRoute: () -> Boolean,
    private val dispatch: (ManualActivityEntryAction) -> Unit,
) {
    var attached = true

    private fun available(state: ManualActivityEntryState): Boolean =
        attached &&
            ownsRoute() &&
            state.command !is ManualEntryCommand.Committing &&
            state.command !is ManualEntryCommand.Committed

    fun canFocus(): Boolean = attached && ownsRoute()

    fun back(block: () -> Unit): () -> Unit =
        {
            if (available(current()) && current() === publication) block()
        }

    fun action(action: ManualActivityEntryAction): () -> Unit =
        {
            val state = current()
            if (available(state) && accepts(publication, state, action)) dispatch(action)
        }

    fun input(action: (String) -> ManualActivityEntryAction): (String) -> Unit {
        var expected = publication
        return { text ->
            val state = current()
            val edit = action(text)
            if (available(state) && accepts(expected, state, edit)) {
                dispatch(edit)
                expected = current()
            }
        }
    }

    private fun accepts(
        expected: ManualActivityEntryState,
        state: ManualActivityEntryState,
        action: ManualActivityEntryAction,
    ): Boolean {
        val selection = state.selected === expected.selected
        return when (action) {
            is ManualActivityEntryAction.Select ->
                selection &&
                    state.draftVersion == expected.draftVersion &&
                    state.catalog === expected.catalog &&
                    (
                        (state.catalog as? ManualEntryLoad.Content)?.value?.any { it.id == action.id } == true ||
                            state.selectedTemplateId == action.id &&
                            (state.selected as? ManualEntryLoad.Failure)?.issue ==
                            ManualEntryIssue.TEMPLATE_READ_FAILURE
                    )
            ManualActivityEntryAction.RetryCatalog ->
                state.catalog === expected.catalog &&
                    state.catalog is ManualEntryLoad.Failure
            ManualActivityEntryAction.LoadMore ->
                state.catalog === expected.catalog &&
                    state.canLoadMore &&
                    state.catalog is ManualEntryLoad.Content
            ManualActivityEntryAction.ReviewStaleTemplate ->
                state.selected === expected.selected &&
                    state.command === expected.command &&
                    (state.command as? ManualEntryCommand.Failure)?.issue == ManualEntryIssue.TEMPLATE_STALE
            else ->
                selection &&
                    state.selected is ManualEntryLoad.Content &&
                    state.draftVersion == expected.draftVersion &&
                    state.command === expected.command
        }
    }
}

internal val LocalManualEntryAdmission =
    staticCompositionLocalOf<ManualEntryAdmission> {
        error("Manual entry action owner missing")
    }

@Composable
internal fun rememberManualEntryAdmission(
    state: ManualActivityEntryState,
    current: () -> ManualActivityEntryState,
    ownsRoute: () -> Boolean,
    visible: Boolean,
    dispatch: (ManualActivityEntryAction) -> Unit,
): ManualEntryAdmission {
    val read by rememberUpdatedState(current)
    val owner by rememberUpdatedState(ownsRoute)
    val send by rememberUpdatedState(dispatch)
    // StateFlow publishes immutable objects; use reference identity rather than large form equality.
    val identity = ManualEntryPublication(state)
    val admission =
        remember(identity, visible) {
            ManualEntryAdmission(state, { read() }, { visible && owner() }, { send(it) })
        }
    DisposableEffect(admission) { onDispose { admission.attached = false } }
    return admission
}

private class ManualEntryPublication(
    val state: ManualActivityEntryState,
) {
    override fun equals(other: Any?): Boolean = other is ManualEntryPublication && state === other.state

    override fun hashCode(): Int = System.identityHashCode(state)
}

/** Tab always traverses, including multiline Text values. Enter retains native editing behavior. */
@Composable
internal fun manualEntryInputTarget(enabled: Boolean): Modifier {
    val manager = LocalFocusManager.current
    return historyFocusTarget(enabled = enabled).onPreviewKeyEvent {
        if (it.key == Key.Tab) {
            if (it.type == KeyEventType.KeyDown) {
                manager.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
            }
            true
        } else {
            false
        }
    }
}
