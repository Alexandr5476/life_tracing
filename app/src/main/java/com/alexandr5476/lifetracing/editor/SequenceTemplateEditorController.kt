@file:Suppress("LongParameterList", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.DraftIdentity
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
)

data class SequenceEditorActivityChoice(
    val id: ActivityTemplateId,
    val name: String,
)

class SequenceTemplateEditorController internal constructor(
    private val scope: CoroutineScope,
    private val target: SequenceTemplateEditorTarget,
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
        if (closed || saving.get()) return
        mutableState.update {
            it.copy(load = ready.copy(draft = transform(ready.draft)), save = SequenceTemplateEditorSave.Idle)
        }
    }

    fun newKey(prefix: String): DraftIdentity.New = DraftIdentity.New("$prefix-${newIdentity.incrementAndGet()}")

    fun newLocalActivityDraft() = ActivitySnapshotDraft("", null, TimeTrackingMode.STOPWATCH, null)

    fun requestBack(onExit: () -> Unit) {
        if (saving.get()) return
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready
        if (ready == null || ready.draft == ready.original) {
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

    fun save() {
        val ready = mutableState.value.load as? SequenceTemplateEditorLoad.Ready ?: return
        if (closed || !saving.compareAndSet(false, true)) return
        val submittedDraft = ready.submittedDraft()
        val invalid = runCatching { TemplateAuthoringDraftValidator.requireValid(submittedDraft) }.exceptionOrNull()
        if (invalid != null) {
            saving.set(false)
            mutableState.update { it.copy(save = invalid.toSaveFailure()) }
            return
        }
        mutableState.update { it.copy(save = SequenceTemplateEditorSave.Saving) }
        scope.launch {
            try {
                when (target) {
                    SequenceTemplateEditorTarget.New ->
                        createSequence(
                            submittedDraft,
                            TemplateLibraryPlacement(),
                            now(),
                        )
                    is SequenceTemplateEditorTarget.Existing ->
                        saveSequence(
                            target.id,
                            requireNotNull(ready.expectedRevision),
                            submittedDraft,
                            now(),
                        )
                }
                if (!closed) mutableState.update { it.copy(save = SequenceTemplateEditorSave.Committed) }
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
