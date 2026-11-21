@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal class SettingsPresentation(
    private val interactive: Boolean,
    private val ownsRoute: () -> Boolean,
    private val onRevoked: () -> Unit,
) {
    private var attached = true
    private var revoked = false

    fun isCurrent(): Boolean {
        if (!attached || revoked || !interactive) return false
        val owns = ownsRoute()
        if (!owns) revoke()
        return owns
    }

    fun leave(navigate: () -> Unit) {
        if (!isCurrent()) return
        // Back and forward navigation revoke synchronously, even between composition frames.
        revoke()
        navigate()
    }

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
internal fun rememberSettingsPresentation(
    isRouteVisible: Boolean,
    ownsRoute: () -> Boolean,
): SettingsPresentation {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val interactive = isRouteVisible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var generation by remember { mutableStateOf(Any()) }
    val presentation =
        remember(interactive, lifecycle, ownsRoute, generation) {
            SettingsPresentation(
                interactive,
                { ownsRoute() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
                { generation = Any() },
            )
        }
    DisposableEffect(presentation) {
        if (!interactive) return@DisposableEffect onDispose { presentation.detach() }
        val lifecycleObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) presentation.revoke()
            }
        lifecycle.addObserver(lifecycleObserver)
        val routeObserver = SnapshotStateObserver { it() }
        routeObserver.start()
        routeObserver.observeReads(presentation, { it.revoke() }) { ownsRoute() }
        onDispose {
            presentation.detach()
            routeObserver.stop()
            routeObserver.clear()
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return presentation
}

internal class SettingsBackGesture {
    private var start: SettingsPresentation? = null
    var startedOnSettings: Boolean = false
        private set

    fun begin(
        presentation: SettingsPresentation,
        isSettingsRoute: Boolean,
    ) {
        if (start != null) return
        start = presentation
        startedOnSettings = isSettingsRoute
    }

    fun end() {
        start = null
        startedOnSettings = false
    }

    fun allows(presentation: SettingsPresentation): Boolean = start == null || start === presentation
}

@Composable
internal fun rememberSettingsBackGesture(
    presentation: SettingsPresentation,
    isSettingsRoute: Boolean,
): SettingsBackGesture {
    val gesture = remember { SettingsBackGesture() }
    val currentPresentation by rememberUpdatedState(presentation)
    val currentIsSettingsRoute by rememberUpdatedState(isSettingsRoute)
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val scope = rememberCoroutineScope()
    DisposableEffect(dispatcher) {
        // Navigation3 reads its latest onBack at completion. Capture the owner when a gesture starts.
        val observation =
            scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                dispatcher?.transitionState?.collect { transition ->
                    if (transition is NavigationEventTransitionState.InProgress &&
                        transition.direction == NavigationEventTransitionState.TRANSITIONING_BACK
                    ) {
                        gesture.begin(currentPresentation, currentIsSettingsRoute)
                    } else {
                        gesture.end()
                    }
                }
            }
        onDispose {
            observation.cancel()
            gesture.end()
        }
    }
    return gesture
}

internal class SettingsActionAdmission(
    private val presentation: SettingsPresentation,
    publication: Any,
    readCurrent: () -> Any,
) {
    // Lazy rows and retained native semantics need identity only. The controller owns the rows.
    private val publication = WeakReference(publication)
    private var readCurrent: (() -> Any)? = readCurrent

    fun isCurrent(): Boolean {
        val read = readCurrent ?: return false
        return presentation.isCurrent() && publication.get() === read()
    }

    fun detach() {
        readCurrent = null
    }
}

private class PublicationKey(
    private val state: Any,
) {
    override fun equals(other: Any?): Boolean = other is PublicationKey && state === other.state

    override fun hashCode(): Int = System.identityHashCode(state)
}

@Composable
internal fun SettingsPresentation.rememberAdmission(
    publication: Any,
    readCurrent: () -> Any,
): SettingsActionAdmission {
    val admission =
        remember(this, PublicationKey(publication), readCurrent) {
            SettingsActionAdmission(this, publication, readCurrent)
        }
    DisposableEffect(admission) { onDispose { admission.detach() } }
    return admission
}

private class ControlAttachment(
    var onClick: (() -> Unit)?,
)

@Composable
internal fun rememberSettingsAction(
    ownsAction: () -> Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
): () -> Boolean {
    val attachment = remember(ownsAction, enabled) { ControlAttachment(onClick) }
    SideEffect { attachment.onClick = onClick }
    DisposableEffect(attachment) { onDispose { attachment.onClick = null } }
    return remember(attachment) {
        action@{
            val dispatch = attachment.onClick ?: return@action false
            val allowed = enabled && ownsAction()
            if (allowed) dispatch()
            allowed
        }
    }
}

// Capture this control's action directly. Material's native action forwards to its latest handler.
internal fun Modifier.settingsActionSemantics(action: () -> Boolean): Modifier = semantics { onClick(action = action) }

internal fun Modifier.settingsKeyboardBack(
    presentation: SettingsPresentation,
    onBack: () -> Unit,
): Modifier =
    onPreviewKeyEvent {
        if (it.key != Key.Escape || it.type != KeyEventType.KeyUp || !presentation.isCurrent()) {
            false
        } else {
            onBack()
            true
        }
    }

@Composable
internal fun SettingsActionButton(
    ownsAction: () -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val action = rememberSettingsAction(ownsAction, enabled, onClick)
    LifeTracingSecondaryButton(
        onClick = { action() },
        modifier = modifier.settingsActionSemantics(action),
        enabled = enabled,
        content = content,
    )
}
