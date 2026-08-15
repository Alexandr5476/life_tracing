package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class LifeTracingDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var folders: FolderDao
    private lateinit var tags: TagDao
    private lateinit var series: StatisticsSeriesDao
    private val createdAt = Instant.ofEpochMilli(1_700_000_000_123)
    private val updatedAt = Instant.ofEpochMilli(1_700_000_010_456)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, LifeTracingDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        folders = database.folderDao()
        tags = database.tagDao()
        series = database.statisticsSeriesDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rootFolderRoundTripPreservesAllFields() {
        val folder = folder("root")

        folders.insert(folder.toEntity())

        assertEquals(folder, folders.getById("root")?.toDomain())
    }

    @Test
    fun nestedFolderRoundTripPreservesParentId() {
        val parent = folder("parent")
        val child = folder("child", parent.id)
        folders.insert(parent.toEntity())

        folders.insert(child.toEntity())

        assertEquals(child, folders.getById("child")?.toDomain())
    }

    @Test
    fun nonexistentFolderParentViolatesForeignKey() {
        val child = folder("child", FolderId("missing"))

        assertThrows(SQLiteConstraintException::class.java) { folders.insert(child.toEntity()) }
    }

    @Test
    fun parentDeleteIsRestrictedUntilChildIsDeleted() {
        folders.insert(folder("parent").toEntity())
        folders.insert(folder("child", FolderId("parent")).toEntity())

        assertThrows(SQLiteConstraintException::class.java) { folders.deleteById("parent") }

        assertEquals(1, folders.deleteById("child"))
        assertEquals(1, folders.deleteById("parent"))
    }

    @Test
    fun updatingFolderNameAndParentPreservesIdentity() {
        folders.insert(folder("first-parent").toEntity())
        folders.insert(folder("second-parent").toEntity())
        folders.insert(folder("child", FolderId("first-parent")).toEntity())

        val updated = folder("child", FolderId("second-parent"), "Renamed")
        folders.update(updated.toEntity())

        assertEquals(updated, folders.getById("child")?.toDomain())
        assertNull(folders.getById("Renamed"))
    }

    @Test
    fun tagRoundTripAndDeletePreserveExpectedRowSemantics() {
        val tag = Tag(TagId("tag"), "Focus", createdAt, updatedAt)
        tags.insert(tag.toEntity())

        assertEquals(tag, tags.getById("tag")?.toDomain())
        assertEquals(1, tags.deleteById("tag"))
        assertNull(tags.getById("tag"))
    }

    @Test
    fun duplicateAndCaseOnlyDifferentTagNamesAreAllowed() {
        tags.insert(Tag(TagId("one"), "Focus", createdAt, updatedAt).toEntity())
        tags.insert(Tag(TagId("two"), "Focus", createdAt, updatedAt).toEntity())
        tags.insert(Tag(TagId("three"), "focus", createdAt, updatedAt).toEntity())

        assertEquals(3, tags.getAll().size)
    }

    @Test
    fun allStatisticsKindsRoundTripWithNullableArchiveTime() {
        StatisticsSeriesKind.entries.forEachIndexed { index, kind ->
            val archivedAt = if (index == 0) null else updatedAt
            val expected = statisticsSeries(kind, archivedAt)
            series.insert(expected.toEntity())

            assertEquals(expected, series.getById(expected.id.value)?.toDomain())
        }
    }

    @Test
    fun statisticsArchiveAndDisplayNameCanChangeWithoutChangingIdentity() {
        val original = statisticsSeries(StatisticsSeriesKind.ACTIVITY, null)
        series.insert(original.toEntity())

        val updated = original.copy(displayName = "Renamed", archivedAt = updatedAt)
        series.update(updated.toEntity())

        assertEquals(updated, series.getById(original.id.value)?.toDomain())
        assertNull(series.getById("Renamed"))
    }

    @Test
    fun statisticsKindIsStoredAsTextCode() {
        series.insert(statisticsSeries(StatisticsSeriesKind.ONE_OFF_BUCKET, null).toEntity())

        database.openHelper.readableDatabase
            .query("SELECT kind FROM statistics_series")
            .use { cursor ->
                cursor.moveToFirst()
                assertEquals("ONE_OFF_BUCKET", cursor.getString(0))
            }
    }

    private fun folder(
        id: String,
        parentFolderId: FolderId? = null,
        name: String = id,
    ) = Folder(FolderId(id), name, parentFolderId, createdAt, updatedAt)

    private fun statisticsSeries(
        kind: StatisticsSeriesKind,
        archivedAt: Instant?,
    ) = StatisticsSeries(
        id = StatisticsSeriesId("series-${kind.name}"),
        kind = kind,
        displayName = "Series ${kind.name}",
        createdAt = createdAt,
        archivedAt = archivedAt,
    )
}
