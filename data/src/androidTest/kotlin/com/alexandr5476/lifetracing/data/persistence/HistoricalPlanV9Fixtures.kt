@file:Suppress("MaxLineLength", "LongMethod", "LongParameterList")
// Explicit v9 SQL, never current Plan writers or entity mappers.

package com.alexandr5476.lifetracing.data.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import java.time.Instant

internal fun historicalPlanV9(
    id: String,
    target: PlanTarget,
    sequence: Boolean = false,
    snapshot: String = if (sequence) "sequence-snapshot" else "activity-snapshot",
    status: PlanEntryStatus = PlanEntryStatus.PLANNED,
    execution: String? = null,
    fulfilledAt: Instant? = null,
): PlanEntry =
    PlanEntry(
        PlanEntryId(id),
        if (sequence) PlanTrackableKind.SEQUENCE else PlanTrackableKind.ACTIVITY,
        if (sequence) null else ActivityTemplateId("activity-template"),
        if (sequence) SequenceTemplateId("sequence-template") else null,
        if (sequence) 2L else 3L,
        if (sequence) null else ActivitySnapshotId(snapshot),
        if (sequence) SequenceSnapshotId(snapshot) else null,
        target,
        status,
        if (sequence) null else execution?.let(::ActivityExecutionId),
        if (sequence) execution?.let(::SequenceExecutionId) else null,
        Instant.ofEpochMilli(16),
        fulfilledAt ?: Instant.ofEpochMilli(18),
        if (status == PlanEntryStatus.CANCELLED) Instant.ofEpochMilli(17) else null,
        fulfilledAt,
    )

/** Column order is the physical v9 order exported by Room and constrained by PlanEntrySchemaV9. */
internal fun SupportSQLiteDatabase.seedHistoricalPlanV9(plan: PlanEntry) {
    check(version == 9) { "Historical Plans must be seeded before production migration" }
    val target = plan.target
    execSQL(
        "INSERT INTO plan_entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<Any?>(
            plan.id.value,
            plan.kind.name,
            plan.sourceActivityTemplateId?.value,
            plan.sourceSequenceTemplateId?.value,
            plan.sourceRevision,
            plan.activitySnapshotId?.value,
            plan.sequenceSnapshotId?.value,
            target.precision.name,
            (target as? PlanTarget.FloatingDay)?.date?.toString(),
            (target as? PlanTarget.Week)?.weekStart?.toString(),
            (target as? PlanTarget.Month)?.month?.toString(),
            (target as? PlanTarget.ExactDay)?.scheduledAt?.toEpochMilli(),
            (target as? PlanTarget.ExactDay)?.creationZoneId?.id,
            plan.status.name,
            plan.fulfilledActivityExecutionId?.value,
            plan.fulfilledSequenceExecutionId?.value,
            plan.createdAt.toEpochMilli(),
            plan.updatedAt.toEpochMilli(),
            plan.cancelledAt?.toEpochMilli(),
            plan.fulfilledAt?.toEpochMilli(),
        ),
    )
}

/** All tables are bounded fixture data. This is a test-only complete preservation oracle. */
internal fun SupportSQLiteDatabase.historicalPlanFacts(): Map<String, List<List<String?>>> {
    val tables =
        query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    return tables.associateWith { table ->
        query("SELECT * FROM `$table` ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }
    }
}
