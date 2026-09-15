@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "TooManyFunctions",
) // Canonical validation remains explicit and transaction-local.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityHistoricalSnapshotPolicy
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateValidator
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailySequencePlanMetadata
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.PlanActivityRowMetadata
import com.alexandr5476.lifetracing.domain.PlanDayPresence
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryValidator
import com.alexandr5476.lifetracing.domain.PlanOverdueCalculator
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSourceStateResolver
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.PlanningPrecision
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshotValidator
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.domain.actionIdentity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
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
                val weekEnd = query.weekStart.plusDays(WEEK_DAY_COUNT)
                val rangeStart = query.weekStart.atStartOfDay(zone).toInstant()
                val rangeEnd = weekEnd.atStartOfDay(zone).toInstant()
                val dayPlans =
                    (
                        database.planEntryDao().getSupportedFloatingDays(
                            query.weekStart.toString(),
                            weekEnd.minusDays(1).toString(),
                        ) +
                            database.planEntryDao().getSupportedExactDays(
                                rangeStart.toEpochMilli(),
                                rangeEnd.toEpochMilli(),
                            )
                    ).map { it.toDomain().also(PlanEntryValidator::requireValid) }
                require(dayPlans.map(PlanEntry::id).distinct().size == dayPlans.size) {
                    "Week Day selectors returned duplicate Plan identities"
                }
                val dayPlansByDate = dayPlans.groupBy { it.effectiveDate(zone) }
                require(dayPlansByDate.keys.all { !it.isBefore(query.weekStart) && it.isBefore(weekEnd) }) {
                    "Week Day selector returned a Plan outside the requested week"
                }
                val weekPlans =
                    database
                        .planEntryDao()
                        .getDailyWeek(query.weekStart.toString())
                        .map { it.toDomain().also(PlanEntryValidator::requireValid) }
                val selectedPlans =
                    dayPlansByDate[query.selectedDate].orEmpty().sortedWith(
                        compareBy<PlanEntry> { it.exactLocalTime(zone) ?: LocalTime.MAX }
                            .thenBy(PlanEntry::createdAt)
                            .thenBy { it.id.value },
                    )
                require(
                    (selectedPlans + weekPlans).map { it.id }.distinct().size == selectedPlans.size + weekPlans.size,
                ) {
                    "Week Plan projection contains duplicate Plan identities"
                }
                val rows =
                    loadWeekRows(selectedPlans + weekPlans, query.now, zone)
                        .associateBy { it.plan.id }
                WeekPlanRead(
                    query.weekStart,
                    query.selectedDate,
                    selectedPlans.map { rows.getValue(it.id) },
                    weekPlans.map { rows.getValue(it.id) },
                    (0L until WEEK_DAY_COUNT).map { offset ->
                        val date = query.weekStart.plusDays(offset)
                        PlanDayPresence(date, dayPlansByDate[date].orEmpty().size)
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
                val engagement =
                    daily
                        .loadPlanEngagement(
                            listOf(plan),
                        ).getValue(plan.id)
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
                val snapshot =
                    when (plan.kind) {
                        PlanTrackableKind.ACTIVITY -> {
                            val ids =
                                (
                                    listOf(requireNotNull(plan.activitySnapshotId).value) +
                                        activityLinks.values.map(PlanActivityExecutionLinkRow::snapshotId)
                                ).distinct()
                            val activities = loadActivitySnapshots(ids)
                            require(activities.keys.map(ActivitySnapshotId::value).toSet() == ids.toSet()) {
                                "Focused Activity Plan references a missing Activity snapshot"
                            }
                            daily.validatePlanSnapshotsAndFulfillment(
                                listOf(plan),
                                activities,
                                emptyMap(),
                                activityLinks,
                                emptyMap(),
                            )
                            FocusedPlanAction.Snapshot.Activity(
                                activities.getValue(requireNotNull(plan.activitySnapshotId)),
                            )
                        }
                        PlanTrackableKind.SEQUENCE -> {
                            val sequence = loadFocusedSequenceSnapshot(plan)
                            val childIds = sequence.referencedActivitySnapshotIds()
                            val activities = loadActivitySnapshots(childIds.map(ActivitySnapshotId::value))
                            require(activities.keys == childIds) {
                                "Focused Sequence Activity snapshot set does not match its frozen Steps"
                            }
                            SequenceConfigSnapshotValidator.requireValid(
                                sequence,
                                activities.mapValues { it.value.timeTrackingMode },
                            )
                            daily.validatePlanSnapshotsAndFulfillment(
                                listOf(plan),
                                emptyMap(),
                                mapOf(sequence.id to sequence.toDailyPlanMetadata()),
                                emptyMap(),
                                sequenceLinks,
                            )
                            FocusedPlanAction.Snapshot.Sequence(sequence, activities)
                        }
                    }
                FocusedPlanAction(
                    plan.actionIdentity(),
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
                daily.loadPlanEngagement(plans)
                val page = plans.take(query.limit)
                CancelledPlanPage(page.toSummaryRows(), plans.size > query.limit)
            },
        )

    private fun List<PlanEntry>.toSummaryRows(): List<PlanReadRow> {
        if (isEmpty()) return emptyList()
        val activityIds = mapNotNull { it.activitySnapshotId?.value }.distinct()
        val sequenceIds = mapNotNull { it.sequenceSnapshotId?.value }.distinct()
        val activitySummaries =
            activityIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::activitySummaries)
                .associateBy { ActivitySnapshotId(it.id) }
        val sequenceSummaries =
            sequenceIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::sequenceSummaries)
                .associateBy { SequenceSnapshotId(it.id) }
        require(activitySummaries.keys.map(ActivitySnapshotId::value).toSet() == activityIds.toSet()) {
            "Cancelled Plan references a missing Activity snapshot"
        }
        require(sequenceSummaries.keys.map(SequenceSnapshotId::value).toSet() == sequenceIds.toSet()) {
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
            val activitySummary = plan.activitySnapshotId?.let(activitySummaries::getValue)
            val sequenceSummary = plan.sequenceSnapshotId?.let(sequenceSummaries::getValue)
            val summarySourceTemplateId =
                when (plan.kind) {
                    PlanTrackableKind.ACTIVITY -> requireNotNull(activitySummary).sourceTemplateId
                    PlanTrackableKind.SEQUENCE -> requireNotNull(sequenceSummary).sourceTemplateId
                }
            val summarySourceRevision =
                when (plan.kind) {
                    PlanTrackableKind.ACTIVITY -> requireNotNull(activitySummary).sourceRevision
                    PlanTrackableKind.SEQUENCE -> requireNotNull(sequenceSummary).sourceRevision
                }
            val sourceId = plan.sourceActivityTemplateId?.value ?: plan.sourceSequenceTemplateId?.value
            if (sourceId != null) {
                summarySourceTemplateId?.let { require(it == sourceId) { "Plan and snapshot source mismatch" } }
                require(summarySourceRevision == plan.sourceRevision) { "Plan and snapshot revision mismatch" }
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
                if (plan.kind ==
                    PlanTrackableKind.ACTIVITY
                ) {
                    requireNotNull(activitySummary).name
                } else {
                    requireNotNull(sequenceSummary).name
                },
                if (plan.kind ==
                    PlanTrackableKind.ACTIVITY
                ) {
                    activitySummary?.shortComment
                } else {
                    sequenceSummary?.shortComment
                },
                PlanSourceStateResolver.resolve(plan.sourceRevision, source?.revision, source?.deletedAtMs != null),
                false,
                false,
                activitySummary?.toRowMetadata(),
            )
        }
    }

    private fun loadWeekRows(
        plans: List<PlanEntry>,
        now: java.time.Instant,
        zone: ZoneId,
    ): List<PlanReadRow> {
        if (plans.isEmpty()) return emptyList()
        val activityIds = plans.mapNotNull(PlanEntry::activitySnapshotId).distinct()
        val sequenceIds = plans.mapNotNull(PlanEntry::sequenceSnapshotId).distinct()
        val activitySummaries =
            activityIds
                .map(ActivitySnapshotId::value)
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::activitySummaries)
                .associateBy { ActivitySnapshotId(it.id) }
        val sequenceSummaries =
            sequenceIds
                .map(SequenceSnapshotId::value)
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::sequenceSummaries)
                .associateBy { SequenceSnapshotId(it.id) }
        require(activitySummaries.keys == activityIds.toSet()) { "Week Plan references a missing Activity snapshot" }
        require(sequenceSummaries.keys == sequenceIds.toSet()) { "Week Plan references a missing Sequence snapshot" }

        val activityLinks =
            plans
                .mapNotNull { it.fulfilledActivityExecutionId?.value }
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::activityExecutionLinks)
                .associateBy(PlanActivityExecutionLinkRow::id)
        val sequenceLinks =
            plans
                .mapNotNull { it.fulfilledSequenceExecutionId?.value }
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.planEntryDao()::sequenceExecutionLinks)
                .associateBy(PlanSequenceExecutionLinkRow::id)
        val replacementIds =
            plans
                .mapNotNull { plan ->
                    plan.fulfilledActivityExecutionId
                        ?.let { activityLinks[it.value] }
                        ?.snapshotId
                        ?.takeIf { it != plan.activitySnapshotId?.value }
                        ?.let { listOf(requireNotNull(plan.activitySnapshotId).value, it) }
                }.flatten()
                .distinct()
        val replacementSnapshots = loadActivitySnapshots(replacementIds)
        require(replacementSnapshots.keys.map(ActivitySnapshotId::value).toSet() == replacementIds.toSet()) {
            "Fulfilled Activity replacement references a missing snapshot"
        }

        val sourceStates = daily.loadSourceStates(plans)
        val engagement = daily.loadPlanEngagement(plans)
        return plans.map { plan ->
            val activitySummary = plan.activitySnapshotId?.let(activitySummaries::getValue)
            val sequenceSummary = plan.sequenceSnapshotId?.let(sequenceSummaries::getValue)
            validateWeekRow(plan, activitySummary, sequenceSummary, activityLinks, sequenceLinks, replacementSnapshots)
            val exact = (plan.target as? PlanTarget.ExactDay)?.scheduledAt?.atZone(zone)
            PlanReadRow(
                plan,
                if (plan.target is PlanTarget.Week) null else plan.effectiveDate(zone),
                exact?.toLocalTime(),
                if (plan.kind ==
                    PlanTrackableKind.ACTIVITY
                ) {
                    requireNotNull(activitySummary).name
                } else {
                    requireNotNull(sequenceSummary).name
                },
                if (plan.kind ==
                    PlanTrackableKind.ACTIVITY
                ) {
                    activitySummary?.shortComment
                } else {
                    sequenceSummary?.shortComment
                },
                sourceStates.getValue(plan.id),
                engagement.getValue(plan.id),
                PlanOverdueCalculator.isOverdue(plan, now, zone),
                activitySummary?.toRowMetadata(),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun validateWeekRow(
        plan: PlanEntry,
        activitySummary: PlanActivitySnapshotSummaryRow?,
        sequenceSummary: PlanSnapshotSummaryRow?,
        activityLinks: Map<String, PlanActivityExecutionLinkRow>,
        sequenceLinks: Map<String, PlanSequenceExecutionLinkRow>,
        replacementSnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ) {
        when (plan.kind) {
            PlanTrackableKind.ACTIVITY -> {
                val summary = requireNotNull(activitySummary)
                plan.sourceActivityTemplateId?.let { source ->
                    summary.sourceTemplateId?.let {
                        require(
                            it == source.value,
                        ) { "Plan and Activity snapshot source mismatch" }
                    }
                    require(
                        summary.sourceRevision == plan.sourceRevision,
                    ) { "Plan and Activity snapshot revision mismatch" }
                }
                plan.fulfilledActivityExecutionId?.let { id ->
                    val link = requireNotNull(activityLinks[id.value]) { "Fulfilled Activity execution is missing" }
                    require(
                        link.contextType == "STANDALONE" &&
                            link.planEntryId == plan.id.value &&
                            link.status == "COMPLETED" &&
                            (
                                link.snapshotId == summary.id ||
                                    ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(
                                        replacementSnapshots.getValue(requireNotNull(plan.activitySnapshotId)),
                                        replacementSnapshots.getValue(ActivitySnapshotId(link.snapshotId)),
                                    )
                            ),
                    ) { "Plan fulfillment Activity execution linkage is invalid" }
                }
            }
            PlanTrackableKind.SEQUENCE -> {
                val summary = requireNotNull(sequenceSummary)
                plan.sourceSequenceTemplateId?.let { source ->
                    summary.sourceTemplateId?.let {
                        require(
                            it == source.value,
                        ) { "Plan and Sequence snapshot source mismatch" }
                    }
                    require(
                        summary.sourceRevision == plan.sourceRevision,
                    ) { "Plan and Sequence snapshot revision mismatch" }
                }
                plan.fulfilledSequenceExecutionId?.let { id ->
                    val link = requireNotNull(sequenceLinks[id.value]) { "Fulfilled Sequence execution is missing" }
                    require(
                        link.planEntryId == plan.id.value &&
                            link.snapshotId == summary.id &&
                            link.status in setOf("COMPLETED", "ENDED_EARLY") &&
                            link.endedAtMs == plan.fulfilledAt?.toEpochMilli(),
                    ) { "Plan fulfillment Sequence execution linkage is invalid" }
                }
            }
        }
    }

    private fun loadActivitySnapshots(ids: List<String>): Map<ActivitySnapshotId, ActivityConfigSnapshot> =
        ids
            .chunked(SQLITE_BIND_CHUNK_SIZE)
            .flatMap(database.activitySnapshotDao()::getAggregates)
            .map(ActivitySnapshotAggregateEntity::toDomain)
            .associateBy(ActivityConfigSnapshot::id)

    private fun PlanActivitySnapshotSummaryRow.toRowMetadata(): PlanActivityRowMetadata {
        val mode = TimeTrackingMode.valueOf(timeTrackingMode)
        val target = timerTargetMs?.let(Duration::ofMillis)
        ActivityTemplateValidator.requireValidTracking(mode, target)
        return PlanActivityRowMetadata(mode, target)
    }

    private fun PlanEntry.effectiveDate(zone: ZoneId): LocalDate =
        when (val target = target) {
            is PlanTarget.FloatingDay -> target.date
            is PlanTarget.ExactDay -> target.scheduledAt.atZone(zone).toLocalDate()
            is PlanTarget.Week -> target.weekStart
            is PlanTarget.Month -> error("Month Plan is outside the Week read")
        }

    private fun PlanEntry.exactLocalTime(zone: ZoneId): LocalTime? =
        (target as? PlanTarget.ExactDay)?.scheduledAt?.atZone(zone)?.toLocalTime()

    private fun SequenceConfigSnapshot.referencedActivitySnapshotIds(): Set<ActivitySnapshotId> =
        nodes
            .flatMap { node ->
                when (node) {
                    is SequenceSnapshotActivityStep -> listOf(node.activitySnapshotId)
                    is SequenceSnapshotRepeatBlock ->
                        node.children.map(
                            SequenceSnapshotActivityStep::activitySnapshotId,
                        )
                }
            }.toSet()

    private fun SequenceConfigSnapshot.toDailyPlanMetadata() =
        DailySequencePlanMetadata(id, name, shortComment, sourceTemplateId, sourceRevision)

    private fun loadFocusedSequenceSnapshot(plan: PlanEntry): SequenceConfigSnapshot =
        requireNotNull(
            database.sequenceSnapshotDao().getAggregate(requireNotNull(plan.sequenceSnapshotId).value),
        ) {
            "Plan SequenceSnapshot is missing"
        }.toDomain().also { validateSequenceSnapshot(plan, it) }

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
