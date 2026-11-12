package com.alexandr5476.lifetracing.ui

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Child-level performScrollTo cannot discover an item that a lazy list has not composed. */
internal fun ComposeTestRule.onLazyNode(
    matcher: SemanticsMatcher,
    useUnmergedTree: Boolean = false,
): SemanticsNodeInteraction {
    val list = onNode(hasScrollToIndexAction())
    try {
        list.performScrollToNode(matcher)
        return onNode(matcher, useUnmergedTree).performScrollTo()
    } catch (failure: AssertionError) {
        // Retain evidence before ActivityScenario teardown removes the failed viewport.
        // Never retry scrolling or invoke an action while collecting this report.
        val window =
            runCatching {
                val view = (list.fetchSemanticsNode().root as ViewRootForTest).view
                runOnIdle {
                    "attached=${view.isAttachedToWindow}, windowFocused=${view.hasWindowFocus()}, " +
                        "touchMode=${view.isInTouchMode}, " +
                        "imeVisible=${ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())}"
                }
            }.getOrElse { "Window capture failed: $it" }
        val tree =
            runCatching {
                onAllNodes(isRoot(), useUnmergedTree).printToString(maxDepth = Int.MAX_VALUE)
            }.getOrElse { "Semantics capture failed: $it" }
        val report = nativeFailureReport("Lazy target: ${matcher.description}\n$window\n$tree")
        throw AssertionError("Lazy target reveal failed. $report", failure)
    }
}
