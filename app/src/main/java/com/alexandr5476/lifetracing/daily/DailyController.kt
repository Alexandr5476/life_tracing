package com.alexandr5476.lifetracing.daily

import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

enum class DailyDateRelation {
    PAST,
    TODAY,
    FUTURE,
}

sealed interface DailyLoadState {
    data object Loading : DailyLoadState

    data class Empty(
        val daily: DailyRead,
    ) : DailyLoadState

    data class Content(
        val daily: DailyRead,
    ) : DailyLoadState

    data class Failure(
        val message: String,
    ) : DailyLoadState
}

sealed interface DailyCommandFailure {
    val message: String

    data class Rejected(
        override val message: String,
    ) : DailyCommandFailure

    data class Coordination(
        override val message: String,
    ) : DailyCommandFailure
}

data class DailyPresentationState(
    val selectedDate: LocalDate,
    val dateRelation: DailyDateRelation,
    val load: DailyLoadState,
    val runtimeDisplayBaseline: RuntimeDisplayBaseline? = null,
    val commandInFlight: Boolean = false,
    val commandFailure: DailyCommandFailure? = null,
)

sealed interface DailyAction {
    data object PreviousDay : DailyAction

    data object NextDay : DailyAction

    data object Today : DailyAction

    data object Retry : DailyAction

    data object Visible : DailyAction

    data object Hidden : DailyAction

    sealed interface Runtime : DailyAction

    data object PauseActivity : Runtime

    data object ResumeActivity : Runtime

    data object FinishActivity : Runtime

    data object PauseSequence : Runtime

    data object ResumeSequence : Runtime

    data object StartNextSequenceStep : Runtime

    data class CompleteCurrentSequenceStep(
        val occurrenceId: SequenceOccurrenceId,
    ) : Runtime
}

internal sealed interface DailyRuntimeCommand {
    val at: Instant

    data class PauseActivity(
        val pauseId: ActivityExecutionPauseId,
        override val at: Instant,
    ) : DailyRuntimeCommand

    data class ResumeActivity(
        override val at: Instant,
    ) : DailyRuntimeCommand

    data class FinishActivity(
        override val at: Instant,
    ) : DailyRuntimeCommand

    data class PauseSequence(
        override val at: Instant,
    ) : DailyRuntimeCommand

    data class ResumeSequence(
        override val at: Instant,
    ) : DailyRuntimeCommand

    data class StartNextSequenceStep(
        override val at: Instant,
    ) : DailyRuntimeCommand

    data class CompleteCurrentSequenceStep(
        val occurrenceId: SequenceOccurrenceId,
        override val at: Instant,
    ) : DailyRuntimeCommand
}

internal fun interface LocalDateBoundaryScheduler {
    fun arm(
        now: Instant,
        zoneId: ZoneId,
        onBoundary: () -> Unit,
    )

    fun cancel() = Unit
}

internal class CoroutineLocalDateBoundaryScheduler(
    private val scope: CoroutineScope,
) : LocalDateBoundaryScheduler {
    private var job: Job? = null

    @Synchronized
    override fun arm(
        now: Instant,
        zoneId: ZoneId,
        onBoundary: () -> Unit,
    ) {
        job?.cancel()
        val nextMidnight = nextLocalDateBoundary(now, zoneId)
        val delayMillis = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
        job =
            scope.launch {
                delay(delayMillis)
                onBoundary()
            }
    }

    @Synchronized
    override fun cancel() {
        job?.cancel()
        job = null
    }
}

internal fun nextLocalDateBoundary(
    now: Instant,
    zoneId: ZoneId,
): Instant =
    now
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()

@Suppress("LongParameterList", "TooManyFunctions") // One state holder owns the complete Daily application boundary.
class DailyController internal constructor(
    private val scope: CoroutineScope,
    private val readDaily: suspend (DailyQuery) -> DailyRead,
    private val executeRuntimeCommand: suspend (DailyRuntimeCommand) -> Unit,
    private val coordinateRuntimeStateChanged: suspend () -> Unit,
    private val semanticGeneration: StateFlow<Long>,
    private val displayBaseline: () -> RuntimeDisplayBaseline?,
    private val wallClock: WallClock,
    private val zoneId: () -> ZoneId,
    private val nextPauseId: () -> ActivityExecutionPauseId,
    private val dateBoundaryScheduler: LocalDateBoundaryScheduler,
    private val completedHistoryLimit: Int = DEFAULT_COMPLETED_HISTORY_LIMIT,
) {
    private val loadGeneration = AtomicLong()
    private val commandMutex = Mutex()

    @Volatile
    private var visible = true

    private val initialNow = wallClock.now()
    private val initialToday = initialNow.atZone(zoneId()).toLocalDate()
    private val mutableState =
        MutableStateFlow(
            DailyPresentationState(initialToday, DailyDateRelation.TODAY, DailyLoadState.Loading),
        )
    val state: StateFlow<DailyPresentationState> = mutableState
    private val initialSemanticGeneration = semanticGeneration.value

    private val invalidationJob =
        scope.launch {
            var handled = initialSemanticGeneration
            semanticGeneration.collect { generation ->
                if (generation != handled) {
                    handled = generation
                    if (visible) {
                        armDateBoundary()
                        refresh()
                    }
                }
            }
        }

    init {
        require(completedHistoryLimit > 0) { "Daily completed-history limit must be positive" }
        armDateBoundary(initialNow)
        refresh(initialNow)
    }

    fun dispatch(action: DailyAction) {
        when (action) {
            DailyAction.PreviousDay -> selectDate(mutableState.value.selectedDate.minusDays(1))
            DailyAction.NextDay -> selectDate(mutableState.value.selectedDate.plusDays(1))
            DailyAction.Today -> selectDate(today())
            DailyAction.Retry -> refresh()
            DailyAction.Visible -> onVisible()
            DailyAction.Hidden -> onHidden()
            is DailyAction.Runtime -> scope.launch { runCommand(action) }
        }
    }

    fun onVisible() {
        visible = true
        armDateBoundary()
        refresh()
    }

    internal fun onRouteEntered() {
        if (!visible) onVisible()
    }

    fun onHidden() {
        visible = false
        dateBoundaryScheduler.cancel()
    }

    fun close() {
        invalidationJob.cancel()
        dateBoundaryScheduler.cancel()
    }

    private fun selectDate(date: LocalDate) {
        mutableState.update { current ->
            current.copy(selectedDate = date, dateRelation = relation(date, today()))
        }
        refresh()
    }

    @Suppress("TooGenericExceptionCaught") // Any reader failure becomes bounded presentation failure.
    private fun refresh(now: Instant = wallClock.now()) {
        val generation = loadGeneration.incrementAndGet()
        val selectedDate = mutableState.value.selectedDate
        val today = now.atZone(zoneId()).toLocalDate()
        mutableState.update {
            it.copy(
                dateRelation = relation(selectedDate, today),
                load = DailyLoadState.Loading,
                runtimeDisplayBaseline = null,
            )
        }
        scope.launch {
            try {
                val daily = readDaily(DailyQuery(selectedDate, now, completedHistoryLimit))
                if (loadGeneration.get() == generation) publish(daily, selectedDate, today)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (loadGeneration.get() == generation) {
                    mutableState.update {
                        it.copy(load = DailyLoadState.Failure(failure.message()), runtimeDisplayBaseline = null)
                    }
                }
            }
        }
    }

    private fun publish(
        daily: DailyRead,
        selectedDate: LocalDate,
        today: LocalDate,
    ) {
        val empty =
            daily.dayPlans.isEmpty() &&
                daily.weekPlans.isEmpty() &&
                daily.completedHistory.isEmpty() &&
                daily.active == null
        val baseline =
            daily.active
                ?.runtime
                ?.takeIf { selectedDate == today }
                ?.let { runtime -> displayBaseline()?.takeIf { it.matches(runtime) } }
        mutableState.update {
            it.copy(
                dateRelation = relation(selectedDate, today),
                load = if (empty) DailyLoadState.Empty(daily) else DailyLoadState.Content(daily),
                runtimeDisplayBaseline = baseline,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // The boundary must distinguish both failure phases without crashing UI.
    private suspend fun runCommand(action: DailyAction.Runtime) {
        commandMutex.withLock {
            val command = command(action)
            if (command == null) {
                mutableState.update {
                    it.copy(commandFailure = DailyCommandFailure.Rejected("Action is not valid for the loaded runtime"))
                }
                return
            }
            mutableState.update { it.copy(commandInFlight = true, commandFailure = null) }
            try {
                executeRuntimeCommand(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(commandInFlight = false, commandFailure = DailyCommandFailure.Rejected(failure.message()))
                }
                refresh()
                return
            }

            try {
                coordinateRuntimeStateChanged()
                mutableState.update { it.copy(commandInFlight = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(
                        commandInFlight = false,
                        commandFailure = DailyCommandFailure.Coordination(failure.message()),
                    )
                }
            }
            refresh()
        }
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive typed action/state validation is intentionally centralized.
    private fun command(action: DailyAction.Runtime): DailyRuntimeCommand? {
        val active = mutableState.value.loadedDaily()?.active ?: return null
        val at = wallClock.now()
        return when (action) {
            DailyAction.PauseActivity ->
                (active as? DailyActive.Activity)
                    ?.takeIf {
                        it.runtime.session.state ==
                            com.alexandr5476.lifetracing.domain.ActiveSessionState.RUNNING
                    }?.let { DailyRuntimeCommand.PauseActivity(nextPauseId(), at) }
            DailyAction.ResumeActivity ->
                (active as? DailyActive.Activity)
                    ?.takeIf {
                        it.runtime.session.state == com.alexandr5476.lifetracing.domain.ActiveSessionState.PAUSED
                    }?.let { DailyRuntimeCommand.ResumeActivity(at) }
            DailyAction.FinishActivity ->
                (active as? DailyActive.Activity)?.let { DailyRuntimeCommand.FinishActivity(at) }
            DailyAction.PauseSequence ->
                (active as? DailyActive.Sequence)
                    ?.takeIf {
                        it.state == DailyActiveSequenceState.RUNNING_CURRENT ||
                            it.state == DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN
                    }?.let { DailyRuntimeCommand.PauseSequence(at) }
            DailyAction.ResumeSequence ->
                (active as? DailyActive.Sequence)
                    ?.takeIf {
                        it.state == DailyActiveSequenceState.PAUSED_CURRENT ||
                            it.state == DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                    }?.let { DailyRuntimeCommand.ResumeSequence(at) }
            DailyAction.StartNextSequenceStep ->
                (active as? DailyActive.Sequence)
                    ?.takeIf { it.state == DailyActiveSequenceState.WAITING_NEXT }
                    ?.let { DailyRuntimeCommand.StartNextSequenceStep(at) }
            is DailyAction.CompleteCurrentSequenceStep ->
                (active as? DailyActive.Sequence)
                    ?.takeIf {
                        it.state == DailyActiveSequenceState.RUNNING_CURRENT &&
                            it.current?.occurrence?.id == action.occurrenceId
                    }?.let { DailyRuntimeCommand.CompleteCurrentSequenceStep(action.occurrenceId, at) }
        }
    }

    private fun onLocalDateBoundary() {
        if (!visible) return
        armDateBoundary()
        refresh()
    }

    private fun armDateBoundary(now: Instant = wallClock.now()) {
        if (visible) dateBoundaryScheduler.arm(now, zoneId(), ::onLocalDateBoundary)
    }

    private fun today(): LocalDate = wallClock.now().atZone(zoneId()).toLocalDate()

    private fun DailyPresentationState.loadedDaily(): DailyRead? =
        when (val current = load) {
            is DailyLoadState.Content -> current.daily
            is DailyLoadState.Empty -> current.daily
            is DailyLoadState.Failure,
            DailyLoadState.Loading,
            -> null
        }

    private fun Throwable.message(): String = message ?: javaClass.simpleName

    private fun relation(
        selectedDate: LocalDate,
        today: LocalDate,
    ): DailyDateRelation =
        when {
            selectedDate < today -> DailyDateRelation.PAST
            selectedDate > today -> DailyDateRelation.FUTURE
            else -> DailyDateRelation.TODAY
        }

    private companion object {
        const val DEFAULT_COMPLETED_HISTORY_LIMIT = 100
    }
}
