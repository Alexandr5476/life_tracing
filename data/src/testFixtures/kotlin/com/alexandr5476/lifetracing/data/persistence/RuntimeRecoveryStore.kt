package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import java.time.ZoneId
import java.util.UUID

/** Owns a physical database for application tests; no Room models leave this fixture. */
class RuntimeRecoveryStore(
    context: Context,
    name: String,
) : AutoCloseable {
    private val database = LifeTracingDatabase.builder(context, name).build()
    val live: LiveSessionRepository = LiveSessionRepository.create(database)
    val activityCommands: ActivityCommandRepository =
        ActivityCommandRepository(
            database,
            live,
            ActivitySnapshotFactory(
                { ActivitySnapshotId(UUID.randomUUID().toString()) },
                { ActivitySnapshotFieldId(UUID.randomUUID().toString()) },
                { ActivitySnapshotCategoryOptionId(UUID.randomUUID().toString()) },
            ),
            { ActivityExecutionId(UUID.randomUUID().toString()) },
        )
    val daily: DailyReadRepository = DailyReadRepository(database, CurrentZoneIdProvider(ZoneId::systemDefault))
    val statistics: StatisticsRepository =
        StatisticsRepository(database) { StatisticsSeriesId(UUID.randomUUID().toString()) }
    val library: LibraryRepository = LibraryRepository.create(database)
    val history: HistoryReadRepository = HistoryReadRepository(database)
    val authoring: TemplateAuthoringRepository = TemplateAuthoringRepository(database, TemplateAuthoringIds.random())

    fun activity(id: ActivityExecutionId): ActivityExecution =
        requireNotNull(database.activityExecutionDao().getAggregate(id.value)).toDomain()

    fun sequence(id: SequenceExecutionId): SequenceExecution =
        requireNotNull(database.sequenceExecutionDao().getAggregate(id.value)).toDomain()

    fun children(id: SequenceExecutionId): List<ActivityExecution> =
        database.activityExecutionDao().getSequenceChildAggregates(id.value).map { it.toDomain() }

    fun executionCounts(): Pair<Long, Long> = count("activity_executions") to count("sequence_executions")

    private fun count(table: String): Long =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    /** Complete bounded fixture comparison covers snapshots, provenance, intervals and source state. */
    fun durableFacts(): Map<String, List<List<String?>>> {
        val db = database.openHelper.readableDatabase
        val tables =
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val table = cursor.getString(0)
                        if (!table.startsWith("sqlite_") && !table.startsWith("room_") && table != "android_metadata") {
                            add(table)
                        }
                    }
                }
            }
        return tables.associateWith { table ->
            db.query("SELECT * FROM \"$table\" ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
                    }
                }
            }
        }
    }

    override fun close() {
        database.close()
    }

    companion object {
        /** Close the actual production Room owner without exposing Room models to application tests. */
        fun closeProductionRuntimeRepository(repository: LiveSessionRepository) {
            repository.database.close()
        }
    }
}
