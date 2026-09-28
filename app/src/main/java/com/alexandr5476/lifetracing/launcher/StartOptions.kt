@file:Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition", "MaxLineLength")

package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.history.HistoricalLocalDateTimeResolution
import com.alexandr5476.lifetracing.history.ManualEntryFieldDraft
import com.alexandr5476.lifetracing.history.TemplateEntryValueIssue
import com.alexandr5476.lifetracing.history.TemplateEntryValues
import com.alexandr5476.lifetracing.history.initialEntryValues
import com.alexandr5476.lifetracing.history.proposeEntryValues
import com.alexandr5476.lifetracing.history.resolveHistoricalLocalDateTime
import com.alexandr5476.lifetracing.history.toHistoricalLocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

enum class StartOptionsIssue {
    INVALID_START,
    INVALID_END,
    NONEXISTENT_START,
    NONEXISTENT_END,
    AMBIGUOUS_START,
    AMBIGUOUS_END,
    FUTURE_START,
    FUTURE_END,
    REVERSED_INTERVAL,
    INVALID_NUMBER,
    INVALID_CATEGORY,
    EXPIRED_FINISH,
    LIVE_CONFLICT,
    STALE_TEMPLATE,
    ZONE_CHANGED,
    READ_FAILURE,
    SAVE_FAILURE,
}

data class StartOptionsDraft(
    val template: ActivityTemplate,
    val startedText: String,
    val completedText: String,
    val startedOffset: ZoneOffset? = null,
    val completedOffset: ZoneOffset? = null,
    val startedOffsets: List<ZoneOffset> = emptyList(),
    val completedOffsets: List<ZoneOffset> = emptyList(),
    val values: Map<ActivityTemplateFieldId, ManualEntryFieldDraft>,
    val issue: StartOptionsIssue? = null,
    val overlap: StartOptionsProposal? = null,
    val draftVersion: Long = 0,
    val stale: Boolean = false,
)

data class StartOptionsProposal(
    val source: ActivityEntrySource.Template,
    val expectedRevision: Long,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val commandAt: Instant,
    val zoneId: ZoneId,
    val values: List<ActivityEntryValueOverride>,
    val draftVersion: Long,
) {
    val isLive: Boolean get() = startedAt != null && completedAt == null
    val interval: Pair<Instant, Instant>? get() =
        if (startedAt != null &&
            completedAt != null
        ) {
            startedAt to completedAt
        } else {
            null
        }
}

internal sealed interface StartOptionsValidation {
    data class Valid(
        val proposal: StartOptionsProposal,
    ) : StartOptionsValidation

    data class Invalid(
        val issue: StartOptionsIssue,
        val offsets: List<ZoneOffset> = emptyList(),
    ) : StartOptionsValidation
}

internal fun ActivityTemplate.initialStartOptions(
    now: Instant,
    zone: ZoneId,
): StartOptionsDraft {
    val local = now.toHistoricalLocalDateTime(zone)
    return StartOptionsDraft(
        template = this,
        startedText = local.text,
        completedText = if (timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) local.text else "",
        startedOffset = local.selectedOffset.takeIf { local.validOffsets.isEmpty() },
        completedOffset = local.selectedOffset.takeIf { local.validOffsets.isEmpty() },
        values = initialEntryValues(),
    )
}

internal fun StartOptionsDraft.validate(
    commandAt: Instant,
    zone: ZoneId,
): StartOptionsValidation {
    fun resolve(
        text: String,
        offset: ZoneOffset?,
        start: Boolean,
    ): Pair<Instant?, StartOptionsValidation.Invalid?> =
        when (val result = resolveHistoricalLocalDateTime(text, zone, offset)) {
            is HistoricalLocalDateTimeResolution.Resolved -> result.instant to null
            HistoricalLocalDateTimeResolution.Invalid ->
                null to
                    StartOptionsValidation.Invalid(
                        if (start) StartOptionsIssue.INVALID_START else StartOptionsIssue.INVALID_END,
                    )
            HistoricalLocalDateTimeResolution.Nonexistent ->
                null to
                    StartOptionsValidation.Invalid(
                        if (start) StartOptionsIssue.NONEXISTENT_START else StartOptionsIssue.NONEXISTENT_END,
                    )
            is HistoricalLocalDateTimeResolution.Ambiguous ->
                null to
                    StartOptionsValidation.Invalid(
                        if (start) StartOptionsIssue.AMBIGUOUS_START else StartOptionsIssue.AMBIGUOUS_END,
                        result.offsets,
                    )
        }
    val noLive = template.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING
    val start = if (noLive) null else resolve(startedText, startedOffset, true)
    start?.second?.let { return it }
    val completed = if (noLive || completedText.isNotBlank()) resolve(completedText, completedOffset, false) else null
    completed?.second?.let { return it }
    val startedAt = start?.first
    val completedAt = completed?.first
    if (startedAt != null && (startedAt > commandAt || (completedAt == null && startedAt == commandAt))) {
        return StartOptionsValidation.Invalid(StartOptionsIssue.FUTURE_START)
    }
    if (completedAt != null &&
        completedAt > commandAt
    ) {
        return StartOptionsValidation.Invalid(StartOptionsIssue.FUTURE_END)
    }
    if (startedAt != null && completedAt != null && completedAt < startedAt) {
        return StartOptionsValidation.Invalid(StartOptionsIssue.REVERSED_INTERVAL)
    }
    val overrides =
        when (val proposed = template.proposeEntryValues(values)) {
            is TemplateEntryValues.Valid -> proposed.values
            is TemplateEntryValues.Invalid -> return StartOptionsValidation.Invalid(
                when (proposed.issue) {
                    TemplateEntryValueIssue.MISSING_DRAFT -> StartOptionsIssue.STALE_TEMPLATE
                    TemplateEntryValueIssue.INVALID_NUMBER -> StartOptionsIssue.INVALID_NUMBER
                    TemplateEntryValueIssue.INVALID_CATEGORY -> StartOptionsIssue.INVALID_CATEGORY
                },
            )
        }
    return StartOptionsValidation.Valid(
        StartOptionsProposal(
            ActivityEntrySource.Template(template.id),
            template.revision,
            startedAt,
            completedAt,
            commandAt,
            zone,
            overrides,
            draftVersion,
        ),
    )
}
