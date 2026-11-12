package com.alexandr5476.lifetracing.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScrollableTestNodesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun missingLazyTargetRetainsOriginalCauseAndFocusedViewportWithoutInvokingActions() {
        val value = mutableStateOf("Evidence editor")
        var invocations = 0
        compose.setContent {
            LifeTracingTheme {
                LazyColumn {
                    item {
                        OutlinedTextField(value.value, { value.value = it }, Modifier.testTag("input"))
                    }
                    item {
                        TextButton(onClick = { invocations++ }) { Text("Existing action") }
                    }
                }
            }
        }
        val input = compose.onNodeWithTag("input")
        keyboard.enter(input)
        input.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(input)
        val directory = nativeDiagnosticArtifactDirectory()
        val before =
            directory
                .listFiles()
                .orEmpty()
                .map { it.name }
                .toSet()

        val failure =
            assertThrows(AssertionError::class.java) {
                compose.onLazyNode(hasText("Missing Done") and hasClickAction())
            }

        assertTrue(failure.cause is AssertionError)
        val originalMessage = failure.cause!!.message
        assertTrue(originalMessage?.contains("Missing Done") == true)
        val artifact = directory.listFiles().orEmpty().single { it.name !in before && it.extension == "txt" }
        val evidence = artifact.readText(Charsets.UTF_8)
        assertTrue(evidence.contains("Lazy target:"))
        assertTrue(evidence.contains("Missing Done"))
        assertTrue(evidence.contains("windowFocused=true"))
        assertTrue(evidence.contains("imeVisible="))
        assertTrue(evidence.contains("Focused"))
        assertTrue(evidence.contains("Evidence editor"))
        input.assertIsFocused().assertTextEquals("Evidence editor")
        assertEquals(0, invocations)
    }
}
