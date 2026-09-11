@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
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
import java.util.concurrent.atomic.AtomicBoolean
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
    ) : SequenceTemplateEditorSave
}

data class SequenceTemplateEditorState(
    val load: SequenceTemplateEditorLoad = SequenceTemplateEditorLoad.Loading,
    val save: SequenceTemplateEditorSave = SequenceTemplateEditorSave.Idle,
    val discardConfirmationVisible: Boolean = false,
    val availableActivities: List<SequenceEditorActivityChoice> = emptyList(),
    val textInputs: Map<String, SequenceEditorTextInput> = emptyMap(),
    val manipulation: SequenceManipulationUiState? = null,
    val appliedGeneration: Long = 0,
)

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
    target: SequenceTemplateEditorTarget,
    private val loadSequence: suspend (SequenceTemplateId) -> SequenceTemplateAuthoringState?,
    private val loadActivities: suspend () -> List<SequenceEditorActivityChoice>,
    private val createSequence: suspend (SequenceTemplateDraft, TemplateLibraryPlacement, Instant) -> SequenceTemplate,
    private val saveSequence: suspend (SequenceTemplateId, Long, SequenceTemplateDraft, Instant) -> SequenceTemplate,
    private val now: () -> Instant,
) {
    private val mutableState = MutableStateFlow(SequenceTemplateEditorState())
    val state: StateFlow<SequenceTemplateEditorState> = mutableState
    private val saving = AtomicBoolean()
    private val newIdentity = AtomicLong()
    private var durableTarget = target
    private var manipulationSession: SequenceManipulationSession? = null
    private var committedAwaitingReload: SequenceTemplate? = null

    @Volatile private var closed = false

    init {
        load()
    }

    fun close() {
        closed = true
    }

    fun retry() {
        if (!closed && !saving.get()) load()
    }

    fun updateDraft(transform: (SequenceTemplateDraft) -> SequenceTemplateDraft) {
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
        if (closed || saving.get() || committedAwaitingReload != null) return
        mutableState.update {
            it.copy(load = ready.copy(draft = transform(ready.draft)), save = SequenceTemplateEditorSave.Idle)
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
        if (closed || saving.get() || committedAwaitingReload != null) return
        val value = text.toLongOrNull()
        val valid = value != null && value in minimum..maximum
        mutableState.update { state ->
            val originalText = state.textInputs[key]?.originalText ?: originalValue.toString()
            state.copy(
                textInputs = state.textInputs + (key to SequenceEditorTextInput(text, originalText, valid)),
                save = SequenceTemplateEditorSave.Idle,
            )
        }
        if (valid) onValid(requireNotNull(value))
    }

    fun clearNumberInput(key: String) {
        mutableState.update { it.copy(textInputs = it.textInputs - key, save = SequenceTemplateEditorSave.Idle) }
    }

    fun newKey(prefix: String): DraftIdentity.New = DraftIdentity.New("$prefix-${newIdentity.incrementAndGet()}")

    fun newLocalActivityDraft() = ActivitySnapshotDraft("", null, TimeTrackingMode.STOPWATCH, null)

    fun enterManipulation(identity: DraftIdentity<SequenceNodeId>) {
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
        if (closed || saving.get() || committedAwaitingReload != null) return
        val session =
            manipulationSession ?: SequenceManipulationSession(ready.draft, identity, ready.original).also {
                manipulationSession = it
            }
        session.select(identity)
        publishManipulation(session)
    }

    fun selectManipulation(identity: DraftIdentity<SequenceNodeId>) {
        manipulationSession?.let {
            it.select(identity)
            publishManipulation(it)
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
            session.duplicate(draft, identity, newKey("duplicate"), destination)
        }

    fun undoManipulation(): Boolean = changeManipulation { session, draft -> session.undo(draft) }

    fun redoManipulation(): Boolean = changeManipulation { session, draft -> session.redo(draft) }

    fun discardManipulation() {
        if (committedAwaitingReload != null) return
        val session = manipulationSession ?: return
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
        manipulationSession = null
        mutableState.update {
            it.copy(
                load = ready.copy(draft = session.baseline),
                manipulation = null,
                save = SequenceTemplateEditorSave.Idle,
            )
        }
    }

    fun requestBack(onExit: () -> Unit) {
        if (saving.get() || committedAwaitingReload != null) return
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready
        if (ready == null || !mutableState.value.isDirty(ready)) {
            onExit()
        } else {
            mutableState.update { it.copy(discardConfirmationVisible = true) }
        }
    }

    fun dismissDiscard() {
        mutableState.update { it.copy(discardConfirmationVisible = false) }
    }

    fun discard(onExit: () -> Unit) {
        if (!saving.get()) {
            mutableState.update { it.copy(discardConfirmationVisible = false) }
            onExit()
        }
    }

    fun save() = commit(exitAfter = true)

    fun applyManipulation() = commit(exitAfter = false)

    private fun commit(exitAfter: Boolean) {
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
        if (closed || !saving.compareAndSet(false, true)) return
        if (mutableState.value.hasInvalidInput(ready.draft)) {
            saving.set(false)
            return
        }
        val submittedDraft = ready.submittedDraft()
        val invalid = runCatching { TemplateAuthoringDraftValidator.requireValid(submittedDraft) }.exceptionOrNull()
        if (invalid != null) {
            saving.set(false)
            mutableState.update { it.copy(save = invalid.toSaveFailure()) }
            return
        }
        if (submittedDraft == ready.original) {
            saving.set(false)
            manipulationSession = null
            mutableState.update {
                it.copy(
                    manipulation = null,
                    save = if (exitAfter) SequenceTemplateEditorSave.Committed else SequenceTemplateEditorSave.Idle,
                )
            }
            return
        }
        mutableState.update { it.copy(save = SequenceTemplateEditorSave.Saving) }
        scope.launch {
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
                        }.also {
                            durableTarget = SequenceTemplateEditorTarget.Existing(it.id)
                            committedAwaitingReload = it
                        }
                val canonical = requireNotNull(loadSequence(committed.id)) { "Committed Sequence is unavailable" }
                val canonicalDraft = canonical.toAuthoringDraft()
                durableTarget = SequenceTemplateEditorTarget.Existing(committed.id)
                committedAwaitingReload = null
                manipulationSession = null
                if (!closed) {
                    mutableState.update {
                        it.copy(
                            load =
                                SequenceTemplateEditorLoad.Ready(
                                    canonicalDraft,
                                    canonical.sequence.revision,
                                    canonicalDraft,
                                ),
                            save =
                                if (exitAfter) {
                                    SequenceTemplateEditorSave.Committed
                                } else {
                                    SequenceTemplateEditorSave.Idle
                                },
                            manipulation = null,
                            textInputs = emptyMap(),
                            appliedGeneration = it.appliedGeneration + if (exitAfter) 0 else 1,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) mutableState.update { it.copy(save = failure.toSaveFailure()) }
            } finally {
                saving.set(false)
            }
        }
    }

    private fun load() {
        if (closed) return
        mutableState.value = SequenceTemplateEditorState()
        scope.launch {
            try {
                val target = durableTarget
                val authoring = (target as? SequenceTemplateEditorTarget.Existing)?.let { loadSequence(it.id) }
                require(
                    target !is SequenceTemplateEditorTarget.Existing || authoring != null,
                ) { "Sequence template is unavailable" }
                require(authoring?.sequence?.deletedAt == null) { "Sequence template is unavailable" }
                val draft = authoring?.toAuthoringDraft() ?: SequenceTemplateDraft("", null)
                val choices = loadActivities()
                if (!closed) {
                    mutableState.value =
                        SequenceTemplateEditorState(
                            load = SequenceTemplateEditorLoad.Ready(draft, authoring?.sequence?.revision, draft),
                            availableActivities = choices,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) {
                    mutableState.value =
                        SequenceTemplateEditorState(
                            load =
                                SequenceTemplateEditorLoad.Failure(
                                    failure.message ?: "Unknown error",
                                ),
                        )
                }
            }
        }
    }

    private fun changeManipulation(
        change: (SequenceManipulationSession, SequenceTemplateDraft) -> SequenceTemplateDraft?,
    ): Boolean {
        val session = manipulationSession ?: return false
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return false
        if (closed || saving.get() || committedAwaitingReload != null) return false
        val changed = change(session, ready.draft) ?: return false
        mutableState.update {
            it.copy(
                load = ready.copy(draft = changed),
                save = SequenceTemplateEditorSave.Idle,
                manipulation = session.uiState(),
            )
        }
        return true
    }

    private fun publishManipulation(session: SequenceManipulationSession) {
        mutableState.update { it.copy(manipulation = session.uiState(), save = SequenceTemplateEditorSave.Idle) }
    }

    private fun Throwable.toSaveFailure(): SequenceTemplateEditorSave.Failure {
        val detail = message ?: "Unable to save the sequence template"
        return SequenceTemplateEditorSave.Failure(detail, detail.contains("revision changed concurrently", true))
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
                            identity = DraftIdentity.New("sequence-field-replacement-${newIdentity.incrementAndGet()}"),
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

private fun SequenceTemplateEditorState.isDirty(ready: SequenceTemplateEditorLoad.Ready): Boolean =
    ready.draft != ready.original ||
        ready.draft.activeNumberInputKeys().any { key ->
            textInputs[key]?.let { it.text != it.originalText } == true
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
