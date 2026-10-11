package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class PlanModelsTest {
    private val createdAt = Instant.parse("2026-08-20T09:00:00Z")

    @Test
    fun `new ExactDay input rejects DST gap and unresolved overlap`() {
        val zone = ZoneId.of("Europe/Berlin")
        listOf("2026-03-29T02:30", "2026-10-25T02:30").forEach { local ->
            assertThrows(IllegalArgumentException::class.java) {
                PlanSchedule.ExactDay(LocalDateTime.parse(local), zone).toTarget()
            }
        }
    }

    @Test
    fun `equivalent exact reschedule retains original identity precision and provenance`() {
        val instant = Instant.parse("2026-10-25T01:30:27.123Z")
        val original = activityPlan(PlanTarget.ExactDay(instant, null))
        val result =
            PlanEntryTransitions.reschedule(
                original,
                PlanTarget.ExactDay(instant, ZoneId.of("Pacific/Auckland")),
                createdAt.plusSeconds(1),
            )

        assertEquals(original, result)
        assertEquals(original.actionIdentity(), result.actionIdentity())
    }

    @Test
    fun `literal target equivalence preserves all Plan facts while target class changes do not`() {
        listOf(
            PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
            PlanTarget.Week(LocalDate.parse("2026-08-17")),
            PlanTarget.ExactDay(createdAt, ZoneId.of("UTC")),
        ).forEach { target ->
            val original = activityPlan(target)
            assertEquals(original, PlanEntryTransitions.reschedule(original, target, createdAt.plusSeconds(1)))
            assertEquals(original, PlanEntryTransitions.preserveSchedule(original, createdAt.plusSeconds(1)))
        }
        val original = activityPlan(PlanTarget.ExactDay(createdAt, null))
        val floating = PlanTarget.FloatingDay(LocalDate.parse("2026-08-20"))
        assertEquals(
            original.copy(target = floating, updatedAt = createdAt.plusSeconds(1)),
            PlanEntryTransitions.reschedule(original, floating, createdAt.plusSeconds(1)),
        )
    }

    @Test
    fun `no-op domain transitions still reject terminal states and out of order time`() {
        val original = activityPlan()
        val cancelled = PlanEntryTransitions.cancel(original, createdAt.plusSeconds(1))
        val fulfilled = PlanEntryTransitions.fulfill(original, ActivityExecutionId("execution"), null, createdAt)
        listOf(cancelled, fulfilled).forEach { terminal ->
            assertThrows(IllegalArgumentException::class.java) {
                PlanEntryTransitions.preserveSchedule(terminal, createdAt.plusSeconds(2))
            }
            assertThrows(IllegalArgumentException::class.java) {
                PlanEntryTransitions.reschedule(terminal, terminal.target, createdAt.plusSeconds(2))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanEntryTransitions.preserveSchedule(original, createdAt.minusMillis(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanEntryTransitions.reschedule(original, original.target, createdAt.minusMillis(1))
        }
    }

    @Test
    fun `production schedules resolve only supported day and Monday week targets`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val local = LocalDateTime.parse("2026-08-20T15:30:00")

        assertEquals(
            PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
            PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")).toTarget(),
        )
        assertEquals(
            PlanTarget.ExactDay(Instant.parse("2026-08-20T10:00:00Z"), zone),
            PlanSchedule.ExactDay(local, zone).toTarget(),
        )
        assertEquals(
            PlanTarget.Week(LocalDate.parse("2026-08-17")),
            PlanSchedule.Week(LocalDate.parse("2026-08-17")).toTarget(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PlanSchedule.Week(LocalDate.parse("2026-08-18"))
        }
    }

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

    @Test
    fun `focused action identity changes for every stale relevant fact`() {
        val base =
            PlanActionIdentity(
                PlanEntryId("plan"),
                PlanTrackableKind.ACTIVITY,
                ActivitySnapshotId("snapshot"),
                null,
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
                PlanEntryStatus.PLANNED,
                2,
                createdAt,
            )
        val identities =
            listOf(
                base,
                base.copy(planEntryId = PlanEntryId("other-plan")),
                base.copy(
                    kind = PlanTrackableKind.SEQUENCE,
                    activitySnapshotId = null,
                    sequenceSnapshotId = SequenceSnapshotId("snapshot"),
                ),
                base.copy(activitySnapshotId = ActivitySnapshotId("other-snapshot")),
                base.copy(target = PlanTarget.Week(LocalDate.parse("2026-08-17"))),
                base.copy(status = PlanEntryStatus.FULFILLED),
                base.copy(sourceRevision = 3),
                base.copy(updatedAt = createdAt.plusMillis(1)),
            )

        assertEquals(identities.size, identities.toSet().size)
    }

    @Test
    fun `overlap occurrence overdue follows its absolute Instant in every zone`() {
        val first = Instant.parse("2026-10-25T00:30:00Z")
        val second = Instant.parse("2026-10-25T01:30:00Z")
        val zones = listOf(ZoneId.of("Europe/Berlin"), ZoneId.of("America/New_York"))
        for (scheduled in listOf(first, second)) {
            val plan = activityPlan(PlanTarget.ExactDay(scheduled, ZoneId.of("UTC")))
            for (zone in zones) {
                assertFalse(PlanOverdueCalculator.isOverdue(plan, scheduled.minusMillis(1), zone))
                assertFalse(PlanOverdueCalculator.isOverdue(plan, scheduled, zone))
                assertTrue(PlanOverdueCalculator.isOverdue(plan, scheduled.plusMillis(1), zone))
            }
        }
        assertTrue(
            PlanOverdueCalculator.isOverdue(activityPlan(PlanTarget.ExactDay(first, null)), second, zones.first()),
        )
        assertFalse(
            PlanOverdueCalculator.isOverdue(activityPlan(PlanTarget.ExactDay(second, null)), second, zones.first()),
        )
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
