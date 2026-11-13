package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.ActivityHistoryDetailRoot
import com.alexandr5476.lifetracing.DailyRoot
import com.alexandr5476.lifetracing.HistoryRoot
import com.alexandr5476.lifetracing.SequenceHistoryDetailRoot
import com.alexandr5476.lifetracing.openActivityHistoryDetail
import com.alexandr5476.lifetracing.openSequenceHistoryDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistoryNavigationAdmissionTest {
    @Test
    fun obsoleteAndEqualRematerializedPublicationsCannotNavigate() {
        val original = HistoryPresentationState(HistoryBrowseWindow(DATE, DATE))
        var current = original
        var actions = 0
        val admission = HistoryNavigationAdmission(original, { current }, { true })
        val callback = admission.callback { actions++ }
        current = original.copy()
        callback()
        assertEquals(0, actions)
    }

    @Test
    fun ownershipIsCheckedBeforeOutgoingContentDisposalAndSubmissionRevokesSiblingActions() {
        val publication = Any()
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(DailyRoot, HistoryRoot)
        val admission = HistoryNavigationAdmission(publication, { publication }, { stack.last() == HistoryRoot })
        val activity = admission.callback { stack.openActivityHistoryDetail("exact-activity") }
        val sequence = admission.callback { stack.openSequenceHistoryDetail("exact-sequence") }
        activity()
        sequence()
        assertEquals(ActivityHistoryDetailRoot("exact-activity"), stack.last())
        stack.removeAt(stack.lastIndex)
        sequence()
        assertEquals(HistoryRoot, stack.last())
    }

    @Test
    fun sequenceUsesExactIdentityAndHiddenBackCannotRemoveForegroundRoute() {
        val publication = Any()
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(DailyRoot, HistoryRoot)
        val admission = HistoryNavigationAdmission(publication, { publication }, { stack.last() == HistoryRoot })
        val back = admission.callback { stack.removeAt(stack.lastIndex) }
        stack.openSequenceHistoryDetail("exact-sequence")
        back()
        assertEquals(SequenceHistoryDetailRoot("exact-sequence"), stack.last())
    }

    @Test
    fun disposalAndDisabledControlsRejectRetainedActions() {
        val publication = Any()
        var actions = 0
        val admission = HistoryNavigationAdmission(publication, { publication }, { true })
        admission.callback(enabled = false) { actions++ }()
        val callback = admission.callback { actions++ }
        admission.attached = false
        callback()
        assertEquals(0, actions)
    }

    companion object {
        private val DATE = java.time.LocalDate.of(2026, 1, 1)
    }
}
