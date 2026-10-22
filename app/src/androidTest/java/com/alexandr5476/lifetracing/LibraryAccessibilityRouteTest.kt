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
            compose
                .onNodeWithContentDescription(text(R.string.library_open_folder, name, 1))
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
