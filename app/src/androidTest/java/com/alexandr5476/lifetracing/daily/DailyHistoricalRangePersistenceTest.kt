@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.alexandr5476.lifetracing.daily

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone
import java.util.UUID

class DailyHistoricalRangePersistenceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun equalClocksOnConsecutiveOriginalDatesRemainDistinctAfterReload() {
        verifyRange("2026-06-01T08:00:00Z", "2026-06-02T08:00:00Z", includeDate = true)
    }

    @Test
    fun shortOriginalZoneOvernightRangeShowsBothDatesAfterReload() {
        verifyRange("2026-06-01T21:50:00Z", "2026-06-01T22:30:00Z", includeDate = true)
    }

    @Test
    fun sameOriginalDayAcrossUtcMidnightKeepsCompactRangeAfterReload() {
        verifyRange("2026-06-01T22:30:00Z", "2026-06-02T08:00:00Z", includeDate = false)
    }

    @Test
    fun repeatedOriginalClockShowsBothActualOffsetsAfterReload() {
        verifyRange("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z", includeDate = false)
    }

    private fun verifyRange(
        startText: String,
        endText: String,
        includeDate: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "daily-historical-range-${UUID.randomUUID()}.db"
        val originalDeviceZone = TimeZone.getDefault()
        val zone = ZoneId.of("Europe/Berlin")
        val start = Instant.parse(startText)
        val end = Instant.parse(endText)
        val primaryDate = start.atZone(zone).toLocalDate()
        val endDate = end.atZone(zone).toLocalDate()
        val duration = Duration.between(start, end)
        var store = RuntimeRecoveryStore(context, name)
        try {
            val activityTemplate =
                store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Timed Activity", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = start,
                )
            val activity = store.library.startActivityFromTemplate(activityTemplate.id, start, start, zone)
            store.live.completeActiveActivity(end)
            val sequences =
                listOf(false, true).map { early ->
                    val template =
                        store.authoring.createSequenceTemplate(
                            SequenceTemplateDraft(
                                if (early) "Ended Sequence" else "Completed Sequence",
                                null,
                                nodes =
                                    listOf(
                                        SequenceNodeDraft.Step(
                                            ActivityStepDraft(
                                                DraftIdentity.New("step"),
                                                0,
                                                StepActivityDraft.FromTemplate(activityTemplate.id),
                                            ),
                                        ),
                                    ),
                            ),
                            createdAt = start,
                        )
                    val running = store.library.startSequenceFromTemplate(template.id, start, start, zone)
                    if (early) {
                        store.live.endSequenceEarly(end)
                    } else {
                        store.live.completeCurrentSequenceStep(running.execution.currentOccurrenceId!!, end)
                    }
                    store.sequence(running.execution.id)
                }
            val completedActivity = store.activity(activity.id)
            val beforeRoots = store.daily.getDaily(DailyQuery(primaryDate, end, 20, zone)).completedHistory
            val beforeFacts = store.durableFacts()
            val beforeStatistics = store.statistics.overview(StatisticsPeriod.Day(primaryDate))
            assertEquals(duration.multipliedBy(3), beforeStatistics.global.totalTrackedDuration)
            assertEquals(3L, beforeStatistics.global.topLevelExecutionCount)
            if (endDate != primaryDate) {
                assertEquals(0L, store.statistics.global(StatisticsPeriod.Day(endDate)).topLevelExecutionCount)
            }
            store.close()
            store = RuntimeRecoveryStore(context, name)
            val presentation =
                mutableStateOf(DailyPresentationState(primaryDate, DailyDateRelation.PAST, DailyLoadState.Loading))
            compose.setContent {
                LifeTracingTheme { DailyScreen(presentation.value, onAction = {}) }
            }
            for (deviceZone in listOf("Asia/Tokyo", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                val read = store.daily.getDaily(DailyQuery(primaryDate, end, 20))
                assertEquals(beforeRoots, read.completedHistory)
                assertEquals(3, read.completedHistory.size)
                read.completedHistory.forEach { root ->
                    assertEquals(primaryDate, root.primaryLocalDate)
                    assertEquals(end, root.completedAt)
                    assertEquals(zone, root.originalZoneId)
                    assertEquals(start.atZone(zone).offset.totalSeconds / 60, root.originalUtcOffsetMinutes)
                    when (root) {
                        is CompletedActivityHistoryRoot -> {
                            assertEquals(start, root.startedAt)
                            assertEquals(duration, root.activeDuration)
                            assertEquals(completedActivity.snapshotId, root.snapshotId)
                        }
                        is CompletedSequenceHistoryRoot -> {
                            assertEquals(start, root.startedAt)
                            assertEquals(duration, root.activeDuration)
                            assertEquals(duration, root.wallDuration)
                            assertEquals(sequences.single { it.id == root.executionId }.snapshotId, root.snapshotId)
                        }
                    }
                }
                compose.runOnIdle {
                    presentation.value =
                        DailyPresentationState(primaryDate, DailyDateRelation.PAST, DailyLoadState.Content(read))
                }
                val expected =
                    compose.activity.getString(
                        R.string.daily_time_range,
                        endpoint(start, zone, includeDate),
                        endpoint(end, zone, includeDate),
                    )
                val rows = compose.onAllNodesWithText(expected)
                rows.assertCountEquals(3)
                repeat(3) { rows[it].performScrollTo().assertIsDisplayed() }
                assertEquals(completedActivity, store.activity(activity.id))
                sequences.forEach { assertEquals(it, store.sequence(it.id)) }
                assertEquals(beforeStatistics, store.statistics.overview(StatisticsPeriod.Day(primaryDate)))
                assertEquals(beforeFacts, store.durableFacts())
            }
        } finally {
            store.close()
            context.deleteDatabase(name)
            TimeZone.setDefault(originalDeviceZone)
        }
    }

    private fun endpoint(
        instant: Instant,
        zone: ZoneId,
        includeDate: Boolean,
    ): String {
        val zoned = instant.atZone(zone)
        val formatter =
            if (includeDate) {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            } else {
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            }
        val locale = compose.activity.resources.configuration.locales[0]
        return compose.activity.getString(
            R.string.history_timestamp_context,
            formatter.withLocale(locale).format(zoned),
            zoned.offset.id,
            zone.id,
        )
    }
}
