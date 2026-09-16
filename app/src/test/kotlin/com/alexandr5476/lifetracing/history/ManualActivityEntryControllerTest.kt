@file:Suppress("MaxLineLength")

package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityCompletionReason
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class ManualActivityEntryControllerTest {
    @Test
    fun selectionHydratesOnlySelectedTemplateAndBuildsStableActualOverrides() =
        runBlocking {
            val fixture = fixture(template = timedTemplate())

            fixture.controller.dispatch(ManualActivityEntryAction.Select(TEMPLATE_ID))
            fixture.awaitSelected()
            val state = fixture.controller.state.value
            assertEquals(1, fixture.catalogReads)
            assertEquals(listOf(TEMPLATE_ID), fixture.templateReads)
            assertEquals("0", state.values.getValue(NUMBER_ID).numberText)
            assertEquals(OPTION_ID, state.values.getValue(CATEGORY_ID).selectedOptionId)
            assertEquals("default", state.values.getValue(TEXT_ID).text)

            fixture.controller.dispatch(ManualActivityEntryAction.EditNumber(NUMBER_ID, "0"))
            fixture.controller.dispatch(ManualActivityEntryAction.SelectCategory(CATEGORY_ID, OPTION_ID))
            fixture.controller.dispatch(ManualActivityEntryAction.EditText(TEXT_ID, "actual"))
            fixture.controller.dispatch(ManualActivityEntryAction.SetMissing(TEXT_ID))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()

            val values =
                fixture.timed
                    .single()
                    .values
                    .associateBy { (it.field as ActivityEntryFieldReference.Template).id }
            assertEquals(ActivityEntryValue.Number(0), values.getValue(NUMBER_ID).value)
            assertEquals(
                ActivityEntryValue.Category(ActivityEntryOptionReference.Template(OPTION_ID)),
                values.getValue(CATEGORY_ID).value,
            )
            assertEquals(ActivityEntryValue.Missing, values.getValue(TEXT_ID).value)
            fixture.close()
        }

    @Test
    fun futureOrReversedTimedInputDoesNotCheckOverlapOrWrite() =
        runBlocking {
            val fixture = fixture(template = timedTemplate())
            fixture.select()
            fixture.controller.dispatch(ManualActivityEntryAction.EditStarted("2026-09-16 11:00"))
            fixture.controller.dispatch(ManualActivityEntryAction.EditCompleted("2026-09-16 10:00"))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)

            assertInstanceOf(ManualEntryCommand.Invalid::class.java, fixture.controller.state.value.command)
            assertEquals(0, fixture.overlapChecks)
            assertTrue(fixture.timed.isEmpty())
            fixture.close()
        }

    @Test
    fun overlapCancelAndInFlightRepeatsProduceNoDuplicateWrites() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fixture = fixture(template = timedTemplate(), overlap = true, timedWriteGate = gate)
            fixture.select()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitOverlap()
            fixture.controller.dispatch(ManualActivityEntryAction.CancelOverlap)
            assertTrue(fixture.timed.isEmpty())

            fixture.overlap = false
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitting()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            gate.complete(Unit)
            fixture.awaitCommitted()
            assertEquals(1, fixture.timed.size)
            fixture.close()
        }

    @Test
    fun noLiveWritesOnlyNoLiveProposalWithoutOverlap() =
        runBlocking {
            val fixture = fixture(template = timedTemplate(TimeTrackingMode.NO_LIVE_TRACKING))
            fixture.select()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()

            assertEquals(0, fixture.overlapChecks)
            assertTrue(fixture.timed.isEmpty())
            assertEquals(1, fixture.noLive.size)
            assertEquals(null, fixture.noLive.single().startedAt)
            fixture.close()
        }

    private fun fixture(
        template: ActivityTemplate,
        overlap: Boolean = false,
        timedWriteGate: CompletableDeferred<Unit>? = null,
    ): Fixture {
        val fixture = Fixture(template, timedWriteGate)
        fixture.overlap = overlap
        fixture.controller =
            ManualActivityEntryController(
                fixture.scope,
                { after ->
                    fixture.catalogReads++
                    assertTrue(after == null || after.id == TEMPLATE_ID)
                    listOf(
                        ReusableActivityCatalogItem(
                            TEMPLATE_ID,
                            "Catalog",
                            template.timeTrackingMode,
                            template.timerTarget,
                            null,
                            null,
                            null,
                            null,
                        ),
                    )
                },
                { id ->
                    fixture.templateReads += id
                    template.takeIf { it.id == id }
                },
                { _, _ ->
                    fixture.overlapChecks++
                    fixture.overlap
                },
                { proposal ->
                    timedWriteGate?.await()
                    fixture.timed += proposal
                    execution()
                },
                { proposal ->
                    fixture.noLive += proposal
                    execution()
                },
                { NOW },
                { ZoneOffset.UTC },
            )
        return fixture
    }

    private class Fixture(
        val template: ActivityTemplate,
        val timedWriteGate: CompletableDeferred<Unit>?,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var controller: ManualActivityEntryController
        var catalogReads = 0
        val templateReads = mutableListOf<ActivityTemplateId>()
        var overlapChecks = 0
        var overlap = false
        val timed = mutableListOf<ManualEntryProposal>()
        val noLive = mutableListOf<ManualEntryProposal>()

        suspend fun select() {
            controller.dispatch(ManualActivityEntryAction.Select(TEMPLATE_ID))
            awaitSelected()
        }

        suspend fun awaitSelected() =
            withTimeout(2_000) {
                controller.state.first { it.selected is ManualEntryLoad.Content }
            }

        suspend fun awaitOverlap() =
            withTimeout(2_000) {
                controller.state.first { it.command is ManualEntryCommand.Overlap }
            }

        suspend fun awaitCommitting() =
            withTimeout(2_000) {
                controller.state.first { it.command is ManualEntryCommand.Committing }
            }

        suspend fun awaitCommitted() =
            withTimeout(2_000) {
                controller.state.first { it.command is ManualEntryCommand.Committed }
            }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-16T10:00:00Z")
        private val TEMPLATE_ID = ActivityTemplateId("template")
        private val NUMBER_ID = ActivityTemplateFieldId("number")
        private val CATEGORY_ID = ActivityTemplateFieldId("category")
        private val TEXT_ID = ActivityTemplateFieldId("text")
        private val OPTION_ID = CategoryOptionId("option")

        private fun timedTemplate(mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH) =
            ActivityTemplate(
                TEMPLATE_ID,
                "Template",
                null,
                mode,
                null,
                StatisticsSeriesId("series"),
                createdAt = NOW,
                updatedAt = NOW,
                fields =
                    listOf(
                        ActivityTemplateField(
                            NUMBER_ID,
                            0,
                            "Number",
                            CustomFieldType.NUMBER,
                            defaultNumberScaled = 0,
                            createdAt = NOW,
                            updatedAt = NOW,
                        ),
                        ActivityTemplateField(
                            CATEGORY_ID,
                            1,
                            "Category",
                            CustomFieldType.CATEGORY,
                            defaultCategoryOptionId = OPTION_ID,
                            createdAt = NOW,
                            updatedAt = NOW,
                            categoryOptions = listOf(CategoryOption(OPTION_ID, 0, "Option")),
                        ),
                        ActivityTemplateField(
                            TEXT_ID,
                            2,
                            "Text",
                            CustomFieldType.TEXT,
                            defaultText = "default",
                            createdAt = NOW,
                            updatedAt = NOW,
                        ),
                    ),
            )

        private fun execution() =
            ActivityExecution(
                id = ActivityExecutionId("execution"),
                snapshotId = ActivitySnapshotId("snapshot"),
                context = ActivityExecutionContext.STANDALONE,
                statisticsSeriesId = StatisticsSeriesId("series"),
                status = ActivityExecutionStatus.COMPLETED,
                startedAt = NOW,
                completedAt = NOW,
                activeDuration = java.time.Duration.ZERO,
                originalZoneId = ZoneOffset.UTC,
                originalUtcOffsetMinutes = 0,
                primaryLocalDate = NOW.atZone(ZoneOffset.UTC).toLocalDate(),
                completionReason = ActivityCompletionReason.MANUAL_HISTORY_ENTRY,
                deletedAt = null,
                createdAt = NOW,
                updatedAt = NOW,
            )
    }
}
