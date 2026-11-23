@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.alexandr5476.lifetracing.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.PrimaryWorkflowAppearance
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.assertTextFitsLayout
import com.alexandr5476.lifetracing.ui.assertTextFullyVisible
import com.alexandr5476.lifetracing.ui.renderedTextLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Instant
import java.util.Locale

class ArchivedTemplatesScreenTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val keyboard = KeyboardInputFixture(compose)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(keyboard).around(compose)

    @Test
    fun shared_pending_state_disables_only_the_typed_target_until_completion_requires_a_fresh_publication() {
        val activity = LibraryTemplateId.Activity(ActivityTemplateId("same-id"))
        val sequence = LibraryTemplateId.Sequence(SequenceTemplateId("same-id"))
        val restores = ArchivedRestoreCoordinator()
        val state = mutableStateOf(ArchivedTemplatesState(listOf(row(activity), row(sequence)), loading = false))
        val requests = mutableListOf<LibraryTemplateId>()
        compose.setContent {
            val status by restores.status.collectAsState()
            LifeTracingAppearance(AppearancePreferences()) {
                ArchivedTemplatesScreen(
                    state.value,
                    { requests += it },
                    {},
                    {},
                    {},
                    { state.value },
                    restoreStatus = status,
                    readRestoreStatus = { restores.status.value },
                )
            }
        }
        val activityTag = "archived-restore-${activity.archivedRowKey()}"
        val sequenceTag = "archived-restore-${sequence.archivedRowKey()}"
        val oldActivity = nativeClick(activityTag)
        compose.runOnIdle {
            assertTrue(restores.reserve(activity, archiveCompletion = 0))
            assertFalse(oldActivity())
        }
        compose.onNodeWithTag(activityTag).assertIsNotEnabled()
        val pending = compose.onNodeWithTag(activityTag).fetchSemanticsNode().config
        assertTrue(pending.contains(SemanticsProperties.StateDescription))
        val oldSequence = nativeClick(sequenceTag)
        compose.onNodeWithTag(sequenceTag).assertIsEnabled()
        val completion =
            compose.runOnIdle {
                assertTrue(oldSequence())
                restores.complete(activity).also { assertFalse(oldSequence()) }
            }
        compose.onNodeWithTag(sequenceTag).assertIsNotEnabled()
        compose.runOnIdle {
            state.value = ArchivedTemplatesState(listOf(row(sequence)), loading = false, archiveCompletion = completion)
        }
        val currentSequence = nativeClick(sequenceTag)
        compose.runOnIdle {
            assertFalse(oldActivity())
            assertFalse(oldSequence())
            assertTrue(currentSequence())
            assertEquals(listOf(sequence, sequence), requests)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun reload_retry_and_typed_restore_admit_only_current_canonical_actions() {
        val activity = LibraryTemplateId.Activity(ActivityTemplateId("same-id"))
        val sequence = LibraryTemplateId.Sequence(SequenceTemplateId("same-id"))
        val initial = ArchivedTemplatesState(listOf(row(activity), row(sequence)), loading = false)
        val state = mutableStateOf(initial)
        val visible = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        val ownsRoute = { visible.value }
        val restores = mutableListOf<LibraryTemplateId>()
        var reloads = 0
        var backs = 0
        compose.setContent {
            if (mounted.value) {
                LifeTracingAppearance(AppearancePreferences()) {
                    ArchivedTemplatesScreen(
                        state.value,
                        {
                            restores += it
                            state.value = state.value.copy(pending = it)
                        },
                        {
                            reloads++
                            state.value = state.value.copy(loading = true, readFailed = false)
                        },
                        {
                            val id = requireNotNull(state.value.failedRestore)
                            restores += id
                            state.value = state.value.copy(pending = id, failedRestore = null)
                        },
                        { backs++ },
                        { state.value },
                        visible.value,
                        ownsRoute,
                    )
                }
            }
        }
        val activityTag = "archived-restore-${activity.archivedRowKey()}"
        val sequenceTag = "archived-restore-${sequence.archivedRowKey()}"
        val obsoletePublication = nativeClick(activityTag)
        compose.runOnIdle {
            state.value = initial.copy(items = listOf(row(activity), row(sequence)), readGeneration = 1)
            assertFalse(obsoletePublication())
        }
        val activityRestore = nativeClick(activityTag)
        compose.runOnIdle {
            assertTrue(activityRestore())
            assertFalse(activityRestore())
            assertEquals(listOf(activity, sequence), state.value.items?.map { it.id })
            state.value = state.value.copy(pending = null, readFailed = true)
            assertFalse(activityRestore())
        }
        val reload = nativeClick("archived-reload")
        compose.runOnIdle {
            assertTrue(reload())
            assertFalse(reload())
            assertFalse(activityRestore())
            state.value =
                ArchivedTemplatesState(
                    listOf(row(sequence)),
                    loading = false,
                    failedRestore = sequence,
                    readGeneration = 2,
                )
        }
        val obsoleteRetry = nativeClick("archived-retry")
        compose.runOnIdle {
            state.value = state.value.copy(failedRestore = null)
            assertFalse(obsoleteRetry())
            assertFalse(reload())
            assertFalse(activityRestore())
            state.value = state.value.copy(failedRestore = sequence, readGeneration = 3)
        }
        val retry = nativeClick("archived-retry")
        compose.runOnIdle {
            assertTrue(retry())
            assertFalse(retry())
            state.value = state.value.copy(pending = null, readGeneration = 4)
        }
        val back = nativeClick("archived-back")
        val sequenceRestore = nativeClick(sequenceTag)
        compose.runOnIdle {
            visible.value = false
            assertFalse(back())
            assertFalse(sequenceRestore())
        }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = false }
        compose.onNodeWithTag("archived-page").assertDoesNotExist()
        compose.runOnIdle {
            mounted.value = true
            visible.value = true
        }
        val currentBack = nativeClick("archived-back")
        compose.runOnIdle {
            assertFalse(back())
            assertFalse(sequenceRestore())
            assertTrue(currentBack())
            assertEquals(listOf(activity, sequence), restores)
            assertEquals(1, reloads)
            assertEquals(1, backs)
        }
        scrollTo(sequenceTag)
        val restoreButton = compose.onNodeWithTag(sequenceTag)
        restoreButton.performTouchInput { down(center) }
        compose.runOnIdle { visible.value = false }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(activity, sequence), restores)
            visible.value = true
        }
        restoreButton.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(activity, sequence, sequence), restores) }
    }

    @Test
    fun english_restore_actions_include_ids_for_names_differing_only_by_case() {
        verifyCaseDistinctActions(Locale.ENGLISH)
    }

    @Test
    fun russian_restore_actions_include_ids_for_names_differing_only_by_case() {
        verifyCaseDistinctActions(Locale.forLanguageTag("ru"))
    }

    private fun verifyCaseDistinctActions(locale: Locale) {
        val upperId = LibraryTemplateId.Activity(ActivityTemplateId("first-run"))
        val lowerId = LibraryTemplateId.Activity(ActivityTemplateId("second-run"))
        val upper = row(upperId).copy(name = "Run", shortComment = null)
        val lower = row(lowerId).copy(name = "run", shortComment = null)
        val state = mutableStateOf(ArchivedTemplatesState(listOf(upper, lower), loading = false))
        val requests = mutableListOf<LibraryTemplateId>()
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(locale) }
        val localized = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                LifeTracingAppearance(AppearancePreferences()) {
                    ArchivedTemplatesScreen(state.value, { requests += it }, {}, {}, {}, { state.value })
                }
            }
        }

        val upperTag = "archived-restore-${upperId.archivedRowKey()}"
        val lowerTag = "archived-restore-${lowerId.archivedRowKey()}"
        listOf(upper to upperTag, lower to lowerTag).forEach { (row, tag) ->
            scrollTo(tag)
            val target = targetLabel(localized, row, includeId = true)
            assertDescription(tag, localized.getString(R.string.archived_templates_restore_target, target))
        }

        val restoreUpper = nativeClick(upperTag)
        val obsoleteLower = nativeClick(lowerTag)
        compose.runOnIdle {
            assertTrue(restoreUpper())
            state.value = ArchivedTemplatesState(listOf(lower), loading = false, readGeneration = 1)
            assertFalse(restoreUpper())
            assertFalse(obsoleteLower())
        }
        val restoreLower = nativeClick(lowerTag)
        compose.runOnIdle {
            assertTrue(restoreLower())
            assertEquals(listOf(upperId, lowerId), requests)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun english_and_russian_states_reflow_and_restore_is_the_only_row_action() {
        val locale = mutableStateOf(Locale.ENGLISH)
        val state = mutableStateOf(ArchivedTemplatesState())
        val activity = LibraryTemplateId.Activity(ActivityTemplateId("same-id"))
        val sequence = LibraryTemplateId.Sequence(SequenceTemplateId("same-id"))
        val otherActivity = LibraryTemplateId.Activity(ActivityTemplateId("same-id-with-a-long-common-prefix"))
        val rows = listOf(row(activity), row(otherActivity), row(sequence).copy(shortComment = null))
        val requests = mutableListOf<LibraryTemplateId>()
        var retries = 0
        val appearance = AppearancePreferences(interfaceScalePercent = 120, textScalePercent = 130)
        compose.setContent {
            val base = LocalContext.current
            val configuration = scaledArchiveConfiguration(LocalConfiguration.current, locale.value)
            val localized = base.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(localized.resources.displayMetrics.density, configuration.fontScale),
            ) {
                LifeTracingAppearance(appearance) {
                    ArchivedTemplatesScreen(
                        state.value,
                        {
                            requests += it
                            state.value = state.value.copy(pending = it)
                        },
                        { state.value = state.value.copy(loading = true, readFailed = false) },
                        {
                            retries++
                            state.value = ArchivedTemplatesState(listOf(rows.last()), loading = false)
                        },
                        {},
                        readCurrent = { state.value },
                    )
                }
            }
        }
        listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")).forEach { language ->
            compose.runOnIdle {
                locale.value = language
                state.value = ArchivedTemplatesState()
            }
            val configuration = scaledArchiveConfiguration(compose.activity.resources.configuration, language)
            val context = compose.activity.createConfigurationContext(configuration)
            showText(context.getString(R.string.archived_templates_loading))
            compose.runOnIdle { state.value = ArchivedTemplatesState(emptyList(), loading = false) }
            showText(context.getString(R.string.archived_templates_empty))
            compose.runOnIdle {
                state.value = ArchivedTemplatesState(rows, loading = false, readFailed = true, readGeneration = 1)
            }
            showText(context.getString(R.string.archived_templates_read_failure))
            compose.onNodeWithTag(ARCHIVE_RETRY).assertDoesNotExist()
            scrollTo("archived-restore-${activity.archivedRowKey()}")
            compose.onNodeWithTag("archived-restore-${activity.archivedRowKey()}").assertIsNotEnabled()
            scrollTo("archived-reload")
            compose.onNodeWithTag("archived-reload").performClick()
            showText(context.getString(R.string.archived_templates_reloading))
            compose.runOnIdle { state.value = ArchivedTemplatesState(rows, loading = false, readGeneration = 2) }
            showText(context.resources.getQuantityString(R.plurals.archived_templates_count, rows.size, rows.size))
            val descriptions = rows.map { verifyRow(context, it, includeId = it.id != sequence) }
            assertEquals(rows.size, descriptions.toSet().size)
            val restoreTag = "archived-restore-${activity.archivedRowKey()}"
            scrollTo(restoreTag)
            compose.onNodeWithTag(restoreTag).performClick().assertIsNotEnabled()
            assertEquals(activity, requests.last())
            compose.onNodeWithTag("archived-row-${activity.archivedRowKey()}").assertExists()
            val label = targetLabel(context, rows.first(), includeId = true)
            showText(context.getString(R.string.archived_templates_restoring_target, label))
            compose.runOnIdle { state.value = state.value.copy(reconcilingRestore = true) }
            showText(context.getString(R.string.archived_templates_reconciling))
            compose.runOnIdle {
                state.value = state.value.copy(pending = null, reconcilingRestore = false, failedRestore = activity)
            }
            showText(context.getString(R.string.archived_templates_restore_failure, label))
            scrollTo("archived-retry")
            assertDescription("archived-retry", context.getString(R.string.archived_templates_retry_target, label))
            compose.onNodeWithTag("archived-retry").performClick()
            scrollTo("archived-row-${sequence.archivedRowKey()}")
            compose.onNodeWithTag("archived-row-${sequence.archivedRowKey()}").assertIsDisplayed()
            compose.onNodeWithTag("archived-row-${activity.archivedRowKey()}").assertDoesNotExist()
            compose.onNodeWithTag("archived-purge").assertDoesNotExist()
            compose.onNodeWithText("Permanent purge").assertDoesNotExist()
            compose.onNodeWithText("Удалить навсегда").assertDoesNotExist()
        }
        assertEquals(2, retries)
        assertEquals(listOf(activity, activity), requests)
    }

    @Test
    fun longDuplicateRowsAndRestoreFailureStayReadableAtRussianMaximumScale() {
        val configuration = PrimaryWorkflowAppearance.RussianMaximum
        val context = configuration.resourcesContext(compose.activity)
        val first = row(LibraryTemplateId.Activity(ActivityTemplateId("duplicate-first"))).copy(shortComment = null)
        val second = row(LibraryTemplateId.Activity(ActivityTemplateId("duplicate-second"))).copy(shortComment = null)
        val state = ArchivedTemplatesState(listOf(first, second), loading = false, failedRestore = second.id)
        val requests = mutableListOf<LibraryTemplateId>()
        var retries = 0
        var back = 0
        compose.setContent {
            configuration.Content {
                ArchivedTemplatesScreen(
                    state,
                    requests::add,
                    onReload = {},
                    onRetryRestore = { retries++ },
                    onBack = { back++ },
                    readCurrent = { state },
                )
            }
        }
        listOf(first, second).forEach { archived ->
            val key = archived.id.archivedRowKey()
            scrollTo("archived-details-$key")
            val target = targetLabel(context, archived, includeId = true)
            val name =
                compose.onNode(
                    hasText(archived.name) and hasAnyAncestor(hasTestTag("archived-details-$key")),
                    useUnmergedTree = true,
                )
            name.assertIsDisplayed().renderedTextLayout().assertTextFitsLayout()
            compose
                .onNodeWithText(
                    context.getString(R.string.archived_templates_id, archived.id.value),
                    useUnmergedTree = true,
                ).assertTextFullyVisible()
            scrollTo("archived-restore-$key")
            assertDescription(
                "archived-restore-$key",
                context.getString(R.string.archived_templates_restore_target, target),
            )
        }
        val failedTarget = targetLabel(context, second, includeId = true)
        val feedback = context.getString(R.string.archived_templates_restore_failure, failedTarget)
        showText(feedback)
        val statusText = compose.onNodeWithText(feedback)
        val layout = statusText.renderedTextLayout()
        layout.assertTextFitsLayout()
        val statusScroll = compose.onNodeWithTag("archived-feedback-scroll").assert(hasScrollAction())
        val range = statusScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        statusScroll.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, range.maxValue()) }
        compose.waitUntil(5_000) {
            val status = statusText.fetchSemanticsNode()
            status.boundsInRoot.bottom + 1f >= status.positionInRoot.y + layout.getLineBottom(layout.lineCount - 1)
        }
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            assertEquals(0, retries)
            assertEquals(0, back)
        }
        compose.onNodeWithTag("archived-restore-${second.id.archivedRowKey()}").assertIsDisplayed().performClick()
        compose.onNodeWithTag(ARCHIVE_RETRY).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ARCHIVE_BACK).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(second.id), requests)
            assertEquals(1, retries)
            assertEquals(1, back)
        }
    }

    @Test
    fun keyboard_focus_follows_the_typed_row_through_reordering_failure_reload_and_removal() {
        val activity = row(LibraryTemplateId.Activity(ActivityTemplateId("same-id")))
        val sequence = row(LibraryTemplateId.Sequence(SequenceTemplateId("same-id")))
        val state = mutableStateOf(ArchivedTemplatesState(listOf(activity, sequence), loading = false))
        val writes = mutableListOf<LibraryTemplateId>()
        var reads = 0

        fun restore(id: LibraryTemplateId) {
            writes += id
            state.value = state.value.copy(pending = id, failedRestore = null)
        }
        compose.setContent {
            LifeTracingAppearance(AppearancePreferences()) {
                ArchivedTemplatesScreen(
                    state.value,
                    ::restore,
                    {
                        reads++
                        state.value = state.value.copy(loading = true, readFailed = false)
                    },
                    { restore(requireNotNull(state.value.failedRestore)) },
                    {},
                    { state.value },
                )
            }
        }
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithTag(ARCHIVE_BACK))
        val restoreTag = "archived-restore-${activity.id.archivedRowKey()}"
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        keyboard.awaitFocus(compose.onNodeWithTag(restoreTag))
        val obsolete = nativeClick(restoreTag)
        compose.runOnIdle {
            state.value = state.value.copy(items = listOf(sequence, activity), readGeneration = 1)
            assertFalse(obsolete())
        }
        enterFocused(restoreTag)
        keyboard.awaitFocus(compose.onNodeWithTag(ARCHIVE_HEADING))
        compose.runOnIdle {
            state.value = state.value.copy(pending = null, failedRestore = activity.id, readGeneration = 2)
        }
        val staleRetry = nativeClick(ARCHIVE_RETRY)
        enterFocused(ARCHIVE_RETRY)
        compose.runOnIdle {
            state.value = state.value.copy(pending = null, readFailed = true, readGeneration = 3)
            assertFalse(staleRetry())
        }
        enterFocused(ARCHIVE_RELOAD)
        compose.runOnIdle {
            state.value =
                ArchivedTemplatesState(
                    listOf(sequence),
                    loading = false,
                    readGeneration = 4,
                    restoreReconciled = true,
                )
        }
        keyboard.awaitFocus(compose.onNodeWithTag(ARCHIVE_HEADING))
        compose.onNodeWithTag(restoreTag).assertDoesNotExist()
        val sequenceTag = "archived-restore-${sequence.id.archivedRowKey()}"
        compose.onRoot().performKeyInput { repeat(2) { pressKey(Key.Tab) } }
        enterFocused(sequenceTag)
        compose.runOnIdle {
            state.value =
                ArchivedTemplatesState(
                    emptyList(),
                    loading = false,
                    readGeneration = 5,
                    restoreReconciled = true,
                )
        }
        keyboard.awaitFocus(compose.onNodeWithTag(ARCHIVE_HEADING))
        compose.onNodeWithTag(sequenceTag).assertDoesNotExist()
        val context = compose.activity
        showText(
            context.getString(
                R.string.archived_templates_restore_confirmed,
                context.getString(R.string.archived_templates_empty),
            ),
        )
        compose.runOnIdle {
            assertEquals(listOf(activity.id, activity.id, sequence.id), writes)
            assertEquals(1, reads)
        }
    }

    @Test
    fun lazy_archive_scroll_and_keyboard_tab_reach_offscreen_typed_actions() {
        val rows =
            List(56) { index ->
                val id =
                    if (index % 2 == 0) {
                        LibraryTemplateId.Activity(ActivityTemplateId("row-$index"))
                    } else {
                        LibraryTemplateId.Sequence(SequenceTemplateId("row-$index"))
                    }
                row(id).copy(name = "Archive item $index", shortComment = null)
            }
        val state = ArchivedTemplatesState(rows, loading = false)
        val writes = mutableListOf<LibraryTemplateId>()
        compose.setContent {
            LifeTracingAppearance(AppearancePreferences()) {
                ArchivedTemplatesScreen(state, { writes += it }, {}, {}, {}, { state })
            }
        }
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithTag(ARCHIVE_BACK))
        val rowMatcher =
            SemanticsMatcher("archive row") {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("archived-row-")
            }
        assertTrue(compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size < rows.size)
        val start = 32
        compose.onNodeWithTag("archived-page").performSemanticsAction(SemanticsActions.ScrollToIndex) { it(start) }

        fun tag(index: Int) = "archived-restore-${rows[index].id.archivedRowKey()}"

        compose.onNodeWithTag(tag(start)).performSemanticsAction(SemanticsActions.RequestFocus)
        enterFocused(tag(start))
        compose.onRoot().performKeyInput { repeat(9) { pressKey(Key.Tab) } }
        enterFocused(tag(start + 9))
        for (index in start + 8 downTo start) {
            compose.onRoot().performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            val expected = compose.onNodeWithTag(tag(index))
            expected.assertExists("Reverse Tab from row ${index + 1} should reach row $index")
            keyboard.awaitFocus(expected)
        }
        compose.runOnIdle { assertEquals(listOf(rows[start].id, rows[start + 9].id), writes) }
    }

    private fun enterFocused(tag: String) {
        val node = compose.onNodeWithTag(tag)
        keyboard.awaitFocus(node)
        node.performKeyInput { pressKey(Key.Enter) }
    }

    private fun scaledArchiveConfiguration(
        base: Configuration,
        locale: Locale,
    ): Configuration =
        Configuration(base).apply {
            setLocale(locale)
            densityDpi = (densityDpi * 1.2f).toInt()
            fontScale = 1.4f
        }

    private fun verifyRow(
        context: Context,
        row: LibraryTrackable,
        includeId: Boolean,
    ): String {
        val key = row.id.archivedRowKey()
        val label = targetLabel(context, row, includeId)
        val description =
            row.shortComment?.let { context.getString(R.string.archived_templates_row_with_comment, label, it) }
                ?: context.getString(R.string.archived_templates_row, label)
        val detailsTag = "archived-details-$key"
        scrollTo(detailsTag)
        assertDescription(detailsTag, description)
        compose.onNodeWithTag("archived-row-$key").assert(!hasClickAction())
        val restoreTag = "archived-restore-$key"
        scrollTo(restoreTag)
        val action = context.getString(R.string.archived_templates_restore_target, label)
        assertDescription(restoreTag, action)
        val restore =
            compose
                .onNodeWithTag(restoreTag)
                .assertIsDisplayed()
                .assert(hasClickAction())
                .assertIsEnabled()
        val bounds = restore.fetchSemanticsNode().touchBoundsInRoot
        val minimum = 48 * context.resources.displayMetrics.density - 1
        val locale = context.resources.configuration.locales[0]
        val diagnostic = "$locale $restoreTag touch bounds=$bounds, minimum=$minimum"
        assertTrue("$diagnostic height", bounds.height >= minimum)
        assertTrue("$diagnostic width", bounds.width >= minimum)
        return action
    }

    private fun targetLabel(
        context: Context,
        row: LibraryTrackable,
        includeId: Boolean = false,
    ): String {
        val kind =
            context.getString(
                if (row.id is LibraryTemplateId.Activity) R.string.library_activity else R.string.library_sequence,
            )
        return if (includeId) {
            context.getString(R.string.archived_templates_target_with_id, kind, row.name, row.id.value)
        } else {
            context.getString(R.string.archived_templates_target, kind, row.name)
        }
    }

    private fun assertDescription(
        tag: String,
        description: String,
    ) {
        val semantics = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        assertEquals(listOf(description), semantics[SemanticsProperties.ContentDescription])
    }

    private fun scrollTo(tag: String) {
        if (tag != ARCHIVE_BACK && tag != ARCHIVE_RELOAD && tag != ARCHIVE_RETRY) {
            compose.onNodeWithTag("archived-page").performScrollToNode(hasTestTag(tag))
        }
    }

    private fun nativeClick(tag: String): () -> Boolean {
        scrollTo(tag)
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return requireNotNull(node.config[SemanticsActions.OnClick].action)
    }

    private fun showText(text: String) {
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onNodeWithText(text).assertIsDisplayed().assert(polite)
    }

    private fun row(id: LibraryTemplateId) =
        LibraryTrackable(
            id,
            "Long archived template / Длинное название архивного шаблона",
            "Preserved comment / Сохранённый комментарий",
            null,
            emptySet(),
            null,
            null,
            Instant.EPOCH,
        )
}
