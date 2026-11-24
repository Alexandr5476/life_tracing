package com.alexandr5476.lifetracing.runtime

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.PlanSchedulingStore
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.actionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeManifestDeadlineAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as RuntimeIsolationApplication

    @Test(timeout = 60_000)
    fun productionPendingIntentReachesManifestReceiverAndDurableHistoryAfterReopen() =
        runBlocking {
            assertDatabaseUnchanged { runManifestAcceptance(cue = ManifestNotificationCue(failPlayback = true)) }
        }

    @Test(timeout = 60_000)
    fun repeatedManifestFixturePreservesUnrelatedRunningPlanAndEveryDurableTable() =
        runBlocking { assertUnrelatedPlanPreserved(paused = false, failAfterSeed = false) }

    @Test(timeout = 60_000)
    fun repeatedManifestFixturePreservesUnrelatedPausedPlanAndEveryDurableTable() =
        runBlocking { assertUnrelatedPlanPreserved(paused = true, failAfterSeed = false) }

    @Test(timeout = 60_000)
    fun failedManifestFixturePreservesUnrelatedPlanAndDeletesItsLiveFixture() =
        runBlocking { assertUnrelatedPlanPreserved(paused = true, failAfterSeed = true) }

    @Test(timeout = 60_000)
    fun occupiedTimedAlarmAndNotificationRejectManifestFixtureWithoutChangingSystemOrDurableState() =
        runBlocking { assertOccupiedPlatformPreserved(TimeTrackingMode.TIMER) }

    @Test(timeout = 60_000)
    fun occupiedPlanAlarmAndNotificationRejectManifestFixtureWithoutChangingPlanOrSystemState() =
        runBlocking { assertOccupiedPlatformPreserved(TimeTrackingMode.TIMER, planLinked = true) }

    @Test(timeout = 60_000)
    fun occupiedStopwatchNotificationWithoutAlarmRejectsManifestFixtureWithoutChangingState() =
        runBlocking { assertOccupiedPlatformPreserved(TimeTrackingMode.STOPWATCH) }

    @Test(timeout = 60_000)
    fun occupiedUnscheduledTokenRejectsManifestFixtureWithoutReplacingItsPayload() =
        runBlocking { assertOccupiedPlatformPreserved(TimeTrackingMode.TIMER, armAlarm = false, notify = false) }

    private suspend fun assertOccupiedPlatformPreserved(
        mode: TimeTrackingMode,
        planLinked: Boolean = false,
        armAlarm: Boolean = true,
        notify: Boolean = true,
    ) {
        assertDatabaseUnchanged {
            assertManifestPlatformBaselinePreserved(
                application,
                mode,
                planLinked,
                armAlarm,
                notify,
            ) { afterSeed, afterDelivery ->
                runManifestAcceptance(afterSeed, afterDelivery)
            }
            // Rejected acquisition must release its lease; prove clean manifest acceptance here,
            // without relying on another testcase to discover leaked ownership.
            runManifestAcceptance()
        }
    }

    private suspend fun assertUnrelatedPlanPreserved(
        paused: Boolean,
        failAfterSeed: Boolean,
    ) {
        assertDatabaseUnchanged {
            application.withDisposableDatabase { baselineEnvironment ->
                val startedAt = Instant.ofEpochMilli(Instant.now().minusSeconds(60).toEpochMilli())
                val originalPlan =
                    planStore().use { store ->
                        val template =
                            store.authoring.createActivityTemplate(
                                ActivityTemplateDraft(
                                    "Unrelated Plan",
                                    null,
                                    TimeTrackingMode.TIMER,
                                    Duration.ofDays(1),
                                ),
                                createdAt = startedAt,
                            )
                        val plan =
                            store.plans.createActivityPlanFromTemplate(
                                template.id,
                                PlanSchedule.FloatingDay(startedAt.atOffset(ZoneOffset.UTC).toLocalDate()),
                                startedAt,
                            )
                        store.live.startActivityFromPlan(plan.actionIdentity(), startedAt, startedAt, ZoneOffset.UTC)
                        if (paused) {
                            store.live.pauseActiveActivity(
                                ActivityExecutionPauseId(UUID.randomUUID().toString()),
                                startedAt.plusSeconds(1),
                            )
                        }
                        plan
                    }
                val originalRuntime =
                    RuntimeRecoveryStore(context, APPLICATION_DATABASE).use { it.live.getActiveRuntime() }
                assertEquals(PlanEntryStatus.PLANNED, originalPlan.status)
                assertNotNull(originalRuntime)
                // The exact helper exercised by the acceptance testcase runs twice against a
                // nonempty baseline. Comparing all tables detects both retained rows and cleanup
                // that terminates a non-owned session or fulfills its Plan.
                repeat(2) { attempt ->
                    assertDatabaseUnchanged {
                        if (failAfterSeed) {
                            val failure = IllegalStateException("injected after fixture live write")
                            try {
                                if (attempt == 0) {
                                    runManifestAcceptance(afterSeed = { throw failure })
                                } else {
                                    runManifestAcceptance(afterDelivery = { throw failure })
                                }
                                fail("Fixture must reach the injected failure")
                            } catch (actual: IllegalStateException) {
                                assertSame(failure, actual)
                            }
                        } else {
                            runManifestAcceptance()
                        }
                    }
                    RuntimeRecoveryStore(context, APPLICATION_DATABASE).use { reopened ->
                        assertEquals(originalRuntime, reopened.live.getActiveRuntime())
                    }
                    planStore().use { reopened ->
                        assertEquals(originalPlan, reopened.plans.getPlan(originalPlan.id))
                    }
                    assertEquals("Unrelated graph must remain cold", 0, baselineEnvironment.acquisitions)
                }
            }
        }
    }

    /** Includes every source/snapshot/interval/Plan/Statistics table and physical-file retention. */
    private suspend fun assertDatabaseUnchanged(block: suspend () -> Unit) {
        val path = context.getDatabasePath(APPLICATION_DATABASE)
        // A baseline read must not migrate or initialize an existing application database.
        val before = RuntimeRecoveryStore.readOnlyDurableFacts(context, APPLICATION_DATABASE)
        val filesBefore = databaseFiles()
        val ownerBefore = application.runtimeGraphOwner
        try {
            block()
        } finally {
            assertSame(ownerBefore, application.runtimeGraphOwner)
            assertEquals(filesBefore, databaseFiles())
            if (before == null) {
                assertFalse("Fixture must not create the ordinary application database", path.exists())
            } else {
                assertEquals(before, RuntimeRecoveryStore.readOnlyDurableFacts(context, APPLICATION_DATABASE))
            }
        }
    }

    private fun databaseFiles(): Set<String> =
        context
            .databaseList()
            .filterNot { it.endsWith("-wal") || it.endsWith("-shm") || it.endsWith("-journal") }
            .toSet()

    private fun planStore(): PlanSchedulingStore =
        PlanSchedulingStore(context, APPLICATION_DATABASE, CurrentZoneIdProvider { ZoneOffset.UTC })

    private suspend fun runManifestAcceptance(
        afterSeed: () -> Unit = {},
        afterDelivery: () -> Unit = {},
        cue: ManifestNotificationCue? = null,
    ) {
        application.withDisposableRuntime(notificationCue = cue) { environment ->
            val persisted =
                RuntimeRecoveryStore(context, APPLICATION_DATABASE).use { store ->
                    assertNull(store.live.getActiveSession())
                    assertEquals(0, environment.acquisitions)
                    val future = seed(store, Instant.ofEpochMilli(Instant.now().toEpochMilli()), Duration.ofDays(1))
                    afterSeed()
                    val futureDeadline = deadline(store)
                    val before = store.durableFacts()
                    // First graph acquisition is cold and belongs to the actual manifest receiver.
                    send(futureDeadline.copy(executionId = "obsolete-" + UUID.randomUUID()))
                    assertEquals(1, environment.acquisitions)
                    assertEquals(before, store.durableFacts())
                    assertEquals(future, (store.live.getActiveRuntime() as ActiveActivityRuntime).execution)
                    cue?.assertNoResolution()
                    afterDelivery()
                    val graph = LifeTracingRuntimeGraph.from(context)
                    graph.coordinator.mutationGate.admit().run {
                        store.live.completeActiveActivity(future.startedAt!!.plusSeconds(1))
                    }
                    graph.coordinator.onRuntimeStateChanged()
                    cue?.assertNoResolution()

                    // No foreground recovery or local driver is armed for this overdue fixture.
                    // Only the unsuppressed production token can trigger the matching transition.
                    val original = seed(store, Instant.now().minusSeconds(600), Duration.ofSeconds(60))
                    val expected = deadline(store)
                    if (cue == null) {
                        send(expected)
                    } else {
                        withRuntimeNotificationsEnabled(context) { _, manager ->
                            send(expected)
                            cue.assertPlayback()
                            assertSilentCompletion(manager)
                        }
                    }
                    awaitRuntimeSystemState("manifest receiver durable completion") {
                        store.live.getActiveSession() == null
                    }
                    assertCompleted(store, original, expected)
                    val completed = store.durableFacts()
                    send(expected)
                    send(expected.copy(executionId = "obsolete-" + UUID.randomUUID()))
                    assertEquals(completed, store.durableFacts())
                    cue?.assertNoResolution()
                    Triple(original, expected, completed)
                }
            RuntimeRecoveryStore(context, APPLICATION_DATABASE).use { reopened ->
                assertCompleted(reopened, persisted.first, persisted.second)
                assertEquals(persisted.third, reopened.durableFacts())
            }
        }
    }

    @Test(timeout = 60_000)
    fun coldProductionGraphAcquisitionReconcilesClosedPhysicalFixture() =
        runBlocking {
            val cue = ManifestNotificationCue(failPlayback = false)
            assertDatabaseUnchanged {
                application.withDisposableRuntime(notificationCue = cue) { environment ->
                    val original =
                        RuntimeRecoveryStore(context, environment.name).use { store ->
                            seed(store, Instant.now().minusSeconds(600), Duration.ofSeconds(60))
                        }
                    val expected =
                        RuntimeRecoveryStore(context, environment.name).use { store -> deadline(store) }
                    assertEquals(0, environment.acquisitions)
                    withRuntimeNotificationsEnabled(context) { _, manager ->
                        // Acquisition and dispatch use the production launcher and graph.
                        dispatchAndAwaitFinish(RuntimeBroadcastOperation.Deadline(expected))
                        cue.assertPlayback()
                        assertSilentCompletion(manager)
                    }
                    assertEquals(1, environment.acquisitions)
                    environment.discardGraph()
                    val completed =
                        RuntimeRecoveryStore(context, environment.name).use { reopened ->
                            assertCompleted(reopened, original, expected)
                            assertEquals(1L to 0L, reopened.executionCounts())
                            reopened.durableFacts()
                        }

                    val reconstructed = LifeTracingRuntimeGraph.from(context)
                    assertEquals(2, environment.acquisitions)
                    cue.assertNoResolution()
                    dispatchAndAwaitFinish(RuntimeBroadcastOperation.Deadline(expected))
                    reconstructed.coordinator.onForeground()
                    reconstructed.coordinator.recoverAndSchedule()
                    cue.assertNoResolution()
                    environment.discardGraph()
                    RuntimeRecoveryStore(context, environment.name).use { reopened ->
                        assertCompleted(reopened, original, expected)
                        assertEquals(completed, reopened.durableFacts())
                    }
                }
            }
        }

    private suspend fun dispatchAndAwaitFinish(operation: RuntimeBroadcastOperation) {
        val finished = CompletableDeferred<Unit>()
        launchRuntimeBroadcast(operation, { finished.complete(Unit) }) { LifeTracingRuntimeGraph.from(context) }
        withTimeout(10_000) { finished.await() }
    }

    @Suppress("DEPRECATION") // Inspect the posted payload as well as the legacy defaults it could carry.
    private fun assertSilentCompletion(manager: NotificationManager) {
        val title = context.getString(R.string.runtime_notification_due)

        fun completion() =
            manager.activeNotifications
                .singleOrNull { it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID && it.tag == null }
                ?.notification
                ?.takeIf { it.extras.getString(Notification.EXTRA_TITLE) == title }

        awaitRuntimeSystemState("silent runtime completion") { completion() != null }
        val notification = requireNotNull(completion())
        val channelId = AndroidRuntimeNotificationPublisher.CHANNEL_ID
        assertEquals(channelId, notification.channelId)
        val channel = requireNotNull(manager.getNotificationChannel(channelId))
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertEquals(0, notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
    }

    private fun send(expected: RuntimeDeadline) {
        assertManifestDeadlineIntent(context, RuntimeDeadlineIntentCodec.intent(context, expected))
        assertEquals(expected, sendDeadlineToManifest(RuntimeDeadlineIntentCodec.pendingIntent(context, expected)))
    }

    private fun seed(
        store: RuntimeRecoveryStore,
        startedAt: Instant,
        duration: Duration,
    ): ActivityExecution {
        val template =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Manifest timer " + UUID.randomUUID(),
                    null,
                    TimeTrackingMode.TIMER,
                    duration,
                    ActivityTemplateSettings(timerEndSound = true, timerEndVibration = true),
                ),
                createdAt = startedAt,
            )
        store.activityCommands.startLive(
            ActivityEntrySource.Template(template.id),
            startedAt,
            startedAt,
            ZoneOffset.UTC,
        )
        return (store.live.getActiveRuntime() as ActiveActivityRuntime).execution
    }

    private fun deadline(store: RuntimeRecoveryStore): RuntimeDeadline =
        requireNotNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(store.live.getActiveRuntime())))

    private fun assertCompleted(
        store: RuntimeRecoveryStore,
        original: ActivityExecution,
        expected: RuntimeDeadline,
    ) {
        val detail = requireNotNull(store.history.getActivityDetail(original.id))
        assertEquals(original.id, detail.root.executionId)
        assertEquals(original.snapshotId, detail.root.snapshotId)
        assertEquals(expected.at, detail.root.completedAt)
        assertEquals(Duration.ofSeconds(60), detail.root.activeDuration)
        assertEquals(ZoneOffset.UTC, detail.originalZoneId)
        assertEquals(ActivityExecutionStatus.COMPLETED, store.activity(original.id).status)
        assertNull(store.live.getActiveSession())
        assertNull(store.live.getActiveRuntime())
    }

    private companion object {
        const val APPLICATION_DATABASE = "lifetracing.db"
    }

    private class ManifestNotificationCue(
        private val failPlayback: Boolean,
    ) : RuntimeNotificationCue {
        private val selectedCue = Uri.parse("content://runtime-test/default-notification")
        private val resolutions = Channel<Int>(Channel.UNLIMITED)
        private val playback = Channel<Uri>(Channel.UNLIMITED)

        override fun resolve(type: Int): Uri {
            check(resolutions.trySend(type).isSuccess)
            return selectedCue
        }

        override suspend fun play(uri: Uri) {
            playback.send(uri)
            check(!failPlayback) { "Playback unavailable" }
        }

        suspend fun assertPlayback() {
            withTimeout(10_000) {
                assertEquals(RingtoneManager.TYPE_NOTIFICATION, resolutions.receive())
                assertEquals(selectedCue, playback.receive())
            }
        }

        suspend fun assertNoResolution() {
            // The receiver can finish before the graph's asynchronous sound consumer runs.
            assertNull("Unexpected asynchronous cue resolution", withTimeoutOrNull(250) { resolutions.receive() })
        }
    }
}
