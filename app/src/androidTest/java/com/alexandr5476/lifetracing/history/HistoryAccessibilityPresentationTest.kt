@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.history

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.PrimaryWorkflowAppearance
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.assertTextFullyVisible
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class HistoryAccessibilityPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun detailDistinguishesMissingPresentEmptyAndNonemptyTextInEnglishAndRussian() {
        val language = mutableStateOf("en")
        val values = listOf<String?>(null, "", "Frozen text")
        val detail =
            ActivityHistoryDetail(
                activity("text-states"),
                END,
                ZONE,
                ActivityTemplateSettings(),
                values.mapIndexed { index, value ->
                    ActivityHistoryField(
                        ActivitySnapshotFieldId("text-$index"),
                        "Text $index",
                        CustomFieldType.TEXT,
                        null,
                        null,
                        false,
                        value?.let {
                            ActivityHistoryConfiguredValue.Text(
                                it,
                            )
                        } ?: ActivityHistoryConfiguredValue.Missing,
                        value?.let { ActivityHistoryActualValue.Text(it) } ?: ActivityHistoryActualValue.Missing,
                        emptyList(),
                    )
                },
            )
        compose.setContent {
            val context = LocalContext.current
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    setLocale(Locale.forLanguageTag(language.value))
                }
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
            ) {
                LifeTracingTheme {
                    androidx.compose.foundation.layout
                        .Column { ActivityDetail(detail) }
                }
            }
        }
        for (locale in listOf("en", "ru")) {
            compose.runOnIdle { language.value = locale }
            val context =
                compose.activity.createConfigurationContext(
                    Configuration(
                        compose.activity.resources.configuration,
                    ).apply { setLocale(Locale.forLanguageTag(locale)) },
                )
            val descriptions =
                values.mapIndexed { index, value ->
                    val presented =
                        when (value) {
                            null -> context.getString(R.string.history_missing_value)
                            "" -> context.getString(R.string.manual_history_present_empty)
                            else -> value
                        }
                    val actual = context.getString(R.string.history_actual_field, "Text $index", presented)
                    val configured = context.getString(R.string.history_configured_value, presented)
                    "$actual. $configured"
                }
            assertEquals(3, descriptions.toSet().size)
            for (description in descriptions) {
                for (unmerged in listOf(false, true)) {
                    compose.onNode(hasContentDescription(description), useUnmergedTree = unmerged).assertExists()
                }
            }
        }
    }

    @Test
    fun duplicateNamesAndTimesKeepOneNativeActionPerExactTypedExecution() {
        val roots = listOf(activity("same"), activity("second"), sequence("same"))
        val state = mutableStateOf(page(roots), referentialEqualityPolicy())
        val opened = mutableListOf<CompletedHistoryRoot>()

        fun open(root: CompletedHistoryRoot) {
            opened += root
            state.value = state.value.copy()
        }
        compose.setContent {
            LifeTracingTheme {
                HistoryScreen(
                    state.value,
                    {},
                    onOpenActivity = ::open,
                    onOpenSequence = ::open,
                    readCurrent = { state.value },
                )
            }
        }
        keyboard.enter()
        for (root in roots) {
            val record = record(root)
            val row = compose.onNode(hasContentDescription(record, substring = true) and hasClickAction())
            for (unmerged in listOf(false, true)) {
                val native =
                    compose
                        .onNode(
                            hasContentDescription(record, substring = true) and hasClickAction(),
                            useUnmergedTree = unmerged,
                        ).fetchSemanticsNode()
                assertEquals(text(R.string.history_open_record, record), native.config[SemanticsActions.OnClick].label)
                assertTrue(native.config[SemanticsProperties.ContentDescription].single().contains("Duplicate"))
                assertTrue(native.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.isEmpty())
            }
            row.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(row)
            row.performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertSame(root, opened.last()) }
        }
        assertEquals(roots, opened)
    }

    @Test
    fun rootDescriptionsKeepEndpointOffsetsDatesStoredGroupingAndMissingVersusZero() {
        val noLive =
            activity("missing").copy(
                startedAt = null,
                activeDuration = null,
                timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                originalUtcOffsetMinutes = 60,
            )
        val zero =
            activity("zero").copy(
                activeDuration = Duration.ZERO,
                timeTrackingMode = TimeTrackingMode.TIMER,
                timerTarget = Duration.ofMinutes(5),
            )
        val midnight =
            activity("midnight").copy(
                startedAt = Instant.parse("2026-10-24T21:50:00Z"),
                completedAt = Instant.parse("2026-10-24T22:30:00Z"),
            )
        val ended = sequence("early").copy(status = SequenceExecutionStatus.ENDED_EARLY)
        compose.setContent { LifeTracingTheme { HistoryScreen(page(listOf(noLive, zero, midnight, ended)), {}) } }

        fun description(root: CompletedHistoryRoot): String =
            compose
                .onNode(hasContentDescription(record(root), substring = true))
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .single()
        val absent = description(noLive)
        assertTrue(absent.contains(text(R.string.history_no_live)))
        assertTrue(absent.contains(text(R.string.history_active_duration, text(R.string.history_missing_value))))
        val startLabel = text(R.string.history_timed_range, "START", "END").substringBefore("START")
        assertTrue(!absent.contains(startLabel))
        val timed = description(zero)
        assertTrue(timed.contains("UTC+02:00"))
        assertTrue(timed.contains("UTC+01:00"))
        assertTrue(timed.contains("Europe/Berlin"))
        assertTrue(timed.contains(text(R.string.history_active_duration, "00:00")))
        assertTrue(timed.contains(text(R.string.history_timer_target, "05:00")))
        val crossing = description(midnight)
        val dates =
            java.time.format.DateTimeFormatter
                .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(compose.activity.resources.configuration.locales[0])
        assertTrue(crossing.contains(dates.format(LocalDate.of(2026, 10, 24))))
        assertTrue(crossing.contains(dates.format(LocalDate.of(2026, 10, 25))))
        assertTrue(description(ended).contains(text(R.string.history_ended_early)))
        compose.onNodeWithText(dates.format(DATE)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
        )
    }

    @Test
    fun originalZoneRootTextAndExactActionsSurviveCombinedAppearance() {
        val zone = ZoneId.of("America/New_York")
        val first = Instant.parse("2024-11-03T05:30:00Z")
        val second = Instant.parse("2024-11-03T06:30:00Z")
        val beforeMidnight = Instant.parse("2024-11-03T03:50:00Z")
        val originalDate = LocalDate.of(2024, 11, 3)
        val overlap =
            activity("overlap").copy(
                title = "A long standalone activity title from the original snapshot",
                primaryLocalDate = originalDate,
                startedAt = first,
                completedAt = second,
                originalZoneId = zone,
                originalUtcOffsetMinutes = -240,
            )
        val crossing =
            sequence("midnight").copy(
                title = "A long sequence title spanning two original calendar dates",
                primaryLocalDate = originalDate.minusDays(1),
                startedAt = beforeMidnight,
                completedAt = second,
                activeDuration = Duration.ofHours(2).plusMinutes(30),
                wallDuration = Duration.ofHours(2).plusMinutes(40),
                originalZoneId = zone,
                originalUtcOffsetMinutes = -240,
            )
        val noLive =
            overlap.copy(
                executionId = ActivityExecutionId("missing"),
                snapshotId = ActivitySnapshotId("snapshot-missing"),
                title = "One-off note",
                startedAt = null,
                activeDuration = null,
                timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                originalUtcOffsetMinutes = -300,
            )
        val appearance = mutableStateOf(PrimaryWorkflowAppearance.EnglishMinimum)
        val window = HistoryBrowseWindow(originalDate.minusDays(1), originalDate)
        val empty = HistoryPresentationState(window, HistoryRootsLoad.Empty)
        val populated = HistoryPresentationState(window, HistoryRootsLoad.Content(listOf(noLive, overlap, crossing)))
        val publication = mutableStateOf(empty)
        val actions = mutableListOf<HistoryAction>()
        val opened = mutableListOf<CompletedHistoryRoot>()
        compose.setContent {
            appearance.value.Content {
                HistoryScreen(
                    publication.value,
                    actions::add,
                    onOpenActivity = { opened += it },
                    onOpenSequence = { opened += it },
                    readCurrent = { publication.value },
                )
            }
        }
        for (mode in PrimaryWorkflowAppearance.entries) {
            compose.runOnIdle {
                appearance.value = mode
                publication.value = empty
            }
            val resources = mode.resourcesContext(compose.activity)
            val clock =
                DateTimeFormatter
                    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withLocale(resources.resources.configuration.locales[0])
                    .withZone(zone)

            fun at(
                instant: Instant,
                offset: String,
            ) = resources.getString(R.string.history_timestamp_context, clock.format(instant), offset, zone.id)

            fun visible(text: String) {
                compose.onNodeWithText(text, useUnmergedTree = true).assertTextFullyVisible()
            }

            visible(resources.getString(R.string.history_empty))
            compose.runOnIdle { publication.value = populated }
            visible(overlap.title)
            visible(resources.getString(R.string.history_timed_range, at(first, "-04:00"), at(second, "-05:00")))
            visible(crossing.title)
            visible(
                resources.getString(R.string.history_timed_range, at(beforeMidnight, "-04:00"), at(second, "-05:00")),
            )
            visible(resources.getString(R.string.history_completion_time, at(second, "-05:00")))
            visible(
                resources.getString(
                    R.string.history_active_duration,
                    resources.getString(R.string.history_missing_value),
                ),
            )
            compose.onNodeWithTag("history-back").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("history-add-completed-activity").performScrollTo().assertIsEnabled()
        }
        compose.runOnIdle {
            assertTrue(actions.isEmpty())
            assertTrue(opened.isEmpty())
        }
        compose.onNodeWithTag("history-activity-overlap").performScrollTo().performClick()
        compose.onNodeWithTag("history-sequence-midnight").performScrollTo().performClick()
        compose.runOnIdle { publication.value = publication.value.copy(load = HistoryRootsLoad.Failure("offline")) }
        val english = PrimaryWorkflowAppearance.EnglishMinimum.resourcesContext(compose.activity)
        compose.onNodeWithText(english.getString(R.string.history_read_failure)).assertTextFullyVisible()
        compose.onNodeWithTag("history-retry").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(overlap, crossing), opened)
            assertEquals(listOf(HistoryAction.Retry), actions)
        }
    }

    @Test
    fun reorderedRefreshRetainsNativeFocusButRevokesOldActionBeforeRecomposition() {
        val first = activity("first")
        val other = activity("other")
        val state = mutableStateOf(page(listOf(first, other)), referentialEqualityPolicy())
        val opened = mutableListOf<CompletedHistoryRoot>()
        compose.setContent {
            LifeTracingTheme {
                HistoryScreen(state.value, {}, onOpenActivity = opened::add, readCurrent = { state.value })
            }
        }
        keyboard.enter()
        val row = compose.onNode(hasContentDescription(record(first), substring = true))
        row.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(row)
        val nativeId = row.fetchSemanticsNode().id
        val retained = row.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val updated = first.copy(shortComment = "New publication")
        compose.runOnIdle {
            state.value = page(listOf(other, updated))
            retained()
            assertTrue(opened.isEmpty())
        }
        assertEquals(nativeId, row.fetchSemanticsNode().id)
        row.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertSame(updated, opened.single()) }
        compose.runOnIdle { state.value = page(listOf(other)) }
        keyboard.awaitFocus(compose.onNodeWithTag("history-back"))
        compose.runOnIdle { assertEquals(1, opened.size) }
    }

    @Test
    fun removedLoadMoreRecoversThroughLoadingFailureRetryAndEmptyWithoutReplayingActions() {
        val state = mutableStateOf(page(listOf(activity("root"))).copy(canLoadMore = true))
        val actions = mutableListOf<HistoryAction>()
        compose.setContent {
            LifeTracingTheme {
                HistoryScreen(state.value, {
                    actions += it
                    state.value = state.value.copy(load = HistoryRootsLoad.Loading, canLoadMore = false)
                }, readCurrent = { state.value })
            }
        }
        keyboard.enter()
        compose.onNodeWithTag("history-newer").assertIsNotEnabled()
        val more = compose.onNodeWithTag("history-load-more")
        more.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(more)
        more.performKeyInput { pressKey(Key.Enter) }
        keyboard.awaitFocus(compose.onNodeWithTag("history-back"))
        compose.onNodeWithText(text(R.string.history_loading)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        compose.runOnIdle { state.value = state.value.copy(load = HistoryRootsLoad.Failure("read failed")) }
        val retry = compose.onNodeWithTag("history-retry")
        keyboard.awaitFocus(retry)
        retry.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { state.value = state.value.copy(load = HistoryRootsLoad.Empty) }
        keyboard.awaitFocus(compose.onNodeWithTag("history-back"))
        compose.onNodeWithText(text(R.string.history_empty)).assertIsDisplayed()
        assertEquals(listOf(HistoryAction.LoadMore, HistoryAction.Retry), actions)
        compose.onNodeWithTag("history-load-more").assertDoesNotExist()
        compose.runOnIdle {
            state.value = page(listOf(activity("discovery"))).copy(discoveryFailure = "Discovery failed")
        }
        compose.onNodeWithTag("history-retry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.history_read_failure)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test
    fun lifecycleLossRevokesEveryHistoryCallbackWithoutChangingRouteOrPublication() {
        class HistoryOwner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val owner = compose.runOnIdle { HistoryOwner().also { it.registry.currentState = Lifecycle.State.RESUMED } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var reads = 0
        val roots =
            listOf(activity("exact-activity"), sequence("exact-sequence")) +
                (0 until 99).map { activity("other-$it") }
        val controller =
            HistoryController(scope, { query ->
                assertEquals(101, query.limit)
                reads++
                roots
            }, { END }, { ZONE })
        val destinations = mutableListOf<String>()
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LifeTracingTheme {
                        HistoryRoute(
                            controller,
                            onBack = { destinations += "back" },
                            onAddCompletedActivity = { destinations += "manual-session" },
                            onOpenActivity = { destinations += "activity:${it.executionId.value}" },
                            onOpenSequence = { destinations += "sequence:${it.executionId.value}" },
                            ownsRoute = { true },
                        )
                    }
                }
            }
            keyboard.enter()
            val tags =
                listOf(
                    "history-back",
                    "history-older",
                    "history-add-completed-activity",
                    "history-activity-exact-activity",
                    "history-sequence-exact-sequence",
                    "history-load-more",
                )
            val retained =
                tags.map {
                    compose
                        .onNodeWithTag(it)
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                        .action!!
                }
            val publication = compose.runOnIdle { controller.state.value }
            // Activate in the pause UI turn, before a disabled frame can be composed.
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                retained.forEach { it() }
                assertSame(publication, controller.state.value)
                assertEquals(1, reads)
                assertTrue(destinations.isEmpty())
            }
            tags.forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            tags.forEach { compose.onNodeWithTag(it).assertIsEnabled() }
            keyboard.awaitFocus(compose.onNodeWithTag("history-back"))
            compose.runOnIdle {
                retained.forEach { it() }
                assertSame(publication, controller.state.value)
                assertEquals(1, reads)
                assertTrue(destinations.isEmpty())
            }
            // Fresh publications admit all native actions and retain exact typed record identity.
            tags.forEach { tag ->
                compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
            }
            compose.runOnIdle {
                assertEquals(3, reads)
                assertEquals(
                    listOf("back", "manual-session", "activity:exact-activity", "sequence:exact-sequence"),
                    destinations,
                )
            }
            // Pause must revoke even an uninvoked callback when resume precedes recomposition.
            val uninvoked =
                compose
                    .onNodeWithTag("history-add-completed-activity")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                owner.registry.currentState = Lifecycle.State.RESUMED
                uninvoked()
                assertEquals(4, destinations.size)
            }
            compose.onNodeWithTag("history-add-completed-activity").assertIsEnabled()
        } finally {
            controller.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun hiddenAndDisposedHistoryRejectsRetainedActionsAndCannotStealForegroundFocus() {
        val visible = mutableStateOf(true)
        val attached = mutableStateOf(true)
        val state = mutableStateOf(page(listOf(activity("removed"))))
        var ownsRoute = true
        var dispatched = 0
        compose.setContent {
            LifeTracingTheme {
                Box {
                    if (attached.value) {
                        HistoryScreen(
                            state.value,
                            { dispatched++ },
                            onBack = { dispatched++ },
                            onAddCompletedActivity = { dispatched++ },
                            onOpenActivity = { dispatched++ },
                            isRouteVisible = visible.value,
                            readCurrent = { state.value },
                            ownsRoute = { ownsRoute },
                        )
                    }
                    TextButton(onClick = {}) { Text("Foreground") }
                }
            }
        }
        keyboard.enter()
        val old =
            compose
                .onNodeWithTag("history-older")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val back =
            compose
                .onNodeWithTag("history-back")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val row = compose.onNode(hasContentDescription(record(activity("removed")), substring = true))
        row.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {
            ownsRoute = false
            visible.value = false
            old()
            back()
            state.value = state.value.copy(load = HistoryRootsLoad.Failure("hidden"))
        }
        val foreground = compose.onNodeWithText("Foreground")
        foreground.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(foreground)
        compose.runOnIdle { state.value = state.value.copy(load = HistoryRootsLoad.Empty) }
        foreground.assertIsFocused()
        compose.runOnIdle {
            attached.value = false
        }
        compose.runOnIdle {
            ownsRoute = true
            old()
            back()
            assertEquals(0, dispatched)
        }
    }

    @Test
    fun englishRussianScaleExtremesKeepDatesAndNativeTargetsReachable() {
        val language = mutableStateOf("en")
        val scale = mutableStateOf(0)
        val configurations = listOf(Triple(90, 90, 1f), Triple(120, 130, 2f), Triple(90, 130, 2f))
        var actions = 0
        compose.setContent {
            val context = LocalContext.current
            val (interfaceScale, textScale, font) = configurations[scale.value]
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    setLocale(Locale.forLanguageTag(language.value))
                    fontScale = font
                }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, font),
            ) {
                LifeTracingAppearance(
                    AppearancePreferences(
                        interfaceScalePercent = interfaceScale,
                        textScalePercent = textScale,
                    ),
                ) {
                    HistoryScreen(
                        page(listOf(activity("scale"))).copy(
                            canNavigateNewer = true,
                            newestDate = DATE.plusDays(4),
                            canLoadMore = true,
                        ),
                        { actions++ },
                    )
                }
            }
        }
        keyboard.enter()
        for (locale in listOf("en", "ru")) {
            for (index in configurations.indices) {
                compose.runOnIdle {
                    language.value = locale
                    scale.value = index
                }
                for (tag in listOf(
                    "history-back",
                    "history-add-completed-activity",
                    "history-older",
                    "history-newer",
                    "history-activity-scale",
                    "history-load-more",
                )) {
                    val node = compose.onNodeWithTag(tag)
                    node.performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
                    keyboard.awaitFocus(node)
                    val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                    val density = compose.activity.resources.displayMetrics.density
                    val context = "$locale scale=$index $tag bounds=$bounds"
                    assertTrue("$context minimum native width", bounds.width >= 48 * density - 1)
                    assertTrue("$context minimum native height", bounds.height >= 48 * density - 1)
                    compose.onRoot().performKeyInput { pressKey(Key.Tab) }
                }
                val older = compose.onNodeWithTag("history-older").fetchSemanticsNode()
                assertTrue(
                    older.config[SemanticsActions.OnClick].label!!.contains(
                        if (locale ==
                            "ru"
                        ) {
                            "Раньше"
                        } else {
                            "Older"
                        },
                    ),
                )
            }
        }
        assertEquals(0, actions)
    }

    @Test
    fun readOnlyDetailPublishesLoadingRetryUnavailableAndFrozenConfiguredActualValuesWithoutWrites() {
        val root = activity("detail")
        val detail =
            com.alexandr5476.lifetracing.domain.ActivityHistoryDetail(
                root,
                END,
                ZONE,
                com.alexandr5476.lifetracing.domain
                    .ActivityTemplateSettings(),
                listOf(
                    com.alexandr5476.lifetracing.domain.ActivityHistoryField(
                        com.alexandr5476.lifetracing.domain
                            .ActivitySnapshotFieldId("amount"),
                        "Amount",
                        com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                        "kg",
                        1,
                        true,
                        com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
                            .Number(12_500),
                        com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
                            .Number(0),
                        emptyList(),
                    ),
                    com.alexandr5476.lifetracing.domain.ActivityHistoryField(
                        com.alexandr5476.lifetracing.domain
                            .ActivitySnapshotFieldId("missing"),
                        "Notes",
                        com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT,
                        null,
                        null,
                        false,
                        com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
                            .Text("Frozen default"),
                        com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue.Missing,
                        emptyList(),
                    ),
                ),
            )
        val first = kotlinx.coroutines.CompletableDeferred<com.alexandr5476.lifetracing.domain.ActivityHistoryDetail?>()
        val second =
            kotlinx.coroutines
                .CompletableDeferred<com.alexandr5476.lifetracing.domain.ActivityHistoryDetail?>()
        val third = kotlinx.coroutines.CompletableDeferred<com.alexandr5476.lifetracing.domain.ActivityHistoryDetail?>()
        val gates = ArrayDeque(listOf(first, second, third))
        val scope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
            )
        val controller =
            ActivityHistoryMutationController(
                scope,
                root.executionId,
                { gates.removeFirst().await() },
                { _, _, _ -> error("Read-only focus must never correct history") },
                { _, _, _ -> error("Read-only focus must never delete history") },
            )
        val session = ActivityHistoryMutationRouteSession(root.executionId, controller)
        val appearance = mutableStateOf(PrimaryWorkflowAppearance.EnglishMinimum)
        var backs = 0
        try {
            compose.setContent { appearance.value.Content { ActivityHistoryDetailRoute(session, { backs++ }, {}, {}) } }
            keyboard.enter()
            keyboard.awaitFocus(compose.onNodeWithText(text(R.string.history_back)))
            compose.onNodeWithText(text(R.string.history_detail_loading)).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
            first.completeExceptionally(IllegalStateException("Read failed"))
            compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Failure }
            val retry = compose.onNodeWithTag("history-retry")
            retry.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(retry)
            retry.performKeyInput { pressKey(Key.Enter) }
            second.complete(null)
            compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Unavailable }
            compose.onNodeWithText(text(R.string.history_unavailable)).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
            keyboard.awaitFocus(compose.onNodeWithText(text(R.string.history_back)))
            compose.runOnIdle { controller.dispatch(ActivityHistoryMutationAction.Retry) }
            third.complete(detail)
            compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Content }
            compose.onNodeWithText(root.title).assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            val actual = text(R.string.history_actual_field, "Amount", "0 kg")
            val configured = text(R.string.history_configured_value, "12.5 kg")
            compose.onNode(hasContentDescription("$actual. $configured")).performScrollTo().assertIsDisplayed()
            val missing = text(R.string.history_actual_field, "Notes", text(R.string.history_missing_value))
            val default = text(R.string.history_configured_value, "Frozen default")
            compose.onNode(hasContentDescription("$missing. $default")).performScrollTo().assertIsDisplayed()
            val back = compose.onNodeWithText(text(R.string.history_back))
            back.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(back)
            back.performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertEquals(1, backs) }
            for (mode in PrimaryWorkflowAppearance.entries) {
                compose.runOnIdle { appearance.value = mode }
                val resources = mode.resourcesContext(compose.activity)
                val missingValue = resources.getString(R.string.history_missing_value)
                val labels =
                    listOf(
                        resources.getString(R.string.history_field_value, "Amount", "0 kg"),
                        resources.getString(R.string.history_configured_value, "12.5 kg"),
                        resources.getString(R.string.history_field_value, "Notes", missingValue),
                        resources.getString(R.string.history_configured_value, "Frozen default"),
                    )
                labels.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertTextFullyVisible() }
            }
            compose.runOnIdle { assertEquals(1, backs) }
        } finally {
            controller.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun deletionConfirmationNativeFocusLabelsAndTargetsSurviveEnglishRussianScaleExtremes() {
        val language = mutableStateOf("en")
        val scale = mutableStateOf(0)
        val configurations = listOf(Triple(90, 90, 1f), Triple(120, 130, 2f), Triple(90, 130, 2f))
        val root =
            activity(
                "exact-deletion-id",
            ).copy(
                planEntryId =
                    com.alexandr5476.lifetracing.domain
                        .PlanEntryId("fulfilled"),
            )
        val detail =
            com.alexandr5476.lifetracing.domain.ActivityHistoryDetail(
                root,
                END.plusSeconds(1),
                ZONE,
                com.alexandr5476.lifetracing.domain
                    .ActivityTemplateSettings(),
                emptyList(),
            )
        val scope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
            )
        var writes = 0
        val controller =
            ActivityHistoryMutationController(scope, root.executionId, {
                detail
            }, { _, _, _ -> error("Plan correction forbidden") }, { _, _, _ -> writes++ })
        val session = ActivityHistoryMutationRouteSession(root.executionId, controller)
        var localized: android.content.Context = compose.activity
        try {
            compose.setContent {
                val context = LocalContext.current
                val (interfaceScale, textScale, font) = configurations[scale.value]
                val configuration =
                    Configuration(LocalConfiguration.current).apply {
                        setLocale(Locale.forLanguageTag(language.value))
                        fontScale = font
                    }
                val density = LocalDensity.current
                localized = context.createConfigurationContext(configuration)
                CompositionLocalProvider(
                    LocalContext provides localized,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(density.density, font),
                ) {
                    LifeTracingAppearance(
                        AppearancePreferences(interfaceScalePercent = interfaceScale, textScalePercent = textScale),
                    ) {
                        ActivityHistoryDetailRoute(session, {}, {}, {})
                    }
                }
            }
            keyboard.enter()
            compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Content }
            for (locale in listOf("en", "ru")) {
                for (index in configurations.indices) {
                    compose.runOnIdle {
                        language.value = locale
                        scale.value = index
                    }
                    val delete = compose.onNodeWithTag("history-delete")
                    delete.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
                    keyboard.awaitFocus(delete)
                    assertTrue(
                        delete.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().contains(
                            root.executionId.value,
                        ),
                    )
                    compose.onNodeWithTag("history-correct").assertDoesNotExist()
                    delete.performKeyInput { pressKey(Key.Enter) }
                    val cancel = compose.onNodeWithTag("history-delete-cancel")
                    keyboard.awaitFocus(cancel)
                    compose
                        .onNodeWithText(
                            localized.getString(R.string.history_delete_plan_preserved),
                            substring = true,
                        ).performScrollTo()
                        .assertIsDisplayed()
                    compose
                        .onNodeWithText(
                            localized.getString(R.string.history_delete_template_preserved),
                            substring = true,
                        ).performScrollTo()
                        .assertIsDisplayed()
                    val confirm = compose.onNodeWithTag("history-delete-confirm")
                    assertTrue(
                        confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].label!!.contains(
                            root.executionId.value,
                        ),
                    )
                    val retained = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                    for (node in listOf(cancel, confirm)) {
                        node.performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus)
                        keyboard.awaitFocus(node)
                        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                        val density = compose.activity.resources.displayMetrics.density
                        assertTrue("$locale/$index width $bounds", bounds.width >= 48 * density - 1)
                        assertTrue("$locale/$index height $bounds", bounds.height >= 48 * density - 1)
                    }
                    assertEquals(0, writes)
                    cancel.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
                    cancel.performKeyInput { pressKey(Key.Enter) }
                    keyboard.awaitFocus(delete)
                    compose.runOnIdle { retained() }
                    assertEquals(0, writes)
                }
            }
        } finally {
            controller.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun hiddenDeletionRetainsWriterAndTerminalRouteNeverRevivesDestructiveControls() {
        val visible = mutableStateOf(true)
        val attached = mutableStateOf(true)
        var ownsRoute = true
        val root = activity("terminal-deletion")
        val detail =
            com.alexandr5476.lifetracing.domain.ActivityHistoryDetail(
                root,
                END,
                ZONE,
                com.alexandr5476.lifetracing.domain
                    .ActivityTemplateSettings(),
                emptyList(),
            )
        val scope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
            )
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var writes = 0
        var refreshes = 0
        var completions = 0
        val controller =
            ActivityHistoryMutationController(
                scope,
                root.executionId,
                { detail },
                { _, _, _ -> error("unused correction") },
                { _, _, _ ->
                    writes++
                    gate.await()
                },
            )
        val session = ActivityHistoryMutationRouteSession(root.executionId, controller)
        try {
            compose.setContent {
                LifeTracingTheme {
                    Box {
                        if (attached.value) {
                            ActivityHistoryDetailRoute(
                                session,
                                {},
                                { refreshes++ },
                                { completions++ },
                                isRouteVisible = visible.value,
                                ownsRoute = { ownsRoute },
                            )
                        }
                        TextButton(onClick = {}) { Text("Foreground destination") }
                    }
                }
            }
            keyboard.enter()
            compose.waitUntil(5_000) { controller.state.value.load is HistoryDetailLoad.Content }
            compose.onNodeWithTag("history-delete").performSemanticsAction(SemanticsActions.OnClick) { it() }
            val retained =
                compose
                    .onNodeWithTag(
                        "history-delete-confirm",
                    ).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            compose.runOnIdle {
                ownsRoute = false
                retained()
                ownsRoute = true
                retained()
                ownsRoute = false
                visible.value = false
            }
            assertEquals(0, writes)
            compose.runOnIdle {
                ownsRoute = true
                visible.value = true
            }
            compose
                .onNodeWithTag(
                    "history-delete-confirm",
                ).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) {
                    it()
                }
            compose.waitUntil(5_000) { controller.state.value.isMutating }
            compose.runOnIdle {
                ownsRoute = false
                visible.value = false
            }
            val foreground = compose.onNodeWithText("Foreground destination")
            foreground.performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(foreground)
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil(5_000) { controller.state.value.deleted }
            compose.waitForIdle()
            foreground.assertIsFocused()
            assertEquals(0, completions)
            assertEquals(1, refreshes)
            compose.onNodeWithTag("history-delete").assertDoesNotExist()
            compose.onNodeWithTag("history-delete-confirm").assertDoesNotExist()
            compose.runOnIdle {
                retained()
                attached.value = false
            }
            compose.runOnIdle {
                ownsRoute = true
                retained()
                visible.value = true
                attached.value = true
            }
            compose.waitUntil(5_000) { completions == 1 }
            compose.runOnIdle { retained() }
            assertEquals(1, writes)
            assertEquals(1, refreshes)
            compose.onNodeWithTag("history-delete").assertDoesNotExist()
            compose.onNodeWithTag("history-correct").assertDoesNotExist()
        } finally {
            controller.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun page(roots: List<CompletedHistoryRoot>) =
        HistoryPresentationState(HistoryBrowseWindow(DATE.minusDays(27), DATE), HistoryRootsLoad.Content(roots))

    private fun activity(id: String) =
        CompletedActivityHistoryRoot(
            ActivityExecutionId(id),
            ActivitySnapshotId("snapshot-$id"),
            DATE,
            END,
            START,
            Duration.ofHours(1),
            null,
            "Duplicate",
            null,
            TimeTrackingMode.STOPWATCH,
            null,
            ZONE,
            120,
        )

    private fun sequence(id: String) =
        CompletedSequenceHistoryRoot(
            SequenceExecutionId(id),
            SequenceSnapshotId("snapshot-$id"),
            DATE,
            END,
            START,
            SequenceExecutionStatus.COMPLETED,
            Duration.ofMinutes(50),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            null,
            "Duplicate",
            null,
            ZONE,
            120,
        )

    private fun record(root: CompletedHistoryRoot): String =
        text(
            if (root is CompletedActivityHistoryRoot) {
                R.string.history_activity_record
            } else {
                R.string.history_sequence_record
            },
            root.identity().executionId,
        )

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    companion object {
        private val DATE = LocalDate.of(2001, 2, 3)
        private val START = Instant.parse("2026-10-25T00:30:00Z")
        private val END = Instant.parse("2026-10-25T01:30:00Z")
        private val ZONE = ZoneId.of("Europe/Berlin")
    }
}
