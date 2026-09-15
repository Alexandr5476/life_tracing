package com.alexandr5476.lifetracing.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivityExecutionValueOverride
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber

internal enum class PlanExecutionOrigin {
    DAILY,
    PLAN,
}

internal enum class PlanExecutionRouteExitDecision {
    BACK,
    WAIT_FOR_COMMIT,
    DELIVER_COMMIT,
}

internal fun PlanExecutionController.arbitrateRouteExit(): PlanExecutionRouteExitDecision =
    when (state.value.command) {
        is PlanExecutionCommandState.Committing -> PlanExecutionRouteExitDecision.WAIT_FOR_COMMIT
        is PlanExecutionCommandState.Committed,
        is PlanExecutionCommandState.CommittedCoordinationFailure,
        -> PlanExecutionRouteExitDecision.DELIVER_COMMIT
        else -> PlanExecutionRouteExitDecision.BACK
    }

internal class PlanExecutionRouteExitPolicy {
    private var delivered = false

    fun requestExit(
        controller: PlanExecutionController,
        onBack: () -> Unit,
        onCommitted: (PlanExecutionCommit) -> Unit,
    ) {
        when (controller.arbitrateRouteExit()) {
            PlanExecutionRouteExitDecision.BACK -> onBack()
            PlanExecutionRouteExitDecision.WAIT_FOR_COMMIT -> Unit
            PlanExecutionRouteExitDecision.DELIVER_COMMIT ->
                controller.state.value.command
                    .commitOrNull()
                    ?.let { deliver(it, onCommitted) }
        }
    }

    fun onCommand(
        command: PlanExecutionCommandState,
        onCommitted: (PlanExecutionCommit) -> Unit,
    ) {
        command.commitOrNull()?.let { deliver(it, onCommitted) }
    }

    private fun deliver(
        result: PlanExecutionCommit,
        onCommitted: (PlanExecutionCommit) -> Unit,
    ) {
        if (delivered) return
        delivered = true
        onCommitted(result)
    }
}

private fun PlanExecutionCommandState.commitOrNull(): PlanExecutionCommit? =
    when (this) {
        is PlanExecutionCommandState.Committed -> result
        is PlanExecutionCommandState.CommittedCoordinationFailure -> result
        else -> null
    }

internal class PlanExecutionRouteSessionOwner : ViewModel() {
    private var session: PlanExecutionRouteSession? = null

    val activeSession: PlanExecutionRouteSession?
        get() = session

    fun acquire(
        expectedIdentity: PlanActionIdentity,
        origin: PlanExecutionOrigin,
        createController: () -> PlanExecutionController,
    ): PlanExecutionRouteSession? {
        session?.let {
            return it.takeIf { retained ->
                retained.expectedIdentity == expectedIdentity && retained.origin == origin
            }
        }
        return PlanExecutionRouteSession(
            expectedIdentity,
            origin,
            createController(),
        ).also { session = it }
    }

    fun release(expected: PlanExecutionRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class PlanExecutionRouteSession(
    val expectedIdentity: PlanActionIdentity,
    val origin: PlanExecutionOrigin,
    val controller: PlanExecutionController,
    val exitPolicy: PlanExecutionRouteExitPolicy = PlanExecutionRouteExitPolicy(),
) {
    var quickDraft by mutableStateOf<PlanQuickCompletionDraft?>(null)
        private set

    fun prepareQuickDraft(snapshot: ActivityConfigSnapshot) {
        if (quickDraft?.snapshotId == snapshot.id) return
        quickDraft = PlanQuickCompletionDraft.from(snapshot)
    }

    fun editNumber(
        field: ActivitySnapshotField,
        text: String,
    ) {
        require(field.type == CustomFieldType.NUMBER)
        val parsed = parseLauncherNumber(text, field.displayPrecision)
        quickDraft =
            requireNotNull(quickDraft).edit(
                field.id,
                if (text.isBlank()) null else parsed?.let { NumberExecutionValue(field.id, it) },
                numberText = text,
                valid = text.isBlank() || parsed != null,
            )
    }

    fun editText(
        field: ActivitySnapshotField,
        text: String,
    ) {
        require(field.type == CustomFieldType.TEXT)
        quickDraft =
            requireNotNull(quickDraft).edit(
                field.id,
                text
                    .takeIf(String::isNotBlank)
                    ?.let { TextExecutionValue(field.id, it) },
            )
    }

    fun editCategory(
        field: ActivitySnapshotField,
        optionId: ActivitySnapshotCategoryOptionId,
    ) {
        require(field.type == CustomFieldType.CATEGORY && field.categoryOptions.any { it.id == optionId })
        quickDraft = requireNotNull(quickDraft).edit(field.id, CategoryExecutionValue(field.id, optionId))
    }

    fun markMissing(field: ActivitySnapshotField) {
        quickDraft = requireNotNull(quickDraft).edit(field.id, null, numberText = "")
    }
}

internal data class PlanQuickCompletionDraft(
    val snapshotId: ActivitySnapshotId,
    val values: Map<ActivitySnapshotFieldId, ActivityExecutionFieldValue?>,
    val numberTexts: Map<ActivitySnapshotFieldId, String>,
    val changed: Set<ActivitySnapshotFieldId> = emptySet(),
    val invalid: Set<ActivitySnapshotFieldId> = emptySet(),
) {
    fun overrides(fields: List<ActivitySnapshotField>): List<ActivityExecutionValueOverride> =
        fields.filter { it.id in changed }.map { ActivityExecutionValueOverride(it.id, values[it.id]) }

    fun edit(
        id: ActivitySnapshotFieldId,
        value: ActivityExecutionFieldValue?,
        numberText: String? = null,
        valid: Boolean = true,
    ) = copy(
        values = values + (id to value),
        numberTexts = if (numberText == null) numberTexts else numberTexts + (id to numberText),
        changed = changed + id,
        invalid = if (valid) invalid - id else invalid + id,
    )

    companion object {
        fun from(snapshot: ActivityConfigSnapshot): PlanQuickCompletionDraft {
            val values = snapshot.fields.associate { it.id to it.defaultValue() }
            return PlanQuickCompletionDraft(
                snapshot.id,
                values,
                snapshot.fields.filter { it.type == CustomFieldType.NUMBER }.associate {
                    val number = values[it.id] as? NumberExecutionValue
                    it.id to formatLauncherNumber(number?.scaledValue, it.displayPrecision)
                },
            )
        }
    }
}

private fun ActivitySnapshotField.defaultValue(): ActivityExecutionFieldValue? =
    when (type) {
        CustomFieldType.NUMBER -> defaultNumberScaled?.let { NumberExecutionValue(id, it) }
        CustomFieldType.CATEGORY -> defaultCategoryOptionId?.let { CategoryExecutionValue(id, it) }
        CustomFieldType.TEXT -> defaultText?.let { TextExecutionValue(id, it) }
    }
