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

/** Ephemeral presentation authority; never persisted or used as source identity. */
data class PlanCatalogContext(
    val dialogId: UUID = UUID.randomUUID(),
    val collectionId: UUID = UUID.randomUUID(),
)

data class PlanCatalogState(
    val query: String = "",
    val items: List<ReusablePlanCatalogItem> = emptyList(),
    val hasNextPage: Boolean = false,
    val loading: Boolean = false,
    val failure: PlanMessage? = null,
    val context: PlanCatalogContext = PlanCatalogContext(),
    val retrying: Boolean = false,
)

/** Dialog lifetime and published collection authority are independent of read generations. */
data class PlanCancelledContext(
    val dialogId: UUID = UUID.randomUUID(),
    val collectionId: UUID = UUID.randomUUID(),
)

data class PlanPresentationState(
    val weekStart: LocalDate,
    val selectedDate: LocalDate,
    val week: PlanLoad<WeekPlanRead> = PlanLoad.Loading,
    val catalog: PlanCatalogState? = null,
    val form: PlanScheduleForm? = null,
    val cancelledOpen: Boolean = false,
    val cancelledContext: PlanCancelledContext? = null,
    val cancelledOffset: Int = 0,
    val cancelledAppending: Boolean = false,
    val cancelledRetrying: Boolean = false,
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

    data class DismissCatalog(
        val context: PlanCatalogContext,
    ) : PlanAction

    data class SearchCatalog(
        val query: String,
        val context: PlanCatalogContext,
        val retry: Boolean = false,
    ) : PlanAction

    data class LoadMoreCatalog(
        val context: PlanCatalogContext,
    ) : PlanAction

    data class SelectCatalogItem(
        val item: ReusablePlanCatalogItem,
        val context: PlanCatalogContext,
    ) : PlanAction

    data class Reschedule(
        val row: PlanReadRow,
    ) : PlanAction

    data class ChangeScheduleKind(
        val kind: PlanScheduleKind,
        val formId: UUID,
    ) : PlanAction

    data class ChangeScheduleDate(
        val value: String,
        val formId: UUID,
    ) : PlanAction

    data class ChangeScheduleTime(
        val value: String,
        val formId: UUID,
    ) : PlanAction

    data class SelectScheduleOffset(
        val offset: ZoneOffset,
        val date: String,
        val time: String,
        val zoneId: ZoneId,
        val inputRevision: Long,
        val formId: UUID,
    ) : PlanAction

    data class RevalidateScheduleZone(
        val formId: UUID,
        val inputRevision: Long,
    ) : PlanAction

    data class DismissForm(
        val formId: UUID,
    ) : PlanAction

    data class SubmitForm(
        val formId: UUID,
        val inputRevision: Long,
    ) : PlanAction

    data class Cancel(
        val row: PlanReadRow,
    ) : PlanAction

    data class UpdateFromTemplate(
        val row: PlanReadRow,
    ) : PlanAction

    data object OpenCancelled : PlanAction

    data class DismissCancelled(
        val context: PlanCancelledContext,
    ) : PlanAction

    data class LoadMoreCancelled(
        val context: PlanCancelledContext,
        val offset: Int,
    ) : PlanAction

    data class RetryCancelled(
        val context: PlanCancelledContext,
        val offset: Int,
    ) : PlanAction

    data class Restore(
        val row: PlanReadRow,
        val context: PlanCancelledContext,
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
    private var requestedSemanticGeneration = initialSemanticGeneration
    private val invalidationJob =
        scope.launch {
            var handled = initialSemanticGeneration
            semanticGeneration.collect { generation ->
                synchronized(recoveryLock) {
                    if (generation != handled) {
                        handled = generation
                        checkDraftZone()
                        if (visible && requestedSemanticGeneration != semanticGeneration.value) {
                            loadWeek()
                        }
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
                is PlanAction.DismissCatalog -> if (ownsCatalogDialog(action.context)) dismissCatalog()
                is PlanAction.SearchCatalog ->
                    if (ownsCatalogDialog(action.context) && (!action.retry || ownsCatalog(action.context))) {
                        loadCatalog(action.query, null, false)
                    }
                is PlanAction.LoadMoreCatalog ->
                    if (ownsCatalog(action.context)) {
                        mutableState.value.catalog?.let {
                            if (it.hasNextPage && !it.loading) loadCatalog(it.query, it.items.lastOrNull(), true)
                        }
                    }
                is PlanAction.SelectCatalogItem ->
                    if (ownsCatalog(action.context) &&
                        mutableState.value.catalog
                            ?.items
                            ?.contains(action.item) == true
                    ) {
                        selectCatalogItem(action.item)
                    }
                is PlanAction.Reschedule -> openReschedule(action.row)
                is PlanAction.ChangeScheduleKind ->
                    updateForm(action.formId) { form ->
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
                    updateForm(action.formId) { form ->
                        if (form.date == action.value) form else form.edited().copy(date = action.value)
                    }
                is PlanAction.ChangeScheduleTime ->
                    updateForm(action.formId) { form ->
                        if (form.time == action.value) form else form.edited().copy(time = action.value)
                    }
                is PlanAction.SelectScheduleOffset -> selectOffset(action)
                is PlanAction.RevalidateScheduleZone ->
                    updateForm(action.formId, action.inputRevision) {
                        it.copy(
                            schedulingZoneId = zoneId(),
                            selectedOffset = null,
                            zoneReviewRequired = false,
                            error = null,
                            inputRevision =
                                it.inputRevision + 1,
                        )
                    }
                is PlanAction.DismissForm ->
                    if (ownsForm(action.formId)) mutableState.update { it.copy(form = null) }
                is PlanAction.SubmitForm ->
                    if (ownsForm(action.formId, action.inputRevision)) submitForm()
                is PlanAction.Cancel ->
                    if (ownsActiveRow(action.row)) mutate(PlanMutation.Cancel(action.row.rowIdentity(), now()))
                is PlanAction.UpdateFromTemplate ->
                    if (ownsActiveRow(action.row) &&
                        action.row.sourceState ==
                        com.alexandr5476.lifetracing.domain.PlanSourceState.CHANGED
                    ) {
                        mutate(PlanMutation.Update(action.row.rowIdentity(), now()))
                    }
                PlanAction.OpenCancelled -> openCancelled()
                is PlanAction.DismissCancelled -> if (ownsCancelledDialog(action.context)) dismissCancelled()
                is PlanAction.LoadMoreCancelled ->
                    if (canAppendCancelled(action)) loadCancelled(true)
                is PlanAction.RetryCancelled ->
                    if (canRetryCancelled(action)) {
                        if (recovery != null) retryRecovery() else loadCancelled(mutableState.value.cancelledAppending)
                    }
                is PlanAction.Restore ->
                    if (ownsCancelled(action.context) &&
                        mutableState.value.cancelledItems.any { it === action.row } &&
                        action.row.plan.status == PlanEntryStatus.CANCELLED
                    ) {
                        mutate(PlanMutation.Restore(action.row.rowIdentity(), now()))
                    }
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
                    cancelledContext = null,
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
            // Re-entry or an explicit refresh may already consume a queued invalidation.
            requestedSemanticGeneration = semantic
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

    // Search and Close belong to the surviving dialog, rather than a removed result collection.
    // Result, pagination and Retry callbacks additionally require their original collection.
    private fun ownsCatalogDialog(context: PlanCatalogContext): Boolean =
        !closed &&
            visible &&
            mutableState.value.catalog
                ?.context
                ?.dialogId == context.dialogId

    private fun ownsCatalog(context: PlanCatalogContext): Boolean =
        ownsCatalogDialog(context) && mutableState.value.catalog?.context == context

    private fun dismissCatalog() {
        catalogGeneration.incrementAndGet()
        mutableState.update { it.copy(catalog = null) }
    }

    private fun openCatalog() {
        val unavailable = closed || !visible || mutationInFlight.get()
        if (unavailable || mutableState.value.cancelledOpen) return
        mutableState.update { it.copy(catalog = PlanCatalogState(), form = null) }
        loadCatalog("", null, false)
    }

    private fun loadCatalog(
        query: String,
        after: ReusablePlanCatalogItem?,
        append: Boolean,
    ): Unit =
        synchronized(recoveryLock) {
            val current = mutableState.value.catalog ?: return
            val generation = catalogGeneration.incrementAndGet()
            if (!publishIfOwned(catalogGeneration, generation) {
                    it.copy(
                        catalog =
                            current.copy(
                                query = query,
                                loading = true,
                                items = if (append) current.items else emptyList(),
                                hasNextPage = append && current.hasNextPage,
                                context =
                                    if (append) {
                                        current.context
                                    } else {
                                        current.context.copy(
                                            collectionId = UUID.randomUUID(),
                                        )
                                    },
                                retrying = current.failure != null && query == current.query,
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
                                catalog.copy(
                                    items = (prior + page.take(CATALOG_PAGE_SIZE)).distinctBy { it.id },
                                    hasNextPage = page.size > CATALOG_PAGE_SIZE,
                                    loading = false,
                                    failure = null,
                                    retrying = false,
                                ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publishIfOwned(catalogGeneration, generation) { state ->
                        val catalog = state.catalog?.takeIf { it.query == query } ?: return@publishIfOwned null
                        state.copy(
                            catalog =
                                catalog.copy(
                                    loading = false,
                                    failure = PlanMessage.LOAD_FAILED,
                                    retrying = false,
                                ),
                        )
                    }
                }
            }
        }

    private fun selectCatalogItem(item: ReusablePlanCatalogItem) {
        if (closed || !visible || mutationInFlight.get()) return
        catalogGeneration.incrementAndGet()
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
        if (closed || !visible || mutableState.value.cancelledOpen) return
        if (mutableState.value.isMutating || !row.isMutable()) return
        dismissCatalog()
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
                        existingTitle = row.title,
                        existingKind = row.plan.kind,
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

    private fun ownsForm(
        formId: UUID,
        inputRevision: Long? = null,
    ): Boolean {
        val form = mutableState.value.form
        return !closed &&
            visible &&
            form?.formId == formId &&
            (inputRevision == null || form.inputRevision == inputRevision)
    }

    private fun updateForm(
        formId: UUID,
        inputRevision: Long? = null,
        transform: (PlanScheduleForm) -> PlanScheduleForm,
    ) {
        if (!ownsForm(formId, inputRevision) || mutationInFlight.get()) return
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
        if (!ownsForm(action.formId) || mutationInFlight.get()) return
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
            val pending = recovery
            // Invalidate both surfaces before either reader can publish synchronously.
            pending?.let {
                it.weekPublished = false
                if (it.needsCancelled) it.cancelledPublished = false
            }
            mutableState.update { it.copy(recoveryFailure = null) }
            loadWeek()
            if (pending?.needsCancelled == true) loadCancelled(false)
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

    private fun canRetryCancelled(action: PlanAction.RetryCancelled): Boolean {
        val state = mutableState.value
        val failed = state.cancelled is PlanLoad.Failure || state.recoveryFailure != null
        return failed && ownsCancelled(action.context) && action.offset == state.cancelledOffset
    }

    private fun canAppendCancelled(action: PlanAction.LoadMoreCancelled): Boolean {
        val state = mutableState.value
        val ready = state.cancelled is PlanLoad.Content && state.cancelledHasNextPage && !mutationInFlight.get()
        return ready && ownsCancelled(action.context) && action.offset == state.cancelledOffset
    }

    private fun ownsCalendar(): Boolean =
        !closed && visible && mutableState.value.let { it.catalog == null && it.form == null && !it.cancelledOpen }

    private fun ownsActiveRow(row: PlanReadRow): Boolean {
        val read = (mutableState.value.week as? PlanLoad.Content)?.value ?: return false
        return ownsCalendar() &&
            row.isMutable() &&
            (read.selectedDayPlans.any { it === row } || read.weekPlans.any { it === row })
    }

    private fun PlanReadRow.rowIdentity() = plan.actionIdentity()

    private fun ownsCancelledDialog(context: PlanCancelledContext): Boolean =
        !closed &&
            visible &&
            mutableState.value.let {
                it.cancelledOpen &&
                    it.catalog == null &&
                    it.form == null &&
                    it.cancelledContext?.dialogId == context.dialogId
            }

    private fun ownsCancelled(context: PlanCancelledContext): Boolean =
        ownsCancelledDialog(context) && mutableState.value.cancelledContext == context

    private fun openCancelled() {
        if (!ownsCalendar()) return
        mutableState.update { it.copy(cancelledOpen = true, cancelledContext = PlanCancelledContext()) }
        // Recovery remains internal and is never authorized by a dialog context.
        if (recovery != null) retryRecovery() else loadCancelled(false)
    }

    private fun dismissCancelled() {
        cancelledGeneration.incrementAndGet()
        mutableState.update {
            it.copy(
                cancelledOpen = false,
                cancelledContext = null,
                cancelled = null,
                cancelledItems = emptyList(),
                cancelledHasNextPage = false,
            )
        }
        if (recovery?.needsCancelled == true) loadCancelled(false)
    }

    private fun loadCancelled(append: Boolean): Unit =
        synchronized(recoveryLock) {
            val generation = cancelledGeneration.incrementAndGet()
            if (!append) {
                recovery?.let {
                    it.cancelledGeneration = generation
                    it.cancelledPublished = false
                }
            }
            val offset = if (append) mutableState.value.cancelledOffset else 0
            if (!publishIfOwned(cancelledGeneration, generation) {
                    it.copy(
                        cancelled = PlanLoad.Loading,
                        cancelledItems = if (append) it.cancelledItems else emptyList(),
                        cancelledHasNextPage = append && it.cancelledHasNextPage,
                        cancelledOffset = offset,
                        cancelledAppending = append,
                        cancelledRetrying = it.cancelled is PlanLoad.Failure,
                        cancelledContext =
                            if (append) {
                                it.cancelledContext
                            } else {
                                it.cancelledContext?.copy(collectionId = UUID.randomUUID())
                            },
                    )
                }
            ) {
                return
            }
            scope.launch {
                try {
                    val page = readCancelled(CancelledPlanPageQuery(offset, CANCELLED_PAGE_SIZE))
                    if (publishIfOwned(cancelledGeneration, generation) { state ->
                            val items = if (append) state.cancelledItems + page.items else page.items
                            state.copy(
                                cancelled = PlanLoad.Content(page),
                                cancelledItems = items.distinctBy { it.plan.id },
                                cancelledHasNextPage = page.hasNextPage,
                                cancelledOffset = offset + page.items.size,
                                cancelledRetrying = false,
                            )
                        }
                    ) {
                        if (!append) canonicalPublished(CanonicalSurface.CANCELLED, generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (publishIfOwned(cancelledGeneration, generation) {
                            it.copy(cancelled = PlanLoad.Failure(PlanMessage.LOAD_FAILED), cancelledRetrying = false)
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
