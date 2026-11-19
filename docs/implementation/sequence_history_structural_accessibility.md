# Accessible Sequence structural History removal (S5)

## Authority and durable boundary

This implements the supplied S5 contract with accepted S1 admission, S2/S2C1
canonical presentation and navigation, S3/S3C1 timing/recovery and S4 tombstones.
No separate Parent Contract or resolved-decision file is tracked; the accepted
implementation notes record the same boundary. Product v0.16 sections 16/24.5,
domain v0.10 section 13, schema v0.6 sections 14-17 and the implementation
clarifications govern ownership, removal, completion reasons, Repeat iteration
and the 10,000-occurrence limit.

The existing removal policy, command, transaction and canonical readers are
unchanged. There is no schema/migration, alternative writer, placement inference,
repair algorithm, persistent selection state or new Plan/Statistics policy.
The clarification already resolves older Leave gap shorthand: target-owned
intervals are removed; non-active wall time is derived, without inserting idle.

## Native stages and exact authority

The entry action announces the exact occurrence and child IDs. The shared
structural eligibility predicate admits completed children and visible deleted
child tombstones independently of S4 child-deletion eligibility. Missing/mismatched
child/snapshot facts, hidden or unperformed occurrences cannot expose an enabled
structural action. Existing route admission owns canonical validity, terminal
status, pending writer, exact publication and session/foreground authority;
the unchanged transaction remains authoritative for active-session ownership and
persisted graph integrity.

Every stage identifies the root, selected occurrence, retained child, frozen
Activity snapshot, source Step/Repeat IDs, 1-based iteration and runtime-added
provenance without authorizing by title, position or time. Selected original
occurrence endpoints and the exact Close gap span are visible. EN/RU consequences
distinguish structural hiding from child-only logical deletion and describe the
retained durable identity/provenance, immutable snapshots, both gap modes, root
historical date, child date attribution and fulfilled Plan end synchronization.

Each ambiguous ownerless interval has its exact ID, kind, ownerless context,
Fixed/Translated endpoint alternatives and native radio selected semantics.
Original ZoneId, each endpoint's offset, seconds and persisted fractional precision
use the accepted exact historical formatter. Unresolved choices block review and
Confirm; selected choices remain visible while other choices are unresolved.
A sole legal outcome stays builder-determined and is labeled in review.

Review consumes the builder's immutable command and every existing preview change:
removed occurrence/owned intervals, root end, all affected visible occurrence and
child boundaries, visible child pause IDs and endpoints, retained interval changes,
and all explicit/forced ownerless outcomes. Child endpoints/pauses use that child's
recorded zone. Deleted child translations identify retained child/pause IDs from
the command without displaying their hidden actual results. Root active/pause/wall
consequences use the existing interval-union calculator. Review text never becomes
a command input.

Native Cancel/mode/Proceed/Confirm controls wrap outside one bounded lazy list.
Different stages and warning/final confirmation have distinct native nodes,
preventing held pointer presses from transferring into destructive confirmation.
S1 admission revokes every captured action on publication/session/route loss.
Initial native focus borrows the existing attached safe-heading landing.
F6 reaches navigation; Shift+F6 reaches the primary action (Cancel during placement).

The bounded navigation popup reaches target/consequence/mode/result summaries and
any choice/review ordinal, including retained-deleted-child notices. Ordinals are
read-only positions within the exact immutable stage, never durable identity.
One pending navigation request borrows the S2C1 read-only lease, rechecks after
its frame, uses synchronous requestScrollToItem, and rechecks pending ownership,
scope, attachment/window and lease before focus. It has no reader or dispatcher.
Disposal, replacement and PAUSE cannot revive a request. Existing shared History
focus recovery handles the removed occurrence after canonical replacement.

## Selection and long-Sequence complexity

Mode selection performs the existing builder once. Unresolved publications share
the immutable choice list and an exact-ID indexed balanced tree. Each selection
updates only O(log M) nodes and an O(1) unresolved count; it neither reads canonical
collections nor calls the builder. When the last choice resolves, the existing
builder reconstructs the complete final proposal once. Immutable publication
identity avoids StateFlow comparing complete proposal graphs on each choice.
The controller retains only its current proposal; no chain of previous proposals
or duplicate historical aggregate is accumulated.

There are bounded lazy rows with type-qualified exact-ID keys. Rendered rows use
indices or once-built canonical ID indexes, never collection-wide lookups.
Root duration calculation operates on the selected final graph. No repository
read occurs during placement/navigation, and existing batched canonical hydration
and transactional loading are unchanged. No global History/Statistics scan or
additional historical writer was introduced.

## Executable evidence and verification ownership

The counted-collection regression reproduced the original per-selection graph
traversal before production changes. It resolves 512 independent choices, requires
zero canonical interval visits for the first 511, checks immutable old publications
and exact final placements/token, and bounds the single final build. Admission
tests cover both terminal states, retained tombstones and invalid/mismatched facts.
Deterministic frame/dispatcher tests cover structural scroll/focus revocation and
attached current requests without writes. Existing builder/domain tests cover
No-live, countdown owners, complete suffixes, pauses and overlap.

Six new HistoryScreenPresentationTest cases cover native choice state and exact
IDs, mode/confirmation cancellation and ownership loss, held Proceed retirement,
ordinary failure/current retry, invalid/stale recovery, committed read-only Retry
and removed-target focus, and 10,000 occurrences with 256 choices and early/middle/
late lazy review navigation. A MainActivityRouteSessionTest checks structural
writer/recreation/session/refresh-gate preservation.

Two new PrimaryExecutionJourneyTest cases use production History navigation and
the existing physical repositories. Leave gap selects an existing visible child
tombstone and requires its earlier deletion metadata unchanged. Close gap chooses
Fixed for ownerless idle, explicitly approves overlap, and checks one exact later
child/pause translation across midnight. Both compare independent prewrite durable
facts after Cancel and while the writer is gated, require one command/token/refresh,
then physically close/reopen and read canonical History, Daily, Activity/Sequence/
global Statistics and Plan. They check frozen Repeat provenance, retained hidden
durable identity, exact intervals, union-derived caches, child date attribution and
fulfilled Plan identity/time. Close gap also injects committed read failure and
requires read-only Retry. EN reduced scale and RU maximum app scales plus 150%
system font are represented.

Existing SequenceHistoryCommandRepositoryTest cases remain the transaction
evidence for stale/active-session rejection, pause-write and Plan-write rollback,
preserved child deletion metadata and batched loading. They complement the new
canonical physical-reopen journeys; DAO comparisons do not replace those readers.
All nine new instrumentation identities are appended to the mandatory inventory.
Older structural instrumentation uses exact lazy keys and fixed-control activation.

Device execution, Parent full/coverage and cross-API gates, manual accessibility
certification and independent exact-head GitHub CI remain Controller-owned.
Compilation/package success supplies no device-test PASS.

## Local verification (2026-11-17)

The final production/instrumentation compilation and packaging invocation passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :domain:test :data:testDebugUnitTest :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain --max-workers=2
```

It completed BUILD SUCCESSFUL (184 tasks). All 220 focused app JVM tests have zero
failures, errors or skips. Unchanged domain/data tasks were UP-TO-DATE; their
reports contain 245 and 36 tests respectively, also with zero failures/errors/skips.
App ktlint, Detekt, Android lint, debug APK and app/data AndroidTest APK packaging
passed. A final follow-up strengthened the existing equal-looking-target test to
assert the structural command's exact occurrence, child and optimistic token;
the same focused app JVM suite plus app ktlint passed again (52 tasks).

The first counted-collection regression failed before the production bookkeeping
change. Intermediate formatting/compilation issues were corrected. Packaging
also exposed a compiler-generated invalid non-local-return marker from an early
return inside Compose's inline key block. Moving the malformed-target guard before
that block removed the marker; bytecode inspection and the subsequent successful
D8/APK assembly verify that repair. The History boundary had no sibling
return-at-key construct. No local check failure remains.

```powershell
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The runner's 74 unit tests passed with mocked device output. Inventory/source
identity checks match HistoryScreenPresentationTest (46), the Sequence repository
integration class (7), MainActivityRouteSessionTest (31) and
PrimaryExecutionJourneyTest (59), without duplicates. EN/RU XML parsing and unique
resource-name checks passed. Git diff whitespace validation passed. No emulator,
managed-device task or GitHub CI was launched. These are local patch-readiness
results; the physical-reopen/native journeys are compiled evidence awaiting
Controller execution, not device PASS or merge approval.

## Canonical API 33 repair (2026-11-17)

Controller's `gmd-api33-app` attempt 1 reached 779 tests and reported two failures.
Both exposed test assumptions, without a production graph or writer defect:

- The Close gap physical-reopen journey compared the translated graph in its old
  interval order. A translated owned interval moves past a fixed ownerless pause;
  the canonical DAO correctly reloads intervals ordered by start time and ID.
  Expected intervals now use that ordering before full list equality. Every ID,
  kind, owner, endpoint and list cardinality remains asserted, as do the later
  durable, duration, History/Daily/Statistics/Plan and physical-reopen checks.
- The overlap integration journey searched for warning text before the lazy
  result-summary row was composed. It now navigates to `summary:result`, then
  asserts the individually tagged native warning is displayed with the exact
  localized text, both before Cancel and after reopening before Proceed. The
  adjacent child-deletion interval equality remains unchanged because that
  operation preserves all interval timestamps and canonical ordering.

The authorized real repair probe passed both originally failing tests on API 33
(2 tests, zero failures/errors/skips; 197.6 seconds; probe
`48a4e2ed5ff84733869cf800d8ddcb74`). Their downstream assertions also completed.
Only whitespace formatting changed in these tests after that probe.

The focused invocation passed all 187 History JVM tests. Its ktlint wrapping
failures in the updated interval assertion were corrected. The final app ktlint,
Detekt and AndroidTest APK assembly recheck passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --console=plain --max-workers=2
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --console=plain --max-workers=2
```

The two affected classes' 59 and 7 test identities still match the inventory;
no new instrumentation identity was introduced by this repair. Git whitespace
validation passed. The scoped probe is diagnostic evidence, not a replacement
for Controller's full API 33 run or independent exact-head GitHub CI.
