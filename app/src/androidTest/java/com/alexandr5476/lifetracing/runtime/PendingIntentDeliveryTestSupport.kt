package com.alexandr5476.lifetracing.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Observes Android's actual sent Intent, including extras retained in the system token. */
internal fun deliveredDeadline(token: PendingIntent): RuntimeDeadline? {
    val delivered = AtomicReference<Intent>()
    val finished = CountDownLatch(1)
    val context = ApplicationProvider.getApplicationContext<Context>()
    // Observe the system-resolved payload without invoking the unrelated application DB graph.
    // No receiver holds this undeclared permission; Android still delivers the sent Intent to OnFinished.
    token.send(
        context,
        0,
        null,
        PendingIntent.OnFinished { _, intent, _, _, _ ->
            delivered.set(Intent(intent))
            finished.countDown()
        },
        Handler(Looper.getMainLooper()),
        context.packageName + ".permission.TEST_PENDING_INTENT_OBSERVATION",
    )
    assertTrue("PendingIntent delivery callback timed out", finished.await(5, TimeUnit.SECONDS))
    return RuntimeDeadlineIntentCodec.decode(delivered.get())
}
