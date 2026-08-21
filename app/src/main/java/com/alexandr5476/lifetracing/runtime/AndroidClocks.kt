package com.alexandr5476.lifetracing.runtime

import android.os.SystemClock
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import java.time.Clock
import java.time.Instant

class AndroidWallClock(
    private val clock: Clock = Clock.systemUTC(),
) : WallClock {
    override fun now(): Instant = clock.instant()
}

object AndroidMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

class RuntimeClockAnchor(
    private val wallClock: WallClock,
    private val monotonicClock: MonotonicClock,
) {
    @Volatile
    private var anchor = capture()

    fun reset() {
        anchor = capture()
    }

    fun estimatedWallNow(): Instant = anchor.wallAt(monotonicClock.elapsedRealtimeMillis())

    private fun capture() = WallMonotonicAnchor(wallClock.now(), monotonicClock.elapsedRealtimeMillis())
}
