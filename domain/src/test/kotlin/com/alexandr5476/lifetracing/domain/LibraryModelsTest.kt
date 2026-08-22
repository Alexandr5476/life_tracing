package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class LibraryModelsTest {
    private val createdAt = Instant.ofEpochMilli(1_700_000_000_123)
    private val updatedAt = Instant.ofEpochMilli(1_700_000_010_456)

    @Test
    fun `folder and tag names are not identity`() {
        val folder = Folder(FolderId("folder"), "Original", null, createdAt, updatedAt)
        val tag = Tag(TagId("tag"), "Original", createdAt, updatedAt)

        assertEquals(folder.id, folder.copy(name = "Renamed").id)
        assertEquals(tag.id, tag.copy(name = "Renamed").id)
    }

    @Test
    fun `statistics label and archive state do not change series identity`() {
        val series =
            StatisticsSeries(
                StatisticsSeriesId("series"),
                StatisticsSeriesKind.ACTIVITY,
                "Original",
                createdAt,
                null,
            )

        assertNull(series.archivedAt)
        assertEquals(series.id, series.copy(displayName = "Renamed", archivedAt = updatedAt).id)
    }

    @Test
    fun `statistics kinds expose only frozen stable codes`() {
        assertEquals(
            setOf("ACTIVITY", "SEQUENCE", "ONE_OFF_BUCKET"),
            StatisticsSeriesKind.entries.mapTo(mutableSetOf(), StatisticsSeriesKind::name),
        )
    }

    @Test
    fun `pinned ranks are sparse deterministic and reject duplicate typed identities`() {
        val activity = LibraryTemplateId.Activity(ActivityTemplateId("same"))
        val sequence = LibraryTemplateId.Sequence(SequenceTemplateId("same"))

        assertEquals(mapOf(activity to 1024, sequence to 2048), LibraryPinnedRanks.forOrder(listOf(activity, sequence)))
        assertThrows(IllegalArgumentException::class.java) {
            LibraryPinnedRanks.forOrder(listOf(activity, activity))
        }
    }
}
