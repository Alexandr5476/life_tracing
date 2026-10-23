# Library discovery accessibility (Slice S1)

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
