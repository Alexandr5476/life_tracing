@file:Suppress("LongMethod")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SequenceNumberFieldInputTest {
    @Test
    fun bothSurfacesRetainExactLiteralsAndRejectPrecisionMalformedAndOverflowWithoutChangingLastValue() =
        runBlocking {
            for (stepField in listOf(false, true)) {
                val fixture = Fixture(this, stepField)
                fixture.ready()
                fixture.precision("2")
                var previous: Long? = null
                val valid =
                    listOf(
                        "" to null,
                        "0" to 0L,
                        "+12" to 12000L,
                        "-12" to -12000L,
                        "+1.25" to 1250L,
                        "-1.25" to -1250L,
                    )
                for ((text, expected) in valid) {
                    fixture.default(text)
                    assertEquals(text, fixture.state.inputText(fixture.defaultKey, "missing"))
                    assertEquals(expected, fixture.number())
                    assertFalse(fixture.state.hasInvalidInput(fixture.state.readyDraft()!!))
                    previous = expected
                }
                for (text in listOf("1.234", "--1", "no", "9223372036854775808", "1e100")) {
                    fixture.default(text)
                    assertEquals(text, fixture.state.inputText(fixture.defaultKey, "missing"))
                    assertEquals(previous, fixture.number())
                    assertTrue(fixture.state.inputIsInvalid(fixture.defaultKey))
                    fixture.controller.save()
                    fixture.controller.applyManipulation()
                    assertEquals(0, fixture.writes)
                }
                fixture.precision("")
                fixture.default("-9223372036854775.808")
                assertEquals(Long.MIN_VALUE, fixture.number())
                fixture.default("9223372036854775.807")
                assertEquals(Long.MAX_VALUE, fixture.number())
                fixture.default("9223372036854775.808")
                assertEquals(Long.MAX_VALUE, fixture.number())
                assertTrue(fixture.state.inputIsInvalid(fixture.defaultKey))
                fixture.default("")
                assertEquals(null, fixture.number())
                assertFalse(fixture.state.hasInvalidInput(fixture.state.readyDraft()!!))
                fixture.controller.close()
            }
        }

    @Test
    fun precisionChangesRevalidateTheOriginalLiteralWithoutRoundingOrLosingRejectedPrecision() =
        runBlocking {
            for (stepField in listOf(false, true)) {
                val fixture = Fixture(this, stepField)
                fixture.ready()
                fixture.default("-1.250")
                fixture.precision("2")
                assertEquals("-1.250", fixture.state.inputText(fixture.defaultKey, ""))
                assertEquals(-1250L, fixture.number())
                assertTrue(fixture.state.inputIsInvalid(fixture.defaultKey))
                fixture.default("-1.25")
                assertFalse(fixture.state.inputIsInvalid(fixture.defaultKey))
                for (text in listOf("9", "-1", "2.5", "bad", "99999999999999")) {
                    fixture.precision(text)
                    assertEquals(text, fixture.state.inputText(fixture.precisionKey, ""))
                    assertTrue(fixture.state.inputIsInvalid(fixture.precisionKey))
                    assertEquals(2, fixture.currentPrecision())
                    assertEquals(-1250L, fixture.number())
                }
                fixture.precision("0")
                assertEquals("-1.25", fixture.state.inputText(fixture.defaultKey, ""))
                assertTrue(fixture.state.inputIsInvalid(fixture.defaultKey))
                assertEquals(-1250L, fixture.number())
                fixture.precision("")
                assertEquals(null, fixture.currentPrecision())
                assertFalse(fixture.state.hasInvalidInput(fixture.state.readyDraft()!!))
                for (precision in 0..3) {
                    fixture.default("0")
                    fixture.precision(precision.toString())
                    fixture.default(if (precision == 0) "-2" else "-2." + "5".repeat(precision))
                    assertFalse(fixture.state.hasInvalidInput(fixture.state.readyDraft()!!))
                }
                fixture.controller.close()
            }
        }

    @Test
    fun historyRestoresExactRawTextSchemaAndSelectionAndDiscardRestoresPreexistingInvalidInput() =
        runBlocking {
            for (stepField in listOf(false, true)) {
                val f = Fixture(this, stepField)
                f.ready()
                f.default("preexisting rejected")
                val baseline = f.state.readyDraft()
                val raw = f.state.textInputs
                f.controller.enterManipulation(f.owner.identity)
                f.default("-1.25")
                assertEquals(1, f.state.manipulation!!.operationCount)
                val accepted = f.state
                f.precision("0")
                assertEquals(2, f.state.manipulation!!.operationCount)
                val rejected = f.state
                assertTrue(f.controller.undoManipulation())
                assertEquals(accepted.readyDraft(), f.state.readyDraft())
                assertEquals(accepted.textInputs, f.state.textInputs)
                assertEquals(f.owner.identity, f.state.manipulation!!.selected)
                assertTrue(f.controller.undoManipulation())
                assertEquals(baseline, f.state.readyDraft())
                assertEquals(raw, f.state.textInputs)
                assertTrue(f.controller.redoManipulation())
                assertTrue(f.controller.redoManipulation())
                assertEquals(rejected.readyDraft(), f.state.readyDraft())
                assertEquals(rejected.textInputs, f.state.textInputs)
                f.controller.discardManipulation()
                assertEquals(baseline, f.state.readyDraft())
                assertEquals(raw, f.state.textInputs)
                f.controller.close()
            }
        }

    @Test
    fun movedRepeatChildKeepsOwnershipWhileRemovalReplacementAndTypeChangeRetireKeysAndRejectStaleCallbacks() =
        runBlocking {
            for (stepField in listOf(false, true)) {
                val f = Fixture(this, stepField)
                f.ready()
                f.default("bad")
                val raw = f.state.textInputs
                f.controller.enterManipulation(f.owner.identity)
                f.controller.moveManipulation(f.owner.identity, SequenceDropDestination(position = 1))
                assertEquals(raw, f.state.textInputs)
                f.controller.updateDraft { draft ->
                    if (stepField) {
                        draft.updateStep(f.owner.identity) { owner ->
                            val activity = owner.activity as StepActivityDraft.Local
                            owner.copy(
                                activity =
                                    activity.copy(
                                        configuration = activity.configuration.copy(fields = emptyList()),
                                    ),
                            )
                        }
                    } else {
                        draft.copy(fields = emptyList())
                    }
                }
                assertFalse(f.state.hasInvalidInput(f.state.readyDraft()!!))
                val removed = f.state
                f.default("stale")
                assertEquals(removed, f.state)
                assertTrue(f.controller.undoManipulation())
                assertEquals(raw, f.state.textInputs)
                assertTrue(f.state.hasInvalidInput(f.state.readyDraft()!!))
                f.controller.discardManipulation()
                for (replacement in listOf("identity", "type", "unit")) {
                    val original = f.state.readyDraft()!!
                    f.controller.updateDraft { draft ->
                        if (stepField) {
                            draft.updateStep(f.owner.identity) { owner ->
                                val activity = owner.activity as StepActivityDraft.Local
                                owner.copy(
                                    activity =
                                        activity.copy(
                                            configuration =
                                                activity.configuration.copy(
                                                    fields =
                                                        listOf(
                                                            f.field.copy(
                                                                identity =
                                                                    if (replacement ==
                                                                        "identity"
                                                                    ) {
                                                                        DraftIdentity.New("successor")
                                                                    } else {
                                                                        f.field.identity
                                                                    },
                                                                type =
                                                                    if (replacement ==
                                                                        "type"
                                                                    ) {
                                                                        CustomFieldType.TEXT
                                                                    } else {
                                                                        CustomFieldType.NUMBER
                                                                    },
                                                                unit = if (replacement == "unit") "lb" else null,
                                                            ),
                                                        ),
                                                ),
                                        ),
                                )
                            }
                        } else {
                            draft.copy(
                                fields =
                                    listOf(
                                        f.sequenceField.copy(
                                            identity =
                                                if (replacement ==
                                                    "identity"
                                                ) {
                                                    DraftIdentity.New("successor")
                                                } else {
                                                    f.sequenceField.identity
                                                },
                                            type =
                                                if (replacement ==
                                                    "type"
                                                ) {
                                                    CustomFieldType.CATEGORY
                                                } else {
                                                    CustomFieldType.NUMBER
                                                },
                                            unit = if (replacement == "unit") "lb" else null,
                                        ),
                                    ),
                            )
                        }
                    }
                    assertFalse(f.state.hasInvalidInput(f.state.readyDraft()!!))
                    val changed = f.state
                    f.default("obsolete")
                    assertEquals(changed, f.state)
                    f.controller.updateDraft { original }
                }
                f.controller.close()
            }
        }

    @Test
    fun sequenceSchemaRoundTripsKeepIdentityAndRetireNativeLeasesWithoutRetiringCurrentPrecisionCallbacks() =
        runBlocking {
            val f = Fixture(this, false)
            f.ready()
            val original = f.state.readyDraft()!!
            val parent = SequenceTemplateEditorActions(f.controller, f.state.generation)
            val old = parent.forSequenceFields(f.state.sequenceFieldEpoch)
            old.numberPrecision(f.sequenceField, "2")
            old.numberDefault(f.sequenceField, "-1.25")
            assertEquals(-1250L, f.number())
            f.controller.updateDraft {
                it.copy(
                    fields =
                        it.fields.map { field ->
                            field.copy(type = CustomFieldType.TEXT)
                        },
                )
            }
            f.controller.updateDraft { original }
            assertEquals(original, f.state.readyDraft())
            old.numberDefault(f.sequenceField, "bad")
            assertTrue(f.state.textInputs.isEmpty())
            assertEquals(null, f.number())
            val current = parent.forSequenceFields(f.state.sequenceFieldEpoch)
            current.numberDefault(f.sequenceField, "0")
            assertEquals(0L, f.number())
            assertEquals(
                f.sequenceField.identity,
                f.state
                    .readyDraft()!!
                    .fields
                    .single()
                    .identity,
            )
            f.controller.close()
        }

    @Test
    fun genericPrecisionTransformsAlsoRevalidateActiveLiteralBeforeCommandAdmission() =
        runBlocking {
            for (stepField in listOf(false, true)) {
                val f = Fixture(this, stepField)
                f.ready()
                f.precision("2")
                f.default("-1.25")
                f.controller.updateDraft { draft ->
                    if (stepField) {
                        draft.updateStep(f.owner.identity) { owner ->
                            val activity = owner.activity as StepActivityDraft.Local
                            owner.copy(
                                activity =
                                    activity.copy(
                                        configuration =
                                            activity.configuration.copy(
                                                fields =
                                                    listOf(
                                                        activity.configuration.fields
                                                            .single()
                                                            .copy(displayPrecision = 0),
                                                    ),
                                            ),
                                    ),
                            )
                        }
                    } else {
                        draft.copy(fields = draft.fields.map { it.copy(displayPrecision = 0) })
                    }
                }
                assertEquals("-1.25", f.state.inputText(f.defaultKey, ""))
                assertEquals(-1250L, f.number())
                assertTrue(f.state.inputIsInvalid(f.defaultKey))
                assertTrue(f.state.hasInvalidInput(f.state.readyDraft()!!))
                f.controller.save()
                assertEquals(0, f.writes)
                f.controller.close()
            }
        }

    @Test
    fun survivingFieldLeaseKeepsEditingAfterSiblingRemovalButOwnSchemaRoundTripRetiresIt() =
        runBlocking {
            val f = Fixture(this, false)
            f.ready()
            val sibling = f.sequenceField.copy(identity = DraftIdentity.New("same-name-sibling"), position = 1)
            f.controller.updateDraft { it.copy(fields = it.fields + sibling) }
            val parent = SequenceTemplateEditorActions(f.controller, f.state.generation)
            val survivor =
                parent.forSequenceFields(
                    f.state.sequenceFieldEpochs[f.sequenceField.identity] ?: 0,
                    f.sequenceField.identity,
                )
            val removed = parent.forSequenceFields(f.state.sequenceFieldEpochs[sibling.identity] ?: 0, sibling.identity)
            f.controller.updateDraft { it.copy(fields = it.fields.take(1)) }
            survivor.numberDefault(f.sequenceField, "-1.25")
            assertEquals(-1250L, f.number())
            val afterSurvivor = f.state
            removed.numberDefault(sibling, "bad")
            assertEquals(afterSurvivor, f.state)
            val original = f.state.readyDraft()!!
            f.controller.updateDraft { it.copy(fields = it.fields.map { field -> field.copy(unit = "kg") }) }
            f.controller.updateDraft { original }
            val restored = f.state
            survivor.numberDefault(f.sequenceField, "obsolete")
            assertEquals(restored, f.state)
            val current =
                parent.forSequenceFields(
                    f.state.sequenceFieldEpochs[f.sequenceField.identity] ?: 0,
                    f.sequenceField.identity,
                )
            current.numberDefault(f.sequenceField, "0")
            assertEquals(0L, f.number())
            assertEquals(setOf(f.sequenceField.identity), f.state.sequenceFieldEpochs.keys)
            f.controller.close()
        }

    @Test
    fun fieldLeaseNeverRevivesAcrossRemovalUndoRedoAndDiscardWhileRecordedRawInputReturns() =
        runBlocking {
            val f = Fixture(this, false)
            f.ready()
            f.default("preexisting bad")
            val baseline = f.state.textInputs
            f.controller.enterManipulation(f.owner.identity)
            val parent = SequenceTemplateEditorActions(f.controller, f.state.generation)
            val retired =
                parent.forSequenceFields(
                    f.state.sequenceFieldEpochs[f.sequenceField.identity] ?: 0,
                    f.sequenceField.identity,
                )
            f.controller.updateDraft { it.copy(fields = emptyList()) }
            assertFalse(f.state.hasInvalidInput(f.state.readyDraft()!!))
            assertTrue(f.controller.undoManipulation())
            assertEquals(baseline, f.state.textInputs)
            val undone = f.state
            retired.numberDefault(f.sequenceField, "0")
            assertEquals(undone, f.state)
            assertTrue(f.controller.redoManipulation())
            f.controller.discardManipulation()
            assertEquals(baseline, f.state.textInputs)
            assertTrue(f.state.hasInvalidInput(f.state.readyDraft()!!))
            val beforeStale = f.state
            retired.numberDefault(f.sequenceField, "0")
            assertEquals(beforeStale, f.state)
            f.controller.close()
        }

    private class Fixture(
        scope: CoroutineScope,
        val stepField: Boolean,
    ) {
        var writes = 0
        val field =
            ActivitySnapshotFieldDraft(
                DraftIdentity.New("same"),
                ActivityTemplateFieldId("source"),
                0,
                "Same",
                type = CustomFieldType.NUMBER,
            )
        val sequenceField = SequenceFieldDraft(DraftIdentity.New("same"), 0, "Same", CustomFieldType.NUMBER)
        val owner =
            ActivityStepDraft(
                DraftIdentity.New("owner"),
                0,
                StepActivityDraft.Local(
                    ActivitySnapshotDraft("Same", null, TimeTrackingMode.STOPWATCH, null, fields = listOf(field)),
                ),
            )
        val controller =
            SequenceTemplateEditorController(
                scope,
                SequenceTemplateEditorTarget.New,
                { null },
                { emptyList() },
                { _, _, _ ->
                    writes++
                    error("Unexpected write")
                },
                { _, _, _, _ ->
                    writes++
                    error("Unexpected write")
                },
                { Instant.EPOCH },
            )
        val state get() = controller.state.value
        val defaultKey =
            if (stepField) {
                SequenceNumberInputKey.default(field, owner.identity)
            } else {
                SequenceNumberInputKey.default(sequenceField)
            }
        val precisionKey =
            if (stepField) {
                SequenceNumberInputKey.precision(field, owner.identity)
            } else {
                SequenceNumberInputKey.precision(sequenceField)
            }

        suspend fun ready() {
            withTimeout(2000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
            val other = owner.copy(identity = DraftIdentity.New("same-name-successor"), position = 1)
            controller.updateDraft {
                it.copy(
                    name = "Numbers",
                    fields = listOf(sequenceField),
                    nodes =
                        listOf(
                            SequenceNodeDraft.Repeat(
                                SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 2, listOf(owner, other)),
                            ),
                        ),
                )
            }
        }

        fun default(text: String) {
            if (stepField) {
                controller.setStepFieldNumber(owner.identity, field, text)
            } else {
                controller.setSequenceFieldNumber(sequenceField, text)
            }
        }

        fun precision(text: String) {
            if (stepField) {
                controller.setStepFieldNumber(owner.identity, field, text, precision = true)
            } else {
                controller.setSequenceFieldNumber(sequenceField, text, precision = true)
            }
        }

        fun number(): Long? =
            if (stepField) {
                state
                    .readyDraft()!!
                    .findStep(owner.identity)!!
                    .configuration()!!
                    .fields
                    .single()
                    .defaultNumberScaled
            } else {
                state
                    .readyDraft()!!
                    .fields
                    .single()
                    .defaultNumberScaled
            }

        fun currentPrecision(): Int? =
            if (stepField) {
                state
                    .readyDraft()!!
                    .findStep(owner.identity)!!
                    .configuration()!!
                    .fields
                    .single()
                    .displayPrecision
            } else {
                state
                    .readyDraft()!!
                    .fields
                    .single()
                    .displayPrecision
            }
    }
}
