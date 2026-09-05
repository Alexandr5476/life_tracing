@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "NestedBlockDepth",
    "TooManyFunctions",
) // Explicit Plan-link and five-state runtime validation stays visible at the canonical read boundary.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityHistoricalSnapshotPolicy
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyPlan
import com.alexandr5476.lifetracing.domain.DailyPlanSnapshot
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.DailySequenceOccurrence
import com.alexandr5476.lifetracing.domain.DailySequencePlanMetadata
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanEntryValidator
import com.alexandr5476.lifetracing.domain.PlanOverdueCalculator
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanSourceStateResolver
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.nextRemainingOccurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Callable

class DailyReadRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val zoneIdProvider: CurrentZoneIdProvider,
    private val liveSessionRepository: LiveSessionRepository = LiveSessionRepository.create(database),
) {
    private val history = HistoryReadRepository(database)

    fun getDaily(query: DailyQuery): DailyRead =
        database.runInTransaction(
            Callable {
                val zone = zoneIdProvider.currentZoneId()
                val today = query.now.atZone(zone).toLocalDate()
                val activeRuntime = liveSessionRepository.getActiveRuntimeLocked()
                val plans = loadPlans(query.selectedDate, query.now, zone, activeRuntime)
                DailyRead(
                    dayPlans =
                        plans.filter {
                            it.plan.target.precision ==
                                com.alexandr5476.lifetracing.domain.PlanningPrecision.DAY
                        },
                    weekPlans =
                        plans.filter {
                            it.plan.target.precision ==
                                com.alexandr5476.lifetracing.domain.PlanningPrecision.WEEK
                        },
                    completedHistory =
                        history.getCompletedRootsLocked(
                            CompletedHistoryQuery(
                                HistoryDateRange(query.selectedDate, query.selectedDate),
                                query.completedHistoryLimit,
                            ),
                        ),
                    active = activeRuntime?.takeIf { query.selectedDate == today }?.toDailyActive(),
                )
            },
        )

    private fun loadPlans(
        selectedDate: LocalDate,
        now: java.time.Instant,
        zone: ZoneId,
        activeRuntime: ActiveRuntime?,
    ): List<DailyPlan> {
        val start = selectedDate.atStartOfDay(zone).toInstant()
        val end = selectedDate.plusDays(1).atStartOfDay(zone).toInstant()
        val weekStart = selectedDate.with(DayOfWeek.MONDAY)
        val rows =
            database.planEntryDao().getDailyFloatingDay(selectedDate.toString()) +
                database.planEntryDao().getDailyExactDay(start.toEpochMilli(), end.toEpochMilli()) +
                database.planEntryDao().getDailyWeek(weekStart.toString())
        val plans = rows.map { it.toDomain().also(PlanEntryValidator::requireValid) }
        require(plans.map(PlanEntry::id).distinct().size == plans.size) { "Daily Plan selectors returned duplicates" }

        val fulfilledActivityLinks =
            plans
                .mapNotNull { it.fulfilledActivityExecutionId?.value }
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::activityExecutionLinks)
                .associateBy(PlanActivityExecutionLinkRow::id)
        val fulfilledSequenceLinks =
            plans
                .mapNotNull { it.fulfilledSequenceExecutionId?.value }
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::sequenceExecutionLinks)
                .associateBy(PlanSequenceExecutionLinkRow::id)

        val activitySnapshotIds =
            (
                plans.mapNotNull { it.activitySnapshotId?.value } +
                    fulfilledActivityLinks.values.map(PlanActivityExecutionLinkRow::snapshotId)
            ).distinct()
        val activitySnapshots =
            activitySnapshotIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.activitySnapshotDao()::getAggregates)
                .map(ActivitySnapshotAggregateEntity::toDomain)
                .associateBy(ActivityConfigSnapshot::id)
        require(activitySnapshots.keys.map(ActivitySnapshotId::value).toSet() == activitySnapshotIds.toSet()) {
            "Daily Plan references a missing Activity snapshot"
        }
        val sequenceSnapshotIds = plans.mapNotNull { it.sequenceSnapshotId?.value }.distinct()
        val sequenceSnapshots =
            sequenceSnapshotIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.sequenceSnapshotDao()::getDailyPlanMetadata)
                .map { it.toDailyPlanMetadata() }
                .associateBy(DailySequencePlanMetadata::id)
        require(sequenceSnapshots.keys.map(SequenceSnapshotId::value).toSet() == sequenceSnapshotIds.toSet()) {
            "Daily Plan references a missing Sequence snapshot"
        }
        validatePlanSnapshotsAndFulfillment(
            plans,
            activitySnapshots,
            sequenceSnapshots,
            fulfilledActivityLinks,
            fulfilledSequenceLinks,
        )

        val sourceStates = loadSourceStates(plans)
        val engagement = loadEngagement(plans, activeRuntime)
        return plans
            .map { plan ->
                val target = plan.target
                val exact = (target as? PlanTarget.ExactDay)?.scheduledAt?.atZone(zone)
                val effectiveDate =
                    when (target) {
                        is PlanTarget.FloatingDay -> target.date
                        is PlanTarget.ExactDay -> exact!!.toLocalDate()
                        is PlanTarget.Week -> selectedDate
                        is PlanTarget.Month -> error("Month Plan is outside Daily")
                    }
                require(effectiveDate == selectedDate) { "Daily Plan selector returned a Plan for another date" }
                DailyPlan(
                    plan,
                    effectiveDate,
                    exact?.toLocalTime(),
                    when (plan.kind) {
                        PlanTrackableKind.ACTIVITY ->
                            DailyPlanSnapshot.Activity(
                                activitySnapshots.getValue(requireNotNull(plan.activitySnapshotId)),
                            )
                        PlanTrackableKind.SEQUENCE ->
                            DailyPlanSnapshot.Sequence(
                                sequenceSnapshots.getValue(requireNotNull(plan.sequenceSnapshotId)),
                            )
                    },
                    sourceStates.getValue(plan.id),
                    engagement[plan.id] == true,
                    PlanOverdueCalculator.isOverdue(plan, now, zone),
                )
            }.sortedWith(
                compareBy<DailyPlan> { it.plan.target.precision }
                    .thenBy { it.exactLocalTime ?: LocalTime.MAX }
                    .thenBy { it.plan.createdAt }
                    .thenBy { it.plan.id.value },
            )
    }

    private fun validatePlanSnapshotsAndFulfillment(
        plans: List<PlanEntry>,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
        sequenceSnapshots: Map<SequenceSnapshotId, DailySequencePlanMetadata>,
        activityLinks: Map<String, PlanActivityExecutionLinkRow>,
        sequenceLinks: Map<String, PlanSequenceExecutionLinkRow>,
    ) {
        plans.forEach { plan ->
            when (plan.kind) {
                PlanTrackableKind.ACTIVITY -> {
                    val snapshot = activitySnapshots.getValue(requireNotNull(plan.activitySnapshotId))
                    plan.sourceActivityTemplateId?.let { source ->
                        snapshot.sourceTemplateId?.let {
                            require(
                                it == source,
                            ) { "Plan and Activity snapshot source mismatch" }
                        }
                        require(
                            snapshot.sourceRevision == plan.sourceRevision,
                        ) { "Plan and Activity snapshot revision mismatch" }
                    }
                    plan.fulfilledActivityExecutionId?.let { id ->
                        val link = requireNotNull(activityLinks[id.value]) { "Fulfilled Activity execution is missing" }
                        val executionSnapshot = activitySnapshots.getValue(ActivitySnapshotId(link.snapshotId))
                        require(
                            link.contextType == "STANDALONE" &&
                                link.planEntryId == plan.id.value &&
                                link.status == "COMPLETED" &&
                                (
                                    executionSnapshot.id == snapshot.id ||
                                        ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(
                                            snapshot,
                                            executionSnapshot,
                                        )
                                ),
                        ) { "Plan fulfillment Activity execution linkage is invalid" }
                    }
                }
                PlanTrackableKind.SEQUENCE -> {
                    val snapshot = sequenceSnapshots.getValue(requireNotNull(plan.sequenceSnapshotId))
                    plan.sourceSequenceTemplateId?.let { source ->
                        snapshot.sourceTemplateId?.let {
                            require(
                                it == source,
                            ) { "Plan and Sequence snapshot source mismatch" }
                        }
                        require(
                            snapshot.sourceRevision == plan.sourceRevision,
                        ) { "Plan and Sequence snapshot revision mismatch" }
                    }
                    plan.fulfilledSequenceExecutionId?.let { id ->
                        val link = requireNotNull(sequenceLinks[id.value]) { "Fulfilled Sequence execution is missing" }
                        require(
                            link.planEntryId == plan.id.value &&
                                link.snapshotId == snapshot.id.value &&
                                link.status in setOf("COMPLETED", "ENDED_EARLY") &&
                                link.endedAtMs == plan.fulfilledAt?.toEpochMilli(),
                        ) { "Plan fulfillment Sequence execution linkage is invalid" }
                    }
                }
            }
        }
    }

    private fun DailySequencePlanMetadataEntity.toDailyPlanMetadata() =
        DailySequencePlanMetadata(
            SequenceSnapshotId(id),
            name,
            shortComment,
            sourceTemplateId?.let(::SequenceTemplateId),
            sourceRevision,
        )

    private fun loadSourceStates(plans: List<PlanEntry>): Map<PlanEntryId, PlanSourceState> {
        val activitySources =
            plans
                .mapNotNull { it.sourceActivityTemplateId?.value }
                .distinct()
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::activitySources)
                .associateBy(PlanSourceMetadataRow::id)
        val sequenceSources =
            plans
                .mapNotNull { it.sourceSequenceTemplateId?.value }
                .distinct()
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::sequenceSources)
                .associateBy(PlanSourceMetadataRow::id)
        return plans.associate { plan ->
            val source =
                plan.sourceActivityTemplateId?.value?.let(activitySources::get)
                    ?: plan.sourceSequenceTemplateId?.value?.let(sequenceSources::get)
            plan.id to
                PlanSourceStateResolver.resolve(
                    plan.sourceRevision,
                    source?.revision,
                    source?.deletedAtMs != null,
                )
        }
    }

    private fun loadEngagement(
        plans: List<PlanEntry>,
        activeRuntime: ActiveRuntime?,
    ): Map<PlanEntryId, Boolean> {
        val planIds = plans.map { it.id.value }
        val activityLinks =
            planIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::liveActivityLinks)
        val sequenceLinks =
            planIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::liveSequenceLinks)
        val links =
            activityLinks.map {
                LivePlanLink(requireNotNull(it.planEntryId), PlanTrackableKind.ACTIVITY, it.id, it.snapshotId)
            } +
                sequenceLinks.map {
                    LivePlanLink(requireNotNull(it.planEntryId), PlanTrackableKind.SEQUENCE, it.id, it.snapshotId)
                }
        require(links.map(LivePlanLink::planId).distinct().size == links.size) {
            "A Daily Plan has multiple linked live roots"
        }
        val activeIdentity = activeRuntime?.identityOrNull()
        val plansById = plans.associateBy { it.id.value }
        links.forEach { link ->
            val plan = plansById.getValue(link.planId)
            require(
                plan.status == PlanEntryStatus.PLANNED &&
                    plan.kind == link.kind &&
                    (plan.activitySnapshotId?.value ?: plan.sequenceSnapshotId?.value) == link.snapshotId &&
                    activeIdentity == link,
            ) { "Engaged Daily Plan is inconsistent with the canonical active session" }
        }
        val engagedIds = links.mapTo(hashSetOf(), LivePlanLink::planId)
        return plans.associate { it.id to (it.id.value in engagedIds) }
    }

    private fun ActiveRuntime.toDailyActive(): DailyActive =
        when (this) {
            is ActiveActivityRuntime -> DailyActive.Activity(this)
            is ActiveSequenceRuntime -> {
                val current = execution.currentOccurrenceId?.let { id -> execution.occurrences.single { it.id == id } }
                val next = nextRemainingOccurrence(execution)
                DailyActive.Sequence(
                    this,
                    when {
                        current != null && session.state == ActiveSessionState.RUNNING ->
                            DailyActiveSequenceState.RUNNING_CURRENT
                        current != null && session.state == ActiveSessionState.PAUSED ->
                            DailyActiveSequenceState.PAUSED_CURRENT
                        current == null && session.state == ActiveSessionState.WAITING_NEXT ->
                            DailyActiveSequenceState.WAITING_NEXT
                        current == null && session.state == ActiveSessionState.RUNNING ->
                            DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN
                        current == null && session.state == ActiveSessionState.PAUSED ->
                            DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                        else -> error("Unsupported canonical active Sequence state")
                    },
                    current?.toDailyOccurrence(this),
                    next?.toDailyOccurrence(this),
                )
            }
        }

    private fun RuntimeOccurrence.toDailyOccurrence(runtime: ActiveSequenceRuntime) =
        DailySequenceOccurrence(
            this,
            runtime.activitySnapshots.getValue(activitySnapshotId),
            NextRuntimeDeadlineResolver.effectiveSettings(runtime, this),
        )

    private fun ActiveRuntime.identityOrNull(): LivePlanLink? =
        when (this) {
            is ActiveActivityRuntime ->
                execution.planEntryId?.let {
                    LivePlanLink(
                        it.value,
                        PlanTrackableKind.ACTIVITY,
                        execution.id.value,
                        execution.snapshotId.value,
                    )
                }
            is ActiveSequenceRuntime ->
                execution.planEntryId?.let {
                    LivePlanLink(
                        it.value,
                        PlanTrackableKind.SEQUENCE,
                        execution.id.value,
                        execution.snapshotId.value,
                    )
                }
        }

    companion object {
        fun create(
            context: Context,
            zoneIdProvider: CurrentZoneIdProvider = CurrentZoneIdProvider(ZoneId::systemDefault),
        ): DailyReadRepository {
            val database = LifeTracingDatabase.builder(context.applicationContext, DATABASE_NAME).build()
            return DailyReadRepository(database, zoneIdProvider, LiveSessionRepository.create(database))
        }

        private const val DATABASE_NAME = "lifetracing.db"
        private const val SQLITE_BIND_CHUNK_SIZE = 900
    }

    private data class LivePlanLink(
        val planId: String,
        val kind: PlanTrackableKind,
        val executionId: String,
        val snapshotId: String,
    )
}
