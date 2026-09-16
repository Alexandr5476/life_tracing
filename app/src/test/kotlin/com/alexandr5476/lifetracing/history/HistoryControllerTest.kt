package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryControllerTest {
    @Test
    fun initialRouteEntryIssuesOneFiniteQueryContainingToday() =
        runBlocking {
            val fixture = fixture { emptyList() }

            fixture.controller.onRouteEntered()
            fixture.awaitQueries(1)

            val query = fixture.queries.single()
            assertTrue(TODAY in query.dateRange.startDate..query.dateRange.endDate)
            assertTrue(query.limit in 1..500)
            assertEquals(HISTORY_WINDOW_DAYS, query.dateRange.lengthInDays())
            fixture.close()
        }

    @Test
    fun olderAndNewerUseOrderedAdjacentFiniteWindowsWithoutAccumulation() =
        runBlocking {
            var read = 0
            val fixture = fixture { listOf(root("root-${read++}")) }
            fixture.controller.onRouteEntered()
            fixture.awaitQueries(1)
            val initial = fixture.queries[0]

            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitQueries(2)
            val older = fixture.queries[1]
            assertEquals(initial.dateRange.startDate.minusDays(1), older.dateRange.endDate)
            assertEquals(HISTORY_WINDOW_DAYS, older.dateRange.lengthInDays())
            assertEquals(listOf("root-1"), fixture.contentIds())

            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(3)
            val newer = fixture.queries[2]
            assertEquals(older.dateRange.endDate.plusDays(1), newer.dateRange.startDate)
            assertEquals(initial.dateRange, newer.dateRange)
            assertEquals(listOf("root-2"), fixture.contentIds())

            repeat(3) {
                fixture.controller.dispatch(HistoryAction.Older)
                fixture.awaitQueries(4 + it)
            }
            assertTrue(fixture.queries.all { it.dateRange.lengthInDays() == HISTORY_WINDOW_DAYS })
            assertEquals(listOf("root-5"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun retryReloadsCurrentWindowAndExercisesEmptyFailureAndContent() =
        runBlocking {
            var read = 0
            val fixture =
                fixture {
                    when (read++) {
                        0 -> emptyList()
                        1 -> error("read failed")
                        else -> listOf(root("recovered"))
                    }
                }
            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Empty>()
            val window = fixture.queries.single().dateRange

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitLoad<HistoryRootsLoad.Failure>()
            assertEquals(window, fixture.queries[1].dateRange)

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            assertEquals(window, fixture.queries[2].dateRange)
            assertEquals(listOf("recovered"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun slowerObsoleteWindowCannotReplaceNewestResult() =
        runBlocking {
            val first = CompletableDeferred<List<CompletedHistoryRoot>>()
            val second = CompletableDeferred<List<CompletedHistoryRoot>>()
            val reads = ArrayDeque(listOf(first, second))
            val fixture = fixture { reads.removeFirst().await() }
            fixture.controller.onRouteEntered()
            fixture.awaitQueries(1)
            assertInstanceOf(HistoryRootsLoad.Loading::class.java, fixture.controller.state.value.load)

            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitQueries(2)
            second.complete(listOf(root("newest")))
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            first.complete(listOf(root("obsolete")))
            yield()

            assertEquals(listOf("newest"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun exitAndReentryInvalidateLatePublicationAndReloadExactlyOncePerEntry() =
        runBlocking {
            val first = CompletableDeferred<List<CompletedHistoryRoot>>()
            val second = CompletableDeferred<List<CompletedHistoryRoot>>()
            val reads = ArrayDeque(listOf(first, second))
            val fixture = fixture { reads.removeFirst().await() }

            fixture.controller.onRouteEntered()
            fixture.controller.onRouteEntered()
            fixture.awaitQueries(1)
            fixture.controller.onRouteExited()
            fixture.controller.onRouteEntered()
            fixture.awaitQueries(2)
            second.complete(listOf(root("reentered")))
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            first.complete(listOf(root("obsolete")))
            yield()

            assertEquals(2, fixture.queries.size)
            assertEquals(listOf("reentered"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun detailLoadsContentUnavailableAndFailureThenRetries() =
        runBlocking {
            val contentFixture = detailFixture { detail() }
            contentFixture.awaitLoad<HistoryDetailLoad.Content<ActivityHistoryDetail>>()

            val unavailableFixture = detailFixture<ActivityHistoryDetail> { null }
            unavailableFixture.awaitLoad<HistoryDetailLoad.Unavailable>()

            var reads = 0
            val retryFixture = detailFixture<ActivityHistoryDetail> { if (reads++ == 0) error("failed") else detail() }
            retryFixture.awaitLoad<HistoryDetailLoad.Failure>()
            retryFixture.controller.reload()
            retryFixture.awaitLoad<HistoryDetailLoad.Content<ActivityHistoryDetail>>()

            contentFixture.close()
            unavailableFixture.close()
            retryFixture.close()
        }

    @Test
    fun closedOrSupersededDetailCannotPublishLateResult() =
        runBlocking {
            val first = CompletableDeferred<ActivityHistoryDetail?>()
            val second = CompletableDeferred<ActivityHistoryDetail?>()
            val reads = ArrayDeque(listOf(first, second))
            val fixture = detailFixture { reads.removeFirst().await() }
            fixture.controller.reload()
            second.complete(detail("newest"))
            fixture.awaitLoad<HistoryDetailLoad.Content<ActivityHistoryDetail>>()
            first.complete(detail("obsolete"))
            yield()
            assertEquals("newest", fixture.content().root.title)

            val closedGate = CompletableDeferred<ActivityHistoryDetail?>()
            val closed = detailFixture { closedGate.await() }
            closed.controller.close()
            closedGate.complete(detail("late"))
            yield()
            assertInstanceOf(HistoryDetailLoad.Loading::class.java, closed.controller.state.value)

            fixture.close()
            closed.scope.cancel()
        }

    private fun fixture(read: suspend (CompletedHistoryQuery) -> List<CompletedHistoryRoot>): RootFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queries = mutableListOf<CompletedHistoryQuery>()
        val controller =
            HistoryController(
                scope,
                { query ->
                    queries += query
                    read(query)
                },
                { NOW },
                { ZoneOffset.UTC },
            )
        return RootFixture(scope, controller, queries)
    }

    private fun <T> detailFixture(read: suspend () -> T?): DetailFixture<T> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return DetailFixture(scope, HistoryDetailController(scope, read))
    }

    private data class RootFixture(
        val scope: CoroutineScope,
        val controller: HistoryController,
        val queries: MutableList<CompletedHistoryQuery>,
    ) {
        suspend fun awaitQueries(count: Int) = withTimeout(2_000) { while (queries.size < count) yield() }

        suspend inline fun <reified T : HistoryRootsLoad> awaitLoad() =
            withTimeout(2_000) { controller.state.first { it.load is T } }

        fun contentIds(): List<String> =
            (controller.state.value.load as HistoryRootsLoad.Content).roots.map {
                (it as CompletedActivityHistoryRoot).executionId.value
            }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private data class DetailFixture<T>(
        val scope: CoroutineScope,
        val controller: HistoryDetailController<T>,
    ) {
        suspend inline fun <reified L : HistoryDetailLoad<T>> awaitLoad() =
            withTimeout(2_000) { controller.state.first { it is L } }

        @Suppress("UNCHECKED_CAST")
        fun content(): T = (controller.state.value as HistoryDetailLoad.Content<T>).value

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-16T10:00:00Z")
        private val TODAY = LocalDate.parse("2026-09-16")

        private fun com.alexandr5476.lifetracing.domain.HistoryDateRange.lengthInDays(): Long =
            java.time.temporal.ChronoUnit.DAYS
                .between(startDate, endDate) + 1

        private fun root(id: String): CompletedActivityHistoryRoot =
            CompletedActivityHistoryRoot(
                ActivityExecutionId(id),
                ActivitySnapshotId("snapshot-$id"),
                TODAY,
                NOW,
                NOW.minusSeconds(60),
                Duration.ofSeconds(60),
                null,
                id,
                null,
                TimeTrackingMode.STOPWATCH,
                null,
            )

        private fun detail(title: String = "detail") =
            ActivityHistoryDetail(
                root(title),
                NOW,
                ZoneOffset.UTC,
                ActivityTemplateSettings(),
                emptyList(),
            )
    }
}
