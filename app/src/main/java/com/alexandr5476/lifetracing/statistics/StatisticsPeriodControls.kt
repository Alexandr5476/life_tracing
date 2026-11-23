@file:Suppress("FunctionNaming", "LongParameterList", "TooManyFunctions")

package com.alexandr5476.lifetracing.statistics

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.dateRangeOrNull
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal enum class StatisticsPeriodKind(
    val label: Int,
    val inputLabel: Int? = null,
    val invalidInput: Int? = null,
) {
    DAY(R.string.statistics_period_day, R.string.statistics_day_date, R.string.statistics_invalid_date),
    WEEK(R.string.statistics_period_week, R.string.statistics_week_date, R.string.statistics_invalid_week),
    MONTH(R.string.statistics_period_month, R.string.statistics_month_date, R.string.statistics_invalid_month),
    YEAR(R.string.statistics_period_year, R.string.statistics_year_date, R.string.statistics_invalid_year),
    CUSTOM(R.string.statistics_period_custom, R.string.statistics_custom_start, R.string.statistics_invalid_range),
    ALL_TIME(R.string.statistics_period_all_time),
    ;

    val showsNavigation: Boolean
        get() = this != CUSTOM && this != ALL_TIME

    companion object {
        fun from(period: StatisticsPeriod) =
            when (period) {
                is StatisticsPeriod.Day -> DAY
                is StatisticsPeriod.Week -> WEEK
                is StatisticsPeriod.Month -> MONTH
                is StatisticsPeriod.Year -> YEAR
                is StatisticsPeriod.Custom -> CUSTOM
                StatisticsPeriod.AllTime -> ALL_TIME
            }
    }
}

internal data class StatisticsPeriodDraft(
    val start: String,
    val end: String = "",
) {
    fun period(kind: StatisticsPeriodKind): StatisticsPeriod? =
        runCatching {
            val result =
                when (kind) {
                    StatisticsPeriodKind.DAY -> StatisticsPeriod.Day(LocalDate.parse(start))
                    StatisticsPeriodKind.WEEK -> StatisticsPeriod.Week(LocalDate.parse(start).with(monday))
                    StatisticsPeriodKind.MONTH -> StatisticsPeriod.Month(YearMonth.parse(start, isoMonth))
                    StatisticsPeriodKind.YEAR -> StatisticsPeriod.Year(Year.parse(start, isoYear))
                    StatisticsPeriodKind.CUSTOM -> StatisticsPeriod.Custom(LocalDate.parse(start), LocalDate.parse(end))
                    StatisticsPeriodKind.ALL_TIME -> StatisticsPeriod.AllTime
                }
            result.dateRangeOrNull()
            result
        }.getOrNull()

    companion object {
        fun from(
            kind: StatisticsPeriodKind,
            applied: StatisticsPeriod,
            date: LocalDate,
        ): StatisticsPeriodDraft =
            when (kind) {
                StatisticsPeriodKind.DAY -> StatisticsPeriodDraft(date.toString())
                StatisticsPeriodKind.WEEK -> StatisticsPeriodDraft(date.with(monday).toString())
                StatisticsPeriodKind.MONTH -> StatisticsPeriodDraft(isoMonth.format(date))
                StatisticsPeriodKind.YEAR -> StatisticsPeriodDraft(isoYear.format(date))
                StatisticsPeriodKind.CUSTOM ->
                    if (applied is StatisticsPeriod.Custom) {
                        StatisticsPeriodDraft(applied.startDate.toString(), applied.endDateInclusive.toString())
                    } else {
                        StatisticsPeriodDraft("")
                    }
                StatisticsPeriodKind.ALL_TIME -> StatisticsPeriodDraft("")
            }
    }
}

private const val ISO_YEAR_WIDTH = 4
private const val MAX_YEAR_WIDTH = 9
private val monday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
private val isoYear =
    DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, ISO_YEAR_WIDTH, MAX_YEAR_WIDTH, SignStyle.EXCEEDS_PAD)
        .toFormatter(Locale.ROOT)
private val isoMonth = DateTimeFormatterBuilder().append(isoYear).appendPattern("-MM").toFormatter(Locale.ROOT)

internal class StatisticsPeriodEditor(
    val applied: StatisticsPeriod,
) {
    private val date =
        when (applied) {
            is StatisticsPeriod.Week -> applied.weekStart
            else -> applied.dateRangeOrNull()?.startDate ?: LocalDate.now()
        }
    private val drafts = mutableStateMapOf<StatisticsPeriodKind, StatisticsPeriodDraft>()
    var kind by mutableStateOf(StatisticsPeriodKind.from(applied))
        private set
    var attempted by mutableStateOf(false)
        private set

    val draft: StatisticsPeriodDraft
        get() = drafts[kind] ?: StatisticsPeriodDraft.from(kind, applied, date)

    fun select(
        kind: StatisticsPeriodKind,
        onSelect: (StatisticsPeriod) -> Unit,
    ) {
        this.kind = kind
        attempted = false
        if (kind != StatisticsPeriodKind.CUSTOM) draft.period(kind)?.let(onSelect)
    }

    fun edit(
        value: String,
        end: Boolean,
    ) {
        drafts[kind] = if (end) draft.copy(end = value) else draft.copy(start = value)
        attempted = false
    }

    fun apply(onSelect: (StatisticsPeriod) -> Unit) {
        val period = draft.period(kind)
        attempted = period == null
        if (period != null) {
            if (period == applied) drafts.remove(kind)
            onSelect(period)
        }
    }

    fun matchesApplied(): Boolean =
        kind == StatisticsPeriodKind.from(applied) && draft == StatisticsPeriodDraft.from(kind, applied, date)
}

internal fun navigate(
    period: StatisticsPeriod,
    amount: Long,
): StatisticsPeriod? =
    runCatching {
        val destination =
            when (period) {
                is StatisticsPeriod.Day -> StatisticsPeriod.Day(period.date.plusDays(amount))
                is StatisticsPeriod.Week -> StatisticsPeriod.Week(period.weekStart.plusWeeks(amount))
                is StatisticsPeriod.Month -> StatisticsPeriod.Month(period.month.plusMonths(amount))
                is StatisticsPeriod.Year -> StatisticsPeriod.Year(period.year.plusYears(amount))
                is StatisticsPeriod.Custom, StatisticsPeriod.AllTime -> null
            }
        destination?.dateRangeOrNull()
        destination
    }.getOrNull()

@Composable
internal fun statisticsPeriodDescription(period: StatisticsPeriod): String {
    val locale = LocalConfiguration.current.locales[0]
    val kind = stringResource(StatisticsPeriodKind.from(period).label)
    val range = runCatching { period.dateRangeOrNull() }.getOrNull()
    return when (period) {
        StatisticsPeriod.AllTime -> stringResource(R.string.statistics_period_unbounded, kind)
        is StatisticsPeriod.Day ->
            stringResource(R.string.statistics_period_single_date, kind, statisticsPeriodDate(period.date, locale))
        else -> {
            if (range == null && period is StatisticsPeriod.Week) {
                stringResource(
                    R.string.statistics_period_single_date,
                    kind,
                    statisticsPeriodDate(period.weekStart, locale),
                )
            } else {
                requireNotNull(range)
                stringResource(
                    R.string.statistics_period_date_range,
                    kind,
                    statisticsPeriodDate(range.startDate, locale),
                    statisticsPeriodDate(range.endDateInclusive, locale),
                )
            }
        }
    }
}

/** The requested focus survives editor recomposition and only moves when a focused control disappears. */
internal class StatisticsPeriodFocus {
    val kindButtons = StatisticsPeriodKind.entries.associateWith { FocusRequester() }
    var owner: Any? = null
    var recover by mutableStateOf(false)
    var editorFocused = false
}

@Composable
internal fun StatisticsPeriodControls(
    editor: StatisticsPeriodEditor,
    focus: StatisticsPeriodFocus,
    admission: StatisticsActionAdmission,
    tag: String,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = editor.kind
    val error = kind.invalidInput?.takeIf { editor.draft.period(kind) == null }?.let { stringResource(it) }
    val currentEditor = { editor.kind == kind }
    LaunchedEffect(focus.recover, kind, admission) {
        if (focus.recover && admission.isCurrent()) {
            withFrameNanos { }
            if (focus.recover && admission.isCurrent()) {
                focus.kindButtons.getValue(kind).requestFocus()
                focus.recover = false
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(
            stringResource(R.string.statistics_period_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.statistics_period_applied, statisticsPeriodDescription(editor.applied)),
            Modifier.testTag("$tag-applied-period"),
        )
        PeriodKindButtons(editor, focus, admission, tag, onSelectPeriod)
        Text(
            stringResource(R.string.statistics_period_editing, stringResource(kind.label)),
            Modifier.testTag("$tag-editing-period"),
        )
        if (kind.inputLabel != null) {
            key(kind) {
                PeriodDraftControls(editor, focus, admission, tag, error, onSelectPeriod)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            PeriodNavigation(editor, focus, admission, tag, -1, currentEditor, onSelectPeriod)
            PeriodNavigation(editor, focus, admission, tag, 1, currentEditor, onSelectPeriod)
        }
    }
}

@Composable
private fun PeriodKindButtons(
    editor: StatisticsPeriodEditor,
    focus: StatisticsPeriodFocus,
    admission: StatisticsActionAdmission,
    tag: String,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        StatisticsPeriodKind.entries.forEach { kind ->
            val isEditing = editor.kind == kind
            StatisticsChoiceButton(
                admission,
                selected = isEditing,
                onClick = {
                    if (focus.editorFocused || !kind.showsNavigation && focus.owner !is StatisticsPeriodKind) {
                        focus.recover = focus.owner != null
                    }
                    editor.select(kind, onSelectPeriod)
                },
                applicable = { editor.kind != kind },
                modifier =
                    Modifier
                        .focusRequester(focus.kindButtons.getValue(kind))
                        .onFocusChanged {
                            if (it.isFocused) {
                                focus.owner = kind
                            }
                        }.semantics { selected = isEditing }
                        .testTag("$tag-period-${kind.name.lowercase(Locale.ROOT)}"),
            ) { Text(stringResource(kind.label)) }
        }
    }
}

@Composable
private fun PeriodDraftControls(
    editor: StatisticsPeriodEditor,
    focus: StatisticsPeriodFocus,
    admission: StatisticsActionAdmission,
    tag: String,
    error: String?,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
) {
    val kind = editor.kind
    val draft = editor.draft
    val custom = kind == StatisticsPeriodKind.CUSTOM
    val applicable = { editor.kind == kind }
    val apply = admission.action(applicable = applicable) { editor.apply(onSelectPeriod) }
    val startError = error?.takeIf { !custom || runCatching { LocalDate.parse(draft.start) }.isFailure }
    val endError = error?.takeIf { startError == null || runCatching { LocalDate.parse(draft.end) }.isFailure }
    Column(
        focus.recoverOnRemoval(editorGroup = true).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        PeriodInput(
            draft.start,
            requireNotNull(kind.inputLabel),
            startError,
            Modifier.testTag(if (custom) "$tag-custom-start" else "$tag-period-input"),
            admission.isCurrent(),
            { value -> admission.run(applicable()) { editor.edit(value, end = false) } },
            apply,
        )
        if (custom) {
            PeriodInput(
                draft.end,
                R.string.statistics_custom_end,
                endError,
                Modifier.testTag("$tag-custom-end"),
                admission.isCurrent(),
                { value -> admission.run(applicable()) { editor.edit(value, end = true) } },
                apply,
            )
        }
        if (error != null) {
            Text(
                error,
                Modifier.semantics { if (editor.attempted) liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!editor.matchesApplied()) {
            Text(stringResource(R.string.statistics_period_unapplied), Modifier.testTag("$tag-period-draft"))
        }
        StatisticsSecondaryButton(
            admission,
            onClick = { editor.apply(onSelectPeriod) },
            applicable = applicable,
            modifier = Modifier.testTag(if (custom) "$tag-custom-apply" else "statistics-period-apply"),
        ) { Text(stringResource(if (custom) R.string.statistics_apply_range else R.string.statistics_apply)) }
    }
}

@Composable
private fun PeriodInput(
    value: String,
    label: Int,
    validation: String?,
    modifier: Modifier,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onApply: () -> Boolean,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { if (validation != null) error(validation) }
                .onPreviewKeyEvent {
                    when (it.key) {
                        Key.Tab -> {
                            if (it.type == KeyEventType.KeyDown) {
                                val direction = if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                                focusManager.moveFocus(direction)
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            if (it.type == KeyEventType.KeyUp) onApply()
                            true
                        }
                        else -> false
                    }
                },
        label = { Text(stringResource(label)) },
        isError = validation != null,
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onApply() }),
    )
}

@Composable
private fun PeriodNavigation(
    editor: StatisticsPeriodEditor,
    focus: StatisticsPeriodFocus,
    admission: StatisticsActionAdmission,
    tag: String,
    direction: Long,
    applicable: () -> Boolean,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
) {
    // Preserve the editor layout when switching to a bounded kind. Only the canonical period is navigable.
    if (!editor.kind.showsNavigation) return
    val target = navigate(editor.applied, direction) ?: return
    val previous = direction < 0
    StatisticsSecondaryButton(
        admission,
        onClick = { onSelectPeriod(target) },
        applicable = applicable,
        modifier = focus.recoverOnRemoval().testTag(if (previous) "$tag-previous" else "$tag-next"),
    ) { Text(stringResource(if (previous) R.string.statistics_previous else R.string.statistics_next)) }
}

@Composable
private fun StatisticsPeriodFocus.recoverOnRemoval(editorGroup: Boolean = false): Modifier {
    val token = remember { Any() }
    return Modifier
        .onFocusTargetAttachmentChanged { attached ->
            if (!attached && owner === token) recover = true
        }.onFocusChanged {
            if (editorGroup) editorFocused = it.hasFocus
            if (it.hasFocus) {
                owner = token
            } else if (owner === token && !recover) {
                owner = null
            }
        }
}
