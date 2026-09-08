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
    private val onCommitted: () -> Unit,
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

    fun retry() = load()

    fun updateDraft(transform: (ActivityTemplateDraft) -> ActivityTemplateDraft) {
        val ready = mutableState.value.load as? ActivityTemplateEditorLoad.Ready ?: return
        if (saving.get()) return
        val draft = normalizeDraft(ready.draft, transform(ready.draft))
        mutableState.update {
            it.copy(
                load = ready.copy(draft = draft),
                save = ActivityTemplateEditorSave.Idle,
                timerTargetText = if (draft.timeTrackingMode == TimeTrackingMode.TIMER) it.timerTargetText else "",
                timerTargetError = draft.timeTrackingMode == TimeTrackingMode.TIMER && draft.timerTarget == null,
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
        val seconds = text.toLongOrNull()?.takeIf { it > 0 }
        updateDraft { it.copy(timerTarget = seconds?.let(Duration::ofSeconds)) }
        mutableState.update { it.copy(timerTargetText = text, timerTargetError = seconds == null) }
    }

    fun setNumberDefault(
        field: ActivityFieldDraft,
        text: String,
        parse: (String, Int?) -> Long?,
    ) {
        val key = field.identity.key()
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
        val precision = text.toIntOrNull()?.takeIf { it in 0..3 }
        if (text.isNotBlank() && precision == null) return
        updateDraft { draft ->
            draft.withField(field.identity) { it.copy(displayPrecision = precision) }
        }
        val key = field.identity.key()
        val invalid = !isRepresentableAtPrecision(field.defaultNumberScaled, precision)
        mutableState.update {
            it.copy(
                numberDefaultTexts =
                    if (invalid) {
                        it.numberDefaultTexts + (key to formatLauncherNumber(field.defaultNumberScaled, 3))
                    } else {
                        it.numberDefaultTexts
                    },
                invalidNumberFields =
                    if (invalid) it.invalidNumberFields + key else it.invalidNumberFields - key,
            )
        }
    }

    fun requestBack(onExit: () -> Unit) {
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
        mutableState.update { it.copy(discardConfirmationVisible = false) }
        onExit()
    }

    fun save() {
        val ready = mutableState.value.load as? ActivityTemplateEditorLoad.Ready ?: return
        if (mutableState.value.timerTargetError || mutableState.value.invalidNumberFields.isNotEmpty()) return
        if (!saving.compareAndSet(false, true)) return
        val validationFailure =
            runCatching {
                TemplateAuthoringDraftValidator.requireValid(
                    ready.draft,
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
                        createTemplate(ready.draft, TemplateLibraryPlacement(), now())
                    is ActivityTemplateEditorTarget.Existing ->
                        saveTemplate(target.id, requireNotNull(ready.expectedRevision), ready.draft, now())
                }
                if (!closed) onCommitted()
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

    private fun normalizeDraft(
        current: ActivityTemplateDraft,
        proposed: ActivityTemplateDraft,
    ): ActivityTemplateDraft {
        val before = current.fields.associateBy(ActivityFieldDraft::identity)
        return proposed.copy(
            fields =
                proposed.fields.mapIndexed { position, field ->
                    val old = before[field.identity]
                    val replacement =
                        field.identity is DraftIdentity.Existing &&
                            old != null &&
                            (old.type != field.type || old.unit != field.unit)
                    (if (replacement) field.replacement() else field).copy(position = position).normalizedMetadata()
                },
        )
    }

    private fun ActivityFieldDraft.replacement() =
        copy(
            identity = DraftIdentity.New("field-replacement-${newIdentity.incrementAndGet()}"),
            categoryOptions =
                categoryOptions.mapIndexed { index, option ->
                    option.copy(
                        identity = DraftIdentity.New("option-replacement-${newIdentity.incrementAndGet()}"),
                        position = index,
                    )
                },
            defaultCategoryOption = null,
        )

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

    private fun DraftIdentity<*>.key() =
        when (this) {
            is DraftIdentity.Existing -> "existing-$id"
            is DraftIdentity.New -> "new-$key"
        }

    private fun isRepresentableAtPrecision(
        value: Long?,
        precision: Int?,
    ): Boolean {
        if (value == null) return true
        val divisor =
            when (precision) {
                0 -> 1_000L
                1 -> 100L
                2 -> 10L
                else -> 1L
            }
        return value % divisor == 0L
    }

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
