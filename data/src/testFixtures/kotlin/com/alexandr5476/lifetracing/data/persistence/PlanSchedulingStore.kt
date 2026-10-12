package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import java.util.UUID

/** Isolated file-backed scheduling fixture; application tests consume only domain-facing repositories. */
class PlanSchedulingStore(
    context: Context,
    name: String,
    zone: CurrentZoneIdProvider,
) : AutoCloseable {
    private val database = LifeTracingDatabase.builder(context, name).build()
    val authoring = TemplateAuthoringRepository(database, TemplateAuthoringIds.random())
    val live = LiveSessionRepository.create(database)
    val reads = PlanReadRepository(database, zone)
    val plans =
        PlanRepository(
            database,
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
            zone,
        )

    /** Full durable facts of this bounded fixture, including snapshot-owned metadata and Recent. */
    fun durableFacts(): Map<String, List<List<String?>>> {
        val db = database.openHelper.readableDatabase
        val tables =
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (!name.startsWith("sqlite_") &&
                            !name.startsWith("room_") &&
                            name != "android_metadata"
                        ) {
                            add(name)
                        }
                    }
                }
            }
        return tables.associateWith { table ->
            db.query("SELECT * FROM \"$table\" ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) },
                        )
                    }
                }
            }
        }
    }

    fun forbidPlanUpdates() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER forbid_plan_updates BEFORE UPDATE ON plan_entries " +
                "BEGIN SELECT RAISE(ABORT, 'Unexpected Plan update'); END",
        )
    }

    override fun close() = database.close()
}
