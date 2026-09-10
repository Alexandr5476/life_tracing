package com.alexandr5476.lifetracing

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.ActivityCommandRepository
import com.alexandr5476.lifetracing.data.persistence.DailyReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.LauncherDurableCommand
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.launcher.StartActivityRoute
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSession
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSessionOwner
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class ComposedStartActivityRouteIntegrationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun preprimedTimedActivityIsConsumedOnceByTheComposedRouteAcrossHostRecomposition() {
        assertImmediateComposedLaunch(sequence = false)
    }

    @Test
    fun preprimedSequenceIsConsumedOnceByTheComposedRouteAcrossHostRecomposition() {
        assertImmediateComposedLaunch(sequence = true)
    }

    @Test
    fun preprimedNoLiveMainValueWaitsForTheRenderedQuickEditorAndCompletesOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.now()
        val suffix = System.nanoTime().toString()
        val title = "S5C2 no-live $suffix"
        val fieldName = "S5C2 value $suffix"
        val authoring = TemplateAuthoringRepository.create(context)
        val template =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    title,
                    null,
                    TimeTrackingMode.NO_LIVE_TRACKING,
                    null,
                    fields =
                        listOf(
                            ActivityFieldDraft(
                                DraftIdentity.New("main"),
                                0,
                                fieldName,
                                CustomFieldType.NUMBER,
                                displayPrecision = 0,
                                defaultNumberScaled = 7,
                                isMainValue = true,
                            ),
                        ),
                ),
                createdAt = now,
            )
        val id = LibraryTemplateId.Activity(template.id)
        val fixture = routeFixture(context, now)
        val generation = mutableIntStateOf(0)
        try {
            fixture.session.primeInitialSelection(id)
            composeTestRule.setContent {
                key(generation.intValue) {
                    LifeTracingTheme {
                        StartActivityRoute(
                            fixture.session,
                            onBack = { fixture.backCalls.incrementAndGet() },
                            onCommitted = { fixture.commitCalls.incrementAndGet() },
                        )
                    }
                }
            }

            composeTestRule.waitUntil(5_000) { fixture.session.interaction.quickEditor != null }
            composeTestRule.onNodeWithText(fieldName).performScrollTo().assertIsDisplayed()
            assertEquals(1, fixture.selectCalls.get())
            assertEquals(0, fixture.writerCalls.get())
            assertEquals(0, fixture.commitCalls.get())
            assertNull(fixture.session.interaction.pendingSelectionId)
            assertTrue(fixture.library.getRecent(100).none { it.id == id })
            assertTrue(
                fixture.daily(now).completedHistory.none {
                    it is CompletedActivityHistoryRoot &&
                        it.title == title
                },
            )

            composeTestRule.runOnUiThread { generation.intValue++ }
            composeTestRule.waitForIdle()
            assertEquals(1, fixture.selectCalls.get())
            assertEquals(0, fixture.writerCalls.get())

            composeTestRule
                .onNodeWithText(composeTestRule.activity.getString(R.string.launcher_complete))
                .performScrollTo()
                .performClick()
            composeTestRule.waitUntil(5_000) { fixture.commitCalls.get() == 1 }

            composeTestRule.runOnUiThread { generation.intValue++ }
            composeTestRule.waitForIdle()
            assertEquals(1, fixture.selectCalls.get())
            assertEquals(1, fixture.writerCalls.get())
            assertEquals(1, fixture.commitCalls.get())
            assertEquals(0, fixture.backCalls.get())
            assertTrue(fixture.commands.single() is LauncherDurableCommand.CompleteNoLive)
            assertEquals(
                1,
                fixture.daily(now).completedHistory.count {
                    it is CompletedActivityHistoryRoot &&
                        it.title == title
                },
            )
        } finally {
            fixture.close(now)
        }
    }

    private fun assertImmediateComposedLaunch(sequence: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.now()
        val suffix = System.nanoTime().toString()
        val authoring = TemplateAuthoringRepository.create(context)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("S5C2 activity $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = now.minusSeconds(1),
            )
        val id =
            if (sequence) {
                LibraryTemplateId.Sequence(
                    authoring
                        .createSequenceTemplate(
                            SequenceTemplateDraft(
                                "S5C2 sequence $suffix",
                                null,
                                nodes =
                                    listOf(
                                        SequenceNodeDraft.Step(
                                            ActivityStepDraft(
                                                DraftIdentity.New("step"),
                                                0,
                                                StepActivityDraft.FromTemplate(activity.id),
                                            ),
                                        ),
                                    ),
                            ),
                            createdAt = now,
                        ).id,
                )
            } else {
                LibraryTemplateId.Activity(activity.id)
            }
        val fixture = routeFixture(context, now)
        val generation = mutableIntStateOf(0)
        try {
            fixture.session.primeInitialSelection(id)
            composeTestRule.setContent {
                key(generation.intValue) {
                    LifeTracingTheme {
                        StartActivityRoute(
                            fixture.session,
                            onBack = { fixture.backCalls.incrementAndGet() },
                            onCommitted = { fixture.commitCalls.incrementAndGet() },
                        )
                    }
                }
            }
            composeTestRule.waitUntil(5_000) { fixture.commitCalls.get() == 1 }

            composeTestRule.runOnUiThread { generation.intValue++ }
            composeTestRule.waitForIdle()

            assertEquals(1, fixture.selectCalls.get())
            assertEquals(1, fixture.writerCalls.get())
            assertEquals(1, fixture.commitCalls.get())
            assertEquals(0, fixture.backCalls.get())
            assertNull(fixture.session.interaction.pendingSelectionId)
            assertTrue(
                if (sequence) {
                    fixture.commands.single() is LauncherDurableCommand.StartSequence
                } else {
                    fixture.commands.single() is LauncherDurableCommand.StartActivity
                },
            )
            val active = requireNotNull(fixture.daily(now).active)
            assertEquals(sequence, active is DailyActive.Sequence)
        } finally {
            fixture.close(now.plusSeconds(1))
        }
    }

    private fun routeFixture(
        context: Context,
        now: Instant,
    ): RouteFixture {
        val library = LibraryRepository.create(context)
        val live = LiveSessionRepository.create(context)
        clearLiveSession(live, now)
        val commands = ActivityCommandRepository.create(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val writerCalls = AtomicInteger()
        val selectCalls = AtomicInteger()
        val durableCommands = java.util.Collections.synchronizedList(mutableListOf<LauncherDurableCommand>())
        val controller =
            StartActivityController(
                scope,
                { emptyList() },
                { emptyList() },
                { emptyList() },
                { LibraryContents(emptyList(), emptyList(), emptyList()) },
                { id -> withContext(Dispatchers.IO) { library.getLaunchTarget(id) } },
                {},
                { withContext(Dispatchers.IO) { live.getActiveSession() != null } },
                { command ->
                    writerCalls.incrementAndGet()
                    durableCommands += command
                    withContext(Dispatchers.IO) {
                        executeLauncherCommand(command, commands, library)
                    }
                },
                {},
                FixedWallClock(now),
                { ZoneOffset.UTC },
                PreflightScheduler { _, _ -> PreflightHandle {} },
                initialLiveConflict = { target ->
                    withContext(Dispatchers.IO) {
                        library.hasLiveLaunchConflict(target.id, target.revision)
                    }
                },
                onSelectObserved = { selectCalls.incrementAndGet() },
            )
        val owner = StartActivityRouteSessionOwner()
        val session = owner.acquire { controller }
        return RouteFixture(
            scope,
            live,
            library,
            DailyReadRepository.create(context, CurrentZoneIdProvider { ZoneOffset.UTC }),
            owner,
            session,
            writerCalls,
            selectCalls,
            AtomicInteger(),
            AtomicInteger(),
            durableCommands,
        )
    }

    private data class RouteFixture(
        val scope: CoroutineScope,
        val live: LiveSessionRepository,
        val library: LibraryRepository,
        val dailyReader: DailyReadRepository,
        val owner: StartActivityRouteSessionOwner,
        val session: StartActivityRouteSession,
        val writerCalls: AtomicInteger,
        val selectCalls: AtomicInteger,
        val backCalls: AtomicInteger,
        val commitCalls: AtomicInteger,
        val commands: List<LauncherDurableCommand>,
    ) {
        fun daily(now: Instant) = dailyReader.getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))

        fun close(at: Instant) {
            owner.release(session)
            clearLiveSession(live, at)
            scope.cancel()
        }
    }

    private class FixedWallClock(
        private val value: Instant,
    ) : WallClock {
        override fun now(): Instant = value
    }

    private companion object {
        fun clearLiveSession(
            live: LiveSessionRepository,
            at: Instant,
        ) {
            when (live.getActiveSession()?.kind) {
                ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(at)
                ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(at)
                null -> Unit
            }
        }
    }
}
