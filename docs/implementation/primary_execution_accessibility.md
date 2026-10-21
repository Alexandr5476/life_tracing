# Primary execution route continuity (S5)

Product v0.16, domain v0.10, schema v0.6 and the implementation clarifications were
read with the committed S1?S4 implementation. Navigation still uses production
NavDisplay, route-session owners, controllers, the runtime mutation gate and repository
commands. No business admission, cancellation, deadline, snapshot or durable-state
policy changes. No schema change or new destination.

## Corrections

Daily provides a stable keyboard landing at Previous day on route entry and when a
loaded active surface that owned keyboard focus disappears on the same selected date.
A surviving control retains its existing focus (see the S5C1 correction below). Start Activity and focused
Plan Execution land at their existing Back control unless a retained editor owns focus.
Plan and completed History land at their existing Back controls. Native nested-editor,
menu and dialog behavior remains in use. Existing S2 editor/preflight return and S3
expanded Back focus remain intact. Date changes reset the terminal-recovery marker.
The focus-only visibility input comes from the actual top back-stack entry, including
exact focused Plan/Sequence route identity. Outgoing animation content cannot request
a new landing; return restores it even when that composition was never disposed.
Lazy launcher/expanded headers scroll into composition before requesting Back focus.
Neither timer ticks, same-runtime reads nor unrelated recomposition request focus.
Focus changes perform no execution command and never target an occurrence or timer.

Committed coordination failures previously disappeared when launcher/Plan result delivery
closed the focused route. Both callbacks now forward their already-committed feedback
to Daily's existing Coordination failure state before releasing the session. Daily's
existing localized saved/recovery message remains readable across route re-entry and
Activity recreation. Delivery and transaction ownership stay exactly once. The feedback
handoff does not coordinate or retry; the next explicit runtime command uses the normal
controller path and clears old command feedback in its existing way. Plan non-live
completion never invokes runtime coordination and retains its PLAN return behavior.

Shared accessible targets, motion, localized strings, timer collectors and runtime
writers are unchanged. The Plan/History changes attach focus only to their existing
navigation Back buttons; management, correction and authoring remain out of scope.
Library, editors, Settings and Statistics retain their existing navigation callbacks
and shared components. No DAO reads from Compose, history lookup for focus, Field
hydration or eager Sequence loading was added. Focus bookkeeping is O(1).

## Automated coverage

`PrimaryExecutionJourneyTest` contains 13 mandatory original-JUnit cases. Every case
owns a disposable production database and a platform fixture lease, launches the real
MainActivity and cleans up its Activity, connections, database and platform resources.
Normal journeys use the production graph without command substitution. Two failure
cases retain the ordinary production controller in the production session owner and
control only a read failure, commit gates and the coordination-failure port; their
writers call the production execute functions and real repositories. Deferred gates,
canonical state and bounded waits replace sleeps. No business action uses a test tag,
screen coordinate or accessibility-specific controller.

| Journey/boundary | Evidence |
| --- | --- |
| Daily ? pinned Activity ? Daily ? Pause ? History/Back ? Resume ? Finish ? History detail/Back | Same execution/snapshot, one closed pause, no stale actions or duplicate root; physical reopen of History, Daily, runtime and Series |
| Daily ? pinned Sequence ? compact ? expanded ? Pause ? Back/re-enter ? Resume ? Complete ? waiting ? Back/re-enter ? Start next ? normal completion | Same execution, snapshot and occurrence IDs; explicit absent current in waiting; canonical children and source Step links after reopen |
| DAILY Plan timed Activity | Native contextual Plan action, exact PlanActionIdentity, frozen snapshot, stored PLANNED/derived engaged, retained recreation, terminal fulfillment |
| PLAN Plan Sequence | Exact frozen linkage, first current, waiting, native Runtime Add/Start now, new snapshot/source/child provenance, End early cancel/confirm, same explicitly fulfilled execution |
| DAILY and PLAN No-live | Native contextual Plan actions, explicit numeric zero on the frozen Main Field, missing duration, no ActiveSession, correct return and completed History |
| Idle/preflight/Time Entry/stale | Toolbar and system Back, real keyboard Enter, retained preflight recreation, no selection replay or durable residue, Time Entry's existing Back to Plan action, cancelled stale target and origin reload |
| Read failure/Retry and admitted commit | Plan read error is text and Retry is native; both launcher and Plan retain Committing through Back/recreation, write once, then retain committed coordination feedback at Daily |
| Appearance | Real production LifeTracingApp/NavDisplay Activity journey in Russian at system font 200%, interface 120%/text 130%, and English at interface/text 90%; required controls scroll into view and reserve native 48 Android dp targets |

Scale cases scope Configuration/Context/Density and app appearance around the production
composition; they do not change device settings. Baseline journeys use the normal
MainActivity composition. These cases assert keyboard/semantics behavior, not
TalkBack virtual focus; device execution remains Controller-owned. Async boundaries wait for canonical readers or visible enabled
semantics, including History detail readiness.

Each completed journey captures all durable fixture facts, root discovery, Daily and
global Statistics before closing its physical connection and the production live owner,
then reopens Room and compares them. Assertions also check root-zone/date attribution,
execution counts, frozen identities, missing duration/zero, Series counts, child and
runtime-added provenance, and linked Plan fulfillment. The database is bounded fixture
evidence; whole-fixture capture is test-only. This is connection-reopen and supported
Activity-recreation evidence, not a process-death simulation.

`DailyScreenPresentationTest.accessibilityKeyboardFocusRecoversOnTerminalRemovalWithoutFollowingTicksOrRefreshes`
keeps keyboard focus on Finish before/at/after Timer zero and on History through a read
refresh/date change or hidden transition, then checks stable recovery when active controls disappear, without
any dispatched action. `DailyControllerTest.committedRouteCoordinationFailureSurvivesReentryWithoutExecutingOrCoordinatingAgain`
checks retained feedback with zero commands or coordinator calls.

The original suites remain mandatory and cover the rest of the matrix:

- S1 Daily action/announcement/error/timing matrices and physical paused duration.
- S2/S2C1 nested Browse Checking/preflight cancellation, rejected/stale/conflict reads,
  obsolete callback rejection, zero/Missing/default and exactly-once result policies.
- S3/S3C1 all five Sequence states, transition countdown running/paused and exact deadline
  boundaries, repeated Step/Repeat Make next, runtime source selection, current Field
  values and large-Sequence rendering bounds. Display ticks compare unchanged facts.
- S4 original-source states, live PLANNED/engaged after physical reopen, Missing versus
  zero and configured defaults, frozen schema, independent DST endpoints, overlap
  admission/revalidation, no-live completion and retained committing routes.
- MainActivityRouteSessionTest retains the real stale expanded-route recreation, Plan
  origin/live/no-live returns, late conflict, preflight and blocked-commit cases.
  MainActivityNavigationTest retains missing/mismatched-session normalization without
  acquisition. Runtime recovery, serialization and large-history checks stay required.

## Verification record

Initial implementation checks covered source/APK compilation and JVM tests; device
execution remained Controller-owned. Compilation alone does not establish an
instrumentation PASS. The subsequent device repair evidence is recorded below. No TalkBack or Switch Access
validation was performed. Final local verification on 2026-10-20 passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt :app:lintDebug --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The app JVM XML reports 406 tests, zero failures/errors/skips. AndroidTest Kotlin
compiled and the APK assembled; app ktlint, Detekt and lintDebug passed. The Python
runner suite passed 17 tests with mocked Gradle/device invocations, without starting
an emulator. A separate source/inventory comparison verified exactly 14 additions,
all preceding identities retained, and matching nonignored original-JUnit names.
Intermediate local checks caught formatting, a snapshot-property name and a trailing-
lambda signature mismatch; these were corrected before the successful final run.

Controller must run `full` and `gmd-full`, independently verify installed production
startup, and run the mandatory exact-head GitHub matrix on 26/31/33/35/36. Inventory:
627 data, 431 app (including S5C1), 2 startup per API. S1?S4 identities are preserved. Parent completion
requires the separate Decomposer closure audit after S5.

## API 35 canonical failure repair (2026-10-20)

Controller's first `gmd-full` execution at head
`f9df53ef4a0a5dca1fc513a1048c87f247aa0495` ran all 424 app cases and reported
nine failures, all in PrimaryExecutionJourneyTest. The shared fixture now:

- Uses a millisecond-aligned seed clock and captures each expected PlanActionIdentity
  from the canonical focused Plan reader before navigation. Full identity assertions
  and optimistic cancellation checks retain every token component.
- Supplies MainActivity's retained owners when wrapping its production composition
  for appearance tests, including the History owners observed by readiness assertions.
- Settles Compose navigation before subsequent Back input and waits for the focused
  Plan heading even when the fixture deliberately injects a read failure.
- Seeds the Number default as `7_000`, which is a visible seven at display precision
  zero. The earlier `7` meant 0.007 at fixed storage scale 1,000 and already displayed
  zero; submitting unchanged zero correctly retained that configured default. The
  repaired path asserts visible seven/unchanged draft, native focus/SetText, and the
  exact changed Field/zero override before Complete, then verifies persisted zero.

These are fixture corrections; production commands, focus implementation, persistence
policies and all 424 inventory identities are unchanged. No failed assertion was
removed or relaxed. The first authorized real-device probe passed seven of nine;
a second pre-submit diagnostic exposed the untouched rounded default in both No-live
origins. The third authorized probe passed **all nine cases**, zero failures/errors/
skips, in 207.4 seconds. Its original JUnit XML was independently counted:

`.runtime/repair-probes/wf-2d37dc47821f590fa8e9b475df962789/3851935d7fc4431fb90643daf3d8df82/tree-3/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`

The repaired source also passed app ktlint, Detekt, AndroidTest APK assembly and
lintDebug (native exit code zero):

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
```

A source/inventory comparison retained all 13 journey names and totals of 627 data,
424 app and 2 startup cases. `git diff --check` passed. No production code changed
in this repair, so the original implementation JVM results above were not rerun.

This is scoped API 35 repair evidence, not an unfiltered `gmd-full` PASS. Controller
still must rerun the full suite and the independent startup/five-API exact-head gate.
Manual TalkBack and Switch Access validation remains unperformed.

## S5C1 duplicate Plan targets and conditional focus recovery (2026-10-21)

Daily and Plan execution buttons now include the frozen title, Start/Complete operation,
Activity/Sequence kind, and grounded scheduling context. FloatingDay includes its date;
Week includes its Monday; ExactDay uses the canonical projected date/time and only its
supplied occurrence offset. Missing local projection falls back to the stored Instant,
without inventing a zone. Identical complete descriptions receive localized duplicate-
group ordinals (Plan 1 of 2 / Plan 2 of 2) in current presented order. Labels recalculate
with section contents and locale. EN/RU resources carry all new prose. Management
buttons, frozen snapshots, targets, source eligibility, status and authorization stay
unchanged. Every execution action is still one native button with one activation.

The shared helper processes each presented section in O(N) time and O(N) temporary
space. Daily uses its combined day/week Planned section; Plan uses its selected-day
and week sections separately. Grouping uses final localized descriptions so display
collisions also receive ordinals. Neither ordinal nor list position selects commands:
each unchanged callback captures the original PlanEntry.actionIdentity(). Only the two
execution surfaces consume the helper. No new reads, queries, history scans, persisted
fields, timers, collectors or writes are introduced.

Daily observes native focus on the local active subtree and surviving control groups.
Removal recovers Previous day only when that runtime owned focus. A pending marker
can span the Controller's transient Loading read, but a surviving control taking focus
cancels recovery. Canonical terminal publication consumes the marker; date/visibility
changes discard it. Route entry still lands on Previous day. Display ticks, unchanged
runtime reads and recomposition never request landing focus. Visibility remains guarded
by the existing MainActivity top-route identity. Focus callbacks dispatch no commands;
focus bookkeeping is O(1), ephemeral and independent of assistive-service virtual focus.

Seven new inventory identities plus the retained Timer regression cover:

- Daily/Plan duplicate frozen Activity titles, same-source/different-schedule Plans,
  identical schedule duplicates, Activity/Sequence kind, Start/Complete, and changed,
  archived and unavailable sources in EN/RU. Tests traverse without executing, inspect
  merged/unmerged native actions, activate every eligible button and check exact tokens.
- Date/week replacement refreshes the labels and engaged/fulfilled/cancelled Plans stay ineligible.
- Both production navigation origins present two same-source/same-day Plans and a
  same-source Week Plan. They select the intended duplicate by its distinct spoken
  control, retain the exact route-session token, launch its frozen snapshot, complete,
  physically reopen, and verify only its Plan linkage changed.
- Finish dispatches once across Loading/terminal publication; Timer FINISH-like and
  Sequence waiting/final-Timer removal preserve History focus or recover a runtime-owned focus.
  Ticks, refreshes, date changes, hidden content and reentry retain their assertions.

Initial S5C1 verification covered local implementation checks only. The first
Controller `gmd-full` execution and scoped repair are recorded below; the unfiltered
suite must be rerun after repair. Controller still owns `full`, independent startup
and the unfiltered exact-head five-API GitHub matrix. Current mandatory inventory: **431 app,
627 data, 2 startup per API**, with all prior identities retained. No device PASS
is inferred from the following local implementation checks.

Final local verification passed with native exit code zero:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The final JVM XML reports **406 tests, zero failures/errors/skips**. All changed
AndroidTest sources compiled and the test APK assembled. App ktlint, Detekt and
lintDebug passed. The final rebuild could not connect to the Kotlin daemon, then
completed successfully through Gradle's fallback compiler (7m 6s). The host runner
suite passed **17 tests using mocked device/Gradle invocations**; no emulator or
device task ran. Independent inventory/source validation confirms exactly seven new
nonignored AndroidTest identities, every original identity retained, and totals of
431 app / 627 data / 2 startup. Both new EN/RU resource sets parse with intact localized
text. Initial local checks caught resource payload encoding and formatting issues;
these were corrected before the passing checks. Source/APK checks remain distinct
from Controller-owned execution of the new and retained device regressions.

Manual TalkBack virtual-focus and Switch Access scan/selection certification remains
unperformed. Repeat the walkthrough below with duplicate same-title/same-schedule
Plans, both kinds and operations in EN/RU, and with History focused during automatic
Timer/Sequence completion. Native keyboard assertions do not establish virtual-focus
placement, service utterance ordering or physical switch behavior.

## API 35 date-change focus fixture repair (2026-10-21)

Controller's first S5C1 `gmd-full` at head
`5d24fa18e737fcae8d91b6732369f6a38b141e2c` completed 431 app cases with one failure,
zero errors and zero skips. The original data XML contains 632 cases: 627 passed,
five existing ignored fixtures skipped, zero failures/errors. The only failure was
`DailyScreenPresentationTest.dateChangesDiscardRuntimeFocusOwnershipBeforeTerminalPublication`.

The first authorized diagnostic probe established native focus on Finish, then
observed Previous day already focused during Loading, before terminal publication.
The date-change test had incorrectly forbidden a valid native fallback when the
focused runtime subtree was removed. Its negative focus assertion did not distinguish
native fallback from an explicit terminal recovery request. Daily requests no terminal
recovery while its canonical read is Loading.

The fixture now explicitly establishes History focus during that Loading gap and
asserts that both terminal publication and return to the original date preserve it.
It retains the initial runtime-focus assertion and verifies that the complete sequence
dispatches no command. No production focus, runtime, route or persistence code changed.
The neighboring Timer, explicit-Finish and Sequence focus cases passed in the recorded
unfiltered execution; no other fixture uses the invalid negative-focus assumption.

The second authorized probe passed the exact failing identity on API 35: one test,
zero failures/errors/skips, 65.6 seconds. Both original XML reports were inspected:

`.runtime/repair-probes/wf-2d37dc47821f590fa8e9b475df962789/0cfea2c6e4174e208016046a11a97337/tree-1/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`

`.runtime/repair-probes/wf-2d37dc47821f590fa8e9b475df962789/0cfea2c6e4174e208016046a11a97337/tree-2/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`

Local repair checks also passed with native exit code zero:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain
git diff --check
```

The same testcase identity remains mandatory exactly once; counts remain 431 app,
627 data and 2 startup per API. This scoped PASS does not replace an unfiltered
`gmd-full` rerun, independent startup or exact-head five-API CI. Manual TalkBack and
Switch Access certification remains unperformed.

## Reproducible assistive-service walkthrough

Record app SHA, API/device, locale, system font/display settings, app scales, service
versions and actual results. Repeat baseline English and Russian, Russian/English at
increased system font with app interface 120%/text 130%, and both at 90%/90%.
Use separate runs with TalkBack swipe/activate and headings, hardware keyboard
Tab/Shift-Tab/Enter/Escape/system Back, and Switch Access scan/select/Back. Do not
infer one service's behavior from another.

1. Begin Daily with no active execution. Enter Start Activity. Check a useful landing,
   traversal, independently named Template/Start Options controls, nested Browse Back,
   editor cancellation and return to Daily without a launch. Repeat preflight Cancel
   and system Back; return again and verify the old countdown/editor does not reopen.
2. Select a known timed Activity and complete the normal launch. At Daily identify its
   frozen name, Running and passive timing. Pause, leave for History and return, Resume,
   then Finish. Check obsolete actions disappear, focus has a stable recovery point,
   and the completed History fact is readable. Back must not restart it.
3. Launch a known two-Step Sequence with auto-advance off. Read compact identity/state;
   Expand, Pause and Back, re-enter, Resume and Complete. Waiting must have no current
   child and offer Start next. Back/re-enter and continue. Exercise Make next, Runtime
   Add and menu/confirmation cancel on known targets; finish or confirm End early.
   Compare completed History, distinguishing normal completion from early end.
4. Activate a Plan's contextual action from Daily, then from Plan. Check exact frozen
   title/source context, enter/leave Time Entry with its existing Back control, and
   cancel the focused route. Start timed execution: return to Daily, stored Plan remains
   PLANNED/derived engaged and has no second Start. Finish and inspect History.
   Repeat No-live with a numeric zero, Missing and untouched default: return to the
   originating Daily/Plan, inspect fulfilled fact and absent duration, never zero duration.
   Use S4's documented DST/overlap fixture and cancel/confirm its current proposal.
5. On a controlled failure build exercise loading/read failure ? Retry, live conflict,
   rejection, stale action/reload and committed coordination failure. Hear/read distinct
   text. Press toolbar/system Back during a gated admitted commit; after release verify
   one result and the saved/recovery message at Daily. Recreate/re-enter: no duplicate
   command, confirmation or retry. Restore a stale route and verify normalization.
6. Observe Stopwatch, Timer before/at/after zero, preflight and Sequence countdown;
   pause/resume countdown. Timing stays readable on demand without repeated announcements
   or focus jumps. Display updates alone must not be treated as a durable transition.
7. At each route/dialog boundary record keyboard focus separately from TalkBack virtual
   focus and Switch Access scanning focus. Verify full labels, reachable independent
   controls, non-overlap and stable recovery after dismissal/removal. Record actual
   service failures rather than claiming certification from semantics assertions.
