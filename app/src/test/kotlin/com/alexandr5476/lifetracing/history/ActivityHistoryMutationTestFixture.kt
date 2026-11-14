package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

abstract class ActivityHistoryMutationTestFixture {
    protected fun fixture(
        initial: ActivityHistoryDetail,
        read: suspend () -> ActivityHistoryDetail? = { initial },
        correct: suspend (
            com.alexandr5476.lifetracing.domain.ActivityExecutionId,
            ActivityHistoryCorrection,
            Instant,
        ) -> Unit = { _, _, _ -> },
        delete: suspend (
            com.alexandr5476.lifetracing.domain.ActivityExecutionId,
            Instant,
            Instant,
        ) -> Unit = { _, _, _ -> },
        overlaps: suspend (Instant, Instant, ActivityExecutionId) -> Boolean = { _, _, _ -> false },
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var fixture: Fixture
        val controller =
            ActivityHistoryMutationController(
                scope,
                initial.root.executionId,
                { read() },
                { id, correction, at ->
                    fixture.correctAttempts++
                    fixture.corrections += correction
                    correct(id, correction, at)
                },
                { id, expected, at ->
                    fixture.deleteAttempts++
                    delete(id, expected, at)
                },
                { correctionCommandTime },
                { startedAt, completedAt, excludingExecutionId ->
                    fixture.overlapCalls += OverlapCall(startedAt, completedAt, excludingExecutionId)
                    overlaps(startedAt, completedAt, excludingExecutionId)
                },
            )
        return Fixture(scope, controller).also { fixture = it }
    }

    protected data class Fixture(
        val scope: CoroutineScope,
        val controller: ActivityHistoryMutationController,
        val corrections: MutableList<ActivityHistoryCorrection> = mutableListOf(),
        var correctAttempts: Int = 0,
        var deleteAttempts: Int = 0,
        val overlapCalls: MutableList<OverlapCall> = mutableListOf(),
    ) {
        suspend fun awaitLoaded() =
            withTimeout(2_000) { controller.state.first { it.load is HistoryDetailLoad.Content } }

        suspend fun awaitRefresh() = withTimeout(2_000) { controller.state.first { it.refreshGeneration > 0 } }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    protected data class OverlapCall(
        val startedAt: Instant,
        val completedAt: Instant,
        val excludingExecutionId: ActivityExecutionId,
    )

    protected val berlin = ZoneId.of("Europe/Berlin")
    protected val token = Instant.parse("2026-01-15T12:00:00Z")
    protected val correctionCommandTime = Instant.parse("2026-12-01T12:00:00Z")
    protected val numberId = ActivitySnapshotFieldId("number")
    protected val categoryId = ActivitySnapshotFieldId("category")
    protected val textId = ActivitySnapshotFieldId("text")
    protected val optionA = ActivitySnapshotCategoryOptionId("a")
    protected val optionB = ActivitySnapshotCategoryOptionId("b")

    protected fun detail(
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        startedAt: Instant? = Instant.parse("2026-01-15T09:00:00Z"),
        completedAt: Instant = Instant.parse("2026-01-15T10:00:00Z"),
        planEntryId: PlanEntryId? = null,
    ) = ActivityHistoryDetail(
        CompletedActivityHistoryRoot(
            com.alexandr5476.lifetracing.domain
                .ActivityExecutionId("execution"),
            ActivitySnapshotId("snapshot"),
            LocalDate.parse("2026-01-15"),
            completedAt,
            startedAt,
            startedAt?.let { Duration.between(it, completedAt) },
            planEntryId,
            "Activity",
            "comment",
            mode,
            null,
            berlin,
            null,
        ),
        token,
        berlin,
        ActivityTemplateSettings(),
        listOf(
            ActivityHistoryField(
                numberId,
                "Number",
                CustomFieldType.NUMBER,
                null,
                0,
                false,
                ActivityHistoryConfiguredValue.Number(5_000),
                ActivityHistoryActualValue.Missing,
                emptyList(),
            ),
            ActivityHistoryField(
                categoryId,
                "Category",
                CustomFieldType.CATEGORY,
                null,
                null,
                false,
                ActivityHistoryConfiguredValue.Category(optionA),
                ActivityHistoryActualValue.Category(optionA, "A"),
                listOf(
                    ActivityHistoryCategoryOption(optionA, "A"),
                    ActivityHistoryCategoryOption(optionB, "B"),
                ),
            ),
            ActivityHistoryField(
                textId,
                "Text",
                CustomFieldType.TEXT,
                null,
                null,
                false,
                ActivityHistoryConfiguredValue.Text("default"),
                ActivityHistoryActualValue.Missing,
                emptyList(),
            ),
        ),
    )
}
