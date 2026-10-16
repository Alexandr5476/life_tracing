package com.alexandr5476.lifetracing.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode

/** Child-level performScrollTo cannot discover an item that a lazy list has not composed. */
internal fun ComposeTestRule.onLazyNode(
    matcher: SemanticsMatcher,
    useUnmergedTree: Boolean = false,
): SemanticsNodeInteraction {
    onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    return onNode(matcher, useUnmergedTree).performScrollTo()
}
