package com.alexandr5476.lifetracing.runtime

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.RuntimeGraphOwner
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeManifestDeadlineAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test(timeout = 60_000)
    fun productionPendingIntentReachesManifestReceiverAndDurableHistoryAfterReopen() =
        runBlocking {
            // The actual manifest receiver owns the normal application graph and database. Do not
            // inject a launcher, register another receiver, or depend on another test's live slot.
            val graph = LifeTracingRuntimeGraph.from(context)
            try {
                val persisted =
                    RuntimeRecoveryStore(context, APPLICATION_DATABASE).use { store ->
                        graph.coordinator.mutationGate.admit().run {
                            clearLiveSlot(store)
                        }
                        graph.coordinator.onRuntimeStateChanged() // Cancel any preceding in-process timer.
                        val future = seed(store, Instant.ofEpochMilli(Instant.now().toEpochMilli()), Duration.ofDays(1))
                        val futureDeadline = deadline(store)
                        val before = store.durableFacts()
                        send(futureDeadline.copy(executionId = "obsolete-" + UUID.randomUUID()))
                        assertEquals(before, store.durableFacts())
                        assertEquals(future, (store.live.getActiveRuntime() as ActiveActivityRuntime).execution)
                        graph.coordinator.mutationGate.admit().run {
                            store.live.completeActiveActivity(future.startedAt!!.plusSeconds(1))
                        }
                        graph.coordinator.onRuntimeStateChanged()

                        // No foreground recovery or local driver is armed for this overdue fixture.
                        // Only the unsuppressed production token can trigger the matching transition.
                        val original = seed(store, Instant.now().minusSeconds(600), Duration.ofSeconds(60))
                        val expected = deadline(store)
                        send(expected)
                        awaitRuntimeSystemState("manifest receiver durable completion") {
                            store.live.getActiveSession() == null
                        }
                        assertCompleted(store, original, expected)
                        val completed = store.durableFacts()
                        send(expected) // The completed transition cancelled the original system token.
                        send(expected.copy(executionId = "obsolete-" + UUID.randomUUID()))
                        assertEquals(completed, store.durableFacts())
                        Triple(original, expected, completed)
                    }
                // New physical Room connection and canonical History/active-runtime readers.
                RuntimeRecoveryStore(context, APPLICATION_DATABASE).use { reopened ->
                    assertCompleted(reopened, persisted.first, persisted.second)
                    assertEquals(persisted.third, reopened.durableFacts())
                }
            } finally {
                RuntimeRecoveryStore(context, APPLICATION_DATABASE).use(::clearLiveSlot)
                graph.coordinator.onRuntimeStateChanged()
            }
        }

    @Test(timeout = 60_000)
    fun coldProductionGraphAcquisitionReconcilesClosedPhysicalFixture() =
        runBlocking {
            val name = "cold-production-" + UUID.randomUUID()
            val isolated = IsolatedRuntimeDatabaseContext(context, name)
            var graph: LifeTracingRuntimeGraph? = null
            var closeRuntimeDatabase: () -> Unit = {}
            try {
                val original =
                    RuntimeRecoveryStore(context, name).use { store ->
                        seed(store, Instant.now().minusSeconds(600), Duration.ofSeconds(60))
                    }
                val expected =
                    RuntimeRecoveryStore(context, name).use { store -> deadline(store) }
                var acquisitions = 0
                val owner =
                    RuntimeGraphOwner { applicationContext ->
                        acquisitions++
                        LifeTracingRuntimeGraph
                            .create(applicationContext) { repository ->
                                closeRuntimeDatabase = {
                                    RuntimeRecoveryStore.closeProductionRuntimeRepository(repository)
                                }
                            }.also { graph = it }
                    }
                assertEquals(0, acquisitions)
                val finished = CompletableDeferred<Unit>()
                // Separate cold-acquisition proof uses the real factory and all real repositories,
                // clocks and platform adapters. It does not claim manifest dispatch or process death.
                launchRuntimeBroadcast(RuntimeBroadcastOperation.Deadline(expected), { finished.complete(Unit) }) {
                    LifeTracingRuntimeGraph.from(isolated, owner)
                }
                withTimeout(10_000) { finished.await() }
                assertEquals(1, acquisitions)
                graph!!.scope.coroutineContext[Job]!!.cancelAndJoin()
                closeRuntimeDatabase() // Close the production Room owner on every supported API.
                closeRuntimeDatabase = {}
                RuntimeRecoveryStore(context, name).use { reopened ->
                    assertCompleted(reopened, original, expected)
                    assertEquals(1L to 0L, reopened.executionCounts())
                }
            } finally {
                graph
                    ?.scope
                    ?.coroutineContext
                    ?.get(Job)
                    ?.cancelAndJoin()
                closeRuntimeDatabase()
                AndroidRuntimeDeadlineScheduler(context).cancel()
                context
                    .getSystemService(NotificationManager::class.java)
                    .cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
                context.deleteDatabase(name)
            }
        }

    private fun send(expected: RuntimeDeadline) {
        assertManifestDeadlineIntent(context, RuntimeDeadlineIntentCodec.intent(context, expected))
        val sent = sendDeadlineToManifest(RuntimeDeadlineIntentCodec.pendingIntent(context, expected))
        assertManifestDeadlineIntent(context, sent)
        assertEquals(expected, RuntimeDeadlineIntentCodec.decode(sent))
    }

    private fun seed(
        store: RuntimeRecoveryStore,
        startedAt: Instant,
        duration: Duration,
    ): ActivityExecution {
        val template =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft("Manifest timer " + UUID.randomUUID(), null, TimeTrackingMode.TIMER, duration),
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

    private fun clearLiveSlot(store: RuntimeRecoveryStore) {
        // Explicit singleton prerequisite/cleanup even if a preceding unrelated test left live state.
        val cleanupAt = Instant.now().plus(Duration.ofDays(36_525))
        when (store.live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> store.live.completeActiveActivity(cleanupAt)
            ActiveSessionKind.SEQUENCE -> store.live.endSequenceEarly(cleanupAt)
            null -> Unit
        }
    }

    private companion object {
        const val APPLICATION_DATABASE = "lifetracing.db"
    }
}

/** Redirect only the database filename; production graph construction and Android services stay real. */
private class IsolatedRuntimeDatabaseContext(
    base: Context,
    private val databaseName: String,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File = baseContext.getDatabasePath(scopedName(name))

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = baseContext.openOrCreateDatabase(scopedName(name), mode, factory)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase = baseContext.openOrCreateDatabase(scopedName(name), mode, factory, errorHandler)

    private fun scopedName(name: String): String = if (name == "lifetracing.db") databaseName else name
}
