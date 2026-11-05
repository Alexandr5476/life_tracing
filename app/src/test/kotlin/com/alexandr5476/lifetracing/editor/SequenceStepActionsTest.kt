package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SequenceStepActionsTest {
    @Test
    fun movedStepResolvesCurrentContainerAndRemovedStepCannotEditSameNameSuccessor() =
        runBlocking {
            val controller = controller(this)
            val original = step("original")
            val successor = step("successor")
            val repeat = SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 2, listOf(original))
            controller.updateDraft {
                it.copy(nodes = listOf(SequenceNodeDraft.Repeat(repeat), SequenceNodeDraft.Step(successor)))
            }
            val actions = actions(controller, original)
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(original.copy(position = 0)),
                            SequenceNodeDraft.Step(successor),
                        ),
                )
            }
            actions.activity { it.copy(name = "Moved") }
            assertEquals("Moved", actions.configuration()?.name)
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(successor.copy(position = 0)))) }
            val before = controller.state.value
            actions.activity { it.copy(name = "Obsolete") }
            assertEquals(before, controller.state.value)
            assertEquals("Same name", actions(controller, successor).configuration()?.name)
        }

    @Test
    fun fieldAndOptionActionsResolveIdentityTypeMembershipAndCurrentPrecision() =
        runBlocking {
            val controller = controller(this)
            val owner = step("owner")
            val number = field("number", CustomFieldType.NUMBER)
            val other = field("other", CustomFieldType.NUMBER).copy(position = 1)
            val first = ActivitySnapshotCategoryOptionDraft(DraftIdentity.New("first"), null, 0, "Same")
            val second = first.copy(identity = DraftIdentity.New("second"), position = 1)
            val category = field("category", CustomFieldType.CATEGORY).copy(categoryOptions = listOf(first, second))
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(owner))) }
            val actions = actions(controller, owner)
            actions.activity { it.copy(fields = listOf(number, other, category)) }
            actions.field(number) { it.copy(displayPrecision = 2) }
            actions.numberDefault(number, "-1.25")
            assertEquals(
                -1250L,
                actions
                    .configuration()
                    ?.fields
                    ?.first()
                    ?.defaultNumberScaled,
            )
            actions.removeField(number)
            actions.field(number) { it.copy(defaultNumberScaled = 0) }
            assertNull(
                actions
                    .configuration()
                    ?.fields
                    ?.first()
                    ?.defaultNumberScaled,
            )
            actions.option(category, first) { current, _ ->
                current.copy(categoryOptions = current.categoryOptions.filterNot { it.identity == first.identity })
            }
            val before = controller.state.value
            actions.option(category, first) { current, _ -> current.copy(defaultCategoryOption = second.identity) }
            assertEquals(before, controller.state.value)
            actions.option(
                category,
                second,
            ) { current, option -> current.copy(defaultCategoryOption = option.identity) }
            assertEquals(
                second.identity,
                actions
                    .configuration()
                    ?.fields
                    ?.last()
                    ?.defaultCategoryOption,
            )
            actions.field(other) { it.copy(type = CustomFieldType.TEXT) }
            val replaced = controller.state.value
            actions.numberDefault(other, "0")
            assertEquals(replaced, controller.state.value)
        }

    @Test
    fun replacementDetachesSourceAndObsoleteIdentityCannotOverwriteReplacement() =
        runBlocking {
            val controller = controller(this)
            val owner = step("owner")
            val field =
                field("number", CustomFieldType.NUMBER).copy(
                    identity =
                        DraftIdentity.Existing(
                            com.alexandr5476.lifetracing.domain
                                .ActivitySnapshotFieldId("durable"),
                        ),
                    sourceFieldId =
                        com.alexandr5476.lifetracing.domain
                            .ActivityTemplateFieldId("source"),
                    unit = "kg",
                )
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(owner))) }
            val actions = actions(controller, owner)
            actions.activity { it.copy(fields = listOf(field)) }
            actions.field(field) { it.copy(localNameOverride = "Local") }
            assertEquals(
                field.identity,
                actions
                    .configuration()
                    ?.fields
                    ?.single()
                    ?.identity,
            )
            actions.field(field) { it.withCompatibleUnitReplacement("lb", controller.newKey("replacement")) }
            val replacement = requireNotNull(actions.configuration()?.fields?.single())
            assertNotEquals(field.identity, replacement.identity)
            assertNull(replacement.sourceFieldId)
            assertEquals("Local", replacement.nameAtCreation)
            actions.field(field) { it.copy(localNameOverride = "Old") }
            assertEquals(replacement, actions.configuration()?.fields?.single())
        }

    @Test
    fun collapsedHiddenAndObsoleteLeasesRejectConfigurationAndRawInput() =
        runBlocking {
            val controller = controller(this)
            val owner = step("owner")
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(owner))) }
            var expanded = true
            val lease =
                SequenceTemplateEditorActions(controller, controller.state.value.generation, available = { expanded })
            val actions = SequenceStepActions(controller, lease, owner.identity)
            expanded = false
            val before = controller.state.value
            actions.activity { it.copy(name = "Obsolete") }
            actions.invoke {
                controller.updateNumberInput(SequenceEditorInputKey.stepCountdown(owner.identity), "bad", 0, 0) {}
            }
            assertEquals(before, controller.state.value)
            expanded = true
            lease.visible = false
            actions.activity { it.copy(name = "Hidden") }
            assertEquals(before, controller.state.value)
            lease.visible = true
            lease.attached = false
            actions.activity { it.copy(name = "Disposed") }
            assertEquals(before, controller.state.value)
        }

    @Test
    fun pickerSelectionIsSingleUseAndStaleRepeatNeverFallsBackToTopLevel() =
        runBlocking {
            val controller = controller(this)
            val repeat = SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 2, emptyList())
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Repeat(repeat))) }
            val stale = requireNotNull(controller.offerStepInsertion(repeat.identity))
            controller.updateDraft { it.copy(nodes = emptyList()) }
            controller.chooseStepInsertion(stale, null)
            assertTrue(
                controller.state.value
                    .readyDraft()
                    ?.nodes
                    .orEmpty()
                    .isEmpty(),
            )
            val cancelled = requireNotNull(controller.offerStepInsertion(null))
            controller.dismissStepInsertion(cancelled)
            val current = requireNotNull(controller.offerStepInsertion(null))
            controller.chooseStepInsertion(cancelled, null)
            assertTrue(
                controller.state.value
                    .readyDraft()
                    ?.nodes
                    .orEmpty()
                    .isEmpty(),
            )
            controller.chooseStepInsertion(current, null)
            controller.chooseStepInsertion(current, null)
            val added =
                controller.state.value
                    .readyDraft()
                    ?.nodes
                    .orEmpty()
            assertEquals(1, added.size)
            assertTrue(added.single().identity is DraftIdentity.New)
            assertTrue((added.single() as SequenceNodeDraft.Step).value.activity is StepActivityDraft.Local)
        }

    @Test
    fun obsoletePickerReadCannotPublishIntoReopenedPickerAndSameNamesKeepExactIds() =
        runBlocking {
            val first = CompletableDeferred<List<SequenceEditorActivityChoice>>()
            var reads = 0
            val a = SequenceEditorActivityChoice(ActivityTemplateId("a"), "Same")
            val b = a.copy(id = ActivityTemplateId("b"))
            val controller = controller(this) { if (++reads == 1) first.await() else listOf(a, b) }
            assertEquals(0, reads)
            val old = requireNotNull(controller.offerStepInsertion(null))
            kotlinx.coroutines.yield()
            controller.dismissStepInsertion(old)
            val current = requireNotNull(controller.offerStepInsertion(null))
            withTimeout(2_000) { controller.state.first { it.availableActivities.size == 2 } }
            first.complete(listOf(a))
            kotlinx.coroutines.yield()
            assertEquals(listOf(a, b), controller.state.value.availableActivities)
            controller.chooseStepInsertion(current, b.id)
            val value =
                (
                    controller.state.value
                        .readyDraft()
                        ?.nodes
                        ?.single() as SequenceNodeDraft.Step
                ).value
            assertEquals(StepActivityDraft.FromTemplate(b.id), value.activity)
        }

    @Test
    fun manipulationHistoryRestoresConfigurationOverridesAndInvalidRawInputExactly() =
        runBlocking {
            val controller = controller(this)
            val owner = step("owner")
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(owner))) }
            val baseline = controller.state.value.readyDraft()
            controller.enterManipulation(owner.identity)
            val actions = actions(controller, owner)
            actions.activity { it.copy(shortComment = "Session") }
            actions.step {
                it.copy(
                    overrides = it.overrides.copy(timerEndSound = false, startCountdown = Duration.ZERO),
                )
            }
            actions.invoke {
                controller.updateNumberInput(SequenceEditorInputKey.stepCountdown(owner.identity), "bad", 0, 0) {}
            }
            assertTrue(controller.state.value.hasInvalidInput(requireNotNull(controller.state.value.readyDraft())))
            assertTrue(controller.undoManipulation())
            assertFalse(controller.state.value.hasInvalidInput(requireNotNull(controller.state.value.readyDraft())))
            assertTrue(controller.redoManipulation())
            controller.applyManipulation()
            assertTrue(controller.state.value.save is SequenceTemplateEditorSave.Idle)
            controller.discardManipulation()
            assertEquals(baseline, controller.state.value.readyDraft())
            assertTrue(
                controller.state.value.textInputs
                    .isEmpty(),
            )
        }

    @Test
    fun oneTrackingModeActionRestoresItsRawInputAndConfigurationInOneUndo() =
        runBlocking {
            val controller = controller(this)
            val owner = step("owner")
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(owner))) }
            val initial = actions(controller, owner)
            initial.activity {
                it.copy(
                    timeTrackingMode = TimeTrackingMode.TIMER,
                    timerTarget = Duration.ofSeconds(10),
                )
            }
            val key = SequenceEditorInputKey.timerTarget(owner.identity)
            controller.updateNumberInput(key, "bad", 10, 1) {}
            val baseline = controller.state.value.readyDraft()
            val raw = controller.state.value.textInputs
            controller.enterManipulation(owner.identity)
            val actions = actions(controller, owner)
            actions.invoke {
                controller.clearNumberInput(key)
                actions.activity { it.copy(timeTrackingMode = TimeTrackingMode.STOPWATCH, timerTarget = null) }
            }
            assertEquals(
                1,
                controller.state.value.manipulation
                    ?.operationCount,
            )
            assertTrue(controller.undoManipulation())
            assertEquals(baseline, controller.state.value.readyDraft())
            assertEquals(raw, controller.state.value.textInputs)
            assertEquals(
                owner.identity,
                controller.state.value.manipulation
                    ?.selected,
            )
            assertTrue(controller.redoManipulation())
            assertEquals(TimeTrackingMode.STOPWATCH, actions.configuration()?.timeTrackingMode)
            assertTrue(
                controller.state.value.textInputs
                    .isEmpty(),
            )
            controller.discardManipulation()
            assertEquals(baseline, controller.state.value.readyDraft())
            assertEquals(raw, controller.state.value.textInputs)
        }

    @Test
    fun explicitOverridesPreserveNullFalseZeroAndTheExistingSnapshotConfiguration() =
        runBlocking {
            val controller = controller(this)
            val configuration = requireNotNull((step("source").activity as StepActivityDraft.Local).configuration)
            val owner =
                step("owner").copy(
                    activity =
                        StepActivityDraft.Existing(
                            com.alexandr5476.lifetracing.domain
                                .ActivitySnapshotId("frozen"),
                            configuration,
                            ActivityTemplateId("source"),
                            3,
                            null,
                            false,
                        ),
                )
            val other = step("other").copy(position = 1)
            controller.updateDraft {
                it.copy(
                    nodes = listOf(SequenceNodeDraft.Step(owner), SequenceNodeDraft.Step(other)),
                )
            }
            val actions = actions(controller, owner)
            for (intent in listOf(false, true, null)) {
                actions.step { it.copy(overrides = it.overrides.copy(timerEndSound = intent)) }
                assertEquals(
                    intent,
                    controller.state.value
                        .readyDraft()
                        ?.findStep(owner.identity)
                        ?.overrides
                        ?.timerEndSound,
                )
            }
            for (intent in listOf(Duration.ZERO, Duration.ofSeconds(5), null)) {
                actions.step { it.copy(overrides = it.overrides.copy(startCountdown = intent)) }
                assertEquals(
                    intent,
                    controller.state.value
                        .readyDraft()
                        ?.findStep(owner.identity)
                        ?.overrides
                        ?.startCountdown,
                )
            }
            assertEquals(
                owner.activity,
                controller.state.value
                    .readyDraft()
                    ?.findStep(owner.identity)
                    ?.activity,
            )
            assertEquals(
                other,
                controller.state.value
                    .readyDraft()
                    ?.findStep(other.identity),
            )
        }

    @Test
    fun pendingSourceAndDuplicateConfigurationStayFrozenAndDuplicateRejectsRawInput() =
        runBlocking {
            val controller = controller(this)
            val pending = step("pending").copy(activity = StepActivityDraft.FromTemplate(ActivityTemplateId("source")))
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(pending))) }
            val before = controller.state.value
            actions(controller, pending).activity { it.copy(name = "Forbidden") }
            assertEquals(before, controller.state.value)
            val duplicate =
                step("duplicate").copy(
                    activity =
                        StepActivityDraft.Duplicate(
                            com.alexandr5476.lifetracing.domain
                                .SequenceNodeId("source-step"),
                            (step("original").activity as StepActivityDraft.Local).configuration,
                            null,
                            null,
                            null,
                            false,
                        ),
                )
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(duplicate))) }
            val frozen = controller.state.value
            val actions = actions(controller, duplicate)
            actions.activity { it.copy(name = "Forbidden") }
            actions.step { it.copy(overrides = it.overrides.copy(timerEndSound = false)) }
            actions.invoke {
                controller.updateNumberInput(SequenceEditorInputKey.stepCountdown(duplicate.identity), "bad", 0, 0) {}
            }
            assertEquals(frozen, controller.state.value)
        }

    @Test
    fun numberDefaultsKeepMissingZeroAndUniqueMainValue() =
        runBlocking {
            val controller = controller(this)
            val owner = step("owner")
            val a = field("a", CustomFieldType.NUMBER)
            val b = field("b", CustomFieldType.NUMBER).copy(position = 1)
            controller.updateDraft { it.copy(nodes = listOf(SequenceNodeDraft.Step(owner))) }
            val actions = actions(controller, owner)
            actions.activity { it.copy(fields = listOf(a, b)) }
            actions.numberDefault(a, "0")
            actions.numberDefault(b, "")
            actions.mainValue(a, true)
            actions.mainValue(b, true)
            val fields = requireNotNull(actions.configuration()).fields
            assertEquals(listOf(0L, null), fields.map { it.defaultNumberScaled })
            assertEquals(listOf(false, true), fields.map { it.isMainValue })
            actions.mainValue(a, false)
            assertEquals(listOf(false, true), requireNotNull(actions.configuration()).fields.map { it.isMainValue })
            actions.mainValue(b, false)
            assertTrue(requireNotNull(actions.configuration()).fields.none { it.isMainValue })
        }

    @Test
    fun obsoleteContinuationCannotPublishIntoNewOfferAndRepeatedPageActionStaysBounded() =
        runBlocking {
            val first = List(50) { SequenceEditorActivityChoice(ActivityTemplateId("first-$it"), "Same") }
            val obsolete = CompletableDeferred<List<SequenceEditorActivityChoice>>()
            val later = List(50) { SequenceEditorActivityChoice(ActivityTemplateId("later-$it"), "Same") }
            var pageReads = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.New,
                    { null },
                    { first },
                    { _, _, _ -> error("No write") },
                    { _, _, _, _ -> error("No write") },
                    { Instant.EPOCH },
                    loadMoreActivities = { cursor ->
                        assertEquals(first.last(), cursor)
                        if (++pageReads == 1) obsolete.await() else later
                    },
                )
            withTimeout(2_000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
            val old = requireNotNull(controller.offerStepInsertion(null))
            withTimeout(2_000) { controller.state.first { it.availableActivities.size == 50 } }
            controller.loadMoreStepInsertion(old, first.last().id)
            kotlinx.coroutines.yield()
            controller.loadMoreStepInsertion(old, first.last().id)
            assertEquals(1, pageReads)
            controller.dismissStepInsertion(old)
            val current = requireNotNull(controller.offerStepInsertion(null))
            controller.loadMoreStepInsertion(current, first.last().id)
            withTimeout(2_000) { controller.state.first { it.availableActivities.size == 100 } }
            obsolete.complete(listOf(SequenceEditorActivityChoice(ActivityTemplateId("obsolete"), "Wrong")))
            kotlinx.coroutines.yield()
            assertEquals(first + later, controller.state.value.availableActivities)
            controller.loadMoreStepInsertion(current, first.last().id)
            assertEquals(2, pageReads)
            assertEquals(first + later, controller.state.value.availableActivities)
        }

    private fun step(key: String) =
        ActivityStepDraft(
            DraftIdentity.New(key),
            0,
            StepActivityDraft.Local(ActivitySnapshotDraft("Same name", null, TimeTrackingMode.STOPWATCH, null)),
        )

    private fun field(
        key: String,
        type: CustomFieldType,
    ) = ActivitySnapshotFieldDraft(
        DraftIdentity.New(key),
        null,
        0,
        "Same",
        type = type,
    )

    private fun actions(
        controller: SequenceTemplateEditorController,
        step: ActivityStepDraft,
    ) = SequenceStepActions(
        controller,
        SequenceTemplateEditorActions(controller, controller.state.value.generation),
        step.identity,
    )

    private suspend fun controller(
        scope: CoroutineScope,
        catalog: suspend () -> List<SequenceEditorActivityChoice> = { emptyList() },
    ): SequenceTemplateEditorController {
        val controller =
            SequenceTemplateEditorController(
                scope,
                SequenceTemplateEditorTarget.New,
                { null },
                catalog,
                { _, _, _ ->
                    error("No writer admitted")
                },
                { _, _, _, _ -> error("No writer admitted") },
                { Instant.EPOCH },
            )
        withTimeout(2_000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
        return controller
    }
}
