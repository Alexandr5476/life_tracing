package com.alexandr5476.lifetracing.runtime

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.runner.AndroidJUnitRunner
import com.alexandr5476.lifetracing.LifeTracingApplication
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.RuntimeGraphOwner
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Only instrumentation substitutes the Application; installed debug/release startup is unchanged. */
class RuntimeIsolationTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(cl, RuntimeIsolationApplication::class.java.name, context)
}

/** Real manifest acquisition and production repositories share one disposable physical database. */
class RuntimeIsolationApplication : LifeTracingApplication() {
    @Volatile
    private var environment: ManifestRuntimeEnvironment? = null

    internal override val runtimeGraphOwner: RuntimeGraphOwner
        get() = environment?.owner ?: super.runtimeGraphOwner

    override fun getDatabasePath(name: String): File = super.getDatabasePath(scopedName(name))

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = super.openOrCreateDatabase(scopedName(name), mode, factory)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase = super.openOrCreateDatabase(scopedName(name), mode, factory, errorHandler)

    internal suspend fun <T> withDisposableRuntime(block: suspend (ManifestRuntimeEnvironment) -> T): T {
        val previous = environment
        val owned = ManifestRuntimeEnvironment(this)
        environment = owned
        try {
            return block(owned)
        } finally {
            withContext(NonCancellable) {
                try {
                    owned.close()
                } finally {
                    environment = previous
                    if (getDatabasePath(owned.name).exists()) {
                        check(deleteDatabase(owned.name)) { "Could not remove fixture database ${owned.name}" }
                    }
                    check(!getDatabasePath(owned.name).exists())
                    check(!getDatabasePath(owned.name + "-wal").exists())
                    check(!getDatabasePath(owned.name + "-shm").exists())
                }
            }
        }
    }

    private fun scopedName(name: String): String = if (name == "lifetracing.db") environment?.name ?: name else name
}

/** Cold owner uses the production factory, clocks, adapters and repositories without substitution. */
internal class ManifestRuntimeEnvironment(
    private val application: Context,
) {
    val name = "manifest-runtime-" + UUID.randomUUID()
    var acquisitions = 0
        private set
    private var graph: LifeTracingRuntimeGraph? = null
    private var closeRuntimeDatabase: () -> Unit = {}
    val owner =
        RuntimeGraphOwner { context ->
            acquisitions++
            LifeTracingRuntimeGraph
                .create(context) { repository ->
                    closeRuntimeDatabase = { RuntimeRecoveryStore.closeProductionRuntimeRepository(repository) }
                }.also { graph = it }
        }

    suspend fun close() {
        try {
            graph
                ?.scope
                ?.coroutineContext
                ?.get(Job)
                ?.cancelAndJoin()
        } finally {
            try {
                closeRuntimeDatabase()
            } finally {
                if (graph != null) {
                    AndroidRuntimeDeadlineScheduler(application).cancel()
                    application
                        .getSystemService(NotificationManager::class.java)
                        .cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
                }
            }
        }
    }
}
