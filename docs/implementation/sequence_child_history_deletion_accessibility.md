# Accessible Sequence child History deletion (S4)

## Authority and boundary

This follows the supplied S4 contract, accepted S1 admission, S2/S2C1 canonical
presentation/focus, S3/S3C1 committed-result recovery, product v0.16 §16,
domain v0.10 §13, schema v0.6 §§13–17, and the implementation clarifications
(especially child-deletion tombstones, structural removal, occurrence completion
reasons, 1-based Repeat iteration and 10,000-occurrence cardinality). As recorded
in the accepted S2/S3 notes, there is no separately tracked Parent Contract file.
The supplied task contract and those implementation notes provide that context.
No unresolved semantic contradiction was found: the tombstone clarification
already resolves the older product/domain shorthand about deleting child data.

No command, transaction, domain policy, schema, migration, Plan policy or
Statistics reader changes. `deleteChildHistory` remains the only writer.

## Exact native review

`SequenceHistoryChildDeletion.kt` resolves the selected occurrence once from the
current canonical detail and `SequenceChildHistoryDeletionCommand`. It verifies
the optimistic token, completed/retained status, displayed completed child and
mutation-fact child ID, and matching frozen Activity snapshot. Presentation never
uses titles, clocks, ordinals or the old display descriptor as target authority.

The heading, occurrence/root identity, selected child identity and consequence
explanation are independent native focus targets. EN/RU strings show full root,
occurrence, child and Activity snapshot IDs, frozen title/position, source Step
and Repeat node IDs/iteration, and runtime-added provenance when present.
Configuration and provenance are distinct from actual historical child results.

Consequences describe logical deletion and durable retained facts, a visible
Deleted execution occurrence, unchanged Sequence boundaries/date/intervals/values/
duration, retained Sequence/global Statistics, excluded child Activity Statistics,
unchanged other children, retained Templates/snapshots, and unchanged fulfilled
Plan linkage/time. No structural gap, physical purge or restoration is promised.

Material Cancel and specific-child Confirm use the existing S1 callbacks. A
separate native stage identity also binds exact canonical/proposal references.
Heading landing borrows the existing read-only navigation lease, checks native
attachment/window and current authority again after its frame, and consumes old
History recovery debt only after successful focus. It never dispatches a command.
Controls wrap and the selected-target surface scrolls vertically at larger scales.
In-flight text and writer failures use existing live-region status semantics;
Confirm, Cancel and native Back are disabled during the writer.

Missing displayed child, noncompleted child, mismatched child identity/snapshot,
nonterminal or unavailable aggregate, hidden/paused/disposed/replaced session and
pending writer cannot expose an enabled deletion action. A legacy identity-test
fixture formerly omitted its displayed child; it now establishes that prerequisite
while retaining its exact duplicate-title/time/position assertions. The new
fail-first test explicitly requires missing-child rejection.

## Recovery and complexity

S1 owns all actions and Back. Cancel/Back consume idle confirmation without a
write; an in-flight writer blocks both. Old controls cannot borrow a replacement
publication. Writer failure permits an explicit current retry; stale rejection
clears review and reloads. `CHILD_DELETION` retains its existing committed meaning:
postcommit read failure announces successful deletion and failed reload, while
Retry reads only canonical detail and never replays the writer. Existing route
session generation delivery remains exactly-once across Back/recreation.

Canonical reload renders the existing visible tombstone with frozen configuration
and provenance, omits the child summary/actual Fields/deletion action, and retains
policy-supported timing and structural entry. Existing nested focus recovery
lands on an attached safe target when child controls disappear.

The admission identity cache indexes occurrence references once per canonical
instance; repeated visible-button/semantics admission uses O(1) typed lookup.
It shares the canonical objects, never copies child history. Confirmation resolves
one selected target once and does not hydrate another list. The old unconditional
all-occurrence descriptor map is now built only by structural review. Idle detail
remains the S2 lazy list. No repository reads, N+1 access or global scans were added.

## Executable evidence (device execution remains Controller-owned)

New JVM cases cover equal-looking exact typed target selection, frozen Repeat and
runtime-added provenance, terminal statuses/ineligible states, missing/mismatched
child/snapshot, stale token, and repeated admission without canonical rescans.
The missing-child regression was observed failing before the production change.

Six new `HistoryScreenPresentationTest` identities cover independently focusable
review, exact command assertions, Cancel/system Back, held pointer-stage transfer,
keyboard/semantics confirmation, ordinary failure/current retry, stale/new review,
hide/PAUSE/disposal/session replacement and canonical tombstone/focus/timing
recovery. Existing in-flight and postcommit Retry tests now additionally assert
Cancel disabled and family-correct read failure followed by a canonical tombstone.
The S2 10,000-row test retains its exact selection/zero-write assertions; its Cancel
now explicitly scrolls through the longer selected-target consequence text.

`sequenceChildDeletionSessionAndCommittedRefreshGateSurviveActivityRecreation`
reuses the real repository/session fixture with deterministic writer gates,
verifies retained identity, blocked Back, one commit/generation delivery across
two recreations, and a tombstone after reopening the native route. Its fixture
cancels and joins its repository scope before release.

Two new `PrimaryExecutionJourneyTest` identities traverse production History,
exact occurrence, native review/Cancel/keyboard Confirm, existing repository writer,
canonical reload and physical database close/reopen. Both are linked to a fulfilled
Plan, retain frozen Repeat identity and nonempty root/child values, and compare
independently captured root/occurrences/children, immutable database tables,
canonical History/Daily, Sequence/global Statistics and complete Plan projections.
The timed EN journey has a durable child pause and another performed child; it
runs reduced 90% interface/text scales. The RU journey uses 120% interface, 130%
app text and 150% system font scaling, ENDED_EARLY No-live, explicitly verifies no
announced start and Missing duration, injects a postcommit canonical read failure,
then proves read-only Retry and retained missing durable start/duration after reopen.
The existing fixture closes/joins graph work before database closure. Writer
counts and exact typed command/generation assertions stay independent of titles.
All nine new instrumentation identities are in the mandatory inventory.

Existing `SequenceHistoryCommandRepositoryTest` evidence checked against S4:

- `deleteChildHistoryPersistsCanonicalTombstoneWithoutChangingSequenceOrPlanFacts`
  and `deletionPreservesChildPausesAndValuesAndSequenceTimeline`: exact permitted
  durable changes, retained values/pauses, unchanged Plan and root statistics.
- `endedEarlyNoLiveChildDeletionKeepsDurationMissing`: no fabricated duration/start.
- `invalidChildDeletionTargetsAndStatesFailWithoutWrites`,
  `persistedTargetIntegrityMismatchesFailWithoutFurtherWrites`, and
  `liveAndActiveSessionOwnedRootsRejectChildDeletionWithoutWrites`: invalid pairs,
  repeated deletion, stale token, live states and active-session ownership.
- `childDeletionRollsBackRootAndOccurrenceWhenChildWriteFails` and
  `incoherentFulfilledPlanRejectsChildDeletionWithoutWrites`: transaction rollback.
- `readerRejectsMismatchedCompletedAndTombstoneChildStates`: canonical consistency.
- `rootTimingCorrectionAfterDeletionRetainsTombstoneAndChildTimingCorrectionRejectsIt`,
  `leaveGapOnVisibleTombstonePreservesOriginalChildDeletionMetadata`, and
  `deletionGraphLoadingRemainsBatched`: timing/structural symmetry and bounded reads.

These repository tests are existing device evidence sources, not newly executed
DAO-only substitutes for the two physical-reopen production journeys. Controller
owns device leases/execution, cross-API checks, full Parent checks/coverage and
independent exact-head GitHub CI. Manual accessibility-service certification is
deferred. APK compilation establishes source/API compatibility, not device PASS.

## Local verification (2026-11-17)

The final combined invocation completed BUILD SUCCESSFUL:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :domain:test :data:testDebugUnitTest :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain --max-workers=2
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The focused app run has 217 tests, zero failures/errors/skips. Unchanged domain
and data tasks were UP-TO-DATE; their reports contain 245 and 36 tests respectively,
also with zero failures/errors/skips. App ktlint, Detekt, Android lint, production
APK assembly, and app/data AndroidTest source/APK packaging passed. An identical
cached Gradle invocation through `cmd.exe /d /s /c` independently confirmed exit
code 0 (184 tasks, 183 up-to-date), avoiding PowerShell's redirected Java stderr
wrapper status. Earlier formatting/compilation issues were corrected before this
successful verification; no local check failure remains.

The fail-first missing-child regression reproduced the original admission bug.
The runner's 74 unit tests passed using mocked device output. Direct inventory
comparison matches all methods in HistoryScreenPresentationTest (40),
SequenceHistoryPresentationTest (30), MainActivityRouteSessionTest (30) and
PrimaryExecutionJourneyTest (57), with no duplicate inventory identities. EN/RU
XML parsing and unique resource-name checks passed. `git diff --check` passed.
These checks establish local patch readiness; no emulator/device task, full Parent
coverage gate or GitHub CI was run in this turn.

## API 33 cancellation test repair (2026-11-17)

Controller's canonical API 33 app run at `85ee0ca17f8ef64fe3135b4a622ad833b45adcf0`
reported one failure among 770 tests. In
`destructiveSurfacesIdentifyOccurrenceAndCloseGapReviewHidesDeletedChildFacts`,
the legacy pointer click did not scroll to Cancel after S4 expanded the identity
and consequence explanation. The next assertion tried to scroll the detail list
while the deletion confirmation was still open.

The test now scrolls to the native child-deletion Cancel control and verifies it
is displayed before clicking. Before entering structural review, it asserts that
the confirmation heading is gone, the canonical detail list is displayed, the
deletion proposal is cleared, and both writer count and refresh generation remain
zero. All downstream identity, interval-placement and deleted-child exclusion
assertions remain intact. Sibling child-deletion cancellation tests already scroll
to Cancel or use Back. Production code and durable semantics are unchanged by
this repair.

The authorized `repair-probe.cmd` executed the original failing testcase on API 33:
one test passed, zero failures/errors/skips (151.2 seconds). A subsequent class
probe selected `HistoryScreenPresentationTest` through `--scope-file`: all 40
tests passed, zero failures/errors/skips (410.8 seconds), exercising the shared
fixture and adjacent structural-review assertions. Reports are recorded under
`.runtime/repair-probes/wf-66d1e26240c0559b97e60b9bf4b5c7fe/fcbbcb45fcdc4d279b61e0fca6aa0b3e/`,
with the isolated run in `tree-1/attempt-1` and class run in `tree-2/attempt-1`.

The repair also passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --console=plain --max-workers=2
git diff --check
```

These scoped device probes establish the cancellation repair; they do not replace
the full canonical API 33 rerun, other API gates, or independent exact-head GitHub
CI, which remain Controller-owned.
