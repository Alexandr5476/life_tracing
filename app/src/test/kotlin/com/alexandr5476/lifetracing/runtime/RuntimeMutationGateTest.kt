package com.alexandr5476.lifetracing.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeMutationGateTest {
    @Test
    fun commandCaptureRegistersBeforeADeadlineCanTakeItsTurn() =
        runBlocking {
            val gate = RuntimeMutationGate()
            val deadlineAttempted = CountDownLatch(1)
            val order = mutableListOf<String>()
            lateinit var deadlineTurn: RuntimeMutationTurn
            lateinit var deadline: Thread
            val admission =
                gate.admit {
                    deadline =
                        Thread {
                            deadlineAttempted.countDown()
                            deadlineTurn = gate.admit()
                        }.also(Thread::start)
                    assertTrue(deadlineAttempted.await(1, TimeUnit.SECONDS))
                    "user"
                }
            requireNotNull(admission).turn.run { order += admission.command }
            deadline.join(1_000)
            deadlineTurn.run { order += "deadline" }

            assertEquals(listOf("user", "deadline"), order)
        }

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
