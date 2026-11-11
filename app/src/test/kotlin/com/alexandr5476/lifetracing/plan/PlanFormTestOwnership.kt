package com.alexandr5476.lifetracing.plan

import java.util.UUID

// Tests with no current form still send an explicitly unowned event, never a production fallback.
internal val PlanController.formId: UUID get() = state.value.form?.formId ?: UUID.randomUUID()
internal val PlanController.formRevision: Long get() = state.value.form?.inputRevision ?: -1L
