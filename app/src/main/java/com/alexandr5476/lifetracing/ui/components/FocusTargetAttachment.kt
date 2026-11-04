package com.alexandr5476.lifetracing.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/** Reports detachment before focus-owner invalidation at the end of UI changes. */
internal fun Modifier.onFocusTargetAttachmentChanged(onChanged: (Boolean) -> Unit): Modifier =
    then(FocusTargetAttachmentElement(onChanged))

private data class FocusTargetAttachmentElement(
    val onChanged: (Boolean) -> Unit,
) : ModifierNodeElement<FocusTargetAttachmentNode>() {
    override fun create() = FocusTargetAttachmentNode(onChanged)

    override fun update(node: FocusTargetAttachmentNode) {
        node.onChanged = onChanged
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onFocusTargetAttachmentChanged"
    }
}

private class FocusTargetAttachmentNode(
    var onChanged: (Boolean) -> Unit,
) : Modifier.Node() {
    override fun onAttach() {
        onChanged(true)
    }

    override fun onDetach() {
        onChanged(false)
    }
}
