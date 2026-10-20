package com.alexandr5476.lifetracing.ui.components

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Reserves native Material target space even when app interface density is reduced. */
@Composable
@Suppress("FunctionNaming", "MagicNumber")
internal fun LifeTracingAccessibleTargets(content: @Composable () -> Unit) {
    val systemDensity = LocalContext.current.resources.displayMetrics.density
    val minimumTarget = with(LocalDensity.current) { (48 * systemDensity).toDp() }.coerceAtLeast(48.dp)
    val parentConfiguration = LocalViewConfiguration.current
    val targetConfiguration =
        remember(parentConfiguration, minimumTarget) {
            object : ViewConfiguration by parentConfiguration {
                override val minimumTouchTargetSize =
                    DpSize(
                        parentConfiguration.minimumTouchTargetSize.width.coerceAtLeast(minimumTarget),
                        parentConfiguration.minimumTouchTargetSize.height.coerceAtLeast(minimumTarget),
                    )
            }
        }
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides
            maxOf(
                targetConfiguration.minimumTouchTargetSize.width,
                targetConfiguration.minimumTouchTargetSize.height,
            ),
        LocalViewConfiguration provides targetConfiguration,
        content = content,
    )
}
