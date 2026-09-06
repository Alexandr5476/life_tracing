package com.alexandr5476.lifetracing.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode

/** Keeps route disposal outside the durable transaction's unresolved boundary. */
internal class LauncherRouteExitPolicy {
    private var committedResultHandled = false

    fun requestExit(
        command: LauncherCommandState,
        onBack: () -> Unit,
        onCommitted: () -> Unit,
    ) {
        when {
            command.isCommittedResult() -> deliverCommittedResult(onCommitted)
            command != LauncherCommandState.Committing -> onBack()
        }
    }

    fun onCommand(
        command: LauncherCommandState,
        onCommitted: () -> Unit,
    ) {
        if (command.isCommittedResult()) deliverCommittedResult(onCommitted)
    }

    private fun deliverCommittedResult(onCommitted: () -> Unit) {
        if (committedResultHandled) return
        committedResultHandled = true
        onCommitted()
    }
}

internal class StartActivityRouteSessionOwner : ViewModel() {
    private var session: StartActivityRouteSession? = null

    val activeSession: StartActivityRouteSession?
        get() = session

    fun acquire(createController: () -> StartActivityController): StartActivityRouteSession =
        session ?: StartActivityRouteSession(createController(), LauncherRouteExitPolicy()).also { session = it }

    fun release(expected: StartActivityRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class StartActivityRouteSession(
    val controller: StartActivityController,
    val exitPolicy: LauncherRouteExitPolicy,
    val interaction: StartActivityRouteInteraction = StartActivityRouteInteraction(),
)

internal data class LauncherBreadcrumb(
    val id: FolderId,
    val name: String,
)

internal data class QuickMainValueEditorState(
    val targetId: LibraryTemplateId.Activity,
    val fieldId: ActivityTemplateFieldId,
    val text: String,
    val changed: Boolean,
)

/** Bounded, non-durable interaction state for one launcher route entry. */
@Suppress("ReturnCount")
internal class StartActivityRouteInteraction {
    var pendingSelectionId by mutableStateOf<LibraryTemplateId?>(null)
        private set

    var quickEditor by mutableStateOf<QuickMainValueEditorState?>(null)
        private set

    var browsePath by mutableStateOf<List<LauncherBreadcrumb>>(emptyList())
        private set

    fun select(id: LibraryTemplateId) {
        pendingSelectionId = id
    }

    fun resolveSelection(target: LibraryLaunchTarget): StartActivityAction.Launch? {
        if (target.id != pendingSelectionId) return null
        pendingSelectionId = null
        val activity = target as? LibraryLaunchTarget.Activity
        val mainValue = activity?.mainValue
        if (activity?.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING && mainValue != null) {
            quickEditor =
                QuickMainValueEditorState(
                    activity.id,
                    mainValue.fieldId,
                    formatLauncherNumber(mainValue.defaultNumberScaled, mainValue.displayPrecision),
                    changed = false,
                )
            return null
        }
        return StartActivityAction.Launch()
    }

    fun editQuickValue(text: String) {
        quickEditor = quickEditor?.copy(text = text, changed = true)
    }

    fun cancelQuickEditor() {
        quickEditor = null
    }

    fun completeQuickEditor(target: LibraryLaunchTarget.Activity): StartActivityAction.Launch? {
        val editor = quickEditor ?: return null
        val mainValue = target.mainValue ?: return null
        if (target.id != editor.targetId || mainValue.fieldId != editor.fieldId) return null
        val action = StartActivityAction.Launch(quickMainValueOverride(mainValue, editor.text, editor.changed))
        quickEditor = null
        return action
    }

    fun openRoot() {
        browsePath = emptyList()
    }

    fun openFolder(folder: Folder) {
        browsePath = browsePath + LauncherBreadcrumb(folder.id, folder.name)
    }

    fun browseBack(): FolderId? {
        browsePath = browsePath.dropLast(1)
        return browsePath.lastOrNull()?.id
    }
}

internal fun LauncherCommandState.isCommittedResult(): Boolean =
    this is LauncherCommandState.Committed || this is LauncherCommandState.CommittedCoordinationFailure
