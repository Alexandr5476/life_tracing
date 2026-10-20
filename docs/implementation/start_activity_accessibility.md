# Start Activity accessibility boundary (S2)

Product v0.16, domain v0.10, database v0.6 and the implementation clarifications
remain authoritative. This slice changes launcher presentation and route Back
dispatch, without adding a controller, command, repository reader, or writer.

## Presentation and interaction

Start Activity and its present Recent, Pinned, Search and Browse sections expose
headings. One-off creation, Start Options and the quick editor expose headings
when open. Primary rows retain their native click/keyboard action and merge their
name, Activity/Sequence kind and comment. Start Options is a sibling native button
with a localized target name, outside the primary click target. Typed identity keys
remain unchanged; identical names never become identity keys.

Native fields retain editable text and SetText actions; invalid non-missing historical
Number input also exposes the native Material error state. Search uses its existing
localized label. Historical Number/Text fields include their Field name; Category
choices include Field and option names. Category and timing-mode buttons expose
selected state. Missing clears Category selection semantically without replacing
the retained option identity. Missing actions identify their Field. Offset choices
identify start/completion, first/second occurrence and the actual UTC offset,
with selected semantics only after explicit selection. The historical resolver,
draft version, proposal, timezone and overlap admission checks are unchanged.

Home, search, browse and target load/failure/empty/content states remain rendered
through the existing state. The loaded target includes kind and name. Material
input errors expose quick-number and one-off validation. Quick values distinguish
untouched defaults, edited values and Missing through localized state descriptions;
invalid Complete is disabled. An explicit Missing action uses the same blank-value
edit path. Opening/focusing never parses a rounded display value into an override.

Only isolated material status/failure text uses polite live regions. Preflight has
a static announcement plus a noninteractive indicator describing its total duration.
The visual 250 ms countdown text has cleared semantics, and progress exposes a
stable indeterminate range instead of a changing announced percentage. Its existing
display loop, deadline, attempt and cancellation command are unchanged. Committed
coordination failure explicitly says the execution is saved and coordination needs
recovery. One-off retries have one visible command path, through the existing form
operation, without a duplicate generic Retry. Overlap cancellation has a distinct
label from closing Start Options.

System Back and toolbar Back share the existing context policy: nested forms close,
folders go up, and route exits arbitrate through LauncherRouteExitPolicy. Committing
waits at the durable boundary; committed results deliver once. No focus or semantics
callback selects or writes. Editors scroll into view and request keyboard focus at
their first input on composition. Dismissal and preflight cancellation return
keyboard focus to Back while idle. Focus state is ephemeral and stores no Template
selection or draft. TalkBack virtual focus behavior still requires the manual
walkthrough below; keyboard focus requests are not a claim of TalkBack certification.

Pinned move buttons retain contextual names/directions and native disabled state at
boundaries and during organization. The pointer drag handle is hidden from
accessibility and has no click/long-click semantic action; pointerInput is retained.
Moves dispatch the complete ordered typed IDs through ReorderPinned. Canonical
success refresh and failure restoration remain unchanged.

## Layout and shared-component impact

Pinned rows stack their primary selection above wrapping move controls. Paired form
actions use FlowRow. Offset and mode choices stack vertically; text fields grow
naturally. The primary row surface clips indication to its shape. Material buttons
retain native roles, focus, activation and disabled behavior.

LifeTracingAccessibleTargets extracts the existing S1 provider verbatim in behavior:
it reserves at least 48 Android dp under reduced app interface density, using the same
Material minimum and ViewConfiguration. Only Daily and Start Activity opt in. Daily's
other semantics and actions are unchanged. LifeTracingControls itself is unchanged.
Inspection of unchanged Plan, Library, Activity/Sequence Template editors, History,
Settings and Statistics consumers found no call-site/API changes: their native
buttons, text fields, choice controls and FilterChips keep their existing selection
and editable semantics. No global selected-state modifier or custom action was added.
Existing presentation/route tests for these consumers remain required on all APIs.

No DAO call was added to Compose, no per-row hydration, independent timer loop,
unbounded search/history materialization, or accessibility-triggered durable write
was introduced. The existing bounded catalog models and LazyColumn ownership remain.
Focus requests and live-region recomposition are presentation only.

## Automated evidence and execution boundary

Nine new StartActivityScreenPresentationTest cases exercise headings, merged/unmerged
action uniqueness, typed duplicate names, focus without commands, blocked selection,
pinned boundaries/canonical recovery/drag metadata, quick default/zero/Missing/negative/
invalid input, exact Field/Category/offset actions, command feedback, passive preflight,
native/toolbar Back, and English/Russian keyboard/semantic activation at system font
200% with app interface 120%/text 130%, plus the 90%/90% app minimum. Existing pointer
drag, load/failure/retry, rounded-default, selection-recomposition and selected-indicator
cases are retained. The scaled case checks minimum touch bounds and non-overlap for
primary/Options, quick Complete/Cancel, offset pairs, Save/Cancel, overlap Proceed/Cancel
and pinned moves, as well as keyboard activation and return focus.

Ten StartActivityAccessibilityPersistenceTest cases use unique physical databases,
production repositories and executeLauncherCommand through the actual route,
interaction and controller. Activity selection uses Search; Sequence selection uses
Browse; both focus before activating and compare canonical runtime, Daily, snapshot
and durable facts after closing/reopening Room. Quick completion covers explicit zero,
Missing and an untouched rounded default, exact source Field and Statistics identities,
one historical root and preservation of an unrelated live session. One-off No-live
completion verifies the system bucket, no Template and one root. Pinned movement
compares Template/snapshot/Statistics facts and canonical complete order after reopen.

The historical-options fixture activates native SetText and contextual offsets,
rejects gaps/future/reversed times, checks explicit offsets, cancels overlap, invalidates
an old proposal by editing, then confirms the current exact proposal and reopens its
snapshot. Preflight cancellation fires an obsolete boundary and verifies no write.
A CompletableDeferred gate holds an admitted commit while both Back paths are invoked.
Conflict retry and a post-commit coordination failure verify a single writer invocation.
Fixtures establish their own prerequisites and delete their own databases in finally;
no arbitrary sleep, shared application database or fake selection-only assertion is
used for this evidence. Existing production launcher, atomic/stale/retry and route
session tests remain mandatory.

All 19 new original-JUnit identities are pinned in instrumentation-inventory.json:
627 data, 350 app and two independent startup cases per API. The mandatory unfiltered
26/31/33/35/36 gate is unchanged. Controller owns authoritative app-unit, gmd-app, lint
and exact-head GitHub evidence. Instrumentation compilation is not device execution.

Manual TalkBack/Switch Access testing has **not been performed** here. No managed
device or AndroidTest device task was launched by this implementation turn.

## Reproducible manual walkthrough

Record app SHA, API, locale, TalkBack version, keyboard/Switch Access method, system
font/display settings and app interface/text settings with observed results. Run
English and Russian at baseline and system font 200% plus app 120%/130%; repeat with
app 90%/90%. Use disposable fixtures including duplicate names, both kinds, three
Pinned items, a No-live Number main value, a timed Activity with Number/Text/Category
fields, a short preflight, folders and a Sequence.

1. From Daily, reach Start activity with keyboard or TalkBack and activate it. Hear
   the route heading and Back. Traverse Recent/Pinned/Search/Browse in product order.
   Return using toolbar and system Back; verify Daily remains usable.
2. Identify Activity and Sequence by spoken kind. Activate each duplicate-name target
   separately and verify the intended identity. Discover the Activity's independent
   Start Options button; verify Sequence has no such button. Focus alone must not start.
3. Enter Search with native editing. Wait for results and traverse/activate one using
   Enter or a semantic action. Exercise empty, loading and recoverable read failure/Retry
   using a controlled test build. Confirm labels and stale controls do not mislead.
4. Browse a nested folder. Use both Back methods to go up, then to Daily at the root.
   Re-enter; verify no prior selection or dismissed editor replays.
5. Move first/middle/last Pinned items using Up/Down buttons. First Up and last Down
   must be disabled; a single item has no valid moves. During an in-flight move,
   another move is unavailable. Verify canonical order after success/failure.
   The drag decoration must not appear as an executable TalkBack control.
6. Select No-live with a configured fractional default. Hear its field name, value
   and unchanged-default state. Complete untouched and verify the stored default.
   Repeat with edited zero, negative fraction, Missing and invalid input. Invalid
   Complete stays disabled. Cancel, move focus and recompose: no completion may replay.
7. Create a one-off. Enter Name and Short Comment. Traverse Stopwatch/Timer/No-live
   and hear the selected mode. Test blank name and invalid/zero/overflow Timer target.
   Verify timed Start and No-live Complete, then Cancel without a partial execution.
8. Open Start Options, traverse all inputs by Field name and edit through the native
   keyboard. Select Category then Missing: no Category remains announced as selected.
   Restore a value and verify the original Field/option identity, not display position.
9. With Europe/Berlin as the device zone and a test clock after 2026-10-25, enter
   start 2026-10-25 02:10 and completion 02:30. Neither overlap offset is initially
   selected. Hear the timestamp context, occurrence and UTC offset for every choice.
   Focus each without activation, then select explicitly for both timestamps.
10. Try 2026-03-29 02:30 in Berlin (gap), future timestamps and a reversed full
    interval. Hear validation; no timestamp is normalized and nothing is saved.
    Change timezone while editing and review the rejection without losing allowed text.
11. Create a genuine overlap with completed history. Cancel confirmation, edit a
    Field, and Save again. Proceed must authorize the current proposal only.
    Exercise stale-template Review current in a controlled fixture.
12. Start a countdown. Hear its static state and target; wait several display ticks:
    no repeated announcements or focus movement. Cancel and verify no execution.
    Repeat and let it commit. While a commit is held by a test gate, both Back paths
    must stay within the route. After success, Daily receives exactly one execution.
13. Exercise live conflict, rejected launch and recoverable Retry in a controlled
    fixture. Exercise committed coordination failure: hear that it is already saved;
    no retry creates a second execution. Verify focus/keyboard reachability after
    cancellation, failure, editor close and successful return to Daily.
14. At every scale, independently traverse primary rows, Start Options, move controls,
    modes, quick actions, offset choices and Save/Cancel/Proceed. Check complete Russian
    labels, visible focus, scroll reachability and non-overlapping interactive targets.

## Initial local verification (2026-10-20)

The pre-device local checks passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --console=plain
python -m unittest discover -s .github/scripts -p test_run_instrumentation.py
```

The app JVM reports contain 400 tests with zero failures, errors or skips. Ktlint,
Detekt and Android lint passed, and the changed instrumentation sources compiled and
the APK assembled. The runner's 17 host tests use mocked Gradle runners; their printed
device commands are not device executions. Inventory auditing retained every prior
identity and matched all 20 launcher presentation/ten persistence method registrations;
English/Russian resources add the same 13 names with no duplicates. git diff --check
also passed. Callback registration is explicitly observed before firing either
preflight attempt, and fixture scopes are cancelled before physical database reopening.

These local results do not establish instrumentation execution, manual accessibility
validation or the Controller's mandatory independent five-API exact-head CI gate.

## Controller API 35 failure repair (2026-10-20)

Controller completed 350 app cases: 346 passed and four failed. The Activity
persistence selector also matched the search field's editable value, and the Timer
mode selector matched its input and validation text. Button-role matchers now select
the intended actions while retaining native editable semantics. The persistence
activation helper uses the same matcher, avoiding the same collision on activation.

Focus fixtures explicitly enter Android keyboard input mode after the Compose
owner is attached. The shared KeyboardInputFixture uses requestFocusFromTouch,
observes the platform mode with a bounded wait, and restores the original mode
after each test, following the existing Plan keyboard fixture's approach.
They assert successful button focus before Enter activation and retain the Back
focus assertions after quick-editor cancellation. Adjacent row, field and DST
focus checks also assert actual focus before checking that no command occurred;
offset focus additionally retains unselected state. No assertion or testcase
identity was removed, and this repair changes no production launcher behavior.

Repair verification passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --console=plain
git diff --check
```

An inventory audit matched all 20 presentation and ten persistence methods to their
mandatory registrations; the app total remains 350. No JVM behavior changed in
this test-fixture repair, so the earlier app-unit result is not a new execution.

The first authorized repair probe returned exit code 1 with "No active repair-probe
lease"; no device testcase executed. Controller must rerun instrumentation to
confirm the repair. The independent five-API gate and manual walkthrough remain
required.

The next Controller retry executed the original four cases: the Timer-heading case
passed, while the other three failed during the initial composition's input-mode
setup. Requesting keyboard mode there either returned false or left the observed
mode as Touch. This was a fixture failure before selection, focus restoration or
keyboard activation could be exercised. That composition-time setup is now removed
from both launcher test classes; input-mode setup runs after attachment instead.

After this attachment correction, app ktlint, Detekt and AndroidTest APK assembly
passed again. The authorized repair probe was invoked again and returned "No
active repair-probe lease" before running any testcase. The three reported cases
therefore still require Controller runtime confirmation; compilation is not a
passing device result. KeyboardInputFixture adds no testcase or inventory change.
