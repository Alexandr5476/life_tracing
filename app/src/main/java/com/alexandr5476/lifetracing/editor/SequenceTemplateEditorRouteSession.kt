package com.alexandr5476.lifetracing.editor

import androidx.lifecycle.ViewModel

internal class SequenceTemplateEditorRouteSessionOwner : ViewModel() {
    private var session: SequenceTemplateEditorRouteSession? = null
    val activeSession get() = session

    fun acquire(
        target: SequenceTemplateEditorTarget,
        createController: () -> SequenceTemplateEditorController,
    ) = session ?: SequenceTemplateEditorRouteSession(target, createController()).also { session = it }

    fun release(expected: SequenceTemplateEditorRouteSession) {
        if (session === expected) {
            expected.controller.close()
            session = null
        }
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class SequenceTemplateEditorRouteSession(
    val target: SequenceTemplateEditorTarget,
    val controller: SequenceTemplateEditorController,
    val exitPolicy: SequenceTemplateEditorRouteExitPolicy = SequenceTemplateEditorRouteExitPolicy(),
)

internal class SequenceTemplateEditorRouteExitPolicy {
    private var delivered = false

    fun deliverCommitted(action: () -> Unit) {
        if (!delivered) {
            delivered = true
            action()
        }
    }
}
