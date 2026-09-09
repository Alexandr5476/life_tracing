package com.alexandr5476.lifetracing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.alexandr5476.lifetracing.data.persistence.DailyReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.editor.readyDraft
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class MainActivityRouteSessionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionLauncherSessionSurvivesActivityRecreationAndIsReleasedOnlyWhenPopped() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_start_activity)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession != null
        }
        val first = requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        val pending = LibraryTemplateId.Activity(ActivityTemplateId("pending"))
        composeTestRule.runOnUiThread { first.interaction.select(pending) }

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        assertSame(first, composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertEquals(pending, first.interaction.pendingSelectionId)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
        assertNull(composeTestRule.activity.startActivityRouteSessions.activeSession)

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_start_activity)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession != null
        }
        val next: StartActivityRouteSession =
            requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertNotSame(first, next)
        assertNull(next.interaction.pendingSelectionId)
        assertNull(next.interaction.quickEditor)
        assertEquals(0, next.interaction.browsePath.size)

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.launcher_browse_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
    }

    @Test
    fun productionDirtyEditorSessionSurvivesActivityRecreationAndReleasesOnlyAfterRoutePop() {
        val name = "Dirty editor ${System.nanoTime()}"
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_library))
            .performClick()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_new_activity))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession != null
        }
        val session = requireNotNull(composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession)
        val controller = session.controller
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.activity_editor_name)) and hasSetTextAction())
            .performTextInput(name)

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        assertSame(session, composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession)
        assertSame(
            controller,
            requireNotNull(composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession).controller,
        )
        val retainedState = controller.state.value
        val retainedDraft = retainedState.readyDraft()
        assertEquals(name, retainedDraft?.name)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertSame(session, composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_discard))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession == null
        }
    }

    @Test
    fun productionLibraryControllerAndProjectionSurviveActivityRecreation() {
        val name = "Retained library ${System.nanoTime()}"
        TemplateAuthoringRepository.create(composeTestRule.activity).createActivityTemplate(
            ActivityTemplateDraft(name, null, TimeTrackingMode.STOPWATCH, null),
            createdAt = Instant.now(),
        )
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        val controller =
            composeTestRule.activity.libraryControllerOwner.get {
                error("Library route must initialize the retained controller")
            }
        val projection = controller.state.value

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        val recreated =
            composeTestRule.activity.libraryControllerOwner.get {
                error("Activity recreation must reuse the retained controller")
            }
        assertSame(controller, recreated)
        assertSame(projection, recreated.state.value)
    }

    @Test
    fun committedActivityCatalogChangeRefreshesTheRetainedLibraryAcrossTheRecreationBoundary() {
        val oldName = "Catalog before commit ${System.nanoTime()}"
        val newName = "Catalog after commit ${System.nanoTime()}"
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val created =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(oldName, null, TimeTrackingMode.STOPWATCH, null),
                createdAt = Instant.now(),
            )
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(oldName).fetchSemanticsNodes().isNotEmpty()
        }
        val controller =
            composeTestRule.activity.libraryControllerOwner.get {
                error("Library route must initialize the retained controller")
            }
        authoring.saveActivityTemplate(
            created.id,
            created.revision,
            created.toAuthoringDraft().copy(name = newName),
            Instant.now(),
        )
        composeTestRule.runOnUiThread {
            composeTestRule.activity.libraryControllerOwner.refreshIfInitialized()
        }

        composeTestRule.activityRule.scenario.recreate()

        assertSame(
            controller,
            composeTestRule.activity.libraryControllerOwner.get {
                error("Activity recreation must retain the catalog controller")
            },
        )
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(newName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(oldName).assertDoesNotExist()
    }

    @Test
    fun productionLibraryEntersAndPopsOnTheExistingDailyStack() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule
            .onAllNodesWithText(composeTestRule.activity.getString(R.string.library_title))[0]
            .assertIsDisplayed()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).assertIsDisplayed()
    }

    @Test
    @Suppress("LongMethod") // One production navigation round trip is the behavior under test.
    fun libraryOriginCancelKeepsRetainedBrowseStateAndCreatesNoDurableResidueOrRefresh() {
        val fixture = returnFixture(countdown = Duration.ofDays(1), withMainValue = false)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        enterFilteredFolderSearch(fixture)

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_quick_start))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession
                ?.controller
                ?.state
                ?.value
                ?.command is LauncherCommandState.Preflight
        }
        val session = requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        val externalName = "${fixture.query} external cancel"
        fixture.authoring.createActivityTemplate(
            ActivityTemplateDraft(externalName, null, TimeTrackingMode.STOPWATCH, null),
            createdAt = Instant.now(),
        )

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }

        assertNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertEquals(LauncherCommandState.Idle, session.controller.state.value.command)
        assertRetainedFilteredFolderState(fixture)
        composeTestRule.onNodeWithText(externalName).assertDoesNotExist()
        val freshLibrary = LibraryRepository.create(composeTestRule.activity)
        assertTrue(freshLibrary.search(externalName, LibraryKindFilter.ACTIVITIES).single().name == externalName)
        assertNull(freshLibrary.getRecent(100).firstOrNull { it.id == fixture.targetId })
        assertNull(live.getActiveSession())
        assertEquals(0, durableCount("activity_snapshots", fixture.activity.id.value))
        assertEquals(0, durableCount("activity_executions", fixture.activity.id.value))
        assertTrue(
            daily().completedHistory.none {
                it is CompletedActivityHistoryRoot && it.title == fixture.activity.name
            },
        )
    }

    @Test
    @Suppress("LongMethod") // Assertions cover the complete production commit/return boundary.
    fun libraryOriginCommitRefreshesDailyAndCanonicalLibraryWithoutLosingBrowseState() {
        val fixture = returnFixture(countdown = Duration.ZERO, withMainValue = true)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.daily_previous_day))
            .performClick()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_return_today))
            .assertIsDisplayed()
        enterFilteredFolderSearch(fixture)
        val beforeTemplate = requireNotNull(fixture.authoring.getActivityTemplate(fixture.activity.id))
        val beforeTrackable =
            fixture.library
                .getFolderContents(fixture.folderId)
                .activities
                .single { it.id == fixture.targetId }

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_quick_start))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession
                ?.interaction
                ?.quickEditor != null
        }
        val session = requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertNull(session.interaction.pendingSelectionId)
        assertEquals(0, durableCount("activity_snapshots", fixture.activity.id.value))
        assertEquals(0, durableCount("activity_executions", fixture.activity.id.value))
        assertNull(fixture.library.getRecent(100).firstOrNull { it.id == fixture.targetId })

        val externalName = "${fixture.query} canonical refresh"
        fixture.authoring.createActivityTemplate(
            ActivityTemplateDraft(externalName, null, TimeTrackingMode.STOPWATCH, null),
            createdAt = Instant.now(),
        )
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.launcher_complete))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(externalName).fetchSemanticsNodes().isNotEmpty()
        }

        assertRetainedFilteredFolderState(fixture)
        composeTestRule.onNodeWithText(externalName).assertIsDisplayed()
        val freshLibrary = LibraryRepository.create(composeTestRule.activity)
        val afterTrackable = freshLibrary.search(fixture.activity.name, LibraryKindFilter.ACTIVITIES).single()
        val afterTemplate =
            requireNotNull(
                TemplateAuthoringRepository.create(composeTestRule.activity).getActivityTemplate(fixture.activity.id),
            )
        assertEquals(beforeTemplate.revision, afterTemplate.revision)
        assertEquals(beforeTemplate.statisticsSeriesId, afterTemplate.statisticsSeriesId)
        assertEquals(beforeTrackable.folderId, afterTrackable.folderId)
        assertEquals(beforeTrackable.tagIds, afterTrackable.tagIds)
        assertEquals(beforeTrackable.pinnedRank, afterTrackable.pinnedRank)
        assertTrue(requireNotNull(afterTrackable.lastUsedAt) >= fixture.createdAt)
        assertEquals(1, durableCount("activity_snapshots", fixture.activity.id.value))
        assertEquals(1, durableCount("activity_executions", fixture.activity.id.value))
        assertEquals(
            1,
            daily().completedHistory.count {
                it is CompletedActivityHistoryRoot && it.title == fixture.activity.name
            },
        )
        assertNull(live.getActiveSession())

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.library_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Library / ${fixture.folderName}").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.library_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(fixture.activity.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_return_today))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(fixture.activity.name).assertIsDisplayed()
    }

    @Test
    fun productionEditorReturnRefreshesTheRetainedFilteredSearchFromCanonicalStorage() {
        val prefix = "S2C2-${System.nanoTime()}"
        val query = "$prefix match"
        val activityName = "$query activity"
        val sequenceName = "$query sequence"
        val renamed = "$prefix renamed"
        val newMatchingName = "$query new"
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val createdAt = Instant.now().minusSeconds(10)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(activityName, null, TimeTrackingMode.STOPWATCH, null),
                createdAt = createdAt,
            )
        authoring.createSequenceTemplate(
            SequenceTemplateDraft(sequenceName, null),
            createdAt = createdAt.plusMillis(1),
        )

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(activityName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.library_search_hint)) and hasSetTextAction())
            .performTextInput(query)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_filter_activities))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(activityName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()

        composeTestRule.onNodeWithText(activityName).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasText(activityName) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasText(activityName) and hasSetTextAction()).performTextReplacement(renamed)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_done))
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodesWithText(composeTestRule.activity.getString(R.string.library_search_empty))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(query).assertIsDisplayed()
        composeTestRule.onNodeWithText(activityName).assertDoesNotExist()
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()
        assertEquals(renamed, authoring.getActivityTemplate(activity.id)?.name)

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_new_activity))
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.activity_editor_name)) and hasSetTextAction())
            .performTextInput(newMatchingName)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_done))
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(newMatchingName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(newMatchingName).assertIsDisplayed()
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()
    }

    private fun returnFixture(
        countdown: Duration,
        withMainValue: Boolean,
    ): ReturnFixture {
        val suffix = System.nanoTime().toString()
        val query = "S5C2-$suffix"
        val createdAt = Instant.now().minusSeconds(2)
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val library = LibraryRepository.create(composeTestRule.activity)
        val folderId = FolderId("folder-$suffix")
        val folderName = "S5C2 folder $suffix"
        library.createFolder(folderId, folderName, null, createdAt)
        val fields =
            if (withMainValue) {
                listOf(
                    ActivityFieldDraft(
                        DraftIdentity.New("main"),
                        0,
                        "S5C2 value $suffix",
                        CustomFieldType.NUMBER,
                        displayPrecision = 0,
                        defaultNumberScaled = 3,
                        isMainValue = true,
                    ),
                )
            } else {
                emptyList()
            }
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "$query activity",
                    null,
                    if (withMainValue) TimeTrackingMode.NO_LIVE_TRACKING else TimeTrackingMode.STOPWATCH,
                    null,
                    settings = ActivityTemplateSettings(startCountdown = countdown),
                    fields = fields,
                ),
                createdAt = createdAt.plusMillis(1),
            )
        val sequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft("$query sequence", null),
                createdAt = createdAt.plusMillis(2),
            )
        val targetId = LibraryTemplateId.Activity(activity.id)
        library.moveActivityTemplateToFolder(activity.id, folderId, createdAt.plusMillis(3))
        library.moveSequenceTemplateToFolder(sequence.id, folderId, createdAt.plusMillis(3))
        library.createTagAndAssign(TagId("tag-$suffix"), "S5C2 tag $suffix", targetId, createdAt.plusMillis(4))
        library.pin(targetId)
        return ReturnFixture(query, createdAt, folderId, folderName, activity, targetId, authoring, library)
    }

    private fun enterFilteredFolderSearch(fixture: ReturnFixture) {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodesWithText(fixture.folderName)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule
            .onNodeWithText(fixture.folderName)
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.library_search_hint)) and hasSetTextAction())
            .performTextInput(fixture.query)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_filter_activities))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(fixture.activity.name).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("${fixture.query} sequence").fetchSemanticsNodes().isEmpty()
        }
        assertRetainedFilteredFolderState(fixture)
    }

    private fun assertRetainedFilteredFolderState(fixture: ReturnFixture) {
        composeTestRule.onNodeWithText(fixture.query).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Library / ${fixture.folderName}")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(fixture.activity.name).assertIsDisplayed()
        composeTestRule.onNodeWithText("${fixture.query} sequence").assertDoesNotExist()
    }

    private fun daily() =
        Instant.now().let { now ->
            DailyReadRepository.create(composeTestRule.activity).getDaily(
                DailyQuery(now.atZone(ZoneId.systemDefault()).toLocalDate(), now, 100),
            )
        }

    private fun durableCount(
        table: String,
        sourceTemplateId: String,
    ): Int {
        val database =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                composeTestRule.activity.getDatabasePath("lifetracing.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
        return database.use { db ->
            val query =
                if (table == "activity_snapshots") {
                    "SELECT COUNT(*) FROM activity_snapshots WHERE source_template_id = ?"
                } else {
                    "SELECT COUNT(*) FROM activity_executions e JOIN activity_snapshots s ON s.id = e.snapshot_id " +
                        "WHERE s.source_template_id = ?"
                }
            db.rawQuery(query, arrayOf(sourceTemplateId)).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }
    }

    private fun clearLiveSession(live: LiveSessionRepository) {
        val at = Instant.now()
        when (live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(at)
            ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(at)
            null -> Unit
        }
    }

    private data class ReturnFixture(
        val query: String,
        val createdAt: Instant,
        val folderId: FolderId,
        val folderName: String,
        val activity: com.alexandr5476.lifetracing.domain.ActivityTemplate,
        val targetId: LibraryTemplateId.Activity,
        val authoring: TemplateAuthoringRepository,
        val library: LibraryRepository,
    )
}
