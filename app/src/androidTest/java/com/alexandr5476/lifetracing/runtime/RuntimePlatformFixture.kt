package com.alexandr5476.lifetracing.runtime

import android.app.NotificationManager
import android.content.Context
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class RuntimePlatformOccupiedException(
    message: String,
) : IllegalStateException(message)

/**
 * Production identities cannot be namespaced without changing the manifest path under test.
 * Claim only empty slots on the exclusively leased, serial instrumentation device. Never capture,
 * update, cancel or repost somebody else's resources. FLAG_NO_CREATE performs a read-only lookup.
 */
internal class RuntimePlatformFixture private constructor(
    private val context: Context,
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            AndroidRuntimeDeadlineScheduler(context).cancel()
        } finally {
            try {
                context
                    .getSystemService(NotificationManager::class.java)
                    .cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
                awaitRuntimeSystemState("owned runtime notification cleanup") {
                    context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                        it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID && it.tag == null
                    }
                }
            } finally {
                inUse.set(false)
            }
        }
    }

    companion object {
        private val inUse = AtomicBoolean()

        fun acquire(context: Context): RuntimePlatformFixture {
            if (!inUse.compareAndSet(false, true)) {
                throw RuntimePlatformOccupiedException("Runtime platform fixture occupied")
            }
            try {
                requireVacant(context)
                return RuntimePlatformFixture(context)
            } catch (failure: Throwable) {
                // No lease was acquired: do not create/update/cancel any token or notification.
                inUse.set(false)
                throw failure
            }
        }

        fun requireVacant(context: Context) {
            if (RuntimeDeadlineIntentCodec.existingPendingIntent(context) != null) {
                throw RuntimePlatformOccupiedException(
                    "Runtime alarm PendingIntent occupied; use an exclusive fresh installation",
                )
            }
            requireNotificationVacant(context)
        }

        fun requireNotificationVacant(context: Context) {
            if (context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID && it.tag == null
                }
            ) {
                throw RuntimePlatformOccupiedException(
                    "Runtime notification occupied; use an exclusive fresh installation",
                )
            }
        }
    }
}

/** A failed preflight never installs an @After cancellation against an unrelated runtime. */
internal class RuntimePlatformFixtureRule(
    private val context: Context,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                RuntimePlatformFixture.acquire(context).use { base.evaluate() }
            }
        }
}
