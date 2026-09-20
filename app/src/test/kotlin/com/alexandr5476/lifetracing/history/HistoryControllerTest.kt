package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
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
    fun sameDayContinuationReachesEveryMixedRootOnceThenKeepsDateNavigationAdjacent() =
        runBlocking {
            val roots =
                (0..100).map { root("activity-%03d".format(it)) } +
                    listOf(sequence("sequence-a"), sequence("sequence-b"))
            val fixture =
                fixture { query ->
                    val after = query.continuation?.executionId
                    roots
                        .drop(if (after == null) 0 else roots.indexOfFirst { it.cursorId() == after } + 1)
                        .take(query.limit)
                }
            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            val first = fixture.rootIds()
            assertEquals(HISTORY_RESULT_LIMIT, first.size)
            assertTrue(fixture.controller.state.value.canLoadMore)

            fixture.controller.dispatch(HistoryAction.LoadMore)
            fixture.awaitQueries(2)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            val second = fixture.rootIds()
            assertEquals(listOf("activity-100", "sequence-a", "sequence-b"), second)
            assertEquals(roots.map { it.cursorId() }, first + second)
            assertEquals(roots.size, (first + second).distinct().size)
            assertTrue(fixture.queries.all { it.limit == HISTORY_RESULT_LIMIT + 1 })

            val current = fixture.controller.state.value.window
            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitQueries(3)
            assertEquals(current.startDate.minusDays(1), fixture.queries[2].dateRange.endDate)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(4)
            assertEquals(current.dateRange(), fixture.queries[3].dateRange)
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
    fun failedUpperBoundDiscoveryRetriesDiscoveryBeforeLoadingTheInitialWindow() =
        runBlocking {
            var discoveries = 0
            val persistedDate = TODAY.plusDays(1)
            val fixture =
                fixture(
                    readLatestDate = {
                        if (discoveries++ == 0) error("upper bound failed")
                        persistedDate
                    },
                ) { listOf(root("recovered")) }

            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Failure>()
            assertTrue(fixture.queries.isEmpty())

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitLoad<HistoryRootsLoad.Content>()

            assertEquals(2, discoveries)
            assertEquals(
                persistedDate,
                fixture.queries
                    .single()
                    .dateRange
                    .endDate,
            )
            assertEquals(listOf("recovered"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun retryRepeatsTheFailedLoadMoreRequestAndRestoresItsNextPage() =
        runBlocking {
            val roots = (0..HISTORY_RESULT_LIMIT).map { root("root-%03d".format(it)) }
            var failLoadMore = true
            val fixture =
                fixture { query ->
                    if (query.continuation != null && failLoadMore) {
                        failLoadMore = false
                        error("load more failed")
                    }
                    val after = query.continuation?.executionId
                    roots
                        .drop(if (after == null) 0 else roots.indexOfFirst { it.cursorId() == after } + 1)
                        .take(query.limit)
                }

            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            fixture.controller.dispatch(HistoryAction.LoadMore)
            fixture.awaitLoad<HistoryRootsLoad.Failure>()
            val failedLoadMore = fixture.queries.last()

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitLoad<HistoryRootsLoad.Content>()

            assertEquals(failedLoadMore, fixture.queries.last())
            assertEquals(listOf("root-100"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun retryAfterFailedNewerFromOlderContinuationUsesTheNewerWindowFirstPage() =
        runBlocking {
            val initialRange = HistoryDateRange(TODAY.minusDays(HISTORY_WINDOW_DAYS - 1), TODAY)
            val olderRange =
                HistoryDateRange(
                    initialRange.startDate.minusDays(HISTORY_WINDOW_DAYS),
                    initialRange.startDate.minusDays(1),
                )
            val olderRoots = (0..HISTORY_RESULT_LIMIT).map { root("older-%03d".format(it)) }
            var failNewer = false
            val fixture =
                fixture { query ->
                    when (query.dateRange) {
                        olderRange -> {
                            val after = query.continuation?.executionId
                            olderRoots
                                .drop(if (after == null) 0 else olderRoots.indexOfFirst { it.cursorId() == after } + 1)
                                .take(query.limit)
                        }

                        initialRange -> {
                            if (failNewer) {
                                failNewer = false
                                error("newer failed")
                            }
                            if (query.continuation == null) listOf(root("newer-window")) else emptyList()
                        }

                        else -> error("Unexpected history range")
                    }
                }

            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            fixture.controller.dispatch(HistoryAction.LoadMore)
            fixture.awaitLoad<HistoryRootsLoad.Content>()

            failNewer = true
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitLoad<HistoryRootsLoad.Failure>()
            assertEquals(null, fixture.queries.last().continuation)

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitLoad<HistoryRootsLoad.Content>()

            val retry = fixture.queries.last()
            assertEquals(initialRange, retry.dateRange)
            assertEquals(null, retry.continuation)
            assertEquals(listOf("newer-window"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun slowerObsoleteWindowCannotReplaceNewestResultOrRetryOwnership() =
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
            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitQueries(3)
            assertEquals(fixture.queries[1], fixture.queries[2])
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
    fun lateDiscoveryAfterExitCannotReplaceTheReenteredRoute() =
        runBlocking {
            val first = CompletableDeferred<LocalDate?>()
            val second = CompletableDeferred<LocalDate?>()
            val discoveries = ArrayDeque(listOf(first, second))
            val fixture = fixture(readLatestDate = { discoveries.removeFirst().await() }) { listOf(root("current")) }

            fixture.controller.onRouteEntered()
            fixture.controller.onRouteExited()
            fixture.controller.onRouteEntered()
            second.complete(TODAY)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            first.completeExceptionally(IllegalStateException("obsolete discovery"))
            yield()

            assertEquals(TODAY, fixture.controller.state.value.window.endDate)
            assertEquals(listOf("current"), fixture.contentIds())
            fixture.close()
        }

    @Test
    fun supersedingRootLoadOwnsRetryAfterAnObsoleteDiscoverySuccess() =
        runBlocking {
            val discovery = CompletableDeferred<LocalDate?>()
            val fixture = fixture(readLatestDate = { discovery.await() }) { listOf(root("newest")) }

            fixture.controller.onRouteEntered()
            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            val supersedingQuery = fixture.queries.single()
            discovery.complete(TODAY.plusDays(5))
            yield()

            assertEquals(listOf("newest"), fixture.contentIds())
            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitQueries(2)
            assertEquals(supersedingQuery, fixture.queries.last())
            fixture.close()
        }

    @Test
    fun delayedInitialDiscoveryRetainsOlderWindowAndSuppliesItsNewerBound() =
        runBlocking {
            val discovery = CompletableDeferred<LocalDate?>()
            val latest = TODAY.plusDays(5)
            val fixture =
                fixture(readLatestDate = { discovery.await() }) { query ->
                    listOf(
                        root(query.dateRange.endDate.toString()).copy(
                            primaryLocalDate = query.dateRange.endDate,
                        ),
                    )
                }

            fixture.controller.onRouteEntered()
            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            val older = fixture.queries.single().dateRange

            discovery.complete(latest)
            yield()

            assertEquals(older, fixture.queries.single().dateRange)
            assertTrue(fixture.controller.state.value.canNavigateNewer)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(2)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(3)
            assertEquals(
                latest,
                fixture.queries
                    .last()
                    .dateRange
                    .endDate,
            )
            fixture.close()
        }

    @Test
    fun failedInitialDiscoveryAfterOlderKeepsRetryableOwnershipAndLaterNewerBound() =
        runBlocking {
            val initialDiscovery = CompletableDeferred<LocalDate?>()
            val latest = TODAY.plusDays(HISTORY_WINDOW_DAYS + 2)
            var discoveries = 0
            val fixture =
                fixture(
                    readLatestDate = {
                        if (discoveries++ == 0) initialDiscovery.await() else latest
                    },
                ) { query -> listOf(root(query.dateRange.endDate.toString())) }

            fixture.controller.onRouteEntered()
            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            val olderWindow = fixture.controller.state.value.window

            initialDiscovery.completeExceptionally(IllegalStateException("discovery failed"))
            fixture.awaitDiscoveryFailure()

            assertEquals(olderWindow, fixture.controller.state.value.window)
            assertInstanceOf(HistoryRootsLoad.Content::class.java, fixture.controller.state.value.load)
            assertEquals("discovery failed", fixture.controller.state.value.discoveryFailure)

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitDiscoveryRecovered()

            assertEquals(olderWindow, fixture.controller.state.value.window)
            assertTrue(fixture.controller.state.value.canNavigateNewer)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(2)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(3)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(4)
            assertEquals(
                latest,
                fixture.queries
                    .last()
                    .dateRange
                    .endDate,
            )
            assertTrue(fixture.queries.all { it.dateRange.lengthInDays() <= HISTORY_WINDOW_DAYS })
            fixture.close()
        }

    @Test
    fun discoveryRetryKeepsAnAlreadyFailedOlderRequestRetryable() =
        runBlocking {
            val initialDiscovery = CompletableDeferred<LocalDate?>()
            val latest = TODAY.plusDays(HISTORY_WINDOW_DAYS + 2)
            var discoveries = 0
            var failOlder = true
            val fixture =
                fixture(
                    readLatestDate = {
                        if (discoveries++ == 0) initialDiscovery.await() else latest
                    },
                ) { query ->
                    if (failOlder) {
                        failOlder = false
                        error("older failed")
                    }
                    listOf(root(query.dateRange.endDate.toString()))
                }

            fixture.controller.onRouteEntered()
            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitLoad<HistoryRootsLoad.Failure>()
            val failedOlder = fixture.queries.single()

            initialDiscovery.completeExceptionally(IllegalStateException("discovery failed"))
            fixture.awaitDiscoveryFailure()

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitDiscoveryRecovered()

            assertInstanceOf(HistoryRootsLoad.Failure::class.java, fixture.controller.state.value.load)
            assertEquals(1, fixture.queries.size)

            fixture.controller.dispatch(HistoryAction.Retry)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            assertEquals(failedOlder, fixture.queries.last())

            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(3)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(4)
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitQueries(5)
            assertEquals(latest, fixture.controller.state.value.window.endDate)
            assertTrue(fixture.queries.all { it.dateRange.lengthInDays() <= HISTORY_WINDOW_DAYS })
            fixture.close()
        }

    @Test
    fun refreshRediscoversNewestDateBeforeReloadingRoots() =
        runBlocking {
            var persistedDate = TODAY
            val executionId = "corrected"
            val fixture =
                fixture(readLatestDate = { persistedDate }) { query ->
                    listOf(root(executionId).copy(primaryLocalDate = persistedDate))
                        .filter { it.primaryLocalDate in query.dateRange.startDate..query.dateRange.endDate }
                }

            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            assertEquals(TODAY, fixture.controller.state.value.window.endDate)

            persistedDate = TODAY.plusDays(1)
            fixture.controller.dispatch(HistoryAction.Refresh)
            fixture.awaitLoad<HistoryRootsLoad.Content>()

            assertEquals(persistedDate, fixture.controller.state.value.window.endDate)
            assertEquals(listOf(executionId), fixture.contentIds())
            assertEquals(1, fixture.contentIds().distinct().size)
            fixture.close()
        }

    @Test
    fun refreshDiscoverySupersededByOlderKeepsOlderWindowAndUpdatesNewerBound() =
        runBlocking {
            val refreshDiscovery = CompletableDeferred<LocalDate?>()
            val latest = TODAY.plusDays(HISTORY_WINDOW_DAYS + 2)
            var discoveries = 0
            val fixture =
                fixture(
                    readLatestDate = {
                        if (discoveries++ == 0) TODAY else refreshDiscovery.await()
                    },
                ) { query -> listOf(root(query.dateRange.endDate.toString())) }

            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Content>()

            fixture.controller.dispatch(HistoryAction.Refresh)
            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            val olderWindow = fixture.controller.state.value.window
            val olderContent = fixture.contentIds()

            refreshDiscovery.complete(latest)
            yield()

            assertEquals(2, fixture.queries.size)
            assertEquals(olderWindow, fixture.controller.state.value.window)
            assertEquals(olderContent, fixture.contentIds())
            assertTrue(fixture.controller.state.value.canNavigateNewer)

            repeat(3) { fixture.controller.dispatch(HistoryAction.Newer) }
            fixture.awaitQueries(5)
            assertEquals(latest, fixture.controller.state.value.window.endDate)
            assertTrue(fixture.queries.all { it.dateRange.lengthInDays() <= HISTORY_WINDOW_DAYS })
            fixture.close()
        }

    @Test
    fun persistedOriginalDateAheadOfDeviceTodayRemainsReachableAfterZoneChange() =
        runBlocking {
            val persistedDate = TODAY.plusDays(1)
            var zone = ZoneOffset.UTC
            val fixture =
                fixture(
                    readLatestDate = { persistedDate },
                    zoneId = { zone },
                ) { query ->
                    if (persistedDate in query.dateRange.startDate..query.dateRange.endDate) {
                        listOf(root("persisted").copy(primaryLocalDate = persistedDate))
                    } else {
                        emptyList()
                    }
                }

            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            assertEquals(persistedDate, fixture.controller.state.value.window.endDate)
            assertEquals(listOf("persisted"), fixture.contentIds())
            assertTrue(fixture.queries.all { it.limit in 1..500 })

            fixture.controller.dispatch(HistoryAction.Older)
            fixture.awaitLoad<HistoryRootsLoad.Empty>()
            fixture.controller.dispatch(HistoryAction.Newer)
            fixture.awaitLoad<HistoryRootsLoad.Content>()

            zone = ZoneOffset.ofHours(-12)
            fixture.controller.onRouteExited()
            fixture.controller.onRouteEntered()
            fixture.awaitLoad<HistoryRootsLoad.Content>()
            assertEquals(persistedDate, fixture.controller.state.value.window.endDate)
            assertEquals(listOf("persisted"), fixture.contentIds())
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

    private fun fixture(
        readLatestDate: suspend () -> LocalDate? = { null },
        zoneId: () -> ZoneOffset = { ZoneOffset.UTC },
        read: suspend (CompletedHistoryQuery) -> List<CompletedHistoryRoot>,
    ): RootFixture {
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
                zoneId,
                readLatestDate,
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

        suspend fun awaitDiscoveryFailure() =
            withTimeout(2_000) { controller.state.first { it.discoveryFailure != null } }

        suspend fun awaitDiscoveryRecovered() =
            withTimeout(2_000) { controller.state.first { it.discoveryFailure == null && it.canNavigateNewer } }

        fun contentIds(): List<String> =
            (controller.state.value.load as HistoryRootsLoad.Content).roots.map {
                (it as CompletedActivityHistoryRoot).executionId.value
            }

        fun rootIds(): List<String> =
            (controller.state.value.load as HistoryRootsLoad.Content).roots.map { it.cursorId() }

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

        private fun sequence(id: String) =
            CompletedSequenceHistoryRoot(
                SequenceExecutionId(id),
                SequenceSnapshotId("snapshot-$id"),
                TODAY,
                NOW,
                NOW.minusSeconds(60),
                SequenceExecutionStatus.COMPLETED,
                Duration.ofSeconds(60),
                Duration.ZERO,
                Duration.ofSeconds(60),
                null,
                id,
                null,
            )

        private fun CompletedHistoryRoot.cursorId(): String =
            when (this) {
                is CompletedActivityHistoryRoot -> executionId.value
                is CompletedSequenceHistoryRoot -> executionId.value
            }

        private fun HistoryBrowseWindow.dateRange() = HistoryDateRange(startDate, endDate)

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
