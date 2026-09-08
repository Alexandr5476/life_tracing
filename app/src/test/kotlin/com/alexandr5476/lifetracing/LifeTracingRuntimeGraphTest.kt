package com.alexandr5476.lifetracing

import com.alexandr5476.lifetracing.daily.DailyController
import com.alexandr5476.lifetracing.daily.DailyLoadState
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryRoot
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeReconciliationResult
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.library.LibraryController
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeCoordinator
import com.alexandr5476.lifetracing.runtime.InProcessRuntimeDeadlineDriver
import com.alexandr5476.lifetracing.runtime.RuntimeDeadlineScheduler
import com.alexandr5476.lifetracing.runtime.RuntimeFeedbackDispatcher
import com.alexandr5476.lifetracing.runtime.RuntimeNotificationPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class LifeTracingRuntimeGraphTest {
    @Test
    @Suppress("LongMethod")
    fun runtimeRecoveryLeavesDailyIdleUntilItsFirstAcquisition() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val boundary = RecordingBoundary()
            var controllerCreations = 0
            var launcherCreations = 0
            var reads = 0
            val graph =
                LifeTracingRuntimeGraph(
                    scope,
                    coordinator(),
                    DailyControllerOwner {
                        controllerCreations++
                        DailyController(
                            scope,
                            {
                                reads++
                                DailyRead(emptyList(), emptyList(), emptyList(), null)
                            },
                            {},
                            {},
                            MutableStateFlow(0L),
                            { null },
                            FixedWallClock,
                            { ZoneOffset.UTC },
                            { ActivityExecutionPauseId("pause") },
                            boundary,
                        )
                    },
                    {
                        launcherCreations++
                        launcher(scope)
                    },
                    {
                        LibraryController(
                            scope,
                            { LibraryRoot(LibraryContents(emptyList(), emptyList(), emptyList()), emptyList()) },
                            { error("unused folder reader") },
                            { error("unused path reader") },
                            { _, _ -> emptyList() },
                        )
                    },
                    { _, _ -> error("unused editor") },
                )

            graph.coordinator.recoverAndSchedule()
            assertEquals(0, controllerCreations)
            assertEquals(0, reads)
            assertEquals(0, boundary.arms)

            val first = graph.dailyController
            withTimeout(2_000) { first.state.first { it.load !is DailyLoadState.Loading } }
            val repeated = graph.dailyController

            assertSame(first, repeated)
            assertEquals(1, controllerCreations)
            assertEquals(1, reads)
            assertEquals(1, boundary.arms)
            val firstLauncher = graph.createStartActivityController()
            val secondLauncher = graph.createStartActivityController()
            assertNotSame(firstLauncher, secondLauncher)
            assertEquals(2, launcherCreations)
            firstLauncher.close()
            secondLauncher.close()
            first.close()
            scope.cancel()
        }

    private fun coordinator() =
        AndroidRuntimeCoordinator(
            { null },
            { RuntimeReconciliationResult(null, emptyList()) },
            FixedWallClock,
            object : MonotonicClock {
                override fun elapsedRealtimeMillis(): Long = 0
            },
            object : RuntimeDeadlineScheduler {
                override fun schedule(deadline: RuntimeDeadline) = Unit

                override fun cancel() = Unit

                override fun canScheduleExactRuntimeDeadlines(): Boolean = true
            },
            object : InProcessRuntimeDeadlineDriver {
                override fun arm(
                    deadline: RuntimeDeadline,
                    anchor: WallMonotonicAnchor,
                    callback: suspend (RuntimeDeadline) -> Unit,
                ) = Unit

                override fun cancel() = Unit
            },
            RuntimeFeedbackDispatcher {},
            object : RuntimeNotificationPublisher {
                override fun publish(
                    runtime: ActiveRuntime?,
                    completion: RuntimeDeadlineFeedback?,
                ) = Unit

                override fun canPostRuntimeNotifications(): Boolean = true
            },
            {},
        )

    private fun launcher(scope: CoroutineScope) =
        StartActivityController(
            scope,
            { emptyList() },
            { emptyList() },
            { emptyList() },
            { LibraryContents(emptyList(), emptyList(), emptyList()) },
            { error("unused target reader") },
            {},
            { false },
            { error("unused launcher writer") },
            {},
            FixedWallClock,
            { ZoneOffset.UTC },
            PreflightScheduler { _, _ -> PreflightHandle {} },
        )

    private class RecordingBoundary : LocalDateBoundaryScheduler {
        var arms = 0

        override fun arm(
            now: Instant,
            zoneId: java.time.ZoneId,
            onBoundary: () -> Unit,
        ) {
            arms++
        }
    }

    private object FixedWallClock : WallClock {
        override fun now(): Instant = Instant.parse("2026-08-20T10:00:00Z")
    }
}
