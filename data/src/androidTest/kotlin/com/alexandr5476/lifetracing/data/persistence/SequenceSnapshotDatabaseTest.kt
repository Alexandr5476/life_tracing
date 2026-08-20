package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SequenceSnapshotDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var snapshots: SequenceSnapshotDao

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        snapshots = database.sequenceSnapshotDao()
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity("activity", "Activity", null, "TIMER", 30_000, null, null, null, false, 1),
                ActivitySnapshotSettingsEntity("activity"),
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun aggregateRoundTripsFrozenOverridesAndHardDeletePrunesItsOnlyActivitySnapshot() {
        snapshots.insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity("snapshot", "Sequence", null, null, null, null, 1),
                SequenceSnapshotSettingsEntity("snapshot", true, 1_000, 2_000, true, true, false, true, true, "PAUSE"),
                nodes = listOf(SequenceSnapshotNodeEntity("step", "snapshot", "STEP", null, 0, "activity", null)),
                stepOverrides = listOf(SequenceSnapshotStepOverrideEntity("step", 0, "OVERTIME", false, false, false)),
            ),
        )

        val aggregate = requireNotNull(snapshots.getAggregate("snapshot"))
        assertEquals("PAUSE", aggregate.settings.noLiveTimeAccounting)
        assertEquals(0L, aggregate.stepOverrides.single().startCountdownMs)
        assertEquals(false, aggregate.stepOverrides.single().timerEndSound)

        snapshots.hardDeleteAndPruneOwnedActivitySnapshots("snapshot")

        assertNull(snapshots.getById("snapshot"))
        assertNull(database.activitySnapshotDao().getById("activity"))
    }
}
