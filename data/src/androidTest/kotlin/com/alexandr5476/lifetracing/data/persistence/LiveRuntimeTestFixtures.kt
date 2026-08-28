package com.alexandr5476.lifetracing.data.persistence

internal class LiveRuntimeTestFixtures(
    private val database: LifeTracingDatabase,
) {
    fun seedSeries() {
        database.statisticsSeriesDao().insert(
            StatisticsSeriesEntity("activity-series", "ACTIVITY", "Activity", 0, null),
        )
        database.statisticsSeriesDao().insert(
            StatisticsSeriesEntity("sequence-series", "SEQUENCE", "Sequence", 0, null),
        )
    }

    fun activity(
        id: String,
        mode: String,
        targetMs: Long? = null,
        zeroBehavior: String = "FINISH",
        seriesId: String? = "activity-series",
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    id,
                    id,
                    null,
                    mode,
                    targetMs,
                    null,
                    null,
                    seriesId,
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity(id, timerZeroBehavior = zeroBehavior),
            ),
        )
    }

    fun sequence(
        id: String,
        activityIds: List<String>,
        autoAdvance: Boolean = true,
        countdownMs: Long = 0,
        noLiveAccounting: String = "ACTIVE",
        timerZeroOverrides: Map<Int, String> = emptyMap(),
    ) {
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(id, id, null, null, null, "sequence-series", 0),
                SequenceSnapshotSettingsEntity(
                    id,
                    autoAdvance,
                    5_000,
                    countdownMs,
                    true,
                    true,
                    false,
                    true,
                    true,
                    noLiveAccounting,
                ),
                nodes =
                    activityIds.mapIndexed { index, activityId ->
                        SequenceSnapshotNodeEntity("$id-step-$index", id, "STEP", null, index, activityId, null)
                    },
                stepOverrides =
                    timerZeroOverrides.map { (index, behavior) ->
                        SequenceSnapshotStepOverrideEntity("$id-step-$index", null, behavior, null, null, null)
                    },
            ),
        )
    }

    fun repeatSequence(
        id: String,
        activityId: String,
        repeatCount: Int = 2,
        autoAdvance: Boolean = false,
        timerZeroBehavior: String? = null,
    ) {
        val repeatId = "$id-repeat"
        val stepId = "$id-step"
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(id, id, null, null, null, "sequence-series", 0),
                SequenceSnapshotSettingsEntity(id, autoAdvance, 5_000, 0, true, true, false, true, true, "ACTIVE"),
                nodes =
                    listOf(
                        SequenceSnapshotNodeEntity(repeatId, id, "REPEAT", null, 0, null, repeatCount),
                        SequenceSnapshotNodeEntity(stepId, id, "STEP", repeatId, 0, activityId, null),
                    ),
                stepOverrides =
                    timerZeroBehavior?.let {
                        listOf(SequenceSnapshotStepOverrideEntity(stepId, null, it, null, null, null))
                    } ?: emptyList(),
            ),
        )
    }

    fun standaloneExecution(
        id: String = "activity-execution",
        snapshotId: String = "stopwatch",
        status: String = "RUNNING",
        pauseId: String = "pause",
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
                    "activity-series",
                    status,
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
                pauses =
                    if (status == "PAUSED") {
                        listOf(ActivityExecutionPauseEntity(pauseId, id, 0, null))
                    } else {
                        emptyList()
                    },
            ),
        )
    }

    fun sequenceExecution(
        id: String = "sequence-execution",
        snapshotId: String = "sequence",
    ) {
        database.sequenceExecutionDao().insertAggregate(
            SequenceExecutionAggregateEntity(
                SequenceExecutionEntity(
                    id,
                    snapshotId,
                    null,
                    "sequence-series",
                    "RUNNING",
                    0,
                    null,
                    null,
                    null,
                    null,
                    "UTC",
                    0,
                    "1970-01-01",
                    null,
                    0,
                    0,
                ),
                occurrences =
                    listOf(
                        SequenceOccurrenceEntity(
                            "$id-occurrence",
                            id,
                            "$snapshotId-step-0",
                            "stopwatch",
                            0,
                            null,
                            null,
                            "NOT_STARTED",
                            null,
                            null,
                            null,
                        ),
                    ),
            ),
        )
    }
}
