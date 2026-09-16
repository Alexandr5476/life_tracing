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
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.StalePlanActionException
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
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

enum class PlanMessage { LOAD_FAILED, INVALID_SCHEDULE, ACTION_UNAVAILABLE }

enum class PlanScheduleKind { FLOATING_DAY, EXACT_DAY, WEEK }

data class PlanScheduleForm(
    val source: ReusablePlanCatalogItem?,
    val identity: PlanActionIdentity?,
    val kind: PlanScheduleKind,
    val date: String,
    val time: String,
)

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

    @Volatile private var visible = true

    @Volatile private var recovery: MutationRecovery? = null

    private val initialSemanticGeneration = semanticGeneration.value
    private val invalidationJob =
        scope.launch {
            var handled = initialSemanticGeneration
            semanticGeneration.collect { generation ->
                if (generation != handled) {
                    handled = generation
                    if (visible) {
                        armBoundary()
                        loadWeek()
                    }
                }
            }
        }

    init {
        loadWeek()
    }

    fun dispatch(action: PlanAction) {
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
                mutableState.update { state ->
                    state.form?.let { form ->
                        state.copy(
                            form =
                                form.copy(
                                    kind = action.kind,
                                    date =
                                        if (action.kind == PlanScheduleKind.WEEK) {
                                            state.weekStart.toString()
                                        } else {
                                            form.date
                                        },
                                ),
                        )
                    }
                        ?: state
                }
            is PlanAction.ChangeScheduleDate ->
                mutableState.update { state ->
                    state.form?.let { state.copy(form = it.copy(date = action.value)) }
                        ?: state
                }
            is PlanAction.ChangeScheduleTime ->
                mutableState.update { state ->
                    state.form?.let { state.copy(form = it.copy(time = action.value)) }
                        ?: state
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

    fun close() {
        closed = true
        visible = false
        invalidationJob.cancel()
        boundaryScheduler.cancel()
        readGeneration.incrementAndGet()
        catalogGeneration.incrementAndGet()
        cancelledGeneration.incrementAndGet()
    }

    fun onRouteEntered() {
        visible = true
        armBoundary()
        loadWeek(clearFailure = true)
    }

    fun onRouteExited() {
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
        if (recovery?.needsCancelled == true) loadCancelled(false)
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
    ) {
        mutableState.update { it.copy(weekStart = weekStart, selectedDate = selectedDate) }
        loadWeek()
    }

    private fun loadWeek(clearFailure: Boolean = false) {
        val generation = readGeneration.incrementAndGet()
        synchronized(recoveryLock) {
            recovery?.let {
                it.weekGeneration = generation
                it.weekPublished = false
            }
        }
        val state = mutableState.value
        if (!publishIfOwned(readGeneration, generation) {
                it.copy(week = PlanLoad.Loading, mutationFailure = if (clearFailure) null else it.mutationFailure)
            }
        ) {
            return
        }
        scope.launch {
            try {
                val read = readWeek(WeekPlanQuery(state.weekStart, state.selectedDate, now()))
                if (publishIfOwned(readGeneration, generation) { it.copy(week = PlanLoad.Content(read)) }) {
                    armBoundary(read)
                    canonicalPublished(CanonicalSurface.WEEK, generation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (publishIfOwned(readGeneration, generation) {
                        it.copy(week = PlanLoad.Failure(PlanMessage.LOAD_FAILED))
                    }
                ) {
                    canonicalFailed(CanonicalSurface.WEEK, generation)
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
    ) {
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
                    ),
            )
        }
    }

    private fun openReschedule(row: PlanReadRow) {
        if (mutableState.value.isMutating || !row.isMutable()) return
        val (kind, date, time) = row.plan.target.toForm(zoneId())
        mutableState.update { it.copy(form = PlanScheduleForm(null, row.plan.actionIdentity(), kind, date, time)) }
    }

    private fun submitForm() {
        val form = mutableState.value.form ?: return
        val schedule =
            runCatching { form.toSchedule(zoneId()) }.getOrElse {
                mutableState.update { it.copy(mutationFailure = PlanMessage.INVALID_SCHEDULE) }
                return
            }
        val at = now()
        val mutation =
            when (val source = form.source?.id) {
                is LibraryTemplateId.Activity -> PlanMutation.CreateActivity(source.id, schedule, at)
                is LibraryTemplateId.Sequence -> PlanMutation.CreateSequence(source.id, schedule, at)
                null -> PlanMutation.Reschedule(requireNotNull(form.identity), schedule, at)
            }
        mutate(mutation)
    }

    private fun mutate(command: PlanMutation) {
        if (closed || !mutationInFlight.compareAndSet(false, true)) return
        mutableState.update { it.copy(isMutating = true, mutationFailure = null) }
        scope.launch {
            var failureMessage: PlanMessage? = null
            try {
                commitMutation(command)
            } catch (cancelled: CancellationException) {
                mutationInFlight.set(false)
                throw cancelled
            } catch (_: StalePlanActionException) {
                failureMessage = PlanMessage.ACTION_UNAVAILABLE
            } catch (_: Exception) {
                failureMessage = PlanMessage.ACTION_UNAVAILABLE
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

    private fun loadCancelled(append: Boolean) {
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
    ): Boolean {
        var published = false
        var eligible = !closed && visible && generation == owner.get()
        while (eligible && !published) {
            val current = mutableState.value
            val replacement = transform(current)
            eligible = replacement != null && !closed && visible && generation == owner.get()
            if (eligible) published = mutableState.compareAndSet(current, requireNotNull(replacement))
        }
        return published
    }

    private fun LocalDate.monday() = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun onTemporalBoundary() {
        if (visible) loadWeek()
    }

    private fun armBoundary(read: WeekPlanRead? = null) {
        if (visible) {
            val current = now()
            val zone = zoneId()
            boundaryScheduler.arm(
                current,
                zone,
                read?.let {
                    nextPlanTemporalBoundary(
                        it.selectedDayPlans.map(PlanReadRow::plan) + it.weekPlans.map(PlanReadRow::plan),
                        current,
                        zone,
                    )
                },
                ::onTemporalBoundary,
            )
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

    private fun PlanScheduleForm.toSchedule(zone: ZoneId): PlanSchedule =
        when (kind) {
            PlanScheduleKind.FLOATING_DAY -> PlanSchedule.FloatingDay(LocalDate.parse(date))
            PlanScheduleKind.EXACT_DAY ->
                PlanSchedule.ExactDay(
                    LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)),
                    zone,
                )
            PlanScheduleKind.WEEK -> PlanSchedule.Week(LocalDate.parse(date))
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

    fun get(factory: () -> PlanController) = controller ?: factory().also { controller = it }

    override fun onCleared() {
        controller?.close()
        controller = null
    }
}
