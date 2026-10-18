package com.alexandr5476.lifetracing.runtime

import android.app.Application
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
        // Seed connection closes before framework foreground delivery. No graph acquisition,
        // direct coordinator call, receiver, local deadline or alarm is armed by this fixture.
        val foregroundArrival = Instant.now()
        assertTrue(logicalDeadline < foregroundArrival)
        ActivityScenario.launch(MainActivity::class.java).use {
            val coordinator = LifeTracingRuntimeGraph.from(application).coordinator
            withTimeout(10_000) { coordinator.semanticGeneration.first { it > 0 } }
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
