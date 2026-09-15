package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PlanExecutionRouteSessionTest {
    @Test
    fun frozenDefaultsMissingAndEditedOverridesKeepSnapshotIdentities() {
        val fields = fields()
        var draft = PlanQuickCompletionDraft.from(snapshot(fields))

        assertEquals(0L, (draft.values[fields[0].id] as NumberExecutionValue).scaledValue)
        assertNull(draft.values[fields[1].id])
        assertEquals(12_345L, (draft.values[fields[2].id] as NumberExecutionValue).scaledValue)
        assertEquals(fields[3].defaultCategoryOptionId, (draft.values[fields[3].id] as CategoryExecutionValue).optionId)
        assertEquals("default text", (draft.values[fields[4].id] as TextExecutionValue).value)
        assertTrue(draft.overrides(fields).isEmpty())

        draft = draft.edit(fields[1].id, NumberExecutionValue(fields[1].id, 7_000L), numberText = "7")
        draft = draft.edit(fields[2].id, null, numberText = "")
        draft = draft.edit(fields[3].id, CategoryExecutionValue(fields[3].id, fields[3].categoryOptions.last().id))
        draft = draft.edit(fields[4].id, TextExecutionValue(fields[4].id, "edited"))

        val overrides = draft.overrides(fields).associateBy { it.snapshotFieldId }
        assertEquals(fields.drop(1).map(ActivitySnapshotField::id).toSet(), overrides.keys)
        assertNull(overrides.getValue(fields[2].id).value)
        assertEquals(
            fields[3].categoryOptions.last().id,
            (overrides.getValue(fields[3].id).value as CategoryExecutionValue).optionId,
        )
        assertEquals("edited", (overrides.getValue(fields[4].id).value as TextExecutionValue).value)
    }

    @Test
    fun invalidNumberAndForeignCategoryOptionCannotDispatchAsValidFrozenValues() =
        runBlocking {
            val snapshot = snapshot(fields())
            val action = focused(snapshot)
            val controller = controller(this, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.DAILY, controller)
            session.prepareQuickDraft(snapshot)

            session.editNumber(snapshot.fields[0], "1.2345")
            assertTrue(snapshot.fields[0].id in requireNotNull(session.quickDraft).invalid)
            assertThrows(IllegalArgumentException::class.java) {
                session.editCategory(snapshot.fields[3], ActivitySnapshotCategoryOptionId("foreign"))
            }
            controller.close()
        }

    @Test
    fun ownerReusesOneAttemptAndExitPolicyDeliversOneDurableResult() =
        runBlocking {
            val action = focused(snapshot(emptyList()))
            val controller =
                controller(this, action) {
                    PlanExecutionCommit.Activity(ActivityExecutionId("execution"), false)
                }
            val owner = PlanExecutionRouteSessionOwner()
            var creations = 0
            val first =
                owner.acquire(action.identity, PlanExecutionOrigin.PLAN) {
                    creations++
                    controller
                }
            val repeated =
                owner.acquire(action.identity, PlanExecutionOrigin.PLAN) {
                    creations++
                    error("duplicate")
                }

            assertTrue(first === repeated)
            assertEquals(1, creations)
            assertEquals(PlanExecutionRouteExitDecision.BACK, controller.arbitrateRouteExit())
            withTimeout(2_000) { controller.state.first { it.prepared is PlanExecutionLoad.Content } }
            controller.launch()
            val committed =
                withTimeout(2_000) {
                    controller.state.first { it.command is PlanExecutionCommandState.Committed }
                }.command
            var deliveries = 0
            first.exitPolicy.onCommand(committed) { deliveries++ }
            first.exitPolicy.requestExit(controller, {}, { deliveries++ })
            assertEquals(1, deliveries)
            assertEquals(PlanExecutionRouteExitDecision.DELIVER_COMMIT, controller.arbitrateRouteExit())
            owner.release(first)
        }

    private fun controller(
        scope: CoroutineScope,
        action: FocusedPlanAction,
        execute: suspend (PlanExecutionDurableCommand) -> PlanExecutionCommit = { error("not launched") },
    ) = PlanExecutionController(
        scope,
        action.identity,
        { action },
        { false },
        execute,
        {},
        WallClock { NOW },
        { ZoneOffset.UTC },
        object : PreflightScheduler {
            override fun schedule(
                duration: Duration,
                onBoundary: () -> Unit,
            ) = PreflightHandle {}
        },
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        fun snapshot(fields: List<ActivitySnapshotField>) =
            ActivityConfigSnapshot(
                ActivitySnapshotId("snapshot"),
                "Frozen activity",
                null,
                TimeTrackingMode.NO_LIVE_TRACKING,
                null,
                null,
                null,
                null,
                false,
                NOW,
                fields = fields,
            )

        fun focused(snapshot: ActivityConfigSnapshot) =
            FocusedPlanAction(
                PlanActionIdentity(
                    PlanEntryId("plan"),
                    PlanTrackableKind.ACTIVITY,
                    snapshot.id,
                    null,
                    PlanTarget.FloatingDay(LocalDate.parse("2026-09-15")),
                    PlanEntryStatus.PLANNED,
                    null,
                    NOW,
                ),
                PlanSourceState.UNAVAILABLE,
                false,
                FocusedPlanAction.Snapshot.Activity(snapshot),
            )

        fun fields(): List<ActivitySnapshotField> {
            val first = ActivitySnapshotCategoryOption(ActivitySnapshotCategoryOptionId("category-a"), null, 0, "A")
            val second = ActivitySnapshotCategoryOption(ActivitySnapshotCategoryOptionId("category-b"), null, 1, "B")
            return listOf(
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("zero"),
                    null,
                    0,
                    "Zero",
                    type = CustomFieldType.NUMBER,
                    displayPrecision = 0,
                    defaultNumberScaled = 0,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("missing-number"),
                    null,
                    1,
                    "Optional number",
                    type = CustomFieldType.NUMBER,
                    displayPrecision = 3,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("main"),
                    null,
                    2,
                    "Main",
                    type = CustomFieldType.NUMBER,
                    displayPrecision = 3,
                    defaultNumberScaled = 12_345,
                    isMainValue = true,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("category"),
                    null,
                    3,
                    "Category",
                    type = CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = first.id,
                    categoryOptions = listOf(first, second),
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("text"),
                    null,
                    4,
                    "Text",
                    type = CustomFieldType.TEXT,
                    defaultText = "default text",
                ),
            )
        }
    }
}
