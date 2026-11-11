package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanTarget
import kotlinx.coroutines.runBlocking
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

class PlanScheduleOwnershipTest {
    @Test
    fun invalidWeekAnchorRetainsLiteralInputAndRejectsSaveUntilCorrection() =
        runBlocking {
            val fixture = PlanScheduleControllerFixture(this)
            try {
                fixture.newExact(false, "2026-10-26", "10:00")
                val id = fixture.form.formId
                fixture.controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.WEEK, id))
                fixture.controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-27", id))
                fixture.controller.dispatch(PlanAction.SubmitForm(id, fixture.form.inputRevision))
                assertEquals("2026-10-27", fixture.form.date)
                assertEquals(PlanMessage.INVALID_SCHEDULE, fixture.form.error)
                assertTrue(fixture.mutations.isEmpty())
                fixture.controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-26", id))
                fixture.submit()
                val mutation = fixture.mutations.single() as PlanMutation.CreateActivity
                assertEquals(PlanSchedule.Week(LocalDate.parse("2026-10-26")), mutation.schedule)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun dismissedFormCallbacksCannotEditOrDismissReplacement() =
        runBlocking {
            val fixture = PlanScheduleControllerFixture(this)
            try {
                fixture.newExact(false)
                val obsoleteEdit = PlanAction.ChangeScheduleTime("obsolete", fixture.controller.formId)
                val obsoleteClose = PlanAction.DismissForm(fixture.controller.formId)
                fixture.controller.dispatch(PlanAction.DismissForm(fixture.controller.formId))
                fixture.newExact(true)
                val replacement = fixture.form
                fixture.controller.dispatch(obsoleteEdit)
                assertEquals(replacement, fixture.form)
                fixture.controller.dispatch(obsoleteClose)
                assertEquals(replacement, fixture.form)
                assertTrue(fixture.mutations.isEmpty())
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    @Suppress("LongMethod") // One bounded replacement matrix compares every captured event with its owner.
    fun allObsoleteFormActionsAreRejectedAcrossCreationRescheduleAndRouteReplacement() =
        runBlocking {
            for (oldCreation in listOf(false, true)) {
                for (newCreation in listOf(false, true)) {
                    for (dismiss in listOf(false, true)) {
                        val fixture = PlanScheduleControllerFixture(this)
                        try {
                            if (oldCreation) {
                                fixture.newExact(false)
                            } else {
                                fixture.controller.dispatch(PlanAction.Reschedule(existingExactPlanRow()))
                                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31", fixture.form.formId))
                            }
                            val old = fixture.form
                            val obsolete =
                                listOf(
                                    PlanAction.ChangeScheduleKind(PlanScheduleKind.WEEK, old.formId),
                                    PlanAction.ChangeScheduleDate("obsolete-date", old.formId),
                                    PlanAction.ChangeScheduleTime("obsolete-time", old.formId),
                                    PlanAction.RevalidateScheduleZone(old.formId, old.inputRevision),
                                    fixture.selection(ZoneOffset.ofHours(1)),
                                    PlanAction.SubmitForm(old.formId, old.inputRevision),
                                    PlanAction.DismissForm(old.formId),
                                )
                            if (dismiss) fixture.controller.dispatch(PlanAction.DismissForm(old.formId))
                            if (newCreation) {
                                fixture.newExact(true)
                            } else {
                                val row =
                                    existingExactPlanRow().let {
                                        it.copy(
                                            plan = it.plan.copy(id = PlanEntryId("replacement")),
                                        )
                                    }
                                fixture.controller.dispatch(PlanAction.Reschedule(row))
                                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31", fixture.form.formId))
                            }
                            fixture.choose(ZoneOffset.ofHours(1))
                            val replacement = fixture.form
                            assertTrue(old.formId != replacement.formId)
                            obsolete.forEach {
                                fixture.controller.dispatch(it)
                                assertEquals(replacement, fixture.form, it.toString())
                                assertTrue(fixture.mutations.isEmpty())
                            }
                            fixture.controller.onRouteExited()
                            obsolete.forEach(fixture.controller::dispatch)
                            fixture.controller.dispatch(PlanAction.Reschedule(existingExactPlanRow()))
                            assertNull(fixture.controller.state.value.form)
                            fixture.controller.onRouteEntered()
                            obsolete.forEach(fixture.controller::dispatch)
                            assertNull(fixture.controller.state.value.form)
                            fixture.newExact(false, "2026-10-26", "10:00")
                            obsolete.forEach(fixture.controller::dispatch)
                            fixture.submit()
                            assertEquals(1, fixture.mutations.size)
                        } finally {
                            fixture.controller.close()
                        }
                    }
                }
            }
        }

    @Test
    fun revisionScopedSaveAndReviewRejectOldDraftButRapidTextEditsKeepOwner() =
        runBlocking {
            val fixture = PlanScheduleControllerFixture(this)
            try {
                fixture.newExact(false, "2026-10-26", "10:00")
                val initial = fixture.form
                val save = PlanAction.SubmitForm(initial.formId, initial.inputRevision)
                val review = PlanAction.RevalidateScheduleZone(initial.formId, initial.inputRevision)
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("11:", initial.formId))
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("11:00:00.123000", initial.formId))
                assertEquals("11:00:00.123000", fixture.form.time)
                assertEquals(initial.formId, fixture.form.formId)
                val edited = fixture.form
                fixture.controller.dispatch(save)
                fixture.controller.dispatch(review)
                assertEquals(edited, fixture.form)
                assertTrue(fixture.mutations.isEmpty())
                fixture.submit()
                assertEquals(LocalDateTime.parse("2026-10-26T11:00:00.123"), fixture.schedule.localDateTime)
            } finally {
                fixture.controller.close()
            }
        }

    @Test
    fun everyUntouchedTargetRetainsOriginalIdentityAndEditsPermanentlyRevokePreservation() =
        runBlocking {
            val targets =
                listOf(
                    PlanTarget.FloatingDay(LocalDate.parse("2026-10-26")),
                    PlanTarget.Week(LocalDate.parse("2026-10-26")),
                    PlanTarget.ExactDay(Instant.parse("2026-10-25T01:30:27.123Z"), null),
                    PlanTarget.ExactDay(Instant.parse("2026-10-25T01:30:27.123Z"), ZoneId.of("Europe/Berlin")),
                )
            for (target in targets) {
                val fixture = PlanScheduleControllerFixture(this)
                try {
                    val row = existingExactPlanRow().let { it.copy(plan = it.plan.copy(target = target)) }
                    fixture.controller.dispatch(PlanAction.Reschedule(row))
                    val original = fixture.form
                    fixture.zone = ZoneId.of("Pacific/Auckland")
                    fixture.controller.dispatch(PlanAction.ChangeScheduleKind(original.kind, original.formId))
                    assertTrue(fixture.form.preservesExistingSchedule)
                    assertEquals(original.identity, fixture.form.identity)
                    fixture.submit()
                    assertEquals(
                        original.identity,
                        (fixture.mutations.single() as PlanMutation.PreserveSchedule).identity,
                    )
                    fixture.controller.dispatch(PlanAction.Reschedule(row))
                    val form = fixture.form
                    fixture.controller.dispatch(PlanAction.ChangeScheduleDate("invalid", form.formId))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleDate(form.date, form.formId))
                    assertTrue(fixture.form.scheduleEdited)
                    assertFalse(fixture.form.preservesExistingSchedule)
                } finally {
                    fixture.controller.close()
                }
            }
        }

    @Test
    fun wrongTupleZoneOrRevisionCannotAuthorizeAnOffsetAndCurrentSelectionRecovers() =
        runBlocking {
            val edits =
                listOf<(PlanAction.SelectScheduleOffset) -> PlanAction.SelectScheduleOffset>(
                    { it.copy(date = "2026-10-26") },
                    { it.copy(time = "02:31") },
                    { it.copy(zoneId = ZoneOffset.UTC) },
                    { it.copy(inputRevision = it.inputRevision + 1) },
                )
            for (edit in edits) {
                val fixture = PlanScheduleControllerFixture(this)
                try {
                    fixture.newExact(false)
                    val choice = fixture.selection(ZoneOffset.ofHours(1))
                    fixture.controller.dispatch(edit(choice))
                    assertNull(fixture.form.selectedOffset)
                    assertEquals(PlanMessage.INVALID_OFFSET, fixture.form.error)
                    fixture.controller.dispatch(PlanAction.SubmitForm(fixture.form.formId, fixture.form.inputRevision))
                    assertTrue(fixture.mutations.isEmpty())
                    fixture.controller.dispatch(choice)
                    assertEquals(ZoneOffset.ofHours(1), fixture.form.selectedOffset)
                    fixture.submit()
                    assertEquals(1, fixture.mutations.size)
                } finally {
                    fixture.controller.close()
                }
            }
        }
}
