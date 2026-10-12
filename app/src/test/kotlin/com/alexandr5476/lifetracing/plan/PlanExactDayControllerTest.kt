package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LocalDateTimeResolution
import com.alexandr5476.lifetracing.domain.LocalDateTimeResolutionException
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.UnsupportedPlanTimePrecisionException
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class PlanExactDayControllerTest {
    @Test
    fun finerFractionsNeverDispatchCreationOrReschedulingAndCorrectionKeepsMilliseconds() =
        runBlocking {
            for (sequence in listOf(false, true)) {
                for (existing in listOf(false, true)) {
                    val fixture = Fixture(this)
                    try {
                        if (existing) {
                            fixture.controller.dispatch(PlanAction.Reschedule(existingRow()))
                            fixture.controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-26"))
                        } else {
                            fixture.newExact(sequence, "2026-10-26", "10:00")
                        }
                        for (fraction in listOf("123456789", "123001", "000000001")) {
                            fixture.controller.dispatch(PlanAction.ChangeScheduleTime("10:00:00.$fraction"))
                            fixture.controller.dispatch(PlanAction.SubmitForm)
                            assertEquals(PlanMessage.UNSUPPORTED_TIME_PRECISION, fixture.form.error)
                            assertTrue(fixture.mutations.isEmpty())
                            assertFalse(fixture.controller.state.value.isMutating)
                        }
                        fixture.controller.dispatch(PlanAction.ChangeScheduleTime("10:00:00.123"))
                        fixture.submit()
                        assertEquals(
                            Instant.parse("2026-10-26T09:00:00.123Z"),
                            (fixture.schedule.toTarget() as PlanTarget.ExactDay).scheduledAt,
                        )
                    } finally {
                        fixture.controller.close()
                    }
                }
            }
        }

    @Test
    fun commandPrecisionFailureKeepsTheFormAndAllowsCorrectionWithoutCanonicalRecovery() =
        runBlocking {
            var fail = true
            val fixture = Fixture(this) { if (fail) throw UnsupportedPlanTimePrecisionException() }
            try {
                fixture.ready()
                fixture.newExact(false, "2026-10-26", "10:00:00.123")
                val reads = fixture.readCount
                fixture.submit()
                assertEquals(PlanMessage.UNSUPPORTED_TIME_PRECISION, fixture.form.error)
                assertEquals("10:00:00.123", fixture.form.time)
                assertEquals(reads, fixture.readCount)
                assertFalse(fixture.controller.state.value.isMutating)
                fail = false
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("10:00:00.124"))
                fixture.submit()
                assertNull(fixture.controller.state.value.form)
                assertEquals(2, fixture.mutations.size)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun gapInputDoesNotDispatchAndLeavesAnEditableForm() =
        runBlocking {
            for (sequence in listOf(false, true)) {
                val fixture = Fixture(this)
                try {
                    fixture.newExact(sequence, "2026-03-29", "02:30")
                    fixture.controller.dispatch(PlanAction.SubmitForm)
                    assertEquals(PlanMessage.NONEXISTENT_TIME, fixture.form.error)
                    assertTrue(fixture.mutations.isEmpty())
                    assertFalse(fixture.controller.state.value.isMutating)
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime("03:30"))
                    fixture.submit()
                    assertEquals(
                        Instant.parse("2026-03-29T01:30:00Z"),
                        fixture.schedule.toTarget().let { (it as PlanTarget.ExactDay).scheduledAt },
                    )
                } finally {
                    fixture.controller.close()
                }
            }
        }

    @Test
    fun malformedDateTimeAndUnresolvedOverlapNeverDispatch() =
        runBlocking {
            val cases =
                listOf(
                    Triple("not-date", "02:30", PlanMessage.INVALID_DATE),
                    Triple("2026-10-25", "25:00", PlanMessage.INVALID_TIME),
                    Triple("2026-10-25", "02:30", PlanMessage.AMBIGUOUS_TIME),
                )
            for ((date, time, error) in cases) {
                val fixture = Fixture(this)
                try {
                    fixture.newExact(false, date, time)
                    fixture.controller.dispatch(PlanAction.SubmitForm)
                    assertEquals(error, fixture.form.error)
                    assertTrue(fixture.mutations.isEmpty())
                    assertFalse(fixture.controller.state.value.isMutating)
                } finally {
                    fixture.controller.close()
                }
            }
        }

    @Test
    fun bothOverlapChoicesForwardTheExactTupleForBothCreationKinds() =
        runBlocking {
            for (sequence in listOf(false, true)) {
                for ((offset, instant) in listOf(
                    "+02:00" to "2026-10-25T00:30:00Z",
                    "+01:00" to "2026-10-25T01:30:00Z",
                )) {
                    val fixture = Fixture(this)
                    try {
                        fixture.newExact(sequence)
                        assertNull(fixture.form.selectedOffset)
                        assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), fixture.form.offsetChoices)
                        fixture.choose(ZoneOffset.of(offset))
                        fixture.submit()
                        assertEquals(sequence, fixture.mutations.single() is PlanMutation.CreateSequence)
                        assertEquals(
                            PlanSchedule.ExactDay(
                                LocalDateTime.parse("2026-10-25T02:30"),
                                fixture.zone,
                                ZoneOffset.of(offset),
                            ),
                            fixture.schedule,
                        )
                        assertEquals(
                            Instant.parse(instant),
                            (fixture.schedule.toTarget() as PlanTarget.ExactDay).scheduledAt,
                        )
                    } finally {
                        fixture.controller.close()
                    }
                }
            }
        }

    @Test
    fun invalidAndObsoleteChoicesCannotAuthorizeSubmission() =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.newExact(false)
                fixture.choose(ZoneOffset.ofHours(3))
                fixture.controller.dispatch(PlanAction.SubmitForm)
                assertEquals(PlanMessage.INVALID_OFFSET, fixture.form.error)
                val obsolete = fixture.selection(ZoneOffset.ofHours(2))
                fixture.controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-26"))
                fixture.controller.dispatch(obsolete)
                assertEquals(PlanMessage.INVALID_OFFSET, fixture.form.error)
                assertNull(fixture.form.selectedOffset)
                fixture.choose(ZoneOffset.ofHours(2))
                fixture.controller.dispatch(PlanAction.SubmitForm)
                assertEquals(PlanMessage.INVALID_OFFSET, fixture.form.error)
                assertTrue(fixture.mutations.isEmpty())
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("10:00"))
                fixture.submit()
                assertNull(fixture.schedule.selectedOffset)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun eachActualSchedulingEditInvalidatesSelectionIncludingRestoredText() =
        runBlocking {
            val edits =
                listOf(
                    PlanAction.ChangeScheduleKind(PlanScheduleKind.FLOATING_DAY),
                    PlanAction.ChangeScheduleDate("2026-10-26"),
                    PlanAction.ChangeScheduleTime("02:31"),
                )
            for (edit in edits) {
                val fixture = Fixture(this)
                try {
                    fixture.newExact(false)
                    fixture.choose(ZoneOffset.ofHours(1))
                    fixture.controller.dispatch(edit)
                    assertNull(fixture.form.selectedOffset)
                    assertTrue(fixture.form.scheduleEdited)
                    fixture.controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-25"))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:30"))
                    assertEquals(PlanMessage.AMBIGUOUS_TIME, fixture.form.error)
                    assertNull(fixture.form.selectedOffset)
                } finally {
                    fixture.controller.close()
                }
            }
        }

    @Test
    fun unchangedHighPrecisionOverlapTargetIsPreservedAcrossZoneChangesAndDismissalIsReadOnly() =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.controller.dispatch(PlanAction.Reschedule(existingRow()))
                val identity = fixture.form.identity
                assertTrue(fixture.form.preservesExistingSchedule)
                assertTrue(fixture.form.offsetChoices.isEmpty())
                fixture.zone = ZoneId.of("Pacific/Auckland")
                fixture.signal.value++
                fixture.submit()
                assertEquals(identity, (fixture.mutations.single() as PlanMutation.PreserveSchedule).identity)
                fixture.controller.dispatch(PlanAction.Reschedule(existingRow()))
                fixture.controller.dispatch(PlanAction.DismissForm)
                assertEquals(1, fixture.mutations.size)
                assertNull(fixture.controller.state.value.form)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun editedAndRestoredExistingTextRequiresSelectionAndEmitsReschedule() =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.controller.dispatch(PlanAction.Reschedule(existingRow()))
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31"))
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:30"))
                assertFalse(fixture.form.preservesExistingSchedule)
                fixture.controller.dispatch(PlanAction.SubmitForm)
                assertEquals(PlanMessage.AMBIGUOUS_TIME, fixture.form.error)
                assertTrue(fixture.mutations.isEmpty())
                fixture.choose(ZoneOffset.ofHours(1))
                fixture.submit()
                assertTrue(fixture.mutations.single() is PlanMutation.Reschedule)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun zoneMismatchRequiresExplicitReviewWithOrWithoutInvalidationAndCannotRestoreOffsetAuthority() =
        runBlocking {
            for (signal in listOf(false, true)) {
                for (existing in listOf(false, true)) {
                    val fixture = Fixture(this)
                    try {
                        if (existing) {
                            fixture.controller.dispatch(PlanAction.Reschedule(existingRow()))
                            fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31"))
                        } else {
                            fixture.newExact(false)
                        }
                        fixture.choose(ZoneOffset.ofHours(1))
                        fixture.zone = ZoneId.of("Pacific/Auckland")
                        if (signal) {
                            fixture.signal.value++
                            withTimeout(
                                2_000,
                            ) { fixture.controller.state.first { it.form?.zoneReviewRequired == true } }
                        }
                        fixture.controller.dispatch(PlanAction.SubmitForm)
                        assertEquals(PlanMessage.ZONE_CHANGED, fixture.form.error)
                        assertNull(fixture.form.selectedOffset)
                        assertTrue(fixture.mutations.isEmpty())
                        fixture.zone = ZoneId.of("Europe/Berlin")
                        fixture.controller.dispatch(PlanAction.SubmitForm)
                        assertTrue(fixture.form.zoneReviewRequired)
                        fixture.zone = ZoneId.of("Pacific/Auckland")
                        fixture.controller.dispatch(PlanAction.RevalidateScheduleZone)
                        assertEquals(fixture.zone, fixture.form.schedulingZoneId)
                        assertNull(fixture.form.selectedOffset)
                        assertNull(fixture.form.error)
                        fixture.submit()
                        assertEquals(fixture.zone, fixture.schedule.currentZoneId)
                    } finally {
                        fixture.controller.close()
                    }
                }
            }
        }

    @Test
    fun typedRepositoryTemporalFailuresKeepFormReleaseLockAndAllowCorrectionWithoutRecovery() =
        runBlocking {
            for ((failure, message) in listOf(
                LocalDateTimeResolution.Nonexistent to PlanMessage.NONEXISTENT_TIME,
                LocalDateTimeResolution.Ambiguous(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1))) to
                    PlanMessage.AMBIGUOUS_TIME,
                LocalDateTimeResolution.InvalidOffset(ZoneOffset.ofHours(3), listOf(ZoneOffset.ofHours(1))) to
                    PlanMessage.INVALID_OFFSET,
            )) {
                var fail = true
                val fixture = Fixture(this) { if (fail) throw LocalDateTimeResolutionException(failure) }
                try {
                    fixture.ready()
                    fixture.newExact(false, "2026-10-26", "10:00")
                    val readCount = fixture.readCount
                    fixture.submit()
                    assertEquals(message, fixture.form.error)
                    assertEquals("10:00", fixture.form.time)
                    assertEquals(readCount, fixture.readCount)
                    assertFalse(fixture.controller.state.value.isMutating)
                    fail = false
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime("11:00"))
                    fixture.submit()
                    assertNull(fixture.controller.state.value.form)
                    assertEquals(2, fixture.mutations.size)
                } finally {
                    fixture.controller.close()
                }
            }
        }

    @Test
    fun zoneIsCheckedAgainWhenAnAdmittedCommandStartsRunning() =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.ready()
                fixture.newExact(false, "2026-10-26", "10:00")
                fixture.controller.dispatch(PlanAction.SubmitForm)
                assertTrue(fixture.controller.state.value.isMutating)
                fixture.zone = ZoneId.of("Pacific/Auckland")
                withTimeout(2_000) { fixture.controller.state.first { !it.isMutating } }
                assertEquals(PlanMessage.ZONE_CHANGED, fixture.form.error)
                assertTrue(fixture.mutations.isEmpty())
                fixture.controller.dispatch(PlanAction.RevalidateScheduleZone)
                fixture.submit()
                assertEquals(fixture.zone, fixture.schedule.currentZoneId)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun unexpectedWriterFailureClosesTheFormAndPerformsCanonicalRecoveryWithoutReplay() =
        runBlocking {
            val fixture = Fixture(this) { error("Storage failed") }
            try {
                fixture.ready()
                fixture.newExact(false, "2026-10-26", "10:00")
                val reads = fixture.readCount
                fixture.submit()
                assertEquals(PlanMessage.PERSISTENCE_FAILED, fixture.controller.state.value.mutationFailure)
                assertNull(fixture.controller.state.value.form)
                assertTrue(fixture.readCount > reads)
                assertEquals(1, fixture.mutations.size)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun anOldSelectionEventCannotAuthorizeAnEditedAndRestoredTuple() =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.newExact(false)
                val oldChoice = fixture.selection(ZoneOffset.ofHours(1))
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31"))
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:30"))
                fixture.controller.dispatch(oldChoice)
                fixture.controller.dispatch(PlanAction.SubmitForm)
                assertEquals(PlanMessage.INVALID_OFFSET, fixture.form.error)
                assertNull(fixture.form.selectedOffset)
                assertTrue(fixture.mutations.isEmpty())
                fixture.choose(ZoneOffset.ofHours(1))
                fixture.submit()
                assertEquals(1, fixture.mutations.size)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun identicalInputCallbacksDoNotTurnAnUntouchedTargetIntoNewInput() =
        runBlocking {
            val fixture = Fixture(this)
            try {
                fixture.controller.dispatch(PlanAction.Reschedule(existingRow()))
                fixture.controller.dispatch(PlanAction.ChangeScheduleDate(fixture.form.date))
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime(fixture.form.time))
                fixture.controller.dispatch(PlanAction.ChangeScheduleKind(fixture.form.kind))
                assertTrue(fixture.form.preservesExistingSchedule)
                fixture.submit()
                assertTrue(fixture.mutations.single() is PlanMutation.PreserveSchedule)
            } finally {
                fixture.controller.close()
            }
        }

    private class Fixture(
        scope: CoroutineScope,
        commit: (PlanMutation) -> Unit = {},
    ) {
        var zone: ZoneId = ZoneId.of("Europe/Berlin")
        val signal = MutableStateFlow(0L)
        val mutations = mutableListOf<PlanMutation>()
        var readCount = 0
        val controller =
            PlanController(
                scope,
                {
                    readCount++
                    WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList())
                },
                { _, _, _ -> emptyList() },
                { CancelledPlanPage(emptyList(), false) },
                {
                    mutations += it
                    commit(it)
                },
                { Instant.parse("2026-10-24T10:00:00Z") },
                { zone },
                signal,
                LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
        val form get() = requireNotNull(controller.state.value.form)
        val schedule get() =
            when (val mutation = mutations.last()) {
                is PlanMutation.CreateActivity -> mutation.schedule
                is PlanMutation.CreateSequence -> mutation.schedule
                is PlanMutation.Reschedule -> mutation.schedule
                else -> error("Expected new temporal input")
            } as PlanSchedule.ExactDay

        fun newExact(
            sequence: Boolean,
            date: String = "2026-10-25",
            time: String = "02:30",
        ) {
            val id =
                if (sequence) {
                    LibraryTemplateId.Sequence(
                        SequenceTemplateId("sequence"),
                    )
                } else {
                    LibraryTemplateId.Activity(ActivityTemplateId("activity"))
                }
            controller.dispatch(PlanAction.SelectCatalogItem(ReusablePlanCatalogItem(id, "Template", null, null)))
            controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY))
            controller.dispatch(PlanAction.ChangeScheduleDate(date))
            controller.dispatch(PlanAction.ChangeScheduleTime(time))
        }

        fun selection(offset: ZoneOffset) =
            PlanAction.SelectScheduleOffset(offset, form.date, form.time, form.schedulingZoneId, form.inputRevision)

        fun choose(offset: ZoneOffset) = controller.dispatch(selection(offset))

        suspend fun ready() = withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }

        suspend fun submit() {
            controller.dispatch(PlanAction.SubmitForm)
            withTimeout(2_000) { controller.state.first { !it.isMutating } }
        }
    }

    private fun existingRow(): PlanReadRow {
        val plan =
            PlanEntry(
                PlanEntryId("plan"),
                PlanTrackableKind.ACTIVITY,
                ActivityTemplateId("activity"),
                null,
                7,
                ActivitySnapshotId("frozen"),
                null,
                PlanTarget.ExactDay(Instant.parse("2026-10-25T01:30:27.123Z"), ZoneId.of("Europe/Berlin")),
                PlanEntryStatus.PLANNED,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null,
            )
        return PlanReadRow(
            plan,
            LocalDate.parse("2026-10-25"),
            null,
            "Frozen",
            null,
            PlanSourceState.CURRENT,
            false,
            false,
            null,
        )
    }
}
