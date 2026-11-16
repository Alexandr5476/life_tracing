# Accessible Sequence historical timing correction (S3)

## Authority and scope

This slice follows the supplied S3 contract, product v0.16 §§16/24.4,
domain v0.10 §13, database v0.6 §§14–17, and the implementation clarifications.
As already recorded in the S2 implementation notes, no separate Parent Contract
file is tracked here. Accepted S1 admission/session ownership and S2/S2C1 canonical
presentation, read-only navigation leases, and native focus patterns remain the
boundaries. Domain policies, local-time resolution, repository transactions,
optimistic tokens, schema, snapshots, Plan and Statistics semantics are unchanged.

## Native editing and review

`SequenceHistoryTimingPresentation.kt` supplies bounded lazy editor/review lists.
Every item is keyed by the existing typed target inside the exact root session.
Labels include timestamp kind, root execution identity/title, occurrence identity,
visible historical position/title, Repeat/runtime-added context, applicable child
execution identity, and interval identity/kind/owner context. Titles, clocks and
ordinals never identify a correction command. Draft membership still comes only
from `SequenceHistoryProposalBuilder.timingDraft`: no new endpoints are invented
for No-live children, unperformed rows, child-deletion tombstones or absent children.
The canonical reader continues excluding structurally removed occurrences.

Inputs retain S1's independent native SetText publication cursor. Sequence-root
original ZoneId remains the correction-input zone for all endpoints; a different
child recorded zone is identified separately. Both zones are also associated with
the native input description for direct keyboard/accessibility focus. The existing resolver preserves
actual Instants, endpoint-specific offsets, seconds and persisted fractions.
An edit clears only that endpoint's offset authority. DST alternatives use native
radio selection semantics and include the exact timestamp identity, first/second
occurrence and UTC offset. Malformed syntax, gaps and ambiguity associate a native
error with the builder's exact diagnostic target. A stale selected overlap offset
has explanatory wording. Global graph/writer failures have no field-error target.
Edits, offset selection, new review, cancellation and reload retire stale diagnostics.
The error-focus action only scrolls/focuses; it cannot submit Review or write.

Changed review consumes the builder's immutable proposal directly. Each exact
fact says Before and After in EN/RU and includes identity/context and reversible
ISO zoned endpoint text. Child previews use the child's recorded zone; Sequence
facts use the root zone. Actual offsets and fractions survive repeated-hour clocks.
The shared structural timestamp formatter also now includes exact zone/offset
context; its equal-clock regression checks both original Instants round-trip.
Preview text is never parsed to construct a correction. No-change remains a
read-only result with no Save, writer invocation or refresh generation.

Sequence overlap wording describes Sequence intervals and union-based active
duration. Its warning is a reachable native focus target inside the review list;
critical Cancel/Proceed/Save actions remain outside that list and wrap. Approval
still belongs to the current proposal and only enables explicit Save. Warning and
confirmation footer controls have separate Compose identities so an in-progress
native press cannot transfer from Proceed to Save. A deterministic instrumentation
regression holds Proceed down while native approval changes the stage, then
releases it and requires a separate Save activation. This test is compiled here;
its runtime execution remains Controller-owned. Graph validation remains in the
existing command transaction. Retained controls continue
using S1 publication, foreground/session, phase and mutation-lock admission.

## Navigation, focus and complexity

A native menu selects timestamp, root-boundary, visible-occurrence, child-owner or
interval ordinal navigation. It resolves an existing typed target through an index;
an absent/unchanged endpoint reports that no target is available. Empty owner
sections are explicitly labeled and disabled, including a root-only graph after
structural removal; no invalid 1–0 range is offered. Child navigation
lands on completion when no editable start exists. Owner ordinals address the
canonical visible collections and are never durable identity or historical edits.
Editor/review root, middle and late targets are reachable without thousands of Tabs.
F6 focuses the fixed navigation control; Shift+F6 focuses Review/Proceed/Save from
any timing pane. These shortcuts only request native focus, cancel pending jump
focus, and recheck the current lease. Enter remains explicit S1 activation. Cancel
is the adjacent native action and system Back retains S1 admission. EN/RU shortcut
help lives in the bounded navigation popup so it does not consume the form viewport.

Navigation borrows only `SequenceHistoryNavigation` read-only authority, including
synchronous pause revocation, disposal, exact publication/canonical/session checks,
queued-work cancellation and rechecking the initiating lease after a frame wait.
Native focus properties on inputs, fixed actions and read-only targets use its
unobserved authority read. The native regression captures a fixed action focus
request while another control owns focus, rejects it during and after a hidden
round trip, and admits a fresh current request. Old requests cannot
borrow a replacement stage's lease. Stage landing consumes prior recovery debt;
shared History recovery rechecks that debt after its frame wait, preventing an old
Back landing from overriding the new editor/review target. Focus never dispatches
a mutation. Existing Activity/structural focus and S2C1 recovery remain covered.

There is one bounded vertical list in each timing stage; there is no enclosing
unbounded vertical scroller. Material's navigation menu is a bounded popup. Item
keys and native input nodes survive ordinary publications. Canonical occurrence and
interval references are indexed once per stage; labels do not search collections.
Owner lookup is constant time. Only visible fields/review facts compose, and action
rows reflow under EN/RU, app scale extremes and increased system font scaling.

The previous `Map + entry` edit copied every endpoint. The non-durable timestamp
table now shares an immutable balanced tree and fixed target index: construction
and traversal are O(N), lookup/update O(log N), and an edit allocates only one tree
path. Old publications remain immutable; no edit chain or aggregate history is
accumulated. Draft equality is publication identity so StateFlow does not compare
all N values on every keystroke. Review still resolves the complete graph once.
No global History/Statistics scan or per-child/interval read was added. Existing
canonical hydration uses batched snapshot reads; transaction/query ownership and
the documented overlap preflight performance exception remain unchanged.

## Completion and recovery

Non-durable committed-result metadata distinguishes timing correction, child
deletion and structural removal. The UI distinguishes saving, committed/reloading,
committed/reloaded and committed/read-failed. A canonical read failure after commit
is never presented as a writer failure. Retry still calls only `getSequenceDetail`.
The existing route-session generation delivery gate remains unchanged. Stale
rejection clears proposals and requires fresh canonical review; invalid, cancelled,
unchanged and rejected proposals do not advance the generation. In-flight and
retained callback admission remain S1's responsibility.

## Executable evidence and limits

New JVM evidence covers every target type's diagnostic, independent DST offset
authority, stale offsets/gaps, No-live/unperformed/tombstone endpoint exclusion,
global graph rejection versus local errors, postcommit read-only recovery,
60,000-endpoint immutable drafts, owner navigation and exact preview round-trips.
Existing History admission/navigation/proposal and MainActivity navigation tests
remain in the focused run.

New native tests encode field error association, error-focus recovery without
Review, selected DST roles and exact endpoint context, stage focus and old Save
rejection, and bounded 10,000-occurrence editor/review navigation, including explicit Repeat
and runtime-added labels on independently keyed equal-clock rows. Scale coverage
includes EN/RU, 90/120% interface, 90/130% app text and 150% system font scaling,
including child previews in a different recorded zone and focus-only F6 pane
movement without Review or repository dispatch. Inventory entries match
all test methods in the three changed instrumentation classes.

Two new production `MainActivity` History journeys use real existing repositories:

- Plan-linked timing: no-change, cancellation, local invalidity, transactional graph
  rejection and a competing stale token preserve their independently captured facts.
  Fresh review changes root date/end plus occurrence/child/interval start, approves
  actual active overlap, preserves child pauses/frozen configuration, and verifies
  active union versus child sums. Physical close/reopen compares canonical History,
  Daily, Sequence/global Statistics and the same fulfilled Plan, including corrected
  `fulfilledAt == endedAt` and exact millisecond endpoints.
- Berlin repeated hour and No-live: native second-occurrence selection commits the
  exact `.456` endpoint through the existing writer. An injected postcommit canonical
  read failure performs exactly one write; native Retry only reads. Physical reopen
  verifies canonical History/Daily/Statistics and missing child start/duration.

The test store exposes the existing Sequence command repository on its already
owned database. It adds no production persistence. Fixture shutdown cancels and
joins graph work before database closure, including outstanding non-cancellable
repository reads, following S2C1.

Local JVM/static/package checks passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --continue --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest :domain:test :data:testDebugUnitTest --continue --console=plain
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt :app:lintDebug --continue --console=plain
```

Data ktlint and Detekt tasks also completed successfully in an earlier combined
invocation. That invocation had app failures subsequently corrected by the exact
preview assertions and deferred recovery check described above.
Focused app reports contain 207 tests, domain 245 and data 36, with no failures,
errors or skips. EN/RU XML/unique resources, instrumentation inventory consistency
and `git diff --check` pass. The initial ordinary-timestamp zone/offset regression
was observed failing before the formatter change. AndroidTest APK compilation
checks source/API compatibility; it does not prove native runtime or persistence
journeys passed on a device.

Controller check applicability: `ci-fast` has focused local evidence only;
`androidtest-apks` has local compilation/package evidence. Parent `full`,
`gmd-full`, cross-API device gates and independent exact-head GitHub CI remain
Controller-owned. Initial implementation checks did not execute device journeys;
the authorized repair probe below adds targeted runtime evidence.
Manual accessibility-service certification remains deferred.

## API 33 canonical-test repair (2026-11-16)

Controller's canonical `gmd-api33-app` execution at head
`694498c962158ab0c0d20e084c79c3e7963cad13` ran 754 tests with 11 failures.
Ten failures were obsolete test navigation: `performScrollTo()` requires a
scrollable ancestor, while timing Cancel/Review/Proceed/Save now intentionally
remain in fixed controls outside the bounded lazy list. Tests now assert these
controls are displayed before activation. Scrolling remains explicit for actual
list items and the deferred structural-review surfaces. Writer rejection messages
are checked in fixed review content; stale-token recovery waits for canonical
detail and scrolls to the existing `sequence:issue` item.

The remaining failure selected a repeated-hour offset by offset text alone,
matching both root end and occurrence entry. The production native labels already
distinguished them. The journey now selects the root-end kind and exact execution
identity as well as the offset, asserts native selected state, and proves other
timestamp drafts are unchanged. Sibling timing DST selectors also include the
root-end context. No production behavior, writer, resolver or persistence changed
in this repair; all downstream duration, Plan, refresh and reopen assertions remain.

The authorized `repair-probe.cmd` returned REAL PASS for all 11 original failing
testcases on API 33. Evidence is under Controller's
`repair-probes/wf-66d1e26240c0559b97e60b9bf4b5c7fe/2f50240d2b714fc2bfefd7281d315c02/tree-1/attempt-2`.
Attempt 1 reached zero tests because UTP could not create its instrumentation
launch script under `/sdcard` (Operation not permitted); Controller automatically
retried and attempt 2 completed the tests. This targeted diagnostic PASS does not
replace the full canonical check, cross-API gates or independent exact-head CI.

## S3C1 deferred timing navigation and ownerless context (2026-11-16)

Baseline: `b76a889b22b27548623fde85a4b0f9967c0d0107`. This correction follows
S3C1 and the S1/S2C1/S3 authorities above. No separate Parent Contract file is
tracked. Historical correction input, targets, immutable proposals, optimistic
tokens, transactional writers and canonical recovery keep their existing model.

All timing jump paths (initial editor landing, validation landing, error action,
ordinal navigation and overlap warning) now use synchronous `requestScrollToItem`
after the queued frame and a fresh authority check. The resolved Foundation 1.10.0
artifact exposes a non-suspending `void requestScrollToItem(int, int)`; local bytecode
inspection confirms it calls `snapToItemIndexInternal` synchronously, with scroll
cancellation launched separately. There is no scroll-mutex continuation carrying
a previously admitted position into a later publication.

One pending request retains its typed endpoint (or the existing review warning),
resolved position, originating lease and coroutine scope. Supersession, revocation,
owner/index replacement, scope cancellation and stage disposal discard its focus
authority. Native focus starts only after scroll admission, then rechecks the
exact pending token, target membership, lease, live scope, attachment and focused
native window after its frame. A declined native focus request stays retryable on
attachment/window/input-mode change; successful focus consumes the request.
Navigation still has no mutation dispatcher or repository reader.

Read-only canonical and timing-owner remembered state now use identity tokens.
Structural equality must neither retain an old equal-looking canonical/proposal
nor reject its fresh replacement authority. Editor destinations are also keyed
by exact canonical identity; review destinations use exact canonical and proposal
identity. The existing once-built position and context indexes remain shared.
Navigation and row context lookup remain O(1), with no collection search per row,
additional historical copy, queued-request collection or new History/Statistics
scan. Lazy composition and the unchanged O(log N) immutable timestamp update remain
in place.

The shared timing label now adds `sequence_history_ownerless` only when the
canonical interval's `occurrenceId` is null. Its existing EN/RU wording explicitly
says that the Sequence interval has no occurrence owner. Editor input descriptions
and exact Before/After review both retain interval kind/ID and root execution
identity. Owned intervals retain their exact canonical occurrence context;
countdown owners gain no timestamps, child execution or invented relationship.

The fail-first JVM regression `revokedTimingJumpDiscardsItsPendingFocus` failed
against the original timing navigator before the repair. Deterministic JVM frame
and dispatcher tests cover current editor/review navigation, all authority losses,
supersession, changed/removed membership, native attachment/availability, scope
cancellation before and after scroll, exact equal-looking canonical replacement,
and fresh usable authority. No navigation fixture exposes a repository reader;
dispatch counters stay zero.

Six new Compose tests are registered in the instrumentation inventory. Their
paused clocks and explicit coroutine gates cover editor/review loss before scroll
and between scroll/focus, hide/reentry without a frame, PAUSE/RESUME, same-execution
session replacement, publication/detail/proposal replacement, cancellation/stage
disposal and fresh navigation directly on the replacement publication. A real
LazyListState is held in a PreventUserInput scroll while a current synchronous
request reaches its exact target; the revoked case releases that gate and must
remain at its original position. The EN/RU fixture has equal-looking endpoint
timestamps and distinct interval IDs for performed, skipped-countdown,
not-started-countdown and explicitly ownerless cases. Both endpoint types and
Before/After descriptions are checked without creating unperformed child facts.

Controller limits remain explicit: device execution is prohibited in this turn.
APK/source compilation does not establish a Compose/device PASS. Existing S1
native admission, S2/S2C1 focus, 10,000-occurrence editor/review, production History
and MainActivity navigation, repository integration and physical-reopen timing
journeys remain in Controller's device gates. Full parity/coverage, cross-API
checks and independent GitHub CI remain mandatory Controller-owned gates; manual
accessibility-service certification stays deferred.

Local S3C1 checks completed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
.\gradlew.bat :app:ktlintCheck :app:detekt --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The combined invocation passed all 214 focused JVM tests across 14 suites,
including 18 navigation tests, with zero failures, errors or skips. App Detekt,
Android lint, debug assembly and app/data AndroidTest source/APK compilation and
packaging passed. Its overall exit was nonzero only for formatting of the new
editor destination declaration; that whitespace-only issue was corrected and the
follow-up ktlint/Detekt invocation completed BUILD SUCCESSFUL. No remaining local
check failure is outstanding. The runner's 74 unit tests passed with mocked device
output, and inventory/source identity matches all 29 Sequence presentation tests.
No emulator or device task was launched. These results establish local patch
readiness, not Controller parity, a device PASS, or merge/CI approval.

### S3C1 API 33 attachment repair (2026-11-16)

Controller's canonical API 33 app run reached 760 tests and reported five timing
focus assertion failures in `SequenceHistoryPresentationTest`. The target rows
were displayed in an attached, focused foreground window, but navigation left
native focus on the ordinal input. These were application/test failures, not an
infrastructure-only failure.

Two authorized diagnostic probes reproduced all five failures. A stable per-row
requester alone did not repair them. Temporary tracing then showed current,
enabled requests with `attached=false` after lazy reuse; the replacement observer
never received the attachment of the retained modifier node. Both the temporary
tracing and requester-only experiment were removed.

`FocusTargetAttachmentElement.update` now publishes the current attached state to
its replacement callback when the node is attached. Actual detachment still
publishes false. This fixes the shared observation boundary used by both timing
editor and review without changing their lease, pending-token, membership,
foreground or native-focus admission checks. Other consumers were inspected:
removal-only callbacks ignore the additional true notification, while consumers
tracking attachment receive the correct current state. No resolver, proposal,
reader, writer or historical timestamp semantics changed.

The deterministic Compose regression
`replacementNativeAttachmentObserverReceivesCurrentStateWithoutReattachment`
replaces the observer on one retained native modifier node, checks immediate true
delivery without a detach, then checks false on actual removal. All original five
regressions and their downstream fresh-navigation assertions remain intact. The
instrumentation inventory now contains all 30 Sequence presentation tests.

The authorized class-scoped repair probe completed REAL PASS: 30 tests, zero
failures or skips, including all five original failures, the new observer test,
S2/S2C1 native focus, EN/RU ownership, stage/proposal replacement, contended
scrolling and the 10,000-occurrence lazy editor/review checks. Evidence:
`repair-probes/wf-66d1e26240c0559b97e60b9bf4b5c7fe/afa67feb9bbf40f0bb9c644ec972b373/tree-3/attempt-1/01.log`
and its archived API 33 XML report. The failed requester and tracing probes are
retained in trees 1 and 2 of the same probe. Runtime execution used only the
Controller-authorized `repair-probe.cmd --scope-file ...` entrypoint. This is a
diagnostic class PASS, not a rerun of the full 760-test canonical check or CI PASS.

Follow-up local checks completed successfully:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

Gradle completed BUILD SUCCESSFUL. The focused JVM XML reports contain 214 tests
across 14 suites, zero failures, errors or skips. App ktlint, Detekt, Android lint
and AndroidTest source/APK packaging passed. The runner's 74 unit tests passed
using mocked device output; inventory and source match exactly for all 30 Sequence
presentation methods. No new repository paths were introduced. Full canonical
device checks, cross-API coverage and independent GitHub CI remain Controller's
final gates.
