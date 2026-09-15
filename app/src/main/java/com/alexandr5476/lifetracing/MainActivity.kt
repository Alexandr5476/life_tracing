@file:Suppress("LongParameterList", "TooManyFunctions")

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
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorRoute
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorRouteSessionOwner
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorTarget
import com.alexandr5476.lifetracing.launcher.StartActivityRoute
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSessionOwner
import com.alexandr5476.lifetracing.library.LibraryControllerOwner
import com.alexandr5476.lifetracing.library.LibraryRoute
import com.alexandr5476.lifetracing.live.ExpandedLiveSequenceRoute
import com.alexandr5476.lifetracing.live.ExpandedLiveSequenceRouteSessionOwner
import com.alexandr5476.lifetracing.plan.PlanControllerOwner
import com.alexandr5476.lifetracing.plan.PlanExecutionCommit
import com.alexandr5476.lifetracing.plan.PlanExecutionOrigin
import com.alexandr5476.lifetracing.plan.PlanExecutionRoute
import com.alexandr5476.lifetracing.plan.PlanExecutionRouteSession
import com.alexandr5476.lifetracing.plan.PlanExecutionRouteSessionOwner
import com.alexandr5476.lifetracing.plan.PlanRoute
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
    internal val sequenceTemplateEditorRouteSessions by lazy {
        ViewModelProvider(this)[SequenceTemplateEditorRouteSessionOwner::class.java]
    }
    internal val libraryControllerOwner by lazy {
        ViewModelProvider(this)[LibraryControllerOwner::class.java]
    }
    internal val expandedLiveSequenceRouteSessions by lazy {
        ViewModelProvider(this)[ExpandedLiveSequenceRouteSessionOwner::class.java]
    }
    internal val planControllerOwner by lazy { ViewModelProvider(this)[PlanControllerOwner::class.java] }
    internal val planExecutionRouteSessions by lazy {
        ViewModelProvider(this)[PlanExecutionRouteSessionOwner::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by appearancePreferences.preferences.collectAsState(initial = AppearancePreferences())
            LifeTracingApp(
                appearance,
                startActivityRouteSessions = startActivityRouteSessions,
                activityTemplateEditorRouteSessions = activityTemplateEditorRouteSessions,
                sequenceTemplateEditorRouteSessions = sequenceTemplateEditorRouteSessions,
                libraryControllerOwner = libraryControllerOwner,
                expandedLiveSequenceRouteSessions = expandedLiveSequenceRouteSessions,
                planControllerOwner = planControllerOwner,
                planExecutionRouteSessions = planExecutionRouteSessions,
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
data object PlanRoot : NavKey

@Serializable
data class PlanExecutionRoot(
    val planEntryId: String,
) : NavKey

@Serializable
data object NewActivityTemplateEditor : NavKey

@Serializable
data class ExistingActivityTemplateEditor(
    val id: String,
) : NavKey

@Serializable data object NewSequenceTemplateEditor : NavKey

@Serializable data class ExistingSequenceTemplateEditor(
    val id: String,
) : NavKey

@Serializable data class ExpandedLiveSequenceRoot(
    val executionId: String,
) : NavKey

internal val dailyInitialBackStack: List<NavKey> = listOf(DailyRoot)

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun LifeTracingApp(
    appearance: AppearancePreferences = AppearancePreferences(),
    systemIsDark: Boolean = isSystemInDarkTheme(),
    startActivityRouteSessions: StartActivityRouteSessionOwner? = null,
    activityTemplateEditorRouteSessions: ActivityTemplateEditorRouteSessionOwner? = null,
    sequenceTemplateEditorRouteSessions: SequenceTemplateEditorRouteSessionOwner? = null,
    libraryControllerOwner: LibraryControllerOwner? = null,
    expandedLiveSequenceRouteSessions: ExpandedLiveSequenceRouteSessionOwner? = null,
    planControllerOwner: PlanControllerOwner? = null,
    planExecutionRouteSessions: PlanExecutionRouteSessionOwner? = null,
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
            val sequenceEditorSessions =
                sequenceTemplateEditorRouteSessions ?: remember { SequenceTemplateEditorRouteSessionOwner() }
            val expandedSequenceSessions =
                expandedLiveSequenceRouteSessions ?: remember { ExpandedLiveSequenceRouteSessionOwner() }
            val planOwner = planControllerOwner ?: remember { PlanControllerOwner() }
            val planExecutionSessions =
                planExecutionRouteSessions ?: remember { PlanExecutionRouteSessionOwner() }
            val closeExpanded: (String) -> Unit = { expectedExecutionId ->
                expandedSequenceSessions.release(
                    com.alexandr5476.lifetracing.domain
                        .SequenceExecutionId(expectedExecutionId),
                )
                backStack.removeExpandedLiveSequence(expectedExecutionId)
            }
            val closePlanExecution: (PlanExecutionRouteSession) -> Unit = { session ->
                planExecutionSessions.release(session)
                backStack.removePlanExecution(session.expectedIdentity.planEntryId.value)
            }
            val reloadPlanExecutionOrigin: (PlanExecutionRouteSession) -> Unit = { session ->
                when (session.origin) {
                    PlanExecutionOrigin.DAILY ->
                        controller.dispatch(com.alexandr5476.lifetracing.daily.DailyAction.Retry)
                    PlanExecutionOrigin.PLAN ->
                        planOwner.get(runtimeGraph::createPlanController).dispatch(
                            com.alexandr5476.lifetracing.plan.PlanAction.Refresh,
                        )
                }
                closePlanExecution(session)
            }
            val completePlanExecution: (PlanExecutionRouteSession, PlanExecutionCommit) -> Unit = { session, result ->
                when (session.origin) {
                    PlanExecutionOrigin.DAILY -> {
                        controller.dispatch(
                            if (result.isLive) {
                                com.alexandr5476.lifetracing.daily.DailyAction.Today
                            } else {
                                com.alexandr5476.lifetracing.daily.DailyAction.Retry
                            },
                        )
                        closePlanExecution(session)
                    }
                    PlanExecutionOrigin.PLAN -> {
                        planOwner.get(runtimeGraph::createPlanController).dispatch(
                            com.alexandr5476.lifetracing.plan.PlanAction.Refresh,
                        )
                        closePlanExecution(session)
                        if (result.isLive) {
                            planOwner.get(runtimeGraph::createPlanController).onRouteExited()
                            backStack.removePlan()
                            controller.dispatch(com.alexandr5476.lifetracing.daily.DailyAction.Today)
                        }
                    }
                }
            }
            NavDisplay(
                backStack = backStack,
                onBack = {
                    val expanded = backStack.lastOrNull() as? ExpandedLiveSequenceRoot
                    val planExecution = backStack.lastOrNull() as? PlanExecutionRoot
                    when {
                        expanded != null -> closeExpanded(expanded.executionId)
                        planExecution != null -> {
                            val session = planExecutionSessions.activeSession
                            if (session == null ||
                                session.expectedIdentity.planEntryId.value != planExecution.planEntryId
                            ) {
                                backStack.normalizeRestoredPlanExecution()
                            } else if (session.controller.state.value.command ==
                                com.alexandr5476.lifetracing.plan.PlanExecutionCommandState.Stale
                            ) {
                                reloadPlanExecutionOrigin(session)
                            } else {
                                session.exitPolicy.requestExit(
                                    session.controller,
                                    { closePlanExecution(session) },
                                    { completePlanExecution(session, it) },
                                )
                            }
                        }
                        backStack.lastOrNull() is PlanRoot -> {
                            planOwner.get(runtimeGraph::createPlanController).onRouteExited()
                            backStack.removePlan()
                        }
                        else -> backStack.removeLastOrNull()
                    }
                },
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
                                onPlan = { backStack.openPlan() },
                                onExpandSequence = { executionId ->
                                    expandedSequenceSessions.acquire(executionId) {
                                        runtimeGraph.createExpandedLiveSequenceController(executionId)
                                    }
                                    backStack.openExpandedLiveSequence(executionId.value)
                                },
                                onExecutePlan = { identity ->
                                    val session =
                                        planExecutionSessions.acquire(identity, PlanExecutionOrigin.DAILY) {
                                            runtimeGraph.createPlanExecutionController(identity)
                                        }
                                    backStack.openPlanExecution(session.expectedIdentity.planEntryId.value)
                                },
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
                                onCreateSequence = {
                                    sequenceEditorSessions.acquire(SequenceTemplateEditorTarget.New) {
                                        runtimeGraph.createSequenceTemplateEditorController(
                                            SequenceTemplateEditorTarget.New,
                                        )
                                    }
                                    backStack.openNewSequenceTemplateEditor()
                                },
                                onOpenActivity = { id ->
                                    val target = ActivityTemplateEditorTarget.Existing(id)
                                    editorSessions.acquire(target) {
                                        runtimeGraph.createActivityTemplateEditorController(target)
                                    }
                                    backStack.openExistingActivityTemplateEditor(id.value)
                                },
                                onOpenSequence = { id ->
                                    val target = SequenceTemplateEditorTarget.Existing(id)
                                    sequenceEditorSessions.acquire(target) {
                                        runtimeGraph.createSequenceTemplateEditorController(target)
                                    }
                                    backStack.openExistingSequenceTemplateEditor(id.value)
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
                        entry<PlanRoot> {
                            val planController = planOwner.get(runtimeGraph::createPlanController)
                            PlanRoute(
                                planController,
                                onBack = {
                                    planController.onRouteExited()
                                    backStack.removePlan()
                                },
                                onExecute = { identity ->
                                    val session =
                                        planExecutionSessions.acquire(identity, PlanExecutionOrigin.PLAN) {
                                            runtimeGraph.createPlanExecutionController(identity)
                                        }
                                    backStack.openPlanExecution(session.expectedIdentity.planEntryId.value)
                                },
                            )
                        }
                        entry<PlanExecutionRoot> { route ->
                            val session = planExecutionSessions.activeSession
                            if (session == null || session.expectedIdentity.planEntryId.value != route.planEntryId) {
                                LaunchedEffect(Unit) { backStack.normalizeRestoredPlanExecution() }
                            } else {
                                PlanExecutionRoute(
                                    session,
                                    onBack = { closePlanExecution(session) },
                                    onReloadOrigin = { reloadPlanExecutionOrigin(session) },
                                    onCommitted = { completePlanExecution(session, it) },
                                )
                            }
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
                        entry<NewSequenceTemplateEditor> {
                            val session = sequenceEditorSessions.activeSession
                            if (session?.target != SequenceTemplateEditorTarget.New) {
                                LaunchedEffect(Unit) { backStack.normalizeRestoredSequenceTemplateEditor() }
                            } else {
                                SequenceTemplateEditorRoute(session.controller, onBack = {
                                    sequenceEditorSessions.release(session)
                                    backStack.removeSequenceTemplateEditor()
                                }, onApplied = {
                                    libraryOwner.refreshIfInitialized()
                                }, onCommitted = {
                                    session.exitPolicy.deliverCommitted {
                                        sequenceEditorSessions.release(session)
                                        backStack.completeSequenceTemplateEditor(libraryOwner::refreshIfInitialized)
                                    }
                                })
                            }
                        }
                        entry<ExistingSequenceTemplateEditor> { route ->
                            val session = sequenceEditorSessions.activeSession
                            val target =
                                SequenceTemplateEditorTarget.Existing(
                                    com.alexandr5476.lifetracing.domain
                                        .SequenceTemplateId(route.id),
                                )
                            if (session?.target != target) {
                                LaunchedEffect(Unit) { backStack.normalizeRestoredSequenceTemplateEditor() }
                            } else {
                                SequenceTemplateEditorRoute(session.controller, onBack = {
                                    sequenceEditorSessions.release(session)
                                    backStack.removeSequenceTemplateEditor()
                                }, onApplied = {
                                    libraryOwner.refreshIfInitialized()
                                }, onCommitted = {
                                    session.exitPolicy.deliverCommitted {
                                        sequenceEditorSessions.release(session)
                                        backStack.completeSequenceTemplateEditor(libraryOwner::refreshIfInitialized)
                                    }
                                })
                            }
                        }
                        entry<ExpandedLiveSequenceRoot> { route ->
                            val executionId =
                                com.alexandr5476.lifetracing.domain
                                    .SequenceExecutionId(route.executionId)
                            val session =
                                remember(route.executionId) {
                                    expandedSequenceSessions.acquire(executionId) {
                                        runtimeGraph.createExpandedLiveSequenceController(executionId)
                                    }
                                }
                            val close = { closeExpanded(route.executionId) }
                            ExpandedLiveSequenceRoute(session.controller, onBack = close, onStale = close)
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

internal fun MutableList<NavKey>.openPlan() {
    if (lastOrNull() !is PlanRoot) add(PlanRoot)
}

internal fun MutableList<NavKey>.removePlan() {
    if (lastOrNull() is PlanRoot) removeAt(lastIndex)
}

internal fun MutableList<NavKey>.openPlanExecution(planEntryId: String) {
    if (lastOrNull() !is PlanExecutionRoot) add(PlanExecutionRoot(planEntryId))
}

internal fun MutableList<NavKey>.removePlanExecution(expectedPlanEntryId: String) {
    if ((lastOrNull() as? PlanExecutionRoot)?.planEntryId == expectedPlanEntryId) removeAt(lastIndex)
}

/** A restored route has no expected identity or transient countdown/editor state. */
internal fun MutableList<NavKey>.normalizeRestoredPlanExecution() {
    if (lastOrNull() is PlanExecutionRoot) removeAt(lastIndex)
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

internal fun MutableList<NavKey>.openNewSequenceTemplateEditor() {
    if (lastOrNull() !is NewSequenceTemplateEditor) add(NewSequenceTemplateEditor)
}

internal fun MutableList<NavKey>.openExistingSequenceTemplateEditor(id: String) {
    if (lastOrNull() !is ExistingSequenceTemplateEditor) add(ExistingSequenceTemplateEditor(id))
}

internal fun MutableList<NavKey>.removeSequenceTemplateEditor() {
    if (lastOrNull() is NewSequenceTemplateEditor || lastOrNull() is ExistingSequenceTemplateEditor) removeAt(lastIndex)
}

internal fun MutableList<NavKey>.completeSequenceTemplateEditor(refreshLibrary: () -> Unit) {
    if (lastOrNull() is NewSequenceTemplateEditor || lastOrNull() is ExistingSequenceTemplateEditor) {
        refreshLibrary()
        removeSequenceTemplateEditor()
    }
}

internal fun MutableList<NavKey>.normalizeRestoredSequenceTemplateEditor() {
    removeSequenceTemplateEditor()
}

/** A restored launcher route has no durable command state and must never acquire a new controller. */
internal fun MutableList<NavKey>.normalizeRestoredStartActivity() {
    removeStartActivity()
}

internal fun MutableList<NavKey>.completeStartActivity(selectToday: () -> Unit) {
    selectToday()
    removeStartActivity()
}

internal fun MutableList<NavKey>.openExpandedLiveSequence(executionId: String) {
    if (lastOrNull() !is ExpandedLiveSequenceRoot) add(ExpandedLiveSequenceRoot(executionId))
}

internal fun MutableList<NavKey>.removeExpandedLiveSequence(expectedExecutionId: String) {
    if ((lastOrNull() as? ExpandedLiveSequenceRoot)?.executionId == expectedExecutionId) removeAt(lastIndex)
}

private fun lifeTracingNavigationTransition(): ContentTransform =
    fadeIn(animationSpec = tween(dailyNavigationTransitionDurationMillis)) togetherWith
        fadeOut(animationSpec = tween(dailyNavigationTransitionDurationMillis))
