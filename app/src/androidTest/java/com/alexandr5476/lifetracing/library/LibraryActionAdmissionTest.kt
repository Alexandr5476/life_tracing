@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Exercises retained native actions, including the interval before recomposition. */
class LibraryActionAdmissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    private val activity = item(LibraryTemplateId.Activity(ActivityTemplateId("same-id")), 0)
    private val sequence = item(LibraryTemplateId.Sequence(SequenceTemplateId("same-id")), 1)
    private val last = item(LibraryTemplateId.Activity(ActivityTemplateId("last")), 2)
    private val folder = Folder(FolderId("folder"), "Same", null, Instant.EPOCH, Instant.EPOCH)

    @Test
    fun independentCommandsRemainKeyboardAndAccessibilityActionableWithoutOrganizationMetadata() {
        var state by mutableStateOf(catalog())
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }
        keyboard.enter()
        for (metadata in listOf(LibraryLoad.Loading, LibraryLoad.Failure("metadata"))) {
            for (nested in listOf(false, true)) {
                compose.runOnIdle {
                    state =
                        catalog().copy(
                            folderId = folder.id.takeIf { nested },
                            browse =
                                LibraryLoad.Content(
                                    browse().copy(
                                        folderId = folder.id.takeIf { nested },
                                        path = if (nested) listOf(folder) else emptyList(),
                                    ),
                                ),
                            organization = metadata,
                            filter = LibraryKindFilter.ACTIVITIES,
                        )
                }
                val create =
                    text(
                        R.string.library_create_folder_here,
                        if (nested) "Library / Same" else "Library",
                    )
                val entry = compose.onNodeWithContentDescription(create).performScrollTo().assertIsEnabled()
                entry.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.awaitFocus(entry)
                keyboard.sendKey(entry, android.view.KeyEvent.KEYCODE_ENTER)
                compose
                    .onNodeWithContentDescription(text(R.string.library_name_context, create))
                    .performTextInput(" New ")
                val confirm = retained(text(R.string.library_confirm_context, text(R.string.library_create), create))
                compose.runOnIdle {
                    confirm()
                    confirm()
                }
                val ordering = compose.onNodeWithText(text(R.string.library_reorder_pinned)).performScrollTo()
                ordering.performSemanticsAction(SemanticsActions.OnClick)
                val context = context(sequence, R.string.library_pinned, 2)
                val up =
                    compose
                        .onNodeWithContentDescription(text(R.string.library_pinned_move_up, context))
                        .performScrollTo()
                        .assertIsEnabled()
                up.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.awaitFocus(up)
                keyboard.sendKey(up, android.view.KeyEvent.KEYCODE_ENTER)
                val down = retained(text(R.string.library_pinned_move_down, context))
                compose.runOnIdle {
                    down()
                    down()
                }
                assertEquals(
                    listOf(
                        LibraryAction.CreateFolder("New"),
                        LibraryAction.ReorderPinned(listOf(sequence.id, activity.id, last.id)),
                        LibraryAction.ReorderPinned(listOf(activity.id, last.id, sequence.id)),
                    ),
                    actions.takeLast(3),
                )
                ordering.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            }
        }
        assertEquals(12, actions.size)
    }

    @Test
    fun independentCommandsRejectInvalidBrowseHiddenMutationDisposedAndStaleContexts() {
        val baseline = catalog().copy(organization = LibraryLoad.Failure("metadata"))
        var state by mutableStateOf(baseline)
        var visible by mutableStateOf(true)
        var attached by mutableStateOf(true)
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            if (attached) {
                LifeTracingTheme {
                    LibraryScreen(
                        state,
                        actions::add,
                        currentState = { state },
                        currentRouteVisible = { visible },
                        isRouteVisible = visible,
                    ) {}
                }
            }
        }
        val invalid =
            listOf(
                baseline.copy(browse = LibraryLoad.Loading),
                baseline.copy(browse = LibraryLoad.Failure("browse")),
                baseline.copy(isMutating = true),
                baseline.copy(folderId = folder.id),
                baseline.copy(browse = LibraryLoad.Content(browse(listOf(sequence, activity, last)))),
            )
        for (replacement in invalid + baseline) {
            compose.runOnIdle {
                state = baseline
                visible = true
            }
            val create = text(R.string.library_create_folder_here, "Library")
            val open = retained(create)
            compose
                .onNodeWithText(text(R.string.library_reorder_pinned))
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            val context = context(sequence, R.string.library_pinned, 2)
            val up = retained(text(R.string.library_pinned_move_up, context))
            val down = retained(text(R.string.library_pinned_move_down, context))
            compose.runOnIdle {
                state = replacement
                if (replacement === baseline) visible = false
                open()
                up()
                down()
                assertTrue("Live context must reject before recomposition", actions.isEmpty())
            }
            compose.waitForIdle()
            if (replacement.isMutating) {
                compose.onNodeWithContentDescription(create).assertIsNotEnabled()
                compose
                    .onNodeWithContentDescription(
                        text(R.string.library_pinned_move_up, context),
                    ).assertIsNotEnabled()
                compose
                    .onNodeWithContentDescription(
                        text(R.string.library_pinned_move_down, context),
                    ).assertIsNotEnabled()
            }
            if (replacement.browse !is LibraryLoad.Content) {
                compose.onNodeWithContentDescription(create).assertDoesNotExist()
            }
            compose.runOnIdle {
                open()
                up()
                down()
            }
            assertTrue("Retained commands must not forward to the replacement presentation", actions.isEmpty())
            compose.runOnIdle {
                state = baseline
                visible = true
            }
            // Loading/failure removes this panel; recreate a known collapsed instance each iteration.
            compose.runOnIdle { attached = false }
            compose.waitForIdle()
            compose.runOnIdle { attached = true }
        }
        val create = text(R.string.library_create_folder_here, "Library")
        val open = retained(create)
        compose.runOnIdle { open() }
        val confirmDescription = text(R.string.library_confirm_context, text(R.string.library_create), create)
        val blankConfirm = retained(confirmDescription)
        compose.onNodeWithContentDescription(text(R.string.library_name_context, create)).performTextInput("Blocked")
        compose.runOnIdle { blankConfirm() }
        assertTrue("An earlier invalid draft must not submit the current valid name", actions.isEmpty())
        val confirm = retained(confirmDescription)
        compose.runOnIdle {
            state = baseline.copy(isMutating = true)
            confirm()
        }
        compose.runOnIdle { attached = false }
        compose.waitForIdle()
        compose.runOnIdle {
            open()
            confirm()
        }
        assertTrue(actions.isEmpty())
    }

    @Test
    fun metadataDependentControlsRetireOnLoadingAndFailure() {
        var state by mutableStateOf(catalog(emptyList()))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            LifeTracingTheme { LibraryScreen(state, actions::add, currentState = { state }) {} }
        }
        val context = context(activity, R.string.library_all, 1)
        compose
            .onNodeWithContentDescription(text(R.string.library_organize_template, context))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        for (metadata in listOf(LibraryLoad.Loading, LibraryLoad.Failure("metadata"))) {
            compose.runOnIdle { state = catalog(emptyList()) }
            val pin = retained(text(R.string.library_unpin_target, context))
            val move = retained(text(R.string.library_move_target_root, context))
            val tag = retained(text(R.string.library_create_tag_target, context))
            compose.runOnIdle {
                state = state.copy(organization = metadata)
                pin()
                move()
                tag()
            }
            compose.onNodeWithContentDescription(text(R.string.library_unpin_target, context)).assertDoesNotExist()
            compose.runOnIdle {
                pin()
                move()
                tag()
            }
        }
        compose.runOnIdle { state = catalog(emptyList()) }
        val pin = retained(text(R.string.library_unpin_target, context))
        val move = retained(text(R.string.library_move_target_root, context))
        val tag = retained(text(R.string.library_create_tag_target, context))
        compose.runOnIdle { state = state.copy(mutationSuccessGeneration = 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            pin()
            move()
            tag()
        }
        assertTrue("A surviving organization control must not retarget a retained action", actions.isEmpty())
    }

    @Test
    fun retainedOpenAndQuickStartRejectRemovedFilteredAndReplacedSearchRows() {
        val baseline = catalog(emptyList())
        var state by mutableStateOf(baseline)
        val opened = mutableListOf<LibraryTemplateId>()
        val quick = mutableListOf<LibraryTemplateId>()
        compose.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    state,
                    {},
                    onOpenActivity = { opened.add(LibraryTemplateId.Activity(it)) },
                    onOpenSequence = { opened.add(LibraryTemplateId.Sequence(it)) },
                    onQuickStart = quick::add,
                    currentState = { state },
                ) {}
            }
        }
        val replacements =
            listOf(
                baseline.copy(
                    browse =
                        LibraryLoad.Content(
                            browse(emptyList()).copy(
                                contents = LibraryContents(emptyList(), emptyList(), listOf(sequence)),
                            ),
                        ),
                ),
                baseline.copy(filter = LibraryKindFilter.SEQUENCES),
                baseline.copy(query = "other", search = LibraryLoad.Content(listOf(sequence))),
                baseline.copy(browse = LibraryLoad.Loading),
                baseline.copy(browse = LibraryLoad.Failure("browse")),
            )
        for (replacement in replacements) {
            compose.runOnIdle { state = baseline }
            val context = context(activity, R.string.library_all, 1)
            val open = retained(text(R.string.library_open_template, context))
            val start = retained(text(R.string.library_quick_start_template, context))
            compose.runOnIdle {
                state = replacement
                open()
                start()
                assertTrue("Open must reject before recomposition", opened.isEmpty())
                assertTrue("Quick Start must reject before recomposition", quick.isEmpty())
            }
            compose.waitForIdle()
            compose.runOnIdle {
                open()
                start()
            }
        }
        compose.runOnIdle {
            state = baseline.copy(query = "Same", search = LibraryLoad.Content(listOf(activity, sequence)))
        }
        val context = context(activity, R.string.library_search_results, 1)
        val open = retained(text(R.string.library_open_template, context))
        val start = retained(text(R.string.library_quick_start_template, context))
        compose.runOnIdle {
            state = state.copy(query = "Same revised", search = LibraryLoad.Content(listOf(activity, sequence)))
            open()
            start()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            open()
            start()
        }
        assertTrue(opened.isEmpty())
        assertTrue(quick.isEmpty())
        for ((index, item) in listOf(activity, sequence).withIndex()) {
            val currentContext = context(item, R.string.library_search_results, index + 1)
            val validOpen = retained(text(R.string.library_open_template, currentContext))
            val validStart = retained(text(R.string.library_quick_start_template, currentContext))
            compose.runOnIdle {
                validOpen()
                validOpen()
                validStart()
                validStart()
            }
        }
        assertEquals(listOf(activity.id, sequence.id), opened)
        assertEquals(listOf(activity.id, sequence.id), quick)
    }

    @Test
    fun retainedNavigationRejectsHiddenAndDisposedRoutesAndFolderContextChanges() {
        val baseline = catalog(emptyList())
        var state by mutableStateOf(baseline)
        var visible by mutableStateOf(true)
        var attached by mutableStateOf(true)
        val actions = mutableListOf<LibraryAction>()
        var navigations = 0
        compose.setContent {
            if (attached) {
                LifeTracingTheme {
                    LibraryScreen(
                        state,
                        actions::add,
                        onCreateActivity = { navigations++ },
                        onCreateSequence = { navigations++ },
                        onOpenActivity = { navigations++ },
                        onQuickStart = { navigations++ },
                        currentState = { state },
                        currentRouteVisible = { visible },
                        isRouteVisible = visible,
                        onRouteBack = { navigations++ },
                    )
                }
            }
        }

        fun callbacks(): List<() -> Boolean> {
            val context = context(activity, R.string.library_all, 1)
            return listOf(
                retained(text(R.string.library_open_template, context)),
                retained(text(R.string.library_quick_start_template, context)),
                retainedText(R.string.library_new_activity),
                retainedText(R.string.library_new_sequence),
                retained(text(R.string.library_open_folder, folder.name, 1)),
                retainedText(R.string.library_back),
            )
        }
        val hidden = callbacks()
        compose.runOnIdle {
            visible = false
            hidden.forEach { it() }
        }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        compose.runOnIdle { hidden.forEach { it() } }
        assertEquals("Returning to Library must not revive retained navigation", 0, navigations)
        assertTrue(actions.isEmpty())
        val moved = callbacks()
        compose.runOnIdle {
            state = baseline.copy(folderId = folder.id)
            moved.forEach { it() }
        }
        compose.runOnIdle { state = baseline }
        val removedFolder = callbacks()
        compose.runOnIdle {
            state =
                baseline.copy(
                    browse =
                        LibraryLoad.Content(
                            browse(emptyList()).copy(
                                contents = LibraryContents(emptyList(), listOf(activity), listOf(sequence)),
                            ),
                        ),
                )
            removedFolder.forEach { it() }
        }
        compose.waitForIdle()
        compose.runOnIdle { removedFolder.forEach { it() } }
        assertEquals("Catalog refresh must not retarget retained navigation", 0, navigations)
        assertTrue(actions.isEmpty())
        compose.runOnIdle { state = baseline }
        val disposed = callbacks()
        compose.runOnIdle { attached = false }
        compose.waitForIdle()
        compose.runOnIdle { disposed.forEach { it() } }
        assertEquals(0, navigations)
        assertTrue(actions.isEmpty())
        compose.runOnIdle { attached = true }
        val valid = callbacks()
        compose.runOnIdle {
            valid.forEach {
                it()
                it()
            }
        }
        assertEquals(5, navigations)
        assertEquals(listOf(LibraryAction.OpenFolder(folder.id)), actions)
    }

    private fun retained(description: String): () -> Boolean =
        compose.onNodeWithContentDescription(description).let { node ->
            if (generateSequence(node.fetchSemanticsNode().parent) { it.parent }
                    .any { it.config.contains(SemanticsActions.ScrollBy) }
            ) {
                node.performScrollTo()
            }
            node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        }

    private fun retainedText(id: Int): () -> Boolean =
        compose
            .onNodeWithText(text(id))
            .performScrollTo()
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action!!

    private fun context(
        item: LibraryTrackable,
        section: Int,
        position: Int,
    ) = text(
        R.string.library_template_context,
        text(if (item.id is LibraryTemplateId.Activity) R.string.library_activity else R.string.library_sequence),
        item.name,
        text(section),
        position,
    )

    private fun text(
        id: Int,
        vararg args: Any,
    ) = compose.activity.getString(id, *args)

    private fun catalog(pinned: List<LibraryTrackable> = listOf(activity, sequence, last)) =
        LibraryPresentationState(
            browse = LibraryLoad.Content(browse(pinned)),
            organization = LibraryLoad.Content(LibraryOrganization(listOf(folder), emptyList())),
        )

    private fun browse(pinned: List<LibraryTrackable> = listOf(activity, sequence, last)) =
        LibraryBrowse(
            null,
            emptyList(),
            pinned,
            LibraryContents(listOf(folder), listOf(activity), listOf(sequence)),
        )

    private fun item(
        id: LibraryTemplateId,
        rank: Int,
    ) = LibraryTrackable(id, "Same", null, null, emptySet(), rank, null, null)
}
