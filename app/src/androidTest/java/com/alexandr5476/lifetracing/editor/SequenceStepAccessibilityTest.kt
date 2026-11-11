@file:Suppress("LongMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing.editor

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.onLazyNode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class SequenceStepAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = mutableStateOf(true)
    private val attachment = mutableStateOf(0)
    private var editor: SequenceTemplateEditorController? = null
    private var resources: android.content.Context? = null

    @After
    fun close() {
        editor?.close()
        scope.cancel()
    }

    @Test
    fun nativePickerCreatesIndependentLocalAndSameNameTemplateStepsInExactContainers() {
        val choices = listOf(choice("one"), choice("two"))
        val repeat = SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 2, emptyList())
        val controller =
            present(SequenceTemplateDraft("Sequence", null, nodes = listOf(SequenceNodeDraft.Repeat(repeat))), {
                choices
            })
        keyboard.enter()
        activate(native(R.string.sequence_editor_add_step))
        val cancel = dialog(R.string.sequence_editor_cancel)
        keyboard.enter(cancel)
        keyboard.awaitFocus(cancel)
        val cancelId = cancel.fetchSemanticsNode().id
        keyboard.sendKey(cancel, KeyEvent.KEYCODE_TAB)
        val next = compose.onNode(isFocused() and hasAnyAncestor(isDialog()))
        assertTrue(cancelId != next.fetchSemanticsNode().id)
        next.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        keyboard.awaitFocus(cancel)
        val cancelled =
            dialog(
                R.string.sequence_editor_add_local_step,
            ).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        activate(dialog(R.string.sequence_editor_cancel))
        compose.runOnIdle { cancelled() }
        assertEquals(
            1,
            controller.state.value
                .readyDraft()!!
                .nodes.size,
        )
        activate(native(R.string.sequence_editor_add_step))
        val local = dialog(R.string.sequence_editor_add_local_step)
        val retained = local.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        activate(local)
        compose.runOnIdle {
            retained()
            cancelled()
        }
        val added =
            (
                controller.state.value
                    .readyDraft()!!
                    .nodes
                    .last() as SequenceNodeDraft.Step
            ).value
        assertTrue(added.identity is DraftIdentity.New)
        assertTrue(added.activity is StepActivityDraft.Local)
        val destination = text(R.string.sequence_manipulation_repeat, 2, 1)
        activate(
            compose.onLazyNode(hasContentDescription(text(R.string.sequence_editor_add_step_to_repeat, destination))),
        )
        val description =
            text(
                R.string.sequence_step_picker_choice,
                2,
                choices[1].name,
                text(R.string.activity_editor_stopwatch),
                destination,
            )
        activate(compose.onNode(hasContentDescription(description)))
        val current = controller.state.value.readyDraft()!!
        val child = (current.nodes.first() as SequenceNodeDraft.Repeat).value.children.single()
        assertEquals(StepActivityDraft.FromTemplate(choices[1].id), child.activity)
        assertTrue(child.identity is DraftIdentity.New)
        assertTrue(child.identity != added.identity)
        assertEquals(0, child.position)
        assertEquals(1, added.position)
    }

    @Test
    fun nativePickerKeepsBoundedPagesAndRevokesHiddenRecreatedAndRemovedTargetActions() {
        var reads = 0
        var continuations = 0
        val first = List(50) { choice("first-$it") }
        val later = List(50) { choice("later-$it") }
        val repeat = SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 2, emptyList())
        val controller =
            present(
                SequenceTemplateDraft("Sequence", null, nodes = listOf(SequenceNodeDraft.Repeat(repeat))),
                {
                    reads++
                    first
                },
                { cursor ->
                    assertEquals(first.last(), cursor)
                    continuations++
                    later
                },
            )
        assertEquals(0, reads)
        activate(native(R.string.sequence_editor_add_step))
        assertEquals(1, reads)
        val list = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
        list.performScrollToIndex(51)
        activate(dialog(R.string.sequence_editor_load_more))
        assertEquals(1, continuations)
        list.performScrollToIndex(100)
        val destination = text(R.string.sequence_step_top_destination)
        val last =
            text(
                R.string.sequence_step_picker_choice,
                100,
                later.last().name,
                text(R.string.activity_editor_stopwatch),
                destination,
            )
        val old =
            compose
                .onNode(
                    hasContentDescription(last),
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { old() }
        assertEquals(
            1,
            controller.state.value
                .readyDraft()!!
                .nodes.size,
        )
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        activate(native(R.string.sequence_editor_add_step))
        compose.runOnIdle { old() }
        compose.onNode(isDialog()).assertExists()
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog())).performScrollToIndex(100)
        activate(compose.onNode(hasContentDescription(last)))
        val selected =
            (
                controller.state.value
                    .readyDraft()!!
                    .nodes
                    .last() as SequenceNodeDraft.Step
            ).value
        assertEquals(StepActivityDraft.FromTemplate(later.last().id), selected.activity)
        val repeatContext = text(R.string.sequence_manipulation_repeat, 2, 1)
        activate(
            compose.onLazyNode(hasContentDescription(text(R.string.sequence_editor_add_step_to_repeat, repeatContext))),
        )
        val removed =
            dialog(
                R.string.sequence_editor_add_local_step,
            ).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            controller.updateDraft { it.copy(nodes = emptyList()) }
            removed()
        }
        assertTrue(
            controller.state.value
                .readyDraft()!!
                .nodes
                .isEmpty(),
        )
        compose.waitForIdle()
        activate(native(R.string.sequence_editor_add_step))
        val recreated =
            dialog(
                R.string.sequence_editor_add_local_step,
            ).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { attachment.value++ }
        compose.waitForIdle()
        compose.runOnIdle { recreated() }
        assertTrue(
            controller.state.value
                .readyDraft()!!
                .nodes
                .isEmpty(),
        )
        assertEquals(1, reads)
        assertEquals(1, continuations)
    }

    @Test
    fun nativePickerReportsLoadingFailureEmptyAndCancellationWithoutWrites() {
        val pending = CompletableDeferred<List<SequenceEditorActivityChoice>>()
        var reads = 0
        val controller =
            present(SequenceTemplateDraft("Sequence", null), {
                if (++reads ==
                    1
                ) {
                    pending.await()
                } else {
                    emptyList()
                }
            })
        activate(native(R.string.sequence_editor_add_step))
        compose.onNodeWithText(text(R.string.sequence_step_picker_loading)).assertExists()
        compose.runOnIdle { pending.completeExceptionally(IllegalStateException("Read failed")) }
        compose.waitUntil(5000) { controller.state.value.activityPickerFailed }
        compose.onNodeWithText(text(R.string.sequence_step_picker_failure)).assertExists()
        activate(dialog(R.string.sequence_editor_retry))
        compose.onNodeWithText(text(R.string.sequence_step_picker_empty)).assertExists()
        compose.onNode(isFocused() and hasAnyAncestor(isDialog())).assertExists()
        activate(dialog(R.string.sequence_editor_cancel))
        assertTrue(
            controller.state.value
                .readyDraft()!!
                .nodes
                .isEmpty(),
        )
        assertEquals(2, reads)
    }

    @Test
    fun nativeLocalTrackingSettingsOverridesAndRepeatExposeSelectedCheckedAndRawErrors() {
        val step = step("step")
        val repeat = SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 1, 2, emptyList())
        val controller =
            present(
                SequenceTemplateDraft(
                    "Sequence",
                    null,
                    nodes = listOf(SequenceNodeDraft.Step(step), SequenceNodeDraft.Repeat(repeat)),
                ),
            )
        open(step)
        input(R.string.sequence_editor_name, context(step)).performTextReplacement("Edited")
        input(R.string.sequence_editor_short_comment, context(step)).performTextReplacement("Comment")
        activate(native(R.string.activity_editor_timer))
        native(R.string.activity_editor_timer).assertIsSelected()
        val timer = input(R.string.activity_editor_timer_seconds, context(step))
        for (raw in listOf("bad", "", "0")) {
            val currentTimer = input(R.string.activity_editor_timer_seconds, context(step))
            currentTimer.performTextReplacement(raw)
            currentTimer.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            native(R.string.sequence_editor_done).assertIsNotEnabled()
        }
        input(R.string.activity_editor_timer_seconds, context(step)).performTextReplacement("45")
        assertEquals(Duration.ofSeconds(45), configuration(controller, step).timerTarget)
        val localZero =
            compose.onLazyNode(
                hasText(text(R.string.sequence_editor_timer_zero_overtime)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context(step)),
            )
        activate(localZero)
        localZero.assertIsSelected()
        assertEquals(TimerZeroBehavior.OVERTIME, configuration(controller, step).settings.timerZeroBehavior)
        val zeroContext =
            text(
                R.string.activity_editor_control_context,
                text(R.string.sequence_step_override_timer_behavior),
                context(step),
            )
        val explicitZero =
            compose.onLazyNode(
                hasText(text(R.string.sequence_editor_timer_zero_finish)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, zeroContext),
            )
        val staleOverride = explicitZero.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        activate(explicitZero)
        explicitZero.assertIsSelected()
        val inheritedZero =
            compose.onLazyNode(
                hasText(text(R.string.sequence_editor_inherit)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, zeroContext),
            )
        activate(inheritedZero)
        inheritedZero.assertIsSelected()
        assertNull(
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.timerZeroBehavior,
        )
        val staleTimer = timer.fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        activate(native(R.string.activity_editor_stopwatch))
        compose.runOnIdle {
            staleTimer(AnnotatedString("bad"))
            staleOverride()
        }
        assertNull(
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.timerZeroBehavior,
        )
        assertFalse(controller.state.value.hasInvalidInput(controller.state.value.readyDraft()!!))
        activate(native(R.string.activity_editor_none))
        native(R.string.activity_editor_none).assertIsSelected()
        val flags: List<Pair<Int, (ActivityTemplateSettings) -> Boolean>> =
            listOf(
                R.string.sequence_editor_local_show_seconds to { it.showSeconds },
                R.string.sequence_editor_local_timer_sound to { it.timerEndSound },
                R.string.sequence_editor_local_timer_vibration to { it.timerEndVibration },
                R.string.sequence_editor_local_keep_awake to { it.keepScreenAwake },
                R.string.sequence_editor_local_confirm_finish to { it.confirmManualFinish },
            )
        for ((label, value) in flags) {
            val expected = !value(configuration(controller, step).settings)
            val target = control(label, context(step))
            activate(target)
            if (expected) target.assertIsOn() else target.assertIsOff()
            assertEquals(expected, value(configuration(controller, step).settings))
        }
        input(R.string.sequence_editor_local_start_countdown, context(step)).performTextReplacement("3")
        assertEquals(Duration.ofSeconds(3), configuration(controller, step).settings.startCountdown)
        val overrideContext =
            text(R.string.activity_editor_control_context, text(R.string.sequence_editor_override_sound), context(step))
        val explicitFalse =
            compose.onLazyNode(
                hasText(text(R.string.sequence_editor_off)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, overrideContext),
            )
        activate(explicitFalse)
        explicitFalse.assertIsSelected()
        input(R.string.sequence_editor_override_countdown, context(step))
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            ).performTextReplacement("0")
        assertEquals(
            false,
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.timerEndSound,
        )
        assertEquals(
            Duration.ZERO,
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.startCountdown,
        )
        for (intent in listOf(
            R.string.sequence_editor_on,
            R.string.sequence_editor_inherit,
            R.string.sequence_editor_off,
        )) {
            val target =
                compose.onLazyNode(
                    hasText(text(intent)) and
                        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, overrideContext),
                )
            activate(target)
            target.assertIsSelected()
            val expected =
                when (intent) {
                    R.string.sequence_editor_on -> true
                    R.string.sequence_editor_off -> false
                    else -> null
                }
            assertEquals(
                expected,
                controller.state.value
                    .readyDraft()!!
                    .findStep(step.identity)!!
                    .overrides.timerEndSound,
            )
        }
        input(R.string.sequence_editor_override_countdown, context(step)).performTextReplacement("5")
        assertEquals(
            Duration.ofSeconds(5),
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.startCountdown,
        )
        activate(control(R.string.sequence_editor_inherit, context(step)))
        assertNull(
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.startCountdown,
        )
        input(R.string.sequence_editor_override_countdown, context(step))
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            ).performTextReplacement("0")
        assertEquals(
            Duration.ZERO,
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.startCountdown,
        )
        activate(control(R.string.sequence_editor_inherit, context(step)))
        assertNull(
            controller.state.value
                .readyDraft()!!
                .findStep(step.identity)!!
                .overrides.startCountdown,
        )
        val repeatContext = text(R.string.sequence_manipulation_repeat, 2, 2)
        val count = input(R.string.sequence_editor_repeat_count, repeatContext)
        count.performTextReplacement("bad")
        count.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertEquals(
            2,
            (
                controller.state.value
                    .readyDraft()!!
                    .nodes
                    .last() as SequenceNodeDraft.Repeat
            ).value.repeatCount,
        )
        count.performTextReplacement("3")
        assertEquals(
            3,
            (
                controller.state.value
                    .readyDraft()!!
                    .nodes
                    .last() as SequenceNodeDraft.Repeat
            ).value.repeatCount,
        )
        val obsoleteCount =
            input(R.string.sequence_editor_repeat_count, text(R.string.sequence_manipulation_repeat, 3, 2))
                .fetchSemanticsNode()
                .config[SemanticsActions.SetText]
                .action!!
        compose.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            it.nodes.first(),
                            SequenceNodeDraft.Repeat(repeat.copy(identity = DraftIdentity.New("replacement"))),
                        ),
                )
            }
            val current = controller.state.value
            obsoleteCount(AnnotatedString("99"))
            assertEquals(current, controller.state.value)
        }
    }

    @Test
    fun nativeSnapshotFieldAndOptionCallbacksResolveIdentityAfterRemovalPrecisionTypeMovementAndCollapse() {
        val a = field("a", CustomFieldType.NUMBER)
        val b = field("b", CustomFieldType.NUMBER).copy(position = 1)
        val first = ActivitySnapshotCategoryOptionDraft(DraftIdentity.New("one"), null, 0, "Same")
        val second = first.copy(identity = DraftIdentity.New("two"), position = 1)
        val category =
            field(
                "category",
                CustomFieldType.CATEGORY,
            ).copy(position = 2, categoryOptions = listOf(first, second))
        val step = step("owner", listOf(a, b, category))
        val other = step("other").copy(position = 1)
        val controller =
            present(
                SequenceTemplateDraft(
                    "Sequence",
                    null,
                    nodes = listOf(SequenceNodeDraft.Step(step), SequenceNodeDraft.Step(other)),
                ),
            )
        open(step)
        val staleA =
            input(
                R.string.activity_editor_default,
                fieldContext(step, 0),
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val bDefault =
            input(
                R.string.activity_editor_default,
                fieldContext(step, 1),
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        input(R.string.activity_editor_precision, fieldContext(step, 1)).performTextReplacement("2")
        compose.runOnIdle { bDefault(AnnotatedString("-1.25")) }
        assertEquals(-1250L, configuration(controller, step).fields[1].defaultNumberScaled)
        activate(control(R.string.sequence_editor_remove, fieldContext(step, 0)))
        compose.runOnIdle { staleA(AnnotatedString("0")) }
        assertEquals(-1250L, configuration(controller, step).fields.first().defaultNumberScaled)
        val optionContext = text(R.string.activity_editor_option_context, fieldContext(step, 1), 1, "Same")
        val oldOption =
            input(
                R.string.activity_editor_option,
                optionContext,
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        activate(control(R.string.activity_editor_default, optionContext))
        activate(control(R.string.sequence_editor_remove, optionContext))
        compose.runOnIdle { oldOption(AnnotatedString("Wrong")) }
        val current = configuration(controller, step).fields.last()
        assertNull(current.defaultCategoryOption)
        assertEquals(listOf(second), current.categoryOptions.map { it.copy(position = 1) })
        val oldName =
            input(
                R.string.activity_editor_field_name,
                fieldContext(step, 0),
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        compose.runOnIdle {
            controller.updateDraft { draft ->
                val owner = draft.findStep(step.identity)!!
                draft.copy(
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(other.copy(position = 0)),
                            SequenceNodeDraft.Repeat(
                                SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 1, 2, listOf(owner)),
                            ),
                        ),
                )
            }
            oldName(AnnotatedString("Moved"))
        }
        assertEquals("Moved", configuration(controller, step).fields.first().nameAtCreation)
        assertEquals(
            other.copy(position = 0),
            controller.state.value
                .readyDraft()!!
                .findStep(other.identity),
        )
        val oldDefault =
            input(
                R.string.activity_editor_default,
                fieldContext(step, 0),
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val textType =
            compose.onLazyNode(
                hasText(text(R.string.activity_editor_text)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, fieldContext(step, 0)),
            )
        activate(textType)
        compose.runOnIdle { oldDefault(AnnotatedString("17")) }
        assertNull(configuration(controller, step).fields.first().defaultNumberScaled)
        val retained =
            input(
                R.string.activity_editor_field_name,
                fieldContext(step, 0),
            ).fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        activate(control(R.string.sequence_editor_close_step, context(step)))
        val before = controller.state.value
        compose.runOnIdle { retained(AnnotatedString("Collapsed")) }
        assertEquals(before, controller.state.value)
        open(step)
        compose.runOnIdle { retained(AnnotatedString("Reopened")) }
        assertEquals(before, controller.state.value)
    }

    @Test
    fun pendingTemplateDisclosureHasNativeGuidanceAndRetiredDisclosureCannotReopenOrCloseCurrentContent() {
        val source = choice("source")
        val controller = present(SequenceTemplateDraft("Sequence", null), { listOf(source) })
        activate(native(R.string.sequence_editor_add_step))
        val target =
            text(
                R.string.sequence_step_picker_choice,
                1,
                source.name,
                text(R.string.activity_editor_stopwatch),
                text(R.string.sequence_step_top_destination),
            )
        activate(compose.onNode(hasContentDescription(target)))
        val pending =
            (
                controller.state.value
                    .readyDraft()!!
                    .nodes
                    .single() as SequenceNodeDraft.Step
            ).value
        val baseline = controller.state.value
        open(pending)
        compose.onLazyNode(hasText(text(R.string.sequence_editor_pending_source_configuration))).assertExists()
        compose
            .onNode(
                hasSetTextAction() and
                    hasContentDescription(
                        text(
                            R.string.activity_editor_control_context,
                            text(R.string.sequence_editor_name),
                            context(pending),
                        ),
                    ),
            ).assertDoesNotExist()
        val close = control(R.string.sequence_editor_close_step, context(pending))
        val obsoleteClose = close.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        activate(close)
        compose.runOnIdle { obsoleteClose() }
        control(R.string.sequence_editor_edit_step, context(pending)).assertExists()
        assertEquals(baseline, controller.state.value)
        open(pending)
        compose.runOnIdle { obsoleteClose() }
        control(R.string.sequence_editor_close_step, context(pending)).assertExists()
        assertEquals(baseline, controller.state.value)
        assertEquals(
            StepActivityDraft.FromTemplate(source.id),
            controller.state.value
                .readyDraft()!!
                .findStep(pending.identity)!!
                .activity,
        )
    }

    @Test
    fun nativeSnapshotInputFocusSurvivesUnrelatedRemovalAndInsertion() {
        val first = field("first", CustomFieldType.TEXT)
        val second = field("second", CustomFieldType.TEXT).copy(position = 1)
        val step = step("owner", listOf(first, second))
        present(SequenceTemplateDraft("Sequence", null, nodes = listOf(SequenceNodeDraft.Step(step))))
        open(step)
        val remove =
            control(
                R.string.sequence_editor_remove,
                fieldContext(step, 0),
            ).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val add =
            compose
                .onLazyNode(
                    hasContentDescription(
                        text(
                            R.string.activity_editor_control_context,
                            text(R.string.activity_editor_add_field_context, text(R.string.activity_editor_text)),
                            context(step),
                        ),
                    ),
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val focusedInput = input(R.string.activity_editor_default, fieldContext(step, 1))
        keyboard.enter(focusedInput)
        focusedInput.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(focusedInput)
        val id = focusedInput.fetchSemanticsNode().id
        compose.runOnIdle { remove() }
        compose.waitForIdle()
        val survivor = input(R.string.activity_editor_default, fieldContext(step, 0))
        survivor.assertIsFocused()
        assertEquals(id, survivor.fetchSemanticsNode().id)
        compose.runOnIdle { add() }
        compose.waitForIdle()
        input(R.string.activity_editor_default, fieldContext(step, 0)).assertIsFocused()
        assertEquals(id, input(R.string.activity_editor_default, fieldContext(step, 0)).fetchSemanticsNode().id)
    }

    @Test
    fun stepConfigurationAndPickerReflowAtEnglishAndRussianCombinedScalingExtremes() {
        val step = step("scale", listOf(field("number", CustomFieldType.NUMBER)))
        val controller = create({ emptyList() })
        val locale = mutableStateOf(Locale.ENGLISH)
        val appearance = mutableStateOf(AppearancePreferences(interfaceScalePercent = 90, textScalePercent = 90))
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    setLocale(locale.value)
                    fontScale =
                        2f
                }
            val context = compose.activity.createConfigurationContext(configuration)
            resources = context
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 2f),
            ) {
                LifeTracingAppearance(appearance.value) { SequenceTemplateEditorRoute(controller) {} }
            }
        }
        ready(controller)
        compose.runOnIdle {
            controller.updateDraft {
                SequenceTemplateDraft("Sequence", null, nodes = listOf(SequenceNodeDraft.Step(step)))
            }
        }
        open(step)
        for (language in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
            for (scale in listOf(90 to 90, 120 to 130)) {
                compose.runOnIdle {
                    locale.value = language
                    appearance.value =
                        AppearancePreferences(interfaceScalePercent = scale.first, textScalePercent = scale.second)
                }
                compose.waitForIdle()
                input(R.string.activity_editor_default, fieldContext(step, 0)).performTextReplacement("0")
                input(R.string.sequence_editor_override_countdown, context(step)).performTextReplacement("0")
                activate(native(R.string.sequence_editor_add_step))
                dialog(R.string.sequence_editor_add_local_step).assertExists()
                activate(dialog(R.string.sequence_editor_cancel))
                assertEquals(0L, configuration(controller, step).fields.single().defaultNumberScaled)
            }
        }
    }

    private fun create(
        catalog: suspend () -> List<SequenceEditorActivityChoice>,
        more: suspend (SequenceEditorActivityChoice) -> List<SequenceEditorActivityChoice> = {
            emptyList()
        },
    ) = SequenceTemplateEditorController(
        scope,
        SequenceTemplateEditorTarget.New,
        { null },
        catalog,
        { _, _, _ ->
            error("No write")
        },
        { _, _, _, _ -> error("No write") },
        { Instant.EPOCH },
        loadMoreActivities = more,
    ).also {
        editor =
            it
    }

    private fun present(
        draft: SequenceTemplateDraft,
        catalog: suspend () -> List<SequenceEditorActivityChoice> = {
            emptyList()
        },
        more: suspend (SequenceEditorActivityChoice) -> List<SequenceEditorActivityChoice> = {
            emptyList()
        },
    ): SequenceTemplateEditorController {
        val controller = create(catalog, more)
        compose.setContent {
            LifeTracingTheme {
                key(attachment.value) { SequenceTemplateEditorRoute(controller, isRouteVisible = visible.value) {} }
            }
        }
        ready(controller)
        compose.runOnIdle { controller.updateDraft { draft } }
        return controller
    }

    private fun ready(controller: SequenceTemplateEditorController) =
        compose.waitUntil(5000) {
            controller.state.value.load is SequenceTemplateEditorLoad.Ready
        }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = (resources ?: compose.activity).getString(id, *args)

    private fun native(id: Int) = compose.onLazyNode(hasText(text(id)) and hasClickAction())

    private fun dialog(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))

    private fun control(
        id: Int,
        context: String,
    ) = compose.onLazyNode(hasContentDescription(text(R.string.activity_editor_control_context, text(id), context)))

    private fun input(
        id: Int,
        context: String,
    ) = compose.onLazyNode(
        hasSetTextAction() and hasContentDescription(text(R.string.activity_editor_control_context, text(id), context)),
    )

    private fun open(step: ActivityStepDraft) = activate(control(R.string.sequence_editor_edit_step, context(step)))

    private fun activate(node: SemanticsNodeInteraction) {
        keyboard.enter(node)
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(node)
        keyboard.sendKey(node, KeyEvent.KEYCODE_ENTER)
        compose.waitForIdle()
    }

    private fun configuration(
        controller: SequenceTemplateEditorController,
        step: ActivityStepDraft,
    ) = controller.state.value
        .readyDraft()!!
        .findStep(step.identity)!!
        .configuration()!!

    private fun context(step: ActivityStepDraft): String {
        val draft = editor!!.state.value.readyDraft()!!
        val current = draft.findStep(step.identity)!!
        val name =
            current.configuration()?.name ?: (current.activity as? StepActivityDraft.FromTemplate)
                ?.let { pending ->
                    editor!!
                        .state.value.availableActivities
                        .firstOrNull { it.id == pending.templateId }
                        ?.name
                }.orEmpty()
        val parent =
            draft.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().firstOrNull {
                it.value.children.any {
                    it.identity ==
                        step.identity
                }
            }
        return if (parent == null) {
            text(R.string.sequence_manipulation_top_step, name, current.position + 1)
        } else {
            text(
                R.string.sequence_manipulation_child,
                name,
                current.position + 1,
                text(
                    R.string.sequence_manipulation_repeat,
                    parent.value.repeatCount,
                    parent.position + 1,
                ),
            )
        }
    }

    private fun fieldContext(
        step: ActivityStepDraft,
        ordinal: Int,
    ): String {
        val field = configuration(editor!!, step).fields[ordinal]
        val type =
            when (field.type) {
                CustomFieldType.NUMBER -> R.string.activity_editor_number
                CustomFieldType.CATEGORY -> R.string.activity_editor_category
                CustomFieldType.TEXT -> R.string.activity_editor_text
            }
        return text(
            R.string.activity_editor_control_context,
            text(
                R.string.activity_editor_field_context,
                text(type),
                ordinal + 1,
                field.localNameOverride ?: field.nameAtCreation,
            ),
            context(step),
        )
    }

    private fun choice(id: String) = SequenceEditorActivityChoice(ActivityTemplateId(id), "Same")

    private fun field(
        id: String,
        type: CustomFieldType,
    ) = ActivitySnapshotFieldDraft(DraftIdentity.New(id), null, 0, "Same", type = type)

    private fun step(
        id: String,
        fields: List<ActivitySnapshotFieldDraft> = emptyList(),
    ) = ActivityStepDraft(
        DraftIdentity.New(id),
        0,
        StepActivityDraft.Local(
            ActivitySnapshotDraft("Same", null, TimeTrackingMode.STOPWATCH, null, fields = fields),
        ),
    )
}
