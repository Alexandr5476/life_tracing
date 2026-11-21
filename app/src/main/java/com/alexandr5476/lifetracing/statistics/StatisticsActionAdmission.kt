@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.statistics

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import java.lang.ref.WeakReference

internal class StatisticsActionAdmission(
    publication: Any,
    private val readCurrent: () -> Any,
    private val ownsRoute: () -> Boolean,
    private val onRevoked: () -> Unit = {},
) {
    // Retained native actions only need to identify their publication.
    private val publicationRef = WeakReference(publication)
    private var attached = true
    private var revoked = false

    fun isCurrent(): Boolean = attached && !revoked && ownsRoute() && hasCurrentPublication()

    fun run(
        applicable: Boolean,
        action: () -> Unit,
    ): Boolean {
        if (!attached || revoked) return false
        val owns = ownsRoute()
        if (!owns) revoke()
        val allowed = owns && applicable && hasCurrentPublication()
        if (allowed) action()
        return allowed
    }

    private fun hasCurrentPublication(): Boolean = readCurrent() === publicationRef.get()

    fun revoke() {
        if (attached && !revoked) {
            revoked = true
            onRevoked()
        }
    }

    fun detach() {
        attached = false
    }
}

@Composable
internal fun rememberStatisticsActionAdmission(
    publication: Any,
    readCurrent: () -> Any,
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { true },
): StatisticsActionAdmission {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val interactive = isRouteVisible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val read by rememberUpdatedState(readCurrent)
    var revocation by remember { mutableStateOf(Any()) }
    val admission =
        remember(StatisticsIdentityKey(publication), interactive, lifecycle, revocation) {
            StatisticsActionAdmission(publication, { read() }, {
                interactive && ownsRoute() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }) { revocation = Any() }
        }
    DisposableEffect(admission, lifecycle, ownsRoute) {
        val lifecycleObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) admission.revoke()
            }
        lifecycle.addObserver(lifecycleObserver)
        // Observe navigation ownership even while Navigation3 retains the outgoing composition.
        val routeObserver = SnapshotStateObserver { it() }
        routeObserver.start()
        routeObserver.observeReads(admission, { it.revoke() }) { ownsRoute() }
        onDispose {
            routeObserver.stop()
            routeObserver.clear()
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    DisposableEffect(admission) { onDispose { admission.detach() } }
    return admission
}

internal class StatisticsIdentityKey(
    private val value: Any,
) {
    override fun equals(other: Any?): Boolean = other is StatisticsIdentityKey && value === other.value

    override fun hashCode(): Int = System.identityHashCode(value)
}

private class StatisticsControlAttachment {
    var attached = true
}

@Composable
internal fun StatisticsActionAdmission.action(
    enabled: Boolean = true,
    applicable: () -> Boolean = { true },
    onClick: () -> Unit,
): () -> Boolean {
    val attachment = remember(this, enabled) { StatisticsControlAttachment() }
    DisposableEffect(attachment) { onDispose { attachment.attached = false } }
    return { run(attachment.attached && enabled && applicable(), onClick) }
}

// Capture the guarded action in native semantics while Material retains role, focus and ripple.
internal fun Modifier.statisticsActionSemantics(action: () -> Boolean): Modifier =
    semantics { onClick(action = action) }

@Composable
internal fun StatisticsSecondaryButton(
    admission: StatisticsActionAdmission,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    applicable: () -> Boolean = { true },
    content: @Composable RowScope.() -> Unit,
) {
    val action = admission.action(enabled, applicable, onClick)
    LifeTracingSecondaryButton(
        onClick = { action() },
        modifier = modifier.statisticsActionSemantics(action),
        enabled = enabled && admission.isCurrent(),
        content = content,
    )
}

@Composable
internal fun StatisticsChoiceButton(
    admission: StatisticsActionAdmission,
    selected: Boolean,
    onClick: () -> Unit,
    applicable: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val action = admission.action(applicable = applicable, onClick = onClick)
    LifeTracingChoiceButton(
        selected = selected,
        onClick = { action() },
        modifier = modifier.statisticsActionSemantics(action),
        enabled = admission.isCurrent(),
        content = content,
    )
}

@Composable
internal fun StatisticsTextButton(
    admission: StatisticsActionAdmission,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val action = admission.action(onClick = onClick)
    TextButton(
        onClick = { action() },
        modifier = modifier.statisticsActionSemantics(action),
        enabled = admission.isCurrent(),
        content = content,
    )
}
