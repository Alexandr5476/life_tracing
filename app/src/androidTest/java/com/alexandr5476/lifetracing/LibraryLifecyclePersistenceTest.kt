@file:Suppress("LongMethod")

package com.alexandr5476.lifetracing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.HistoryReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.StatisticsRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.LauncherLoad
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityAction
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.library.LibraryMutation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class LibraryLifecyclePersistenceTest {
    @Test
    fun productionArchiveBoundaryPreservesIdentityHistoryStatisticsAndFreshLauncherExclusion() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val suffix = now.toEpochMilli().toString()
            val folderId = FolderId("s4-archive-folder-$suffix")
            val tagId = TagId("s4-archive-tag-$suffix")
            val library = LibraryRepository.create(context)
            val live = LiveSessionRepository.create(context)
            clearActive(live, now)
            library.createFolder(folderId, "S4 archive folder $suffix", null, now.minusSeconds(20))
            library.createTag(tagId, "S4 archive tag $suffix", now.minusSeconds(20))
            val authoring = TemplateAuthoringRepository.create(context)
            val placement = TemplateLibraryPlacement(folderId, setOf(tagId))
            val activity =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft("S4 activity $suffix", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                    placement,
                    now.minusSeconds(19),
                )
            val sequence =
                authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "S4 sequence $suffix",
                        null,
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    ActivityStepDraft(
                                        DraftIdentity.New("step"),
                                        0,
                                        StepActivityDraft.FromTemplate(activity.id),
                                    ),
                                ),
                            ),
                    ),
                    placement,
                    now.minusSeconds(18),
                )
            val activityId = LibraryTemplateId.Activity(activity.id)
            val sequenceId = LibraryTemplateId.Sequence(sequence.id)
            library.pin(activityId)
            library.pin(sequenceId)
            val activityExecution =
                library.completeNoLiveActivityFromTemplate(
                    activity.id,
                    now.minusSeconds(16),
                    now.minusSeconds(16),
                    ZoneOffset.UTC,
                )
            val sequenceExecution =
                library.startSequenceFromTemplate(
                    sequence.id,
                    now.minusSeconds(15),
                    now.minusSeconds(15),
                    ZoneOffset.UTC,
                )
            live.endSequenceEarly(now.minusSeconds(14))

            executeLibraryMutation(LibraryMutation.ArchiveTemplate(activityId, now.minusSeconds(2)), library)
            executeLibraryMutation(LibraryMutation.ArchiveTemplate(sequenceId, now.minusSeconds(1)), library)

            val freshLibrary = LibraryRepository.create(context)
            val freshAuthoring = TemplateAuthoringRepository.create(context)
            val archived = freshLibrary.getArchived().filter { it.id == activityId || it.id == sequenceId }
            assertEquals(setOf(activityId, sequenceId), archived.mapTo(hashSetOf()) { it.id })
            assertTrue(archived.all { it.folderId == folderId && tagId in it.tagIds })
            assertTrue(freshLibrary.getFolderContents(folderId).activities.isEmpty())
            assertTrue(freshLibrary.getFolderContents(folderId).sequences.isEmpty())
            assertTrue(freshLibrary.search("S4").none { it.id == activityId || it.id == sequenceId })
            assertTrue(freshLibrary.getPinned().none { it.id == activityId || it.id == sequenceId })
            assertTrue(freshLibrary.getRecent(100).none { it.id == activityId || it.id == sequenceId })
            val archivedActivity = requireNotNull(freshAuthoring.getActivityTemplate(activity.id))
            val archivedSequence = requireNotNull(freshAuthoring.getSequenceTemplate(sequence.id))
            assertEquals(activity.id, archivedActivity.id)
            assertEquals(activity.statisticsSeriesId, archivedActivity.statisticsSeriesId)
            assertEquals(activity.revision, archivedActivity.revision)
            assertEquals(sequence.id, archivedSequence.id)
            assertEquals(sequence.statisticsSeriesId, archivedSequence.statisticsSeriesId)
            assertEquals(sequence.revision, archivedSequence.revision)
            assertNotNull(HistoryReadRepository.create(context).getActivityDetail(activityExecution.id))
            assertNotNull(HistoryReadRepository.create(context).getSequenceDetail(sequenceExecution.execution.id))
            val seriesIds = StatisticsRepository.create(context).seriesCatalog().mapTo(hashSetOf()) { it.id }
            assertTrue(activity.statisticsSeriesId in seriesIds)
            assertTrue(sequence.statisticsSeriesId in seriesIds)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val launcher = canonicalLauncher(scope, freshLibrary, live, now)
            try {
                val home =
                    withTimeout(5_000) { launcher.state.first { it.home is LauncherLoad.Content } }
                        .let { (it.home as LauncherLoad.Content).value }
                assertTrue(home.pinned.none { it.id == activityId || it.id == sequenceId })
                assertTrue(home.recent.none { it.id == activityId || it.id == sequenceId })
                launcher.dispatch(StartActivityAction.Search("S4"))
                val search =
                    withTimeout(5_000) { launcher.state.first { it.search is LauncherLoad.Content } }
                        .let { (it.search as LauncherLoad.Content).value }
                assertTrue(search.none { it.id == activityId || it.id == sequenceId })
                launcher.dispatch(StartActivityAction.Browse(folderId))
                val browse =
                    withTimeout(5_000) { launcher.state.first { it.browse is LauncherLoad.Content } }
                        .let { (it.browse as LauncherLoad.Content).value }
                assertTrue(browse.activities.isEmpty())
                assertTrue(browse.sequences.isEmpty())
            } finally {
                launcher.close()
                scope.cancel()
            }
        }

    @Test
    fun productionFolderDeleteBoundariesPreserveSubtreesAndArchiveWithoutPurge() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.now()
        val suffix = now.toEpochMilli().toString()
        val library = LibraryRepository.create(context)
        val authoring = TemplateAuthoringRepository.create(context)
        val source = FolderId("s4-move-source-$suffix")
        val child = FolderId("s4-move-child-$suffix")
        val grandchild = FolderId("s4-move-grandchild-$suffix")
        val destination = FolderId("s4-move-destination-$suffix")
        val empty = FolderId("s4-empty-$suffix")
        library.createFolder(empty, "Empty $suffix", null, now.minusSeconds(20))
        executeLibraryMutation(LibraryMutation.DeleteEmptyFolder(empty, now.minusSeconds(19)), library)
        assertTrue(library.getFolders().none { it.id == empty })
        library.createFolder(source, "Move source $suffix", null, now.minusSeconds(20))
        library.createFolder(child, "Move child $suffix", source, now.minusSeconds(19))
        library.createFolder(grandchild, "Move grandchild $suffix", child, now.minusSeconds(18))
        library.createFolder(destination, "Move destination $suffix", null, now.minusSeconds(20))
        val directActivity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Move activity $suffix", null, TimeTrackingMode.STOPWATCH, null),
                TemplateLibraryPlacement(source),
                now.minusSeconds(17),
            )
        val directSequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft("Move sequence $suffix", null),
                TemplateLibraryPlacement(source),
                now.minusSeconds(16),
            )
        val nestedActivity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Nested activity $suffix", null, TimeTrackingMode.STOPWATCH, null),
                TemplateLibraryPlacement(grandchild),
                now.minusSeconds(15),
            )

        executeLibraryMutation(
            LibraryMutation.DeleteFolderMovingContents(source, destination, now.minusSeconds(10)),
            library,
        )

        val moved = LibraryRepository.create(context)
        assertFalse(moved.getFolders().any { it.id == source })
        assertEquals(destination, moved.getAll().single { it.id.value == directActivity.id.value }.folderId)
        assertEquals(destination, moved.getAll().single { it.id.value == directSequence.id.value }.folderId)
        assertEquals(destination, moved.getFolders().single { it.id == child }.parentFolderId)
        assertEquals(child, moved.getFolders().single { it.id == grandchild }.parentFolderId)
        assertEquals(grandchild, moved.getAll().single { it.id.value == nestedActivity.id.value }.folderId)

        val deleteRoot = FolderId("s4-delete-root-$suffix")
        val deleteChild = FolderId("s4-delete-child-$suffix")
        library.createFolder(deleteRoot, "Delete root $suffix", null, now.minusSeconds(9))
        library.createFolder(deleteChild, "Delete child $suffix", deleteRoot, now.minusSeconds(8))
        val deletedActivity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Delete activity $suffix", null, TimeTrackingMode.STOPWATCH, null),
                TemplateLibraryPlacement(deleteChild),
                now.minusSeconds(7),
            )
        val deletedSequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft("Delete sequence $suffix", null),
                TemplateLibraryPlacement(deleteRoot),
                now.minusSeconds(6),
            )

        executeLibraryMutation(
            LibraryMutation.DeleteFolderAndArchiveContents(deleteRoot, now.minusSeconds(1)),
            library,
        )

        val deleted = LibraryRepository.create(context)
        assertTrue(deleted.getFolders().none { it.id == deleteRoot || it.id == deleteChild })
        val archivedIds = deleted.getArchived().mapTo(hashSetOf()) { it.id }
        assertTrue(LibraryTemplateId.Activity(deletedActivity.id) in archivedIds)
        assertTrue(LibraryTemplateId.Sequence(deletedSequence.id) in archivedIds)
        assertNull(authoring.getActivityTemplate(deletedActivity.id)?.folderId)
        assertNull(authoring.getSequenceTemplate(deletedSequence.id)?.folderId)
        assertEquals(deletedActivity.revision, authoring.getActivityTemplate(deletedActivity.id)?.revision)
        assertEquals(deletedSequence.revision, authoring.getSequenceTemplate(deletedSequence.id)?.revision)
        val seriesIds = StatisticsRepository.create(context).seriesCatalog().mapTo(hashSetOf()) { it.id }
        assertTrue(
            setOf(deletedActivity.statisticsSeriesId, deletedSequence.statisticsSeriesId).all(seriesIds::contains),
        )
    }

    private fun canonicalLauncher(
        scope: CoroutineScope,
        library: LibraryRepository,
        live: LiveSessionRepository,
        now: Instant,
    ) = StartActivityController(
        scope,
        library::getRecent,
        library::getPinned,
        { library.search(it) },
        { id -> id?.let(library::getFolderContents) ?: library.getRoot().contents },
        library::getLaunchTarget,
        library::reorderPinned,
        { live.getActiveSession() != null },
        { error("Launcher writer is not used") },
        {},
        WallClock { now },
        { ZoneOffset.UTC },
        PreflightScheduler { _, _ -> PreflightHandle {} },
    )

    private fun clearActive(
        live: LiveSessionRepository,
        at: Instant,
    ) {
        when (live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(at)
            ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(at)
            null -> Unit
        }
    }
}
