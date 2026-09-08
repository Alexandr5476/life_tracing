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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeTestRule.onNodeWithText("Projects").performClick()
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
        val activity = trackable("Editable activity", false)
        val sequence = trackable("Sequence without editor", true)
        val opened = mutableListOf<ActivityTemplateId>()
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
                        ),
                    onAction = {},
                    onOpenActivity = opened::add,
                    onRouteBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Editable activity").performClick()

        assertEquals(listOf((activity.id as LibraryTemplateId.Activity).id), opened)
        assertFalse(
            composeTestRule
                .onNodeWithText("Sequence without editor")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnClick),
        )
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
