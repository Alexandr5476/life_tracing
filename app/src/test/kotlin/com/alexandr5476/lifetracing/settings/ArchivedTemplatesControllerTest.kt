package com.alexandr5476.lifetracing.settings

import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import kotlinx.coroutines.CompletableDeferred
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

class ArchivedTemplatesControllerTest {
    @Test
    fun loading_blocks_restore_and_an_empty_canonical_read_is_representable() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val read = CompletableDeferred<List<LibraryTrackable>>()
            val requests = mutableListOf<LibraryTemplateId>()
            val controller = ArchivedTemplatesController({ read.await() }, { requests += it }, {}, scope)
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

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = listOf(row(activityId), row(sequenceId))
        var canonical = initial
        var read: suspend () -> List<LibraryTrackable> = { canonical }
        var write: suspend (LibraryTemplateId) -> Unit = {}
        val requests = mutableListOf<LibraryTemplateId>()
        var refreshes = 0

        fun ids(): List<LibraryTemplateId> {
            val items = requireNotNull(controller.state.value.items)
            return items.map { it.id }
        }

        val controller =
            ArchivedTemplatesController(
                { read() },
                {
                    requests += it
                    write(it)
                },
                { refreshes++ },
                scope,
            )
    }

    companion object {
        private val activityId = LibraryTemplateId.Activity(ActivityTemplateId("same-value"))
        private val sequenceId = LibraryTemplateId.Sequence(SequenceTemplateId("same-value"))

        private fun row(id: LibraryTemplateId) =
            LibraryTrackable(id, "Same name", "Comment", null, emptySet(), null, null, Instant.EPOCH)
    }
}
