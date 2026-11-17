@file:Suppress("LongMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing.history

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

class SequenceHistoryNavigationTest {
    @Test
    fun structuralNavigationChecksPublicationBeforeScrollAndAttachedFocus() {
        for (afterScroll in listOf(false, true)) {
            val f = Fixture()
            try {
                val navigation = StructuralListNavigation(f.lease)
                var scrolls = 0
                navigation.go(9_999, f.scope) { scrolls++ }
                val jump = requireNotNull(navigation.pending)
                assertFalse(navigation.focus(jump, true, true) { error("Focus before scroll") })
                f.queue.drain()
                if (afterScroll) f.nextFrame()
                f.state = f.state.copy(publication = 1)
                f.nextFrame()
                assertEquals(if (afterScroll) 1 else 0, scrolls)
                assertFalse(navigation.focus(jump, true, true) { error("Obsolete publication focus") })
                val lease = SequenceHistoryNavigation({ true })
                val fresh = StructuralListNavigation(lease)
                fresh.go(5_000, f.scope) { scrolls++ }
                f.nextFrame()
                val current = requireNotNull(fresh.pending)
                assertFalse(fresh.focus(current, false, true) { error("Detached native target") })
                assertFalse(fresh.focus(current, true, false) { error("Hidden native target") })
                assertTrue(fresh.focus(current, true, true) { true })
                assertNull(fresh.pending)
                fresh.go(1, f.scope) { error("Disposed stage scroll") }
                fresh.dispose()
                f.nextFrame()
                assertNull(fresh.pending)
                assertEquals(0, f.writes)
            } finally {
                f.scope.cancel()
            }
        }
    }

    @Test
    fun revokedTimingJumpDiscardsItsPendingFocus() {
        val fixture = Fixture()
        try {
            val navigation = TimingNavigation(fixture.lease) { 10 }
            navigation.go(SequenceHistoryTimestampTarget.RootEndedAt, fixture.scope) { error("Stale scroll") }
            fixture.visible = false
            fixture.queue.drain()
            assertNull(navigation.pending)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun currentEditorAndReviewJumpsResolveTypedTargetsBeforeNativeFocus() {
        for (review in listOf(false, true)) {
            val f = Fixture()
            try {
                val targets =
                    SequenceHistoryProposalBuilder
                        .timingDraft(f.detail)
                        .timestamps.keys
                        .toList()
                val selected = if (review) targets.reversed().take(3) else targets
                val destinations = SequenceTimingDestinations(f.detail, selected)
                val prefix = if (review) 1 else 0
                val navigation = TimingNavigation(f.lease) { target -> destinations.positions[target]?.plus(prefix) }
                val target = selected.last()
                val scrolls = mutableListOf<Int>()
                navigation.go(target, f.scope) { scrolls += it }
                val request = requireNotNull(navigation.pending)
                assertSame(f.lease, request.lease)
                assertEquals(target, request.target)
                assertFalse(navigation.focus(request, true, true) { error("Focus before scroll") })
                f.nextFrame()
                assertEquals(listOf(selected.lastIndex + prefix), scrolls)
                assertFalse(navigation.focus(request, false, true) { error("Detached focus") })
                assertFalse(navigation.focus(request, true, false) { error("Unavailable native focus") })
                assertFalse(navigation.focus(request, true, true) { false })
                assertSame(request, navigation.pending, "A declined native request remains retryable")
                assertTrue(navigation.focus(request, true, true) { true })
                assertNull(navigation.pending)
                assertEquals(0, f.writes)
            } finally {
                f.scope.cancel()
            }
        }
    }

    @Test
    fun timingJumpsLoseEveryAuthorityBeforeScrollAndBetweenScrollAndFocus() {
        for (afterScroll in listOf(false, true)) {
            for (invalidate in listOf<(Fixture) -> Unit>(
                { it.visible = false },
                { it.activeSession = Any() },
                { it.state = it.state.copy(publication = 1) },
                { it.state = it.state.copy(load = HistoryDetailLoad.Content(it.detail.copy())) },
                { it.state = it.state.copy(timingDraft = SequenceHistoryProposalBuilder.timingDraft(it.detail)) },
                { it.lease.revoke() },
                { it.lease.attached = false },
            )) {
                timingJumpLosesAuthority(afterScroll, invalidate)
            }
        }
    }

    private fun timingJumpLosesAuthority(
        afterScroll: Boolean,
        invalidate: (Fixture) -> Unit,
    ) {
        val f = Fixture()
        try {
            val target = SequenceHistoryTimestampTarget.RootEndedAt
            val navigation = TimingNavigation(f.lease) { if (it == target) 1 else null }
            var scrolls = 0
            navigation.go(target, f.scope) { scrolls++ }
            val request = requireNotNull(navigation.pending)
            f.queue.drain() // Explicitly suspended at the frame boundary.
            if (afterScroll) f.nextFrame()
            invalidate(f)
            f.nextFrame()
            assertEquals(if (afterScroll) 1 else 0, scrolls)
            assertNull(navigation.pending)
            assertFalse(navigation.focus(request, true, true) { error("Stale focus") })
            f.visible = true
            f.activeSession = f.session
            navigation.go(target, f.scope) { error("Revived old scroll") }
            f.nextFrame()
            assertNull(navigation.pending)
            val fresh = SequenceHistoryNavigation({ f.visible })
            val current = TimingNavigation(fresh) { if (it == target) 1 else null }
            current.go(target, f.scope) { scrolls++ }
            f.nextFrame()
            assertTrue(current.focus(requireNotNull(current.pending), true, true) { true })
            assertEquals(if (afterScroll) 2 else 1, scrolls)
            assertEquals(0, f.writes)
        } finally {
            f.scope.cancel()
        }
    }

    @Test
    fun supersededTimingTargetsAndReplacedMembershipCannotInheritPendingFocus() {
        for (afterScroll in listOf(false, true)) {
            val f = Fixture()
            try {
                val first = SequenceHistoryTimestampTarget.RootStartedAt
                val second = SequenceHistoryTimestampTarget.RootEndedAt
                val positions = mutableMapOf<SequenceHistoryTimestampTarget, Int>(first to 0, second to 1)
                val navigation = TimingNavigation(f.lease) { positions[it] }
                val scrolls = mutableListOf<Int>()
                navigation.go(first, f.scope) { scrolls += it }
                val old = requireNotNull(navigation.pending)
                if (afterScroll) f.nextFrame()
                navigation.go(second, f.scope) { scrolls += it }
                f.nextFrame()
                assertFalse(navigation.focus(old, true, true) { error("Superseded focus") })
                assertEquals(second, navigation.pending?.target)
                val current = requireNotNull(navigation.pending)
                positions.remove(second)
                assertFalse(navigation.focus(current, true, true) { error("Removed target") })
                assertNull(navigation.pending)
                assertEquals(if (afterScroll) listOf(0, 1) else listOf(1), scrolls)
                navigation.go(first, f.scope) { error("Disposed stage scroll") }
                navigation.dispose()
                f.nextFrame()
                assertNull(navigation.pending)
                assertFalse(navigation.focus(current, true, true) { error("Dismissed stage focus") })
                assertEquals(0, f.writes)
            } finally {
                f.scope.cancel()
            }
        }
    }

    @Test
    fun cancelledTimingScopeAndChangedIndexDiscardTheExactRequest() {
        for (cancelScope in listOf(false, true)) {
            val f = Fixture()
            try {
                var index = 1
                val navigation = TimingNavigation(f.lease) { index }
                navigation.go(SequenceHistoryTimestampTarget.RootEndedAt, f.scope) { error("Obsolete scroll") }
                f.queue.drain()
                if (cancelScope) f.scope.cancel() else index = 0
                f.nextFrame()
                assertNull(navigation.pending)
                assertEquals(0, f.writes)
            } finally {
                f.scope.cancel()
            }
        }
    }

    @Test
    fun cancellingTheOriginatingNavigationScopeAfterScrollCannotDeliverPendingFocus() {
        val f = Fixture()
        try {
            val navigation = TimingNavigation(f.lease) { 1 }
            var scrolls = 0
            navigation.go(SequenceHistoryTimestampTarget.RootEndedAt, f.scope) { scrolls++ }
            f.nextFrame()
            val request = requireNotNull(navigation.pending)
            assertEquals(1, scrolls)
            f.scope.cancel()
            assertNull(navigation.pending)
            assertFalse(navigation.focus(request, true, true) { error("Cancelled focus") })
            assertEquals(0, f.writes)
        } finally {
            f.scope.cancel()
        }
    }

    @Test
    fun equalLookingCanonicalReplacementRejectsOldJumpAndAllowsItsOwnFreshNavigation() {
        val f = Fixture()
        try {
            val replacement = f.detail.copy()
            assertEquals(f.detail, replacement)
            val originalReference = SequenceDetailReference(f.detail)
            val replacementReference = SequenceDetailReference(replacement)
            val canonical = mutableStateOf(originalReference)
            val oldLease = SequenceHistoryNavigation({ canonical.value == originalReference })
            val old = TimingNavigation(oldLease) { 1 }
            val target = SequenceHistoryTimestampTarget.RootEndedAt
            old.go(target, f.scope) { error("Replaced canonical scroll") }
            f.queue.drain()
            canonical.value = replacementReference
            f.nextFrame()
            assertNull(old.pending)
            val freshLease = SequenceHistoryNavigation({ canonical.value == replacementReference })
            val fresh = TimingNavigation(freshLease) { 1 }
            var scrolls = 0
            fresh.go(target, f.scope) { scrolls++ }
            f.nextFrame()
            assertEquals(1, scrolls)
            assertTrue(fresh.focus(requireNotNull(fresh.pending), true, true) { true })
            assertEquals(0, f.writes)
        } finally {
            f.scope.cancel()
        }
    }

    @Test
    fun timingOwnerOrdinalsResolveExactExistingTargetsAndMissingEndpointsStayMissing() {
        val detail = SequenceAdmissionFacts.detail()
        val targets =
            SequenceHistoryProposalBuilder
                .timingDraft(detail)
                .timestamps.keys
                .toList()
        val navigation = SequenceTimingDestinations(detail, targets)
        assertEquals(
            SequenceHistoryTimestampTarget.RootEndedAt,
            targets[navigation.index(SequenceTimingSection.ROOT, 2)!!],
        )
        assertEquals(
            SequenceHistoryTimestampTarget.OccurrenceEnteredAt(detail.occurrences.first().occurrenceId),
            targets[navigation.index(SequenceTimingSection.OCCURRENCES, 1)!!],
        )
        assertEquals(
            SequenceHistoryTimestampTarget.ChildStartedAt(detail.occurrences[2].occurrenceId),
            targets[navigation.index(SequenceTimingSection.CHILDREN, 3)!!],
        )
        assertEquals(
            SequenceHistoryTimestampTarget.IntervalStartedAt(detail.intervals.last().id),
            targets[navigation.index(SequenceTimingSection.INTERVALS, detail.intervals.size)!!],
        )
        assertEquals(null, navigation.index(SequenceTimingSection.OCCURRENCES, 2))
        assertEquals(null, navigation.index(SequenceTimingSection.CHILDREN, 4))
        assertEquals(null, navigation.index(SequenceTimingSection.TIMESTAMPS, 0))
        assertEquals(null, navigation.index(SequenceTimingSection.TIMESTAMPS, targets.size + 1))
        val reviewed = listOf(SequenceHistoryTimestampTarget.ChildCompletedAt(detail.occurrences[2].occurrenceId))
        val reviewNavigation = SequenceTimingDestinations(detail, reviewed)
        assertEquals(0, reviewNavigation.index(SequenceTimingSection.CHILDREN, 3))
        assertEquals(null, reviewNavigation.index(SequenceTimingSection.CHILDREN, 1))
        assertEquals(null, reviewNavigation.index(SequenceTimingSection.ROOT, 2))
    }

    @Test
    fun nativeFocusPropertiesDoNotObserveRetiringPublicationsButRequestsStayGuarded() {
        val initial = Any()
        val publication = mutableStateOf(initial)
        val lease = SequenceHistoryNavigation({ publication.value === initial })
        val nativeObserver = SnapshotStateObserver { it() }
        var invalidated = false
        Snapshot.sendApplyNotifications()
        nativeObserver.start()
        try {
            nativeObserver.observeReads(Any(), { invalidated = true }) { assertTrue(lease.canFocus()) }
            publication.value = Any()
            Snapshot.sendApplyNotifications()
            assertFalse(invalidated, "Replacing a publication must not clear a surviving native focus target")
            assertFalse(lease.canFocus(), "A new focus request still rejects the obsolete publication")
            publication.value = initial
            assertFalse(lease.canFocus(), "A rejected native request cannot revive")
            assertTrue(SequenceHistoryNavigation({ publication.value === initial }).canFocus())
        } finally {
            nativeObserver.stop()
            nativeObserver.clear()
        }
    }

    @Test
    fun routePublicationCanonicalAndSessionReplacementPermanentlyRevokeQueuedReadOnlyWork() {
        for (invalidate in listOf<(Fixture) -> Unit>(
            { it.visible = false },
            { it.activeSession = Any() },
            { it.state = it.state.copy(publication = 1) },
            { it.state = it.state.copy(load = HistoryDetailLoad.Content(it.detail.copy())) },
            { it.lease.attached = false },
            { it.lease.revoke() },
        )) {
            val fixture = Fixture()
            try {
                var scrolls = 0
                fixture.lease.launch(fixture.scope) { if (fixture.lease.isCurrent()) scrolls++ }
                invalidate(fixture)
                fixture.queue.drain()
                assertEquals(0, scrolls)
                assertFalse(fixture.lease.isCurrent())
                fixture.visible = true
                fixture.activeSession = fixture.session
                fixture.lease.launch(fixture.scope) { scrolls++ }
                fixture.queue.drain()
                assertEquals(0, scrolls)
                assertEquals(0, fixture.writes)
            } finally {
                fixture.scope.cancel()
            }
        }
    }

    @Test
    fun deferredFocusCannotBorrowNewAuthorityAfterLossOrCanonicalReplacement() {
        for (replace in listOf(false, true)) {
            val fixture = Fixture()
            val frame = CompletableDeferred<Unit>()
            var scrolls = 0
            var focuses = 0
            try {
                fixture.lease.launch(fixture.scope) {
                    if (fixture.lease.isCurrent()) scrolls++
                    frame.await()
                    if (fixture.lease.isCurrent()) focuses++
                }
                fixture.queue.drain()
                assertEquals(1, scrolls)
                if (replace) {
                    fixture.state = fixture.state.copy(load = HistoryDetailLoad.Content(fixture.detail.copy()))
                } else {
                    fixture.visible = false
                }
                assertFalse(fixture.lease.isCurrent())
                fixture.visible = true
                frame.complete(Unit)
                fixture.queue.drain()
                assertEquals(0, focuses)
                assertEquals(0, fixture.writes)
            } finally {
                fixture.scope.cancel()
            }
        }
    }

    @Test
    fun currentReadOnlyWorkAndNewPublicationWorkRemainUsable() {
        val fixture = Fixture()
        try {
            var visits = 0
            repeat(2) {
                fixture.lease.launch(fixture.scope) { if (fixture.lease.isCurrent()) visits++ }
                fixture.queue.drain()
            }
            fixture.visible = false
            assertFalse(fixture.lease.isCurrent())
            fixture.visible = true
            val fresh = SequenceHistoryNavigation({ fixture.admission.isCurrent() })
            fresh.launch(fixture.scope) { visits++ }
            fixture.queue.drain()
            assertEquals(3, visits)
            assertEquals(0, fixture.writes)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun anInitiallyHiddenLeaseDoesNotPublishEndlessReplacementGenerations() {
        var publications = 0
        val lease = SequenceHistoryNavigation({ false }) { publications++ }
        repeat(3) { assertFalse(lease.isCurrent()) }
        assertEquals(0, publications)
    }

    @Test
    fun disposingNavigationCancelsWorkWithoutInvalidatingNewComposition() {
        var publications = 0
        val lease = SequenceHistoryNavigation({ true }) { publications++ }
        assertTrue(lease.isCurrent())
        lease.attached = false
        lease.revoke()
        assertEquals(0, publications)
        assertFalse(lease.isCurrent())
    }

    @Test
    fun readOnlyAuthorityBindsTheExactCanonicalObject() {
        val fixture = Fixture()
        try {
            assertTrue(fixture.admission.isShowing(fixture.detail))
            assertFalse(fixture.admission.isShowing(fixture.detail.copy()))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun supersedingJumpCancelsThePreviousDeferredFocus() {
        val fixture = Fixture()
        val frame = CompletableDeferred<Unit>()
        val visits = mutableListOf<Int>()
        try {
            fixture.lease.launch(fixture.scope) {
                frame.await()
                if (fixture.lease.isCurrent()) visits += 1
            }
            fixture.queue.drain()
            fixture.lease.launch(fixture.scope) { visits += 2 }
            frame.complete(Unit)
            fixture.queue.drain()
            assertEquals(listOf(2), visits)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun retainedOccurrenceDoesNotRetainRemovedChildSummaryFieldOrDeleteAction() {
        val base = SequenceAdmissionFacts.detail()
        val performed = base.occurrences.first()
        val child = requireNotNull(performed.child)
        val field = field()
        val original = base.copy(occurrences = listOf(performed.copy(child = child.copy(fields = listOf(field)))))
        val tombstone =
            original.copy(
                occurrences = listOf(performed.copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION, child = null)),
            )
        val identities =
            listOf(
                SequenceHistoryFocusElement.Child(child.executionId),
                SequenceHistoryFocusElement.ChildField(child.executionId, field.id),
                SequenceHistoryFocusElement.DeleteChild(child.executionId),
            )
        identities.forEach { element ->
            val identity = SequenceHistoryFocusIdentity(performed.occurrenceId, element)
            assertTrue(original.represents(identity, true, true))
            assertFalse(tombstone.represents(identity, true, true))
            assertFalse(original.copy(occurrences = emptyList()).represents(identity, true, true))
        }
        assertTrue(
            tombstone.represents(
                SequenceHistoryFocusIdentity(performed.occurrenceId, SequenceHistoryFocusElement.Summary),
                true,
                true,
            ),
        )
        assertTrue(
            tombstone.represents(
                SequenceHistoryFocusIdentity(
                    performed.occurrenceId,
                    SequenceHistoryFocusElement.RemoveOccurrence(child.executionId),
                ),
                true,
                true,
            ),
        )
    }

    @Test
    fun nestedMembershipUsesExactFrozenIdsAndAvailabilityRatherThanLabelsOrPositions() {
        val base = SequenceAdmissionFacts.detail()
        val occurrence = base.occurrences.first()
        val child = requireNotNull(occurrence.child)
        val field = field()
        val original = base.copy(occurrences = listOf(occurrence.copy(child = child.copy(fields = listOf(field)))))
        val identity =
            SequenceHistoryFocusIdentity(
                occurrence.occurrenceId,
                SequenceHistoryFocusElement.ChildField(child.executionId, field.id),
            )
        assertTrue(original.copy().represents(identity, false, false))
        assertFalse(base.represents(identity, true, true))
        assertFalse(
            original.represents(
                identity.copy(element = SequenceHistoryFocusElement.ChildField(ActivityExecutionId("other"), field.id)),
                true,
                true,
            ),
        )
        val configured =
            base.copy(
                occurrences =
                    listOf(
                        occurrence.copy(child = null, activity = occurrence.activity.copy(mainValue = field)),
                    ),
            )
        val configuredIdentity =
            identity.copy(
                element = SequenceHistoryFocusElement.ConfiguredField(occurrence.activitySnapshotId, field.id),
            )
        assertTrue(configured.represents(configuredIdentity, true, true))
        assertFalse(original.represents(configuredIdentity, true, true))
        val delete = identity.copy(element = SequenceHistoryFocusElement.DeleteChild(child.executionId))
        val remove = identity.copy(element = SequenceHistoryFocusElement.RemoveOccurrence(child.executionId))
        assertFalse(original.represents(delete, false, true))
        assertFalse(original.represents(remove, true, false))
        assertFalse(original.represents(identity.copy(element = SequenceHistoryFocusElement.Tombstone), true, true))
    }

    private fun field() =
        ActivityHistoryField(
            ActivitySnapshotFieldId("field"),
            "Same label",
            CustomFieldType.NUMBER,
            null,
            0,
            false,
            ActivityHistoryConfiguredValue.Missing,
            ActivityHistoryActualValue.Number(0),
            emptyList(),
        )

    private class Fixture {
        val detail = SequenceAdmissionFacts.detail()
        val session = Any()
        var activeSession = session
        var visible = true
        var writes = 0
        var state = SequenceHistoryMutationState(load = HistoryDetailLoad.Content(detail))
        val admission =
            SequenceHistoryAdmission(detail.root.executionId, state, { state }, {
                visible &&
                    activeSession === session
            }, { writes++ })
        val lease = SequenceHistoryNavigation({ admission.isCurrent() })
        val queue = QueueDispatcher()
        val frame = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + queue + frame)

        fun nextFrame() {
            queue.drain()
            frame.sendFrame(1L)
            queue.drain()
        }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
