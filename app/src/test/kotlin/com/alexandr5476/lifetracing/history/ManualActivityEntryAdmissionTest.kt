package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ManualActivityEntryAdmissionTest {
    @Test
    fun routeLossPermanentlyRevokesRetryAndBackUntilNewPublication() {
        for (useBack in listOf(false, true)) {
            val state =
                ManualActivityEntryState(catalog = ManualEntryLoad.Failure(ManualEntryIssue.CATALOG_READ_FAILURE))
            var owns = true
            var calls = 0
            val old = ManualEntryAdmission(state, { state }, { owns }, { calls++ })
            val callback = if (useBack) old.back { calls++ } else old.action(ManualActivityEntryAction.RetryCatalog)
            owns = false
            callback()
            owns = true
            callback()
            assertEquals(0, calls)
            val fresh = ManualEntryAdmission(state, { state }, { owns }, { calls++ })
            if (useBack) fresh.back { calls++ }() else fresh.action(ManualActivityEntryAction.RetryCatalog)()
            assertEquals(1, calls)
        }
    }

    @Test
    fun replacedCatalogCannotSelectEvenWhenDuplicateNamesMatchAndCurrentExactIdRemainsActionable() {
        val first = item("first")
        val second = item("second")
        var current = ManualActivityEntryState(catalog = ManualEntryLoad.Content(listOf(first)))
        val actions = mutableListOf<ManualActivityEntryAction>()
        val old = ManualEntryAdmission(current, { current }, { true }, actions::add)
        val select = old.action(ManualActivityEntryAction.Select(first.id))
        current = current.copy(catalog = ManualEntryLoad.Content(listOf(second)))
        select()
        assertEquals(emptyList<ManualActivityEntryAction>(), actions)
        val visible = ManualEntryAdmission(current, { current }, { true }, actions::add)
        visible.action(ManualActivityEntryAction.Select(second.id))()
        assertEquals(listOf(ManualActivityEntryAction.Select(second.id)), actions)
    }

    @Test
    fun repeatedInputMayAdvanceItselfButCannotEditNewerDraftSelectionOrDisposedRoute() {
        val source =
            ActivityTemplate(
                ActivityTemplateId("first"),
                "Same",
                null,
                TimeTrackingMode.STOPWATCH,
                null,
                StatisticsSeriesId("series"),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        var current = ManualActivityEntryState(selected = ManualEntryLoad.Content(source))
        var visible = true
        val values = mutableListOf<String>()
        val admission =
            ManualEntryAdmission(current, { current }, { visible }, dispatch = {
                val text = (it as ManualActivityEntryAction.EditStarted).text
                values += text
                current = current.copy(startedText = text, draftVersion = current.draftVersion + 1)
            })
        val edit = admission.input { ManualActivityEntryAction.EditStarted(it) }
        edit("2")
        edit("20")
        assertEquals(listOf("2", "20"), values)
        current = current.copy(draftVersion = current.draftVersion + 1)
        edit("obsolete")
        current = current.copy(selected = ManualEntryLoad.Content(source.copy(id = ActivityTemplateId("second"))))
        edit("other selection")
        visible = false
        edit("hidden")
        admission.attached = false
        visible = true
        edit("disposed")
        assertEquals(listOf("2", "20"), values)
    }

    @Test
    fun catalogRetryLoadMoreAndBackAreRevokedBeforeRecompositionAndAfterExit() {
        val row = item("first")
        var current = ManualActivityEntryState(catalog = ManualEntryLoad.Failure(ManualEntryIssue.CATALOG_READ_FAILURE))
        var owns = true
        var calls = 0
        val retryOwner = ManualEntryAdmission(current, { current }, { owns }, { calls++ })
        val retry = retryOwner.action(ManualActivityEntryAction.RetryCatalog)
        current = current.copy(catalog = ManualEntryLoad.Content(listOf(row)), nextCatalogCursor = row)
        retry()
        val pageOwner = ManualEntryAdmission(current, { current }, { owns }, { calls++ })
        val more = pageOwner.action(ManualActivityEntryAction.LoadMore)
        val back = pageOwner.back { calls++ }
        current = current.copy(catalog = ManualEntryLoad.Loading)
        more()
        back()
        owns = false
        more()
        retry()
        back()
        assertEquals(0, calls)
    }

    private fun item(id: String) =
        ReusableActivityCatalogItem(
            ActivityTemplateId(id),
            "Same",
            TimeTrackingMode.STOPWATCH,
            null,
            null,
            null,
            null,
            null,
        )
}
