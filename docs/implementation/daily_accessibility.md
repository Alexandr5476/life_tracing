# Daily accessibility boundary (S1)

Daily exposes headings for its header and present Active, Planned and Completed
sections. Today orders Active, Planned, Completed; past days retain Completed before
Planned and future days retain Planned before Completed. The selected-date description
includes its relationship to Today. Date controls retain native button semantics and
localized names. A traversal group keeps date navigation before route actions; cards
keep their text and native interactive descendants independently discoverable.

Runtime state descriptions distinguish standalone Running/Paused and all five compact
Sequence states. Spoken timing labels identify Stopwatch elapsed time, Timer remaining
or overtime, current-step timing, Sequence active total and transition countdown.
Explicit units preserve the displayed precision. Missing baselines are unavailable;
No-live values are not durations. Plan text retains frozen title, scheduling context,
lifecycle, derived engagement, overdue and source information. Historical timestamps
continue using original zones and endpoint offsets, and absent duration is explicit.

Material3 controls supply native roles, disabled semantics, keyboard activation and
minimum interactive layout space. Daily adjusts the Material minimum locally so the
90% app interface setting still reserves at least 48 Android dp. Compact runtime
controls use FlowRow so paired
buttons wrap at increased text size without overlapping touch regions. Shared controls,
MainActivity callbacks, controller authorization, repositories and domain are unchanged.

Only isolated runtime-state and failure text uses polite live regions. Timing text and
its ancestors are never live regions; no focus is requested on timer ticks. Existing
baseline arithmetic and the existing display tick are reused. No DAO read, collector,
polling loop or durable writer was added. All commands still dispatch DailyAction through
DailyController; Plan callbacks retain plan.actionIdentity() and expanded Sequence
callbacks retain the existing execution ID.

## Automated evidence

DailyScreenPresentationTest adds seven pinned original-JUnit cases covering headings,
date context/names, merged/unmerged action uniqueness, loading/failure/retry/empty
transitions, feedback distinction, Activity timing boundaries and actions, all five
Sequence states/actions, unavailable/No-live data, Plan lifecycle and exact identity,
historical duration facts, and English/Russian semantics at system font 200% combined
with maximum app interface 120% and text 130%. Both Activity and Sequence paired
controls, paused controls and non-overlapping bounds are covered at that scale.
A 90% interface/text fixture at normal system font also checks native touch bounds
against 48 Android dp, without scaling that requirement down with the app.
Tests invoke SemanticsActions.OnClick;
no screen coordinates or accessibility-specific business action is used. Existing Daily
formatting, historical offset and route tests remain required.

DailyAccessibilityPersistenceTest uses a unique physical RuntimeRecoveryStore with
production repositories, AndroidRuntimeCoordinator and the real DailyRoute/controller.
Only clocks and platform scheduling/notification/feedback ports are controlled. It
invokes Pause, Resume and Finish through native semantics, checks controller and runtime
states, leaves/re-enters Daily while paused, closes and reopens the Room owner, and
compares canonical active-session, History, Daily and complete durable fixture facts.
One execution and one closed pause remain, with 25 seconds active over a 40-second span.
The fixture deletes its database in finally. No mock command collector substitutes for
this persistence test.

Device tests are authored for Controller execution; compilation alone is not a device
PASS. Controller owns app-unit, gmd-app and lint plus the mandatory independent five-API
GitHub gate. Final local verification on 2026-10-20 passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --console=plain
```

The app JVM reports contain 400 cases, with zero failures, errors or skips (including
all 20 DailyControllerTest cases). AndroidTest sources compiled and the APK assembled;
these are not device execution results.
The instrumentation runner/inventory Python suite passed 17 host tests using mocked
Gradle runners; those tests did not launch a device. Controller still must execute the
new instrumentation cases and the full required app-unit/gmd-app/lint checks.

## API 35 assertion repair (2026-10-20)

Controller attempt 1 executed all 331 app cases: 329 passed and two Daily accessibility
cases failed. Both failures required a Material button to contain its Text label and
OnClick on the same node in the unmerged tree. That tree keeps the label on a child.
The shared assertion now requires exactly one labeled action in each tree, accepting
text on the action or its descendant, and exactly one Text label in the unmerged tree.
The adjacent icon-action checks retain their direct content-description matcher.
Production semantics, callbacks, authorization and the pinned inventory are unchanged.

Repair checks passed: `:app:ktlintCheck :app:assembleDebugAndroidTest` and
`git diff --check`. The authorized `repair-probe.cmd` exited before device/testcase
execution with "No active repair-probe lease." No corrected device PASS is claimed;
Controller must rerun the two failing cases and the required instrumentation gate.

## Reproducible manual non-pointer walkthrough

Manual TalkBack validation has **not been performed** in this implementation environment.
Semantics-tree assertions are automated evidence only. Run this procedure on a device
with production data fixtures and record API, app SHA, locale, font/display settings,
TalkBack version and observed results. Repeat English/Russian and maximum app scaling
with increased system font; repeat using a hardware keyboard or Switch Access.

1. Enable TalkBack in Android Accessibility settings. Enter Daily through the normal app
   navigation. Swipe forward/back or use keyboard traversal. Hear Daily, the selected
   full date and Today relation, then Previous day and Next day. Activate both and Today;
   confirm selected-date context changes and the correct date-dependent section order.
2. Traverse the available route actions before content. Open Start activity, then use
   system Back to return. This checks Daily's navigation boundary; destination
   accessibility is outside S1.
3. With a confirmed running Stopwatch or Timer Activity, navigate by headings to Active.
   Identify its frozen name, Running state, tracking mode and labeled time. Wait several
   ticks: confirm no repeated automatic timer announcements or timer focus jumps.
4. Activate Pause. Verify Paused and Resume are reachable and Pause is absent. Navigate
   away and return. Activate Resume, then Finish. Verify the Active section and obsolete
   commands disappear and the completed historical fact becomes discoverable. Repeat
   with Timer remaining, exact zero and overtime; never treat announcement time as a
   completion timestamp.
5. Navigate to Planned. Read frozen title, exact-day clock/offset or floating-day/week
   context, lifecycle, engagement/overdue and any changed/archived/unavailable source.
   Confirm engaged/fulfilled/cancelled rows offer no second start. Activate an eligible
   Plan's existing Start/Complete action and verify the focused Plan execution route;
   return with system Back. Destination form accessibility remains deferred.
6. Navigate to Completed. Identify timed active duration and original-zone timestamps,
   No-live duration absence, and an ended-early Sequence outcome. Include a repeated
   local hour or overnight original-zone fact to check offsets/date context.
7. With a live Sequence, repeat Active traversal for running current, paused current,
   waiting next, running transition countdown and paused transition countdown. Confirm
   current/next identities and only the legal compact controls. Activate Expand and
   return with Back; confirm the same Sequence identity and state. Expanded-interface
   accessibility remains deferred.
8. With a controlled read failure, verify stale runtime controls are absent and Retry
   is discoverable/activatable. Restore the read and Retry; confirm content and actions
   recover. Exercise command rejection and committed coordination failure in a test
   build; confirm their distinct messages without claiming an uncommitted change.
9. At large text, traverse each wrapped pair independently. Confirm focus indicators,
   full labels, non-overlapping targets, scroll reachability and keyboard activation.
   Record any focus loss after state changes or nested navigation for follow-up; no
   cross-route focus-restoration guarantee is claimed.
