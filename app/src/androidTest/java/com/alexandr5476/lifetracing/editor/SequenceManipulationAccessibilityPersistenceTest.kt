@file:Suppress("FunctionNaming", "LongMethod")

package com.alexandr5476.lifetracing.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.onLazyNode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SequenceManipulationAccessibilityPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun nativeCrossContainerMoveAndFrozenDuplicateApplyOnceAndPhysicallyReopenIndependentOwnership() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "native-sequence-${UUID.randomUUID()}.db"
        var store = RuntimeRecoveryStore(context, databaseName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: SequenceTemplateEditorController? = null
        val base = Instant.parse("2026-11-01T10:00:00Z")
        try {
            val category = DraftIdentity.New("category")
            val option = DraftIdentity.New("option")
            val source =
                store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Same",
                        "Frozen source",
                        TimeTrackingMode.TIMER,
                        Duration.ofMinutes(5),
                        fields =
                            listOf(
                                ActivityFieldDraft(
                                    DraftIdentity.New("number"),
                                    0,
                                    "Number",
                                    CustomFieldType.NUMBER,
                                    unit = "kg",
                                    displayPrecision = 2,
                                    defaultNumberScaled = 1250,
                                ),
                                ActivityFieldDraft(
                                    category,
                                    1,
                                    "Category",
                                    CustomFieldType.CATEGORY,
                                    defaultCategoryOption = option,
                                    categoryOptions = listOf(ActivityCategoryOptionDraft(option, 0, "Frozen option")),
                                ),
                            ),
                    ),
                    createdAt = base,
                )
            val overrides =
                SequenceStepOverrides(
                    startCountdown = Duration.ZERO,
                    timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                    timerEndSound = false,
                    timerEndVibration = true,
                    keepScreenAwake = false,
                )

            fun step(
                key: String,
                position: Int,
            ) = ActivityStepDraft(
                DraftIdentity.New(key),
                position,
                StepActivityDraft.FromTemplate(source.id),
                overrides,
            )
            val created =
                store.authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "Native manipulation",
                        null,
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(step("top", 0)),
                                SequenceNodeDraft.Repeat(
                                    SequenceRepeatBlockDraft(DraftIdentity.New("r1"), 1, 2, listOf(step("child", 0))),
                                ),
                                SequenceNodeDraft.Repeat(
                                    SequenceRepeatBlockDraft(DraftIdentity.New("r2"), 2, 2, emptyList()),
                                ),
                            ),
                    ),
                    createdAt = base.plusSeconds(1),
                )
            val original = created.nodes.filterIsInstance<ActivityStep>().single()
            val initial = store.authoring.getSequenceTemplateAuthoringState(created.id)!!
            store.authoring.saveStepConfiguration(
                created.id,
                original.id,
                created.revision,
                initial.activitySnapshots
                    .getValue(
                        original.activitySnapshotId,
                    ).toAuthoringDraft()
                    .copy(shortComment = "Frozen locally modified"),
                base.plusSeconds(2),
            )
            val before = store.authoring.getSequenceTemplateAuthoringState(created.id)!!
            val frozen =
                before.activitySnapshots.getValue(
                    (before.sequence.nodes.first() as ActivityStep).activitySnapshotId,
                )
            assertEquals(TimeTrackingMode.TIMER, frozen.timeTrackingMode)
            assertEquals(Duration.ofMinutes(5), frozen.timerTarget)
            assertEquals(overrides, (before.sequence.nodes.first() as ActivityStep).overrides)
            store.authoring.saveActivityTemplate(
                source.id,
                source.revision,
                source.toAuthoringDraft().copy(
                    name = "Mutable source now differs",
                    fields =
                        source.toAuthoringDraft().fields.map { field ->
                            if (field.type ==
                                CustomFieldType.NUMBER
                            ) {
                                field.copy(defaultNumberScaled = 99000)
                            } else {
                                field.copy(
                                    categoryOptions = field.categoryOptions.map { it.copy(label = "Mutable option") },
                                )
                            }
                        },
                ),
                base.plusSeconds(3),
            )
            store.library.startSequenceFromTemplate(
                created.id,
                base.plusSeconds(4),
                base.plusSeconds(4),
                ZoneOffset.UTC,
            )
            store.live.endSequenceEarly(base.plusSeconds(5))
            store.plans.createSequencePlanFromTemplate(
                created.id,
                PlanSchedule.FloatingDay(LocalDate.of(2026, 11, 5)),
                base.plusSeconds(6),
            )
            store.library.startActivityFromTemplate(source.id, base.plusSeconds(7), base.plusSeconds(7), ZoneOffset.UTC)
            val active = store.live.getActiveSession()
            val runtime = store.live.getActiveRuntime()
            val facts = store.durableFacts()
            val repeats = before.sequence.nodes.filterIsInstance<SequenceRepeatBlock>()
            val r1 = repeats[0]
            val r2 = repeats[1]
            var writerCalls = 0
            var deliveries = 0
            val editor =
                SequenceTemplateEditorController(
                    scope,
                    SequenceTemplateEditorTarget.Existing(created.id),
                    { store.authoring.getSequenceTemplateAuthoringState(it) },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { id, revision, draft, at ->
                        writerCalls++
                        store.authoring.saveSequenceTemplate(id, revision, draft, at)
                    },
                    { base.plusSeconds(8) },
                )
            controller = editor
            val session = SequenceTemplateEditorRouteSession(SequenceTemplateEditorTarget.Existing(created.id), editor)
            val attachment = mutableStateOf(0)
            compose.setContent {
                key(attachment.value) {
                    LifeTracingTheme {
                        SequenceTemplateEditorRoute(editor, onApplied = { generation ->
                            session.deliverApplied(generation) { deliveries++ }
                        }) {}
                    }
                }
            }
            compose.waitUntil(5000) { editor.state.value.load is SequenceTemplateEditorLoad.Ready }
            keyboard.enter()
            val entry =
                text(
                    R.string.activity_editor_control_context,
                    text(R.string.sequence_manipulation_enter),
                    text(R.string.sequence_manipulation_top_step, "Same", 1),
                )
            activate(compose.onLazyNode(hasContentDescription(entry)))
            activate(compose.onLazyNode(hasText(text(R.string.sequence_manipulation_move))))
            val rejected =
                compose
                    .onNodeWithTag(
                        "sequence-destination-top-0",
                    ).fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            activate(compose.onNodeWithText(text(R.string.sequence_manipulation_cancel_destination)))
            compose.runOnIdle { rejected() }
            assertEquals(
                0,
                editor.state.value.manipulation!!
                    .operationCount,
            )
            assertEquals(facts, store.durableFacts())
            activate(compose.onLazyNode(hasText(text(R.string.sequence_manipulation_move))))
            choose(r2.id, 0)
            activate(compose.onLazyNode(hasText(text(R.string.sequence_manipulation_duplicate))))
            choose(r1.id, 1)
            val transient =
                editor.state.value.manipulation!!
                    .selected
            assertTrue(transient is DraftIdentity.New)
            val disabledDuplicate =
                compose
                    .onLazyNode(hasText(text(R.string.sequence_manipulation_duplicate)))
                    .assertIsNotEnabled()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            val draft = editor.state.value.readyDraft()
            compose.runOnIdle { disabledDuplicate() }
            assertEquals(draft, editor.state.value.readyDraft())
            assertEquals(facts, store.durableFacts())
            assertEquals(0, writerCalls)
            val apply = compose.onLazyNode(hasText(text(R.string.sequence_editor_apply)))
            val retainedApply = apply.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            activate(apply)
            compose.waitUntil(5000) { editor.state.value.appliedGeneration == 1L && deliveries == 1 }
            compose.runOnIdle {
                retainedApply()
                rejected()
                attachment.value++
            }
            compose.waitForIdle()
            assertEquals(1, writerCalls)
            assertEquals(1, deliveries)
            val canonical = store.authoring.getSequenceTemplateAuthoringState(created.id)!!
            assertEquals(before.sequence.revision + 1, canonical.sequence.revision)
            assertEquals(before.sequence.statisticsSeriesId, canonical.sequence.statisticsSeriesId)
            val blocks = canonical.sequence.nodes.filterIsInstance<SequenceRepeatBlock>()
            assertEquals(listOf(r1.id, r2.id), blocks.map { it.id })
            assertEquals(listOf(0, 1), blocks.map { it.position })
            assertEquals(listOf(r1.children.single().id), blocks[0].children.dropLast(1).map { it.id })
            assertEquals(listOf(0, 1), blocks[0].children.map { it.position })
            assertEquals(listOf(0), blocks[1].children.map { it.position })
            val duplicate = blocks[0].children.last()
            val moved = blocks[1].children.single()
            assertEquals(original.id, moved.id)
            assertEquals(frozen.id, moved.activitySnapshotId)
            assertEquals(overrides, moved.overrides)
            assertEquals(frozen, canonical.activitySnapshots.getValue(moved.activitySnapshotId))
            assertNotEquals(original.id, duplicate.id)
            val copied = canonical.activitySnapshots.getValue(duplicate.activitySnapshotId)
            assertNotEquals(frozen.id, copied.id)
            assertEquals(
                frozen.toAuthoringDraft().copy(fields = emptyList()),
                copied.toAuthoringDraft().copy(fields = emptyList()),
            )
            assertEquals(frozen.sourceTemplateId, copied.sourceTemplateId)
            assertEquals(frozen.sourceRevision, copied.sourceRevision)
            assertEquals(frozen.statisticsSeriesId, copied.statisticsSeriesId)
            assertTrue(frozen.locallyModified)
            assertEquals(frozen.locallyModified, copied.locallyModified)
            assertEquals(overrides, duplicate.overrides)
            assertEquals(frozen.fields.map { it.sourceFieldId }, copied.fields.map { it.sourceFieldId })
            assertTrue(
                frozen.fields
                    .map { it.id }
                    .intersect(copied.fields.map { it.id }.toSet())
                    .isEmpty(),
            )
            assertEquals(frozen.fields.map { it.defaultNumberScaled }, copied.fields.map { it.defaultNumberScaled })
            val frozenOptions = frozen.fields.flatMap { it.categoryOptions }
            val copiedOptions = copied.fields.flatMap { it.categoryOptions }
            val copiedCategory = copied.fields.single { it.type == CustomFieldType.CATEGORY }
            assertEquals(copiedCategory.categoryOptions.single().id, copiedCategory.defaultCategoryOptionId)
            assertEquals(frozenOptions.map { it.sourceOptionId }, copiedOptions.map { it.sourceOptionId })
            assertEquals(frozenOptions.map { it.labelAtCreation }, copiedOptions.map { it.labelAtCreation })
            assertTrue(frozenOptions.map { it.id }.intersect(copiedOptions.map { it.id }.toSet()).isEmpty())
            val after = store.durableFacts()
            val structuralTables =
                setOf(
                    "sequence_templates",
                    "sequence_nodes",
                    "sequence_step_overrides",
                    "activity_snapshots",
                    "activity_snapshot_fields",
                    "activity_snapshot_category_options",
                    "activity_snapshot_settings",
                )
            facts.filterKeys { it !in structuralTables }.forEach { (table, rows) ->
                assertEquals("Unrelated durable table $table", rows, after[table])
            }
            for (table in listOf(
                "activity_snapshots",
                "activity_snapshot_fields",
                "activity_snapshot_category_options",
                "activity_snapshot_settings",
            )) {
                assertTrue(
                    "Original snapshot rows retained in $table",
                    after.getValue(table).containsAll(facts.getValue(table)),
                )
            }
            assertEquals(active, store.live.getActiveSession())
            assertEquals(runtime, store.live.getActiveRuntime())
            editor.close()
            scope.cancel()
            store.close()
            store = RuntimeRecoveryStore(context, databaseName)
            assertEquals(canonical, store.authoring.getSequenceTemplateAuthoringState(created.id))
            assertEquals(after, store.durableFacts())
            assertEquals(active, store.live.getActiveSession())
            assertEquals(runtime, store.live.getActiveRuntime())
            assertFalse(copied.name == "Mutable source now differs")
        } finally {
            controller?.close()
            scope.cancel()
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun choose(
        repeat: SequenceNodeId,
        position: Int,
    ) {
        val key = sequenceDestinationKey(SequenceDropDestination(DraftIdentity.Existing(repeat), position))
        compose.onNodeWithTag("sequence-destinations").performScrollToKey(key)
        activate(compose.onNodeWithTag(key))
    }

    private fun activate(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(node)
        keyboard.sendKey(node, android.view.KeyEvent.KEYCODE_ENTER)
        compose.waitForIdle()
        if (compose.onAllNodes(hasTestTag("sequence-destinations")).fetchSemanticsNodes().isNotEmpty()) {
            // Observe the deferred production landing before requesting a destination or Cancel's focus.
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-destination-top-0"))
        }
    }

    private fun text(
        resource: Int,
        vararg args: Any,
    ): String = compose.activity.getString(resource, *args)
}
