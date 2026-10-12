@file:Suppress("MaxLineLength", "LongMethod", "TooManyFunctions")
// Historical SQL is independent of current-schema writers and mappers.

package com.alexandr5476.lifetracing.data.persistence

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun SupportSQLiteDatabase.seedFrozenActivityV3() {
    execSQL("INSERT INTO folders VALUES ('folder', 'Historical folder', NULL, 10, 20)")
    execSQL("INSERT INTO tags VALUES ('tag', 'Historical tag', 10, 20)")
    execSQL(
        "INSERT INTO statistics_series VALUES ('activity-series', 'ACTIVITY', 'Historical activity series', 10, NULL)",
    )
    execSQL(
        "INSERT INTO activity_templates VALUES ('activity-template', 'Current activity', 'Current comment', 'TIMER', 90000, 'activity-series', 9, 10, 20, NULL, 'folder')",
    )
    execSQL("INSERT INTO activity_template_settings (activity_template_id) VALUES ('activity-template')")
    execSQL("INSERT INTO activity_template_user_state VALUES ('activity-template', 1, 20)")
    execSQL("INSERT INTO activity_template_tags VALUES ('activity-template', 'tag')")
    execSQL(
        "INSERT INTO activity_template_fields VALUES ('number', 'activity-template', 0, 'Current amount', 'NUMBER', 'kg', 2, 9000, NULL, NULL, 1, 10, 20, NULL)",
    )
    execSQL(
        "INSERT INTO activity_template_fields VALUES ('category', 'activity-template', 1, 'Current effort', 'CATEGORY', NULL, NULL, NULL, NULL, NULL, 0, 10, 20, NULL)",
    )
    execSQL("INSERT INTO activity_template_category_options VALUES ('option', 'category', 0, 'Current option', 1)")
    execSQL(
        "INSERT INTO activity_template_fields VALUES ('text', 'activity-template', 2, 'Current note', 'TEXT', NULL, NULL, NULL, NULL, 'Current default', 0, 10, 20, NULL)",
    )
    execSQL(
        "INSERT INTO activity_snapshots VALUES ('activity-snapshot', 'Frozen activity', 'Frozen comment', 'TIMER', 30000, 'activity-template', 3, 'activity-series', 1, 15)",
    )
    execSQL("INSERT INTO activity_snapshot_settings VALUES ('activity-snapshot', 0, 2000, 'OVERTIME', 0, 1, 1, 1)")
    execSQL(
        "INSERT INTO activity_snapshot_fields VALUES ('f-number', 'activity-snapshot', 'number', 0, 'Captured amount', 'Local amount', 'NUMBER', 'kg', 1, 1500, NULL, NULL, 1)",
    )
    execSQL(
        "INSERT INTO activity_snapshot_fields VALUES ('f-category', 'activity-snapshot', 'category', 1, 'Captured effort', NULL, 'CATEGORY', NULL, NULL, NULL, 'f-option', NULL, 0)",
    )
    execSQL(
        "INSERT INTO activity_snapshot_category_options VALUES ('f-option', 'f-category', 'option', 0, 'Captured option', 'Local option')",
    )
    execSQL(
        "INSERT INTO activity_snapshot_fields VALUES ('f-text', 'activity-snapshot', 'text', 2, 'Captured note', NULL, 'TEXT', NULL, NULL, NULL, NULL, 'Frozen default', 0)",
    )
}

internal fun SupportSQLiteDatabase.seedFrozenSequenceV6() {
    execSQL(
        "INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Historical sequence series', 10, NULL)",
    )
    execSQL(
        "INSERT INTO sequence_templates VALUES ('sequence-template', 'Current sequence', 'Current sequence comment', 'sequence-series', 8, 10, 20, NULL, 'folder', 'PAUSE')",
    )
    execSQL(
        "INSERT INTO sequence_template_settings (sequence_template_id, auto_advance) VALUES ('sequence-template', 0)",
    )
    execSQL("INSERT INTO sequence_template_user_state VALUES ('sequence-template', 2, 20)")
    execSQL("INSERT INTO sequence_template_tags VALUES ('sequence-template', 'tag')")
    execSQL(
        "INSERT INTO sequence_template_fields VALUES ('s-number', 'sequence-template', 0, 'Current rounds', 'NUMBER', 'reps', 0, 9000, NULL, NULL, 1, 10, 20, NULL)",
    )
    execSQL(
        "INSERT INTO sequence_template_fields VALUES ('s-category', 'sequence-template', 1, 'Current quality', 'CATEGORY', NULL, NULL, NULL, 's-option', NULL, 0, 10, 20, NULL)",
    )
    execSQL(
        "INSERT INTO sequence_template_category_options VALUES ('s-option', 's-category', 0, 'Current quality option', 0)",
    )
    execSQL(
        "INSERT INTO sequence_nodes VALUES ('top', 'sequence-template', 'STEP', NULL, 0, 'activity-snapshot', NULL)",
    )
    execSQL("INSERT INTO sequence_nodes VALUES ('repeat', 'sequence-template', 'REPEAT', NULL, 1, NULL, 4)")
    execSQL(
        "INSERT INTO sequence_nodes VALUES ('nested', 'sequence-template', 'STEP', 'repeat', 0, 'activity-snapshot', NULL)",
    )
    execSQL("INSERT INTO sequence_step_overrides VALUES ('nested', 0, 'OVERTIME', 0, 1, 1)")
    execSQL(
        "INSERT INTO sequence_snapshots VALUES ('sequence-snapshot', 'Frozen sequence', 'Frozen sequence comment', 'sequence-template', 2, 'sequence-series', 15)",
    )
    execSQL("INSERT INTO sequence_snapshot_settings VALUES ('sequence-snapshot', 1, 0, 0, 0, 1, 1, 1, 1, 'ACTIVE')")
    execSQL(
        "INSERT INTO sequence_snapshot_fields VALUES ('sf-number', 'sequence-snapshot', 's-number', 0, 'Captured rounds', 'Local rounds', 'NUMBER', 'reps', 0, 2000, NULL, NULL, 1)",
    )
    execSQL(
        "INSERT INTO sequence_snapshot_fields VALUES ('sf-category', 'sequence-snapshot', 's-category', 1, 'Captured quality', NULL, 'CATEGORY', NULL, NULL, NULL, 'sf-option', NULL, 0)",
    )
    execSQL(
        "INSERT INTO sequence_snapshot_category_options VALUES ('sf-option', 'sf-category', 's-option', 0, 'Captured quality option', 'Local quality option')",
    )
    execSQL(
        "INSERT INTO sequence_snapshot_nodes VALUES ('f-top', 'sequence-snapshot', 'STEP', NULL, 0, 'activity-snapshot', NULL)",
    )
    execSQL("INSERT INTO sequence_snapshot_nodes VALUES ('f-repeat', 'sequence-snapshot', 'REPEAT', NULL, 1, NULL, 2)")
    execSQL(
        "INSERT INTO sequence_snapshot_nodes VALUES ('f-nested', 'sequence-snapshot', 'STEP', 'f-repeat', 0, 'activity-snapshot', NULL)",
    )
    execSQL("INSERT INTO sequence_snapshot_step_overrides VALUES ('f-nested', 0, 'OVERTIME', 0, 1, 1)")
}

internal fun SupportSQLiteDatabase.seedStandaloneV4(start: Long) {
    execSQL(
        "INSERT INTO activity_executions VALUES ('timed', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', 'COMPLETED', ?, ?, 50000, 'Asia/Tokyo', 540, '2020-01-02', NULL, NULL, ?, ?)",
        arrayOf<Any>(start, start + 60000, start, start + 60000),
    )
    execSQL(
        "INSERT INTO activity_execution_pauses VALUES ('pause', 'timed', ?, ?)",
        arrayOf<Any>(
            start + 10000,
            start + 20000,
        ),
    )
    execSQL("INSERT INTO activity_execution_field_values VALUES ('timed', 'f-number', 0, NULL, NULL)")
    execSQL("INSERT INTO activity_execution_field_values VALUES ('timed', 'f-category', NULL, 'f-option', NULL)")
    execSQL("INSERT INTO activity_execution_field_values VALUES ('timed', 'f-text', NULL, NULL, 'Recorded note')")
}

internal fun SupportSQLiteDatabase.seedTerminalV7(start: Long) {
    execSQL(
        "INSERT INTO activity_snapshots VALUES ('no-live-snapshot', 'Frozen no-live', 'Frozen comment', 'NO_LIVE_TRACKING', NULL, 'activity-template', 3, 'activity-series', 0, 15)",
    )
    execSQL("INSERT INTO activity_snapshot_settings VALUES ('no-live-snapshot', 0, 2000, 'OVERTIME', 0, 1, 1, 1)")
    execSQL(
        "INSERT INTO activity_executions VALUES ('zero', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', 'COMPLETED', ?, ?, 0, 'Asia/Tokyo', NULL, '2020-01-02', NULL, NULL, ?, ?)",
        Array<Any>(4) { start + 120000 },
    )
    execSQL(
        "INSERT INTO activity_executions VALUES ('no-live', 'no-live-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', 'COMPLETED', NULL, ?, NULL, 'Asia/Tokyo', 540, '2020-01-02', NULL, NULL, ?, ?)",
        Array<Any>(3) { start + 180000 },
    )
    execSQL(
        "INSERT INTO sequence_executions VALUES ('sequence-execution', 'sequence-snapshot', NULL, 'sequence-series', 'COMPLETED', ?, ?, 90000, 20000, 110000, 'Asia/Tokyo', 540, '2020-01-02', NULL, ?, ?)",
        arrayOf<Any>(start, start + 110000, start, start + 110000),
    )
    repeat(3) { index ->
        val entered = start + index * 40000
        execSQL(
            "INSERT INTO sequence_occurrences VALUES (?, 'sequence-execution', ?, 'activity-snapshot', ?, ?, ?, 'COMPLETED', ?, ?, 'MANUAL_FINISH', 0, 0)",
            arrayOf<Any?>(
                "occurrence-$index",
                if (index == 0) "f-top" else "f-nested",
                index,
                if (index == 0) null else "f-repeat",
                if (index == 0) null else index,
                entered,
                entered + 30000,
            ),
        )
        execSQL(
            "INSERT INTO sequence_intervals VALUES (?, 'sequence-execution', 'ACTIVE_STEP', ?, ?, ?)",
            arrayOf<Any>("active-$index", entered, entered + 30000, "occurrence-$index"),
        )
        execSQL(
            "INSERT INTO activity_executions VALUES (?, 'activity-snapshot', 'SEQUENCE_CHILD', 'sequence-execution', ?, NULL, 'activity-series', 'COMPLETED', ?, ?, 30000, 'Asia/Tokyo', 540, '2020-01-02', NULL, NULL, ?, ?)",
            arrayOf<Any>("child-$index", "occurrence-$index", entered, entered + 30000, entered, entered + 30000),
        )
        execSQL(
            "INSERT INTO activity_execution_field_values VALUES (?, 'f-number', 2000, NULL, NULL)",
            arrayOf<Any>("child-$index"),
        )
    }
    execSQL(
        "INSERT INTO sequence_intervals VALUES ('idle-0', 'sequence-execution', 'IMPLICIT_IDLE', ?, ?, NULL)",
        arrayOf<Any>(start + 30000, start + 40000),
    )
    execSQL(
        "INSERT INTO sequence_intervals VALUES ('pause-1', 'sequence-execution', 'EXPLICIT_PAUSE', ?, ?, NULL)",
        arrayOf<Any>(start + 70000, start + 80000),
    )
    execSQL("INSERT INTO sequence_execution_field_values VALUES ('sequence-execution', 'sf-number', 0, NULL, NULL)")
    execSQL(
        "INSERT INTO sequence_execution_field_values VALUES ('sequence-execution', 'sf-category', NULL, 'sf-option', NULL)",
    )
}
