package com.alexandr5476.lifetracing.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import com.alexandr5476.lifetracing.data.persistence.PlanSchedulingStore
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.actionIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Real preexisting package resources, distinct from the manifest fixture that must reject them. */
internal suspend fun assertManifestPlatformBaselinePreserved(
    application: RuntimeIsolationApplication,
    mode: TimeTrackingMode,
    planLinked: Boolean,
    armAlarm: Boolean,
    notify: Boolean,
    acceptance: suspend (() -> Unit, () -> Unit) -> Unit,
) {
    RuntimePlatformFixture.requireVacant(application)
    application.withDisposableDatabase { baseline ->
        val originalPlan = seedPlatformBaseline(application, mode, planLinked)
        val original = RuntimeRecoveryStore(application, DATABASE).use { requireNotNull(it.live.getActiveRuntime()) }
        val expected = NextRuntimeDeadlineResolver.resolve(original)
        var ownedToken: PendingIntent? = null
        try {
            if (expected != null) {
                // Only these setup-owned resources may be removed by the outer regression.
                ownedToken = RuntimeDeadlineIntentCodec.pendingIntent(application, expected)
                if (armAlarm) AndroidRuntimeDeadlineScheduler(application).schedule(expected)
                assertEquals(expected, deliveredDeadline(requireNotNull(ownedToken)))
            }
            val alarmsBefore = alarmSignatures(application)
            assertEquals(if (expected != null && armAlarm) 1 else 0, alarmsBefore.size)
            if (expected != null && armAlarm) {
                assertTrue(alarmsBefore.single().contains(expected.at.toEpochMilli().toString()))
            }
            val verify: suspend () -> Unit = {
                val notificationBefore = notificationSignature(application)
                assertEquals(notify, notificationBefore != null)
                repeat(3) { attempt ->
                    val facts = RuntimeRecoveryStore.readOnlyDurableFacts(application, DATABASE)
                    assertNotNull(facts)
                    var entered = false
                    val injected = IllegalStateException("injected manifest failure $attempt")
                    val failureHook = {
                        entered = true
                        throw injected
                    }
                    try {
                        when (attempt) {
                            0 -> acceptance({}, {})
                            1 -> acceptance(failureHook, {})
                            else -> acceptance({}, failureHook)
                        }
                        fail("Occupied platform state must reject manifest acceptance")
                    } catch (rejected: RuntimePlatformOccupiedException) {
                        assertTrue(rejected.message.orEmpty(), rejected.message.orEmpty().contains("occupied"))
                    } finally {
                        // Assert even when an unguarded fixture reaches a failure hook and tears down.
                        assertEquals(ownedToken, RuntimeDeadlineIntentCodec.existingPendingIntent(application))
                        if (ownedToken != null) assertEquals(expected, deliveredDeadline(requireNotNull(ownedToken)))
                        assertEquals(alarmsBefore, alarmSignatures(application))
                        assertEquals(notificationBefore, notificationSignature(application))
                        assertEquals(facts, RuntimeRecoveryStore.readOnlyDurableFacts(application, DATABASE))
                        RuntimeRecoveryStore(application, DATABASE).use { reopened ->
                            assertEquals(original, reopened.live.getActiveRuntime())
                        }
                        planStore(application).use { reopened ->
                            if (originalPlan != null) {
                                assertEquals(originalPlan, reopened.plans.getPlan(originalPlan.id))
                            }
                        }
                        assertEquals(0, baseline.acquisitions)
                    }
                    assertFalse("Rejection must precede fixture writes and manifest delivery", entered)
                }
            }
            if (notify) {
                withRuntimeNotificationsEnabled(application) { publisher, _ ->
                    publisher.publish(original, null)
                    awaitRuntimeSystemState("preexisting real runtime notification") {
                        notificationSignature(application) != null
                    }
                    runBlocking { verify() }
                }
            } else {
                verify()
            }
        } finally {
            ownedToken?.let {
                application.getSystemService(android.app.AlarmManager::class.java).cancel(it)
                it.cancel()
            }
        }
    }
}

private fun seedPlatformBaseline(
    context: Context,
    mode: TimeTrackingMode,
    planLinked: Boolean,
): PlanEntry? {
    val startedAt = Instant.ofEpochMilli(Instant.now().minusSeconds(60).toEpochMilli())
    return planStore(context).use { store ->
        val template =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Preexisting platform runtime",
                    null,
                    mode,
                    if (mode == TimeTrackingMode.TIMER) Duration.ofDays(1) else null,
                ),
                createdAt = startedAt,
            )
        if (planLinked) {
            val plan =
                store.plans.createActivityPlanFromTemplate(
                    template.id,
                    PlanSchedule.FloatingDay(startedAt.atOffset(ZoneOffset.UTC).toLocalDate()),
                    startedAt,
                )
            store.live.startActivityFromPlan(plan.actionIdentity(), startedAt, startedAt, ZoneOffset.UTC)
            assertEquals(PlanEntryStatus.PLANNED, plan.status)
            plan
        } else {
            RuntimeRecoveryStore(context, DATABASE).use { runtimeStore ->
                runtimeStore.activityCommands.startLive(
                    ActivityEntrySource.Template(template.id),
                    startedAt,
                    startedAt,
                    ZoneOffset.UTC,
                )
            }
            null
        }
    }
}

/** Ignore diagnostic countdown text; retain actual alarm object, epoch target, mode and operation. */
private fun alarmSignatures(context: Context): List<List<String>> =
    pendingRuntimeAlarmRecords(runtimeShell("dumpsys alarm"), context.packageName).map { record ->
        listOf(
            "Alarm\\{(\\S+)",
            "(?:origWhen|when) (\\d+)",
            "\\bwindow=(\\S+)",
            "\\bflags=(\\S+)",
            "operation=([^\\n]+)",
        ).map { pattern -> requireNotNull(Regex(pattern).find(record)) { record }.groupValues[1] }
    }

/** postTime/key detect cancellation/republication; payload and flags detect in-place replacement. */
private fun notificationSignature(context: Context): List<Any?>? =
    context
        .getSystemService(NotificationManager::class.java)
        .activeNotifications
        .singleOrNull { it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID && it.tag == null }
        ?.let(::notificationSignature)

private fun notificationSignature(status: StatusBarNotification): List<Any?> =
    status.notification.let { notification ->
        listOf(
            status.key,
            status.postTime,
            status.tag,
            status.id,
            notification.`when`,
            notification.channelId,
            notification.flags,
            notification.visibility,
            notification.category,
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

private fun planStore(context: Context): PlanSchedulingStore =
    PlanSchedulingStore(context, DATABASE, CurrentZoneIdProvider { ZoneOffset.UTC })

private const val DATABASE = "lifetracing.db"
