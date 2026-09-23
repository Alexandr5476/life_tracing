@file:Suppress("LongParameterList", "TooManyFunctions")

package com.alexandr5476.lifetracing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.alexandr5476.lifetracing.daily.CoroutineLocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.daily.DailyController
import com.alexandr5476.lifetracing.daily.DailyRuntimeCommand
import com.alexandr5476.lifetracing.data.persistence.ActivityCommandRepository
import com.alexandr5476.lifetracing.data.persistence.DailyReadRepository
import com.alexandr5476.lifetracing.data.persistence.HistoryReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.SequenceHistoryCommandRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorController
import com.alexandr5476.lifetracing.editor.ActivityTemplateEditorTarget
import com.alexandr5476.lifetracing.editor.SequenceEditorActivityChoice
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorController
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorTarget
import com.alexandr5476.lifetracing.history.ActivityHistoryMutationController
import com.alexandr5476.lifetracing.history.HistoryController
import com.alexandr5476.lifetracing.history.ManualActivityEntryController
import com.alexandr5476.lifetracing.history.SequenceHistoryMutationController
import com.alexandr5476.lifetracing.launcher.CoroutinePreflightScheduler
import com.alexandr5476.lifetracing.launcher.LauncherCommit
import com.alexandr5476.lifetracing.launcher.LauncherDurableCommand
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.launcher.toEntryValue
import com.alexandr5476.lifetracing.library.LibraryController
import com.alexandr5476.lifetracing.library.LibraryMutation
import com.alexandr5476.lifetracing.library.LibraryOrganization
import com.alexandr5476.lifetracing.live.ExpandedLiveSequenceController
import com.alexandr5476.lifetracing.live.ExpandedSequenceCommand
import com.alexandr5476.lifetracing.plan.PlanController
import com.alexandr5476.lifetracing.plan.PlanExecutionCommit
import com.alexandr5476.lifetracing.plan.PlanExecutionController
import com.alexandr5476.lifetracing.plan.PlanExecutionDurableCommand
import com.alexandr5476.lifetracing.plan.PlanMutation
import com.alexandr5476.lifetracing.runtime.AndroidMonotonicClock
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeCoordinator
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeDeadlineScheduler
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeFeedbackDispatcher
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeNotificationPublisher
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeVibrator
import com.alexandr5476.lifetracing.runtime.AndroidWallClock
import com.alexandr5476.lifetracing.runtime.CoroutineInProcessRuntimeDeadlineDriver
import com.alexandr5476.lifetracing.runtime.NoOpRuntimeSoundPlayer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

class LifeTracingApplication : Application() {
    private val runtimeGraph by lazy { LifeTracingRuntimeGraph.from(this) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    runtimeGraph.scope.launch { runtimeGraph.coordinator.onForeground() }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    state: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    state: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}

class LifeTracingRuntimeGraph internal constructor(
    val scope: kotlinx.coroutines.CoroutineScope,
    val coordinator: AndroidRuntimeCoordinator,
    private val dailyControllerOwner: DailyControllerOwner,
    private val startActivityControllerFactory: (onPinnedOrderCommitted: () -> Unit) -> StartActivityController,
    private val libraryControllerFactory: () -> LibraryController,
    private val activityTemplateEditorControllerFactory: (
        ActivityTemplateEditorTarget,
    ) -> ActivityTemplateEditorController,
    private val sequenceTemplateEditorControllerFactory: (
        SequenceTemplateEditorTarget,
    ) -> SequenceTemplateEditorController = {
        error("Sequence editor is unavailable")
    },
    private val expandedLiveSequenceControllerFactory: (
        com.alexandr5476.lifetracing.domain.SequenceExecutionId,
    ) -> ExpandedLiveSequenceController = {
        error("Expanded live Sequence is unavailable")
    },
    private val planExecutionControllerFactory: (PlanActionIdentity) -> PlanExecutionController = {
        error("Plan execution is unavailable")
    },
    private val planControllerFactory: () -> PlanController = { error("Plan is unavailable") },
    private val historyControllerFactory: () -> HistoryController = { error("History is unavailable") },
    private val manualActivityEntryControllerFactory: () -> ManualActivityEntryController = {
        error("Manual History entry is unavailable")
    },
    private val activityHistoryDetailControllerFactory: (
        com.alexandr5476.lifetracing.domain.ActivityExecutionId,
    ) -> ActivityHistoryMutationController = {
        error("Activity History is unavailable")
    },
    private val sequenceHistoryDetailControllerFactory: (
        com.alexandr5476.lifetracing.domain.SequenceExecutionId,
    ) -> SequenceHistoryMutationController = {
        error("Sequence History is unavailable")
    },
) {
    val dailyController: DailyController
        get() = dailyControllerOwner.get()

    fun createStartActivityController(onPinnedOrderCommitted: () -> Unit = {}): StartActivityController =
        startActivityControllerFactory(onPinnedOrderCommitted)

    fun createLibraryController(): LibraryController = libraryControllerFactory()

    fun createActivityTemplateEditorController(
        target: ActivityTemplateEditorTarget,
    ): ActivityTemplateEditorController = activityTemplateEditorControllerFactory(target)

    fun createSequenceTemplateEditorController(
        target: SequenceTemplateEditorTarget,
    ): SequenceTemplateEditorController = sequenceTemplateEditorControllerFactory(target)

    internal fun createExpandedLiveSequenceController(
        executionId: com.alexandr5476.lifetracing.domain.SequenceExecutionId,
    ): ExpandedLiveSequenceController = expandedLiveSequenceControllerFactory(executionId)

    fun createPlanExecutionController(expectedIdentity: PlanActionIdentity): PlanExecutionController =
        planExecutionControllerFactory(expectedIdentity)

    fun createPlanController(): PlanController = planControllerFactory()

    fun createHistoryController(): HistoryController = historyControllerFactory()

    fun createManualActivityEntryController(): ManualActivityEntryController = manualActivityEntryControllerFactory()

    fun createActivityHistoryDetailController(
        executionId: com.alexandr5476.lifetracing.domain.ActivityExecutionId,
    ): ActivityHistoryMutationController = activityHistoryDetailControllerFactory(executionId)

    fun createSequenceHistoryDetailController(
        executionId: com.alexandr5476.lifetracing.domain.SequenceExecutionId,
    ): SequenceHistoryMutationController = sequenceHistoryDetailControllerFactory(executionId)

    companion object {
        @Volatile
        private var instance: LifeTracingRuntimeGraph? = null

        fun from(context: Context): LifeTracingRuntimeGraph =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        @Suppress("CyclomaticComplexMethod", "LongMethod") // Runtime graph wiring stays at one composition root.
        private fun create(context: Context): LifeTracingRuntimeGraph {
            val exceptionHandler =
                kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                    Log.e("LifeTracingRuntime", "runtime_recovery_failed", error)
                }
            val scope =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() +
                        kotlinx.coroutines.Dispatchers.IO +
                        exceptionHandler,
                )
            val uiScope =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() +
                        kotlinx.coroutines.Dispatchers.Main.immediate +
                        exceptionHandler,
                )
            val wallClock = AndroidWallClock()
            val repository = LiveSessionRepository.create(context)
            val libraryRepository = LibraryRepository.create(context)
            val templateAuthoringRepository = TemplateAuthoringRepository.create(context)
            val activityCommandRepository = ActivityCommandRepository.create(context)
            val planReadRepository = PlanReadRepository.create(context)
            val planRepository = PlanRepository.create(context)
            val historyReadRepository = HistoryReadRepository.create(context)
            val sequenceHistoryCommandRepository = SequenceHistoryCommandRepository.create(context)
            val coordinator =
                AndroidRuntimeCoordinator(
                    repository,
                    wallClock,
                    AndroidMonotonicClock,
                    AndroidRuntimeDeadlineScheduler(context),
                    CoroutineInProcessRuntimeDeadlineDriver(scope, AndroidMonotonicClock),
                    AndroidRuntimeFeedbackDispatcher(NoOpRuntimeSoundPlayer, AndroidRuntimeVibrator(context)),
                    AndroidRuntimeNotificationPublisher(context),
                )
            return LifeTracingRuntimeGraph(
                scope,
                coordinator,
                DailyControllerOwner {
                    val dailyReadRepository = DailyReadRepository.create(context)
                    DailyController(
                        uiScope,
                        { query ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                dailyReadRepository.getDaily(query)
                            }
                        },
                        { command ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                when (command) {
                                    is DailyRuntimeCommand.PauseActivity ->
                                        repository.pauseActiveActivity(command.pauseId, command.at)
                                    is DailyRuntimeCommand.ResumeActivity ->
                                        repository.resumeActiveActivity(command.at)
                                    is DailyRuntimeCommand.FinishActivity ->
                                        repository.completeActiveActivity(command.at)
                                    is DailyRuntimeCommand.PauseSequence ->
                                        repository.pauseActiveSequence(command.at)
                                    is DailyRuntimeCommand.ResumeSequence ->
                                        repository.resumeActiveSequence(command.at)
                                    is DailyRuntimeCommand.StartNextSequenceStep ->
                                        repository.startNextSequenceStep(command.at)
                                    is DailyRuntimeCommand.CompleteCurrentSequenceStep ->
                                        repository.completeCurrentSequenceStep(command.occurrenceId, command.at)
                                }
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                coordinator.onRuntimeStateChanged()
                            }
                        },
                        coordinator.semanticGeneration,
                        { coordinator.displayBaseline },
                        wallClock,
                        ZoneId::systemDefault,
                        { ActivityExecutionPauseId(UUID.randomUUID().toString()) },
                        CoroutineLocalDateBoundaryScheduler(uiScope),
                        mutationGate = coordinator.mutationGate,
                    )
                },
                { onPinnedOrderCommitted ->
                    StartActivityController(
                        uiScope,
                        { limit ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getRecent(limit)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getPinned()
                            }
                        },
                        { query ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.search(query)
                            }
                        },
                        { folderId ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                folderId?.let(libraryRepository::getFolderContents)
                                    ?: libraryRepository.getRoot().contents
                            }
                        },
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getLaunchTarget(id)
                            }
                        },
                        { ids ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.reorderPinned(ids)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repository.getActiveSession() != null
                            }
                        },
                        { command ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                executeLauncherCommand(command, activityCommandRepository, libraryRepository)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                coordinator.onRuntimeStateChanged()
                            }
                        },
                        wallClock,
                        ZoneId::systemDefault,
                        CoroutinePreflightScheduler(uiScope),
                        initialLiveConflict = { target ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.hasLiveLaunchConflict(target.id, target.revision)
                            }
                        },
                        onPinnedOrderCommitted = onPinnedOrderCommitted,
                        mutationGate = coordinator.mutationGate,
                    )
                },
                {
                    LibraryController(
                        uiScope,
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getRoot()
                            }
                        },
                        { folderId ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getFolderContents(folderId)
                            }
                        },
                        { folderId ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getFolderPath(folderId)
                            }
                        },
                        { query, filter ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.search(query, filter)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                LibraryOrganization(libraryRepository.getFolders(), libraryRepository.getTags())
                            }
                        },
                        { mutation ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                executeLibraryMutation(mutation, libraryRepository)
                            }
                        },
                        readFolderDeletionIsEmpty = { folderId ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.isFolderEmptyForDeletion(folderId)
                            }
                        },
                    )
                },
                { target ->
                    ActivityTemplateEditorController(
                        uiScope,
                        target,
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.getActivityTemplate(id)
                            }
                        },
                        { draft, placement, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.createActivityTemplate(draft, placement, at)
                            }
                        },
                        { id, expectedRevision, draft, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.saveActivityTemplate(id, expectedRevision, draft, at)
                            }
                        },
                        java.time.Instant::now,
                    )
                },
                { target ->
                    SequenceTemplateEditorController(
                        uiScope,
                        target,
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.getSequenceTemplateAuthoringState(id)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository
                                    .getReusableActivityCatalog(ACTIVITY_PICKER_PAGE_SIZE)
                                    .map { activity ->
                                        SequenceEditorActivityChoice(
                                            activity.id,
                                            activity.name,
                                            activity.timeTrackingMode,
                                            activity.timerTarget,
                                            activity.mainValueName,
                                            activity.mainValueUnit,
                                            activity.mainValueDisplayPrecision,
                                            activity.mainValueDefaultNumberScaled,
                                        )
                                    }
                            }
                        },
                        { draft, placement, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.createSequenceTemplate(draft, placement, at)
                            }
                        },
                        { id, revision, draft, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.saveSequenceTemplate(id, revision, draft, at)
                            }
                        },
                        java.time.Instant::now,
                        { ids ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.getActivityTemplateSourceStatuses(ids)
                            }
                        },
                        { sequenceId, stepId, revision, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.updateStepFromSourceTemplate(
                                    sequenceId,
                                    stepId,
                                    revision,
                                    at,
                                )
                            }
                        },
                        { sequenceId, stepId, sequenceRevision, sourceRevision, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.updateSourceTemplateFromStep(
                                    sequenceId,
                                    stepId,
                                    sequenceRevision,
                                    sourceRevision,
                                    at,
                                )
                            }
                        },
                        { sequenceId, stepId, revision, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.saveStepAsNewActivityTemplate(
                                    sequenceId,
                                    stepId,
                                    revision,
                                    savedAt = at,
                                )
                            }
                        },
                        loadMoreActivities = { after ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository
                                    .getReusableActivityCatalog(ACTIVITY_PICKER_PAGE_SIZE, after.toCatalogItem())
                                    .map { activity ->
                                        SequenceEditorActivityChoice(
                                            activity.id,
                                            activity.name,
                                            activity.timeTrackingMode,
                                            activity.timerTarget,
                                            activity.mainValueName,
                                            activity.mainValueUnit,
                                            activity.mainValueDisplayPrecision,
                                            activity.mainValueDefaultNumberScaled,
                                        )
                                    }
                            }
                        },
                    )
                },
                { executionId ->
                    ExpandedLiveSequenceController(
                        uiScope,
                        executionId,
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repository.getExpandedSequence(id)
                            }
                        },
                        { command ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                executeExpandedSequenceCommand(command, repository)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                coordinator.onRuntimeStateChanged()
                            }
                        },
                        coordinator.semanticGeneration,
                        { coordinator.displayBaseline },
                        { after ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getReusableActivityCatalog(ACTIVITY_PICKER_PAGE_SIZE, after)
                            }
                        },
                        wallClock,
                        mutationGate = coordinator.mutationGate,
                    )
                },
                { expectedIdentity ->
                    PlanExecutionController(
                        uiScope,
                        expectedIdentity,
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                planReadRepository.getFocusedAction(id)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repository.getActiveSession() != null
                            }
                        },
                        { command ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                executePlanCommand(command, repository)
                            }
                        },
                        {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                coordinator.onRuntimeStateChanged()
                            }
                        },
                        wallClock,
                        ZoneId::systemDefault,
                        CoroutinePreflightScheduler(uiScope),
                        mutationGate = coordinator.mutationGate,
                    )
                },
                {
                    PlanController(
                        uiScope,
                        { query ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) { planReadRepository.getWeek(query) }
                        },
                        {
                            query,
                            limit,
                            after,
                            ->
                            withContext(
                                kotlinx.coroutines.Dispatchers.IO,
                            ) { libraryRepository.getReusablePlanCatalog(query, limit, after) }
                        },
                        { query ->
                            withContext(
                                kotlinx.coroutines.Dispatchers.IO,
                            ) { planReadRepository.getCancelledPage(query) }
                        },
                        { command ->
                            withContext(
                                kotlinx.coroutines.Dispatchers.IO,
                            ) { executePlanMutation(command, planRepository) }
                        },
                        java.time.Instant::now,
                        ZoneId::systemDefault,
                        coordinator.semanticGeneration,
                    )
                },
                {
                    HistoryController(
                        uiScope,
                        { query ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                historyReadRepository.getCompletedRoots(query)
                            }
                        },
                        java.time.Instant::now,
                        ZoneId::systemDefault,
                        { timeContext ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                historyReadRepository.getLatestCompletedPrimaryLocalDate(
                                    timeContext.now,
                                    timeContext.zoneId,
                                )
                            }
                        },
                        coordinator.semanticGeneration,
                    )
                },
                {
                    ManualActivityEntryController(
                        uiScope,
                        { after ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                libraryRepository.getReusableActivityCatalog(
                                    com.alexandr5476.lifetracing.history.MANUAL_ACTIVITY_CATALOG_PAGE_SIZE,
                                    after,
                                )
                            }
                        },
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                templateAuthoringRepository.getActivityTemplate(id)
                            }
                        },
                        { startedAt, completedAt ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                activityCommandRepository.overlapsCompletedHistory(startedAt, completedAt)
                            }
                        },
                        { proposal ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                activityCommandRepository.addManualTimed(
                                    proposal.source,
                                    requireNotNull(proposal.startedAt),
                                    proposal.completedAt,
                                    proposal.commandAt,
                                    proposal.zoneId,
                                    proposal.values,
                                    proposal.expectedTemplateRevision,
                                )
                            }
                        },
                        { proposal ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                activityCommandRepository.addManualNoLive(
                                    proposal.source,
                                    proposal.completedAt,
                                    proposal.commandAt,
                                    proposal.zoneId,
                                    proposal.values,
                                    proposal.expectedTemplateRevision,
                                )
                            }
                        },
                        java.time.Instant::now,
                        ZoneId::systemDefault,
                    )
                },
                { executionId ->
                    ActivityHistoryMutationController(
                        uiScope,
                        executionId,
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                historyReadRepository.getActivityDetail(id)
                            }
                        },
                        { id, correction, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                activityCommandRepository.correctHistory(id, correction, at)
                            }
                        },
                        { id, expectedUpdatedAt, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                activityCommandRepository.softDeleteHistory(id, expectedUpdatedAt, at)
                            }
                        },
                        java.time.Instant::now,
                        { startedAt, completedAt, excludingExecutionId ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                activityCommandRepository.overlapsCompletedHistory(
                                    startedAt,
                                    completedAt,
                                    excludingExecutionId,
                                )
                            }
                        },
                    )
                },
                { executionId ->
                    SequenceHistoryMutationController(
                        uiScope,
                        executionId,
                        { id ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                historyReadRepository.getSequenceDetail(id)
                            }
                        },
                        { id, correction, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                sequenceHistoryCommandRepository.correctTiming(id, correction, at)
                            }
                        },
                        { id, command, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                sequenceHistoryCommandRepository.deleteChildHistory(id, command, at)
                            }
                        },
                        { id, command, at ->
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                sequenceHistoryCommandRepository.removeOccurrenceHistory(id, command, at)
                            }
                        },
                        java.time.Instant::now,
                    )
                },
            )
        }
    }
}

private fun SequenceEditorActivityChoice.toCatalogItem() =
    com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem(
        id,
        name,
        timeTrackingMode,
        timerTarget,
        mainValueName,
        mainValueUnit,
        mainValueDisplayPrecision,
        mainValueDefaultNumberScaled,
    )

private const val ACTIVITY_PICKER_PAGE_SIZE = 50

internal fun executePlanMutation(
    command: PlanMutation,
    repository: PlanRepository,
) {
    when (command) {
        is PlanMutation.CreateActivity ->
            repository.createActivityPlanFromTemplate(
                command.id,
                command.schedule,
                command.at,
            )
        is PlanMutation.CreateSequence ->
            repository.createSequencePlanFromTemplate(
                command.id,
                command.schedule,
                command.at,
            )
        is PlanMutation.Reschedule -> repository.reschedulePlanEntry(command.identity, command.schedule, command.at)
        is PlanMutation.Cancel -> repository.cancelPlan(command.identity, command.at)
        is PlanMutation.Restore -> repository.restoreCancelledPlan(command.identity, command.at)
        is PlanMutation.Update -> repository.updatePlanFromTemplate(command.identity, command.at)
    }
}

internal fun executePlanCommand(
    command: PlanExecutionDurableCommand,
    repository: LiveSessionRepository,
): PlanExecutionCommit =
    when (command.identity.kind) {
        com.alexandr5476.lifetracing.domain.PlanTrackableKind.ACTIVITY -> {
            val execution =
                if (command.noLive) {
                    repository.completeNoLiveActivityFromPlan(
                        command.identity,
                        command.at,
                        command.zoneId,
                        valueOverrides = command.values,
                    )
                } else {
                    repository.startActivityFromPlan(
                        command.identity,
                        command.at,
                        command.at,
                        command.zoneId,
                        command.values,
                    )
                }
            PlanExecutionCommit.Activity(execution.id, !command.noLive)
        }
        com.alexandr5476.lifetracing.domain.PlanTrackableKind.SEQUENCE -> {
            require(!command.noLive && command.values.isEmpty()) { "Sequence start does not accept Activity values" }
            val state = repository.startSequenceFromPlan(command.identity, command.at, command.at, command.zoneId)
            PlanExecutionCommit.Sequence(state.execution.id)
        }
    }

internal fun executeExpandedSequenceCommand(
    command: ExpandedSequenceCommand,
    repository: LiveSessionRepository,
) {
    when (command) {
        is ExpandedSequenceCommand.Pause -> repository.pauseActiveSequence(command.executionId, command.at)
        is ExpandedSequenceCommand.Resume -> repository.resumeActiveSequence(command.executionId, command.at)
        is ExpandedSequenceCommand.StartNext -> repository.startNextSequenceStep(command.executionId, command.at)
        is ExpandedSequenceCommand.Complete ->
            repository.completeCurrentSequenceStep(
                command.executionId,
                command.occurrenceId,
                command.values,
                command.at,
            )
        is ExpandedSequenceCommand.GoNow ->
            repository.goNow(command.executionId, command.occurrenceId, command.at)
        is ExpandedSequenceCommand.MakeNext ->
            repository.makeNext(command.executionId, command.occurrenceId, command.at)
        is ExpandedSequenceCommand.DoAgain ->
            repository.doAgain(command.executionId, command.occurrenceId, command.placement, command.at)
        is ExpandedSequenceCommand.RuntimeAdd ->
            repository.runtimeAdd(command.executionId, command.source, command.placement, command.at)
        is ExpandedSequenceCommand.SaveValues ->
            repository.updateCurrentSequenceStepValues(
                command.executionId,
                command.occurrenceId,
                command.values,
                command.at,
            )
        is ExpandedSequenceCommand.EndEarly -> repository.endSequenceEarly(command.executionId, command.at)
    }
}

internal fun executeLauncherCommand(
    command: LauncherDurableCommand,
    activityCommandRepository: ActivityCommandRepository,
    libraryRepository: LibraryRepository,
): LauncherCommit =
    when (command) {
        is LauncherDurableCommand.StartActivity -> {
            val execution =
                activityCommandRepository.startLive(
                    ActivityEntrySource.Template(command.templateId),
                    command.at,
                    command.at,
                    command.zoneId,
                    expectedTemplateRevision = command.expectedRevision,
                )
            LauncherCommit.Activity(execution.id, true)
        }
        is LauncherDurableCommand.CompleteNoLive -> {
            val overrides =
                command.override
                    ?.let { override ->
                        listOf(
                            ActivityEntryValueOverride(
                                ActivityEntryFieldReference.Template(override.fieldId),
                                override.toEntryValue(),
                            ),
                        )
                    }.orEmpty()
            val execution =
                libraryRepository.completeNoLiveActivityFromTemplate(
                    command.templateId,
                    command.at,
                    command.at,
                    command.zoneId,
                    overrides,
                    command.expectedRevision,
                )
            LauncherCommit.Activity(execution.id, false)
        }
        is LauncherDurableCommand.StartSequence -> {
            val state =
                libraryRepository.startSequenceFromTemplate(
                    command.templateId,
                    command.at,
                    command.at,
                    command.zoneId,
                    command.expectedRevision,
                )
            LauncherCommit.Sequence(state.execution.id)
        }
    }

@Suppress("CyclomaticComplexMethod") // Exhaustive routing stays at the composition boundary.
internal fun executeLibraryMutation(
    mutation: LibraryMutation,
    libraryRepository: LibraryRepository,
) {
    when (mutation) {
        is LibraryMutation.CreateFolder ->
            libraryRepository.createFolder(mutation.id, mutation.name, mutation.parentId, mutation.at)
        is LibraryMutation.RenameFolder ->
            libraryRepository.renameFolder(mutation.id, mutation.name, mutation.at)
        is LibraryMutation.MoveFolder ->
            libraryRepository.moveFolder(mutation.id, mutation.parentId, mutation.at)
        is LibraryMutation.MoveTemplate ->
            libraryRepository.moveTemplatesToFolder(listOf(mutation.id), mutation.folderId, mutation.at)
        is LibraryMutation.CreateAndAssignTag ->
            libraryRepository.createTagAndAssign(mutation.id, mutation.name, mutation.templateId, mutation.at)
        is LibraryMutation.AssignTag -> libraryRepository.addTag(mutation.templateId, mutation.tagId)
        is LibraryMutation.UnassignTag -> libraryRepository.removeTag(mutation.templateId, mutation.tagId)
        is LibraryMutation.SetPinned ->
            if (mutation.pinned) {
                libraryRepository.pin(mutation.templateId)
            } else {
                libraryRepository.unpin(mutation.templateId)
            }
        is LibraryMutation.ReorderPinned -> libraryRepository.reorderPinned(mutation.ids)
        is LibraryMutation.ArchiveTemplate ->
            when (val id = mutation.id) {
                is com.alexandr5476.lifetracing.domain.LibraryTemplateId.Activity ->
                    libraryRepository.archiveActivityTemplate(id.id, mutation.at)
                is com.alexandr5476.lifetracing.domain.LibraryTemplateId.Sequence ->
                    libraryRepository.archiveSequenceTemplate(id.id, mutation.at)
            }
        is LibraryMutation.DeleteEmptyFolder ->
            libraryRepository.deleteEmptyFolder(mutation.id, mutation.at)
        is LibraryMutation.DeleteFolderMovingContents ->
            libraryRepository.deleteFolderMovingContents(mutation.id, mutation.destinationId, mutation.at)
        is LibraryMutation.DeleteFolderAndArchiveContents ->
            libraryRepository.deleteFolderAndArchiveContents(mutation.id, mutation.at)
    }
}

internal class DailyControllerOwner(
    factory: () -> DailyController,
) {
    private val controller = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)

    fun get(): DailyController = controller.value
}
