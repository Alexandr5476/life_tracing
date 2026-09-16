package com.alexandr5476.lifetracing.history

import androidx.lifecycle.ViewModel

internal class ManualActivityEntryRouteSessionOwner : ViewModel() {
    private var session: ManualActivityEntryRouteSession? = null

    val activeSession: ManualActivityEntryRouteSession?
        get() = session

    fun acquire(create: () -> ManualActivityEntryController): ManualActivityEntryRouteSession =
        session ?: ManualActivityEntryRouteSession(create()).also { session = it }

    fun release(expected: ManualActivityEntryRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class ManualActivityEntryRouteSession(
    val controller: ManualActivityEntryController,
) {
    private var delivered = false

    fun deliverCommitted(onCommitted: () -> Unit) {
        if (delivered) return
        delivered = true
        onCommitted()
    }
}
