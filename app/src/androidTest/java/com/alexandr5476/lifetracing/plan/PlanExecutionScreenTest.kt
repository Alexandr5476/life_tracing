@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.alexandr5476.lifetracing.plan

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlanExecutionScreenTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun focusedHeadingAndFrozenSourceContextAreDiscoverable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val action = focusedActivity(activity())
            val controller = controller(scope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.DAILY, controller)
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        PlanExecutionState(
                            PlanExecutionLoad.Content(PreparedPlanExecution(action, Duration.ZERO, true)),
                        ),
                        session,
                        {},
                        {},
                    )
                }
            }
            compose.onNodeWithText("Plan execution").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithText("Activity").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithText("Source unavailable").assertExists()
            compose.onNodeWithText("Day: 2026-09-15, any time").assertExists()
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun activityOffersSecondaryTimeEntry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val activity = activity()
            val action = focusedActivity(activity)
            val controller = controller(scope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.DAILY, controller)
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        PlanExecutionState(
                            PlanExecutionLoad.Content(PreparedPlanExecution(action, Duration.ZERO, true)),
                        ),
                        session,
                        {},
                        {},
                    )
                }
            }
            compose.onNodeWithTag("plan-execution-time-entry").assertExists().performClick()
            compose.onNodeWithTag("plan-time-start").assertExists()
            compose.onNodeWithTag("plan-time-end").assertExists()
            compose.onNodeWithTag("plan-time-save").assertExists()
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sequenceDoesNotOfferSecondaryTimeEntry() {
        val sequenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val action = focusedSequence(activity())
            val controller = controller(sequenceScope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.PLAN, controller)
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        PlanExecutionState(
                            PlanExecutionLoad.Content(PreparedPlanExecution(action, Duration.ZERO, true)),
                        ),
                        session,
                        {},
                        {},
                    )
                }
            }
            compose.onNodeWithTag("plan-execution-time-entry").assertDoesNotExist()
            compose
                .onNode(
                    hasText("Sequence") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                ).assertExists()
            compose.onNode(hasText("Start") and hasClickAction()).assertIsEnabled()
            compose.onNode(hasText("Enter time") and hasClickAction()).assertDoesNotExist()
            controller.close()
        } finally {
            sequenceScope.cancel()
        }
    }

    @Test
    fun quickFieldsExposeNativeEditingMainValueSelectedCategoryAndMissingWithoutFocusWrites() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val fields = frozenFields()
            val snapshot = activity().copy(timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING, fields = fields)
            val action = focusedActivity(snapshot)
            val controller = controller(scope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.PLAN, controller)
            session.prepareQuickDraft(snapshot)
            compose.setContent { LifeTracingTheme { PlanExecutionRoute(session, {}, {}, {}) } }
            keyboard.enter()
            val number = fields.first()
            val numberLabel = "Amount kg, Main Value"
            compose
                .onNode(hasText(numberLabel) and hasSetTextAction())
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Configured default, unchanged"),
                )
            compose.waitForIdle()
            assertTrue(session.quickDraft!!.changed.isEmpty())
            assertTrue(session.quickDraft!!.overrides(fields).isEmpty())
            set(numberLabel, "-1.25")
            assertEquals(NumberExecutionValue(number.id, -1250), session.quickDraft!!.values[number.id])
            set(numberLabel, "invalid")
            compose.onNode(hasText("Complete") and hasClickAction()).assertIsNotEnabled()
            invoke("Amount kg, Main Value: Set missing")
            assertNull(session.quickDraft!!.values[number.id])
            compose
                .onNode(hasText(numberLabel) and hasSetTextAction())
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Missing"))
            set(numberLabel, "0")
            set("Note", "edited text")
            assertEquals(
                com.alexandr5476.lifetracing.domain
                    .TextExecutionValue(fields[1].id, "edited text"),
                session.quickDraft!!.values[fields[1].id],
            )
            Espresso.closeSoftKeyboard()
            invoke("Effort: A")
            compose
                .onNode(hasText("Selected: Effort: A") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .assertIsSelected()
            val category = fields.last()
            compose
                .onNode(hasText("Effort: B") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .assertIsNotSelected()
                .performSemanticsAction(SemanticsActions.RequestFocus)
            assertEquals(
                CategoryExecutionValue(category.id, category.categoryOptions.first().id),
                session.quickDraft!!.values[category.id],
            )
            assertTrue(category.id !in session.quickDraft!!.changed)
            dismissCategoryMenu(compose.onNode(isPopup()))
            compose
                .onNode(
                    hasText("Effort: B") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
                ).assertDoesNotExist()
            invoke("Effort: A")
            compose
                .onNode(
                    hasText("Effort: B") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
                ).performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(
                CategoryExecutionValue(category.id, category.categoryOptions.last().id),
                session.quickDraft!!.values[category.id],
            )
            invoke("Effort: Set missing")
            invoke("Effort: Missing")
            compose
                .onNode(
                    hasText("Selected: Effort: Set missing") and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
                ).assertIsSelected()
            compose
                .onNode(hasText("Effort: B") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
                .assertIsNotSelected()
            compose.runOnIdle { session.prepareQuickDraft(snapshot) }
            assertEquals(3, session.quickDraft!!.overrides(fields).size)
            assertEquals(PlanExecutionCommandState.Idle, controller.state.value.command)
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun countdownIsPassiveAtBeforeExactAndAfterZeroWithoutCommitClaim() {
        val deadline = now.plusSeconds(5)
        val preflight = PlanExecutionCommandState.Preflight(7, Duration.ofSeconds(5), now, deadline)
        val clock = mutableStateOf(now)
        compose.setContent { LifeTracingTheme { PlanPreflightCountdown(preflight, clock.value) } }
        compose.onNodeWithContentDescription("Countdown remaining: 0:05").assertExists()
        for (instant in listOf(deadline, deadline.plusSeconds(2))) {
            compose.runOnIdle { clock.value = instant }
            compose.onNodeWithContentDescription("Countdown remaining: 0:00").assertExists()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                ).assertCountEquals(0)
            compose.onNodeWithText("Saved.").assertDoesNotExist()
        }
    }

    @Test
    fun materialCommandFeedbackRemainsDistinctAndCannotExposeSecondStart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val action = focusedActivity(activity())
            val controller = controller(scope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.DAILY, controller)
            val commit = PlanExecutionCommit.Activity(ActivityExecutionId("saved"), true)
            val command = mutableStateOf<PlanExecutionCommandState>(PlanExecutionCommandState.Idle)
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        PlanExecutionState(
                            PlanExecutionLoad.Content(PreparedPlanExecution(action, Duration.ZERO, true)),
                            command.value,
                        ),
                        session,
                        {},
                        {},
                    )
                }
            }
            val states =
                listOf(
                    PlanExecutionCommandState.Checking(1) to R.string.plan_execution_checking,
                    PlanExecutionCommandState.Committing(1) to R.string.plan_execution_committing,
                    PlanExecutionCommandState.Stale to R.string.plan_execution_stale,
                    PlanExecutionCommandState.Conflict("conflict") to R.string.plan_execution_conflict,
                    PlanExecutionCommandState.Rejected("rejected") to R.string.plan_execution_rejected,
                    PlanExecutionCommandState.ExpiredFinish to R.string.plan_time_entry_expired_finish,
                    PlanExecutionCommandState.Committed(commit) to R.string.plan_execution_committed,
                    PlanExecutionCommandState.CommittedCoordinationFailure(commit, "coordination") to
                        R.string.plan_execution_committed_coordination,
                )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for ((state, label) in states) {
                compose.runOnIdle { command.value = state }
                compose
                    .onNode(hasText(context.getString(label)))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
                compose.onNode(hasText("Start") and hasClickAction()).assertIsNotEnabled()
                compose.onNode(hasText("Enter time") and hasClickAction()).assertIsNotEnabled()
                if (state is PlanExecutionCommandState.Committed ||
                    state is PlanExecutionCommandState.CommittedCoordinationFailure
                ) {
                    compose.onNodeWithText("Retry").assertDoesNotExist()
                }
            }
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadingReadFailureRetryAndStaleReloadAreIndependentNativeActions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val action = focusedActivity(activity())
            val controller = controller(scope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.DAILY, controller)
            val state = mutableStateOf(PlanExecutionState())
            var reloads = 0
            var backs = 0
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        state.value,
                        session,
                        { backs++ },
                        { reloads++ },
                    )
                }
            }
            compose.onNodeWithText("Loading the frozen plan…").assertExists()
            compose.runOnIdle { state.value = PlanExecutionState(PlanExecutionLoad.Failure("failure")) }
            for (unmerged in listOf(false, true)) {
                compose
                    .onAllNodes(
                        hasClickAction() and (hasText("Retry") or hasAnyDescendant(hasText("Retry"))),
                        useUnmergedTree = unmerged,
                    ).assertCountEquals(1)
            }
            invoke("Retry")
            compose.runOnIdle { state.value = PlanExecutionState(command = PlanExecutionCommandState.Stale) }
            compose.onNodeWithText("Loading the frozen plan…").assertDoesNotExist()
            invoke("Reload")
            invoke("Back")
            assertEquals(1, reloads)
            assertEquals(1, backs)
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun frozenExactLegacyAndWeekContextsKeepSourceStateSeparateFromScheduling() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val base = focusedActivity(activity())
            val controller = controller(scope, base)
            val session = PlanExecutionRouteSession(base.identity, PlanExecutionOrigin.PLAN, controller)
            val action = mutableStateOf(base)
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        PlanExecutionState(
                            PlanExecutionLoad.Content(PreparedPlanExecution(action.value, Duration.ZERO, true)),
                        ),
                        session,
                        {},
                        {},
                    )
                }
            }
            compose.runOnIdle {
                action.value =
                    base.copy(
                        identity = base.identity.copy(target = PlanTarget.ExactDay(now, null)),
                        sourceState = PlanSourceState.CHANGED,
                    )
            }
            compose.onNode(hasText("Scheduled instant: 2026-09-15T10:00:00Z")).assertExists()
            compose.onNode(hasText("Creation time zone unavailable")).assertExists()
            compose.onNode(hasText("Source changed")).assertExists()
            compose.onNode(hasText("Start") and hasClickAction()).assertIsEnabled()
            compose.runOnIdle {
                action.value =
                    base.copy(
                        identity = base.identity.copy(target = PlanTarget.Week(LocalDate.parse("2026-09-14"))),
                        sourceState = PlanSourceState.ARCHIVED,
                    )
            }
            compose.onNode(hasText("Week starting 2026-09-14")).assertExists()
            compose.onNode(hasText("Source archived")).assertExists()
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun englishRussianKeyboardAndNativeTargetsRemainReachableAtCombinedScaleExtremes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val original = InstrumentationRegistry.getInstrumentation().targetContext
            val language = mutableStateOf("en")
            val appearance =
                mutableStateOf(
                    com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                        interfaceScalePercent = 120,
                        textScalePercent = 130,
                    ),
                )
            val snapshot =
                activity().copy(
                    timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                    fields = frozenFields(),
                )
            val action = focusedActivity(snapshot)
            val controller = controller(scope, action)
            val session =
                PlanExecutionRouteSession(
                    action.identity,
                    PlanExecutionOrigin.PLAN,
                    controller,
                    now = { now },
                    deviceZone = { ZoneOffset.UTC },
                )
            compose.setContent {
                val configuration =
                    android.content.res.Configuration(original.resources.configuration).apply {
                        setLocale(java.util.Locale.forLanguageTag(language.value))
                        fontScale = 2f
                    }
                val context = original.createConfigurationContext(configuration)
                val parent = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides context,
                    androidx.compose.ui.platform.LocalConfiguration provides configuration,
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit
                            .Density(parent.density, 2f),
                ) {
                    com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance(appearance.value) {
                        PlanExecutionRoute(session, {}, {}, {})
                    }
                }
            }
            keyboard.enter()
            for (locale in listOf("en", "ru")) {
                compose.runOnIdle { language.value = locale }
                val context =
                    original.createConfigurationContext(
                        android.content.res.Configuration(original.resources.configuration).apply {
                            setLocale(java.util.Locale.forLanguageTag(locale))
                        },
                    )

                fun actionNode(id: Int) =
                    compose.onNode(hasText(context.getString(id)) and hasClickAction()).performScrollTo()
                val complete =
                    actionNode(
                        R.string.plan_complete_action,
                    ).assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot
                val secondary =
                    actionNode(
                        R.string.plan_time_entry_action,
                    ).assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot
                // Coordinates are compared in content space by returning to the same scroll position.
                actionNode(R.string.plan_complete_action)
                val completeNow =
                    compose
                        .onNode(
                            hasText(context.getString(R.string.plan_complete_action)) and hasClickAction(),
                        ).fetchSemanticsNode()
                        .touchBoundsInRoot
                val secondaryNow =
                    compose
                        .onNode(
                            hasText(context.getString(R.string.plan_time_entry_action)) and hasClickAction(),
                        ).fetchSemanticsNode()
                        .touchBoundsInRoot
                assertTrue(!completeNow.overlaps(secondaryNow))
                assertTrue(complete.height > 0 && secondary.height > 0)
                Espresso.closeSoftKeyboard()
                invoke(context.getString(R.string.plan_execution_category_value, "Effort", "A"))
                val missing =
                    compose
                        .onNode(
                            hasText(context.getString(R.string.plan_execution_field_missing, "Effort")) and
                                SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
                        ).assertIsDisplayed()
                        .assertIsNotSelected()
                val menuBounds = missing.fetchSemanticsNode().touchBoundsInRoot
                val minimumTarget = 48 * original.resources.displayMetrics.density
                assertTrue(menuBounds.height >= minimumTarget - 1 && menuBounds.width >= minimumTarget - 1)
                dismissCategoryMenu(compose.onNode(isPopup()))
                assertTrue(session.quickDraft!!.changed.isEmpty())
                val timeEntry = actionNode(R.string.plan_time_entry_action)
                timeEntry.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
                timeEntry.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
                compose
                    .onNode(hasText(context.getString(R.string.plan_time_entry_completion)) and hasSetTextAction())
                    .performScrollTo()
                    .performSemanticsAction(SemanticsActions.RequestFocus)
                assertTrue(session.quickDraft!!.changed.isEmpty())
                actionNode(R.string.plan_time_entry_save).assertIsDisplayed().assertIsEnabled()
                actionNode(R.string.plan_time_entry_back).performSemanticsAction(SemanticsActions.OnClick)
                compose.onNode(hasText(context.getString(R.string.plan_back)) and hasClickAction()).assertIsFocused()
            }
            compose.runOnIdle {
                appearance.value =
                    com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                        interfaceScalePercent = 90,
                        textScalePercent = 90,
                    )
            }
            val context =
                original.createConfigurationContext(
                    android.content.res.Configuration(original.resources.configuration).apply {
                        setLocale(java.util.Locale.forLanguageTag("ru"))
                    },
                )
            val minimum = 48 * original.resources.displayMetrics.density
            for (id in listOf(R.string.plan_complete_action, R.string.plan_time_entry_action, R.string.plan_back)) {
                val bounds =
                    compose
                        .onNode(hasText(context.getString(id)) and hasClickAction())
                        .performScrollTo()
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .touchBoundsInRoot
                assertTrue(bounds.height >= minimum - 1 && bounds.width >= minimum - 1)
            }
            Espresso.closeSoftKeyboard()
            invoke(context.getString(R.string.plan_execution_category_value, "Effort", "A"))
            val missingBounds =
                compose
                    .onNode(
                        hasText(context.getString(R.string.plan_execution_field_missing, "Effort")) and
                            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
                    ).assertIsDisplayed()
                    .fetchSemanticsNode()
                    .touchBoundsInRoot
            val selectedBounds =
                compose
                    .onNode(
                        hasText(
                            context.getString(
                                R.string.plan_execution_selected_category,
                                context.getString(R.string.plan_execution_category_value, "Effort", "A"),
                            ),
                        ) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
                    ).assertIsDisplayed()
                    .fetchSemanticsNode()
                    .touchBoundsInRoot
            assertTrue(missingBounds.height >= minimum - 1 && selectedBounds.height >= minimum - 1)
            assertTrue(!missingBounds.overlaps(selectedBounds))
            dismissCategoryMenu(compose.onNode(isPopup()))
            assertTrue(session.quickDraft!!.changed.isEmpty())
            assertEquals(PlanExecutionCommandState.Idle, controller.state.value.command)
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun localizedTimedOffsetChoicesStayIndependentAtMaximumScaleAndZoneReviewRetainsText() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val original = InstrumentationRegistry.getInstrumentation().targetContext
            val language = mutableStateOf("en")
            var zone: java.time.ZoneId = java.time.ZoneId.of("America/New_York")
            val frozen = activity()
            val action = focusedActivity(frozen)
            val controller = controller(scope, action)
            val session =
                PlanExecutionRouteSession(
                    action.identity,
                    PlanExecutionOrigin.PLAN,
                    controller,
                    now = { Instant.parse("2026-11-01T12:00:00Z") },
                    deviceZone = { zone },
                )
            compose.setContent {
                val configuration =
                    android.content.res.Configuration(original.resources.configuration).apply {
                        setLocale(java.util.Locale.forLanguageTag(language.value))
                        fontScale = 2f
                    }
                val parent = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides
                        original.createConfigurationContext(configuration),
                    androidx.compose.ui.platform.LocalConfiguration provides configuration,
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit
                            .Density(parent.density, 2f),
                ) {
                    com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance(
                        com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                            interfaceScalePercent = 120,
                            textScalePercent = 130,
                        ),
                    ) { PlanExecutionRoute(session, {}, {}, {}) }
                }
            }
            keyboard.enter()
            for (locale in listOf("en", "ru")) {
                compose.runOnIdle {
                    language.value = locale
                    zone = java.time.ZoneId.of("America/New_York")
                    session.openTimeEntry(frozen)
                    session.editStarted("2026-11-01T01:10")
                    session.editCompleted("2026-11-01T01:50")
                }
                val context =
                    original.createConfigurationContext(
                        android.content.res.Configuration(original.resources.configuration).apply {
                            setLocale(java.util.Locale.forLanguageTag(locale))
                        },
                    )

                fun choice(
                    start: Boolean,
                    second: Boolean,
                ): String =
                    context.getString(
                        R.string.plan_execution_offset,
                        context.getString(
                            if (start) R.string.plan_time_entry_start else R.string.plan_time_entry_completion,
                        ),
                        context.getString(
                            if (second) {
                                R.string.manual_history_second_occurrence
                            } else {
                                R.string.manual_history_first_occurrence
                            },
                            if (second) "-05:00" else "-04:00",
                        ),
                    )
                val start = choice(true, false)
                val end = choice(false, true)
                for (label in listOf(start, end)) {
                    compose
                        .onNode(hasText(label) and hasClickAction())
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertIsNotSelected()
                        .performSemanticsAction(SemanticsActions.RequestFocus)
                }
                assertNull(session.timeDraft?.startedOffset)
                assertNull(session.timeDraft?.completedOffset)
                invoke(start)
                invoke(end)
                for (label in listOf(start, end)) {
                    compose
                        .onNode(hasText(label, substring = true) and hasClickAction())
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertIsSelected()
                }
                compose.runOnIdle {
                    zone = ZoneOffset.UTC
                    session.submitTimeEntry(frozen)
                }
                compose
                    .onNode(
                        hasText(context.getString(R.string.plan_time_entry_zone_changed)),
                    ).performScrollTo()
                    .assertIsDisplayed()
                invoke(context.getString(R.string.plan_time_entry_review_zone))
                assertEquals("2026-11-01T01:10", session.timeDraft?.startedText)
                assertEquals("2026-11-01T01:50", session.timeDraft?.completedText)
                assertNull(session.timeDraft?.startedOffset)
                assertNull(session.timeDraft?.completedOffset)
                assertEquals(ZoneOffset.UTC, session.timeDraft?.zoneId)
                invoke(context.getString(R.string.plan_time_entry_back))
            }
            assertEquals(PlanExecutionCommandState.Idle, controller.state.value.command)
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    private fun dismissCategoryMenu(target: SemanticsNodeInteraction) {
        keyboard.sendKey(target, android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    private fun set(
        label: String,
        text: String,
    ) {
        compose
            .onNode(
                hasText(label) and hasSetTextAction(),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString(text))
            }
    }

    private fun invoke(label: String) {
        compose
            .onNode(
                hasText(label) and hasClickAction(),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun frozenFields(): List<ActivitySnapshotField> {
        val a = ActivitySnapshotCategoryOption(ActivitySnapshotCategoryOptionId("a"), null, 0, "A")
        val b = ActivitySnapshotCategoryOption(ActivitySnapshotCategoryOptionId("b"), null, 1, "B")
        return listOf(
            ActivitySnapshotField(
                ActivitySnapshotFieldId("number"),
                null,
                2,
                "Amount",
                type = CustomFieldType.NUMBER,
                unit = "kg",
                displayPrecision = 2,
                defaultNumberScaled = 1234,
                isMainValue = true,
            ),
            ActivitySnapshotField(
                ActivitySnapshotFieldId("text"),
                null,
                0,
                "Note",
                type = CustomFieldType.TEXT,
                defaultText = "original",
            ),
            ActivitySnapshotField(
                ActivitySnapshotFieldId("category"),
                null,
                1,
                "Effort",
                type = CustomFieldType.CATEGORY,
                defaultCategoryOptionId = a.id,
                categoryOptions = listOf(a, b),
            ),
        )
    }

    private fun controller(
        scope: CoroutineScope,
        action: FocusedPlanAction,
    ) = PlanExecutionController(
        scope,
        action.identity,
        { action },
        { false },
        { error("No command is submitted in this presentation test") },
        {},
        WallClock { now },
        { ZoneOffset.UTC },
        object : PreflightScheduler {
            override fun schedule(
                duration: Duration,
                onBoundary: () -> Unit,
            ) = PreflightHandle {}
        },
    )

    private fun activity() =
        ActivityConfigSnapshot(
            ActivitySnapshotId("activity-snapshot"),
            "Activity",
            null,
            TimeTrackingMode.STOPWATCH,
            null,
            null,
            null,
            null,
            false,
            now,
        )

    private fun focusedActivity(snapshot: ActivityConfigSnapshot) =
        FocusedPlanAction(
            identity(PlanTrackableKind.ACTIVITY, snapshot.id, null),
            PlanSourceState.UNAVAILABLE,
            false,
            FocusedPlanAction.Snapshot.Activity(snapshot),
        )

    private fun focusedSequence(activity: ActivityConfigSnapshot): FocusedPlanAction {
        val snapshot =
            SequenceConfigSnapshot(
                SequenceSnapshotId("sequence-snapshot"),
                "Sequence",
                null,
                null,
                null,
                null,
                now,
                SequenceSnapshotSettings(
                    autoAdvance = false,
                    sequenceStartCountdown = Duration.ZERO,
                    beforeEachStepCountdown = Duration.ZERO,
                    transitionSound = false,
                    transitionVibration = false,
                    keepScreenAwake = false,
                    confirmJump = false,
                    confirmEarlyEnd = false,
                    noLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
                ),
                nodes =
                    listOf(
                        SequenceSnapshotActivityStep(
                            SequenceSnapshotNodeId("step"),
                            0,
                            activity.id,
                            SequenceStepOverrides(),
                        ),
                    ),
            )
        return FocusedPlanAction(
            identity(PlanTrackableKind.SEQUENCE, null, snapshot.id),
            PlanSourceState.UNAVAILABLE,
            false,
            FocusedPlanAction.Snapshot.Sequence(snapshot, mapOf(activity.id to activity)),
        )
    }

    private fun identity(
        kind: PlanTrackableKind,
        activity: ActivitySnapshotId?,
        sequence: SequenceSnapshotId?,
    ) = PlanActionIdentity(
        PlanEntryId("plan"),
        kind,
        activity,
        sequence,
        PlanTarget.FloatingDay(LocalDate.parse("2026-09-15")),
        PlanEntryStatus.PLANNED,
        null,
        now,
    )

    private companion object {
        val now: Instant = Instant.parse("2026-09-15T10:00:00Z")
    }
}
