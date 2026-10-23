@file:Suppress("LongMethod", "MagicNumber")

package com.alexandr5476.lifetracing.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LibraryOrganizationPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard =
        com.alexandr5476.lifetracing.ui
            .KeyboardInputFixture(compose)

    @Test
    fun pinnedBoundariesExpansionAndCollapseRetainNativeFocusWithoutCommands() {
        val only = item("only", false, 0)
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { LifeTracingTheme { LibraryScreen(state(listOf(only)), actions::add) {} } }
        keyboard.enter()
        val entry = compose.onNodeWithText(text(R.string.library_reorder_pinned))
        entry.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        entry.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        entry.assertIsFocused()
        entry.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.library_expanded)),
        )
        val context = context(only, R.string.library_pinned, 1)
        compose.onNodeWithContentDescription(text(R.string.library_pinned_move_up, context)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.library_pinned_move_down, context)).assertIsNotEnabled()
        entry.performSemanticsAction(SemanticsActions.OnClick)
        entry.assertIsFocused()
        entry.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.library_collapsed)),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun duplicateTemplateFolderAndTagControlsDispatchOnlyTypedTargetsAndExposeSelection() {
        val first = item("first", false, null)
        val second = item("second", false, null)
        val third = item("third", true, null)
        val folderA = folder("a", "Same folder")
        val folderB = folder("b", "Same folder")
        val tagA = Tag(TagId("a"), "Same tag", Instant.EPOCH, Instant.EPOCH)
        val tagB = Tag(TagId("b"), "Same tag", Instant.EPOCH, Instant.EPOCH)
        var state by mutableStateOf(content(listOf(first, second, third), listOf(folderA, folderB), listOf(tagA, tagB)))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }
        for ((index, item) in listOf(first, second, third).withIndex()) {
            val context = context(item, R.string.library_all, index + 1)
            compose
                .onNodeWithContentDescription(
                    text(R.string.library_organize_template, context),
                ).performScrollTo()
                .performClick()
            val pin = compose.onNodeWithContentDescription(text(R.string.library_pin_target, context))
            pin.performScrollTo().assertIsNotSelected().performClick()
            assertEquals(LibraryAction.SetPinned(item.id, true), actions.last())
            val move =
                text(
                    R.string.library_move_target_folder,
                    context,
                    text(R.string.library_folder_context, folderB.name, 2),
                )
            compose.onNodeWithContentDescription(move).performScrollTo().performClick()
            assertEquals(LibraryAction.MoveTemplate(item.id, folderB.id), actions.last())
            val assign =
                text(R.string.library_assign_target_tag, context, text(R.string.library_tag_context, tagB.name, 2))
            compose
                .onNodeWithContentDescription(assign)
                .performScrollTo()
                .assertIsNotSelected()
                .performClick()
            assertEquals(LibraryAction.AssignTag(item.id, tagB.id), actions.last())
            compose
                .onNodeWithContentDescription(
                    text(R.string.library_move_target_root, context),
                ).performScrollTo()
                .performClick()
            assertEquals(LibraryAction.MoveTemplate(item.id, null), actions.last())
        }
        state =
            content(
                listOf(first.copy(pinnedRank = 0, tagIds = setOf(tagB.id)), second, third),
                listOf(folderA, folderB),
                listOf(tagA, tagB),
            )
        val context = context(first, R.string.library_all, 1)
        val unpin = compose.onNodeWithContentDescription(text(R.string.library_unpin_target, context))
        unpin.performScrollTo().assertIsSelected().performClick()
        assertEquals(LibraryAction.SetPinned(first.id, false), actions.last())
        compose
            .onNodeWithContentDescription(
                text(
                    R.string.library_unassign_target_tag,
                    context,
                    text(R.string.library_tag_context, tagB.name, 2),
                ),
            ).performScrollTo()
            .assertIsSelected()
            .performClick()
        assertEquals(LibraryAction.UnassignTag(first.id, tagB.id), actions.last())
        assertEquals(14, actions.size)
    }

    @Test
    fun folderMoveExcludesSourceAndDescendantsAndKeepsDuplicateDestinationsDistinct() {
        val source = folder("source", "Same")
        val child = folder("child", "Child", source.id)
        val first = folder("a", "Same")
        val second = folder("b", "Same")
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    content(
                        emptyList(),
                        listOf(source, first, second),
                        folders = listOf(source, child, first, second),
                    ),
                    actions::add,
                ) {}
            }
        }
        compose
            .onNodeWithContentDescription(
                text(R.string.library_organize_folder, source.name, 1),
            ).performScrollTo()
            .performClick()
        val target = text(R.string.library_folder_context, source.name, 1)
        for ((index, forbidden) in listOf(source, child).withIndex()) {
            compose
                .onNodeWithContentDescription(
                    text(
                        R.string.library_move_target_folder,
                        target,
                        text(R.string.library_folder_context, forbidden.name, index + 1),
                    ),
                ).assertDoesNotExist()
        }
        compose
            .onNodeWithContentDescription(
                text(
                    R.string.library_move_target_folder,
                    target,
                    text(R.string.library_folder_context, second.name, 4),
                ),
            ).performScrollTo()
            .performClick()
        assertEquals(listOf(LibraryAction.MoveFolder(source.id, second.id)), actions)
        compose
            .onNodeWithContentDescription(
                text(R.string.library_move_target_root, target),
            ).performScrollTo()
            .performClick()
        assertEquals(LibraryAction.MoveFolder(source.id, null), actions.last())
    }

    @Test
    fun dialogsKeepDraftsValidateRestoreInvokerAndNeverMutateOnCancelOrDismiss() {
        val actions = mutableListOf<LibraryAction>()
        var state by mutableStateOf(content(emptyList()))
        compose.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }
        keyboard.enter()
        val context = text(R.string.library_create_folder_here, text(R.string.library_root))
        val entry = compose.onNodeWithContentDescription(context)
        entry.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        entry.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        compose.onNodeWithText(context).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val input = dialogInput(context)
        input.assertIsFocused().performTextInput("Draft")
        state = state.copy(mutationSuccessGeneration = 4)
        dialogInput(context).assertTextContains("Draft")
        assertTrue(actions.isEmpty())
        compose.onNodeWithContentDescription(text(R.string.library_cancel_context, context)).performClick()
        entry.assertIsFocused()
        assertTrue(actions.isEmpty())
        entry.performClick()
        val accept =
            compose.onNodeWithContentDescription(
                text(R.string.library_confirm_context, text(R.string.library_create), context),
            )
        accept.assertIsNotEnabled()
        dialogInput(context).performTextInput("  ")
        accept.assertIsNotEnabled()
        androidx.test.espresso.Espresso
            .pressBack()
        entry.assertIsFocused()
        assertTrue(actions.isEmpty())
        entry.performClick()
        dialogInput(context).performTextInput("Created")
        accept.performSemanticsAction(SemanticsActions.RequestFocus)
        accept.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        assertEquals(listOf(LibraryAction.CreateFolder("Created")), actions)
    }

    @Test
    fun renameAndCreateTagDialogsIdentifyExactDuplicateOwners() {
        val duplicate = folder("other", "Same")
        val folder = folder("rename", "Same")
        val template = item("target", true, null)
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    content(listOf(template), listOf(folder, duplicate)),
                    actions::add,
                ) {}
            }
        }
        compose
            .onNodeWithContentDescription(
                text(R.string.library_organize_folder, folder.name, 1),
            ).performScrollTo()
            .performClick()
        val rename = text(R.string.library_rename_folder_target, text(R.string.library_folder_context, folder.name, 1))
        compose.onNodeWithContentDescription(rename).performScrollTo().performClick()
        dialogInput(rename).performTextReplacement("Renamed")
        compose
            .onNodeWithContentDescription(
                text(R.string.library_confirm_context, text(R.string.library_rename), rename),
            ).performClick()
        assertEquals(listOf(LibraryAction.RenameFolder(folder.id, "Renamed")), actions)
        val context = context(template, R.string.library_all, 1)
        compose
            .onNodeWithContentDescription(
                text(R.string.library_organize_template, context),
            ).performScrollTo()
            .performClick()
        val create = text(R.string.library_create_tag_target, context)
        compose.onNodeWithContentDescription(create).performScrollTo().performClick()
        dialogInput(create).performTextInput("New tag")
        assertEquals(1, actions.size)
        compose
            .onNodeWithContentDescription(
                text(R.string.library_confirm_context, text(R.string.library_create), create),
            ).performClick()
        assertEquals(LibraryAction.CreateAndAssignTag(template.id, "New tag"), actions.last())
    }

    @Test
    fun disposedOrStaleCallbacksCannotSubmitAndFailureRecoveryDoesNotAnnounceFalseSuccess() {
        val item = item("target", false, null)
        var state by mutableStateOf(content(listOf(item)))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }
        val context = context(item, R.string.library_all, 1)
        compose
            .onNodeWithContentDescription(
                text(R.string.library_organize_template, context),
            ).performScrollTo()
            .performClick()
        keyboard.enter()
        val pin = compose.onNodeWithContentDescription(text(R.string.library_pin_target, context))
        pin.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        pin.assertIsFocused()
        val organize = compose.onNodeWithContentDescription(text(R.string.library_organize_template, context))
        organize.performSemanticsAction(SemanticsActions.OnClick)
        organize.assertIsFocused()
        organize.performSemanticsAction(SemanticsActions.OnClick)
        organize.assertIsFocused()
        pin.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        val retained = pin.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        state = state.copy(organization = LibraryLoad.Loading)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.library_back)).assertIsFocused()
        compose.runOnIdle { retained() }
        assertTrue(actions.isEmpty())
        state = state.copy(organization = LibraryLoad.Failure("read"))
        compose.onNodeWithText(text(R.string.library_organization_failure)).performScrollTo().assertIsDisplayed()
        state = content(listOf(item)).copy(isMutating = true)
        compose.onNodeWithContentDescription(text(R.string.library_pin_target, context)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.library_organization_busy)).performScrollTo().assertIsDisplayed()
        state = state.copy(isMutating = false, mutationFailure = "writer rejected")
        compose.onNodeWithText(text(R.string.library_mutation_failure)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_organization_updated)).assertDoesNotExist()
        state = state.copy(mutationFailure = null, mutationSuccessGeneration = 1)
        compose.onNodeWithText(text(R.string.library_organization_updated)).performScrollTo().assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun englishRussianOrganizationTargetsAndDialogsReflowAtCombinedScaleExtremes() {
        var language by mutableStateOf(java.util.Locale.US)
        var small by mutableStateOf(false)
        val target = item("target", false, 0)
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            val configuration =
                android.content.res.Configuration(compose.activity.resources.configuration).apply {
                    setLocale(language)
                    fontScale = 2f
                }
            val context = compose.activity.createConfigurationContext(configuration)
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides configuration,
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
                ) {
                    LibraryScreen(state(listOf(target)), actions::add) {}
                }
            }
        }
        keyboard.enter()
        for (locale in listOf(java.util.Locale.US, java.util.Locale.forLanguageTag("ru"))) {
            for (minimum in listOf(false, true)) {
                compose.runOnIdle {
                    language = locale
                    small = minimum
                }

                fun label(
                    id: Int,
                    vararg args: Any,
                ): String {
                    val config = android.content.res.Configuration(compose.activity.resources.configuration)
                    config.setLocale(locale)
                    return compose.activity.createConfigurationContext(config).getString(id, *args)
                }
                val context =
                    label(
                        R.string.library_template_context,
                        label(R.string.library_activity),
                        target.name,
                        label(R.string.library_pinned),
                        1,
                    )
                val organize = compose.onNodeWithContentDescription(label(R.string.library_organize_template, context))
                organize.performScrollTo().performClick()
                val create = label(R.string.library_create_tag_target, context)
                val entry = compose.onNodeWithContentDescription(create)
                entry.performScrollTo()
                val pin = compose.onNodeWithContentDescription(label(R.string.library_unpin_target, context))
                pin.performScrollTo().assertIsSelected()
                val minimumPixels = 48 * compose.activity.resources.displayMetrics.density
                for (node in listOf(pin, entry)) {
                    node.performScrollTo().assertIsDisplayed()
                    val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                    assertTrue(bounds.height >= minimumPixels - 1)
                    assertTrue(bounds.width >= minimumPixels - 1)
                }
                assertTrue(
                    !pin.fetchSemanticsNode().touchBoundsInRoot.overlaps(entry.fetchSemanticsNode().touchBoundsInRoot),
                )
                entry.performSemanticsAction(SemanticsActions.RequestFocus)
                entry.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
                val input = compose.onNodeWithContentDescription(label(R.string.library_name_context, create))
                input.assertIsFocused().performTextInput("Readable / Читаемый")
                val cancel = compose.onNodeWithContentDescription(label(R.string.library_cancel_context, create))
                val confirm =
                    compose.onNodeWithContentDescription(
                        label(
                            R.string.library_confirm_context,
                            label(R.string.library_create),
                            create,
                        ),
                    )
                confirm.assertIsEnabled()
                assertTrue(
                    !cancel.fetchSemanticsNode().touchBoundsInRoot.overlaps(
                        confirm.fetchSemanticsNode().touchBoundsInRoot,
                    ),
                )
                cancel.performSemanticsAction(SemanticsActions.RequestFocus)
                cancel.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
                entry.assertIsFocused()
                organize.performScrollTo().performClick()
                assertTrue(actions.isEmpty())
            }
        }
    }

    @Test
    fun retainedMutationCallbackChecksLiveRouteOwnershipBeforeRecomposition() {
        val target = item("target", false, null)
        var visible by mutableStateOf(true)
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            LifeTracingTheme {
                LibraryScreen(
                    content(listOf(target)),
                    actions::add,
                    isRouteVisible = visible,
                    currentRouteVisible = { visible },
                ) {}
            }
        }
        val context = context(target, R.string.library_all, 1)
        compose
            .onNodeWithContentDescription(
                text(R.string.library_organize_template, context),
            ).performScrollTo()
            .performClick()
        val callback =
            compose
                .onNodeWithContentDescription(text(R.string.library_pin_target, context))
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val create = text(R.string.library_create_tag_target, context)
        val openDialog =
            compose
                .onNodeWithContentDescription(create)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle {
            visible = false
            callback()
            openDialog()
        }
        compose.onNodeWithContentDescription(text(R.string.library_name_context, create)).assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

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

    private fun folder(
        id: String,
        name: String,
        parent: FolderId? = null,
    ) = Folder(FolderId(id), name, parent, Instant.EPOCH, Instant.EPOCH)

    private fun content(
        items: List<LibraryTrackable>,
        shownFolders: List<Folder> = emptyList(),
        tags: List<Tag> = emptyList(),
        folders: List<Folder> = shownFolders,
    ) = LibraryPresentationState(
        browse =
            LibraryLoad.Content(
                LibraryBrowse(
                    null,
                    emptyList(),
                    emptyList(),
                    LibraryContents(
                        shownFolders,
                        items.filter { it.id is LibraryTemplateId.Activity },
                        items.filter { it.id is LibraryTemplateId.Sequence },
                    ),
                ),
            ),
        organization = LibraryLoad.Content(LibraryOrganization(folders, tags)),
    )

    @Test
    fun filteredPinnedMovesSubmitTheCompleteTypedOrderAndRespectMutationAdmission() {
        val first = item("first", false, 0)
        val second = item("second", true, 1)
        val third = item("third", false, 2)
        val pinned = listOf(first, second, third)
        var state by mutableStateOf(state(pinned).copy(filter = LibraryKindFilter.ACTIVITIES))
        val actions = mutableListOf<LibraryAction>()
        compose.setContent { LifeTracingTheme { LibraryScreen(state, actions::add) {} } }
        compose.onNodeWithText(text(R.string.library_reorder_pinned)).performScrollTo().performClick()
        val firstContext = context(first, R.string.library_pinned, 1)
        val lastContext = context(third, R.string.library_pinned, 3)
        compose.onNodeWithContentDescription(text(R.string.library_pinned_move_up, firstContext)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.library_pinned_move_down, firstContext)).assertIsEnabled()
        compose.onNodeWithContentDescription(text(R.string.library_pinned_move_up, lastContext)).assertIsEnabled()
        compose.onNodeWithContentDescription(text(R.string.library_pinned_move_down, lastContext)).assertIsNotEnabled()
        val context =
            text(
                R.string.library_template_context,
                text(R.string.library_sequence),
                "Same",
                text(R.string.library_pinned),
                2,
            )
        val up = compose.onNodeWithContentDescription(text(R.string.library_pinned_move_up, context))
        up.performScrollTo().performClick()
        assertEquals(listOf(LibraryAction.ReorderPinned(listOf(second.id, first.id, third.id))), actions)
        state = state.copy(isMutating = true)
        up.assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.library_pinned_move_down, context)).assertIsNotEnabled()
    }

    private fun dialogInput(context: String) =
        compose.onNodeWithContentDescription(text(R.string.library_name_context, context))

    private fun text(
        id: Int,
        vararg args: Any,
    ) = compose.activity.getString(id, *args)

    private fun state(pinned: List<LibraryTrackable>) =
        LibraryPresentationState(
            browse =
                LibraryLoad.Content(
                    LibraryBrowse(null, emptyList(), pinned, LibraryContents(emptyList(), emptyList(), emptyList())),
                ),
            organization = LibraryLoad.Content(LibraryOrganization(emptyList(), emptyList())),
        )

    private fun item(
        id: String,
        sequence: Boolean,
        rank: Int?,
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
        rank,
        null,
        null,
    )
}
