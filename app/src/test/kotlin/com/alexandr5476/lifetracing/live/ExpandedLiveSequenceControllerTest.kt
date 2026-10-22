@file:Suppress("LargeClass", "LongMethod")

package com.alexandr5476.lifetracing.live

import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceProjector
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceRead
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceMaterializer
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecutionFactory
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceRuntimeEngine
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExpandedLiveSequenceControllerTest {
    @Test
    fun runtimeAddAcknowledgesOnlyMatchingIdleAdmissionBeforeExecution() =
        runBlocking {
            val original = expanded()
            var result: ExpandedLiveSequenceRead = ExpandedLiveSequenceRead.Active(original)
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val executionGate = CompletableDeferred<Unit>()
            var admissions = 0
            val oneOff =
                com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft(
                    "Retained draft",
                    null,
                    TimeTrackingMode.TIMER,
                    Duration.ofSeconds(137),
                )
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    original.runtime.execution.id,
                    { result },
                    {
                        commands += it
                        executionGate.await()
                    },
                    {},
                    MutableStateFlow(0L),
                    { null },
                    { emptyList() },
                    { Instant.EPOCH },
                )
            controller.awaitLoaded()
            val changedCurrent =
                original.copy(
                    runtime =
                        original.runtime.copy(
                            execution =
                                original.runtime.execution.copy(
                                    currentOccurrenceId =
                                        original.occurrences
                                            .last()
                                            .occurrence.id,
                                ),
                        ),
                )
            for (changed in listOf(changedCurrent, original.copy(state = ActiveSequenceState.WAITING_NEXT))) {
                result = ExpandedLiveSequenceRead.Active(changed)
                controller.refresh()
                controller.awaitLoaded()
                controller.runtimeAddTemplate(
                    ActivityTemplateId("template"),
                    RuntimeInsertionPlacement.TO_END,
                    expectedSequence = original,
                    onAdmitted = { admissions++ },
                )
                controller.runtimeAddOneOff(
                    oneOff,
                    RuntimeInsertionPlacement.AFTER_CURRENT,
                    expectedSequence = original,
                    onAdmitted = { admissions++ },
                )
                assertEquals(0, admissions)
                assertTrue(commands.isEmpty())
            }
            result = ExpandedLiveSequenceRead.Active(original)
            controller.refresh()
            controller.awaitLoaded()
            controller.runtimeAddTemplate(
                ActivityTemplateId("template"),
                RuntimeInsertionPlacement.START_NOW,
                expectedSequence = original,
                onAdmitted = { admissions++ },
            )
            val foreignContext =
                original.copy(
                    runtime =
                        original.runtime.copy(
                            execution = original.runtime.execution.copy(id = SequenceExecutionId("foreign")),
                        ),
                )
            controller.runtimeAddOneOff(
                oneOff,
                RuntimeInsertionPlacement.AFTER_CURRENT,
                expectedSequence = foreignContext,
                onAdmitted = { admissions++ },
            )
            assertEquals(0, admissions)
            assertTrue(commands.isEmpty())
            controller.runtimeAddOneOff(
                oneOff,
                RuntimeInsertionPlacement.AFTER_CURRENT,
                expectedSequence = original,
                onAdmitted = { admissions++ },
            )
            // Acknowledgement is synchronous with admission, before the queued write.
            assertEquals(1, admissions)
            assertTrue(commands.isEmpty())
            controller.runtimeAddTemplate(
                ActivityTemplateId("template"),
                RuntimeInsertionPlacement.TO_END,
                expectedSequence = original,
                onAdmitted = { admissions++ },
            )
            assertEquals(1, admissions)
            assertNull(controller.state.value.commandFailure)
            executionGate.complete(Unit)
            withTimeout(1_000) { controller.state.first { !it.commandInFlight } }
            assertEquals(1, commands.size)
            assertEquals(
                ActivityEntrySource.OneOff(oneOff),
                (commands.single() as ExpandedSequenceCommand.RuntimeAdd).source,
            )
            controller.close()
        }

    @Test
    fun runtimeAddDuringRefreshKeepsAdmissionClosedWithoutTransientRejection() =
        runBlocking {
            val original = expanded()
            var pending: CompletableDeferred<ExpandedLiveSequenceRead>? = null
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    original.runtime.execution.id,
                    { pending?.await() ?: ExpandedLiveSequenceRead.Active(original) },
                    { commands += it },
                    {},
                    MutableStateFlow(0L),
                    { null },
                    { emptyList() },
                    { Instant.EPOCH },
                )
            controller.awaitLoaded()
            pending = CompletableDeferred()
            controller.refresh()
            controller.runtimeAddTemplate(ActivityTemplateId("template"), RuntimeInsertionPlacement.TO_END)
            controller.runtimeAddOneOff(
                com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft(
                    "Retained draft",
                    null,
                    TimeTrackingMode.TIMER,
                    Duration.ofSeconds(137),
                ),
                RuntimeInsertionPlacement.AFTER_CURRENT,
            )
            assertTrue(commands.isEmpty())
            assertFalse(controller.state.value.commandInFlight)
            assertNull(controller.state.value.commandFailure)
            pending.complete(ExpandedLiveSequenceRead.Active(original))
            controller.awaitLoaded()
            assertNull(controller.state.value.commandFailure)
            controller.runtimeAddTemplate(ActivityTemplateId("template"), RuntimeInsertionPlacement.TO_END)
            withTimeout(1_000) { controller.state.first { !it.commandInFlight } }
            assertEquals(1, commands.size)
            controller.close()
        }

    @Test
    fun foregroundGenerationRetainsUncommittedValuesWithoutRestoringConfirmationOrWriting() =
        runBlocking {
            val original = expanded(withAllValueTypes = true)
            val generation = MutableStateFlow(0L)
            var pending: CompletableDeferred<ExpandedLiveSequenceRead>? = null
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    original.runtime.execution.id,
                    { pending?.await() ?: ExpandedLiveSequenceRead.Active(original) },
                    { commands += it },
                    {},
                    generation,
                    { null },
                    { emptyList() },
                    { Instant.EPOCH },
                )
            controller.awaitLoaded()
            val fields =
                original.occurrences
                    .first()
                    .activity.fields
            controller.editNumber(fields[0].id, "73")
            controller.editText(fields[1].id, "Uncommitted text")
            val draft = controller.state.value.currentValueDraft
            controller.requestEndEarly()
            val read = CompletableDeferred<ExpandedLiveSequenceRead>()
            pending = read
            generation.value++
            withTimeout(1_000) { controller.state.first { it.loading } }
            assertNull(controller.state.value.confirmedSequence)
            assertNull(controller.state.value.confirmation)
            controller.editNumber(fields[0].id, "999")
            controller.editText(fields[1].id, "Stale edit")
            controller.saveCurrentValues()
            assertEquals(draft, controller.state.value.currentValueDraft)
            assertTrue(commands.isEmpty())
            read.complete(ExpandedLiveSequenceRead.Active(original))
            controller.awaitLoaded()
            assertEquals(original, controller.state.value.confirmedSequence)
            assertEquals(draft, controller.state.value.currentValueDraft)
            controller.confirmPending()
            assertNull(controller.state.value.confirmation)
            assertTrue(commands.isEmpty())
            controller.close()
        }

    @Test
    fun reloadAfterSuccessBlocksEveryCommandUntilFreshPublicationInAllFiveStates() =
        runBlocking {
            for (runtimeState in ActiveSequenceState.entries) {
                val original = expanded(confirmJump = true, withValue = true).copy(state = runtimeState)
                var pending: CompletableDeferred<ExpandedLiveSequenceRead>? = null
                val commands = mutableListOf<ExpandedSequenceCommand>()
                val controller =
                    ExpandedLiveSequenceController(
                        this,
                        original.runtime.execution.id,
                        { pending?.await() ?: ExpandedLiveSequenceRead.Active(original) },
                        { commands += it },
                        {},
                        MutableStateFlow(0L),
                        { null },
                        { emptyList() },
                        { Instant.EPOCH.plusSeconds(1) },
                    )
                controller.awaitLoaded()
                val target =
                    original.occurrences
                        .last()
                        .occurrence.id
                controller.requestEndEarly()
                assertEquals(ExpandedSequenceConfirmation.EndEarly, controller.state.value.confirmation)
                pending = CompletableDeferred()
                controller.refresh()

                fun attemptOldCallbacks() {
                    controller.confirmPending()
                    controller.pause()
                    controller.resume()
                    controller.startNext()
                    controller.completeCurrent()
                    controller.makeNext(target)
                    controller.requestGoNow(target)
                    controller.doAgain(target, RuntimeInsertionPlacement.TO_END)
                    controller.runtimeAddTemplate(ActivityTemplateId("old"), RuntimeInsertionPlacement.TO_END)
                    controller.runtimeAddOneOff(
                        com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft(
                            "Old draft",
                            null,
                            TimeTrackingMode.STOPWATCH,
                            null,
                        ),
                        RuntimeInsertionPlacement.TO_END,
                    )
                    controller.saveCurrentValues()
                    controller.requestEndEarly()
                    controller.editNumber(
                        original.occurrences
                            .first()
                            .activity.fields
                            .single()
                            .id,
                        "99",
                    )
                }

                attemptOldCallbacks()
                assertFalse(controller.state.value.commandInFlight, runtimeState.name)
                assertNull(controller.state.value.confirmation, runtimeState.name)
                pending.completeExceptionally(IllegalStateException("refresh failed"))
                withTimeout(1_000) { controller.state.first { it.readFailure != null } }
                attemptOldCallbacks()
                assertFalse(controller.state.value.commandInFlight, runtimeState.name)
                assertTrue(commands.isEmpty(), runtimeState.name)
                assertNull(controller.state.value.confirmation, runtimeState.name)

                pending = CompletableDeferred()
                controller.refresh()
                val recovered = original.copy(state = ActiveSequenceState.PAUSED_CURRENT)
                pending.complete(ExpandedLiveSequenceRead.Active(recovered))
                controller.awaitLoaded()
                controller.confirmPending()
                controller.pause()
                assertTrue(commands.isEmpty())
                controller.resume()
                withTimeout(1_000) { controller.state.first { !it.commandInFlight } }
                assertEquals(listOf(ExpandedSequenceCommand.Resume::class), commands.map { it::class })
                controller.close()
            }
        }

    @Test
    fun supersededSuccessfulReadCannotRestoreCommandsAfterLatestReadFailure() {
        val original = expanded()
        val oldRead = CompletableDeferred<ExpandedLiveSequenceRead>()
        val latestRead = CompletableDeferred<ExpandedLiveSequenceRead>()
        var reads = 0
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller =
            ExpandedLiveSequenceController(
                CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                original.runtime.execution.id,
                {
                    when (++reads) {
                        2 -> oldRead.await()
                        3 -> latestRead.await()
                        else -> ExpandedLiveSequenceRead.Active(original)
                    }
                },
                { commands += it },
                {},
                MutableStateFlow(0L),
                { null },
                { emptyList() },
                { Instant.EPOCH.plusSeconds(1) },
            )
        controller.refresh()
        controller.refresh()
        latestRead.completeExceptionally(IllegalStateException("latest failed"))
        oldRead.complete(ExpandedLiveSequenceRead.Active(original))
        assertEquals("latest failed", controller.state.value.readFailure)
        controller.pause()
        assertTrue(commands.isEmpty())
        controller.refresh()
        controller.pause()
        assertEquals(listOf(ExpandedSequenceCommand.Pause::class), commands.map { it::class })
        controller.close()
    }

    @Test
    fun fiveStateActionMatrixIsExact() {
        assertEquals(
            ExpandedSequenceActions(
                pause = true,
                completeCurrent = true,
                editCurrentValues = true,
                goNow = true,
                makeNext = true,
                runtimeAdd = true,
                doAgain = true,
            ),
            expandedActions(ActiveSequenceState.RUNNING_CURRENT),
        )
        assertEquals(
            ExpandedSequenceActions(resume = true, editCurrentValues = true),
            expandedActions(ActiveSequenceState.PAUSED_CURRENT),
        )
        assertEquals(
            ExpandedSequenceActions(startNext = true, goNow = true, runtimeAdd = true, doAgain = true),
            expandedActions(ActiveSequenceState.WAITING_NEXT),
        )
        assertEquals(
            ExpandedSequenceActions(pause = true, goNow = true, runtimeAdd = true, doAgain = true),
            expandedActions(ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN),
        )
        assertEquals(
            ExpandedSequenceActions(resume = true),
            expandedActions(ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN),
        )
        assertEquals(
            setOf(RuntimeInsertionPlacement.TO_END, RuntimeInsertionPlacement.AFTER_CURRENT),
            validRuntimePlacements(ActiveSequenceState.RUNNING_CURRENT),
        )
        assertEquals(
            setOf(RuntimeInsertionPlacement.TO_END, RuntimeInsertionPlacement.START_NOW),
            validRuntimePlacements(ActiveSequenceState.WAITING_NEXT),
        )
        assertTrue(validRuntimePlacements(ActiveSequenceState.PAUSED_CURRENT).isEmpty())
    }

    @Test
    fun frozenJumpConfirmationPreventsWriterUntilConfirmed() =
        runBlocking {
            val expanded = expanded(confirmJump = true)
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val controller = controller(this, expanded, commands)
            controller.awaitLoaded()
            val target =
                expanded.occurrences
                    .last()
                    .occurrence.id

            controller.requestGoNow(target)
            assertInstanceOf(ExpandedSequenceConfirmation.GoNow::class.java, controller.state.value.confirmation)
            assertTrue(commands.isEmpty())

            controller.confirmPending()
            withTimeout(1_000) { controller.state.first { commands.size == 1 && !it.commandInFlight } }
            assertEquals(ExpandedSequenceCommand.GoNow::class, commands.single()::class)
            controller.close()
        }

    @Test
    fun staleUiActionIsRejectedAgainstLatestPausedProjection() =
        runBlocking {
            val running = expanded()
            val pausedState = running.copy(state = ActiveSequenceState.PAUSED_CURRENT)
            var current = running
            val semantic = MutableStateFlow(0L)
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val controller = controller(this, running, commands, semantic) { current }
            controller.awaitLoaded()

            current = pausedState
            semantic.value++
            withTimeout(1_000) { controller.state.first { it.sequence?.state == ActiveSequenceState.PAUSED_CURRENT } }
            controller.completeCurrent()
            controller.makeNext(
                running.occurrences
                    .last()
                    .occurrence.id,
            )

            assertTrue(commands.isEmpty())
            val failure = withTimeout(1_000) { controller.state.first { it.commandFailure != null }.commandFailure }
            assertInstanceOf(ExpandedSequenceFailure.Rejected::class.java, failure)
            controller.close()
        }

    @Test
    fun routeSessionRetainsExactControllerAndRejectsAnotherExecution() {
        val owner = ExpandedLiveSequenceRouteSessionOwner()
        val controller = stubController(SequenceExecutionId("a"))
        val first = owner.acquire(SequenceExecutionId("a")) { controller }

        assertSame(first, owner.acquire(SequenceExecutionId("a")) { error("must reuse") })
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            owner.acquire(SequenceExecutionId("b")) { stubController(SequenceExecutionId("b")) }
        }
        owner.release(SequenceExecutionId("b"))
        assertSame(first, owner.activeSession)
        owner.release(SequenceExecutionId("a"))
        assertNull(owner.activeSession)

        val second = owner.acquire(SequenceExecutionId("b")) { stubController(SequenceExecutionId("b")) }
        owner.release(SequenceExecutionId("a"))
        assertSame(second, owner.activeSession)
        owner.release(second)
        assertNull(owner.activeSession)
    }

    @Test
    fun runtimeAddCatalogIsPagedAndDispatchesTheLaterTemplateIdentity() =
        runBlocking {
            val expanded = expanded()
            val firstPage = (0 until 50).map { catalogItem("item-$it") }
            val later = catalogItem("later")
            val after = mutableListOf<ReusableActivityCatalogItem?>()
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    expanded.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(expanded) },
                    { commands += it },
                    {},
                    MutableStateFlow(0L),
                    { null },
                    { cursor ->
                        after += cursor
                        if (cursor == null) firstPage else listOf(later)
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitLoaded()

            controller.loadRuntimeAddCatalog()
            withTimeout(1_000) { controller.state.first { it.catalog?.size == 50 && it.catalogCanLoadMore } }
            assertEquals(listOf<ReusableActivityCatalogItem?>(null), after)

            controller.loadMoreRuntimeAddCatalog()
            withTimeout(1_000) { controller.state.first { it.catalog?.size == 51 && !it.catalogCanLoadMore } }
            assertEquals(firstPage.last(), after.last())

            controller.runtimeAddTemplate(later.id, RuntimeInsertionPlacement.TO_END)
            withTimeout(1_000) { controller.state.first { commands.size == 1 && !it.commandInFlight } }
            assertEquals(
                ActivityEntrySource.Template(later.id),
                (commands.single() as ExpandedSequenceCommand.RuntimeAdd).source,
            )
            controller.close()
        }

    @Test
    fun durableCommandsAreSerializedAndCoordinationFailureNeverRepeatsTheWriter() =
        runBlocking {
            val expanded = expanded()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var activeWriters = 0
            var maxActiveWriters = 0
            var writes = 0
            var coordinationCalls = 0
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    expanded.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(expanded) },
                    {
                        writes++
                        activeWriters++
                        maxActiveWriters = maxOf(maxActiveWriters, activeWriters)
                        entered.complete(Unit)
                        release.await()
                        activeWriters--
                    },
                    {
                        coordinationCalls++
                        error("platform")
                    },
                    MutableStateFlow(0L),
                    { null },
                    { _ -> emptyList() },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitLoaded()

            controller.pause()
            controller.pause()
            withTimeout(1_000) { entered.await() }
            assertEquals(1, writes)
            assertEquals(1, maxActiveWriters)
            release.complete(Unit)
            withTimeout(1_000) { controller.state.first { coordinationCalls == 2 && !it.commandInFlight } }

            assertEquals(2, writes)
            assertEquals(1, maxActiveWriters)
            assertEquals(2, coordinationCalls)
            assertInstanceOf(ExpandedSequenceFailure.Coordination::class.java, controller.state.value.commandFailure)
            controller.close()
        }

    @Test
    fun timedDraftHydratesCanonicalZeroAndMissingAndRetainsExactFieldsAfterConfirmedSave() =
        runBlocking {
            val initial = expanded(mode = TimeTrackingMode.TIMER, withAllValueTypes = true)
            val row = initial.occurrences.first()
            val number = row.activity.fields.first()
            val child = row.childExecution!!.copy(values = listOf(NumberExecutionValue(number.id, 0)))
            var canonical =
                ExpandedLiveSequenceProjector.project(
                    initial.runtime.copy(currentChild = child),
                    listOf(child),
                )
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val committed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    initial.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(canonical) },
                    { command ->
                        commands += command
                        committed.await()
                        val save = command as ExpandedSequenceCommand.SaveValues
                        val saved = child.copy(values = save.values.mapNotNull { it.value })
                        canonical =
                            ExpandedLiveSequenceProjector.project(
                                initial.runtime.copy(currentChild = saved),
                                listOf(saved),
                            )
                    },
                    {},
                    MutableStateFlow(0L),
                    { null },
                    { emptyList() },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitLoaded()
            val hydrated = controller.state.value.currentValueDraft!!
            assertEquals(NumberExecutionValue(number.id, 0), hydrated.values[number.id])
            assertTrue(
                row.activity.fields.drop(1).all {
                    hydrated.values.containsKey(it.id) &&
                        hydrated.values[it.id] == null
                },
            )
            controller.editNumber(number.id, "2")
            assertTrue(commands.isEmpty())
            assertEquals(
                NumberExecutionValue(number.id, 0),
                canonical.occurrences
                    .first()
                    .childExecution!!
                    .values
                    .single(),
            )
            controller.saveCurrentValues()
            withTimeout(1_000) { controller.state.first { it.commandInFlight } }
            val retained = controller.state.value.currentValueDraft!!
            committed.complete(Unit)
            withTimeout(1_000) { controller.state.first { !it.commandInFlight } }
            controller.refresh()
            withTimeout(1_000) { controller.state.first { it.sequence == canonical } }
            assertSame(retained, controller.state.value.currentValueDraft)
            val save = commands.single() as ExpandedSequenceCommand.SaveValues
            assertEquals(row.occurrence.id, save.occurrenceId)
            assertEquals(row.activity.fields.map { it.id }, save.values.map { it.snapshotFieldId })
            assertEquals(NumberExecutionValue(number.id, 2_000), save.values.first().value)
            assertTrue(save.values.drop(1).all { it.value == null })
            assertEquals(row.activity, canonical.occurrences.first().activity)
            controller.close()
        }

    @Test
    fun noLiveDraftKeepsMissingDistinctFromZeroAcrossRetainedRouteSession() =
        runBlocking {
            val expanded = expanded(mode = TimeTrackingMode.NO_LIVE_TRACKING, withValue = true)
            val controller = controller(this, expanded, mutableListOf())
            controller.awaitLoaded()
            val fieldId = ActivitySnapshotFieldId("first-value")
            assertEquals(
                5L,
                (
                    controller.state.value.currentValueDraft
                        ?.values
                        ?.get(fieldId) as NumberExecutionValue
                ).scaledValue,
            )

            controller.editNumber(fieldId, "0")
            assertEquals(
                0L,
                (
                    controller.state.value.currentValueDraft
                        ?.values
                        ?.get(fieldId) as NumberExecutionValue
                ).scaledValue,
            )
            val owner = ExpandedLiveSequenceRouteSessionOwner()
            val retained = owner.acquire(expanded.runtime.execution.id) { controller }
            assertSame(retained, owner.acquire(expanded.runtime.execution.id) { error("must retain") })
            assertEquals(
                0L,
                (
                    retained.controller.state.value.currentValueDraft
                        ?.values
                        ?.get(fieldId) as NumberExecutionValue
                ).scaledValue,
            )
            retained.controller.markMissing(fieldId)
            assertTrue(
                retained.controller.state.value.currentValueDraft
                    ?.values
                    ?.containsKey(fieldId) == true,
            )
            assertEquals(
                null,
                retained.controller.state.value.currentValueDraft
                    ?.values
                    ?.get(fieldId),
            )
            owner.release(retained)
        }

    @Test
    fun currentValueDraftRejectsAllWritersWhileDurableCommandIsInFlight() =
        runBlocking {
            val expanded = expanded(withAllValueTypes = true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    expanded.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(expanded) },
                    {
                        entered.complete(Unit)
                        release.await()
                    },
                    {},
                    MutableStateFlow(0L),
                    { null },
                    { _ -> emptyList() },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitLoaded()
            val number = ActivitySnapshotFieldId("first-number")
            val text = ActivitySnapshotFieldId("first-text")
            val category = ActivitySnapshotFieldId("first-category")
            controller.editNumber(number, "7")
            val submitted = requireNotNull(controller.state.value.currentValueDraft)

            controller.completeCurrent()
            withTimeout(1_000) { entered.await() }
            controller.editNumber(number, "8")
            controller.editText(text, "after-submit")
            controller.editCategory(
                category,
                com.alexandr5476.lifetracing.domain
                    .ActivitySnapshotCategoryOptionId("first-option"),
            )
            controller.markMissing(number)
            assertEquals(submitted, controller.state.value.currentValueDraft)

            release.complete(Unit)
            withTimeout(1_000) {
                controller.state.first { !it.commandInFlight }
            }
            assertEquals(submitted, controller.state.value.currentValueDraft)
            controller.close()
        }

    @Test
    fun secondSaveValuesIsRejectedWhileTheFirstValueCommandOwnsTheReservation() =
        runBlocking {
            val expanded = expanded(withAllValueTypes = true)
            val writerEntered = CompletableDeferred<Unit>()
            val releaseWriter = CompletableDeferred<Unit>()
            val writerCompleted = CompletableDeferred<Unit>()
            val coordinationEntered = CompletableDeferred<Unit>()
            val releaseCoordination = CompletableDeferred<Unit>()
            val commands = mutableListOf<ExpandedSequenceCommand>()
            var captures = 0
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    expanded.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(expanded) },
                    {
                        commands += it
                        writerEntered.complete(Unit)
                        releaseWriter.await()
                        writerCompleted.complete(Unit)
                    },
                    {
                        coordinationEntered.complete(Unit)
                        releaseCoordination.await()
                    },
                    MutableStateFlow(0L),
                    { null },
                    { _ -> emptyList() },
                    { Instant.EPOCH.plusSeconds(1) },
                    onCurrentValueCommandCaptured = { captures++ },
                )
            controller.awaitLoaded()
            val number = ActivitySnapshotFieldId("first-number")
            val text = ActivitySnapshotFieldId("first-text")
            val category = ActivitySnapshotFieldId("first-category")
            val option =
                com.alexandr5476.lifetracing.domain
                    .ActivitySnapshotCategoryOptionId("first-option")
            controller.editNumber(number, "7")
            val submitted = requireNotNull(controller.state.value.currentValueDraft)

            controller.saveCurrentValues()
            writerEntered.await()
            controller.saveCurrentValues()

            assertEquals(1, captures)
            assertTrue(controller.state.value.commandInFlight)
            assertInstanceOf(ExpandedSequenceFailure.Rejected::class.java, controller.state.value.commandFailure)

            controller.editNumber(number, "8")
            controller.editText(text, "after-submit")
            controller.editCategory(category, option)
            controller.markMissing(number)
            assertEquals(submitted, controller.state.value.currentValueDraft)

            releaseWriter.complete(Unit)
            writerCompleted.await()
            coordinationEntered.await()
            controller.editNumber(number, "8")
            controller.editText(text, "after-submit")
            controller.editCategory(category, option)
            controller.markMissing(number)
            assertEquals(submitted, controller.state.value.currentValueDraft)

            releaseCoordination.complete(Unit)
            withTimeout(1_000) { controller.state.first { !it.commandInFlight } }

            assertEquals(1, commands.size)
            assertEquals(submitted, controller.state.value.currentValueDraft)
            val command = commands.single() as ExpandedSequenceCommand.SaveValues
            val current = expanded.occurrences.single { it.occurrence.id == submitted.occurrenceId }
            assertEquals(submitted.overrides(current.activity.fields), command.values)
            controller.close()
        }

    @Test
    fun queuedPauseKeepsTheValueGateOwnedWhenSaveValuesIsRejectedBeforeCapture() =
        runBlocking {
            val expanded = expanded(withAllValueTypes = true)
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val scope = CoroutineScope(dispatcher)
            val dispatcherBlocked = CompletableDeferred<Unit>()
            val releaseDispatcher = CountDownLatch(1)
            val pauseEntered = CompletableDeferred<Unit>()
            val releasePause = CompletableDeferred<Unit>()
            val coordinationEntered = CompletableDeferred<Unit>()
            val releaseCoordination = CompletableDeferred<Unit>()
            val commands = mutableListOf<ExpandedSequenceCommand>()
            var captures = 0
            val controller =
                ExpandedLiveSequenceController(
                    scope,
                    expanded.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(expanded) },
                    {
                        commands += it
                        pauseEntered.complete(Unit)
                        releasePause.await()
                    },
                    {
                        coordinationEntered.complete(Unit)
                        releaseCoordination.await()
                    },
                    MutableStateFlow(0L),
                    { null },
                    { _ -> emptyList() },
                    { Instant.EPOCH.plusSeconds(1) },
                    onCurrentValueCommandCaptured = { captures++ },
                )
            try {
                controller.awaitLoaded()
                val number = ActivitySnapshotFieldId("first-number")
                val text = ActivitySnapshotFieldId("first-text")
                val category = ActivitySnapshotFieldId("first-category")
                val option =
                    com.alexandr5476.lifetracing.domain
                        .ActivitySnapshotCategoryOptionId("first-option")
                controller.editNumber(number, "7")
                val submitted = requireNotNull(controller.state.value.currentValueDraft)

                scope.launch {
                    dispatcherBlocked.complete(Unit)
                    // Block the worker thread; a suspending await would let queued commands run.
                    assertTrue(releaseDispatcher.await(10, TimeUnit.SECONDS))
                }
                withTimeout(1_000) { dispatcherBlocked.await() }
                controller.pause()
                controller.saveCurrentValues()

                assertFalse(pauseEntered.isCompleted)
                assertEquals(0, captures)
                assertTrue(controller.state.value.commandInFlight)
                assertInstanceOf(ExpandedSequenceFailure.Rejected::class.java, controller.state.value.commandFailure)
                controller.editNumber(number, "8")
                controller.editText(text, "after-submit")
                controller.editCategory(category, option)
                controller.markMissing(number)
                assertEquals(submitted, controller.state.value.currentValueDraft)

                releaseDispatcher.countDown()
                withTimeout(1_000) { pauseEntered.await() }
                releasePause.complete(Unit)
                withTimeout(1_000) { coordinationEntered.await() }
                assertTrue(controller.state.value.commandInFlight)
                controller.editNumber(number, "8")
                controller.editText(text, "after-submit")
                controller.editCategory(category, option)
                controller.markMissing(number)
                assertEquals(submitted, controller.state.value.currentValueDraft)

                releaseCoordination.complete(Unit)
                withTimeout(1_000) { controller.state.first { !it.commandInFlight } }

                assertEquals(
                    listOf(ExpandedSequenceCommand.Pause(expanded.runtime.execution.id, Instant.EPOCH.plusSeconds(1))),
                    commands,
                )
                assertEquals(submitted, controller.state.value.currentValueDraft)
            } finally {
                releaseDispatcher.countDown()
                releasePause.complete(Unit)
                releaseCoordination.complete(Unit)
                controller.close()
                dispatcher.close()
            }
        }

    @Test
    fun pauseCapturesPreDeadlineTimeBeforeBlockedWorkerRuns() =
        runBlocking {
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val scope = CoroutineScope(dispatcher)
            val dispatcherBlocked = CompletableDeferred<Unit>()
            val releaseDispatcher = CountDownLatch(1)
            val writerEntered = CompletableDeferred<ExpandedSequenceCommand>()
            var now = Instant.EPOCH.plusSeconds(59)
            val expanded = expanded(mode = TimeTrackingMode.TIMER)
            val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(expanded.runtime)).at
            val controller =
                ExpandedLiveSequenceController(
                    scope,
                    expanded.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(expanded) },
                    { writerEntered.complete(it) },
                    {},
                    MutableStateFlow(0L),
                    { null },
                    { _ -> emptyList() },
                    { now },
                )
            try {
                controller.awaitLoaded()
                scope.launch {
                    dispatcherBlocked.complete(Unit)
                    // Block the worker thread; a suspending await would let queued commands run.
                    assertTrue(releaseDispatcher.await(10, TimeUnit.SECONDS))
                }
                withTimeout(1_000) { dispatcherBlocked.await() }

                controller.pause()
                assertFalse(writerEntered.isCompleted)
                now = deadline.plusSeconds(1)
                releaseDispatcher.countDown()

                assertEquals(
                    ExpandedSequenceCommand.Pause(expanded.runtime.execution.id, deadline.minusSeconds(1)),
                    withTimeout(1_000) { writerEntered.await() },
                )
            } finally {
                releaseDispatcher.countDown()
                controller.close()
                dispatcher.close()
            }
        }

    @Test
    fun currentValueEditCannotSlipBetweenCommandCaptureAndDurableReservation() =
        runBlocking {
            val expanded = expanded(withValue = true)
            val captured = CountDownLatch(1)
            val releaseCapture = CountDownLatch(1)
            val editFinished = CountDownLatch(1)
            val commands = mutableListOf<ExpandedSequenceCommand>()
            val controller =
                ExpandedLiveSequenceController(
                    this,
                    expanded.runtime.execution.id,
                    { ExpandedLiveSequenceRead.Active(expanded) },
                    { commands += it },
                    {},
                    MutableStateFlow(0L),
                    { null },
                    { _ -> emptyList() },
                    { Instant.EPOCH.plusSeconds(1) },
                    onCurrentValueCommandCaptured = {
                        captured.countDown()
                        releaseCapture.await()
                    },
                )
            controller.awaitLoaded()
            val field = ActivitySnapshotFieldId("first-value")
            controller.editNumber(field, "7")
            val submitted = requireNotNull(controller.state.value.currentValueDraft)

            Thread { controller.completeCurrent() }.apply { start() }
            assertTrue(captured.await(1, TimeUnit.SECONDS))
            Thread {
                controller.editNumber(field, "8")
                editFinished.countDown()
            }.apply { start() }
            assertFalse(editFinished.await(100, TimeUnit.MILLISECONDS))
            releaseCapture.countDown()
            assertTrue(editFinished.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) { controller.state.first { !it.commandInFlight && commands.size == 1 } }

            assertEquals(submitted, controller.state.value.currentValueDraft)
            val command = commands.single() as ExpandedSequenceCommand.Complete
            val current = expanded.occurrences.single { it.occurrence.id == submitted.occurrenceId }
            assertEquals(
                submitted.overrides(current.activity.fields),
                command.values,
            )
            controller.close()
        }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        initial: com.alexandr5476.lifetracing.domain.ExpandedLiveSequence,
        commands: MutableList<ExpandedSequenceCommand>,
        semantic: MutableStateFlow<Long> = MutableStateFlow(0L),
        read: () -> com.alexandr5476.lifetracing.domain.ExpandedLiveSequence = { initial },
    ) = ExpandedLiveSequenceController(
        scope,
        initial.runtime.execution.id,
        { ExpandedLiveSequenceRead.Active(read()) },
        { commands += it },
        { semantic.value++ },
        semantic,
        { null },
        { _ -> emptyList() },
        { Instant.EPOCH.plusSeconds(1) },
    )

    private fun stubController(id: SequenceExecutionId) =
        ExpandedLiveSequenceController(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            id,
            { ExpandedLiveSequenceRead.StaleOrInactive },
            {},
            {},
            MutableStateFlow(0L),
            { null },
            { _ -> emptyList() },
            { Instant.EPOCH },
        )

    private fun catalogItem(id: String) =
        ReusableActivityCatalogItem(
            ActivityTemplateId(id),
            id,
            TimeTrackingMode.STOPWATCH,
            null,
            null,
            null,
            null,
            null,
        )

    private suspend fun ExpandedLiveSequenceController.awaitLoaded() {
        withTimeout(1_000) { state.first { it.sequence != null && !it.loading } }
    }

    private fun expanded(
        confirmJump: Boolean = false,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        withValue: Boolean = false,
        withAllValueTypes: Boolean = false,
    ): com.alexandr5476.lifetracing.domain.ExpandedLiveSequence {
        var occurrence = 0
        var child = 0
        var interval = 0
        val first = activity("first", mode, withValue, withAllValueTypes)
        val second = activity("second")
        val activities = listOf(first, second).associateBy(ActivityConfigSnapshot::id)
        val snapshot =
            SequenceConfigSnapshot(
                SequenceSnapshotId("snapshot"),
                "Sequence A",
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                SequenceSnapshotSettings(
                    autoAdvance = false,
                    sequenceStartCountdown = Duration.ZERO,
                    beforeEachStepCountdown = Duration.ZERO,
                    transitionSound = true,
                    transitionVibration = true,
                    keepScreenAwake = false,
                    confirmJump = confirmJump,
                    confirmEarlyEnd = true,
                    noLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
                ),
                nodes =
                    listOf(
                        SequenceSnapshotActivityStep(SequenceSnapshotNodeId("first"), 0, first.id),
                        SequenceSnapshotActivityStep(SequenceSnapshotNodeId("second"), 1, second.id),
                    ),
            )
        val engine =
            SequenceRuntimeEngine(
                SequenceExecutionFactory(
                    { SequenceExecutionId("a") },
                    RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occurrence-${++occurrence}") },
                ),
                ActivityExecutionFactory { ActivityExecutionId("child-${++child}") },
                { ActivityExecutionPauseId("pause") },
                { SequenceIntervalId("interval-${++interval}") },
                { SequenceOccurrenceId("added-${++occurrence}") },
            )
        val state = engine.start(snapshot, activities, Instant.EPOCH, Instant.EPOCH, ZoneOffset.UTC)
        val runtime =
            ActiveSequenceRuntime(
                ActiveSession(
                    ActiveSessionKind.SEQUENCE,
                    ActiveSessionState.RUNNING,
                    null,
                    state.execution.id,
                    state.execution.updatedAt,
                ),
                state.execution,
                snapshot,
                activities,
                state.currentChild,
                null,
            )
        return ExpandedLiveSequenceProjector.project(runtime, state.children.values.toList())
    }

    private fun activity(
        id: String,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        withValue: Boolean = false,
        withAllValueTypes: Boolean = false,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(id),
        id,
        null,
        mode,
        if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(60) else null,
        null,
        null,
        null,
        false,
        Instant.EPOCH,
        fields =
            if (withAllValueTypes) {
                listOf(
                    ActivitySnapshotField(
                        ActivitySnapshotFieldId("$id-number"),
                        null,
                        0,
                        "Number",
                        type = com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                        displayPrecision = 0,
                        defaultNumberScaled = 5,
                    ),
                    ActivitySnapshotField(
                        ActivitySnapshotFieldId("$id-text"),
                        null,
                        1,
                        "Text",
                        type = com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT,
                        defaultText = "before",
                    ),
                    ActivitySnapshotField(
                        ActivitySnapshotFieldId("$id-category"),
                        null,
                        2,
                        "Category",
                        type = com.alexandr5476.lifetracing.domain.CustomFieldType.CATEGORY,
                        defaultCategoryOptionId =
                            com.alexandr5476.lifetracing.domain
                                .ActivitySnapshotCategoryOptionId("$id-option"),
                        categoryOptions =
                            listOf(
                                com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption(
                                    com.alexandr5476.lifetracing.domain
                                        .ActivitySnapshotCategoryOptionId("$id-option"),
                                    null,
                                    0,
                                    "Option",
                                ),
                            ),
                    ),
                )
            } else if (withValue) {
                listOf(
                    ActivitySnapshotField(
                        ActivitySnapshotFieldId("$id-value"),
                        null,
                        0,
                        "Value",
                        type = com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                        displayPrecision = 0,
                        defaultNumberScaled = 5,
                    ),
                )
            } else {
                emptyList()
            },
    )
}
