@file:Suppress(
    "CyclomaticComplexMethod",
    "LongParameterList",
    "MaxLineLength",
    "ReturnCount",
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
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
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
import java.time.format.DateTimeFormatter

sealed interface ManualEntryLoad<out T> {
    data object Idle : ManualEntryLoad<Nothing>

    data object Loading : ManualEntryLoad<Nothing>

    data class Content<T>(
        val value: T,
    ) : ManualEntryLoad<T>

    data class Failure(
        val message: String,
    ) : ManualEntryLoad<Nothing>
}

sealed interface ManualEntryCommand {
    data object Idle : ManualEntryCommand

    data class Invalid(
        val message: String,
    ) : ManualEntryCommand

    data class Overlap(
        val proposal: ManualEntryProposal,
    ) : ManualEntryCommand

    data object Committing : ManualEntryCommand

    data class Failure(
        val message: String,
    ) : ManualEntryCommand

    data class Committed(
        val execution: ActivityExecution,
    ) : ManualEntryCommand
}

data class ManualEntryProposal(
    val source: ActivityEntrySource.Template,
    val startedAt: Instant?,
    val completedAt: Instant,
    val commandAt: Instant,
    val zoneId: ZoneId,
    val values: List<ActivityEntryValueOverride>,
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
    val canLoadMore: Boolean = false,
    val selected: ManualEntryLoad<ActivityTemplate> = ManualEntryLoad.Idle,
    val startedText: String = "",
    val completedText: String = "",
    val values: Map<ActivityTemplateFieldId, ManualEntryFieldDraft> = emptyMap(),
    val command: ManualEntryCommand = ManualEntryCommand.Idle,
)

sealed interface ManualActivityEntryAction {
    data object RetryCatalog : ManualActivityEntryAction

    data object LoadMore : ManualActivityEntryAction

    data class Select(
        val id: com.alexandr5476.lifetracing.domain.ActivityTemplateId,
    ) : ManualActivityEntryAction

    data class EditStarted(
        val text: String,
    ) : ManualActivityEntryAction

    data class EditCompleted(
        val text: String,
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

    data object Save : ManualActivityEntryAction

    data object ProceedOverlap : ManualActivityEntryAction

    data object CancelOverlap : ManualActivityEntryAction
}

/** One retained, non-durable History entry flow. Durable writes remain in ActivityCommandRepository. */
class ManualActivityEntryController internal constructor(
    private val scope: CoroutineScope,
    private val readCatalog: suspend (ReusableActivityCatalogItem?) -> List<ReusableActivityCatalogItem>,
    private val readTemplate: suspend (com.alexandr5476.lifetracing.domain.ActivityTemplateId) -> ActivityTemplate?,
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
    private var closed = false

    init {
        loadCatalog()
    }

    fun dispatch(action: ManualActivityEntryAction) {
        if (closed) return
        when (action) {
            ManualActivityEntryAction.RetryCatalog -> loadCatalog(reset = true)
            ManualActivityEntryAction.LoadMore -> loadCatalog()
            is ManualActivityEntryAction.Select -> select(action.id)
            is ManualActivityEntryAction.EditStarted -> editTime(started = action.text)
            is ManualActivityEntryAction.EditCompleted -> editTime(completed = action.text)
            is ManualActivityEntryAction.EditNumber -> editNumber(action.id, action.text)
            is ManualActivityEntryAction.EditText -> editText(action.id, action.text)
            is ManualActivityEntryAction.SelectCategory -> editCategory(action.id, action.optionId)
            is ManualActivityEntryAction.SetMissing -> markMissing(action.id)
            ManualActivityEntryAction.Save -> save()
            ManualActivityEntryAction.ProceedOverlap ->
                (mutableState.value.command as? ManualEntryCommand.Overlap)?.let { commit(it.proposal) }
            ManualActivityEntryAction.CancelOverlap ->
                mutableState.update {
                    it.copy(
                        command = ManualEntryCommand.Idle,
                    )
                }
        }
    }

    fun close() {
        closed = true
        mutation?.cancel()
    }

    private fun loadCatalog(reset: Boolean = false) {
        val current = (mutableState.value.catalog as? ManualEntryLoad.Content)?.value.orEmpty()
        if (!reset && (current.isNotEmpty() && !mutableState.value.canLoadMore)) return
        if (mutableState.value.catalog is ManualEntryLoad.Loading) return
        val retained = if (reset) emptyList() else current
        mutableState.update { it.copy(catalog = ManualEntryLoad.Loading) }
        scope.launch {
            try {
                val page = readCatalog(retained.lastOrNull())
                if (!closed) {
                    mutableState.update {
                        it.copy(catalog = ManualEntryLoad.Content(retained + page), canLoadMore = page.size == pageSize)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) {
                    mutableState.update {
                        it.copy(
                            catalog =
                                ManualEntryLoad.Failure(
                                    failure.message ?: "Unable to load activities",
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun select(id: com.alexandr5476.lifetracing.domain.ActivityTemplateId) {
        if (mutationInFlight || mutableState.value.selected is ManualEntryLoad.Loading) return
        mutableState.update { it.copy(selected = ManualEntryLoad.Loading, command = ManualEntryCommand.Idle) }
        scope.launch {
            try {
                val template = requireNotNull(readTemplate(id)) { "Selected activity is unavailable" }
                require(template.deletedAt == null) { "Selected activity is unavailable" }
                if (!closed) mutableState.value = template.initialState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) {
                    mutableState.update {
                        it.copy(
                            selected =
                                ManualEntryLoad.Failure(
                                    failure.message ?: "Unable to load activity",
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun ActivityTemplate.initialState(): ManualActivityEntryState {
        val timestamp =
            DATE_TIME.format(
                now()
                    .atZone(zoneId())
                    .toLocalDateTime()
                    .withSecond(0)
                    .withNano(0),
            )
        return ManualActivityEntryState(
            catalog = mutableState.value.catalog,
            canLoadMore = mutableState.value.canLoadMore,
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
            command = it.command.dropOverlap(),
        )
    }

    private fun editNumber(
        id: ActivityTemplateFieldId,
        text: String,
    ) = editValue(id) {
        it.copy(numberText = text, missing = false)
    }

    private fun editText(
        id: ActivityTemplateFieldId,
        text: String,
    ) = editValue(id) {
        it.copy(text = text, missing = false)
    }

    private fun editCategory(
        id: ActivityTemplateFieldId,
        option: CategoryOptionId,
    ) = editValue(id) {
        it.copy(selectedOptionId = option, missing = false)
    }

    private fun markMissing(id: ActivityTemplateFieldId) =
        editValue(id) {
            it.copy(missing = true, numberText = "", selectedOptionId = null, text = "")
        }

    private fun editValue(
        id: ActivityTemplateFieldId,
        transform: (ManualEntryFieldDraft) -> ManualEntryFieldDraft,
    ) = mutableState.update { state ->
        state.copy(
            values =
                state.values[id]?.let { state.values + (id to transform(it)) } ?: state.values,
            command = state.command.dropOverlap(),
        )
    }

    private fun save() {
        if (mutationInFlight) return
        val template = (mutableState.value.selected as? ManualEntryLoad.Content)?.value ?: return
        val sampledNow = now()
        val sampledZone = zoneId()
        val proposal =
            runCatching { proposal(template, sampledNow, sampledZone) }.getOrElse {
                mutableState.update { state ->
                    state.copy(
                        command =
                            ManualEntryCommand.Invalid(
                                it.message ?: "Enter valid date and time",
                            ),
                    )
                }
                return
            }
        if (proposal.startedAt == null) {
            commit(proposal)
        } else {
            mutationInFlight = true
            mutation =
                scope.launch {
                    try {
                        if (overlaps(proposal.startedAt, proposal.completedAt)) {
                            mutableState.update { it.copy(command = ManualEntryCommand.Overlap(proposal)) }
                            mutationInFlight = false
                        } else {
                            commit(proposal, alreadyInFlight = true)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failed(failure)
                    } finally {
                        if (mutableState.value.command !is ManualEntryCommand.Committing) mutationInFlight = false
                    }
                }
        }
    }

    private fun commit(
        proposal: ManualEntryProposal,
        alreadyInFlight: Boolean = false,
    ) {
        if (mutationInFlight && !alreadyInFlight) return
        mutationInFlight = true
        mutableState.update { it.copy(command = ManualEntryCommand.Committing) }
        mutation =
            scope.launch {
                try {
                    val result = if (proposal.startedAt == null) writeNoLive(proposal) else writeTimed(proposal)
                    if (!closed) mutableState.update { it.copy(command = ManualEntryCommand.Committed(result)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed(failure)
                } finally {
                    mutationInFlight = false
                }
            }
    }

    private fun failed(failure: Exception) {
        if (!closed) {
            mutableState.update {
                it.copy(
                    command =
                        ManualEntryCommand.Failure(
                            failure.message ?: "Unable to save activity",
                        ),
                )
            }
        }
    }

    private fun proposal(
        template: ActivityTemplate,
        commandAt: Instant,
        zone: ZoneId,
    ): ManualEntryProposal {
        val completed = parseTime(mutableState.value.completedText, zone)
        val started =
            if (template.timeTrackingMode ==
                TimeTrackingMode.NO_LIVE_TRACKING
            ) {
                null
            } else {
                parseTime(mutableState.value.startedText, zone)
            }
        require(completed <= commandAt) { "Completion cannot be in the future" }
        require(started == null || started <= completed) { "Start must be before completion" }
        val active = template.fields.filter { it.deletedAt == null }
        val overrides =
            active.map { field ->
                val draft = requireNotNull(mutableState.value.values[field.id])
                val value =
                    when {
                        draft.missing -> ActivityEntryValue.Missing
                        field.type == CustomFieldType.NUMBER ->
                            ActivityEntryValue.Number(
                                requireNotNull(
                                    parseLauncherNumber(draft.numberText, field.displayPrecision),
                                ) {
                                    "Enter a valid number"
                                },
                            )
                        field.type == CustomFieldType.CATEGORY ->
                            ActivityEntryValue
                                .Category(
                                    ActivityEntryOptionReference.Template(
                                        requireNotNull(
                                            draft.selectedOptionId,
                                        ) {
                                            "Choose a category"
                                        }.also { optionId ->
                                            require(
                                                field.categoryOptions.any {
                                                    it.id == optionId && !it.isArchived
                                                },
                                            ) { "Choose an active category" }
                                        },
                                    ),
                                )
                        else -> ActivityEntryValue.Text(draft.text)
                    }
                ActivityEntryValueOverride(ActivityEntryFieldReference.Template(field.id), value)
            }
        return ManualEntryProposal(
            ActivityEntrySource.Template(template.id),
            started,
            completed,
            commandAt,
            zone,
            overrides,
        )
    }

    private fun parseTime(
        text: String,
        zone: ZoneId,
    ): Instant = LocalDateTime.parse(text.trim().replace(' ', 'T'), DATE_TIME).atZone(zone).toInstant()

    private fun ManualEntryCommand.dropOverlap(): ManualEntryCommand =
        if (this is ManualEntryCommand.Overlap) ManualEntryCommand.Idle else this
}

internal const val MANUAL_ACTIVITY_CATALOG_PAGE_SIZE = 50
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
