@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.history

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.TextExecutionValue
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
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

private val CORRECTION_DATE_TIME_FORMATTER =
    DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm")
        .optionalStart()
        .appendPattern(":ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, MAX_MILLISECOND_DIGITS, true)
        .optionalEnd()
        .optionalEnd()
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

private const val MAX_MILLISECOND_DIGITS = 3

enum class ActivityHistoryMutationIssue {
    INVALID_DATE_TIME,
    NONEXISTENT_LOCAL_TIME,
    AMBIGUOUS_LOCAL_TIME,
    FUTURE_COMPLETION,
    REVERSED_INTERVAL,
    INVALID_NUMBER,
    INVALID_CATEGORY,
    STALE,
    SAVE_FAILURE,
    DELETE_FAILURE,
}

data class ActivityHistoryFieldDraft(
    val type: CustomFieldType,
    val numberText: String = "",
    val selectedOptionId: ActivitySnapshotCategoryOptionId? = null,
    val text: String = "",
    val missing: Boolean = false,
)

data class ActivityHistoryCorrectionDraft(
    val expectedUpdatedAt: Instant,
    val eventZoneId: ZoneId,
    val startedText: String?,
    val completedText: String,
    val startedOffset: ZoneOffset? = null,
    val completedOffset: ZoneOffset? = null,
    val startedOffsets: List<ZoneOffset> = emptyList(),
    val completedOffsets: List<ZoneOffset> = emptyList(),
    val values: Map<ActivitySnapshotFieldId, ActivityHistoryFieldDraft>,
    val shortComment: String,
    val originalShortComment: String?,
    val shortCommentWasEdited: Boolean = false,
)

data class ActivityHistoryMutationState(
    val load: HistoryDetailLoad<ActivityHistoryDetail> = HistoryDetailLoad.Loading,
    val draft: ActivityHistoryCorrectionDraft? = null,
    val deleteConfirmation: Boolean = false,
    val isMutating: Boolean = false,
    val issue: ActivityHistoryMutationIssue? = null,
    val refreshGeneration: Long = 0,
    val deleted: Boolean = false,
)

sealed interface ActivityHistoryMutationAction {
    data object Retry : ActivityHistoryMutationAction

    data object BeginCorrection : ActivityHistoryMutationAction

    data object Cancel : ActivityHistoryMutationAction

    data class EditStarted(
        val text: String,
    ) : ActivityHistoryMutationAction

    data class EditCompleted(
        val text: String,
    ) : ActivityHistoryMutationAction

    data class EditComment(
        val text: String,
    ) : ActivityHistoryMutationAction

    data class SelectStartedOffset(
        val offset: ZoneOffset,
    ) : ActivityHistoryMutationAction

    data class SelectCompletedOffset(
        val offset: ZoneOffset,
    ) : ActivityHistoryMutationAction

    data class EditNumber(
        val id: ActivitySnapshotFieldId,
        val text: String,
    ) : ActivityHistoryMutationAction

    data class EditText(
        val id: ActivitySnapshotFieldId,
        val text: String,
    ) : ActivityHistoryMutationAction

    data class SelectCategory(
        val id: ActivitySnapshotFieldId,
        val optionId: ActivitySnapshotCategoryOptionId,
    ) : ActivityHistoryMutationAction

    data class SetMissing(
        val id: ActivitySnapshotFieldId,
        val missing: Boolean,
    ) : ActivityHistoryMutationAction

    data object Save : ActivityHistoryMutationAction

    data object RequestDelete : ActivityHistoryMutationAction

    data object ConfirmDelete : ActivityHistoryMutationAction
}

class ActivityHistoryMutationController internal constructor(
    private val scope: CoroutineScope,
    val executionId: ActivityExecutionId,
    private val readDetail: suspend (ActivityExecutionId) -> ActivityHistoryDetail?,
    private val correct: suspend (ActivityExecutionId, ActivityHistoryCorrection, Instant) -> Unit,
    private val softDelete: suspend (ActivityExecutionId, Instant, Instant) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    private val mutableState = MutableStateFlow(ActivityHistoryMutationState())
    val state: StateFlow<ActivityHistoryMutationState> = mutableState
    private var operation: Job? = null
    private var closed = false

    init {
        reload()
    }

    fun dispatch(action: ActivityHistoryMutationAction) {
        if (closed) return
        when (action) {
            ActivityHistoryMutationAction.Retry -> reload()
            ActivityHistoryMutationAction.BeginCorrection -> beginCorrection()
            ActivityHistoryMutationAction.Cancel -> cancelTransient()
            is ActivityHistoryMutationAction.EditStarted ->
                editDraft {
                    copy(startedText = action.text, startedOffset = null, startedOffsets = emptyList())
                }
            is ActivityHistoryMutationAction.EditCompleted ->
                editDraft {
                    copy(completedText = action.text, completedOffset = null, completedOffsets = emptyList())
                }
            is ActivityHistoryMutationAction.EditComment ->
                editDraft { copy(shortComment = action.text, shortCommentWasEdited = true) }
            is ActivityHistoryMutationAction.SelectStartedOffset -> editDraft { copy(startedOffset = action.offset) }
            is ActivityHistoryMutationAction.SelectCompletedOffset ->
                editDraft {
                    copy(
                        completedOffset = action.offset,
                    )
                }
            is ActivityHistoryMutationAction.EditNumber ->
                editValue(
                    action.id,
                ) { copy(numberText = action.text, missing = false) }
            is ActivityHistoryMutationAction.EditText ->
                editValue(
                    action.id,
                ) { copy(text = action.text, missing = false) }
            is ActivityHistoryMutationAction.SelectCategory ->
                editValue(action.id) {
                    copy(selectedOptionId = action.optionId, missing = false)
                }
            is ActivityHistoryMutationAction.SetMissing -> editValue(action.id) { copy(missing = action.missing) }
            ActivityHistoryMutationAction.Save -> save()
            ActivityHistoryMutationAction.RequestDelete -> requestDelete()
            ActivityHistoryMutationAction.ConfirmDelete -> delete()
        }
    }

    fun handleBack(): Boolean {
        val state = mutableState.value
        if (state.isMutating) return true
        if (state.draft != null || state.deleteConfirmation) {
            cancelTransient()
            return true
        }
        return false
    }

    fun close() {
        closed = true
        operation?.cancel()
    }

    private fun reload(issue: ActivityHistoryMutationIssue? = null) {
        if (closed || mutableState.value.isMutating) return
        operation?.cancel()
        mutableState.update { it.copy(load = HistoryDetailLoad.Loading, draft = null, deleteConfirmation = false) }
        operation =
            scope.launch {
                try {
                    val detail = readDetail(executionId)
                    if (!closed) {
                        mutableState.update {
                            it.copy(
                                load =
                                    detail?.let(HistoryDetailLoad<ActivityHistoryDetail>::Content)
                                        ?: HistoryDetailLoad.Unavailable,
                                issue = issue,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!closed) {
                        mutableState.update {
                            it.copy(
                                load = HistoryDetailLoad.Failure("read"),
                                issue = issue,
                            )
                        }
                    }
                }
            }
    }

    private fun beginCorrection() {
        if (mutableState.value.isMutating) return
        val detail = (mutableState.value.load as? HistoryDetailLoad.Content)?.value ?: return
        if (detail.root.planEntryId != null) return
        val zone = detail.originalZoneId
        mutableState.update {
            it.copy(
                draft =
                    ActivityHistoryCorrectionDraft(
                        detail.updatedAt,
                        zone,
                        detail.root.startedAt?.let { instant ->
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(instant.atZone(zone))
                        },
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(detail.root.completedAt.atZone(zone)),
                        values = detail.fields.associate { field -> field.id to field.actualDraft() },
                        shortComment = detail.root.shortComment.orEmpty(),
                        originalShortComment = detail.root.shortComment,
                    ),
                deleteConfirmation = false,
                issue = null,
            )
        }
    }

    private fun requestDelete() {
        if (mutableState.value.isMutating || mutableState.value.load !is HistoryDetailLoad.Content) return
        mutableState.update { it.copy(draft = null, deleteConfirmation = true, issue = null) }
    }

    private fun cancelTransient() {
        if (mutableState.value.isMutating) return
        mutableState.update { it.copy(draft = null, deleteConfirmation = false, issue = null) }
    }

    private fun editDraft(transform: ActivityHistoryCorrectionDraft.() -> ActivityHistoryCorrectionDraft) {
        if (mutableState.value.isMutating) return
        mutableState.update { state -> state.copy(draft = state.draft?.transform(), issue = null) }
    }

    private fun editValue(
        id: ActivitySnapshotFieldId,
        transform: ActivityHistoryFieldDraft.() -> ActivityHistoryFieldDraft,
    ) = editDraft { copy(values = values[id]?.let { values + (id to it.transform()) } ?: values) }

    private fun save() {
        if (mutableState.value.isMutating) return
        val detail = (mutableState.value.load as? HistoryDetailLoad.Content)?.value ?: return
        val draft = mutableState.value.draft ?: return
        if (detail.root.planEntryId != null) return
        val commandAt = now()
        val correction = buildCorrection(detail, draft, commandAt) ?: return
        mutableState.update { it.copy(isMutating = true, issue = null) }
        operation =
            scope.launch {
                try {
                    correct(executionId, correction, commandAt)
                    if (!closed) {
                        mutableState.update {
                            it.copy(
                                load = HistoryDetailLoad.Loading,
                                draft = null,
                                isMutating = false,
                                refreshGeneration = it.refreshGeneration + 1,
                            )
                        }
                        val canonical =
                            try {
                                readDetail(executionId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                mutableState.update { it.copy(load = HistoryDetailLoad.Failure("read")) }
                                return@launch
                            }
                        if (!closed) {
                            mutableState.update {
                                it.copy(
                                    load =
                                        canonical?.let(HistoryDetailLoad<ActivityHistoryDetail>::Content)
                                            ?: HistoryDetailLoad.Unavailable,
                                )
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ConcurrentModificationException) {
                    if (!closed) {
                        mutableState.update { it.copy(isMutating = false, draft = null) }
                        reload(ActivityHistoryMutationIssue.STALE)
                    }
                } catch (_: Exception) {
                    if (!closed) {
                        mutableState.update {
                            it.copy(
                                isMutating = false,
                                issue = ActivityHistoryMutationIssue.SAVE_FAILURE,
                            )
                        }
                    }
                }
            }
    }

    private fun delete() {
        if (mutableState.value.isMutating) return
        val detail = (mutableState.value.load as? HistoryDetailLoad.Content)?.value ?: return
        val commandAt = now()
        mutableState.update { it.copy(isMutating = true, issue = null) }
        operation =
            scope.launch {
                try {
                    softDelete(executionId, detail.updatedAt, commandAt)
                    if (!closed) {
                        mutableState.update {
                            it.copy(
                                isMutating = false,
                                deleteConfirmation = false,
                                refreshGeneration = it.refreshGeneration + 1,
                                deleted = true,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ConcurrentModificationException) {
                    if (!closed) {
                        mutableState.update { it.copy(isMutating = false, deleteConfirmation = false) }
                        reload(ActivityHistoryMutationIssue.STALE)
                    }
                } catch (_: Exception) {
                    if (!closed) {
                        mutableState.update {
                            it.copy(
                                isMutating = false,
                                issue = ActivityHistoryMutationIssue.DELETE_FAILURE,
                            )
                        }
                    }
                }
            }
    }

    private fun buildCorrection(
        detail: ActivityHistoryDetail,
        draft: ActivityHistoryCorrectionDraft,
        commandAt: Instant,
    ): ActivityHistoryCorrection? {
        val completed = resolve(draft.completedText, draft.eventZoneId, draft.completedOffset, false) ?: return null
        val started =
            draft.startedText?.let {
                resolve(
                    it,
                    draft.eventZoneId,
                    draft.startedOffset,
                    true,
                ) ?: return null
            }
        if (completed > commandAt) return invalid(ActivityHistoryMutationIssue.FUTURE_COMPLETION)
        if (started != null && started > completed) return invalid(ActivityHistoryMutationIssue.REVERSED_INTERVAL)
        val values =
            detail.fields.mapNotNull { field ->
                val value = draft.values[field.id] ?: return invalid(ActivityHistoryMutationIssue.SAVE_FAILURE)
                if (value.missing) return@mapNotNull null
                when (field.type) {
                    CustomFieldType.NUMBER ->
                        NumberExecutionValue(
                            field.id,
                            parseLauncherNumber(value.numberText, field.displayPrecision)
                                ?: return invalid(ActivityHistoryMutationIssue.INVALID_NUMBER),
                        )
                    CustomFieldType.CATEGORY -> {
                        val option =
                            value.selectedOptionId?.takeIf { id -> field.categoryOptions.any { it.id == id } }
                                ?: return invalid(ActivityHistoryMutationIssue.INVALID_CATEGORY)
                        CategoryExecutionValue(field.id, option)
                    }
                    CustomFieldType.TEXT -> TextExecutionValue(field.id, value.text)
                }
            }
        return ActivityHistoryCorrection(
            draft.expectedUpdatedAt,
            if (detail.root.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                ActivityHistoryTimeCorrection.NoLive(completed)
            } else {
                ActivityHistoryTimeCorrection.Timed(requireNotNull(started), completed)
            },
            draft.eventZoneId,
            values,
            if (draft.shortCommentWasEdited) draft.shortComment.ifBlank { null } else draft.originalShortComment,
        )
    }

    private fun resolve(
        text: String,
        zone: ZoneId,
        selectedOffset: ZoneOffset?,
        started: Boolean,
    ): Instant? {
        val local =
            try {
                LocalDateTime.parse(text.trim().replace(' ', 'T'), CORRECTION_DATE_TIME_FORMATTER)
            } catch (_: DateTimeParseException) {
                return invalid(ActivityHistoryMutationIssue.INVALID_DATE_TIME)
            }
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return invalid(ActivityHistoryMutationIssue.NONEXISTENT_LOCAL_TIME)
        if (offsets.size > 2) error("ZoneRules returned ${offsets.size} valid offsets")
        if (offsets.size == 2 && (selectedOffset == null || selectedOffset !in offsets)) {
            mutableState.update { state ->
                state.copy(
                    draft =
                        state.draft?.let {
                            if (started) it.copy(startedOffsets = offsets) else it.copy(completedOffsets = offsets)
                        },
                    issue = ActivityHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME,
                )
            }
            return null
        }
        return local.toInstant(selectedOffset ?: offsets.single())
    }

    private fun <T> invalid(issue: ActivityHistoryMutationIssue): T? {
        mutableState.update { it.copy(issue = issue) }
        return null
    }

    private fun com.alexandr5476.lifetracing.domain.ActivityHistoryField.actualDraft(): ActivityHistoryFieldDraft =
        when (val actual = actualValue) {
            ActivityHistoryActualValue.Missing -> ActivityHistoryFieldDraft(type, missing = true)
            is ActivityHistoryActualValue.Number ->
                ActivityHistoryFieldDraft(type, numberText = formatLauncherNumber(actual.scaledValue, displayPrecision))
            is ActivityHistoryActualValue.Category ->
                ActivityHistoryFieldDraft(type, selectedOptionId = actual.optionId)
            is ActivityHistoryActualValue.Text -> ActivityHistoryFieldDraft(type, text = actual.value)
        }
}

internal class ActivityHistoryMutationRouteSessionOwner : ViewModel() {
    private var session: ActivityHistoryMutationRouteSession? = null

    val activeSession: ActivityHistoryMutationRouteSession?
        get() = session

    fun acquire(
        executionId: ActivityExecutionId,
        create: () -> ActivityHistoryMutationController,
    ): ActivityHistoryMutationRouteSession {
        session?.takeIf { it.executionId == executionId }?.let { return it }
        session?.controller?.close()
        return ActivityHistoryMutationRouteSession(executionId, create()).also { session = it }
    }

    fun release(expected: ActivityHistoryMutationRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class ActivityHistoryMutationRouteSession(
    val executionId: ActivityExecutionId,
    val controller: ActivityHistoryMutationController,
) {
    private var deliveredRefreshGeneration = 0L
    private var deliveredDelete = false

    fun deliverRefresh(
        generation: Long,
        refresh: () -> Unit,
    ) {
        if (generation <= deliveredRefreshGeneration) return
        deliveredRefreshGeneration = generation
        refresh()
    }

    fun deliverDelete(delete: () -> Unit) {
        if (deliveredDelete) return
        deliveredDelete = true
        delete()
    }
}
