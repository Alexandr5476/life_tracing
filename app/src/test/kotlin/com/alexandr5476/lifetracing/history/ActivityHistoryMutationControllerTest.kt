package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ActivityHistoryMutationControllerTest {
    @Test
    fun correctionUsesCanonicalTokenZoneSnapshotIdsAndPreservesMissingDistinctions() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val draft = requireNotNull(fixture.controller.state.value.draft)
            assertEquals("2026-01-15T10:00:00", draft.startedText)
            assertEquals("2026-01-15T11:00:00", draft.completedText)

            fixture.controller.dispatch(ActivityHistoryMutationAction.EditNumber(NUMBER, "0"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.SetMissing(TEXT, false))
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditText(TEXT, ""))
            fixture.controller.dispatch(ActivityHistoryMutationAction.SelectCategory(CATEGORY, OPTION_B))
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()

            val correction = fixture.corrections.single()
            assertEquals(TOKEN, correction.expectedUpdatedAt)
            assertEquals(BERLIN, correction.eventZoneId)
            assertEquals(
                ActivityHistoryTimeCorrection.Timed(
                    Instant.parse("2026-01-15T09:00:00Z"),
                    Instant.parse("2026-01-15T10:00:00Z"),
                ),
                correction.time,
            )
            assertEquals(
                listOf(
                    NumberExecutionValue(NUMBER, 0),
                    CategoryExecutionValue(CATEGORY, OPTION_B),
                    TextExecutionValue(TEXT, ""),
                ),
                correction.values,
            )
            assertEquals("changed", correction.shortComment)
            fixture.close()
        }

    @Test
    fun noLiveHasNoStartAndPlanLinkedDetailIsDeleteOnly() =
        runBlocking {
            val noLive = fixture(detail(mode = TimeTrackingMode.NO_LIVE_TRACKING, startedAt = null))
            noLive.awaitLoaded()
            noLive.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertNull(
                noLive.controller.state.value.draft
                    ?.startedText,
            )
            noLive.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            noLive.controller.dispatch(ActivityHistoryMutationAction.Save)
            noLive.awaitRefresh()
            assertTrue(noLive.corrections.single().time is ActivityHistoryTimeCorrection.NoLive)
            noLive.close()

            val planned = fixture(detail(planEntryId = PlanEntryId("plan")))
            planned.awaitLoaded()
            planned.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertNull(planned.controller.state.value.draft)
            planned.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            assertTrue(planned.controller.state.value.deleteConfirmation)
            planned.close()
        }

    @Test
    fun strictGapOverlapOrderingAndFutureValidationUsePersistedEventZone() =
        runBlocking {
            val fixture =
                fixture(
                    detail(
                        startedAt = Instant.parse("2026-03-29T00:30:00Z"),
                        completedAt = Instant.parse("2026-03-29T01:30:00Z"),
                    ),
                )
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted("2026-02-30 10:00"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertEquals(ActivityHistoryMutationIssue.INVALID_DATE_TIME, fixture.controller.state.value.issue)

            fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted("2026-03-29 02:30"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertEquals(ActivityHistoryMutationIssue.NONEXISTENT_LOCAL_TIME, fixture.controller.state.value.issue)

            fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted("2026-10-25 02:30"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditCompleted("2026-10-25 03:30"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            val overlap = requireNotNull(fixture.controller.state.value.draft)
            assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), overlap.startedOffsets)
            assertEquals(ActivityHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME, fixture.controller.state.value.issue)
            fixture.controller.dispatch(ActivityHistoryMutationAction.SelectStartedOffset(ZoneOffset.ofHours(1)))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()
            assertEquals(
                Instant.parse("2026-10-25T01:30:00Z"),
                (fixture.corrections.single().time as ActivityHistoryTimeCorrection.Timed).startedAt,
            )
            fixture.close()
        }

    @Test
    fun staleCorrectionDropsDraftReloadsCanonicalAndNeverReplays() =
        runBlocking {
            val canonical = detail()
            val fresh = canonical.copy(updatedAt = TOKEN.plusSeconds(1))
            val reads = ArrayDeque(listOf(canonical, fresh, fresh))
            val fixture =
                fixture(canonical, read = { reads.removeFirst() }, correct = {
                    _,
                    _,
                    _,
                    ->
                    if (reads.size == 2) throw ConcurrentModificationException()
                })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("stale draft"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            withTimeout(2_000) {
                fixture.controller.state.first {
                    it.issue == ActivityHistoryMutationIssue.STALE &&
                        (it.load as? HistoryDetailLoad.Content)?.value?.updatedAt == fresh.updatedAt
                }
            }
            assertNull(fixture.controller.state.value.draft)
            assertEquals(1, fixture.correctAttempts)
            yield()
            assertEquals(1, fixture.correctAttempts)
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("reviewed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()
            assertEquals(2, fixture.correctAttempts)
            assertEquals(fresh.updatedAt, fixture.corrections.last().expectedUpdatedAt)
            fixture.close()
        }

    @Test
    fun committedCorrectionCannotBeRedispatchedWhenCanonicalReloadFails() =
        runBlocking {
            var reads = 0
            val fixture =
                fixture(
                    detail(),
                    read = {
                        if (reads++ == 0) detail() else error("read failed after commit")
                    },
                )
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            withTimeout(2_000) { fixture.controller.state.first { it.load is HistoryDetailLoad.Failure } }

            assertEquals(1, fixture.correctAttempts)
            assertNull(fixture.controller.state.value.draft)
            assertEquals(1, fixture.controller.state.value.refreshGeneration)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertEquals(1, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun routeSessionDeliversCommittedEffectsExactlyOnce() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            val session = ActivityHistoryMutationRouteSession(detail().root.executionId, fixture.controller)
            var refreshes = 0
            var deletes = 0

            session.deliverRefresh(1) { refreshes++ }
            session.deliverRefresh(1) { refreshes++ }
            session.deliverDelete { deletes++ }
            session.deliverDelete { deletes++ }

            assertEquals(1, refreshes)
            assertEquals(1, deletes)
            fixture.close()
        }

    @Test
    fun retainedOwnerKeepsDraftAcrossReacquisitionAndBackCancelsWithoutWriting() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            val owner = ActivityHistoryMutationRouteSessionOwner()
            val first = owner.acquire(detail().root.executionId) { fixture.controller }
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("draft"))

            val reacquired = owner.acquire(detail().root.executionId) { error("must retain") }
            assertSame(first, reacquired)
            assertEquals(
                "draft",
                reacquired.controller.state.value.draft
                    ?.shortComment,
            )
            assertTrue(reacquired.controller.handleBack())
            assertNull(reacquired.controller.state.value.draft)
            assertEquals(0, fixture.correctAttempts)

            reacquired.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            assertTrue(reacquired.controller.handleBack())
            assertFalse(reacquired.controller.state.value.deleteConfirmation)
            assertEquals(0, fixture.deleteAttempts)
            owner.release(reacquired)
            fixture.scope.cancel()
        }

    @Test
    fun inFlightCorrectionAndDeleteAdmitOneWriteAndBackCannotCancelOwnership() =
        runBlocking {
            val correctionGate = CompletableDeferred<Unit>()
            val correction = fixture(detail(), correct = { _, _, _ -> correctionGate.await() })
            correction.awaitLoaded()
            correction.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            correction.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            correction.controller.dispatch(ActivityHistoryMutationAction.Save)
            correction.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertTrue(correction.controller.handleBack())
            assertTrue(correction.controller.state.value.isMutating)
            assertEquals(1, correction.correctAttempts)
            correctionGate.complete(Unit)
            correction.awaitRefresh()
            correction.close()

            val deleteGate = CompletableDeferred<Unit>()
            val deleting = fixture(detail(), delete = { _, _, _ -> deleteGate.await() })
            deleting.awaitLoaded()
            deleting.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            deleting.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            deleting.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertTrue(deleting.controller.handleBack())
            assertEquals(1, deleting.deleteAttempts)
            deleteGate.complete(Unit)
            withTimeout(2_000) { deleting.controller.state.first { it.deleted } }
            deleting.close()
        }

    private fun fixture(
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
                { COMMAND_AT },
            )
        return Fixture(scope, controller).also { fixture = it }
    }

    private data class Fixture(
        val scope: CoroutineScope,
        val controller: ActivityHistoryMutationController,
        val corrections: MutableList<ActivityHistoryCorrection> = mutableListOf(),
        var correctAttempts: Int = 0,
        var deleteAttempts: Int = 0,
    ) {
        suspend fun awaitLoaded() =
            withTimeout(2_000) { controller.state.first { it.load is HistoryDetailLoad.Content } }

        suspend fun awaitRefresh() = withTimeout(2_000) { controller.state.first { it.refreshGeneration > 0 } }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    companion object {
        private val BERLIN = ZoneId.of("Europe/Berlin")
        private val TOKEN = Instant.parse("2026-01-15T12:00:00Z")
        private val COMMAND_AT = Instant.parse("2026-12-01T12:00:00Z")
        private val NUMBER = ActivitySnapshotFieldId("number")
        private val CATEGORY = ActivitySnapshotFieldId("category")
        private val TEXT = ActivitySnapshotFieldId("text")
        private val OPTION_A = ActivitySnapshotCategoryOptionId("a")
        private val OPTION_B = ActivitySnapshotCategoryOptionId("b")

        private fun detail(
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
            ),
            TOKEN,
            BERLIN,
            ActivityTemplateSettings(),
            listOf(
                ActivityHistoryField(
                    NUMBER,
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
                    CATEGORY,
                    "Category",
                    CustomFieldType.CATEGORY,
                    null,
                    null,
                    false,
                    ActivityHistoryConfiguredValue.Category(OPTION_A),
                    ActivityHistoryActualValue.Category(OPTION_A, "A"),
                    listOf(
                        ActivityHistoryCategoryOption(OPTION_A, "A"),
                        ActivityHistoryCategoryOption(OPTION_B, "B"),
                    ),
                ),
                ActivityHistoryField(
                    TEXT,
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
}
