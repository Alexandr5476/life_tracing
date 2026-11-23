package com.alexandr5476.lifetracing

import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MINUTES
import java.time.temporal.ChronoUnit.SECONDS

/** IDs from the seed commands select the same bounded canonical reads on each database open. */
internal class ConfigurationRecoveryFacts private constructor(
    val activity: ActivityTemplate,
    val archived: LibraryTemplateId.Activity,
    private val sequence: SequenceTemplate,
    private val planId: PlanEntryId,
    val overlapId: ActivityExecutionId,
    private val zeroId: ActivityExecutionId,
    private val noLiveId: ActivityExecutionId,
    private val sequenceExecutionId: SequenceExecutionId,
    private val pausedId: ActivityExecutionId,
) {
    fun read(store: RuntimeRecoveryStore): Map<String, Any?> =
        with(store) {
            mapOf(
                "activity authoring" to requireNotNull(authoring.getActivityTemplate(activity.id)),
                "archived activity authoring" to requireNotNull(authoring.getActivityTemplate(archived.id)),
                "sequence and linked Step snapshots" to
                    requireNotNull(authoring.getSequenceTemplateAuthoringState(sequence.id)),
                "library rows" to library.getAll(),
                "library archive" to library.getArchived(),
                "library folders" to library.getFolders(),
                "library tags" to library.getTags(),
                "library pinned" to library.getPinned(),
                "library recent" to library.getRecent(5),
                "plan target and fulfillment" to requireNotNull(plans.getPlan(planId)),
                "focused Plan and source snapshots" to planReads.getFocusedAction(planId),
                "completed history roots" to
                    history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 8)),
                "activity history details" to
                    listOf(overlapId, zeroId, noLiveId).map { requireNotNull(history.getActivityDetail(it)) },
                "activity histories and snapshot provenance" to
                    listOf(overlapId, zeroId, noLiveId).map {
                        requireNotNull(activityCommands.getHistory(it))
                    },
                "sequence history, occurrences and children" to
                    requireNotNull(history.getSequenceDetail(sequenceExecutionId)),
                "sequence execution" to sequence(sequenceExecutionId),
                "sequence child executions" to children(sequenceExecutionId),
                "statistics overview" to statistics.overview(period),
                "statistics catalog" to statistics.seriesCatalog(),
                "series details" to
                    listOf(activity.statisticsSeriesId, sequence.statisticsSeriesId).map {
                        statistics.seriesDetail(it, period)
                    },
                "active pointer" to requireNotNull(live.getActiveSession()),
                "active runtime" to requireNotNull(live.getActiveRuntime()),
            )
        }

    fun assertSeed(store: RuntimeRecoveryStore) {
        val overlap = requireNotNull(store.history.getActivityDetail(overlapId))
        val frozen = requireNotNull(store.activityCommands.getHistory(overlapId)).snapshot
        val category = activity.fields.single { it.type == CustomFieldType.CATEGORY }
        val frozenCategory = frozen.fields.single { it.sourceFieldId == category.id }
        val actualCategory =
            overlap.fields
                .single {
                    it.id == frozenCategory.id
                }.actualValue as ActivityHistoryActualValue.Category
        assertEquals(
            category.categoryOptions.last().id,
            frozenCategory.categoryOptions
                .single {
                    it.id ==
                        actualCategory.optionId
                }.sourceOptionId,
        )
        assertEquals(category.categoryOptions.last().label, actualCategory.label)
        assertEquals(date, overlap.root.primaryLocalDate)
        assertEquals(overlapStart, overlap.root.startedAt)
        assertEquals(overlapEnd, overlap.root.completedAt)
        assertEquals(zone, overlap.originalZoneId)
        assertEquals(-240, overlap.root.originalUtcOffsetMinutes)
        assertEquals(activity.shortComment, overlap.root.shortComment)
        assertEquals(activity.statisticsSeriesId, frozen.statisticsSeriesId)
        val zero = requireNotNull(store.history.getActivityDetail(zeroId))
        val zeroCompletedAt = zero.root.completedAt
        val zeroUtcDate = zeroCompletedAt.atOffset(ZoneOffset.UTC).toLocalDate()
        assertEquals(date.plusDays(1), zeroUtcDate)
        assertEquals(date, zero.root.primaryLocalDate)
        assertEquals(Duration.ZERO, zero.root.activeDuration)
        val number = zero.fields.single { it.isMainValue }
        assertEquals(ActivityHistoryConfiguredValue.Number(Long.MAX_VALUE), number.configuredValue)
        assertEquals(ActivityHistoryActualValue.Number(0), number.actualValue)
        assertNull(requireNotNull(store.history.getActivityDetail(noLiveId)).root.activeDuration)
        assertSequenceSeed(store)
        val active = requireNotNull(store.live.getActiveSession())
        assertEquals(pausedId, active.activityExecutionId)
        assertEquals(ActiveSessionState.PAUSED, active.state)
        val global = store.statistics.overview(period).global
        assertEquals(4L, global.topLevelExecutionCount)
        assertEquals(Duration.ofMinutes(65), global.totalTrackedDuration)
    }

    private fun assertSequenceSeed(store: RuntimeRecoveryStore) {
        val plan = requireNotNull(store.plans.getPlan(planId))
        val detail = requireNotNull(store.history.getSequenceDetail(sequenceExecutionId))
        assertEquals(PlanTarget.ExactDay(overlapEnd, zone), plan.target)
        assertEquals(PlanEntryStatus.FULFILLED, plan.status)
        assertEquals(sequenceExecutionId, plan.fulfilledSequenceExecutionId)
        assertEquals(plan.sequenceSnapshotId, detail.root.snapshotId)
        assertEquals(plan.id, detail.root.planEntryId)
        assertEquals(plan.fulfilledAt, detail.root.completedAt)
        assertEquals(listOf(1, 2), detail.occurrences.map { it.repeatIteration })
        val performed = detail.occurrences.first()
        assertNotNull(performed.sourceSequenceSnapshotNodeId)
        assertNotNull(performed.repeatSourceSnapshotNodeId)
        assertFalse(performed.isRuntimeAdded)
        assertEquals(store.children(sequenceExecutionId).single().id, requireNotNull(performed.child).executionId)
        assertNull(detail.occurrences.last().child)
    }

    companion object {
        val date: LocalDate = LocalDate.parse("2024-11-03")
        val period = StatisticsPeriod.Custom(date, date)
        val zone: ZoneId = ZoneId.of("America/New_York")
        private val overlapStart = Instant.parse("2024-11-03T05:30:00Z")
        private val overlapEnd = Instant.parse("2024-11-03T06:30:00Z")
        private val earlyOffset = ZoneOffset.ofHours(-4)
        private val lateOffset = ZoneOffset.ofHours(-5)
        private val schedule = PlanSchedule.ExactDay(LocalDateTime.parse("2024-11-03T01:30:00"), zone, lateOffset)
        private const val COMMENT = "Заметка / training"

        fun seed(store: RuntimeRecoveryStore): ConfigurationRecoveryFacts {
            val created = overlapStart.minus(10, MINUTES)
            val draft = StatisticsJourneyHistory.activityDraft().copy(shortComment = COMMENT)
            val activity = store.authoring.createActivityTemplate(draft, createdAt = created)
            val sequence = createSequence(store, activity, created)
            val plan = store.plans.createSequencePlanFromTemplate(sequence.id, schedule, created)
            val sequenceExecutionId = performPlan(store, plan.id)
            assertNull(store.live.getActiveSession())
            val overlap = addOverlap(store, activity)
            val zero = addZero(store, activity)
            val noLive =
                store.authoring.createActivityTemplate(
                    draft.copy(name = "Archive me / архив", timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING),
                    createdAt = created,
                )
            val noLiveExecution =
                store.library.completeNoLiveActivityFromTemplate(
                    noLive.id,
                    overlapEnd.plus(10, MINUTES),
                    overlapEnd.plus(10, MINUTES),
                    zone,
                )
            store.library.archiveActivityTemplate(noLive.id, overlapEnd.plus(11, MINUTES))
            val id = LibraryTemplateId.Activity(activity.id)
            val folder = store.library.createFolder(FolderId("training-folder"), "Training / тренировка", null, created)
            store.library.moveActivityTemplateToFolder(activity.id, folder.id, created)
            store.library.pin(id)
            store.library.createTagAndAssign(TagId("training-tag"), "Training / тренировка", id, created)
            val now = Instant.now().truncatedTo(SECONDS)
            val paused =
                store.library.startActivityFromTemplate(
                    activity.id,
                    now.minus(10, SECONDS),
                    now.minus(10, SECONDS),
                    zone,
                )
            store.live.pauseActiveActivity(ActivityExecutionPauseId("configuration-pause"), now.minus(5, SECONDS))
            return ConfigurationRecoveryFacts(
                activity,
                LibraryTemplateId.Activity(noLive.id),
                sequence,
                plan.id,
                overlap,
                zero,
                noLiveExecution.id,
                sequenceExecutionId,
                paused.id,
            )
        }

        private fun createSequence(
            store: RuntimeRecoveryStore,
            activity: ActivityTemplate,
            created: Instant,
        ): SequenceTemplate {
            val step =
                ActivityStepDraft(DraftIdentity.New("training-step"), 0, StepActivityDraft.FromTemplate(activity.id))
            val repeat = SequenceRepeatBlockDraft(DraftIdentity.New("training-repeat"), 0, 2, listOf(step))
            return store.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Training Repeat",
                    COMMENT,
                    settings = SequenceTemplateSettings(autoAdvance = false),
                    nodes = listOf(SequenceNodeDraft.Repeat(repeat)),
                ),
                createdAt = created,
            )
        }

        private fun performPlan(
            store: RuntimeRecoveryStore,
            planId: PlanEntryId,
        ): SequenceExecutionId {
            val start = schedule.toTarget().scheduledAt
            val running =
                store.live.startSequenceFromPlan(
                    store.planReads.getFocusedAction(planId).identity,
                    start,
                    start,
                    zone,
                )
            val id = running.execution.id
            val finish = start.plus(5, MINUTES)
            val waiting =
                store.live.completeCurrentSequenceStep(
                    id,
                    requireNotNull(running.execution.currentOccurrenceId),
                    at = finish,
                )
            assertNull(waiting.currentChild)
            assertNull(waiting.execution.currentOccurrenceId)
            store.live.endSequenceEarly(id, finish)
            return id
        }

        private fun addOverlap(
            store: RuntimeRecoveryStore,
            activity: ActivityTemplate,
        ): ActivityExecutionId {
            assertEquals(earlyOffset, overlapStart.atZone(zone).offset)
            val category = activity.fields.single { it.type == CustomFieldType.CATEGORY }
            val option = ActivityEntryOptionReference.Template(category.categoryOptions.last().id)
            return store.activityCommands
                .addManualTimed(
                    ActivityEntrySource.Template(activity.id),
                    overlapStart,
                    overlapEnd,
                    overlapEnd,
                    zone,
                    listOf(
                        ActivityEntryValueOverride(
                            ActivityEntryFieldReference.Template(category.id),
                            ActivityEntryValue.Category(option),
                        ),
                    ),
                    activity.revision,
                ).id
        }

        private fun addZero(
            store: RuntimeRecoveryStore,
            activity: ActivityTemplate,
        ): ActivityExecutionId {
            val instant = Instant.parse("2024-11-04T02:00:00Z")
            val number = activity.fields.single { it.isMainValue }
            return store.activityCommands
                .addManualTimed(
                    ActivityEntrySource.Template(activity.id),
                    instant,
                    instant,
                    instant,
                    zone,
                    listOf(
                        ActivityEntryValueOverride(
                            ActivityEntryFieldReference.Template(number.id),
                            ActivityEntryValue.Number(0),
                        ),
                    ),
                    activity.revision,
                ).id
        }
    }
}
