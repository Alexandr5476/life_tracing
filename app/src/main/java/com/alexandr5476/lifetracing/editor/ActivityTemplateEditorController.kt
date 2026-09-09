@file:Suppress("LongParameterList", "MagicNumber", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.TemplateAuthoringDraftValidator
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface ActivityTemplateEditorTarget {
    data object New : ActivityTemplateEditorTarget

    data class Existing(
        val id: ActivityTemplateId,
    ) : ActivityTemplateEditorTarget
}

sealed interface ActivityTemplateEditorLoad {
    data object Loading : ActivityTemplateEditorLoad

    data class Ready(
        val draft: ActivityTemplateDraft,
        val expectedRevision: Long?,
        val original: ActivityTemplateDraft,
    ) : ActivityTemplateEditorLoad

    data class Failure(
        val message: String,
    ) : ActivityTemplateEditorLoad
}

sealed interface ActivityTemplateEditorSave {
    data object Idle : ActivityTemplateEditorSave

    data object Saving : ActivityTemplateEditorSave

    data object Committed : ActivityTemplateEditorSave

    data class Failure(
        val message: String,
        val isConflict: Boolean,
    ) : ActivityTemplateEditorSave
}

data class ActivityTemplateEditorState(
    val load: ActivityTemplateEditorLoad = ActivityTemplateEditorLoad.Loading,
    val save: ActivityTemplateEditorSave = ActivityTemplateEditorSave.Idle,
    val discardConfirmationVisible: Boolean = false,
    val timerTargetText: String = "",
    val timerTargetError: Boolean = false,
    val numberDefaultTexts: Map<String, String> = emptyMap(),
    val invalidNumberFields: Set<String> = emptySet(),
)

class ActivityTemplateEditorController internal constructor(
    private val scope: CoroutineScope,
    private val target: ActivityTemplateEditorTarget,
    private val loadTemplate: suspend (ActivityTemplateId) -> ActivityTemplate?,
    private val createTemplate: suspend (ActivityTemplateDraft, TemplateLibraryPlacement, Instant) -> ActivityTemplate,
    private val saveTemplate: suspend (ActivityTemplateId, Long, ActivityTemplateDraft, Instant) -> ActivityTemplate,
    private val now: () -> Instant,
) {
    private val mutableState = MutableStateFlow(ActivityTemplateEditorState())
    val state: StateFlow<ActivityTemplateEditorState> = mutableState
    private val saving = AtomicBoolean()
    private val newIdentity = AtomicLong()

    @Volatile
    private var closed = false

    init {
        load()
    }

    fun close() {
        closed = true
    }

    fun retry() {
        if (!closed && !saving.get()) load()
    }

    fun updateDraft(transform: (ActivityTemplateDraft) -> ActivityTemplateDraft) {
        val ready = mutableState.value.load as? ActivityTemplateEditorLoad.Ready ?: return
        if (!canEdit()) return
        val proposed = transform(ready.draft)
        val draft = normalizeDraft(proposed)
        val numberKeys =
            draft.fields
                .filter { it.type == CustomFieldType.NUMBER }
                .map { it.identity.editorKey() }
                .toSet()
        mutableState.update {
            it.copy(
                load = ready.copy(draft = draft),
                save = ActivityTemplateEditorSave.Idle,
                timerTargetText = if (draft.timeTrackingMode == TimeTrackingMode.TIMER) it.timerTargetText else "",
                timerTargetError = draft.timeTrackingMode == TimeTrackingMode.TIMER && draft.timerTarget == null,
                numberDefaultTexts =
                    it.numberDefaultTexts
                        .filterKeys(numberKeys::contains),
                invalidNumberFields =
                    it.invalidNumberFields
                        .filterTo(mutableSetOf(), numberKeys::contains),
            )
        }
    }

    fun setTimeTrackingMode(mode: TimeTrackingMode) =
        updateDraft {
            it.copy(
                timeTrackingMode = mode,
                timerTarget = if (mode == TimeTrackingMode.TIMER) it.timerTarget else null,
            )
        }

    fun setTimerTargetSeconds(text: String) {
        if (!canEdit()) return
        val seconds = text.toLongOrNull()?.takeIf { it > 0 }
        updateDraft { it.copy(timerTarget = seconds?.let(Duration::ofSeconds)) }
        mutableState.update { it.copy(timerTargetText = text, timerTargetError = seconds == null) }
    }

    fun setNumberDefault(
        field: ActivityFieldDraft,
        text: String,
        parse: (String, Int?) -> Long?,
    ) {
        if (!canEdit()) return
        val key = field.identity.editorKey()
        val number = parse(text, field.displayPrecision)
        if (text.isBlank() || number != null) {
            updateDraft { draft -> draft.withField(field.identity) { it.copy(defaultNumberScaled = number) } }
        }
        mutableState.update {
            it.copy(
                numberDefaultTexts = it.numberDefaultTexts + (key to text),
                invalidNumberFields =
                    if (text.isBlank() ||
                        number != null
                    ) {
                        it.invalidNumberFields - key
                    } else {
                        it.invalidNumberFields + key
                    },
            )
        }
    }

    fun setDisplayPrecision(
        field: ActivityFieldDraft,
        text: String,
    ) {
        if (!canEdit()) return
        val precision = text.toIntOrNull()?.takeIf { it in 0..3 }
        if (text.isNotBlank() && precision == null) return
        val key = field.identity.editorKey()
        val visibleText =
            mutableState.value.numberDefaultTexts[key]
                ?: formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision)
        val parsed = visibleText.takeUnless(String::isBlank)?.let { parseLauncherNumber(it, precision) }
        val valid = visibleText.isBlank() || parsed != null
        updateDraft { draft ->
            draft.withField(field.identity) {
                it.copy(
                    displayPrecision = precision,
                    defaultNumberScaled = if (valid) parsed else it.defaultNumberScaled,
                )
            }
        }
        mutableState.update {
            it.copy(
                numberDefaultTexts = it.numberDefaultTexts + (key to visibleText),
                invalidNumberFields = if (valid) it.invalidNumberFields - key else it.invalidNumberFields + key,
            )
        }
    }

    fun requestBack(onExit: () -> Unit) {
        if (saving.get()) return
        val ready = mutableState.value.load as? ActivityTemplateEditorLoad.Ready
        val hasUnsavedInput = mutableState.value.invalidNumberFields.isNotEmpty()
        if (ready == null || (ready.draft == ready.original && !hasUnsavedInput)) {
            onExit()
        } else {
            mutableState.update { it.copy(discardConfirmationVisible = true) }
        }
    }

    fun dismissDiscard() {
        mutableState.update { it.copy(discardConfirmationVisible = false) }
    }

    fun discard(onExit: () -> Unit) {
        if (saving.get()) return
        mutableState.update { it.copy(discardConfirmationVisible = false) }
        onExit()
    }

    fun save() {
        val ready = mutableState.value.load as? ActivityTemplateEditorLoad.Ready ?: return
        if (!canStartSave()) return
        if (!saving.compareAndSet(false, true)) return
        val submittedDraft = ready.submittedDraft()
        val validationFailure =
            runCatching {
                TemplateAuthoringDraftValidator.requireValid(
                    submittedDraft,
                )
            }.exceptionOrNull()
        if (validationFailure != null) {
            saving.set(false)
            mutableState.update {
                it.copy(
                    save =
                        (
                            validationFailure as? Exception ?: IllegalArgumentException(
                                validationFailure,
                            )
                        ).toSaveFailure(),
                )
            }
            return
        }
        mutableState.update { it.copy(save = ActivityTemplateEditorSave.Saving) }
        scope.launch {
            try {
                when (target) {
                    ActivityTemplateEditorTarget.New ->
                        createTemplate(submittedDraft, TemplateLibraryPlacement(), now())
                    is ActivityTemplateEditorTarget.Existing ->
                        saveTemplate(target.id, requireNotNull(ready.expectedRevision), submittedDraft, now())
                }
                if (!closed) mutableState.update { it.copy(save = ActivityTemplateEditorSave.Committed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) mutableState.update { it.copy(save = failure.toSaveFailure()) }
            } finally {
                saving.set(false)
            }
        }
    }

    private fun canStartSave(): Boolean =
        !closed &&
            mutableState.value.save !is ActivityTemplateEditorSave.Committed &&
            !mutableState.value.timerTargetError &&
            mutableState.value.invalidNumberFields.isEmpty()

    private fun canEdit(): Boolean = !closed && !saving.get()

    private fun load() {
        if (closed) return
        mutableState.value = ActivityTemplateEditorState()
        scope.launch {
            try {
                val template = (target as? ActivityTemplateEditorTarget.Existing)?.let { loadTemplate(it.id) }
                require(
                    target !is ActivityTemplateEditorTarget.Existing || template != null,
                ) { "Activity template is unavailable" }
                val draft =
                    template?.toAuthoringDraft() ?: ActivityTemplateDraft("", null, TimeTrackingMode.STOPWATCH, null)
                if (!closed) {
                    mutableState.value =
                        ActivityTemplateEditorState(
                            load = ActivityTemplateEditorLoad.Ready(draft, template?.revision, draft),
                            timerTargetText =
                                draft.timerTarget
                                    ?.seconds
                                    ?.toString()
                                    .orEmpty(),
                            timerTargetError =
                                draft.timeTrackingMode == TimeTrackingMode.TIMER && draft.timerTarget == null,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed) {
                    mutableState.value =
                        ActivityTemplateEditorState(
                            load =
                                ActivityTemplateEditorLoad.Failure(
                                    failure.message ?: "Unknown error",
                                ),
                        )
                }
            }
        }
    }

    private fun normalizeDraft(proposed: ActivityTemplateDraft): ActivityTemplateDraft =
        proposed.copy(
            fields =
                proposed.fields.map { it.normalizedMetadata() },
        )

    private fun ActivityFieldDraft.replacement() =
        copy(
            identity = DraftIdentity.New("field-replacement-${newIdentity.incrementAndGet()}"),
        )

    private fun ActivityTemplateEditorLoad.Ready.submittedDraft(): ActivityTemplateDraft {
        val originalFields =
            original.fields.associateBy { (it.identity as? DraftIdentity.Existing)?.id }
        return draft.copy(
            fields =
                draft.fields.map { field ->
                    val originalField = originalFields[(field.identity as? DraftIdentity.Existing)?.id]
                    if (originalField != null &&
                        (field.type != originalField.type || field.unit != originalField.unit)
                    ) {
                        field.replacement()
                    } else {
                        field
                    }
                },
        )
    }

    private fun ActivityFieldDraft.normalizedMetadata() =
        when (type) {
            CustomFieldType.NUMBER ->
                copy(
                    defaultCategoryOption = null,
                    defaultText = null,
                    categoryOptions = emptyList(),
                )
            CustomFieldType.CATEGORY ->
                copy(
                    unit = null,
                    displayPrecision = null,
                    defaultNumberScaled = null,
                    defaultText = null,
                    isMainValue = false,
                )
            CustomFieldType.TEXT ->
                copy(
                    unit = null,
                    displayPrecision = null,
                    defaultNumberScaled = null,
                    defaultCategoryOption = null,
                    categoryOptions = emptyList(),
                    isMainValue = false,
                )
        }

    private fun ActivityTemplateDraft.withField(
        identity: DraftIdentity<ActivityTemplateFieldId>,
        transform: (ActivityFieldDraft) -> ActivityFieldDraft,
    ) = copy(fields = fields.map { if (it.identity == identity) transform(it) else it })

    private fun Exception.toSaveFailure(): ActivityTemplateEditorSave.Failure {
        val message = message ?: "Unable to save the activity template"
        return ActivityTemplateEditorSave.Failure(
            message,
            message.contains("revision changed concurrently", ignoreCase = true),
        )
    }
}

internal fun DraftIdentity<*>.editorKey(): String =
    when (this) {
        is DraftIdentity.Existing -> "existing-$id"
        is DraftIdentity.New -> "new-$key"
    }

internal fun ActivityTemplateDraft.withFieldAt(
    index: Int,
    transform: (ActivityFieldDraft) -> ActivityFieldDraft,
) = copy(fields = fields.mapIndexed { current, field -> if (current == index) transform(field) else field })

internal fun ActivityTemplateEditorState.readyDraft(): ActivityTemplateDraft? =
    (load as? ActivityTemplateEditorLoad.Ready)?.draft
