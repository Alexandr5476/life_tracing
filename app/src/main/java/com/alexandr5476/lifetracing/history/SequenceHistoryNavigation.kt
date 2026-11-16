@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Read-only authority. Loss is permanent, including between two foreground frames. */
internal class SequenceHistoryNavigation(
    private val ownsPresentation: () -> Boolean,
    private val onRevoked: () -> Unit = {},
) {
    var attached = true
    private var revoked = false
    private var issued = false
    private var work: Job? = null

    fun revoke() {
        if (!revoked) {
            revoked = true
            work?.cancel()
            if (issued && attached) onRevoked()
        }
    }

    fun isCurrent(): Boolean {
        if (!attached || revoked) return false
        if (!ownsPresentation()) revoke() else issued = true
        return !revoked
    }

    /** Native requests read live authority; retiring publications must not invalidate existing focus. */
    fun canFocus(): Boolean = Snapshot.withoutReadObservation { isCurrent() }

    fun launch(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ) {
        if (!isCurrent()) return
        work?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { if (isCurrent()) block() }
        work = job
        job.start()
    }
}

/** Production borrows only S1's presentation ownership, never its dispatch capability. */
@Composable
internal fun rememberSequenceHistoryNavigation(
    detail: SequenceHistoryDetail,
    hasTiming: Boolean,
    hasIssue: Boolean,
): SequenceHistoryNavigation {
    val admission = LocalSequenceHistoryAdmission.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val reference = SequenceDetailReference(detail)
    val canonical by rememberUpdatedState(reference)
    var generation by remember { mutableStateOf(0L) }
    val lease =
        remember(reference, admission, lifecycle, resumed, hasTiming, hasIssue, generation) {
            SequenceHistoryNavigation({
                resumed &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    canonical == reference &&
                    (admission?.isShowing(detail) != false)
            }) { generation++ }
        }
    DisposableEffect(lease) {
        val observer = SnapshotStateObserver { it() }
        observer.start()
        observer.observeReads(lease, { it.revoke() }) { lease.isCurrent() }
        onDispose {
            observer.stop()
            observer.clear()
            lease.attached = false
            lease.revoke()
        }
    }
    DisposableEffect(lifecycle, lease) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) lease.revoke()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return lease
}

/** Navigation ordinals select existing typed targets only; they never become correction identities. */
internal enum class SequenceTimingSection { TIMESTAMPS, ROOT, OCCURRENCES, CHILDREN, INTERVALS }

internal class SequenceTimingDestinations(
    private val detail: SequenceHistoryDetail,
    private val targets: List<SequenceHistoryTimestampTarget>,
) {
    val positions = targets.withIndex().associate { it.value to it.index }

    fun target(index: Int): SequenceHistoryTimestampTarget = targets[index]

    fun count(section: SequenceTimingSection): Int =
        when (section) {
            SequenceTimingSection.TIMESTAMPS -> positions.size
            SequenceTimingSection.ROOT -> 2
            SequenceTimingSection.OCCURRENCES, SequenceTimingSection.CHILDREN -> detail.occurrences.size
            SequenceTimingSection.INTERVALS -> detail.intervals.size
        }

    fun index(
        section: SequenceTimingSection,
        ordinal: Int,
    ): Int? {
        if (ordinal !in 1..count(section)) return null
        val position = ordinal - 1
        return when (section) {
            SequenceTimingSection.TIMESTAMPS -> position
            SequenceTimingSection.ROOT ->
                positions[
                    if (position ==
                        0
                    ) {
                        SequenceHistoryTimestampTarget.RootStartedAt
                    } else {
                        SequenceHistoryTimestampTarget.RootEndedAt
                    },
                ]
            SequenceTimingSection.OCCURRENCES ->
                detail.occurrences[position].occurrenceId.let {
                    positions[SequenceHistoryTimestampTarget.OccurrenceEnteredAt(it)]
                        ?: positions[SequenceHistoryTimestampTarget.OccurrenceCompletedAt(it)]
                }
            SequenceTimingSection.CHILDREN ->
                detail.occurrences[position].occurrenceId.let {
                    positions[SequenceHistoryTimestampTarget.ChildStartedAt(it)]
                        ?: positions[SequenceHistoryTimestampTarget.ChildCompletedAt(it)]
                }
            SequenceTimingSection.INTERVALS ->
                detail.intervals[position].id.let {
                    positions[SequenceHistoryTimestampTarget.IntervalStartedAt(it)]
                        ?: positions[SequenceHistoryTimestampTarget.IntervalEndedAt(it)]
                }
        }
    }
}
