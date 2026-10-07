package com.alexandr5476.lifetracing.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.executeLauncherCommand
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.LauncherLoad
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityAction
import com.alexandr5476.lifetracing.launcher.StartActivityController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class RuntimePreflightRecoveryTest {
    @Test
    fun abandonedActivityCountdownCreatesNoExecutionBeforeOrAfterPhysicalRecreation() = assertAbandoned(false)

    @Test
    fun abandonedSequenceCountdownCreatesNoExecutionBeforeOrAfterPhysicalRecreation() = assertAbandoned(true)

    private fun assertAbandoned(sequence: Boolean) =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "preflight-recovery-" + System.nanoTime()
            try {
                prepareAndDiscard(context, name, sequence)
                RuntimeRecoveryStore(context, name).use { fresh ->
                    assertEquals(0L to 0L, fresh.executionCounts())
                    assertNull(fresh.live.getActiveSession())
                    assertNull(fresh.live.getActiveRuntime())
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private suspend fun prepareAndDiscard(
        context: Context,
        name: String,
        sequence: Boolean,
    ) {
        RuntimeRecoveryStore(context, name).use { store ->
            val activity =
                store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Preflight",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                        settings = ActivityTemplateSettings(startCountdown = Duration.ofSeconds(5)),
                    ),
                    createdAt = Instant.EPOCH,
                )
            val target =
                if (sequence) {
                    val template =
                        store.authoring.createSequenceTemplate(
                            SequenceTemplateDraft(
                                "Preflight Sequence",
                                null,
                                settings = SequenceTemplateSettings(sequenceStartCountdown = Duration.ofSeconds(7)),
                                nodes =
                                    listOf(
                                        SequenceNodeDraft.Step(
                                            ActivityStepDraft(
                                                DraftIdentity.New("step"),
                                                0,
                                                StepActivityDraft.FromTemplate(activity.id),
                                            ),
                                        ),
                                    ),
                            ),
                            createdAt = Instant.EPOCH,
                        )
                    LibraryTemplateId.Sequence(template.id)
                } else {
                    LibraryTemplateId.Activity(activity.id)
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val preflight = HeldPreflight()
            val controller =
                StartActivityController(
                    scope,
                    { emptyList() },
                    { emptyList() },
                    { emptyList() },
                    { LibraryContents(emptyList(), emptyList(), emptyList()) },
                    store.library::getLaunchTarget,
                    {},
                    { store.live.getActiveSession() != null },
                    { command -> executeLauncherCommand(command, store.activityCommands, store.library) },
                    {},
                    WallClock { Instant.EPOCH },
                    { ZoneOffset.UTC },
                    preflight,
                    initialLiveConflict = { launch -> store.library.hasLiveLaunchConflict(launch.id, launch.revision) },
                )
            try {
                withTimeout(5_000) { controller.state.first { it.home !is LauncherLoad.Loading } }
                controller.dispatch(StartActivityAction.Select(target))
                withTimeout(5_000) { controller.state.first { it.selected is LauncherLoad.Content } }
                controller.dispatch(StartActivityAction.Launch())
                val state =
                    withTimeout(5_000) {
                        controller.state.first { it.command is LauncherCommandState.Preflight }
                    }
                withTimeout(5_000) { preflight.armed.await() }
                assertEquals(
                    Duration.ofSeconds(if (sequence) 7 else 5),
                    (state.command as LauncherCommandState.Preflight).duration,
                )
                assertEquals(0L to 0L, store.executionCounts())
                assertNull(store.live.getActiveSession())
                assertNull(store.live.getActiveRuntime())
            } finally {
                controller.close()
                scope.cancel()
                preflight.discard()
            }
        }
        // The function returns no controller, callback, repository, or open database to the new process.
    }

    private class HeldPreflight : PreflightScheduler {
        val armed = CompletableDeferred<Unit>()
        private var callback: (() -> Unit)? = null

        override fun schedule(
            duration: Duration,
            onBoundary: () -> Unit,
        ): PreflightHandle {
            callback = onBoundary
            armed.complete(Unit)
            return PreflightHandle { callback = null }
        }

        fun discard() {
            callback = null
        }
    }
}
