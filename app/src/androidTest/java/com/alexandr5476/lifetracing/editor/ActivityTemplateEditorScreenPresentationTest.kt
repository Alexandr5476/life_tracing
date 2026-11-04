@file:Suppress("LongMethod", "MagicNumber", "LargeClass", "TooManyFunctions")

package com.alexandr5476.lifetracing.editor

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ActivityTemplateEditorScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(composeTestRule)

    @Test
    fun cleanToolbarBackExitsAndFocusedEditorHasNoPrimaryNavigation() {
        var exits = 0
        val controller = controller()
        composeTestRule.setContent {
            LifeTracingTheme { ActivityTemplateEditorRoute(controller) { exits++ } }
        }
        awaitReady(controller)

        composeTestRule.onNodeWithText(text(R.string.activity_editor_back)).performClick()

        assertEquals(1, exits)
        composeTestRule.onNodeWithText(text(R.string.daily_start_activity)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.daily_library)).assertDoesNotExist()
        controller.close()
    }

    @Test
    fun dirtyToolbarAndNativeBackShareDiscardConfirmationAndCancelKeepsDraft() {
        var exits = 0
        val controller = controller()
        composeTestRule.setContent {
            LifeTracingTheme { ActivityTemplateEditorRoute(controller) { exits++ } }
        }
        awaitReady(controller)
        composeTestRule.runOnIdle { controller.updateDraft { it.copy(name = "Draft") } }

        composeTestRule.onNodeWithText(text(R.string.activity_editor_back)).performClick()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_discard_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_continue_editing)).performClick()
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.onNodeWithText(text(R.string.activity_editor_discard_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_continue_editing)).performClick()

        assertEquals(0, exits)
        assertEquals(
            "Draft",
            controller.state.value
                .readyDraft()
                ?.name,
        )
        controller.close()
    }

    @Test
    fun selectedCategoryDefaultCanBeClearedWithoutRemovingItsOption() {
        var saved: ActivityTemplateDraft? = null
        val draft =
            ActivityTemplateDraft(
                "Rating",
                null,
                TimeTrackingMode.NO_LIVE_TRACKING,
                null,
                fields =
                    listOf(
                        ActivityFieldDraft(
                            DraftIdentity.New("difficulty"),
                            0,
                            "Difficulty",
                            CustomFieldType.CATEGORY,
                            defaultCategoryOption = DraftIdentity.New("easy"),
                            categoryOptions =
                                listOf(ActivityCategoryOptionDraft(DraftIdentity.New("easy"), 0, "Easy")),
                        ),
                    ),
            )
        val controller = controller(draft) { saved = it }
        composeTestRule.setContent { LifeTracingTheme { ActivityTemplateEditorRoute(controller) {} } }
        awaitReady(controller)

        composeTestRule.onNodeWithText(text(R.string.activity_editor_clear_default)).performScrollTo().performClick()
        composeTestRule.runOnIdle {
            val field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            assertNull(field.defaultCategoryOption)
            assertEquals(listOf("Easy"), field.categoryOptions.map(ActivityCategoryOptionDraft::label))
        }
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule.waitUntil { saved != null }
        assertNull(saved!!.fields.single().defaultCategoryOption)
        controller.close()
    }

    @Test
    fun ioWriterCompletionReturnsToComposeOnceOnTheMainThread() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller =
            ActivityTemplateEditorController(
                scope,
                ActivityTemplateEditorTarget.New,
                { null },
                { draft, _, _ -> template(draft) },
                { _, _, draft, _ -> template(draft) },
                Instant::now,
            )
        var callbacks = 0
        var callbackLooper: Looper? = null
        composeTestRule.setContent {
            LifeTracingTheme {
                ActivityTemplateEditorRoute(
                    controller,
                    onBack = {},
                    onCommitted = {
                        callbacks++
                        callbackLooper = Looper.myLooper()
                    },
                )
            }
        }
        awaitReady(controller)

        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule.waitUntil { callbacks == 1 }
        composeTestRule.waitForIdle()

        assertEquals(1, callbacks)
        assertEquals(Looper.getMainLooper(), callbackLooper)
        controller.close()
        scope.cancel()
    }

    @Test
    fun englishCompactChoicesStayInsideBounds() = compactChoicesStayInsideBounds(Locale.ENGLISH)

    @Test
    fun russianCompactChoicesStayInsideBounds() = compactChoicesStayInsideBounds(Locale.forLanguageTag("ru"))

    @Test
    fun newContextHeadingsAndNativeEditableTextDoNotWriteOnFocusOrTab() {
        var writes = 0
        val controller = controller(save = { writes++ })
        present(controller)
        composeTestRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(R.string.activity_editor_pristine),
                ),
            ).assertExists()
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_new_context),
            ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        listOf(R.string.activity_editor_tracking, R.string.activity_editor_fields).forEach {
            composeTestRule.onNodeWithText(text(it)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
        val unmergedInputs =
            composeTestRule
                .onAllNodes(
                    hasSetTextAction(),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertEquals(2, unmergedInputs.size)
        assertTrue(unmergedInputs.all { it.config.contains(SemanticsProperties.EditableText) })
        input(R.string.activity_editor_name).performSemanticsAction(SemanticsActions.RequestFocus).awaitKeyboardFocus()
        input(R.string.activity_editor_name).performTextReplacement("Keyboard template")
        input(R.string.activity_editor_name).performKeyInput { pressKey(Key.Tab) }
        assertEquals(
            "Tab must navigate without editing Name",
            "Keyboard template",
            controller.state.value
                .readyDraft()!!
                .name,
        )
        input(R.string.activity_editor_short_comment).awaitKeyboardFocus().performTextReplacement("Draft comment")
        input(R.string.activity_editor_short_comment).performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        input(R.string.activity_editor_name).awaitKeyboardFocus()
        input(R.string.activity_editor_name).performKeyInput { pressKey(Key.Tab) }
        input(R.string.activity_editor_short_comment).awaitKeyboardFocus()
        assertEquals(
            "Keyboard template",
            controller.state.value
                .readyDraft()!!
                .name,
        )
        assertEquals(
            "Draft comment",
            controller.state.value
                .readyDraft()!!
                .shortComment,
        )
        composeTestRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(R.string.activity_editor_modified),
                ),
            ).assertExists()
        assertEquals(0, writes)
        controller.close()
    }

    @Test
    fun existingContextLoadingFailureKeyboardRetryAndReadyKeepExactTarget() {
        val first = CompletableDeferred<Unit>()
        var reads = 0
        val id = ActivityTemplateId("exact-existing")
        val controller =
            advancedController(ActivityTemplateEditorTarget.Existing(id), load = { requested ->
                assertEquals(id, requested)
                if (++reads == 1) {
                    first.await()
                    error("read failed")
                }
                template(ActivityTemplateDraft("Existing", null, TimeTrackingMode.STOPWATCH, null)).copy(id = id)
            })
        composeTestRule.setContent { LifeTracingTheme { ActivityTemplateEditorRoute(controller) {} } }
        keyboard.enter()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_existing_context)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_loading)).assertIsDisplayed()
        composeTestRule.runOnIdle { first.complete(Unit) }
        composeTestRule.waitUntil { controller.state.value.load is ActivityTemplateEditorLoad.Failure }
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_retry),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        awaitReady(controller)
        composeTestRule.waitForIdle()
        input(R.string.activity_editor_name).awaitKeyboardFocus()
        assertEquals(ActivityTemplateEditorTarget.Existing(id), controller.target)
        assertEquals(2, reads)
        controller.close()
    }

    @Test
    fun nativeTrackingSelectionAndTimerErrorsDispatchExactDraftTransitions() {
        var writes = 0
        val controller = controller(save = { writes++ })
        present(controller)
        composeTestRule.onNodeWithText(text(R.string.activity_editor_stopwatch)).assertIsSelected()
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_timer),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText(text(R.string.activity_editor_timer)).assertIsSelected()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_stopwatch)).assertIsNotSelected()
        listOf("", "0", "-1", "letters", "9223372036854775808").forEach { invalid ->
            input(R.string.activity_editor_timer_seconds)
                .performScrollTo()
                .performTextReplacement(invalid)
            input(
                R.string.activity_editor_timer_seconds,
            ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().assertIsNotEnabled()
            assertNull(
                controller.state.value
                    .readyDraft()!!
                    .timerTarget,
            )
        }
        input(R.string.activity_editor_timer_seconds).performScrollTo().performTextReplacement("90")
        assertEquals(
            java.time.Duration.ofSeconds(90),
            controller.state.value
                .readyDraft()!!
                .timerTarget,
        )
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().assertIsEnabled()
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_none),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText(text(R.string.activity_editor_none)).assertIsSelected()
        assertEquals(
            TimeTrackingMode.NO_LIVE_TRACKING,
            controller.state.value
                .readyDraft()!!
                .timeTrackingMode,
        )
        assertNull(
            controller.state.value
                .readyDraft()!!
                .timerTarget,
        )
        composeTestRule.onNodeWithText(text(R.string.activity_editor_timer)).performScrollTo().performClick()
        assertEquals("", controller.state.value.timerTargetText)
        assertTrue(controller.state.value.timerTargetError)
        assertEquals(0, writes)
        controller.close()
    }

    @Test
    fun numberControlsExposeNativeErrorsPrecisionUnitsAndMissingVersusZero() {
        val field =
            ActivityFieldDraft(DraftIdentity.New("number"), 0, "Same", CustomFieldType.NUMBER, displayPrecision = 2)
        val controller = controller(fieldsDraft(field))
        present(controller)
        val context = fieldContext(field, 1)
        control(R.string.activity_editor_unit, context).performScrollTo().performTextReplacement("kg")
        control(R.string.activity_editor_precision, context).performScrollTo().performTextReplacement("2")
        val default = control(R.string.activity_editor_default, context)
        for ((value, expected) in listOf("" to null, "0" to 0L, "-1.25" to -1250L, "2.5" to 2500L)) {
            default.performScrollTo().performTextReplacement(value)
            assertEquals(
                expected,
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .defaultNumberScaled,
            )
        }
        default.performTextReplacement("bad")
        default.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().assertIsNotEnabled()
        control(R.string.activity_editor_precision, context).performScrollTo().performTextReplacement("9")
        assertEquals(
            2,
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
                .displayPrecision,
        )
        assertEquals(
            "kg",
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
                .unit,
        )
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_precision_guidance),
            ).performScrollTo()
            .assertIsDisplayed()
        controller.close()
    }

    @Test
    fun duplicateFieldControlsAndMainValueDispatchToStableIdentities() {
        val first = ActivityFieldDraft(DraftIdentity.New("one"), 0, "Same", CustomFieldType.NUMBER)
        val second = first.copy(identity = DraftIdentity.New("two"), position = 1)
        val textField = first.copy(identity = DraftIdentity.New("three"), position = 2, type = CustomFieldType.TEXT)
        val controller = controller(fieldsDraft(first, second, textField))
        present(controller)
        control(
            R.string.activity_editor_main_value,
            fieldContext(first, 1),
        ).performScrollTo().assertIsOff().performClick()
        control(R.string.activity_editor_main_value, fieldContext(first, 1)).assertIsOn()
        control(
            R.string.activity_editor_main_value,
            fieldContext(second, 2),
        ).performScrollTo().performClick().assertIsOn()
        control(R.string.activity_editor_main_value, fieldContext(first, 1)).assertIsOff()
        assertEquals(
            listOf(false, true, false),
            controller.state.value
                .readyDraft()!!
                .fields
                .map { it.isMainValue },
        )
        control(
            R.string.activity_editor_default,
            fieldContext(textField, 3),
        ).performScrollTo().performTextReplacement("Only text")
        assertEquals(
            "Only text",
            controller.state.value
                .readyDraft()!!
                .fields[2]
                .defaultText,
        )
        control(
            R.string.activity_editor_field_name,
            fieldContext(second, 2),
        ).performScrollTo().performTextReplacement("Second")
        assertEquals(
            listOf("Same", "Second", "Same"),
            controller.state.value
                .readyDraft()!!
                .fields
                .map { it.name },
        )
        controller.close()
    }

    @Test
    fun duplicateCategoryOptionsRenameSelectClearAndRemoveExactIdentities() {
        val first = ActivityCategoryOptionDraft(DraftIdentity.New("option-one"), 0, "Same")
        val second = first.copy(identity = DraftIdentity.New("option-two"), position = 1)
        val field =
            ActivityFieldDraft(
                DraftIdentity.New("category"),
                0,
                "Same",
                CustomFieldType.CATEGORY,
                categoryOptions = listOf(first, second),
            )
        val controller = controller(fieldsDraft(field))
        present(controller)
        val context = fieldContext(field, 1)
        val firstContext = optionContext(context, 1, "Same")
        val secondContext = optionContext(context, 2, "Same")
        control(
            R.string.activity_editor_make_default,
            secondContext,
        ).performScrollTo().assertIsNotSelected().performClick()
        control(R.string.activity_editor_clear_default, secondContext).assertIsSelected()
        assertEquals(
            second.identity,
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
                .defaultCategoryOption,
        )
        control(R.string.activity_editor_clear_default, secondContext).performClick()
        assertNull(
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
                .defaultCategoryOption,
        )
        control(R.string.activity_editor_option, secondContext).performScrollTo().performTextReplacement("Renamed")
        control(
            R.string.activity_editor_make_default,
            optionContext(context, 2, "Renamed"),
        ).performScrollTo().performClick()
        control(R.string.activity_editor_remove, firstContext).performScrollTo().performClick()
        val current =
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
        assertEquals(listOf(second.identity), current.categoryOptions.map { it.identity })
        assertEquals(second.identity, current.defaultCategoryOption)
        control(R.string.activity_editor_remove, optionContext(context, 1, "Renamed")).performScrollTo().performClick()
        assertNull(
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
                .defaultCategoryOption,
        )
        control(R.string.activity_editor_add_option, context).performScrollTo().performKeyInput { pressKey(Key.Tab) }
        control(R.string.activity_editor_add_option, context).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(
            1,
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
                .categoryOptions.size,
        )
        controller.close()
    }

    @Test
    fun removedFieldAndOptionNativeCallbacksCannotMutateTheirSuccessors() {
        val option = ActivityCategoryOptionDraft(DraftIdentity.New("old-option"), 0, "Same")
        val category =
            ActivityFieldDraft(
                DraftIdentity.New("old-field"),
                0,
                "Same",
                CustomFieldType.CATEGORY,
                categoryOptions = listOf(option),
            )
        val survivor =
            category.copy(
                identity = DraftIdentity.New("survivor"),
                position = 1,
                categoryOptions = listOf(option.copy(identity = DraftIdentity.New("survivor-option"))),
            )
        val controller = controller(fieldsDraft(category, survivor))
        present(controller)
        val context = fieldContext(category, 1)
        val label =
            control(
                R.string.activity_editor_option,
                optionContext(context, 1, "Same"),
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val select =
            control(
                R.string.activity_editor_make_default,
                optionContext(context, 1, "Same"),
            ).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val name =
            control(
                R.string.activity_editor_field_name,
                context,
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        control(R.string.activity_editor_remove, optionContext(context, 1, "Same")).performScrollTo().performClick()
        composeTestRule.runOnIdle {
            label(AnnotatedString("Stale"))
            select()
        }
        assertNull(
            controller.state.value
                .readyDraft()!!
                .fields
                .first()
                .defaultCategoryOption,
        )
        control(R.string.activity_editor_remove, context).performScrollTo().performClick()
        val before = controller.state.value.readyDraft()
        composeTestRule.runOnIdle {
            name(AnnotatedString("Wrong field"))
            label(AnnotatedString("Wrong option"))
            select()
        }
        assertEquals(before, controller.state.value.readyDraft())
        assertEquals(survivor.identity, before!!.fields.single().identity)
        controller.close()
    }

    @Test
    fun stableSurvivingFieldFocusAndNativeAddTypeChangeRemainReachable() {
        val first = ActivityFieldDraft(DraftIdentity.New("first"), 0, "Same", CustomFieldType.TEXT)
        val second = first.copy(identity = DraftIdentity.New("second"), position = 1)
        val controller = controller(fieldsDraft(first, second))
        present(controller)
        val secondName = control(R.string.activity_editor_field_name, fieldContext(second, 2))
        secondName.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus).awaitKeyboardFocus()
        // A semantics action removes another row without moving native keyboard focus.
        control(
            R.string.activity_editor_remove,
            fieldContext(first, 1),
        ).performSemanticsAction(SemanticsActions.OnClick)
        control(R.string.activity_editor_field_name, fieldContext(second, 1)).awaitKeyboardFocus()
        val change =
            composeTestRule.activity.getString(
                R.string.activity_editor_change_type_context,
                text(R.string.activity_editor_number),
                fieldContext(second, 1),
            )
        composeTestRule
            .onNodeWithContentDescription(
                change,
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(
            CustomFieldType.NUMBER,
            controller.state.value
                .readyDraft()!!
                .fields
                .single()
                .type,
        )
        val add =
            composeTestRule.onNodeWithContentDescription(
                composeTestRule.activity.getString(
                    R.string.activity_editor_add_field_context,
                    text(R.string.activity_editor_text),
                ),
            )
        add
            .performScrollTo()
            .performSemanticsAction(
                SemanticsActions.RequestFocus,
            ).performKeyInput { pressKey(Key.Enter) }
        assertEquals(
            2,
            controller.state.value
                .readyDraft()!!
                .fields.size,
        )
        controller.close()
    }

    @Test
    fun discardInitialKeyboardFocusDismissalAndRetainedDialogActionsAreSafe() {
        var exits = 0
        val controller = controller()
        composeTestRule.setContent { LifeTracingTheme { ActivityTemplateEditorRoute(controller) { exits++ } } }
        awaitReady(controller)
        keyboard.enter()
        input(R.string.activity_editor_name).awaitKeyboardFocus().performTextReplacement("Unsaved")
        keyboard.closeKeyboard(input(R.string.activity_editor_name))
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_back),
            ).performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        val keep = composeTestRule.onNodeWithText(text(R.string.activity_editor_continue_editing))
        keep.awaitKeyboardFocus()
        val discard =
            composeTestRule
                .onNodeWithText(
                    text(R.string.activity_editor_discard),
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        keyboard.sendKey(keep, android.view.KeyEvent.KEYCODE_BACK)
        composeTestRule.waitUntil { !controller.state.value.discardConfirmationVisible }
        composeTestRule.runOnIdle { discard() }
        assertEquals(0, exits)
        assertEquals(
            "Unsaved",
            controller.state.value
                .readyDraft()!!
                .name,
        )
        val back = composeTestRule.onNodeWithText(text(R.string.activity_editor_back))
        back.awaitKeyboardFocus()
        controller.close()
    }

    @Test
    fun gatedSavingDisablesBackAndSaveThenCommittedHasNoSecondAction() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var exits = 0
        val controller =
            advancedController(write = { draft ->
                writes++
                entered.complete(Unit)
                release.await()
                template(draft)
            })
        composeTestRule.setContent {
            LifeTracingTheme { ActivityTemplateEditorRoute(controller, onCommitted = { exits++ }) {} }
        }
        awaitReady(controller)
        keyboard.enter()
        val save = composeTestRule.onNodeWithText(text(R.string.activity_editor_done))
        val retained = save.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        save
            .performScrollTo()
            .performSemanticsAction(
                SemanticsActions.RequestFocus,
            ).performKeyInput { pressKey(Key.Enter) }
        composeTestRule.waitUntil { entered.isCompleted }
        composeTestRule.onNodeWithText(text(R.string.activity_editor_back)).performScrollTo().assertIsNotEnabled()
        composeTestRule.runOnIdle {
            retained()
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertEquals(0, exits)
        assertEquals(1, writes)
        composeTestRule.runOnIdle { release.complete(Unit) }
        composeTestRule.waitUntil { exits == 1 }
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_committed)).performScrollTo().assertIsDisplayed()
        composeTestRule.runOnIdle { retained() }
        assertEquals(1, writes)
        controller.close()
    }

    @Test
    fun ordinaryFailureExplicitSaveRetryAndConflictReloadRetainUnsavedText() {
        var attempts = 0
        var reads = 0
        val original = template(ActivityTemplateDraft("Stored", null, TimeTrackingMode.STOPWATCH, null))
        val controller =
            advancedController(ActivityTemplateEditorTarget.Existing(original.id), load = {
                reads++
                original
            }, write = {
                attempts++
                if (attempts == 1) error("writer unavailable")
                error("revision changed concurrently")
            })
        present(controller)
        input(R.string.activity_editor_name).performTextReplacement("Unsaved")
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_save_failure),
            ).performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            "Unsaved",
            controller.state.value
                .readyDraft()!!
                .name,
        )
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_conflict)).performScrollTo().assertIsDisplayed()
        assertEquals(
            "Unsaved",
            controller.state.value
                .readyDraft()!!
                .name,
        )
        assertEquals(1, reads)
        val staleName =
            input(
                R.string.activity_editor_name,
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val staleSave =
            composeTestRule
                .onNodeWithText(text(R.string.activity_editor_done))
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeTestRule
            .onNodeWithText(
                text(R.string.activity_editor_reload),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        awaitReady(controller)
        composeTestRule.runOnIdle {
            staleName(AnnotatedString("Obsolete"))
            staleSave()
        }
        assertEquals(
            "Stored",
            controller.state.value
                .readyDraft()!!
                .name,
        )
        assertEquals(2, reads)
        assertEquals(2, attempts)
        controller.close()
    }

    @Test
    fun englishCombinedScaleExtremesKeepNativeTargetsReachable() = combinedScales(Locale.ENGLISH)

    @Test
    fun russianCombinedScaleExtremesKeepNativeTargetsReachable() = combinedScales(Locale.forLanguageTag("ru"))

    private fun combinedScales(locale: Locale) {
        val configuration =
            android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                setLocale(locale)
                fontScale =
                    2f
            }
        val context = composeTestRule.activity.createConfigurationContext(configuration)
        val appearance = mutableStateOf(AppearancePreferences(interfaceScalePercent = 90, textScalePercent = 90))
        val controller =
            controller(
                fieldsDraft(
                    ActivityFieldDraft(
                        DraftIdentity.New("category"),
                        0,
                        "Repeated name",
                        CustomFieldType.CATEGORY,
                        categoryOptions =
                            listOf(
                                ActivityCategoryOptionDraft(DraftIdentity.New("option"), 0, "Repeated label"),
                            ),
                    ),
                ),
            )
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
                LocalDensity provides Density(density.density, 2f),
            ) {
                LifeTracingAppearance(appearance.value) { ActivityTemplateEditorRoute(controller) {} }
            }
        }
        awaitReady(controller)
        keyboard.enter()
        composeTestRule
            .onNode(hasText(context.getString(R.string.activity_editor_name)) and hasSetTextAction())
            .awaitKeyboardFocus()
        for (scale in listOf(90 to 90, 120 to 130)) {
            composeTestRule.runOnIdle {
                appearance.value =
                    AppearancePreferences(interfaceScalePercent = scale.first, textScalePercent = scale.second)
            }
            val targets =
                composeTestRule
                    .onAllNodes(
                        SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
                    ).fetchSemanticsNodes()
            val minimum = 48 * context.resources.displayMetrics.density
            val visibleBounds = targets.map { it.touchBoundsInRoot }.filter { it.width > 0 && it.height > 0 }
            visibleBounds.forEachIndexed { index, bounds ->
                visibleBounds.drop(index + 1).forEach { other ->
                    assertFalse("Interactive targets overlap", bounds.overlaps(other))
                }
            }
            targets.forEach { target ->
                val node = composeTestRule.onNode(SemanticsMatcher("id") { it.id == target.id })
                node.performScrollTo().assertIsDisplayed()
                val semantics = node.fetchSemanticsNode()
                val bounds = semantics.touchBoundsInRoot
                assertTrue(
                    "height ${bounds.height}, visual ${semantics.boundsInRoot}, ${semantics.config}",
                    bounds.height >= minimum - 1,
                )
                assertTrue("width ${bounds.width}", bounds.width >= minimum - 1)
                assertTrue(bounds.left >= 0)
                assertTrue(bounds.right <= composeTestRule.activity.resources.displayMetrics.widthPixels)
            }
        }
        keyboard.closeKeyboard(composeTestRule.onNodeWithText(context.getString(R.string.activity_editor_back)))
        val back =
            composeTestRule
                .onNodeWithText(context.getString(R.string.activity_editor_back))
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .awaitKeyboardFocus()
        keyboard.sendKey(back, android.view.KeyEvent.KEYCODE_ENTER)
        val keep = composeTestRule.onNodeWithText(context.getString(R.string.activity_editor_continue_editing))
        keep.awaitKeyboardFocus()
        keyboard.sendKey(keep, android.view.KeyEvent.KEYCODE_ENTER)
        composeTestRule.waitUntil { !controller.state.value.discardConfirmationVisible }
        assertFalse(controller.state.value.discardConfirmationVisible)
        controller.close()
    }

    @Test
    fun authoritativeValidationFailureIsAnnouncedWithoutWriterAndNativeMainValueCanResolveIt() {
        val first =
            ActivityFieldDraft(DraftIdentity.New("first"), 0, "Same", CustomFieldType.NUMBER, isMainValue = true)
        val second = first.copy(identity = DraftIdentity.New("second"), position = 1)
        var writes = 0
        val controller = controller(fieldsDraft(first, second), save = { writes++ })
        present(controller)
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule
            .onNodeWithText(text(R.string.activity_editor_save_failure))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        assertEquals(0, writes)
        control(R.string.activity_editor_main_value, fieldContext(second, 2)).performScrollTo().performClick()
        assertEquals(
            listOf(false, false),
            controller.state.value
                .readyDraft()!!
                .fields
                .map { it.isMainValue },
        )
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule.waitUntil { writes == 1 }
        controller.close()
    }

    @Test
    fun hiddenOrDisposedEditorCannotStealForegroundFocusOrSubmitRetainedActions() {
        val visible = mutableStateOf(true)
        val shown = mutableStateOf(true)
        var writes = 0
        val field = ActivityFieldDraft(DraftIdentity.New("field"), 0, "Same", CustomFieldType.NUMBER)
        val controller = controller(fieldsDraft(field), save = { writes++ })
        composeTestRule.setContent {
            LifeTracingTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button(onClick = {}) { androidx.compose.material3.Text("Foreground") }
                    if (shown.value) ActivityTemplateEditorRoute(controller, isRouteVisible = visible.value) {}
                }
            }
        }
        awaitReady(controller)
        keyboard.enter()
        input(R.string.activity_editor_name).awaitKeyboardFocus()
        val staleText =
            control(R.string.activity_editor_default, fieldContext(field, 1))
                .fetchSemanticsNode()
                .config[SemanticsActions.SetText]
                .action!!
        val staleSave =
            composeTestRule
                .onNodeWithText(text(R.string.activity_editor_done))
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeTestRule.runOnIdle { visible.value = false }
        composeTestRule
            .onNodeWithText(
                "Foreground",
            ).performSemanticsAction(SemanticsActions.RequestFocus)
            .awaitKeyboardFocus()
        composeTestRule.runOnIdle { controller.updateDraft { it.copy(fields = emptyList()) } }
        val before = controller.state.value
        composeTestRule.runOnIdle {
            staleText(AnnotatedString("1"))
            staleSave()
        }
        assertEquals(before, controller.state.value)
        composeTestRule.runOnIdle { controller.retry() }
        awaitReady(controller)
        composeTestRule.onNodeWithText("Foreground").awaitKeyboardFocus()
        composeTestRule.runOnIdle { visible.value = true }
        input(R.string.activity_editor_name).awaitKeyboardFocus()
        val disposedSave =
            composeTestRule
                .onNodeWithText(text(R.string.activity_editor_done))
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeTestRule.runOnIdle { shown.value = false }
        composeTestRule.onNodeWithText("Foreground").performSemanticsAction(SemanticsActions.RequestFocus)
        composeTestRule.runOnIdle { disposedSave() }
        assertEquals(0, writes)
        composeTestRule.onNodeWithText("Foreground").awaitKeyboardFocus()
        controller.close()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.awaitKeyboardFocus() =
        apply { keyboard.awaitFocus(this) }

    private fun present(controller: ActivityTemplateEditorController) {
        composeTestRule.setContent { LifeTracingTheme { ActivityTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        keyboard.enter()
        input(R.string.activity_editor_name).awaitKeyboardFocus()
    }

    private fun input(id: Int) = composeTestRule.onNode(hasText(text(id)) and hasSetTextAction())

    private fun control(
        operation: Int,
        context: String,
    ) = composeTestRule.onNodeWithContentDescription(
        composeTestRule.activity.getString(R.string.activity_editor_control_context, text(operation), context),
    )

    private fun fieldContext(
        field: ActivityFieldDraft,
        ordinal: Int,
    ) = composeTestRule.activity.getString(
        R.string.activity_editor_field_context,
        text(
            when (field.type) {
                CustomFieldType.NUMBER -> R.string.activity_editor_number
                CustomFieldType.CATEGORY -> R.string.activity_editor_category
                CustomFieldType.TEXT -> R.string.activity_editor_text
            },
        ),
        ordinal,
        field.name,
    )

    private fun optionContext(
        field: String,
        ordinal: Int,
        label: String,
    ) = composeTestRule.activity.getString(R.string.activity_editor_option_context, field, ordinal, label)

    private fun fieldsDraft(vararg fields: ActivityFieldDraft) =
        ActivityTemplateDraft("Template", null, TimeTrackingMode.STOPWATCH, null, fields = fields.toList())

    private fun advancedController(
        target: ActivityTemplateEditorTarget = ActivityTemplateEditorTarget.New,
        load: suspend (ActivityTemplateId) -> ActivityTemplate? = { null },
        write: suspend (ActivityTemplateDraft) -> ActivityTemplate = { template(it) },
    ) = ActivityTemplateEditorController(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        target,
        load,
        { draft, _, _ -> write(draft) },
        { _, _, draft, _ -> write(draft) },
        Instant::now,
    )

    private fun compactChoicesStayInsideBounds(locale: Locale) {
        val configuration =
            android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                setLocale(locale)
            }
        val context = composeTestRule.activity.createConfigurationContext(configuration)
        val controller = controller()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                LifeTracingTheme {
                    Box(Modifier.width(320.dp)) { ActivityTemplateEditorRoute(controller) {} }
                }
            }
        }
        awaitReady(controller)

        val labels =
            listOf(
                R.string.activity_editor_stopwatch,
                R.string.activity_editor_timer,
                R.string.activity_editor_none,
                R.string.activity_editor_number,
                R.string.activity_editor_category,
                R.string.activity_editor_text,
            ).map(context::getString)
        val compactWidthPixels = 320 * context.resources.displayMetrics.density
        labels.forEach { label ->
            val node = composeTestRule.onNodeWithText(label)
            node.performScrollTo().assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= 0f)
            assertTrue(bounds.right <= compactWidthPixels)
        }
        controller.close()
    }

    private fun controller(
        initial: ActivityTemplateDraft = ActivityTemplateDraft("", null, TimeTrackingMode.STOPWATCH, null),
        save: (ActivityTemplateDraft) -> Unit = {},
    ) = ActivityTemplateEditorController(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ActivityTemplateEditorTarget.New,
        { null },
        { draft, _, _ ->
            save(draft)
            template(draft)
        },
        { _, _, draft, _ ->
            save(draft)
            template(draft)
        },
        Instant::now,
    ).also { controller ->
        if (initial.name.isNotEmpty() || initial.fields.isNotEmpty()) {
            composeTestRule.waitUntil { controller.state.value.load is ActivityTemplateEditorLoad.Ready }
            composeTestRule.runOnIdle { controller.updateDraft { initial } }
        }
    }

    private fun awaitReady(controller: ActivityTemplateEditorController) {
        composeTestRule.waitUntil { controller.state.value.load is ActivityTemplateEditorLoad.Ready }
    }

    private fun template(draft: ActivityTemplateDraft) =
        ActivityTemplate(
            ActivityTemplateId("template"),
            draft.name,
            draft.shortComment,
            draft.timeTrackingMode,
            draft.timerTarget,
            StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            settings = draft.settings,
        )

    private fun text(id: Int): String = composeTestRule.activity.getString(id)
}
