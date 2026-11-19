@file:Suppress("LongMethod", "MaxLineLength")

package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset

class SequenceHistoryTimingStateTest {
    @Test
    fun allEightTypedTargetsRetainTheirLocalDiagnosticAndEditingRetiresIt() {
        Fixture().use { f ->
            f.send(SequenceHistoryMutationAction.BeginTiming)
            val targets =
                f.state.timingDraft!!
                    .timestamps.keys
                    .toList()
            assertEquals(8, targets.map { it.javaClass }.distinct().size)
            for (target in targets) {
                f.send(SequenceHistoryMutationAction.EditTimestamp(target, "invalid"))
                f.send(SequenceHistoryMutationAction.ReviewTiming)
                assertEquals(target, f.state.invalidTimestampTarget)
                assertEquals(SequenceHistoryMutationIssue.INVALID_DATE_TIME, f.state.issue)
                f.send(SequenceHistoryMutationAction.Cancel)
                assertNull(f.state.invalidTimestampTarget)
                f.send(SequenceHistoryMutationAction.BeginTiming)
            }
            assertEquals(0, f.writes)
            assertEquals(0L, f.state.refreshGeneration)
        }
    }

    @Test
    fun offsetAuthorityIsIndependentAndOnlyTheEditedEndpointLosesItsChoice() {
        Fixture(SequenceAdmissionFacts.detail().copy(originalZoneId = ZoneId.of("Europe/Berlin"))).use { f ->
            f.send(SequenceHistoryMutationAction.BeginTiming)
            val targets =
                listOf(SequenceHistoryTimestampTarget.RootStartedAt, SequenceHistoryTimestampTarget.RootEndedAt)
            for ((index, target) in targets.withIndex()) {
                f.send(SequenceHistoryMutationAction.EditTimestamp(target, "2026-10-25T02:30:27.123"))
                f.send(SequenceHistoryMutationAction.ReviewTiming)
                assertEquals(target, f.state.invalidTimestampTarget)
                assertEquals(SequenceHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME, f.state.issue)
                f.send(SequenceHistoryMutationAction.SelectTimestampOffset(target, ZoneOffset.ofHours(2 - index)))
            }
            val before = f.state.timingDraft!!
            f.send(SequenceHistoryMutationAction.EditTimestamp(targets[0], "2026-10-25T02:31:27.123"))
            assertNull(
                f.state.timingDraft!!
                    .timestamps
                    .getValue(targets[0])
                    .selectedOffset,
            )
            assertEquals(
                ZoneOffset.ofHours(1),
                f.state.timingDraft!!
                    .timestamps
                    .getValue(targets[1])
                    .selectedOffset,
            )
            assertEquals(ZoneOffset.ofHours(2), before.timestamps.getValue(targets[0]).selectedOffset)
            f.send(SequenceHistoryMutationAction.ReviewTiming)
            assertEquals(targets[0], f.state.invalidTimestampTarget)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun dstGapAndObsoleteOffsetKeepTheExactDiagnosticTarget() {
        val detail = SequenceAdmissionFacts.detail().copy(originalZoneId = ZoneId.of("Europe/Berlin"))
        val target = SequenceHistoryTimestampTarget.RootEndedAt
        val draft = SequenceHistoryProposalBuilder.timingDraft(detail)
        for ((text, offset, issue) in listOf(
            Triple("2026-03-29T02:30:00", null, SequenceHistoryProposalIssue.NONEXISTENT_LOCAL_TIME),
            Triple("2026-10-25T02:30:00", ZoneOffset.ofHours(3), SequenceHistoryProposalIssue.AMBIGUOUS_LOCAL_TIME),
        )) {
            val result =
                SequenceHistoryProposalBuilder.timing(
                    detail,
                    draft.copy(
                        timestamps =
                            draft.timestamps.withTimestamp(
                                target,
                                SequenceHistoryTimestampDraft(text, offset),
                            ),
                    ),
                )
            assertTrue(result is SequenceHistoryTimingBuildResult.Invalid)
            result as SequenceHistoryTimingBuildResult.Invalid
            assertEquals(target, result.target)
            assertEquals(issue, result.issue)
        }
    }

    @Test
    fun committedReadFailureRetainsTimingMeaningAndRetryNeverWritesAgain() {
        Fixture().use { f ->
            f.changedReview()
            f.read = { error("canonical read failed after commit") }
            f.send(SequenceHistoryMutationAction.ConfirmTiming)
            assertEquals(1, f.writes)
            assertEquals(SequenceHistoryCommittedMutation.TIMING, f.state.committedMutation)
            assertEquals(SequenceHistoryMutationIssue.READ_FAILURE, f.state.issue)
            assertEquals(1L, f.state.refreshGeneration)
            assertNull(f.state.timingProposal)
            f.read = { f.detail }
            f.send(SequenceHistoryMutationAction.Retry)
            assertEquals(1, f.writes)
            assertEquals(3, f.reads)
            assertTrue(f.state.load is HistoryDetailLoad.Content)
            assertEquals(SequenceHistoryCommittedMutation.TIMING, f.state.committedMutation)
            assertEquals(1L, f.state.refreshGeneration)
        }
    }

    @Test
    fun graphRejectionIsGlobalAndExplicitCurrentSaveCanRetry() {
        Fixture().use { f ->
            f.changedReview()
            f.write = { throw IllegalArgumentException("invalid graph") }
            f.send(SequenceHistoryMutationAction.ConfirmTiming)
            assertEquals(SequenceHistoryMutationIssue.INVALID_PROPOSAL, f.state.issue)
            assertNull(f.state.invalidTimestampTarget)
            assertNull(f.state.committedMutation)
            assertEquals(0L, f.state.refreshGeneration)
            f.write = {}
            f.send(SequenceHistoryMutationAction.ConfirmTiming)
            assertEquals(2, f.writes)
            assertEquals(1L, f.state.refreshGeneration)
        }
    }

    @Test
    fun noLiveUnperformedAndTombstoneDraftsContainOnlySupportedEndpoints() {
        val base = SequenceAdmissionFacts.detail()
        val performed = base.occurrences.first()
        val noLive =
            performed.copy(
                activity = performed.activity.copy(timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING),
                child = performed.child!!.copy(startedAt = null, activeDuration = null),
                childMutationFacts = performed.childMutationFacts!!.copy(startedAt = null, pauses = emptyList()),
            )
        val detail = base.copy(occurrences = base.occurrences.map { if (it === performed) noLive else it })
        val targets = SequenceHistoryProposalBuilder.timingDraft(detail).timestamps.keys
        assertTrue(SequenceHistoryTimestampTarget.ChildCompletedAt(noLive.occurrenceId) in targets)
        assertTrue(SequenceHistoryTimestampTarget.ChildStartedAt(noLive.occurrenceId) !in targets)
        for (unperformed in detail.occurrences.filter {
            it.status == RuntimeOccurrenceStatus.SKIPPED ||
                it.status == RuntimeOccurrenceStatus.DELETED_EXECUTION
        }) {
            assertTrue(SequenceHistoryTimestampTarget.OccurrenceEnteredAt(unperformed.occurrenceId) !in targets)
            assertTrue(SequenceHistoryTimestampTarget.OccurrenceCompletedAt(unperformed.occurrenceId) !in targets)
            assertTrue(SequenceHistoryTimestampTarget.ChildStartedAt(unperformed.occurrenceId) !in targets)
            assertTrue(SequenceHistoryTimestampTarget.ChildCompletedAt(unperformed.occurrenceId) !in targets)
        }
    }

    @Test
    fun largeImmutableDraftPreservesEveryOtherValueAndOldPublicationsAcrossLateEdits() {
        val values: Map<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft> =
            (0 until 60_000).associate { index ->
                SequenceHistoryTimestampTarget.IntervalStartedAt(SequenceIntervalId("interval-$index")) to
                    SequenceHistoryTimestampDraft("2026-01-01T00:00:00.123", ZoneOffset.UTC)
            }
        val original = SequenceHistoryTimestampMap.from(values)
        var changed: Map<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft> = original
        for (index in listOf(0, 30_000, 59_999)) {
            val target = SequenceHistoryTimestampTarget.IntervalStartedAt(SequenceIntervalId("interval-$index"))
            changed = changed.withTimestamp(target, SequenceHistoryTimestampDraft("edited-$index"))
        }
        assertEquals(values, original)
        assertEquals(values.keys.toList(), changed.keys.toList())
        assertEquals(3, changed.count { (key, value) -> value != original[key] })
        assertEquals(values.size, changed.size)
    }

    private class Fixture(
        val detail: SequenceHistoryDetail = SequenceAdmissionFacts.detail(),
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var reads = 0
        var writes = 0
        var read: () -> SequenceHistoryDetail = { detail }
        var write: () -> Unit = {}
        val controller =
            SequenceHistoryMutationController(
                scope,
                detail.root.executionId,
                {
                    reads++
                    read()
                },
                { _, _, _ ->
                    writes++
                    write()
                },
                { _, _, _ -> error("unexpected child writer") },
                { _, _, _ -> error("unexpected structural writer") },
            )
        val state get() = controller.state.value

        fun send(action: SequenceHistoryMutationAction) {
            SequenceHistoryAdmission(
                controller.executionId,
                state,
                { state },
                { true },
                controller::dispatch,
            ).action(action)()
        }

        fun changedReview() {
            send(SequenceHistoryMutationAction.BeginTiming)
            send(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:51:00.123",
                ),
            )
            send(SequenceHistoryMutationAction.ReviewTiming)
            assertNotNull(state.timingProposal)
        }

        override fun close() {
            controller.close()
            scope.cancel()
        }
    }
}
