package com.alexandr5476.lifetracing.ui

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.sameInstance
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

    /** Observes native root admission without assigning focus to a semantic control. */
    fun awaitWindowFocus(target: SemanticsNodeInteraction) {
        val view = (target.fetchSemanticsNode().root as ViewRootForTest).view
        onView(isRoot()).inRoot(withDecorView(sameInstance(view.rootView))).check { root, failure ->
            failure?.let { throw it }
            assertTrue("The intended native root must own window focus", root?.hasWindowFocus() == true)
        }
    }

    /** Waits for the production focus effect, never requests the expected control's focus. */
    fun awaitFocus(target: SemanticsNodeInteraction) {
        awaitWindowFocus(target)
        compose.waitUntil(timeoutMillis = 5_000) {
            val semantics = target.fetchSemanticsNode().config
            semantics.contains(SemanticsProperties.Focused) && semantics[SemanticsProperties.Focused]
        }
        target.assertIsFocused()
    }

    /** Routes a native key through the target's attached window, rather than global input focus. */
    fun sendKey(
        target: SemanticsNodeInteraction,
        keyCode: Int,
    ) {
        val view = (target.fetchSemanticsNode().root as ViewRootForTest).view
        onView(isRoot()).inRoot(withDecorView(sameInstance(view.rootView))).perform(pressKey(keyCode))
    }

    @Suppress("DEPRECATION") // Restore the platform mode even if the Activity rule has already closed.
    override fun after() {
        previousTouchMode?.let { InstrumentationRegistry.getInstrumentation().setInTouchMode(it) }
        previousTouchMode = null
    }
}
