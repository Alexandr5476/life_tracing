@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LibraryScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val keyboardInput = KeyboardInputFixture(composeTestRule)

    @Test
    fun folderActionsBreadcrumbAndToolbarBackKeepSeparateAdmission() {
        val parent = folder("parent", "Projects")
        val nested = folder("child", "Nested", parent.id)
        var state by mutableStateOf(
            catalog(emptyList()).copy(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            null,
                            emptyList(),
                            emptyList(),
                            LibraryContents(listOf(parent), emptyList(), emptyList()),
                        ),
                    ),
            ),
        )
        val actions = mutableListOf<LibraryAction>()
        var exits = 0
        composeTestRule.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) { exits += 1 } } }
        val open = composeTestRule.onNodeWithContentDescription(text(R.string.library_open_folder, parent.name, 1))
        val organize =
            composeTestRule.onNodeWithContentDescription(
                text(R.string.library_organize_folder, parent.name, 1),
            )
        organize.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(actions.isEmpty())
        assertEquals(0, exits)
        open.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(LibraryAction.OpenFolder(parent.id)), actions)
        state =
            state.copy(
                folderId = nested.id,
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            nested.id,
                            listOf(parent, nested),
                            emptyList(),
                            LibraryContents(emptyList(), emptyList(), emptyList()),
                        ),
                    ),
            )
        val breadcrumb = composeTestRule.onNodeWithText("Library / Projects / Nested")
        breadcrumb.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        breadcrumb.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(text(R.string.library_location, "Library / Projects / Nested")),
            ),
        )
        composeTestRule.onNodeWithText(text(R.string.library_back)).performScrollTo().performClick()
        assertEquals(listOf(LibraryAction.OpenFolder(parent.id), LibraryAction.Back), actions)
        assertEquals(0, exits)
        state = state.copy(folderId = null)
        composeTestRule.onNodeWithText(text(R.string.library_back)).performClick()
        assertEquals(1, exits)
    }

    @Test
    fun browseSearchAndOrganizationStatesExposeFailuresAndExplicitRetryOutcomes() {
        var state by mutableStateOf(LibraryPresentationState())
        val actions = mutableListOf<LibraryAction>()
        composeTestRule.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }
        composeTestRule.onNodeWithText(text(R.string.library_loading)).performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text(R.string.library_organization_loading),
            ).performScrollTo()
            .assertIsDisplayed()
        state = catalog(emptyList())
        composeTestRule.onNodeWithText(text(R.string.library_empty)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.library_organization_ready)).performScrollTo().assertIsDisplayed()
        state = state.copy(browse = LibraryLoad.Failure("browse"), organization = LibraryLoad.Failure("organization"))
        for (failure in listOf(R.string.library_load_failure, R.string.library_organization_failure)) {
            composeTestRule
                .onNodeWithText(text(failure))
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        androidx.compose.ui.semantics.LiveRegionMode.Polite,
                    ),
                )
        }
        composeTestRule
            .onNodeWithContentDescription(
                text(R.string.library_retry_for, text(R.string.library_load_failure)),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(LibraryAction.Retry), actions)
        state = state.copy(browse = LibraryLoad.Loading, organization = LibraryLoad.Loading)
        composeTestRule.waitForIdle()
        state = catalog(emptyList())
        composeTestRule.onNodeWithText(text(R.string.library_retry_success)).performScrollTo().assertIsDisplayed()
        state = state.copy(query = "query", search = LibraryLoad.Loading)
        composeTestRule.onNodeWithText(text(R.string.library_retry_success)).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(text(R.string.library_search_loading))
            .performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        state = state.copy(search = LibraryLoad.Content(emptyList()))
        composeTestRule.onNodeWithText(text(R.string.library_search_empty)).performScrollTo().assertIsDisplayed()
        state = state.copy(search = LibraryLoad.Failure("search"))
        composeTestRule.onNodeWithText(text(R.string.library_search_failure)).performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(
                text(R.string.library_retry_for, text(R.string.library_search_failure)),
            ).performScrollTo()
            .performClick()
        assertEquals(listOf(LibraryAction.Retry, LibraryAction.Retry), actions)
        state = state.copy(search = LibraryLoad.Content(listOf(trackable("Found", false))))
        composeTestRule
            .onNodeWithText(text(R.string.library_search_results))
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeTestRule.onNodeWithText(text(R.string.library_retry_success)).performScrollTo().assertIsDisplayed()
        state = state.copy(isMutating = true)
        composeTestRule.waitForIdle()
        assertEquals(2, actions.size)
        composeTestRule.onNodeWithText("Found").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun duplicateNamesHaveIndependentContextualActionsAndTypedDispatchAcrossProjections() {
        val activity = trackable("Same", false).copy(shortComment = "Useful item detail")
        val second = activity.copy(id = LibraryTemplateId.Activity(ActivityTemplateId("second")))
        val sequence = trackable("Same", true)
        val items = listOf(activity, second, sequence)
        var state by mutableStateOf(catalog(items, pinned = listOf(activity)))
        val opened = mutableListOf<LibraryTemplateId>()
        val started = mutableListOf<LibraryTemplateId>()
        val commands = mutableListOf<LibraryAction>()
        composeTestRule.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    state,
                    commands::add,
                    onOpenActivity = { opened.add(LibraryTemplateId.Activity(it)) },
                    onOpenSequence = { opened.add(LibraryTemplateId.Sequence(it)) },
                    onQuickStart = started::add,
                    onRouteBack = {},
                )
            }
        }
        for (searching in listOf(false, true)) {
            if (searching) state = state.copy(query = "Same", search = LibraryLoad.Content(items))
            val projected = if (searching) items else listOf(activity) + items
            val descriptions = mutableSetOf<String>()
            projected.forEachIndexed { index, item ->
                val section =
                    if (searching) {
                        R.string.library_search_results
                    } else if (index == 0) {
                        R.string.library_pinned
                    } else {
                        R.string.library_all
                    }
                val position = if (searching || index == 0) index + 1 else index
                val context =
                    text(
                        R.string.library_template_context,
                        text(
                            if (item.id is LibraryTemplateId.Activity) {
                                R.string.library_activity
                            } else {
                                R.string.library_sequence
                            },
                        ),
                        item.name,
                        text(section),
                        position,
                    )
                val openLabel = text(R.string.library_open_template, context)
                descriptions.add(openLabel)
                val open = composeTestRule.onNodeWithContentDescription(openLabel, substring = true)
                open.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                val expectedDescription =
                    item.shortComment?.let {
                        text(R.string.library_template_details, openLabel, it)
                    } ?: openLabel
                open.assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(expectedDescription),
                    ),
                )
                val quick =
                    composeTestRule.onNodeWithContentDescription(
                        text(R.string.library_quick_start_template, context),
                    )
                quick.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                val organize =
                    composeTestRule.onNodeWithContentDescription(
                        text(R.string.library_organize_template, context),
                    )
                organize.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(item.id, opened.last())
                assertEquals(item.id, started.last())
                assertTrue(commands.isEmpty())
                assertEquals(opened.size, started.size)
                assertTrue(
                    !open
                        .fetchSemanticsNode()
                        .parent!!
                        .config
                        .contains(SemanticsActions.OnClick),
                )
            }
            assertEquals(projected.size, descriptions.size)
        }
        assertEquals(listOf(activity.id, activity.id, second.id, sequence.id) + items.map { it.id }, opened)
        assertEquals(opened, started)
        composeTestRule.onAllNodesWithText("Useful item detail", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun keyboardTraversalAndEnterKeepTheThreeTemplateActionsIndependent() {
        val item = trackable("Keyboard target", false)
        val opened = mutableListOf<ActivityTemplateId>()
        val started = mutableListOf<LibraryTemplateId>()
        val actions = mutableListOf<LibraryAction>()
        composeTestRule.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    catalog(listOf(item)),
                    actions::add,
                    onOpenActivity = opened::add,
                    onQuickStart = started::add,
                    onRouteBack = {},
                )
            }
        }
        keyboardInput.enter()
        val open = composeTestRule.onNodeWithText(item.name)
        open.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        open.awaitKeyboardFocus()
        open.performKeyInput { pressKey(Key.Tab) }
        val quick = composeTestRule.onNodeWithText(text(R.string.library_quick_start))
        quick.awaitKeyboardFocus()
        assertTrue(started.isEmpty())
        assertTrue(opened.isEmpty())
        assertTrue(actions.isEmpty())
        quick.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf(item.id), started)
        quick.performKeyInput { pressKey(Key.Tab) }
        val organize = composeTestRule.onNodeWithText(text(R.string.library_organize))
        organize.awaitKeyboardFocus()
        organize.performKeyInput { pressKey(Key.Enter) }
        assertTrue(opened.isEmpty())
        assertTrue(actions.isEmpty())
        open.performSemanticsAction(SemanticsActions.RequestFocus)
        open.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf((item.id as LibraryTemplateId.Activity).id), opened)
        assertEquals(listOf(item.id), started)
        val search = composeTestRule.onNode(hasSetTextAction())
        search.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        assertTrue(actions.isEmpty())
        search.performKeyInput { pressKey(Key.A) }
        assertEquals(listOf(LibraryAction.Search("a")), actions)
    }

    @Test
    fun survivingSearchFilterAndTemplateFocusRemainDuringUnrelatedRefreshes() {
        val item = trackable("Focus result", false)
        val preceding = trackable("Earlier result", false)
        var state by mutableStateOf(
            catalog(listOf(preceding, item)).copy(
                query = "result",
                search = LibraryLoad.Content(listOf(preceding, item)),
            ),
        )
        val actions = mutableListOf<LibraryAction>()
        var opens = 0
        composeTestRule.setContent {
            LifeTracingTheme { LibraryScreen(state, actions::add, onOpenActivity = { opens += 1 }) {} }
        }
        keyboardInput.enter()
        val search = composeTestRule.onNode(hasSetTextAction())
        search.performSemanticsAction(SemanticsActions.RequestFocus)
        state = state.copy(search = LibraryLoad.Loading, organization = LibraryLoad.Failure("unrelated"))
        search.awaitKeyboardFocus()
        state = state.copy(search = LibraryLoad.Content(listOf(preceding, item)))
        search.awaitKeyboardFocus()
        val filter = composeTestRule.onNodeWithText(text(R.string.library_filter_activities))
        filter.performSemanticsAction(SemanticsActions.RequestFocus)
        state =
            state.copy(
                filter = LibraryKindFilter.ACTIVITIES,
                organization = LibraryLoad.Content(LibraryOrganization(emptyList(), emptyList())),
            )
        filter.awaitKeyboardFocus()
        val result = composeTestRule.onNodeWithText(item.name)
        result.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        state = state.copy(search = LibraryLoad.Content(listOf(item.copy(shortComment = "Refreshed detail"))))
        result.awaitKeyboardFocus()
        result.performScrollTo()
        composeTestRule.onNodeWithText("Refreshed detail", useUnmergedTree = true).assertExists()
        assertTrue(actions.isEmpty())
        assertEquals(0, opens)
    }

    @Test
    fun removedResultsFoldersAndRetryRecoverToBackOnlyOnTheVisibleRoute() {
        val item = trackable("Removable", false)
        val folder = folder("target", "Focus folder")
        val baseline = catalog(listOf(item))
        var state by mutableStateOf(baseline)
        var visible by mutableStateOf(true)
        val actions = mutableListOf<LibraryAction>()
        var opens = 0
        var foregroundActivations = 0
        composeTestRule.setContent {
            LifeTracingTheme {
                Box {
                    LibraryScreen(state, actions::add, onOpenActivity = { opens += 1 }, isRouteVisible = visible) {}
                    if (!visible) {
                        TextButton(onClick = { foregroundActivations += 1 }) { Text("Foreground route control") }
                    }
                }
            }
        }
        keyboardInput.enter()
        val back = composeTestRule.onNodeWithText(text(R.string.library_back))
        val replacements =
            listOf(
                baseline.copy(filter = LibraryKindFilter.SEQUENCES),
                baseline.copy(query = "other", search = LibraryLoad.Content(emptyList())),
                baseline.copy(folderId = folder.id, browse = LibraryLoad.Loading),
                baseline.copy(browse = LibraryLoad.Failure("failure")),
            )
        for (replacement in replacements) {
            state = baseline
            val row = composeTestRule.onNodeWithText(item.name)
            row.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            row.awaitKeyboardFocus()
            state = replacement
            back.awaitKeyboardFocus()
        }
        val retry = composeTestRule.onNodeWithText(text(R.string.library_retry))
        retry.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        retry.awaitKeyboardFocus()
        state = baseline
        back.awaitKeyboardFocus()
        state =
            baseline.copy(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            null,
                            emptyList(),
                            emptyList(),
                            LibraryContents(listOf(folder), listOf(item), emptyList()),
                        ),
                    ),
            )
        composeTestRule
            .onNodeWithText(folder.name)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .awaitKeyboardFocus()
        state = baseline.copy(folderId = folder.id, browse = LibraryLoad.Loading)
        back.awaitKeyboardFocus()
        state = baseline
        val row = composeTestRule.onNodeWithText(item.name)
        row.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        row.awaitKeyboardFocus()
        visible = false
        val foreground = composeTestRule.onNodeWithText("Foreground route control")
        foreground.performSemanticsAction(SemanticsActions.RequestFocus)
        foreground.awaitKeyboardFocus()
        state = baseline.copy(browse = LibraryLoad.Loading)
        foreground.awaitKeyboardFocus()
        back.assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
        state = baseline
        foreground.awaitKeyboardFocus()
        visible = true
        back.awaitKeyboardFocus()
        assertTrue(actions.isEmpty())
        assertEquals(0, opens)
        assertEquals(0, foregroundActivations)
    }

    @Test
    fun englishRussianActionsReflowAndReserveTargetsAtCombinedScaleExtremes() {
        var locale by mutableStateOf(Locale.US)
        var small by mutableStateOf(false)
        val item =
            trackable("Long template name / Длинное название шаблона", false)
                .copy(shortComment = "Readable detail / Читаемое описание")
        val started = mutableListOf<LibraryTemplateId>()
        var opens = 0
        var commands = 0
        composeTestRule.setContent {
            val configuration =
                android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                    setLocale(locale)
                    fontScale = 2f
                }
            val context = composeTestRule.activity.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
                LocalDensity provides Density(density.density, configuration.fontScale),
            ) {
                LifeTracingAppearance(
                    AppearancePreferences(
                        interfaceScalePercent = if (small) 90 else 120,
                        textScalePercent = if (small) 90 else 130,
                    ),
                ) {
                    LibraryScreen(
                        catalog(listOf(item)),
                        { commands += 1 },
                        onOpenActivity = { opens += 1 },
                        onQuickStart = started::add,
                        onRouteBack = {},
                    )
                }
            }
        }
        keyboardInput.enter()
        for (language in listOf(Locale.US, Locale.forLanguageTag("ru"))) {
            for (minimum in listOf(false, true)) {
                composeTestRule.runOnIdle {
                    locale = language
                    small = minimum
                }

                fun label(id: Int): String {
                    val config = android.content.res.Configuration(composeTestRule.activity.resources.configuration)
                    config.setLocale(language)
                    return composeTestRule.activity.createConfigurationContext(config).getString(id)
                }
                val open = composeTestRule.onNodeWithText(item.name)
                val quick = composeTestRule.onNodeWithText(label(R.string.library_quick_start))
                val organize = composeTestRule.onNodeWithText(label(R.string.library_organize))
                val nodes = listOf(open, quick, organize)
                val minimumPixels = 48 * composeTestRule.activity.resources.displayMetrics.density
                nodes.forEach { node ->
                    node.performScrollTo().assertIsDisplayed()
                    val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                    assertTrue(bounds.height >= minimumPixels - 1)
                    assertTrue(bounds.width >= minimumPixels - 1)
                    assertTrue(bounds.left >= 0)
                    assertTrue(bounds.right <= composeTestRule.activity.resources.displayMetrics.widthPixels)
                }
                // Compare at a common scroll offset, after every individual target has been inspected.
                val bounds = nodes.map { it.fetchSemanticsNode().touchBoundsInRoot }
                for (a in bounds.indices) for (b in a + 1 until bounds.size) assertTrue(!bounds[a].overlaps(bounds[b]))
                quick.performSemanticsAction(SemanticsActions.RequestFocus)
                quick.awaitKeyboardFocus()
                quick.performKeyInput { pressKey(Key.Enter) }
                organize.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(0, opens)
                assertEquals(0, commands)
            }
        }
        assertEquals(List(4) { item.id }, started)
    }

    private fun SemanticsNodeInteraction.awaitKeyboardFocus() = apply { keyboardInput.awaitFocus(this) }

    private fun catalog(
        items: List<LibraryTrackable>,
        pinned: List<LibraryTrackable> = emptyList(),
    ) = LibraryPresentationState(
        browse =
            LibraryLoad.Content(
                LibraryBrowse(
                    null,
                    emptyList(),
                    pinned,
                    LibraryContents(
                        emptyList(),
                        items.filter { it.id is LibraryTemplateId.Activity },
                        items.filter { it.id is LibraryTemplateId.Sequence },
                    ),
                ),
            ),
        organization = LibraryLoad.Content(LibraryOrganization(emptyList(), emptyList())),
    )

    @Test
    fun headingsNativeSearchAndSelectedFiltersDispatchExactlyOnce() {
        val item = trackable("Discoverable", false)
        val actions = mutableListOf<LibraryAction>()
        var state by mutableStateOf(
            LibraryPresentationState(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            null,
                            emptyList(),
                            listOf(item),
                            LibraryContents(emptyList(), listOf(item), emptyList()),
                        ),
                    ),
            ),
        )
        composeTestRule.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }
        for (id in listOf(R.string.library_title, R.string.library_pinned, R.string.library_folders)) {
            composeTestRule
                .onNode(hasText(text(id)) and SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
        composeTestRule
            .onNodeWithContentDescription(text(R.string.library_location, text(R.string.library_root)))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeTestRule
            .onNode(hasText(text(R.string.library_all)) and hasClickAction().not())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val labels =
            listOf(R.string.library_filter_all, R.string.library_filter_activities, R.string.library_filter_sequences)
        LibraryKindFilter.entries.forEachIndexed { index, filter ->
            composeTestRule.onNode(hasText(text(labels[index])) and hasClickAction()).performClick()
            assertEquals(listOf(LibraryAction.SetFilter(filter)), actions)
            actions.clear()
            state = state.copy(filter = filter)
            labels.forEachIndexed { other, label ->
                val node = composeTestRule.onNode(hasText(text(label)) and hasClickAction())
                if (other == index) node.assertIsSelected() else node.assertIsNotSelected()
            }
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput("native")
        assertEquals(listOf(LibraryAction.Search("native")), actions)
    }

    @Test
    fun canonicalPinnedFoldersAndKindsRespectTheSelectedFilter() {
        val folder = folder("folder", "Projects")
        val activity = trackable("Catalog activity alpha", false)
        val sequence = trackable("Sequence", true)
        val archived = trackable("Archived", false, archived = true)
        val actions = mutableListOf<LibraryAction>()
        var state by mutableStateOf(
            LibraryPresentationState(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            null,
                            emptyList(),
                            listOf(sequence, activity),
                            LibraryContents(listOf(folder), listOf(activity, archived), listOf(sequence)),
                        ),
                    ),
            ),
        )
        composeTestRule.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }

        composeTestRule.onAllNodesWithText("Sequence")[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Catalog activity alpha")[0].assertIsDisplayed()
        composeTestRule.onNodeWithText("Archived").assertDoesNotExist()
        composeTestRule.onNodeWithText("Projects").performScrollTo().performClick()
        assertEquals(LibraryAction.OpenFolder(folder.id), actions.last())
        composeTestRule.onNodeWithText(text(R.string.library_filter_sequences)).performClick()
        assertEquals(LibraryAction.SetFilter(LibraryKindFilter.SEQUENCES), actions.last())

        state = state.copy(filter = LibraryKindFilter.SEQUENCES)
        composeTestRule.onAllNodesWithText("Sequence")[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Catalog activity alpha").assertCountEquals(0)
    }

    @Test
    fun nestedBreadcrumbAndGlobalSearchResultsStayReadable() {
        val root = folder("root", "Root")
        val nested = folder("nested", "Nested", root.id)
        val activity = trackable("Search activity", false)
        val sequence = trackable("Search sequence", true)
        val actions = mutableListOf<LibraryAction>()
        val state =
            LibraryPresentationState(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            nested.id,
                            listOf(root, nested),
                            emptyList(),
                            LibraryContents(emptyList(), emptyList(), emptyList()),
                        ),
                    ),
                query = "Search",
                search = LibraryLoad.Content(listOf(activity, sequence)),
            )
        composeTestRule.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }

        composeTestRule.onNodeWithText("Library / Root / Nested").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search activity").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search sequence").assertIsDisplayed()
    }

    @Test
    fun activityAndSequenceRowsOpenTheirEditorsWhileQuickStartRemainsIndependent() {
        val activity = trackable("Editable activity", false).copy(shortComment = "Ordinary row content")
        val sequence = trackable("Editable sequence", true)
        val actions = mutableListOf<LibraryAction>()
        val opened = mutableListOf<ActivityTemplateId>()
        val openedSequences = mutableListOf<SequenceTemplateId>()
        var createdActivities = 0
        var createdSequences = 0
        val quickStarts = mutableListOf<LibraryTemplateId>()
        composeTestRule.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    state =
                        LibraryPresentationState(
                            browse =
                                LibraryLoad.Content(
                                    LibraryBrowse(
                                        null,
                                        emptyList(),
                                        emptyList(),
                                        LibraryContents(emptyList(), listOf(activity), listOf(sequence)),
                                    ),
                                ),
                            organization = LibraryLoad.Content(LibraryOrganization(emptyList(), emptyList())),
                        ),
                    onAction = actions::add,
                    onCreateActivity = { createdActivities += 1 },
                    onCreateSequence = { createdSequences += 1 },
                    onOpenActivity = opened::add,
                    onOpenSequence = openedSequences::add,
                    onQuickStart = quickStarts::add,
                    onRouteBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(text(R.string.library_new_activity)).performClick()
        composeTestRule.onNodeWithText(text(R.string.library_new_sequence)).performClick()
        assertEquals(1, createdActivities)
        assertEquals(1, createdSequences)
        composeTestRule
            .onNodeWithText("Ordinary row content")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule
            .onNodeWithText("Editable sequence")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        assertEquals(listOf((sequence.id as LibraryTemplateId.Sequence).id), openedSequences)
        composeTestRule.onAllNodesWithText(text(R.string.library_quick_start))[0].performScrollTo().performClick()
        composeTestRule.onAllNodesWithText(text(R.string.library_quick_start))[1].performScrollTo().performClick()

        assertEquals(listOf(activity.id, sequence.id), quickStarts)
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        assertEquals(listOf((sequence.id as LibraryTemplateId.Sequence).id), openedSequences)
        composeTestRule.onAllNodesWithText(text(R.string.library_organize))[0].performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.library_pin)).performClick()
        assertEquals(LibraryAction.SetPinned(activity.id, true), actions.last())
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        assertEquals(listOf((sequence.id as LibraryTemplateId.Sequence).id), openedSequences)
        assertEquals(listOf(activity.id, sequence.id), quickStarts)
    }

    @Test
    fun quickStartIsAvailableFromPinnedFolderAndSearchProjections() {
        val pinned = trackable("Pinned start", false)
        val folder = folder("folder", "Folder")
        val inFolder = trackable("Folder start", true)
        val search = trackable("Search start", false)
        val quickStarts = mutableListOf<LibraryTemplateId>()
        var state by mutableStateOf(
            LibraryPresentationState(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            null,
                            emptyList(),
                            listOf(pinned),
                            LibraryContents(emptyList(), emptyList(), emptyList()),
                        ),
                    ),
            ),
        )
        composeTestRule.setContent {
            LifeTracingTheme {
                LibraryScreen(state, onAction = {}, onQuickStart = quickStarts::add, onRouteBack = {})
            }
        }

        composeTestRule.onNodeWithText(text(R.string.library_quick_start)).performClick()
        state =
            state.copy(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            folder.id,
                            listOf(folder),
                            emptyList(),
                            LibraryContents(emptyList(), emptyList(), listOf(inFolder)),
                        ),
                    ),
            )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(text(R.string.library_quick_start)).performClick()
        state = state.copy(query = "Search", search = LibraryLoad.Content(listOf(search)))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(text(R.string.library_quick_start)).performClick()

        assertEquals(listOf(pinned.id, inFolder.id, search.id), quickStarts)
    }

    @Test
    fun russianFiltersWrapWithinACompactPhoneWidthAndRemainActionable() {
        val russian = Locale.forLanguageTag("ru")
        val configuration =
            android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                setLocale(russian)
            }
        val context = composeTestRule.activity.createConfigurationContext(configuration)
        val actions = mutableListOf<LibraryAction>()
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
            ) {
                LifeTracingTheme {
                    Box(Modifier.width(320.dp)) {
                        LibraryScreen(
                            state =
                                LibraryPresentationState(
                                    browse =
                                        LibraryLoad.Content(
                                            LibraryBrowse(
                                                null,
                                                emptyList(),
                                                emptyList(),
                                                LibraryContents(emptyList(), emptyList(), emptyList()),
                                            ),
                                        ),
                                ),
                            onAction = actions::add,
                            onRouteBack = {},
                        )
                    }
                }
            }
        }

        val filters =
            listOf(
                context.getString(R.string.library_filter_all) to LibraryKindFilter.ALL,
                context.getString(R.string.library_filter_activities) to LibraryKindFilter.ACTIVITIES,
                context.getString(R.string.library_filter_sequences) to LibraryKindFilter.SEQUENCES,
            )
        val compactWidthPixels = 320 * context.resources.displayMetrics.density
        filters.forEach { (label, filter) ->
            val node = composeTestRule.onNodeWithText(label)
            node.assertIsDisplayed()
            assertTrue(node.fetchSemanticsNode().boundsInRoot.right <= compactWidthPixels)
            node.performClick()
            assertEquals(LibraryAction.SetFilter(filter), actions.last())
        }
    }

    @Test
    fun organizationLoadFailureIsVisibleAndRetryable() {
        val actions = mutableListOf<LibraryAction>()
        composeTestRule.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    state =
                        LibraryPresentationState(
                            browse =
                                LibraryLoad.Content(
                                    LibraryBrowse(
                                        null,
                                        emptyList(),
                                        emptyList(),
                                        LibraryContents(emptyList(), emptyList(), emptyList()),
                                    ),
                                ),
                            organization = LibraryLoad.Failure("failed"),
                        ),
                    onAction = actions::add,
                    onRouteBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(text(R.string.library_organization_failure)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.library_retry)).performClick()

        assertEquals(LibraryAction.Retry, actions.last())
    }

    @Test
    fun englishOrganizationControlsRemainActionableAtCompactWidth() = verifyCompactOrganization(Locale.ENGLISH)

    @Test
    fun russianOrganizationControlsRemainActionableAtCompactWidth() {
        verifyCompactOrganization(Locale.forLanguageTag("ru"))
    }

    @Test
    fun englishFolderOrganizationControlsRemainActionableAtCompactWidth() {
        verifyCompactFolderOrganization(Locale.ENGLISH)
    }

    @Test
    fun russianFolderOrganizationControlsRemainActionableAtCompactWidth() {
        verifyCompactFolderOrganization(Locale.forLanguageTag("ru"))
    }

    @Test
    fun englishDestructiveLifecycleIsExplicitAtCompactWidth() = verifyCompactDestructiveLifecycle(Locale.ENGLISH)

    @Test
    fun russianDestructiveLifecycleIsExplicitAtCompactWidth() {
        verifyCompactDestructiveLifecycle(Locale.forLanguageTag("ru"))
    }

    private fun verifyCompactOrganization(locale: Locale) {
        val configuration =
            android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                setLocale(locale)
            }
        val context = composeTestRule.activity.createConfigurationContext(configuration)
        val source = folder("source", "Source")
        val destination = folder("destination", "Destination")
        val activity = trackable("Compact activity", false).copy(pinnedRank = 0, shortComment = "Compact body")
        val sequence = trackable("Compact sequence", true).copy(pinnedRank = 1)
        val unpinned = trackable("Unpinned activity", false)
        val actions = mutableListOf<LibraryAction>()
        val opened = mutableListOf<ActivityTemplateId>()
        val openedSequences = mutableListOf<SequenceTemplateId>()
        val quickStarts = mutableListOf<LibraryTemplateId>()
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
            ) {
                LifeTracingTheme {
                    Box(Modifier.width(320.dp)) {
                        LibraryScreen(
                            state =
                                LibraryPresentationState(
                                    browse =
                                        LibraryLoad.Content(
                                            LibraryBrowse(
                                                null,
                                                emptyList(),
                                                listOf(activity, sequence),
                                                LibraryContents(listOf(source), listOf(unpinned), emptyList()),
                                            ),
                                        ),
                                    organization =
                                        LibraryLoad.Content(
                                            LibraryOrganization(listOf(source, destination), emptyList()),
                                        ),
                                ),
                            onAction = actions::add,
                            onOpenActivity = opened::add,
                            onOpenSequence = openedSequences::add,
                            onQuickStart = quickStarts::add,
                            onRouteBack = {},
                        )
                    }
                }
            }
        }
        val widthPixels = 320 * context.resources.displayMetrics.density
        val organize = context.getString(R.string.library_organize)
        val unpin = context.getString(R.string.library_unpin)
        val moveRoot = context.getString(R.string.library_move_to_root)
        val moveDestination = context.getString(R.string.library_move_to_folder, destination.name)
        val quickStart = context.getString(R.string.library_quick_start)

        clickInside(composeTestRule.onNodeWithText("Compact body"), widthPixels)
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        clickInside(composeTestRule.onAllNodesWithText(quickStart)[0], widthPixels)
        clickInside(composeTestRule.onAllNodesWithText(quickStart)[1], widthPixels)
        assertEquals(listOf(activity.id, sequence.id), quickStarts)
        clickInside(composeTestRule.onAllNodesWithText(organize)[0], widthPixels)
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        clickInside(composeTestRule.onAllNodesWithText(unpin)[0], widthPixels)
        assertEquals(LibraryAction.SetPinned(activity.id, false), actions.last())
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        clickInside(composeTestRule.onAllNodesWithText(moveRoot)[0], widthPixels)
        assertEquals(LibraryAction.MoveTemplate(activity.id, null), actions.last())
        clickInside(composeTestRule.onAllNodesWithText(moveDestination)[0], widthPixels)
        assertEquals(LibraryAction.MoveTemplate(activity.id, destination.id), actions.last())

        clickInside(composeTestRule.onNodeWithText(sequence.name), widthPixels)
        assertEquals(listOf((sequence.id as LibraryTemplateId.Sequence).id), openedSequences)
        clickInside(composeTestRule.onAllNodesWithText(organize)[1], widthPixels)
        clickInside(composeTestRule.onAllNodesWithText(unpin)[1], widthPixels)
        assertEquals(LibraryAction.SetPinned(sequence.id, false), actions.last())

        clickInside(composeTestRule.onNodeWithText(context.getString(R.string.library_reorder_pinned)), widthPixels)
        clickInside(
            composeTestRule.onNode(hasText(context.getString(R.string.library_move_down)) and isEnabled()),
            widthPixels,
        )
        assertEquals(LibraryAction.ReorderPinned(listOf(sequence.id, activity.id)), actions.last())
        clickInside(
            composeTestRule.onNode(hasText(context.getString(R.string.library_move_up)) and isEnabled()),
            widthPixels,
        )
        assertEquals(LibraryAction.ReorderPinned(listOf(sequence.id, activity.id)), actions.last())

        clickInside(composeTestRule.onAllNodesWithText(organize)[3], widthPixels)
        clickInside(composeTestRule.onNodeWithText(context.getString(R.string.library_pin)), widthPixels)
        assertEquals(LibraryAction.SetPinned(unpinned.id, true), actions.last())
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
    }

    private fun verifyCompactFolderOrganization(locale: Locale) {
        val configuration =
            android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                setLocale(locale)
            }
        val context = composeTestRule.activity.createConfigurationContext(configuration)
        val source = folder("folder-source", "Source")
        val destination = folder("folder-destination", "Destination")
        val actions = mutableListOf<LibraryAction>()
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
            ) {
                LifeTracingTheme {
                    Box(Modifier.width(320.dp)) {
                        LibraryScreen(
                            state =
                                LibraryPresentationState(
                                    browse =
                                        LibraryLoad.Content(
                                            LibraryBrowse(
                                                null,
                                                emptyList(),
                                                emptyList(),
                                                LibraryContents(listOf(source), emptyList(), emptyList()),
                                            ),
                                        ),
                                    organization =
                                        LibraryLoad.Content(
                                            LibraryOrganization(listOf(source, destination), emptyList()),
                                        ),
                                ),
                            onAction = actions::add,
                            onRouteBack = {},
                        )
                    }
                }
            }
        }
        val widthPixels = 320 * context.resources.displayMetrics.density
        clickInside(composeTestRule.onNodeWithText(context.getString(R.string.library_organize)), widthPixels)
        assertActionInside(
            composeTestRule.onNodeWithText(context.getString(R.string.library_rename_folder)),
            widthPixels,
        )
        clickInside(composeTestRule.onNodeWithText(context.getString(R.string.library_move_to_root)), widthPixels)
        assertEquals(LibraryAction.MoveFolder(source.id, null), actions.last())
        clickInside(
            composeTestRule.onNodeWithText(
                context.getString(R.string.library_move_to_folder, destination.name),
            ),
            widthPixels,
        )
        assertEquals(LibraryAction.MoveFolder(source.id, destination.id), actions.last())
    }

    private fun verifyCompactDestructiveLifecycle(locale: Locale) {
        val configuration =
            android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                setLocale(locale)
            }
        val context = composeTestRule.activity.createConfigurationContext(configuration)
        val source = folder("delete-source", "Delete source")
        val destination = folder("delete-destination", "Delete destination")
        val activity = trackable("Delete activity", false)
        val actions = mutableListOf<LibraryAction>()
        var state by mutableStateOf(
            LibraryPresentationState(
                browse =
                    LibraryLoad.Content(
                        LibraryBrowse(
                            null,
                            emptyList(),
                            emptyList(),
                            LibraryContents(listOf(source), listOf(activity), emptyList()),
                        ),
                    ),
                organization = LibraryLoad.Content(LibraryOrganization(listOf(source, destination), emptyList())),
            ),
        )
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
            ) {
                LifeTracingTheme {
                    Box(Modifier.width(320.dp)) {
                        LibraryScreen(state, actions::add, onRouteBack = {})
                    }
                }
            }
        }
        val widthPixels = 320 * context.resources.displayMetrics.density
        val organize = context.getString(R.string.library_organize)
        val delete = context.getString(R.string.library_delete)
        val dialogContext = context

        clickInside(composeTestRule.onAllNodesWithText(organize)[1], widthPixels)
        clickInside(composeTestRule.onNodeWithText(delete), widthPixels)
        composeTestRule
            .onNodeWithText(dialogContext.getString(R.string.library_archive_template_title))
            .assertIsDisplayed()
        clickDialogInside(
            composeTestRule.onNodeWithText(dialogContext.getString(R.string.library_archive)),
            widthPixels,
        )
        assertEquals(LibraryAction.ArchiveTemplate(activity.id), actions.last())

        clickInside(composeTestRule.onAllNodesWithText(organize)[0], widthPixels)
        clickInside(composeTestRule.onAllNodesWithText(delete)[0], widthPixels)
        assertEquals(LibraryAction.RequestFolderDeletion(source), actions.last())
        state =
            state.copy(
                folderDeletion =
                    LibraryFolderDeletion(
                        source,
                        LibraryLoad.Content(LibraryFolderDeletionOptions(true, listOf(destination))),
                    ),
            )
        composeTestRule
            .onNodeWithText(dialogContext.getString(R.string.library_delete_empty_folder_message, source.name))
            .assertIsDisplayed()
        clickDialogInside(
            composeTestRule.onNodeWithText(dialogContext.getString(R.string.library_delete_folder)),
            widthPixels,
        )
        assertEquals(LibraryAction.DeleteEmptyFolder(source.id), actions.last())

        state =
            state.copy(
                folderDeletion =
                    LibraryFolderDeletion(
                        source,
                        LibraryLoad.Content(LibraryFolderDeletionOptions(false, listOf(destination))),
                    ),
            )
        clickDialogInside(
            composeTestRule.onNodeWithText(dialogContext.getString(R.string.library_move_contents)),
            widthPixels,
        )
        clickDialogInside(composeTestRule.onNodeWithText(destination.name), widthPixels)
        assertEquals(LibraryAction.DeleteFolderMovingContents(source.id, destination.id), actions.last())

        state = state.copy(folderDeletion = null)
        composeTestRule.waitForIdle()
        state =
            state.copy(
                folderDeletion =
                    LibraryFolderDeletion(
                        source,
                        LibraryLoad.Content(LibraryFolderDeletionOptions(false, listOf(destination))),
                    ),
            )
        val countBeforeDisposition = actions.size
        clickDialogInside(
            composeTestRule.onNodeWithText(dialogContext.getString(R.string.library_delete_contents)),
            widthPixels,
        )
        assertEquals(countBeforeDisposition, actions.size)
        composeTestRule
            .onNodeWithText(dialogContext.getString(R.string.library_delete_contents_title))
            .assertIsDisplayed()
        clickDialogInside(
            composeTestRule.onNodeWithText(dialogContext.getString(R.string.library_delete_contents)),
            widthPixels,
        )
        assertEquals(LibraryAction.DeleteFolderAndArchiveContents(source.id), actions.last())
    }

    private fun clickInside(
        node: SemanticsNodeInteraction,
        widthPixels: Float,
    ) {
        assertActionInside(node, widthPixels)
        node.performClick()
    }

    private fun clickDialogInside(
        node: SemanticsNodeInteraction,
        widthPixels: Float,
    ) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 0f)
        assertTrue(bounds.right <= widthPixels)
        assertTrue(node.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        node.performClick()
    }

    private fun assertActionInside(
        node: SemanticsNodeInteraction,
        widthPixels: Float,
    ) {
        node.performScrollTo()
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 0f)
        assertTrue(bounds.right <= widthPixels)
        assertTrue(node.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
    }

    private fun folder(
        id: String,
        name: String,
        parent: FolderId? = null,
    ) = Folder(FolderId(id), name, parent, Instant.EPOCH, Instant.EPOCH)

    private fun trackable(
        name: String,
        sequence: Boolean,
        archived: Boolean = false,
    ) = LibraryTrackable(
        if (sequence) {
            LibraryTemplateId.Sequence(
                SequenceTemplateId(name),
            )
        } else {
            LibraryTemplateId.Activity(ActivityTemplateId(name))
        },
        name,
        null,
        null,
        emptySet(),
        null,
        null,
        Instant.EPOCH.takeIf { archived },
    )

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = composeTestRule.activity.getString(id, *args)
}
