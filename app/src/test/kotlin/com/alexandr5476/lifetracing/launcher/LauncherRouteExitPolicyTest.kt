package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LauncherRouteExitPolicyTest {
    @Test
    fun unresolvedCommitCannotExitAndEachDurableResultIsDeliveredOnce() {
        val policy = LauncherRouteExitPolicy()
        var backs = 0
        var committed = 0

        policy.requestExit(LauncherCommandState.Committing, { backs++ }, { committed++ })
        assertEquals(0, backs)
        assertEquals(0, committed)

        val result = LauncherCommandState.Committed(LauncherCommit.Activity(ActivityExecutionId("done"), false))
        policy.requestExit(result, { backs++ }, { committed++ })
        policy.onCommand(result) { committed++ }
        policy.requestExit(result, { backs++ }, { committed++ })

        assertEquals(0, backs)
        assertEquals(1, committed)
    }

    @Test
    fun ordinaryExitAndCoordinationFailureUseTheirSeparateRequiredPaths() {
        val ordinary = LauncherRouteExitPolicy()
        var backs = 0
        ordinary.requestExit(LauncherCommandState.Checking, { backs++ }, {})
        assertEquals(1, backs)

        val committed = LauncherRouteExitPolicy()
        var delivered = 0
        committed.onCommand(
            LauncherCommandState.CommittedCoordinationFailure(
                LauncherCommit.Activity(ActivityExecutionId("done"), true),
                "scheduler unavailable",
            ),
        ) { delivered++ }
        assertEquals(1, delivered)
    }
}
