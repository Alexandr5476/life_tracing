@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
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
import java.util.Locale

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LibraryDestructivePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun duplicateArchiveTargetsRequireConfirmationAndDispatchEachTypedIdOnlyOnce() {
        val items = listOf(item("a"), item("b"), item("s", true))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { screen(catalog(items), actions::add) }
        keyboard.enter()
        for ((index, item) in items.withIndex()) {
            val context = templateContext(item, index + 1)
            click(text(R.string.library_organize_template, context))
            val trigger = text(R.string.library_archive_target, context)
            click(trigger)
            compose
                .onNodeWithText(text(R.string.library_archive_template_title))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithText(text(R.string.library_cancel)).assertIsFocused()
            assertEquals(index, actions.size)
            cancel()
            compose.onNodeWithContentDescription(trigger).assertIsFocused()
            click(trigger)
            dismissDialogWithSystemBack()
            assertEquals(index, actions.size)
            click(trigger)
            val confirm = compose.onNodeWithContentDescription(text(R.string.library_archive_confirm, context))
            val retained = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            confirm.enter()
            compose.runOnIdle {
                retained()
                retained()
            }
            assertEquals(LibraryAction.ArchiveTemplate(item.id), actions.last())
            assertEquals(index + 1, actions.size)
        }
    }

    @Test
    fun archiveBusyRemovalFailureAndRouteExitCannotReplayAConfirmation() {
        val item = item("a")
        var state by mutableStateOf(catalog(listOf(item)))
        var visible by mutableStateOf(true)
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            LifeTracingTheme {
                LibraryScreen(state, actions::add, isRouteVisible = visible, currentRouteVisible = { visible }) {}
            }
        }
        keyboard.enter()
        val context = templateContext(item, 1)
        val trigger = text(R.string.library_archive_target, context)
        click(text(R.string.library_organize_template, context))
        click(trigger)
        val confirm = compose.onNodeWithContentDescription(text(R.string.library_archive_confirm, context))
        val old = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        state = state.copy(isMutating = true)
        confirm.assertIsNotEnabled()
        compose.runOnIdle { old() }
        cancel()
        state = state.copy(isMutating = false, mutationFailure = "rejected")
        compose.onNodeWithText(text(R.string.library_mutation_failure)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_organization_updated)).assertDoesNotExist()
        click(trigger)
        val refreshed = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        state = state.copy(browseRequestId = state.browseRequestId + 1)
        compose.onNodeWithText(text(R.string.library_archive_template_title)).assertDoesNotExist()
        compose.runOnIdle { refreshed() }
        click(trigger)
        val background = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            visible = false
            background()
        }
        compose.waitForIdle()
        visible = true
        compose.onNodeWithText(text(R.string.library_archive_template_title)).assertDoesNotExist()
        click(trigger)
        val removed = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        state = catalog(emptyList())
        compose.waitForIdle()
        compose.runOnIdle { removed() }
        compose.onNodeWithText(text(R.string.library_back)).assertIsFocused()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun inspectionLoadingFailureRetryAndDismissExposeContextWithoutDeleteAdmission() {
        val source = folder("source", "Same")
        var state by mutableStateOf(
            catalog(folders = listOf(source)).copy(
                folderDeletion = LibraryFolderDeletion(source, requestId = 1),
            ),
        )
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { screen(state, actions::add) }
        keyboard.enter(compose.onNodeWithText(text(R.string.library_cancel)))
        compose.onNodeWithText(text(R.string.library_delete_folder_inspecting)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_delete_folder)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_cancel)).assertIsFocused()
        state = state.copy(folderDeletion = LibraryFolderDeletion(source, LibraryLoad.Failure("Offline"), 1))
        compose.onNodeWithText(text(R.string.library_inspection_error, "Offline")).assertIsDisplayed()
        val retry =
            compose.onNodeWithContentDescription(
                text(R.string.library_retry_inspection, folderContext(source, 1)),
            )
        val stale = retry.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        retry.enter()
        compose.runOnIdle { stale() }
        assertEquals(listOf(LibraryAction.RequestFolderDeletion(source)), actions)
        state = state.copy(folderDeletion = LibraryFolderDeletion(source, requestId = 2))
        dismissDialogWithSystemBack()
        assertEquals(LibraryAction.DismissFolderDeletion, actions.last())
        compose.runOnIdle { stale() }
        assertEquals(2, actions.size)
    }

    @Test
    fun emptyFolderConfirmationIsOneShotAndRemovedInvokerRecoversToBack() {
        val source = folder("source", "Same")
        var state by mutableStateOf(catalog(folders = listOf(source)))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { screen(state, actions::add) }
        keyboard.enter()
        click(text(R.string.library_organize_folder, source.name, 1))
        click(text(R.string.library_delete_folder_target, folderContext(source, 1)))
        assertEquals(listOf(LibraryAction.RequestFolderDeletion(source)), actions)
        state = state.copy(folderDeletion = inspected(source, true, 1))
        cancel()
        state = state.copy(folderDeletion = inspected(source, true, 2))
        dismissDialogWithSystemBack()
        state = state.copy(folderDeletion = inspected(source, true, 3))
        val confirm =
            compose.onNodeWithContentDescription(
                text(R.string.library_delete_empty_confirm, folderContext(source, 1)),
            )
        val old = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        state = state.copy(isMutating = true)
        confirm.assertIsNotEnabled()
        compose.runOnIdle { old() }
        state = state.copy(isMutating = false)
        confirm.enter()
        compose.runOnIdle { old() }
        assertEquals(LibraryAction.DeleteEmptyFolder(source.id), actions.last())
        assertEquals(4, actions.size)
        state = catalog().copy(isMutating = true)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.library_back)).assertIsFocused()
    }

    @Test
    fun nonemptyMoveUsesExplicitRootAndDuplicateDestinationIdsAndRevokesPreviousStages() {
        val source = folder("source", "Same")
        val a = folder("a", "Destination")
        val b = folder("b", "Destination")
        var state by mutableStateOf(
            catalog(folders = listOf(source, a, b)).copy(
                folderDeletion = inspected(source, false, 1, listOf(a, b)),
            ),
        )
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { screen(state, actions::add) }
        keyboard.enter(compose.onNodeWithText(text(R.string.library_cancel)))
        val context = folderContext(source, 1)
        for (request in 1L..2L) {
            if (request == 2L) state = state.copy(folderDeletion = inspected(source, false, request, listOf(a, b)))
            val deleteBranch =
                compose
                    .onNodeWithContentDescription(text(R.string.library_delete_contents_target, context))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            click(text(R.string.library_move_contents_target, context))
            compose.runOnIdle { deleteBranch() }
            compose.onNodeWithText(text(R.string.library_delete_contents_title)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.library_cancel)).assertIsFocused()
            assertEquals((request - 1).toInt(), actions.size)
            val destination =
                if (request == 1L) {
                    text(R.string.library_move_contents_root, context)
                } else {
                    text(R.string.library_move_contents_destination, context, folderContext(b, 2))
                }
            val button = compose.onNodeWithContentDescription(destination)
            val stale = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            button.enter()
            compose.runOnIdle {
                stale()
                stale()
            }
            assertEquals(
                LibraryAction.DeleteFolderMovingContents(
                    source.id,
                    if (request ==
                        1L
                    ) {
                        null
                    } else {
                        b.id
                    },
                ),
                actions.last(),
            )
        }
        assertEquals(2, actions.size)
    }

    @Test
    fun finalDestructiveConfirmationCancelsDismissesAndRejectsRefreshAndBackgroundCallbacks() {
        val source = folder("source", "Same")
        var state by mutableStateOf(
            catalog(folders = listOf(source)).copy(folderDeletion = inspected(source, false, 1)),
        )
        var visible by mutableStateOf(true)
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            LifeTracingTheme {
                LibraryScreen(state, actions::add, isRouteVisible = visible, currentRouteVisible = { visible }) {}
            }
        }
        keyboard.enter(compose.onNodeWithText(text(R.string.library_cancel)))
        val context = folderContext(source, 1)
        for (request in 1L..4L) {
            if (request > 1) state = state.copy(folderDeletion = inspected(source, false, request))
            click(text(R.string.library_delete_contents_target, context))
            compose
                .onNodeWithText(text(R.string.library_delete_contents_title))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            val confirm = compose.onNodeWithContentDescription(text(R.string.library_delete_contents_confirm, context))
            val old = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            when (request) {
                1L -> cancel()
                2L ->
                    dismissDialogWithSystemBack()
                3L -> {
                    compose.runOnIdle {
                        visible = false
                        old()
                    }
                    compose.waitForIdle()
                    visible = true
                    state = state.copy(folderDeletion = null)
                }
                else -> {
                    state = state.copy(folderDeletion = null, browse = LibraryLoad.Loading)
                    compose.waitForIdle()
                }
            }
            compose.runOnIdle { old() }
            assertTrue(actions.none { it is LibraryAction.DeleteFolderAndArchiveContents })
        }
        state = catalog(folders = listOf(source)).copy(folderDeletion = inspected(source, false, 5))
        click(text(R.string.library_delete_contents_target, context))
        val confirm = compose.onNodeWithContentDescription(text(R.string.library_delete_contents_confirm, context))
        val old = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        confirm.enter()
        compose.runOnIdle { old() }
        assertEquals(1, actions.count { it == LibraryAction.DeleteFolderAndArchiveContents(source.id) })
    }

    @Test
    fun englishRussianLifecycleDialogsKeepIndependentTargetsAtCombinedScaleExtremes() {
        var locale by mutableStateOf(Locale.US)
        var small by mutableStateOf(false)
        val source = folder("source", "Long folder / Длинное имя папки")
        var deletion by mutableStateOf(inspected(source, false, 1))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            val config =
                android.content.res.Configuration(compose.activity.resources.configuration).apply {
                    setLocale(locale)
                    fontScale = 2f
                }
            val context = compose.activity.createConfigurationContext(config)
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(
                        density.density,
                        2f,
                    ),
            ) {
                com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance(
                    com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                        interfaceScalePercent = if (small) 90 else 120,
                        textScalePercent = if (small) 90 else 130,
                    ),
                ) { LibraryScreen(catalog(folders = listOf(source)).copy(folderDeletion = deletion), actions::add) {} }
            }
        }
        keyboard.enter(compose.onNodeWithText(text(R.string.library_cancel)))
        var request = 1L
        for (language in listOf(Locale.US, Locale.forLanguageTag("ru"))) {
            for (minimum in listOf(false, true)) {
                compose.runOnIdle {
                    locale = language
                    small = minimum
                    deletion = inspected(source, false, ++request)
                }

                fun label(id: Int): String {
                    val config =
                        android.content.res
                            .Configuration(
                                compose.activity.resources.configuration,
                            ).apply { setLocale(language) }
                    return compose.activity.createConfigurationContext(config).getString(id)
                }
                val move = compose.onNodeWithText(label(R.string.library_move_contents))
                val cancel = compose.onNodeWithText(label(R.string.library_cancel))
                move.performScrollTo().assertIsDisplayed()
                cancel.assertIsFocused()
                val minPx = 48 * compose.activity.resources.displayMetrics.density
                for ((name, node) in listOf("move" to move, "cancel" to cancel)) {
                    val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                    assertTrue(
                        "locale=$language small=$minimum $name touch=$bounds minimum=$minPx",
                        bounds.width >= minPx - 1 && bounds.height >= minPx - 1,
                    )
                }
                val moveNode = move.fetchSemanticsNode()
                val cancelNode = cancel.fetchSemanticsNode()
                assertTrue(
                    "locale=$language small=$minimum move=${moveNode.boundsInRoot}/${moveNode.touchBoundsInRoot} " +
                        "cancel=${cancelNode.boundsInRoot}/${cancelNode.touchBoundsInRoot}",
                    !moveNode.touchBoundsInRoot.overlaps(cancelNode.touchBoundsInRoot),
                )
                move.enter()
                val root = compose.onNode(hasText(label(R.string.library_root)) and hasClickAction())
                root.performScrollTo().assertIsDisplayed()
                cancel.enter()
                assertTrue(actions.none { it is LibraryAction.DeleteFolderMovingContents })
            }
        }
    }

    @Test
    fun destructiveOutcomeWaitsForCanonicalRefreshAndFailureCannotLeakIntoAnotherCommit() {
        val item = item("a")
        var state by mutableStateOf(catalog(listOf(item)))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { screen(state, actions::add) }
        keyboard.enter()
        val context = templateContext(item, 1)
        click(text(R.string.library_organize_template, context))
        click(text(R.string.library_archive_target, context))
        click(text(R.string.library_archive_confirm, context))
        val ready = state.browse
        state = state.copy(mutationSuccessGeneration = 1, browse = LibraryLoad.Loading)
        compose.onNodeWithText(text(R.string.library_archive_outcome, context)).assertDoesNotExist()
        state = state.copy(browse = ready)
        compose.onNodeWithText(text(R.string.library_archive_outcome, context)).performScrollTo().assertIsDisplayed()
        // The recreated row has its own expansion state.
        click(text(R.string.library_organize_template, context))
        click(text(R.string.library_archive_target, context))
        click(text(R.string.library_archive_confirm, context))
        state = state.copy(mutationFailure = "writer rejected")
        compose.onNodeWithText(text(R.string.library_archive_outcome, context)).assertDoesNotExist()
        state = state.copy(mutationFailure = null, mutationSuccessGeneration = 2)
        compose.onNodeWithText(text(R.string.library_organization_updated)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_archive_outcome, context)).assertDoesNotExist()
        assertEquals(listOf(LibraryAction.ArchiveTemplate(item.id), LibraryAction.ArchiveTemplate(item.id)), actions)
    }

    @Test
    fun cancelAndSystemDismissAtEveryFolderStageNeverDispatchAMutation() {
        val source = folder("source", "Same")
        var state by mutableStateOf(catalog(folders = listOf(source)))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { screen(state, actions::add) }
        keyboard.enter()
        val context = folderContext(source, 1)
        var request = 0L
        for (phase in listOf("loading", "failure", "empty", "disposition", "move", "delete")) {
            for (systemDismiss in listOf(false, true)) {
                val options =
                    when (phase) {
                        "loading" -> LibraryLoad.Loading
                        "failure" -> LibraryLoad.Failure("Unavailable")
                        else -> LibraryLoad.Content(LibraryFolderDeletionOptions(phase == "empty", emptyList()))
                    }
                state = state.copy(folderDeletion = LibraryFolderDeletion(source, options, ++request))
                if (phase == "move") click(text(R.string.library_move_contents_target, context))
                if (phase == "delete") click(text(R.string.library_delete_contents_target, context))
                compose.onNodeWithText(text(R.string.library_cancel)).assertIsFocused()
                if (systemDismiss) {
                    dismissDialogWithSystemBack()
                } else {
                    cancel()
                }
                assertEquals(request.toInt(), actions.size)
                assertTrue(actions.all { it == LibraryAction.DismissFolderDeletion })
                state = state.copy(folderDeletion = null)
                compose.waitForIdle()
                compose.onNodeWithText(text(R.string.library_back)).assertIsFocused()
            }
        }
    }

    @Composable
    private fun screen(
        state: LibraryPresentationState,
        actions: (LibraryAction) -> Unit,
    ) {
        LifeTracingTheme { LibraryScreen(state, actions) {} }
    }

    private fun click(description: String) {
        val node = compose.onNodeWithContentDescription(description)
        if (generateSequence(node.fetchSemanticsNode().parent) {
                it.parent
            }.any { it.config.contains(SemanticsActions.ScrollBy) }
        ) {
            node.performScrollTo()
        }
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun SemanticsNodeInteraction.enter() {
        if (generateSequence(fetchSemanticsNode().parent) {
                it.parent
            }.any { it.config.contains(SemanticsActions.ScrollBy) }
        ) {
            performScrollTo()
        }
        performSemanticsAction(SemanticsActions.RequestFocus)
        performKeyInput { pressKey(Key.Enter) }
    }

    private fun dismissDialogWithSystemBack() {
        val cancel = compose.onNodeWithText(text(R.string.library_cancel))
        cancel.assertIsDisplayed().assertIsFocused()
        val view = (cancel.fetchSemanticsNode().root as ViewRootForTest).view
        compose.waitUntil(timeoutMillis = 5_000) { compose.runOnIdle { view.hasWindowFocus() } }
        androidx.test.espresso.Espresso
            .pressBack()
        compose.waitForIdle()
    }

    private fun cancel() = compose.onNodeWithText(text(R.string.library_cancel)).enter()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = compose.activity.getString(id, *args)

    private fun folderContext(
        folder: Folder,
        position: Int,
    ) = text(R.string.library_folder_context, folder.name, position)

    private fun templateContext(
        item: LibraryTrackable,
        position: Int,
    ) = text(
        R.string.library_template_context,
        text(if (item.id is LibraryTemplateId.Activity) R.string.library_activity else R.string.library_sequence),
        item.name,
        text(R.string.library_all),
        position,
    )

    private fun inspected(
        folder: Folder,
        empty: Boolean,
        request: Long,
        destinations: List<Folder> = emptyList(),
    ) = LibraryFolderDeletion(folder, LibraryLoad.Content(LibraryFolderDeletionOptions(empty, destinations)), request)

    private fun folder(
        id: String,
        name: String,
    ) = Folder(FolderId(id), name, null, Instant.EPOCH, Instant.EPOCH)

    private fun item(
        id: String,
        sequence: Boolean = false,
    ) = LibraryTrackable(
        if (sequence) {
            LibraryTemplateId.Sequence(
                SequenceTemplateId(id),
            )
        } else {
            LibraryTemplateId.Activity(ActivityTemplateId(id))
        },
        "Same",
        null,
        null,
        emptySet(),
        null,
        null,
        null,
    )

    private fun catalog(
        items: List<LibraryTrackable> = emptyList(),
        folders: List<Folder> = emptyList(),
    ) = LibraryPresentationState(
        browse =
            LibraryLoad.Content(
                LibraryBrowse(
                    null,
                    emptyList(),
                    emptyList(),
                    LibraryContents(
                        folders,
                        items.filter { it.id is LibraryTemplateId.Activity },
                        items.filter { it.id is LibraryTemplateId.Sequence },
                    ),
                ),
            ),
        organization = LibraryLoad.Content(LibraryOrganization(folders, emptyList())),
    )
}
