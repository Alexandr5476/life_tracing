@file:Suppress("LongMethod", "LongParameterList", "MagicNumber")

package com.alexandr5476.lifetracing.daily

import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.CalendarTemporalContext
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.plan.PlanController
import com.alexandr5476.lifetracing.plan.PlanLoad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class CalendarTemporalProjectionTest {
    @Test
    fun obsoleteSuccessesAndFailuresCannotPublishOrRearmEitherRoute() =
        runBlocking {
            for (plan in listOf(false, true)) {
                for (trigger in Trigger.entries) {
                    for ((oldFailure, newFailure) in listOf(false to false, true to false, false to true)) {
                        val harness = Harness(this, plan)
                        val old = harness.nextRead()
                        val obsoleteCallback = harness.boundary.callbacks.last()
                        val selected = harness.selectedDate()
                        when (trigger) {
                            Trigger.ZONE -> harness.zone = ZoneId.of("America/New_York")
                            Trigger.WALL -> harness.now = harness.now.minusSeconds(3_600)
                            Trigger.DAY -> harness.now = harness.now.plusSeconds(86_400)
                            Trigger.FOREGROUND -> Unit
                            Trigger.ROUTE -> harness.hide()
                        }
                        if (trigger == Trigger.ROUTE) harness.show() else harness.semantic.value++
                        val fresh = harness.nextRead()
                        fresh.release.complete(newFailure)
                        harness.awaitPublished()
                        val published = harness.published()
                        if (newFailure) assertTrue(harness.failed()) else assertSame(fresh.result, published)
                        val arms = harness.boundary.callbacks.size
                        old.release.complete(oldFailure)
                        withTimeout(2_000) { old.finished.await() }
                        obsoleteCallback()
                        assertSame(published, harness.published())
                        assertEquals(arms, harness.boundary.callbacks.size)
                        assertEquals(selected, harness.selectedDate())
                        assertEquals(2, harness.reads.size)
                        harness.close()
                    }
                }
            }
        }

    @Test
    fun zoneChangeDuringReadRetriesEvenBeforeASemanticSignalArrives() =
        runBlocking {
            for (plan in listOf(false, true)) {
                val harness = Harness(this, plan)
                val old = harness.nextRead()
                harness.zone = ZoneId.of("Pacific/Auckland")
                old.release.complete(false)
                val fresh = harness.nextRead()
                assertEquals(harness.zone, fresh.context.zoneId)
                fresh.release.complete(false)
                harness.awaitPublished()
                assertSame(fresh.result, harness.published())
                assertEquals(harness.zone, harness.boundary.zones.last())
                harness.close()
            }
        }

    @Test
    fun hiddenSuccessAndFailureCannotPublishAndReentryUsesCurrentContext() =
        runBlocking {
            for (plan in listOf(false, true)) {
                for (failure in listOf(false, true)) {
                    val harness = Harness(this, plan)
                    val old = harness.nextRead()
                    val callback = harness.boundary.callbacks.last()
                    harness.hide()
                    harness.zone = ZoneId.of("Asia/Tokyo")
                    harness.now = harness.now.plusSeconds(86_400)
                    harness.semantic.value++
                    old.release.complete(failure)
                    withTimeout(2_000) { old.finished.await() }
                    callback()
                    assertFalse(harness.failed())
                    assertEquals(1, harness.reads.size)
                    assertFalse(harness.boundary.active)
                    harness.show()
                    val fresh = harness.nextRead()
                    fresh.release.complete(false)
                    harness.awaitPublished()
                    assertSame(fresh.result, harness.published())
                    assertEquals(harness.zone, fresh.context.zoneId)
                    harness.close()
                }
            }
        }

    @Test
    fun localDateBoundariesFollowTwentyThreeAndTwentyFiveHourDays() {
        val zone = ZoneId.of("Europe/Berlin")
        for ((date, hours) in listOf("2026-03-29" to 23L, "2026-10-25" to 25L)) {
            val start =
                java.time.LocalDate
                    .parse(date)
                    .atStartOfDay(zone)
                    .toInstant()
            val end = nextLocalDateBoundary(start, zone)
            assertEquals(hours, Duration.between(start, end).toHours())
            assertEquals(
                java.time.LocalDate
                    .parse(date)
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant(),
                end,
            )
            assertEquals(end, nextLocalDateBoundary(end.minusMillis(1), zone))
        }
    }

    @Test
    fun repeatedRearmingKeepsOnlyOneCoroutineBoundaryJob() =
        runBlocking {
            val owner = kotlinx.coroutines.SupervisorJob()
            val scheduler = CoroutineLocalDateBoundaryScheduler(CoroutineScope(coroutineContext + owner))
            var callbacks = 0
            try {
                repeat(20) { iteration ->
                    val zone = ZoneId.of(if (iteration % 2 == 0) "Europe/Berlin" else "America/New_York")
                    val now =
                        java.time.LocalDate
                            .parse("2026-10-25")
                            .atStartOfDay(zone)
                            .toInstant()
                    scheduler.arm(now, zone, null) { callbacks++ }
                    kotlinx.coroutines.yield()
                    assertEquals(1, owner.children.count { it.isActive })
                }
                scheduler.cancel()
                kotlinx.coroutines.yield()
                assertEquals(0, owner.children.count { it.isActive })
                assertEquals(0, callbacks)
            } finally {
                scheduler.cancel()
                owner.cancel()
            }
        }

    @Test
    fun reentrantRefreshAtPublicationCannotRearmTheSupersededZone() =
        runBlocking {
            for (plan in listOf(false, true)) {
                val harness = Harness(this, plan)
                val old = harness.nextRead()
                var invalidated = false
                val watcher =
                    harness.watch(this) {
                        if (!invalidated && harness.published() === old.result) {
                            invalidated = true
                            harness.zone = ZoneId.of("Asia/Tokyo")
                            harness.show()
                        }
                    }
                try {
                    old.release.complete(false)
                    val fresh = harness.nextRead()
                    assertTrue(invalidated)
                    assertEquals(harness.zone, harness.boundary.zones.last())
                    assertEquals(2, harness.boundary.callbacks.size)
                    fresh.release.complete(false)
                    harness.awaitPublished()
                    assertSame(fresh.result, harness.published())
                } finally {
                    watcher.cancel()
                    harness.close()
                }
            }
        }

    private enum class Trigger { ZONE, WALL, DAY, FOREGROUND, ROUTE }

    private class Pending(
        val context: CalendarTemporalContext,
        val result: Any,
    ) {
        val release = CompletableDeferred<Boolean>()
        val finished = CompletableDeferred<Unit>()

        suspend fun awaitResult(): Any {
            try {
                if (release.await()) error("read failed")
                return result
            } finally {
                finished.complete(Unit)
            }
        }
    }

    private class Boundary : LocalDateBoundaryScheduler {
        val callbacks = mutableListOf<() -> Unit>()
        val zones = mutableListOf<ZoneId>()
        var active = false

        override fun arm(
            now: Instant,
            zoneId: ZoneId,
            exactBoundary: Instant?,
            onBoundary: () -> Unit,
        ) {
            zones += zoneId
            callbacks += onBoundary
            active = true
        }

        override fun cancel() {
            active = false
        }
    }

    private class Harness(
        scope: CoroutineScope,
        plan: Boolean,
    ) {
        var now = Instant.parse("2026-10-25T00:00:00Z")
        var zone = ZoneId.of("Europe/Berlin")
        val semantic = MutableStateFlow(0L)
        val boundary = Boundary()
        val reads = mutableListOf<Pending>()
        private val entered =
            kotlinx.coroutines.channels.Channel<Pending>(
                kotlinx.coroutines.channels.Channel.UNLIMITED,
            )
        private val dailyController: DailyController?
        private val planController: PlanController?

        init {
            dailyController =
                if (!plan) {
                    DailyController(
                        scope,
                        ::daily,
                        {},
                        {},
                        semantic,
                        { null },
                        WallClock { now },
                        { zone },
                        { ActivityExecutionPauseId("pause") },
                        boundary,
                    )
                } else {
                    null
                }
            planController =
                if (plan) {
                    PlanController(
                        scope,
                        ::week,
                        { _, _, _ -> emptyList() },
                        { CancelledPlanPage(emptyList(), false) },
                        {},
                        { now },
                        { zone },
                        semantic,
                        boundary,
                    )
                } else {
                    null
                }
        }

        private suspend fun daily(query: DailyQuery): DailyRead {
            val context = CalendarTemporalContext(query.now, requireNotNull(query.zoneId))
            val result = DailyRead(emptyList(), emptyList(), emptyList(), null, context)
            return pending(context, result).awaitResult() as DailyRead
        }

        private suspend fun week(query: WeekPlanQuery): WeekPlanRead {
            val context = CalendarTemporalContext(query.now, requireNotNull(query.zoneId))
            val result =
                WeekPlanRead(query.weekStart, query.selectedDate, emptyList(), emptyList(), emptyList(), context)
            return pending(context, result).awaitResult() as WeekPlanRead
        }

        private fun pending(
            context: CalendarTemporalContext,
            result: Any,
        ): Pending =
            Pending(context, result).also {
                reads += it
                check(entered.trySend(it).isSuccess)
            }

        suspend fun nextRead(): Pending = withTimeout(2_000) { entered.receive() }

        suspend fun awaitPublished() {
            withTimeout(2_000) {
                if (dailyController != null) {
                    dailyController.state.first { it.load !is DailyLoadState.Loading }
                } else {
                    requireNotNull(planController).state.first { it.week !is PlanLoad.Loading }
                }
            }
        }

        fun published(): Any? =
            dailyController?.state?.value?.load?.let {
                when (it) {
                    is DailyLoadState.Content -> it.daily
                    is DailyLoadState.Empty -> it.daily
                    else -> it
                }
            } ?: planController
                ?.state
                ?.value
                ?.week
                ?.let { (it as? PlanLoad.Content)?.value ?: it }

        fun failed() =
            dailyController?.state?.value?.load is DailyLoadState.Failure ||
                planController?.state?.value?.week is PlanLoad.Failure

        fun selectedDate() =
            dailyController?.state?.value?.selectedDate ?: requireNotNull(planController).state.value.selectedDate

        fun hide() {
            dailyController?.onHidden()
            planController?.onRouteExited()
        }

        fun show() {
            dailyController?.onVisible()
            planController?.onRouteEntered()
        }

        fun watch(
            scope: CoroutineScope,
            onPublication: () -> Unit,
        ) = scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            if (dailyController != null) {
                dailyController.state.collect { onPublication() }
            } else {
                requireNotNull(planController).state.collect { onPublication() }
            }
        }

        fun close() {
            reads.forEach { it.release.complete(false) }
            dailyController?.close()
            planController?.close()
        }
    }
}
