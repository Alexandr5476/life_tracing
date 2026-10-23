# Template archive and Folder deletion accessibility (Slice S3)

Product v0.16 §§13/33 and archive clarifications, domain v0.10 §§4A/17–19,
schema v0.6, implementation clarifications and the design README remain
authoritative. This extends [S1 discovery](library_accessibility.md) and
[S2 organization](library_organization_accessibility.md). Existing Open/Edit,
Quick Start, search/filter, non-destructive organization and route ownership remain.

## Dialogs, identity and admission

Archive entries and confirmations identify Activity/Sequence kind, Template name,
section and ordinal. Folder actions identify the Folder name and browse ordinal;
move destinations use their inspected-list ordinal to distinguish equal names.
All dispatch retains the exact LibraryTemplateId.Activity/Sequence or FolderId.
Names, ordinals and translated descriptions are presentation only.

Archive entry and confirmation are separate native buttons. The existing message
explains archival and preservation of history/statistics. Folder inspection exposes
Loading, Failure with its relevant error and Retry, and empty/nonempty Content.
Loading has no confirmation. Empty confirmation still invokes DeleteEmptyFolder;
it never falls back to another disposition. Nonempty Content offers Move contents
as the safe option and a separate Delete contents branch. Destination selection is
the existing Move contents confirmation, with explicit root and contextual Folder
targets. Delete contents still requires its separate final destructive confirmation.
Dialog titles have heading semantics; body/destination content scrolls. Native
Material enabled state, keyboard activation and disabled colors remain in place.

A scoped lifecycle lease checks attachment, one-shot use, live foreground route,
current controller state reference, inspected Folder identity and selected stage.
Folder confirmations use the deletion admission conditions, independently of S2's
ordinary organization readiness. Stage changes, cancellation, retry, dismissal,
row disposal, route exit and accepted confirmation revoke obsolete callbacks.
Disposed/background archive dialogs cannot revive on route return.

The existing inspection generation is exposed as an ephemeral requestId. Every new
request gets a fresh identity, even when two inspections produce equal Content.
A superseding Refresh/Retry invalidates an active inspection. Success and failure
publications check the generation and current request inside the state update, so
a dismissed/replaced inspection cannot republish stale authorization. These are
presentation lifecycle changes; canonical controller and transactional writer
guards remain authoritative.

Archive dialogs also capture browseRequestId, exposing the existing browse read
generation. This invalidates a confirmation when a fast refresh conflates Loading
and returns structurally equal Content. Focus/cancellation never advance either
read counter or initiate a read; no additional generation was introduced.

## Focus and feedback

The existing S1/S2 focus owner stores the deletion invoker handle. Every new dialog
stage focuses Cancel after attachment, with a live route check. This covers
inspection Loading/Failure/Content, retry, destination selection and final deletion.
It never activates a control. Cancel/dismiss restores a surviving invoker;
canonical deletion or disposal falls back to Library Back. Closing archive has the
same restoration rule. A background Library cannot request dialog focus.

Relevant inspection errors have isolated polite live-region semantics. Confirmed
archive/Folder-removal outcomes include their target context. They appear only
after the existing writer succeeds and canonical refresh is ready. A pending
generation hides old success while the new canonical read is incomplete; a failed
attempt cannot lend its contextual outcome to a later successful metadata command.
Unrelated refresh/recomposition does not create a new completion announcement.
All new descriptions and outcomes have EN/RU resources. Combined scale tests use
Android font 200%, interface/text 90%/90% and 120%/130%, native focus/Enter, minimum
targets, separate footer actions and scrollable destination content.

## Changed files

- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryDestructiveControls.kt` (new): scoped native lifecycle dialogs, callbacks, stages, focus and outcomes.
- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryScreen.kt`: calls the scoped archive/deletion components and displays contextual outcomes.
- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryAccessibility.kt`: invoker/outcome presentation state and canonical-generation feedback.
- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryController.kt`: transient request identity and inspection invalidation/publication guard.
- `app/src/main/res/values/strings.xml` and `values-ru/strings.xml`: localized actions, headings, errors and outcomes.
- `app/src/androidTest/java/com/alexandr5476/lifetracing/library/LibraryDestructivePresentationTest.kt` (new).
- `app/src/androidTest/java/com/alexandr5476/lifetracing/library/LibraryScreenPresentationTest.kt`: localized compact-dialog expectations.
- `app/src/androidTest/java/com/alexandr5476/lifetracing/LibraryAccessibilityRouteTest.kt`: production route and physical-reopen cases.
- `app/src/test/kotlin/com/alexandr5476/lifetracing/library/LibraryControllerTest.kt`: inspection invalidation regressions.
- `.github/scripts/instrumentation-inventory.json`: thirteen additions, zero removals.
- `docs/implementation/library_accessibility.md`, `library_organization_accessibility.md`, `android_compatibility_gate.md`, and this new record.

## Exact new original-JUnit identities

Nine methods in
`com.alexandr5476.lifetracing.library.LibraryDestructivePresentationTest`:

- `duplicateArchiveTargetsRequireConfirmationAndDispatchEachTypedIdOnlyOnce`
- `archiveBusyRemovalFailureAndRouteExitCannotReplayAConfirmation`
- `inspectionLoadingFailureRetryAndDismissExposeContextWithoutDeleteAdmission`
- `emptyFolderConfirmationIsOneShotAndRemovedInvokerRecoversToBack`
- `nonemptyMoveUsesExplicitRootAndDuplicateDestinationIdsAndRevokesPreviousStages`
- `finalDestructiveConfirmationCancelsDismissesAndRejectsRefreshAndBackgroundCallbacks`
- `englishRussianLifecycleDialogsKeepIndependentTargetsAtCombinedScaleExtremes`
- `destructiveOutcomeWaitsForCanonicalRefreshAndFailureCannotLeakIntoAnotherCommit`
- `cancelAndSystemDismissAtEveryFolderStageNeverDispatchAMutation`

Four methods in `com.alexandr5476.lifetracing.LibraryAccessibilityRouteTest`:

- `accessibleArchiveReopensExactTemplatesWithHistoryPlansAndStatisticsPreserved`
- `emptyDeletionAndSafeMovesToRootAndFolderReopenCanonicalSubtrees`
- `deleteContentsArchivesBothKindsAndPreservesCanonicalHistoryAfterPhysicalReopen`
- `hiddenArchivedOccupancyAndStaleMoveDestinationRejectWithoutPartialWrites`

All S1/S2 and earlier identities remain. Current mandatory counts are **490 app,
627 data and 2 independent startup cases per API**.

New JVM methods in `com.alexandr5476.lifetracing.library.LibraryControllerTest`:

- `refreshInvalidatesFolderInspectionAndRetryGetsANewRequestIdentity`
- `supersedingRefreshRevokesAnInspectionStillReadingDurableOccupancy`

The tests use explicit prerequisites, native KeyboardInputFixture, semantic OnClick,
RequestFocus and Enter, and bounded waits/CompletableDeferred. There are no sleeps,
reflection, test-order dependencies or UI-side repository readers.

## Production route and physical reopen assertions

The new route cases use real MainActivity route ownership, the retained controller,
production writers and an isolated physical database. They close the Activity,
runtime graph and database connections before reopening the same file. Canonical
Library/Template/History/Statistics/Plan/runtime readers assert:

- Exact archived identities, active-catalog/pinned/search exclusion and canonical
  root catalog exclusion used by fresh launcher discovery.
- Configuration, semantic revision, source/Step provenance and StatisticsSeries
  identity, normalizing only lifecycle/organization properties for comparison.
- Unchanged prior Activity and Sequence history, Plan linkage and Series catalog.
- An explicitly seeded existing Stopwatch session/runtime remains identical when
  its source Template is archived; execution counts do not grow through UI actions.
- Empty Folder removal without unrelated effects.
- Move contents to root and a Folder, preserving direct membership and nested
  child/grandchild subtrees without flattening.
- Delete contents archives both Template kinds and removes only the intended
  hierarchy, preserving prior history and Series identities.
- Cancellation and disposition selection alone preserve all durable fixture facts.
- An empty inspection followed by newly placed hidden archived occupancy is
  rejected; fresh inspection reports nonempty. A destination removed after
  inspection is rejected. Neither failure changes a completion generation or
  partially moves/archives/deletes data, and Retry does not replay a write.

Bounded complete fixture facts supplement these canonical assertions for snapshots,
Field provenance, intervals, Executions, Plans, Statistics and active_session.
The reopen helper still requires a null session for ordinary cases; the archive
case explicitly supplies and compares its preexisting active session.

Existing mandatory LibraryRepositoryTest regressions remain untouched:
`folderDeleteFlowsMoveSubtreesArchiveContentsAndRollbackAtomically`,
`emptyFolderDeleteRejectsNewAndHiddenDurableContentsWithoutMutation`,
`archivedOnlyFolderContentsRemainHiddenButSupportBothDeleteDispositions`,
`folderSubtreeArchiveChunksSafeBindSetsAndPreservesIdentity`,
`largeFolderSubtreeArchiveRollsBackEarlierChunksWhenFolderDeleteFails`,
`folderMutationsRejectCyclesAndCorruptCycleReadsFailExplicitly`, and
`metadataOperationsKeepRevisionAndSeriesWhileArchiveRetainsPinAndRecent`.

## Local checks and execution boundary

On 2026-10-23 this exact command completed with **BUILD SUCCESSFUL** on the final
source/test state:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LibraryControllerTest' --tests '*MainActivityNavigationTest' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
```

`LibraryControllerTest` passed all 28 cases and `MainActivityNavigationTest` passed
all 26 cases: 54 tests, zero failures, errors or skips. App ktlint and Detekt passed.
Lint reported zero errors, 35 warnings and one hint. All AndroidTest sources compiled
and the test APK assembled. The thirteen new instrumented cases, including their
physical-reopen assertions, were **compiled, not executed** in this turn.

An intermediate check of the final test additions also passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
```

Earlier checks found formatting, condition-complexity and declaration-name lint
issues; they were corrected before the final successful command. Final review added
current-state focus guards, atomic inspection publication guards and browse-generation
invalidation for equal-content refreshes, then reran the complete focused command.

The initial command below failed Detekt style checks, with no testcase failure:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LibraryControllerTest' :app:compileDebugAndroidTestKotlin :app:detekt --continue --console=plain
```

An early execution of the combined command also failed Detekt before those style
corrections. This presentation-only command subsequently passed before the final
browse-generation regression was added; the combined final command above then
passed again with that regression included:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
```

```powershell
python -m unittest discover -s .github/scripts -p 'test_*.py'
git diff --check
```

All 17 runner/inventory host tests passed. Their device command output is mocked;
they do not launch a device. A read-only HEAD inventory comparison confirmed thirteen
additions, zero removals, exact source methods for every addition and 490/627/2
app/data/startup totals. Whitespace validation passed. No managed-device/emulator
command, Git mutation or GitHub operation was run.

No domain command, repository, DAO, transaction, schema, migration or durable state
changed. SQLite safe-bind count stays 900; archive UPDATE chunks reserve one bind
for deletedAtMs and contain at most 899 Folder IDs. The existing writer derives the
subtree once, chunks both Template-kind updates and deletes within its outer
transaction. No per-Template update loop, oversized IN query, repeated subtree scan
or UI-side authorization was added.

Presentation work uses already loaded rows, inspection options and metadata. It
adds no database reads, History scan, catalog hydration or persistent focus/stage
state. The ephemeral request token uses the existing inspection generation and
does not add polling or reads. Runtime behavior and both editor implementations
are unchanged; Activity Template Editor Save/Conflict/Discard/Field/Category
accessibility and Sequence Editor accessibility remain deferred.

The initial turn compiled the thirteen new instrumentation cases and canonical
physical-reopen assertions. Subsequent Controller execution is recorded below.
Controller owns unfiltered API 26/31/33/35/36,
independent startup and exact-head GitHub evidence. Compilation is not device PASS.
Automated semantics evidence does not constitute manual TalkBack or Switch Access
certification. No manual assistive-service validation was performed.

## Controller API 35 attempt 2 and scoped repair

Controller's canonical attempt 2 at head
`0df9587ab2492b879b320cc9ec7dd788fdb08f31` completed 490 app cases: 484 passed
and six failed. All eleven LibraryAccessibilityRouteTest cases passed, including
all four S3 canonical physical-reopen journeys. No repository testcase failure
was reported. The original app report is
`.runtime/check-runs/wf-2933485f29755d0f9317fdc0a813f2f8/S3-e1f25278deb9479bb779ce680fe336e9/attempt-2/reports/app/TEST-pixel2Api35-_app-.xml`.

The six failures exposed bounded fixture and native-dialog presentation issues:

- Four tests invoked KeyboardInputFixture's default single-root selector after a
  dialog opened. They now supply its Cancel control explicitly, selecting the
  actual dialog Compose owner without changing the shared fixture's API.
- A replacement dialog was dismissed before its native window gained focus.
  All five system-dismiss paths in the destructive fixture now assert Cancel's
  focus and wait on that owner's hasWindowFocus before real Espresso system Back.
  The wait is bounded; there are no sleeps or simulated dismissal callbacks.
- The compact Russian fixture used Activity resources for dialog expectations.
  The native dialog also mixed Library-local body strings with Activity-local
  title/button strings. Every lifecycle-dialog slot now retains the Library's
  Context, Configuration and Density, and the compact fixture consistently
  expects its configured locale. The existing assertions and typed payloads remain.
- At actual enlarged typography, the two disposition choices and Cancel overlapped
  in the dialog footer. Move contents measured y=918..1182 while Cancel measured
  y=1115..1280 in the first English maximum-scale scenario. The non-mutating
  disposition choices now live in the scrollable body; Cancel stays in the footer.
- The existing LifeTracingAccessibleTargets policy is reapplied inside native
  dialog content so dialog defaults cannot reduce reserved target space. The
  scaling test scrolls to the body action and retains minimum-size, non-overlap,
  native focus/Enter and zero-mutation assertions, with bounds diagnostics.

Only the scoped authorization was used for device feedback:

```powershell
D:\controller\.runtime\agent-tools\repair-probe.cmd
```

Four REAL API 35 probes used the same six original failing identities:

| Probe tree | Result | Evidence |
| --- | --- | --- |
| 1 | 5 PASS, 1 FAIL | Remaining scale assertion identified footer overlap. |
| 2 | 5 PASS, 1 FAIL | Diagnostic rectangles measured the 67-pixel vertical overlap. |
| 3 | 5 PASS, 1 FAIL | Scrollable-body layout advanced to the minimum-target assertion. |
| 4 | **6 PASS, 0 FAIL** | Native dialog target policy retained; all scale combinations passed. |

Passing methods in `com.alexandr5476.lifetracing.library.LibraryDestructivePresentationTest`:

- `finalDestructiveConfirmationCancelsDismissesAndRejectsRefreshAndBackgroundCallbacks`
- `emptyFolderConfirmationIsOneShotAndRemovedInvokerRecoversToBack`
- `englishRussianLifecycleDialogsKeepIndependentTargetsAtCombinedScaleExtremes`
- `inspectionLoadingFailureRetryAndDismissExposeContextWithoutDeleteAdmission`
- `nonemptyMoveUsesExplicitRootAndDuplicateDestinationIdsAndRevokesPreviousStages`

Also passing:
`com.alexandr5476.lifetracing.library.LibraryScreenPresentationTest.russianDestructiveLifecycleIsExplicitAtCompactWidth`.

The final probe completed in 149.2 seconds. Its immutable log and original-JUnit XML
are under
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/f95ef2e9aa334c278db45e7e2f0b8d56/tree-4/attempt-1/`:
`01.log` and `reports/app/TEST-pixel2Api35-_app-.xml`.

Post-repair local verification passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
```

Gradle completed successfully in 2m 23s. Lint reported zero errors, 35 warnings
and one hint. Instrumentation APK assembly and both static checks passed.

No testcase identity was added, removed or weakened in this repair. Counts remain
490/627/2, with zero removals against HEAD. The repair changes dialog presentation
and fixture synchronization only; controller admission, typed dispatch, readers,
transaction boundaries, safe binds and persistence remain unchanged. A scoped
six-case API 35 PASS does not replace Controller's full repaired-tree matrix,
independent startup or exact-head GitHub gates.
