package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class LibraryMappersTest {
    private val createdAt = Instant.ofEpochMilli(1_700_000_000_123)
    private val updatedAt = Instant.ofEpochMilli(1_700_000_010_456)

    @Test
    fun `folder round trip preserves typed identities and instants`() {
        val folder = Folder(FolderId("child"), "Child", FolderId("parent"), createdAt, updatedAt)

        assertEquals(folder, folder.toEntity().toDomain())
    }

    @Test
    fun `tag round trip preserves typed identity and instants`() {
        val tag = Tag(TagId("tag"), "Tag", createdAt, updatedAt)

        assertEquals(tag, tag.toEntity().toDomain())
    }

    @Test
    fun `statistics series round trip uses stable codes and nullable archive time`() {
        StatisticsSeriesKind.entries.forEach { kind ->
            val series = StatisticsSeries(StatisticsSeriesId(kind.name), kind, "Series", createdAt, updatedAt)
            val entity = series.toEntity()

            assertEquals(kind.name, entity.kind)
            assertEquals(series, entity.toDomain())
        }
    }

    @Test
    fun `unknown statistics kind fails explicitly`() {
        val entity = StatisticsSeriesEntity("id", "UNKNOWN", "Series", createdAt.toEpochMilli(), null)

        val error = assertThrows(IllegalStateException::class.java, entity::toDomain)

        assertEquals("Unknown statistics series kind code: UNKNOWN", error.message)
    }
}
