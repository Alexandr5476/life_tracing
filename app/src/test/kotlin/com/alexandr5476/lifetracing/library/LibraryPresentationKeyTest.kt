package com.alexandr5476.lifetracing.library

import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryPresentationKeyTest {
    @Test
    fun rematerializedCatalogKeyComparisonsHaveLinearControlWorkWithoutElementVisits() {
        for (size in listOf(16, 32, 64, 128)) {
            val probes = Probes()
            val before = publication(size, "before", probes)
            val after = publication(size, "after", probes)
            val controls = size * 3 // Open, Quick Start and organization for each rendered row.
            repeat(controls) { assertNotEquals(before, after) }
            assertEquals(2 * size * controls, probes.reads, "Calibrate the former structural-key cost")
            probes.reads = 0
            val previousKeys = List(controls) { LibraryPresentationKey(before) }
            val currentKeys = List(controls) { LibraryPresentationKey(after) }
            var comparisons = 0
            previousKeys.zip(currentKeys).forEach { (previous, current) ->
                assertNotEquals(previous, current)
                comparisons++
            }
            assertEquals(controls, comparisons)
            assertEquals(0, probes.reads, "Publication keys must never traverse catalog elements")
        }
    }

    @Test
    fun samePublicationKeepsItsKeyAcrossScopeRecreationButEqualNewPublicationRevokesIt() {
        val state = publication(16, "same", Probes())
        val equalPublication = publication(16, "same", Probes())
        assertEquals(state, equalPublication)
        val scope = LibraryOrganizationScope(state, { state }, { true }, "Root")
        val recreated = LibraryOrganizationScope(state, { state }, { true }, "Root")
        assertEquals(scope.presentationKey, recreated.presentationKey)
        assertEquals(scope.presentationKey.hashCode(), recreated.presentationKey.hashCode())
        assertNotEquals(scope.presentationKey, LibraryPresentationKey(equalPublication))
        assertNotEquals(scope.presentationKey, LibraryPresentationKey(state.copy()))
    }

    @Test
    fun keyEqualityAndHashingNeverReadTheCatalog() {
        val probes = Probes()
        val state = publication(16, "same", probes)
        val first = LibraryPresentationKey(state)
        val second = LibraryPresentationKey(state)
        assertTrue(first == second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, LibraryPresentationKey(state.copy(query = "search")))
        assertEquals(0, probes.reads)
    }

    private fun publication(
        size: Int,
        lastName: String,
        probes: Probes,
    ): LibraryPresentationState {
        val rows =
            ProbedList(
                List(size) { index ->
                    LibraryTrackable(
                        LibraryTemplateId.Activity(ActivityTemplateId("item-$index")),
                        if (index == size - 1) lastName else "Same",
                        null,
                        null,
                        emptySet(),
                        index,
                        null,
                        null,
                    )
                },
                probes,
            )
        return LibraryPresentationState(
            browse =
                LibraryLoad.Content(
                    LibraryBrowse(null, emptyList(), rows, LibraryContents(emptyList(), rows, emptyList())),
                ),
            organization = LibraryLoad.Content(LibraryOrganization(emptyList(), emptyList())),
        )
    }

    private class Probes {
        var reads = 0
    }

    private class ProbedList<T>(
        private val rows: List<T>,
        private val probes: Probes,
    ) : AbstractList<T>() {
        override val size: Int get() = rows.size

        override fun get(index: Int): T {
            probes.reads++
            return rows[index]
        }
    }
}
