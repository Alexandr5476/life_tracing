package com.alexandr5476.lifetracing.editor

import androidx.lifecycle.ViewModel

/** Keeps an in-memory authoring draft alive for the navigation entry, not a composition. */
internal class ActivityTemplateEditorRouteSessionOwner : ViewModel() {
    private var session: ActivityTemplateEditorRouteSession? = null

    val activeSession: ActivityTemplateEditorRouteSession?
        get() = session

    fun acquire(
        target: ActivityTemplateEditorTarget,
        createController: () -> ActivityTemplateEditorController,
    ): ActivityTemplateEditorRouteSession =
        session ?: ActivityTemplateEditorRouteSession(target, createController()).also { session = it }

    fun release(expected: ActivityTemplateEditorRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class ActivityTemplateEditorRouteSession(
    val target: ActivityTemplateEditorTarget,
    val controller: ActivityTemplateEditorController,
    val exitPolicy: ActivityTemplateEditorRouteExitPolicy = ActivityTemplateEditorRouteExitPolicy(),
)

/** Delivers a completed durable write once even if the host recreates before navigation pops. */
internal class ActivityTemplateEditorRouteExitPolicy {
    private var committedDelivered = false

    fun deliverCommitted(onCommitted: () -> Unit) {
        if (committedDelivered) return
        committedDelivered = true
        onCommitted()
    }
}
