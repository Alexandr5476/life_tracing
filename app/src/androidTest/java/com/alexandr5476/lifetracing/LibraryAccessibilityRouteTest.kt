@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorLoad
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorSave
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorTarget
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorLoad
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorTarget
import com.alexandr5476.lifetracing.editor.readyDraft
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.LauncherLoad
import com.alexandr5476.lifetracing.library.LibraryBrowse
import com.alexandr5476.lifetracing.library.LibraryController
import com.alexandr5476.lifetracing.library.LibraryLoad
import com.alexandr5476.lifetracing.runtime.RuntimeIsolationApplication
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.onLazyNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
            assertSame(session, f.host().activityTemplateEditorRouteSessions.activeSession)
            editorName.awaitKeyboardFocus()
            assertEquals(afterArchive, f.store.durableFacts())
            f.systemBack()
            f.libraryLanding()
            assertNull(f.host().activityTemplateEditorRouteSessions.activeSession)
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

        fun editorDraft() =
            host()
                .activityTemplateEditorRouteSessions.activeSession!!
                .controller.state.value
                .readyDraft()!!

        fun editorInput(id: Int) = compose.onNode(hasText(text(id)) and hasSetTextAction()).performScrollTo()

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
            val node =
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNode(matcher)
                } else {
                    compose.onLazyNode(matcher)
                }
            val ancestors = generateSequence(node.fetchSemanticsNode().parent) { it.parent }
            if (ancestors.any { it.config.contains(SemanticsActions.ScrollBy) }) node.performScrollTo()
            node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
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
            scenario!!.recreate()
            scenario!!.onActivity { activity = it }
            compose.waitForIdle()
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
