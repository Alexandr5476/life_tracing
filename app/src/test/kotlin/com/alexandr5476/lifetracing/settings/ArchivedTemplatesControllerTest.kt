package com.alexandr5476.lifetracing.settings

import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.CoroutineContext

class ArchivedTemplatesControllerTest {
    @Test
    fun loading_blocks_restore_and_an_empty_canonical_read_is_representable() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val read = CompletableDeferred<List<LibraryTrackable>>()
            val requests = mutableListOf<LibraryTemplateId>()
            val controller =
                ArchivedTemplatesController(
                    { read.await() },
                    { requests += it },
                    {},
                    scope,
                    ArchivedRestoreCoordinator(),
                )
            try {
                assertTrue(controller.state.value.loading)
                controller.restore(activityId)
                assertTrue(requests.isEmpty())
                read.complete(emptyList())
                assertEquals(emptyList<LibraryTrackable>(), controller.state.value.items)
                assertFalse(controller.state.value.loading)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun restore_keeps_the_row_until_canonical_reload_and_blocks_duplicate_dispatch() =
        runBlocking {
            val fixture = Fixture()
            val releaseWrite = CompletableDeferred<Unit>()
            val reloaded = CompletableDeferred<List<LibraryTrackable>>()
            fixture.write = { releaseWrite.await() }
            fixture.read = { reloaded.await() }
            try {
                fixture.controller.restore(activityId)
                fixture.controller.restore(activityId)
                fixture.controller.restore(sequenceId)
                assertEquals(listOf(activityId), fixture.requests)
                assertEquals(fixture.initial, fixture.controller.state.value.items)
                releaseWrite.complete(Unit)
                assertEquals(activityId, fixture.controller.state.value.pending)
                assertEquals(fixture.initial, fixture.controller.state.value.items)
                reloaded.complete(listOf(fixture.initial.last()))
                assertNull(fixture.controller.state.value.pending)
                assertEquals(listOf(sequenceId), fixture.ids())
                assertEquals(1, fixture.refreshes)
                fixture.controller.restore(activityId)
                assertEquals(listOf(activityId), fixture.requests)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun failed_restore_reloads_keeps_the_identity_and_retries_only_that_kind() =
        runBlocking {
            val fixture = Fixture()
            fixture.write = { throw IOException("failed transaction") }
            try {
                fixture.controller.restore(sequenceId)
                assertEquals(sequenceId, fixture.controller.state.value.failedRestore)
                assertEquals(fixture.initial, fixture.controller.state.value.items)
                assertNull(fixture.controller.state.value.pending)
                assertEquals(0, fixture.refreshes)
                fixture.write = { id -> fixture.canonical = fixture.canonical.filterNot { it.id == id } }
                fixture.controller.retryRestore()
                assertEquals(listOf(sequenceId, sequenceId), fixture.requests)
                assertEquals(listOf(activityId), fixture.ids())
                assertNull(fixture.controller.state.value.failedRestore)
                assertEquals(1, fixture.refreshes)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun stale_restore_converges_from_the_reader_and_recreation_only_reads() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.canonical = fixture.canonical.filterNot { it.id == activityId }
                fixture.write = { throw IllegalArgumentException("already active") }
                fixture.controller.restore(activityId)
                assertEquals(listOf(sequenceId), fixture.ids())
                assertNull(fixture.controller.state.value.failedRestore)
                assertEquals(1, fixture.refreshes)
                val recreated =
                    ArchivedTemplatesController(
                        { fixture.canonical },
                        { fixture.requests += it },
                        {},
                        fixture.scope,
                        fixture.restores,
                    )
                assertEquals(fixture.canonical, recreated.state.value.items)
                assertEquals(listOf(activityId), fixture.requests)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun a_committed_restore_with_failed_reload_retains_rows_and_read_retry_does_not_replay_restore() =
        runBlocking {
            val fixture = Fixture()
            fixture.write = { id ->
                fixture.canonical = fixture.canonical.filterNot { it.id == id }
                fixture.read = { throw IOException("reload unavailable") }
            }
            try {
                fixture.controller.restore(activityId)
                assertTrue(fixture.controller.state.value.readFailed)
                assertFalse(fixture.controller.state.value.canRestore)
                assertEquals(fixture.initial, fixture.controller.state.value.items)
                assertNull(fixture.controller.state.value.pending)
                assertEquals(1, fixture.refreshes)
                fixture.read = { fixture.canonical }
                fixture.controller.reload()
                assertEquals(listOf(sequenceId), fixture.ids())
                assertFalse(fixture.controller.state.value.readFailed)
                assertEquals(listOf(activityId), fixture.requests)
                assertEquals(1, fixture.refreshes)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun dispatched_restore_finishes_canonical_reload_and_library_refresh_when_the_screen_scope_is_cancelled() =
        runBlocking {
            val fixture = Fixture()
            val releaseWrite = CompletableDeferred<Unit>()
            val reloaded = CompletableDeferred<Unit>()
            fixture.write = { id ->
                releaseWrite.await()
                fixture.canonical = fixture.canonical.filterNot { it.id == id }
            }
            fixture.read = {
                reloaded.complete(Unit)
                fixture.canonical
            }
            try {
                fixture.controller.restore(activityId)
                assertEquals(activityId, fixture.controller.state.value.pending)
                fixture.scope.cancel()
                assertEquals(fixture.initial, fixture.controller.state.value.items)
                releaseWrite.complete(Unit)
                withTimeout(5_000) { reloaded.await() }
                assertEquals(listOf(sequenceId), fixture.ids())
                assertNull(fixture.controller.state.value.pending)
                assertEquals(1, fixture.refreshes)
                assertEquals(listOf(activityId), fixture.requests)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun accepted_restore_survives_disposal_before_the_dispatcher_can_run_it() {
        val dispatcher = QueuedDispatcher()
        val fixture = Fixture(dispatcher)
        dispatcher.runNext()
        val releaseWrite = CompletableDeferred<Unit>()
        fixture.write = { id ->
            releaseWrite.await()
            fixture.canonical = fixture.canonical.filterNot { it.id == id }
        }

        fixture.controller.restore(activityId)
        assertEquals(activityId, fixture.controller.state.value.pending)
        fixture.scope.cancel()
        releaseWrite.complete(Unit)
        dispatcher.drain()

        assertEquals(listOf(activityId), fixture.requests)
        assertEquals(1, fixture.refreshes)
        assertEquals(listOf(sequenceId), fixture.ids())
        assertNull(fixture.controller.state.value.pending)
    }

    @Test
    fun reentry_blocks_the_pending_typed_identity_but_admits_the_other_kind_with_the_same_raw_id() {
        val fixture = Fixture()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val activityWrite = CompletableDeferred<Unit>()
        val sequenceWrite = CompletableDeferred<Unit>()
        val sequenceReconciliation = CompletableDeferred<Unit>()
        var nextReads = 0
        fixture.write = { id ->
            (if (id == activityId) activityWrite else sequenceWrite).await()
            fixture.canonical = fixture.canonical.filterNot { it.id == id }
        }
        try {
            fixture.controller.restore(activityId)
            fixture.scope.cancel()
            val next =
                fixture.createController(nextScope) {
                    val snapshot = fixture.canonical
                    if (++nextReads == 2) sequenceReconciliation.await()
                    snapshot
                }
            assertEquals(fixture.initial, next.state.value.items)
            next.restore(activityId)
            assertEquals(listOf(activityId), fixture.requests)
            next.restore(sequenceId)
            assertEquals(listOf(activityId, sequenceId), fixture.requests)

            sequenceWrite.complete(Unit)
            activityWrite.complete(Unit)
            assertEquals(sequenceId, next.state.value.pending)
            assertEquals(fixture.initial, next.state.value.items)
            sequenceReconciliation.complete(Unit)
            assertEquals(3, nextReads)
            assertEquals(emptyList<LibraryTrackable>(), next.state.value.items)
            assertEquals(2, fixture.refreshes)
        } finally {
            activityWrite.complete(Unit)
            sequenceWrite.complete(Unit)
            sequenceReconciliation.complete(Unit)
            nextScope.cancel()
            fixture.scope.cancel()
        }
    }

    @Test
    fun reentry_refreshes_after_completion_even_if_its_initial_read_took_a_precommit_snapshot() {
        val fixture = Fixture()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseWrite = CompletableDeferred<Unit>()
        val staleRead = CompletableDeferred<Unit>()
        val freshRead = CompletableDeferred<Unit>()
        var reads = 0
        fixture.write = { id ->
            releaseWrite.await()
            fixture.canonical = fixture.canonical.filterNot { it.id == id }
        }
        try {
            fixture.controller.restore(activityId)
            fixture.scope.cancel()
            val next =
                fixture.createController(nextScope) {
                    val snapshot = fixture.canonical
                    if (++reads == 1) staleRead.await() else freshRead.await()
                    snapshot
                }
            assertTrue(next.state.value.loading)
            releaseWrite.complete(Unit)
            assertEquals(listOf(sequenceId), fixture.ids())
            assertEquals(1, fixture.refreshes)
            staleRead.complete(Unit)
            assertEquals(2, reads)
            next.restore(activityId)
            assertEquals(listOf(activityId), fixture.requests)
            freshRead.complete(Unit)
            assertEquals(listOf(sequenceId), next.ids())
            assertTrue(next.state.value.canRestore)
        } finally {
            releaseWrite.complete(Unit)
            staleRead.complete(Unit)
            freshRead.complete(Unit)
            nextScope.cancel()
            fixture.scope.cancel()
        }
    }

    @Test
    fun completed_reservation_blocks_restore_from_a_stale_controller_before_its_observer_runs() {
        val fixture = Fixture()
        val dispatcher = QueuedDispatcher()
        val nextScope = CoroutineScope(SupervisorJob() + dispatcher)
        val release = CompletableDeferred<Unit>()
        fixture.write = { id ->
            release.await()
            fixture.canonical = fixture.canonical.filterNot { it.id == id }
        }
        try {
            fixture.controller.restore(activityId)
            fixture.scope.cancel()
            val next = fixture.createController(nextScope)
            dispatcher.drain()
            val old = next.state.value
            assertEquals(fixture.initial, old.items)
            release.complete(Unit)
            val status = fixture.restores.status.value
            assertTrue(status.pending.isEmpty())
            assertEquals(old, next.state.value)
            next.restore(activityId)
            next.restore(sequenceId)
            assertEquals(listOf(activityId), fixture.requests)
            dispatcher.drain()
            assertEquals(listOf(sequenceId), next.ids())
        } finally {
            release.complete(Unit)
            nextScope.cancel()
            dispatcher.drain()
            fixture.scope.cancel()
        }
    }

    @Test
    fun failed_original_restore_leaves_the_row_eligible_in_the_new_controller_after_canonical_refresh() {
        val fixture = Fixture()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseFailure = CompletableDeferred<Unit>()
        val freshRead = CompletableDeferred<Unit>()
        var reads = 0
        fixture.write = {
            releaseFailure.await()
            throw IOException("Restore failed")
        }
        try {
            fixture.controller.restore(activityId)
            fixture.scope.cancel()
            val next =
                fixture.createController(nextScope) {
                    if (++reads == 2) freshRead.await()
                    fixture.canonical
                }
            releaseFailure.complete(Unit)
            assertEquals(activityId, fixture.controller.state.value.failedRestore)
            assertTrue(next.state.value.loading)
            next.restore(activityId)
            assertEquals(listOf(activityId), fixture.requests)
            assertEquals(0, fixture.refreshes)

            freshRead.complete(Unit)
            assertEquals(fixture.initial, next.state.value.items)
            fixture.write = { id -> fixture.canonical = fixture.canonical.filterNot { it.id == id } }
            next.restore(activityId)
            assertEquals(listOf(activityId, activityId), fixture.requests)
            assertEquals(listOf(sequenceId), next.ids())
            assertEquals(1, fixture.refreshes)
        } finally {
            releaseFailure.complete(Unit)
            freshRead.complete(Unit)
            nextScope.cancel()
            fixture.scope.cancel()
        }
    }

    @Test
    fun competing_restore_removes_the_row_from_both_controllers_and_refreshes_library_once() {
        val fixture = Fixture()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        fixture.write = {
            release.await()
            throw IllegalArgumentException("Already active")
        }
        try {
            fixture.controller.restore(activityId)
            fixture.scope.cancel()
            val next = fixture.createController(nextScope)
            assertEquals(fixture.initial, next.state.value.items)
            fixture.canonical = fixture.canonical.filterNot { it.id == activityId }
            release.complete(Unit)
            assertEquals(listOf(sequenceId), fixture.ids())
            assertEquals(listOf(sequenceId), next.ids())
            assertNull(fixture.controller.state.value.failedRestore)
            assertNull(next.state.value.failedRestore)
            assertEquals(1, fixture.refreshes)
            assertEquals(listOf(activityId), fixture.requests)
        } finally {
            release.complete(Unit)
            nextScope.cancel()
            fixture.scope.cancel()
        }
    }

    @Test
    fun committed_restore_notifies_reentry_even_when_both_reads_fail_and_reload_is_read_only() {
        val fixture = Fixture()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        fixture.write = { id ->
            release.await()
            fixture.canonical = fixture.canonical.filterNot { it.id == id }
        }
        try {
            fixture.controller.restore(activityId)
            fixture.scope.cancel()
            val next = fixture.createController(nextScope)
            assertEquals(fixture.initial, next.state.value.items)
            fixture.read = { throw IOException("Archive unavailable") }
            release.complete(Unit)
            assertTrue(fixture.controller.state.value.readFailed)
            assertTrue(next.state.value.readFailed)
            assertEquals(fixture.initial, next.state.value.items)
            assertFalse(next.state.value.canRestore)
            next.restore(activityId)
            next.retryRestore()
            assertEquals(listOf(activityId), fixture.requests)

            fixture.read = { fixture.canonical }
            next.reload()
            assertEquals(listOf(sequenceId), next.ids())
            assertNull(next.state.value.failedRestore)
            assertEquals(listOf(activityId), fixture.requests)
            assertEquals(1, fixture.refreshes)
        } finally {
            release.complete(Unit)
            nextScope.cancel()
            fixture.scope.cancel()
        }
    }

    private class Fixture(
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val initial = listOf(row(activityId), row(sequenceId))
        val restores = ArchivedRestoreCoordinator()
        var canonical = initial
        var read: suspend () -> List<LibraryTrackable> = { canonical }
        var write: suspend (LibraryTemplateId) -> Unit = {}
        val requests = mutableListOf<LibraryTemplateId>()
        var refreshes = 0

        fun ids(): List<LibraryTemplateId> = controller.ids()

        val controller = createController(scope)

        fun createController(
            scope: CoroutineScope,
            readArchived: suspend () -> List<LibraryTrackable> = { read() },
        ): ArchivedTemplatesController =
            ArchivedTemplatesController(
                readArchived,
                {
                    requests += it
                    write(it)
                },
                { refreshes++ },
                scope,
                restores,
            )
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun runNext() = tasks.removeFirst().run()

        fun drain() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    companion object {
        private val activityId = LibraryTemplateId.Activity(ActivityTemplateId("same-value"))
        private val sequenceId = LibraryTemplateId.Sequence(SequenceTemplateId("same-value"))

        private fun ArchivedTemplatesController.ids(): List<LibraryTemplateId> {
            val items = requireNotNull(state.value.items)
            return items.map { it.id }
        }

        private fun row(id: LibraryTemplateId) =
            LibraryTrackable(id, "Same name", "Comment", null, emptySet(), null, null, Instant.EPOCH)
    }
}
