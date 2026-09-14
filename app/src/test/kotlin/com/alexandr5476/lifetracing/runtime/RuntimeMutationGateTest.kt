package com.alexandr5476.lifetracing.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeMutationGateTest {
    @Test
    fun admittedUserMutationRunsBeforeLaterDeadlineWork() =
        runBlocking {
            val gate = RuntimeMutationGate()
            val userEntered = CompletableDeferred<Unit>()
            val releaseUser = CompletableDeferred<Unit>()
            var deadlineRan = false

            val userTurn = gate.admit()
            val user =
                launch {
                    userTurn.run {
                        userEntered.complete(Unit)
                        releaseUser.await()
                    }
                }
            userEntered.await()
            val deadlineTurn = gate.admit()
            val deadline =
                launch {
                    deadlineTurn.run { deadlineRan = true }
                }

            assertFalse(deadlineRan)
            releaseUser.complete(Unit)
            user.join()
            deadline.join()
            assertTrue(deadlineRan)
        }
}
