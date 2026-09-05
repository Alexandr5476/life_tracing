package com.alexandr5476.lifetracing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.alexandr5476.lifetracing.daily.CoroutineLocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.daily.DailyController
import com.alexandr5476.lifetracing.daily.DailyRuntimeCommand
import com.alexandr5476.lifetracing.data.persistence.DailyReadRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
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
) {
    val dailyController: DailyController
        get() = dailyControllerOwner.get()

    companion object {
        @Volatile
        private var instance: LifeTracingRuntimeGraph? = null

        fun from(context: Context): LifeTracingRuntimeGraph =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

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
