@file:Suppress("LongMethod") // Focused hydration keeps the canonical validation boundary in one transaction.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyPlan
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanDayPresence
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryValidator
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSourceStateResolver
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.PlanningPrecision
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import java.time.ZoneId
import java.util.concurrent.Callable

/** Canonical bounded Plan reads for the first Day/Week production surface. */
class PlanReadRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val zoneIdProvider: CurrentZoneIdProvider,
    private val liveSessionRepository: LiveSessionRepository = LiveSessionRepository.create(database),
) {
    private val daily = DailyReadRepository(database, zoneIdProvider, liveSessionRepository)

    fun getWeek(query: WeekPlanQuery): WeekPlanRead =
        database.runInTransaction(
            Callable {
                val zone = zoneIdProvider.currentZoneId()
                val active = liveSessionRepository.getActiveRuntimeLocked()
                val byDate =
                    (0L until WEEK_DAY_COUNT).associate { offset ->
                        val date = query.weekStart.plusDays(offset)
                        date to
                            daily.loadPlans(
                                date,
                                query.now,
                                zone,
                                active,
                                includeWeek = date == query.selectedDate,
                            )
                    }
                val selected = byDate.getValue(query.selectedDate)
                val dayRows = selected.filter { it.plan.target.precision == PlanningPrecision.DAY }
                val weekRows = selected.filter { it.plan.target.precision == PlanningPrecision.WEEK }
                require((dayRows + weekRows).map { it.plan.id }.distinct().size == dayRows.size + weekRows.size) {
                    "Week Plan projection contains duplicate Plan identities"
                }
                WeekPlanRead(
                    query.weekStart,
                    query.selectedDate,
                    dayRows.map { it.toReadRow() },
                    weekRows.map { it.toReadRow() },
                    byDate.map { (date, rows) ->
                        PlanDayPresence(date, rows.count { it.plan.target.precision == PlanningPrecision.DAY })
                    },
                )
            },
        )

    fun getFocusedAction(id: PlanEntryId): FocusedPlanAction =
        database.runInTransaction(
            Callable {
                val plan =
                    requireNotNull(database.planEntryDao().getById(id.value)) {
                        "Unknown Plan: ${id.value}"
                    }.toDomain()
                require(plan.target.precision != PlanningPrecision.MONTH) { "Month Plan actions are deferred" }
                val sourceStates = daily.loadSourceStates(listOf(plan))
                val active = liveSessionRepository.getActiveRuntimeLocked()
                val engagement = daily.loadEngagement(listOf(plan), active).getValue(plan.id)
                val activityLinks =
                    database
                        .planEntryDao()
                        .activityExecutionLinks(listOfNotNull(plan.fulfilledActivityExecutionId?.value))
                        .associateBy(PlanActivityExecutionLinkRow::id)
                val sequenceLinks =
                    database
                        .planEntryDao()
                        .sequenceExecutionLinks(listOfNotNull(plan.fulfilledSequenceExecutionId?.value))
                        .associateBy(PlanSequenceExecutionLinkRow::id)
                val activitySnapshots =
                    database
                        .activitySnapshotDao()
                        .getAggregates(
                            (
                                listOfNotNull(plan.activitySnapshotId?.value) +
                                    activityLinks.values.map(PlanActivityExecutionLinkRow::snapshotId)
                            ).distinct(),
                        ).map(ActivitySnapshotAggregateEntity::toDomain)
                        .associateBy(ActivityConfigSnapshot::id)
                val sequenceMetadata =
                    database
                        .sequenceSnapshotDao()
                        .getDailyPlanMetadata(listOfNotNull(plan.sequenceSnapshotId?.value))
                        .map { daily.run { it.toDailyPlanMetadata() } }
                        .associateBy { it.id }
                daily.validatePlanSnapshotsAndFulfillment(
                    listOf(plan),
                    activitySnapshots,
                    sequenceMetadata,
                    activityLinks,
                    sequenceLinks,
                )
                val snapshot =
                    when (plan.kind) {
                        PlanTrackableKind.ACTIVITY ->
                            FocusedPlanAction.Snapshot.Activity(loadFocusedActivitySnapshot(plan))
                        PlanTrackableKind.SEQUENCE ->
                            FocusedPlanAction.Snapshot.Sequence(loadFocusedSequenceSnapshot(plan))
                    }
                FocusedPlanAction(
                    PlanActionIdentity(
                        plan.id,
                        plan.kind,
                        plan.activitySnapshotId,
                        plan.sequenceSnapshotId,
                        plan.target,
                        plan.status,
                        plan.sourceRevision,
                        plan.updatedAt,
                    ),
                    sourceStates.getValue(plan.id),
                    engagement,
                    snapshot,
                )
            },
        )

    fun getCancelledPage(query: CancelledPlanPageQuery): CancelledPlanPage =
        database.runInTransaction(
            Callable {
                val rows = database.planEntryDao().getCancelledSupportedPage(query.limit + 1, query.offset)
                val plans = rows.map { it.toDomain().also(PlanEntryValidator::requireValid) }
                daily.loadEngagement(plans, liveSessionRepository.getActiveRuntimeLocked())
                val page = plans.take(query.limit)
                CancelledPlanPage(page.toSummaryRows(), plans.size > query.limit)
            },
        )

    private fun List<PlanEntry>.toSummaryRows(): List<PlanReadRow> {
        if (isEmpty()) return emptyList()
        val activityIds = mapNotNull { it.activitySnapshotId?.value }.distinct()
        val sequenceIds = mapNotNull { it.sequenceSnapshotId?.value }.distinct()
        val summaries =
            activityIds.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap {
                database.planEntryDao().activitySummaries(it)
            } +
                sequenceIds.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap {
                    database.planEntryDao().sequenceSummaries(it)
                }
        val bySnapshot = summaries.associateBy(PlanSnapshotSummaryRow::id)
        require(bySnapshot.keys == (activityIds + sequenceIds).toSet()) {
            "Cancelled Plan references a missing snapshot"
        }
        val activitySources =
            mapNotNull { it.sourceActivityTemplateId?.value }
                .distinct()
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap { database.planEntryDao().activitySources(it) }
                .associateBy(PlanSourceMetadataRow::id)
        val sequenceSources =
            mapNotNull { it.sourceSequenceTemplateId?.value }
                .distinct()
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap { database.planEntryDao().sequenceSources(it) }
                .associateBy(PlanSourceMetadataRow::id)
        return map { plan ->
            val snapshotId = plan.activitySnapshotId?.value ?: requireNotNull(plan.sequenceSnapshotId).value
            val summary = bySnapshot.getValue(snapshotId)
            val sourceId = plan.sourceActivityTemplateId?.value ?: plan.sourceSequenceTemplateId?.value
            if (sourceId != null) {
                summary.sourceTemplateId?.let { require(it == sourceId) { "Plan and snapshot source mismatch" } }
                require(summary.sourceRevision == plan.sourceRevision) { "Plan and snapshot revision mismatch" }
            }
            val source =
                when (plan.kind) {
                    PlanTrackableKind.ACTIVITY -> plan.sourceActivityTemplateId?.value?.let(activitySources::get)
                    PlanTrackableKind.SEQUENCE -> plan.sourceSequenceTemplateId?.value?.let(sequenceSources::get)
                }
            PlanReadRow(
                plan,
                null,
                null,
                summary.name,
                summary.shortComment,
                PlanSourceStateResolver.resolve(plan.sourceRevision, source?.revision, source?.deletedAtMs != null),
                false,
                false,
            )
        }
    }

    private fun DailyPlan.toReadRow() =
        PlanReadRow(
            plan,
            effectiveLocalDate,
            exactLocalTime,
            snapshot.title,
            snapshot.shortComment,
            sourceState,
            engaged,
            overdue,
        )

    private fun loadFocusedActivitySnapshot(plan: PlanEntry): ActivityConfigSnapshot =
        requireNotNull(
            database.activitySnapshotDao().getAggregate(requireNotNull(plan.activitySnapshotId).value),
        ) {
            "Plan ActivitySnapshot is missing"
        }.toDomain().also { validateActivitySnapshot(plan, it) }

    private fun loadFocusedSequenceSnapshot(plan: PlanEntry): SequenceConfigSnapshot =
        requireNotNull(
            database.sequenceSnapshotDao().getAggregate(requireNotNull(plan.sequenceSnapshotId).value),
        ) {
            "Plan SequenceSnapshot is missing"
        }.toDomain().also { validateSequenceSnapshot(plan, it) }

    private fun validateActivitySnapshot(
        plan: PlanEntry,
        snapshot: ActivityConfigSnapshot,
    ) {
        plan.sourceActivityTemplateId?.let { source ->
            snapshot.sourceTemplateId?.let { require(it == source) { "Plan and Activity snapshot source mismatch" } }
            require(snapshot.sourceRevision == plan.sourceRevision) { "Plan and Activity snapshot revision mismatch" }
        }
    }

    private fun validateSequenceSnapshot(
        plan: PlanEntry,
        snapshot: SequenceConfigSnapshot,
    ) {
        plan.sourceSequenceTemplateId?.let { source ->
            snapshot.sourceTemplateId?.let { require(it == source) { "Plan and Sequence snapshot source mismatch" } }
            require(snapshot.sourceRevision == plan.sourceRevision) { "Plan and Sequence snapshot revision mismatch" }
        }
    }

    companion object {
        private const val WEEK_DAY_COUNT = 7L

        fun create(
            context: Context,
            zoneIdProvider: CurrentZoneIdProvider = CurrentZoneIdProvider(ZoneId::systemDefault),
        ): PlanReadRepository {
            val database = LifeTracingDatabase.builder(context.applicationContext, "lifetracing.db").build()
            return PlanReadRepository(database, zoneIdProvider, LiveSessionRepository.create(database))
        }

        private const val SQLITE_BIND_CHUNK_SIZE = 900
    }
}
