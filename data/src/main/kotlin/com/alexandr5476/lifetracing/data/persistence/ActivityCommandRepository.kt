@file:Suppress("ComplexCondition", "LongParameterList", "TooManyFunctions")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionValidator
import com.alexandr5476.lifetracing.domain.ActivityExecutionValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionValuePolicy
import com.alexandr5476.lifetracing.domain.ActivityHistoricalSnapshotPolicy
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrectionPolicy
import com.alexandr5476.lifetracing.domain.ActivityHistoryItem
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.ExpiredFinishTimerDecisionRequiredException
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import java.time.Instant
import java.time.ZoneId
import java.util.ConcurrentModificationException
import java.util.UUID
import java.util.concurrent.Callable

class ActivityCommandRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val liveSessions: LiveSessionRepository,
    private val snapshotFactory: ActivitySnapshotFactory,
    nextExecutionId: () -> ActivityExecutionId,
) {
    private val executionFactory = ActivityExecutionFactory(nextExecutionId)

    fun startLive(
        source: ActivityEntrySource,
        startedAt: Instant,
        createdAt: Instant,
        eventZoneId: ZoneId,
        valueOverrides: List<ActivityEntryValueOverride> = emptyList(),
    ): ActivityExecution =
        transaction {
            val prepared = prepare(source, createdAt)
            require(prepared.snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
                "NO_LIVE_TRACKING Activity cannot start live"
            }
            requireUnambiguousLiveStart(prepared.snapshot, startedAt, createdAt)
            val resolved = resolveValueOverrides(prepared, valueOverrides)
            val execution =
                when (source) {
                    is ActivityEntrySource.Plan ->
                        liveSessions.startActivityFromPlan(
                            source.id,
                            startedAt,
                            createdAt,
                            eventZoneId,
                            resolved,
                        )
                    else ->
                        liveSessions.startStandaloneTimedActivityFromSnapshot(
                            prepared.snapshot.id,
                            startedAt,
                            createdAt,
                            eventZoneId,
                            resolved,
                        )
                }
            prepared.directTemplateId?.let { templateId ->
                check(database.libraryDao().touchActivity(templateId, createdAt.toEpochMilli()) == 1) {
                    "ActivityTemplate is missing user state"
                }
            }
            execution
        }

    fun addManualTimed(
        source: ActivityEntrySource,
        startedAt: Instant,
        completedAt: Instant,
        createdAt: Instant,
        eventZoneId: ZoneId,
        valueOverrides: List<ActivityEntryValueOverride> = emptyList(),
    ): ActivityExecution =
        transaction {
            val prepared = prepare(source, createdAt)
            require(prepared.snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
                "Timed history requires a timed Activity"
            }
            val generated =
                executionFactory.createManualTimed(
                    prepared.snapshot,
                    startedAt,
                    completedAt,
                    createdAt,
                    eventZoneId,
                    prepared.plan?.id,
                )
            persistManual(prepared, generated, valueOverrides, createdAt)
        }

    fun addManualNoLive(
        source: ActivityEntrySource,
        completedAt: Instant,
        createdAt: Instant,
        eventZoneId: ZoneId,
        valueOverrides: List<ActivityEntryValueOverride> = emptyList(),
    ): ActivityExecution =
        transaction {
            val prepared = prepare(source, createdAt)
            require(prepared.snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                "No-live history requires a NO_LIVE_TRACKING Activity"
            }
            val generated =
                executionFactory.createManualNoLiveHistory(
                    prepared.snapshot,
                    completedAt,
                    createdAt,
                    eventZoneId,
                    prepared.plan?.id,
                )
            persistManual(prepared, generated, valueOverrides, createdAt)
        }

    fun overlapsCompletedHistory(
        startedAt: Instant,
        completedAt: Instant,
        excludingExecutionId: ActivityExecutionId? = null,
    ): Boolean {
        val startMs = startedAt.toEpochMilli()
        val endMs = completedAt.toEpochMilli()
        require(startMs <= endMs) { "Proposed overlap interval must be ordered" }
        return database.activityExecutionDao().overlapsCompletedStandalone(
            startMs,
            endMs,
            excludingExecutionId?.value,
        )
    }

    fun getHistory(id: ActivityExecutionId): ActivityHistoryItem? =
        transaction {
            val execution =
                database.activityExecutionDao().getAggregate(id.value)?.toDomain()
                    ?: return@transaction null
            val snapshot = loadSnapshot(execution.snapshotId)
            ActivityExecutionValidator.requireValid(execution, snapshot)
            ActivityHistoryItem(execution, snapshot)
        }

    fun correctHistory(
        id: ActivityExecutionId,
        correction: ActivityHistoryCorrection,
        correctedAt: Instant,
    ): ActivityHistoryItem =
        transaction {
            val current = requireNotNull(getHistory(id)) { "Unknown Activity history: ${id.value}" }
            val expectedUpdatedAt = Instant.ofEpochMilli(correction.expectedUpdatedAt.toEpochMilli())
            if (current.execution.updatedAt != expectedUpdatedAt) {
                throw ConcurrentModificationException("Activity history changed concurrently")
            }
            if (ActivityHistoryCorrectionPolicy.isNoOp(current.execution, current.snapshot, correction)) {
                return@transaction current
            }
            var corrected =
                ActivityHistoryCorrectionPolicy.correct(
                    current.execution,
                    current.snapshot,
                    correction,
                    correctedAt,
                )
            var snapshot = current.snapshot
            if (correction.shortComment != current.snapshot.shortComment) {
                val replacement =
                    snapshotFactory.replaceShortComment(
                        current.snapshot,
                        correction.shortComment,
                        correctedAt,
                    )
                snapshot = replacement.snapshot
                require(ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(current.snapshot, snapshot)) {
                    "Historical Short Comment replacement changed frozen Activity configuration"
                }
                corrected =
                    corrected.copy(
                        snapshotId = snapshot.id,
                        values =
                            corrected.values
                                .map { value -> value.remap(replacement.fieldIds, replacement.optionIds) }
                                .sortedBy { it.snapshotFieldId.value },
                    )
                ActivityExecutionValidator.requireValid(corrected, snapshot)
                database.activitySnapshotDao().insertAggregate(snapshot.toEntityAggregate())
            }
            database.activityExecutionDao().correctCompletedStandalone(
                expectedUpdatedAt.toEpochMilli(),
                current.snapshot.id.value,
                corrected.toEntityAggregate(),
            )
            if (snapshot.id != current.snapshot.id) pruneActivitySnapshot(current.snapshot.id)
            ActivityHistoryItem(corrected, snapshot)
        }

    fun softDeleteHistory(
        id: ActivityExecutionId,
        expectedUpdatedAt: Instant,
        deletedAt: Instant,
    ): ActivityExecution =
        transaction {
            database.activityExecutionDao().softDeleteCompletedStandalone(
                id.value,
                expectedUpdatedAt.toEpochMilli(),
                deletedAt.toEpochMilli(),
            )
            requireNotNull(database.activityExecutionDao().getAggregate(id.value)).toDomain()
        }

    private fun persistManual(
        prepared: PreparedSource,
        generated: ActivityExecution,
        valueOverrides: List<ActivityEntryValueOverride>,
        commandAt: Instant,
    ): ActivityExecution {
        val execution =
            ActivityExecutionValuePolicy.apply(
                generated,
                prepared.snapshot,
                resolveValueOverrides(prepared, valueOverrides),
            )
        database.activityExecutionDao().insertAggregate(execution.toEntityAggregate())
        prepared.plan?.let { plan ->
            check(
                database.planEntryDao().fulfillActivity(
                    plan.id.value,
                    prepared.snapshot.id.value,
                    execution.id.value,
                    requireNotNull(execution.completedAt).toEpochMilli(),
                ) == 1,
            ) { "Plan changed before manual completion" }
            plan.sourceActivityTemplateId?.let { sourceId ->
                check(database.planEntryDao().touchActivitySource(sourceId.value, commandAt.toEpochMilli()) == 1) {
                    "Plan source is missing user state"
                }
            }
        }
        prepared.directTemplateId?.let { templateId ->
            check(database.libraryDao().touchActivity(templateId, commandAt.toEpochMilli()) == 1) {
                "ActivityTemplate is missing user state"
            }
        }
        return execution
    }

    private fun prepare(
        source: ActivityEntrySource,
        createdAt: Instant,
    ): PreparedSource =
        when (source) {
            is ActivityEntrySource.Template -> {
                val template =
                    requireNotNull(database.activityTemplateDao().getAggregate(source.id.value)) {
                        "Unknown ActivityTemplate: ${source.id.value}"
                    }.toDomain()
                require(template.deletedAt == null) { "Archived ActivityTemplate cannot be used directly" }
                val snapshot = snapshotFactory.fromTemplate(template, createdAt)
                database.activitySnapshotDao().insertAggregate(snapshot.toEntityAggregate())
                PreparedSource(snapshot, directTemplateId = source.id.value)
            }
            is ActivityEntrySource.Plan -> {
                val plan =
                    requireNotNull(database.planEntryDao().getById(source.id.value)) {
                        "Unknown Plan: ${source.id.value}"
                    }.toDomain()
                require(plan.kind == PlanTrackableKind.ACTIVITY && plan.status == PlanEntryStatus.PLANNED) {
                    "Plan is not a planned Activity"
                }
                require(!database.planEntryDao().hasLiveActivity(plan.id.value)) {
                    "Plan already has a linked live execution"
                }
                PreparedSource(loadSnapshot(requireNotNull(plan.activitySnapshotId)), plan = plan)
            }
            is ActivityEntrySource.OneOff -> {
                val built = snapshotFactory.fromOneOff(source.draft, createdAt)
                database.activitySnapshotDao().insertAggregate(built.snapshot.toEntityAggregate())
                PreparedSource(
                    built.snapshot,
                    oneOffFieldIds = built.fieldIdsByKey,
                    oneOffOptionIds = built.optionIdsByKey,
                )
            }
        }

    private fun resolveValueOverrides(
        prepared: PreparedSource,
        overrides: List<ActivityEntryValueOverride>,
    ): List<ActivityExecutionValueOverride> {
        if (prepared.directTemplateId != null) {
            return resolveDirectTemplateValueOverrides(prepared.snapshot, overrides)
        }
        return overrides.map { override ->
            val field =
                when (val reference = override.field) {
                    is ActivityEntryFieldReference.Template -> {
                        throw IllegalArgumentException("Template Field reference requires direct Template use")
                    }
                    is ActivityEntryFieldReference.Snapshot -> {
                        require(prepared.plan != null) { "Snapshot Field reference requires Plan snapshot use" }
                        prepared.snapshot.fields.singleOrNull { it.id == reference.id }
                    }
                    is ActivityEntryFieldReference.OneOff -> {
                        require(prepared.oneOffFieldIds.isNotEmpty()) { "One-off Field reference requires one-off use" }
                        prepared.oneOffFieldIds[reference.key]?.let { id ->
                            prepared.snapshot.fields.singleOrNull { it.id == id }
                        }
                    }
                } ?: throw IllegalArgumentException("Unknown Activity entry Field reference")
            ActivityExecutionValueOverride(
                field.id,
                when (val value = override.value) {
                    ActivityEntryValue.Missing -> null
                    is ActivityEntryValue.Number -> NumberExecutionValue(field.id, value.scaledValue)
                    is ActivityEntryValue.Text -> TextExecutionValue(field.id, value.value)
                    is ActivityEntryValue.Category ->
                        CategoryExecutionValue(field.id, resolveOption(prepared, field.id, value.option))
                },
            )
        }
    }

    private fun resolveOption(
        prepared: PreparedSource,
        fieldId: ActivitySnapshotFieldId,
        reference: ActivityEntryOptionReference,
    ): ActivitySnapshotCategoryOptionId {
        val field = prepared.snapshot.fields.single { it.id == fieldId }
        return when (reference) {
            is ActivityEntryOptionReference.Template -> {
                require(prepared.directTemplateId != null) { "Template option reference requires direct Template use" }
                field.categoryOptions.singleOrNull { it.sourceOptionId == reference.id }?.id
            }
            is ActivityEntryOptionReference.Snapshot -> {
                require(prepared.plan != null) { "Snapshot option reference requires Plan snapshot use" }
                field.categoryOptions.singleOrNull { it.id == reference.id }?.id
            }
            is ActivityEntryOptionReference.OneOff -> {
                require(prepared.oneOffOptionIds.isNotEmpty()) { "One-off option reference requires one-off use" }
                prepared.oneOffOptionIds[reference.key]?.takeIf { id -> field.categoryOptions.any { it.id == id } }
            }
        } ?: throw IllegalArgumentException("Category option must belong to the selected Field")
    }

    private fun requireUnambiguousLiveStart(
        snapshot: ActivityConfigSnapshot,
        startedAt: Instant,
        createdAt: Instant,
    ) {
        val startedAtMs = startedAt.toEpochMilli()
        val createdAtMs = createdAt.toEpochMilli()
        require(startedAtMs <= createdAtMs) { "Start must not be in the future" }
        if (
            snapshot.timeTrackingMode == TimeTrackingMode.TIMER &&
            snapshot.settings.timerZeroBehavior == TimerZeroBehavior.FINISH &&
            Math.addExact(startedAtMs, requireNotNull(snapshot.timerTarget).toMillis()) <= createdAtMs
        ) {
            throw ExpiredFinishTimerDecisionRequiredException()
        }
    }

    private fun loadSnapshot(id: ActivitySnapshotId): ActivityConfigSnapshot =
        requireNotNull(database.activitySnapshotDao().getAggregate(id.value)) {
            "Unknown ActivitySnapshot: ${id.value}"
        }.toDomain()

    private fun pruneActivitySnapshot(id: ActivitySnapshotId) {
        val references = database.planEntryDao()
        if (
            !references.hasActivityPlanReference(id.value) &&
            !references.hasSequenceNodeReference(id.value) &&
            !references.hasSequenceSnapshotNodeReference(id.value) &&
            !references.hasActivityExecutionReference(id.value) &&
            !references.hasSequenceOccurrenceReference(id.value)
        ) {
            check(database.activitySnapshotDao().hardDelete(id.value) == 1)
        }
    }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    private data class PreparedSource(
        val snapshot: ActivityConfigSnapshot,
        val plan: PlanEntry? = null,
        val directTemplateId: String? = null,
        val oneOffFieldIds: Map<String, ActivitySnapshotFieldId> = emptyMap(),
        val oneOffOptionIds: Map<String, ActivitySnapshotCategoryOptionId> = emptyMap(),
    )

    companion object {
        fun create(context: Context): ActivityCommandRepository {
            val database = LifeTracingDatabase.builder(context.applicationContext, "lifetracing.db").build()
            val live =
                LiveSessionRepository(
                    database,
                    { ActivityExecutionId(uuid()) },
                    { ActivityExecutionPauseId(uuid()) },
                    { SequenceExecutionId(uuid()) },
                    { SequenceOccurrenceId(uuid()) },
                    { SequenceIntervalId(uuid()) },
                )
            return ActivityCommandRepository(
                database,
                live,
                ActivitySnapshotFactory(
                    { ActivitySnapshotId(uuid()) },
                    { ActivitySnapshotFieldId(uuid()) },
                    { ActivitySnapshotCategoryOptionId(uuid()) },
                ),
                { ActivityExecutionId(uuid()) },
            )
        }

        private fun uuid(): String = UUID.randomUUID().toString()
    }
}

private fun ActivityExecutionFieldValue.remap(
    fieldIds: Map<ActivitySnapshotFieldId, ActivitySnapshotFieldId>,
    optionIds: Map<ActivitySnapshotCategoryOptionId, ActivitySnapshotCategoryOptionId>,
): ActivityExecutionFieldValue =
    when (this) {
        is NumberExecutionValue -> NumberExecutionValue(fieldIds.getValue(snapshotFieldId), scaledValue)
        is CategoryExecutionValue ->
            CategoryExecutionValue(fieldIds.getValue(snapshotFieldId), optionIds.getValue(optionId))
        is TextExecutionValue -> TextExecutionValue(fieldIds.getValue(snapshotFieldId), value)
    }

internal fun resolveDirectTemplateValueOverrides(
    snapshot: ActivityConfigSnapshot,
    overrides: List<ActivityEntryValueOverride>,
): List<ActivityExecutionValueOverride> =
    overrides.map { override ->
        val reference =
            override.field as? ActivityEntryFieldReference.Template
                ?: throw IllegalArgumentException("Template Field reference requires direct Template use")
        val field =
            snapshot.fields.singleOrNull { it.sourceFieldId == reference.id }
                ?: throw IllegalArgumentException("Unknown Activity entry Field reference")
        ActivityExecutionValueOverride(
            field.id,
            when (val value = override.value) {
                ActivityEntryValue.Missing -> null
                is ActivityEntryValue.Number -> NumberExecutionValue(field.id, value.scaledValue)
                is ActivityEntryValue.Text -> TextExecutionValue(field.id, value.value)
                is ActivityEntryValue.Category -> {
                    val option =
                        value.option as? ActivityEntryOptionReference.Template
                            ?: throw IllegalArgumentException(
                                "Template option reference requires direct Template use",
                            )
                    val snapshotOption =
                        field.categoryOptions.singleOrNull { it.sourceOptionId == option.id }
                            ?: throw IllegalArgumentException("Category option must belong to the selected Field")
                    CategoryExecutionValue(field.id, snapshotOption.id)
                }
            },
        )
    }
