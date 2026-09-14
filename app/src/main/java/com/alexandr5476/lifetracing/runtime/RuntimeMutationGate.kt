package com.alexandr5476.lifetracing.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** FIFO admission order for user commands and automatic live-runtime reconciliation. */
internal class RuntimeMutationGate {
    private val lock = Any()
    private var tail: Deferred<Unit> = CompletableDeferred(Unit)

    fun admit(): RuntimeMutationTurn =
        synchronized(lock) {
            RuntimeMutationTurn(tail, CompletableDeferred<Unit>()).also { tail = it.completion }
        }

    /** Captures a user command and registers its FIFO turn under the same lock. */
    fun <T : Any> admit(capture: () -> T?): RuntimeMutationAdmission<T>? =
        synchronized(lock) {
            capture()?.let { command ->
                RuntimeMutationAdmission(command, RuntimeMutationTurn(tail, CompletableDeferred<Unit>())).also {
                    tail = it.turn.completion
                }
            }
        }
}

internal data class RuntimeMutationAdmission<T : Any>(
    val command: T,
    val turn: RuntimeMutationTurn,
)

internal class RuntimeMutationTurn internal constructor(
    private val previous: Deferred<Unit>,
    internal val completion: CompletableDeferred<Unit>,
) {
    suspend fun <T> run(block: suspend () -> T): T =
        try {
            previous.await()
            block()
        } finally {
            completion.complete(Unit)
        }
}
