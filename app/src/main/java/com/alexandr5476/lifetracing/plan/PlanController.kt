@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.plan

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.daily.CoroutineLocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.daily.nextPlanTemporalBoundary
import com.alexandr5476.lifetracing.domain.CalendarTemporalContext
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LocalDateTimeResolutionException
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.StalePlanActionException
import com.alexandr5476.lifetracing.domain.UnsupportedPlanTimePrecisionException
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.domain.actionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface PlanLoad<out T> {
    data object Loading : PlanLoad<Nothing>

    data class Content<T>(
        val value: T,
    ) : PlanLoad<T>

    data class Failure(
        val message: PlanMessage,
    ) : PlanLoad<Nothing>
}

enum class PlanMessage {
    LOAD_FAILED,
    INVALID_SCHEDULE,
    INVALID_DATE,
    INVALID_TIME,
    UNSUPPORTED_TIME_PRECISION,
    NONEXISTENT_TIME,
    AMBIGUOUS_TIME,
    INVALID_OFFSET,
    ZONE_CHANGED,
    ACTION_UNAVAILABLE,
    PERSISTENCE_FAILED,
}

enum class PlanScheduleKind { FLOATING_DAY, EXACT_DAY, WEEK }

data class PlanCatalogState(
    val query: String = "",
    val items: List<ReusablePlanCatalogItem> = emptyList(),
    val hasNextPage: Boolean = false,
    val loading: Boolean = false,
    val failure: PlanMessage? = null,
)

data class PlanPresentationState(
    val weekStart: LocalDate,
    val selectedDate: LocalDate,
    val week: PlanLoad<WeekPlanRead> = PlanLoad.Loading,
    val catalog: PlanCatalogState? = null,
    val form: PlanScheduleForm? = null,
    val cancelledOpen: Boolean = false,
    val cancelled: PlanLoad<CancelledPlanPage>? = null,
    val cancelledItems: List<PlanReadRow> = emptyList(),
    val cancelledHasNextPage: Boolean = false,
    val isMutating: Boolean = false,
    val mutationFailure: PlanMessage? = null,
    val recoveryFailure: PlanMessage? = null,
)

sealed interface PlanAction {
    data object PreviousWeek : PlanAction

    data object NextWeek : PlanAction

    data object Today : PlanAction

    data class SelectDate(
        val date: LocalDate,
    ) : PlanAction

    data object Refresh : PlanAction

    data object OpenCatalog : PlanAction

    data object DismissCatalog : PlanAction

    data class SearchCatalog(
        val query: String,
    ) : PlanAction

    data object LoadMoreCatalog : PlanAction

    data class SelectCatalogItem(
        val item: ReusablePlanCatalogItem,
    ) : PlanAction

    data class Reschedule(
        val row: PlanReadRow,
    ) : PlanAction

    data class ChangeScheduleKind(
        val kind: PlanScheduleKind,
    ) : PlanAction

    data class ChangeScheduleDate(
        val value: String,
    ) : PlanAction

    data class ChangeScheduleTime(
        val value: String,
    ) : PlanAction

    data class SelectScheduleOffset(
        val offset: ZoneOffset,
        val date: String,
        val time: String,
        val zoneId: ZoneId,
        val inputRevision: Long,
        val formId: UUID,
    ) : PlanAction

    data object RevalidateScheduleZone : PlanAction

    data object DismissForm : PlanAction

    data object SubmitForm : PlanAction

    data class Cancel(
        val row: PlanReadRow,
    ) : PlanAction

    data class UpdateFromTemplate(
        val row: PlanReadRow,
    ) : PlanAction

    data object OpenCancelled : PlanAction

    data object DismissCancelled : PlanAction

    data object LoadMoreCancelled : PlanAction

    data class Restore(
        val row: PlanReadRow,
    ) : PlanAction
}

sealed interface PlanMutation {
    data class CreateActivity(
        val id: com.alexandr5476.lifetracing.domain.ActivityTemplateId,
        val schedule: PlanSchedule,
        val at: Instant,
    ) : PlanMutation

    data class CreateSequence(
        val id: com.alexandr5476.lifetracing.domain.SequenceTemplateId,
        val schedule: PlanSchedule,
        val at: Instant,
    ) : PlanMutation

    data class Reschedule(
        val identity: PlanActionIdentity,
        val schedule: PlanSchedule,
        val at: Instant,
    ) : PlanMutation

    data class PreserveSchedule(
        val identity: PlanActionIdentity,
        val at: Instant,
    ) : PlanMutation

    data class Cancel(
        val identity: PlanActionIdentity,
        val at: Instant,
    ) : PlanMutation

    data class Restore(
        val identity: PlanActionIdentity,
        val at: Instant,
    ) : PlanMutation

    data class Update(
        val identity: PlanActionIdentity,
        val at: Instant,
    ) : PlanMutation
}

@Suppress("LargeClass") // One owner keeps Plan commands and canonical recovery atomic with calendar publication.
class PlanController internal constructor(
    private val scope: CoroutineScope,
    private val readWeek: suspend (WeekPlanQuery) -> WeekPlanRead,
    private val readCatalog: suspend (String, Int, ReusablePlanCatalogItem?) -> List<ReusablePlanCatalogItem>,
    private val readCancelled: suspend (CancelledPlanPageQuery) -> CancelledPlanPage,
    private val commitMutation: suspend (PlanMutation) -> Unit,
    private val now: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val semanticGeneration: StateFlow<Long> = MutableStateFlow(0L),
    private val boundaryScheduler: LocalDateBoundaryScheduler = CoroutineLocalDateBoundaryScheduler(scope),
    initiallyVisible: Boolean = true,
) {
    private val readGeneration = AtomicLong()
    private val catalogGeneration = AtomicLong()
    private val cancelledGeneration = AtomicLong()
    private val mutationInFlight = AtomicBoolean()
    private val recoveryLock = Any()
    private val initialDate = now().atZone(zoneId()).toLocalDate()
    private val mutableState = MutableStateFlow(PlanPresentationState(initialDate.monday(), initialDate))
    val state: StateFlow<PlanPresentationState> = mutableState

    @Volatile private var closed = false

    @Volatile private var visible = initiallyVisible

    @Volatile private var recovery: MutationRecovery? = null

    private val initialSemanticGeneration = semanticGeneration.value
    private val invalidationJob =
        scope.launch {
            var handled = initialSemanticGeneration
            semanticGeneration.collect { generation ->
                if (generation != handled) {
                    handled = generation
                    checkDraftZone()
                    if (visible) {
                        loadWeek()
                    }
                }
            }
        }

    init {
        loadWeek()
    }

    fun dispatch(action: PlanAction): Unit =
        synchronized(recoveryLock) {
            when (action) {
                PlanAction.PreviousWeek -> changeWeek(-1)
                PlanAction.NextWeek -> changeWeek(1)
                PlanAction.Today -> today()
                is PlanAction.SelectDate -> selectDate(action.date)
                PlanAction.Refresh ->
                    if (recovery == null) {
                        loadWeek(clearFailure = true)
                    } else {
                        retryRecovery()
                    }
                PlanAction.OpenCatalog -> openCatalog()
                PlanAction.DismissCatalog -> {
                    catalogGeneration.incrementAndGet()
                    mutableState.update { it.copy(catalog = null) }
                }
                is PlanAction.SearchCatalog -> loadCatalog(action.query, null, false)
                PlanAction.LoadMoreCatalog ->
                    mutableState.value.catalog?.let {
                        if (it.hasNextPage &&
                            !it.loading
                        ) {
                            loadCatalog(it.query, it.items.lastOrNull(), true)
                        }
                    }
                is PlanAction.SelectCatalogItem -> selectCatalogItem(action.item)
                is PlanAction.Reschedule -> openReschedule(action.row)
                is PlanAction.ChangeScheduleKind ->
                    updateForm { form ->
                        if (form.kind == action.kind) {
                            form
                        } else {
                            form.edited().copy(
                                kind = action.kind,
                                date =
                                    if (action.kind ==
                                        PlanScheduleKind.WEEK
                                    ) {
                                        mutableState.value.weekStart.toString()
                                    } else {
                                        form.date
                                    },
                            )
                        }
                    }
                is PlanAction.ChangeScheduleDate ->
                    updateForm { form ->
                        if (form.date == action.value) form else form.edited().copy(date = action.value)
                    }
                is PlanAction.ChangeScheduleTime ->
                    updateForm { form ->
                        if (form.time == action.value) form else form.edited().copy(time = action.value)
                    }
                is PlanAction.SelectScheduleOffset -> selectOffset(action)
                PlanAction.RevalidateScheduleZone ->
                    updateForm {
                        it.copy(
                            schedulingZoneId = zoneId(),
                            selectedOffset = null,
                            zoneReviewRequired = false,
                            error = null,
                            inputRevision =
                                it.inputRevision + 1,
                        )
                    }
                PlanAction.DismissForm -> mutableState.update { it.copy(form = null) }
                PlanAction.SubmitForm -> submitForm()
                is PlanAction.Cancel -> mutate(PlanMutation.Cancel(action.row.plan.actionIdentity(), now()))
                is PlanAction.UpdateFromTemplate -> mutate(PlanMutation.Update(action.row.plan.actionIdentity(), now()))
                PlanAction.OpenCancelled -> {
                    mutableState.update { it.copy(cancelledOpen = true) }
                    loadCancelled(false)
                }
                PlanAction.DismissCancelled -> {
                    cancelledGeneration.incrementAndGet()
                    mutableState.update {
                        it.copy(
                            cancelledOpen = false,
                            cancelled = null,
                            cancelledItems = emptyList(),
                            cancelledHasNextPage = false,
                        )
                    }
                    if (recovery?.needsCancelled == true) loadCancelled(false)
                }
                PlanAction.LoadMoreCancelled -> if (mutableState.value.cancelledHasNextPage) loadCancelled(true)
                is PlanAction.Restore -> mutate(PlanMutation.Restore(action.row.plan.actionIdentity(), now()))
            }
        }

    fun close(): Unit =
        synchronized(recoveryLock) {
            closed = true
            visible = false
            invalidationJob.cancel()
            boundaryScheduler.cancel()
            readGeneration.incrementAndGet()
            catalogGeneration.incrementAndGet()
            cancelledGeneration.incrementAndGet()
        }

    fun onRouteEntered(): Unit =
        synchronized(recoveryLock) {
            if (closed) return
            visible = true
            checkDraftZone()
            if (recovery == null) loadWeek(clearFailure = true) else retryRecovery()
        }

    fun onRouteExited(): Unit =
        synchronized(recoveryLock) {
            visible = false
            readGeneration.incrementAndGet()
            boundaryScheduler.cancel()
            catalogGeneration.incrementAndGet()
            cancelledGeneration.incrementAndGet()
            mutableState.update {
                it.copy(
                    catalog = null,
                    form = null,
                    cancelledOpen = false,
                    cancelled = null,
                    cancelledItems = emptyList(),
                    cancelledHasNextPage = false,
                    mutationFailure = null,
                )
            }
        }

    private fun changeWeek(delta: Long) {
        val state = mutableState.value
        val week = state.weekStart.plusWeeks(delta)
        updateContext(
            week,
            week.plusDays(
                java.time.temporal.ChronoUnit.DAYS
                    .between(state.weekStart, state.selectedDate),
            ),
        )
    }

    private fun today() {
        val date = now().atZone(zoneId()).toLocalDate()
        updateContext(date.monday(), date)
    }

    private fun selectDate(date: LocalDate) {
        val state = mutableState.value
        if (date in
            state.weekStart..state.weekStart.plusDays(6)
        ) {
            updateContext(state.weekStart, date)
        }
    }

    private fun updateContext(
        weekStart: LocalDate,
        selectedDate: LocalDate,
    ): Unit =
        synchronized(recoveryLock) {
            mutableState.update { it.copy(weekStart = weekStart, selectedDate = selectedDate) }
            loadWeek()
        }

    private fun loadWeek(clearFailure: Boolean = false): Unit =
        synchronized(recoveryLock) {
            if (closed || !visible) return
            val generation = readGeneration.incrementAndGet()
            synchronized(recoveryLock) {
                recovery?.let {
                    it.weekGeneration = generation
                    it.weekPublished = false
                }
            }
            val state = mutableState.value
            val context = CalendarTemporalContext(now(), zoneId())
            val semantic = semanticGeneration.value
            if (!publishIfOwned(readGeneration, generation) {
                    it.copy(week = PlanLoad.Loading, mutationFailure = if (clearFailure) null else it.mutationFailure)
                }
            ) {
                return
            }
            armBoundary(context = context, generation = generation, semantic = semantic, requested = state)
            scope.launch {
                try {
                    val read = readWeek(WeekPlanQuery(state.weekStart, state.selectedDate, context.now, context.zoneId))
                    synchronized(recoveryLock) {
                        if (ownsWeek(generation, state, context, semantic)) {
                            if (read.temporalContext != null && read.temporalContext != context) {
                                loadWeek()
                            } else if (read.weekStart == state.weekStart &&
                                read.selectedDate == state.selectedDate &&
                                publishIfOwned(readGeneration, generation) { it.copy(week = PlanLoad.Content(read)) }
                            ) {
                                armBoundary(read, context, generation, semantic, state)
                                canonicalPublished(CanonicalSurface.WEEK, generation)
                            }
                        } else {
                            retryChangedZone(generation, semantic, context)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(recoveryLock) {
                        if (ownsWeek(generation, state, context, semantic)) {
                            if (publishIfOwned(readGeneration, generation) {
                                    it.copy(week = PlanLoad.Failure(PlanMessage.LOAD_FAILED))
                                }
                            ) {
                                canonicalFailed(CanonicalSurface.WEEK, generation)
                            }
                        } else {
                            retryChangedZone(generation, semantic, context)
                        }
                    }
                }
            }
        }

    private fun openCatalog() {
        mutableState.update { it.copy(catalog = PlanCatalogState()) }
        loadCatalog("", null, false)
    }

    private fun loadCatalog(
        query: String,
        after: ReusablePlanCatalogItem?,
        append: Boolean,
    ): Unit =
        synchronized(recoveryLock) {
            val generation = catalogGeneration.incrementAndGet()
            if (!publishIfOwned(catalogGeneration, generation) {
                    it.copy(
                        catalog =
                            (it.catalog ?: PlanCatalogState()).copy(
                                query = query,
                                loading = true,
                                items = if (append) it.catalog?.items.orEmpty() else emptyList(),
                                failure = null,
                            ),
                    )
                }
            ) {
                return
            }
            scope.launch {
                try {
                    val page = readCatalog(query, CATALOG_PAGE_SIZE + 1, after)
                    publishIfOwned(catalogGeneration, generation) { state ->
                        val catalog = state.catalog?.takeIf { it.query == query } ?: return@publishIfOwned null
                        val prior = if (append) catalog.items else emptyList()
                        state.copy(
                            catalog =
                                PlanCatalogState(
                                    query,
                                    (prior + page.take(CATALOG_PAGE_SIZE)).distinctBy { it.id },
                                    page.size > CATALOG_PAGE_SIZE,
                                    false,
                                    null,
                                ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publishIfOwned(catalogGeneration, generation) { state ->
                        val catalog = state.catalog?.takeIf { it.query == query } ?: return@publishIfOwned null
                        state.copy(catalog = catalog.copy(loading = false, failure = PlanMessage.LOAD_FAILED))
                    }
                }
            }
        }

    private fun selectCatalogItem(item: ReusablePlanCatalogItem) {
        if (closed || mutationInFlight.get()) return
        val state = mutableState.value
        mutableState.update {
            it.copy(
                catalog = null,
                form =
                    PlanScheduleForm(
                        item,
                        null,
                        PlanScheduleKind.FLOATING_DAY,
                        state.selectedDate.toString(),
                        "09:00",
                        schedulingZoneId = zoneId(),
                        formId = UUID.randomUUID(),
                    ),
            )
        }
    }

    private fun openReschedule(row: PlanReadRow) {
        if (mutableState.value.isMutating || !row.isMutable()) return
        val zone = zoneId()
        val (kind, date, time) = row.plan.target.toForm(zone)
        mutableState.update {
            it.copy(
                form =
                    PlanScheduleForm(
                        null,
                        row.plan.actionIdentity(),
                        kind,
                        date,
                        time,
                        zone,
                        formId = UUID.randomUUID(),
                    ),
            )
        }
    }

    private fun PlanScheduleForm.edited() =
        copy(
            scheduleEdited = true,
            selectedOffset = null,
            error = null,
            inputRevision =
                inputRevision + 1,
        )

    private fun updateForm(transform: (PlanScheduleForm) -> PlanScheduleForm) {
        if (closed || mutationInFlight.get()) return
        mutableState.update { state ->
            state.form?.let {
                state.copy(form = PlanScheduleInput.evaluate(PlanScheduleInput.checkZone(transform(it), zoneId())))
            } ?: state
        }
    }

    private fun checkDraftZone() {
        mutableState.update { state ->
            state.form?.let { state.copy(form = PlanScheduleInput.checkZone(it, zoneId())) } ?: state
        }
    }

    private fun selectOffset(action: PlanAction.SelectScheduleOffset) {
        if (closed || mutationInFlight.get()) return
        mutableState.update { state ->
            val form = state.form ?: return@update state
            if (form.formId != action.formId) return@update state
            val checked = PlanScheduleInput.checkZone(form, zoneId())
            if (checked.zoneReviewRequired) return@update state.copy(form = checked)
            val currentTuple =
                form.kind == PlanScheduleKind.EXACT_DAY &&
                    !form.preservesExistingSchedule &&
                    form.date == action.date &&
                    form.time == action.time &&
                    form.schedulingZoneId == action.zoneId &&
                    form.inputRevision == action.inputRevision
            val selected =
                if (currentTuple) {
                    PlanScheduleInput.evaluate(form.copy(selectedOffset = action.offset))
                } else {
                    form.copy(selectedOffset = null, error = PlanMessage.INVALID_OFFSET)
                }
            state.copy(form = selected)
        }
    }

    private fun submitForm() {
        if (closed || mutationInFlight.get()) return
        val original = mutableState.value.form ?: return
        val form = PlanScheduleInput.evaluate(PlanScheduleInput.checkZone(original, zoneId()))
        mutableState.update { it.copy(form = form) }
        if (form.error == null) {
            val at = now()
            val mutation =
                if (form.preservesExistingSchedule) {
                    PlanMutation.PreserveSchedule(requireNotNull(form.identity), at)
                } else {
                    val schedule = PlanScheduleInput.schedule(form)
                    when (val source = form.source?.id) {
                        is LibraryTemplateId.Activity -> PlanMutation.CreateActivity(source.id, schedule, at)
                        is LibraryTemplateId.Sequence -> PlanMutation.CreateSequence(source.id, schedule, at)
                        null -> PlanMutation.Reschedule(requireNotNull(form.identity), schedule, at)
                    }
                }
            mutate(mutation)
        }
    }

    private fun mutate(command: PlanMutation) {
        if (closed || !mutationInFlight.compareAndSet(false, true)) return
        mutableState.update { it.copy(isMutating = true, mutationFailure = null) }
        scope.launch {
            var failureMessage: PlanMessage? = null
            try {
                val proposal =
                    when (command) {
                        is PlanMutation.CreateActivity -> command.schedule
                        is PlanMutation.CreateSequence -> command.schedule
                        is PlanMutation.Reschedule -> command.schedule
                        else -> null
                    }
                if (proposal is PlanSchedule.ExactDay && proposal.currentZoneId != zoneId()) {
                    mutationInFlight.set(false)
                    mutableState.update {
                        it.copy(
                            isMutating = false,
                            form =
                                it.form?.let { form ->
                                    PlanScheduleInput.checkZone(form, zoneId())
                                },
                        )
                    }
                    return@launch
                }
                commitMutation(command)
            } catch (cancelled: CancellationException) {
                mutationInFlight.set(false)
                throw cancelled
            } catch (invalid: LocalDateTimeResolutionException) {
                mutationInFlight.set(false)
                mutableState.update { state ->
                    state.copy(
                        isMutating = false,
                        form = state.form?.let { PlanScheduleInput.failed(it, invalid.failure) },
                    )
                }
                return@launch
            } catch (_: UnsupportedPlanTimePrecisionException) {
                mutationInFlight.set(false)
                mutableState.update { state ->
                    state.copy(
                        isMutating = false,
                        form = state.form?.copy(error = PlanMessage.UNSUPPORTED_TIME_PRECISION),
                    )
                }
                return@launch
            } catch (_: StalePlanActionException) {
                failureMessage = PlanMessage.ACTION_UNAVAILABLE
            } catch (_: IllegalArgumentException) {
                failureMessage = PlanMessage.ACTION_UNAVAILABLE
            } catch (_: Exception) {
                failureMessage = PlanMessage.PERSISTENCE_FAILED
            }
            if (!closed) {
                mutableState.update {
                    it.copy(
                        form = null,
                        mutationFailure = failureMessage,
                    )
                }
                beginRecovery(command is PlanMutation.Cancel || command is PlanMutation.Restore)
            } else {
                mutationInFlight.set(false)
            }
        }
    }

    private fun beginRecovery(needsCancelled: Boolean) {
        synchronized(recoveryLock) { recovery = MutationRecovery(needsCancelled) }
        retryRecovery()
    }

    private fun retryRecovery() {
        synchronized(recoveryLock) {
            mutableState.update { it.copy(recoveryFailure = null) }
            loadWeek()
            if (recovery?.needsCancelled == true) loadCancelled(false)
        }
    }

    private fun canonicalFailed(
        surface: CanonicalSurface,
        generation: Long,
    ) {
        synchronized(recoveryLock) {
            val ownsRecovery =
                recovery?.let {
                    generation ==
                        when (surface) {
                            CanonicalSurface.WEEK -> it.weekGeneration
                            CanonicalSurface.CANCELLED -> it.cancelledGeneration
                        }
                } == true
            if (ownsRecovery) {
                mutableState.update { it.copy(recoveryFailure = PlanMessage.LOAD_FAILED) }
            }
        }
    }

    private fun canonicalPublished(
        surface: CanonicalSurface,
        generation: Long,
    ) {
        val completed =
            synchronized(recoveryLock) {
                val pending = recovery
                val ownsSurface =
                    pending != null &&
                        when (surface) {
                            CanonicalSurface.WEEK -> generation == pending.weekGeneration
                            CanonicalSurface.CANCELLED -> generation == pending.cancelledGeneration
                        }
                if (ownsSurface && surface == CanonicalSurface.WEEK) pending?.weekPublished = true
                if (ownsSurface && surface == CanonicalSurface.CANCELLED) pending?.cancelledPublished = true
                val weekReady = pending?.weekPublished == true
                val cancelledReady = pending != null && (!pending.needsCancelled || pending.cancelledPublished)
                if (ownsSurface && weekReady && cancelledReady) {
                    recovery = null
                    true
                } else {
                    false
                }
            }
        if (completed) {
            mutationInFlight.set(false)
            mutableState.update { it.copy(isMutating = false, recoveryFailure = null) }
        }
    }

    private fun loadCancelled(append: Boolean): Unit =
        synchronized(recoveryLock) {
            val generation = cancelledGeneration.incrementAndGet()
            if (!append) {
                synchronized(recoveryLock) {
                    recovery?.let {
                        it.cancelledGeneration = generation
                        it.cancelledPublished = false
                    }
                }
            }
            val offset = if (append) mutableState.value.cancelledItems.size else 0
            if (!publishIfOwned(cancelledGeneration, generation) { it.copy(cancelled = PlanLoad.Loading) }) return
            scope.launch {
                try {
                    val page = readCancelled(CancelledPlanPageQuery(offset, CANCELLED_PAGE_SIZE))
                    if (publishIfOwned(cancelledGeneration, generation) { state ->
                            val items = if (append) state.cancelledItems + page.items else page.items
                            state.copy(
                                cancelled = PlanLoad.Content(page),
                                cancelledItems = items,
                                cancelledHasNextPage = page.hasNextPage,
                            )
                        }
                    ) {
                        if (!append) canonicalPublished(CanonicalSurface.CANCELLED, generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (publishIfOwned(cancelledGeneration, generation) {
                            it.copy(cancelled = PlanLoad.Failure(PlanMessage.LOAD_FAILED))
                        }
                    ) {
                        if (!append) canonicalFailed(CanonicalSurface.CANCELLED, generation)
                    }
                }
            }
        }

    private inline fun publishIfOwned(
        owner: AtomicLong,
        generation: Long,
        transform: (PlanPresentationState) -> PlanPresentationState?,
    ): Boolean =
        synchronized(recoveryLock) {
            var published = false
            var eligible = !closed && visible && generation == owner.get()
            while (eligible && !published) {
                val current = mutableState.value
                val replacement = transform(current)
                eligible = replacement != null && !closed && visible && generation == owner.get()
                if (eligible) published = mutableState.compareAndSet(current, requireNotNull(replacement))
            }
            published
        }

    private fun LocalDate.monday() = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun ownsWeek(
        generation: Long,
        requested: PlanPresentationState,
        context: CalendarTemporalContext,
        semantic: Long,
    ): Boolean =
        !closed &&
            visible &&
            readGeneration.get() == generation &&
            mutableState.value.weekStart == requested.weekStart &&
            mutableState.value.selectedDate == requested.selectedDate &&
            semanticGeneration.value == semantic &&
            zoneId() == context.zoneId

    private fun retryChangedZone(
        generation: Long,
        semantic: Long,
        context: CalendarTemporalContext,
    ) {
        val ownsGeneration = !closed && visible && readGeneration.get() == generation
        if (ownsGeneration && semanticGeneration.value == semantic && zoneId() != context.zoneId) {
            loadWeek()
        }
    }

    private fun armBoundary(
        read: WeekPlanRead? = null,
        context: CalendarTemporalContext,
        generation: Long,
        semantic: Long,
        requested: PlanPresentationState,
    ) {
        if (!ownsWeek(generation, requested, context, semantic)) return
        boundaryScheduler.arm(
            now(),
            context.zoneId,
            read?.let {
                nextPlanTemporalBoundary(
                    it.selectedDayPlans.map(PlanReadRow::plan) + it.weekPlans.map(PlanReadRow::plan),
                    context.now,
                    context.zoneId,
                )
            },
        ) {
            synchronized(recoveryLock) {
                if (ownsWeek(generation, requested, context, semantic)) loadWeek()
            }
        }
    }

    private fun PlanReadRow.isMutable() = plan.status == PlanEntryStatus.PLANNED && !engaged

    private fun PlanTarget.toForm(zone: ZoneId) =
        when (this) {
            is PlanTarget.FloatingDay -> Triple(PlanScheduleKind.FLOATING_DAY, date.toString(), "09:00")
            is PlanTarget.ExactDay ->
                scheduledAt.atZone(zone).let {
                    Triple(
                        PlanScheduleKind.EXACT_DAY,
                        it.toLocalDate().toString(),
                        it
                            .toLocalTime()
                            .withSecond(0)
                            .withNano(0)
                            .toString(),
                    )
                }
            is PlanTarget.Week -> Triple(PlanScheduleKind.WEEK, weekStart.toString(), "09:00")
            is PlanTarget.Month -> error("Month scheduling is unavailable")
        }

    private companion object {
        const val CATALOG_PAGE_SIZE = 30
        const val CANCELLED_PAGE_SIZE = 30
    }

    private enum class CanonicalSurface { WEEK, CANCELLED }

    private data class MutationRecovery(
        val needsCancelled: Boolean,
        var weekGeneration: Long = 0,
        var cancelledGeneration: Long = 0,
        var weekPublished: Boolean = false,
        var cancelledPublished: Boolean = false,
    )
}

internal class PlanControllerOwner : ViewModel() {
    private var controller: PlanController? = null
    private var routeVisible = false

    fun get(factory: () -> PlanController) =
        controller ?: factory().also {
            controller = it
            if (routeVisible) it.onRouteEntered()
        }

    /** The top navigation entry owns visibility, independently of outgoing Compose transition content. */
    fun onRouteVisibilityChanged(visible: Boolean) {
        if (routeVisible == visible) return
        routeVisible = visible
        controller?.let { if (visible) it.onRouteEntered() else it.onRouteExited() }
    }

    override fun onCleared() {
        controller?.close()
        controller = null
    }
}
