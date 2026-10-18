package com.alexandr5476.lifetracing.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Observes Android's actual sent Intent, including extras retained in the system token. */
internal fun deliveredDeadline(token: PendingIntent): RuntimeDeadline? =
    RuntimeDeadlineIntentCodec.decode(sentDeadlineIntent(token, suppressManifestReceiver = true))

/** No permission filter, fill-in Intent, component substitution or injected receiver/graph. */
internal fun sendDeadlineToManifest(token: PendingIntent): Intent =
    sentDeadlineIntent(token, suppressManifestReceiver = false)

internal fun assertManifestDeadlineIntent(
    context: Context,
    intent: Intent,
) {
    assertEquals(ComponentName(context, RuntimeDeadlineReceiver::class.java), intent.component)
    assertEquals(RuntimeDeadlineIntentCodec.ACTION_RUNTIME_DEADLINE, intent.action)
    assertEquals("Manifest delivery must be allowed", 0, intent.flags and Intent.FLAG_RECEIVER_REGISTERED_ONLY)
}

private fun sentDeadlineIntent(
    token: PendingIntent,
    suppressManifestReceiver: Boolean,
): Intent {
    val delivered = AtomicReference<Intent>()
    val finished = CountDownLatch(1)
    val context = ApplicationProvider.getApplicationContext<Context>()
    // Payload-only tests use an undeclared permission. Manifest acceptance explicitly omits it.
    token.send(
        context,
        0,
        null,
        PendingIntent.OnFinished { _, intent, _, _, _ ->
            delivered.set(Intent(intent))
            finished.countDown()
        },
        Handler(Looper.getMainLooper()),
        if (suppressManifestReceiver) context.packageName + ".permission.TEST_PENDING_INTENT_OBSERVATION" else null,
    )
    assertTrue("PendingIntent delivery callback timed out", finished.await(5, TimeUnit.SECONDS))
    return requireNotNull(delivered.get())
}
