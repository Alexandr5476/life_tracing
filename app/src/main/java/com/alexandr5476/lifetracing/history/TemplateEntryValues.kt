package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber

internal enum class TemplateEntryValueIssue { MISSING_DRAFT, INVALID_NUMBER, INVALID_CATEGORY }

internal sealed interface TemplateEntryValues {
    data class Valid(
        val values: List<ActivityEntryValueOverride>,
    ) : TemplateEntryValues

    data class Invalid(
        val issue: TemplateEntryValueIssue,
    ) : TemplateEntryValues
}

internal fun ActivityTemplate.initialEntryValues(): Map<ActivityTemplateFieldId, ManualEntryFieldDraft> =
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
    }

@Suppress("ReturnCount")
internal fun ActivityTemplate.proposeEntryValues(
    drafts: Map<ActivityTemplateFieldId, ManualEntryFieldDraft>,
): TemplateEntryValues {
    val values = mutableListOf<ActivityEntryValueOverride>()
    for (field in fields.filter { it.deletedAt == null }) {
        val draft = drafts[field.id] ?: return TemplateEntryValues.Invalid(TemplateEntryValueIssue.MISSING_DRAFT)
        val value =
            when {
                draft.missing -> ActivityEntryValue.Missing
                field.type == CustomFieldType.NUMBER -> {
                    val number =
                        parseLauncherNumber(draft.numberText, field.displayPrecision)
                            ?: return TemplateEntryValues.Invalid(TemplateEntryValueIssue.INVALID_NUMBER)
                    ActivityEntryValue.Number(number)
                }
                field.type == CustomFieldType.CATEGORY -> {
                    val selected =
                        draft.selectedOptionId?.takeIf { id ->
                            field.categoryOptions.any { it.id == id && !it.isArchived }
                        } ?: return TemplateEntryValues.Invalid(TemplateEntryValueIssue.INVALID_CATEGORY)
                    ActivityEntryValue.Category(ActivityEntryOptionReference.Template(selected))
                }
                else -> ActivityEntryValue.Text(draft.text)
            }
        values += ActivityEntryValueOverride(ActivityEntryFieldReference.Template(field.id), value)
    }
    return TemplateEntryValues.Valid(values)
}
