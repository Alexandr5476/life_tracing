@file:Suppress(
    "CyclomaticComplexMethod",
    "LongParameterList",
    "MaxLineLength",
    "LoopWithTooManyJumpStatements",
    "ReturnCount",
    "ThrowsCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
import com.alexandr5476.lifetracing.domain.StaleLauncherTargetException
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

enum class ManualEntryIssue {
    INVALID_DATE_TIME,
    NONEXISTENT_LOCAL_TIME,
    AMBIGUOUS_LOCAL_TIME,
    FUTURE_COMPLETION,
    REVERSED_INTERVAL,
    INVALID_NUMBER,
    INVALID_CATEGORY,
    TEMPLATE_UNAVAILABLE,
    TEMPLATE_STALE,
    CATALOG_READ_FAILURE,
    TEMPLATE_READ_FAILURE,
    SAVE_FAILURE,
}

sealed interface ManualEntryLoad<out T> {
    data object Idle : ManualEntryLoad<Nothing>

    data object Loading : ManualEntryLoad<Nothing>

    data class Content<T>(
        val value: T,
    ) : ManualEntryLoad<T>

    data class Failure(
        val issue: ManualEntryIssue,
    ) : ManualEntryLoad<Nothing>
}

sealed interface ManualEntryCommand {
    data object Idle : ManualEntryCommand

    data class Invalid(
        val issue: ManualEntryIssue,
    ) : ManualEntryCommand

    data class Overlap(
        val proposal: ManualEntryProposal,
    ) : ManualEntryCommand

    data object Committing : ManualEntryCommand

    data class Failure(
        val issue: ManualEntryIssue,
    ) : ManualEntryCommand

    data class Committed(
        val execution: ActivityExecution,
    ) : ManualEntryCommand
}

data class ManualEntryProposal(
    val source: ActivityEntrySource.Template,
    val expectedTemplateRevision: Long,
    val startedAt: Instant?,
    val completedAt: Instant,
    val commandAt: Instant,
    val zoneId: ZoneId,
    val values: List<ActivityEntryValueOverride>,
    internal val draftVersion: Long = 0,
) {
    internal val intervalKey = ManualEntryIntervalKey(startedAt, completedAt, zoneId)
}

internal data class ManualEntryIntervalKey(
    val startedAt: Instant?,
    val completedAt: Instant,
    val zoneId: ZoneId,
)

data class ManualTimeAmbiguity(
    val localDateTime: LocalDateTime,
    val zoneId: ZoneId,
    val offsets: List<ZoneOffset>,
    val selectedOffset: ZoneOffset? = null,
)

data class ManualEntryFieldDraft(
    val type: CustomFieldType,
    val numberText: String = "",
    val selectedOptionId: CategoryOptionId? = null,
    val text: String = "",
    val missing: Boolean = false,
)

data class ManualActivityEntryState(
    val catalog: ManualEntryLoad<List<ReusableActivityCatalogItem>> = ManualEntryLoad.Idle,
    val nextCatalogCursor: ReusableActivityCatalogItem? = null,
    val selected: ManualEntryLoad<ActivityTemplate> = ManualEntryLoad.Idle,
    val startedText: String = "",
    val completedText: String = "",
    val startedAmbiguity: ManualTimeAmbiguity? = null,
    val completedAmbiguity: ManualTimeAmbiguity? = null,
    val startedIssue: ManualEntryIssue? = null,
    val completedIssue: ManualEntryIssue? = null,
    val values: Map<ActivityTemplateFieldId, ManualEntryFieldDraft> = emptyMap(),
    val command: ManualEntryCommand = ManualEntryCommand.Idle,
    internal val draftVersion: Long = 0,
) {
    val canLoadMore: Boolean get() = nextCatalogCursor != null
}

sealed interface ManualActivityEntryAction {
    data object RetryCatalog : ManualActivityEntryAction

    data object LoadMore : ManualActivityEntryAction

    data class Select(
        val id: ActivityTemplateId,
    ) : ManualActivityEntryAction

    data class EditStarted(
        val text: String,
    ) : ManualActivityEntryAction

    data class EditCompleted(
        val text: String,
    ) : ManualActivityEntryAction

    data class SelectStartedOffset(
        val offset: ZoneOffset,
    ) : ManualActivityEntryAction

    data class SelectCompletedOffset(
        val offset: ZoneOffset,
    ) : ManualActivityEntryAction

    data class EditNumber(
        val id: ActivityTemplateFieldId,
        val text: String,
    ) : ManualActivityEntryAction

    data class EditText(
        val id: ActivityTemplateFieldId,
        val text: String,
    ) : ManualActivityEntryAction

    data class SelectCategory(
        val id: ActivityTemplateFieldId,
        val optionId: CategoryOptionId,
    ) : ManualActivityEntryAction

    data class SetMissing(
        val id: ActivityTemplateFieldId,
    ) : ManualActivityEntryAction

    data class SetPresent(
        val id: ActivityTemplateFieldId,
    ) : ManualActivityEntryAction

    data object Save : ManualActivityEntryAction

    data object ProceedOverlap : ManualActivityEntryAction

    data object CancelOverlap : ManualActivityEntryAction

    data object ReviewStaleTemplate : ManualActivityEntryAction
}

/** One retained, non-durable History entry flow. Durable writes remain in ActivityCommandRepository. */
class ManualActivityEntryController internal constructor(
    private val scope: CoroutineScope,
    private val readCatalog: suspend (ReusableActivityCatalogItem?) -> List<ReusableActivityCatalogItem>,
    private val readTemplate: suspend (ActivityTemplateId) -> ActivityTemplate?,
    private val overlaps: suspend (Instant, Instant) -> Boolean,
    private val writeTimed: suspend (ManualEntryProposal) -> ActivityExecution,
    private val writeNoLive: suspend (ManualEntryProposal) -> ActivityExecution,
    private val now: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val pageSize: Int = MANUAL_ACTIVITY_CATALOG_PAGE_SIZE,
) {
    private val mutableState = MutableStateFlow(ManualActivityEntryState())
    val state: StateFlow<ManualActivityEntryState> = mutableState
    private var mutation: Job? = null
    private var mutationInFlight = false
    private var catalogRequestCursor: ReusableActivityCatalogItem? = null
    private var staleTemplateId: ActivityTemplateId? = null
    private var closed = false

    init {
        loadCatalog(null)
    }

    fun dispatch(action: ManualActivityEntryAction) {
        if (closed) return
        when (action) {
            ManualActivityEntryAction.RetryCatalog -> loadCatalog(catalogRequestCursor)
            ManualActivityEntryAction.LoadMore -> mutableState.value.nextCatalogCursor?.let(::loadCatalog)
            is ManualActivityEntryAction.Select -> select(action.id)
            is ManualActivityEntryAction.EditStarted -> editTime(started = action.text)
            is ManualActivityEntryAction.EditCompleted -> editTime(completed = action.text)
            is ManualActivityEntryAction.SelectStartedOffset -> selectOffset(started = action.offset)
            is ManualActivityEntryAction.SelectCompletedOffset -> selectOffset(completed = action.offset)
            is ManualActivityEntryAction.EditNumber -> editNumber(action.id, action.text)
            is ManualActivityEntryAction.EditText -> editText(action.id, action.text)
            is ManualActivityEntryAction.SelectCategory -> editCategory(action.id, action.optionId)
            is ManualActivityEntryAction.SetMissing -> setMissing(action.id, true)
            is ManualActivityEntryAction.SetPresent -> setMissing(action.id, false)
            ManualActivityEntryAction.Save -> save()
            ManualActivityEntryAction.ProceedOverlap ->
                (mutableState.value.command as? ManualEntryCommand.Overlap)?.let { save(it.proposal.intervalKey) }
            ManualActivityEntryAction.CancelOverlap ->
                mutableState.update {
                    it.copy(
                        command = ManualEntryCommand.Idle,
                    )
                }
            ManualActivityEntryAction.ReviewStaleTemplate -> staleTemplateId?.let(::select)
        }
    }

    fun close() {
        closed = true
        mutation?.cancel()
    }

    private fun loadCatalog(cursor: ReusableActivityCatalogItem?) {
        if (mutableState.value.catalog is ManualEntryLoad.Loading) return
        val retainedPage = (mutableState.value.catalog as? ManualEntryLoad.Content)?.value
        catalogRequestCursor = cursor
        mutableState.update { it.copy(catalog = ManualEntryLoad.Loading) }
        scope.launch {
            try {
                val page = readCatalog(cursor)
                require(page.size <= pageSize) { "Catalog page exceeded its declared bound" }
                if (!closed) {
                    val shown = retainedPage.takeIf { cursor != null && page.isEmpty() && !it.isNullOrEmpty() } ?: page
                    val next = page.lastOrNull().takeIf { page.size == pageSize && it != cursor }
                    mutableState.update {
                        it.copy(catalog = ManualEntryLoad.Content(shown), nextCatalogCursor = next)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed) {
                    mutableState.update {
                        it.copy(
                            catalog = ManualEntryLoad.Failure(ManualEntryIssue.CATALOG_READ_FAILURE),
                            nextCatalogCursor = null,
                        )
                    }
                }
            }
        }
    }

    private fun select(id: ActivityTemplateId) {
        if (mutationInFlight || mutableState.value.selected is ManualEntryLoad.Loading) return
        staleTemplateId = null
        mutableState.update { it.copy(selected = ManualEntryLoad.Loading, command = ManualEntryCommand.Idle) }
        scope.launch {
            try {
                val template = readTemplate(id)
                if (!closed) {
                    mutableState.value =
                        if (template == null || template.deletedAt != null) {
                            mutableState.value.copy(
                                selected = ManualEntryLoad.Failure(ManualEntryIssue.TEMPLATE_UNAVAILABLE),
                            )
                        } else {
                            template.initialState()
                        }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed) {
                    mutableState.update {
                        it.copy(selected = ManualEntryLoad.Failure(ManualEntryIssue.TEMPLATE_READ_FAILURE))
                    }
                }
            }
        }
    }

    private fun ActivityTemplate.initialState(): ManualActivityEntryState {
        val timestamp =
            DATE_TIME.format(
                LocalDateTime.ofInstant(now(), zoneId()).withSecond(0).withNano(0),
            )
        return ManualActivityEntryState(
            catalog = mutableState.value.catalog,
            nextCatalogCursor = mutableState.value.nextCatalogCursor,
            selected = ManualEntryLoad.Content(this),
            startedText = timestamp,
            completedText = timestamp,
            values =
                fields.filter { it.deletedAt == null }.associate { field ->
                    field.id to
                        ManualEntryFieldDraft(
                            field.type,
                            formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision),
                            field.defaultCategoryOptionId,
                            field.defaultText.orEmpty(),
                            missing =
                                when (field.type) {
                                    CustomFieldType.NUMBER -> field.defaultNumberScaled == null
                                    CustomFieldType.CATEGORY -> field.defaultCategoryOptionId == null
                                    CustomFieldType.TEXT -> field.defaultText == null
                                },
                        )
                },
        )
    }

    private fun editTime(
        started: String? = null,
        completed: String? = null,
    ) = mutableState.update {
        it.copy(
            startedText = started ?: it.startedText,
            completedText = completed ?: it.completedText,
            startedAmbiguity = if (started != null) null else it.startedAmbiguity,
            completedAmbiguity = if (completed != null) null else it.completedAmbiguity,
            startedIssue = if (started != null) null else it.startedIssue,
            completedIssue = if (completed != null) null else it.completedIssue,
            command = it.command.dropOverlap(),
            draftVersion = it.draftVersion + 1,
        )
    }

    private fun selectOffset(
        started: ZoneOffset? = null,
        completed: ZoneOffset? = null,
    ) = mutableState.update { state ->
        val startChoice = started?.let { state.startedAmbiguity?.select(it) } ?: state.startedAmbiguity
        val completionChoice = completed?.let { state.completedAmbiguity?.select(it) } ?: state.completedAmbiguity
        state.copy(
            startedAmbiguity = startChoice,
            completedAmbiguity = completionChoice,
            startedIssue = if (started != null) null else state.startedIssue,
            completedIssue = if (completed != null) null else state.completedIssue,
            command = state.command.dropOverlap(),
            draftVersion = state.draftVersion + 1,
        )
    }

    private fun ManualTimeAmbiguity.select(offset: ZoneOffset): ManualTimeAmbiguity {
        require(offset in offsets) { "Offset is not valid for this local time" }
        return copy(selectedOffset = offset)
    }

    private fun editNumber(
        id: ActivityTemplateFieldId,
        text: String,
    ) = editValue(id) { it.copy(numberText = text, missing = false) }

    private fun editText(
        id: ActivityTemplateFieldId,
        text: String,
    ) = editValue(id) { it.copy(text = text, missing = false) }

    private fun editCategory(
        id: ActivityTemplateFieldId,
        option: CategoryOptionId,
    ) = editValue(id) { it.copy(selectedOptionId = option, missing = false) }

    private fun setMissing(
        id: ActivityTemplateFieldId,
        missing: Boolean,
    ) = editValue(id) { it.copy(missing = missing) }

    private fun editValue(
        id: ActivityTemplateFieldId,
        transform: (ManualEntryFieldDraft) -> ManualEntryFieldDraft,
    ) = mutableState.update { state ->
        state.copy(values = state.values[id]?.let { state.values + (id to transform(it)) } ?: state.values)
    }

    private fun save(approvedInterval: ManualEntryIntervalKey? = null) {
        if (mutationInFlight) return
        val template = (mutableState.value.selected as? ManualEntryLoad.Content)?.value ?: return
        mutationInFlight = true
        mutation =
            scope.launch {
                try {
                    var approval = approvedInterval
                    while (!closed) {
                        val checked = buildProposal(template)
                        if (checked.startedAt != null && approval != checked.intervalKey) {
                            if (overlaps(checked.startedAt, checked.completedAt)) {
                                if (!checked.isCurrent()) {
                                    approval = null
                                    continue
                                }
                                mutableState.update { it.copy(command = ManualEntryCommand.Overlap(checked)) }
                                return@launch
                            }
                        }
                        val fresh = buildProposal(template)
                        if (fresh.intervalKey != checked.intervalKey || !fresh.isCurrent()) {
                            approval = null
                            continue
                        }
                        mutableState.update { it.copy(command = ManualEntryCommand.Committing) }
                        val result = if (fresh.startedAt == null) writeNoLive(fresh) else writeTimed(fresh)
                        if (!closed) mutableState.update { it.copy(command = ManualEntryCommand.Committed(result)) }
                        return@launch
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (invalid: ManualEntryIssueException) {
                    if (!closed) invalid.present()
                } catch (_: StaleLauncherTargetException) {
                    if (!closed) {
                        staleTemplateId = template.id
                        mutableState.update {
                            it.copy(
                                selected = ManualEntryLoad.Failure(ManualEntryIssue.TEMPLATE_STALE),
                                values = emptyMap(),
                                command = ManualEntryCommand.Failure(ManualEntryIssue.TEMPLATE_STALE),
                            )
                        }
                    }
                } catch (_: Exception) {
                    if (!closed) {
                        mutableState.update {
                            it.copy(command = ManualEntryCommand.Failure(ManualEntryIssue.SAVE_FAILURE))
                        }
                    }
                } finally {
                    mutationInFlight = false
                }
            }
    }

    private fun ManualEntryProposal.isCurrent(): Boolean =
        mutableState.value.draftVersion == draftVersion && zoneId() == zoneId

    private fun ManualEntryIssueException.present() {
        mutableState.update { state ->
            state.copy(
                startedAmbiguity =
                    if (point ==
                        TimePoint.STARTED
                    ) {
                        ambiguity
                    } else if (clearAmbiguity &&
                        point == TimePoint.STARTED
                    ) {
                        null
                    } else {
                        state.startedAmbiguity
                    },
                completedAmbiguity =
                    if (point ==
                        TimePoint.COMPLETED
                    ) {
                        ambiguity
                    } else if (clearAmbiguity &&
                        point == TimePoint.COMPLETED
                    ) {
                        null
                    } else {
                        state.completedAmbiguity
                    },
                startedIssue = if (point == TimePoint.STARTED) issue else state.startedIssue,
                completedIssue = if (point == TimePoint.COMPLETED) issue else state.completedIssue,
                command = ManualEntryCommand.Invalid(issue),
            )
        }
    }

    private fun buildProposal(template: ActivityTemplate): ManualEntryProposal {
        val commandAt = now()
        val zone = zoneId()
        val state = mutableState.value
        val completed = resolveTime(state.completedText, zone, state.completedAmbiguity, TimePoint.COMPLETED)
        val started =
            if (template.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                null
            } else {
                resolveTime(state.startedText, zone, state.startedAmbiguity, TimePoint.STARTED)
            }
        mutableState.update {
            it.copy(
                startedAmbiguity = started?.ambiguity,
                completedAmbiguity = completed.ambiguity,
                startedIssue = null,
                completedIssue = null,
            )
        }
        if (completed.instant > commandAt) throw ManualEntryIssueException(ManualEntryIssue.FUTURE_COMPLETION)
        if (started != null && started.instant > completed.instant) {
            throw ManualEntryIssueException(ManualEntryIssue.REVERSED_INTERVAL)
        }
        val active = template.fields.filter { it.deletedAt == null }
        val overrides =
            active.map { field ->
                val draft = state.values[field.id] ?: throw ManualEntryIssueException(ManualEntryIssue.SAVE_FAILURE)
                val value =
                    when {
                        draft.missing -> ActivityEntryValue.Missing
                        field.type == CustomFieldType.NUMBER ->
                            ActivityEntryValue.Number(
                                parseLauncherNumber(draft.numberText, field.displayPrecision)
                                    ?: throw ManualEntryIssueException(ManualEntryIssue.INVALID_NUMBER),
                            )
                        field.type == CustomFieldType.CATEGORY -> {
                            val option =
                                draft.selectedOptionId?.takeIf { selected ->
                                    field.categoryOptions.any { it.id == selected && !it.isArchived }
                                } ?: throw ManualEntryIssueException(ManualEntryIssue.INVALID_CATEGORY)
                            ActivityEntryValue.Category(ActivityEntryOptionReference.Template(option))
                        }
                        else -> ActivityEntryValue.Text(draft.text)
                    }
                ActivityEntryValueOverride(ActivityEntryFieldReference.Template(field.id), value)
            }
        return ManualEntryProposal(
            ActivityEntrySource.Template(template.id),
            template.revision,
            started?.instant,
            completed.instant,
            commandAt,
            zone,
            overrides,
            state.draftVersion,
        )
    }

    private fun resolveTime(
        text: String,
        zone: ZoneId,
        previous: ManualTimeAmbiguity?,
        point: TimePoint,
    ): ResolvedTime {
        val local =
            try {
                LocalDateTime.parse(text.trim().replace(' ', 'T'), DATE_TIME)
            } catch (_: DateTimeParseException) {
                throw ManualEntryIssueException(
                    ManualEntryIssue.INVALID_DATE_TIME,
                    point = point,
                    clearAmbiguity = true,
                )
            }
        val offsets = zone.rules.getValidOffsets(local)
        return when (offsets.size) {
            0 -> throw ManualEntryIssueException(
                ManualEntryIssue.NONEXISTENT_LOCAL_TIME,
                point = point,
                clearAmbiguity = true,
            )
            1 -> ResolvedTime(local.toInstant(offsets.single()), null)
            2 -> {
                val selected =
                    previous
                        ?.takeIf { it.localDateTime == local && it.zoneId == zone && it.offsets == offsets }
                        ?.selectedOffset
                        ?.takeIf(offsets::contains)
                val ambiguity = ManualTimeAmbiguity(local, zone, offsets, selected)
                if (selected == null) {
                    throw ManualEntryIssueException(
                        ManualEntryIssue.AMBIGUOUS_LOCAL_TIME,
                        point,
                        ambiguity,
                    )
                }
                ResolvedTime(local.toInstant(selected), ambiguity)
            }
            else -> error("ZoneRules returned ${offsets.size} valid offsets")
        }
    }

    private fun ManualEntryCommand.dropOverlap(): ManualEntryCommand =
        if (this is ManualEntryCommand.Overlap || this is ManualEntryCommand.Invalid) ManualEntryCommand.Idle else this

    private data class ResolvedTime(
        val instant: Instant,
        val ambiguity: ManualTimeAmbiguity?,
    )

    private enum class TimePoint { STARTED, COMPLETED }

    private class ManualEntryIssueException(
        val issue: ManualEntryIssue,
        val point: TimePoint? = null,
        val ambiguity: ManualTimeAmbiguity? = null,
        val clearAmbiguity: Boolean = false,
    ) : IllegalArgumentException()
}

internal const val MANUAL_ACTIVITY_CATALOG_PAGE_SIZE = 50
private val DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT)
