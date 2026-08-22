package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class PlanModelsTest {
    private val createdAt = Instant.parse("2026-08-20T09:00:00Z")

    @Test
    fun `validator accepts every target and retained revision after source purge`() {
        val targets =
            listOf(
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
                PlanTarget.ExactDay(createdAt.plusSeconds(60), ZoneId.of("Europe/Moscow")),
                PlanTarget.Week(LocalDate.parse("2026-08-17")),
                PlanTarget.Month(YearMonth.parse("2026-08")),
            )

        targets.forEach { target ->
            assertDoesNotThrow {
                PlanEntryValidator.requireValid(activityPlan(target, sourceId = null, sourceRevision = 3))
            }
        }
    }

    @Test
    fun `validator rejects cross-kind and non-Monday shapes`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanEntryValidator.requireValid(
                activityPlan().copy(sequenceSnapshotId = SequenceSnapshotId("sequence")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanEntryValidator.requireValid(activityPlan(PlanTarget.Week(LocalDate.parse("2026-08-18"))))
        }
    }

    @Test
    fun `cancel restore reschedule and fulfill preserve lifecycle rules`() {
        val planned = activityPlan()
        val cancelled = PlanEntryTransitions.cancel(planned, createdAt.plusSeconds(1))
        assertEquals(PlanEntryStatus.CANCELLED, cancelled.status)
        val restored = PlanEntryTransitions.restore(cancelled, createdAt.plusSeconds(2))
        val rescheduled =
            PlanEntryTransitions.reschedule(
                restored,
                PlanTarget.Month(YearMonth.parse("2026-09")),
                createdAt.plusSeconds(3),
            )
        val fulfilled =
            PlanEntryTransitions.fulfill(
                rescheduled,
                ActivityExecutionId("execution"),
                null,
                createdAt.plusSeconds(4),
            )

        assertEquals(PlanTarget.Month(YearMonth.parse("2026-09")), fulfilled.target)
        assertEquals(PlanEntryStatus.FULFILLED, fulfilled.status)
        assertThrows(IllegalArgumentException::class.java) {
            PlanEntryTransitions.cancel(fulfilled, createdAt.plusSeconds(5))
        }
    }

    @Test
    fun `overdue follows exact floating week and month boundaries`() {
        val utc = ZoneId.of("UTC")
        val now = Instant.parse("2026-08-21T00:00:00Z")

        assertFalse(PlanOverdueCalculator.isOverdue(activityPlan(PlanTarget.ExactDay(now, null)), now, utc))
        assertTrue(
            PlanOverdueCalculator.isOverdue(activityPlan(PlanTarget.ExactDay(now.minusMillis(1), null)), now, utc),
        )
        assertTrue(
            PlanOverdueCalculator.isOverdue(
                activityPlan(PlanTarget.FloatingDay(LocalDate.parse("2026-08-20"))),
                now,
                utc,
            ),
        )
        assertTrue(
            PlanOverdueCalculator.isOverdue(
                activityPlan(PlanTarget.Week(LocalDate.parse("2026-08-10"))),
                Instant.parse("2026-08-17T00:00:00Z"),
                utc,
            ),
        )
        assertTrue(
            PlanOverdueCalculator.isOverdue(
                activityPlan(PlanTarget.Month(YearMonth.parse("2026-07"))),
                now,
                utc,
            ),
        )
        assertFalse(
            PlanOverdueCalculator.isOverdue(
                PlanEntryTransitions.cancel(activityPlan(), createdAt.plusSeconds(1)),
                now,
                utc,
            ),
        )
    }

    @Test
    fun `exact projection can move dates while floating identity does not`() {
        val instant = Instant.parse("2026-08-20T23:30:00Z")
        assertEquals(LocalDate.parse("2026-08-20"), instant.atZone(ZoneId.of("UTC")).toLocalDate())
        assertEquals(LocalDate.parse("2026-08-21"), instant.atZone(ZoneId.of("Europe/Moscow")).toLocalDate())
        assertEquals(
            LocalDate.parse("2026-08-20"),
            (activityPlan(PlanTarget.FloatingDay(LocalDate.parse("2026-08-20"))).target as PlanTarget.FloatingDay).date,
        )
    }

    @Test
    fun `source state uses revision and lifecycle only`() {
        assertEquals(PlanSourceState.CURRENT, PlanSourceStateResolver.resolve(2, 2, false))
        assertEquals(PlanSourceState.CHANGED, PlanSourceStateResolver.resolve(2, 3, false))
        assertEquals(PlanSourceState.ARCHIVED, PlanSourceStateResolver.resolve(2, 2, true))
        assertEquals(PlanSourceState.UNAVAILABLE, PlanSourceStateResolver.resolve(2, null, false))
    }

    private fun activityPlan(
        target: PlanTarget = PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
        sourceId: ActivityTemplateId? = ActivityTemplateId("template"),
        sourceRevision: Long? = 2,
    ) = PlanEntry(
        id = PlanEntryId("plan"),
        kind = PlanTrackableKind.ACTIVITY,
        sourceActivityTemplateId = sourceId,
        sourceSequenceTemplateId = null,
        sourceRevision = sourceRevision,
        activitySnapshotId = ActivitySnapshotId("snapshot"),
        sequenceSnapshotId = null,
        target = target,
        status = PlanEntryStatus.PLANNED,
        fulfilledActivityExecutionId = null,
        fulfilledSequenceExecutionId = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        cancelledAt = null,
        fulfilledAt = null,
    )
}
