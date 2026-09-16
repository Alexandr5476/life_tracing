package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.PlanEntry

/** The executable snapshot, not mutable source metadata, is Plan provenance authority. */
internal fun PlanEntry.requireSnapshotProvenance(
    snapshotSourceTemplateId: String?,
    snapshotSourceRevision: Long?,
    snapshotType: String,
) {
    val planSourceTemplateId = sourceActivityTemplateId?.value ?: sourceSequenceTemplateId?.value
    require(snapshotSourceTemplateId == planSourceTemplateId) {
        "Plan and $snapshotType source mismatch"
    }
    require(snapshotSourceRevision == sourceRevision) {
        "Plan and $snapshotType revision mismatch"
    }
}
