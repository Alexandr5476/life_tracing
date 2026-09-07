package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LiveSessionConflictException
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StaleLauncherTargetException
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TagId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private lateinit var database: LifeTracingDatabase

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun catalogQueriesAreLightweightMixedDeterministicAndBounded() {
        val repository = repository()
        repository.createFolder(FolderId("folder"), "Folder", null, instant(1))
        repository.createFolder(FolderId("nested"), "Nested", FolderId("folder"), instant(2))
        repository.createTag(TagId("focus"), "Focus", instant(1))
        activity("activity-root", "Alpha", pinned = 7, recent = 100)
        activity("activity-folder", "100% Focus", folder = "folder")
        sequence("sequence-root", "Alpha", pinned = 7, recent = 100)
        sequence("sequence-archived", "Archived", deleted = 50)
        repository.addTag(LibraryTemplateId.Activity(ActivityTemplateId("activity-root")), TagId("focus"))
        repository.addTag(LibraryTemplateId.Sequence(SequenceTemplateId("sequence-root")), TagId("focus"))

        val root = repository.getRoot()
        assertEquals(listOf("folder"), root.contents.folders.map { it.id.value })
        assertEquals(listOf("activity-root"), root.contents.activities.map { it.id.value })
        assertEquals(listOf("sequence-root"), root.contents.sequences.map { it.id.value })
        assertEquals(
            listOf("activity-root", "sequence-root"),
            root.pinned.map { it.id.value },
        )
        assertEquals(
            listOf("activity-folder", "activity-root", "sequence-root"),
            repository.getAll().map { it.id.value },
        )
        assertEquals(
            listOf("activity-folder", "activity-root"),
            repository.getAll(LibraryKindFilter.ACTIVITIES).map { it.id.value },
        )
        assertEquals(listOf("activity-folder"), repository.search("%").map { it.id.value })
        assertEquals(
            setOf("activity-root", "sequence-root"),
            repository.getByTag(TagId("focus")).mapTo(hashSetOf()) { it.id.value },
        )
        assertEquals(listOf("sequence-archived"), repository.getArchived().map { it.id.value })
        assertEquals(listOf("activity-root"), repository.getRecent(1).map { it.id.value })
        assertThrows(IllegalArgumentException::class.java) { repository.getRecent(0) }

        val folder = repository.getFolderContents(FolderId("folder"))
        assertEquals(listOf("nested"), folder.folders.map { it.id.value })
        assertEquals(listOf("activity-folder"), folder.activities.map { it.id.value })
        assertTrue(folder.sequences.isEmpty())
    }

    @Test
    fun catalogTagHydrationChunksBothKindsAboveTheSafeBindCount() {
        val tagQueries: MutableList<Pair<String, List<Any?>>> = mutableListOf()
        database.close()
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .setQueryCallback(
                    RoomDatabase.QueryCallback { sql, bindArgs -> tagQueries += sql to bindArgs },
                    Executor { command -> command.run() },
                ).allowMainThreadQueries()
                .build()
        val repository = repository()
        repository.createTag(TagId("tag"), "Tag", instant(0))
        val itemCount = 901

        repeat(itemCount) { index ->
            activity("activity-$index", "Activity $index")
            sequence("sequence-$index", "Sequence $index")
            database.libraryDao().addActivityTag(ActivityTemplateTagEntity("activity-$index", "tag"))
            database.libraryDao().addSequenceTag(SequenceTemplateTagEntity("sequence-$index", "tag"))
        }

        val all = repository.getAll()
        assertEquals(itemCount, all.count { it.id is LibraryTemplateId.Activity })
        assertEquals(itemCount, all.count { it.id is LibraryTemplateId.Sequence })
        assertTrue(all.all { TagId("tag") in it.tagIds })
        assertEquals(
            listOf(900, 1),
            tagQueries.filter { "FROM activity_template_tags" in it.first }.map { it.second.size },
        )
        assertEquals(
            listOf(900, 1),
            tagQueries.filter { "FROM sequence_template_tags" in it.first }.map { it.second.size },
        )
    }

    @Test
    fun completeThreeItemPinnedOrderPersists() {
        val repository = repository()
        activity("activity-a", "A")
        activity("activity-b", "B")
        sequence("sequence-c", "C")
        val a = LibraryTemplateId.Activity(ActivityTemplateId("activity-a"))
        val b = LibraryTemplateId.Activity(ActivityTemplateId("activity-b"))
        val c = LibraryTemplateId.Sequence(SequenceTemplateId("sequence-c"))

        listOf(a, b, c).forEach(repository::pin)
        repository.reorderPinned(listOf(b, c, a))

        assertEquals(listOf(b, c, a), repository.getPinned().map { it.id })
    }

    @Test
    fun launchTargetLoadsOnlySelectedConfigurationAndUsesFirstEffectiveRepeatStepOverride() {
        val repository = repository()
        activity(
            "activity",
            "Activity",
            mode = "NO_LIVE_TRACKING",
            fields = listOf(activityField("main", "activity", null)),
            settings = ActivityTemplateSettingsEntity("activity", startCountdownMs = 2_000),
        )
        seedSnapshot("repeat-step", "STOPWATCH")
        sequence(
            "sequence",
            "Sequence",
            nodes =
                listOf(
                    SequenceNodeEntity("empty", "sequence", "REPEAT", null, 0, null, 2),
                    SequenceNodeEntity("repeat", "sequence", "REPEAT", null, 1, null, 3),
                    SequenceNodeEntity("first", "sequence", "STEP", "repeat", 0, "repeat-step", null),
                ),
            settings = SequenceTemplateSettingsEntity("sequence", sequenceStartCountdownMs = 5_000),
            stepOverrides = listOf(SequenceStepOverrideEntity("first", 7_000, null, null, null, null)),
        )

        val activity = repository.getLaunchTarget(LibraryTemplateId.Activity(ActivityTemplateId("activity")))
        val sequence = repository.getLaunchTarget(LibraryTemplateId.Sequence(SequenceTemplateId("sequence")))

        assertEquals(2_000L, activity.startCountdown.toMillis())
        assertEquals(ActivityTemplateFieldId("main"), (activity as LibraryLaunchTarget.Activity).mainValue?.fieldId)
        assertEquals(7_000L, sequence.startCountdown.toMillis())
        assertNull(database.activitySnapshotDao().getById("activity-launch-1"))
        assertNull(database.activityTemplateDao().getUserState("activity")?.lastUsedAtMs)
        assertNull(database.sequenceTemplateDao().getUserState("sequence")?.lastUsedAtMs)
    }

    @Test
    fun staleLauncherRevisionsRollbackAllWriterResidueAndAcceptedSnapshotsKeepAuthorizedRevision() {
        val library = repository()
        activity("timed", "Timed", revision = 2)
        activity("quick", "Quick", mode = "NO_LIVE_TRACKING", revision = 2)
        seedSnapshot("step", "STOPWATCH")
        sequence(
            "sequence",
            "Sequence",
            revision = 2,
            nodes = listOf(step("step-node", "sequence", "step")),
        )
        val commands = activityCommands()

        assertThrows(StaleLauncherTargetException::class.java) {
            commands.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("timed")),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
                expectedTemplateRevision = 1,
            )
        }
        assertNull(database.activitySnapshotDao().getById("command-snapshot-1"))
        assertNull(database.activeSessionDao().get())
        assertNull(database.activityTemplateDao().getUserState("timed")?.lastUsedAtMs)

        val timed =
            commands.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("timed")),
                instant(11),
                instant(11),
                ZoneOffset.UTC,
                expectedTemplateRevision = 2,
            )
        assertEquals(2L, database.activitySnapshotDao().getById(timed.snapshotId.value)?.sourceRevision)
        liveForExistingDatabase().completeActiveActivity(instant(12))

        assertThrows(StaleLauncherTargetException::class.java) {
            library.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("quick"),
                instant(13),
                instant(13),
                ZoneOffset.UTC,
                expectedRevision = 1,
            )
        }
        assertNull(database.activitySnapshotDao().getById("activity-launch-1"))
        assertNull(database.activityTemplateDao().getUserState("quick")?.lastUsedAtMs)
        val quick =
            library.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("quick"),
                instant(14),
                instant(14),
                ZoneOffset.UTC,
                expectedRevision = 2,
            )
        assertEquals(2L, database.activitySnapshotDao().getById(quick.snapshotId.value)?.sourceRevision)

        assertThrows(StaleLauncherTargetException::class.java) {
            library.startSequenceFromTemplate(
                SequenceTemplateId("sequence"),
                instant(15),
                instant(15),
                ZoneOffset.UTC,
                expectedRevision = 1,
            )
        }
        assertNull(database.sequenceSnapshotDao().getById("sequence-launch-1"))
        assertNull(database.activeSessionDao().get())
        assertNull(database.sequenceTemplateDao().getUserState("sequence")?.lastUsedAtMs)
        val launched =
            library.startSequenceFromTemplate(
                SequenceTemplateId("sequence"),
                instant(16),
                instant(16),
                ZoneOffset.UTC,
                expectedRevision = 2,
            )
        assertEquals(2L, database.sequenceSnapshotDao().getById(launched.execution.snapshotId.value)?.sourceRevision)
    }

    @Test
    fun staleNoLiveTargetIsClassifiedBeforeItsCurrentModeAndInitialLiveEligibility() {
        val library = repository()
        activity("mode-changed", "Mode changed", mode = "STOPWATCH", revision = 2)

        assertThrows(StaleLauncherTargetException::class.java) {
            library.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("mode-changed"),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
                expectedRevision = 1,
            )
        }
        assertThrows(StaleLauncherTargetException::class.java) {
            library.hasLiveLaunchConflict(LibraryTemplateId.Activity(ActivityTemplateId("mode-changed")), 1)
        }
        assertNull(database.activitySnapshotDao().getById("activity-launch-1"))
        assertNull(database.activeSessionDao().get())
        assertNull(database.activityTemplateDao().getUserState("mode-changed")?.lastUsedAtMs)
    }

    @Test
    fun folderMutationsRejectCyclesAndCorruptCycleReadsFailExplicitly() {
        val repository = repository()
        repository.createFolder(FolderId("a"), "A", null, instant(1))
        repository.createFolder(FolderId("b"), "B", FolderId("a"), instant(2))
        repository.createFolder(FolderId("c"), "C", FolderId("b"), instant(3))

        assertThrows(IllegalArgumentException::class.java) {
            repository.moveFolder(FolderId("a"), FolderId("c"), instant(4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.moveFolder(FolderId("b"), FolderId("missing"), instant(4))
        }
        assertEquals(listOf("a", "b", "c"), repository.getFolderPath(FolderId("c")).map { it.id.value })

        database.openHelper.writableDatabase.execSQL("UPDATE folders SET parent_folder_id = 'c' WHERE id = 'a'")
        assertThrows(IllegalArgumentException::class.java) { repository.getFolderPath(FolderId("c")) }
    }

    @Test
    fun metadataOperationsKeepRevisionAndSeriesWhileArchiveRetainsPinAndRecent() {
        val repository = repository()
        repository.createFolder(FolderId("folder"), "Folder", null, instant(1))
        repository.createTag(TagId("tag"), "Tag", instant(1))
        activity("activity", "Activity", revision = 7)
        sequence("sequence", "Sequence", revision = 9)
        val activityId = LibraryTemplateId.Activity(ActivityTemplateId("activity"))
        val sequenceId = LibraryTemplateId.Sequence(SequenceTemplateId("sequence"))

        repository.moveTemplatesToFolder(listOf(activityId, sequenceId), FolderId("folder"), instant(2))
        assertIdentity("activity", "sequence", 7, 9)
        repository.addTag(activityId, TagId("tag"))
        repository.addTag(sequenceId, TagId("tag"))
        repository.addTag(activityId, TagId("tag"))
        repository.addTag(sequenceId, TagId("tag"))
        assertIdentity("activity", "sequence", 7, 9)
        repository.removeTag(activityId, TagId("tag"))
        repository.removeTag(sequenceId, TagId("tag"))
        repository.removeTag(activityId, TagId("tag"))
        repository.removeTag(sequenceId, TagId("tag"))
        assertIdentity("activity", "sequence", 7, 9)
        repository.pin(activityId)
        repository.pin(sequenceId)
        assertThrows(IllegalArgumentException::class.java) { repository.reorderPinned(listOf(activityId)) }
        repository.reorderPinned(listOf(sequenceId, activityId))
        assertIdentity("activity", "sequence", 7, 9)
        repository.unpin(activityId)
        repository.pin(activityId)
        database.libraryDao().touchActivity("activity", 20)
        database.libraryDao().touchSequence("sequence", 20)
        assertIdentity("activity", "sequence", 7, 9)

        repository.archiveActivityTemplate(ActivityTemplateId("activity"), instant(30))
        repository.archiveSequenceTemplate(SequenceTemplateId("sequence"), instant(30))
        assertTrue(repository.getPinned().isEmpty())
        assertTrue(repository.getRecent(5).isEmpty())
        assertNotNull(database.activityTemplateDao().getUserState("activity")?.pinnedRank)
        assertEquals(20L, database.sequenceTemplateDao().getUserState("sequence")?.lastUsedAtMs)

        repository.restoreActivityTemplate(ActivityTemplateId("activity"))
        repository.restoreSequenceTemplate(SequenceTemplateId("sequence"))
        assertEquals(setOf("activity", "sequence"), repository.getPinned().mapTo(hashSetOf()) { it.id.value })
        assertEquals(setOf("activity", "sequence"), repository.getRecent(5).mapTo(hashSetOf()) { it.id.value })
        assertIdentity("activity", "sequence", 7, 9)

        val activityAggregate = database.activityTemplateDao().getAggregate("activity")!!
        database.activityTemplateDao().updateSemanticAggregate(
            ActivityTemplateSemanticUpdate(
                activityAggregate.template.copy(name = "Activity edited", revision = 8, updatedAtMs = 40),
                activityAggregate.settings,
                activityAggregate.fields,
                activityAggregate.options,
            ),
        )
        val sequenceAggregate = database.sequenceTemplateDao().getAggregate("sequence")!!
        database.sequenceTemplateDao().updateSemanticAggregate(
            SequenceTemplateSemanticUpdate(
                9,
                sequenceAggregate.template.copy(name = "Sequence edited", revision = 10, updatedAtMs = 40),
                sequenceAggregate.settings,
                sequenceAggregate.fields,
                sequenceAggregate.options,
                sequenceAggregate.nodes,
                sequenceAggregate.stepOverrides,
            ),
        )
        assertIdentity("activity", "sequence", 8, 10)
    }

    @Test
    fun folderDeleteFlowsMoveSubtreesArchiveContentsAndRollbackAtomically() {
        val repository = repository()
        repository.createFolder(FolderId("source"), "Source", null, instant(1))
        repository.createFolder(FolderId("nested"), "Nested", FolderId("source"), instant(2))
        repository.createFolder(FolderId("destination"), "Destination", null, instant(1))
        activity("direct", "Direct", folder = "source")
        sequence("nested-sequence", "Nested", folder = "nested")

        repository.deleteFolderMovingContents(FolderId("source"), FolderId("destination"), instant(3))
        assertNull(database.folderDao().getById("source"))
        assertEquals("destination", database.activityTemplateDao().getById("direct")?.folderId)
        assertEquals("destination", database.folderDao().getById("nested")?.parentFolderId)
        assertEquals("nested", database.sequenceTemplateDao().getById("nested-sequence")?.folderId)

        repository.createFolder(FolderId("empty"), "Empty", null, instant(3))
        repository.deleteFolderMovingContents(FolderId("empty"), null, instant(4))
        assertNull(database.folderDao().getById("empty"))

        repository.createFolder(FolderId("delete"), "Delete", null, instant(4))
        repository.createFolder(FolderId("delete-child"), "Child", FolderId("delete"), instant(5))
        activity("delete-activity", "Delete Activity", folder = "delete-child")
        sequence("delete-sequence", "Delete Sequence", folder = "delete")
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "history-snapshot",
                    "Delete Activity",
                    null,
                    "STOPWATCH",
                    null,
                    "delete-activity",
                    1,
                    "delete-activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity("history-snapshot"),
            ),
        )
        standaloneExecution("history", "history-snapshot", "delete-activity-series")
        repository.deleteFolderAndArchiveContents(FolderId("delete"), instant(6))
        assertNull(database.folderDao().getById("delete"))
        assertNull(database.folderDao().getById("delete-child"))
        assertEquals(6L, database.activityTemplateDao().getById("delete-activity")?.deletedAtMs)
        assertNull(database.activityTemplateDao().getById("delete-activity")?.folderId)
        assertEquals(6L, database.sequenceTemplateDao().getById("delete-sequence")?.deletedAtMs)
        assertNull(database.sequenceTemplateDao().getById("delete-sequence")?.folderId)
        assertNotNull(database.activityExecutionDao().getById("history"))
        assertNotNull(database.statisticsSeriesDao().getById("delete-activity-series"))

        repository.createFolder(FolderId("rollback"), "Rollback", null, instant(7))
        activity("rollback-activity", "Rollback", folder = "rollback")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_folder_delete BEFORE DELETE ON folders " +
                "WHEN OLD.id = 'rollback' BEGIN SELECT RAISE(ABORT, 'forced'); END",
        )
        assertThrows(SQLiteException::class.java) {
            repository.deleteFolderMovingContents(FolderId("rollback"), null, instant(8))
        }
        assertNotNull(database.folderDao().getById("rollback"))
        assertEquals("rollback", database.activityTemplateDao().getById("rollback-activity")?.folderId)
    }

    @Test
    fun deletingTagCascadesOnlyClassificationLinks() {
        val repository = repository()
        repository.createTag(TagId("tag"), "Tag", instant(1))
        activity("activity", "Activity")
        sequence("sequence", "Sequence")
        repository.addTag(LibraryTemplateId.Activity(ActivityTemplateId("activity")), TagId("tag"))
        repository.addTag(LibraryTemplateId.Sequence(SequenceTemplateId("sequence")), TagId("tag"))
        seedSnapshot("snapshot", "STOPWATCH")
        LiveRuntimeTestFixtures(database).standaloneExecution("execution", "snapshot")

        repository.deleteTag(TagId("tag"))

        assertTrue(database.activityTemplateDao().getTagIds("activity").isEmpty())
        assertTrue(database.sequenceTemplateDao().getTags("sequence").isEmpty())
        assertNotNull(database.activityTemplateDao().getById("activity"))
        assertNotNull(database.sequenceTemplateDao().getById("sequence"))
        assertNotNull(database.activityExecutionDao().getById("execution"))
        assertNotNull(database.statisticsSeriesDao().getById("activity-series"))
    }

    @Test
    fun productionTemplateLaunchesCreateFreshSnapshotsAndUpdateRecentWithoutPlanLinkage() {
        val repository = repository()
        val activityCommands = activityCommands()
        activity("timed", "Timed")
        activity("timer", "Timer", mode = "TIMER")
        activity(
            "no-live",
            "No live",
            mode = "NO_LIVE_TRACKING",
            fields = listOf(activityField("no-live-field", "no-live", deleted = null, name = "Shared value")),
        )
        activity(
            "same-label-other",
            "Other",
            mode = "NO_LIVE_TRACKING",
            fields =
                listOf(
                    activityField(
                        "same-label-other-field",
                        "same-label-other",
                        deleted = null,
                        name = "Shared value",
                    ),
                ),
        )
        seedSnapshot("plan-snapshot", "STOPWATCH")
        database.planEntryDao().insert(
            PlanEntryEntity(
                "unrelated-plan",
                "ACTIVITY",
                null,
                null,
                null,
                "plan-snapshot",
                null,
                "DAY",
                "1970-01-01",
                null,
                null,
                null,
                null,
                "PLANNED",
                null,
                null,
                0,
                0,
                null,
                null,
            ),
        )
        seedSnapshot("step", "NO_LIVE_TRACKING")
        sequence(
            "sequence",
            "Sequence",
            recent = 100,
            nodes = listOf(step("sequence-step", "sequence", "step")),
        )

        repository.archiveActivityTemplate(ActivityTemplateId("timed"), instant(5))
        assertThrows(IllegalArgumentException::class.java) {
            activityCommands.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("timed")),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
            )
        }
        repository.restoreActivityTemplate(ActivityTemplateId("timed"))
        val timed =
            activityCommands.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("timed")),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
            )
        assertEquals(ActivityExecutionContext.STANDALONE, timed.context)
        assertNull(timed.planEntryId)
        assertEquals("timed", database.activitySnapshotDao().getById(timed.snapshotId.value)?.sourceTemplateId)
        assertEquals(10L, database.activityTemplateDao().getUserState("timed")?.lastUsedAtMs)
        assertEquals(1L, database.activityTemplateDao().getById("timed")?.revision)
        assertEquals("PLANNED", database.planEntryDao().getById("unrelated-plan")?.status)
        liveForExistingDatabase().completeActiveActivity(instant(20))
        val timer =
            activityCommands.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("timer")),
                instant(21),
                instant(21),
                ZoneOffset.UTC,
            )
        val timerSnapshot =
            requireNotNull(
                database.activitySnapshotDao().getAggregate(timer.snapshotId.value),
            ).toDomain()
        assertEquals("TIMER", timerSnapshot.timeTrackingMode.name)
        assertEquals(60_000L, timerSnapshot.timerTarget?.toMillis())
        assertEquals("FINISH", timerSnapshot.settings.timerZeroBehavior.name)
        val activeTimer =
            DailyReadRepository(database, CurrentZoneIdProvider { ZoneOffset.UTC }, liveForExistingDatabase())
                .getDaily(DailyQuery(LocalDate.ofEpochDay(0), instant(21), 100))
                .active as DailyActive.Activity
        assertEquals(timer.id, activeTimer.runtime.execution.id)
        val noLive =
            repository.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("no-live"),
                instant(30),
                instant(29),
                ZoneOffset.UTC,
            )
        assertEquals(ActivityExecutionStatus.COMPLETED, noLive.status)
        assertEquals(1_000L, (noLive.values.single() as NumberExecutionValue).scaledValue)
        assertNull(noLive.activeDuration)
        assertNull(noLive.completionReason)
        assertNull(noLive.planEntryId)
        assertEquals(timer.id, database.activeSessionDao().get()?.activityExecutionId)
        assertEquals(29L, database.activityTemplateDao().getUserState("no-live")?.lastUsedAtMs)
        val overridden =
            repository.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("no-live"),
                instant(31),
                instant(31),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(ActivityTemplateFieldId("no-live-field")),
                        ActivityEntryValue.Number(0),
                    ),
                ),
            )
        assertEquals(0L, (overridden.values.single() as NumberExecutionValue).scaledValue)
        assertEquals(
            ActivityTemplateFieldId("no-live-field"),
            database
                .activitySnapshotDao()
                .getAggregate(overridden.snapshotId.value)
                ?.toDomain()
                ?.fields
                ?.single()
                ?.sourceFieldId,
        )
        val missing =
            repository.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("no-live"),
                instant(32),
                instant(32),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(ActivityTemplateFieldId("no-live-field")),
                        ActivityEntryValue.Missing,
                    ),
                ),
            )
        assertTrue(missing.values.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            repository.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("no-live"),
                instant(33),
                instant(33),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(ActivityTemplateFieldId("same-label-other-field")),
                        ActivityEntryValue.Number(99),
                    ),
                ),
            )
        }
        assertNull(database.activitySnapshotDao().getById("activity-launch-6"))
        assertEquals(32L, database.activityTemplateDao().getUserState("no-live")?.lastUsedAtMs)
        assertEquals(timer.id, database.activeSessionDao().get()?.activityExecutionId)
        val dailyAfterNoLive =
            DailyReadRepository(database, CurrentZoneIdProvider { ZoneOffset.UTC }, liveForExistingDatabase())
                .getDaily(DailyQuery(LocalDate.ofEpochDay(0), instant(32), 100))
        assertEquals(timer.id, (dailyAfterNoLive.active as DailyActive.Activity).runtime.execution.id)
        assertTrue(
            dailyAfterNoLive.completedHistory
                .filterIsInstance<CompletedActivityHistoryRoot>()
                .mapTo(mutableSetOf()) { it.executionId }
                .containsAll(setOf(noLive.id, overridden.id, missing.id)),
        )
        val detail = requireNotNull(HistoryReadRepository(database).getActivityDetail(overridden.id))
        assertEquals(
            0L,
            (detail.fields.single().actualValue as ActivityHistoryActualValue.Number).scaledValue,
        )
        assertEquals("Shared value", detail.fields.single().name)

        liveForExistingDatabase().completeActiveActivity(instant(34))

        repository.archiveSequenceTemplate(SequenceTemplateId("sequence"), instant(35))
        assertThrows(IllegalArgumentException::class.java) {
            repository.startSequenceFromTemplate(
                SequenceTemplateId("sequence"),
                instant(40),
                instant(40),
                ZoneOffset.UTC,
            )
        }
        repository.restoreSequenceTemplate(SequenceTemplateId("sequence"))
        val sequence =
            repository.startSequenceFromTemplate(
                SequenceTemplateId("sequence"),
                instant(40),
                instant(40),
                ZoneOffset.UTC,
            )
        assertNull(sequence.execution.planEntryId)
        assertEquals(
            "sequence",
            database.sequenceSnapshotDao().getById(sequence.execution.snapshotId.value)?.sourceTemplateId,
        )
        assertTrue(sequence.children.values.all { it.planEntryId == null })
        assertEquals(100L, database.sequenceTemplateDao().getUserState("sequence")?.lastUsedAtMs)
        assertEquals(1L, database.sequenceTemplateDao().getById("sequence")?.revision)
        assertEquals("PLANNED", database.planEntryDao().getById("unrelated-plan")?.status)
        val activeSequence =
            DailyReadRepository(database, CurrentZoneIdProvider { ZoneOffset.UTC }, liveForExistingDatabase())
                .getDaily(DailyQuery(LocalDate.ofEpochDay(0), instant(40), 100))
                .active as DailyActive.Sequence
        assertEquals(sequence.execution.id, activeSequence.runtime.execution.id)
    }

    @Test
    fun launchCollisionsRollbackSnapshotsSessionAndRecentForBothKinds() {
        activity("activity", "Activity")
        seedSnapshot("existing", "STOPWATCH")
        LiveRuntimeTestFixtures(database).standaloneExecution("activity-collision", "existing")
        val activityRepository = repository(activityExecutionCollision = "activity-collision")

        assertThrows(SQLiteConstraintException::class.java) {
            activityRepository.startActivityFromTemplate(
                ActivityTemplateId("activity"),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
            )
        }
        assertNull(database.activitySnapshotDao().getById("activity-launch-1"))
        assertNull(database.activeSessionDao().get())
        assertNull(database.activityTemplateDao().getUserState("activity")?.lastUsedAtMs)
        assertEquals("existing", database.activityExecutionDao().getById("activity-collision")?.snapshotId)

        seedSnapshot("step", "STOPWATCH")
        sequence("sequence", "Sequence", nodes = listOf(step("sequence-step", "sequence", "step")))
        seedSnapshot("stopwatch", "STOPWATCH")
        LiveRuntimeTestFixtures(database).apply {
            sequence("existing-sequence", listOf("stopwatch"))
            sequenceExecution("sequence-collision", "existing-sequence")
        }
        val sequenceRepository = repository(sequenceExecutionCollision = "sequence-collision")
        assertThrows(SQLiteConstraintException::class.java) {
            sequenceRepository.startSequenceFromTemplate(
                SequenceTemplateId("sequence"),
                instant(20),
                instant(20),
                ZoneOffset.UTC,
            )
        }
        assertNull(database.sequenceSnapshotDao().getById("sequence-launch-1"))
        assertNull(database.activeSessionDao().get())
        assertNull(database.sequenceTemplateDao().getUserState("sequence")?.lastUsedAtMs)
        assertEquals("existing-sequence", database.sequenceExecutionDao().getById("sequence-collision")?.snapshotId)
    }

    @Test
    fun liveConflictRollsBackRequestedSnapshotAndRecentWithoutTouchingTheWinner() {
        activity("winner", "Winner")
        activity("loser", "Loser")
        val repository = repository()
        val winner =
            repository.startActivityFromTemplate(
                ActivityTemplateId("winner"),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
            )

        assertThrows(LiveSessionConflictException::class.java) {
            repository.startActivityFromTemplate(
                ActivityTemplateId("loser"),
                instant(20),
                instant(20),
                ZoneOffset.UTC,
            )
        }

        assertNull(database.activitySnapshotDao().getById("activity-launch-2"))
        assertNull(database.activityTemplateDao().getUserState("loser")?.lastUsedAtMs)
        assertEquals(winner.id, database.activeSessionDao().get()?.activityExecutionId)
    }

    @Test
    fun sourceLinkedPlanUseStillFeedsTheMixedRecentProjection() {
        val repository = repository()
        activity("plan-source", "Plan Source", revision = 5)
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "plan-source-snapshot",
                    "Plan Source",
                    null,
                    "STOPWATCH",
                    null,
                    "plan-source",
                    5,
                    "plan-source-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity("plan-source-snapshot"),
            ),
        )
        database.planEntryDao().insert(
            PlanEntryEntity(
                "source-plan",
                "ACTIVITY",
                "plan-source",
                null,
                5,
                "plan-source-snapshot",
                null,
                "DAY",
                "1970-01-01",
                null,
                null,
                null,
                null,
                "PLANNED",
                null,
                null,
                0,
                0,
                null,
                null,
            ),
        )

        liveForExistingDatabase().startActivityFromPlan(
            com.alexandr5476.lifetracing.domain
                .PlanEntryId("source-plan"),
            instant(50),
            instant(60),
            ZoneOffset.UTC,
        )

        assertEquals(listOf("plan-source"), repository.getRecent(1).map { it.id.value })
        assertEquals(60L, database.activityTemplateDao().getUserState("plan-source")?.lastUsedAtMs)
        assertEquals(5L, database.activityTemplateDao().getById("plan-source")?.revision)
    }

    @Test
    fun duplicationCreatesIndependentLineageAndCopiesOnlyActiveReusableConfiguration() {
        val repository = repository()
        repository.createFolder(FolderId("folder"), "Folder", null, instant(1))
        repository.createTag(TagId("tag"), "Tag", instant(1))
        activity(
            "activity",
            "Activity",
            folder = "folder",
            pinned = 1,
            recent = 2,
            fields =
                listOf(
                    activityField("active-field", "activity", deleted = null),
                    activityField("deleted-field", "activity", deleted = 3),
                ),
        )
        repository.addTag(LibraryTemplateId.Activity(ActivityTemplateId("activity")), TagId("tag"))
        seedSnapshot("step", "STOPWATCH")
        sequence(
            "sequence",
            "Sequence",
            folder = "folder",
            pinned = 1,
            recent = 2,
            fields = listOf(sequenceField("sequence-field", "sequence")),
            nodes = listOf(step("source-node", "sequence", "step")),
        )
        repository.addTag(LibraryTemplateId.Sequence(SequenceTemplateId("sequence")), TagId("tag"))

        val activityCopy = repository.duplicateActivityTemplate(ActivityTemplateId("activity"), instant(10))
        val sequenceCopy = repository.duplicateSequenceTemplate(SequenceTemplateId("sequence"), instant(10))

        assertNotEquals(ActivityTemplateId("activity"), activityCopy.id)
        assertNotEquals("activity-series", activityCopy.statisticsSeriesId.value)
        assertEquals(FolderId("folder"), activityCopy.folderId)
        assertEquals(setOf(TagId("tag")), activityCopy.tagIds)
        assertEquals(1, activityCopy.fields.size)
        assertNotEquals(ActivityTemplateFieldId("active-field"), activityCopy.fields.single().id)
        assertNull(database.activityTemplateDao().getUserState(activityCopy.id.value)?.pinnedRank)
        assertNull(database.activityTemplateDao().getUserState(activityCopy.id.value)?.lastUsedAtMs)

        assertNotEquals(SequenceTemplateId("sequence"), sequenceCopy.id)
        assertNotEquals("sequence-series", sequenceCopy.statisticsSeriesId.value)
        assertEquals(FolderId("folder"), sequenceCopy.folderId)
        assertEquals(setOf(TagId("tag")), sequenceCopy.tagIds)
        assertNotEquals(SequenceTemplateFieldId("sequence-field"), sequenceCopy.fields.single().id)
        assertNotEquals(SequenceNodeId("source-node"), sequenceCopy.nodes.single().id)
        assertEquals(
            ActivitySnapshotId("step"),
            (sequenceCopy.nodes.single() as com.alexandr5476.lifetracing.domain.ActivityStep).activitySnapshotId,
        )
        assertNull(sequenceCopy.userState.pinnedRank)
        assertNull(sequenceCopy.userState.lastUsedAt)
        assertNotNull(database.statisticsSeriesDao().getById(activityCopy.statisticsSeriesId.value))
        assertNotNull(database.statisticsSeriesDao().getById(sequenceCopy.statisticsSeriesId.value))
    }

    private fun repository(
        activityExecutionCollision: String? = null,
        sequenceExecutionCollision: String? = null,
    ): LibraryRepository {
        var activityExecution = 0
        var sequenceExecution = 0
        var occurrence = 0
        var interval = 0
        var activitySnapshot = 0
        var activitySnapshotField = 0
        var activitySnapshotOption = 0
        var sequenceSnapshot = 0
        var sequenceSnapshotField = 0
        var sequenceSnapshotOption = 0
        var sequenceSnapshotNode = 0
        var activityTemplate = 0
        var activityField = 0
        var activityOption = 0
        var sequenceTemplate = 0
        var sequenceField = 0
        var sequenceOption = 0
        var sequenceNode = 0
        var series = 0
        val live =
            LiveSessionRepository(
                database,
                { ActivityExecutionId(activityExecutionCollision ?: "activity-execution-${++activityExecution}") },
                { ActivityExecutionPauseId("pause-${++activityExecution}") },
                { SequenceExecutionId(sequenceExecutionCollision ?: "sequence-execution-${++sequenceExecution}") },
                { SequenceOccurrenceId("occurrence-${++occurrence}") },
                { SequenceIntervalId("interval-${++interval}") },
            )
        return LibraryRepository(
            database,
            live,
            ActivitySnapshotFactory(
                { ActivitySnapshotId("activity-launch-${++activitySnapshot}") },
                { ActivitySnapshotFieldId("activity-launch-field-${++activitySnapshotField}") },
                { ActivitySnapshotCategoryOptionId("activity-launch-option-${++activitySnapshotOption}") },
            ),
            SequenceSnapshotFactory(
                { SequenceSnapshotId("sequence-launch-${++sequenceSnapshot}") },
                { SequenceSnapshotFieldId("sequence-launch-field-${++sequenceSnapshotField}") },
                { SequenceSnapshotCategoryOptionId("sequence-launch-option-${++sequenceSnapshotOption}") },
                { SequenceSnapshotNodeId("sequence-launch-node-${++sequenceSnapshotNode}") },
            ),
            { ActivityTemplateId("activity-copy-${++activityTemplate}") },
            { ActivityTemplateFieldId("activity-copy-field-${++activityField}") },
            { CategoryOptionId("activity-copy-option-${++activityOption}") },
            { SequenceTemplateId("sequence-copy-${++sequenceTemplate}") },
            { SequenceTemplateFieldId("sequence-copy-field-${++sequenceField}") },
            { SequenceTemplateCategoryOptionId("sequence-copy-option-${++sequenceOption}") },
            { SequenceNodeId("sequence-copy-node-${++sequenceNode}") },
            { StatisticsSeriesId("copy-series-${++series}") },
        )
    }

    private fun activityCommands(): ActivityCommandRepository {
        var execution = 0
        var snapshot = 0
        var field = 0
        var option = 0
        return ActivityCommandRepository(
            database,
            LiveSessionRepository(
                database,
                { ActivityExecutionId("command-execution-${++execution}") },
                { ActivityExecutionPauseId("command-pause-${++execution}") },
                { SequenceExecutionId("unused-sequence") },
                { SequenceOccurrenceId("unused-occurrence") },
                { SequenceIntervalId("unused-interval") },
            ),
            ActivitySnapshotFactory(
                { ActivitySnapshotId("command-snapshot-${++snapshot}") },
                { ActivitySnapshotFieldId("command-field-${++field}") },
                { ActivitySnapshotCategoryOptionId("command-option-${++option}") },
            ),
            { ActivityExecutionId("command-execution-${++execution}") },
        )
    }

    private fun liveForExistingDatabase(): LiveSessionRepository {
        var pause = 0
        return LiveSessionRepository(
            database,
            { ActivityExecutionId("unused") },
            { ActivityExecutionPauseId("completion-pause-${++pause}") },
            { SequenceExecutionId("unused-sequence") },
            { SequenceOccurrenceId("unused-occurrence") },
            { SequenceIntervalId("unused-interval") },
        )
    }

    private fun activity(
        id: String,
        name: String,
        mode: String = "STOPWATCH",
        folder: String? = null,
        deleted: Long? = null,
        revision: Long = 1,
        pinned: Int? = null,
        recent: Long? = null,
        fields: List<ActivityTemplateFieldEntity> = emptyList(),
        settings: ActivityTemplateSettingsEntity = ActivityTemplateSettingsEntity(id),
    ) {
        series("$id-series", "ACTIVITY")
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    id,
                    name,
                    "$name comment",
                    mode,
                    if (mode == "TIMER") 60_000 else null,
                    "$id-series",
                    revision,
                    0,
                    0,
                    deleted,
                    folder,
                ),
                settings,
                fields = fields,
                userState = ActivityTemplateUserStateEntity(id, pinned, recent),
            ),
        )
    }

    private fun sequence(
        id: String,
        name: String,
        folder: String? = null,
        deleted: Long? = null,
        revision: Long = 1,
        pinned: Int? = null,
        recent: Long? = null,
        fields: List<SequenceTemplateFieldEntity> = emptyList(),
        nodes: List<SequenceNodeEntity> = emptyList(),
        settings: SequenceTemplateSettingsEntity = SequenceTemplateSettingsEntity(id),
        stepOverrides: List<SequenceStepOverrideEntity> = emptyList(),
    ) {
        series("$id-series", "SEQUENCE")
        database.sequenceTemplateDao().insertAggregate(
            SequenceTemplateAggregateEntity(
                SequenceTemplateEntity(id, name, "$name comment", "$id-series", revision, 0, 0, deleted, folder),
                settings,
                SequenceTemplateUserStateEntity(id, pinned, recent),
                fields = fields,
                nodes = nodes,
                stepOverrides = stepOverrides,
            ),
        )
    }

    private fun seedSnapshot(
        id: String,
        mode: String,
    ) {
        series("activity-series", "ACTIVITY")
        series("sequence-series", "SEQUENCE")
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(id, id, null, mode, null, null, null, "activity-series", false, 0),
                ActivitySnapshotSettingsEntity(id),
            ),
        )
    }

    private fun step(
        id: String,
        sequenceId: String,
        snapshotId: String,
    ) = SequenceNodeEntity(id, sequenceId, "STEP", null, 0, snapshotId, null)

    private fun standaloneExecution(
        id: String,
        snapshotId: String,
        seriesId: String,
    ) {
        database.activityExecutionDao().insertAggregate(
            ActivityExecutionAggregateEntity(
                ActivityExecutionEntity(
                    id,
                    snapshotId,
                    "STANDALONE",
                    null,
                    null,
                    null,
                    seriesId,
                    "RUNNING",
                    0,
                    null,
                    null,
                    "UTC",
                    0,
                    "1970-01-01",
                    null,
                    null,
                    0,
                    0,
                ),
            ),
        )
    }

    private fun activityField(
        id: String,
        owner: String,
        deleted: Long?,
        name: String = id,
    ) = ActivityTemplateFieldEntity(id, owner, 0, name, "NUMBER", "reps", 0, 1_000, null, null, true, 0, 0, deleted)

    private fun sequenceField(
        id: String,
        owner: String,
    ) = SequenceTemplateFieldEntity(id, owner, 0, id, "NUMBER", "reps", 0, 1_000, null, null, true, 0, 0, null)

    private fun series(
        id: String,
        kind: String,
    ) {
        if (database.statisticsSeriesDao().getById(id) == null) {
            database.statisticsSeriesDao().insert(StatisticsSeriesEntity(id, kind, id, 0, null))
        }
    }

    private fun assertIdentity(
        activityId: String,
        sequenceId: String,
        activityRevision: Long,
        sequenceRevision: Long,
    ) {
        assertEquals(activityRevision, database.activityTemplateDao().getById(activityId)?.revision)
        assertEquals(sequenceRevision, database.sequenceTemplateDao().getById(sequenceId)?.revision)
        assertEquals("$activityId-series", database.activityTemplateDao().getById(activityId)?.statisticsSeriesId)
        assertEquals("$sequenceId-series", database.sequenceTemplateDao().getById(sequenceId)?.statisticsSeriesId)
    }

    private fun instant(ms: Long): Instant = Instant.ofEpochMilli(ms)
}
