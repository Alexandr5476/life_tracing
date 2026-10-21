@file:Suppress("MatchingDeclarationName") // Shared presentation functions and their input model.

package com.alexandr5476.lifetracing.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Presentation only: neither labels nor section ordinals authorize a command. */
internal data class PlanExecutionActionTarget(
    val title: String,
    val kind: PlanTrackableKind,
    val target: PlanTarget,
    val noLive: Boolean,
    val effectiveLocalDate: LocalDate?,
    val exactLocalTime: LocalTime?,
    val exactUtcOffset: ZoneOffset?,
)

/** Linear work over one presented section; no repository or historical lookups. */
@Composable
internal fun planExecutionActionDescriptions(targets: List<PlanExecutionActionTarget>): List<String> {
    val descriptions = targets.map { executionDescription(it) }
    val counts = descriptions.groupingBy { it }.eachCount()
    val positions = mutableMapOf<String, Int>()
    return descriptions.map { description ->
        val count = counts.getValue(description)
        if (count == 1) {
            description
        } else {
            val ordinal = positions.getOrDefault(description, 0) + 1
            positions[description] = ordinal
            stringResource(R.string.plan_execution_action_ordinal, description, ordinal, count)
        }
    }
}

@Composable
private fun executionDescription(target: PlanExecutionActionTarget): String {
    val operation = stringResource(if (target.noLive) R.string.plan_complete_action else R.string.plan_start_action)
    val kind =
        stringResource(
            if (target.kind ==
                PlanTrackableKind.ACTIVITY
            ) {
                R.string.plan_execution_activity_kind
            } else {
                R.string.plan_execution_sequence
            },
        )
    return stringResource(
        R.string.plan_execution_action_context,
        stringResource(R.string.plan_execution_action, target.title, operation),
        kind,
        target.scheduleDescription(),
    )
}

@Composable
private fun PlanExecutionActionTarget.scheduleDescription(): String {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    return when (val schedule = target) {
        is PlanTarget.FloatingDay -> stringResource(R.string.plan_execution_day, dateFormat.format(schedule.date))
        is PlanTarget.Week -> stringResource(R.string.plan_execution_week, dateFormat.format(schedule.weekStart))
        is PlanTarget.Month -> error("Month Plans have no execution action")
        is PlanTarget.ExactDay -> {
            val date = effectiveLocalDate
            val time = exactLocalTime
            if (date == null || time == null) {
                // The canonical Instant is safe even when no local projection is supplied.
                stringResource(R.string.plan_execution_exact, schedule.scheduledAt.toString())
            } else {
                val localTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
                val clock =
                    exactUtcOffset?.let { offset ->
                        stringResource(
                            R.string.calendar_time_with_offset,
                            localTime,
                            if (offset.totalSeconds == 0) "+00:00" else offset.id,
                        )
                    } ?: localTime
                stringResource(R.string.plan_execution_exact_local, dateFormat.format(date), clock)
            }
        }
    }
}
