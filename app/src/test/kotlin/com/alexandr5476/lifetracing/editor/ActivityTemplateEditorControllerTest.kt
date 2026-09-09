@file:Suppress("LargeClass")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ActivityTemplateEditorControllerTest {
    @Test
    fun repeatedSaveDuringOneCreateCommitsExactlyOnce() =
        runBlocking {
            val releaseWrite = CompletableDeferred<Unit>()
            var writes = 0
            val controller =
                controller(
                    target = ActivityTemplateEditorTarget.New,
                    create = { draft, _, _ ->
                        writes++
                        releaseWrite.await()
                        template(draft)
                    },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Walk") }

            controller.save()
            controller.save()
            releaseWrite.complete(Unit)
            controller.awaitCommitted()

            assertEquals(1, writes)
            controller.close()
        }

    @Test
    fun dirtyBackRequiresDiscardButCleanBackDoesNot() =
        runBlocking {
            val controller = controller()
            controller.awaitReady()
            var exits = 0

            controller.requestBack { exits++ }
            assertEquals(1, exits)
            controller.updateDraft { it.copy(name = "Walk") }
            controller.requestBack { exits++ }

            assertTrue(controller.state.value.discardConfirmationVisible)
            controller.dismissDiscard()
            assertFalse(controller.state.value.discardConfirmationVisible)
            assertEquals("Walk", requireNotNull(controller.state.value.readyDraft()).name)
            controller.close()
        }

    @Test
    fun confirmedDiscardExitsWithoutWriting() =
        runBlocking {
            var exits = 0
            var writes = 0
            val controller =
                controller(create = { draft, _, _ ->
                    writes++
                    template(draft)
                })
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Walk") }

            controller.requestBack { exits++ }
            controller.discard { exits++ }

            assertEquals(1, exits)
            assertEquals(0, writes)
            controller.close()
        }

    @Test
    fun everyExitEntryPointWaitsForAnInFlightSaveInsteadOfReportingDiscard() =
        runBlocking {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var exits = 0
            var writes = 0
            val controller =
                controller(
                    create = { draft, _, _ ->
                        writes++
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        template(draft)
                    },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Walk") }
            controller.save()
            writeStarted.await()

            controller.requestBack { exits++ }
            controller.requestBack { exits++ }
            controller.discard { exits++ }

            assertEquals(0, exits)
            assertFalse(controller.state.value.discardConfirmationVisible)
            assertEquals(ActivityTemplateEditorSave.Saving, controller.state.value.save)
            releaseWrite.complete(Unit)
            controller.awaitCommitted()
            assertEquals(1, writes)
            controller.close()
        }

    @Test
    fun disposalNeverTurnsADraftIntoAWriteOrStartsASecondWriter() =
        runBlocking {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val writeFinished = CompletableDeferred<Unit>()
            var writes = 0
            val unsaved =
                controller(create = { draft, _, _ ->
                    writes++
                    template(draft)
                })
            unsaved.awaitReady()
            unsaved.updateDraft { it.copy(name = "Unsaved") }
            unsaved.close()
            assertEquals(0, writes)

            val saving =
                controller(
                    create = { draft, _, _ ->
                        writes++
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        writeFinished.complete(Unit)
                        template(draft)
                    },
                )
            saving.awaitReady()
            saving.updateDraft { it.copy(name = "Saving") }
            saving.save()
            writeStarted.await()
            saving.close()
            saving.save()
            releaseWrite.complete(Unit)
            writeFinished.await()
            saving.save()

            assertEquals(1, writes)
            assertFalse(saving.state.value.save is ActivityTemplateEditorSave.Committed)
        }

    @Test
    fun failedSaveKeepsTheDraftAndCanRetry() =
        runBlocking {
            var writes = 0
            val controller =
                controller(
                    create = { draft, _, _ ->
                        writes++
                        if (writes == 1) error("disk full")
                        template(draft)
                    },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Walk") }

            controller.save()
            controller.awaitSaveFailure()
            assertEquals(
                "Walk",
                controller.state.value
                    .readyDraft()
                    ?.name,
            )
            controller.save()
            controller.awaitCommitted()

            assertEquals(2, writes)
            controller.close()
        }

    @Test
    @Suppress("LongMethod") // The gate, failure, and retry assertions describe one save boundary.
    fun numberPresentationCannotDivergeFromTheFrozenRetryDraftWhileSaving() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val submitted = mutableListOf<ActivityTemplateDraft>()
            var writes = 0
            val controller =
                controller(
                    create = { draft, _, _ ->
                        submitted += draft
                        writes++
                        if (writes == 1) {
                            started.complete(Unit)
                            release.await()
                            error("disk full")
                        }
                        template(draft)
                    },
                )
            controller.awaitReady()
            controller.updateDraft {
                it.copy(
                    fields =
                        listOf(
                            numberField().let { field ->
                                ActivityFieldDraft(
                                    DraftIdentity.New("distance"),
                                    field.position,
                                    field.name,
                                    field.type,
                                    field.unit,
                                    field.displayPrecision,
                                    field.defaultNumberScaled,
                                    isMainValue = field.isMainValue,
                                )
                            },
                        ),
                )
            }
            val field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            controller.save()
            started.await()
            controller.setNumberDefault(field, "2", ::parseLauncherNumber)
            controller.setDisplayPrecision(field, "2")
            release.complete(Unit)
            controller.awaitSaveFailure()

            val retryable =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            assertEquals(1_000, retryable.defaultNumberScaled)
            assertEquals(3, retryable.displayPrecision)
            assertTrue(
                controller.state.value.numberDefaultTexts
                    .isEmpty(),
            )
            controller.save()
            controller.awaitCommitted()
            assertEquals(submitted.first(), submitted.last())
            controller.close()
        }

    @Test
    fun staleRevisionKeepsDraftUntilExplicitReload() =
        runBlocking {
            var canonical = template(ActivityTemplateDraft("Original", null, TimeTrackingMode.STOPWATCH, null))
            val controller =
                existingController(
                    load = { canonical },
                    save = { _, expectedRevision, draft, _ ->
                        require(expectedRevision == canonical.revision) {
                            "ActivityTemplate revision changed concurrently"
                        }
                        template(draft)
                    },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "My draft") }
            canonical = canonical.copy(name = "Elsewhere", revision = 2)

            controller.save()
            val failure = controller.awaitSaveFailure()
            assertTrue(failure.isConflict)
            assertEquals(
                "My draft",
                controller.state.value
                    .readyDraft()
                    ?.name,
            )

            controller.retry()
            controller.awaitReady { it.draft.name == "Elsewhere" }
            assertEquals(
                "Elsewhere",
                controller.state.value
                    .readyDraft()
                    ?.name,
            )
            controller.close()
        }

    @Test
    fun numericInputFollowsStableFieldLineageAndRemovalDropsStaleValidation() =
        runBlocking {
            val originalField = numberField()
            val original = templateWithField(originalField)
            var writes = 0
            val controller =
                existingController(
                    load = { original },
                    save = { _, _, draft, _ ->
                        writes++
                        template(draft)
                    },
                )
            controller.awaitReady()
            var field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()

            controller.setNumberDefault(field, "invalid", ::parseLauncherNumber)
            val oldKey = field.identity.editorKey()
            assertTrue(oldKey in controller.state.value.invalidNumberFields)

            controller.updateDraft { draft ->
                draft.copy(fields = listOf(draft.fields.single().copy(unit = "m")))
            }
            field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            assertInstanceOf(DraftIdentity.Existing::class.java, field.identity)
            assertEquals(mapOf(oldKey to "invalid"), controller.state.value.numberDefaultTexts)
            assertEquals(setOf(oldKey), controller.state.value.invalidNumberFields)

            controller.updateDraft { it.copy(fields = emptyList()) }
            assertTrue(
                controller.state.value.numberDefaultTexts
                    .isEmpty(),
            )
            assertTrue(
                controller.state.value.invalidNumberFields
                    .isEmpty(),
            )
            controller.save()
            controller.awaitCommitted()
            assertEquals(1, writes)
            controller.close()
        }

    @Test
    @Suppress("LongMethod")
    fun finalUnitAndTypeReplacementUseNewSubmittedIdentity() =
        runBlocking {
            var unitSubmission: ActivityTemplateDraft? = null
            val controller =
                existingController(
                    load = { templateWithField(numberField()) },
                    save = { _, _, draft, _ ->
                        unitSubmission = draft
                        template(draft)
                    },
                )
            controller.awaitReady()
            val original =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            controller.setNumberDefault(original, "1,234", ::parseLauncherNumber)

            controller.updateDraft { draft ->
                draft.copy(fields = listOf(draft.fields.single().copy(unit = "m")))
            }
            val unitReplacement =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            assertEquals(original.identity, unitReplacement.identity)
            assertEquals(1_234, unitReplacement.defaultNumberScaled)
            assertEquals(
                mapOf(unitReplacement.identity.editorKey() to "1,234"),
                controller.state.value.numberDefaultTexts,
            )
            controller.save()
            controller.awaitCommitted()
            assertInstanceOf(DraftIdentity.New::class.java, unitSubmission!!.fields.single().identity)

            var typeSubmission: ActivityTemplateDraft? = null
            val typeController =
                existingController(
                    load = { templateWithField(numberField()) },
                    save = { _, _, draft, _ ->
                        typeSubmission = draft
                        template(draft)
                    },
                )
            typeController.awaitReady()
            val existingIdentity =
                typeController.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .identity
            typeController.updateDraft { draft ->
                draft.copy(fields = listOf(draft.fields.single().copy(type = CustomFieldType.TEXT)))
            }
            val typeReplacement =
                typeController.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            assertEquals(existingIdentity, typeReplacement.identity)
            assertTrue(
                typeController.state.value.numberDefaultTexts
                    .isEmpty(),
            )
            assertTrue(
                typeController.state.value.invalidNumberFields
                    .isEmpty(),
            )
            typeController.save()
            typeController.awaitCommitted()
            assertInstanceOf(DraftIdentity.New::class.java, typeSubmission!!.fields.single().identity)
            controller.close()
            typeController.close()
        }

    @Test
    @Suppress("LongMethod")
    fun precisionRevalidatesVisibleTextWithoutFallingBackToStoredDefault() =
        runBlocking {
            var writes = 0
            val controller =
                existingController(
                    load = { templateWithField(numberField().copy(displayPrecision = 2)) },
                    save = { _, _, draft, _ ->
                        writes++
                        template(draft)
                    },
                )
            controller.awaitReady()
            var field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()

            controller.setNumberDefault(field, "1,234", ::parseLauncherNumber)
            assertTrue(field.identity.editorKey() in controller.state.value.invalidNumberFields)
            controller.setDisplayPrecision(field, "0")

            assertEquals(
                "1,234",
                controller.state.value.numberDefaultTexts
                    .getValue(field.identity.editorKey()),
            )
            assertTrue(field.identity.editorKey() in controller.state.value.invalidNumberFields)
            assertEquals(
                1_000,
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .defaultNumberScaled,
            )
            controller.save()
            assertEquals(0, writes)

            field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            controller.setDisplayPrecision(field, "3")
            assertEquals(
                "1,234",
                controller.state.value.numberDefaultTexts
                    .getValue(field.identity.editorKey()),
            )
            assertFalse(field.identity.editorKey() in controller.state.value.invalidNumberFields)
            assertEquals(
                1_234,
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .defaultNumberScaled,
            )

            field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            controller.setNumberDefault(field, "", ::parseLauncherNumber)
            controller.setDisplayPrecision(field, "0")
            assertEquals(
                "",
                controller.state.value.numberDefaultTexts
                    .getValue(field.identity.editorKey()),
            )
            assertFalse(field.identity.editorKey() in controller.state.value.invalidNumberFields)
            assertEquals(
                null,
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .defaultNumberScaled,
            )

            field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            controller.setNumberDefault(field, "1.2345", ::parseLauncherNumber)
            controller.setDisplayPrecision(field, "3")
            assertEquals(
                "1.2345",
                controller.state.value.numberDefaultTexts
                    .getValue(field.identity.editorKey()),
            )
            assertTrue(field.identity.editorKey() in controller.state.value.invalidNumberFields)
            controller.close()
        }

    @Test
    fun restoredFinalUnitAndTypeRetainExistingIdentityAtSubmission() =
        runBlocking {
            val submissions = mutableListOf<ActivityTemplateDraft>()
            val controller =
                existingController(
                    load = { templateWithField(numberField()) },
                    save = { _, _, draft, _ ->
                        submissions += draft
                        template(draft)
                    },
                )
            controller.awaitReady()
            val original =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()

            controller.updateDraft { draft -> draft.copy(fields = listOf(draft.fields.single().copy(unit = "m"))) }
            controller.updateDraft { draft -> draft.copy(fields = listOf(draft.fields.single().copy(unit = "km"))) }
            controller.updateDraft { draft ->
                draft.copy(fields = listOf(draft.fields.single().copy(type = CustomFieldType.TEXT)))
            }
            controller.updateDraft { draft ->
                draft.copy(
                    fields =
                        listOf(
                            draft.fields.single().copy(
                                type = CustomFieldType.NUMBER,
                                unit = "km",
                                displayPrecision = original.displayPrecision,
                                defaultNumberScaled = original.defaultNumberScaled,
                                isMainValue = original.isMainValue,
                            ),
                        ),
                )
            }
            controller.save()
            controller.awaitCommitted()

            assertEquals(
                original.identity,
                submissions
                    .single()
                    .fields
                    .single()
                    .identity,
            )
            controller.close()
        }

    @Test
    fun compatibleExistingFieldEditKeepsIdentity() =
        runBlocking {
            val controller = existingController(load = { templateWithField(numberField()) })
            controller.awaitReady()
            val existingIdentity =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .identity

            controller.updateDraft { draft ->
                draft.copy(fields = listOf(draft.fields.single().copy(name = "Length", displayPrecision = 2)))
            }

            assertEquals(
                existingIdentity,
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .identity,
            )
            controller.close()
        }

    @Test
    fun switchingAwayFromTimerClearsTheTargetWithoutWriting() =
        runBlocking {
            var writes = 0
            val controller =
                controller(
                    create = { draft, _, _ ->
                        writes++
                        template(draft)
                    },
                )
            controller.awaitReady()

            controller.setTimeTrackingMode(TimeTrackingMode.TIMER)
            controller.setTimerTargetSeconds("125")
            controller.setTimeTrackingMode(TimeTrackingMode.NO_LIVE_TRACKING)

            val draft = requireNotNull(controller.state.value.readyDraft())
            assertEquals(TimeTrackingMode.NO_LIVE_TRACKING, draft.timeTrackingMode)
            assertEquals(null, draft.timerTarget)
            assertEquals(0, writes)
            controller.close()
        }

    private fun CoroutineScope.controller(
        target: ActivityTemplateEditorTarget = ActivityTemplateEditorTarget.New,
        create: suspend (
            ActivityTemplateDraft,
            TemplateLibraryPlacement,
            Instant,
        ) -> ActivityTemplate = { draft, _, _ ->
            template(draft)
        },
    ): ActivityTemplateEditorController =
        ActivityTemplateEditorController(
            this,
            target,
            { null },
            create,
            { _, _, draft, _ -> template(draft) },
            { Instant.EPOCH },
        )

    private fun CoroutineScope.existingController(
        load: suspend (ActivityTemplateId) -> ActivityTemplate?,
        save: suspend (ActivityTemplateId, Long, ActivityTemplateDraft, Instant) -> ActivityTemplate =
            { _, _, draft, _ -> template(draft) },
    ) = ActivityTemplateEditorController(
        this,
        ActivityTemplateEditorTarget.Existing(ActivityTemplateId("template")),
        load,
        { draft, _, _ -> template(draft) },
        save,
        { Instant.EPOCH },
    )

    private suspend fun ActivityTemplateEditorController.awaitReady(
        predicate: (ActivityTemplateEditorLoad.Ready) -> Boolean = { true },
    ) = withTimeout(2_000) {
        state.first { (it.load as? ActivityTemplateEditorLoad.Ready)?.let(predicate) == true }
    }

    private suspend fun ActivityTemplateEditorController.awaitSaveFailure() =
        withTimeout(2_000) {
            state.first { it.save is ActivityTemplateEditorSave.Failure }.save as ActivityTemplateEditorSave.Failure
        }

    private suspend fun ActivityTemplateEditorController.awaitCommitted() =
        withTimeout(2_000) { state.first { it.save is ActivityTemplateEditorSave.Committed } }

    private fun template(draft: ActivityTemplateDraft) =
        ActivityTemplate(
            ActivityTemplateId("template"),
            draft.name,
            draft.shortComment,
            draft.timeTrackingMode,
            draft.timerTarget,
            com.alexandr5476.lifetracing.domain
                .StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            settings = draft.settings,
        )

    private fun templateWithField(field: ActivityTemplateField) =
        template(ActivityTemplateDraft("Walk", null, TimeTrackingMode.STOPWATCH, null)).copy(fields = listOf(field))

    private fun numberField() =
        ActivityTemplateField(
            ActivityTemplateFieldId("distance"),
            0,
            "Distance",
            CustomFieldType.NUMBER,
            "km",
            3,
            1_000,
            isMainValue = true,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
