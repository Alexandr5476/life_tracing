@file:Suppress("LongParameterList", "MaxLineLength")

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
import com.alexandr5476.lifetracing.domain.StaleLauncherTargetException
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ManualActivityEntryControllerTest {
    @Test
    fun selectionHydratesOnlySelectedTemplateAndMissingIsReversibleForEveryFieldType() =
        runBlocking {
            val fixture = fixture(timedTemplate())
            fixture.select()
            val initial = fixture.controller.state.value
            assertEquals(listOf(TEMPLATE_ID), fixture.templateReads)
            assertEquals("0", initial.values.getValue(NUMBER_ID).numberText)
            assertEquals(OPTION_ID, initial.values.getValue(CATEGORY_ID).selectedOptionId)
            assertEquals("default", initial.values.getValue(TEXT_ID).text)

            listOf(NUMBER_ID, CATEGORY_ID, TEXT_ID).forEach {
                fixture.controller.dispatch(ManualActivityEntryAction.SetMissing(it))
                assertTrue(
                    fixture.controller.state.value.values
                        .getValue(it)
                        .missing,
                )
                fixture.controller.dispatch(ManualActivityEntryAction.SetPresent(it))
            }
            fixture.controller.dispatch(ManualActivityEntryAction.EditNumber(NUMBER_ID, "0"))
            fixture.controller.dispatch(
                ManualActivityEntryAction.SelectCategory(CATEGORY_ID, CategoryOptionId("archived")),
            )
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.INVALID_CATEGORY, invalidIssue(fixture))
            assertTrue(fixture.timed.isEmpty())
            fixture.controller.dispatch(ManualActivityEntryAction.SelectCategory(CATEGORY_ID, OPTION_ID))
            fixture.controller.dispatch(ManualActivityEntryAction.EditText(TEXT_ID, ""))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()

            val proposal = fixture.timed.single()
            assertEquals(7, proposal.expectedTemplateRevision)
            val values = proposal.values.associateBy { (it.field as ActivityEntryFieldReference.Template).id }
            assertEquals(ActivityEntryValue.Number(0), values.getValue(NUMBER_ID).value)
            assertEquals(
                ActivityEntryValue.Category(ActivityEntryOptionReference.Template(OPTION_ID)),
                values.getValue(CATEGORY_ID).value,
            )
            assertEquals(ActivityEntryValue.Text(""), values.getValue(TEXT_ID).value)
            fixture.close()
        }

    @Test
    fun ordinaryNamedZoneTimeResolvesWithItsOnlyOffset() =
        runBlocking {
            val fixture = fixture(timedTemplate(), zone = BERLIN)
            fixture.select()
            fixture.editTimes("2026-01-15 10:00", "2026-01-15 11:00")
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()

            assertEquals(Instant.parse("2026-01-15T09:00:00Z"), fixture.timed.single().startedAt)
            assertEquals(Instant.parse("2026-01-15T10:00:00Z"), fixture.timed.single().completedAt)
            fixture.close()
        }

    @Test
    fun springGapIsTypedInvalidAndDoesNotCheckOverlapOrWrite() =
        runBlocking {
            val fixture = fixture(timedTemplate(), zone = BERLIN)
            fixture.select()
            fixture.editTimes("2026-03-29 02:30", "2026-03-29 03:30")
            fixture.controller.dispatch(ManualActivityEntryAction.Save)

            assertEquals(
                ManualEntryCommand.Invalid(ManualEntryIssue.NONEXISTENT_LOCAL_TIME),
                fixture.controller.state.value.command,
            )
            assertEquals(ManualEntryIssue.NONEXISTENT_LOCAL_TIME, fixture.controller.state.value.startedIssue)
            assertNull(fixture.controller.state.value.completedIssue)
            assertEquals(0, fixture.overlapChecks)
            assertTrue(fixture.timed.isEmpty())
            fixture.close()
        }

    @Test
    fun syntaxFutureReversedAndNumberFailuresAreTypedBeforeOverlapOrWrite() =
        runBlocking {
            val fixture = fixture(timedTemplate())
            fixture.select()

            fixture.controller.dispatch(ManualActivityEntryAction.EditStarted("not-a-time"))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.INVALID_DATE_TIME, invalidIssue(fixture))

            fixture.editTimes("2026-12-02 10:00", "2026-12-02 11:00")
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.FUTURE_COMPLETION, invalidIssue(fixture))

            fixture.editTimes("2026-09-16 11:00", "2026-09-16 10:00")
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.REVERSED_INTERVAL, invalidIssue(fixture))

            fixture.editTimes("2026-09-16 09:00", "2026-09-16 10:00")
            fixture.controller.dispatch(ManualActivityEntryAction.EditNumber(NUMBER_ID, "not-a-number"))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.INVALID_NUMBER, invalidIssue(fixture))
            assertEquals(0, fixture.overlapChecks)
            assertTrue(fixture.timed.isEmpty())
            fixture.close()
        }

    @Test
    fun impossibleCalendarAndClockValuesAreRejectedWithoutSmartNormalization() =
        runBlocking {
            listOf(
                "2026-02-30 10:00",
                "2026-02-29 10:00",
                "2026-01-01 24:00",
                "2026-01-01 10:60",
                "not-a-time",
            ).forEach { invalid ->
                val fixture = fixture(timedTemplate(TimeTrackingMode.NO_LIVE_TRACKING))
                fixture.select()
                fixture.controller.dispatch(ManualActivityEntryAction.EditCompleted(invalid))
                fixture.controller.dispatch(ManualActivityEntryAction.Save)

                assertEquals(ManualEntryIssue.INVALID_DATE_TIME, invalidIssue(fixture), invalid)
                assertTrue(fixture.noLive.isEmpty(), invalid)
                fixture.close()
            }

            val leapYear = fixture(timedTemplate(TimeTrackingMode.NO_LIVE_TRACKING))
            leapYear.select()
            leapYear.controller.dispatch(ManualActivityEntryAction.EditCompleted("2024-02-29 10:00"))
            leapYear.controller.dispatch(ManualActivityEntryAction.Save)
            leapYear.awaitCommitted()
            assertEquals(Instant.parse("2024-02-29T10:00:00Z"), leapYear.noLive.single().completedAt)
            leapYear.close()
        }

    @Test
    fun overlapExposesExactlyZoneRulesCandidatesAndEachChoiceProducesItsInstant() =
        runBlocking {
            val expectedOffsets = BERLIN.rules.getValidOffsets(java.time.LocalDateTime.parse("2026-10-25T02:30"))
            assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), expectedOffsets)

            for ((offset, expected) in expectedOffsets.zip(listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z"))) {
                val fixture = fixture(timedTemplate(), zone = BERLIN)
                fixture.select()
                fixture.editTimes("2026-10-25 02:30", "2026-10-25 03:30")
                fixture.controller.dispatch(ManualActivityEntryAction.Save)
                assertEquals(
                    expectedOffsets,
                    fixture.controller.state.value.startedAmbiguity
                        ?.offsets,
                )
                assertEquals(0, fixture.overlapChecks)
                assertTrue(fixture.timed.isEmpty())

                fixture.controller.dispatch(ManualActivityEntryAction.SelectStartedOffset(offset))
                fixture.controller.dispatch(ManualActivityEntryAction.Save)
                fixture.awaitCommitted()
                assertEquals(Instant.parse(expected), fixture.timed.single().startedAt)
                fixture.close()
            }
        }

    @Test
    fun startAndCompletionAmbiguitiesAreIndependentAndOrderingUsesResolvedInstants() =
        runBlocking {
            val fixture = fixture(timedTemplate(), zone = BERLIN)
            fixture.select()
            fixture.editTimes("2026-10-25 02:45", "2026-10-25 02:30")
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.AMBIGUOUS_LOCAL_TIME, invalidIssue(fixture))

            fixture.controller.dispatch(ManualActivityEntryAction.SelectCompletedOffset(ZoneOffset.ofHours(1)))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.AMBIGUOUS_LOCAL_TIME, invalidIssue(fixture))
            assertEquals(
                ZoneOffset.ofHours(1),
                fixture.controller.state.value.completedAmbiguity
                    ?.selectedOffset,
            )

            fixture.controller.dispatch(ManualActivityEntryAction.SelectStartedOffset(ZoneOffset.ofHours(2)))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()
            assertEquals(Instant.parse("2026-10-25T00:45:00Z"), fixture.timed.single().startedAt)
            assertEquals(Instant.parse("2026-10-25T01:30:00Z"), fixture.timed.single().completedAt)

            fixture.controller.dispatch(ManualActivityEntryAction.EditStarted("2026-10-25 02:40"))
            assertNull(fixture.controller.state.value.startedAmbiguity)
            assertEquals(
                ZoneOffset.ofHours(1),
                fixture.controller.state.value.completedAmbiguity
                    ?.selectedOffset,
            )
            fixture.close()
        }

    @Test
    fun noLiveUsesTheSameGapAndOverlapPolicyWithoutStartOrOverlapQuery() =
        runBlocking {
            val fixture = fixture(timedTemplate(TimeTrackingMode.NO_LIVE_TRACKING), zone = BERLIN)
            fixture.select()
            fixture.controller.dispatch(ManualActivityEntryAction.EditCompleted("2026-03-29 02:30"))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(ManualEntryIssue.NONEXISTENT_LOCAL_TIME, invalidIssue(fixture))
            assertEquals(ManualEntryIssue.NONEXISTENT_LOCAL_TIME, fixture.controller.state.value.completedIssue)

            fixture.controller.dispatch(ManualActivityEntryAction.EditCompleted("2026-10-25 02:30"))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertEquals(
                2,
                fixture.controller.state.value.completedAmbiguity
                    ?.offsets
                    ?.size,
            )
            fixture.controller.dispatch(ManualActivityEntryAction.SelectCompletedOffset(ZoneOffset.ofHours(1)))
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()

            assertEquals(0, fixture.overlapChecks)
            assertNull(fixture.noLive.single().startedAt)
            assertEquals(Instant.parse("2026-10-25T01:30:00Z"), fixture.noLive.single().completedAt)
            fixture.close()
        }

    @Test
    fun catalogPageReplacementIsBoundedAndSelectionDoesNotHydrateRows() =
        runBlocking {
            val fixture = fixture(timedTemplate(), catalogPages = true)
            fixture.awaitCatalog()
            fixture.select()
            repeat(3) {
                fixture.controller.dispatch(ManualActivityEntryAction.LoadMore)
                fixture.awaitCatalogReads(it + 2)
                val page = (fixture.controller.state.value.catalog as ManualEntryLoad.Content).value
                assertTrue(page.size <= MANUAL_ACTIVITY_CATALOG_PAGE_SIZE)
                assertEquals(page.map { row -> row.id }.distinct().size, page.size)
                assertInstanceOf(ManualEntryLoad.Content::class.java, fixture.controller.state.value.selected)
            }
            assertEquals(listOf(TEMPLATE_ID), fixture.templateReads)
            assertEquals(listOf<Int?>(null, 49, 99, 149), fixture.catalogAfterIndexes)
            fixture.close()
        }

    @Test
    fun emptyPageAfterExactlyFullTerminalPageRetainsTheLastUsableBoundedPage() =
        runBlocking {
            val fixture = fixture(timedTemplate(), catalogItemCount = MANUAL_ACTIVITY_CATALOG_PAGE_SIZE * 2)
            fixture.awaitCatalog()
            fixture.controller.dispatch(ManualActivityEntryAction.LoadMore)
            fixture.awaitCatalogReads(2)
            val terminal = (fixture.controller.state.value.catalog as ManualEntryLoad.Content).value
            assertEquals(MANUAL_ACTIVITY_CATALOG_PAGE_SIZE, terminal.size)

            fixture.controller.dispatch(ManualActivityEntryAction.LoadMore)
            fixture.awaitCatalogReads(3)

            assertEquals(terminal, (fixture.controller.state.value.catalog as ManualEntryLoad.Content).value)
            assertTrue(!fixture.controller.state.value.canLoadMore)
            assertEquals(listOf<Int?>(null, 49, 99), fixture.catalogAfterIndexes)
            fixture.close()
        }

    @Test
    fun overlapProceedResamplesCommandTimeAndZoneChangeRestartsChecking() =
        runBlocking {
            val fixture = fixture(timedTemplate(), zone = BERLIN, overlap = true)
            fixture.select()
            fixture.editTimes("2026-09-15 10:00", "2026-09-15 11:00")
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitOverlap()
            val warnedAt = (fixture.controller.state.value.command as ManualEntryCommand.Overlap).proposal.commandAt

            fixture.zone = ZoneId.of("America/New_York")
            fixture.controller.dispatch(ManualActivityEntryAction.ProceedOverlap)
            fixture.awaitOverlapChecks(2)
            fixture.awaitOverlap()
            assertTrue(fixture.timed.isEmpty())
            assertEquals(
                fixture.zone,
                (fixture.controller.state.value.command as ManualEntryCommand.Overlap).proposal.zoneId,
            )

            fixture.overlap = false
            fixture.controller.dispatch(ManualActivityEntryAction.ProceedOverlap)
            fixture.awaitCommitted()
            assertTrue(fixture.timed.single().commandAt > warnedAt)
            assertEquals(fixture.zone, fixture.timed.single().zoneId)
            fixture.close()
        }

    @Test
    fun overlapCancelIntervalEditAndDuplicateCommitTapsCannotWriteTwice() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fixture = fixture(timedTemplate(), overlap = true, timedWriteGate = gate)
            fixture.select()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitOverlap()
            fixture.controller.dispatch(ManualActivityEntryAction.CancelOverlap)
            assertTrue(fixture.timed.isEmpty())

            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitOverlap()
            fixture.controller.dispatch(ManualActivityEntryAction.EditStarted("2026-09-16 09:59"))
            assertEquals(ManualEntryCommand.Idle, fixture.controller.state.value.command)

            fixture.overlap = false
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitting()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.controller.dispatch(ManualActivityEntryAction.ProceedOverlap)
            gate.complete(Unit)
            fixture.awaitCommitted()
            assertEquals(1, fixture.timed.size)
            fixture.close()
        }

    @Test
    fun staleWriteClearsReviewedTemplateAndRequiresExplicitReselection() =
        runBlocking {
            val fixture = fixture(timedTemplate())
            fixture.select()
            fixture.writerFailure = StaleLauncherTargetException()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitFailure()

            assertEquals(
                ManualEntryCommand.Failure(ManualEntryIssue.TEMPLATE_STALE),
                fixture.controller.state.value.command,
            )
            assertEquals(
                ManualEntryLoad.Failure(ManualEntryIssue.TEMPLATE_STALE),
                fixture.controller.state.value.selected,
            )
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            assertTrue(fixture.timed.isEmpty())

            fixture.writerFailure = null
            fixture.select()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()
            assertEquals(1, fixture.timed.size)
            fixture.close()
        }

    @Test
    fun staleSelectionCanRereadItsExactSourceAfterPagingAwayWithoutAutoCommit() =
        runBlocking {
            val fixture = fixture(timedTemplate(), catalogPages = true)
            fixture.select()
            fixture.controller.dispatch(ManualActivityEntryAction.LoadMore)
            fixture.awaitCatalogReads(2)
            assertTrue(
                (fixture.controller.state.value.catalog as ManualEntryLoad.Content).value.none { it.id == TEMPLATE_ID },
            )

            fixture.currentTemplate = timedTemplate().copy(name = "Changed", revision = 8)
            fixture.writerFailure = StaleLauncherTargetException()
            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitFailure()
            fixture.writerFailure = null

            fixture.controller.dispatch(ManualActivityEntryAction.ReviewStaleTemplate)
            fixture.awaitSelectedRevision(8)

            val selected = (fixture.controller.state.value.selected as ManualEntryLoad.Content).value
            assertEquals("Changed", selected.name)
            assertEquals(8, selected.revision)
            assertEquals(ManualEntryCommand.Idle, fixture.controller.state.value.command)
            assertTrue(fixture.timed.isEmpty())
            assertEquals(listOf(TEMPLATE_ID, TEMPLATE_ID), fixture.templateReads)

            fixture.controller.dispatch(ManualActivityEntryAction.Save)
            fixture.awaitCommitted()
            assertEquals(8, fixture.timed.single().expectedTemplateRevision)
            fixture.close()
        }

    private fun invalidIssue(fixture: Fixture) =
        (fixture.controller.state.value.command as ManualEntryCommand.Invalid).issue

    private fun fixture(
        template: ActivityTemplate,
        zone: ZoneId = ZoneOffset.UTC,
        overlap: Boolean = false,
        timedWriteGate: CompletableDeferred<Unit>? = null,
        catalogPages: Boolean = false,
        catalogItemCount: Int? = null,
    ): Fixture {
        val fixture = Fixture(template, timedWriteGate, zone, catalogPages, catalogItemCount)
        fixture.overlap = overlap
        fixture.controller =
            ManualActivityEntryController(
                fixture.scope,
                fixture::readCatalog,
                { id ->
                    fixture.templateReads += id
                    fixture.currentTemplate.takeIf { it.id == id }
                },
                { _, _ ->
                    fixture.overlapChecks++
                    fixture.overlap
                },
                { proposal ->
                    timedWriteGate?.await()
                    fixture.writerFailure?.let { throw it }
                    fixture.timed += proposal
                    execution()
                },
                { proposal ->
                    fixture.writerFailure?.let { throw it }
                    fixture.noLive += proposal
                    execution()
                },
                {
                    fixture.now = fixture.now.plusMillis(1)
                    fixture.now
                },
                { fixture.zone },
            )
        return fixture
    }

    private class Fixture(
        var currentTemplate: ActivityTemplate,
        val timedWriteGate: CompletableDeferred<Unit>?,
        var zone: ZoneId,
        val catalogPages: Boolean,
        val catalogItemCount: Int?,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var controller: ManualActivityEntryController
        var now: Instant = NOW
        var catalogReads = 0
        val catalogAfterIndexes = mutableListOf<Int?>()
        val templateReads = mutableListOf<ActivityTemplateId>()
        var overlapChecks = 0
        var overlap = false
        var writerFailure: RuntimeException? = null
        val timed = mutableListOf<ManualEntryProposal>()
        val noLive = mutableListOf<ManualEntryProposal>()

        suspend fun readCatalog(after: ReusableActivityCatalogItem?): List<ReusableActivityCatalogItem> {
            catalogReads++
            val afterIndex =
                after
                    ?.id
                    ?.value
                    ?.removePrefix("catalog-")
                    ?.toIntOrNull()
            catalogAfterIndexes += afterIndex
            if (!catalogPages && catalogItemCount == null) return listOf(catalogItem(TEMPLATE_ID, currentTemplate))
            val start = (afterIndex ?: -1) + 1
            val end = minOf(start + MANUAL_ACTIVITY_CATALOG_PAGE_SIZE, catalogItemCount ?: Int.MAX_VALUE)
            return (start until end).map { index ->
                catalogItem(if (index == 0) TEMPLATE_ID else ActivityTemplateId("catalog-$index"), currentTemplate)
                    .copy(name = "Catalog $index")
            }
        }

        suspend fun select() {
            controller.dispatch(ManualActivityEntryAction.Select(TEMPLATE_ID))
            awaitSelected()
        }

        fun editTimes(
            started: String,
            completed: String,
        ) {
            controller.dispatch(ManualActivityEntryAction.EditStarted(started))
            controller.dispatch(ManualActivityEntryAction.EditCompleted(completed))
        }

        suspend fun awaitCatalog() =
            withTimeout(2_000) { controller.state.first { it.catalog is ManualEntryLoad.Content } }

        suspend fun awaitCatalogReads(count: Int) =
            withTimeout(2_000) {
                controller.state.first {
                    catalogReads >= count &&
                        it.catalog is ManualEntryLoad.Content
                }
            }

        suspend fun awaitSelected() =
            withTimeout(2_000) { controller.state.first { it.selected is ManualEntryLoad.Content } }

        suspend fun awaitSelectedRevision(revision: Long) =
            withTimeout(2_000) {
                controller.state.first {
                    (it.selected as? ManualEntryLoad.Content)?.value?.revision == revision
                }
            }

        suspend fun awaitOverlap() =
            withTimeout(2_000) { controller.state.first { it.command is ManualEntryCommand.Overlap } }

        suspend fun awaitOverlapChecks(count: Int) =
            withTimeout(2_000) {
                controller.state.first {
                    overlapChecks >=
                        count
                }
            }

        suspend fun awaitCommitting() =
            withTimeout(2_000) {
                controller.state.first { it.command is ManualEntryCommand.Committing }
            }

        suspend fun awaitCommitted() =
            withTimeout(2_000) {
                controller.state.first { it.command is ManualEntryCommand.Committed }
            }

        suspend fun awaitFailure() =
            withTimeout(2_000) { controller.state.first { it.command is ManualEntryCommand.Failure } }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-12-01T12:00:00Z")
        private val BERLIN = ZoneId.of("Europe/Berlin")
        private val TEMPLATE_ID = ActivityTemplateId("catalog-0")
        private val NUMBER_ID = ActivityTemplateFieldId("number")
        private val CATEGORY_ID = ActivityTemplateFieldId("category")
        private val TEXT_ID = ActivityTemplateFieldId("text")
        private val OPTION_ID = CategoryOptionId("option")

        private fun catalogItem(
            id: ActivityTemplateId,
            template: ActivityTemplate,
        ) = ReusableActivityCatalogItem(
            id,
            "Catalog",
            template.timeTrackingMode,
            template.timerTarget,
            null,
            null,
            null,
            null,
        )

        private fun timedTemplate(mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH) =
            ActivityTemplate(
                TEMPLATE_ID,
                "Template",
                null,
                mode,
                if (mode == TimeTrackingMode.TIMER) Duration.ofMinutes(30) else null,
                StatisticsSeriesId("series"),
                revision = 7,
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
                            categoryOptions =
                                listOf(
                                    CategoryOption(OPTION_ID, 0, "Option"),
                                    CategoryOption(CategoryOptionId("archived"), 1, "Archived", true),
                                ),
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
                ActivityExecutionId("execution"),
                ActivitySnapshotId("snapshot"),
                ActivityExecutionContext.STANDALONE,
                StatisticsSeriesId("series"),
                ActivityExecutionStatus.COMPLETED,
                NOW,
                NOW,
                Duration.ZERO,
                ZoneOffset.UTC,
                0,
                NOW.atZone(ZoneOffset.UTC).toLocalDate(),
                ActivityCompletionReason.MANUAL_HISTORY_ENTRY,
                null,
                NOW,
                NOW,
            )
    }
}
