@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
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
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorLoad
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorTarget
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorLoad
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorTarget
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
                row.assertIsFocused()
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
            editorName.assertIsFocused()
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
            editorName.assertIsFocused()
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
            androidx.test.espresso.Espresso
                .pressBack()
            compose.waitForIdle()
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
            compose.onNodeWithText(text(R.string.library_back)).assertIsFocused()
        }

        fun dailyLanding() {
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.daily_library)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(R.string.daily_previous_day)).assertIsFocused()
        }

        fun invoke(id: Int) {
            val matcher = hasText(text(id)) and hasClickAction()
            val node =
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNode(matcher).performScrollTo()
                } else {
                    compose.onLazyNode(matcher)
                }
            node.performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }

        fun templateAction(
            id: LibraryTemplateId,
            operation: Int,
        ): androidx.compose.ui.test.SemanticsNodeInteraction {
            await { library.state.value.browse is LibraryLoad.Content }
            val browse = (library.state.value.browse as LibraryLoad.Content<LibraryBrowse>).value
            val rows = browse.contents.activities + browse.contents.sequences
            val index = rows.indexOfFirst { it.id == id }
            check(index >= 0)
            val context =
                text(
                    R.string.library_template_context,
                    text(
                        if (id is LibraryTemplateId.Activity) R.string.library_activity else R.string.library_sequence,
                    ),
                    rows[index].name,
                    text(R.string.library_all),
                    index + 1,
                )
            return compose.onNodeWithContentDescription(text(operation, context))
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
            dialogInput(context).performTextReplacement(name)
            val before = library.state.value.mutationSuccessGeneration
            compose
                .onNodeWithContentDescription(text(R.string.library_confirm_context, text(confirm), context))
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.Enter) }
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

        fun reopenOrganization(checks: RuntimeRecoveryStore.() -> Unit) {
            val before = store.durableFacts()
            assertNull(store.live.getActiveSession())
            scenario?.close()
            scenario = null
            closeGraph()
            store.close()
            store = RuntimeRecoveryStore(app, "lifetracing.db")
            assertEquals(before, store.durableFacts())
            assertNull(store.live.getActiveSession())
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
