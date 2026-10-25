package com.alexandr5476.lifetracing.ui

import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
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
    private val screenFlags = mutableMapOf<View, Boolean>()

    override fun before() = wakeDisplay()

    private fun wakeDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (!context.getSystemService(PowerManager::class.java).isInteractive) {
            shell("input keyevent KEYCODE_WAKEUP")
        }
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            shell("wm dismiss-keyguard")
        }
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.reader().readText()
        }
    }

    private fun attachedView(target: SemanticsNodeInteraction): View {
        wakeDisplay()
        val view = (target.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle {
            val root = view.rootView
            screenFlags.putIfAbsent(root, root.keepScreenOn)
            root.keepScreenOn = true
        }
        return view
    }

    fun enter(target: SemanticsNodeInteraction = compose.onRoot()) {
        val view = attachedView(target)
        compose.runOnIdle {
            if (previousTouchMode == null) previousTouchMode = view.isInTouchMode
            assertTrue("The attached Compose view must accept keyboard focus", view.requestFocusFromTouch())
        }
        compose.waitUntil(timeoutMillis = 5_000) { compose.runOnIdle { !view.isInTouchMode } }
        awaitWindowFocus(target)
    }

    /** Admits the native root and establishes keyboard mode for each window of an active fixture. */
    fun awaitWindowFocus(target: SemanticsNodeInteraction) {
        keyboardView(target)
    }

    private fun keyboardView(target: SemanticsNodeInteraction): View {
        val view = attachedView(target)
        try {
            onView(isRoot()).inRoot(withDecorView(sameInstance(view.rootView))).check { root, failure ->
                failure?.let { throw it }
                assertTrue("The intended native root must own window focus", root?.hasWindowFocus() == true)
            }
        } catch (failure: RuntimeException) {
            val diagnostics = runCatching { windowDiagnostics(view) }.getOrElse { it.toString() }
            throw AssertionError("Native keyboard window admission failed. $diagnostics", failure)
        }
        if (previousTouchMode != null && compose.runOnIdle { view.isInTouchMode }) {
            compose.runOnIdle {
                assertTrue("The admitted Compose window must accept keyboard focus", view.requestFocusFromTouch())
            }
            compose.waitUntil(timeoutMillis = 5_000) { compose.runOnIdle { !view.isInTouchMode } }
        }
        return view
    }

    internal fun windowDiagnostics(view: View): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val native =
            compose.runOnIdle {
                "attached=${view.isAttachedToWindow}, windowFocused=${view.hasWindowFocus()}, " +
                    "touchMode=${view.isInTouchMode}, focusedView=${view.rootView.findFocus()}"
            }
        val windows =
            shell("dumpsys window")
                .lineSequence()
                .filter { "mCurrentFocus=" in it || "mFocusedApp=" in it }
                .joinToString("\n")
        return "interactive=$interactive, keyguardLocked=$locked, $native\n$windows"
    }

    /** Waits for the production focus effect, never requests the expected control's focus. */
    fun awaitFocus(target: SemanticsNodeInteraction) {
        awaitWindowFocus(target)
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                val semantics = target.fetchSemanticsNode().config
                semantics.contains(SemanticsProperties.Focused) && semantics[SemanticsProperties.Focused]
            }
        } catch (failure: ComposeTimeoutException) {
            val view = (target.fetchSemanticsNode().root as ViewRootForTest).view
            val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            throw AssertionError(
                "Production focus unavailable. ${windowDiagnostics(view)}\nTarget: ${target.printToString()}\n" +
                    "Focused: ${focused.joinToString { it.config.toString() }}",
                failure,
            )
        }
        target.assertIsFocused()
    }

    /** Hides the IME in this control's window, including when a dialog owns native focus. */
    fun closeKeyboard(target: SemanticsNodeInteraction) {
        val view = keyboardView(target)
        onView(isRoot()).inRoot(withDecorView(sameInstance(view.rootView))).perform(closeSoftKeyboard())
    }

    /** Routes a native key through the target's attached window, rather than global input focus. */
    fun sendKey(
        target: SemanticsNodeInteraction,
        keyCode: Int,
    ) {
        val view = keyboardView(target)
        onView(isRoot()).inRoot(withDecorView(sameInstance(view.rootView))).perform(pressKey(keyCode))
    }

    @Suppress("DEPRECATION") // Restore the platform mode even if the Activity rule has already closed.
    override fun after() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            screenFlags.forEach { (view, previous) -> view.keepScreenOn = previous }
            screenFlags.clear()
        }
        previousTouchMode?.let { InstrumentationRegistry.getInstrumentation().setInTouchMode(it) }
        previousTouchMode = null
    }
}
