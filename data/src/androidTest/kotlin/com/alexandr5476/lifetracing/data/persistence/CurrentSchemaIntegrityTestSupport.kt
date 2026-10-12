@file:Suppress("MaxLineLength", "LongMethod")
// Finite schema introspection keeps all constraint dimensions explicit.

package com.alexandr5476.lifetracing.data.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import java.util.Locale

@Ignore("Schema value object, not an instrumentation test")
internal data class CurrentTableIntegrity(
    val columns: Set<List<String?>>,
    val foreignKeys: Set<List<String?>>,
    val indexes: Map<String, CurrentIndexIntegrity>,
    val checks: Set<String>,
)

@Ignore("Schema value object, not an instrumentation test")
internal data class CurrentIndexIntegrity(
    val unique: Boolean,
    val columns: List<String>,
    val orders: List<String>,
    val predicate: String,
)

/** Actual SQLite structure, excluding Room/SQLite bookkeeping and auto-index names. */
internal fun SupportSQLiteDatabase.readCurrentIntegrity(): Map<String, CurrentTableIntegrity> =
    integrityRows("SELECT name FROM sqlite_master WHERE type = 'table'", "name")
        .map { requireNotNull(it.single()) }
        .filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it == "android_metadata" }
        .associateWith { table ->
            val indexes = integrityRows("PRAGMA index_list(`$table`)", "name", "unique", "origin", "partial")
            CurrentTableIntegrity(
                columns =
                    integrityRows(
                        "PRAGMA table_info(`$table`)",
                        "name",
                        "type",
                        "notnull",
                        "dflt_value",
                        "pk",
                    ).toSet(),
                foreignKeys =
                    integrityRows(
                        "PRAGMA foreign_key_list(`$table`)",
                        "table",
                        "from",
                        "to",
                        "on_update",
                        "on_delete",
                    ).toSet(),
                indexes =
                    indexes.filter { it[2] == "c" }.associate { row ->
                        val name = requireNotNull(row[0])
                        val indexed =
                            integrityRows("PRAGMA index_xinfo(`$name`)", "name", "desc", "key").filter {
                                it[2] ==
                                    "1"
                            }
                        name to
                            CurrentIndexIntegrity(
                                unique = row[1] == "1",
                                columns = indexed.map { requireNotNull(it[0]) },
                                orders = indexed.map { if (it[1] == "1") "DESC" else "ASC" },
                                predicate =
                                    if (row[3] ==
                                        "1"
                                    ) {
                                        integritySql("index", name).sqlTokens().substringAfter(" where ", "")
                                    } else {
                                        ""
                                    },
                            )
                    },
                checks = integritySql("table", table).checkExpressions(),
            )
        }

/** Assert each physical database independently, so equality cannot hide a shared omission. */
internal fun SupportSQLiteDatabase.assertCurrentIntegrity(): Map<String, CurrentTableIntegrity> {
    assertEquals(10, version)
    assertEquals(listOf(listOf("1")), integrityRows("PRAGMA foreign_keys", "foreign_keys"))
    query("PRAGMA foreign_key_check").use { assertFalse("Foreign-key violations", it.moveToFirst()) }
    val actual = readCurrentIntegrity()
    val schemaPath = "com.alexandr5476.lifetracing.data.persistence.LifeTracingDatabase/10.json"
    val exported =
        InstrumentationRegistry.getInstrumentation().context.assets.open(schemaPath).bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database").getJSONArray("entities").objects()
        }
    assertEquals(exported.map { it.getString("tableName") }.toSet(), actual.keys)
    exported.forEach { entity -> assertRoomStructure(entity, actual.getValue(entity.getString("tableName"))) }
    actual.forEach { (table, shape) ->
        assertEquals(
            "CHECK constraints of $table",
            REQUIRED_CURRENT_CHECKS[table].orEmpty().map(String::sqlTokens).toSet(),
            shape.checks,
        )
    }
    REQUIRED_PARTIAL_INDEXES.forEach { requirement ->
        val table = requirement[0]
        val name = requirement[1]
        val column = requirement[2]
        val predicate = requirement[3]
        assertEquals(
            "$table.$name",
            CurrentIndexIntegrity(true, listOf(column), listOf("ASC"), predicate.sqlTokens()),
            actual.getValue(table).indexes[name],
        )
    }
    assertEquals(
        CurrentIndexIntegrity(
            false,
            listOf("context_type", "status", "deleted_at_ms", "primary_local_date"),
            List(4) {
                "ASC"
            },
            "",
        ),
        actual.getValue("activity_executions").indexes["activity_executions_history_latest_root"],
    )
    assertEquals(
        CurrentIndexIntegrity(false, listOf("status", "primary_local_date"), List(2) { "ASC" }, ""),
        actual.getValue("sequence_executions").indexes["sequence_executions_status_primary_date"],
    )
    // Reuse established manual-schema readers alongside the complete structural inventory.
    assertEquals(EXPECTED_ACTIVITY_TEMPLATE_MANUAL_SCHEMA, readActivityTemplateManualSchema())
    assertEquals(EXPECTED_ACTIVITY_SNAPSHOT_MANUAL_SCHEMA, readActivitySnapshotManualSchema())
    return actual
}

private fun assertRoomStructure(
    entity: JSONObject,
    actual: CurrentTableIntegrity,
) {
    val table = entity.getString("tableName")
    val primaryKey = entity.getJSONObject("primaryKey").getJSONArray("columnNames").strings()
    val columns =
        entity
            .getJSONArray("fields")
            .objects()
            .map { field ->
                val name = field.getString("columnName")
                val primaryKeyPosition = (primaryKey.indexOf(name) + 1).toString()
                listOf(
                    name,
                    field.getString("affinity"),
                    if (field.optBoolean("notNull")) "1" else "0",
                    field.nullableString("defaultValue"),
                    primaryKeyPosition,
                )
            }.toSet()
    assertEquals("Columns of $table (type, nullability, default, primary-key position)", columns, actual.columns)
    val foreignKeys =
        entity
            .optJSONArray("foreignKeys")
            ?.objects()
            .orEmpty()
            .flatMap { key ->
                val fromColumns = key.getJSONArray("columns").strings()
                val toColumns = key.getJSONArray("referencedColumns").strings()
                fromColumns.zip(toColumns).map { (from, to) ->
                    listOf(key.getString("table"), from, to, key.getString("onUpdate"), key.getString("onDelete"))
                }
            }.toSet()
    assertEquals("Foreign keys of $table", foreignKeys, actual.foreignKeys)
    val roomIndexes = entity.optJSONArray("indices")?.objects().orEmpty()
    val manualNames = REQUIRED_PARTIAL_INDEXES.filter { it[0] == table }.map { it[1] }
    assertEquals(
        "Indexes of $table",
        (roomIndexes.map { it.getString("name") } + manualNames).toSet(),
        actual.indexes.keys,
    )
    roomIndexes.forEach { index ->
        val name = index.getString("name")
        val names = index.getJSONArray("columnNames").strings()
        val orders = index.getJSONArray("orders").strings().ifEmpty { List(names.size) { "ASC" } }
        val predicate =
            REQUIRED_PARTIAL_INDEXES
                .singleOrNull { it[1] == name }
                ?.get(3)
                .orEmpty()
                .sqlTokens()
        assertEquals(
            "$table.$name",
            CurrentIndexIntegrity(index.getBoolean("unique"), names, orders, predicate),
            actual.indexes[name],
        )
    }
}

private fun SupportSQLiteDatabase.integrityRows(
    sql: String,
    vararg columns: String,
): List<List<String?>> =
    query(sql).use { cursor ->
        val positions = columns.map(cursor::getColumnIndexOrThrow)
        buildList {
            while (cursor.moveToNext()) {
                add(
                    positions.map { if (cursor.isNull(it)) null else cursor.getString(it) },
                )
            }
        }
    }

private fun SupportSQLiteDatabase.integritySql(
    type: String,
    name: String,
): String =
    query("SELECT sql FROM sqlite_master WHERE type = ? AND name = ?", arrayOf(type, name)).use {
        assertTrue("Missing $type $name", it.moveToFirst())
        it.getString(0)
    }

// Ignore SQL whitespace and identifier backticks; preserve case within string literals.
private fun String.sqlTokens(): String =
    Regex("'(?:''|[^'])*'|[a-zA-Z_][a-zA-Z_0-9]*|>=|<=|<>|!=|\\S").findAll(replace("`", "")).joinToString(" ") {
        if (it.value.startsWith("'")) it.value else it.value.lowercase(Locale.ROOT)
    }

private fun String.checkExpressions(): Set<String> {
    val tokens = sqlTokens().split(" ")
    return buildSet {
        tokens.forEachIndexed { index, token ->
            if (token == "check") {
                check(tokens[index + 1] == "(")
                var depth = 1
                var end = index + 2
                while (depth > 0) {
                    when (tokens[end++]) {
                        "(" -> depth++
                        ")" -> depth--
                    }
                }
                add(tokens.subList(index + 2, end - 1).joinToString(" "))
            }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = List(length(), ::getJSONObject)

private fun JSONArray.strings(): List<String> = List(length(), ::getString)

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

private val REQUIRED_PARTIAL_INDEXES =
    listOf(
        listOf(
            "activity_template_fields",
            "idx_activity_template_one_main_field",
            "activity_template_id",
            "is_main_value = 1 AND deleted_at_ms IS NULL",
        ),
        listOf("activity_snapshot_fields", "idx_one_activity_main_snapshot_field", "snapshot_id", "is_main_value = 1"),
        listOf(
            "sequence_template_fields",
            "idx_one_sequence_template_main_field",
            "sequence_template_id",
            "is_main_value = 1 AND deleted_at_ms IS NULL",
        ),
        listOf(
            "sequence_snapshot_fields",
            "idx_one_sequence_main_snapshot_field",
            "sequence_snapshot_id",
            "is_main_value = 1",
        ),
        listOf("sequence_occurrences", "idx_one_current_occurrence", "sequence_execution_id", "status = 'CURRENT'"),
        listOf(
            "activity_executions",
            "idx_one_child_execution_per_occurrence",
            "sequence_occurrence_id",
            "sequence_occurrence_id IS NOT NULL",
        ),
    )
