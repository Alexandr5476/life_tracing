# Expanded live Sequence accessibility (S3)

The expanded destination keeps the existing execution ID, route owner, controller,
typed commands, mutation gate, canonical readers and repository transaction boundaries.
There is no accessibility writer, runtime reader, reconciliation trigger, polling
collector, persistent focus state or schema change.

## Presentation and lifecycle

The Sequence heading exposes its frozen name and one of five explicit runtime states.
Active time, pause/idle time, transition countdown, Stopwatch elapsed time, Timer
remaining/target/overtime and recorded duration have labels. Spoken durations include
hours, minutes and seconds; visual durations keep the existing format. A missing
baseline is explicitly unavailable. Timing nodes and their ancestors are not live
regions. The existing baseline and single display tick are reused; zero is display
information, not a reconciliation command. Progress counts retained COMPLETED
occurrences separately from tombstones.

Each lazy row retains its occurrence-ID key. Its heading and menu trigger include
the Activity name and 1-based runtime position; repeat iteration, runtime-added
provenance and each of the five occurrence statuses remain separate readable text.
Commands capture the original occurrence ID, never the spoken position or label.
Configured defaults, current editable values and recorded values have distinct labels. Deleted
executions have no value summary. Childless No-live current rows retain their frozen
Field metadata and defaults/draft without inventing a child or duration.

Number and Text use native editable text semantics and frozen Field name/unit.
Category triggers identify their Field and selected value; options expose selected
semantics. Every Missing button identifies its Field. Invalid Number drafts disable
Complete and Save values; No-live has no standalone Save. Opening a picker or moving
focus cannot save values.

Buttons, menus and dialogs retain Material keyboard and accessibility actions.
The local LifeTracingAccessibleTargets wrapper preserves target space under reduced
interface density. Global controls and selection choices wrap; the Add body scrolls
and the catalog remains a bounded lazy list. The occurrence list remains fully lazy.
Native window content explicitly receives the route's effective Context,
Configuration and Density for localized resources and app appearance. This covers
the global, occurrence and Category menus plus confirmation and Runtime Add dialog
slots; each window retains its own native View and focus owner.
Confirmation and Runtime Add headings belong to their scrollable bodies rather
than an unbounded fixed title slot. Material retains the separate action area, so
long localized headings cannot push Confirm/Add and Cancel outside the dialog.

Menu state is scoped to runtime authorization/status and command admission, so an
already-open menu closes when that context changes. Runtime Add closes when the
runtime state changes, preserving the chosen placement until dismissal and requiring
a new explicit opening rather than silently changing it. Empty placements are safe.
All Add editors, catalog choices and mutation controls are disabled in flight.

Jump confirmation identifies the original target, and End early identifies the
Sequence. Confirmation disappears if its target/action becomes unavailable and is
disabled during a command. Back, outside dismissal and Cancel use the existing
dismissal callbacks. A stale route synchronously removes command surfaces and dialogs
before the asynchronous onStale exit. Refresh/read failure retains Retry. Rejected,
stale-target and committed coordination feedback remain distinct; transient feedback
alone is a polite live region.

Keyboard focus enters at Back on route entry/confirmed content, never on a timer tick.
Menu/dialog traversal and return use native Material/Compose window focus handling.
There is no stored occurrence focus target to restore after removal. MainActivity's
existing system/toolbar Back and exact route-session ownership remain unchanged.

## Automated evidence and limits

ExpandedLiveSequenceScreenTest adds 27 cases to its five existing cases. They cover
all five states and primary restrictions, missing current/baseline, mixed projection,
repeated-name command identity, confirmation required/optional/cancel/stale/in-flight,
all insertion placement values through Do again, Runtime Add selection/validation,
catalog failure/retry/loading/paging, Number/Text/Category/zero/Missing, childless
No-live with/without Main Value, stale menu/route removal, read failure/retry,
distinct feedback, keyboard focus/Enter/Back and English/Russian scaling.

ExpandedLiveSequenceAccessibilityPersistenceTest adds six self-contained physical
database cases. Production authoring, Plan, Library and live repositories establish
an explicitly Plan-linked Sequence. The actual route/controller and native semantics
drive Pause/Resume/Complete/Start next, repeated-occurrence Make next, reusable-Template
Add with Start now and confirmed End early. Close/reopen compares canonical runtime,
children, intervals, snapshots, occurrence identity/provenance and complete fixture
facts. The engaged Plan remains stored PLANNED with the same explicit plan_entry_id;
early end atomically fulfills that same Plan with ENDED_EARLY and matching endedAt.
Timing cases use real canonical graphs and captured baselines with fixed display
ticks before/at/after Timer zero and running/paused transition countdown. Display
updates are compared against unchanged durable facts.

Existing controller action-matrix, confirmation, serialization, mutation-gate,
10,000-occurrence lazy composition, route-session, runtime recovery, Plan linkage and
S1/S2/S2C1 tests remain mandatory. Existing MainActivityRouteSessionTest covers Daily
entry, system Back, return to the same live execution and retained route identity.

Structurally, progress is one O(N) pass remembered by occurrence-list identity.
Confirmation performs one O(N) target lookup per composition while open. Row
descriptions use only that row's canonical projection and Field metadata: no per-row occurrence rescan,
database query or eager occurrence materialization was added.

The original S3 inventory added 33 JUnit identities, reaching 627 data, 389 app and
two startup cases per API (see S3C1 below for current totals). The unfiltered
26/31/33/35/36 gate and separate startup verification
remain mandatory. Controller owns device execution and final exact-head CI evidence.
Source/APK compilation is not evidence that instrumentation assertions passed.
No TalkBack or Switch Access session was performed during implementation.

Local preparation passed in the uncommitted worktree:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug :app:ktlintCheck :app:detekt :data:ktlintCheck :data:detekt --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The app JVM run executed 401 tests with zero failures, errors or skips. The Python
evidence-runner suite passed 17 tests using mocked device invocations; it did not
launch emulators. AndroidTest APK assembly passed. All prior mandatory inventory
identities are retained. These are implementation-local results, not Controller's
exact-head CI or device results.

Controller's first API 35 run executed all 389 app cases and reported five failures.
The repairs address lazy-item discovery before node assertions, Category popup
selection scoped independently from its trigger, and a persistence-fixture wait
that incorrectly required Make next to advance updatedAt. The fixture now waits
for the full canonical projection after the observed write; identity, provenance
and physical-reopen assertions remain intact. Scaled control bounds are measured
after scrolling, in the same layout. The authorized repair probe was attempted
but rejected before execution with "No active repair-probe lease." Device repair
results and the complete compatibility gates therefore remain unverified.

Repair preparation passed `:app:assembleDebugAndroidTest :app:ktlintCheck
:app:detekt --continue --console=plain` and `git diff --check`. All 32 screen
and six persistence testcase identities and their inventory entries are retained.

Controller's focused retry passed four of the five repaired cases. The subsequent
isolated scaling retry still failed the Russian popup assertion after native
OnClick activation. Inspection of the bundled Compose implementation established
that PopupLayout uses the host View's Context and WrappedComposition installs its
platform locals in the new window. A parent-only locale/density override therefore
does not configure that window's content. The destination now re-provides its
effective presentation inside all five native window boundaries. The scaling
regression retains both languages and display/enabled checks, and additionally
checks localized occurrence actions and End early confirmation with cancellation
and no writes. The authorized repair probe again refused execution with "No active
repair-probe lease" before any testcase ran; device confirmation remains pending.

After the window-presentation repair, `:app:assembleDebugAndroidTest`,
`:app:ktlintCheck`, `:app:detekt`, `:app:testDebugUnitTest` and `:app:lintDebug`
passed together. The app JVM report contains 401 tests with no failures, errors or
skips. `git diff --check` passed. These checks do not establish a device pass for
the revised scaling regression.

Controller's next isolated run passed the Russian popup and Runtime Add portion,
then failed the displayed-Cancel assertion in End early confirmation at combined
scales. The fixed Material title slot could grow ahead of the action area. Both
dialog types now keep their accessible heading in a scrollable body. The existing
scaling regression checks distinct, displayed and enabled confirmation actions
before and after scrolling the message, for both End early and occurrence-specific
Go now, then cancels without writes. The repair probe again returned "No active
repair-probe lease" before executing a testcase; this layout repair still requires
Controller device confirmation.

For this layout repair, AndroidTest APK assembly, app ktlint and Detekt passed
after correcting test-helper formatting. The focused
`ExpandedLiveSequenceControllerTest` suite passed all 12 cases; app lint completed
with zero errors and 29 warnings. `git diff --check` passed. No device or manual
screen-reader pass is inferred from these checks.

The following isolated retry completed both localized menu/dialog flows and failed
only the final reduced-interface-scale size assertion. That assertion compared the
button's visual `boundsInRoot` with the native 48 Android dp target requirement.
It now uses `touchBoundsInRoot`, matching the accepted Daily/launcher checks and
the native target provider. The 48 dp height threshold is unchanged, width is also
checked, and the same reduced-scale control must be displayed, enabled, open by
native OnClick and dismiss without a write. Primary-control separation likewise
uses touch targets. Production shared sizing behavior is unchanged. The authorized
probe again refused execution before tests with "No active repair-probe lease";
the revised target assertions still require Controller verification.

The target-assertion repair passed `:app:assembleDebugAndroidTest :app:ktlintCheck
:app:detekt --continue --console=plain` and `git diff --check`. No instrumentation
testcase identity or inventory count changed.

## S3C1 corrective presentation

Current drafts are labeled **Current editable values** / **Текущие редактируемые
значения**. This describes both canonical hydration and retained post-save drafts
without asserting that either is dirty. Configured defaults and recorded actual
values retain separate labels. Draft retention, edit admission and Save/Complete
commands are unchanged; editing alone performs no write.

Each Field summary exposes its context, frozen name/unit, localized Field type and
exact formatted value, including Missing versus numeric zero. Only the frozen
`ActivitySnapshotField.isMainValue` flag adds the Main Value designation. It is
visible and accessible in configured, childless No-live current, timed current and
completed states. Childless rows still use defaults/editable values and have no
invented execution or duration; completed summaries use actuals even when defaults
differ. The same frozen Field label marks Main Value in native editors and their
Field-specific Missing controls. Native editable/clickable descendants remain separate.

Reusable catalog buttons announce name, tracking mode and 1-based catalog position.
Positions disambiguate otherwise identical metadata and remain stable as the
existing controller appends pages. Native buttons retain one action each and
capture the clicked `ActivityTemplateId`; position and name never drive dispatch.
There are no extra projections, per-item reads, persistent flags or writes.
Only expanded Sequence resources and presentation changed; Daily, launcher and
shared accessible-target components keep their existing labels and behavior.

Two new screen cases cover the English/Russian Main Value projection matrix and
duplicate catalog controls, including a later page, focus without activation,
merged/unmerged action identity and exact Template/placement dispatch. The existing
in-flight case additionally attempts a disabled catalog touch. Two physical cases
use real repositories/controller commands to hydrate zero/Missing, edit without a
write, Save with exact Field IDs, retain the draft after canonical publication,
optionally Complete, then reopen and compare canonical values, frozen snapshot and
durable facts. The existing Runtime Add physical case now selects between distinct
Templates with identical names and preserves source, placement, runtime-added
occurrence and child/snapshot provenance after reopen. A new JVM controller case
gates command confirmation explicitly and verifies canonical hydration and draft
retention after refresh.

The inventory now includes 34 expanded screen and eight expanded persistence cases:
627 data, 393 app and two independent startup identities per API. Existing mandatory
identities are retained. Device regression execution, including fail-first evidence,
the Controller-owned app-unit/gmd-app/lint gates and independent exact-head five-API
GitHub verification remain Controller responsibilities. No local device task or
manual TalkBack/Switch Access validation was performed for this slice.

S3C1 local preparation passed:

```powershell
cmd.exe /d /s /c "gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug :app:ktlintCheck :app:detekt --continue --console=plain"
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The app JVM reports contain 402 tests, including 13 expanded controller cases,
with zero failures, errors or skips. AndroidTest APK assembly, app ktlint and Detekt
passed; lint reported zero errors and 29 warnings. The 17 Python runner tests use
mocked device invocations and passed without starting a device. An explicit source
and inventory comparison confirmed all 34 screen/eight persistence method names
and preservation of every prior mandatory identity. These results do not establish
that the newly compiled instrumentation assertions passed on a device.

## PR38 W1: canonical reload after a successful read

Expanded Sequence now derives command authority from a confirmed projection: a Sequence
must be present, with no loading, read failure or stale-route flag. The screen removes
runtime command surfaces, value editors, menus, Runtime Add and confirmations while the
latest canonical read is pending or failed. Back remains available and a failed read
exposes Retry with existing localized polite feedback. Timer display has no announcement
loop and is not collected for an unconfirmed projection.

The controller uses the same guard for every command and current-value edit. Refresh
invalidates pending confirmations synchronously; publication, refresh invalidation and
command/confirmation capture share the existing capture lock. Superseded reads cannot
restore authority. Recovery never replays a confirmation or restores an open popup/draft.
Commands already admitted before refresh retain their existing mutation-gate ordering.
There are no new readers, polling loops, domain commands, schema or durable facts.

Two JVM regressions cover the five-state admission matrix and superseded read publication.
The five new Compose cases cover open occurrence/global menus, Go now/End early
confirmations and Runtime Add, including callbacks retained from their previous semantics.
They inspect enabled actions in merged/unmerged trees, polite failure feedback and native
Android keyboard Enter activation of Back, Retry and recovered Resume. Five real controller
and repository cases establish each canonical state in an isolated Plan-linked physical
database, reject callbacks during held/failed refresh, recover after an explicit external
runtime change, execute only the freshly authorized command, and compare full durable
facts, occurrence/snapshot identities and canonical Plan/runtime reads after close/reopen.
All ten instrumentation cases are added to the mandatory inventory without removals.

Fail-first JVM execution on the reviewed production code failed as expected: a callback
admitted a command during RUNNING_CURRENT refresh (`commandInFlight` became true).
Local W1 preparation on 2026-10-21 executed 408 app JVM tests, including all 15 expanded
Sequence controller tests, with zero failures, errors or skips. Android lint reported
zero errors and 32 warnings. The combined preparation invocation needed subsequent
test-formatting corrections; after those corrections this command passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest '-Pkotlin.incremental=false' --console=plain --continue
```

The earlier full app JVM/lint task invocation was:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest '-Pkotlin.incremental=false' --console=plain --continue
```

Incremental compilation initially reported unresolved references to unchanged top-level
functions; disabling incremental compilation allowed compilation and JVM execution to
complete. The final formatting-only JVM edit does not change the tested behavior.
The mocked instrumentation-runner suite passed all 17 tests. Inventory/source comparison
confirmed 39 expanded screen and 13 persistence method identities, 441 total app cases,
627 data cases, and no removed mandatory identities. `git diff --check` passed.
No local preparation result establishes the Controller's exact-head or five-API gates.

The Compose/database regressions were added for Controller device execution; this work
does not claim fail-first or corrected instrumentation execution.

For manual reproduction, extend walkthrough step 8: first load each of the five live
states, open the corresponding occurrence/global menu, Jump/End early confirmation or
Runtime Add draft, then hold and fail a canonical reload using a controlled read gate.
With TalkBack, Switch Access and a hardware keyboard, verify only Back and then Retry
are actionable; old popup/confirmation actions must not execute. Retry after an explicit
runtime state change and verify only the newly permitted actions return, with no replay.
Manual TalkBack/Switch Access validation remains unperformed.

## PR38 W2: ephemeral context through foreground revalidation

`LifeTracingApplication.onActivityStarted` calls `AndroidRuntimeCoordinator.onForeground`,
which invalidates semantic generation even when canonical runtime facts are unchanged.
The screen now retains an in-memory Runtime Add draft, dialog scroll and native editor
focus separately from `confirmedSequence`. The controller authority guard is unchanged:
loading/failure/stale presentations expose no runtime commands or editors. No polling,
reader, durable state, domain command or coordinator lifecycle behavior is added.

Only successful revalidation with the same execution, current occurrence and runtime state
can restore this context. Failed reads, stale routes, changed current/state and invalidated
placement discard it. Cancel/Back clears the draft explicitly. Pending Jump/End early
confirmations and menus are never restored. Disposed Add/input callbacks cannot submit or
edit a revalidated form/current occurrence. Route-entry scrolling/focus uses retained
execution identity, so transient loss of command authority does not reset the list/header.
Native focus is restored only when the corresponding authorized editor or occurrence
control is composed again; ordinary user focus changes clear the remembered target.

The seven new screen cases and three physical-database cases are mandatory in the five-API
inventory. The physical cases hold a semantic-generation read after successful loading,
check a named Timer draft with 137 seconds and After current placement, and check Number
and Text focus at occurrence 41 of 80 without scrolling again after revalidation. They
assert zero restoration writes; explicit Add writes once to the original execution with
one-off provenance, and physical close/reopen preserves canonical facts, frozen Plan
linkage and unrelated history. Existing W1 held/failure/stale callback tests are retained.
An additional JVM controller case checks draft retention, edit/write rejection while
unconfirmed, and permanent pending-confirmation invalidation.

The Compose regressions were written before the production correction. Device fail-first
and corrected execution are reserved for the Controller; local compilation cannot certify
those assertions. To reproduce fail-first, apply only the new tests/fixture changes to
head `fcd22da1c7a27021a3e9d279f7e559f30a73fd6f`: the draft case loses its form and the deep
focus cases reset to the header. Run corrected cases and then the full unfiltered gates
at APIs 26/31/33/35/36 plus exact-head CI before acceptance.

Manual check (pending): with TalkBack, Switch Access and a hardware keyboard, open Runtime
Add, enter a nondefault Timer name/seconds/placement, focus seconds, background/foreground
without changing runtime, and verify the same draft/focus returns after revalidation with
no command until explicit Add. Repeat with a current Number/Text Field deep in a long
Sequence. Cancel/Back must stay dismissed. While a read is held or failed, only Back/Retry
may be actionable. Change current/placement authority or end the Sequence while away and
verify old input/menus/confirmations do not return to an unauthorized target.
Repeat in EN/RU with the scales in the walkthrough below. Record API, service versions and
observations; manual assistive-service validation remains unperformed.

### W2 local verification

On 2026-10-22 (Europe/Moscow), the final single-worker invocation passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --continue --no-parallel --max-workers=1 --console=plain
```

App JVM XML contains 409 tests with zero failures/errors/skips. AndroidTest sources and
APK compiled/assembled successfully; lint reports zero errors and no focus-helper warning.
The 17 mocked instrumentation-runner unit tests also passed. An explicit identity audit
matches all 46 expanded screen and 16 physical-persistence methods against the mandatory
inventory (451 app, 627 data, 2 startup). `git diff --check` passed.

An earlier combined build timed out in two unchanged controller tests. The full limited-worker
JVM rerun and the final single-worker pass both passed all 409 tests; no assertion or timeout
was weakened. Device fail-first/corrected runs, the five-API suites, exact-head GitHub CI and
manual TalkBack/Switch Access validation remain unperformed here and are not certified by
these local checks.

### Native key synchronization after the Controller API 35 run

The Controller's full run reported three early Back-counter assertions, one early native
Back-dismissal assertion, then an Activity-window attachment crash. Compose focus/idle is
not a barrier for Android window-focus transfer or completion of an injected key event.
The expanded-screen fixture now waits for the intended attached native window to own input
focus, injects exactly one key, and waits for the observable counter/loading/dismissal
outcome before retaining every original assertion. The same helper covers adjacent native
Back checks for the scaled popup, confirmation dialog and explicitly dismissed Add draft.
No production command, focus-restoration policy, assertion or testcase identity changes.

The original crash log separately showed SurfaceFlinger reaching `MAX_LAYERS (4096)`,
followed by server-side `Surface$OutOfResourcesException: NO_MEMORY`, then the client's
`Adding window failed` / `bad array lengths` while the test Activity attached. The unchanged
tree passed all five reported cases in the first authorized real-device repair probe.
After native-key synchronization, the second probe again passed all five, with zero
failures/errors/skips. Controller evidence is under
`.runtime/repair-probes/wf-2d37dc47821f590fa8e9b475df962789/0f22635c2e164ae09c02a0a65527fde9/`
(`tree-1/attempt-1` and `tree-2/attempt-1`). These scoped passes do not certify the
unfiltered suite after the original run-level surface exhaustion.

Local app ktlint, Detekt, AndroidTest APK assembly and all 16 expanded-controller JVM tests
passed. All 46 expanded-screen identities remain required. The full five-API gate and
manual TalkBack/Switch Access validation remain Controller/release obligations.

## API35 recovery viewport and edited-draft Back prerequisites

The subsequent unfiltered Controller run completed all 451 app cases and reported three
expanded-screen failures. Two shared a recovery fixture that looked for Resume without
scrolling back from an occurrence menu/Jump target. W2 deliberately retains that viewport;
the fixture now uses the list's accessible scroll-to action to reach Resume before checking
enabled state, native focus and exactly one Resume command. All five runtime-state variants
share this correction; the retained-scroll behavior in production remains unchanged.

The edited-draft Back failure's log records `HIDE_SOFT_INPUT_BY_BACK_KEY`: Android consumed
the injected key to hide the IME. The fixture now focuses Cancel and closes the software
keyboard before injecting the single dialog-dismissal Back key. It still asserts dismissal,
no draft resurrection after foreground revalidation, empty values on reopening and zero
commands. Adjacent popup/confirmation Back cases do not enter text or show an IME.

The real authorized API35 repair probe passed all three reported cases (three tests,
zero failures/errors/skips). Evidence:
`.runtime/repair-probes/wf-2d37dc47821f590fa8e9b475df962789/0ee2ed2d9e30461790b801cc44000534/tree-1/attempt-1/`.
After a formatting-only Espresso import correction, local verification passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests com.alexandr5476.lifetracing.live.ExpandedLiveSequenceControllerTest --no-parallel --max-workers=1 --console=plain
```

The controller JVM result contains all 16 tests (Gradle cache reuse). This scoped probe
does not replace the unfiltered five-API or exact-head CI gates.

## PR38 W2 pre-disposal command admission

Runtime Add closes its ephemeral dialog only when the existing controller admits
the command under its command-capture lock and FIFO mutation gate, or on explicit
Cancel/Back. Loading and in-flight attempts do not consume input or publish a
transient rejection. Both one-off and reusable Template callbacks supply the shown
execution/current-occurrence/runtime-state context; admission checks it against
current confirmed authority as well as placement. Dismissal immediately revokes
dialog callbacks, including the interval before Compose disposal; disposed callbacks
remain inert.
This changes no domain commands, persistence, read shape, polling or native editors.

Two new mandatory physical reopen regressions capture enabled Add with a Timer draft,
137 seconds and After current placement: one-off Add and reusable Template selection.
The original semantic-generation foreground-refresh regression remains required.
Each new case calls refresh and the old callback in one UI turn before composition
disposal, checks no admission or
write, physically reopens the database with unchanged complete durable facts, then
revalidates the identical Sequence and explicitly commits one Add. Checks include
frozen snapshots, occurrence provenance, Plan linkage, unrelated history, retained
input/focus and inert old callbacks after recovery/commit. Existing changed-current,
invalid-placement, failed/stale-read and Cancel/Back cases now exercise captured Add
callbacks too. The new controller JVM regression was observed failing before the fix
because the unconfirmed attempt published rejection feedback.

Local verification on 2026-10-22 passed all 411 app JVM tests (18 controller tests),
app ktlint, Detekt, lintDebug, AndroidTest APK compilation/packaging and all 17 mocked
inventory-runner tests. The inventory contains 453 app, 627 data and two startup
identities. Commands:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --continue --no-parallel --max-workers=1 --console=plain
python -m unittest discover -s .github/scripts -p test_run_instrumentation.py
```

Device fail-first/corrected execution and all five unfiltered API suites plus
exact-head CI remain Controller-owned. AndroidTest compilation does not establish
these tests passed on a device. Manual TalkBack/Switch Access remains unperformed.

## Reproducible device walkthrough (manual, pending)

Use a fresh fixture with a repeated Activity, Stopwatch, Timer allowing overtime,
No-live with a Number Main Value, Text/Category Fields and at least three upcoming
Steps. Enable confirmation for Jump and End early. Repeat in English and Russian,
at system font 200% plus app interface 120% / text 130%, then reduced app scales.

1. Enable TalkBack. From Daily, focus the active Sequence and activate Expand.
   Read the Sequence heading/state, progress and labeled active/pause times. Use
   heading navigation through repeated Steps and verify position, iteration,
   runtime-added and status text distinguish them. Wait across Timer zero; verify
   the focused control stays put and time is not repeatedly announced.
2. With a hardware keyboard, use Tab/Shift+Tab, arrows, Enter/Space and Back.
   Pause and Resume the Sequence. Complete a current Step and inspect Waiting next;
   Start next. Inspect running and paused countdowns: no Complete action should
   target a fabricated child, and paused remaining time should stay frozen.
3. Focus current Number/Text/Category controls. Enter invalid Number text, zero,
   Missing and a Category choice; verify Field-specific names and selected choices.
   Focus/open/close controls without committing, then explicitly Save or Complete.
   On No-live, verify Complete is available and standalone Save is absent.
4. Open a repeated upcoming Step menu. Cancel Go now confirmation, reopen and confirm;
   verify the announced target remains the chosen occurrence. Exercise Make next
   and completed-Step Do again at the offered placements. Dismiss menus with Back;
   inspect that no action replays and no removed Step receives restored focus.
5. Open Add. Inspect placement selection, reusable loading/failure/Retry/Load more,
   then select a reusable Activity. Reopen for a one-off: edit Name, choose each mode,
   reject an invalid Timer target, then add a No-live configuration. Cancel another
   draft. While an Add dialog/menu is open, pause/change the canonical runtime using
   the fixture's explicit command; obsolete actions must disappear or disable.
6. Open End early; inspect the Sequence name and separate Cancel/Confirm controls.
   Dismiss with Back, reopen and confirm once. Verify return to Daily, retained
   history and fulfillment of the explicitly linked Plan.
7. Repeat the core path using Switch Access next/previous/select scanning. Verify
   Back, global/occurrence menus, each Field/Missing control, placements, modes and
   dialog buttons are separate reachable targets. Check focus return after dismissal
   and that no focus event starts a command.
8. Exercise initial loading, recoverable read failure/Retry, stale route exit,
   rejected/stale-target feedback and a committed command with coordination recovery
   using controlled fixture gates. Ensure the committed case says it was saved.

Record device/API, service versions, locale/scales and observed results before
claiming manual screen-reader validation. The steps above are a walkthrough, not
a completed certification.
