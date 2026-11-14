package com.alexandr5476.lifetracing.history

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ManualActivityEntryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val keyboard = KeyboardInputFixture(compose)

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun setTextReportsAdmissionInBothTreesAcrossStaleHiddenPendingAndDisposedInputs() {
        val source = fieldTemplate().copy(timeTrackingMode = TimeTrackingMode.STOPWATCH)
        var state by androidx.compose.runtime.mutableStateOf(
            ManualActivityEntryState(
                selected = ManualEntryLoad.Content(source),
                values = source.fields.associate { it.id to ManualEntryFieldDraft(it.type) },
            ),
        )
        var visible by androidx.compose.runtime.mutableStateOf(true)
        var attached by androidx.compose.runtime.mutableStateOf(true)
        var owns = true
        val actions = mutableListOf<ManualActivityEntryAction>()
        compose.setContent {
            LifeTracingTheme {
                if (attached) {
                    ManualActivityEntryScreen(state, { action ->
                        actions += action
                        state =
                            when (action) {
                                is ManualActivityEntryAction.EditStarted -> state.copy(startedText = action.text)
                                is ManualActivityEntryAction.EditCompleted -> state.copy(completedText = action.text)
                                is ManualActivityEntryAction.EditNumber ->
                                    state.copy(
                                        values =
                                            state.values +
                                                (
                                                    action.id to
                                                        state.values.getValue(action.id).copy(numberText = action.text)
                                                ),
                                    )
                                is ManualActivityEntryAction.EditText ->
                                    state.copy(
                                        values =
                                            state.values +
                                                (
                                                    action.id to
                                                        state.values
                                                            .getValue(
                                                                action.id,
                                                            ).copy(text = action.text)
                                                ),
                                    )
                                else -> error("Unexpected text action")
                            }.copy(draftVersion = state.draftVersion + 1)
                    }, {}, visible, { state }, { owns })
                }
            }
        }
        val tags =
            listOf(
                "manual-history-started",
                "manual-history-completed",
                "manual-history-field-number-actual",
                "manual-history-field-text-actual",
            )
        for (tag in tags) {
            for (unmerged in listOf(false, true)) {
                val node = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).performScrollTo()
                val input = node.fetchSemanticsNode().config[SemanticsActions.SetText].action!!
                compose.runOnIdle {
                    assertTrue(input(AnnotatedString("1")))
                    assertTrue(input(AnnotatedString("12")))
                }
                val stale = node.fetchSemanticsNode().config[SemanticsActions.SetText].action!!
                compose.runOnIdle {
                    state = state.copy(draftVersion = state.draftVersion + 1)
                    assertFalse(stale(AnnotatedString("stale")))
                }
                val outgoing = node.fetchSemanticsNode().config[SemanticsActions.SetText].action!!
                compose.runOnIdle {
                    owns = false
                    assertFalse(outgoing(AnnotatedString("hidden")))
                    owns = true
                    assertFalse(outgoing(AnnotatedString("returned")))
                }
                node.performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue(it(AnnotatedString("current")))
                }
            }
        }
        compose.runOnIdle {
            assertEquals("current", state.startedText)
            assertEquals("current", state.completedText)
            assertEquals("current", state.values.getValue(ActivityTemplateFieldId("number")).numberText)
            assertEquals("current", state.values.getValue(ActivityTemplateFieldId("text")).text)
            state =
                state.copy(
                    command =
                        ManualEntryCommand.Invalid(
                            ManualEntryIssue.INVALID_NUMBER,
                            ActivityTemplateFieldId("number"),
                        ),
                )
        }
        for (unmerged in listOf(false, true)) {
            compose.onNodeWithTag("manual-history-field-number-actual", useUnmergedTree = unmerged).assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Error),
            )
            compose.onNodeWithTag("manual-history-field-text-actual", useUnmergedTree = unmerged).assert(
                SemanticsMatcher.keyNotDefined(SemanticsProperties.Error),
            )
        }
        keyboard.enter()
        val started = compose.onNodeWithTag("manual-history-started").performScrollTo()
        started.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(started)
        started.performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("manual-history-completed").assertIsFocused()
        for (pending in listOf(false, true)) {
            compose.runOnIdle {
                visible = pending
                state = state.copy(command = if (pending) ManualEntryCommand.Committing else ManualEntryCommand.Idle)
            }
            for (tag in tags) {
                for (unmerged in listOf(false, true)) {
                    compose
                        .onNodeWithTag(tag, useUnmergedTree = unmerged)
                        .assertIsNotEnabled()
                        .performSemanticsAction(SemanticsActions.SetText) {
                            assertFalse(it(AnnotatedString("disabled")))
                        }
                }
            }
        }
        compose.runOnIdle {
            visible = true
            state = state.copy(command = ManualEntryCommand.Idle)
        }
        val retained =
            compose
                .onNodeWithTag(
                    tags.first(),
                ).fetchSemanticsNode()
                .config[SemanticsActions.SetText]
                .action!!
        compose.runOnIdle { attached = false }
        val count = actions.size
        compose.runOnIdle {
            assertFalse(retained(AnnotatedString("disposed")))
            assertEquals(count, actions.size)
        }
    }

    @Test
    fun ambiguityChoicesShowBothOccurrencesAndConcreteOffsets() {
        val ambiguity =
            ManualTimeAmbiguity(
                LocalDateTime.parse("2026-10-25T02:30"),
                ZoneId.of("Europe/Berlin"),
                listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)),
            )
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.STOPWATCH)),
                startedText = "2026-10-25 02:30",
                completedText = "2026-10-25 03:30",
                startedAmbiguity = ambiguity,
                startedIssue = ManualEntryIssue.AMBIGUOUS_LOCAL_TIME,
                command = ManualEntryCommand.Invalid(ManualEntryIssue.AMBIGUOUS_LOCAL_TIME),
            ),
        )

        compose.onNodeWithText("First occurrence (UTC+02:00)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Second occurrence (UTC+01:00)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manual-history-started-offset-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manual-history-started-offset-1").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noLiveHasNoStartInput() {
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.NO_LIVE_TRACKING)),
                completedText = "2026-09-16 10:00",
            ),
        )
        compose.onAllNodesWithText("Started (YYYY-MM-DD HH:MM)").assertCountEquals(0)
        compose.onNodeWithText("Completed (YYYY-MM-DD HH:MM)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun timerTargetIsHumanReadable() {
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.TIMER)),
                startedText = "2026-09-16 09:00",
                completedText = "2026-09-16 10:00",
            ),
        )
        compose.onNodeWithText("Configured timer target: 01:30").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun expectedErrorsAndMissingPresentationHaveDistinctEnglishAndRussianResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val english = context.localized(Locale.ENGLISH)
        val russian = context.localized(Locale.forLanguageTag("ru"))
        val ids =
            listOf(
                R.string.manual_history_invalid_datetime,
                R.string.manual_history_nonexistent_time,
                R.string.manual_history_ambiguous_time,
                R.string.manual_history_future_completion,
                R.string.manual_history_reversed_interval,
                R.string.manual_history_invalid_number,
                R.string.manual_history_invalid_category,
                R.string.manual_history_template_unavailable,
                R.string.manual_history_template_stale,
                R.string.manual_history_catalog_failure,
                R.string.manual_history_template_failure,
                R.string.manual_history_save_failure,
                R.string.manual_history_missing_value,
                R.string.manual_history_no_selection,
                R.string.manual_history_catalog_empty,
                R.string.manual_history_catalog_item,
                R.string.manual_history_more_pages,
                R.string.manual_history_catalog_end,
                R.string.manual_history_selected,
                R.string.manual_history_zone,
                R.string.manual_history_field_context,
                R.string.manual_history_option_context,
                R.string.manual_history_actual_context,
                R.string.manual_history_present_value,
                R.string.manual_history_present_empty,
                R.string.manual_history_number_format,
                R.string.manual_history_committed,
                R.string.manual_history_page,
                R.string.manual_history_loading_replacement,
                R.string.manual_history_retry_catalog,
                R.string.manual_history_retry_source,
                R.string.manual_history_retry_save,
                R.string.manual_history_selected_source,
            )
        ids.forEach { id -> assertNotEquals(english.getString(id), russian.getString(id)) }
    }

    @Test
    fun actualFieldInteractionsPreserveZeroEmptyAndStableCategoryIdentityAcrossMissing() {
        showInteractive()

        compose.onAllNodesWithText("Missing").assertCountEquals(0)
        compose.onNodeWithTag("manual-history-field-number-actual").assertTextContains("0")
        compose.onNodeWithTag("manual-history-field-number-missing").performScrollTo().performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(1)
        compose.onNodeWithTag("manual-history-field-number-missing").performScrollTo().performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(0)
        compose.onNodeWithTag("manual-history-field-number-actual").assertTextContains("0")

        compose.onNodeWithTag("manual-history-field-text-missing").performScrollTo().performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(1)
        compose.onNodeWithTag("manual-history-field-text-missing").performScrollTo().performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(0)
        compose.onNodeWithTag("manual-history-field-text-actual").performScrollTo().performTextInput("restored")
        compose.onNodeWithTag("manual-history-field-text-actual").assertTextContains("restored")

        compose.onNodeWithTag("manual-history-option-option-b").performScrollTo().performClick()
        compose.onNodeWithTag("manual-history-option-option-b").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("manual-history-field-category-missing").performScrollTo().performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(1)
        compose.onNodeWithTag("manual-history-field-category-missing").performScrollTo().performClick()
        compose.onNodeWithTag("manual-history-option-option-b").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Archived").assertDoesNotExist()
    }

    @Test
    fun gapAndStaleRecoveryRenderTypedLocalizedActions() {
        var dispatched: ManualActivityEntryAction? = null
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.STOPWATCH)),
                startedText = "2026-03-29 02:30",
                completedText = "2026-03-29 03:30",
                startedIssue = ManualEntryIssue.NONEXISTENT_LOCAL_TIME,
                command = ManualEntryCommand.Failure(ManualEntryIssue.TEMPLATE_STALE),
            ),
        ) { dispatched = it }

        compose
            .onNodeWithText(
                "This local time does not exist because the clock moves forward.",
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Review current activity").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ManualActivityEntryAction.ReviewStaleTemplate, dispatched) }
        compose
            .onNodeWithText("The selected activity changed. Select it again to review the current configuration.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun catalogCallbacksLoseOwnershipOnReplacementAndDisposal() {
        val source = template(TimeTrackingMode.NO_LIVE_TRACKING)

        fun row(id: String) =
            ReusableActivityCatalogItem(
                ActivityTemplateId(id),
                "Duplicate",
                TimeTrackingMode.NO_LIVE_TRACKING,
                null,
                null,
                null,
                null,
                null,
            )
        val first = row(source.id.value)
        val second = row("second")
        var state by androidx.compose.runtime.mutableStateOf(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(listOf(first)),
                nextCatalogCursor = first,
                selected = ManualEntryLoad.Content(source),
            ),
        )
        var visible by androidx.compose.runtime.mutableStateOf(true)
        val actions = mutableListOf<ManualActivityEntryAction>()
        compose.setContent {
            LifeTracingTheme {
                if (visible) ManualActivityEntryScreen(state, { actions += it }, {}, readCurrent = { state })
            }
        }
        compose.onNodeWithTag("manual-history-activity-${first.id.value}").assertIsSelected()
        val oldSelect =
            compose
                .onNodeWithTag("manual-history-activity-${first.id.value}")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val oldMore =
            compose
                .onNodeWithTag("manual-history-load-more")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle {
            state = state.copy(catalog = ManualEntryLoad.Content(listOf(second)), nextCatalogCursor = null)
            oldSelect()
            oldMore()
        }
        compose.runOnIdle { assertEquals(emptyList<ManualActivityEntryAction>(), actions) }
        val current = compose.onNodeWithTag("manual-history-activity-${second.id.value}")
        val outgoing = current.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        current.performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ManualActivityEntryAction.Select(second.id)), actions) }
        compose.runOnIdle { visible = false }
        compose.runOnIdle {
            outgoing()
            oldSelect()
            oldMore()
        }
        compose.runOnIdle { assertEquals(1, actions.size) }
    }

    @Test
    fun emptyLoadingFailureAndExhaustedCatalogExposeSeparateRecoveryStates() {
        var state by androidx.compose.runtime.mutableStateOf(
            ManualActivityEntryState(catalog = ManualEntryLoad.Loading),
        )
        val actions = mutableListOf<ManualActivityEntryAction>()
        compose.setContent {
            LifeTracingTheme { ManualActivityEntryScreen(state, { actions += it }, {}, readCurrent = { state }) }
        }
        compose
            .onNodeWithText(
                ApplicationProvider.getApplicationContext<Context>().getString(R.string.manual_history_loading),
            ).assertExists()
        compose.runOnIdle { state = state.copy(catalog = ManualEntryLoad.Content(emptyList())) }
        compose.onNodeWithText("No reusable activities are available.").assertExists()
        compose.onNodeWithTag("manual-history-load-more").assertDoesNotExist()
        compose.runOnIdle {
            state =
                state.copy(
                    catalog = ManualEntryLoad.Failure(ManualEntryIssue.CATALOG_READ_FAILURE),
                )
        }
        compose.onNodeWithTag("manual-history-catalog-retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ManualActivityEntryAction.RetryCatalog), actions) }
        val item =
            ReusableActivityCatalogItem(
                ActivityTemplateId("source"),
                "Source",
                TimeTrackingMode.NO_LIVE_TRACKING,
                null,
                null,
                null,
                null,
                null,
            )
        compose.runOnIdle { state = state.copy(catalog = ManualEntryLoad.Content(listOf(item))) }
        compose.onNodeWithText("End of activity catalog.").assertExists()
        compose.onNodeWithTag("manual-history-load-more").assertDoesNotExist()
    }

    @Test
    fun unrelatedCatalogPublicationPreservesNativeInputFocusWithoutDispatchingAnyAction() {
        var state by androidx.compose.runtime.mutableStateOf(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.STOPWATCH)),
            ),
        )
        val actions = mutableListOf<ManualActivityEntryAction>()
        compose.setContent {
            LifeTracingTheme { ManualActivityEntryScreen(state, { actions += it }, {}, readCurrent = { state }) }
        }
        compose.onNodeWithTag("manual-history-completed").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("manual-history-completed").assertIsFocused()
        compose.runOnIdle { state = state.copy(catalog = ManualEntryLoad.Content(emptyList()), catalogPageNumber = 2) }
        compose.onNodeWithTag("manual-history-completed").assertIsFocused()
        compose.runOnIdle { assertEquals(emptyList<ManualActivityEntryAction>(), actions) }
    }

    @Test
    fun repeatedImmediateReadFailureKeepsTheProductionRouteRetryActionable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reads =
            java.util.concurrent.atomic
                .AtomicInteger()
        val controller =
            ManualActivityEntryController(
                scope,
                {
                    reads.incrementAndGet()
                    error("Read failure")
                },
                { error("No source selected") },
                { _, _ -> error("No interval selected") },
                { error("No Save requested") },
                { error("No Save requested") },
            )
        try {
            compose.setContent { LifeTracingTheme { ManualActivityEntryRoute(controller, {}, {}) } }
            repeat(3) { attempt ->
                compose.onNodeWithTag("manual-history-catalog-retry").performScrollTo().performClick()
                compose.runOnIdle { assertEquals(attempt + 2, reads.get()) }
            }
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun replacingControllerWithEqualFailureContentKeepsCurrentRouteActionsAndRevokesOldCallbacks() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstReads =
            java.util.concurrent.atomic
                .AtomicInteger()
        val secondReads =
            java.util.concurrent.atomic
                .AtomicInteger()

        fun failingController(reads: java.util.concurrent.atomic.AtomicInteger) =
            ManualActivityEntryController(
                scope,
                {
                    reads.incrementAndGet()
                    error("Read failure")
                },
                { error("No source selected") },
                { _, _ -> error("No interval selected") },
                { error("No Save requested") },
                { error("No Save requested") },
            )
        val first = failingController(firstReads)
        val second = failingController(secondReads)
        var controller by androidx.compose.runtime.mutableStateOf(first)
        try {
            compose.setContent { LifeTracingTheme { ManualActivityEntryRoute(controller, {}, {}) } }
            val oldRetry =
                compose
                    .onNodeWithTag("manual-history-catalog-retry")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            compose.runOnIdle {
                first.close()
                controller = second
            }
            compose.waitForIdle()
            compose.runOnIdle { oldRetry() }
            compose.onNodeWithTag("manual-history-catalog-retry").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(1, firstReads.get())
                assertEquals(2, secondReads.get())
            }
        } finally {
            first.close()
            second.close()
            scope.cancel()
        }
    }

    @Test
    fun hiddenRouteExposesDisabledNativeTargetsForEditingAndOverlapCommands() {
        val source = template(TimeTrackingMode.STOPWATCH)
        val row =
            ReusableActivityCatalogItem(source.id, source.name, source.timeTrackingMode, null, null, null, null, null)
        val start = Instant.parse("2026-09-16T09:00:00Z")
        val end = start.plusSeconds(60)
        var state by androidx.compose.runtime.mutableStateOf(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(listOf(row)),
                selected = ManualEntryLoad.Content(source),
                startedText = "2026-09-16 09:00",
                completedText = "2026-09-16 09:01",
                entryZoneId = ZoneOffset.UTC,
                command = ManualEntryCommand.Failure(ManualEntryIssue.SAVE_FAILURE),
            ),
        )
        var calls = 0
        compose.setContent {
            LifeTracingTheme { ManualActivityEntryScreen(state, { calls++ }, { calls++ }, isRouteVisible = false) }
        }
        for (tag in listOf(
            "manual-history-back",
            "manual-history-activity-${source.id.value}",
            "manual-history-started",
            "manual-history-completed",
            "manual-history-save",
            "manual-history-command-retry",
        )) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        compose.onNodeWithTag("manual-history-command-retry").assertContentDescriptionEquals(
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.manual_history_retry_save),
        )
        compose.runOnIdle {
            state =
                state.copy(
                    command =
                        ManualEntryCommand.Overlap(
                            ManualEntryProposal(
                                ActivityEntrySource.Template(source.id),
                                source.revision,
                                start,
                                end,
                                end.plusSeconds(1),
                                ZoneOffset.UTC,
                                emptyList(),
                            ),
                        ),
                )
        }
        compose.onNodeWithTag("manual-history-overlap-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("manual-history-overlap-proceed").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test
    fun lifecycleResumeReenablesNativeControlsAndRevokesCallbacksFromBeforePause() {
        class EntryOwner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val owner = compose.runOnIdle { EntryOwner().also { it.registry.currentState = Lifecycle.State.STARTED } }
        val state =
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.NO_LIVE_TRACKING)),
                completedText = "2026-09-16 10:00",
            )
        var calls = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LifeTracingTheme { ManualActivityEntryScreen(state, { calls++ }, { calls++ }) }
            }
        }
        compose.onNodeWithTag("manual-history-save").assertIsNotEnabled()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("manual-history-save").assertIsEnabled()
        val oldSave =
            compose
                .onNodeWithTag("manual-history-save")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            oldSave()
        }
        compose.onNodeWithTag("manual-history-save").assertIsNotEnabled()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("manual-history-save").assertIsEnabled()
        compose.runOnIdle {
            oldSave()
            assertEquals(0, calls)
        }
        compose.onNodeWithTag("manual-history-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    private fun show(
        state: ManualActivityEntryState,
        onAction: (ManualActivityEntryAction) -> Unit = {},
    ) {
        compose.setContent {
            LifeTracingTheme {
                ManualActivityEntryScreen(state, onAction, {})
            }
        }
    }

    private fun showInteractive() {
        compose.setContent {
            LifeTracingTheme {
                var state by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(
                        ManualActivityEntryState(
                            catalog = ManualEntryLoad.Content(emptyList()),
                            selected = ManualEntryLoad.Content(fieldTemplate()),
                            completedText = "2026-09-16 10:00",
                            values =
                                mapOf(
                                    ActivityTemplateFieldId("number") to
                                        ManualEntryFieldDraft(CustomFieldType.NUMBER, numberText = "0"),
                                    ActivityTemplateFieldId("text") to
                                        ManualEntryFieldDraft(CustomFieldType.TEXT, text = ""),
                                    ActivityTemplateFieldId("category") to
                                        ManualEntryFieldDraft(
                                            CustomFieldType.CATEGORY,
                                            selectedOptionId = CategoryOptionId("option-a"),
                                        ),
                                ),
                        ),
                    )
                }
                ManualActivityEntryScreen(
                    state,
                    { action ->
                        val id =
                            when (action) {
                                is ManualActivityEntryAction.SetMissing -> action.id
                                is ManualActivityEntryAction.SetPresent -> action.id
                                is ManualActivityEntryAction.EditText -> action.id
                                is ManualActivityEntryAction.SelectCategory -> action.id
                                else -> null
                            }
                        if (id != null) {
                            val draft = state.values.getValue(id)
                            val changed =
                                when (action) {
                                    is ManualActivityEntryAction.SetMissing -> draft.copy(missing = true)
                                    is ManualActivityEntryAction.SetPresent -> draft.copy(missing = false)
                                    is ManualActivityEntryAction.EditText ->
                                        draft.copy(
                                            text = action.text,
                                            missing = false,
                                        )
                                    is ManualActivityEntryAction.SelectCategory ->
                                        draft.copy(selectedOptionId = action.optionId, missing = false)
                                    else -> draft
                                }
                            state = state.copy(values = state.values + (id to changed))
                        }
                    },
                    {},
                )
            }
        }
    }

    private fun template(mode: TimeTrackingMode): ActivityTemplate =
        ActivityTemplate(
            ActivityTemplateId("template"),
            "Template",
            null,
            mode,
            if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(90) else null,
            StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun fieldTemplate(): ActivityTemplate =
        template(TimeTrackingMode.NO_LIVE_TRACKING).copy(
            fields =
                listOf(
                    ActivityTemplateField(
                        ActivityTemplateFieldId("number"),
                        0,
                        "Number",
                        CustomFieldType.NUMBER,
                        defaultNumberScaled = 0,
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                    ActivityTemplateField(
                        ActivityTemplateFieldId("text"),
                        1,
                        "Text",
                        CustomFieldType.TEXT,
                        defaultText = "",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                    ActivityTemplateField(
                        ActivityTemplateFieldId("category"),
                        2,
                        "Category",
                        CustomFieldType.CATEGORY,
                        defaultCategoryOptionId = CategoryOptionId("option-a"),
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                        categoryOptions =
                            listOf(
                                CategoryOption(CategoryOptionId("option-a"), 0, "A"),
                                CategoryOption(CategoryOptionId("option-b"), 1, "B"),
                                CategoryOption(CategoryOptionId("archived"), 2, "Archived", true),
                            ),
                    ),
                ),
        )

    private fun Context.localized(locale: Locale): Context =
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
