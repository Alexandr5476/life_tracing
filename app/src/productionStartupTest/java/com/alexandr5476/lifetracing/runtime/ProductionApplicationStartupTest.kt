package com.alexandr5476.lifetracing.runtime

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.LifeTracingApplication
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.MainActivity
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Runs alone under AndroidJUnitRunner, with the manifest Application and a disposable package. */
@RunWith(AndroidJUnit4::class)
class ProductionApplicationStartupTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestApplicationForegroundRecoversOverdueTimerAtLogicalDeadlineAndPreservesPausedTimer() =
        runBlocking {
            assertEquals(LifeTracingApplication::class.java.name, installedApplicationClassName(application))
            assertEquals(
                "Framework must instantiate the installed declaration",
                LifeTracingApplication::class.java,
                application.javaClass,
            )
            assertTrue(
                "Run only in the dedicated disposable installation",
                application.packageName.endsWith(".debug.startup"),
            )
            assertRecoveryBroadcastsDisabled()
            assertFalse("Startup requires a fresh installation", application.getDatabasePath(DATABASE).exists())
            RuntimePlatformFixture.acquire(application).use {
                try {
                    recoverOverdueTimer()
                    preservePausedTimer()
                } finally {
                    // Stop producers before releasing package-wide platform slots. The dedicated
                    // installation owns its database until framework process teardown/uninstall;
                    // never unlink an open production Room database or another package's data.
                    LifeTracingRuntimeGraph
                        .from(application)
                        .scope
                        .coroutineContext[Job]!!
                        .cancelAndJoin()
                }
            }
        }

    private suspend fun recoverOverdueTimer() {
        val original = RuntimeRecoveryStore(application, DATABASE).use { seed(it) }
        val logicalDeadline = original.startedAt!!.plusSeconds(60)
        assertTrue(application.getDatabasePath(DATABASE).isFile)
        // Seed connection closes before framework foreground delivery. Graph acquisition below
        // only captures a generation; no coordinator call, receiver or deadline is armed here.
        val coordinator = LifeTracingRuntimeGraph.from(application).coordinator
        val generation = coordinator.semanticGeneration.value
        val foregroundArrival = Instant.now()
        assertTrue(logicalDeadline < foregroundArrival)
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10_000) { coordinator.semanticGeneration.first { it > generation } }
        }
        // A new physical Room connection and canonical readers, not an in-memory runtime result.
        RuntimeRecoveryStore(application, DATABASE).use { reopened ->
            val detail = requireNotNull(reopened.history.getActivityDetail(original.id))
            assertEquals(original.id, detail.root.executionId)
            assertEquals(original.snapshotId, detail.root.snapshotId)
            assertEquals(logicalDeadline, detail.root.completedAt)
            assertEquals(Duration.ofSeconds(60), detail.root.activeDuration)
            assertEquals(ZoneOffset.UTC, detail.originalZoneId)
            assertEquals(ActivityExecutionStatus.COMPLETED, reopened.activity(original.id).status)
            assertNull(reopened.live.getActiveSession())
            assertNull(reopened.live.getActiveRuntime())
        }
    }

    private suspend fun preservePausedTimer() {
        val original =
            RuntimeRecoveryStore(application, DATABASE).use { store ->
                val execution = seed(store)
                store.live.pauseActiveActivity(
                    ActivityExecutionPauseId(UUID.randomUUID().toString()),
                    execution.startedAt!!.plusSeconds(1),
                )
                requireNotNull(store.live.getActiveRuntime()) as ActiveActivityRuntime
            }
        val coordinator = LifeTracingRuntimeGraph.from(application).coordinator
        val generation = coordinator.semanticGeneration.value
        ActivityScenario.launch(MainActivity::class.java).use {
            // A bounded lifecycle observation, never a direct onForeground invocation.
            withTimeout(10_000) { coordinator.semanticGeneration.first { it > generation } }
        }
        RuntimeRecoveryStore(application, DATABASE).use { reopened ->
            assertEquals(original, reopened.live.getActiveRuntime())
            assertEquals(original.session, reopened.live.getActiveSession())
            val execution = reopened.activity(original.execution.id)
            assertEquals(ActivityExecutionStatus.PAUSED, execution.status)
            assertNull(execution.completedAt)
            assertNull(NextRuntimeDeadlineResolver.resolve(original))
        }
    }

    private fun assertRecoveryBroadcastsDisabled() {
        val component = ComponentName(application, RuntimeRecoveryReceiver::class.java)
        val receiver =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.packageManager.getReceiverInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                application.packageManager.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
            }
        assertFalse("Startup fixture must exclude unsolicited recovery broadcasts", receiver.enabled)
    }

    private fun seed(store: RuntimeRecoveryStore): ActivityExecution {
        val startedAt = Instant.ofEpochMilli(Instant.now().minusSeconds(600).toEpochMilli())
        val template =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Startup timer " + UUID.randomUUID(),
                    null,
                    TimeTrackingMode.TIMER,
                    Duration.ofSeconds(60),
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

    private companion object {
        const val DATABASE = "lifetracing.db"
    }
}
