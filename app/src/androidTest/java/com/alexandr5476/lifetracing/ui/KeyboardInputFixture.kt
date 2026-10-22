package com.alexandr5476.lifetracing.ui

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource

/** Enters native keyboard mode after setContent has attached the Compose owner. */
class KeyboardInputFixture(
    private val compose: ComposeTestRule,
) : ExternalResource() {
    private var previousTouchMode: Boolean? = null

    fun enter(target: SemanticsNodeInteraction = compose.onRoot()) {
        val view = (target.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle {
            if (previousTouchMode == null) previousTouchMode = view.isInTouchMode
            assertTrue("The attached Compose view must accept keyboard focus", view.requestFocusFromTouch())
        }
        compose.waitUntil(timeoutMillis = 5_000) { compose.runOnIdle { !view.isInTouchMode } }
    }

    @Suppress("DEPRECATION") // Restore the platform mode even if the Activity rule has already closed.
    override fun after() {
        previousTouchMode?.let { InstrumentationRegistry.getInstrumentation().setInTouchMode(it) }
        previousTouchMode = null
    }
}
