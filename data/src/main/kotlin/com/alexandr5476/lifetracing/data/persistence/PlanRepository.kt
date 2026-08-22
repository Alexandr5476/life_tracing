@file:Suppress("LongParameterList", "TooManyFunctions")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.PlanCalendarEntry
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanEntryTransitions
import com.alexandr5476.lifetracing.domain.PlanEntryValidator
import com.alexandr5476.lifetracing.domain.PlanListEntry
import com.alexandr5476.lifetracing.domain.PlanOverdueCalculator
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanSourceStateResolver
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable

class PlanRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val nextPlanId: () -> PlanEntryId,
    private val activitySnapshotFactory: ActivitySnapshotFactory,
    private val sequenceSnapshotFactory: SequenceSnapshotFactory,
    private val zoneIdProvider: CurrentZoneIdProvider,
) {
    fun createActivityPlanFromTemplate(
        sourceTemplateId: ActivityTemplateId,
        target: PlanTarget,
        createdAt: Instant,
        updatedAt: Instant = createdAt,
    ): PlanEntry =
        transaction {
            require(updatedAt >= createdAt) { "Plan update cannot precede creation" }
            val template =
                requireNotNull(database.activityTemplateDao().getAggregate(sourceTemplateId.value)) {
                    "Unknown ActivityTemplate: ${sourceTemplateId.value}"
                }.toDomain()
            require(template.deletedAt == null) { "Archived ActivityTemplate cannot create a new Plan" }
            val snapshot = activitySnapshotFactory.fromTemplate(template, createdAt)
            database.activitySnapshotDao().insertAggregate(snapshot.toEntityAggregate())
            val plan =
                PlanEntry(
                    nextPlanId(),
                    PlanTrackableKind.ACTIVITY,
                    template.id,
                    null,
                    template.revision,
                    snapshot.id,
                    null,
                    target,
                    PlanEntryStatus.PLANNED,
                    null,
                    null,
                    createdAt,
                    updatedAt,
                    null,
                    null,
                ).also(PlanEntryValidator::requireValid)
            database.planEntryDao().insert(plan.toEntity())
            plan
        }

    fun createSequencePlanFromTemplate(
        sourceTemplateId: SequenceTemplateId,
        target: PlanTarget,
        createdAt: Instant,
        updatedAt: Instant = createdAt,
    ): PlanEntry =
        transaction {
            require(updatedAt >= createdAt) { "Plan update cannot precede creation" }
            val template =
                requireNotNull(database.sequenceTemplateDao().getAggregate(sourceTemplateId.value)) {
                    "Unknown SequenceTemplate: ${sourceTemplateId.value}"
                }.toDomain()
            require(template.deletedAt == null) { "Archived SequenceTemplate cannot create a new Plan" }
            val modes = activityModesFor(template.nodes.flatMap { it.activitySnapshotIds() })
            val snapshot = sequenceSnapshotFactory.fromTemplate(template, modes, createdAt)
            database.sequenceSnapshotDao().insertAggregate(snapshot.toEntityAggregate())
            val plan =
                PlanEntry(
                    nextPlanId(),
                    PlanTrackableKind.SEQUENCE,
                    null,
                    template.id,
                    template.revision,
                    null,
                    snapshot.id,
                    target,
                    PlanEntryStatus.PLANNED,
                    null,
                    null,
                    createdAt,
                    updatedAt,
                    null,
                    null,
                ).also(PlanEntryValidator::requireValid)
            database.planEntryDao().insert(plan.toEntity())
            plan
        }

    fun getPlan(id: PlanEntryId): PlanEntry? = transaction { loadValidPlan(id.value) }

    fun isEngaged(id: PlanEntryId): Boolean =
        transaction {
            val plan = requireNotNull(loadValidPlan(id.value)) { "Unknown Plan: ${id.value}" }
            isEngaged(plan)
        }

    fun isOverdue(
        plan: PlanEntry,
        now: Instant,
    ): Boolean = PlanOverdueCalculator.isOverdue(plan, now, zoneIdProvider.currentZoneId())

    fun cancelPlan(
        id: PlanEntryId,
        at: Instant,
    ): PlanEntry =
        transaction {
            val plan = requireMutablePlan(id)
            PlanEntryTransitions.cancel(plan, at)
            check(database.planEntryDao().cancel(id.value, at.toEpochMilli()) == 1) { "Plan changed concurrently" }
            requireNotNull(loadValidPlan(id.value))
        }

    fun restoreCancelledPlan(
        id: PlanEntryId,
        at: Instant,
    ): PlanEntry =
        transaction {
            val plan = requireNotNull(loadValidPlan(id.value)) { "Unknown Plan: ${id.value}" }
            PlanEntryTransitions.restore(plan, at)
            check(database.planEntryDao().restore(id.value, at.toEpochMilli()) == 1) { "Plan changed concurrently" }
            requireNotNull(loadValidPlan(id.value))
        }

    fun reschedulePlanEntry(
        id: PlanEntryId,
        target: PlanTarget,
        at: Instant,
    ): PlanEntry =
        transaction {
            val plan = requireMutablePlan(id)
            PlanEntryTransitions.reschedule(plan, target, at)
            val shape = target.persistenceShape()
            check(
                database.planEntryDao().reschedule(
                    id.value,
                    target.precision.name,
                    shape.plannedDay,
                    shape.plannedWeekStart,
                    shape.plannedMonth,
                    shape.scheduledInstantMs,
                    shape.creationZoneId,
                    at.toEpochMilli(),
                ) == 1,
            ) { "Plan changed concurrently" }
            requireNotNull(loadValidPlan(id.value))
        }

    fun updatePlanFromTemplate(
        id: PlanEntryId,
        updatedAt: Instant,
    ): PlanEntry =
        transaction {
            val plan = requireMutablePlan(id)
            require(updatedAt >= plan.updatedAt) { "Plan update time is out of order" }
            when (plan.kind) {
                PlanTrackableKind.ACTIVITY -> updateActivityPlan(plan, updatedAt)
                PlanTrackableKind.SEQUENCE -> updateSequencePlan(plan, updatedAt)
            }
            requireNotNull(loadValidPlan(id.value))
        }

    fun getDayPlans(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<PlanCalendarEntry> {
        require(endDate >= startDate) { "Calendar range is reversed" }
        return transaction {
            val zone = zoneIdProvider.currentZoneId()
            val start = startDate.atStartOfDay(zone).toInstant()
            val end = endDate.plusDays(1).atStartOfDay(zone).toInstant()
            val floating = database.planEntryDao().getFloatingDays(startDate.toString(), endDate.toString())
            val exact = database.planEntryDao().getExactDays(start.toEpochMilli(), end.toEpochMilli())
            (floating + exact)
                .map { row ->
                    val plan = row.toDomain()
                    val zoned = (plan.target as? PlanTarget.ExactDay)?.scheduledAt?.atZone(zone)
                    PlanCalendarEntry(
                        plan,
                        zoned?.toLocalDate() ?: (plan.target as PlanTarget.FloatingDay).date,
                        zoned?.toLocalTime(),
                    )
                }.sortedWith(compareBy(PlanCalendarEntry::effectiveLocalDate, PlanCalendarEntry::exactLocalTime))
        }
    }

    fun getWeekPlans(weekStart: LocalDate): List<PlanEntry> =
        transaction {
            require(weekStart.dayOfWeek == DayOfWeek.MONDAY) { "Plan week must start on Monday" }
            database.planEntryDao().getWeek(weekStart.toString()).map { validatePlanRow(it) }
        }

    fun getMonthPlans(month: YearMonth): List<PlanEntry> =
        transaction { database.planEntryDao().getMonth(month.toString()).map { validatePlanRow(it) } }

    fun getCancelledPlans(): List<PlanEntry> =
        transaction { database.planEntryDao().getCancelled().map { validatePlanRow(it) } }

    fun getListEntries(plans: List<PlanEntry>): List<PlanListEntry> =
        transaction {
            val activitySnapshotIds = plans.mapNotNull { it.activitySnapshotId?.value }.distinct()
            val sequenceSnapshotIds = plans.mapNotNull { it.sequenceSnapshotId?.value }.distinct()
            val summaries =
                (
                    if (activitySnapshotIds.isEmpty()) {
                        emptyList()
                    } else {
                        database.planEntryDao().activitySummaries(activitySnapshotIds)
                    }
                ) + (
                    if (sequenceSnapshotIds.isEmpty()) {
                        emptyList()
                    } else {
                        database.planEntryDao().sequenceSummaries(sequenceSnapshotIds)
                    }
                )
            val summaryById = summaries.associateBy(PlanSnapshotSummaryRow::id)
            val activitySourceIds = plans.mapNotNull { it.sourceActivityTemplateId?.value }.distinct()
            val sequenceSourceIds = plans.mapNotNull { it.sourceSequenceTemplateId?.value }.distinct()
            val sources =
                (
                    if (activitySourceIds.isEmpty()) {
                        emptyList()
                    } else {
                        database.planEntryDao().activitySources(activitySourceIds)
                    }
                ) + (
                    if (sequenceSourceIds.isEmpty()) {
                        emptyList()
                    } else {
                        database.planEntryDao().sequenceSources(sequenceSourceIds)
                    }
                )
            val sourceById = sources.associateBy(PlanSourceMetadataRow::id)
            plans.map { plan ->
                val snapshotId = plan.activitySnapshotId?.value ?: requireNotNull(plan.sequenceSnapshotId).value
                val sourceId = plan.sourceActivityTemplateId?.value ?: plan.sourceSequenceTemplateId?.value
                val summary = requireNotNull(summaryById[snapshotId]) { "Plan snapshot summary is missing" }
                val source = sourceId?.let(sourceById::get)
                PlanListEntry(
                    plan,
                    summary.name,
                    summary.shortComment,
                    PlanSourceStateResolver.resolve(
                        plan.sourceRevision,
                        source?.revision,
                        source?.deletedAtMs != null,
                    ),
                )
            }
        }

    fun sourceState(plan: PlanEntry): PlanSourceState =
        transaction {
            when (plan.kind) {
                PlanTrackableKind.ACTIVITY -> {
                    val source = plan.sourceActivityTemplateId?.let { database.activityTemplateDao().getById(it.value) }
                    PlanSourceStateResolver.resolve(plan.sourceRevision, source?.revision, source?.deletedAtMs != null)
                }
                PlanTrackableKind.SEQUENCE -> {
                    val source = plan.sourceSequenceTemplateId?.let { database.sequenceTemplateDao().getById(it.value) }
                    PlanSourceStateResolver.resolve(plan.sourceRevision, source?.revision, source?.deletedAtMs != null)
                }
            }
        }

    private fun updateActivityPlan(
        plan: PlanEntry,
        at: Instant,
    ) {
        val sourceId = requireNotNull(plan.sourceActivityTemplateId) { "Plan source is unavailable" }
        val template =
            requireNotNull(database.activityTemplateDao().getAggregate(sourceId.value)) { "Plan source is unavailable" }
                .toDomain()
        require(template.deletedAt == null) { "Archived Plan source cannot update a snapshot" }
        val oldId = requireNotNull(plan.activitySnapshotId)
        val replacement = activitySnapshotFactory.fromTemplate(template, at)
        database.activitySnapshotDao().insertAggregate(replacement.toEntityAggregate())
        check(
            database.planEntryDao().replaceActivitySnapshot(
                plan.id.value,
                oldId.value,
                replacement.id.value,
                template.revision,
                at.toEpochMilli(),
            ) == 1,
        ) { "Plan changed concurrently" }
        pruneActivitySnapshot(oldId)
    }

    private fun updateSequencePlan(
        plan: PlanEntry,
        at: Instant,
    ) {
        val sourceId = requireNotNull(plan.sourceSequenceTemplateId) { "Plan source is unavailable" }
        val template =
            requireNotNull(database.sequenceTemplateDao().getAggregate(sourceId.value)) { "Plan source is unavailable" }
                .toDomain()
        require(template.deletedAt == null) { "Archived Plan source cannot update a snapshot" }
        val oldId = requireNotNull(plan.sequenceSnapshotId)
        val modes = activityModesFor(template.nodes.flatMap { it.activitySnapshotIds() })
        val replacement = sequenceSnapshotFactory.fromTemplate(template, modes, at)
        database.sequenceSnapshotDao().insertAggregate(replacement.toEntityAggregate())
        check(
            database.planEntryDao().replaceSequenceSnapshot(
                plan.id.value,
                oldId.value,
                replacement.id.value,
                template.revision,
                at.toEpochMilli(),
            ) == 1,
        ) { "Plan changed concurrently" }
        if (!database.planEntryDao().hasSequencePlanReference(oldId.value) &&
            !database.planEntryDao().hasSequenceExecutionReference(oldId.value)
        ) {
            database.sequenceSnapshotDao().hardDeleteAndPruneOwnedActivitySnapshots(oldId.value)
        }
    }

    private fun requireMutablePlan(id: PlanEntryId): PlanEntry {
        val plan = requireNotNull(loadValidPlan(id.value)) { "Unknown Plan: ${id.value}" }
        require(plan.status == PlanEntryStatus.PLANNED) { "Plan is not planned" }
        require(!isEngaged(plan)) { "Plan has a linked live execution" }
        return plan
    }

    private fun isEngaged(plan: PlanEntry): Boolean =
        when (plan.kind) {
            PlanTrackableKind.ACTIVITY -> database.planEntryDao().hasLiveActivity(plan.id.value)
            PlanTrackableKind.SEQUENCE -> database.planEntryDao().hasLiveSequence(plan.id.value)
        }

    private fun loadValidPlan(id: String): PlanEntry? = database.planEntryDao().getById(id)?.let(::validatePlanRow)

    @Suppress("LongMethod")
    private fun validatePlanRow(row: PlanEntryEntity): PlanEntry {
        val plan = row.toDomain()
        when (plan.kind) {
            PlanTrackableKind.ACTIVITY -> {
                val snapshot =
                    requireNotNull(
                        database.activitySnapshotDao().getAggregate(requireNotNull(plan.activitySnapshotId).value),
                    ) {
                        "Plan ActivitySnapshot is missing"
                    }.toDomain()
                plan.sourceActivityTemplateId?.let { source ->
                    snapshot.sourceTemplateId?.let {
                        require(it == source) { "Plan and ActivitySnapshot source mismatch" }
                    }
                    require(snapshot.sourceRevision == plan.sourceRevision) {
                        "Plan and ActivitySnapshot revision mismatch"
                    }
                }
                plan.fulfilledActivityExecutionId?.let { executionId ->
                    val execution =
                        requireNotNull(database.activityExecutionDao().getAggregate(executionId.value)) {
                            "Fulfilled ActivityExecution is missing"
                        }.toDomain()
                    require(
                        execution.context == ActivityExecutionContext.STANDALONE &&
                            execution.planEntryId == plan.id &&
                            execution.snapshotId == snapshot.id &&
                            execution.status == ActivityExecutionStatus.COMPLETED,
                    ) { "Plan fulfillment ActivityExecution linkage is invalid" }
                }
            }
            PlanTrackableKind.SEQUENCE -> {
                val snapshot =
                    requireNotNull(
                        database.sequenceSnapshotDao().getAggregate(requireNotNull(plan.sequenceSnapshotId).value),
                    ) {
                        "Plan SequenceSnapshot is missing"
                    }.toDomain()
                plan.sourceSequenceTemplateId?.let { source ->
                    snapshot.sourceTemplateId?.let {
                        require(it == source) { "Plan and SequenceSnapshot source mismatch" }
                    }
                    require(snapshot.sourceRevision == plan.sourceRevision) {
                        "Plan and SequenceSnapshot revision mismatch"
                    }
                }
                plan.fulfilledSequenceExecutionId?.let { executionId ->
                    val execution =
                        requireNotNull(database.sequenceExecutionDao().getAggregate(executionId.value)) {
                            "Fulfilled SequenceExecution is missing"
                        }.toDomain()
                    require(
                        execution.planEntryId == plan.id &&
                            execution.snapshotId == snapshot.id &&
                            execution.status == SequenceExecutionStatus.COMPLETED,
                    ) { "Plan fulfillment SequenceExecution linkage is invalid" }
                }
            }
        }
        return plan
    }

    private fun activityModesFor(ids: List<ActivitySnapshotId>): Map<ActivitySnapshotId, TimeTrackingMode> =
        database
            .activitySnapshotDao()
            .getAggregates(ids.distinct().map(ActivitySnapshotId::value))
            .associate {
                val snapshot = it.toDomain()
                snapshot.id to snapshot.timeTrackingMode
            }.also { require(it.keys == ids.toSet()) { "SequenceTemplate is missing ActivitySnapshot metadata" } }

    private fun pruneActivitySnapshot(id: ActivitySnapshotId) {
        val dao = database.planEntryDao()
        val isReferenced =
            dao.hasActivityPlanReference(id.value) ||
                dao.hasSequenceNodeReference(id.value) ||
                dao.hasSequenceSnapshotNodeReference(id.value) ||
                dao.hasActivityExecutionReference(id.value) ||
                dao.hasSequenceOccurrenceReference(id.value)
        if (!isReferenced) check(database.activitySnapshotDao().hardDelete(id.value) == 1)
    }

    private fun com.alexandr5476.lifetracing.domain.SequenceNode.activitySnapshotIds(): List<ActivitySnapshotId> =
        when (this) {
            is com.alexandr5476.lifetracing.domain.ActivityStep -> listOf(activitySnapshotId)
            is com.alexandr5476.lifetracing.domain.SequenceRepeatBlock -> children.map { it.activitySnapshotId }
        }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    companion object {
        fun create(
            context: Context,
            zoneIdProvider: CurrentZoneIdProvider = CurrentZoneIdProvider(ZoneId::systemDefault),
        ): PlanRepository =
            PlanRepository(
                LifeTracingDatabase.builder(context.applicationContext, "lifetracing.db").build(),
                { PlanEntryId(UUID.randomUUID().toString()) },
                ActivitySnapshotFactory(
                    { ActivitySnapshotId(UUID.randomUUID().toString()) },
                    { ActivitySnapshotFieldId(UUID.randomUUID().toString()) },
                    { ActivitySnapshotCategoryOptionId(UUID.randomUUID().toString()) },
                ),
                SequenceSnapshotFactory(
                    { SequenceSnapshotId(UUID.randomUUID().toString()) },
                    { SequenceSnapshotFieldId(UUID.randomUUID().toString()) },
                    { SequenceSnapshotCategoryOptionId(UUID.randomUUID().toString()) },
                    { SequenceSnapshotNodeId(UUID.randomUUID().toString()) },
                ),
                zoneIdProvider,
            )
    }
}

private data class PlanTargetPersistenceShape(
    val plannedDay: String?,
    val plannedWeekStart: String?,
    val plannedMonth: String?,
    val scheduledInstantMs: Long?,
    val creationZoneId: String?,
)

private fun PlanTarget.persistenceShape() =
    when (this) {
        is PlanTarget.FloatingDay -> PlanTargetPersistenceShape(date.toString(), null, null, null, null)
        is PlanTarget.ExactDay ->
            PlanTargetPersistenceShape(null, null, null, scheduledAt.toEpochMilli(), creationZoneId?.id)
        is PlanTarget.Week -> PlanTargetPersistenceShape(null, weekStart.toString(), null, null, null)
        is PlanTarget.Month -> PlanTargetPersistenceShape(null, null, month.toString(), null, null)
    }
