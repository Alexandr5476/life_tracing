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
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.launcher.CoroutinePreflightScheduler
import com.alexandr5476.lifetracing.launcher.LauncherCommit
import com.alexandr5476.lifetracing.launcher.LauncherDurableCommand
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.launcher.toEntryValue
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
    override fun onCreate() {
        super.onCreate()
        val graph = LifeTracingRuntimeGraph.from(this)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    graph.scope.launch { graph.coordinator.onForeground() }
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
    private val startActivityControllerFactory: () -> StartActivityController,
) {
    val dailyController: DailyController
        get() = dailyControllerOwner.get()

    fun createStartActivityController(): StartActivityController = startActivityControllerFactory()

    companion object {
        @Volatile
        private var instance: LifeTracingRuntimeGraph? = null

        fun from(context: Context): LifeTracingRuntimeGraph =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        @Suppress("LongMethod") // Runtime graph wiring is intentionally kept at one composition root.
        private fun create(context: Context): LifeTracingRuntimeGraph {
            val scope =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() +
                        kotlinx.coroutines.Dispatchers.IO +
                        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                            Log.e("LifeTracingRuntime", "runtime_recovery_failed", error)
                        },
                )
            val wallClock = AndroidWallClock()
            val repository = LiveSessionRepository.create(context)
            val libraryRepository = LibraryRepository.create(context)
            val activityCommandRepository = ActivityCommandRepository.create(context)
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
                        scope,
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
                        coordinator::onRuntimeStateChanged,
                        coordinator.semanticGeneration,
                        { coordinator.displayBaseline },
                        wallClock,
                        ZoneId::systemDefault,
                        { ActivityExecutionPauseId(UUID.randomUUID().toString()) },
                        CoroutineLocalDateBoundaryScheduler(scope),
                    )
                },
                {
                    StartActivityController(
                        scope,
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
                                when (command) {
                                    is LauncherDurableCommand.StartActivity -> {
                                        val execution =
                                            activityCommandRepository.startLive(
                                                ActivityEntrySource.Template(command.templateId),
                                                command.at,
                                                command.at,
                                                command.zoneId,
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
                                            )
                                        LauncherCommit.Sequence(state.execution.id)
                                    }
                                }
                            }
                        },
                        coordinator::onRuntimeStateChanged,
                        wallClock,
                        ZoneId::systemDefault,
                        CoroutinePreflightScheduler(scope),
                    )
                },
            )
        }
    }
}

internal class DailyControllerOwner(
    factory: () -> DailyController,
) {
    private val controller = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)

    fun get(): DailyController = controller.value
}
