@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorLoad
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorSave
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorTarget
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorLoad
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorSave
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorTarget
import com.alexandr5476.lifetracing.editor.configuration
import com.alexandr5476.lifetracing.editor.findStep
import com.alexandr5476.lifetracing.editor.hasInvalidInput
import com.alexandr5476.lifetracing.editor.readyDraft
import com.alexandr5476.lifetracing.editor.sourceActionsAllowed
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.LauncherLoad
import com.alexandr5476.lifetracing.library.LibraryAction
import com.alexandr5476.lifetracing.library.LibraryBrowse
import com.alexandr5476.lifetracing.library.LibraryController
import com.alexandr5476.lifetracing.library.LibraryLoad
import com.alexandr5476.lifetracing.runtime.RuntimeIsolationApplication
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.onLazyNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Production NavDisplay and route owners over an isolated physical database and platform lease. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LibraryAccessibilityRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun duplicateTemplatesOpenExactEditorSessionsAndReturnWithoutWrites() =
        journey { f ->
            val ids = f.seedDuplicates()
            f.store.library.pin(ids.first())
            val before = f.store.durableFacts()
            f.launch()
            f.enterLibrary()
            for (id in ids) {
                val row = f.templateAction(id, R.string.library_open_template)
                row.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
                row.awaitKeyboardFocus()
                assertEquals(before, f.store.durableFacts())
                row.performKeyInput { pressKey(Key.Enter) }
                when (id) {
                    is LibraryTemplateId.Activity -> {
                        f.await {
                            f
                                .host()
                                .activityTemplateEditorRouteSessions.activeSession
                                ?.controller
                                ?.state
                                ?.value
                                ?.load is
                                ActivityTemplateEditorLoad.Ready
                        }
                        val session = f.host().activityTemplateEditorRouteSessions.activeSession!!
                        assertEquals(ActivityTemplateEditorTarget.Existing(id.id), session.target)
                        assertNull(f.host().sequenceTemplateEditorRouteSessions.activeSession)
                    }
                    is LibraryTemplateId.Sequence -> {
                        f.await {
                            f
                                .host()
                                .sequenceTemplateEditorRouteSessions.activeSession
                                ?.controller
                                ?.state
                                ?.value
                                ?.load is
                                SequenceTemplateEditorLoad.Ready
                        }
                        val session = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
                        assertEquals(SequenceTemplateEditorTarget.Existing(id.id), session.target)
                        assertNull(f.host().activityTemplateEditorRouteSessions.activeSession)
                    }
                }
                f.systemBack()
                f.libraryLanding()
                assertNull(f.host().activityTemplateEditorRouteSessions.activeSession)
                assertNull(f.host().sequenceTemplateEditorRouteSessions.activeSession)
                assertSame(f.library, f.host().libraryControllerOwner.get { error("Retained Library required") })
                assertEquals(before, f.store.durableFacts())
            }
            f.invoke(R.string.library_back)
            f.dailyLanding()
            f.reopenUnchanged(before)
        }

    @Test
    fun quickStartPrimesEachExactDuplicateOnceAndBackCancelsWithoutDurableResidue() =
        journey { f ->
            val ids = f.seedDuplicates()
            val before = f.store.durableFacts()
            f.launch()
            f.enterLibrary()
            ids.forEachIndexed { index, id ->
                val quick = f.templateAction(id, R.string.library_quick_start_template)
                quick.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                f.await {
                    f
                        .host()
                        .startActivityRouteSessions.activeSession
                        ?.controller
                        ?.state
                        ?.value
                        ?.command is
                        LauncherCommandState.Preflight
                }
                val session = f.host().startActivityRouteSessions.activeSession!!
                val preflight = session.controller.state.value.command as LauncherCommandState.Preflight
                assertEquals(id, preflight.targetId)
                assertEquals(id, (session.controller.state.value.selected as LauncherLoad.Content).value.id)
                assertNull(session.interaction.pendingSelectionId)
                assertEquals(before, f.store.durableFacts())
                if (index == 0) {
                    f.recreate()
                    assertSame(session, f.host().startActivityRouteSessions.activeSession)
                    assertEquals(preflight, session.controller.state.value.command)
                }
                if (index % 2 == 0) f.systemBack() else f.invoke(R.string.launcher_browse_back)
                f.libraryLanding()
                assertNull(f.host().startActivityRouteSessions.activeSession)
                assertEquals(before, f.store.durableFacts())
                compose.runOnIdle { f.host().libraryControllerOwner.refreshIfInitialized() }
                f.libraryLanding()
                assertNull(f.host().startActivityRouteSessions.activeSession)
            }
            f.systemBack()
            f.dailyLanding()
            f.reopenUnchanged(before)
        }

    @Test
    fun retainedLibraryControlsCannotNavigateAfterFilterSearchOrRouteExit() =
        journey { f ->
            val ids = f.seedDuplicates()
            val folder = FolderId("retained-navigation-folder")
            f.store.library.createFolder(folder, "Retained folder", null, f.seedTime)
            val before = f.store.durableFacts()
            f.launch()
            f.enterLibrary()

            fun callbacks(): List<() -> Boolean> {
                val rows =
                    ids.flatMap { id ->
                        listOf(R.string.library_open_template, R.string.library_quick_start_template).map { operation ->
                            f
                                .templateAction(id, operation)
                                .performScrollTo()
                                .fetchSemanticsNode()
                                .config[SemanticsActions.OnClick]
                                .action!!
                        }
                    }
                val creation =
                    listOf(R.string.library_new_activity, R.string.library_new_sequence).map { label ->
                        compose
                            .onNodeWithText(f.text(label))
                            .performScrollTo()
                            .fetchSemanticsNode()
                            .config[SemanticsActions.OnClick]
                            .action!!
                    }
                val openFolder =
                    compose
                        .onNodeWithContentDescription(
                            f.text(R.string.library_open_folder, "Retained folder", 1),
                        ).performScrollTo()
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                        .action!!
                return rows + creation + openFolder
            }

            fun assertNoNavigation() {
                assertNull(f.host().activityTemplateEditorRouteSessions.activeSession)
                assertNull(f.host().sequenceTemplateEditorRouteSessions.activeSession)
                assertNull(f.host().startActivityRouteSessions.activeSession)
                assertNull(f.library.state.value.folderId)
                assertEquals(before, f.store.durableFacts())
            }
            val filtered = callbacks()
            compose.runOnIdle {
                f.library.dispatch(LibraryAction.SetFilter(LibraryKindFilter.ACTIVITIES))
                filtered.forEach { it() }
            }
            compose.waitForIdle()
            compose.runOnIdle { filtered.forEach { it() } }
            assertNoNavigation()
            compose.runOnIdle { f.library.dispatch(LibraryAction.SetFilter(LibraryKindFilter.ALL)) }
            f.awaitOrganization()
            val searched = callbacks()
            compose.runOnIdle {
                f.library.dispatch(LibraryAction.Search("no matching template"))
                searched.forEach { it() }
            }
            f.await { f.library.state.value.search is LibraryLoad.Content }
            compose.runOnIdle { searched.forEach { it() } }
            assertNoNavigation()
            compose.runOnIdle { f.library.dispatch(LibraryAction.Search("")) }
            f.awaitOrganization()
            val exited = callbacks()
            f.invoke(R.string.library_back)
            f.dailyLanding()
            compose.runOnIdle { exited.forEach { it() } }
            f.dailyLanding()
            assertNoNavigation()
            f.enterLibrary()
            compose.runOnIdle { exited.forEach { it() } }
            assertNoNavigation()
            f.reopenUnchanged(before)
        }

    @Test
    fun nestedToolbarBackAndSystemBackPreserveTheirDifferentLibraryAdmission() =
        journey { f ->
            val parent = FolderId("parent")
            val nested = FolderId("nested")
            f.store.library.createFolder(parent, "Projects", null, f.seedTime)
            f.store.library.createFolder(nested, "Nested", parent, f.seedTime)
            val before = f.store.durableFacts()
            f.launch()
            f.enterLibrary()
            f.openFolder("Projects")
            f.openFolder("Nested")
            assertEquals(nested, f.library.state.value.folderId)
            f.invoke(R.string.library_back)
            f.await { f.library.state.value.folderId == parent && f.library.state.value.browse is LibraryLoad.Content }
            f.openFolder("Nested")
            f.systemBack()
            f.dailyLanding()
            assertEquals(nested, f.library.state.value.folderId)
            f.enterLibrary()
            assertEquals(nested, f.library.state.value.folderId)
            compose.onNodeWithText("Library / Projects / Nested").assertIsDisplayed()
            f.invoke(R.string.library_back)
            f.await { f.library.state.value.folderId == parent && f.library.state.value.browse is LibraryLoad.Content }
            f.invoke(R.string.library_back)
            f.await { f.library.state.value.folderId == null && f.library.state.value.browse is LibraryLoad.Content }
            f.invoke(R.string.library_back)
            f.dailyLanding()
            f.reopenUnchanged(before)
        }

    @Test
    fun backgroundCatalogRemovalAndEditorReturnCannotReplayNavigationOrFocusRemovedRow() =
        journey { f ->
            val id = f.seedDuplicates().first() as LibraryTemplateId.Activity
            f.launch()
            f.enterLibrary()
            val staleOpen =
                f
                    .templateAction(id, R.string.library_open_template)
                    .performScrollTo()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            val staleQuickStart =
                f
                    .templateAction(id, R.string.library_quick_start_template)
                    .performScrollTo()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            val staleCreate =
                compose
                    .onNodeWithText(f.text(R.string.library_new_sequence))
                    .performScrollTo()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            f
                .templateAction(id, R.string.library_open_template)
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            f.await {
                f
                    .host()
                    .activityTemplateEditorRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.load is
                    ActivityTemplateEditorLoad.Ready
            }
            val session = f.host().activityTemplateEditorRouteSessions.activeSession!!
            compose.runOnIdle {
                staleOpen()
                staleQuickStart()
                staleCreate()
            }
            assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
            assertNull(f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertNull(f.host().startActivityRouteSessions.activeSession)
            val editorName = compose.onNode(hasText(f.text(R.string.activity_editor_name)) and hasSetTextAction())
            editorName.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            editorName.awaitKeyboardFocus()
            f.store.library.archiveActivityTemplate(id.id, f.seedTime.plusSeconds(1))
            val afterArchive = f.store.durableFacts()
            compose.runOnIdle { f.host().libraryControllerOwner.refreshIfInitialized() }
            f.await {
                (f.library.state.value.browse as? LibraryLoad.Content)?.value?.contents?.activities?.none {
                    it.id ==
                        id
                } ==
                    true
            }
            compose.runOnIdle {
                staleOpen()
                staleQuickStart()
                staleCreate()
            }
            assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
            assertNull(f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertNull(f.host().startActivityRouteSessions.activeSession)
            editorName.awaitKeyboardFocus()
            assertEquals(afterArchive, f.store.durableFacts())
            f.systemBack()
            f.libraryLanding()
            compose.runOnIdle {
                staleOpen()
                staleQuickStart()
                staleCreate()
            }
            assertNull(f.host().activityTemplateEditorRouteSessions.activeSession)
            assertNull(f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertNull(f.host().startActivityRouteSessions.activeSession)
            compose.runOnIdle { f.host().libraryControllerOwner.refreshIfInitialized() }
            f.libraryLanding()
            assertNull(f.host().activityTemplateEditorRouteSessions.activeSession)
            f.reopenUnchanged(afterArchive)
        }

    @Test
    fun nonDestructiveTemplateOrganizationReopensCanonicalMetadataAndFrozenHistory() =
        journey { f ->
            val ids = f.seedDuplicates()
            val history =
                f.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Prior history",
                        null,
                        TimeTrackingMode.NO_LIVE_TRACKING,
                        null,
                    ),
                    createdAt = f.seedTime,
                )
            f.store.library.completeNoLiveActivityFromTemplate(
                history.id,
                f.seedTime,
                f.seedTime,
                java.time.ZoneId.systemDefault(),
            )
            val destination = FolderId("destination")
            f.store.library.createFolder(destination, "Destination", null, f.seedTime)
            val tag =
                com.alexandr5476.lifetracing.domain
                    .TagId("existing")
            f.store.library.createTag(tag, "Existing", f.seedTime)
            val beforeConfig = ids.map { f.semanticTemplate(it) }
            val frozen = f.frozenFacts()
            f.launch()
            f.enterLibrary()
            ids.forEach { id ->
                f.organize(id)
                f.organizationMutation(f.text(R.string.library_pin_target, f.templateContext(id)))
            }
            assertEquals(
                ids.toSet(),
                f.store.library
                    .getPinned()
                    .map { it.id }
                    .toSet(),
            )
            val original =
                f.store.library
                    .getPinned()
                    .map { it.id }
            f.invoke(R.string.library_filter_activities)
            f.invoke(R.string.library_reorder_pinned)
            val moved = original.last()
            val pinnedContext =
                f.text(
                    R.string.library_template_context,
                    f.text(
                        if (moved is LibraryTemplateId.Activity) {
                            R.string.library_activity
                        } else {
                            R.string.library_sequence
                        },
                    ),
                    "Shared name",
                    f.text(R.string.library_pinned),
                    3,
                )
            f.organizationMutation(f.text(R.string.library_pinned_move_up, pinnedContext))
            val reordered = listOf(original[0], original[2], original[1])
            assertEquals(
                reordered,
                f.store.library
                    .getPinned()
                    .map { it.id },
            )
            f.invoke(R.string.library_filter_all)
            val activity = ids.first()
            f.organize(activity)
            val createTag = f.text(R.string.library_create_tag_target, f.templateContext(activity))
            val beforeDraft = f.store.durableFacts()
            f.description(createTag)
            f.dialogInput(createTag).performTextInput("Canceled")
            f.description(f.text(R.string.library_cancel_context, createTag))
            assertEquals(beforeDraft, f.store.durableFacts())
            f.description(createTag)
            f.dialogInput(createTag).performTextInput("Dismissed")
            f.dismissDialogWithSystemBack(f.text(R.string.library_cancel_context, createTag))
            assertEquals(beforeDraft, f.store.durableFacts())
            f.nameMutation(createTag, "Created tag", R.string.library_create)
            val createdTag =
                f.store.library
                    .getTags()
                    .single { it.name == "Created tag" }
            assertEquals(
                setOf(createdTag.id),
                f.store.library
                    .getAll()
                    .single { it.id == activity }
                    .tagIds,
            )
            val tagContext = f.tagContext(tag)
            f.organize(activity)
            f.organizationMutation(f.text(R.string.library_assign_target_tag, f.templateContext(activity), tagContext))
            f.organize(activity)
            f.organizationMutation(
                f.text(R.string.library_unassign_target_tag, f.templateContext(activity), f.tagContext(tag)),
            )
            f.organize(activity)
            f.organizationMutation(f.text(R.string.library_unpin_target, f.templateContext(activity)))
            for (id in listOf(ids.first(), ids.last())) {
                f.organize(id)
                f.organizationMutation(
                    f.text(
                        R.string.library_move_target_folder,
                        f.templateContext(id),
                        f.folderContext(destination),
                    ),
                )
                f.openFolder("Destination")
                f.organize(id)
                f.organizationMutation(f.text(R.string.library_move_target_root, f.templateContext(id)))
                f.invoke(R.string.library_back)
                f.awaitOrganization()
            }
            val pinnedAfter =
                f.store.library
                    .getPinned()
                    .map { it.id }
            f.reopenOrganization {
                assertEquals(pinnedAfter, library.getPinned().map { it.id })
                assertEquals(reordered.filter { it != activity }, pinnedAfter)
                ids.forEach { id -> assertNull(library.getAll().single { it.id == id }.folderId) }
                assertEquals(setOf(createdTag.id), library.getAll().single { it.id == activity }.tagIds)
                assertEquals(listOf(destination), library.getFolderPath(destination).map { it.id })
            }
            assertEquals(beforeConfig, ids.map { f.semanticTemplate(it) })
            assertEquals(frozen, f.frozenFacts())
            assertEquals(1L to 0L, f.store.executionCounts())
        }

    @Test
    fun nestedFolderCreateRenameMoveAndCanonicalPathRefreshSurvivePhysicalReopen() =
        journey { f ->
            val parent = FolderId("parent")
            val destination = FolderId("destination")
            f.store.library.createFolder(parent, "Parent", null, f.seedTime)
            f.store.library.createFolder(destination, "Destination", null, f.seedTime)
            f.launch()
            f.enterLibrary()
            f.openFolder("Parent")
            val create = f.text(R.string.library_create_folder_here, "Library / Parent")
            f.nameMutation(create, "Child", R.string.library_create)
            val child =
                f.store.library
                    .getFolders()
                    .single { it.name == "Child" }
            assertEquals(parent, child.parentFolderId)
            f.description(f.text(R.string.library_organize_folder, "Child", 1))
            val rename =
                f.text(
                    R.string.library_rename_folder_target,
                    f.text(R.string.library_folder_context, "Child", 1),
                )
            f.nameMutation(rename, "Renamed", R.string.library_rename)
            f.organizeFolder(child.id)
            f.organizationMutation(
                f.text(
                    R.string.library_move_target_folder,
                    f.text(R.string.library_folder_context, "Renamed", 1),
                    f.folderContext(destination),
                ),
            )
            assertEquals(
                destination,
                f.store.library
                    .getFolders()
                    .single { it.id == child.id }
                    .parentFolderId,
            )
            f.invoke(R.string.library_back)
            f.awaitOrganization()
            f.openFolder("Destination")
            f.openFolder("Renamed")
            f.store.library.moveFolder(destination, parent, Instant.now())
            compose.runOnIdle { f.host().libraryControllerOwner.refreshIfInitialized() }
            f.awaitOrganization()
            compose.onNodeWithText("Library / Parent / Destination / Renamed").assertIsDisplayed()
            f.invoke(R.string.library_back)
            f.awaitOrganization()
            f.organizeFolder(child.id)
            f.organizationMutation(
                f.text(
                    R.string.library_move_target_root,
                    f.text(R.string.library_folder_context, "Renamed", 1),
                ),
            )
            f.reopenOrganization {
                assertEquals(listOf(parent, destination), library.getFolderPath(destination).map { it.id })
                assertEquals(listOf(child.id), library.getFolderPath(child.id).map { it.id })
                assertEquals("Renamed", library.getFolders().single { it.id == child.id }.name)
                assertEquals(0L to 0L, executionCounts())
            }
        }

    @Test
    fun staleOrganizationDestinationIsRejectedWithoutPartialWritesAndRetryReloadsCanonicalState() =
        journey { f ->
            val id = f.seedDuplicates().first()
            val target = FolderId("stale")
            f.store.library.createFolder(target, "Stale destination", null, f.seedTime)
            f.launch()
            f.enterLibrary()
            f.organize(id)
            val move = f.text(R.string.library_move_target_folder, f.templateContext(id), f.folderContext(target))
            f.store.library.deleteEmptyFolder(target, Instant.now())
            val before = f.store.durableFacts()
            val generation = f.library.state.value.mutationSuccessGeneration
            f.description(move)
            f.await { !f.library.state.value.isMutating && f.library.state.value.mutationFailure != null }
            compose.onNodeWithText(f.text(R.string.library_mutation_failure)).performScrollTo().assertIsDisplayed()
            assertEquals(generation, f.library.state.value.mutationSuccessGeneration)
            assertEquals(before, f.store.durableFacts())
            f.invoke(R.string.library_retry)
            f.awaitOrganization()
            f.await { f.library.state.value.mutationFailure == null }
            compose.onNodeWithContentDescription(move).assertDoesNotExist()
            assertEquals(before, f.store.durableFacts())
            f.reopenOrganization {
                assertNull(library.getAll().single { it.id == id }.folderId)
                assertEquals(emptyList<FolderId>(), library.getFolders().map { it.id })
                assertEquals(before, durableFacts())
            }
        }

    @Test
    fun accessibleArchiveReopensExactTemplatesWithHistoryPlansAndStatisticsPreserved() =
        journey { f ->
            val ids = f.seedDuplicates()
            val activityId = (ids.first() as LibraryTemplateId.Activity).id
            val sequenceId = (ids.last() as LibraryTemplateId.Sequence).id
            val activity =
                f.store.library.startActivityFromTemplate(
                    activityId,
                    f.seedTime,
                    f.seedTime,
                    java.time.ZoneOffset.UTC,
                )
            f.store.live.completeActiveActivity(f.seedTime.plusSeconds(2))
            val sequence =
                f.store.library.startSequenceFromTemplate(
                    sequenceId,
                    f.seedTime.plusSeconds(3),
                    f.seedTime.plusSeconds(3),
                    java.time.ZoneOffset.UTC,
                )
            f.store.live.endSequenceEarly(f.seedTime.plusSeconds(5))
            val plan =
                f.store.plans.createActivityPlanFromTemplate(
                    activityId,
                    com.alexandr5476.lifetracing.domain.PlanSchedule
                        .FloatingDay(java.time.LocalDate.of(2026, 10, 24)),
                    f.seedTime.plusSeconds(6),
                )
            ids.forEach(f.store.library::pin)
            val config = ids.map(f::lifecycleTemplate)
            val activityHistory = f.store.history.getActivityDetail(activity.id)
            val sequenceHistory = f.store.history.getSequenceDetail(sequence.execution.id)
            val series =
                f.store.statistics
                    .seriesCatalog()
                    .map { it.id }
                    .toSet()
            val liveTime = Instant.now()
            f.store.library.startActivityFromTemplate(activityId, liveTime, liveTime, java.time.ZoneOffset.UTC)
            val activeSession = f.store.live.getActiveSession()
            val activeRuntime = f.store.live.getActiveRuntime()
            val counts = f.store.executionCounts()
            val frozenWithRuntime = f.frozenFacts()
            f.launch()
            f.enterLibrary()
            for (id in listOf(ids.first(), ids.last())) {
                f.organize(id)
                val context = f.templateContext(id)
                val before = f.store.durableFacts()
                f.description(f.text(R.string.library_archive_target, context))
                compose.onNodeWithText(f.text(R.string.library_cancel)).performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
                assertEquals(before, f.store.durableFacts())
                f.description(f.text(R.string.library_archive_target, context))
                f.organizationMutation(f.text(R.string.library_archive_confirm, context))
            }
            f.reopenOrganization(expectedSession = activeSession) {
                assertEquals(activeRuntime, live.getActiveRuntime())
                assertEquals(setOf(ids[1]), library.getAll().map { it.id }.toSet())
                assertEquals(setOf(ids.first(), ids.last()), library.getArchived().map { it.id }.toSet())
                assertEquals(setOf(ids[1]), library.getPinned().map { it.id }.toSet())
                assertEquals(listOf(ids[1]), library.search("Shared name").map { it.id })
                assertEquals(activityHistory, history.getActivityDetail(activity.id))
                assertEquals(sequenceHistory, history.getSequenceDetail(sequence.execution.id))
                assertEquals(plan, plans.getPlan(plan.id))
                assertEquals(series, statistics.seriesCatalog().map { it.id }.toSet())
                assertEquals(counts, executionCounts())
            }
            assertEquals(config, ids.map(f::lifecycleTemplate))
            assertEquals(frozenWithRuntime, f.frozenFacts())
            // Fresh production launcher discovery uses its canonical root catalog.
            assertEquals(
                setOf(ids[1]),
                f.store.library
                    .getRoot()
                    .contents.activities
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun emptyDeletionAndSafeMovesToRootAndFolderReopenCanonicalSubtrees() =
        journey { f ->
            val ids = f.seedDuplicates()
            val empty = FolderId("empty")
            val sourceRoot = FolderId("source-root")
            val sourceDestination = FolderId("source-destination")
            val destination = FolderId("destination")
            val child = FolderId("child")
            val grandchild = FolderId("grandchild")
            listOf(empty, sourceRoot, sourceDestination, destination).forEach {
                f.store.library.createFolder(it, it.value, null, f.seedTime)
            }
            f.store.library.createFolder(child, "Child", sourceDestination, f.seedTime)
            f.store.library.createFolder(grandchild, "Grandchild", child, f.seedTime)
            f.store.library.moveTemplatesToFolder(listOf(ids.first()), sourceRoot, f.seedTime.plusSeconds(1))
            f.store.library.moveTemplatesToFolder(listOf(ids.last()), sourceDestination, f.seedTime.plusSeconds(1))
            val config = ids.map(f::lifecycleTemplate)
            val frozen = f.frozenFacts()
            f.launch()
            f.enterLibrary()
            var context = f.requestDeletion(empty)
            f.organizationMutation(f.text(R.string.library_delete_empty_confirm, context))
            context = f.requestDeletion(sourceRoot)
            val beforeChoice = f.store.durableFacts()
            f.description(f.text(R.string.library_move_contents_target, context))
            assertEquals(beforeChoice, f.store.durableFacts())
            f.organizationMutation(f.text(R.string.library_move_contents_root, context))
            context = f.requestDeletion(sourceDestination)
            f.description(f.text(R.string.library_move_contents_target, context))
            val inspected =
                (
                    f.library.state.value.folderDeletion!!
                        .options as LibraryLoad.Content
                ).value
            val destinationIndex = inspected.destinations.indexOfFirst { it.id == destination }
            val target = f.text(R.string.library_folder_context, destination.value, destinationIndex + 1)
            f.organizationMutation(f.text(R.string.library_move_contents_destination, context, target))
            f.reopenOrganization {
                assertEquals(setOf(destination, child, grandchild), library.getFolders().map { it.id }.toSet())
                assertEquals(listOf(destination, child, grandchild), library.getFolderPath(grandchild).map { it.id })
                assertNull(library.getAll().single { it.id == ids.first() }.folderId)
                assertEquals(destination, library.getAll().single { it.id == ids.last() }.folderId)
                assertEquals(emptyList<LibraryTemplateId>(), library.getArchived().map { it.id })
                assertEquals(0L to 0L, executionCounts())
            }
            assertEquals(config, ids.map(f::lifecycleTemplate))
            assertEquals(frozen, f.frozenFacts())
        }

    @Test
    fun deleteContentsArchivesBothKindsAndPreservesCanonicalHistoryAfterPhysicalReopen() =
        journey { f ->
            val ids = f.seedDuplicates()
            val source = FolderId("source")
            val child = FolderId("child")
            f.store.library.createFolder(source, "Same folder", null, f.seedTime)
            f.store.library.createFolder(child, "Child", source, f.seedTime)
            f.store.library.moveTemplatesToFolder(listOf(ids.first()), source, f.seedTime.plusSeconds(1))
            f.store.library.moveTemplatesToFolder(listOf(ids.last()), child, f.seedTime.plusSeconds(1))
            val activity =
                f.store.library.startActivityFromTemplate(
                    (ids.first() as LibraryTemplateId.Activity).id,
                    f.seedTime.plusSeconds(2),
                    f.seedTime.plusSeconds(2),
                    java.time.ZoneOffset.UTC,
                )
            f.store.live.completeActiveActivity(f.seedTime.plusSeconds(4))
            val detail = f.store.history.getActivityDetail(activity.id)
            val config = ids.map(f::lifecycleTemplate)
            val series =
                f.store.statistics
                    .seriesCatalog()
                    .map { it.id }
                    .toSet()
            val frozen = f.frozenFacts()
            f.launch()
            f.enterLibrary()
            var context = f.requestDeletion(source)
            val before = f.store.durableFacts()
            f.description(f.text(R.string.library_delete_contents_target, context))
            assertEquals(before, f.store.durableFacts())
            f.dismissDialogWithSystemBack(
                f.text(R.string.library_cancel_context, f.text(R.string.library_delete_contents_confirm, context)),
            )
            f.await { f.library.state.value.folderDeletion == null }
            assertEquals(before, f.store.durableFacts())
            context = f.requestDeletion(source)
            f.description(f.text(R.string.library_delete_contents_target, context))
            val confirmation =
                compose.onNodeWithContentDescription(
                    f.text(R.string.library_delete_contents_confirm, context),
                )
            val beforeGeneration = f.library.state.value.mutationSuccessGeneration
            keyboard.awaitWindowFocus(confirmation)
            confirmation.performSemanticsAction(SemanticsActions.RequestFocus).awaitKeyboardFocus()
            keyboard.sendKey(confirmation, KeyEvent.KEYCODE_ENTER)
            f.await { f.library.state.value.mutationSuccessGeneration == beforeGeneration + 1 }
            f.awaitOrganization()
            compose.onNodeWithText(f.text(R.string.library_back)).awaitKeyboardFocus()
            f.reopenOrganization {
                assertEquals(emptyList<FolderId>(), library.getFolders().map { it.id })
                assertEquals(setOf(ids.first(), ids.last()), library.getArchived().map { it.id }.toSet())
                assertEquals(listOf(ids[1]), library.getAll().map { it.id })
                assertEquals(detail, history.getActivityDetail(activity.id))
                assertEquals(series, statistics.seriesCatalog().map { it.id }.toSet())
                assertEquals(1L to 0L, executionCounts())
            }
            assertEquals(config, ids.map(f::lifecycleTemplate))
            assertEquals(frozen, f.frozenFacts())
        }

    @Test
    fun hiddenArchivedOccupancyAndStaleMoveDestinationRejectWithoutPartialWrites() =
        journey { f ->
            val source = FolderId("source")
            val destination = FolderId("destination")
            f.store.library.createFolder(source, "Same", null, f.seedTime)
            f.store.library.createFolder(destination, "Same", null, f.seedTime)
            f.launch()
            f.enterLibrary()
            var context = f.requestDeletion(source)
            val hidden =
                f.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Hidden", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                    com.alexandr5476.lifetracing.domain
                        .TemplateLibraryPlacement(folderId = source),
                    f.seedTime.plusSeconds(1),
                )
            f.store.library.archiveActivityTemplate(hidden.id, f.seedTime.plusSeconds(2))
            val beforeEmpty = f.store.durableFacts()
            val generation = f.library.state.value.mutationSuccessGeneration
            f.description(f.text(R.string.library_delete_empty_confirm, context))
            f.await { !f.library.state.value.isMutating && f.library.state.value.mutationFailure != null }
            assertEquals(beforeEmpty, f.store.durableFacts())
            assertEquals(generation, f.library.state.value.mutationSuccessGeneration)
            compose.onNodeWithText(f.text(R.string.library_organization_updated)).assertDoesNotExist()
            context = f.requestDeletion(source)
            assertEquals(
                false,
                (
                    f.library.state.value.folderDeletion!!
                        .options as LibraryLoad.Content
                ).value.isEmpty,
            )
            f.description(f.text(R.string.library_move_contents_target, context))
            val options =
                (
                    f.library.state.value.folderDeletion!!
                        .options as LibraryLoad.Content
                ).value
            val index = options.destinations.indexOfFirst { it.id == destination }
            val target = f.text(R.string.library_folder_context, "Same", index + 1)
            f.store.library.deleteEmptyFolder(destination, Instant.now())
            val beforeMove = f.store.durableFacts()
            f.description(f.text(R.string.library_move_contents_destination, context, target))
            f.await { !f.library.state.value.isMutating && f.library.state.value.mutationFailure != null }
            assertEquals(beforeMove, f.store.durableFacts())
            assertEquals(generation, f.library.state.value.mutationSuccessGeneration)
            f.invoke(R.string.library_retry)
            f.awaitOrganization()
            f.reopenOrganization {
                assertEquals(listOf(source), library.getFolders().map { it.id })
                assertEquals(listOf(source), library.getFolderPath(source).map { it.id })
                assertEquals(listOf(LibraryTemplateId.Activity(hidden.id)), library.getArchived().map { it.id })
                assertEquals(source, library.getArchived().single().folderId)
                assertEquals(beforeMove, durableFacts())
                assertEquals(0L to 0L, executionCounts())
            }
        }

    @Test
    fun nativeNewActivityAuthoringCommitsOnceAndPhysicallyReopensCanonicalFields() =
        journey { f ->
            f.launch()
            f.enterLibrary()
            val retainedLibrary = f.library
            val before = f.store.durableFacts()
            val originalSeries = f.store.statistics.seriesCatalog()
            val originalSeriesIds = originalSeries.map { it.id }.toSet()
            assertEquals(setOf(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID), originalSeriesIds)
            f.invoke(R.string.library_new_activity)
            f.awaitEditor()
            val session = f.host().activityTemplateEditorRouteSessions.activeSession!!
            assertEquals(ActivityTemplateEditorTarget.New, session.target)
            f.editorInput(R.string.activity_editor_name).performSemanticsAction(SemanticsActions.RequestFocus)
            assertEquals(before, f.store.durableFacts())
            f.editorInput(R.string.activity_editor_name).performTextReplacement("Created with keyboard")
            f.editorInput(R.string.activity_editor_short_comment).performTextReplacement("Comment")
            f.invoke(R.string.activity_editor_timer)
            f.editorInput(R.string.activity_editor_timer_seconds).performTextReplacement("invalid")
            assertEquals(before, f.store.durableFacts())
            f.editorInput(R.string.activity_editor_timer_seconds).performTextReplacement("90")
            f.addEditorField(com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER)
            f.editorFieldInput(0, R.string.activity_editor_field_name).performTextReplacement("Same")
            f.editorFieldInput(0, R.string.activity_editor_unit).performTextReplacement("kg")
            f.editorFieldInput(0, R.string.activity_editor_precision).performTextReplacement("2")
            f.editorFieldInput(0, R.string.activity_editor_default).performTextReplacement("bad")
            assertEquals(before, f.store.durableFacts())
            f.editorFieldInput(0, R.string.activity_editor_default).performTextReplacement("-1.25")
            f.editorFieldAction(0, R.string.activity_editor_main_value)
            f.addEditorField(com.alexandr5476.lifetracing.domain.CustomFieldType.CATEGORY)
            f.editorFieldInput(1, R.string.activity_editor_field_name).performTextReplacement("Same")
            repeat(2) {
                f.editorFieldAction(1, R.string.activity_editor_add_option)
                f.editorOptionInput(1, it).performTextReplacement("Same")
            }
            f.editorOptionAction(1, 1, R.string.activity_editor_make_default)
            f.addEditorField(com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT)
            f.editorFieldInput(2, R.string.activity_editor_field_name).performTextReplacement("Same")
            f.editorFieldInput(2, R.string.activity_editor_default).performTextReplacement("Text default")
            f.recreate()
            assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
            assertEquals(before, f.store.durableFacts())
            val submitted =
                session.controller.state.value
                    .readyDraft()!!
            val save = compose.onNodeWithText(f.text(R.string.activity_editor_done))
            val staleSave = save.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            save
                .performScrollTo()
                .performSemanticsAction(
                    SemanticsActions.RequestFocus,
                ).performKeyInput { pressKey(Key.Enter) }
            f.await { f.host().activityTemplateEditorRouteSessions.activeSession == null }
            f.libraryLanding()
            compose.runOnIdle { staleSave() }
            assertSame(retainedLibrary, f.library)
            val created =
                f.store.library
                    .getAll()
                    .single()
            val id = (created.id as LibraryTemplateId.Activity).id
            val canonical = f.store.authoring.getActivityTemplate(id)!!
            assertEquals(1L, canonical.revision)
            assertEquals(submitted.name, canonical.name)
            assertEquals("Comment", canonical.shortComment)
            assertEquals(TimeTrackingMode.TIMER, canonical.timeTrackingMode)
            assertEquals(Duration.ofSeconds(90), canonical.timerTarget)
            val fields = canonical.fields.filter { it.deletedAt == null }
            assertEquals(3, fields.size)
            assertEquals(3, fields.map { it.id }.toSet().size)
            assertEquals(
                fields.map { DraftIdentity.Existing(it.id) },
                canonical.toAuthoringDraft().fields.map { it.identity },
            )
            assertEquals(
                fields[1].categoryOptions.map { DraftIdentity.Existing(it.id) },
                canonical
                    .toAuthoringDraft()
                    .fields[1]
                    .categoryOptions
                    .map { it.identity },
            )
            assertEquals(-1250L, fields[0].defaultNumberScaled)
            assertEquals("kg", fields[0].unit)
            assertEquals(2, fields[0].displayPrecision)
            assertEquals(true, fields[0].isMainValue)
            assertEquals(
                2,
                fields[1]
                    .categoryOptions
                    .map { it.id }
                    .toSet()
                    .size,
            )
            assertEquals(fields[1].categoryOptions[1].id, fields[1].defaultCategoryOptionId)
            assertEquals("Text default", fields[2].defaultText)
            f.reopenOrganization {
                assertEquals(canonical, authoring.getActivityTemplate(id))
                assertEquals(listOf(created), library.search("Created with keyboard"))
                val reopenedSeries = statistics.seriesCatalog()
                assertEquals(originalSeriesIds + canonical.statisticsSeriesId, reopenedSeries.map { it.id }.toSet())
                assertEquals(originalSeries, reopenedSeries.filter { it.id in originalSeriesIds })
                assertEquals(0L to 0L, executionCounts())
                assertEquals(emptyList<Any>(), durableFacts()["plan_entries"])
            }
        }

    @Test
    fun nativeExistingAuthoringPreservesEligibleSourcesMetadataAndFrozenHistoryOnReopen() =
        journey { f ->
            val category =
                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                    DraftIdentity.New("category"),
                    2,
                    "Same",
                    com.alexandr5476.lifetracing.domain.CustomFieldType.CATEGORY,
                    categoryOptions =
                        listOf(
                            com.alexandr5476.lifetracing.domain
                                .ActivityCategoryOptionDraft(DraftIdentity.New("one"), 0, "Same"),
                            com.alexandr5476.lifetracing.domain
                                .ActivityCategoryOptionDraft(DraftIdentity.New("two"), 1, "Same"),
                        ),
                    defaultCategoryOption = DraftIdentity.New("one"),
                )
            val number =
                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                    DraftIdentity.New("kept"),
                    0,
                    "Same",
                    com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                    unit = "kg",
                    displayPrecision = 2,
                    defaultNumberScaled = 1250L,
                    isMainValue = true,
                )
            val draft =
                ActivityTemplateDraft(
                    "Shared edit name",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                    fields =
                        listOf(
                            number,
                            number.copy(identity = DraftIdentity.New("replaced"), position = 1, isMainValue = false),
                            category,
                            com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                DraftIdentity.New("type"),
                                3,
                                "Same",
                                com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT,
                            ),
                            com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                DraftIdentity.New("removed"),
                                4,
                                "Same",
                                com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT,
                            ),
                        ),
                )
            val original = f.store.authoring.createActivityTemplate(draft, createdAt = f.seedTime)
            val duplicate = f.store.authoring.createActivityTemplate(draft, createdAt = f.seedTime)
            val id = LibraryTemplateId.Activity(original.id)
            val folder = FolderId("editor-folder")
            val tag =
                com.alexandr5476.lifetracing.domain
                    .TagId("editor-tag")
            f.store.library.createFolder(folder, "Editing", null, f.seedTime)
            f.store.library.createTag(tag, "Tag", f.seedTime)
            f.store.library.addTag(id, tag)
            f.store.library.pin(id)
            f.store.library.moveActivityTemplateToFolder(original.id, folder, f.seedTime)
            val execution =
                f.store.library.startActivityFromTemplate(
                    original.id,
                    f.seedTime,
                    f.seedTime,
                    java.time.ZoneOffset.UTC,
                )
            f.store.live.completeActiveActivity(f.seedTime.plusSeconds(2))
            val plan =
                f.store.plans.createActivityPlanFromTemplate(
                    original.id,
                    com.alexandr5476.lifetracing.domain.PlanSchedule
                        .FloatingDay(java.time.LocalDate.of(2026, 10, 24)),
                    f.seedTime.plusSeconds(3),
                )
            val history = f.store.history.getActivityDetail(execution.id)
            val frozen = f.frozenFacts()
            val pinned = f.store.library.getPinned()
            val recent = f.store.library.getRecent(10)
            f.launch()
            f.enterLibrary()
            f.openFolder("Editing")
            f.invoke(R.string.library_filter_activities)
            compose
                .onNode(hasText(f.text(R.string.library_search_hint)) and hasSetTextAction())
                .performTextReplacement("Shared edit name")
            f.await { f.library.state.value.search is LibraryLoad.Content }
            val retained = f.library
            f
                .templateAction(
                    id,
                    R.string.library_open_template,
                ).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            f.awaitEditor()
            assertEquals(
                ActivityTemplateEditorTarget.Existing(original.id),
                f
                    .host()
                    .activityTemplateEditorRouteSessions.activeSession!!
                    .target,
            )
            val before = f.store.durableFacts()
            f.editorFieldInput(0, R.string.activity_editor_field_name).performTextReplacement("Compatible rename")
            f.editorFieldInput(0, R.string.activity_editor_default).performTextReplacement("0")
            f.editorFieldInput(1, R.string.activity_editor_unit).performTextReplacement("m")
            f.editorOptionInput(2, 1).performTextReplacement("Compatible option rename")
            f.editorOptionAction(2, 0, R.string.activity_editor_remove)
            assertNull(f.editorDraft().fields[2].defaultCategoryOption)
            f.editorOptionAction(2, 0, R.string.activity_editor_make_default)
            f.editorOptionAction(2, 0, R.string.activity_editor_clear_default)
            f.editorFieldAction(4, R.string.activity_editor_remove)
            val context = f.editorFieldContext(3)
            f.description(
                f.text(R.string.activity_editor_change_type_context, f.text(R.string.activity_editor_number), context),
            )
            assertEquals(before, f.store.durableFacts())
            f.editorInput(R.string.activity_editor_short_comment).performTextReplacement("Changed")
            f.invoke(R.string.activity_editor_done)
            f.await { f.host().activityTemplateEditorRouteSessions.activeSession == null }
            f.libraryLanding()
            assertSame(retained, f.library)
            assertEquals(folder, f.library.state.value.folderId)
            assertEquals("Shared edit name", f.library.state.value.query)
            assertEquals(com.alexandr5476.lifetracing.domain.LibraryKindFilter.ACTIVITIES, f.library.state.value.filter)
            val saved = f.store.authoring.getActivityTemplate(original.id)!!
            val active = saved.fields.filter { it.deletedAt == null }
            assertEquals(original.revision + 1, saved.revision)
            assertEquals(original.statisticsSeriesId, saved.statisticsSeriesId)
            assertEquals(original.fields[0].id, active[0].id)
            assertEquals(0L, active[0].defaultNumberScaled)
            org.junit.Assert.assertNotEquals(original.fields[1].id, active[1].id)
            org.junit.Assert.assertNotEquals(original.fields[3].id, active[3].id)
            assertEquals(original.fields[2].id, active[2].id)
            assertEquals(
                original.fields[2].categoryOptions[1].id,
                active[2].categoryOptions.single { !it.isArchived }.id,
            )
            assertEquals(
                true,
                active[2].categoryOptions.single { it.id == original.fields[2].categoryOptions[0].id }.isArchived,
            )
            assertNull(active[2].defaultCategoryOptionId)
            assertEquals(
                setOf(original.fields[1].id, original.fields[3].id, original.fields[4].id),
                saved.fields
                    .filter { it.deletedAt != null }
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(folder, saved.folderId)
            assertEquals(setOf(tag), saved.tagIds)
            assertEquals(duplicate, f.store.authoring.getActivityTemplate(duplicate.id))
            assertEquals(frozen, f.frozenFacts())
            f
                .templateAction(
                    id,
                    R.string.library_open_template,
                ).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            f.awaitEditor()
            f.editorFieldInput(1, R.string.activity_editor_unit).performTextReplacement("kg")
            f.editorFieldInput(1, R.string.activity_editor_unit).performTextReplacement("m")
            f.invoke(R.string.activity_editor_done)
            f.await { f.host().activityTemplateEditorRouteSessions.activeSession == null }
            f.libraryLanding()
            assertEquals(saved, f.store.authoring.getActivityTemplate(original.id))
            f.reopenOrganization {
                assertEquals(saved, authoring.getActivityTemplate(original.id))
                assertEquals(duplicate, authoring.getActivityTemplate(duplicate.id))
                assertEquals(pinned.map { it.id }, library.getPinned().map { it.id })
                assertEquals(
                    recent.map { it.id to it.lastUsedAt },
                    library.getRecent(10).map { it.id to it.lastUsedAt },
                )
                assertEquals(history, this.history.getActivityDetail(execution.id))
                assertEquals(plan, plans.getPlan(plan.id))
                assertEquals(frozen, f.frozenFacts())
            }
        }

    @Test
    fun productionConflictRequiresExplicitReloadAndDiscardLeavesNoPartialWriteOnReopen() =
        journey { f ->
            val ids = f.seedDuplicates()
            val id = (ids.first() as LibraryTemplateId.Activity).id
            f.launch()
            f.enterLibrary()
            f
                .templateAction(
                    ids.first(),
                    R.string.library_open_template,
                ).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            f.awaitEditor()
            val session = f.host().activityTemplateEditorRouteSessions.activeSession!!
            f.editorInput(R.string.activity_editor_short_comment).performTextReplacement("Unsaved stale draft")
            val original = f.store.authoring.getActivityTemplate(id)!!
            f.store.authoring.saveActivityTemplate(
                id,
                original.revision,
                original.toAuthoringDraft().copy(shortComment = "Concurrent writer"),
                Instant.now(),
            )
            val concurrent = f.store.durableFacts()
            f.invoke(R.string.activity_editor_done)
            f.await {
                session.controller.state.value.save is ActivityTemplateEditorSave.Failure
            }
            compose.onNodeWithText(f.text(R.string.activity_editor_conflict)).performScrollTo().assertIsDisplayed()
            assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
            assertEquals(
                "Unsaved stale draft",
                session.controller.state.value
                    .readyDraft()!!
                    .shortComment,
            )
            assertEquals(concurrent, f.store.durableFacts())
            f.recreate()
            assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
            f.invoke(R.string.activity_editor_reload)
            f.awaitEditor()
            assertEquals("Concurrent writer", f.editorDraft().shortComment)
            f.editorInput(R.string.activity_editor_short_comment).performTextReplacement("Discarded")
            f.systemBack()
            f.invoke(R.string.activity_editor_continue_editing)
            assertEquals("Discarded", f.editorDraft().shortComment)
            f.systemBack()
            f.invoke(R.string.activity_editor_discard)
            f.libraryLanding()
            f.invoke(R.string.library_new_activity)
            f.awaitEditor()
            f.editorInput(R.string.activity_editor_name).performTextReplacement("Never committed")
            f.invoke(R.string.activity_editor_timer)
            f.editorInput(R.string.activity_editor_timer_seconds).performTextReplacement("invalid")
            f.systemBack()
            f.invoke(R.string.activity_editor_discard)
            f.libraryLanding()
            f.reopenUnchanged(concurrent)
        }

    @Test
    fun productionLoadRetryAndGatedWriterFailurePreserveSessionAndBackAdmissionUntilCommit() =
        journey { f ->
            val ids = f.seedDuplicates()
            val id = (ids.first() as LibraryTemplateId.Activity).id
            f.launch()
            f.enterLibrary()
            val scope =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
                )
            val firstLoad = kotlinx.coroutines.CompletableDeferred<Unit>()
            val admitted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            var reads = 0
            var attempts = 0
            var commits = 0
            val owner = f.host().activityTemplateEditorRouteSessions
            lateinit var session: com.alexandr5476.lifetracing.editor.ActivityTemplateEditorRouteSession
            compose.runOnIdle {
                session =
                    owner.acquire(ActivityTemplateEditorTarget.Existing(id)) {
                        com.alexandr5476.lifetracing.editor.ActivityTemplateEditorController(
                            scope,
                            ActivityTemplateEditorTarget.Existing(id),
                            loadTemplate = {
                                if (++reads == 1) {
                                    firstLoad.await()
                                    error("gated read failure")
                                }
                                kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) { f.store.authoring.getActivityTemplate(it) }
                            },
                            createTemplate = { _, _, _ -> error("Existing writer required") },
                            saveTemplate = { target, revision, draft, at ->
                                attempts++
                                if (attempts == 1) error("ordinary writer failure")
                                admitted.complete(Unit)
                                release.await()
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    f.store.authoring
                                        .saveActivityTemplate(target, revision, draft, at)
                                        .also { commits++ }
                                }
                            },
                            now = Instant::now,
                        )
                    }
            }
            try {
                f
                    .templateAction(
                        ids.first(),
                        R.string.library_open_template,
                    ).performScrollTo()
                    .performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText(f.text(R.string.activity_editor_loading)).assertIsDisplayed()
                compose.runOnIdle { firstLoad.complete(Unit) }
                f.await { session.controller.state.value.load is ActivityTemplateEditorLoad.Failure }
                f.recreate()
                assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
                assertEquals(1, reads)
                f.invoke(R.string.activity_editor_retry)
                f.awaitEditor()
                val before = f.store.durableFacts()
                f.editorInput(R.string.activity_editor_short_comment).performTextReplacement("Admitted")
                f.invoke(R.string.activity_editor_done)
                f.await { session.controller.state.value.save is ActivityTemplateEditorSave.Failure }
                assertEquals(before, f.store.durableFacts())
                assertEquals("Admitted", f.editorDraft().shortComment)
                assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
                val save = compose.onNodeWithText(f.text(R.string.activity_editor_done))
                val duplicate = save.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                f.invoke(R.string.activity_editor_done)
                f.await { admitted.isCompleted }
                f.systemBack()
                compose.runOnIdle { duplicate() }
                assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
                assertEquals(before, f.store.durableFacts())
                f.recreate()
                assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
                compose.runOnIdle { session.controller.requestBack { error("Saving toolbar Back exited") } }
                compose.runOnIdle { release.complete(Unit) }
                f.await { f.host().activityTemplateEditorRouteSessions.activeSession == null }
                f.libraryLanding()
                assertEquals(2, reads)
                assertEquals(2, attempts)
                assertEquals(1, commits)
                val canonical = f.store.authoring.getActivityTemplate(id)!!
                assertEquals(2L, canonical.revision)
                f.reopenOrganization { assertEquals(canonical, authoring.getActivityTemplate(id)) }
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            }
        }

    @Test
    fun nativeStepAuthoringAndOverrideOnlyEditsPhysicallyReopenWithoutRewritingFrozenOwnership() =
        journey { f ->
            val source =
                f.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Reusable Step",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                        fields =
                            listOf(
                                ActivityFieldDraft(
                                    DraftIdentity.New("source-number"),
                                    0,
                                    "Same",
                                    CustomFieldType.NUMBER,
                                    unit = "kg",
                                ),
                                ActivityFieldDraft(
                                    DraftIdentity.New("source-category"),
                                    1,
                                    "Same",
                                    CustomFieldType.CATEGORY,
                                    categoryOptions =
                                        listOf(
                                            ActivityCategoryOptionDraft(DraftIdentity.New("source-option"), 0, "Same"),
                                        ),
                                ),
                            ),
                    ),
                    createdAt = f.seedTime,
                )
            val before = f.store.durableFacts()
            f.launch()
            f.enterLibrary()
            f.invoke(R.string.library_new_sequence)
            f.awaitSequenceEditor()
            f.editorInput(R.string.sequence_editor_name).performTextReplacement("Step authoring")
            f.invoke(R.string.sequence_editor_add_step)
            val cancelled =
                compose
                    .onNodeWithText(
                        f.text(R.string.sequence_editor_add_local_step),
                    ).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            f.invoke(R.string.sequence_editor_cancel)
            compose.runOnIdle { cancelled() }
            f.invoke(R.string.sequence_editor_add_step)
            f.invoke(R.string.sequence_editor_add_local_step)
            val local =
                f
                    .sequenceDraft()
                    .nodes
                    .single()
                    .identity
            f.stepControl(local, R.string.sequence_editor_edit_step).performSemanticsAction(SemanticsActions.OnClick)
            f.stepControl(local, R.string.sequence_editor_name).performTextReplacement("Local")
            f.stepControl(local, R.string.sequence_editor_short_comment).performTextReplacement("Local comment")
            f.invoke(R.string.activity_editor_timer)
            f.stepControl(local, R.string.activity_editor_timer_seconds).performTextReplacement("bad")
            val failedDone =
                compose
                    .onLazyNode(
                        hasText(f.text(R.string.sequence_editor_done)),
                    ).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            compose.runOnIdle { failedDone() }
            assertEquals(before, f.store.durableFacts())
            f.stepControl(local, R.string.activity_editor_timer_seconds).performTextReplacement("30")
            f.stepControl(local, R.string.sequence_editor_local_start_countdown).performTextReplacement("2")
            for (type in listOf(
                CustomFieldType.NUMBER,
                CustomFieldType.NUMBER,
                CustomFieldType.NUMBER,
                CustomFieldType.CATEGORY,
                CustomFieldType.TEXT,
            )) {
                f.addStepField(local, type)
                val ordinal =
                    f
                        .sequenceDraft()
                        .findStep(local)!!
                        .configuration()!!
                        .fields.lastIndex
                f.stepFieldControl(local, ordinal, R.string.activity_editor_field_name).performTextReplacement("Same")
            }
            f.stepFieldControl(local, 0, R.string.activity_editor_unit).performTextReplacement("kg")
            f.stepFieldControl(local, 0, R.string.activity_editor_precision).performTextReplacement("2")
            f.stepFieldControl(local, 0, R.string.activity_editor_default).performTextReplacement("-1.25")
            f
                .stepFieldControl(
                    local,
                    0,
                    R.string.sequence_editor_main_value,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f.stepFieldControl(local, 1, R.string.activity_editor_default).performTextReplacement("0")
            repeat(2) { option ->
                f
                    .stepFieldControl(
                        local,
                        3,
                        R.string.activity_editor_add_option,
                    ).performSemanticsAction(SemanticsActions.OnClick)
                f.stepOptionControl(local, 3, option, R.string.activity_editor_option).performTextReplacement("Same")
            }
            f
                .stepOptionControl(
                    local,
                    3,
                    1,
                    R.string.activity_editor_default,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f.stepFieldControl(local, 4, R.string.activity_editor_default).performTextReplacement("Text")
            f
                .stepOverride(
                    local,
                    R.string.sequence_editor_override_sound,
                    R.string.sequence_editor_off,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f
                .stepOverride(
                    local,
                    R.string.sequence_editor_override_vibration,
                    R.string.sequence_editor_on,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f
                .stepOverride(
                    local,
                    R.string.sequence_editor_override_awake,
                    R.string.sequence_editor_off,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f.stepControl(local, R.string.sequence_editor_override_countdown).performTextReplacement("0")
            assertEquals(
                Duration.ZERO,
                f
                    .sequenceDraft()
                    .findStep(local)!!
                    .overrides.startCountdown,
            )
            f.invoke(R.string.sequence_editor_add_step)
            compose
                .onNode(
                    hasText(source.name) and hasAnyAncestor(isDialog()),
                ).performSemanticsAction(SemanticsActions.OnClick)
            f.invoke(R.string.sequence_editor_add_repeat)
            val repeat =
                f
                    .sequenceDraft()
                    .nodes
                    .last()
                    .identity
            f.editorInput(R.string.sequence_editor_repeat_count).performTextReplacement("3")
            val repeatContext = f.text(R.string.sequence_manipulation_repeat, 3, 3)
            compose
                .onLazyNode(
                    hasContentDescription(f.text(R.string.sequence_editor_add_step_to_repeat, repeatContext)),
                ).performSemanticsAction(SemanticsActions.OnClick)
            f.invoke(R.string.sequence_editor_add_local_step)
            val child =
                (f.sequenceDraft().nodes.last() as SequenceNodeDraft.Repeat)
                    .value.children
                    .single()
                    .identity
            f.stepControl(child, R.string.sequence_editor_edit_step).performSemanticsAction(SemanticsActions.OnClick)
            f.stepControl(child, R.string.sequence_editor_name).performTextReplacement("Child")
            compose
                .onLazyNode(
                    hasContentDescription(f.text(R.string.sequence_editor_add_step_to_repeat, repeatContext)),
                ).performSemanticsAction(SemanticsActions.OnClick)
            compose
                .onNode(
                    hasText(source.name) and hasAnyAncestor(isDialog()),
                ).performSemanticsAction(SemanticsActions.OnClick)
            val submitted = f.sequenceDraft()
            assertTrue(submitted.nodes.all { it.identity is DraftIdentity.New })
            assertEquals(before, f.store.durableFacts())
            f.invoke(R.string.sequence_editor_add_step)
            val oldPicker =
                compose
                    .onNodeWithText(f.text(R.string.sequence_editor_add_local_step))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            f.recreate()
            f.awaitSequenceEditor()
            // Recreation retires the picker; the surviving editor may restore text focus.
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            // Exercise semantic Done with explicit text focus, independent of native restoration.
            compose
                .onLazyNode(
                    hasText(f.text(R.string.sequence_editor_name)) and
                        hasText("Step authoring") and hasSetTextAction(),
                ).performSemanticsAction(SemanticsActions.RequestFocus)
                .assertIsFocused()
            compose.runOnIdle { oldPicker() }
            assertEquals(submitted, f.sequenceDraft())
            f.invoke(R.string.sequence_editor_done)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            val item =
                f.store.library
                    .search("Step authoring")
                    .single()
            val id = (item.id as LibraryTemplateId.Sequence).id
            val canonical = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            assertEquals(1L, canonical.sequence.revision)
            val top = canonical.sequence.nodes.filterIsInstance<ActivityStep>()
            val block =
                canonical.sequence.nodes
                    .filterIsInstance<SequenceRepeatBlock>()
                    .single()
            assertEquals(3, block.repeatCount)
            assertEquals(2, block.position)
            assertEquals(listOf(0, 1), block.children.map { it.position })
            val all = top + block.children
            assertEquals(4, all.map { it.id }.toSet().size)
            assertEquals(4, all.map { it.activitySnapshotId }.toSet().size)
            val originalSnapshotRows =
                f.store.durableFacts().filterKeys {
                    it in
                        setOf(
                            "activity_snapshots",
                            "activity_snapshot_fields",
                            "activity_snapshot_category_options",
                            "activity_snapshot_settings",
                        )
                }
            val allFields = canonical.activitySnapshots.values.flatMap { it.fields }
            assertEquals(allFields.size, allFields.map { it.id }.toSet().size)
            val allOptions = allFields.flatMap { it.categoryOptions }
            assertEquals(allOptions.size, allOptions.map { it.id }.toSet().size)
            val localSnapshot = canonical.activitySnapshots.getValue(top[0].activitySnapshotId)
            val linked = canonical.activitySnapshots.getValue(top[1].activitySnapshotId)
            assertEquals(source.id, linked.sourceTemplateId)
            assertEquals(source.revision, linked.sourceRevision)
            assertEquals(source.statisticsSeriesId, linked.statisticsSeriesId)
            assertFalse(linked.locallyModified)
            assertTrue(localSnapshot.locallyModified)
            assertNull(localSnapshot.sourceTemplateId)
            assertNull(localSnapshot.statisticsSeriesId)
            assertEquals(Duration.ofSeconds(30), localSnapshot.timerTarget)
            assertEquals(listOf(-1250L, 0L, null), localSnapshot.fields.take(3).map { it.defaultNumberScaled })
            assertEquals(1, localSnapshot.fields.count { it.isMainValue })
            val category = localSnapshot.fields[3]
            assertEquals(category.categoryOptions[1].id, category.defaultCategoryOptionId)
            assertEquals(
                2,
                category.categoryOptions
                    .map { it.id }
                    .toSet()
                    .size,
            )
            assertEquals("Text", localSnapshot.fields[4].defaultText)
            assertEquals(false, top[0].overrides.timerEndSound)
            assertEquals(true, top[0].overrides.timerEndVibration)
            assertEquals(false, top[0].overrides.keepScreenAwake)
            assertEquals(Duration.ZERO, top[0].overrides.startCountdown)
            val linkedIds = linked.fields.map { it.id }.toSet()
            assertTrue(localSnapshot.fields.none { it.id in linkedIds })
            val runtimeAt = Instant.now()
            f.store.library.startSequenceFromTemplate(id, runtimeAt, runtimeAt, java.time.ZoneOffset.UTC)
            f.store.live.endSequenceEarly(runtimeAt.plusMillis(1))
            f.store.plans.createSequencePlanFromTemplate(
                id,
                com.alexandr5476.lifetracing.domain.PlanSchedule
                    .FloatingDay(java.time.LocalDate.of(2026, 11, 5)),
                runtimeAt.plusMillis(2),
            )
            f.store.library.startActivityFromTemplate(
                source.id,
                runtimeAt.plusMillis(3),
                runtimeAt.plusMillis(3),
                java.time.ZoneOffset.UTC,
            )
            val active = f.store.live.getActiveSession()
            val activeRuntime = f.store.live.getActiveRuntime()
            val sourceBeforeEdit = f.store.authoring.getActivityTemplate(source.id)
            val historical =
                f.store.durableFacts().filterKeys {
                    it.startsWith("sequence_snapshot") ||
                        it.contains("execution") ||
                        it.contains("interval") ||
                        it.startsWith("plan_") ||
                        it == "active_session"
                }
            f.templateAction(LibraryTemplateId.Sequence(id), R.string.library_open_template).performClick()
            f.awaitSequenceEditor()
            val linkedIdentity = DraftIdentity.Existing(top[1].id)
            f
                .stepControl(
                    linkedIdentity,
                    R.string.sequence_editor_edit_step,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f
                .stepOverride(
                    linkedIdentity,
                    R.string.sequence_editor_override_sound,
                    R.string.sequence_editor_off,
                ).performSemanticsAction(SemanticsActions.OnClick)
            val countdown = f.stepControl(linkedIdentity, R.string.sequence_editor_override_countdown)
            // Hardware-keyboard editing does not require a visible software IME.
            // Establish the intended edit target without depending on platform IME policy.
            countdown
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .awaitKeyboardFocus()
                .performTextReplacement("0")
            assertEquals(
                Duration.ZERO,
                f
                    .sequenceDraft()
                    .findStep(linkedIdentity)!!
                    .overrides.startCountdown,
            )
            f.invoke(R.string.sequence_editor_done)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            val overrideOnly = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            assertEquals(2L, overrideOnly.sequence.revision)
            assertEquals(canonical.activitySnapshots, overrideOnly.activitySnapshots)
            assertEquals(Duration.ZERO, (overrideOnly.sequence.nodes[1] as ActivityStep).overrides.startCountdown)
            assertEquals(top[1].activitySnapshotId, (overrideOnly.sequence.nodes[1] as ActivityStep).activitySnapshotId)
            f.templateAction(LibraryTemplateId.Sequence(id), R.string.library_open_template).performClick()
            f.awaitSequenceEditor()
            f
                .stepControl(
                    linkedIdentity,
                    R.string.sequence_editor_edit_step,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f
                .stepFieldControl(
                    linkedIdentity,
                    0,
                    R.string.activity_editor_field_name,
                ).performTextReplacement("Local source name")
            f
                .stepOptionControl(
                    linkedIdentity,
                    1,
                    0,
                    R.string.activity_editor_option,
                ).performTextReplacement("Local option")
            f.invoke(R.string.sequence_editor_done)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            val edited = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            assertEquals(3L, edited.sequence.revision)
            assertEquals(canonical.sequence.statisticsSeriesId, edited.sequence.statisticsSeriesId)
            assertEquals(canonical.sequence.nodes.map { it.id }, edited.sequence.nodes.map { it.id })
            assertEquals(
                block.children.map { it.id },
                (edited.sequence.nodes[2] as SequenceRepeatBlock).children.map { it.id },
            )
            val editedStep = edited.sequence.nodes[1] as ActivityStep
            val replacement = edited.activitySnapshots.getValue(editedStep.activitySnapshotId)
            assertNotEquals(linked.id, replacement.id)
            assertTrue(replacement.locallyModified)
            assertEquals(linked.fields[0].sourceFieldId, replacement.fields[0].sourceFieldId)
            assertEquals(linked.fields[0].nameAtCreation, replacement.fields[0].nameAtCreation)
            assertEquals("Local source name", replacement.fields[0].localNameOverride)
            assertEquals("Local option", replacement.fields[1].categoryOptions[0].localLabelOverride)
            assertEquals(sourceBeforeEdit, f.store.authoring.getActivityTemplate(source.id))
            assertEquals(historical, f.store.durableFacts().filterKeys { it in historical })
            val rows = f.store.durableFacts()
            for (table in listOf(
                "activity_snapshots",
                "activity_snapshot_fields",
                "activity_snapshot_category_options",
                "activity_snapshot_settings",
            )) {
                val priorRows = originalSnapshotRows.getValue(table)
                assertTrue(rows.getValue(table).containsAll(priorRows))
            }
            assertEquals(activeRuntime, f.store.live.getActiveRuntime())
            val noOpFacts = f.store.durableFacts()
            f.templateAction(LibraryTemplateId.Sequence(id), R.string.library_open_template).performClick()
            f.awaitSequenceEditor()
            f
                .stepControl(
                    linkedIdentity,
                    R.string.sequence_editor_edit_step,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f
                .stepFieldControl(
                    linkedIdentity,
                    0,
                    R.string.activity_editor_field_name,
                ).performTextReplacement("Local source name")
            f.invoke(R.string.sequence_editor_done)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            assertEquals(noOpFacts, f.store.durableFacts())
            f.reopenOrganization(expectedSession = active) {
                assertEquals(edited, authoring.getSequenceTemplateAuthoringState(id))
                assertEquals(sourceBeforeEdit, authoring.getActivityTemplate(source.id))
                assertEquals(activeRuntime, live.getActiveRuntime())
                assertEquals(historical, durableFacts().filterKeys { it in historical })
            }
        }

    @Test
    fun nativeNewSequenceAuthoringCommitsOnceAndPhysicallyReopensCanonicalConfiguration() =
        journey { f ->
            f.launch()
            f.enterLibrary()
            val retainedLibrary = f.library
            val before = f.store.durableFacts()
            val originalSeries = f.store.statistics.seriesCatalog()
            f.invoke(R.string.library_new_sequence)
            f.awaitSequenceEditor()
            val session = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
            assertEquals(SequenceTemplateEditorTarget.New, session.target)
            f.editorInput(R.string.sequence_editor_name).performTextReplacement("Accessible Sequence")
            f.editorInput(R.string.sequence_editor_short_comment).performTextReplacement("Comment")
            f.invoke(R.string.sequence_editor_settings)
            compose
                .onLazyNode(hasContentDescription(f.text(R.string.sequence_editor_no_live_pause)))
                .performScrollTo()
                .performClick()
            compose
                .onLazyNode(hasContentDescription(f.text(R.string.sequence_editor_auto_advance)))
                .performScrollTo()
                .performClick()
            f.editorInput(R.string.sequence_editor_start_countdown).performTextReplacement("3")
            f.editorInput(R.string.sequence_editor_before_step_countdown).performTextReplacement("2")
            val settingsToggle =
                compose
                    .onLazyNode(hasContentDescription(f.text(R.string.sequence_editor_auto_advance)))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            val settingsCountdown =
                f
                    .editorInput(R.string.sequence_editor_start_countdown)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.SetText]
                    .action!!
            val beforeCollapse = session.controller.state.value
            f.invoke(R.string.sequence_editor_settings)
            compose.runOnIdle {
                settingsToggle()
                settingsCountdown(AnnotatedString("99"))
            }
            assertEquals(beforeCollapse, session.controller.state.value)
            assertEquals(before, f.store.durableFacts())
            f.invoke(R.string.sequence_editor_settings)
            compose.runOnIdle {
                settingsToggle()
                settingsCountdown(AnnotatedString("99"))
            }
            assertEquals(beforeCollapse, session.controller.state.value)
            f.addSequenceField(CustomFieldType.NUMBER)
            f.sequenceControl(0, R.string.sequence_editor_field_name).performTextReplacement("Same")
            f.sequenceControl(0, R.string.activity_editor_unit).performTextReplacement("kg")
            f.sequenceControl(0, R.string.activity_editor_precision).performTextReplacement("2")
            f.sequenceControl(0, R.string.activity_editor_default).performTextReplacement("0")
            f.sequenceControl(0, R.string.sequence_editor_main_value).performClick()
            f.addSequenceField(CustomFieldType.CATEGORY)
            f.sequenceControl(1, R.string.sequence_editor_field_name).performTextReplacement("Same")
            repeat(2) { option ->
                f.sequenceControl(1, R.string.activity_editor_add_option).performClick()
                f.sequenceOptionControl(1, option, R.string.activity_editor_option).performTextReplacement("Same")
            }
            f.sequenceOptionControl(1, 1, R.string.activity_editor_default).performClick()
            f.addSequenceField(CustomFieldType.TEXT)
            f.sequenceControl(2, R.string.sequence_editor_field_name).performTextReplacement("Same")
            f.sequenceControl(2, R.string.activity_editor_default).performTextReplacement("Default text")
            val submitted = f.sequenceDraft()
            f.recreate()
            assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertEquals(before, f.store.durableFacts())
            val save = compose.onLazyNode(hasText(f.text(R.string.sequence_editor_done))).performScrollTo()
            val retainedSave = save.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            save
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .awaitKeyboardFocus()
                .performKeyInput { pressKey(Key.Enter) }
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            f.libraryLanding()
            compose.runOnIdle { retainedSave() }
            assertSame(retainedLibrary, f.library)
            val item =
                f.store.library
                    .getAll()
                    .single()
            val id = (item.id as LibraryTemplateId.Sequence).id
            val canonical = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            val draft = canonical.toAuthoringDraft()
            assertEquals(1L, canonical.sequence.revision)
            assertEquals(submitted.settings, draft.settings)
            assertEquals(submitted.noLiveTimeAccounting, draft.noLiveTimeAccounting)
            assertEquals("Comment", draft.shortComment)
            assertEquals(
                listOf(CustomFieldType.NUMBER, CustomFieldType.CATEGORY, CustomFieldType.TEXT),
                draft.fields.map {
                    it.type
                },
            )
            assertEquals(listOf(0, 1, 2), draft.fields.map { it.position })
            assertEquals(0L, draft.fields[0].defaultNumberScaled)
            assertEquals("kg", draft.fields[0].unit)
            assertEquals(2, draft.fields[0].displayPrecision)
            assertTrue(draft.fields[0].isMainValue)
            assertEquals(draft.fields[1].categoryOptions[1].identity, draft.fields[1].defaultCategoryOption)
            assertEquals(
                2,
                draft.fields[1]
                    .categoryOptions
                    .map { it.identity }
                    .toSet()
                    .size,
            )
            assertTrue(draft.fields.all { it.identity is DraftIdentity.Existing })
            assertTrue(draft.fields[1].categoryOptions.all { it.identity is DraftIdentity.Existing })
            assertEquals("Default text", draft.fields[2].defaultText)
            f.reopenOrganization {
                assertEquals(canonical, authoring.getSequenceTemplateAuthoringState(id))
                assertEquals(listOf(item), library.search("Accessible Sequence"))
                val originalSeriesIds = originalSeries.map { it.id }.toSet()
                val reopenedSeries = statistics.seriesCatalog()
                assertEquals(
                    originalSeriesIds + canonical.sequence.statisticsSeriesId,
                    reopenedSeries.map { it.id }.toSet(),
                )
                assertEquals(originalSeries, reopenedSeries.filter { it.id in originalSeriesIds })
                assertEquals(0L to 0L, executionCounts())
                assertEquals(emptyList<Any>(), durableFacts()["plan_entries"])
            }
        }

    @Test
    fun nativeExistingSequenceFieldsPreserveCompatibleIdentityAndFrozenStepsOnPhysicalReopen() =
        journey { f ->
            val ids = f.seedDuplicates()
            val id = (ids.last() as LibraryTemplateId.Sequence).id
            val initial = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            f.store.authoring.saveSequenceTemplate(
                id,
                initial.sequence.revision,
                initial.toAuthoringDraft().copy(
                    fields =
                        listOf(
                            SequenceFieldDraft(
                                DraftIdentity.New("number"),
                                0,
                                "Same",
                                CustomFieldType.NUMBER,
                                unit = "kg",
                            ),
                            SequenceFieldDraft(
                                DraftIdentity.New("category"),
                                1,
                                "Same",
                                CustomFieldType.CATEGORY,
                                categoryOptions =
                                    listOf(
                                        SequenceCategoryOptionDraft(DraftIdentity.New("one"), 0, "Same"),
                                        SequenceCategoryOptionDraft(DraftIdentity.New("two"), 1, "Same"),
                                    ),
                            ),
                            SequenceFieldDraft(DraftIdentity.New("text"), 2, "Same", CustomFieldType.TEXT),
                        ),
                ),
                f.seedTime.plusSeconds(1),
            )
            val seeded = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            val original = seeded.toAuthoringDraft()
            val frozen = f.frozenFacts()
            f.launch()
            f.enterLibrary()
            f
                .templateAction(
                    LibraryTemplateId.Sequence(id),
                    R.string.library_open_template,
                ).performScrollTo()
                .performClick()
            f.awaitSequenceEditor()
            f.sequenceControl(0, R.string.sequence_editor_field_name).performTextReplacement("Renamed")
            f.sequenceControl(0, R.string.activity_editor_default).performTextReplacement("-1.25")
            f.sequenceControl(0, R.string.sequence_editor_main_value).performClick()
            f.sequenceOptionControl(1, 0, R.string.sequence_editor_remove).performClick()
            f.sequenceOptionControl(1, 0, R.string.activity_editor_option).performTextReplacement("Renamed option")
            f.sequenceOptionControl(1, 0, R.string.activity_editor_default).performClick()
            f.sequenceControl(2, R.string.activity_editor_default).performTextReplacement("Text")
            compose
                .onLazyNode(
                    hasContentDescription(
                        f.text(
                            R.string.activity_editor_change_type_context,
                            f.text(R.string.activity_editor_number),
                            f.sequenceContext(2),
                        ),
                    ),
                ).performScrollTo()
                .performClick()
            f.sequenceControl(2, R.string.activity_editor_unit).performTextReplacement("reps")
            f.sequenceControl(2, R.string.activity_editor_default).performTextReplacement("5")
            f.invoke(R.string.sequence_editor_settings)
            compose
                .onLazyNode(hasContentDescription(f.text(R.string.sequence_editor_keep_awake)))
                .performScrollTo()
                .performClick()
            f.editorInput(R.string.sequence_editor_before_step_countdown).performTextReplacement("4")
            val submitted = f.sequenceDraft()
            f.invoke(R.string.sequence_editor_done)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            f.libraryLanding()
            val canonical = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            val draft = canonical.toAuthoringDraft()
            assertEquals(seeded.sequence.revision + 1, canonical.sequence.revision)
            assertEquals(seeded.sequence.statisticsSeriesId, canonical.sequence.statisticsSeriesId)
            assertEquals(original.nodes, draft.nodes)
            assertEquals(seeded.activitySnapshots, canonical.activitySnapshots)
            assertEquals(original.fields[0].identity, draft.fields[0].identity)
            assertEquals(original.fields[1].identity, draft.fields[1].identity)
            assertEquals(
                original.fields[1].categoryOptions[1].identity,
                draft.fields[1]
                    .categoryOptions
                    .single()
                    .identity,
            )
            assertEquals(
                draft.fields[1]
                    .categoryOptions
                    .single()
                    .identity,
                draft.fields[1].defaultCategoryOption,
            )
            assertNotEquals(original.fields[2].identity, draft.fields[2].identity)
            assertEquals(-1250L, draft.fields[0].defaultNumberScaled)
            assertEquals(5000L, draft.fields[2].defaultNumberScaled)
            assertEquals(listOf(0, 1, 2), draft.fields.map { it.position })
            assertEquals(submitted.settings, draft.settings)
            assertEquals(frozen, f.frozenFacts())
            // A temporary unit edit restored before Done is a semantic no-op with compatible identity.
            f
                .templateAction(
                    LibraryTemplateId.Sequence(id),
                    R.string.library_open_template,
                ).performScrollTo()
                .performClick()
            f.awaitSequenceEditor()
            f.sequenceControl(0, R.string.activity_editor_unit).performTextReplacement("temporary")
            f.sequenceControl(0, R.string.activity_editor_unit).performTextReplacement("kg")
            f.invoke(R.string.sequence_editor_done)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            f.libraryLanding()
            assertEquals(canonical, f.store.authoring.getSequenceTemplateAuthoringState(id))
            f.reopenOrganization {
                assertEquals(canonical, authoring.getSequenceTemplateAuthoringState(id))
                assertEquals(frozen, durableFacts().filterKeys { it in frozen })
                assertEquals(0L to 0L, executionCounts())
            }
        }

    @Test
    fun productionSequenceConflictExplicitReloadAndDiscardLeaveNoPartialWriteOnReopen() =
        journey { f ->
            val ids = f.seedDuplicates()
            val id = (ids.last() as LibraryTemplateId.Sequence).id
            f.launch()
            f.enterLibrary()
            f
                .templateAction(
                    LibraryTemplateId.Sequence(id),
                    R.string.library_open_template,
                ).performScrollTo()
                .performClick()
            f.awaitSequenceEditor()
            val session = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
            val canonical = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            f.store.authoring.saveSequenceTemplate(
                id,
                canonical.sequence.revision,
                canonical.toAuthoringDraft().copy(name = "Concurrent"),
                f.seedTime.plusSeconds(1),
            )
            val before = f.store.durableFacts()
            f.editorInput(R.string.sequence_editor_name).performTextReplacement("Unsaved")
            f.invoke(R.string.sequence_editor_done)
            f.await { session.controller.state.value.save is SequenceTemplateEditorSave.Failure }
            assertTrue((session.controller.state.value.save as SequenceTemplateEditorSave.Failure).isConflict)
            assertEquals(before, f.store.durableFacts())
            f.recreate()
            assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertEquals("Unsaved", f.sequenceDraft().name)
            f.invoke(R.string.activity_editor_reload)
            f.awaitSequenceEditor()
            assertEquals("Concurrent", f.sequenceDraft().name)
            f.editorInput(R.string.sequence_editor_short_comment).performTextReplacement("Discard me")
            f.invoke(R.string.sequence_editor_back)
            compose.onNode(isDialog()).assertIsDisplayed()
            assertTrue(session.controller.state.value.discardConfirmationVisible)
            f.invoke(R.string.sequence_editor_continue)
            assertFalse(session.controller.state.value.discardConfirmationVisible)
            assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertEquals("Discard me", f.sequenceDraft().shortComment)
            assertEquals(before, f.store.durableFacts())
            f.invoke(R.string.sequence_editor_back)
            compose.onNode(isDialog()).assertIsDisplayed()
            f.invoke(R.string.sequence_editor_discard)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            f.reopenUnchanged(before)
        }

    @Test
    fun nativeSequenceManipulationRetainsSelectionAcrossRecreationAndDeliversApplyToLibraryOnce() =
        journey { f ->
            val id = (f.seedDuplicates().last() as LibraryTemplateId.Sequence).id
            val initial = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            f.store.authoring.saveSequenceTemplate(
                id,
                initial.sequence.revision,
                initial.toAuthoringDraft().copy(
                    nodes =
                        initial.toAuthoringDraft().nodes +
                            listOf(
                                SequenceNodeDraft.Repeat(
                                    com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft(
                                        DraftIdentity.New("one"),
                                        1,
                                        2,
                                        emptyList(),
                                    ),
                                ),
                                SequenceNodeDraft.Repeat(
                                    com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft(
                                        DraftIdentity.New("two"),
                                        2,
                                        2,
                                        emptyList(),
                                    ),
                                ),
                            ),
                ),
                f.seedTime.plusSeconds(1),
            )
            val seeded = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            val top = seeded.sequence.nodes.first() as com.alexandr5476.lifetracing.domain.ActivityStep
            val repeat = seeded.sequence.nodes.last() as com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
            f.launch()
            f.enterLibrary()
            f
                .templateAction(
                    LibraryTemplateId.Sequence(id),
                    R.string.library_open_template,
                ).performScrollTo()
                .performClick()
            f.awaitSequenceEditor()
            keyboard.enter()
            val session = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
            val entry =
                f.text(
                    R.string.activity_editor_control_context,
                    f.text(R.string.sequence_manipulation_enter),
                    f.text(R.string.sequence_manipulation_top_step, "Shared name", 1),
                )
            val button = compose.onLazyNode(hasContentDescription(entry))
            button.performSemanticsAction(SemanticsActions.RequestFocus).awaitKeyboardFocus()
            keyboard.sendKey(button, KeyEvent.KEYCODE_ENTER)
            compose.waitForIdle()
            val selected = session.controller.state.value.manipulation
            assertEquals(DraftIdentity.Existing(top.id), selected!!.selected)
            f.recreate()
            assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertEquals(selected, session.controller.state.value.manipulation)
            val beforeApply = f.library.state.value.browseRequestId
            f.invoke(R.string.sequence_manipulation_move)
            val destination =
                com.alexandr5476.lifetracing.editor.sequenceDestinationKey(
                    com.alexandr5476.lifetracing.editor
                        .SequenceDropDestination(DraftIdentity.Existing(repeat.id), 0),
                )
            compose.onNodeWithTag("sequence-destinations").performScrollToKey(destination)
            compose.onNodeWithTag(destination).performSemanticsAction(SemanticsActions.OnClick)
            f.invoke(R.string.sequence_editor_apply)
            f.await { session.controller.state.value.appliedGeneration == 1L }
            f.await { f.library.state.value.browseRequestId == beforeApply + 1 }
            val canonical = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            assertEquals(seeded.sequence.revision + 1, canonical.sequence.revision)
            assertEquals(seeded.activitySnapshots, canonical.activitySnapshots)
            assertEquals(
                listOf(
                    top.id,
                ),
                (canonical.sequence.nodes.last() as com.alexandr5476.lifetracing.domain.SequenceRepeatBlock)
                    .children
                    .map {
                        it.id
                    },
            )
            f.recreate()
            assertEquals(beforeApply + 1, f.library.state.value.browseRequestId)
            f.invoke(R.string.sequence_editor_back)
            f.libraryLanding()
            f.reopenOrganization { assertEquals(canonical, authoring.getSequenceTemplateAuthoringState(id)) }
        }

    @Test
    fun nativeStepSourceCommandsPreserveIdentityProvenanceAndFrozenFactsThroughPhysicalReopen() =
        journey { f ->
            val option = DraftIdentity.New("option")
            val source =
                f.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Shared name",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                        settings = ActivityTemplateSettings(startCountdown = Duration.ofHours(1)),
                        fields =
                            listOf(
                                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                    DraftIdentity.New("zero"),
                                    0,
                                    "Same",
                                    CustomFieldType.NUMBER,
                                    displayPrecision = 2,
                                    defaultNumberScaled = 0,
                                    isMainValue = true,
                                ),
                                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                    DraftIdentity.New("missing"),
                                    1,
                                    "Same",
                                    CustomFieldType.NUMBER,
                                    displayPrecision = 0,
                                ),
                                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                    DraftIdentity.New("text"),
                                    2,
                                    "Text",
                                    CustomFieldType.TEXT,
                                    defaultText = "Frozen text",
                                ),
                                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                    DraftIdentity.New("category"),
                                    3,
                                    "Category",
                                    CustomFieldType.CATEGORY,
                                    defaultCategoryOption = option,
                                    categoryOptions =
                                        listOf(
                                            com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft(
                                                option,
                                                0,
                                                "Same option",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                    createdAt = f.seedTime,
                )
            val unrelated =
                f.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Shared name", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = f.seedTime,
                )
            val overrides =
                com.alexandr5476.lifetracing.domain.SequenceStepOverrides(
                    startCountdown = Duration.ZERO,
                    timerEndSound = false,
                    timerEndVibration = false,
                    keepScreenAwake = false,
                )

            fun linked(
                key: String,
                position: Int,
            ) = ActivityStepDraft(
                DraftIdentity.New(key),
                position,
                StepActivityDraft.FromTemplate(source.id),
                overrides,
            )
            val sequence =
                f.store.authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "Source sequence",
                        null,
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(linked("first", 0)),
                                SequenceNodeDraft.Repeat(
                                    com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft(
                                        DraftIdentity.New("repeat"),
                                        1,
                                        2,
                                        listOf(linked("second", 0)),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = f.seedTime,
                )
            val initial = f.store.authoring.getSequenceTemplateAuthoringState(sequence.id)!!
            val first = initial.sequence.nodes.first() as com.alexandr5476.lifetracing.domain.ActivityStep
            val second = (initial.sequence.nodes.last() as SequenceRepeatBlock).children.single()
            val historicalStart =
                f.store.library.startSequenceFromTemplate(
                    sequence.id,
                    f.seedTime,
                    f.seedTime,
                    java.time.ZoneOffset.UTC,
                )
            assertTrue(historicalStart.currentChild != null)
            val historicalField =
                initial.activitySnapshots
                    .getValue(first.activitySnapshotId)
                    .fields
                    .first()
                    .id
            val historicalValues =
                f.store.live.updateCurrentSequenceStepValues(
                    historicalStart.execution.id,
                    historicalStart.execution.currentOccurrenceId!!,
                    listOf(
                        com.alexandr5476.lifetracing.domain.ActivityExecutionValueOverride(
                            historicalField,
                            com.alexandr5476.lifetracing.domain
                                .NumberExecutionValue(historicalField, 7_000),
                        ),
                    ),
                    f.seedTime.plusMillis(500),
                )
            assertTrue(
                historicalValues.values.any {
                    it ==
                        com.alexandr5476.lifetracing.domain
                            .NumberExecutionValue(historicalField, 7_000)
                },
            )
            f.store.live.endSequenceEarly(f.seedTime.plusSeconds(1))
            val history = f.store.history.getSequenceDetail(historicalStart.execution.id)!!
            f.store.plans.createSequencePlanFromTemplate(
                sequence.id,
                com.alexandr5476.lifetracing.domain.PlanSchedule
                    .FloatingDay(java.time.LocalDate.of(2026, 11, 5)),
                f.seedTime.plusSeconds(2),
            )
            f.store.library.startActivityFromTemplate(
                source.id,
                f.seedTime.plusSeconds(3),
                f.seedTime.plusSeconds(3),
                java.time.ZoneOffset.UTC,
            )
            val active = f.store.live.getActiveSession()
            val runtime = f.store.live.getActiveRuntime()
            val frozen =
                f.store.durableFacts().filterKeys {
                    it.startsWith("sequence_snapshot") ||
                        it.contains("execution") ||
                        it.contains("interval") ||
                        it.startsWith("plan_") ||
                        it == "active_session"
                }
            val oldSnapshots = f.store.durableFacts().filterKeys { it.startsWith("activity_snapshot") }
            f.launch()
            f.enterLibrary()

            fun openEditor() {
                f
                    .templateAction(
                        LibraryTemplateId.Sequence(sequence.id),
                        R.string.library_open_template,
                    ).performScrollTo()
                    .performClick()
                f.awaitSequenceEditor()
            }
            openEditor()
            val identity = DraftIdentity.Existing(first.id)
            f.stepControl(identity, R.string.sequence_editor_edit_step).performSemanticsAction(SemanticsActions.OnClick)
            val numericSession = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
            val numericRefresh = f.library.state.value.browseRequestId
            val beforeRejectedInput = f.store.durableFacts()
            val sourceCallbacks =
                listOf(
                    R.string.sequence_editor_update_from_source,
                    R.string.sequence_editor_update_source,
                    R.string.sequence_editor_save_as_new_template,
                ).map { operation ->
                    compose
                        .onLazyNode(hasText(f.text(operation)) and hasClickAction())
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                        .action!!
                }
            f.stepFieldControl(identity, 0, R.string.activity_editor_default).performTextReplacement("-1.234")
            compose.runOnIdle {
                sourceCallbacks.forEach { it() }
                numericSession.controller.updateStepFromSource(first.id)
                numericSession.controller.updateSourceTemplate(first.id)
                numericSession.controller.saveStepAsNewTemplate(first.id)
                numericSession.controller.save()
            }
            assertTrue(
                numericSession.controller.state.value.hasInvalidInput(
                    numericSession.controller.state.value
                        .readyDraft()!!,
                ),
            )
            assertEquals(beforeRejectedInput, f.store.durableFacts())
            assertEquals(numericRefresh, f.library.state.value.browseRequestId)
            f.recreate()
            assertSame(numericSession, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertEquals(
                "-1.234",
                numericSession.controller.state.value.textInputs.values
                    .single()
                    .text,
            )
            f
                .stepFieldControl(identity, 0, R.string.activity_editor_default)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            f.stepFieldControl(identity, 0, R.string.activity_editor_default).performTextReplacement("0")
            f.stepFieldControl(identity, 0, R.string.activity_editor_precision).performTextReplacement("9")
            compose.runOnIdle { numericSession.controller.save() }
            assertEquals(beforeRejectedInput, f.store.durableFacts())
            f.stepFieldControl(identity, 0, R.string.activity_editor_precision).performTextReplacement("2")
            assertTrue(
                numericSession.controller.state.value
                    .sourceActionsAllowed(),
            )
            compose.runOnIdle { sourceCallbacks.forEach { it() } }
            assertEquals(beforeRejectedInput, f.store.durableFacts())
            f
                .stepControl(
                    identity,
                    R.string.sequence_editor_short_comment,
                ).performTextReplacement("Locally committed Step")
            f.stepFieldControl(identity, 0, R.string.activity_editor_field_name).performTextReplacement("Local field")
            f.stepOptionControl(identity, 3, 0, R.string.activity_editor_option).performTextReplacement("Local option")
            f.invoke(R.string.sequence_editor_done)
            f.await { f.host().sequenceTemplateEditorRouteSessions.activeSession == null }
            f.libraryLanding()
            val locallyCommitted = f.store.authoring.getSequenceTemplateAuthoringState(sequence.id)!!
            val localStep = locallyCommitted.sequence.nodes.first() as com.alexandr5476.lifetracing.domain.ActivityStep
            val localSnapshot = locallyCommitted.activitySnapshots.getValue(localStep.activitySnapshotId)
            assertTrue(localSnapshot.locallyModified)
            assertNotEquals(first.activitySnapshotId, localStep.activitySnapshotId)
            openEditor()
            f.stepControl(identity, R.string.sequence_editor_edit_step).performSemanticsAction(SemanticsActions.OnClick)
            keyboard.enter()
            val session = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
            val beforeRefresh = f.library.state.value.browseRequestId
            val retired = mutableListOf<() -> Boolean>()

            fun sourceAction(
                operation: Int,
                generation: Long,
            ) {
                val node = compose.onLazyNode(hasText(f.text(operation)) and hasClickAction())
                retired += node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                keyboard.enter(node)
                node.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.awaitFocus(node)
                keyboard.sendKey(node, KeyEvent.KEYCODE_ENTER)
                f.await { session.controller.state.value.appliedGeneration == generation }
                f.await { f.library.state.value.browseRequestId == beforeRefresh + generation }
                compose.runOnIdle { retired.forEach { it() } }
                assertEquals(generation, session.controller.state.value.appliedGeneration)
            }
            sourceAction(R.string.sequence_editor_update_source, 1)
            val updatedSource = f.store.authoring.getActivityTemplate(source.id)!!
            assertEquals(2L, updatedSource.revision)
            assertEquals("Locally committed Step", updatedSource.shortComment)
            assertEquals(source.statisticsSeriesId, updatedSource.statisticsSeriesId)
            assertEquals(source.fields.map { it.id }, updatedSource.fields.map { it.id })
            assertEquals(
                source.fields.last().categoryOptions.map {
                    it.id
                },
                updatedSource.fields
                    .last()
                    .categoryOptions
                    .map { it.id },
            )
            assertEquals(locallyCommitted, f.store.authoring.getSequenceTemplateAuthoringState(sequence.id))
            f.recreate()
            assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            assertEquals(beforeRefresh + 1, f.library.state.value.browseRequestId)
            // Change the source separately; refreshing must use this complete current configuration.
            val advanced =
                f.store.authoring.saveActivityTemplate(
                    source.id,
                    updatedSource.revision,
                    updatedSource.toAuthoringDraft().copy(shortComment = "Separately changed source"),
                    Instant.now(),
                )
            // Recreation preserves the logical disclosure; inspection must not toggle it closed.
            f.stepControl(identity, R.string.sequence_editor_close_step).assertIsDisplayed()
            sourceAction(R.string.sequence_editor_update_from_source, 2)
            val refreshed = f.store.authoring.getSequenceTemplateAuthoringState(sequence.id)!!
            val refreshedStep = refreshed.sequence.nodes.first() as com.alexandr5476.lifetracing.domain.ActivityStep
            val refreshedSnapshot = refreshed.activitySnapshots.getValue(refreshedStep.activitySnapshotId)
            assertEquals(first.id, refreshedStep.id)
            assertEquals(first.overrides, refreshedStep.overrides)
            assertNotEquals(localStep.activitySnapshotId, refreshedStep.activitySnapshotId)
            assertEquals(advanced.revision, refreshedSnapshot.sourceRevision)
            assertEquals(source.statisticsSeriesId, refreshedSnapshot.statisticsSeriesId)
            assertTrue(refreshedSnapshot.fields.none { field -> localSnapshot.fields.any { it.id == field.id } })
            assertTrue(
                refreshedSnapshot.fields.last().categoryOptions.none { option ->
                    localSnapshot.fields.last().categoryOptions.any {
                        it.id ==
                            option.id
                    }
                },
            )
            assertFalse(refreshedSnapshot.locallyModified)
            assertEquals("Separately changed source", refreshedSnapshot.shortComment)
            assertEquals(advanced.fields.map { it.id }, refreshedSnapshot.fields.map { it.sourceFieldId })
            assertEquals(
                advanced.fields.last().categoryOptions.map {
                    it.id
                },
                refreshedSnapshot.fields
                    .last()
                    .categoryOptions
                    .map { it.sourceOptionId },
            )
            assertEquals(
                listOf("Local field", "Same", "Text", "Category"),
                refreshedSnapshot.fields.map { it.nameAtCreation },
            )
            assertTrue(refreshedSnapshot.fields.all { it.localNameOverride == null })
            assertEquals(
                "Local option",
                refreshedSnapshot.fields
                    .last()
                    .categoryOptions
                    .single()
                    .labelAtCreation,
            )
            assertNull(
                refreshedSnapshot.fields
                    .last()
                    .categoryOptions
                    .single()
                    .localLabelOverride,
            )
            assertEquals(listOf(0L, null), refreshedSnapshot.fields.take(2).map { it.defaultNumberScaled })
            assertTrue(refreshedSnapshot.fields.first().isMainValue)
            sourceAction(R.string.sequence_editor_save_as_new_template, 3)
            val result = f.store.authoring.getSequenceTemplateAuthoringState(sequence.id)!!
            val finalStep = result.sequence.nodes.first() as com.alexandr5476.lifetracing.domain.ActivityStep
            val finalSnapshot = result.activitySnapshots.getValue(finalStep.activitySnapshotId)
            val newSourceId = finalSnapshot.sourceTemplateId!!
            val created = f.store.authoring.getActivityTemplate(newSourceId)!!
            assertNotEquals(source.id, created.id)
            assertNotEquals(source.statisticsSeriesId, created.statisticsSeriesId)
            assertEquals(created.statisticsSeriesId, finalSnapshot.statisticsSeriesId)
            assertEquals(1L, created.revision)
            assertEquals(created.revision, finalSnapshot.sourceRevision)
            assertEquals(sequence.revision + 3, result.sequence.revision)
            assertEquals(first.id, finalStep.id)
            assertEquals(first.position, finalStep.position)
            assertEquals(sequence.id, result.sequence.id)
            assertEquals(sequence.statisticsSeriesId, result.sequence.statisticsSeriesId)
            assertEquals(initial.sequence.nodes.drop(1), result.sequence.nodes.drop(1))
            assertEquals(first.overrides, finalStep.overrides)
            assertNotEquals(refreshedStep.activitySnapshotId, finalStep.activitySnapshotId)
            assertFalse(finalSnapshot.locallyModified)
            assertEquals(refreshedSnapshot.shortComment, finalSnapshot.shortComment)
            assertEquals(refreshedSnapshot.settings, finalSnapshot.settings)
            assertEquals(refreshedSnapshot.name, finalSnapshot.name)
            assertEquals(refreshedSnapshot.timeTrackingMode, finalSnapshot.timeTrackingMode)
            assertEquals(refreshedSnapshot.timerTarget, finalSnapshot.timerTarget)
            assertTrue(finalSnapshot.fields.none { field -> refreshedSnapshot.fields.any { it.id == field.id } })
            assertTrue(
                finalSnapshot.fields.last().categoryOptions.none { option ->
                    refreshedSnapshot.fields.last().categoryOptions.any {
                        it.id ==
                            option.id
                    }
                },
            )
            assertTrue(
                created.fields.last().categoryOptions.none { option ->
                    advanced.fields.last().categoryOptions.any {
                        it.id ==
                            option.id
                    }
                },
            )
            assertEquals(
                refreshedSnapshot.fields.map {
                    Triple(it.type, it.unit, it.displayPrecision)
                },
                finalSnapshot.fields.map { Triple(it.type, it.unit, it.displayPrecision) },
            )
            assertEquals(refreshedSnapshot.fields.map { it.defaultText }, finalSnapshot.fields.map { it.defaultText })
            assertEquals(
                refreshedSnapshot.fields.map { it.nameAtCreation },
                finalSnapshot.fields.map { it.nameAtCreation },
            )
            assertTrue(finalSnapshot.fields.all { it.localNameOverride == null })
            assertEquals(
                finalSnapshot.fields
                    .last()
                    .categoryOptions
                    .single()
                    .id,
                finalSnapshot.fields.last().defaultCategoryOptionId,
            )
            assertEquals(
                created.fields
                    .last()
                    .categoryOptions
                    .single()
                    .id,
                created.fields.last().defaultCategoryOptionId,
            )
            assertEquals(0L, created.fields.first().defaultNumberScaled)
            assertEquals(history, f.store.history.getSequenceDetail(historicalStart.execution.id))
            assertEquals(
                refreshedSnapshot.fields.map {
                    it.defaultNumberScaled
                },
                finalSnapshot.fields.map { it.defaultNumberScaled },
            )
            assertEquals(created.fields.map { it.id }, finalSnapshot.fields.map { it.sourceFieldId })
            assertTrue(created.fields.none { field -> source.fields.any { it.id == field.id } })
            assertEquals(
                created.fields.last().categoryOptions.map {
                    it.id
                },
                finalSnapshot.fields
                    .last()
                    .categoryOptions
                    .map { it.sourceOptionId },
            )
            assertEquals(advanced, f.store.authoring.getActivityTemplate(source.id))
            assertEquals(unrelated, f.store.authoring.getActivityTemplate(unrelated.id))
            assertEquals(second, (result.sequence.nodes.last() as SequenceRepeatBlock).children.single())
            assertEquals(
                initial.activitySnapshots.getValue(second.activitySnapshotId),
                result.activitySnapshots.getValue(second.activitySnapshotId),
            )
            assertEquals(frozen, f.store.durableFacts().filterKeys { it in frozen })
            for ((table, rows) in oldSnapshots) {
                assertTrue(
                    "Historical snapshot rows in $table",
                    f.store
                        .durableFacts()
                        .getValue(table)
                        .containsAll(rows),
                )
            }
            assertEquals(runtime, f.store.live.getActiveRuntime())
            f.recreate()
            assertEquals(beforeRefresh + 3, f.library.state.value.browseRequestId)
            assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            compose.runOnIdle { retired.forEach { it() } }
            assertEquals(3L, session.controller.state.value.appliedGeneration)
            f.invoke(R.string.sequence_editor_back)
            f.libraryLanding()
            f.templateAction(LibraryTemplateId.Activity(newSourceId), R.string.library_open_template).assertExists()
            f.reopenOrganization(expectedSession = active) {
                assertEquals(result, authoring.getSequenceTemplateAuthoringState(sequence.id))
                assertEquals(history, this.history.getSequenceDetail(historicalStart.execution.id))
                assertEquals(created, authoring.getActivityTemplate(created.id))
                assertEquals(advanced, authoring.getActivityTemplate(source.id))
                assertEquals(unrelated, authoring.getActivityTemplate(unrelated.id))
                assertEquals(runtime, live.getActiveRuntime())
                assertEquals(frozen, durableFacts().filterKeys { it in frozen })
                val statuses = authoring.getActivityTemplateSourceStatuses(listOf(source.id, created.id))
                assertEquals(advanced.revision, statuses.getValue(source.id).revision)
                assertEquals(1L, statuses.getValue(created.id).revision)
                assertFalse(statuses.values.any { it.isArchived })
            }
        }

    @Test
    fun productionUpdateFromSourceRecoveryRetainsSessionAcrossActivityRecreationWithoutWriterReplay() =
        productionSourceRecovery(R.string.sequence_editor_update_from_source)

    @Test
    fun productionUpdateSourceRecoveryRetainsSessionAcrossActivityRecreationWithoutWriterReplay() =
        productionSourceRecovery(R.string.sequence_editor_update_source)

    @Test
    fun productionSaveAsNewRecoveryRetainsSessionAcrossActivityRecreationWithoutWriterReplay() =
        productionSourceRecovery(R.string.sequence_editor_save_as_new_template)

    private fun productionSourceRecovery(operation: Int) =
        journey { f ->
            val id = (f.seedDuplicates().last() as LibraryTemplateId.Sequence).id
            val seed = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            val step = seed.sequence.nodes.single() as com.alexandr5476.lifetracing.domain.ActivityStep
            f.store.authoring.saveStepConfiguration(
                id,
                step.id,
                seed.sequence.revision,
                seed.activitySnapshots
                    .getValue(
                        step.activitySnapshotId,
                    ).toAuthoringDraft()
                    .copy(shortComment = "Committed local Step"),
                f.seedTime.plusSeconds(1),
            )
            f.launch()
            f.enterLibrary()
            val scope =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
                )
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val reads =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val attempts =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val accepted =
                java.util.concurrent.atomic
                    .AtomicInteger()

            fun checkpoint(phase: String) {
                val message =
                    "Source recovery operation=${f.text(operation)} phase=$phase " +
                        "attempts=${attempts.get()} accepted=${accepted.get()} reads=${reads.get()}"
                android.util.Log.i("LibrarySourceRecovery", message)
                // API 26 managed devices cannot retain additionalTestOutput. Send phases
                // to the host as well, so emulator loss cannot erase the last reached step.
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
            }

            suspend fun gatedWriter(action: () -> Unit) {
                attempts.incrementAndGet()
                started.complete(Unit)
                release.await()
                action()
                accepted.incrementAndGet()
            }
            val controller =
                com.alexandr5476.lifetracing.editor.SequenceTemplateEditorController(
                    scope,
                    SequenceTemplateEditorTarget.Existing(id),
                    { target ->
                        if (reads.incrementAndGet() ==
                            2
                        ) {
                            error("Committed canonical read failure")
                        } else {
                            f.store.authoring.getSequenceTemplateAuthoringState(target)
                        }
                    },
                    { error("No picker catalog") },
                    { _, _, _ -> error("No create") },
                    { _, _, _, _ -> error("No save") },
                    Instant::now,
                    loadSourceStatuses = { f.store.authoring.getActivityTemplateSourceStatuses(it) },
                    updateFromSource = { sequence, node, revision, at ->
                        check(operation == R.string.sequence_editor_update_from_source)
                        gatedWriter { f.store.authoring.updateStepFromSourceTemplate(sequence, node, revision, at) }
                    },
                    updateSource = { sequence, node, revision, sourceRevision, at ->
                        check(operation == R.string.sequence_editor_update_source)
                        gatedWriter {
                            f.store.authoring.updateSourceTemplateFromStep(
                                sequence,
                                node,
                                revision,
                                sourceRevision,
                                at,
                            )
                        }
                    },
                    saveAsNewSource = { sequence, node, revision, at ->
                        check(operation == R.string.sequence_editor_save_as_new_template)
                        gatedWriter {
                            f.store.authoring.saveStepAsNewActivityTemplate(
                                sequence,
                                node,
                                revision,
                                savedAt = at,
                            )
                        }
                    },
                )
            try {
                f.await { controller.state.value.load is SequenceTemplateEditorLoad.Ready }
                compose.runOnIdle {
                    f.host().sequenceTemplateEditorRouteSessions.acquire(
                        controller.target,
                    ) { controller }
                }
                f
                    .templateAction(
                        LibraryTemplateId.Sequence(id),
                        R.string.library_open_template,
                    ).performScrollTo()
                    .performClick()
                f.awaitSequenceEditor()
                val session = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
                assertSame(controller, session.controller)
                f
                    .stepControl(
                        DraftIdentity.Existing(step.id),
                        R.string.sequence_editor_edit_step,
                    ).performSemanticsAction(SemanticsActions.OnClick)
                val before = f.library.state.value.browseRequestId
                val action = compose.onLazyNode(hasText(f.text(operation)) and hasClickAction())
                val retained = action.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                keyboard.enter(action)
                action.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.awaitFocus(action)
                keyboard.sendKey(action, KeyEvent.KEYCODE_ENTER)
                f.await { started.isCompleted }
                compose.runOnIdle {
                    retained()
                    retained()
                }
                assertEquals(1, attempts.get())
                assertEquals(0, accepted.get())
                compose.runOnIdle { release.complete(Unit) }
                f.await {
                    (controller.state.value.save as? SequenceTemplateEditorSave.Failure)?.committedAwaitingReload ==
                        true
                }
                assertEquals(1, accepted.get())
                checkpoint("committed-read-failed")
                val committed = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
                val durable = f.store.durableFacts()
                assertEquals(before, f.library.state.value.browseRequestId)
                checkpoint("before-recreate")
                f.recreate()
                checkpoint("after-recreate")
                assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
                assertTrue((controller.state.value.save as SequenceTemplateEditorSave.Failure).committedAwaitingReload)
                assertEquals(2, reads.get())
                compose.runOnIdle { retained() }
                val retry = compose.onLazyNode(hasText(f.text(R.string.sequence_editor_retry)) and hasClickAction())
                checkpoint("before-retry-focus")
                keyboard.enter(retry)
                retry.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.awaitFocus(retry)
                keyboard.sendKey(retry, KeyEvent.KEYCODE_ENTER)
                f.await { controller.state.value.appliedGeneration == 1L }
                f.await { f.library.state.value.browseRequestId == before + 1 }
                assertEquals(3, reads.get())
                assertEquals(1, attempts.get())
                assertEquals(1, accepted.get())
                assertEquals(committed.toAuthoringDraft(), controller.state.value.readyDraft())
                assertEquals(durable, f.store.durableFacts())
                checkpoint("recovered-before-second-recreate")
                f.recreate()
                checkpoint("after-second-recreate")
                assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
                assertEquals(before + 1, f.library.state.value.browseRequestId)
                compose.runOnIdle { retained() }
                checkpoint("before-editor-back")
                f.invoke(R.string.sequence_editor_back)
                checkpoint("after-editor-back")
                f.libraryLanding()
                checkpoint("before-database-reopen")
                f.reopenOrganization { assertEquals(committed, authoring.getSequenceTemplateAuthoringState(id)) }
                checkpoint("complete")
            } finally {
                controller.close()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

    @Test
    fun nativeSourceArchiveAfterInitialReadRejectsBothUpdateDirectionsAtomicallyAndStillAllowsSaveAsNew() =
        journey { f ->
            val id = (f.seedDuplicates().last() as LibraryTemplateId.Sequence).id
            val seed = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            val step = seed.sequence.nodes.single() as com.alexandr5476.lifetracing.domain.ActivityStep
            val source = seed.activitySnapshots.getValue(step.activitySnapshotId).sourceTemplateId!!
            f.launch()
            f.enterLibrary()
            f
                .templateAction(
                    LibraryTemplateId.Sequence(id),
                    R.string.library_open_template,
                ).performScrollTo()
                .performClick()
            f.awaitSequenceEditor()
            val session = f.host().sequenceTemplateEditorRouteSessions.activeSession!!
            f
                .stepControl(
                    DraftIdentity.Existing(step.id),
                    R.string.sequence_editor_edit_step,
                ).performSemanticsAction(SemanticsActions.OnClick)
            f.store.library.archiveActivityTemplate(source, Instant.now())
            val archived = f.store.authoring.getActivityTemplate(source)!!
            val before = f.store.durableFacts()
            val libraryRequest = f.library.state.value.browseRequestId
            for (operation in listOf(
                R.string.sequence_editor_update_from_source,
                R.string.sequence_editor_update_source,
            )) {
                val epoch = session.controller.state.value.sourceActionEpoch
                f.invoke(operation)
                f.await {
                    session.controller.state.value.sourceActionEpoch > epoch &&
                        session.controller.state.value.save is SequenceTemplateEditorSave.Failure
                }
                assertFalse(
                    (session.controller.state.value.save as SequenceTemplateEditorSave.Failure).committedAwaitingReload,
                )
                compose.onLazyNode(hasText(f.text(R.string.sequence_source_archived_failure))).assertIsDisplayed()
                assertEquals(before, f.store.durableFacts())
                assertEquals(0L, session.controller.state.value.appliedGeneration)
                assertEquals(libraryRequest, f.library.state.value.browseRequestId)
                assertSame(session, f.host().sequenceTemplateEditorRouteSessions.activeSession)
            }
            f.invoke(R.string.sequence_editor_save_as_new_template)
            f.await { session.controller.state.value.appliedGeneration == 1L }
            f.await { f.library.state.value.browseRequestId == libraryRequest + 1 }
            val canonical = f.store.authoring.getSequenceTemplateAuthoringState(id)!!
            val newSource =
                canonical.activitySnapshots.values
                    .single()
                    .sourceTemplateId!!
            assertNotEquals(source, newSource)
            assertEquals(archived, f.store.authoring.getActivityTemplate(source))
            f.invoke(R.string.sequence_editor_back)
            f.libraryLanding()
            f.reopenOrganization {
                assertEquals(canonical, authoring.getSequenceTemplateAuthoringState(id))
                assertEquals(archived, authoring.getActivityTemplate(source))
                assertFalse(
                    authoring.getActivityTemplateSourceStatuses(listOf(newSource)).getValue(newSource).isArchived,
                )
            }
        }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.awaitKeyboardFocus() =
        apply { keyboard.awaitFocus(this) }

    private fun journey(body: (Fixture) -> Unit) =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()
            app.withDisposableRuntime { environment ->
                val fixture = Fixture(app) { runBlocking { environment.close() } }
                try {
                    body(fixture)
                } finally {
                    fixture.close()
                }
            }
        }

    private inner class Fixture(
        private val app: RuntimeIsolationApplication,
        private val closeGraph: () -> Unit,
    ) : AutoCloseable {
        val seedTime: Instant = Instant.ofEpochMilli(Instant.now().minusSeconds(5).toEpochMilli())
        var store = RuntimeRecoveryStore(app, "lifetracing.db")
        private var scenario: ActivityScenario<MainActivity>? = null
        private var activity: MainActivity? = null
        val library: LibraryController get() =
            host().libraryControllerOwner.get {
                error(
                    "Library must be initialized",
                )
            }

        fun host(): MainActivity = requireNotNull(activity)

        fun text(
            id: Int,
            vararg args: Any,
        ): String = host().getString(id, *args)

        fun await(condition: () -> Boolean) = compose.waitUntil(10_000, condition)

        fun awaitEditor() =
            await {
                host()
                    .activityTemplateEditorRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.load is ActivityTemplateEditorLoad.Ready
            }

        fun awaitSequenceEditor() =
            await {
                host()
                    .sequenceTemplateEditorRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.load is SequenceTemplateEditorLoad.Ready
            }

        fun sequenceDraft() =
            host()
                .sequenceTemplateEditorRouteSessions.activeSession!!
                .controller.state.value
                .readyDraft()!!

        fun stepContext(identity: DraftIdentity<SequenceNodeId>): String {
            val draft = sequenceDraft()
            val step = draft.findStep(identity)!!
            val activity = step.activity
            val name =
                step.configuration()?.name ?: when (activity) {
                    is StepActivityDraft.FromTemplate ->
                        host()
                            .sequenceTemplateEditorRouteSessions.activeSession!!
                            .controller.state.value.availableActivities
                            .first {
                                it.id ==
                                    activity.templateId
                            }.name
                    else -> ""
                }
            val parent =
                draft.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().firstOrNull {
                    it.value.children.any {
                        it.identity ==
                            identity
                    }
                }
            return if (parent == null) {
                text(R.string.sequence_manipulation_top_step, name, step.position + 1)
            } else {
                text(
                    R.string.sequence_manipulation_child,
                    name,
                    step.position + 1,
                    text(
                        R.string.sequence_manipulation_repeat,
                        parent.value.repeatCount,
                        parent.position + 1,
                    ),
                )
            }
        }

        fun stepControl(
            identity: DraftIdentity<SequenceNodeId>,
            operation: Int,
        ) = compose.onLazyNode(
            hasContentDescription(
                text(R.string.activity_editor_control_context, text(operation), stepContext(identity)),
            ),
        )

        fun stepFieldContext(
            identity: DraftIdentity<SequenceNodeId>,
            ordinal: Int,
        ): String {
            val field = sequenceDraft().findStep(identity)!!.configuration()!!.fields[ordinal]
            val label =
                when (field.type) {
                    CustomFieldType.NUMBER -> R.string.activity_editor_number
                    CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    CustomFieldType.TEXT -> R.string.activity_editor_text
                }
            return text(
                R.string.activity_editor_control_context,
                text(
                    R.string.activity_editor_field_context,
                    text(label),
                    ordinal + 1,
                    field.localNameOverride ?: field.nameAtCreation,
                ),
                stepContext(identity),
            )
        }

        fun stepFieldControl(
            identity: DraftIdentity<SequenceNodeId>,
            ordinal: Int,
            operation: Int,
        ) = compose.onLazyNode(
            hasContentDescription(
                text(R.string.activity_editor_control_context, text(operation), stepFieldContext(identity, ordinal)),
            ),
        )

        fun stepOptionControl(
            identity: DraftIdentity<SequenceNodeId>,
            field: Int,
            ordinal: Int,
            operation: Int,
        ): androidx.compose.ui.test.SemanticsNodeInteraction {
            val option =
                sequenceDraft()
                    .findStep(identity)!!
                    .configuration()!!
                    .fields[field]
                    .categoryOptions[ordinal]
            val context =
                text(
                    R.string.activity_editor_option_context,
                    stepFieldContext(identity, field),
                    ordinal + 1,
                    option.localLabelOverride ?: option.labelAtCreation,
                )
            return compose.onLazyNode(
                hasContentDescription(text(R.string.activity_editor_control_context, text(operation), context)),
            )
        }

        fun addStepField(
            identity: DraftIdentity<SequenceNodeId>,
            type: CustomFieldType,
        ) {
            val label =
                when (type) {
                    CustomFieldType.NUMBER -> R.string.activity_editor_number
                    CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    CustomFieldType.TEXT -> R.string.activity_editor_text
                }
            compose
                .onLazyNode(
                    hasContentDescription(
                        text(
                            R.string.activity_editor_control_context,
                            text(R.string.activity_editor_add_field_context, text(label)),
                            stepContext(identity),
                        ),
                    ),
                ).performSemanticsAction(SemanticsActions.OnClick)
        }

        fun stepOverride(
            identity: DraftIdentity<SequenceNodeId>,
            operation: Int,
            choice: Int,
        ) = compose.onLazyNode(
            hasText(text(choice)) and
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    text(R.string.activity_editor_control_context, text(operation), stepContext(identity)),
                ),
        )

        fun sequenceContext(ordinal: Int): String {
            val field = sequenceDraft().fields[ordinal]
            val type =
                when (field.type) {
                    CustomFieldType.NUMBER -> R.string.activity_editor_number
                    CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    CustomFieldType.TEXT -> R.string.activity_editor_text
                }
            return text(R.string.activity_editor_field_context, text(type), ordinal + 1, field.name)
        }

        fun addSequenceField(type: CustomFieldType) {
            val label =
                when (type) {
                    CustomFieldType.NUMBER -> R.string.activity_editor_number
                    CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    CustomFieldType.TEXT -> R.string.activity_editor_text
                }
            compose
                .onLazyNode(hasContentDescription(text(R.string.activity_editor_add_field_context, text(label))))
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }

        fun sequenceControl(
            ordinal: Int,
            operation: Int,
        ) = compose
            .onLazyNode(
                hasContentDescription(
                    text(R.string.activity_editor_control_context, text(operation), sequenceContext(ordinal)),
                ),
            ).performScrollTo()

        fun sequenceOptionControl(
            field: Int,
            option: Int,
            operation: Int,
        ) = compose
            .onLazyNode(
                hasContentDescription(
                    text(
                        R.string.activity_editor_control_context,
                        text(operation),
                        text(
                            R.string.activity_editor_option_context,
                            sequenceContext(field),
                            option + 1,
                            sequenceDraft().fields[field].categoryOptions[option].label,
                        ),
                    ),
                ),
            ).performScrollTo()

        fun editorDraft() =
            host()
                .activityTemplateEditorRouteSessions.activeSession!!
                .controller.state.value
                .readyDraft()!!

        fun editorInput(id: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
            val matcher = hasText(text(id)) and hasSetTextAction()
            return if (host().sequenceTemplateEditorRouteSessions.activeSession != null) {
                compose.onLazyNode(matcher)
            } else {
                compose.onNode(matcher).performScrollTo()
            }
        }

        fun editorFieldContext(index: Int): String {
            val field = editorDraft().fields[index]
            val type =
                when (field.type) {
                    com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER -> R.string.activity_editor_number
                    com.alexandr5476.lifetracing.domain.CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT -> R.string.activity_editor_text
                }
            return text(R.string.activity_editor_field_context, text(type), index + 1, field.name)
        }

        fun editorFieldInput(
            index: Int,
            operation: Int,
        ) = compose
            .onNodeWithContentDescription(
                text(R.string.activity_editor_control_context, text(operation), editorFieldContext(index)),
            ).performScrollTo()

        fun editorFieldAction(
            index: Int,
            operation: Int,
        ) = description(text(R.string.activity_editor_control_context, text(operation), editorFieldContext(index)))

        fun editorOptionContext(
            field: Int,
            option: Int,
        ) = text(
            R.string.activity_editor_option_context,
            editorFieldContext(field),
            option + 1,
            editorDraft().fields[field].categoryOptions[option].label,
        )

        fun editorOptionInput(
            field: Int,
            option: Int,
        ) = compose
            .onNodeWithContentDescription(
                text(
                    R.string.activity_editor_control_context,
                    text(R.string.activity_editor_option),
                    editorOptionContext(field, option),
                ),
            ).performScrollTo()

        fun editorOptionAction(
            field: Int,
            option: Int,
            operation: Int,
        ) = description(
            text(R.string.activity_editor_control_context, text(operation), editorOptionContext(field, option)),
        )

        fun addEditorField(type: com.alexandr5476.lifetracing.domain.CustomFieldType) {
            val label =
                when (type) {
                    com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER -> R.string.activity_editor_number
                    com.alexandr5476.lifetracing.domain.CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT -> R.string.activity_editor_text
                }
            description(text(R.string.activity_editor_add_field_context, text(label)))
        }

        fun seedDuplicates(): List<LibraryTemplateId> {
            val activities =
                List(2) {
                    store.authoring.createActivityTemplate(
                        ActivityTemplateDraft(
                            "Shared name",
                            null,
                            TimeTrackingMode.STOPWATCH,
                            null,
                            settings = ActivityTemplateSettings(startCountdown = Duration.ofHours(1)),
                        ),
                        createdAt = seedTime,
                    )
                }
            val sequence =
                store.authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "Shared name",
                        null,
                        settings = SequenceTemplateSettings(sequenceStartCountdown = Duration.ofHours(1)),
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    ActivityStepDraft(
                                        DraftIdentity.New("step"),
                                        0,
                                        StepActivityDraft.FromTemplate(activities.first().id),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = seedTime,
                )
            return activities.map { LibraryTemplateId.Activity(it.id) } + LibraryTemplateId.Sequence(sequence.id)
        }

        fun launch() {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { activity = it }
            compose.onNodeWithText(text(R.string.daily_title)).assertIsDisplayed()
            keyboard.enter()
        }

        fun enterLibrary() {
            invoke(R.string.daily_library)
            libraryLanding()
        }

        fun libraryLanding() {
            await {
                library.state.value.browse is LibraryLoad.Content &&
                    library.state.value.organization is LibraryLoad.Content
            }
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.library_back)).awaitKeyboardFocus()
        }

        fun dailyLanding() {
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.daily_library)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(R.string.daily_previous_day)).awaitKeyboardFocus()
        }

        fun invoke(id: Int) {
            val matcher = hasText(text(id)) and hasClickAction()
            val sequenceEditorOpen = host().sequenceTemplateEditorRouteSessions.activeSession != null
            val dialogOpen = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
            val node =
                when {
                    // A retained editor session also exists behind its native discard dialog.
                    dialogOpen -> compose.onNode(matcher and hasAnyAncestor(isDialog()))
                    sequenceEditorOpen -> compose.onLazyNode(matcher)
                    compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() -> compose.onNode(matcher)
                    else -> compose.onLazyNode(matcher)
                }
            if (sequenceEditorOpen && !dialogOpen) {
                // Finish the edited TextField's focus/IME viewport change before the final lookup.
                node.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.closeKeyboard(compose.onNodeWithTag("sequence-editor-page"))
            }
            // Lazy items may detach during that viewport change; reveal the action again
            // after native IME dismissal has completed, then invoke it exactly once.
            val scopedMatcher = if (dialogOpen) matcher and hasAnyAncestor(isDialog()) else matcher
            val current =
                if (sequenceEditorOpen && !dialogOpen) compose.onLazyNode(matcher) else compose.onNode(scopedMatcher)
            val ancestors = generateSequence(current.fetchSemanticsNode().parent) { it.parent }
            if (ancestors.any { it.config.contains(SemanticsActions.ScrollBy) }) current.performScrollTo()
            current.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            if (id == R.string.sequence_manipulation_move || id == R.string.sequence_manipulation_duplicate) {
                keyboard.awaitFocus(compose.onNodeWithTag("sequence-destination-top-0"))
            }
        }

        fun templateAction(
            id: LibraryTemplateId,
            operation: Int,
        ): androidx.compose.ui.test.SemanticsNodeInteraction {
            await { library.state.value.browse is LibraryLoad.Content }
            val browse = (library.state.value.browse as LibraryLoad.Content<LibraryBrowse>).value
            val searching =
                library.state.value.query
                    .isNotBlank()
            if (searching) await { library.state.value.search is LibraryLoad.Content }
            val rows =
                if (searching) {
                    (library.state.value.search as LibraryLoad.Content).value
                } else {
                    browse.contents.activities + browse.contents.sequences
                }
            val index = rows.indexOfFirst { it.id == id }
            check(index >= 0)
            val context =
                text(
                    R.string.library_template_context,
                    text(
                        if (id is LibraryTemplateId.Activity) R.string.library_activity else R.string.library_sequence,
                    ),
                    rows[index].name,
                    text(if (searching) R.string.library_search_results else R.string.library_all),
                    index + 1,
                )
            val label = text(operation, context)
            val description =
                if (operation == R.string.library_open_template) {
                    rows[index].shortComment?.let { text(R.string.library_template_details, label, it) } ?: label
                } else {
                    label
                }
            return compose.onNodeWithContentDescription(description)
        }

        fun openFolder(name: String) {
            val folders = (library.state.value.browse as LibraryLoad.Content).value.contents.folders
            val position = folders.indexOfFirst { it.name == name } + 1
            check(position > 0)
            compose
                .onNodeWithContentDescription(text(R.string.library_open_folder, name, position))
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            await { library.state.value.browse is LibraryLoad.Content }
            compose.waitForIdle()
        }

        fun dismissDialogWithSystemBack(cancelDescription: String) {
            val cancel = compose.onNodeWithContentDescription(cancelDescription)
            keyboard.closeKeyboard(cancel)
            cancel.performSemanticsAction(SemanticsActions.RequestFocus).awaitKeyboardFocus()
            keyboard.sendKey(cancel, KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription(cancelDescription)).fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
        }

        fun systemBack() {
            compose.waitForIdle()
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }

        fun recreate() {
            val dialogOpen = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
            if (dialogOpen) {
                keyboard.awaitWindowFocus(compose.onNode(isDialog()))
                // Release the stopped dialog's native window before destroying its Activity.
                // Otherwise API 26/33 can retain its retired token as the input focus target.
                scenario!!.moveToState(Lifecycle.State.CREATED)
            }
            scenario!!.recreate()
            if (dialogOpen) scenario!!.moveToState(Lifecycle.State.RESUMED)
            scenario!!.onActivity { activity = it }
            compose.waitForIdle()
            if (dialogOpen) {
                val window =
                    if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
                        compose.onNode(isDialog())
                    } else {
                        compose.onRoot()
                    }
                keyboard.enter(window)
            }
        }

        fun requestDeletion(id: FolderId): String {
            awaitOrganization()
            organizeFolder(id)
            val folders = (library.state.value.browse as LibraryLoad.Content).value.contents.folders
            val index = folders.indexOfFirst { it.id == id }
            val folder = folders[index]
            val context = text(R.string.library_folder_context, folder.name, index + 1)
            description(text(R.string.library_delete_folder_target, context))
            await {
                library.state.value.folderDeletion
                    ?.options is LibraryLoad.Content
            }
            compose.waitForIdle()
            return context
        }

        fun lifecycleTemplate(id: LibraryTemplateId): Any =
            when (id) {
                is LibraryTemplateId.Activity ->
                    store.authoring
                        .getActivityTemplate(id.id)!!
                        .copy(deletedAt = null, folderId = null, tagIds = emptySet())
                is LibraryTemplateId.Sequence ->
                    store.authoring.getSequenceTemplate(id.id)!!.copy(
                        deletedAt = null,
                        folderId = null,
                        tagIds = emptySet(),
                        userState =
                            com.alexandr5476.lifetracing.domain
                                .SequenceTemplateUserState(),
                    )
            }

        fun awaitOrganization() {
            await {
                !library.state.value.isMutating &&
                    library.state.value.browse is LibraryLoad.Content &&
                    library.state.value.organization is LibraryLoad.Content
            }
            compose.waitForIdle()
        }

        fun templateContext(id: LibraryTemplateId): String {
            awaitOrganization()
            val browse = (library.state.value.browse as LibraryLoad.Content<LibraryBrowse>).value
            val rows = browse.contents.activities + browse.contents.sequences
            val index = rows.indexOfFirst { it.id == id }
            check(index >= 0)
            return text(
                R.string.library_template_context,
                text(if (id is LibraryTemplateId.Activity) R.string.library_activity else R.string.library_sequence),
                rows[index].name,
                text(R.string.library_all),
                index + 1,
            )
        }

        fun folderContext(id: FolderId): String {
            val folders = (library.state.value.organization as LibraryLoad.Content).value.folders
            val index = folders.indexOfFirst { it.id == id }
            return text(R.string.library_folder_context, folders[index].name, index + 1)
        }

        fun tagContext(id: com.alexandr5476.lifetracing.domain.TagId): String {
            val tags = (library.state.value.organization as LibraryLoad.Content).value.tags
            val index = tags.indexOfFirst { it.id == id }
            return text(R.string.library_tag_context, tags[index].name, index + 1)
        }

        fun organizeFolder(id: FolderId) {
            awaitOrganization()
            val folders = (library.state.value.browse as LibraryLoad.Content).value.contents.folders
            val index = folders.indexOfFirst { it.id == id }
            val folder = folders[index]
            val rename =
                text(
                    R.string.library_rename_folder_target,
                    text(R.string.library_folder_context, folder.name, index + 1),
                )
            if (compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasContentDescription(rename),
                    ).fetchSemanticsNodes()
                    .isEmpty()
            ) {
                description(text(R.string.library_organize_folder, folder.name, index + 1))
            }
        }

        fun organize(id: LibraryTemplateId) {
            val context = templateContext(id)
            val pin = text(R.string.library_pin_target, context)
            val unpin = text(R.string.library_unpin_target, context)
            val matcher =
                androidx.compose.ui.test
                    .hasContentDescription(pin) or
                    androidx.compose.ui.test
                        .hasContentDescription(unpin)
            if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
                description(text(R.string.library_organize_template, context))
            }
        }

        fun description(value: String) {
            val target = compose.onNodeWithContentDescription(value)
            val ancestors = generateSequence(target.fetchSemanticsNode().parent) { it.parent }
            if (ancestors.any { it.config.contains(SemanticsActions.ScrollBy) }) target.performScrollTo()
            target.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }

        fun organizationMutation(description: String) {
            val before = library.state.value.mutationSuccessGeneration
            description(description)
            await { library.state.value.mutationSuccessGeneration == before + 1 }
            awaitOrganization()
        }

        fun dialogInput(context: String) =
            compose.onNodeWithContentDescription(text(R.string.library_name_context, context))

        fun nameMutation(
            context: String,
            name: String,
            confirm: Int,
        ) {
            description(context)
            dialogInput(context).awaitKeyboardFocus().performTextReplacement(name)
            val before = library.state.value.mutationSuccessGeneration
            val accept =
                compose.onNodeWithContentDescription(text(R.string.library_confirm_context, text(confirm), context))
            accept.performSemanticsAction(SemanticsActions.RequestFocus).awaitKeyboardFocus()
            keyboard.sendKey(accept, KeyEvent.KEYCODE_ENTER)
            await { library.state.value.mutationSuccessGeneration == before + 1 }
            awaitOrganization()
        }

        fun semanticTemplate(id: LibraryTemplateId): Any =
            when (id) {
                is LibraryTemplateId.Activity ->
                    store.authoring
                        .getActivityTemplate(
                            id.id,
                        )!!
                        .copy(folderId = null, tagIds = emptySet())
                is LibraryTemplateId.Sequence ->
                    store.authoring.getSequenceTemplate(id.id)!!.copy(
                        folderId = null,
                        tagIds = emptySet(),
                        userState =
                            com.alexandr5476.lifetracing.domain
                                .SequenceTemplateUserState(),
                    )
            }

        fun frozenFacts() =
            store.durableFacts().filterKeys {
                it.contains("snapshot") ||
                    it.contains("execution") ||
                    it.contains("interval") ||
                    it.contains("statistics") ||
                    it.contains("plan") ||
                    it == "active_session"
            }

        fun reopenOrganization(
            expectedSession: com.alexandr5476.lifetracing.domain.ActiveSession? = null,
            checks: RuntimeRecoveryStore.() -> Unit,
        ) {
            val before = store.durableFacts()
            assertEquals(expectedSession, store.live.getActiveSession())
            scenario?.close()
            scenario = null
            closeGraph()
            store.close()
            store = RuntimeRecoveryStore(app, "lifetracing.db")
            assertEquals(before, store.durableFacts())
            assertEquals(expectedSession, store.live.getActiveSession())
            store.checks()
        }

        fun reopenUnchanged(expected: Map<String, List<List<String?>>>) {
            assertEquals(expected, store.durableFacts())
            assertNull(store.live.getActiveSession())
            assertEquals(0L to 0L, store.executionCounts())
            scenario?.close()
            scenario = null
            closeGraph()
            store.close()
            store = RuntimeRecoveryStore(app, "lifetracing.db")
            assertEquals(expected, store.durableFacts())
            assertNull(store.live.getActiveSession())
            assertEquals(0L to 0L, store.executionCounts())
        }

        override fun close() {
            scenario?.close()
            store.close()
        }
    }
}
