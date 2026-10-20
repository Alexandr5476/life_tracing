# Focused Plan Execution accessibility boundary (S4)

Product v0.16, domain v0.10, database v0.6, implementation clarifications and the
existing design system remain authoritative. S4 changes presentation at the
existing focused destination. It adds no runtime command, persistence field,
migration, Plan lifecycle status, scheduling rule or authorization path.

## Presentation and identity

Daily and Plan execution buttons have a contextual accessible name containing
the clicked frozen title and operation. Their callbacks still pass the original
PlanActionIdentity. MainActivity, PlanExecutionRouteSessionOwner,
PlanExecutionRouteExitPolicy, PlanExecutionController and executePlanCommand
retain their ownership. No identity is reconstructed from labels or dates.

The focused heading, frozen title, Activity/Sequence and live/No-live kind, stored
Plan status, target and source state are independently readable. Floating days
and week starts remain literal dates. Exact targets show the exact stored Instant;
when available, an explicitly labeled creation-zone context shows its local tuple
and actual offset. This is audit context, not a replacement calendar projection
or a proposed schedule. A legacy missing creation zone is stated as unavailable;
no local-zone offset is fabricated. Source CURRENT/CHANGED/ARCHIVED/UNAVAILABLE
remains separate from lifecycle. Controller preparation and durable admission
still reject stale identity, engagement and deferred Month execution.

Number and Text retain Material editable semantics. Each label includes the
frozen Field name and unit. Main Value comes only from isMainValue. State
descriptions distinguish unchanged configured default, edited value, Missing
and invalid Number. Invalid input disables Complete/Save; focusing never parses
a rounded presentation back into an override. Category buttons name their Field
and value; popup options name their Field and expose selected state with a visible
localized Selected label. Native popup content explicitly retains the route context,
configuration, density and accessible target minimum, following the S3 window pattern. Missing
actions identify their Field, and the popup's Missing option exposes selection.
Native button/focus/disabled semantics remain intact.

Start and completion overlap choices name their own timestamp, first/second
occurrence and actual UTC offset. Choices expose selected state only after
explicit activation and keep the same Material button instance as appearance
changes. Strict resolution and session edit/zone-review methods remain unchanged.
Time Entry requests retain exact resolved Instants, zone and Field IDs; No-live
never gains a start or duration.

Overlap feedback identifies the exact proposed interval, offsets and zone. Its
Confirm uses the existing session/controller request comparison; Cancel clears
confirmation through resetTimeEntryIssue and preserves the form. Closing Time
Entry remains a separate existing action. Time/offset/zone/Field edits still
invalidate confirmation through the existing session methods. Production writer
revalidation remains mandatory if overlap appears after the initial check.

## Feedback, focus and layout

Infrequent loading, failure, validation and command feedback uses isolated polite
live regions. Stale preparation no longer simultaneously says it is loading.
Preflight has one static polite entry message. Countdown output is passive,
including 0:00 at and after the deadline while waiting for the controller. Neither
the countdown nor any ancestor is a live region; its redundant progress indicator
is hidden from accessibility and exposes no range. The existing 250 ms display
loop is unchanged. No polling, scheduler callback, command or focus request is
added per tick. A saved coordination failure explicitly continues to say Saved
and exposes no Retry.

Toolbar and system Back still share route-exit arbitration. Checking/Preflight
exit closes the owned controller; admitted Committing waits, then the exit policy
delivers the committed result once. Opening Time Entry requests keyboard focus at
its first applicable native input; closing it returns keyboard focus to Back.
Focus state is local and contains no command, identity, countdown or confirmation
authority. It does not claim TalkBack virtual-focus restoration. Category menus
use native popup dismissal. Offset choices are inline native controls.

The heading/Back arrangement wraps with FlowRow. Inputs and choices stack in a
scrollable column and have no fixed text height. The route opts into the existing
LifeTracingAccessibleTargets provider, including the 48 Android dp minimum under
reduced app interface density. Material shapes and clipped indication are retained.
No shared control or target-provider behavior changed. Daily and Plan only gain
contextual names on their existing exact execution buttons. Inspection of launcher,
expanded Sequence, Library, History and Activity/Sequence Template consumers found
no shared API, interaction or model changes; their existing regressions remain
mandatory. English and Russian production resources cover all new descriptions.

No DAO access, durable write, per-Field hydration, history scan or new independent
timer was added to Compose. The new physical-database fixtures capture their own
small isolated database facts; this is test evidence, not production history work.

## Automated evidence and execution boundary

Eight new PlanExecutionScreenTest cases cover heading/frozen context, native
Field editing/default/zero/Missing/negative/fractional/invalid input, exact Category
selection, passive countdown before/at/after zero, material command feedback,
loading/failure/retry/stale reload, and English/Russian scale/focus/zone-review
controls. Existing Activity/Sequence operation cases remain and now check native
Sequence heading/Start semantics. Retry action uniqueness is checked in merged
and unmerged trees. Keyboard-mode focus alone leaves changed/overrides empty;
Enter opens Time Entry without submitting a business command.

Nine new PlanExecutionAccessibilityPersistenceTest cases use a unique physical
database per case, production repositories, PlanExecutionRoute, the actual
session/controller and executePlanCommand. They cover immediate timed Activity
and Sequence start, No-live zero/Missing/untouched rounded default, independent
explicit DST offsets and exact overlap confirmation, stale confirmation after
editing, canceled preflight plus obsolete callback, Back during an admitted
commit, and overlap introduced after the controller check. Successful paths close
and reopen the physical database, compare durable facts, and inspect canonical
Plan/runtime/History/Daily/Statistics where applicable. Live Plans remain PLANNED
and derived engaged; No-live/historical completion fulfills through the existing
transaction. Gates and captured callbacks provide deterministic synchronization;
fixtures use no arbitrary sleeps or shared application database.

Three new Jupiter route-session cases pin exact timed open/completed and No-live
requests, changed-only Field values, invalid/future/reversed times, the exact FINISH
deadline, OVERTIME continuation and retained Field drafts. Existing gap/overlap/
zone-change, stale/source-divergence, preflight, conflict and admission tests remain.
Existing MainActivityRouteSessionTest cases cover Daily and Plan entry/return,
live and No-live commits, stale origin reload, recreation, pending preflight and
blocked commit Back. They are retained, not replaced by this isolated fixture.

All 17 new original-JUnit identities are registered in instrumentation-inventory.json:
627 data, 410 app and 2 independent startup cases per API. The unfiltered API
26/31/33/35/36 gate and independent production-startup verification remain mandatory.
Controller owns authoritative app-unit, gmd-app, lint and exact-head GitHub CI
evidence. Kotlin compilation and APK packaging do not establish device execution.

Local verification on 2026-10-20 completed successfully:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt :app:lintDebug --continue --console=plain
```

The app JVM XML contains 405 cases with zero failures, errors or skips, including
11 PlanExecutionRouteSessionTest cases. AndroidTest Kotlin compiled and the test
APK assembled; app ktlint, Detekt and lintDebug passed. Inventory validation
confirmed all prior HEAD identities remain and both changed focused suites match
their mandatory registrations. EN/RU XML and description placeholders were checked;
git diff --check passed. These are host-side results, not instrumentation execution.

Manual TalkBack and Switch Access validation has **not been performed**. The
original implementation turn used host checks only. The subsequent Controller
API 35 run completed 410 cases with one failure: the retained production-route
fixture still selected a Category option by its old bare label. It now uses the
localized Field-qualified label and native OnClick semantics, checks the option
is initially unselected, and verifies the exact frozen option ID immediately.
Its existing recreation, persisted values, fulfillment, single-execution and
unrelated-live-session assertions remain unchanged.

Repair verification on 2026-10-20 passed instrumentation ktlint and AndroidTest
APK assembly using
`./gradlew.bat :app:ktlintAndroidTestSourceSetCheck :app:assembleDebugAndroidTest --console=plain`.
The authorized `D:\controller\.runtime\agent-tools\repair-probe.cmd` reported
REAL/PASS for
`MainActivityRouteSessionTest#dailyNoLivePlanUsesProductionRouteAndPreservesAnUnrelatedLiveSession`
on API 35 (probe `cb3123c5cfdf41bbb8d8f632c3707ccd/tree-1/attempt-1`).
This scoped repair result does not replace the full Controller compatibility,
startup or exact-head GitHub gates.

## Reproducible keyboard / TalkBack / Switch Access walkthrough

Record tested SHA, API, locale, service version, input method and settings.
Repeat in English and Russian at baseline, system font 200% with app interface
120%/text 130%, and app interface/text 90%/90%. Use disposable fixtures with a
timed Activity, a No-live Activity with Number/Text/Category and a non-first Main
Value, a Sequence with a first effective Step countdown, an ExactDay legacy zone,
a changed/archived source, and completed History overlapping a DST interval.

1. From Daily and from Plan, traverse to the contextual Start/Complete action,
   activate it and verify the intended frozen title, kind, target, Plan status and
   source state. Traverse heading and Back. Check loading, read failure/Retry and
   stale Reload without a second-start affordance.
2. Use Tab/Shift+Tab, Enter/Space, TalkBack traversal/double-tap and Switch Access
   scanning to reach each independent native control. Check localized wrapping,
   scroll reachability, non-overlapping targets and visible focus. Focusing and
   popup opening/dismissal must never start or complete anything.
3. For No-live, hear the frozen Main Value and configured-default state. Enter zero,
   negative/fractional Number, invalid Number, Text, each Category option and Missing.
   Hear selected/Missing states and Field-specific actions. Invalid Complete is
   disabled. Complete once, return to the origin and verify no live session.
4. For timed and No-live Time Entry, check start/optional or required completion
   labels and captured zone. Try invalid input, a DST gap, future and reversed time,
   and FINISH at its exact expired boundary. Confirm Field edits survive failures.
   On 2026-11-01 in America/New_York, enter 01:10/01:50, traverse both sets of offsets
   without choosing, then explicitly select start first (-04:00), completion second
   (-05:00). Hear each context/occurrence/offset and selected state. Edit one time:
   only its obsolete choice clears. Change device zone and explicitly Review;
   retained text must remain visible and incompatible choices must clear.
5. Save an overlapping historical interval. Hear the exact interval, Cancel it,
   edit it, and confirm the current request. Verify one intended completed fact
   and fulfilled Plan, with the selected timezone/offset. The old confirmation
   must not authorize an edited request.
6. Start preflight, read remaining time and wait through zero. Check that the
   screen reader does not announce every tick or move focus. Native Cancel,
   toolbar Back and system Back cancel before admission. During admitted Saving,
   Back waits. On commit return once to the correct origin, with no replay after
   recreation. A coordination failure must clearly say the execution was saved.
7. Close/reopen Time Entry, dismiss Category menus with keyboard/system Back,
   restore the route/session and reload a stale origin. Check usable keyboard and
   service focus without reopening a canceled form or submitting an old command.

Record observed results separately from automated semantics evidence. This slice
is a focused accessibility boundary, not application-wide certification.
