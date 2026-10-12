@file:Suppress("MaxLineLength")
// Independent frozen v10 row-local requirements; do not derive these at test runtime.

package com.alexandr5476.lifetracing.data.persistence

// Database spec v0.6 sections 38/40, and the implementation clarifications for v4/v6/v10.
// Tables absent from this map have no LifeTracing-managed CHECK constraints.
internal val REQUIRED_CURRENT_CHECKS =
    mapOf(
        "activity_templates" to
            setOf(
                "(time_tracking_mode = 'TIMER' AND timer_target_ms IS NOT NULL AND timer_target_ms > 0) OR (time_tracking_mode IN ('STOPWATCH', 'NO_LIVE_TRACKING') AND timer_target_ms IS NULL)",
            ),
        "activity_template_settings" to
            setOf(
                "start_countdown_ms >= 0",
                "timer_zero_behavior IN ('FINISH', 'OVERTIME')",
            ),
        "activity_snapshots" to
            setOf(
                "(time_tracking_mode = 'TIMER' AND timer_target_ms IS NOT NULL AND timer_target_ms > 0) OR (time_tracking_mode IN ('STOPWATCH', 'NO_LIVE_TRACKING') AND timer_target_ms IS NULL)",
            ),
        "activity_snapshot_settings" to
            setOf(
                "start_countdown_ms >= 0",
                "timer_zero_behavior IN ('FINISH', 'OVERTIME')",
            ),
        "activity_executions" to
            setOf(
                "(context_type = 'STANDALONE' AND sequence_execution_id IS NULL AND sequence_occurrence_id IS NULL) OR (context_type = 'SEQUENCE_CHILD' AND sequence_execution_id IS NOT NULL AND sequence_occurrence_id IS NOT NULL AND plan_entry_id IS NULL)",
                "(status IN ('RUNNING', 'PAUSED') AND started_at_ms IS NOT NULL AND completed_at_ms IS NULL AND active_duration_ms IS NULL AND completion_reason IS NULL) OR (status = 'COMPLETED' AND completed_at_ms IS NOT NULL AND ((started_at_ms IS NULL AND active_duration_ms IS NULL) OR (started_at_ms IS NOT NULL AND active_duration_ms IS NOT NULL AND active_duration_ms >= 0)))",
                "original_utc_offset_minutes IS NULL OR original_utc_offset_minutes BETWEEN -1080 AND 1080",
                "completion_reason IS NULL OR completion_reason = 'MANUAL_HISTORY_ENTRY'",
            ),
        "activity_execution_pauses" to
            setOf(
                "ended_at_ms IS NULL OR ended_at_ms >= started_at_ms",
            ),
        "activity_execution_field_values" to
            setOf(
                "(number_scaled IS NOT NULL) + (category_option_id IS NOT NULL) + (text_value IS NOT NULL) = 1",
            ),
        "sequence_templates" to
            setOf(
                "no_live_time_accounting IN ('ACTIVE', 'PAUSE')",
            ),
        "sequence_template_settings" to
            setOf(
                "sequence_start_countdown_ms >= 0",
                "before_each_step_countdown_ms >= 0",
            ),
        "sequence_nodes" to
            setOf(
                "(node_type = 'STEP' AND activity_snapshot_id IS NOT NULL AND repeat_count IS NULL) OR (node_type = 'REPEAT' AND activity_snapshot_id IS NULL AND repeat_count IS NOT NULL AND repeat_count > 0 AND parent_repeat_node_id IS NULL)",
            ),
        "sequence_step_overrides" to
            setOf(
                "start_countdown_ms IS NULL OR start_countdown_ms >= 0",
                "timer_zero_behavior IS NULL OR timer_zero_behavior IN ('FINISH', 'OVERTIME')",
                "timer_end_sound IS NULL OR timer_end_sound IN (0, 1)",
                "timer_end_vibration IS NULL OR timer_end_vibration IN (0, 1)",
                "keep_screen_awake IS NULL OR keep_screen_awake IN (0, 1)",
            ),
        "sequence_snapshot_settings" to
            setOf(
                "sequence_start_countdown_ms >= 0",
                "before_each_step_countdown_ms >= 0",
                "no_live_time_accounting IN ('ACTIVE', 'PAUSE')",
            ),
        "sequence_snapshot_nodes" to
            setOf(
                "(node_type = 'STEP' AND activity_snapshot_id IS NOT NULL AND repeat_count IS NULL) OR (node_type = 'REPEAT' AND activity_snapshot_id IS NULL AND repeat_count IS NOT NULL AND repeat_count > 0 AND parent_repeat_node_id IS NULL)",
            ),
        "sequence_snapshot_step_overrides" to
            setOf(
                "start_countdown_ms IS NULL OR start_countdown_ms >= 0",
                "timer_zero_behavior IS NULL OR timer_zero_behavior IN ('FINISH', 'OVERTIME')",
                "timer_end_sound IS NULL OR timer_end_sound IN (0, 1)",
                "timer_end_vibration IS NULL OR timer_end_vibration IN (0, 1)",
                "keep_screen_awake IS NULL OR keep_screen_awake IN (0, 1)",
            ),
        "sequence_executions" to
            setOf(
                "(status IN ('RUNNING', 'PAUSED') AND ended_at_ms IS NULL AND active_duration_ms IS NULL AND pause_duration_ms IS NULL AND wall_duration_ms IS NULL) OR (status IN ('COMPLETED', 'ENDED_EARLY') AND ended_at_ms IS NOT NULL AND active_duration_ms IS NOT NULL AND active_duration_ms >= 0 AND pause_duration_ms IS NOT NULL AND pause_duration_ms >= 0 AND wall_duration_ms IS NOT NULL AND wall_duration_ms >= 0 AND current_occurrence_id IS NULL)",
                "ended_at_ms IS NULL OR ended_at_ms >= started_at_ms",
                "original_utc_offset_minutes IS NULL OR original_utc_offset_minutes BETWEEN -1080 AND 1080",
            ),
        "sequence_occurrences" to
            setOf(
                "runtime_position >= 0",
                "repeat_iteration IS NULL OR repeat_iteration >= 1",
                "is_runtime_added IN (0, 1)",
                "is_deleted_from_history IN (0, 1)",
                "completion_reason IS NULL OR completion_reason IN ('NATURAL_TIMER_END', 'MANUAL_FINISH', 'ADVANCED_TO_NEXT', 'JUMP', 'SEQUENCE_ENDED_EARLY')",
                "(status = 'NOT_STARTED' AND entered_at_ms IS NULL AND completed_at_ms IS NULL AND completion_reason IS NULL) OR (status = 'CURRENT' AND entered_at_ms IS NOT NULL AND completed_at_ms IS NULL AND completion_reason IS NULL) OR (status = 'SKIPPED' AND entered_at_ms IS NULL AND completed_at_ms IS NULL AND completion_reason IS NULL) OR (status = 'COMPLETED' AND entered_at_ms IS NOT NULL AND completed_at_ms IS NOT NULL AND completed_at_ms >= entered_at_ms) OR status = 'DELETED_EXECUTION'",
            ),
        "sequence_intervals" to
            setOf(
                "kind IN ('ACTIVE_STEP', 'STEP_PAUSE', 'EXPLICIT_PAUSE', 'IMPLICIT_IDLE', 'TRANSITION_COUNTDOWN')",
                "ended_at_ms IS NULL OR ended_at_ms >= started_at_ms",
            ),
        "sequence_execution_field_values" to
            setOf(
                "(number_scaled IS NOT NULL) + (category_option_id IS NOT NULL) + (text_value IS NOT NULL) = 1",
            ),
        "active_session" to
            setOf(
                "singleton_id = 1",
                "session_kind IN ('ACTIVITY', 'SEQUENCE')",
                "state IN ('RUNNING', 'PAUSED', 'WAITING_NEXT')",
                "(session_kind = 'ACTIVITY' AND activity_execution_id IS NOT NULL AND sequence_execution_id IS NULL AND state IN ('RUNNING', 'PAUSED')) OR (session_kind = 'SEQUENCE' AND activity_execution_id IS NULL AND sequence_execution_id IS NOT NULL AND state IN ('RUNNING', 'PAUSED', 'WAITING_NEXT'))",
            ),
        "plan_entries" to
            setOf(
                "trackable_kind IN ('ACTIVITY', 'SEQUENCE')",
                "precision IN ('DAY', 'WEEK', 'MONTH')",
                "status IN ('PLANNED', 'FULFILLED', 'CANCELLED')",
                "source_revision IS NULL OR source_revision >= 1",
                "(source_activity_template_id IS NULL AND source_sequence_template_id IS NULL) OR (source_activity_template_id IS NOT NULL AND source_sequence_template_id IS NULL AND source_revision IS NOT NULL) OR (source_activity_template_id IS NULL AND source_sequence_template_id IS NOT NULL AND source_revision IS NOT NULL)",
                "(trackable_kind = 'ACTIVITY' AND activity_snapshot_id IS NOT NULL AND sequence_plan_snapshot_id IS NULL AND source_sequence_template_id IS NULL AND fulfilled_sequence_execution_id IS NULL) OR (trackable_kind = 'SEQUENCE' AND sequence_plan_snapshot_id IS NOT NULL AND activity_snapshot_id IS NULL AND source_activity_template_id IS NULL AND fulfilled_activity_execution_id IS NULL)",
                "(precision = 'DAY' AND scheduled_instant_ms IS NOT NULL AND planned_day IS NULL AND planned_week_start IS NULL AND planned_month IS NULL) OR (precision = 'DAY' AND scheduled_instant_ms IS NULL AND planned_day IS NOT NULL AND planned_week_start IS NULL AND planned_month IS NULL AND creation_zone_id IS NULL) OR (precision = 'WEEK' AND planned_week_start IS NOT NULL AND scheduled_instant_ms IS NULL AND planned_day IS NULL AND planned_month IS NULL AND creation_zone_id IS NULL) OR (precision = 'MONTH' AND planned_month IS NOT NULL AND scheduled_instant_ms IS NULL AND planned_day IS NULL AND planned_week_start IS NULL AND creation_zone_id IS NULL)",
                "(status = 'PLANNED' AND cancelled_at_ms IS NULL AND fulfilled_at_ms IS NULL AND fulfilled_activity_execution_id IS NULL AND fulfilled_sequence_execution_id IS NULL) OR (status = 'CANCELLED' AND cancelled_at_ms IS NOT NULL AND fulfilled_at_ms IS NULL AND fulfilled_activity_execution_id IS NULL AND fulfilled_sequence_execution_id IS NULL) OR (status = 'FULFILLED' AND fulfilled_at_ms IS NOT NULL AND cancelled_at_ms IS NULL)",
                "fulfilled_activity_execution_id IS NULL OR fulfilled_sequence_execution_id IS NULL",
                "fulfilled_at_ms IS NULL OR fulfilled_at_ms >= created_at_ms",
                "updated_at_ms >= created_at_ms",
                "cancelled_at_ms IS NULL OR cancelled_at_ms >= created_at_ms",
            ),
    )
