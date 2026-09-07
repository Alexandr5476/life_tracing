package com.alexandr5476.lifetracing.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Rule
import org.junit.Test
import java.time.Instant

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
