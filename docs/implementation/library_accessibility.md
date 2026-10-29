# Library discovery accessibility (Slice S1)

Archive and Folder-deletion dialogs are extended by
[Slice S3 lifecycle accessibility](library_destructive_accessibility.md).
That record covers contextual confirmation, inspection/stage guards, focus and
canonical physical-reopen assertions; S1 discovery behavior remains required.

The accepted discovery behavior is extended by
[Slice S2 non-destructive organization](library_organization_accessibility.md).
That record supersedes S1's organization-dialog deferral and records the new
controls, focus restoration, canonical reopen assertions and verification boundary.

The product v0.16 §33, domain v0.10 §§17–19, implementation clarifications and
design README remain authoritative. This change is scoped to Library presentation
and its existing production navigation.

## Targets, state and focus

The Library title and Pinned, Folders, All and Search results use heading semantics.
The breadcrumb exposes one localized current-location description without segment
actions. Loading and empty messages remain readable; browse, search and organization
failures have contextual Retry buttons. Only isolated failures and the result of an
explicit successful Retry have polite live regions. Search editing, loading, ordinary
catalog refresh and item details are not live regions. Organization readiness is
separate readable status information.

All/Activities/Sequences keep the native Material button and dispatch SetFilter;
their semantics now expose the actual selected state. Search keeps native editing
and keyboard text input, with passive inactive/global-search state context.

A Template Card has three sibling actions: Open editor, Quick Start and Organize.
Only the details surface is clickable for opening the editor, including its visible
name, kind and comment. Its indication is clipped to its visible shape. Native
Material secondary actions retain their own role, focus and activation. No sibling
action is merged into the primary action, and the Card has no activation action.
Folder Open and Organize are also separate native buttons.

EN/RU labels contain operation, kind, name, section and item number. Section/number
are discovery context for identical names, including two Activities with the same
name; all callbacks still capture LibraryTemplateId.Activity/Sequence. A repeated
Pinned/All item keeps the same typed identity. Numbering is never used for dispatch.
No raw persistence identity is spoken and no additional lookup supplies these labels.

Library opts into LifeTracingAccessibleTargets locally. The primary details target
reserves the same minimum; actions wrap with FlowRow and text grows without a fixed
row height or single-line truncation. Other screens and shared controls are unchanged.

Native keyboard focus lands at Back when Library becomes the top route. Typed keys
retain surviving rows, and tracked targets recover to Back after disposal only when
no Library control already owns focus. Search, filters and surviving result controls
keep focus through unrelated refresh and preceding-row removal. Recovery never
activates a target. Top-of-stack visibility comes from MainActivity; a background
Library cannot take focus from an editor or launcher. Native dialog ownership remains
in place; detailed organization-dialog restoration is outside this slice.

Toolbar Back still dispatches LibraryAction.Back inside folders and exits at root.
System Back still pops the existing Library route. MainActivity's editor sessions,
StartActivityRouteSessionOwner and primeInitialSelection path are unchanged.

## Changed files

- `app/.../library/LibraryScreen.kt`: headings, passive location/search state,
  selected filters, separate contextual actions, wrapping layout and scoped targets.
- `app/.../library/LibraryAccessibility.kt`: ephemeral native focus recovery and
  explicit-Retry presentation feedback.
- `app/.../MainActivity.kt`: one top-route visibility input to Library.
- `app/src/main/res/values/strings.xml` and `values-ru/strings.xml`: EN/RU descriptions
  and status strings. The Open description includes the visible Short Comment.
- `LibraryScreenPresentationTest.kt`: eight new regressions; all twelve original
  presentation identities and their behavioral assertions remain.
- `LibraryAccessibilityRouteTest.kt`: four new isolated production-route regressions.
- `.github/scripts/instrumentation-inventory.json`: twelve additions, zero removals.
- `docs/implementation/android_compatibility_gate.md` and this record: verification
  boundary, exact testcase identities and pending manual walkthrough.

## Automated cases and execution boundary

Eight new mandatory original-JUnit identities in
`com.alexandr5476.lifetracing.library.LibraryScreenPresentationTest`:

- `headingsNativeSearchAndSelectedFiltersDispatchExactlyOnce`
- `folderActionsBreadcrumbAndToolbarBackKeepSeparateAdmission`
- `browseSearchAndOrganizationStatesExposeFailuresAndExplicitRetryOutcomes`
- `duplicateNamesHaveIndependentContextualActionsAndTypedDispatchAcrossProjections`
- `keyboardTraversalAndEnterKeepTheThreeTemplateActionsIndependent`
- `survivingSearchFilterAndTemplateFocusRemainDuringUnrelatedRefreshes`
- `removedResultsFoldersAndRetryRecoverToBackOnlyOnTheVisibleRoute`
- `englishRussianActionsReflowAndReserveTargetsAtCombinedScaleExtremes`

The scale case scopes EN/RU Configuration, Context and Density at system font 200%,
with interface/text at both 90%/90% and 120%/130%. It checks target dimensions,
non-overlap, reachability, native focus and Enter activation. Duplicate cases inspect
merged action boundaries and unmerged visible details. Focus-only, scrolling,
inspection and recomposition checks require zero callbacks.

Four new mandatory identities in
`com.alexandr5476.lifetracing.LibraryAccessibilityRouteTest`:

- `duplicateTemplatesOpenExactEditorSessionsAndReturnWithoutWrites`
- `quickStartPrimesEachExactDuplicateOnceAndBackCancelsWithoutDurableResidue`
- `nestedToolbarBackAndSystemBackPreserveTheirDifferentLibraryAdmission`
- `backgroundCatalogRemovalAndEditorReturnCannotReplayNavigationOrFocusRemovedRow`

Each route case owns the existing disposable production database and platform lease.
It launches real MainActivity/NavDisplay, uses production route-session owners,
controllers and repositories, and compares complete bounded fixture facts after
closing the Activity, graph and connections and reopening the database. Duplicate
Activities and a Sequence share one name. Quick Start uses a one-hour preflight,
verifies the exact typed target and retained session across recreation, then cancels
through toolbar/system Back without a durable launch. Background removal explicitly
archives its own fixture, refreshes the retained controller while the editor owns
focus, then verifies safe return without navigation replay. Tests use native
KeyboardInputFixture and bounded state/semantics waits, with no sleeps.

All previous inventory identities remain. The inventory adds twelve app cases:
465 app, 627 data and 2 independent startup cases per API. The initial implementation
compiled instrumented cases without executing them. Subsequent Controller execution
and the scoped repair probe are recorded below. Controller owns the full unfiltered
API 26/31/33/35/36 gates and independent exact-head GitHub CI.

## Local verification

On 2026-10-22, this exact PowerShell command completed with **BUILD SUCCESSFUL**:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LibraryControllerTest' --tests '*StartActivityRouteInteractionTest' --tests '*MainActivityNavigationTest' --tests '*LauncherRouteExitPolicyTest' :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt :app:lintDebug --continue --console=plain
```

| JVM class | Tests | Result |
| --- | ---: | --- |
| `com.alexandr5476.lifetracing.library.LibraryControllerTest` | 24 | PASS |
| `com.alexandr5476.lifetracing.MainActivityNavigationTest` | 26 | PASS |
| `com.alexandr5476.lifetracing.launcher.StartActivityRouteInteractionTest` | 6 | PASS |
| `com.alexandr5476.lifetracing.launcher.LauncherRouteExitPolicyTest` | 3 | PASS |

All 59 JVM cases passed with zero failures, errors or skips. App ktlint and Detekt
passed; lint reported zero errors and 34 warnings. All AndroidTest sources compiled
and the AndroidTest APK assembled. These were implementation-turn compilation and
JVM/static results; subsequent device evidence is recorded below. Compilation is
not device PASS.

```powershell
python -m unittest discover -s .github/scripts -p 'test_*.py'
git diff --check
```

The inventory/runner host suite passed all 17 tests. Diff whitespace validation
passed. An independent comparison with the HEAD inventory found zero removed
identities and the expected 465/627/2 app/data/startup totals.

Earlier local attempts caught formatting, a Detekt condition-complexity issue, and
missing experimental keyboard-test API opt-ins; these were corrected before the
successful final command. One attempt placed --tests after lintDebug and was
rejected during task configuration, before tests ran.

The initial implementation turn ran no managed-device/emulator command, Git mutation,
or GitHub workflow operation. The repair used only the authorized probe below for
device feedback. Controller's `full` and `gmd-full` are check labels, not Gradle tasks.


## API 35 Controller failure and scoped repair

Controller's canonical attempt 1 at head
`d7db2fcfcfa8fb1749399c8ec36ce9bb42ce36c0` executed all 465 app cases:
463 passed and two Library presentation cases failed. All four production Library
route cases passed. The original app XML is under
`.runtime/check-runs/wf-2933485f29755d0f9317fdc0a813f2f8/S1-2f3ea116e5fd4af581e689cf01a61d69/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`.

- `headingsNativeSearchAndSelectedFiltersDispatchExactlyOnce` selected both the
  Library title and the root breadcrumb by their identical visible text. The
  repaired selector distinguishes the title from the described breadcrumb, then
  still asserts Heading. It separately asserts that the breadcrumb has neither
  Heading nor OnClick. Filter selection and exact Search/SetFilter assertions remain.
- `removedResultsFoldersAndRetryRecoverToBackOnlyOnTheVisibleRoute` marked Library
  hidden while leaving it as the only native focus surface, then disposed its
  focused row. The fixture did not establish a foreground route owning focus, so
  native fallback to a surviving Back button could not identify a visibility-guard
  violation. The repaired fixture first asserts native row/Retry/Folder focus. It
  then focuses a separate foreground control, removes and refreshes the hidden
  catalog, and requires that foreground focus survive. The original hidden Back
  unfocused assertion and visible-return Back assertion remain, with zero callbacks
  and zero foreground activations required.

Sibling heading selectors and focus-removal assertions were inspected. No other
affected instance was found in the Library boundary. This repair changes only the
test fixture/selectors and this evidence record; production code and every pinned
test identity remain unchanged.

Authorized command on 2026-10-22:

```powershell
D:\controller\.runtime\agent-tools\repair-probe.cmd
```

The probe reported **PASS**, **Execution: REAL**, duration **143.5 seconds**, for
exactly the two testcase identities above in `gmd-app` on API 35. Its original XML
contains two cases, both PASS, under
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/201f2140211841ddb5d619d2e94c81f7/tree-1/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`;
the corresponding command log is `01.log` in that attempt directory.

Post-repair cheap verification also completed with **BUILD SUCCESSFUL**:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --console=plain
git diff --check
```

App ktlint, Detekt, AndroidTest compilation/APK assembly and whitespace checks passed.
JVM tests were not rerun for this test-fixture-only correction; the earlier 59-case
result is preserved above. Inventory identities and counts are unchanged.

This scoped repair PASS does not certify a corrected unfiltered suite or replace
the five API gates, independent startup, exact-head GitHub CI, or manual
TalkBack/Switch Access validation. Those remain Controller-owned.

## Read and persistence impact

LibraryLoad, LibraryPresentationState, controller generations, retained ownership
and repository paths are unchanged. The only route wiring is visibility for focus.
The helper stores ephemeral focus tokens and explicit-retry presentation feedback.
Per-row computation uses the already-rendered item and section; work and bookkeeping
scale with rendered targets. There are no per-row repository reads, new catalog
projection, DAO calls, History scans, migrations, writers or execution commands.
Revision, snapshot, Field provenance, StatisticsSeries, Plan and Execution policies
are unchanged.

## Manual assistive-service validation still required

Automated semantics and keyboard evidence does not constitute manual TalkBack or
Switch Access certification. Neither service was manually validated in this turn.

On a Controller-owned device, enable TalkBack and browse Library from Daily. Check
root/nested location, headings, editable search and selected filters; then traverse
each duplicate-name Template's Open, Quick Start and Organize targets independently.
Cancel a preflight and return from both editors; confirm the retained folder/query,
and separately exercise nested toolbar Back and system Back. Trigger read failure
and Retry and verify that unrelated refresh and search typing do not repeatedly
announce the catalog. Repeat with Switch Access, EN/RU, increased system font size
and both app-scale extremes. Detailed organization menus and editor-form
accessibility remain deferred.

## PR #39 review corrections W1/W2

Create Folder and Pinned reordering use browse/mutation admission independently of
the Folder/Tag read. Metadata-dependent organization controls retain their existing
catalog requirement, canonical writer validation and transactional authorization.

Open/Edit, Quick Start, root creation, Folder entry and toolbar Back now use attached
presentation leases. Each checks the live route and current presentation before
acting, including before recomposition. State/visibility changes and disposal retire
old native callbacks; navigation is one-shot per presentation. A surviving keyed row
keeps focus. MainActivity also checks Library ownership before acquiring any editor
or launcher session. Quick Start retains primeInitialSelection and performs no start.

Five LibraryActionAdmissionTest cases retain native OnClick actions across metadata
Loading/Failure, browse failure, mutation, search/filter/context replacement, route
hiding and disposal. They cover keyboard Create Folder and upward Pinned movement,
accessibility downward movement, one-shot submission, complete typed cross-kind
orders, metadata-dependent rejection and current typed navigation. One new production
route case covers retained callbacks after filter/search replacement, exit and
return. The existing background archive case now invokes retained Open, Quick Start
and Create callbacks while hidden and after return, preserving its physical reopen
and no-durable-effect assertions. Existing canonical organization reload, editor
conflict/provenance and accepted S4 NO_OP regression cases remain mandatory.

These are automated regressions, not manual TalkBack/Switch Access certification.
Device execution and API 26/31/33/35/36 exact-head verification remain Controller-owned.

Local review verification on 2026-10-28 completed with BUILD SUCCESSFUL:

```powershell
.\gradlew.bat :domain:test --tests '*TemplateAuthoringModelsTest' :app:testDebugUnitTest --tests '*LibraryControllerTest' --tests '*MainActivityNavigationTest' --tests '*ActivityTemplateEditor*Test' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
```

The 86 JVM cases passed with zero failures/errors/skips: 10 authoring-model cases,
28 Library-controller cases, 26 navigation cases and 22 Activity editor/controller
session cases. App ktlint, Detekt and lint passed; lint retains 39 warnings and one
hint, with no issue located in the changed Library/navigation files. AndroidTest
sources compiled and the APK assembled. Initial local import/formatting failures
were corrected before this successful command.

All 40 host runner/inventory tests passed via
`python -m unittest discover -s .github/scripts -p 'test_*.py'`; their printed device
commands are mocked. A read-only HEAD comparison confirmed six added inventory
identities, zero removals and an existing source method for each addition.
`git diff --check` passed, and tracked UTF-8 BOM/line-ending conventions are preserved.

The initial preflight above had compilation-only native evidence. Its later canonical
failure and the authorized repair execution are recorded below. No commit, push or
PR operation was performed.

### Canonical attempt 1: retained native action forwarding

Controller's API 35 execution reached four failing assertions in the new retained-action
cases. The callback leases checked their original context correctly, but Clickable's
native semantics action could forward a retained OnClick to the newer callback after
recomposition. This let the old semantics action acquire the replacement presentation's
lease. The original failure remains a failed full-suite execution, not an infrastructure
failure.

Library now supplies a scoped semantics OnClick that captures the original guarded
callback, outside the native clickable modifier. Current accessibility, pointer and
keyboard actions still use the same callbacks and controller/writer paths. Roles,
disabled/selected state, contextual labels, focus targets and press indication remain
native. Navigation, organization commands, name confirmations and adjacent Library
lifecycle controls use this binding. Archive opening retains its original attachment,
live route and exact-state admission checks.

The existing failing fixtures retain all assertions and add checks separating rejection
before and after recomposition. Adjacent coverage rejects an earlier blank name
confirmation after the draft becomes valid and retained organization actions after a
refresh that leaves their controls attached. No inventory identity was removed or added
by this continuation: 516 app, 627 data and 2 startup identities remain mandatory.

The default authorized probe encountered two startup process crashes, with zero
testcases executed. It supplies no native PASS evidence. The next justified group probe
passed all 17 API 35 cases, with zero failures/errors/skips, including all four original
failures, current exact-target navigation, background archive/physical reopen, keyboard
focus, EN/RU scaling, organization dialogs and lifecycle-stage admission.

The successful probe used `repair-probe.cmd --scope-file` with an explicit `gmd-app`
scope. Its retained evidence is under
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/936c43758aaa4352a315f0d024719162/tree-2/attempt-1/`.
This is scoped diagnostic evidence, not unfiltered API or exact-head CI certification.
Full Controller gates and manual TalkBack/Switch Access verification remain outstanding.

After the successful group probe, the focused Gradle command recorded above completed
again with BUILD SUCCESSFUL: 86 passing JVM cases in the reports, app ktlint, Detekt,
lintDebug and AndroidTest APK assembly. All 40 host runner/inventory tests passed again
(their device commands are mocked). Read-only inventory checks retained 516/627/2
app/data/startup identities with zero removals; `git diff --check` passed.

### Canonical attempt 2: SystemUI window obstruction

Controller execution `review-fix-7-1abcc980ec774f93bd42d878779bb89f/attempt-1`
completed 517 app cases with 120 failures across 15 classes. Inspection of the original
JUnit XML confirms that every failure reaches KeyboardInputFixture native-window
admission and identifies the same owner:
`Window{c61bb3a u0 Application Not Responding: com.android.systemui}`.
LifeTracing's View is attached and its Activity is the focused app, but its native
window does not own input focus. The first failure occurs during initial Daily
keyboard setup, before entering Library. These failures do not establish a failure
of the downstream product assertions.

All 120 source call sites, shared fixtures and adjacent prerequisites were inspected.
The seven preceding cases and their cleanup were also reviewed; no justified fixture
change was found. Three earlier retained-action regressions and all six Activity editor
persistence cases pass in this canonical execution. No assertions, inventory identities,
native-window requirements or product behavior were weakened to recover keyboard input.

The emulator log records version 37.1.11, restoration of `default_boot`, an invalid
`auto-no-window` GPU option falling back to `auto`, and missing ColorBuffer errors.
These observations do not prove the cause of the SystemUI ANR. The inspected canonical
artifacts do not establish its blocked thread or startup cause. A separate read-only
live capture could not enumerate the probe emulator: sandboxed `adb devices` aborted
with `Cannot mkdir '\.android': Permission denied`, including after supplying an
explicit Android user directory. This capture limitation is separate from the original
testcase failures.

The unchanged-source authorized `repair-probe.cmd` then passed **all 120 original
failing identities**, with zero failures, errors, skips, duplicates or missing cases.
It reported REAL execution, duration **1297.3 seconds**. Evidence is under
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/0d4857c0fb644ce4aa1fa5841da6298c/tree-1/attempt-1/`.
The probe JUnit SHA-256 is
`97e59406217d85e7f302c3270c3b943436febf2c1ae0eb39160d219548301b5d`.
The complete canonical failure/source index and original artifact hashes are retained
under Controller operation `eec6ce438ebdd1e55d0d3235/scratch/`.

All 40 host runner/inventory tests passed again; their device commands are mocked.
The mandatory inventory remains 516 app, 627 data and 2 startup cases, with zero
original removals. This continuation changes only the evidence record. It does not
fix or close the original SystemUI ANR, replace the failed full-suite result, establish
CI/local graphics parity, or certify manual TalkBack/Switch Access behavior. Controller
retains the original stability obligation and full API/exact-head verification gates.

### Diagnostic repair: retain native SystemUI evidence

The canonical attempt above retained the obstructing window identity but did not
establish the SystemUI thread/startup cause. The shared KeyboardInputFixture now
appends read-only SystemUI evidence to the first matching native-window failure in
an instrumentation process: `activity lastanr`, the persistent `system_app_anr`
DropBox report, and the last 160 system/event/crash logcat lines. Empty lastanr never
suppresses the independent persistent report. Subsequent failures keep their own
window context and identify that collection was already attempted.

Each command has a five-second I/O deadline and a 64 KiB output limit. Timeout,
read failure and truncation are explicit; acquired and late-returning streams are
closed. Collection failures do not prevent the other reads or replace the original
assertion/cause. The diagnostic path never dismisses an ANR, retries a testcase,
changes native focus admission or changes application/domain behavior.

The adapter merges stderr and records the command exit status in the captured
stream. `COMPLETE` describes pipe completion; `command_exit` describes the command's
outcome. A whitespace-free shell program transport handles the framework's
[Runtime.exec tokenization](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/UiAutomationConnection.java).
It uses the existing API 21 shell entry point; the
[API reference](https://developer.android.com/reference/android/app/UiAutomation)
places the newer Rw/Rwe alternatives at API 31/34. No temporary device file or
additional application permission is introduced.

Four new JUnit 4 cases failed against the missing-behavior scaffolding, then passed
as host executions of the compiled AndroidTest classes. They cover exact SystemUI
selection, one attempt including after read failure, continued evidence collection,
bounded/truncated reads, ordinary errors and deterministic late-pipe cleanup. The
deadline case uses explicit latches, not sleeps. A fifth native case verifies stderr,
a nonzero exit status and all three platform queries through the real Android
adapter. Its SystemUI selector is simulated: it does not create or reproduce an ANR.

The authorized explicit `gmd-app` probe passed **125 cases**: every original 120
failing identity plus all five new diagnostics cases, with zero failures, errors,
skips, missing identities or duplicates. It reported REAL execution and **1267.8
seconds**. Evidence is under
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/0d4857c0fb644ce4aa1fa5841da6298c/tree-2/attempt-1/`.
The JUnit SHA-256 is
`581da8d8021ed37a4d8f954875bcadf184486da8a42b40ff7ee7958caebc5e6c`.
Source hashes remained unchanged throughout the probe. Source hashes, exact scope
and red/green receipts are retained under Controller operation
`0f0f5dec76f040fdb5cf519e/scratch/`.

Final app ktlint, Detekt, lintDebug and AndroidTest APK assembly passed. Lint retains
39 existing warnings and one hint, with none in the changed diagnostic files. All
40 host runner/inventory tests passed; their device commands are mocked. The
mandatory inventory now requires **521 app, 627 data and 2 startup identities**,
adding five diagnostics cases without removing any prior identity.

This fixes the confirmed test-evidence gap. The original full-suite failure,
SystemUI ANR cause and stability obligation remain open; the scoped probe is not
full API or exact-head CI certification. Controller still owns the API
26/31/33/35/36 gates. Manual TalkBack/Switch Access remains unverified. W1/W2
production admission, canonical writer authorization and the accepted S4 NO_OP
revision/timestamp semantics are preserved.

### Native focus mismatch after the causal sequence

The later `review-fix-7-causal-d3c4e609bc5d4d39bea6ecc3d293bc88/attempt-1`
execution passed 89 of its 120 cases, then failed 31 native-window admissions.
WindowManager named each current app Activity (or the popup) while its attached
View reported no window focus. The first sleep/wake regression failed at its
initial `keyboard.enter`, before its sleep command; its preceding organization
case passed. This evidence does not identify a SystemUI ANR or a sleep-induced
failure. All 31 callers, their shared fixtures and adjacent assertions were
inspected; none justified weakening native focus or changing application behavior.

The authorized unchanged-source probe passed all 31 original failing identities,
with no failures, errors, skips or duplicates, in REAL execution (497.6 seconds).
Its evidence is under
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/0ce4960d58d44e869b67d04d96e2f3be/tree-1/attempt-1/`;
JUnit SHA-256 `f449e5f3e713e57bda9815d7d74f076226b495961586d15e686984733b9fc9ab`.
That isolated pass cannot explain the interruption after the earlier fixtures.

The existing SystemUI-only selector omitted input-delivery evidence for this
mismatch. The first native admission exception now also captures `dumpsys input`,
`dumpsys window displays`, `dumpsys window windows`, `dumpsys power` and the last 240
main/system/events/crash logcat entries. It runs once per instrumentation process,
using the existing five-second/65,536-byte bound for each read. Errors, timeouts,
truncation, stderr and command exit status remain explicit. Subsequent failures
do not repeat these reads; the original assertion and cause are preserved.

Two expanded host regressions failed against the missing-behavior scaffold, then
all four host cases passed. They retain the prior SystemUI checks and additionally
verify general-focus collection, one attempt and continued reads after failure.
The existing native adapter case also exercises all five real read-only queries;
this does not manufacture or reproduce the original focus interruption. The
mandatory test inventory is unchanged. The underlying intermittent cause and the
original stability obligation remain unresolved; diagnostics are not a focus fix.

The expanded authorized probe passed **125 cases** in REAL execution (1263.7
seconds), preserving the exact original 120-case order and appending the five
diagnostics cases. It included every one of the 31 original failures, with zero
failures, errors, skips, duplicates, missing or unexpected identities. Source and
inventory hashes remained unchanged through the probe. Evidence is under
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/0ce4960d58d44e869b67d04d96e2f3be/tree-2/attempt-1/`;
JUnit SHA-256 `c5c26923c110d351537367b7170dd79f4c872b0501566cc7f3fb1e3d0812341e`.
The scope, source hashes and red/green/probe receipts are in Controller operation
`d32cedc38c5549ff2cf0b0fc/scratch/`.

Final app ktlint, Detekt, lintDebug and AndroidTest APK assembly passed, as did the
four focused host diagnostic cases and 40 mocked runner/inventory tests. Inventory
remains 521 app, 627 data and 2 startup identities. The native adapter test verified
all five additional queries on a healthy device; no actual focus interruption was
reproduced or captured. These scoped passes establish diagnostic readiness, not
the cause of the original interruption, full-suite stability or CI certification.
The complete API matrix and independent GitHub gate remain Controller-owned.

### Preserve failure causes when diagnostics exceed the JUnit export limit

The full `review-fix-7-f1188ea0691e4f21af31a78f0bb86355/attempt-1` run
reported 120 failed app cases. Its JUnit XML contains 522 app cases and 632 data
cases (five skipped, no data failures). Every reported native admission failure
shares the SystemUI ANR obstruction `4c263cb`. InputDispatcher was enabled and
unfrozen; its focused window was the SystemUI ANR dialog while its focused
application was LifeTracing. The first failure followed seven passing startup,
archive and composed-route cases. Native focus assertions remain required.

The captured DropBox storage-startup stack has timestamp 11:17:46, whereas the
failure was logged at 17:32:29. `dumpsys activity lastanr` reports no ANR since boot.
That old stack does not establish the current obstruction's cause. The original
ANR and full-run stability obligation remain unresolved.

A separate repository-owned evidence defect is confirmed: the first JUnit failure
is cut at 65,537 characters, in the DropBox dump, omitting all five later focus
queries and the original exception cause. The per-test logcat contains the full
bounded reads and actual stack. Lowering every read's byte limit would also lose
InputDispatcher focus state, which occurs late in the `dumpsys input` output.

Failure paths now record complete bounded reads under logcat tag
`NativeWindowDiagnostics` as single-line `NDIAG1 <capture> <index>/<total> <Base64>`
records. Each payload encodes at most 1,024 raw bytes and stays below 2,048 logged
bytes. Group by process and capture, require contiguous indices from 1 through the
declared total, decode and concatenate payload bytes in index order, then decode
UTF-8. Missing frames are detectable. Logging occurs after collection, preserving
the captured logcat window.

Inline JUnit diagnostics retain query headers/statuses, focus state and selected
context, capped at 24,576 UTF-8 bytes; excerpt clipping is explicit. Captured
`TestRunner:` text becomes `TestRunner [captured]:` in inline excerpts. Logging
errors are reported without replacing the original cause. Both fixture failure
paths and the adjacent direct native-window assertion use this formatter; target
and focused-node descriptions also pass through its bound. Read limits,
one-attempt capture, native admission and application behavior are preserved.

Two regressions fail against the missing formatter and all six focused host cases
pass after implementation. They cover oversized exports, late focus fields,
original-cause retention, complete raw logging, Unicode boundaries and log-sink
failure. A replay of the actual captured report retained all 308,728 raw bytes in
the log sink and all eight queries, the input focus owner and actual cause in a
14,689-byte export (4,172 inline bytes). Receipts and captured-data replay are in
Controller operation `8eda7732a82e20215177b4e1/scratch/`. The mandatory app inventory
adds these two cases without removing prior identities.


The 134-case predecessor probe retained the seven earlier passing cases, all 120
original failures and seven diagnostics cases. It completed in 1,835.0 seconds:
14 passed and the same 120 native admissions failed, with no skips. The first
JUnit failure is now 11,023 characters and preserves all eight query headers plus
the original `RootViewWithoutFocusException`. The obstruction is SystemUI window
`de3b77f`; the original ANR remains unresolved. Probe evidence is under
`repair-probes/.../637b8ea4abb94288a92434160941e566/tree-1/attempt-1`.

That probe exposed a second evidence defect: the raw log artifact begins at a
replayed `TestRunner: started:` record and has lost preceding raw query dumps.
The installed SDK host-logcat plugin 31.10.1 matches lifecycle substrings anywhere
in a message and opens the corresponding testcase file with truncation. Framed
Base64 payloads cannot contain those lifecycle markers; inline marking also keeps
captured lifecycle text from being treated as runner control. The two extended
regressions failed before this repair and all six host cases pass afterward,
including Unicode reconstruction, empty captures and a failed first log write.
The framed replay retains all 308,728 captured bytes, all eight queries, input
focus ownership and the actual cause in a 14,711-byte export (4,194 inline bytes).
These are evidence-retention repairs, not a claim that the SystemUI ANR is fixed.

The final 127-case probe omitted only those seven predecessors, retained every
original failing identity and ran all seven diagnostics cases. It passed in
1,285.7 seconds with zero failures, errors or skips. All 16 candidate source,
test, inventory and documentation hashes stayed unchanged during execution.
The exported native logger testcase has all 326 numbered frames: 332,879 decoded
UTF-8 bytes, all eight query headers and both captured lifecycle markers. Its
first log line is the genuine testcase start; encoded lifecycle messages do not
reset its file, and no captured-snippet testcase artifact is created. The longest
logged payload is 1,385 bytes. This validates the platform logger and SDK export,
complementing the failing-run inline-cause check and captured-data replay.

Final probe evidence is under
`repair-probes/.../637b8ea4abb94288a92434160941e566/tree-2/attempt-1`;
`8eda7732a82e20215177b4e1/scratch/native-framing-artifact-verification.json`
records reconstruction and artifact hashes. App ktlint, Detekt, lint and
AndroidTest APK assembly pass; the 40 mocked runner/inventory tests also pass.

Removing the predecessor group and using a fresh managed-device run are
confounding changes, so these probes do not establish an order-dependent cause
or an ANR fix. The original full-run failure and stability obligation remain
open. Canonical Controller execution and independent GitHub managed-device CI
remain mandatory final gates.

### Diagnostic readback on a noisy device

The next canonical run (`review-fix-7-14ae328e2e944031bdbee1ccfbf69469`)
again reports the same 120 failures, all with SystemUI ANR window `370941f`
owning input focus. Its first failure retains all 296 diagnostic frames
(302,398 decoded bytes), eight queries and the original Espresso cause.
The evidence-retention repair is working; the original ANR remains unresolved.

A new scoped probe ran the read-only platform diagnostics testcase before the
seven predecessor cases and all 120 original failures. It captured Launcher
focus and no SystemUI ANR entry before those cases. All 127 later cases passed,
while the diagnostic testcase failed because its filtered `logcat -t 16`
readback was empty despite all 212 frames being present in the SDK artifact.
This clean-device run does not establish the cause of the canonical obstruction.
Controller's first attempt returned zero tests; this 128-case result is its
second attempt after recovery. Device recovery and ordering are confounding
changes, so the probe does not establish a predecessor-caused ANR.

Android 15 logcat applies its global tail limit before its display/tag filter
([implementation](https://android.googlesource.com/platform/system/logging/+/android-15.0.0_r1/logcat/logcat.cpp)).
Readback now filters the bounded main log buffer by the diagnostic tag before
taking the final 16 matching records. The testcase logs 32 distinct unrelated
records after its diagnostic output, explicitly exercising this condition.
The marker assertion and bounded shell reader remain required, with no sleeps,
ANR dismissal or changes to native focus admission.

The repaired 134-case scope passed in 1,352.3 seconds, with zero failures,
errors or skips. The diagnostic testcase ran first, followed by the seven
predecessors, every original failing identity and the six pure diagnostics
regressions. All 16 candidate path hashes stayed unchanged during the run.
Its saved native artifact retains all 281 frames (287,478 decoded bytes),
all eight queries and all 32 unrelated post-report records. Evidence is under
`repair-probes/.../d5eae61112ad41a3bbfdfed091884470/tree-2/attempt-1`.
The six focused host cases, 40 runner/inventory tests, app ktlint, Detekt,
lint and AndroidTest APK packaging also pass.

This repairs diagnostic readback, not the original SystemUI ANR trigger.
The canonical failure and full-run stability obligation remain open;
Controller-owned device admission and independent managed-device CI still
require verification.
