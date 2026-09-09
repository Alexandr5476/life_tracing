@file:Suppress("TooManyFunctions")

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
import androidx.compose.runtime.LaunchedEffect
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
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorRoute
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorRouteSessionOwner
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorTarget
import com.alexandr5476.lifetracing.launcher.StartActivityRoute
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSessionOwner
import com.alexandr5476.lifetracing.library.LibraryControllerOwner
import com.alexandr5476.lifetracing.library.LibraryRoute
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
    internal val activityTemplateEditorRouteSessions by lazy {
        ViewModelProvider(this)[ActivityTemplateEditorRouteSessionOwner::class.java]
    }
    internal val libraryControllerOwner by lazy {
        ViewModelProvider(this)[LibraryControllerOwner::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by appearancePreferences.preferences.collectAsState(initial = AppearancePreferences())
            LifeTracingApp(
                appearance,
                startActivityRouteSessions = startActivityRouteSessions,
                activityTemplateEditorRouteSessions = activityTemplateEditorRouteSessions,
                libraryControllerOwner = libraryControllerOwner,
            )
        }
    }
}

/** Navigation identity only: Daily has no domain identifier. */
@Serializable
data object DailyRoot : NavKey

/** Navigation identity only: one launcher exists for one entry on the Daily-owned stack. */
@Serializable
data object StartActivityRoot : NavKey

/** Navigation identity only: Library reads its canonical catalog on entry. */
@Serializable
data object LibraryRoot : NavKey

@Serializable
data object NewActivityTemplateEditor : NavKey

@Serializable
data class ExistingActivityTemplateEditor(
    val id: String,
) : NavKey

internal val dailyInitialBackStack: List<NavKey> = listOf(DailyRoot)

@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun LifeTracingApp(
    appearance: AppearancePreferences = AppearancePreferences(),
    systemIsDark: Boolean = isSystemInDarkTheme(),
    startActivityRouteSessions: StartActivityRouteSessionOwner? = null,
    activityTemplateEditorRouteSessions: ActivityTemplateEditorRouteSessionOwner? = null,
    libraryControllerOwner: LibraryControllerOwner? = null,
) {
    LifeTracingTheme(
        themeMode = appearance.themeMode,
        accentPaletteId = appearance.accentPaletteId,
        systemIsDark = systemIsDark,
    ) {
        Surface {
            val context = LocalContext.current
            // This is the sole production acquisition point for the lazily-owned controller.
            val runtimeGraph =
                remember(context.applicationContext) {
                    LifeTracingRuntimeGraph.from(context.applicationContext)
                }
            val controller = runtimeGraph.dailyController
            val backStack = rememberNavBackStack(dailyInitialBackStack.single())
            val libraryOwner = libraryControllerOwner ?: remember { LibraryControllerOwner() }
            val launcherSessions = startActivityRouteSessions ?: remember { StartActivityRouteSessionOwner() }
            val editorSessions =
                activityTemplateEditorRouteSessions ?: remember { ActivityTemplateEditorRouteSessionOwner() }
            NavDisplay(
                backStack = backStack,
                entryProvider =
                    entryProvider {
                        entry<DailyRoot> {
                            DailyRoute(
                                controller = controller,
                                onStartActivity = {
                                    launcherSessions.acquire {
                                        runtimeGraph.createStartActivityController(libraryOwner::refreshIfInitialized)
                                    }
                                    backStack.openStartActivity()
                                },
                                onLibrary = { backStack.openLibrary() },
                            )
                        }
                        entry<StartActivityRoot> {
                            val session = launcherSessions.activeSession
                            if (session == null) {
                                LaunchedEffect(Unit) { backStack.normalizeRestoredStartActivity() }
                            } else {
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
                                            libraryOwner.refreshIfInitialized()
                                        }
                                    },
                                )
                            }
                        }
                        entry<LibraryRoot> {
                            val activeLibraryController = libraryOwner.get(runtimeGraph::createLibraryController)
                            LibraryRoute(
                                controller = activeLibraryController,
                                onBack = backStack::removeLibrary,
                                onCreateActivity = {
                                    editorSessions.acquire(ActivityTemplateEditorTarget.New) {
                                        runtimeGraph.createActivityTemplateEditorController(
                                            ActivityTemplateEditorTarget.New,
                                        )
                                    }
                                    backStack.openNewActivityTemplateEditor()
                                },
                                onOpenActivity = { id ->
                                    val target = ActivityTemplateEditorTarget.Existing(id)
                                    editorSessions.acquire(target) {
                                        runtimeGraph.createActivityTemplateEditorController(target)
                                    }
                                    backStack.openExistingActivityTemplateEditor(id.value)
                                },
                                onQuickStart = { id ->
                                    val session =
                                        launcherSessions.acquire {
                                            runtimeGraph.createStartActivityController(
                                                libraryOwner::refreshIfInitialized,
                                            )
                                        }
                                    session.primeInitialSelection(id)
                                    backStack.openStartActivity()
                                },
                            )
                        }
                        entry<NewActivityTemplateEditor> {
                            val session = editorSessions.activeSession
                            if (session?.target != ActivityTemplateEditorTarget.New) {
                                LaunchedEffect(Unit) { backStack.normalizeRestoredActivityTemplateEditor() }
                            } else {
                                ActivityTemplateEditorRoute(
                                    session.controller,
                                    onBack = {
                                        editorSessions.release(session)
                                        backStack.removeActivityTemplateEditor()
                                    },
                                    onCommitted = {
                                        session.exitPolicy.deliverCommitted {
                                            editorSessions.release(session)
                                            backStack.completeActivityTemplateEditor(libraryOwner::refreshIfInitialized)
                                        }
                                    },
                                )
                            }
                        }
                        entry<ExistingActivityTemplateEditor> { route ->
                            val session = editorSessions.activeSession
                            val target =
                                ActivityTemplateEditorTarget.Existing(
                                    com.alexandr5476.lifetracing.domain
                                        .ActivityTemplateId(route.id),
                                )
                            if (session?.target != target) {
                                LaunchedEffect(Unit) { backStack.normalizeRestoredActivityTemplateEditor() }
                            } else {
                                ActivityTemplateEditorRoute(
                                    session.controller,
                                    onBack = {
                                        editorSessions.release(session)
                                        backStack.removeActivityTemplateEditor()
                                    },
                                    onCommitted = {
                                        session.exitPolicy.deliverCommitted {
                                            editorSessions.release(session)
                                            backStack.completeActivityTemplateEditor(libraryOwner::refreshIfInitialized)
                                        }
                                    },
                                )
                            }
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

internal fun MutableList<NavKey>.openLibrary() {
    if (lastOrNull() !is LibraryRoot) add(LibraryRoot)
}

internal fun MutableList<NavKey>.removeLibrary() {
    if (lastOrNull() is LibraryRoot) removeAt(lastIndex)
}

internal fun MutableList<NavKey>.openNewActivityTemplateEditor() {
    if (lastOrNull() !is NewActivityTemplateEditor) add(NewActivityTemplateEditor)
}

internal fun MutableList<NavKey>.openExistingActivityTemplateEditor(id: String) {
    if (lastOrNull() !is ExistingActivityTemplateEditor) add(ExistingActivityTemplateEditor(id))
}

internal fun MutableList<NavKey>.removeActivityTemplateEditor() {
    if (lastOrNull() is NewActivityTemplateEditor || lastOrNull() is ExistingActivityTemplateEditor) removeAt(lastIndex)
}

internal fun MutableList<NavKey>.completeActivityTemplateEditor(refreshLibrary: () -> Unit) {
    if (lastOrNull() !is NewActivityTemplateEditor && lastOrNull() !is ExistingActivityTemplateEditor) return
    refreshLibrary()
    removeActivityTemplateEditor()
}

/** No draft is durable; a process-restored editor must not manufacture a new authoring session. */
internal fun MutableList<NavKey>.normalizeRestoredActivityTemplateEditor() {
    removeActivityTemplateEditor()
}

/** A restored launcher route has no durable command state and must never acquire a new controller. */
internal fun MutableList<NavKey>.normalizeRestoredStartActivity() {
    removeStartActivity()
}

internal fun MutableList<NavKey>.completeStartActivity(selectToday: () -> Unit) {
    selectToday()
    removeStartActivity()
}

private fun lifeTracingNavigationTransition(): ContentTransform =
    fadeIn(animationSpec = tween(dailyNavigationTransitionDurationMillis)) togetherWith
        fadeOut(animationSpec = tween(dailyNavigationTransitionDurationMillis))
