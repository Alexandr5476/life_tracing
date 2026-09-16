package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class HistoryControllerTest {
    @Test
    fun finiteAdjacentWindowsReplaceTheDatasetAndStaleReadsCannotWin() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val queries = mutableListOf<CompletedHistoryQuery>()
            val first = CompletableDeferred<List<com.alexandr5476.lifetracing.domain.CompletedHistoryRoot>>()
            val second = CompletableDeferred<List<com.alexandr5476.lifetracing.domain.CompletedHistoryRoot>>()
            val reads = ArrayDeque(listOf(first, second))
            val controller =
                HistoryController(
                    scope,
                    { query ->
                        queries += query
                        reads.removeFirst().await()
                    },
                    { Instant.parse("2026-09-16T10:00:00Z") },
                    { ZoneOffset.UTC },
                )

            withTimeout(2_000) { while (queries.size < 1) kotlinx.coroutines.yield() }
            controller.dispatch(HistoryAction.Older)
            withTimeout(2_000) { while (queries.size < 2) kotlinx.coroutines.yield() }
            second.complete(emptyList())
            withTimeout(2_000) { controller.state.first { it.load is HistoryRootsLoad.Empty } }
            first.complete(emptyList())

            assertEquals(2, queries.size)
            assertEquals(HISTORY_RESULT_LIMIT, queries[0].limit)
            assertEquals(
                HISTORY_WINDOW_DAYS,
                java.time.temporal.ChronoUnit.DAYS.between(
                    queries[0].dateRange.startDate,
                    queries[0].dateRange.endDate,
                ) + 1,
            )
            assertEquals(queries[0].dateRange.startDate.minusDays(1), queries[1].dateRange.endDate)
            assertTrue(queries.all { it.limit in 1..500 && it.dateRange.startDate <= it.dateRange.endDate })
            controller.close()
            scope.cancel()
        }
}
