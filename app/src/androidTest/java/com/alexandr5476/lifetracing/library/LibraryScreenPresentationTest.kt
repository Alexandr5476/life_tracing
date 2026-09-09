package com.alexandr5476.lifetracing.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

class LibraryScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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
    fun activityRowsOpenTheEditorWhileSequenceRowsRemainNonEditorActions() {
        val activity = trackable("Editable activity", false).copy(shortComment = "Ordinary row content")
        val sequence = trackable("Sequence without editor", true)
        val actions = mutableListOf<LibraryAction>()
        val opened = mutableListOf<ActivityTemplateId>()
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
                    onOpenActivity = opened::add,
                    onQuickStart = quickStarts::add,
                    onRouteBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Ordinary row content").performClick()

        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        assertFalse(
            composeTestRule
                .onNodeWithText("Sequence without editor")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnClick),
        )
        composeTestRule.onAllNodesWithText(text(R.string.library_quick_start))[0].performClick()
        composeTestRule.onAllNodesWithText(text(R.string.library_quick_start))[1].performClick()

        assertEquals(listOf(activity.id, sequence.id), quickStarts)
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        composeTestRule.onAllNodesWithText(text(R.string.library_organize))[0].performClick()
        composeTestRule.onNodeWithText(text(R.string.library_pin)).performClick()
        assertEquals(LibraryAction.SetPinned(activity.id, true), actions.last())
        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
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

        assertFalse(
            composeTestRule
                .onNodeWithText(sequence.name)
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnClick),
        )
        clickInside(composeTestRule.onAllNodesWithText(organize)[1], widthPixels)
        clickInside(composeTestRule.onAllNodesWithText(unpin)[1], widthPixels)
        assertEquals(LibraryAction.SetPinned(sequence.id, false), actions.last())

        clickInside(composeTestRule.onNodeWithText(context.getString(R.string.library_reorder_pinned)), widthPixels)
        clickInside(composeTestRule.onNodeWithText(context.getString(R.string.library_move_down)), widthPixels)
        assertEquals(LibraryAction.ReorderPinned(listOf(sequence.id, activity.id)), actions.last())
        clickInside(composeTestRule.onNodeWithText(context.getString(R.string.library_move_up)), widthPixels)
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
        val dialogContext = composeTestRule.activity

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

    private fun text(id: Int): String = composeTestRule.activity.getString(id)
}
