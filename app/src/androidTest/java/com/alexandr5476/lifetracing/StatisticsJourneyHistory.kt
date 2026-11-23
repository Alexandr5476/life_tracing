package com.alexandr5476.lifetracing

import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.ChronoUnit.MINUTES

internal class StatisticsJourneyHistory private constructor(
    val activity: ActivityTemplate,
    val sequenceId: StatisticsSeriesId,
    private val standaloneId: ActivityExecutionId,
) {
    val activityId get() = activity.statisticsSeriesId
    val mainNumber get() = activity.fields.single { it.isMainValue }
    val category get() = activity.fields.single { it.type == CustomFieldType.CATEGORY }

    fun correctStandalone(store: RuntimeRecoveryStore) {
        val current = requireNotNull(store.activityCommands.getHistory(standaloneId))
        val moved = start.plus(1, DAYS)
        store.activityCommands.correctHistory(
            standaloneId,
            ActivityHistoryCorrection(
                current.execution.updatedAt,
                ActivityHistoryTimeCorrection.Timed(moved, moved.plus(25, MINUTES)),
                ZoneOffset.UTC,
                current.execution.values.map { if (it is NumberExecutionValue) it.copy(scaledValue = -1_250) else it },
                current.snapshot.shortComment,
            ),
            moved.plus(30, MINUTES),
        )
    }

    fun deleteStandalone(store: RuntimeRecoveryStore) {
        val current = requireNotNull(store.activityCommands.getHistory(standaloneId))
        val updated = current.execution.updatedAt
        store.activityCommands.softDeleteHistory(standaloneId, updated, updated.plusMillis(1))
    }

    companion object {
        const val SERIES_NAME = "Shared training name"
        private const val OPTION_NAME = "Same label"
        val day = StatisticsPeriod.Day(LocalDate.parse("2020-02-10"))
        val nextDay = StatisticsPeriod.Day(day.date.plusDays(1))
        private val start = Instant.parse("2020-02-10T10:00:00Z")
        private val firstOption = DraftIdentity.New("first-option")
        private val secondOption = DraftIdentity.New("second-option")

        fun seed(store: RuntimeRecoveryStore): StatisticsJourneyHistory {
            val activity = store.authoring.createActivityTemplate(activityDraft(), createdAt = start.minus(1, MINUTES))
            val sequenceId = completeSequence(store, activity)
            val category = activity.fields.single { it.type == CustomFieldType.CATEGORY }
            val second = ActivityEntryOptionReference.Template(category.categoryOptions.last().id)
            val standalone =
                store.activityCommands.addManualTimed(
                    ActivityEntrySource.Template(activity.id),
                    start.plus(20, MINUTES),
                    start.plus(40, MINUTES),
                    start.plus(41, MINUTES),
                    ZoneOffset.UTC,
                    listOf(
                        ActivityEntryValueOverride(
                            ActivityEntryFieldReference.Template(category.id),
                            ActivityEntryValue.Category(second),
                        ),
                    ),
                    activity.revision,
                )
            completeNoLive(store, activity)
            val oneOff = ActivitySnapshotDraft("One-off root", null, TimeTrackingMode.STOPWATCH, null)
            store.activityCommands.addManualTimed(
                ActivityEntrySource.OneOff(oneOff),
                start.plus(50, MINUTES),
                start.plus(55, MINUTES),
                start.plus(56, MINUTES),
                ZoneOffset.UTC,
            )
            store.library.archiveActivityTemplate(activity.id, start.plus(57, MINUTES))
            return StatisticsJourneyHistory(activity, sequenceId, standalone.id)
        }

        fun activityDraft() =
            ActivityTemplateDraft(
                SERIES_NAME,
                null,
                TimeTrackingMode.STOPWATCH,
                null,
                fields =
                    listOf(
                        ActivityFieldDraft(
                            DraftIdentity.New("distance"),
                            0,
                            "Historical distance with a long label",
                            CustomFieldType.NUMBER,
                            unit = "km",
                            displayPrecision = 2,
                            defaultNumberScaled = Long.MAX_VALUE,
                            isMainValue = true,
                        ),
                        ActivityFieldDraft(
                            DraftIdentity.New("effort"),
                            1,
                            "Effort",
                            CustomFieldType.CATEGORY,
                            defaultCategoryOption = firstOption,
                            categoryOptions =
                                listOf(
                                    ActivityCategoryOptionDraft(firstOption, 0, OPTION_NAME),
                                    ActivityCategoryOptionDraft(secondOption, 1, OPTION_NAME),
                                ),
                        ),
                        ActivityFieldDraft(DraftIdentity.New("notes"), 2, "Notes", CustomFieldType.TEXT),
                        ActivityFieldDraft(
                            DraftIdentity.New("unused"),
                            3,
                            "Unrecorded distance",
                            CustomFieldType.NUMBER,
                        ),
                    ),
            )

        private fun completeSequence(
            store: RuntimeRecoveryStore,
            activity: ActivityTemplate,
        ): StatisticsSeriesId {
            val step = ActivityStepDraft(DraftIdentity.New("step"), 0, StepActivityDraft.FromTemplate(activity.id))
            val sequence =
                store.authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        SERIES_NAME,
                        null,
                        nodes = listOf(SequenceNodeDraft.Step(step)),
                    ),
                    createdAt = start.minusMillis(1),
                )
            val running =
                store.library.startSequenceFromTemplate(sequence.id, start, start, ZoneOffset.UTC, sequence.revision)
            val id = running.execution.id
            store.live.pauseActiveSequence(id, start.plus(4, MINUTES))
            store.live.resumeActiveSequence(id, start.plus(7, MINUTES))
            store.live.completeCurrentSequenceStep(
                id,
                requireNotNull(running.execution.currentOccurrenceId),
                at = start.plus(13, MINUTES),
            )
            return sequence.statisticsSeriesId
        }

        private fun completeNoLive(
            store: RuntimeRecoveryStore,
            activity: ActivityTemplate,
        ) {
            val saved =
                store.authoring.saveActivityTemplate(
                    activity.id,
                    activity.revision,
                    activity.toAuthoringDraft().copy(timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING),
                    start.plus(42, MINUTES),
                )
            val number = activity.fields.single { it.isMainValue }
            val category = activity.fields.single { it.type == CustomFieldType.CATEGORY }
            store.library.completeNoLiveActivityFromTemplate(
                activity.id,
                start.plus(45, MINUTES),
                start.plus(45, MINUTES),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(number.id),
                        ActivityEntryValue.Number(0),
                    ),
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(category.id),
                        ActivityEntryValue.Missing,
                    ),
                ),
                saved.revision,
            )
        }
    }
}
