@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/**
 * Clickable's native semantics action forwards to its current handler. Capture the guarded
 * callback here so a retained semantics action cannot acquire a later presentation's lease.
 * Keep this outside clickable, preserving its role, disabled state, focus and press indication.
 */
internal fun Modifier.libraryActionSemantics(
    action: () -> Unit,
    label: String? = null,
): Modifier =
    semantics {
        onClick(label) {
            action()
            true
        }
    }

private class LibraryPresentationLease {
    var attached = true
    var submitted = false
}

/**
 * A rendered control owns only its current presentation. State changes revoke its old action
 * even before recomposition; disposal and route hiding revoke retained native semantics actions.
 * Rendered rows are keyed by typed identity, so matching display names never grant eligibility.
 * Locale/scaling changes also replace the presentation without replacing its focus target.
 */
@Composable
internal fun libraryPresentationCallback(
    enabled: Boolean = true,
    consume: Boolean = true,
    block: () -> Unit,
): () -> Unit {
    val scope = LocalLibraryOrganization.current
    val visible = LocalLibraryVisible.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val lease = remember(scope.state, visible, configuration, density) { LibraryPresentationLease() }
    DisposableEffect(lease) { onDispose { lease.attached = false } }
    return {
        val attached = enabled && visible && lease.attached && !lease.submitted
        val currentContext = scope.visible() && scope.readCurrent() == scope.state
        if (attached && currentContext) {
            if (consume) lease.submitted = true
            block()
        }
    }
}
