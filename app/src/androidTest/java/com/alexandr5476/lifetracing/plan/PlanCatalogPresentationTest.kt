@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.plan

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PlanCatalogPresentationTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<Fixture>()
    private val activity =
        ReusablePlanCatalogItem(LibraryTemplateId.Activity(ActivityTemplateId("same")), "Same", null, null)
    private val sequence = activity.copy(id = LibraryTemplateId.Sequence(SequenceTemplateId("same")))
    private val secondActivity = activity.copy(id = LibraryTemplateId.Activity(ActivityTemplateId("second")))
    private val sources get() = listOf(activity, secondActivity, sequence)

    @After
    fun dispose() {
        fixtures.forEach {
            it.controller.close()
            it.scope.cancel()
        }
    }

    @Test
    fun nativeEntryLocalizedDuplicatesAndEnterKeepExactTypedSourceWithoutFocusSelection() {
        val fixture = launch()
        // The real route has no calendar cells in this fixture: Back, Previous, Next, Today, Add.
        var current = compose.onNodeWithText(context.getString(R.string.plan_back))
        keyboard.awaitFocus(current)
        repeat(4) {
            keyboard.sendKey(current, KeyEvent.KEYCODE_TAB)
            current =
                compose.onNode(
                    androidx.compose.ui.test
                        .isFocused(),
                )
        }
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        keyboard.sendKey(current, KeyEvent.KEYCODE_ENTER)
        awaitSearch()
        for (language in listOf("en", "ru")) {
            compose.runOnIdle { fixture.language.value = language }
            val localized = localized(language)
            compose.onNodeWithText(localized.getString(R.string.plan_choose_template)).assertIsDisplayed()
            val labels =
                sources.map { item ->
                    val kind =
                        localized.getString(
                            if (item.id is LibraryTemplateId.Activity) {
                                R.string.plan_execution_activity_kind
                            } else {
                                R.string.plan_execution_sequence
                            },
                        )
                    val name = localized.getString(R.string.plan_catalog_result, item.name, kind)
                    if (item ==
                        sequence
                    ) {
                        name
                    } else {
                        localized.getString(
                            R.string.plan_catalog_ordinal,
                            name,
                            if (item ==
                                activity
                            ) {
                                1
                            } else {
                                2
                            },
                            2,
                        )
                    }
                }
            assertEquals(3, labels.toSet().size)
            sources.forEachIndexed { index, item ->
                assertNative(catalogResultTag(item.id))
                val config = compose.onNodeWithTag(catalogResultTag(item.id)).fetchSemanticsNode().config
                assertEquals(listOf(labels[index]), config[SemanticsProperties.ContentDescription])
            }
        }
        val search = compose.onNodeWithTag("plan-catalog-search")
        keyboard.sendKey(search, KeyEvent.KEYCODE_TAB)
        val first = compose.onNodeWithTag(catalogResultTag(activity.id))
        keyboard.awaitFocus(first)
        keyboard.sendKey(first, KeyEvent.KEYCODE_TAB)
        keyboard.awaitFocus(compose.onNodeWithTag(catalogResultTag(secondActivity.id)))
        keyboard.sendKey(compose.onNodeWithTag(catalogResultTag(secondActivity.id)), KeyEvent.KEYCODE_TAB)
        val sequenceNode = compose.onNodeWithTag(catalogResultTag(sequence.id))
        keyboard.awaitFocus(sequenceNode)
        assertTrue(fixture.actions.none { it is PlanAction.SelectCatalogItem })
        keyboard.sendKey(sequenceNode, KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("plan-catalog-search").assertDoesNotExist()
        compose.onNodeWithTag("plan-schedule-form").assertIsDisplayed()
        val firstForm = requireNotNull(fixture.controller.state.value.form)
        assertEquals(sequence.id, firstForm.source?.id)
        assertNull(firstForm.identity)
        assertEquals(PlanScheduleKind.FLOATING_DAY, firstForm.kind)
        assertEquals(
            fixture.controller.state.value.selectedDate
                .toString(),
            firstForm.date,
        )
        assertFalse(firstForm.scheduleEdited)
        assertEquals(ZoneId.of("Europe/Moscow"), firstForm.schedulingZoneId)
        // Background roots can retain local Focused=true while the dialog owns native input.
        keyboard.awaitWindowFocus(compose.onNodeWithTag("plan-schedule-form"))
        compose.runOnIdle { fixture.controller.dispatch(PlanAction.DismissForm(fixture.controller.formId)) }
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        open()
        focus(catalogResultTag(activity.id))
        keyboard.sendKey(compose.onNodeWithTag(catalogResultTag(activity.id)), KeyEvent.KEYCODE_ENTER)
        val secondForm = requireNotNull(fixture.controller.state.value.form)
        assertEquals(activity.id, secondForm.source?.id)
        assertNotEquals(firstForm.formId, secondForm.formId)
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun keyboardQueryReplacementKeepsSearchFocusAndRejectsLateReadPublication() {
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val fixture =
            launch { query, _, _ ->
                when (query) {
                    "a" -> {
                        enteredA.complete(Unit)
                        releaseA.await()
                        listOf(activity)
                    }
                    "ab" -> {
                        releaseB.await()
                        listOf(sequence)
                    }
                    else -> sources
                }
            }
        open()
        val search = compose.onNodeWithTag("plan-catalog-search")
        keyboard.sendKey(search, KeyEvent.KEYCODE_A)
        compose.waitUntil { enteredA.isCompleted }
        val obsoleteSearch = fixture.actions.filterIsInstance<PlanAction.SearchCatalog>().last()
        keyboard.awaitFocus(search)
        compose.onNodeWithTag(catalogResultTag(activity.id)).assertDoesNotExist()
        keyboard.sendKey(search, KeyEvent.KEYCODE_B)
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.query == "ab"
        }
        assertEquals("ab", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle {
            fixture.controller.dispatch(obsoleteSearch.copy(query = "obsolete", editSequence = Long.MAX_VALUE))
            fixture.controller.dispatch(PlanAction.SearchCatalog("a", obsoleteSearch.context, retry = true))
        }
        assertEquals(
            "ab",
            fixture.controller.state.value.catalog
                ?.query,
        )
        assertEquals("ab", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { releaseB.complete(Unit) }
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.items == listOf(sequence)
        }
        keyboard.awaitFocus(search)
        compose.runOnIdle { releaseA.complete(Unit) }
        compose.waitForIdle()
        assertEquals(
            listOf(sequence),
            fixture.controller.state.value.catalog
                ?.items,
        )
        keyboard.sendKey(search, KeyEvent.KEYCODE_TAB)
        val result = compose.onNodeWithTag(catalogResultTag(sequence.id))
        keyboard.awaitFocus(result)
        assertEquals(
            "ab",
            fixture.controller.state.value.catalog
                ?.query,
        )
        assertTrue(fixture.actions.none { it is PlanAction.SelectCatalogItem })
        keyboard.sendKey(result, KeyEvent.KEYCODE_ENTER)
        assertEquals(
            sequence.id,
            fixture.controller.state.value.form
                ?.source
                ?.id,
        )
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun nativePaginationPreservesTypedNodesAndFocusedResultAcrossAppendAndReordering() {
        val page = page()
        val appendEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<Pair<Int, ReusablePlanCatalogItem?>>()
        val fixture =
            launch { _, limit, after ->
                requests += limit to after
                if (after == null) {
                    page
                } else {
                    appendEntered.complete(Unit)
                    release.await()
                    listOf(page.last(), page.last())
                }
            }
        open()
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.items
                ?.size == 30
        }
        val first = compose.onNodeWithTag(catalogResultTag(page.first().id))
        val nativeId = first.fetchSemanticsNode().id
        assertNative("plan-catalog-more")
        focus("plan-catalog-more")
        keyboard.sendKey(compose.onNodeWithTag("plan-catalog-more"), KeyEvent.KEYCODE_ENTER)
        compose.waitUntil { appendEntered.isCompleted }
        keyboard.awaitFocus(compose.onNodeWithTag("plan-catalog-more"))
        focus(catalogResultTag(page.first().id))
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.items
                ?.size == 31
        }
        keyboard.awaitFocus(first)
        assertEquals(nativeId, first.fetchSemanticsNode().id)
        page.forEach { compose.onAllNodesWithTag(catalogResultTag(it.id)).assertCountEquals(1) }
        assertEquals(listOf(31 to null, 31 to page[29]), requests.toList())
        // A presentation reorder preserves each source's native node and keyboard focus.
        compose.runOnIdle {
            fixture.override.value =
                fixture.controller.state.value.copy(
                    catalog =
                        fixture.controller.state.value.catalog!!
                            .copy(items = page.reversed()),
                )
        }
        keyboard.awaitFocus(first)
        assertEquals(nativeId, first.fetchSemanticsNode().id)
        compose.runOnIdle {
            val state = fixture.controller.state.value
            fixture.override.value = state.copy(catalog = state.catalog!!.copy(items = page.drop(1)))
        }
        keyboard.awaitFocus(compose.onNodeWithTag("plan-catalog-search"))
        compose.runOnIdle { fixture.override.value = null }
        keyboard.awaitFocus(compose.onNodeWithTag("plan-catalog-search"))
        focus("plan-catalog-close")
        keyboard.sendKey(compose.onNodeWithTag("plan-catalog-close"), KeyEvent.KEYCODE_ENTER)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        open()
        focus("plan-catalog-more")
        keyboard.sendKey(compose.onNodeWithTag("plan-catalog-more"), KeyEvent.KEYCODE_ENTER)
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.hasNextPage == false
        }
        keyboard.awaitFocus(compose.onNodeWithTag("plan-catalog-close"))
        assertTrue(fixture.actions.none { it is PlanAction.SelectCatalogItem })
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun nativeFirstPageAndAppendRetriesRetainRawQueryAndNeverReplaySelection() {
        val page = page()
        val retryGate = CompletableDeferred<Unit>()
        var firstCalls = 0
        var appendCalls = 0
        val fixture =
            launch { query, _, after ->
                if (after == null) {
                    firstCalls++
                    if (query.isEmpty() || firstCalls == 2) error("first page")
                    page
                } else {
                    appendCalls++
                    if (appendCalls == 1) error("append")
                    retryGate.await()
                    listOf(page.last())
                }
            }
        open()
        val search = compose.onNodeWithTag("plan-catalog-search")
        keyboard.sendKey(search, KeyEvent.KEYCODE_A)
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.query == "a" &&
                fixture.controller.state.value.catalog
                    ?.failure != null
        }
        keyboard.awaitFocus(search)
        compose.onNodeWithText(context.getString(R.string.plan_catalog_search_failed)).assertIsDisplayed()
        keyboard.sendKey(search, KeyEvent.KEYCODE_TAB)
        val retry = compose.onNodeWithTag("plan-catalog-retry")
        keyboard.awaitFocus(retry)
        assertNative("plan-catalog-retry")
        keyboard.sendKey(retry, KeyEvent.KEYCODE_ENTER)
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.items
                ?.size == 30
        }
        keyboard.awaitFocus(search)
        assertEquals("a", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        focus("plan-catalog-more")
        keyboard.sendKey(compose.onNodeWithTag("plan-catalog-more"), KeyEvent.KEYCODE_ENTER)
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.failure != null
        }
        keyboard.awaitFocus(compose.onNodeWithTag("plan-catalog-more"))
        compose
            .onNodeWithText(
                context.getString(R.string.plan_catalog_append_failed),
            ).performScrollTo()
            .assertIsDisplayed()
        focus("plan-catalog-retry")
        keyboard.sendKey(retry, KeyEvent.KEYCODE_ENTER)
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.retrying == true
        }
        keyboard.awaitFocus(retry)
        compose.runOnIdle { retryGate.complete(Unit) }
        compose.waitUntil {
            fixture.controller.state.value.catalog
                ?.items
                ?.size == 31
        }
        keyboard.awaitFocus(search)
        assertEquals(
            "a",
            fixture.controller.state.value.catalog
                ?.query,
        )
        assertTrue(fixture.actions.none { it is PlanAction.SelectCatalogItem })
        assertNull(fixture.controller.state.value.form)
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun visibleCloseNativeBackAndRouteExitInvalidatePendingCatalogAndRestoreEntry() {
        val pending = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val exitPending = CompletableDeferred<Unit>()
        val exitEntered = CompletableDeferred<Unit>()
        var reads = 0
        val fixture =
            launch { _, _, _ ->
                reads++
                if (reads == 1) {
                    entered.complete(Unit)
                    pending.await()
                }
                if (reads == 3) {
                    exitEntered.complete(Unit)
                    exitPending.await()
                }
                sources
            }
        open()
        compose.waitUntil { entered.isCompleted }
        val oldContext = requireNotNull(fixture.controller.state.value.catalog).context
        keyboard.sendKey(compose.onNodeWithTag("plan-catalog-search"), KeyEvent.KEYCODE_TAB)
        val close = compose.onNodeWithTag("plan-catalog-close")
        keyboard.awaitFocus(close)
        keyboard.sendKey(close, KeyEvent.KEYCODE_ENTER)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        assertNull(fixture.controller.state.value.catalog)
        open()
        val reopened = fixture.controller.state.value.catalog
        compose.runOnIdle { pending.complete(Unit) }
        compose.waitForIdle()
        assertEquals(reopened, fixture.controller.state.value.catalog)
        compose.runOnIdle { fixture.controller.dispatch(PlanAction.DismissCatalog(oldContext)) }
        assertEquals(reopened, fixture.controller.state.value.catalog)
        keyboard.closeKeyboard(compose.onNodeWithTag("plan-catalog-search"))
        keyboard.sendKey(compose.onNodeWithTag("plan-catalog-search"), KeyEvent.KEYCODE_BACK)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        open()
        compose.waitUntil { exitEntered.isCompleted }
        val exitContext = requireNotNull(fixture.controller.state.value.catalog).context
        compose.runOnIdle {
            fixture.visible.value = false
            fixture.controller.onRouteExited()
            exitPending.complete(Unit)
        }
        compose.onNodeWithTag("plan-catalog-search").assertDoesNotExist()
        compose.runOnIdle {
            fixture.controller.dispatch(PlanAction.SearchCatalog("obsolete", exitContext))
            fixture.controller.dispatch(PlanAction.SelectCatalogItem(activity, exitContext))
            fixture.controller.onRouteEntered()
            fixture.visible.value = true
        }
        compose.onNodeWithTag("plan-catalog-search").assertDoesNotExist()
        assertNull(fixture.controller.state.value.form)
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun englishRussianScaleExtremesKeepSearchAllLoadedResultsMoreRetryAndCloseReachable() {
        val fixture = launch()
        open()
        val scales = listOf(Triple(90, 90, 1f), Triple(120, 130, 2f), Triple(90, 130, 2f))
        for (language in listOf("en", "ru")) {
            for (scale in scales) {
                compose.runOnIdle {
                    fixture.language.value = language
                    fixture.scale.value = scale
                    val current = fixture.controller.state.value
                    fixture.override.value =
                        current.copy(
                            catalog =
                                current.catalog!!.copy(
                                    items =
                                        sources.map {
                                            it.copy(
                                                name = "A long reusable source name for reading and keyboard traversal",
                                            )
                                        },
                                    hasNextPage = true,
                                    failure = PlanMessage.LOAD_FAILED,
                                ),
                        )
                }
                val tags =
                    listOf("plan-catalog-search") + sources.map { catalogResultTag(it.id) } +
                        listOf("plan-catalog-retry", "plan-catalog-more", "plan-catalog-close")
                for ((index, tag) in tags.withIndex()) {
                    val node = compose.onNodeWithTag(tag)
                    if (tag != "plan-catalog-close") node.performScrollTo()
                    node.assertIsDisplayed().assertIsEnabled()
                    if (tag != "plan-catalog-search") {
                        val semantics = node.fetchSemanticsNode()
                        val bounds = semantics.touchBoundsInRoot
                        val minimum = 48 * context.resources.displayMetrics.density
                        assertTrue(
                            "$language $scale $tag touch=$bounds visual=${semantics.boundsInRoot} minimum=$minimum",
                            bounds.width >= minimum - 1 && bounds.height >= minimum - 1,
                        )
                        assertNative(tag)
                    }
                    if (index ==
                        0
                    ) {
                        focus(tag)
                    } else {
                        keyboard.sendKey(compose.onNodeWithTag(tags[index - 1]), KeyEvent.KEYCODE_TAB)
                    }
                    keyboard.awaitFocus(node)
                }
            }
        }
        assertTrue(fixture.actions.none { it is PlanAction.SelectCatalogItem })
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun confirmedEmptySearchRemovesOldResultsAndRetainsNativeCloseInBothLanguages() {
        val fixture = launch { query, _, _ -> if (query.isEmpty()) sources else emptyList() }
        open()
        for (language in listOf("en", "ru")) {
            compose.runOnIdle { fixture.language.value = language }
            val search = compose.onNodeWithTag("plan-catalog-search")
            keyboard.sendKey(search, KeyEvent.KEYCODE_A)
            compose.waitUntil {
                fixture.controller.state.value.catalog
                    ?.let { !it.loading && it.query.isNotEmpty() } ==
                    true
            }
            keyboard.awaitFocus(search)
            sources.forEach { compose.onNodeWithTag(catalogResultTag(it.id)).assertDoesNotExist() }
            compose.onNodeWithText(localized(language).getString(R.string.plan_catalog_empty)).assertIsDisplayed()
            keyboard.sendKey(search, KeyEvent.KEYCODE_TAB)
            val close = compose.onNodeWithTag("plan-catalog-close")
            keyboard.awaitFocus(close)
            keyboard.sendKey(close, KeyEvent.KEYCODE_ENTER)
            keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
            open()
        }
        assertNull(fixture.controller.state.value.form)
        assertTrue(fixture.actions.none { it is PlanAction.SelectCatalogItem })
        assertTrue(fixture.mutations.isEmpty())
    }

    private fun localized(language: String) =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            },
        )

    private fun assertNative(tag: String) {
        for (unmerged in listOf(false, true)) {
            val config = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().config
            assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role))
            assertTrue(config.contains(SemanticsActions.OnClick))
            assertNull(config.getOrNull(SemanticsActions.CustomActions))
        }
    }

    private fun focus(tag: String) {
        val node = compose.onNodeWithTag(tag)
        keyboard.awaitWindowFocus(node)
        if (tag != "plan-catalog-close") node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        keyboard.awaitFocus(node)
    }

    private fun open() {
        focus("plan-add")
        keyboard.sendKey(compose.onNodeWithTag("plan-add"), KeyEvent.KEYCODE_ENTER)
        awaitSearch()
    }

    private fun awaitSearch() {
        val search = compose.onNodeWithTag("plan-catalog-search")
        search.assertIsDisplayed()
        keyboard.enter(search)
        keyboard.awaitFocus(search)
    }

    private fun page() =
        sources +
            (1..28).map {
                activity.copy(
                    id = LibraryTemplateId.Activity(ActivityTemplateId("page-$it")),
                    name = "Z %02d".format(it),
                )
            }

    private fun launch(
        read: suspend (String, Int, ReusablePlanCatalogItem?) -> List<ReusablePlanCatalogItem> = { _, _, _ -> sources },
    ): Fixture {
        val fixture = Fixture(read)
        fixtures += fixture
        compose.setContent {
            val state by fixture.controller.state.collectAsState()
            val (interfaceScale, textScale, systemFont) = fixture.scale.value
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(fixture.language.value))
                    fontScale = systemFont
                }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, systemFont),
            ) {
                LifeTracingTheme {
                    LifeTracingAppearance(
                        AppearancePreferences(interfaceScalePercent = interfaceScale, textScalePercent = textScale),
                    ) {
                        PlanScreen(fixture.override.value ?: state, {
                            fixture.actions += it
                            fixture.controller.dispatch(it)
                        }, {}, isRouteVisible = fixture.visible.value)
                    }
                }
            }
        }
        keyboard.enter()
        return fixture
    }

    private class Fixture(
        read: suspend (String, Int, ReusablePlanCatalogItem?) -> List<ReusablePlanCatalogItem>,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val mutations = CopyOnWriteArrayList<PlanMutation>()
        val actions = CopyOnWriteArrayList<PlanAction>()
        val language = mutableStateOf("en")
        val scale = mutableStateOf(Triple(100, 100, 1f))
        val visible = mutableStateOf(true)
        val override = mutableStateOf<PlanPresentationState?>(null)
        val controller =
            PlanController(
                scope,
                { WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList()) },
                read,
                { CancelledPlanPage(emptyList(), false) },
                { mutations += it },
                { Instant.parse("2026-09-15T10:00:00Z") },
                { ZoneId.of("Europe/Moscow") },
                boundaryScheduler = LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
    }
}
