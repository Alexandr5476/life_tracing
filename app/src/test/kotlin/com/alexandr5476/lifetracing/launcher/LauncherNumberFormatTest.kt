package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class LauncherNumberFormatTest {
    @Test
    fun fixedScaleFormatterAndParserPreserveRepresentativeValues() {
        assertEquals("12.345", formatLauncherNumber(12_345, 3))
        assertEquals("0", formatLauncherNumber(0, 2))
        assertEquals("-1.2", formatLauncherNumber(-1_200, 3))
        assertEquals("", formatLauncherNumber(null, 3))
        assertEquals("1.23", formatLauncherNumber(1_234, 2))
        assertEquals(12_345, parseLauncherNumber("12,345", 3))
        assertEquals(1_230, parseLauncherNumber("1.23", 2))
        assertEquals(0, parseLauncherNumber("0", 0))
        assertEquals(-1_200, parseLauncherNumber("-1.2", 3))
        assertNull(parseLauncherNumber("1.234", 2))
        assertNull(parseLauncherNumber("1.2345", null))
        assertNull(parseLauncherNumber("not a number", 3))
    }

    @Test
    fun countdownClampsAtZero() {
        assertEquals("0:00", formatLauncherCountdown(java.time.Duration.ofSeconds(-1)))
        assertEquals("1:05", formatLauncherCountdown(java.time.Duration.ofSeconds(65)))
    }

    @Test
    fun preflightRemainingUsesTheDeadlineAtAndAfterZero() {
        val at = Instant.parse("2026-10-20T12:00:00Z")
        val preflight =
            LauncherCommandState.Preflight(
                7,
                LibraryTemplateId.Activity(ActivityTemplateId("timer")),
                Duration.ofSeconds(30),
                at,
                at.plusSeconds(30),
            )
        assertEquals(Duration.ofSeconds(12), preflightCountdownRemaining(preflight, at.plusSeconds(18)))
        assertEquals(Duration.ZERO, preflightCountdownRemaining(preflight, preflight.endsAt))
        assertEquals(Duration.ZERO, preflightCountdownRemaining(preflight, preflight.endsAt.plusSeconds(5)))
    }

    @Test
    fun untouchedValueNeverUsesTheRoundedPresentationAsAnOverride() {
        val mainValue = ActivityLaunchMainValue(ActivityTemplateFieldId("field"), "Value", null, 2, 1_234)
        val noDefault = mainValue.copy(defaultNumberScaled = null)

        assertNull(quickMainValueOverride(mainValue, "1.23", changed = false))
        assertNull(quickMainValueOverride(noDefault, "", changed = false))
        assertEquals(
            QuickMainValueOverride(mainValue.fieldId, QuickMainValue.Number(0)),
            quickMainValueOverride(mainValue, "0", changed = true),
        )
        assertEquals(
            QuickMainValueOverride(mainValue.fieldId, QuickMainValue.Missing),
            quickMainValueOverride(mainValue, "", changed = true),
        )
        assertEquals(
            QuickMainValueOverride(mainValue.fieldId, QuickMainValue.Number(-1_250)),
            quickMainValueOverride(mainValue, "-1.25", changed = true),
        )
    }
}
