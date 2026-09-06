package com.alexandr5476.lifetracing

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.alexandr5476.lifetracing.daily.DailyRoute
import com.alexandr5476.lifetracing.launcher.StartActivityRoute
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSessionOwner
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.theme.LifeTracingMotion
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.serialization.Serializable

class MainActivity : AppCompatActivity() {
    private val appearancePreferences by lazy { AppearancePreferencesRepository(applicationContext) }
    internal val startActivityRouteSessions by lazy {
        ViewModelProvider(this)[StartActivityRouteSessionOwner::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by appearancePreferences.preferences.collectAsState(initial = AppearancePreferences())
            LifeTracingApp(appearance, startActivityRouteSessions = startActivityRouteSessions)
        }
    }
}

/** Navigation identity only: Daily has no domain identifier. */
@Serializable
data object DailyRoot : NavKey

/** Navigation identity only: one launcher exists for one entry on the Daily-owned stack. */
@Serializable
data object StartActivityRoot : NavKey

internal val dailyInitialBackStack: List<NavKey> = listOf(DailyRoot)

@Composable
@Suppress("FunctionNaming")
internal fun LifeTracingApp(
    appearance: AppearancePreferences = AppearancePreferences(),
    systemIsDark: Boolean = isSystemInDarkTheme(),
    startActivityRouteSessions: StartActivityRouteSessionOwner? = null,
) {
    LifeTracingTheme(
        themeMode = appearance.themeMode,
        accentPaletteId = appearance.accentPaletteId,
        systemIsDark = systemIsDark,
    ) {
        Surface {
            val context = LocalContext.current
            // This is the sole production acquisition point for the lazily-owned controller.
            val controller =
                remember(context.applicationContext) {
                    LifeTracingRuntimeGraph.from(context.applicationContext).dailyController
                }
            val backStack = rememberNavBackStack(dailyInitialBackStack.single())
            val launcherSessions = startActivityRouteSessions ?: remember { StartActivityRouteSessionOwner() }
            NavDisplay(
                backStack = backStack,
                entryProvider =
                    entryProvider {
                        entry<DailyRoot> {
                            DailyRoute(
                                controller = controller,
                                onStartActivity = { backStack.openStartActivity() },
                            )
                        }
                        entry<StartActivityRoot> {
                            val session =
                                launcherSessions.acquire {
                                    LifeTracingRuntimeGraph
                                        .from(context.applicationContext)
                                        .createStartActivityController()
                                }
                            StartActivityRoute(
                                session = session,
                                onBack = {
                                    launcherSessions.release(session)
                                    backStack.removeStartActivity()
                                },
                                onCommitted = {
                                    launcherSessions.release(session)
                                    backStack.completeStartActivity {
                                        controller.dispatch(com.alexandr5476.lifetracing.daily.DailyAction.Today)
                                    }
                                },
                            )
                        }
                    },
                transitionSpec = { lifeTracingNavigationTransition() },
                popTransitionSpec = { lifeTracingNavigationTransition() },
                predictivePopTransitionSpec = { _ -> lifeTracingNavigationTransition() },
            )
        }
    }
}

internal val dailyNavigationTransitionDurationMillis = LifeTracingMotion.standardDurationMillis

internal fun MutableList<NavKey>.openStartActivity() {
    if (lastOrNull() !is StartActivityRoot) add(StartActivityRoot)
}

internal fun MutableList<NavKey>.removeStartActivity() {
    if (lastOrNull() is StartActivityRoot) removeAt(lastIndex)
}

internal fun MutableList<NavKey>.completeStartActivity(selectToday: () -> Unit) {
    selectToday()
    removeStartActivity()
}

private fun lifeTracingNavigationTransition(): ContentTransform =
    fadeIn(animationSpec = tween(dailyNavigationTransitionDurationMillis)) togetherWith
        fadeOut(animationSpec = tween(dailyNavigationTransitionDurationMillis))
