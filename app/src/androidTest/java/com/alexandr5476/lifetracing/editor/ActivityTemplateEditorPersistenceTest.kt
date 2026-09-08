package com.alexandr5476.lifetracing.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.HistoryReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ActivityTemplateEditorPersistenceTest {
    @Test
    fun editorWriterReloadsThroughCanonicalAuthoringLibraryAndHistoryReaders() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefix = "S2C1-${Instant.now().toEpochMilli()}"
            val createdAt = Instant.now()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val authoring = TemplateAuthoringRepository.create(context)
            try {
                val settings =
                    ActivityTemplateSettings(
                        showSeconds = false,
                        startCountdown = Duration.ofSeconds(4),
                        timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                        timerEndSound = false,
                        timerEndVibration = false,
                        keepScreenAwake = true,
                        confirmManualFinish = true,
                    )
                val originalDraft = fullDraft("$prefix none", settings)
                createThroughEditor(scope, authoring, originalDraft, createdAt)

                val reloadedAuthoring = TemplateAuthoringRepository.create(context)
                val reloadedLibrary = LibraryRepository.create(context)
                val canonicalItem =
                    reloadedLibrary
                        .search(
                            prefix,
                            LibraryKindFilter.ACTIVITIES,
                        ).single { it.name == originalDraft.name }
                val templateId = (canonicalItem.id as LibraryTemplateId.Activity).id
                val created = requireNotNull(reloadedAuthoring.getActivityTemplate(templateId))

                assertTrue(templateId.value.isNotBlank())
                assertTrue(created.statisticsSeriesId.value.isNotBlank())
                assertEquals(originalDraft.name, canonicalItem.name)
                assertCoreDraftPersisted(created, originalDraft)

                val stopwatchDraft =
                    ActivityTemplateDraft("$prefix stopwatch", null, TimeTrackingMode.STOPWATCH, null)
                val timerDraft =
                    ActivityTemplateDraft(
                        "$prefix timer",
                        null,
                        TimeTrackingMode.TIMER,
                        Duration.ofSeconds(95),
                    )
                createThroughEditor(scope, authoring, stopwatchDraft, createdAt.plusMillis(1))
                createThroughEditor(scope, authoring, timerDraft, createdAt.plusMillis(2))
                val modeReader = TemplateAuthoringRepository.create(context)
                val modeLibrary = LibraryRepository.create(context)
                val modes = modeLibrary.search(prefix, LibraryKindFilter.ACTIVITIES).associateBy { it.name }
                assertEquals(
                    TimeTrackingMode.STOPWATCH,
                    modeReader
                        .getActivityTemplate((modes.getValue(stopwatchDraft.name).id as LibraryTemplateId.Activity).id)
                        ?.timeTrackingMode,
                )
                val timer =
                    modeReader.getActivityTemplate(
                        (modes.getValue(timerDraft.name).id as LibraryTemplateId.Activity).id,
                    )
                assertEquals(TimeTrackingMode.TIMER, timer?.timeTrackingMode)
                assertEquals(Duration.ofSeconds(95), timer?.timerTarget)

                val folderId = FolderId("$prefix-folder")
                val tagId = TagId("$prefix-tag")
                reloadedLibrary.createFolder(folderId, "$prefix folder", null, createdAt.plusMillis(3))
                reloadedLibrary.createTag(tagId, "$prefix tag", createdAt.plusMillis(3))
                reloadedLibrary.moveActivityTemplateToFolder(templateId, folderId, createdAt.plusMillis(3))
                reloadedLibrary.addTag(canonicalItem.id, tagId)
                reloadedLibrary.pin(canonicalItem.id)
                val execution =
                    reloadedLibrary.completeNoLiveActivityFromTemplate(
                        templateId,
                        createdAt.plusSeconds(1),
                        createdAt.plusSeconds(1),
                        ZoneOffset.UTC,
                        expectedRevision = created.revision,
                    )

                saveThroughEditor(scope, authoring, templateId, createdAt.plusSeconds(2)) { draft ->
                    draft.copy(
                        fields =
                            draft.fields.map { field ->
                                if (field.type ==
                                    CustomFieldType.NUMBER
                                ) {
                                    field.copy(name = "Distance renamed")
                                } else {
                                    field
                                }
                            },
                    )
                }
                val presentation =
                    requireNotNull(TemplateAuthoringRepository.create(context).getActivityTemplate(templateId))
                assertEquals(created.id, presentation.id)
                assertEquals(created.statisticsSeriesId, presentation.statisticsSeriesId)
                assertEquals(1L, presentation.revision)
                assertEquals(
                    "Distance renamed",
                    presentation.fields.single { it.type == CustomFieldType.NUMBER }.name,
                )

                saveThroughEditor(scope, authoring, templateId, createdAt.plusSeconds(3)) { draft ->
                    draft.copy(
                        name = "$prefix edited",
                        fields =
                            draft.fields.map { field ->
                                when (field.type) {
                                    CustomFieldType.NUMBER -> field.copy(unit = "m")
                                    CustomFieldType.CATEGORY -> field.copy(defaultCategoryOption = null)
                                    CustomFieldType.TEXT -> field
                                }
                            },
                    )
                }

                val freshAuthoring = TemplateAuthoringRepository.create(context)
                val freshLibrary = LibraryRepository.create(context)
                val edited = requireNotNull(freshAuthoring.getActivityTemplate(templateId))
                assertEquals(templateId, edited.id)
                assertEquals(created.statisticsSeriesId, edited.statisticsSeriesId)
                assertEquals(2L, edited.revision)
                assertEquals(settings, edited.settings)
                assertEquals(folderId, edited.folderId)
                assertEquals(setOf(tagId), edited.tagIds)
                val activeNumber = edited.fields.single { it.deletedAt == null && it.type == CustomFieldType.NUMBER }
                val oldNumber = created.fields.single { it.type == CustomFieldType.NUMBER }
                assertNotEquals(oldNumber.id, activeNumber.id)
                assertNotNull(edited.fields.single { it.id == oldNumber.id }.deletedAt)
                assertEquals("m", activeNumber.unit)
                assertNull(
                    edited.fields
                        .single { it.deletedAt == null && it.type == CustomFieldType.CATEGORY }
                        .defaultCategoryOptionId,
                )

                val libraryAfterEdit = freshLibrary.search("$prefix edited", LibraryKindFilter.ACTIVITIES).single()
                assertEquals(folderId, libraryAfterEdit.folderId)
                assertEquals(setOf(tagId), libraryAfterEdit.tagIds)
                assertNotNull(libraryAfterEdit.pinnedRank)
                assertNotNull(libraryAfterEdit.lastUsedAt)
                assertTrue(freshLibrary.getRoot().pinned.any { it.id == libraryAfterEdit.id })

                val historical = requireNotNull(HistoryReadRepository.create(context).getActivityDetail(execution.id))
                assertEquals(originalDraft.name, historical.root.title)
                assertEquals(TimeTrackingMode.NO_LIVE_TRACKING, historical.root.timeTrackingMode)
                assertEquals(settings, historical.settings)
                val historicalNumber = historical.fields.single { it.type == CustomFieldType.NUMBER }
                assertEquals("kg", historicalNumber.unit)
                assertEquals(ActivityHistoryConfiguredValue.Number(12_345), historicalNumber.configuredValue)
                assertEquals("Distance", historicalNumber.name)
            } finally {
                scope.cancel()
            }
        }

    private suspend fun createThroughEditor(
        scope: CoroutineScope,
        repository: TemplateAuthoringRepository,
        draft: ActivityTemplateDraft,
        at: Instant,
    ) {
        val committed = CompletableDeferred<Unit>()
        val controller = editor(scope, repository, ActivityTemplateEditorTarget.New, at, committed)
        controller.awaitReady()
        controller.updateDraft { draft }
        controller.save()
        withTimeout(5_000) { committed.await() }
        controller.close()
    }

    private suspend fun saveThroughEditor(
        scope: CoroutineScope,
        repository: TemplateAuthoringRepository,
        id: ActivityTemplateId,
        at: Instant,
        transform: (ActivityTemplateDraft) -> ActivityTemplateDraft,
    ) {
        val committed = CompletableDeferred<Unit>()
        val controller = editor(scope, repository, ActivityTemplateEditorTarget.Existing(id), at, committed)
        controller.awaitReady()
        controller.updateDraft(transform)
        controller.save()
        withTimeout(5_000) { committed.await() }
        controller.close()
    }

    private fun editor(
        scope: CoroutineScope,
        repository: TemplateAuthoringRepository,
        target: ActivityTemplateEditorTarget,
        at: Instant,
        committed: CompletableDeferred<Unit>,
    ) = ActivityTemplateEditorController(
        scope,
        target,
        repository::getActivityTemplate,
        repository::createActivityTemplate,
        repository::saveActivityTemplate,
        { at },
        { committed.complete(Unit) },
    )

    private suspend fun ActivityTemplateEditorController.awaitReady() {
        withTimeout(5_000) { state.first { it.load is ActivityTemplateEditorLoad.Ready } }
    }

    private fun fullDraft(
        name: String,
        settings: ActivityTemplateSettings,
    ) = ActivityTemplateDraft(
        name,
        "Core comment",
        TimeTrackingMode.NO_LIVE_TRACKING,
        null,
        settings,
        listOf(
            ActivityFieldDraft(
                DraftIdentity.New("distance"),
                0,
                "Distance",
                CustomFieldType.NUMBER,
                "kg",
                3,
                12_345,
                isMainValue = true,
            ),
            ActivityFieldDraft(
                DraftIdentity.New("difficulty"),
                1,
                "Difficulty",
                CustomFieldType.CATEGORY,
                defaultCategoryOption = DraftIdentity.New("hard"),
                categoryOptions =
                    listOf(
                        ActivityCategoryOptionDraft(DraftIdentity.New("easy"), 0, "Easy"),
                        ActivityCategoryOptionDraft(DraftIdentity.New("hard"), 1, "Hard"),
                    ),
            ),
            ActivityFieldDraft(
                DraftIdentity.New("note"),
                2,
                "Note",
                CustomFieldType.TEXT,
                defaultText = "Keep form",
            ),
        ),
    )

    private fun assertCoreDraftPersisted(
        actual: ActivityTemplate,
        expected: ActivityTemplateDraft,
    ) {
        assertEquals(expected.name, actual.name)
        assertEquals(expected.shortComment, actual.shortComment)
        assertEquals(expected.timeTrackingMode, actual.timeTrackingMode)
        assertEquals(expected.timerTarget, actual.timerTarget)
        assertEquals(expected.settings, actual.settings)
        assertEquals(expected.fields.map(ActivityFieldDraft::type), actual.fields.map { it.type })
        assertEquals(
            expected.fields.map(ActivityFieldDraft::displayPrecision),
            actual.fields.map { it.displayPrecision },
        )
        assertEquals(
            expected.fields.map(ActivityFieldDraft::defaultNumberScaled),
            actual.fields.map { it.defaultNumberScaled },
        )
        assertEquals(expected.fields.map(ActivityFieldDraft::defaultText), actual.fields.map { it.defaultText })
        assertEquals(expected.fields.map(ActivityFieldDraft::isMainValue), actual.fields.map { it.isMainValue })
        assertEquals(
            "Hard",
            actual.fields
                .single { it.type == CustomFieldType.CATEGORY }
                .categoryOptions
                .single { option ->
                    option.id == actual.fields.single { it.type == CustomFieldType.CATEGORY }.defaultCategoryOptionId
                }.label,
        )
    }
}
