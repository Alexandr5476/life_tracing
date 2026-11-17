# Completed History accessibility (S1)

This slice follows product v0.16, domain v0.10, schema v0.6, the implementation
clarifications and the shared design controls. It changes presentation and route
action admission only. Execution IDs, historical writers, snapshots, Plan
fulfillment, Statistics and repository queries are unchanged.

## PR42 review correction (W1, W2, S1)

History and manual-entry admissions permanently revoke a publication when a callback
observes lost route ownership, including before a hidden frame is rendered. Returning
ownership cannot revive its retained callbacks; a fresh foreground publication owns
new actions. Manual input cursors still admit consecutive current text events.
Explicit historical editor SetText handlers return the actual admission result and
reject disabled, hidden, disposed, pending and stale callbacks in both semantics trees.
Activity detail descriptions distinguish Missing, present-empty Text and nonempty
Text for configured and actual values using existing EN/RU resources. Visible stored
Text, canonical readers, historical commands and durable semantics are unchanged.

Three fail-first JVM regressions reproduced W1 against reviewed head
`127cf6408545fb07dae205b103e4bd674dd752c4`. New production Compose cases are registered
in the mandatory instrumentation inventory. Device fail-first/corrected execution,
Controller full and gmd-full, API compatibility suites and independent exact-head
GitHub CI remain Controller-owned. Compilation alone supplies no device execution
or manual TalkBack/Switch Access evidence.

## Review follow-up: foreground lifecycle and catalog continuation

History navigation now observes RESUMED ownership and checks the live lifecycle
at activation. Pause permanently revokes issued callbacks without exiting the
route or clearing canonical browsing state. Native controls and focus follow the
effective foreground boundary. The shared History admission also guards retained
detail navigation callbacks.

Manual catalog continuation retains only its latest successfully displayed page
through read failures. An empty terminal retry restores that exact bounded page
and page number, clears continuation, and makes the old Retry inapplicable.
Nonempty continuation replaces the page. Selection and source hydration remain
independent; canonical readers, writers and durable historical semantics are unchanged.

The catalog regression failed against audited head 2317057538d614aeed9e088bc3ff6f08d4e38b30
before the fix; initial-failure retry already passed. New registered production-route
Compose regressions cover lifecycle revocation and keyboard catalog recovery.
Their device execution, full compatibility gates and exact-head CI remain
Controller-owned. No manual TalkBack or Switch Access walkthrough was performed.

Local verification passed 135 selected domain tests, seven Activity mapper tests
and 418 app tests covering History, navigation, timestamps, Plan, runtime, launcher,
Library, Statistics and Daily, with no failures, errors or skips. App ktlint,
Detekt and lint passed; production and instrumentation APKs assembled. Both new
instrumentation identities are registered and git diff --check passed.

The follow-up review covered route/session ownership, canonical cursor bounds,
source freshness, exact Activity/Sequence IDs, frozen Field/option identities,
timezone/DST and overlap validation, mutation admission, immutable snapshots,
Plan/Statistics consequences and committed-result/recreation delivery. No reader,
writer, transaction, schema or durable-state change was needed.

## Presentation

History keeps the canonical newest-date discovery, adjacent 28-day windows,
101-root repository request and 100-root replacement page. The controller now
publishes its already-calculated newest date for destination-period labels.
Persisted primaryLocalDate remains the grouping authority after zone changes or
clock rollback. Compose preserves the reader's date/root order.

Each native Material row has one detail action. Its description includes Activity
or Sequence kind, frozen title/comment, a localized record identifier containing
the full stable execution ID, historical timing, duration/tracking facts and
terminal status. Full IDs deliberately disambiguate even otherwise identical
records without assigning authority to a visual ordinal. Typed kind/ID Compose
keys preserve execution focus identity through reordering and rematerialization.
No custom secondary accessibility click actions are introduced.

Timing reuses historicalTimestamp for every endpoint with its original ZoneId
and effective offset. Both dates remain explicit, including cross-midnight and
repeated-hour intervals. Completion-only No-live records have no fabricated start;
missing duration remains distinct from zero. Timer target remains separate from
actual active duration.

History dates and detail titles are headings. Loading, empty, read failure,
unavailable detail and successful content expose localized state text; live-region
properties change with the actual read state, not unrelated recomposition.
Activity detail combines each frozen Field's actual and configured values in one
readable group, preserving missing values. Correction/delete and Sequence mutation
controls retain their production commands.

Navigation uses wrapping FlowRows, a separate period heading and the shared
physical minimum-target provider. Labels and feedback use English/Russian
resources. Native roles, disabled state, press indication and keyboard focus stay
with Material controls.

## Admission and focus

HistoryNavigationAdmission captures one rendered publication by reference. Every
Back, Add, Older/Newer, Load more, Retry and root-opening callback checks attachment,
current publication, current top-route ownership and availability when activated.
Native semantics and pointer handlers share the same guarded callback. Submission
revokes sibling callbacks immediately, before outgoing content disposal. A new
rendered lease permits interaction after a same-turn open/Back even when no hidden
frame was composed; retained callbacks keep their revoked lease.

MainActivity supplies a live top-of-stack predicate, including exact Activity and
Sequence detail destinations. Removed detail entries also stop before session
acquisition: NavDisplay's outgoing composition cannot recreate an Activity or
Sequence session after Back has released it. Entries still on the stack retain
their existing mutation and committed-refresh delivery. HistoryController rejects browsing after route exit
and clears retry/cursor authority. Underlying History stays active during detail
navigation. Existing committed-mutation Refresh delivery is deliberately independent
of foreground browsing admission and remains unchanged.

HistoryFocus tracks native owner tokens and detachment/disabled-target recovery.
Visible entry/return lands at Back after window/frame availability. A surviving
row/control receives no passive focus request. Removed results and continuation
controls recover to Back, then Retry if their pending read fails. Every deferred
focus request rechecks live route ownership. Focus changes never invoke callbacks,
navigation or historical writers. Detail initial landing uses the same mechanism;
editor/modal policies remain deferred.

## Bounds and verification

Description formatting and grouping take O(n) work over the presented page
(n <= 100), plus its text length. Each callback performs constant-time reference
and route checks. Focus adds one native token per rendered target and constant
owner/recovery state. There is no per-row database access, detail hydration,
global sorting, global History scan or accumulated page copy.

Focused JVM regressions cover route exit/reentry, stale publication/route/disposal
admission, exact typed routing, clock rollback, legitimate background Refresh and
the existing discovery/continuation/provenance cases. Existing Activity mutation,
manual-entry, Sequence proposal, navigation and timestamp suites remain included.

Seven original JUnit4 methods in HistoryAccessibilityPresentationTest and three
in PrimaryExecutionJourneyTest are registered in instrumentation-inventory.json.
They cover duplicate typed records, one native action, native Enter, stable node
focus on reorder, obsolete callbacks before recomposition, removal/Retry recovery,
detail loading/unavailable/failure/values, EN/RU scale extremes and real isolated
MainActivity/NavDisplay journeys. Production journeys use Tab from Daily, exact
Activity/Sequence IDs, retained callbacks during detail and exit, same-turn Back,
Activity recreation, canonical paging and unchanged durable fixture facts.
The English recreation journey uses MainActivity's production content and temporarily
sets/restores scale preferences; replacing its content with a localized test wrapper
would give the saved navigation stack a different composition path on recreation.
Both detail kinds assert session release on return before testing retained callbacks.
The failed-read journey substitutes only the bounded read port and retains the
production controller and repository reader.

Automated device execution is Controller-owned. APK compilation does not execute
these assertions. No emulator/device task was launched by the agent and no TalkBack or Switch
Access walkthrough/certification was performed. Controller's full CI and managed
device gates remain required. Manual service validation should separately cover
EN/RU record descriptions, headings, original-zone endpoints, Retry, route returns
and combined app/system scaling.

## Local verification

The final focused check completed successfully:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
```

JVM reports contain 117 tests, zero failures, errors or skips. App ktlint, Detekt
and lint passed; production and both instrumentation APKs assembled. Inventory
validation found all ten new instrumentation identities, both resource files
parsed successfully, and git diff --check passed. The exit regression was first
run against the baseline and failed before the production guard was added.
These results do not establish device execution, CI equivalence or accessibility
service certification.

## Controller API35 fixture repair (2026-11-13)

Controller attempt 1 completed 649 app cases with five failures. Both duplicate
journeys failed in their shared fixture before navigation: the Sequence started
before its snapshot creation timestamp. The fixture now creates source Templates
first and gives the Sequence snapshot and start the same earlier timestamp, while
retaining the duplicate terminal timestamps and exact-ID navigation assertions.

The paging journey's injected controller used main-thread Room reads, including
newest-date discovery. Both read ports now use Dispatchers.IO, as the production
graph does. The initial failure explicitly asserts that the intended bounded root
read was reached and that its injected error owns Retry.

The scale matrix now measures native touchBoundsInRoot, matching the established
Plan target checks. It retains the physical 48dp minimum, displayed-state and
native-focus assertions and adds locale/scale/target diagnostics. The original
repeated-hour No-live regression now expects the localized completion label at
both offsets; its adjacent missing-versus-zero assertion checks the actual
localized zero-duration label.

No production implementation, query, writer or instrumentation identity changed
in this repair. The focused JVM/ktlint/Detekt and instrumentation APK command
passed (JVM results restored from Gradle cache):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain
```

The authorized repair-probe command exited before test execution with
"No active repair-probe lease." Consequently these five device cases have not
been re-executed after repair. Their original failures remain open for Controller
verification; neither this local compilation nor the unavailable probe is a device
PASS or CI PASS. No direct managed-device task was launched by the agent.

## Controller follow-up: History entry synchronization

Controller's subsequent five-case API35 run passed the paging, scale and
repeated-hour cases. Both duplicate journeys progressed beyond fixture setup but
then read HistoryControllerOwner before NavDisplay had composed the destination.

Their shared fixture now waits for the native History Back control, verifies its
keyboard landing, then reads the existing controller on the UI thread. The
factory still throws if production failed to create the controller; the test
does not create or substitute one. Content readiness, exact-ID navigation,
same-turn Back, stale callbacks, recreation and durable-fact assertions remain.

The only other owner access in this boundary is the intentional injected-reader
setup, which already runs on the UI thread before entering History. No production
code or instrumentation identities changed. The authorized probe again reported
"No active repair-probe lease" before executing tests, so these two repaired
journeys still require Controller device verification. The three reported passes
do not establish a full-suite or CI PASS.

## Controller follow-up: detail return and recreation

The next two-case API35 run reached detail return and recreation. The English
journey lost its native Back target after replacing MainActivity content with a
test composition whose saveable navigation path differs from production startup.
That journey now sets/restores production scale preferences around the fixture
and keeps MainActivity's own content throughout recreation. The Russian font and
localization wrapper remains confined to its journey, which does not recreate.

Detail entries also reacquired released sessions when NavDisplay recomposed
outgoing content after Back. Both Activity and Sequence entries now check exact
route membership before acquiring a session. Sessions and committed-refresh
delivery for entries still on the stack retain their existing behavior. The
journeys assert session release immediately after return, before invoking retained
row callbacks, and check Sequence session release at the adjacent return boundary.

Local verification passed with 117 JVM tests (zero failures/errors), app ktlint,
Detekt and AndroidTest APK compilation/package:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' --continue --console=plain
```

The authorized probe again rejected execution with "No active repair-probe lease."
Neither failing device journey has been re-executed for this patch; the supplied
assertion failures remain open for Controller verification. No direct device task
or manual accessibility-service walkthrough was performed.

## Sequence History mutation ownership (S1)

`SequenceHistoryAdmission` is the native action boundary for the existing detail,
timing draft/review/overlap, child deletion, structural mode/placement/review,
Retry and Back. It checks the live RESUMED foreground route, exact retained
session/controller and execution identity, publication reference, current phase,
typed target and writer lock. Session-keyed composition isolates retained controls
and focus. Pause events revoke synchronously; navigation snapshot observation,
activation-time ownership checks and disposal permanently revoke older authority.
Foreground reentry issues fresh callbacks. Each timestamp input advances only its
own expected-publication cursor; buttons and offset choices cannot borrow it.
Material pointer/keyboard actions and native semantics use the same guarded
callbacks; disabled controls retain native disabled semantics.

Canonical loading rejects mismatched/nonterminal roots and malformed occurrence,
child and interval identities before creating mutation controls. Validation uses
only the selected canonical aggregate and temporary identity sets. Admission
retains references and a validity flag, never an aggregate copy. Publication checks
use reference identity in constant time; typed target checks use the selected
aggregate. No new reader, global History/Statistics query, persisted state or writer
was introduced. Existing resolver, proposal builder, policies, optimistic tokens
and transactional commands retain their semantics.

System and native Back consume the current transient or block while a writer/read
completion is pending. Safe exit drains the same retained session generation gate
used by Compose refresh delivery before releasing the exact session. A committed
read failure keeps its generation and permits read-only Retry. Generation delivery
is independent of RESUMED ownership and survives Activity recreation.

`SequenceHistoryAdmissionTest` covers publication replacement, identity and phase
rejection, target disambiguation, revocation, independent text cursors, obsolete
offsets, dismissed reviews/warnings, incomplete placement, duplicate confirmation,
Back/session ownership, refresh draining, stale rejection and postcommit recovery.
New production-route native semantics/keyboard tests in
`HistoryScreenPresentationTest` and a MainActivity recreation test are registered
in the mandatory instrumentation inventory. Device execution and independent
GitHub CI remain Controller-owned. Manual accessibility-service certification was
not performed. Full editor descriptions/focus design and large-Sequence rendering
remain deferred.

An additional fail-first JVM regression reproduced StateFlow conflating an immediate
Retry/read failure with the preceding identical failure. Sequence dispatch now
advances a non-durable publication counter, following Activity correction, while
reference identity remains the authorization check. A repeated-failure native Retry
regression covers fresh keyboard recovery and rejection of the retained Retry.
Committed refresh generations and writer behavior are unchanged.

Local verification passed 179 History/navigation JVM tests, including 17 admission
regressions, with no failures, errors or skips. App ktlint, Detekt and Android lint
passed; debug and app/data instrumentation APKs assembled. All eight new device
test identities are registered; inventory/source identity validation and
`git diff --check` passed. Instrumentation compilation supplies no device-execution
or accessibility-service certification evidence. Durable command/repository,
domain, schema, snapshot, Plan and Statistics implementations are unchanged.

## API 33 fixture repair

The canonical `gmd-api33-app` run reached three S1 testcase failures. The recreation
fixture supplied a backdated Sequence start with a later persisted creation time;
starting its first occurrence then violated the existing updated/creation invariant.
The fixture now uses one explicit instant for both start and creation, retaining
its past terminal event and correction. The two Sequence Enter-key cases now enter
native keyboard mode and await the intended control's focus through the shared
`KeyboardInputFixture`. Stale-action, single-writer and refresh assertions remain
intact. Both Enter activations in the affected class were corrected together;
production admission and durable behavior are unchanged by this repair.

The Controller's subsequent API 33 causal run executed all three affected tests.
The recreation test passed. Both keyboard tests reached native window admission
and failed because the same `Application Not Responding: com.android.systemui`
window owned input focus; the intended ComponentActivity remained the focused app.
Their preceding stale-action assertions completed, but native keyboard activation
was blocked in that execution. The full diagnostic captures reconstructed
from acknowledged logcat frames matched the reported byte counts and SHA-256
hashes (316245 bytes, `63cacb159ce3ab132b23e32ed32302a2ba7b5721b3495ff632feb02309b17a3d`;
346046 bytes, `997b642a582d2b6f7673cfcfa1e4f39426ab7cd28365b1f0531e53e6619d0a2c`).
Both identify SystemUI PID 825 and the same ANR dialog window `9b0d710`.

After restoring the probe binding, an authorized containing-class experiment ran
all 55 tests in `MainActivityRouteSessionTest` and `HistoryScreenPresentationTest`.
It reproduced the same two SystemUI window-admission failures; the other 53 tests
passed. The canonical artifacts and this first probe were preserved separately.
Controller then completed its authorized `cleanManagedDevices` and setup recovery.
With identical application source and the same class scope, a second real probe
passed all 55 tests with no failures, errors or skips, including both keyboard
regressions and Sequence session recreation. These are retained as `tree-1` and
`tree-2` under repair-probe lease `b4c01c8a537649fc800a09d8b3f841ac`.

This comparison identifies the device's SystemUI obstruction as the immediate
cause of those native admission failures. No application assertions or focus
guards were changed for the experiment; the underlying SystemUI startup issue was
not repaired in the repository. The class-scoped diagnostic PASS supplies no full
API 33 suite or independent GitHub CI PASS credit. Controller retains those gates.


## Canonical terminal Sequence detail (S2)

The S2 task contract, product v0.16 §16, domain v0.10 §§10/13, schema v0.6
§§14–17 and the current implementation clarifications govern this presentation.
No separate Parent Contract file is tracked in this worktree. Existing S1 route
sessions, exact publication ownership, optimistic mutation tokens, writers and
committed-refresh delivery remain authoritative.

`SequenceHistoryPresentation.kt` consumes the exact `SequenceHistoryDetail`
returned by `HistoryReadRepository.getSequenceDetail`. It does not hydrate
Templates, fetch children during composition, reconstruct topology, restore hidden
occurrences, recalculate root durations or infer interval ownership. Visible
tombstones retain occurrence/source/configuration facts and distinguish deleted
child detail from a present No-live child with Missing duration. The retained
deleted-child identity comes only from the same canonical mutation facts; deleted
actual timestamps/values are not displayed as child results.

Root, occurrence/provenance, child, frozen Field and interval groups are separate
native read-only focus targets. Actions remain separate native Material nodes.
Fields retain exact snapshot Field/option IDs even with duplicate labels; defaults
and actual results are labeled separately. An unperformed occurrence's configured
Main Value has no actual-result announcement. Missing, numeric zero, present-empty
Text and missing Text remain distinct. Sequence and Activity Text types also retain
equivalent present-empty handling in the shared History value formatter.

Sequence-owned endpoints use the root's original zone; child endpoints use the
child's original zone. Exact ISO zoned descriptions are visible as well as spoken,
including seconds, stored fractional precision and each endpoint's actual offset.
They remain distinct across repeated local clocks and local-day/DST boundaries.
Fractional durations are preserved in Sequence detail. Activity History retains its
existing compact timestamp and correction presentation.

Idle content has one `LazyColumn`; no vertically scrolling ancestor wraps it.
Back remains attached outside the lazy viewport, including loading/failure and
transient mutation states. Root/status/section, Field, occurrence and interval keys
use disjoint namespaces. Occurrence/interval item keys contain their exact IDs, and
the lists retain canonical order. The production detail does not create a subtree
for every selected occurrence/interval. Section links and a labeled list-ordinal
input jump to offscreen content using arithmetic indices and exact target IDs;
these controls only scroll and request native focus. Mutation editors retain their
existing scrollable surface and S1 actions.

Lazy disposal is distinguished from historical removal in `HistoryFocus`.
The focused item's type-qualified key and, for occurrence descendants, its typed
semantic identity are checked on canonical instance replacement; removal recovers
to the attached Back target, and failed reads recover to Retry.
Unrelated publications preserve native item focus and the lazy scroll state.
No focus or scrolling effect dispatches a mutation or repository reload. No second
aggregate, historical repository or persisted accessibility state is introduced.

Composition work is bounded by visible lazy items and their own frozen Fields.
Item keys and navigation indices use O(1) operations. The sole canonical-replacement
focus membership check is O(N) in the relevant selected collection. S1 admission
validation is remembered by exact canonical instance and execution ID, with fresh
validation after reload; unrelated publications and timestamp keystrokes reuse its
validity. Cache misses still perform the complete original identity validation.
JVM regressions count selected-list reads across 50 admissions and reject cached
validity reuse for another/equal-but-distinct/malformed aggregate. No global
History/Statistics scan or N+1 child/interval query is added.

New Compose fixtures cover roots, identities, exact original-zone endpoints,
provenance/reasons, unperformed and deleted-child states, No-live/Missing versus
zero, frozen schema/value/option identities, all interval kinds and ownership,
native keyboard focus, canonical removal, read recovery, EN/RU scaling, and a
10,000-occurrence selected detail with early/middle/late discovery and reused action
targets. Existing presentation, mutation integration and MainActivity recreation
fixtures discover offscreen items through the production lazy container before
invoking native controls. All new test identities are registered in the mandatory
instrumentation inventory.

Structural-removal visibility is established by the existing real repository test
`SequenceHistoryCommandRepositoryTest.leaveGapPersistsHiddenOccurrenceAndCanonicalHistoryOmitsIt`
and `SequenceHistoryMutationRepositoryIntegrationTest`; S2 does not synthesize a
structural-removal result from tombstone metadata. Device execution and independent
GitHub CI remain Controller-owned. AndroidTest compilation supplies no device PASS
or manual TalkBack/Switch Access certification.

Local S2 verification (2026-11-16) passed with:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The selected JVM suites ran 188 tests with no failures, errors or skips. All 74
instrumentation-runner unit tests passed; their simulated device command output
does not represent emulator execution. The 14 new Compose test identities match
the instrumentation inventory, and Sequence resource keys are paired and unique
in English/Russian. Production and AndroidTest sources/APKs compile and package;
the new Compose cases and existing device regressions still require Controller
execution. This evidence does not replace the independent CI gates.

### API 33 S2 repair evidence

Controller execution `S2-77163393488948db8628ea64a8d610e7/attempt-1`
reported three failing testcases. Two timestamp assertions matched both the root
and an occurrence sharing its endpoint. They now assert the exact endpoint on the
intended tagged summary; the localized child/root fixture checks both endpoints
in their respective original zones. Adjacent root assertions use the same explicit
identity rather than a global first match. The controller-only persistence fixture
also contained a misplaced UI-scroll call despite never opening a History route;
that call is removed. Its persistence/refresh assertions remain intact. Every
remaining occurrence-scroll call belongs to a fixture that opens the route first.
No production semantics, admission checks or historical writer changed in this
repair.

The authorized real API 33 repair probe passed all three original failing methods
with zero failures, errors or skips. Evidence is retained under repair lease
`b66955e0690d43638aa0d1111f26bb89/tree-1/attempt-1`. The probe compiled and
packaged the revised AndroidTest APK. This scoped result explains the reported
failures but supplies no full API 33 suite or independent CI PASS credit; Controller
retains those gates.

After that probe, only assertion line-break formatting and this evidence changed.
Final `:app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest` and
`git diff --check` passed. No additional JVM suite was needed for this test-only
repair; the real probe executed the affected controller/persistence assertions.


## Sequence navigation ownership and nested recovery (S2C1)

Baseline: `e920b44` (S2). This correction follows the S2C1 task contract and the
same product/domain/schema/clarification authorities recorded above. It changes
presentation authority and native focus recovery only. Canonical formatting,
original-zone endpoints, durable facts, S1 mutation phase/admission rules,
transactional commands and exactly-once committed refresh delivery are unchanged.

`SequenceHistoryNavigation` is a separate, revocable read-only lease. Production
borrows S1's exact current publication/foreground RESUMED session check and verifies
that the rendered canonical detail is that publication's exact object. It receives
no dispatcher. Standalone `SequenceDetail` remains usable with its composition and
RESUMED lifecycle as authority. Snapshot observation, synchronous PAUSE observation,
activation-time rejection and disposal permanently revoke older controls. Fresh
foreground publications create fresh leases; rejected callbacks never revive.
The originating navigation control's coroutine scope must also remain active at
activation, including ordinal edits and native focus admission after lazy disposal.
Native Material enabled/disabled semantics and dynamic `focusProperties` use this
same availability, including invalid ordinal input.

A jump checks its lease at activation and after its queued frame. It binds the
ordinal to the exact occurrence ID at that canonical list index, using arithmetic
section offsets. The guarded scroll uses the synchronous `requestScrollToItem`
API so there is no intervening scroll-mutex suspension after admission. Superseding
jumps cancel queued work and discard the previous pending focus token. A target's
delayed native focus checks its attachment, the initiating lease and the exact
pending request again after its frame. Replaced publications/details/sessions and
hidden, paused or disposed routes cannot authorize that request. Shared History
landing/recovery effects also retain their initiating ownership callback instead
of borrowing the latest callback after a frame; Sequence supplies its exact S1
publication guard. An unrelated publication does not move a surviving target.

Occurrence descendants now compose typed identities from occurrence ID and the
semantic element: occurrence summary, tombstone, child execution summary, child
execution plus frozen Field ID, configured snapshot plus Field ID, or a mutation
action plus its exact child identity. These identities key their native subtrees
and accompany the existing lazy item key in `HistoryFocus`. Canonical replacement
checks only the focused target's membership and applicable action availability.
A retained occurrence therefore cannot conceal the disappearance of its child
summary/Field/delete action when it becomes a visible tombstone. Recovery uses the
attached Back target. Lazy detachment by itself still does not mark historical
removal. No persistent cache or additional aggregate is retained: one focused
typed identity is stored, and the membership lookup is O(selected occurrences +
focused child's Fields), only on canonical replacement or action availability
change. Row composition, focus events and scrolling do not scan the aggregate.

Regression coverage:

| Boundary | Executable evidence |
| --- | --- |
| Queue, publication, canonical reference, route/session loss, disposal, replacement generations, superseding requests | Ten `SequenceHistoryNavigationTest` JVM tests, using a queued dispatcher and explicit deferred frame gate |
| Hidden native controls and input focus, hide/reentry before a frame, PAUSE/RESUME, disposed route, same-execution replacement session, fresh foreground controls | `HistoryScreenPresentationTest` and `SequenceHistoryPresentationTest` production-route fixtures |
| Queued hidden jump and foreign foreground focus, queued replacement session | `sequenceQueuedReadOnlyJumpLosesHiddenRouteBeforeScrollAndCannotStealFocusOnReturn`, `sequenceQueuedReadOnlyJumpCannotScrollReplacementSessionWithSameExecution` |
| Canonical replacement before scroll and between scroll/focus frames | Both `queuedOrdinalJump...` tests use a paused Compose frame clock and the production surface/admission with explicit canonical publications |
| Performed child summary to visible tombstone with the same occurrence ID; disappearing child Field | Both `removedChild...` tests verify absent target, attached native Back focus, retained occurrence and rejection of a captured old child action; the canonical fixture exposes no reader and dispatch count stays zero |
| Surviving nested target and ordinary lazy disposal | `nestedFocusSurvivesCanonicalReplacementAndOffscreenDisposalDoesNotRecover`; an explicit native handoff to an attached control outside the lazy list precedes actual disposal, while History retains the last semantic owner and receives no removal signal |
| Exact ordinal binding despite runtime positions and duplicate titles | `ordinalNavigationUsesCanonicalOrderDespiteUnrelatedRuntimePositions`, plus the existing 10,000-occurrence navigation/reuse fixture |

All eleven added instrumentation identities are registered in the mandatory
inventory. Existing full-occurrence removal, keyboard/section navigation,
10,000-occurrence bounded rendering, S1 stale-action, Activity/History accessibility,
Sequence mutation and MainActivity session tests remain registered and compiled.
No navigation/focus path calls a writer, canonical reload, History scan or per-row
repository query. The initial nested-removal and captured-hidden-action regressions
were added before the production repair; device fail-first execution and device
regression execution are Controller-owned and were not run in this turn.

Initial implementation verification on 2026-11-16 passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The selected JVM suites ran 197 tests (including nine new navigation/focus-decision
tests) with zero failures, errors or skips. All 74 instrumentation-runner unit tests
passed; their mocked device output is not device execution. Inventory/source
identity checks match all 21 Sequence presentation and 31 History presentation
tests. The separate app ktlint, Detekt, lint and AndroidTest APK rebuild after the
final instrumentation fixture refinements also passed. The initial implementation
turn launched no managed-device task or emulator. The subsequently authorized
repair probes are recorded below. Full Controller device suites, mandatory parity/coverage
gates and independent GitHub CI remain outstanding; APK compilation supplies no
instrumentation PASS or manual accessibility-service certification.


### API 33 S2C1 repair evidence

Controller execution `S2C1-917ab8505899436683748e0eacf60d42/attempt-1`
reported three failures in `SequenceHistoryPresentationTest`.

Both nested-removal fixtures used an occurrence-scoped text matcher to find the
route-level Back control after replacement. The shared fixture now binds one
unscoped Back interaction before scrolling and uses it for native keyboard entry
and recovery verification. Assertions still require the removed child summary or
Field to be absent, native Back focus, a retained occurrence and rejection of the
captured old child action. Adjacent route-level Back assertions were audited;
the existing full-occurrence and queued-jump fixtures already reset their scope.

The surviving-child test exposed a production defect: native `focusProperties`
observed the old lease's publication/canonical state. Retiring that lease could
invalidate an existing native focus target before the lazy content republished
its replacement properties, even though the exact child remained represented.
`SequenceHistoryNavigation.canFocus()` now evaluates the same live, revocable
`isCurrent()` check inside `Snapshot.withoutReadObservation`. All five Sequence
native focus-property guards use it. This prevents an obsolete publication's
snapshot reads from independently clearing existing focus; composition still
publishes current enabled/disabled properties, and the lease's own route/lifecycle
observer is unchanged. Activation, ordinal edits, queued scroll and delayed focus
continue to use their original exact-publication checks. Hidden, paused, disposed
and replaced-session requests remain permanently rejected; no refocus workaround
or historical command was introduced.

The next probe passed that survival assertion and exposed a downstream fixture
assumption: `clearFocus()` alone did not establish actual lazy disposal in this
setup. The fixture now explicitly hands native focus to an attached Material
control outside the list and waits for that target to own focus. That control does
not alter History's last semantic owner, which is asserted to remain occurrence
`o0`. The test still requires the old child node to disappear after scrolling,
requires the outside target to retain native focus, checks that no removal signal
was emitted and verifies that the child is discoverable again on return. The
canonical-replacement survival assertion is unchanged and precedes the handoff.

The added JVM regression models native focus-property snapshot observation. It
verifies that canonical/publication replacement does not invalidate an existing
native focus observer, while a new request rejects the obsolete lease permanently
and a fresh lease remains usable. The focused JVM run passed 29 tests: ten
navigation/focus tests and nineteen S1 admission tests, with no failures, errors
or skips. App ktlint, Detekt and AndroidTest APK assembly passed; final fixture
ktlint/Detekt checks also passed.

Authorized real repair probe evidence is under lease
`0e9c990d48e049c290763e72c1b8b8c9`:

- `tree-1/attempt-1`: the three original methods ran; both corrected removal
  fixtures passed and the production focus-survival failure remained (2/3 passed).
- `tree-2/attempt-1`: the complete Sequence presentation class plus four production
  route navigation tests ran; the production survival assertion passed and only
  the later actual-disposal assertion failed (24/25 passed).
- `tree-3/attempt-1`: the same 25-test scope passed with zero failures, errors or
  skips after the explicit native handoff. This includes all three originally
  failing methods, full occurrence removal, section/ordinal and queued navigation,
  exact-ID reuse, the 10,000-occurrence bounded fixture, and hidden/paused/disposed/
  same-execution session ownership regressions.

Only the authorized `repair-probe.cmd` launched device work. The final probe
compiled and packaged the changed AndroidTest APK. Inventory/source identities
remain unchanged and `git diff --check` passes. These scoped real-device results
explain and resolve the supplied failures; they do not supply a full 745-test API
33 rerun, mandatory cross-API parity/coverage or independent GitHub CI PASS credit.


### API 33 full-suite follow-up: Plan fixture reopening

The next canonical `gmd-api33-app` run (attempt 2, tree fingerprint
`426ab5d944a43e11359437bc65406612c5ff6c8a2ac486d7393bbbdc94ec83e6`)
reported one failure among 745 tests. All three previously failing Sequence
presentation methods passed. The remaining failure was
`PlanExactDayPersistenceTest.untouchedHighPrecisionTargetSurvivesPreservationZoneTravelDismissalAndReopening[sequence=false]`,
at the first `getPlan` after fixture reopening. Its recorded logcat reports that
Room closed the old database pool with one connection still in use, followed by
`SQLITE_BUSY` on the reopened store.

The fixture cancelled its controller scope without joining its children. Obsolete
canonical reads can still be inside synchronous Room calls after cancellation;
completion of the latest publication does not establish that those reads finished.
The ExactDay fixture now closes the controller, cancels and joins its owning job,
and only then closes/recreates the store. Teardown uses the same ordering. The
identical permanent-controller-stop/reopen mechanism in
`PlanScheduleAccessibilityTest` receives the same correction. Neither change
alters production Plan/History admission, repository transactions or durable facts.

A new Activity/Sequence parameterized regression holds an obsolete repository
read in a non-cancellable, explicitly gated section. Reopening must suspend and
retain the old store until the read finishes; afterward a fresh store must return
the original high-precision Plan and identical durable facts, with zero mutation
commands. `CoroutineStart.UNDISPATCHED` establishes the pending-reopen assertion
before releasing the gate. There are no sleeps or SQLite retry loops, and all
waits are bounded. The instrumentation inventory includes both new identities.

The authorized repair-probe entry point returned `No active repair-probe lease`
for this follow-up. No device execution was started. The original full-suite
failure therefore still requires Controller runtime verification of this patch;
AndroidTest compilation and JVM/static checks do not establish a device PASS.


Local verification passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*Plan*Test*' --continue --console=plain
```

The seven selected Plan JVM suites ran 90 tests with zero failures, errors or
skips. All changed AndroidTest Kotlin compiled and the APK packaged. A direct
source/inventory comparison matched all ten ExactDay methods and twenty
parameterized identities without duplicates. `git diff --check` passed. The new
gated device regression and the original lock failure remain pending Controller
execution; no runtime repair result or full-suite/CI PASS is inferred.

## Sequence timing correction accessibility (S3)

See [Sequence timing correction evidence](sequence_history_timing_accessibility.md)
for native timing controls, typed error/offset authority, lazy owner navigation,
committed read recovery, complexity reasoning and Controller verification limits.

## Sequence child History deletion (S4)

[Sequence child deletion evidence](sequence_child_history_deletion_accessibility.md)
records the exact native confirmation, localized logical-deletion consequences,
S1 action admission, canonical tombstone/focus and committed read-only recovery,
new physical-reopen production journeys and the existing repository policy matrix.
Local checks pass; device execution and independent CI remain Controller-owned.
