package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSourceStatus
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateAuthoringState
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SequenceStepSourceActionsTest {
    @Test
    @Suppress("LongMethod")
    fun rejectedNumberDefaultBlocksCleanCommittedSourceAndSaveAdmission() =
        runBlocking {
            val initial =
                canonical(true).let { state ->
                    state.copy(
                        activitySnapshots =
                            state.activitySnapshots.mapValues { (_, snapshot) ->
                                snapshot.copy(
                                    fields =
                                        listOf(
                                            com.alexandr5476.lifetracing.domain.ActivitySnapshotField(
                                                com.alexandr5476.lifetracing.domain
                                                    .ActivitySnapshotFieldId("number"),
                                                null,
                                                0,
                                                "Same",
                                                type = com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                                                displayPrecision = 2,
                                                defaultNumberScaled = -1250,
                                            ),
                                        ),
                                )
                            },
                    )
                }
            val fixture = Fixture(this, initial = initial)
            fixture.ready()
            val editor = fixture.editor
            val before = editor.state.value.readyDraft()!!
            val owner = before.findStep(DraftIdentity.Existing(STEP))!!
            val field = owner.configuration()!!.fields.single()
            val source = fixture.actions()
            assertTrue(source.available(SequenceStepSourceOperation.UpdateSource))
            val actions =
                SequenceStepActions(
                    editor,
                    SequenceTemplateEditorActions(editor, editor.state.value.generation),
                    owner.identity,
                )
            assertFalse(actions.numberDefault(field, "-1.234"))
            assertEquals(before, editor.state.value.readyDraft())
            assertTrue(editor.state.value.hasInvalidInput(before))
            assertFalse(editor.state.value.sourceActionsAllowed())
            SequenceStepSourceOperation.entries.forEach { assertFalse(source.available(it)) }
            SequenceStepSourceOperation.entries.forEach(source::invoke)
            editor.updateStepFromSource(STEP)
            editor.updateSourceTemplate(STEP)
            editor.saveStepAsNewTemplate(STEP)
            editor.save()
            assertEquals(0, fixture.attempts)
            assertEquals(0, fixture.saves)
            var exited = false
            editor.requestBack { exited = true }
            assertFalse(exited)
            assertTrue(editor.state.value.discardConfirmationVisible)
            editor.dismissDiscard()
            actions.numberDefault(field, "-1.25")
            assertTrue(editor.state.value.sourceActionsAllowed())
            SequenceStepSourceOperation.entries.forEach { assertFalse(source.available(it)) }
            fixture.release.complete(Unit)
            val current = fixture.actions()
            current.invoke(SequenceStepSourceOperation.UpdateSource)
            current.invoke(SequenceStepSourceOperation.UpdateSource)
            withTimeout(2000) { editor.state.first { it.appliedGeneration == 1L } }
            assertEquals(1, fixture.attempts)
            assertEquals(1, fixture.commits)
            editor.close()
        }

    @Test
    @Suppress("LongMethod")
    fun rejectedPrecisionBlocksCleanCommittedSourceAndSaveAdmission() =
        runBlocking {
            val initial =
                canonical(true).let { state ->
                    state.copy(
                        activitySnapshots =
                            state.activitySnapshots.mapValues { (_, snapshot) ->
                                snapshot.copy(
                                    fields =
                                        listOf(
                                            com.alexandr5476.lifetracing.domain.ActivitySnapshotField(
                                                com.alexandr5476.lifetracing.domain
                                                    .ActivitySnapshotFieldId("number"),
                                                null,
                                                0,
                                                "Same",
                                                type = com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                                                displayPrecision = 2,
                                                defaultNumberScaled = -1250,
                                            ),
                                        ),
                                )
                            },
                    )
                }
            val fixture = Fixture(this, initial = initial)
            fixture.ready()
            val editor = fixture.editor
            val before = editor.state.value.readyDraft()!!
            val owner = before.findStep(DraftIdentity.Existing(STEP))!!
            val field = owner.configuration()!!.fields.single()
            val source = fixture.actions()
            assertTrue(source.available(SequenceStepSourceOperation.UpdateSource))
            val actions =
                SequenceStepActions(
                    editor,
                    SequenceTemplateEditorActions(editor, editor.state.value.generation),
                    owner.identity,
                )
            actions.numberPrecision(field, "9")
            assertEquals(before, editor.state.value.readyDraft())
            assertTrue(editor.state.value.hasInvalidInput(before))
            assertFalse(editor.state.value.sourceActionsAllowed())
            SequenceStepSourceOperation.entries.forEach { assertFalse(source.available(it)) }
            SequenceStepSourceOperation.entries.forEach(source::invoke)
            editor.updateStepFromSource(STEP)
            editor.updateSourceTemplate(STEP)
            editor.saveStepAsNewTemplate(STEP)
            editor.save()
            assertEquals(0, fixture.attempts)
            assertEquals(0, fixture.saves)
            var exited = false
            editor.requestBack { exited = true }
            assertFalse(exited)
            assertTrue(editor.state.value.discardConfirmationVisible)
            editor.dismissDiscard()
            actions.numberPrecision(field, "2")
            assertTrue(editor.state.value.sourceActionsAllowed())
            SequenceStepSourceOperation.entries.forEach { assertFalse(source.available(it)) }
            fixture.release.complete(Unit)
            val current = fixture.actions()
            current.invoke(SequenceStepSourceOperation.UpdateSource)
            current.invoke(SequenceStepSourceOperation.UpdateSource)
            withTimeout(2000) { editor.state.first { it.appliedGeneration == 1L } }
            assertEquals(1, fixture.attempts)
            assertEquals(1, fixture.commits)
            editor.close()
        }

    @Test
    fun activeArchivedMissingAndUnlinkedAvailabilityUsesCurrentCommittedIdentity() =
        runBlocking {
            for (status in listOf("active", "archived", "missing", "unlinked")) {
                val fixture = Fixture(this, status)
                fixture.ready()
                val actions = fixture.actions()
                assertEquals(status == "active", actions.available(SequenceStepSourceOperation.UpdateSource))
                assertEquals(status == "active", actions.available(SequenceStepSourceOperation.UpdateFromSource))
                assertTrue(actions.available(SequenceStepSourceOperation.SaveAsNew))
                if (status != "active") {
                    actions.invoke(SequenceStepSourceOperation.UpdateSource)
                    actions.invoke(SequenceStepSourceOperation.UpdateFromSource)
                    assertEquals(0, fixture.attempts)
                }
                if (status == "archived") {
                    assertEquals(
                        SequenceEditorStepSource.Archived(1),
                        fixture.editor.state.value.sourceStatuses[SOURCE],
                    )
                }
                fixture.editor.close()
            }
        }

    @Test
    @Suppress("LongMethod") // Each independently seeded transition must revoke the same scalar token.
    fun sourceLeaseCannotReviveAfterDraftRemovalMovementInvalidInputModeReloadOrRouteRetirement() =
        runBlocking {
            for (change in listOf(
                "remove",
                "move",
                "input",
                "mode",
                "reload",
                "archive",
                "relink",
                "hidden",
                "detach",
            )) {
                val fixture = Fixture(this)
                fixture.ready()
                val editor = fixture.editor
                val lease = SequenceTemplateEditorActions(editor, editor.state.value.generation)
                val captured = SequenceStepSourceActions(editor, lease, STEP)
                val original = editor.state.value.readyDraft()!!
                when (change) {
                    "remove" -> {
                        editor.updateDraft { it.copy(nodes = it.nodes.drop(1)) }
                        editor.updateDraft { original }
                    }
                    "move" -> {
                        editor.updateDraft {
                            val step = (it.nodes.first() as SequenceNodeDraft.Step).value
                            val repeat = (it.nodes.last() as SequenceNodeDraft.Repeat).value
                            it.copy(
                                nodes =
                                    listOf(
                                        SequenceNodeDraft.Repeat(
                                            repeat.copy(
                                                position = 0,
                                                children =
                                                    repeat.children + step,
                                            ),
                                        ),
                                    ),
                            )
                        }
                        editor.updateDraft { original }
                    }
                    "input" -> {
                        val key = SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN
                        editor.updateNumberInput(key, "invalid", 0, 0) { error("Invalid") }
                        editor.clearNumberInput(key)
                    }
                    "mode" -> {
                        editor.enterManipulation(DraftIdentity.Existing(STEP))
                        editor.discardManipulation()
                    }
                    "reload", "archive", "relink" -> {
                        if (change == "archive") fixture.status = "archived"
                        if (change == "relink") {
                            fixture.canonical =
                                fixture.canonical.copy(
                                    activitySnapshots =
                                        fixture.canonical.activitySnapshots.mapValues { (_, value) ->
                                            value.copy(sourceTemplateId = OTHER_SOURCE)
                                        },
                                )
                        }
                        val generation = editor.state.value.generation
                        editor.retry()
                        withTimeout(2_000) {
                            editor.state.first {
                                it.generation > generation &&
                                    it.load is SequenceTemplateEditorLoad.Ready
                            }
                        }
                    }
                    "hidden" -> lease.visible = false
                    "detach" -> lease.attached = false
                }
                SequenceStepSourceOperation.entries.forEach(captured::invoke)
                assertEquals(0, fixture.attempts, change)
                assertEquals(0, editor.state.value.appliedGeneration)
                assertEquals(1, fixture.canonical.sequence.revision)
                editor.close()
            }
        }

    @Test
    fun everySourceWriterRejectsDuplicateCallbacksAndFailedAttemptRequiresAFreshLease() =
        runBlocking {
            for (operation in SequenceStepSourceOperation.entries) {
                for (failure in listOf(
                    "ordinary writer failure",
                    "ActivityTemplate revision changed concurrently",
                    "Source is archived",
                )) {
                    val fixture = Fixture(this)
                    fixture.ready()
                    fixture.failure = failure
                    val captured = fixture.actions()
                    captured.invoke(operation)
                    fixture.started.await()
                    captured.invoke(operation)
                    fixture.actions().invoke(operation)
                    assertEquals(1, fixture.attempts)
                    fixture.release.complete(Unit)
                    val state =
                        withTimeout(
                            2_000,
                        ) { fixture.editor.state.first { it.save is SequenceTemplateEditorSave.Failure } }
                    assertEquals(
                        failure.contains("revision"),
                        (state.save as SequenceTemplateEditorSave.Failure).isConflict,
                    )
                    assertFalse((state.save as SequenceTemplateEditorSave.Failure).committedAwaitingReload)
                    captured.invoke(operation)
                    assertEquals(1, fixture.attempts)
                    assertEquals(0, fixture.commits)
                    assertEquals(0, state.appliedGeneration)
                    assertEquals(1, fixture.canonical.sequence.revision)
                    fixture.failure = null
                    fixture.actions().invoke(operation)
                    withTimeout(2_000) { fixture.editor.state.first { it.appliedGeneration == 1L } }
                    assertEquals(2, fixture.attempts)
                    assertEquals(1, fixture.commits)
                    assertEquals(listOf(STEP, STEP), fixture.targets)
                    fixture.editor.close()
                }
            }
        }

    @Test
    fun pendingLocalTemplateAndDuplicateStepsNeverDispatchAnySourceWriter() =
        runBlocking {
            for (kind in listOf("local", "template", "duplicate", "removed")) {
                val fixture = Fixture(this)
                fixture.ready()
                val editor = fixture.editor
                val step =
                    editor.state.value
                        .readyDraft()!!
                        .findStep(DraftIdentity.Existing(STEP))!!
                val existing = step.activity as com.alexandr5476.lifetracing.domain.StepActivityDraft.Existing
                editor.updateDraft {
                    val pending =
                        when (kind) {
                            "local" ->
                                com.alexandr5476.lifetracing.domain.StepActivityDraft
                                    .Local(existing.configuration)
                            "template" ->
                                com.alexandr5476.lifetracing.domain.StepActivityDraft
                                    .FromTemplate(SOURCE)
                            else ->
                                com.alexandr5476.lifetracing.domain.StepActivityDraft.Duplicate(
                                    STEP,
                                    existing.configuration,
                                    SOURCE,
                                    1,
                                    null,
                                    false,
                                )
                        }
                    it.copy(
                        nodes =
                            if (kind ==
                                "removed"
                            ) {
                                emptyList()
                            } else {
                                listOf(
                                    SequenceNodeDraft.Step(
                                        step.copy(identity = DraftIdentity.New(kind), activity = pending),
                                    ),
                                )
                            },
                    )
                }
                SequenceStepSourceOperation.entries.forEach(fixture.actions()::invoke)
                editor.updateSourceTemplate(STEP)
                editor.updateStepFromSource(STEP)
                editor.saveStepAsNewTemplate(STEP)
                assertEquals(0, fixture.attempts, kind)
                editor.close()
            }
        }

    @Test
    fun sourceStatusFanOutIsDistinctSharedAndNeverReloadedByPresentationInspection() =
        runBlocking {
            val base = canonical(true)
            val original = base.activitySnapshots.values.first()
            val snapshots =
                List(1_102) { index ->
                    original.copy(
                        id = ActivitySnapshotId("snapshot-$index"),
                        sourceTemplateId =
                            if (index % 1_001 ==
                                0
                            ) {
                                SOURCE
                            } else {
                                ActivityTemplateId("source-${index % 1_001}")
                            },
                    )
                }
            val sequence =
                base.sequence.copy(
                    nodes =
                        snapshots.mapIndexed { index, snapshot ->
                            ActivityStep(if (index == 0) STEP else SequenceNodeId("step-$index"), index, snapshot.id)
                        },
                )
            val fixture =
                Fixture(this, initial = SequenceTemplateAuthoringState(sequence, snapshots.associateBy { it.id }))
            fixture.ready()
            assertEquals(1, fixture.sourceReads)
            assertEquals(1_001, fixture.requestedSources.size)
            assertEquals(1_001, fixture.requestedSources.toSet().size)
            assertEquals(1_102, fixture.editor.state.value.stepSourceIds.size)
            val before = fixture.editor.state.value
            repeat(100) { SequenceStepSourceOperation.entries.forEach { assertTrue(fixture.actions().available(it)) } }
            assertEquals(before, fixture.editor.state.value)
            assertEquals(1, fixture.sourceReads)
            assertEquals(0, fixture.attempts)
            fixture.editor.close()
        }

    private class Fixture(
        scope: CoroutineScope,
        var status: String = "active",
        initial: SequenceTemplateAuthoringState? = null,
    ) {
        var canonical = initial ?: canonical(status != "unlinked")
        var sourceReads = 0
        var requestedSources: List<ActivityTemplateId> = emptyList()
        var attempts = 0
        var commits = 0
        var saves = 0
        val targets = mutableListOf<SequenceNodeId>()
        var failure: String? = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val editor =
            SequenceTemplateEditorController(
                scope,
                SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                { canonical },
                { error("No picker catalog read") },
                { _, _, _ -> error("No create") },
                { _, _, _, _ ->
                    saves++
                    error("No save")
                },
                { Instant.EPOCH.plusSeconds(10) },
                loadSourceStatuses = { ids ->
                    sourceReads++
                    requestedSources = ids.toList()
                    if (status ==
                        "missing"
                    ) {
                        emptyMap()
                    } else {
                        ids.associateWith {
                            ActivityTemplateSourceStatus(
                                it,
                                1,
                                status == "archived",
                            )
                        }
                    }
                },
                updateFromSource = { _, step, _, _ -> write(step) },
                updateSource = { _, step, _, revision, _ ->
                    assertEquals(1, revision)
                    write(step)
                },
                saveAsNewSource = { _, step, _, _ -> write(step) },
            )

        private suspend fun write(step: SequenceNodeId) {
            attempts++
            targets += step
            started.complete(Unit)
            release.await()
            failure?.let { error(it) }
            commits++
            canonical = canonical.copy(sequence = canonical.sequence.copy(revision = canonical.sequence.revision + 1))
        }

        suspend fun ready() {
            withTimeout(2_000) { editor.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
        }

        fun actions() =
            SequenceStepSourceActions(
                editor,
                SequenceTemplateEditorActions(editor, editor.state.value.generation),
                STEP,
            )
    }

    companion object {
        private val STEP = SequenceNodeId("first")
        private val SOURCE = ActivityTemplateId("source")
        private val OTHER_SOURCE = ActivityTemplateId("other-source")

        private fun canonical(linked: Boolean): SequenceTemplateAuthoringState {
            val first = ActivitySnapshotId("first")
            val second = ActivitySnapshotId("second")

            fun snapshot(
                id: ActivitySnapshotId,
                source: ActivityTemplateId?,
            ) = ActivityConfigSnapshot(
                id,
                "Same name",
                null,
                TimeTrackingMode.STOPWATCH,
                null,
                sourceTemplateId = source,
                sourceRevision = source?.let { 1L },
                statisticsSeriesId = null,
                locallyModified = false,
                createdAt = Instant.EPOCH,
            )
            return SequenceTemplateAuthoringState(
                SequenceTemplate(
                    SequenceTemplateId("sequence"),
                    "Sequence",
                    null,
                    StatisticsSeriesId("sequence-series"),
                    revision = 1,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    nodes =
                        listOf(
                            ActivityStep(STEP, 0, first),
                            SequenceRepeatBlock(
                                SequenceNodeId("repeat"),
                                1,
                                2,
                                listOf(ActivityStep(SequenceNodeId("second"), 0, second)),
                            ),
                        ),
                ),
                mapOf(first to snapshot(first, SOURCE.takeIf { linked }), second to snapshot(second, OTHER_SOURCE)),
            )
        }
    }
}
