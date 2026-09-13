@file:Suppress(
    "LongParameterList",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "SwallowedException",
    "TooGenericExceptionCaught",
)

package com.alexandr5476.lifetracing.live

import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivityExecutionValueOverride
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequence
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceOccurrence
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceRead
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.StaleSequenceRouteException
import com.alexandr5476.lifetracing.domain.StaleSequenceTargetException
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal sealed interface ExpandedSequenceFailure {
    data class Rejected(
        val message: String,
    ) : ExpandedSequenceFailure

    data class StaleTarget(
        val message: String,
    ) : ExpandedSequenceFailure

    data class Coordination(
        val message: String,
    ) : ExpandedSequenceFailure
}

internal sealed interface ExpandedSequenceConfirmation {
    data class GoNow(
        val occurrenceId: SequenceOccurrenceId,
    ) : ExpandedSequenceConfirmation

    data object EndEarly : ExpandedSequenceConfirmation
}

internal data class CurrentValueDraft(
    val occurrenceId: SequenceOccurrenceId,
    val values: Map<ActivitySnapshotFieldId, ActivityExecutionFieldValue?>,
    val numberTexts: Map<ActivitySnapshotFieldId, String>,
    val invalidNumberFields: Set<ActivitySnapshotFieldId> = emptySet(),
) {
    fun overrides(fields: List<ActivitySnapshotField>): List<ActivityExecutionValueOverride> =
        fields.map { ActivityExecutionValueOverride(it.id, values[it.id]) }
}

internal data class ExpandedLiveSequenceState(
    val sequence: ExpandedLiveSequence? = null,
    val loading: Boolean = true,
    val stale: Boolean = false,
    val readFailure: String? = null,
    val displayBaseline: RuntimeDisplayBaseline? = null,
    val commandInFlight: Boolean = false,
    val commandFailure: ExpandedSequenceFailure? = null,
    val currentValueDraft: CurrentValueDraft? = null,
    val confirmation: ExpandedSequenceConfirmation? = null,
    val catalog: List<ReusableActivityCatalogItem>? = null,
    val catalogFailure: String? = null,
)

internal sealed interface ExpandedSequenceCommand {
    val executionId: SequenceExecutionId
    val at: Instant

    data class Pause(
        override val executionId: SequenceExecutionId,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class Resume(
        override val executionId: SequenceExecutionId,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class StartNext(
        override val executionId: SequenceExecutionId,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class Complete(
        override val executionId: SequenceExecutionId,
        val occurrenceId: SequenceOccurrenceId,
        val values: List<ActivityExecutionValueOverride>,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class GoNow(
        override val executionId: SequenceExecutionId,
        val occurrenceId: SequenceOccurrenceId,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class MakeNext(
        override val executionId: SequenceExecutionId,
        val occurrenceId: SequenceOccurrenceId,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class DoAgain(
        override val executionId: SequenceExecutionId,
        val occurrenceId: SequenceOccurrenceId,
        val placement: RuntimeInsertionPlacement,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class RuntimeAdd(
        override val executionId: SequenceExecutionId,
        val source: ActivityEntrySource,
        val placement: RuntimeInsertionPlacement,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class SaveValues(
        override val executionId: SequenceExecutionId,
        val occurrenceId: SequenceOccurrenceId,
        val values: List<ActivityExecutionValueOverride>,
        override val at: Instant,
    ) : ExpandedSequenceCommand

    data class EndEarly(
        override val executionId: SequenceExecutionId,
        override val at: Instant,
    ) : ExpandedSequenceCommand
}

internal data class ExpandedSequenceActions(
    val pause: Boolean = false,
    val resume: Boolean = false,
    val startNext: Boolean = false,
    val completeCurrent: Boolean = false,
    val editCurrentValues: Boolean = false,
    val goNow: Boolean = false,
    val makeNext: Boolean = false,
    val runtimeAdd: Boolean = false,
    val doAgain: Boolean = false,
    val endEarly: Boolean = true,
)

internal fun expandedActions(state: ActiveSequenceState): ExpandedSequenceActions =
    when (state) {
        ActiveSequenceState.RUNNING_CURRENT ->
            ExpandedSequenceActions(
                pause = true,
                completeCurrent = true,
                editCurrentValues = true,
                goNow = true,
                makeNext = true,
                runtimeAdd = true,
                doAgain = true,
            )
        ActiveSequenceState.PAUSED_CURRENT -> ExpandedSequenceActions(resume = true, editCurrentValues = true)
        ActiveSequenceState.WAITING_NEXT ->
            ExpandedSequenceActions(startNext = true, goNow = true, runtimeAdd = true, doAgain = true)
        ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ->
            ExpandedSequenceActions(pause = true, goNow = true, runtimeAdd = true, doAgain = true)
        ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN -> ExpandedSequenceActions(resume = true)
    }

internal fun validRuntimePlacements(state: ActiveSequenceState): Set<RuntimeInsertionPlacement> =
    when (state) {
        ActiveSequenceState.RUNNING_CURRENT ->
            setOf(RuntimeInsertionPlacement.TO_END, RuntimeInsertionPlacement.AFTER_CURRENT)
        ActiveSequenceState.WAITING_NEXT,
        ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
        -> setOf(RuntimeInsertionPlacement.TO_END, RuntimeInsertionPlacement.START_NOW)
        ActiveSequenceState.PAUSED_CURRENT,
        ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
        -> emptySet()
    }

internal class ExpandedLiveSequenceController(
    private val scope: CoroutineScope,
    val executionId: SequenceExecutionId,
    private val read: suspend (SequenceExecutionId) -> ExpandedLiveSequenceRead,
    private val execute: suspend (ExpandedSequenceCommand) -> Unit,
    private val coordinateRuntimeStateChanged: suspend () -> Unit,
    semanticGeneration: StateFlow<Long>,
    private val displayBaseline: () -> RuntimeDisplayBaseline?,
    private val readCatalog: suspend () -> List<ReusableActivityCatalogItem>,
    private val wallClock: WallClock,
    private val onCurrentValueCommandCaptured: (() -> Unit)? = null,
) {
    private val loadGeneration = AtomicLong()
    private val commandMutex = Mutex()
    private val commandCaptureLock = Any()
    private val mutableState = MutableStateFlow(ExpandedLiveSequenceState())
    val state: StateFlow<ExpandedLiveSequenceState> = mutableState
    private val initialSemanticGeneration = semanticGeneration.value
    private val invalidationJob: Job =
        scope.launch {
            var handled = initialSemanticGeneration
            semanticGeneration.collect { generation ->
                if (generation != handled) {
                    handled = generation
                    refresh()
                }
            }
        }

    init {
        refresh()
    }

    fun refresh() {
        val generation = loadGeneration.incrementAndGet()
        mutableState.update { it.copy(loading = true, readFailure = null) }
        scope.launch {
            try {
                when (val result = read(executionId)) {
                    is ExpandedLiveSequenceRead.Active -> {
                        if (loadGeneration.get() == generation) publish(result.value)
                    }
                    ExpandedLiveSequenceRead.StaleOrInactive -> {
                        if (loadGeneration.get() == generation) {
                            mutableState.update {
                                it.copy(
                                    loading = false,
                                    stale = true,
                                    displayBaseline = null,
                                    currentValueDraft = null,
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (loadGeneration.get() == generation) {
                    mutableState.update { it.copy(loading = false, readFailure = failure.message()) }
                }
            }
        }
    }

    fun loadRuntimeAddCatalog() {
        if (mutableState.value.catalog != null) return
        scope.launch {
            try {
                val catalog = readCatalog()
                mutableState.update { it.copy(catalog = catalog, catalogFailure = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(catalogFailure = failure.message()) }
            }
        }
    }

    fun pause() =
        submit { sequence ->
            ExpandedSequenceCommand
                .Pause(sequence.runtime.execution.id, wallClock.now())
                .takeIf { expandedActions(sequence.state).pause }
        }

    fun resume() =
        submit { sequence ->
            ExpandedSequenceCommand
                .Resume(sequence.runtime.execution.id, wallClock.now())
                .takeIf { expandedActions(sequence.state).resume }
        }

    fun startNext() =
        submit { sequence ->
            ExpandedSequenceCommand
                .StartNext(sequence.runtime.execution.id, wallClock.now())
                .takeIf { expandedActions(sequence.state).startNext }
        }

    fun completeCurrent() =
        submit(capturesCurrentValues = true) { sequence ->
            val occurrenceId = sequence.runtime.execution.currentOccurrenceId ?: return@submit null
            val draft = mutableState.value.currentValueDraft?.takeIf { it.occurrenceId == occurrenceId }
            if (draft?.invalidNumberFields?.isNotEmpty() == true) return@submit null
            ExpandedSequenceCommand
                .Complete(
                    sequence.runtime.execution.id,
                    occurrenceId,
                    draft
                        ?.overrides(
                            sequence.occurrences
                                .single { it.occurrence.id == occurrenceId }
                                .activity.fields,
                        ).orEmpty(),
                    wallClock.now(),
                ).takeIf { expandedActions(sequence.state).completeCurrent }
        }

    fun requestGoNow(occurrenceId: SequenceOccurrenceId) {
        val sequence = active() ?: return reject()
        if (!canGoNow(sequence, occurrenceId)) return reject()
        if (sequence.runtime.snapshot.settings.confirmJump) {
            mutableState.update { it.copy(confirmation = ExpandedSequenceConfirmation.GoNow(occurrenceId)) }
        } else {
            goNowConfirmed(occurrenceId)
        }
    }

    fun makeNext(occurrenceId: SequenceOccurrenceId) =
        submit { sequence ->
            ExpandedSequenceCommand
                .MakeNext(sequence.runtime.execution.id, occurrenceId, wallClock.now())
                .takeIf {
                    expandedActions(sequence.state).makeNext &&
                        occurrence(sequence, occurrenceId)?.occurrence?.status == RuntimeOccurrenceStatus.NOT_STARTED
                }
        }

    fun doAgain(
        occurrenceId: SequenceOccurrenceId,
        placement: RuntimeInsertionPlacement,
    ) = submit { sequence ->
        ExpandedSequenceCommand
            .DoAgain(sequence.runtime.execution.id, occurrenceId, placement, wallClock.now())
            .takeIf {
                expandedActions(sequence.state).doAgain &&
                    placement in validRuntimePlacements(sequence.state) &&
                    occurrence(sequence, occurrenceId)?.occurrence?.status == RuntimeOccurrenceStatus.COMPLETED
            }
    }

    fun runtimeAddTemplate(
        templateId: ActivityTemplateId,
        placement: RuntimeInsertionPlacement,
    ) = runtimeAdd(ActivityEntrySource.Template(templateId), placement)

    fun runtimeAddOneOff(
        draft: ActivitySnapshotDraft,
        placement: RuntimeInsertionPlacement,
    ) = runtimeAdd(ActivityEntrySource.OneOff(draft), placement)

    fun requestEndEarly() {
        val sequence = active() ?: return reject()
        if (!expandedActions(sequence.state).endEarly) return reject()
        if (sequence.runtime.snapshot.settings.confirmEarlyEnd) {
            mutableState.update { it.copy(confirmation = ExpandedSequenceConfirmation.EndEarly) }
        } else {
            endEarlyConfirmed()
        }
    }

    fun confirmPending() {
        when (val confirmation = mutableState.value.confirmation) {
            is ExpandedSequenceConfirmation.GoNow -> goNowConfirmed(confirmation.occurrenceId)
            ExpandedSequenceConfirmation.EndEarly -> endEarlyConfirmed()
            null -> return
        }
        mutableState.update { it.copy(confirmation = null) }
    }

    fun dismissConfirmation() {
        mutableState.update { it.copy(confirmation = null) }
    }

    fun editNumber(
        fieldId: ActivitySnapshotFieldId,
        text: String,
    ) = editDraft(fieldId) { field, draft ->
        require(field.type == CustomFieldType.NUMBER)
        val parsed = text.takeUnless(String::isBlank)?.let { parseLauncherNumber(it, field.displayPrecision) }
        draft.copy(
            values = draft.values + (fieldId to parsed?.let { NumberExecutionValue(fieldId, it) }),
            numberTexts = draft.numberTexts + (fieldId to text),
            invalidNumberFields =
                if (text.isBlank() || parsed != null) {
                    draft.invalidNumberFields - fieldId
                } else {
                    draft.invalidNumberFields + fieldId
                },
        )
    }

    fun editText(
        fieldId: ActivitySnapshotFieldId,
        text: String,
    ) = editDraft(fieldId) { field, draft ->
        require(field.type == CustomFieldType.TEXT)
        draft.copy(values = draft.values + (fieldId to TextExecutionValue(fieldId, text)))
    }

    fun editCategory(
        fieldId: ActivitySnapshotFieldId,
        optionId: ActivitySnapshotCategoryOptionId?,
    ) = editDraft(fieldId) { field, draft ->
        require(field.type == CustomFieldType.CATEGORY)
        require(optionId == null || field.categoryOptions.any { it.id == optionId })
        draft.copy(values = draft.values + (fieldId to optionId?.let { CategoryExecutionValue(fieldId, it) }))
    }

    fun markMissing(fieldId: ActivitySnapshotFieldId) =
        editDraft(fieldId) { _, draft ->
            draft.copy(
                values = draft.values + (fieldId to null),
                numberTexts = draft.numberTexts + (fieldId to ""),
                invalidNumberFields = draft.invalidNumberFields - fieldId,
            )
        }

    fun saveCurrentValues() =
        submit(capturesCurrentValues = true) { sequence ->
            val occurrenceId = sequence.runtime.execution.currentOccurrenceId ?: return@submit null
            val row = occurrence(sequence, occurrenceId) ?: return@submit null
            val draft =
                mutableState.value.currentValueDraft?.takeIf { it.occurrenceId == occurrenceId } ?: return@submit null
            ExpandedSequenceCommand
                .SaveValues(
                    sequence.runtime.execution.id,
                    occurrenceId,
                    draft.overrides(row.activity.fields),
                    wallClock.now(),
                ).takeIf {
                    expandedActions(sequence.state).editCurrentValues &&
                        row.activity.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING &&
                        draft.invalidNumberFields.isEmpty()
                }
        }

    fun close() {
        invalidationJob.cancel()
    }

    private fun goNowConfirmed(occurrenceId: SequenceOccurrenceId) =
        submit { sequence ->
            ExpandedSequenceCommand
                .GoNow(sequence.runtime.execution.id, occurrenceId, wallClock.now())
                .takeIf { canGoNow(sequence, occurrenceId) }
        }

    private fun endEarlyConfirmed() =
        submit { sequence ->
            ExpandedSequenceCommand
                .EndEarly(sequence.runtime.execution.id, wallClock.now())
                .takeIf { expandedActions(sequence.state).endEarly }
        }

    private fun runtimeAdd(
        source: ActivityEntrySource,
        placement: RuntimeInsertionPlacement,
    ) = submit { sequence ->
        ExpandedSequenceCommand
            .RuntimeAdd(sequence.runtime.execution.id, source, placement, wallClock.now())
            .takeIf {
                expandedActions(
                    sequence.state,
                ).runtimeAdd &&
                    placement in validRuntimePlacements(sequence.state)
            }
    }

    private fun editDraft(
        fieldId: ActivitySnapshotFieldId,
        transform: (ActivitySnapshotField, CurrentValueDraft) -> CurrentValueDraft,
    ) {
        synchronized(commandCaptureLock) {
            if (mutableState.value.commandInFlight) return
            val sequence = active() ?: return reject()
            if (!expandedActions(sequence.state).editCurrentValues) return reject()
            val occurrenceId = sequence.runtime.execution.currentOccurrenceId ?: return reject()
            val row = occurrence(sequence, occurrenceId) ?: return reject()
            val field = row.activity.fields.singleOrNull { it.id == fieldId } ?: return reject()
            val draft =
                mutableState.value.currentValueDraft?.takeIf { it.occurrenceId == occurrenceId }
                    ?: return reject()
            try {
                mutableState.update { it.copy(currentValueDraft = transform(field, draft), commandFailure = null) }
            } catch (failure: IllegalArgumentException) {
                mutableState.update { it.copy(commandFailure = ExpandedSequenceFailure.Rejected(failure.message())) }
            }
        }
    }

    private fun canGoNow(
        sequence: ExpandedLiveSequence,
        occurrenceId: SequenceOccurrenceId,
    ): Boolean =
        expandedActions(sequence.state).goNow &&
            occurrence(sequence, occurrenceId)?.occurrence?.status == RuntimeOccurrenceStatus.NOT_STARTED

    private fun occurrence(
        sequence: ExpandedLiveSequence,
        id: SequenceOccurrenceId,
    ): ExpandedLiveSequenceOccurrence? = sequence.occurrences.singleOrNull { it.occurrence.id == id }

    private fun active(): ExpandedLiveSequence? = mutableState.value.sequence?.takeUnless { mutableState.value.stale }

    private fun reject() {
        mutableState.update {
            it.copy(commandFailure = ExpandedSequenceFailure.Rejected("Action is not valid for the loaded runtime"))
        }
    }

    private fun submit(
        capturesCurrentValues: Boolean = false,
        build: (ExpandedLiveSequence) -> ExpandedSequenceCommand?,
    ) {
        val captured =
            if (capturesCurrentValues) {
                synchronized(commandCaptureLock) {
                    if (mutableState.value.commandInFlight) {
                        reject()
                        return
                    }
                    val command = active()?.let(build)
                    if (command == null || command.executionId != executionId) {
                        reject()
                        return
                    }
                    onCurrentValueCommandCaptured?.invoke()
                    mutableState.update { it.copy(commandInFlight = true, commandFailure = null) }
                    command
                }
            } else {
                null
            }
        scope.launch {
            commandMutex.withLock {
                val command = captured ?: active()?.let(build)
                if (command == null || command.executionId != executionId) {
                    reject()
                    return@withLock
                }
                if (captured == null) mutableState.update { it.copy(commandInFlight = true, commandFailure = null) }
                var commandFailure: ExpandedSequenceFailure? = null
                try {
                    execute(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: StaleSequenceRouteException) {
                    mutableState.update { it.copy(stale = true, currentValueDraft = null) }
                } catch (failure: StaleSequenceTargetException) {
                    commandFailure = ExpandedSequenceFailure.StaleTarget(failure.message())
                } catch (failure: Exception) {
                    commandFailure = ExpandedSequenceFailure.Rejected(failure.message())
                }
                try {
                    coordinateRuntimeStateChanged()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    commandFailure = ExpandedSequenceFailure.Coordination(failure.message())
                    refresh()
                }
                mutableState.update { it.copy(commandInFlight = false, commandFailure = commandFailure) }
            }
        }
    }

    private fun publish(sequence: ExpandedLiveSequence) {
        require(sequence.runtime.execution.id == executionId)
        val currentId = sequence.runtime.execution.currentOccurrenceId
        val priorDraft = mutableState.value.currentValueDraft?.takeIf { it.occurrenceId == currentId }
        val draft =
            priorDraft ?: currentId?.let { id -> draftFor(sequence.occurrences.single { it.occurrence.id == id }) }
        mutableState.update {
            it.copy(
                sequence = sequence,
                loading = false,
                stale = false,
                readFailure = null,
                displayBaseline = displayBaseline()?.takeIf { baseline -> baseline.matches(sequence.runtime) },
                currentValueDraft = draft,
            )
        }
    }

    private fun draftFor(row: ExpandedLiveSequenceOccurrence): CurrentValueDraft {
        val actual =
            row.childExecution
                ?.values
                ?.associateBy(ActivityExecutionFieldValue::snapshotFieldId)
                .orEmpty()
        val values =
            row.activity.fields.associate { field ->
                field.id to if (row.childExecution != null) actual[field.id] else field.defaultValue()
            }
        return CurrentValueDraft(
            row.occurrence.id,
            values,
            row.activity.fields.filter { it.type == CustomFieldType.NUMBER }.associate { field ->
                val number = values[field.id] as? NumberExecutionValue
                field.id to formatLauncherNumber(number?.scaledValue, field.displayPrecision)
            },
        )
    }

    private fun ActivitySnapshotField.defaultValue(): ActivityExecutionFieldValue? =
        when (type) {
            CustomFieldType.NUMBER -> defaultNumberScaled?.let { NumberExecutionValue(id, it) }
            CustomFieldType.CATEGORY -> defaultCategoryOptionId?.let { CategoryExecutionValue(id, it) }
            CustomFieldType.TEXT -> defaultText?.let { TextExecutionValue(id, it) }
        }

    private fun Throwable.message(): String = message ?: javaClass.simpleName
}
