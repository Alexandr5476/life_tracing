package com.alexandr5476.lifetracing.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class KeyboardInputFixtureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun hiddenImeCloseOnBackgroundWindowPreservesFocusedDialog() {
        val shown = mutableStateOf(false)
        compose.setContent {
            LifeTracingTheme {
                TextButton(onClick = { shown.value = true }, modifier = Modifier.testTag("background")) {
                    Text("Open dialog")
                }
                if (shown.value) {
                    AlertDialog(
                        onDismissRequest = { shown.value = false },
                        text = { Text("Fixture window") },
                        confirmButton = {
                            TextButton(onClick = { shown.value = false }, modifier = Modifier.testTag("confirm")) {
                                Text("Close dialog")
                            }
                        },
                    )
                }
            }
        }
        val background = compose.onNodeWithTag("background")
        keyboard.enter(background)
        keyboard.closeKeyboard(background)
        background.performSemanticsAction(SemanticsActions.OnClick)
        val confirm = compose.onNodeWithTag("confirm")
        keyboard.awaitWindowFocus(confirm)
        confirm.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(confirm)
        val backgroundView = (background.fetchSemanticsNode().root as ViewRootForTest).view
        assertTrue(compose.runOnIdle { !backgroundView.hasWindowFocus() })
        assertFalse(imeVisible(background))

        // Already hidden is a no-op even when another native window owns input.
        keyboard.closeKeyboard(background)
        confirm.assertIsFocused()
        keyboard.sendKey(confirm, KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("confirm").assertDoesNotExist()
    }

    @Test
    fun independentFixturesRetainFreshNativeFailureEvidenceWithoutRelaxingWindowAdmission() {
        val shown = mutableStateOf(false)
        var commands = 0
        compose.setContent {
            LifeTracingTheme {
                TextButton(onClick = { shown.value = true }, modifier = Modifier.testTag("background")) {
                    Text("Open dialog")
                }
                if (shown.value) {
                    AlertDialog(
                        onDismissRequest = { shown.value = false },
                        text = { Text("Foreground diagnostic fixture") },
                        confirmButton = {
                            TextButton(onClick = { commands++ }, modifier = Modifier.testTag("confirm")) {
                                Text("Command")
                            }
                        },
                    )
                }
            }
        }
        val background = compose.onNodeWithTag("background")
        keyboard.enter(background)
        background.performSemanticsAction(SemanticsActions.OnClick)
        val foreground = compose.onNodeWithTag("confirm")
        keyboard.awaitWindowFocus(foreground)
        foreground.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(foreground)

        fun verifyFailure(fixture: KeyboardInputFixture) {
            // A foreground dialog makes its attached background root deterministically inadmissible.
            val failure = assertThrows(AssertionError::class.java) { fixture.awaitWindowFocus(background) }
            assertNotNull(failure.cause)
            val report = requireNotNull(failure.message)
            assertTrue(report, report.contains("dumpsys input\nstatus="))
            assertTrue(report, report.contains("dumpsys window windows\nstatus="))
            assertTrue(report, report.contains("Full diagnostic artifact:"))
            assertFalse(report, report.contains("Native focus diagnostic capture already attempted"))
            foreground.assertIsFocused()
            assertTrue(commands == 0)
        }

        verifyFailure(keyboard)
        val independent = KeyboardInputFixture(compose)
        independent
            .apply(
                object : Statement() {
                    override fun evaluate() = verifyFailure(independent)
                },
                Description.createTestDescription(javaClass, "independent diagnostic fixture"),
            ).evaluate()
        keyboard.awaitFocus(foreground)
    }

    @Suppress("DEPRECATION") // Establish and restore the foreground display's actual input mode.
    @Test
    fun rejectedBackgroundEntryPreservesForegroundInputModeAndFailureEvidence() {
        val shown = mutableStateOf(false)
        var commands = 0
        compose.setContent {
            LifeTracingTheme {
                TextButton(onClick = { shown.value = true }, modifier = Modifier.testTag("background")) {
                    Text("Open dialog")
                }
                if (shown.value) {
                    AlertDialog(
                        onDismissRequest = { shown.value = false },
                        text = { Text("Foreground admission fixture") },
                        confirmButton = {
                            TextButton(onClick = { commands++ }, modifier = Modifier.testTag("confirm")) {
                                Text("Command")
                            }
                        },
                    )
                }
            }
        }
        val background = compose.onNodeWithTag("background")
        val backgroundView = (background.fetchSemanticsNode().root as ViewRootForTest).view
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalTouchMode = compose.runOnIdle { backgroundView.isInTouchMode }
        try {
            instrumentation.setInTouchMode(true)
            background.performSemanticsAction(SemanticsActions.OnClick)
            val foreground = compose.onNodeWithTag("confirm")
            keyboard.awaitWindowFocus(foreground)
            val foregroundView = (foreground.fetchSemanticsNode().root as ViewRootForTest).view
            assertTrue(compose.runOnIdle { foregroundView.isInTouchMode })
            val rejected = KeyboardInputFixture(compose)
            rejected
                .apply(
                    object : Statement() {
                        override fun evaluate() {
                            val failure = assertThrows(AssertionError::class.java) { rejected.enter(background) }
                            assertNotNull(failure.cause)
                            assertTrue(
                                "Rejected entry must preserve the foreground input mode",
                                compose.runOnIdle { foregroundView.isInTouchMode },
                            )
                            val report = requireNotNull(failure.message)
                            assertTrue(report, report.contains("touchMode=true"))
                            assertTrue(compose.runOnIdle { foregroundView.hasWindowFocus() })
                            assertTrue(commands == 0)
                            rejected.enter(foreground)
                            foreground.performSemanticsAction(SemanticsActions.RequestFocus)
                            rejected.awaitFocus(foreground)
                            rejected.sendKey(foreground, KeyEvent.KEYCODE_ENTER)
                            assertTrue(commands == 1)
                        }
                    },
                    Description.createTestDescription(javaClass, "rejected background entry"),
                ).evaluate()
        } finally {
            instrumentation.setInTouchMode(originalTouchMode)
        }
    }

    @Test
    fun visibleImeCloseStillDismissesThroughItsFocusedWindow() {
        val value = mutableStateOf("Unchanged")
        compose.setContent {
            LifeTracingTheme {
                OutlinedTextField(value.value, { value.value = it }, modifier = Modifier.testTag("input"))
            }
        }
        val input = compose.onNodeWithTag("input")
        keyboard.enter(input)
        input.performClick()
        compose.waitUntil(5_000) { imeVisible(input) }
        keyboard.closeKeyboard(input)
        compose.waitUntil(5_000) { !imeVisible(input) }
        input.assertTextEquals("Unchanged")
    }

    private fun imeVisible(target: SemanticsNodeInteraction): Boolean {
        val view = (target.fetchSemanticsNode().root as ViewRootForTest).view
        return compose.runOnIdle {
            requireNotNull(ViewCompat.getRootWindowInsets(view)).isVisible(WindowInsetsCompat.Type.ime())
        }
    }
}
