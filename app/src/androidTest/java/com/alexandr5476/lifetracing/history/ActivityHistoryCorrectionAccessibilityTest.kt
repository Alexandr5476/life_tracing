@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.history

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ActivityHistoryCorrectionAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = mutableListOf<ActivityHistoryMutationController>()

    @After
    fun close() {
        controllers.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun nativeInputsKeepDuplicateFieldAndOptionIdentityAndMissingZeroEmptyDistinct() {
        val controller = controller()
        show(controller)
        click("history-correct")
        compose.onNodeWithTag("history-correction-started").assertIsFocused()
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithTag("history-correction-started"))
        compose.onNodeWithTag("history-correction-started").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("history-correction-completed").assertIsFocused()
        compose.onNodeWithTag("history-correction-completed").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        compose.onNodeWithTag("history-correction-started").assertIsFocused()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.history_configured_value, "12.6 kg"),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.manual_history_number_format, "kg", 1),
            ).performScrollTo()
            .assertIsDisplayed()
        input("history-correction-field-number", "bad")
        click("history-correction-save")
        assertEquals(ActivitySnapshotFieldId("number"), controller.state.value.issueFieldId)
        compose.onNodeWithTag("history-correction-field-number").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Error),
        )
        input("history-correction-field-number", "0")
        click("history-correction-field-number-missing")
        assertTrue(
            controller.state.value.draft!!
                .values
                .getValue(ActivitySnapshotFieldId("number"))
                .missing,
        )
        click("history-correction-field-number-missing")
        click("history-correction-field-text-missing")
        input("history-correction-field-text", "")
        click("history-correction-option-second")
        compose.onNodeWithTag("history-correction-option-second").assertIsSelected()
        compose.onNodeWithTag("history-correction-option-first").assertIsNotSelected()
        assertEquals(
            "0",
            controller.state.value.draft!!
                .values
                .getValue(ActivitySnapshotFieldId("number"))
                .numberText,
        )
        assertFalse(
            controller.state.value.draft!!
                .values
                .getValue(ActivitySnapshotFieldId("text"))
                .missing,
        )
        val input = compose.onNodeWithTag("history-correction-field-text")
        input.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(input)
        compose.runOnIdle { controller.dispatch(ActivityHistoryMutationAction.ProceedOverlap) }
        input.assertIsFocused()
        click("history-correction-cancel")
        assertNull(controller.state.value.draft)
        compose.onNodeWithTag("history-correct").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun originalOverlapOffsetsRemainSelectedUntilEachEndpointIsEdited() {
        val original =
            detail().let {
                it.copy(
                    root =
                        it.root.copy(
                            startedAt = Instant.parse("2026-10-25T00:10:00.123Z"),
                            completedAt = Instant.parse("2026-10-25T01:40:00.456Z"),
                            primaryLocalDate = LocalDate.parse("2026-10-25"),
                            activeDuration = Duration.ofMinutes(90).plusMillis(333),
                        ),
                    updatedAt = NOW.minusSeconds(1),
                )
            }
        val controller = controller(original)
        show(controller)
        click("history-correct")
        compose.onNodeWithTag("history-correction-started-offset-0").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("history-correction-completed-offset-1").performScrollTo().assertIsSelected()
        input("history-correction-started", "2026-10-25 02:20:00.123")
        assertNull(
            controller.state.value.draft!!
                .startedOffset,
        )
        assertEquals(
            java.time.ZoneOffset.ofHours(1),
            controller.state.value.draft!!
                .completedOffset,
        )
        click("history-correction-save")
        assertEquals(ActivityHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME, controller.state.value.issue)
        click("history-correction-started-offset-1")
        input("history-correction-completed", "2026-03-29 02:30")
        click("history-correction-save")
        assertEquals(ActivityHistoryMutationIssue.NONEXISTENT_LOCAL_TIME, controller.state.value.issue)
        assertEquals(ActivityCorrectionEndpoint.COMPLETED, controller.state.value.issueEndpoint)
        input("history-correction-completed", "2026-12-02 12:00")
        click("history-correction-save")
        assertEquals(ActivityHistoryMutationIssue.FUTURE_COMPLETION, controller.state.value.issue)
        compose
            .onNodeWithTag("history-correction-completed")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        input("history-correction-completed", "2026-10-25 01:00")
        click("history-correction-save")
        assertEquals(ActivityHistoryMutationIssue.REVERSED_INTERVAL, controller.state.value.issue)
        compose
            .onNodeWithTag("history-correction-started")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        input("history-correction-started", "2026-02-30 10:00")
        click("history-correction-save")
        assertEquals(ActivityHistoryMutationIssue.INVALID_DATE_TIME, controller.state.value.issue)
        assertEquals(0L, controller.state.value.refreshGeneration)
    }

    @Test
    fun hiddenDisposedAndReplacedRoutesRevokeRetainedNativeInputAndSaveActions() {
        val first = controller()
        val second =
            controller(detail().let { it.copy(root = it.root.copy(executionId = ActivityExecutionId("second"))) })
        val selected = mutableStateOf(first)
        val visible = mutableStateOf(true)
        compose.setContent {
            LifeTracingTheme {
                val current = selected.value
                ActivityHistoryDetailRoute(ActivityHistoryMutationRouteSession(current.executionId, current), {
                }, {}, {}, visible.value, { visible.value })
            }
        }
        click("history-correct")
        val edit =
            compose
                .onNodeWithTag(
                    "history-correction-comment",
                ).fetchSemanticsNode()
                .config[SemanticsActions.SetText]
                .action!!
        val save =
            compose
                .onNodeWithTag(
                    "history-correction-save",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("history-correction-comment").assertIsNotEnabled()
        compose.runOnIdle {
            edit(AnnotatedString("obsolete"))
            save()
            selected.value = second
            visible.value = true
        }
        click("history-correct")
        val draft = second.state.value.draft
        compose.runOnIdle {
            edit(AnnotatedString("wrong target"))
            save()
        }
        assertSame(draft, second.state.value.draft)
        assertEquals(0L, second.state.value.refreshGeneration)
    }

    @Test
    fun overlapCancelRevokesApprovalAndDuplicateProceedRunsOnlyOneWriter() {
        val writes = AtomicInteger()
        val controller = controller(overlap = true, correct = { writes.incrementAndGet() })
        show(controller)
        click("history-correct")
        click("history-correction-save")
        compose.waitUntil(5_000) { controller.state.value.overlapWarning }
        val oldProceed =
            compose
                .onNodeWithTag(
                    "history-overlap-proceed",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        click("history-overlap-cancel")
        assertEquals(0, writes.get())
        input("history-correction-completed", "2026-01-15 12:30")
        click("history-correction-save")
        compose.waitUntil(5_000) { controller.state.value.overlapWarning }
        compose.runOnIdle { oldProceed() }
        assertEquals(0, writes.get())
        val proceed =
            compose
                .onNodeWithTag(
                    "history-overlap-proceed",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle {
            proceed()
            proceed()
        }
        compose.waitUntil(5_000) { controller.state.value.refreshGeneration == 1L }
        assertEquals(1, writes.get())
    }

    @Test
    fun committedReadFailureExposesReadOnlyRetryAndPendingBackKeepsTheSession() {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val controller =
            controller(read = {
                if (reads.incrementAndGet() ==
                    2
                ) {
                    error("reload")
                } else {
                    detail()
                }
            }, correct = {
                gate.await()
                writes.incrementAndGet()
            })
        var backs = 0
        compose.setContent {
            LifeTracingTheme {
                ActivityHistoryDetailRoute(
                    ActivityHistoryMutationRouteSession(controller.executionId, controller),
                    { backs++ },
                    {},
                    {},
                )
            }
        }
        click("history-correct")
        click("history-correction-save")
        compose.waitUntil(5_000) { controller.state.value.isMutating }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backs)
        gate.complete(Unit)
        compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Failure }
        compose.onNodeWithText(compose.activity.getString(R.string.history_correction_reload_failure)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        click("history-retry")
        compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Content }
        assertEquals(1, writes.get())
        assertEquals(3, reads.get())
        assertEquals(1L, controller.state.value.refreshGeneration)
    }

    @Test
    fun staleDeletedTargetRequiresCanonicalRecoveryAndHasNoCorrectionControls() {
        var stale = false
        val controller =
            controller(read = { if (stale) null else detail() }, correct = {
                stale = true
                throw ConcurrentModificationException()
            })
        show(controller)
        click("history-correct")
        click("history-correction-save")
        compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Unavailable }
        assertNull(controller.state.value.draft)
        assertEquals(ActivityHistoryMutationIssue.STALE, controller.state.value.issue)
        compose.onNodeWithTag("history-correct").assertDoesNotExist()
        compose.onNodeWithTag("history-correction-save").assertDoesNotExist()
    }

    @Test
    fun localizedScaledNoLiveEditorKeepsNativeTargetsReachableWithoutStartOrWrites() {
        val language = mutableStateOf("en")
        val scale = mutableStateOf(0)
        val controller =
            controller(
                detail().let {
                    it.copy(
                        root =
                            it.root.copy(
                                startedAt = null,
                                activeDuration = null,
                                timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                            ),
                    )
                },
            )
        compose.setContent {
            val configuration =
                Configuration(
                    LocalConfiguration.current,
                ).apply { setLocale(Locale.forLanguageTag(language.value)) }
            val density = LocalDensity.current
            val font = if (scale.value == 0) 1f else 2f
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides LocalContext.current.createConfigurationContext(configuration),
                LocalDensity provides Density(density.density, font),
            ) {
                LifeTracingAppearance(
                    AppearancePreferences(
                        interfaceScalePercent =
                            if (scale.value ==
                                0
                            ) {
                                90
                            } else {
                                120
                            },
                        textScalePercent = if (scale.value == 0) 90 else 130,
                    ),
                ) {
                    ActivityHistoryDetailRoute(
                        ActivityHistoryMutationRouteSession(controller.executionId, controller),
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        click("history-correct")
        keyboard.enter()
        for (locale in listOf("en", "ru")) {
            for (size in 0..1) {
                compose.runOnIdle {
                    language.value = locale
                    scale.value = size
                }
                compose.onNodeWithTag("history-correction-started").assertDoesNotExist()
                for (tag in listOf(
                    "history-correction-completed",
                    "history-correction-comment",
                    "history-correction-field-number",
                    "history-correction-option-second",
                    "history-correction-save",
                    "history-correction-cancel",
                )) {
                    val target = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    val bounds = target.fetchSemanticsNode().touchBoundsInRoot
                    assertTrue("$locale size=$size $tag $bounds", bounds.height >= 48 * compose.density.density - 1)
                    target.performSemanticsAction(SemanticsActions.RequestFocus)
                    keyboard.awaitFocus(target)
                }
            }
        }
        assertEquals(0L, controller.state.value.refreshGeneration)
    }

    @Test
    fun resumedRouteReenablesControlsAndRevokesCallbacksCapturedBeforePause() {
        class RouteOwner : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        val owner =
            compose.runOnIdle {
                RouteOwner().also {
                    it.registry.currentState =
                        androidx.lifecycle.Lifecycle.State.RESUMED
                }
            }
        val writes = AtomicInteger()
        val controller = controller(correct = { writes.incrementAndGet() })
        val session = ActivityHistoryMutationRouteSession(controller.executionId, controller)
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                LifeTracingTheme { ActivityHistoryDetailRoute(session, {}, {}, {}) }
            }
        }
        click("history-correct")
        val save =
            compose
                .onNodeWithTag(
                    "history-correction-save",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle {
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            save()
        }
        compose.onNodeWithTag("history-correction-save").assertIsNotEnabled()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.runOnIdle { save() }
        assertEquals(0, writes.get())
        click("history-correction-save")
        compose.waitUntil(5_000) { controller.state.value.refreshGeneration == 1L }
        assertEquals(1, writes.get())
    }

    @Test
    fun restoringMissingCategoryRequiresAnOwnedChoiceAndAssociatesItsNativeError() {
        val initial =
            detail().let { detail ->
                detail.copy(
                    fields =
                        detail.fields.map {
                            if (it.type ==
                                CustomFieldType.CATEGORY
                            ) {
                                it.copy(actualValue = ActivityHistoryActualValue.Missing)
                            } else {
                                it
                            }
                        },
                )
            }
        val writes = mutableListOf<ActivityHistoryCorrection>()
        val controller = controller(initial, correct = { writes += it })
        show(controller)
        click("history-correct")
        click("history-correction-field-category-missing")
        click("history-correction-save")
        assertEquals(ActivityHistoryMutationIssue.INVALID_CATEGORY, controller.state.value.issue)
        assertEquals(ActivitySnapshotFieldId("category"), controller.state.value.issueFieldId)
        compose
            .onNodeWithTag(
                "history-correction-field-category-group",
            ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertTrue(writes.isEmpty())
        click("history-correction-option-second")
        compose.onNodeWithTag("history-correction-option-second").assertIsSelected()
        click("history-correction-save")
        compose.waitUntil(5_000) { controller.state.value.refreshGeneration == 1L }
        val actual =
            writes
                .single()
                .values
                .filterIsInstance<com.alexandr5476.lifetracing.domain.CategoryExecutionValue>()
                .single()
        assertEquals(ActivitySnapshotFieldId("category"), actual.snapshotFieldId)
        assertEquals(ActivitySnapshotCategoryOptionId("second"), actual.optionId)
    }

    @Test
    fun pendingSessionFirstCompositionRestoresNativeBackFocusAfterCanonicalReload() {
        val release = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val controller =
            controller(correct = {
                release.await()
                writes.incrementAndGet()
            })
        compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Content }
        val showRoute = mutableStateOf(false)
        val session = ActivityHistoryMutationRouteSession(controller.executionId, controller)
        compose.setContent {
            LifeTracingTheme {
                if (showRoute.value) {
                    ActivityHistoryDetailRoute(session, {}, {}, {})
                } else {
                    LifeTracingSecondaryButton(onClick = {}) { Text("Keyboard setup") }
                }
            }
        }
        // Admit keyboard mode before the route first appears with every action disabled.
        keyboard.enter()
        compose.runOnIdle {
            controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            controller.dispatch(ActivityHistoryMutationAction.Save)
            showRoute.value = true
        }
        assertTrue(controller.state.value.isMutating)
        val back = compose.onNodeWithText(compose.activity.getString(R.string.history_back))
        back.assertIsNotEnabled()
        release.complete(Unit)
        compose.waitUntil(5_000) {
            controller.state.value.refreshGeneration == 1L && controller.state.value.load is HistoryDetailLoad.Content
        }
        keyboard.awaitFocus(back)
        assertEquals(1, writes.get())
    }

    private fun controller(
        initial: ActivityHistoryDetail = detail(),
        read: suspend () -> ActivityHistoryDetail? = { initial },
        overlap: Boolean = false,
        correct: suspend (ActivityHistoryCorrection) -> Unit = {},
    ) = ActivityHistoryMutationController(scope, initial.root.executionId, {
        read()
    }, { _, proposal, _ ->
        correct(
            proposal,
        )
    }, { _, _, _ -> error("No deletion") }, { NOW }, { _, _, _ -> overlap }).also {
        controllers +=
            it
    }

    private fun show(controller: ActivityHistoryMutationController) {
        val session = ActivityHistoryMutationRouteSession(controller.executionId, controller)
        compose.setContent { LifeTracingTheme { ActivityHistoryDetailRoute(session, {}, {}, {}) } }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun input(
        tag: String,
        value: String,
    ) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString(value)))
        }
        compose.waitForIdle()
    }

    private fun detail(): ActivityHistoryDetail {
        val zone = ZoneId.of("Europe/Berlin")
        val first = ActivitySnapshotCategoryOptionId("first")
        val second = ActivitySnapshotCategoryOptionId("second")
        return ActivityHistoryDetail(
            CompletedActivityHistoryRoot(
                ActivityExecutionId("exact"),
                ActivitySnapshotId("frozen"),
                LocalDate.parse("2026-01-15"),
                Instant.parse("2026-01-15T11:00:00Z"),
                Instant.parse("2026-01-15T10:00:00Z"),
                Duration.ofHours(1),
                null,
                "Duplicate",
                "Original",
                TimeTrackingMode.STOPWATCH,
                null,
                zone,
                null,
            ),
            Instant.parse("2026-01-16T00:00:00Z"),
            zone,
            ActivityTemplateSettings(),
            listOf(
                ActivityHistoryField(
                    ActivitySnapshotFieldId("number"),
                    "Duplicate",
                    CustomFieldType.NUMBER,
                    "kg",
                    1,
                    false,
                    ActivityHistoryConfiguredValue.Number(12555),
                    ActivityHistoryActualValue.Number(0),
                    emptyList(),
                ),
                ActivityHistoryField(
                    ActivitySnapshotFieldId("category"),
                    "Duplicate",
                    CustomFieldType.CATEGORY,
                    null,
                    null,
                    false,
                    ActivityHistoryConfiguredValue.Category(first),
                    ActivityHistoryActualValue.Category(first, "Same"),
                    listOf(ActivityHistoryCategoryOption(first, "Same"), ActivityHistoryCategoryOption(second, "Same")),
                ),
                ActivityHistoryField(
                    ActivitySnapshotFieldId("text"),
                    "Duplicate",
                    CustomFieldType.TEXT,
                    null,
                    null,
                    false,
                    ActivityHistoryConfiguredValue.Text("Configured"),
                    ActivityHistoryActualValue.Missing,
                    emptyList(),
                ),
            ),
        )
    }

    private companion object {
        val NOW = Instant.parse("2026-12-01T00:00:00Z")
    }
}
