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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSourceStatus
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateAuthoringState
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.onLazyNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class SequenceStepSourceAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = mutableStateOf(true)
    private val attachment = mutableStateOf(0)
    private val locale = mutableStateOf(Locale.ENGLISH)
    private val appearance = mutableStateOf(AppearancePreferences())
    private var scaled = false
    private var localizedContext: android.content.Context? = null
    private var canonical = canonical()
    private var sourceStatus: ActivityTemplateSourceStatus? = ActivityTemplateSourceStatus(SOURCE, 4, false)
    private var failRead = false
    private var writes = 0
    private val targets = mutableListOf<SequenceNodeId>()
    private var reads = 0
    private var deliveries = 0
    private var failure: String? = null
    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private var editor: SequenceTemplateEditorController? = null

    @After
    fun close() {
        editor?.close()
        scope.cancel()
    }

    @Test fun updateFromSourceNativeRecoveryReadsWithoutReplayingWriter() =
        recovery(R.string.sequence_editor_update_from_source)

    @Test fun updateSourceNativeRecoveryReadsWithoutReplayingWriter() = recovery(R.string.sequence_editor_update_source)

    @Test fun saveAsNewNativeRecoveryReadsWithoutReplayingWriter() =
        recovery(
            R.string.sequence_editor_save_as_new_template,
        )

    private fun recovery(operation: Int) {
        val controller = present()
        open()
        val button = native(operation).assertIsEnabled()
        val retained = click(button)
        activate(button)
        compose.waitUntil(5_000) { started.isCompleted }
        native(operation).assertIsNotEnabled()
        compose.runOnIdle {
            retained()
            retained()
        }
        assertEquals(1, writes)
        compose.runOnIdle {
            failRead = true
            release.complete(Unit)
        }
        compose.waitUntil(5_000) {
            (controller.state.value.save as? SequenceTemplateEditorSave.Failure)?.committedAwaitingReload == true
        }
        native(operation).assertIsNotEnabled()
        native(R.string.sequence_editor_back).assertIsNotEnabled()
        assertEquals(0, deliveries)
        compose.runOnIdle { attachment.value++ }
        compose.waitForIdle()
        assertTrue((controller.state.value.save as SequenceTemplateEditorSave.Failure).committedAwaitingReload)
        compose.runOnIdle {
            retained()
            failRead = false
        }
        val retry = native(R.string.sequence_editor_retry)
        activate(retry)
        compose.waitUntil(5_000) { deliveries == 1 }
        assertEquals(1, writes)
        assertEquals(3, reads)
        assertEquals(1L, controller.state.value.appliedGeneration)
        compose.runOnIdle {
            retained()
            attachment.value++
        }
        compose.waitForIdle()
        assertEquals(1, deliveries)
        assertEquals(1, writes)
        assertEquals(canonical.toAuthoringDraft(), controller.state.value.readyDraft())
    }

    @Test
    fun nativeSourceAvailabilityDistinguishesActiveArchivedMissingAndUnlinkedWithoutPickerReads() {
        val controller = present()
        open()
        for (operation in operations) {
            native(
                operation,
            ).assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
        for (status in listOf("archived", "missing", "unlinked")) {
            compose.runOnIdle {
                sourceStatus = if (status == "archived") ActivityTemplateSourceStatus(SOURCE, 4, true) else null
                if (status ==
                    "unlinked"
                ) {
                    canonical =
                        canonical.copy(
                            activitySnapshots =
                                canonical.activitySnapshots.mapValues { (_, value) ->
                                    value.copy(sourceTemplateId = null, sourceRevision = null)
                                },
                        )
                }
                controller.retry()
            }
            ready(controller)
            compose.onNodeWithText(text(R.string.sequence_editor_update_source)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.sequence_editor_update_from_source)).assertDoesNotExist()
            native(R.string.sequence_editor_save_as_new_template).assertIsEnabled()
            val expected =
                when (status) {
                    "archived" -> text(R.string.sequence_source_archived, 4L)
                    "missing" -> text(R.string.sequence_source_missing)
                    else -> text(R.string.sequence_source_unlinked)
                }
            val descriptions =
                native(
                    R.string.sequence_editor_save_as_new_template,
                ).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            assertTrue(descriptions.single().contains(expected))
        }
        assertEquals(0, writes)
    }

    @Test
    fun retainedNativeSourceCallbacksCannotReviveAfterDisclosureModeCanonicalPublicationHiddenOrDetachedRoute() {
        val controller = present()
        open()
        val retained = operations.map { click(native(it)) }
        activate(native(R.string.sequence_editor_close_step))
        open()
        compose.runOnIdle { retained.forEach { it() } }
        assertEquals(0, writes)
        for (change in listOf("mode", "publication", "hidden", "attachment")) {
            val callbacks = operations.map { click(native(it)) }
            compose.runOnIdle {
                when (change) {
                    "mode" -> {
                        controller.enterManipulation(DraftIdentity.Existing(STEP))
                        controller.discardManipulation()
                    }
                    "publication" -> {
                        sourceStatus = ActivityTemplateSourceStatus(SOURCE, 5, false)
                        controller.retry()
                    }
                    "hidden" -> visible.value = false
                    "attachment" -> attachment.value++
                }
            }
            ready(controller)
            compose.waitForIdle()
            compose.runOnIdle { callbacks.forEach { it() } }
            assertEquals(change, 0, writes)
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            if (change == "attachment") open()
        }
        assertEquals(0L, controller.state.value.appliedGeneration)
    }

    @Test
    fun nativeOrdinaryFailureAndSourceConflictPreserveSessionAndRequireExplicitAction() {
        val controller = present()
        open()
        compose.runOnIdle {
            failure = "Source is archived"
            release.complete(Unit)
        }
        activate(native(R.string.sequence_editor_update_source))
        compose.waitUntil(5_000) { controller.state.value.save is SequenceTemplateEditorSave.Failure }
        assertFalse((controller.state.value.save as SequenceTemplateEditorSave.Failure).committedAwaitingReload)
        assertEquals(1L, canonical.sequence.revision)
        compose.onLazyNode(hasText(text(R.string.sequence_source_failure))).assertIsDisplayed()
        val retiredReload = click(native(R.string.activity_editor_reload))
        assertEquals(0, deliveries)
        compose.runOnIdle { failure = "ActivityTemplate revision changed concurrently" }
        activate(native(R.string.sequence_editor_update_source))
        compose.waitUntil(5_000) {
            (controller.state.value.save as? SequenceTemplateEditorSave.Failure)?.isConflict ==
                true
        }
        assertEquals(2, writes)
        compose.onLazyNode(hasText(text(R.string.sequence_source_conflict))).assertIsDisplayed()
        compose.runOnIdle { retiredReload() }
        assertEquals(1, reads)
        assertTrue((controller.state.value.save as SequenceTemplateEditorSave.Failure).isConflict)
        val rejected = canonical
        activate(native(R.string.activity_editor_reload))
        ready(controller)
        assertEquals(rejected.toAuthoringDraft(), controller.state.value.readyDraft())
        assertEquals(2, writes)
    }

    @Test
    fun sourceButtonsRemainContextualKeyboardReachableAndSizedAtEnglishRussianCombinedScaleExtremes() {
        scaled = true
        present()
        open()
        for (language in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
            for ((interfaceScale, textScale) in listOf(90 to 90, 120 to 130)) {
                compose.runOnIdle {
                    locale.value = language
                    appearance.value =
                        AppearancePreferences(interfaceScalePercent = interfaceScale, textScalePercent = textScale)
                }
                compose.waitForIdle()
                for (operation in operations) {
                    val node = native(operation).assertIsDisplayed().assertIsEnabled()
                    val semantics = node.fetchSemanticsNode()
                    val description = semantics.config[SemanticsProperties.ContentDescription].single()
                    assertTrue(description.contains(text(R.string.sequence_manipulation_top_step, "Same name", 1)))
                    assertTrue(description.contains(text(R.string.sequence_source_active, 4L)))
                    assertTrue(description.contains(text(R.string.sequence_source_frozen_revision, 2L)))
                    val visualBounds = semantics.boundsInRoot
                    // Material reserves a native touch target beyond its compact visual surface.
                    val bounds = semantics.touchBoundsInRoot
                    val minimum = 48f * (localizedContext ?: compose.activity).resources.displayMetrics.density
                    val diagnostic =
                        "language=$language scale=$interfaceScale/$textScale operation=${text(operation)} " +
                            "visual=$visualBounds touch=$bounds minimum=$minimum"
                    assertTrue(diagnostic, bounds.width + 1 >= minimum)
                    assertTrue(diagnostic, bounds.height + 1 >= minimum)
                    assertTrue(diagnostic, visualBounds.left >= 0f)
                    assertTrue(diagnostic, visualBounds.right <= compose.activity.resources.displayMetrics.widthPixels)
                }
                val first = native(R.string.sequence_editor_update_from_source)
                keyboard.enter(first)
                first.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.awaitFocus(first)
                keyboard.sendKey(first, KeyEvent.KEYCODE_TAB)
                val second = native(R.string.sequence_editor_update_source)
                keyboard.awaitFocus(second)
                second.performKeyInput {
                    keyDown(Key.ShiftLeft)
                    pressKey(Key.Tab)
                    keyUp(Key.ShiftLeft)
                }
                keyboard.awaitFocus(first)
            }
        }
        assertEquals(0, writes)
    }

    @Test
    fun equalNameStepsInDifferentContainersExposeExactSourceAndNeverRebindRetainedOperations() {
        val controller = present()
        open()
        val firstCallbacks = operations.map { click(native(it)) }
        activate(native(R.string.sequence_editor_close_step))
        val child =
            text(
                R.string.sequence_manipulation_child,
                "Same name",
                1,
                text(R.string.sequence_manipulation_repeat, 2, 2),
            )
        activate(
            compose.onLazyNode(
                hasContentDescription(
                    text(R.string.activity_editor_control_context, text(R.string.sequence_editor_edit_step), child),
                ),
            ),
        )
        val update = native(R.string.sequence_editor_update_source)
        val description = update.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description.contains(child))
        assertTrue(description.contains(text(R.string.sequence_source_active, 9L)))
        compose.runOnIdle { firstCallbacks.forEach { it() } }
        assertEquals(0, writes)
        val untouched = canonical.activitySnapshots.getValue(ActivitySnapshotId("snapshot"))
        activate(update)
        compose.waitUntil(5_000) { started.isCompleted }
        assertEquals(listOf(SECOND_STEP), targets)
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(5_000) { deliveries == 1 }
        assertEquals(untouched, canonical.activitySnapshots.getValue(ActivitySnapshotId("snapshot")))
        assertEquals(1, writes)
        assertEquals(1L, controller.state.value.appliedGeneration)
    }

    private fun present(): SequenceTemplateEditorController {
        val controller =
            SequenceTemplateEditorController(
                scope,
                SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                {
                    reads++
                    if (failRead) error("Canonical read unavailable") else canonical
                },
                { error("Source labels must never load picker catalog") },
                { _, _, _ -> error("No create") },
                { _, _, _, _ -> error("No save") },
                Instant::now,
                loadSourceStatuses = {
                    sourceStatus?.let { mapOf(SOURCE to it) }.orEmpty() +
                        (OTHER_SOURCE to ActivityTemplateSourceStatus(OTHER_SOURCE, 9, false))
                },
                updateFromSource = { _, target, _, _ -> write(target) },
                updateSource = { _, target, _, _, _ -> write(target) },
                saveAsNewSource = { _, target, _, _ -> write(target) },
            )
        editor = controller
        val session = SequenceTemplateEditorRouteSession(controller.target, controller)
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    setLocale(this@SequenceStepSourceAccessibilityTest.locale.value)
                    if (scaled) {
                        fontScale =
                            2f
                    }
                }
            val context = compose.activity.createConfigurationContext(configuration)
            localizedContext = context
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, if (scaled) 2f else density.fontScale),
            ) {
                LifeTracingAppearance(appearance.value) {
                    key(attachment.value) {
                        SequenceTemplateEditorRoute(controller, onApplied = {
                            session.deliverApplied(it) { deliveries++ }
                        }, isRouteVisible = visible.value) {}
                    }
                }
            }
        }
        ready(controller)
        return controller
    }

    private suspend fun write(target: SequenceNodeId) {
        assertTrue(target == STEP || target == SECOND_STEP)
        targets += target
        writes++
        started.complete(Unit)
        release.await()
        failure?.let { error(it) }
        canonical = canonical.copy(sequence = canonical.sequence.copy(revision = canonical.sequence.revision + 1))
    }

    private fun ready(controller: SequenceTemplateEditorController) =
        compose.waitUntil(5_000) {
            controller.state.value.load is SequenceTemplateEditorLoad.Ready
        }

    private fun open() =
        activate(
            compose.onLazyNode(
                hasContentDescription(
                    text(
                        R.string.activity_editor_control_context,
                        text(R.string.sequence_editor_edit_step),
                        text(R.string.sequence_manipulation_top_step, "Same name", 1),
                    ),
                ),
            ),
        )

    private fun native(id: Int) = compose.onLazyNode(hasText(text(id)) and hasClickAction())

    private fun click(node: SemanticsNodeInteraction) =
        node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!

    private fun text(
        id: Int,
        vararg args: Any,
    ) = (localizedContext ?: compose.activity).getString(id, *args)

    private fun activate(node: SemanticsNodeInteraction) {
        keyboard.enter(node)
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(node)
        keyboard.sendKey(node, KeyEvent.KEYCODE_ENTER)
        compose.waitForIdle()
    }

    companion object {
        private val STEP = SequenceNodeId("step")
        private val SOURCE = ActivityTemplateId("source")
        private val OTHER_SOURCE = ActivityTemplateId("other-source")
        private val SECOND_STEP = SequenceNodeId("second")
        private val operations =
            listOf(
                R.string.sequence_editor_update_from_source,
                R.string.sequence_editor_update_source,
                R.string.sequence_editor_save_as_new_template,
            )

        private fun canonical(): SequenceTemplateAuthoringState {
            val snapshot = ActivitySnapshotId("snapshot")
            val second =
                ActivityConfigSnapshot(
                    ActivitySnapshotId("second"),
                    "Same name",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                    sourceTemplateId = OTHER_SOURCE,
                    sourceRevision = 7,
                    statisticsSeriesId = null,
                    locallyModified = false,
                    createdAt = Instant.EPOCH,
                )
            return SequenceTemplateAuthoringState(
                SequenceTemplate(
                    SequenceTemplateId("sequence"),
                    "Sequence",
                    null,
                    StatisticsSeriesId("series"),
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    nodes =
                        listOf(
                            ActivityStep(STEP, 0, snapshot),
                            SequenceRepeatBlock(
                                SequenceNodeId("repeat"),
                                1,
                                2,
                                listOf(ActivityStep(SECOND_STEP, 0, ActivitySnapshotId("second"))),
                            ),
                        ),
                ),
                mapOf(
                    second.id to second,
                    snapshot to
                        ActivityConfigSnapshot(
                            snapshot,
                            "Same name",
                            null,
                            TimeTrackingMode.STOPWATCH,
                            null,
                            sourceTemplateId = SOURCE,
                            sourceRevision = 2,
                            statisticsSeriesId = null,
                            locallyModified = true,
                            createdAt = Instant.EPOCH,
                        ),
                ),
            )
        }
    }
}
