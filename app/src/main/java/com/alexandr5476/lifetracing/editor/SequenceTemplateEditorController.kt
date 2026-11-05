@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "LargeClass",
    "SwallowedException",
)

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSourceStatus
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateAuthoringState
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.TemplateAuthoringDraftValidator
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

sealed interface SequenceTemplateEditorTarget {
    data object New : SequenceTemplateEditorTarget

    data class Existing(
        val id: SequenceTemplateId,
    ) : SequenceTemplateEditorTarget
}

sealed interface SequenceTemplateEditorLoad {
    data object Loading : SequenceTemplateEditorLoad

    data class Ready(
        val draft: SequenceTemplateDraft,
        val expectedRevision: Long?,
        val original: SequenceTemplateDraft,
    ) : SequenceTemplateEditorLoad

    data class Failure(
        val message: String,
    ) : SequenceTemplateEditorLoad
}

sealed interface SequenceTemplateEditorSave {
    data object Idle : SequenceTemplateEditorSave

    data object Saving : SequenceTemplateEditorSave

    data object Committed : SequenceTemplateEditorSave

    data class Failure(
        val message: String,
        val isConflict: Boolean,
        val committedAwaitingReload: Boolean = false,
    ) : SequenceTemplateEditorSave
}

data class SequenceTemplateEditorState(
    val load: SequenceTemplateEditorLoad = SequenceTemplateEditorLoad.Loading,
    val save: SequenceTemplateEditorSave = SequenceTemplateEditorSave.Idle,
    val discardConfirmationVisible: Boolean = false,
    val availableActivities: List<SequenceEditorActivityChoice> = emptyList(),
    val activityPickerLoading: Boolean = false,
    val activityPickerCanLoadMore: Boolean = false,
    val textInputs: Map<String, SequenceEditorTextInput> = emptyMap(),
    val manipulation: SequenceManipulationUiState? = null,
    val stepSourceIds: Map<SequenceNodeId, ActivityTemplateId?> = emptyMap(),
    val sourceStatuses: Map<ActivityTemplateId, SequenceEditorStepSource> = emptyMap(),
    val appliedGeneration: Long = 0,
    val generation: Long = 0,
    val discardGeneration: Long = 0,
)

sealed interface SequenceEditorStepSource {
    data object Loading : SequenceEditorStepSource

    data object Unavailable : SequenceEditorStepSource

    data class Active(
        val revision: Long,
    ) : SequenceEditorStepSource
}

data class SequenceEditorActivityChoice(
    val id: ActivityTemplateId,
    val name: String,
    val timeTrackingMode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
    val timerTarget: java.time.Duration? = null,
    val mainValueName: String? = null,
    val mainValueUnit: String? = null,
    val mainValueDisplayPrecision: Int? = null,
    val mainValueDefaultNumberScaled: Long? = null,
)

data class SequenceEditorTextInput(
    val text: String,
    val originalText: String,
    val isValid: Boolean,
)

class SequenceTemplateEditorController internal constructor(
    private val scope: CoroutineScope,
    val target: SequenceTemplateEditorTarget,
    private val loadSequence: suspend (SequenceTemplateId) -> SequenceTemplateAuthoringState?,
    private val loadActivities: suspend () -> List<SequenceEditorActivityChoice>,
    private val createSequence: suspend (SequenceTemplateDraft, TemplateLibraryPlacement, Instant) -> SequenceTemplate,
    private val saveSequence: suspend (SequenceTemplateId, Long, SequenceTemplateDraft, Instant) -> SequenceTemplate,
    private val now: () -> Instant,
    private val loadSourceStatuses:
        suspend (
            Collection<ActivityTemplateId>,
        ) -> Map<ActivityTemplateId, ActivityTemplateSourceStatus> = { emptyMap() },
    private val updateFromSource: suspend (SequenceTemplateId, SequenceNodeId, Long, Instant) -> Unit = { _, _, _, _ ->
        error("Source actions are unavailable")
    },
    private val updateSource:
        suspend (SequenceTemplateId, SequenceNodeId, Long, Long, Instant) -> Unit = { _, _, _, _, _ ->
        error("Source actions are unavailable")
    },
    private val saveAsNewSource: suspend (SequenceTemplateId, SequenceNodeId, Long, Instant) -> Unit = { _, _, _, _ ->
        error("Source actions are unavailable")
    },
    private val loadMoreActivities:
        suspend (SequenceEditorActivityChoice) -> List<SequenceEditorActivityChoice> = { emptyList() },
) {
    private val mutableState = MutableStateFlow(SequenceTemplateEditorState())
    val state: StateFlow<SequenceTemplateEditorState> = mutableState
    private val commandLock = Any()
    private var saving = false
    private var loadGeneration = 0L
    private val newIdentity = AtomicLong()
    private var durableTarget = target
    private var manipulationSession: SequenceManipulationSession? = null
    private var manipulationTextInputsBaseline: Map<String, SequenceEditorTextInput>? = null
    private var recordingManipulationInput = false
    private var destinationOffer: SequenceDestinationOffer? = null
    private var committedAwaitingReload: CommittedReload? = null
    private var sourceActionAwaitingReload: SequenceTemplateId? = null

    @Volatile private var closed = false

    init {
        load()
    }

    fun close() {
        synchronized(commandLock) {
            closed = true
            destinationOffer = null
        }
    }

    private var modeGeneration = 0L
    internal val presentationModeGeneration get() = synchronized(commandLock) { modeGeneration }

    internal fun ownsGeneration(generation: Long): Boolean =
        synchronized(commandLock) { !closed && loadGeneration == generation }

    internal fun ownsDiscard(generation: Long): Boolean =
        synchronized(commandLock) {
            mutableState.value.discardConfirmationVisible && mutableState.value.discardGeneration == generation
        }

    internal fun withPresentationGeneration(
        generation: Long,
        discardGeneration: Long?,
        action: () -> Unit,
    ) {
        synchronized(commandLock) {
            if (!ownsGeneration(generation)) return
            if (discardGeneration != null && !ownsDiscard(discardGeneration)) return
            action()
        }
    }

    private fun routeUnavailable(): Boolean =
        closed || saving || mutableState.value.save is SequenceTemplateEditorSave.Committed

    private fun commandsBlocked(): Boolean =
        closed ||
            saving ||
            hasCommittedAwaitingReload() ||
            mutableState.value.save is SequenceTemplateEditorSave.Committed ||
            mutableState.value.discardConfirmationVisible

    fun retry() {
        val recovery =
            synchronized(commandLock) {
                if (routeUnavailable() || mutableState.value.discardConfirmationVisible) return
                val committed = committedAwaitingReload
                val source = sourceActionAwaitingReload
                if (committed == null && source == null) {
                    null
                } else {
                    saving = true
                    mutableState.update { it.copy(save = SequenceTemplateEditorSave.Saving) }
                    committed to source
                }
            }
        if (recovery == null) {
            load()
        } else {
            scope.launch {
                val committed = recovery.first
                if (committed != null) {
                    recoverCommitted(committed)
                } else {
                    recoverSourceAction(requireNotNull(recovery.second))
                }
            }
        }
    }

    fun updateDraft(transform: (SequenceTemplateDraft) -> SequenceTemplateDraft) {
        synchronized(commandLock) {
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
            if (commandsBlocked()) return
            val before = manipulationSnapshot(ready)
            val updated = transform(ready.draft)
            if (updated == ready.draft) return
            if (destinationOffer != null &&
                updated.nodes !== ready.draft.nodes &&
                updated.manipulationStructure() != ready.draft.manipulationStructure()
            ) {
                destinationOffer = null
            }
            mutableState.update {
                it.copy(load = ready.copy(draft = updated), save = SequenceTemplateEditorSave.Idle)
            }
            if (!recordingManipulationInput) recordManipulation(before)
        }
    }

    fun updateNumberInput(
        key: String,
        text: String,
        originalValue: Long,
        minimum: Long,
        maximum: Long = Long.MAX_VALUE,
        onValid: (Long) -> Unit,
    ) {
        synchronized(commandLock) {
            if (commandsBlocked()) return
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready
            val before = ready?.let(::manipulationSnapshot)
            val value = text.toLongOrNull()
            val valid = value != null && value in minimum..maximum
            mutableState.update { state ->
                val originalText = state.textInputs[key]?.originalText ?: originalValue.toString()
                state.copy(
                    textInputs = state.textInputs + (key to SequenceEditorTextInput(text, originalText, valid)),
                    save = SequenceTemplateEditorSave.Idle,
                )
            }
            recordingManipulationInput = true
            try {
                if (valid) onValid(requireNotNull(value))
            } finally {
                recordingManipulationInput = false
            }
            before?.let(::recordManipulation)
        }
    }

    fun clearNumberInput(key: String) {
        synchronized(commandLock) {
            if (commandsBlocked()) return
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready
            val before = ready?.let(::manipulationSnapshot)
            mutableState.update { it.copy(textInputs = it.textInputs - key, save = SequenceTemplateEditorSave.Idle) }
            before?.let(::recordManipulation)
        }
    }

    fun newKey(prefix: String): DraftIdentity.New = DraftIdentity.New("$prefix-${newIdentity.incrementAndGet()}")

    fun newLocalActivityDraft() = ActivitySnapshotDraft("", null, TimeTrackingMode.STOPWATCH, null)

    fun loadActivityPicker() {
        val generation =
            synchronized(commandLock) {
                if (commandsBlocked()) return
                if (mutableState.value.availableActivities.isNotEmpty() ||
                    mutableState.value.activityPickerLoading
                ) {
                    return
                }
                mutableState.update { it.copy(activityPickerLoading = true) }
                loadGeneration
            }
        scope.launch {
            try {
                val activities = loadActivities()
                publishPicker(generation) {
                    it.copy(
                        availableActivities = activities,
                        activityPickerLoading = false,
                        activityPickerCanLoadMore = activities.size == ACTIVITY_PICKER_PAGE_SIZE,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishPicker(generation) { it.copy(activityPickerLoading = false) }
            }
        }
    }

    fun loadMoreActivityPicker() {
        val (last, generation) =
            synchronized(commandLock) {
                if (commandsBlocked()) return
                val state = mutableState.value
                val last = state.availableActivities.lastOrNull() ?: return
                if (state.activityPickerLoading || !state.activityPickerCanLoadMore) return
                mutableState.update { it.copy(activityPickerLoading = true) }
                last to loadGeneration
            }
        scope.launch {
            try {
                val activities = loadMoreActivities(last)
                publishPicker(generation) {
                    it.copy(
                        availableActivities = it.availableActivities + activities,
                        activityPickerLoading = false,
                        activityPickerCanLoadMore = activities.size == ACTIVITY_PICKER_PAGE_SIZE,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishPicker(generation) { it.copy(activityPickerLoading = false) }
            }
        }
    }

    private fun publishPicker(
        generation: Long,
        transform: (SequenceTemplateEditorState) -> SequenceTemplateEditorState,
    ) {
        synchronized(commandLock) {
            if (!ownsGeneration(generation)) return
            mutableState.update(transform)
        }
    }

    fun updateStepFromSource(stepId: SequenceNodeId) =
        runSourceAction(stepId, requireActiveSource = true) { id, revision, _, at ->
            updateFromSource(id, stepId, revision, at)
        }

    fun updateSourceTemplate(stepId: SequenceNodeId) =
        runSourceAction(stepId, requireActiveSource = true) { id, revision, sourceRevision, at ->
            updateSource(id, stepId, revision, requireNotNull(sourceRevision), at)
        }

    fun saveStepAsNewTemplate(stepId: SequenceNodeId) =
        runSourceAction(stepId, requireActiveSource = false) { id, revision, _, at ->
            saveAsNewSource(id, stepId, revision, at)
        }

    fun enterManipulation(identity: DraftIdentity<SequenceNodeId>) {
        synchronized(commandLock) {
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
            if (commandsBlocked() || !ready.draft.manipulationContains(identity)) return
            val session =
                manipulationSession ?: SequenceManipulationSession(ready.draft, identity, ready.original).also {
                    modeGeneration++
                    manipulationSession = it
                    manipulationTextInputsBaseline = mutableState.value.textInputs
                }
            if (session.selected != identity) destinationOffer = null
            session.select(identity)
            publishManipulation(session)
        }
    }

    fun selectManipulation(identity: DraftIdentity<SequenceNodeId>) {
        synchronized(commandLock) {
            if (commandsBlocked()) return
            val draft = mutableState.value.readyDraft() ?: return
            if (!draft.manipulationContains(identity)) return
            manipulationSession?.let {
                if (it.selected != identity) destinationOffer = null
                it.select(identity)
                publishManipulation(it)
            }
        }
    }

    fun moveManipulation(
        identity: DraftIdentity<SequenceNodeId>,
        destination: SequenceDropDestination,
    ): Boolean = changeManipulation { session, draft -> session.move(draft, identity, destination) }

    fun duplicateManipulation(
        identity: DraftIdentity<SequenceNodeId>,
        destination: SequenceDropDestination,
    ): Boolean =
        changeManipulation { session, draft ->
            val original = (mutableState.value.load as? SequenceTemplateEditorLoad.Ready)?.original
            if (original == null || !draft.canDuplicateManipulation(identity, original)) {
                null
            } else {
                session.duplicate(draft, identity, newKey("duplicate"), destination)
            }
        }

    internal val manipulationModified: Boolean
        get() =
            synchronized(commandLock) {
                val session = manipulationSession ?: return false
                mutableState.value.readyDraft() != session.baseline ||
                    mutableState.value.textInputs != manipulationTextInputsBaseline
            }

    internal fun offerManipulationDestinations(operation: SequenceDestinationOperation): SequenceDestinationOffer? =
        synchronized(commandLock) {
            destinationOffer = null
            if (commandsBlocked()) return null
            val session = manipulationSession ?: return null
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return null
            val source = session.selected
            if (!ready.draft.manipulationContains(source)) return null
            if (operation == SequenceDestinationOperation.Duplicate &&
                !ready.draft.canDuplicateManipulation(source, ready.original)
            ) {
                return null
            }
            val choices = sequenceManipulationDestinations(ready.draft, source, operation)
            if (choices.isEmpty()) return null
            SequenceDestinationOffer(source, operation, ready.draft.manipulationStructure(), choices).also {
                destinationOffer = it
            }
        }

    internal fun dismissManipulationDestinations(offer: SequenceDestinationOffer) {
        synchronized(commandLock) { if (destinationOffer === offer) destinationOffer = null }
    }

    internal fun chooseManipulationDestination(
        offer: SequenceDestinationOffer,
        choice: SequenceDestinationChoice,
    ): Boolean =
        synchronized(commandLock) {
            if (commandsBlocked() || destinationOffer !== offer) return false
            destinationOffer = null
            val draft = mutableState.value.readyDraft() ?: return false
            if (manipulationSession?.selected != offer.source ||
                draft.manipulationStructure() != offer.structure ||
                choice !in offer.choices
            ) {
                return false
            }
            when (offer.operation) {
                SequenceDestinationOperation.Move -> moveManipulation(offer.source, choice.destination)
                SequenceDestinationOperation.Duplicate -> duplicateManipulation(offer.source, choice.destination)
            }
        }

    fun undoManipulation(): Boolean = restoreManipulation { session, current -> session.undo(current) }

    fun redoManipulation(): Boolean = restoreManipulation { session, current -> session.redo(current) }

    fun discardManipulation() {
        synchronized(commandLock) {
            if (commandsBlocked()) return
            val session = manipulationSession ?: return
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
            val textInputs = requireNotNull(manipulationTextInputsBaseline)
            clearManipulationSession()
            mutableState.update {
                it.copy(
                    load = ready.copy(draft = session.baseline),
                    textInputs = textInputs,
                    manipulation = null,
                    save = SequenceTemplateEditorSave.Idle,
                )
            }
        }
    }

    fun requestBack(onExit: () -> Unit) {
        val shouldExit =
            synchronized(commandLock) {
                if (commandsBlocked()) return
                val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready
                (ready == null || !mutableState.value.isDirty(ready)).also {
                    if (!it) {
                        mutableState.update { state ->
                            state.copy(
                                discardConfirmationVisible = true,
                                discardGeneration =
                                    state.discardGeneration + 1,
                            )
                        }
                    }
                }
            }
        if (shouldExit) onExit()
    }

    fun dismissDiscard() {
        synchronized(commandLock) {
            if (closed || saving || hasCommittedAwaitingReload()) return
            mutableState.update { it.copy(discardConfirmationVisible = false) }
        }
    }

    fun discard(onExit: () -> Unit) {
        val shouldExit =
            synchronized(commandLock) {
                if (routeUnavailable() ||
                    hasCommittedAwaitingReload() ||
                    !mutableState.value.discardConfirmationVisible
                ) {
                    return
                }
                mutableState.update { it.copy(discardConfirmationVisible = false) }
                true
            }
        if (shouldExit) onExit()
    }

    fun save() = commit(exitAfter = true)

    fun applyManipulation() = commit(exitAfter = false)

    private fun commit(exitAfter: Boolean) {
        val (ready, submittedDraft) =
            synchronized(commandLock) {
                val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
                if (commandsBlocked()) return
                destinationOffer = null
                saving = true
                if (mutableState.value.hasInvalidInput(ready.draft)) {
                    saving = false
                    return
                }
                val submittedDraft = ready.submittedDraft()
                val invalid =
                    runCatching { TemplateAuthoringDraftValidator.requireValid(submittedDraft) }.exceptionOrNull()
                if (invalid != null) {
                    saving = false
                    mutableState.update { it.copy(save = invalid.toSaveFailure()) }
                    return
                }
                if (submittedDraft == ready.original) {
                    saving = false
                    clearManipulationSession()
                    mutableState.update {
                        it.copy(
                            manipulation = null,
                            save =
                                if (exitAfter) {
                                    SequenceTemplateEditorSave.Committed
                                } else {
                                    SequenceTemplateEditorSave.Idle
                                },
                        )
                    }
                    return
                }
                mutableState.update { it.copy(save = SequenceTemplateEditorSave.Saving) }
                ready to submittedDraft
            }
        scope.launch {
            persist(ready, submittedDraft, exitAfter)
        }
    }

    private suspend fun persist(
        ready: SequenceTemplateEditorLoad.Ready,
        submittedDraft: SequenceTemplateDraft,
        exitAfter: Boolean,
    ) {
        try {
            val committed =
                committedAwaitingReload
                    ?: when (val currentTarget = durableTarget) {
                        SequenceTemplateEditorTarget.New ->
                            createSequence(
                                submittedDraft,
                                TemplateLibraryPlacement(),
                                now(),
                            )
                        is SequenceTemplateEditorTarget.Existing ->
                            saveSequence(
                                currentTarget.id,
                                requireNotNull(ready.expectedRevision),
                                submittedDraft,
                                now(),
                            )
                    }.let { sequence ->
                        durableTarget = SequenceTemplateEditorTarget.Existing(sequence.id)
                        CommittedReload(sequence, exitAfter).also { committedAwaitingReload = it }
                    }
            completeCommittedReload(committed)
        } catch (cancelled: CancellationException) {
            synchronized(commandLock) { saving = false }
            throw cancelled
        } catch (failure: Exception) {
            synchronized(commandLock) {
                saving = false
                if (!closed) {
                    mutableState.update {
                        it.copy(save = failure.toSaveFailure(committed = committedAwaitingReload != null))
                    }
                }
            }
        }
    }

    private fun load() {
        val (target, generation) =
            synchronized(commandLock) {
                if (commandsBlocked()) return
                clearManipulationSession()
                val generation = ++loadGeneration
                mutableState.value =
                    SequenceTemplateEditorState(
                        generation = generation,
                        appliedGeneration = mutableState.value.appliedGeneration,
                    )
                durableTarget to generation
            }
        scope.launch {
            try {
                val authoring = (target as? SequenceTemplateEditorTarget.Existing)?.let { loadSequence(it.id) }
                require(
                    target !is SequenceTemplateEditorTarget.Existing || authoring != null,
                ) { "Sequence template is unavailable" }
                require(authoring?.sequence?.deletedAt == null) { "Sequence template is unavailable" }
                val draft = authoring?.toAuthoringDraft() ?: SequenceTemplateDraft("", null)
                val loaded = loadEditorData(authoring)
                synchronized(commandLock) {
                    if (closed || loadGeneration != generation) return@launch
                    mutableState.value =
                        SequenceTemplateEditorState(
                            load = SequenceTemplateEditorLoad.Ready(draft, authoring?.sequence?.revision, draft),
                            stepSourceIds = authoring?.stepSourceIds().orEmpty(),
                            sourceStatuses = loaded.sourceStatuses,
                            generation = generation,
                            appliedGeneration = mutableState.value.appliedGeneration,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(commandLock) {
                    if (closed || loadGeneration != generation) return@launch
                    mutableState.value =
                        SequenceTemplateEditorState(
                            load = SequenceTemplateEditorLoad.Failure(failure.message ?: "Unknown error"),
                            generation = generation,
                            appliedGeneration = mutableState.value.appliedGeneration,
                        )
                }
            }
        }
    }

    private suspend fun recoverCommitted(committed: CommittedReload) {
        try {
            completeCommittedReload(committed)
        } catch (cancelled: CancellationException) {
            synchronized(commandLock) { saving = false }
            throw cancelled
        } catch (failure: Exception) {
            synchronized(commandLock) {
                saving = false
                if (!closed) mutableState.update { it.copy(save = failure.toSaveFailure(committed = true)) }
            }
        }
    }

    private suspend fun completeCommittedReload(committed: CommittedReload) {
        val canonical = requireNotNull(loadSequence(committed.sequence.id)) { "Committed Sequence is unavailable" }
        val loaded = loadEditorData(canonical)
        val canonicalDraft = canonical.toAuthoringDraft()
        synchronized(commandLock) {
            durableTarget = SequenceTemplateEditorTarget.Existing(committed.sequence.id)
            committedAwaitingReload = null
            clearManipulationSession()
            saving = false
            if (!closed) {
                val generation = ++loadGeneration
                mutableState.update {
                    it.copy(
                        load =
                            SequenceTemplateEditorLoad.Ready(
                                canonicalDraft,
                                canonical.sequence.revision,
                                canonicalDraft,
                            ),
                        save =
                            if (committed.exitAfter) {
                                SequenceTemplateEditorSave.Committed
                            } else {
                                SequenceTemplateEditorSave.Idle
                            },
                        manipulation = null,
                        textInputs = emptyMap(),
                        generation = generation,
                        stepSourceIds = canonical.stepSourceIds(),
                        sourceStatuses = loaded.sourceStatuses,
                        appliedGeneration = it.appliedGeneration + if (committed.exitAfter) 0 else 1,
                    )
                }
            }
        }
    }

    private fun runSourceAction(
        stepId: SequenceNodeId,
        requireActiveSource: Boolean,
        action: suspend (SequenceTemplateId, Long, Long?, Instant) -> Unit,
    ) {
        val command =
            synchronized(commandLock) {
                val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
                val target = durableTarget as? SequenceTemplateEditorTarget.Existing ?: return
                if (ready.draft.findExistingStep(stepId) == null) return
                if (!canStartSourceAction(ready)) return
                val sourceId = mutableState.value.stepSourceIds[stepId]
                val sourceRevision =
                    (
                        sourceId?.let(
                            mutableState.value.sourceStatuses::get,
                        ) as? SequenceEditorStepSource.Active
                    )?.revision
                if (requireActiveSource && sourceRevision == null) return
                saving = true
                mutableState.update { it.copy(save = SequenceTemplateEditorSave.Saving) }
                SourceActionCommand(target.id, requireNotNull(ready.expectedRevision), sourceRevision)
            }
        scope.launch {
            try {
                action(command.sequenceId, command.expectedRevision, command.sourceRevision, now())
            } catch (cancelled: CancellationException) {
                synchronized(commandLock) { saving = false }
                throw cancelled
            } catch (failure: Exception) {
                synchronized(commandLock) {
                    saving = false
                    if (!closed) mutableState.update { it.copy(save = failure.toSaveFailure()) }
                }
                return@launch
            }
            synchronized(commandLock) { sourceActionAwaitingReload = command.sequenceId }
            recoverSourceAction(command.sequenceId)
        }
    }

    private suspend fun recoverSourceAction(sequenceId: SequenceTemplateId) {
        try {
            val canonical = requireNotNull(loadSequence(sequenceId)) { "Committed Sequence is unavailable" }
            val loaded = loadEditorData(canonical)
            val canonicalDraft = canonical.toAuthoringDraft()
            synchronized(commandLock) {
                saving = false
                sourceActionAwaitingReload = null
                clearManipulationSession()
                if (!closed) {
                    val generation = ++loadGeneration
                    mutableState.update {
                        it.copy(
                            load =
                                SequenceTemplateEditorLoad.Ready(
                                    canonicalDraft,
                                    canonical.sequence.revision,
                                    canonicalDraft,
                                ),
                            save = SequenceTemplateEditorSave.Idle,
                            manipulation = null,
                            textInputs = emptyMap(),
                            generation = generation,
                            stepSourceIds = canonical.stepSourceIds(),
                            sourceStatuses = loaded.sourceStatuses,
                            appliedGeneration = it.appliedGeneration + 1,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(commandLock) { saving = false }
            throw cancelled
        } catch (failure: Exception) {
            synchronized(commandLock) {
                saving = false
                if (!closed) mutableState.update { it.copy(save = failure.toSaveFailure(committed = true)) }
            }
        }
    }

    private suspend fun loadEditorData(authoring: SequenceTemplateAuthoringState?): LoadedEditorData {
        val sourceIds =
            authoring
                ?.stepSourceIds()
                ?.values
                ?.filterNotNull()
                ?.distinct()
                .orEmpty()
        val statuses = if (sourceIds.isEmpty()) emptyMap() else loadSourceStatuses(sourceIds)
        return LoadedEditorData(
            sourceIds.associateWith { id ->
                statuses[id]
                    ?.takeUnless(ActivityTemplateSourceStatus::isArchived)
                    ?.let { SequenceEditorStepSource.Active(it.revision) }
                    ?: SequenceEditorStepSource.Unavailable
            },
        )
    }

    private fun changeManipulation(
        change: (SequenceManipulationSession, SequenceTemplateDraft) -> SequenceTemplateDraft?,
    ): Boolean =
        synchronized(commandLock) {
            val session = manipulationSession ?: return false
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return false
            if (commandsBlocked()) return false
            val before = requireNotNull(manipulationSnapshot(ready))
            val changed = change(session, ready.draft) ?: return false
            destinationOffer = null
            mutableState.update {
                it.copy(
                    load = ready.copy(draft = changed),
                    save = SequenceTemplateEditorSave.Idle,
                    manipulation = session.uiState(),
                )
            }
            recordManipulation(before)
            true
        }

    private fun restoreManipulation(
        restore: (SequenceManipulationSession, SequenceManipulationSnapshot) -> SequenceManipulationSnapshot?,
    ): Boolean =
        synchronized(commandLock) {
            val session = manipulationSession ?: return false
            val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return false
            if (commandsBlocked()) return false
            val selectedBefore = session.selected
            val restored = restore(session, requireNotNull(manipulationSnapshot(ready))) ?: return false
            if (restored.draft.manipulationStructure() != ready.draft.manipulationStructure() ||
                restored.selected != selectedBefore
            ) {
                destinationOffer = null
            }
            mutableState.update {
                it.copy(
                    load = ready.copy(draft = restored.draft),
                    textInputs = restored.textInputs,
                    save = SequenceTemplateEditorSave.Idle,
                    manipulation = session.uiState(),
                )
            }
            true
        }

    private fun manipulationSnapshot(ready: SequenceTemplateEditorLoad.Ready): SequenceManipulationSnapshot? =
        manipulationSession?.let { session ->
            SequenceManipulationSnapshot(ready.draft, mutableState.value.textInputs, session.selected)
        }

    private fun recordManipulation(before: SequenceManipulationSnapshot?) {
        if (before == null) return
        val session = manipulationSession ?: return
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
        session.record(before, requireNotNull(manipulationSnapshot(ready)))
        mutableState.update { it.copy(manipulation = session.uiState()) }
    }

    private fun publishManipulation(session: SequenceManipulationSession) {
        mutableState.update { it.copy(manipulation = session.uiState(), save = SequenceTemplateEditorSave.Idle) }
    }

    private fun clearManipulationSession() {
        if (manipulationSession != null) modeGeneration++
        manipulationSession = null
        destinationOffer = null
        manipulationTextInputsBaseline = null
    }

    private fun Throwable.toSaveFailure(committed: Boolean = false): SequenceTemplateEditorSave.Failure {
        val detail = message ?: "Unable to save the sequence template"
        return SequenceTemplateEditorSave.Failure(
            detail,
            detail.contains("revision changed concurrently", true),
            committed,
        )
    }

    private fun hasCommittedAwaitingReload(): Boolean =
        committedAwaitingReload != null || sourceActionAwaitingReload != null

    private fun sourceActionsAllowed(ready: SequenceTemplateEditorLoad.Ready): Boolean =
        manipulationSession == null &&
            !mutableState.value.hasInvalidInput(ready.draft) &&
            !mutableState.value.isDirty(ready)

    private fun canStartSourceAction(ready: SequenceTemplateEditorLoad.Ready): Boolean {
        if (commandsBlocked()) return false
        return sourceActionsAllowed(ready)
    }

    private fun SequenceTemplateEditorLoad.Ready.submittedDraft(): SequenceTemplateDraft {
        val originalFields = original.fields.associateBy { (it.identity as? DraftIdentity.Existing)?.id }
        return draft.copy(
            fields =
                draft.fields.map { field ->
                    val originalField = originalFields[(field.identity as? DraftIdentity.Existing)?.id]
                    if (originalField != null &&
                        (field.type != originalField.type || field.unit != originalField.unit)
                    ) {
                        field.copy(
                            identity =
                                DraftIdentity.New(
                                    "sequence-field-replacement-${newIdentity.incrementAndGet()}",
                                ),
                        )
                    } else {
                        field
                    }
                },
        )
    }
}

internal fun SequenceTemplateEditorState.readyDraft(): SequenceTemplateDraft? =
    (load as? SequenceTemplateEditorLoad.Ready)?.draft

internal fun SequenceTemplateEditorState.inputText(
    key: String,
    default: String,
): String = textInputs[key]?.text ?: default

internal fun SequenceTemplateEditorState.inputIsInvalid(key: String): Boolean = textInputs[key]?.isValid == false

internal fun SequenceTemplateEditorState.hasInvalidInput(draft: SequenceTemplateDraft): Boolean =
    draft.activeNumberInputKeys().any { textInputs[it]?.isValid == false }

internal fun SequenceTemplateEditorState.isDirty(ready: SequenceTemplateEditorLoad.Ready): Boolean =
    ready.draft != ready.original ||
        ready.draft.activeNumberInputKeys().any { key ->
            textInputs[key]?.let { it.text != it.originalText } == true
        }

internal fun SequenceTemplateEditorState.sourceActionsAllowed(): Boolean {
    val ready = load as? SequenceTemplateEditorLoad.Ready ?: return false
    if (manipulation != null || discardConfirmationVisible) return false
    if (save is SequenceTemplateEditorSave.Saving || save is SequenceTemplateEditorSave.Committed) return false
    if ((save as? SequenceTemplateEditorSave.Failure)?.committedAwaitingReload == true) return false
    return !hasInvalidInput(ready.draft) && !isDirty(ready)
}

private data class LoadedEditorData(
    val sourceStatuses: Map<ActivityTemplateId, SequenceEditorStepSource>,
)

private const val ACTIVITY_PICKER_PAGE_SIZE = 50

private data class SourceActionCommand(
    val sequenceId: SequenceTemplateId,
    val expectedRevision: Long,
    val sourceRevision: Long?,
)

private data class CommittedReload(
    val sequence: SequenceTemplate,
    val exitAfter: Boolean,
)

private fun SequenceTemplateAuthoringState.stepSourceIds(): Map<SequenceNodeId, ActivityTemplateId?> =
    sequence.nodes
        .flatMap { node ->
            when (node) {
                is com.alexandr5476.lifetracing.domain.ActivityStep -> listOf(node)
                is com.alexandr5476.lifetracing.domain.SequenceRepeatBlock -> node.children
            }
        }.associate { step ->
            step.id to requireNotNull(activitySnapshots[step.activitySnapshotId]).sourceTemplateId
        }

private fun SequenceTemplateDraft.findExistingStep(
    stepId: SequenceNodeId,
): com.alexandr5476.lifetracing.domain.ActivityStepDraft? =
    nodes.firstNotNullOfOrNull { node ->
        when (node) {
            is com.alexandr5476.lifetracing.domain.SequenceNodeDraft.Step ->
                node.value.takeIf {
                    (it.identity as? DraftIdentity.Existing)?.id == stepId &&
                        it.activity is com.alexandr5476.lifetracing.domain.StepActivityDraft.Existing
                }
            is com.alexandr5476.lifetracing.domain.SequenceNodeDraft.Repeat ->
                node.value.children.firstOrNull {
                    (it.identity as? DraftIdentity.Existing)?.id == stepId &&
                        it.activity is com.alexandr5476.lifetracing.domain.StepActivityDraft.Existing
                }
        }
    }

internal fun SequenceTemplateDraft.activeNumberInputKeys(): Set<String> =
    buildSet {
        add(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN)
        add(SequenceEditorInputKey.BEFORE_STEP_COUNTDOWN)
        nodes.forEach { node ->
            when (node) {
                is com.alexandr5476.lifetracing.domain.SequenceNodeDraft.Step -> addStep(node.value)
                is com.alexandr5476.lifetracing.domain.SequenceNodeDraft.Repeat -> {
                    add(SequenceEditorInputKey.repeatCount(node.identity))
                    node.value.children.forEach(::addStep)
                }
            }
        }
    }

private fun MutableSet<String>.addStep(step: com.alexandr5476.lifetracing.domain.ActivityStepDraft) {
    add(SequenceEditorInputKey.stepCountdown(step.identity))
    val activity =
        when (val value = step.activity) {
            is com.alexandr5476.lifetracing.domain.StepActivityDraft.Existing -> value.configuration
            is com.alexandr5476.lifetracing.domain.StepActivityDraft.Local -> value.configuration
            else -> null
        } ?: return
    add(SequenceEditorInputKey.activityCountdown(step.identity))
    if (activity.timeTrackingMode == TimeTrackingMode.TIMER) {
        add(SequenceEditorInputKey.timerTarget(step.identity))
    }
}

internal object SequenceEditorInputKey {
    const val SEQUENCE_START_COUNTDOWN = "sequence-start-countdown"
    const val BEFORE_STEP_COUNTDOWN = "before-step-countdown"

    fun repeatCount(identity: DraftIdentity<*>): String = "repeat-count:${identity.editorInputKey()}"

    fun stepCountdown(identity: DraftIdentity<*>): String = "step-countdown:${identity.editorInputKey()}"

    fun activityCountdown(identity: DraftIdentity<*>): String = "activity-countdown:${identity.editorInputKey()}"

    fun timerTarget(identity: DraftIdentity<*>): String = "timer-target:${identity.editorInputKey()}"
}

private fun DraftIdentity<*>.editorInputKey(): String =
    when (this) {
        is DraftIdentity.Existing<*> -> "existing:$id"
        is DraftIdentity.New -> "new:$key"
    }

internal fun ActivitySnapshotFieldDraft.withCompatibleUnitReplacement(
    unit: String?,
    replacementIdentity: DraftIdentity.New,
): ActivitySnapshotFieldDraft =
    if (this.unit == unit || identity is DraftIdentity.New && sourceFieldId == null) {
        copy(unit = unit)
    } else {
        require(type == CustomFieldType.NUMBER) { "Only Number Fields have units" }
        copy(
            identity = replacementIdentity,
            sourceFieldId = null,
            nameAtCreation = localNameOverride ?: nameAtCreation,
            localNameOverride = null,
            unit = unit,
        )
    }

internal fun ActivitySnapshotFieldDraft.withCompatibleTypeReplacement(
    type: CustomFieldType,
    replacementIdentity: DraftIdentity.New,
): ActivitySnapshotFieldDraft =
    if (this.type == type || identity is DraftIdentity.New) {
        copy(type = type)
    } else {
        require(sourceFieldId == null) { "Source-linked Field type is immutable" }
        copy(identity = replacementIdentity, type = type)
    }
