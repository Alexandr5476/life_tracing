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
Configured defaults, draft values and recorded values have distinct labels. Deleted
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

The inventory adds 33 original JUnit identities: 627 data, 389 app and two startup
cases per API. The unfiltered 26/31/33/35/36 gate and separate startup verification
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
