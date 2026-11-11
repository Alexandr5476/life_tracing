# Plan calendar and active-row accessibility (S1)

This presentation boundary follows product v0.16 section 15 and sections 37-40,
domain v0.10 section 14, database v0.6 Plan calendar reads, implementation
clarifications and the shared design controls. No persistence, command admission,
calendar projection or route/controller semantics change.

## Production behavior

`PlanScreen.kt` presents a localized full Monday-Sunday range. Previous/Next name
their destination range. Only captured `WeekPlanRead.temporalContext` identifies
today/current week; legacy missing context stays unlabeled. The selected day is
independent of today. Every canonical day has one native Material Button, native
selected state, weekday/full date and its supplied density. FlowRow wraps dates
and management controls within the existing vertical scroll rather than requiring
a horizontal gesture. Selected-day and Week-precision groups have distinct dated
headings and empty messages. Loading/Failure expose neither old cells/density nor
confirmed-empty groups; failure retains the existing Refresh/Retry callback.

Each row keeps its frozen title, comment, schedule, timer target, source and
lifecycle copy. Kind and stored Planned remain independently readable alongside
engagement/overdue. Cards are traversal groups without merging child actions.
ExactDay accessible schedules use the supplied date/time/occurrence offset; the
creation zone never supplies a display offset. Week placement stays in its section
and contextual action names rather than another repeated visible row label.

`PlanExecutionActionDescriptions.kt` shares kind, schedule and localized ordinal
formatting with management names. The existing Execute wording and per-section
duplicate contract remain unchanged. Reschedule/Cancel/Update names include the
operation, frozen title, kind and schedule, with ordinals for identical targets.
Construction is linear in currently presented rows. Names, ordinals, test tags
and focus tokens never select a command target. Callbacks still receive the
original `PlanReadRow` or existing `PlanActionIdentity`. Engaged/terminal rows
have no actions; Update additionally requires the existing CHANGED source state.

`PlanCalendarFocus.kt` tracks only native keyboard ownership and target attachment.
Entry lands on Back after the visible window accepts focus. Surviving navigation
controls keep focus during query changes. A removed day/row lands on Today while
Loading, selected day on canonical Content, or Retry on Failure; a missing legacy
day strip falls back to Today. A subsequent explicit focus move cancels pending
read recovery. PlanEntryId/date composition keys preserve surviving native nodes
through reordering. Hidden routes and open dialogs suppress recovery. Passive
publication does not request focus when its owner survives. No focus operation
submits/replays a command, and no periodic live-region announcement was added.
The main screen uses the shared accessible minimum-target provider; dialog and
catalog hardening were deferred from S1. The completed S2 catalog boundary is documented in
[Add to Plan catalog accessibility and ownership](plan_catalog_accessibility.md).

## Instrumentation evidence registered for Controller

Nine new original JUnit4 methods in `PlanScreenPresentationTest` are mandatory in
`.github/scripts/instrumentation-inventory.json`:

- `calendarNativeSelectionUsesCanonicalDatesAndNeverInventsDensity`: seven exact
  SelectDate values, moved/unique native selection, headings and no fabricated
  cells/empty state during Loading/Failure.
- `localizedCalendarDistinguishesTodaySelectedAndNavigatedWeekWithoutUiClock`:
  EN/RU weekday/date/count names, native Button/OnClick in both trees, current,
  navigated and missing-context weeks, empty groups, exact Previous/Next/Today.
- `exactSemanticsFollowCanonicalOffsetsZoneAndStrictOverduePublications`: equal
  Berlin clock occurrences with +02:00/+01:00, UTC reprojection despite a Tokyo
  creation zone, before/at/after strict-overdue canonical publications.
- `managementNamesAreUniqueAndNativeActivationKeepsExactRowsAcrossReordering`:
  repeated Activity titles/schedules plus Sequence, EN/RU exact contextual names
  and ordinals, one native OnClick in both trees, stable native node IDs on reorder,
  native Enter, original-row reference and exact actionIdentity assertions for
  Reschedule, Cancel and UpdateFromTemplate. Focus alone sends no command.
- `forbiddenManagementActionsRemainAbsentAndLifecycleStaysSeparateFromEngagement`:
  engaged, fulfilled, cancelled, current/archived/unavailable sources, stored Planned
  and separate engagement, with no custom executable actions.
- `keyboardDayReloadFailureRetryAndRemovedRowRecoverWithoutCommands`: native Enter
  selects another date, Loading/Content recovery, removed row, Failure and native
  Retry delivering only Refresh without replay.
- `englishRussianScaleExtremesKeepAllDatesAndManagementControlsKeyboardReachable`:
  EN/RU at app interface/text 90/90 with system font 1.0, 120/130 with system font
  2.0, and 90/130 with system font 2.0. All seven dates and every rendered row
  action scroll into view, retain physical 48dp minimum targets, and accept native
  Tab traversal without commands.
- `nativeWeekNavigationRetainsItsControlAcrossLoadingAndCanonicalPublication`:
  native Tab/Enter from Back through navigation, exact NextWeek, surviving focus
  through Loading/new-week Content, Today-to-first-date traversal.
- `passivePublicationsReorderingAndHiddenRouteNeverStealKeyboardOwnership`:
  surviving PlanEntryId node/focus, updated canonical original-row dispatch,
  hidden-route removal preserving another control, visible-entry landing.

All eight prior Plan presentation identities remain. Their localized date/section
expectations now match the richer production context. Existing duplicate Execute,
MainActivity visibility and physical execution accessibility suites remain mandatory.
The existing duplicate-action week-replacement regression also observes the new
management descriptions and requires obsolete descriptions to disappear.

## Local validation and remaining gates

Focused Plan JVM tests: 56 cases, zero failures/errors/skips. App ktlint, Detekt,
lintDebug and AndroidTest APK assembly passed using:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.alexandr5476.lifetracing.plan.*' :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt :app:lintDebug --continue --console=plain
```

Compilation/packaging does not execute these instrumentation assertions. No
managed-device task was launched. Controller owns ci-fast, gmd-app and independent
GitHub compatibility gates. Manual TalkBack/Switch Access certification has not
been performed. A manual walkthrough should cover EN/RU dates, selected versus
today, Day versus Week headings, repeated native actions, read-failure Retry,
keyboard entry/reload/removal and all supported scale extremes. Record manual
observations separately from automated semantics evidence.

## Controller API35 route-fixture repair (2026-11-11)

The first Controller gmd-app execution at c15377e3216ccf0c47bb60397e9d24db7340006e
completed 600 cases with one failure and no skips. All 17 Plan presentation cases
passed. The retained production route/transient-state case found only the row's
Reschedule label after a native click. Its recorded button bounds were
(74, 1883)-(369, 1988) px: the fixture activated without first scrolling the control
into view after the richer calendar/row presentation increased content height.

The fixture now targets the Plan it created by its exact row test tag, scrolls it
into view and asserts displayed/enabled before the same native click. It also
asserts that the form belongs to that exact Plan and that the schedule form is
visible. The original two-label/one-label, recreation, route Back, retained
controller and selected-date assertions remain. The adjacent Cancelled plans
entry now establishes the same scrolling prerequisite and asserts its dialog
opened before testing exit and fresh transient state. Both forms' closed states
are explicitly checked after re-entry. No production callback, admission, layout
or mandatory testcase identity changed.

The authorized repair-probe command returned "No active repair-probe lease"
before executing any testcase. Runtime confirmation remains Controller-owned;
this repair does not claim a corrected device PASS or close the original gate.

Repair host verification passed: `./gradlew.bat :app:ktlintAndroidTestSourceSetCheck :app:assembleDebugAndroidTest --console=plain`. AndroidTest Kotlin compilation, APK packaging and instrumentation-source style checks succeeded; `git diff --check` and mandatory-inventory preservation also passed.
