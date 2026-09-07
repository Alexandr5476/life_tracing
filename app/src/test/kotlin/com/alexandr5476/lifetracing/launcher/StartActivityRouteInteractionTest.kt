package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class StartActivityRouteInteractionTest {
    @Test
    fun pendingSelectionMatchesOnceSurvivesFailureAndCanBeReplaced() {
        val interaction = StartActivityRouteInteraction()
        val first = LibraryTemplateId.Activity(ActivityTemplateId("first"))
        val second = LibraryTemplateId.Sequence(SequenceTemplateId("second"))

        interaction.select(first)
        assertNull(interaction.resolveSelection(sequenceTarget(second)))
        assertEquals(first, interaction.pendingSelectionId)

        interaction.select(second)
        assertEquals(second, interaction.pendingSelectionId)
        assertEquals(StartActivityAction.Launch(), interaction.resolveSelection(sequenceTarget(second)))
        assertNull(interaction.pendingSelectionId)
        assertNull(interaction.resolveSelection(sequenceTarget(second)))
    }

    @Test
    fun timedAndNoLiveWithoutMainValueUseTheSameOneShotResolution() {
        val timed = LibraryTemplateId.Activity(ActivityTemplateId("timed"))
        val noLive = LibraryTemplateId.Activity(ActivityTemplateId("no-live"))

        listOf(
            activityTarget(timed, TimeTrackingMode.STOPWATCH),
            activityTarget(noLive, TimeTrackingMode.NO_LIVE_TRACKING),
        ).forEach { target ->
            val interaction = StartActivityRouteInteraction()
            interaction.select(target.id)
            assertEquals(StartActivityAction.Launch(), interaction.resolveSelection(target))
            assertNull(interaction.resolveSelection(target))
        }
    }

    @Test
    fun editorRetainsUntouchedRoundedDefaultAndCompletesOnlyOnce() {
        val interaction = StartActivityRouteInteraction()
        val target = activityTarget(mainValue = mainValue("precise", precision = 2, default = 1_234))

        interaction.select(target.id)
        assertNull(interaction.resolveSelection(target))
        val retainedAfterRecreation = interaction
        assertSame(interaction, retainedAfterRecreation)
        assertEquals("1.23", retainedAfterRecreation.quickEditor?.text)
        assertEquals(false, retainedAfterRecreation.quickEditor?.changed)

        assertEquals(StartActivityAction.Launch(), retainedAfterRecreation.completeQuickEditor(target))
        assertNull(interaction.quickEditor)
        assertNull(interaction.completeQuickEditor(target))
    }

    @Test
    fun editorRetainsExactFieldAndEditedZeroMissingAndNegativeFraction() {
        val cases =
            listOf(
                "0" to QuickMainValue.Number(0),
                "" to QuickMainValue.Missing,
                "-1.25" to QuickMainValue.Number(-1_250),
            )

        cases.forEachIndexed { index, (text, expected) ->
            val interaction = StartActivityRouteInteraction()
            val fieldId = ActivityTemplateFieldId("field-$index")
            val target = activityTarget(mainValue = mainValue(fieldId.value, precision = 3, default = null))
            interaction.select(target.id)
            interaction.resolveSelection(target)
            interaction.editQuickValue(text)

            assertEquals(text, interaction.quickEditor?.text)
            assertEquals(true, interaction.quickEditor?.changed)
            assertEquals(
                StartActivityAction.Launch(QuickMainValueOverride(fieldId, expected)),
                interaction.completeQuickEditor(target),
            )
            assertNull(interaction.completeQuickEditor(target))
        }
    }

    @Test
    fun cancellingEditorConsumesTheSelectionSoItCannotReopen() {
        val interaction = StartActivityRouteInteraction()
        val target = activityTarget(mainValue = mainValue("field", precision = 0, default = 2_000))

        interaction.select(target.id)
        interaction.resolveSelection(target)
        interaction.cancelQuickEditor()

        assertNull(interaction.quickEditor)
        assertNull(interaction.resolveSelection(target))
    }

    @Test
    fun browsePathReturnsToTheCorrectParentAndFreshInteractionStartsAtRoot() {
        val interaction = StartActivityRouteInteraction()
        val parent = folder("parent", null)
        val child = folder("child", parent.id)

        interaction.openFolder(parent)
        interaction.openFolder(child)
        assertEquals(listOf("parent", "child"), interaction.browsePath.map { it.name })
        assertEquals(parent.id, interaction.browseBack())
        assertNull(interaction.browseBack())
        assertEquals(emptyList<LauncherBreadcrumb>(), interaction.browsePath)

        interaction.openFolder(parent)
        interaction.openRoot()
        assertEquals(emptyList<LauncherBreadcrumb>(), interaction.browsePath)
        assertEquals(emptyList<LauncherBreadcrumb>(), StartActivityRouteInteraction().browsePath)
    }

    private fun activityTarget(
        id: LibraryTemplateId.Activity = LibraryTemplateId.Activity(ActivityTemplateId("activity")),
        mode: TimeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
        mainValue: ActivityLaunchMainValue? = null,
    ) = LibraryLaunchTarget.Activity(id, id.value, Duration.ZERO, mode, mainValue)

    private fun sequenceTarget(id: LibraryTemplateId.Sequence) =
        LibraryLaunchTarget.Sequence(id, id.value, Duration.ZERO)

    private fun mainValue(
        id: String,
        precision: Int?,
        default: Long?,
    ) = ActivityLaunchMainValue(ActivityTemplateFieldId(id), "Value", null, precision, default)

    private fun folder(
        id: String,
        parent: FolderId?,
    ) = Folder(FolderId(id), id, parent, Instant.EPOCH, Instant.EPOCH)
}
