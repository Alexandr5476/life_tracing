# Accessible Activity Template authoring (Slice S4)

Product v0.16 sections 6–7, 13, 33 and 46.1, domain v0.10, schema v0.6,
implementation clarifications (including archived Category defaults and frozen
History labels), and the design README remain authoritative. This extends the
accepted Library S1–S3 and shared native-target patterns. No schema, migration,
writer transaction, propagation, StatisticsSeries or historical identity changes.
The explicit S4 no-op decision below corrects shared authoring comparison while
retaining the existing revision policy for real semantic/presentation changes.

## Production behavior

The production route identifies Create Activity Template versus Edit Activity
Template from the controller's immutable New/Existing target. Existing navigation
still acquires the same ActivityTemplateId session, retains drafts through Activity
recreation, normalizes process-restored routes lacking that session, delivers a
committed result once, and refreshes the retained Library. MainActivity adds only
editor top-route visibility for focus ownership. No new route or execution action.

Native Material controls retain SetText, focus, keyboard and OnClick actions.
Editor, Tracking, Fields and individual Field headings expose heading semantics.
The pane exposes route context and pristine/modified draft state. Tracking and
Field-type buttons expose actual Selected; each Main Value checkbox exposes its
own checked state without a second parent activation. Timer and Number defaults
use Material isError and localized supporting text. Rejected precision input keeps
the existing controller policy and explains the permitted blank/0–3 input.

Field descriptions contain operation, localized type, ordinal and current name.
Option descriptions additionally contain option ordinal and label. Duplicate names
and labels remain allowed. Spoken text and ordinals never select an entity.
Compose keys use ActivityFieldDraft.identity and ActivityCategoryOptionDraft.identity.
Actions resolve the captured identity and type against current state and modify
current values, avoiding stale captured copies. Category selection/removal checks
that the captured option still exists; removal still follows the established draft
ordering/default-clearing behavior and append-position helper.

ActivityTemplateEditorActions is an ephemeral presentation lease. It revokes actions
on disposal, hidden content or obsolete load generation and ignores transformations
that no longer target anything. It creates no product-level authoring command and
allocates no identities. Controller identity normalization and submittedDraft remain
authoritative, including restored compatible identity and final type/unit replacement.
Number callbacks resolve current Number type/precision; removed controls cannot
reintroduce stale validation or overwrite a successor. Inactive Timer callbacks are
rejected. No Field or option is identified by a list index at an Activity editor action
boundary; the unused Activity withFieldAt helper was removed.

Controller load publications use a generation check inside StateFlow.update, for
both successful and failed reads. Older retries and closed routes cannot overwrite
an accepted draft. Closed/Committed routes and open Discard dialogs reject stale
edit/Save/exit actions. Discard requires the currently open confirmation. Invalid
Timer raw text, like invalid Number text, participates in dirty Back admission even
when the last model value equals the original. These repair the required admission
boundaries without changing the authoring or persistence state machine.

Save still invokes TemplateAuthoringDraftValidator, createTemplate or saveTemplate
with the loaded expectedRevision. Input edits, focus, inspection, dialogs and selected
state do not commit. Invalid Timer/Number input blocks Save; representable authoring
validation failure remains an accessible failure rather than a successful write.
Saving and Committed have no actionable second Save. Ordinary failure preserves
the draft for explicit Save retry. Conflict preserves it until explicit Reload or exit;
there is no automatic retry or merge. Status/failure text uses isolated polite live
regions; the form is not a live region. Existing error prose stays localized.

## Keyboard focus and scaling

Editor entry and load-phase transitions land on a surviving Back, Retry or Name
control. Focus recovery is scoped to the visible editor and its native window. Stable
Field/option keys retain surviving edit controls. Disposing a focused target recovers
only when native focus has no surviving editor owner. Recomposition and text entry
do not repeatedly request focus. Hidden/disposed content cannot claim foreground
focus or submit a retained presentation action.

Tab and Shift+Tab on native editor inputs traverse the form through Compose's
focus manager instead of inserting tab characters into draft values. Name, Short
Comment, Timer and the shared Field/option input wrapper use the same scoped
modifier. Native SetText, other keyboard editing and multiline values are retained.

Discard is a native AlertDialog. Continue Editing receives initial keyboard focus;
button activation and system-window dismissal use existing controller admission.
Dismissal restores a surviving originating editor control, falling back to Name.
Dialog content explicitly carries the editor's locale/configuration/density and local
LifeTracingAccessibleTargets policy. Both toolbar and system Back retain the Saving
guard, so an admitted write cannot lose its route session before completion.

The local accessible-target policy applies to the editor and native dialog content.
Field and option controls are stacked; choice groups wrap; form and dialog text
scroll. No fixed-height row, shared-component change, custom press indication or
new animation is introduced. EN/RU test fixtures combine system font 200% with
app interface/text 90%/90% and 120%/130%, inspect target dimensions/non-overlap,
and reach actions through native semantics and keyboard activation.

## Executable evidence

The following original-JUnit Android test identities are new and mandatory. They
are source/APK evidence here; execution belongs to Controller's unfiltered device
gates. All prior Activity editor, Library S1–S3 and startup identities remain pinned.
Current inventory: **509 app, 627 data, 2 independent startup cases per API**.

### com.alexandr5476.lifetracing.LibraryAccessibilityRouteTest

- `nativeExistingAuthoringPreservesEligibleSourcesMetadataAndFrozenHistoryOnReopen`
- `nativeNewActivityAuthoringCommitsOnceAndPhysicallyReopensCanonicalFields`
- `productionConflictRequiresExplicitReloadAndDiscardLeavesNoPartialWriteOnReopen`
- `productionLoadRetryAndGatedWriterFailurePreserveSessionAndBackAdmissionUntilCommit`

### com.alexandr5476.lifetracing.editor.ActivityTemplateEditorScreenPresentationTest

- `authoritativeValidationFailureIsAnnouncedWithoutWriterAndNativeMainValueCanResolveIt`
- `discardInitialKeyboardFocusDismissalAndRetainedDialogActionsAreSafe`
- `duplicateCategoryOptionsRenameSelectClearAndRemoveExactIdentities`
- `duplicateFieldControlsAndMainValueDispatchToStableIdentities`
- `englishCombinedScaleExtremesKeepNativeTargetsReachable`
- `existingContextLoadingFailureKeyboardRetryAndReadyKeepExactTarget`
- `gatedSavingDisablesBackAndSaveThenCommittedHasNoSecondAction`
- `hiddenOrDisposedEditorCannotStealForegroundFocusOrSubmitRetainedActions`
- `nativeTrackingSelectionAndTimerErrorsDispatchExactDraftTransitions`
- `newContextHeadingsAndNativeEditableTextDoNotWriteOnFocusOrTab`
- `numberControlsExposeNativeErrorsPrecisionUnitsAndMissingVersusZero`
- `ordinaryFailureExplicitSaveRetryAndConflictReloadRetainUnsavedText`
- `removedFieldAndOptionNativeCallbacksCannotMutateTheirSuccessors`
- `russianCombinedScaleExtremesKeepNativeTargetsReachable`
- `stableSurvivingFieldFocusAndNativeAddTypeChangeRemainReachable`

Presentation cases inspect merged and unmerged native editing semantics, exact
controller effects, selected/checked state, Missing versus zero, signed fractional
Number input, precision rejection, Timer blank/zero/negative/nonnumeric/overflow
and mode transitions, duplicate Field/option targeting, native retained callbacks,
validation failure, gated Save, ordinary failure, Conflict/Reload, Back/dialog focus,
Tab/Enter, visibility/disposal and combined EN/RU scaling. Arbitrary sleeps and
private-code reflection are not used.

Production journeys use actual MainActivity/NavDisplay, the production route owner,
isolated disposable physical database files and production repositories:

- Creation enters Library New activity, edits Name/Comment, Timer, Number/Category/
  Text schemas/defaults and Main Value through native accessibility editing/actions,
  recreates the host without acquiring a second controller, saves via keyboard
  Enter once, and rejects a retained duplicate Save. After Activity/graph/database
  close and reopening the same file, canonical authoring/Library readers verify one
  Template, its generated identity/revision/Series, Field/option source identities,
  schemas/defaults and no Execution, Plan or active session.
- Existing editing targets one of two same-name Templates through its exact ID.
  It retains Library folder/filter/search, preserves compatible Field/option source
  IDs, exercises final unit and type replacement plus explicit Field/option removal,
  selects/clears the Category default and saves one revision increment. A subsequent
  restored-unit Save is a semantic no-op. Physical reopen compares canonical data,
  archived source lifecycle, Folder/Tag/Pin/Recent metadata, the untouched duplicate,
  History detail, Plan and Series identity. Bounded frozen durable facts additionally
  compare snapshot Field/option provenance and execution values.
- A real concurrent writer changes the revision. Failed stale Save retains its
  unsaved draft/session across recreation and leaves complete durable facts unchanged.
  Explicit Reload reads canonical data; existing and new dirty drafts can be discarded
  without writes. Physical reopen confirms the concurrent state is the only change.
- Deterministic load/writer gates retain the ordinary controller in the production
  route owner. A read failure and ordinary writer failure retain the session/draft;
  explicit retry uses the same repository. System/toolbar Back, duplicate Save and
  Activity recreation during an admitted write cannot release the session. Releasing
  the gate commits once; physical reopen verifies the canonical result.

Fixture gates control only read/writer timing and injected ordinary failures; successful
writes call the original production repositories. The production writers are unmodified.
Complete durable-fact comparisons supplement physical reopen and canonical readers.

Six new Jupiter controller regressions cover obsolete failed/successful loads and
closed-route publication, removed Number controls/current precision, dismissed-dialog
and closed-route exits, typed presentation leases, invalid persisted Timer input,
and invalid Timer Save admission. The existing exactly-once, Saving exclusion,
disposal, explicit retry/Conflict/Reload, Number lineage/precision, compatible identity
restoration and submitted replacement tests remain intact.

## Local verification

Fail-first command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ActivityTemplateEditorControllerTest' --console=plain
```

Before production fixes: 17 cases, three expected regression failures
(`obsoleteRetryCannotReplaceANewerAcceptedDraft`,
`removedNumberCallbacksCannotPoisonSurvivingDraftOrUseCapturedPrecision`,
`dismissedDiscardAndClosedRouteCallbacksCannotExit`). After fixes, the focused
17-case suite passed. The later complete app suite passed **421 cases, zero
failures/errors/skips**, including all **20** editor controller cases:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The combined Gradle command completed BUILD SUCCESSFUL, exit zero. App ktlint,
Detekt, lintDebug and AndroidTest APK assembly passed. An earlier check caught two
SetText-return-type test errors, a missing Jupiter import and a StateFlow.value read
inside composition; those were corrected. The host runner suite passed 17 mocked
runner tests; its printed managed-device commands are mocked, not device execution.
Independent source/inventory comparison found zero removed identities and exactly
19 additions, and both localized resource XML files parsed successfully.

After the final test-only additions, this command also passed with native exit
code zero and BUILD SUCCESSFUL (1m 55s):

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
```

This includes the hidden/disposed editor, unmerged native-input inspection,
non-overlap, explicit Field removal/source-identity assertions, and native dialog-window
focus synchronization. The final source/inventory audit still reports 509/627/2 and
zero removed identities. Production and JVM test code did not change after the
421-case passing JVM run. No managed-device task, emulator, Git mutation, PR or
GitHub CI operation was run. Controller still owns `full`/`gmd-full` (aliases, not Gradle commands), the
unfiltered API 26/31/33/35/36 matrix, independent startup and exact-head GitHub gates.
No device PASS or physical-reopen execution PASS is inferred from compilation.

## Read/query impact and deferred boundaries

Composition is O(Fields + Category options), including one root dirty comparison.
Nested controls do no whole-draft lookup during composition. Activation resolves
only the current edited draft/Field/options; existing normalization and writer paths
remain bounded by that draft. Focus bookkeeping is ephemeral and proportional to
composed native targets; it holds no History copy. No per-Field reads, History scans,
new DAO composition, durable duplication, collector or transaction is added.

Sequence Editor accessibility/manipulation, Repeat/Step editing, runtime scheduling,
Settings/Statistics/History/Plan UI and archived-template management remain deferred.
Sequence index callbacks were inspected as sibling occurrences and left outside the
explicit Activity-only scope. Existing mutable linked-Step propagation remains solely
owned by the unchanged authoring repository; these journeys do not invoke it.
Historical Sequence, Plan and Execution snapshots remain immutable.

Automated native semantics and keyboard evidence does **not** certify TalkBack
virtual focus, announcement ordering or Switch Access scanning. Manual validation
is unperformed. On a Controller-owned device, record app SHA/API/service/locale/scales;
repeat creation/editing with duplicate Fields/options, errors/reload, discard/dismiss,
add/remove focus and Save return using TalkBack, keyboard and Switch Access separately,
at baseline and both documented combined scale extremes.

## Exact changed paths

- `.github/scripts/instrumentation-inventory.json`
- `app/src/main/java/com/alexandr5476/lifetracing/MainActivity.kt`
- `app/src/main/java/com/alexandr5476/lifetracing/editor/ActivityTemplateEditorController.kt`
- `app/src/main/java/com/alexandr5476/lifetracing/editor/ActivityTemplateEditorScreen.kt`
- `app/src/main/java/com/alexandr5476/lifetracing/editor/ActivityTemplateEditorAccessibility.kt`
- `app/src/main/java/com/alexandr5476/lifetracing/editor/ActivityTemplateEditorActions.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ru/strings.xml`
- `app/src/test/kotlin/com/alexandr5476/lifetracing/editor/ActivityTemplateEditorControllerTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/editor/ActivityTemplateEditorScreenPresentationTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/LibraryAccessibilityRouteTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/daily/DailyAccessibilityPersistenceTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/live/ExpandedLiveSequenceScreenTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/library/LibraryDestructivePresentationTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/plan/PlanExecutionScreenTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/ui/KeyboardInputFixture.kt`
- `docs/implementation/android_compatibility_gate.md`
- `docs/implementation/activity_template_editor_accessibility.md`
- `docs/spec/lifetracing_implementation_clarifications.md`
- `domain/src/main/kotlin/com/alexandr5476/lifetracing/domain/TemplateAuthoringModels.kt`
- `domain/src/test/kotlin/com/alexandr5476/lifetracing/domain/TemplateAuthoringModelsTest.kt`
- `data/src/androidTest/kotlin/com/alexandr5476/lifetracing/data/persistence/TemplateAuthoringRepositoryTest.kt`

## API 35 Controller failure and scoped repair (2026-10-23)

Controller's canonical attempt 1 at head
`cb240849a9df454275b211ffeb44d2e58c20d67c` executed only 148 of the 509 app
cases before the device crashed: 142 passed and six failed. Data's original XML
contains 632 cases, zero failures/errors and five existing ignored cases. This is
not a complete app-suite result. Original reports are in Controller's
`.runtime/check-runs/wf-2933485f29755d0f9317fdc0a813f2f8/S4-ab814da12a6f4641a1054a35e54838b5/attempt-1/reports/`.

The repair changes only `LibraryAccessibilityRouteTest.kt` and these evidence
records. Existing assertions and original-JUnit identities remain present:

- Creation now establishes the required reserved One-off Statistics bucket before
  authoring. Reopen checks the exact original-plus-created Series ID set and the
  unchanged original bucket projection, alongside the original canonical Template,
  Library, schema/default/source-identity and absence-of-execution/Plan assertions.
- Native dialog Back explicitly selects the dialog's Espresso root after checking
  its native window and keyboard focus. Both affected organization/deletion paths
  still send native Back and assert dismissal without writes. Deletion Cancel uses
  the actual confirmation context, not its preceding Folder-only context.
- Generic button invocation scrolls only when a scroll ancestor exists, retaining
  displayed/action assertions for native Discard/Continue buttons.
- Template Open labels include the canonical Short Comment after Save, exactly as
  production renders them. The first probe ruled out a lazy-list explanation:
  Library uses a regular scrolling Column. The attempted lazy fallback was removed;
  lookup still uses exact current kind/name/section/ordinal/comment context and ID.

The unchanged Daily Timer testcase failed during Activity setup when SurfaceFlinger
reported `mNumLayers (4096) >= MAX_LAYERS (4096)` and surface allocation failed with
Out of memory; SurfaceFlinger then aborted with SIGABRT. The original diagnostic is
in `app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api35/adb.021.exe.adb.exe.fail.txt`
at 06:34:09 UTC. No Daily production/test behavior was changed. It passed in both
fresh scoped probes; this does not establish full-suite device stability.

Authorized runtime command, run twice:

```powershell
D:\controller\.runtime\agent-tools\repair-probe.cmd
```

Both probes reported Execution REAL and ran exactly the six supplied testcase
identities. Probe 1 passed four and failed two (160.4 seconds). Probe 2 passed five
and failed one (178.6 seconds), with no errors or skips. Their original XML reports
are under Controller's
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/d64db1b3ca9f4ec19f159ad6f40e3377/`,
respectively `tree-1/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml` and
`tree-2/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`.

### Durable revision contradiction found by probe 2 (resolved by explicit decision)

`nativeExistingAuthoringPreservesEligibleSourcesMetadataAndFrozenHistoryOnReopen`
now reaches its retained no-op assertion. The initial semantic Save succeeds and
retains the expected identities, archived source lifecycle, metadata and frozen
facts. After canonical reload, restoring the Number unit and saving again changes
revision **2 to 3**, Template updatedAt and a Category Field updatedAt. The no-op
assertion is preserved, and that journey's final physical reopen has not yet run.

The shared `TemplateAuthoringPolicy` compares Field and Category-option lists in
materialization order. Canonical DAO reads order rows by position and ID; repository
resolution appends retained archived rows after submitted active rows. Therefore
the same persisted IDs, positions, values and lifecycle flags can be classified as
SEMANTIC solely because these in-memory list orders differ. Activity and Sequence
classification contained the same mechanism. At probe 2, the implementation stopped
for explicit resolution; no writer fix or assertion weakening was made.

The user confirmed that stable IDs, persisted positions, all values and lifecycle
flags determine no-op equality; incidental list materialization order is not a user
change. Opening/saving unchanged or changing/restoring values preserves revision,
Template updatedAt and Field updatedAt. This is recorded in the authoritative
implementation clarifications before the production correction.

Final cheap verification passed with exit zero (50 seconds):

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain
git diff --check
```

The route-test identity audit found no removals or additions during repair; inventory
remains 509 app / 627 data / 2 startup. JVM tests were not rerun for these fixture-only
changes. Full unfiltered compatibility/startup/exact-head gates remain Controller-owned.
Manual TalkBack/Switch Access validation remains unperformed.

### Confirmed no-op correction and regressions

`TemplateAuthoringPolicy` now compares Activity and Sequence Fields and options in
identity-keyed maps for semantic and presentation equality. Persisted positions and
all previous compared values/lifecycle flags remain part of the comparison. Sequence
node order and snapshot configuration comparison are unchanged. Existing writers
return the canonical current aggregate for NO_OP, preserving every Field timestamp;
no writer transaction or database query was added or changed.

Two new Jupiter cases in `TemplateAuthoringModelsTest` failed first with false
SEMANTIC classification (8 cases, 2 failures). For Detekt's method-length limit,
they were split into four cases using explicit archived-row fixtures, separating
no-op equality from real change admission. They independently exercise Field
order, option order and both together, repeated labels, retained deleted Fields and
archived options. They also retain SEMANTIC admission for changed positions, default,
option identity, option/Field removal and lifecycle flags, and PRESENTATION_ONLY for
Field/option renaming. Existing tests cover Number defaults and Sequence node reorder.

The existing data identities
`optionLabelRenameIsPresentationOnlyAndCreateCollisionRollsBack` and
`representativeSequenceFieldEvolutionAndExplicitZeroOverrideRoundTripInOneRevision`
now save a canonically reloaded aggregate containing archived rows and compare the
entire returned and reread aggregate/authoring state, including timestamps, revision,
source identities and snapshots. No new instrumentation identities were introduced.
The original production-route unit-restoration no-op assertion is unchanged.

Comparison remains O(Fields + options) time and space with bounded identity maps;
there are no History reads, per-Field database queries, schema or migration changes.

Local verification after the correction:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :domain:ktlintCheck :domain:detekt :data:ktlintCheck :data:detekt :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
.\gradlew.bat :domain:test :domain:ktlintCheck :domain:detekt --continue --console=plain
git diff --check
```

The first command completed all JVM suites and APK assembly, but exited 1 solely
for the two new domain test methods exceeding Detekt's length limit (2m 9s).
The fixture/assertion split resolved those findings; the second command passed
with exit 0 (1m 15s). Final XML totals: **245 domain, 36 data JVM, 421 app JVM**,
zero failures, errors or skips. Domain/data ktlint and Detekt passed; both app/data
AndroidTest APKs assembled. The prior scoped app ktlint/Detekt check remains valid
for the unchanged app sources. Inventory audit still finds 19 app additions,
zero data additions and zero removed original-JUnit identities: **509/627/2**.

The exact authorized probe command was attempted once after the decision. It
returned exit 1 with `No active repair-probe lease` and explicitly left managed
devices Controller-owned; no runtime testcase started and no new device PASS is
claimed. At that point the latest device evidence was probe 2's five passes and
one preserved no-op failure, preceding this correction. The next Controller
attempt below supersedes that pending status. All intended changes remain
uncommitted. No ambiguity remains.

## API 35 Controller attempt 2 and keyboard repair (2026-10-24)

The canonical attempt at the same head, tree fingerprint
`5abe386253e3842d3a945e3890171ca5328a448f6f6b58a68bfc87e32045033d`,
completed 393 app cases with nine failures before a native input dispatch timeout.
All **15 Library route cases** and **6 editor persistence cases** passed, including
new creation and existing modification through production UI, physical close/reopen,
identity/provenance, frozen history, metadata and the retained restored-unit no-op
assertion. All **627 mandatory data cases** passed (632 XML cases, five preexisting
ignored), including both extended archived-row no-op writer regressions. Reports:

`D:/controller/.runtime/check-runs/wf-2933485f29755d0f9317fdc0a813f2f8/S4-a8f81f9350c84bf7b14d879a12d72419/attempt-1/reports/{app,data}/TEST-pixel2Api35-_{app,data}-.xml`

The nine failing original-JUnit identities were reproduced with the exact authorized
`repair-probe.cmd` command. Probe 1 ran all nine: five passed and four failed
(179.1 seconds). After fixture corrections, probe 2 ran all nine: eight passed and
one failed (205.1 seconds). Both were REAL runs, with no errors or skipped cases.
Original reports are under Controller's
`.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/b51ccaaf8ed64f26a6046ce22868695a/`,
respectively `tree-1/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml` and
`tree-2/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`.

- Daily's return-to-route fixture could observe the retained PAUSED controller state
  before the re-entered route published its new controls. It now waits for the exact
  expected native Pause/Resume action as well as the canonical controller state;
  all original execution, no-write and physical-reopen assertions remain.
- Editor combined-scale checks incorrectly measured visual button bounds against
  the minimum native touch target. They now inspect `touchBoundsInRoot`, matching
  the established accessible-target policy and accepted Library/Expanded/Plan
  checks. Both EN/RU extremes pass, retaining minimum size, non-overlap, horizontal
  containment, scrolling, reachability and keyboard dialog assertions.
- Expanded's three native-window failures and Plan's two native-Back failures
  passed in the first isolated probe. Their affected shared key path now uses
  Espresso with the exact target decor root, letting native root focus admission
  precede one key injection. Plan closes its text-entry IME before opening the
  Category popup, then awaits actual popup removal before subsequent assertions
  or keyboard actions. All selected-state, draft and command-counter assertions
  remain; no Expanded/Plan production behavior changed. Probe 2 passed all five.
- The remaining editor regression was strengthened before the production fix:
  probe 2 proved that Tab inserted `\t` into Name instead of navigating. The scoped
  editor input modifier now routes Tab/Shift+Tab through the native Compose focus
  manager and consumes the key without draft mutation. It applies to Name, Short
  Comment, Timer and the shared Field/option input wrapper. SetText, multiline
  text editing and other keyboard actions remain native. The existing regression
  retains its focus, draft-text and zero-write assertions and adds reverse traversal.

There are no new instrumentation identities or inventory count changes in this
repair. No repository, revision, snapshot, schema, localization or query policy
changes accompany the keyboard correction. Per-key work is the existing Compose
focus traversal; ordinary composition adds no draft scans or database reads.

The final authorized probe ran all nine original identities and **passed all nine**
with zero failures, errors or skips (REAL execution, 175.6 seconds). Its log/report:

- `D:/controller/.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/b51ccaaf8ed64f26a6046ce22868695a/tree-3/attempt-1/01.log`
- `D:/controller/.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/b51ccaaf8ed64f26a6046ce22868695a/tree-3/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`

Cheap local verification passed with exit 0 (1m 52s):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain
git diff --check
```

The app JVM XML contains **421 tests, zero failures/errors/skips**. Every new and
retained AndroidTest source compiled and the APK assembled. The identity audit
still finds 19 genuine S4 app additions, zero data additions and zero removals
relative to HEAD: **509 app / 627 data / 2 startup per API**. No original-JUnit
identity changed during this repair. Full unfiltered API 26/31/33/35/36, independent
startup and exact-head GitHub gates remain Controller-owned; the scoped probe is
not a complete-suite PASS or manual TalkBack/Switch Access certification.

## API 35 Controller attempt 3 and focus completion (2026-10-24)

The canonical attempt at head `cb240849a9df454275b211ffeb44d2e58c20d67c`,
tree fingerprint `e1a891d6e322c76c9c9578914248dcdbbdd80eb7597ae3b9b6cc21c44574f6c5`,
ran 261 app cases with nine failures before `INSTRUMENTATION_ABORTED: System has
crashed`. Its data XML again contains 632 cases, zero failures/errors and five
preexisting ignored cases (627 mandatory passes). Original reports are under
`D:/controller/.runtime/check-runs/wf-2933485f29755d0f9317fdc0a813f2f8/S4-9dd7c355df5a4c128645714ea0619cbe/attempt-1/reports/`.

Before starting another probe, inspection of that execution's
`app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api35/adb.021.exe.adb.exe.fail.txt`
found these native platform events:

```text
10-23 23:06:22.784 SurfaceFlinger: AddClientLayer failed, mNumLayers (4096) >= MAX_LAYERS (4096)
10-23 23:06:22.789 SurfaceComposerClient: SurfaceComposerClient::createSurface error Out of memory
10-23 23:06:22.948 libc: Fatal signal 6 (SIGABRT) ... (surfaceflinger)
```

The ninth failure, `inspectionLoadingFailureRetryAndDismissExposeContextWithoutDeleteAdmission`,
occurred while AndroidX's EmptyActivity was starting during this system crash;
its test body was not entered. Later probes overwrite the live build-output logs,
so the observations above refer specifically to the inspected attempt-3 artifacts,
not to newer files at the same build path. No application cleanup, persistence or
platform-configuration workaround was added for that crash.

The fresh baseline probe ran all nine original identities: eight passed and
`duplicateArchiveTargetsRequireConfirmationAndDispatchEachTypedIdOnlyOnce` reproduced
its failed focus assertion after Cancel (157.5 seconds, REAL execution). This also
showed that the earlier focus assertions should not all be attributed to the
eventual platform crash. The production focus policies defer landing/restoration
until native window ownership and a frame; model readiness and Compose idle alone
do not establish completion of those effects.

The shared keyboard fixture now admits the exact native root through Espresso,
then observes the expected control's real `SemanticsProperties.Focused` state and
retains `assertIsFocused`. It does not issue RequestFocus or a FocusRequester call
for the expected control. Editor and Library lifecycle assertions now await that
observable completion, including retry/Ready, foreground visibility, initial dialog
focus, restoration to a surviving invoker and recovery to Back. Initial editor
landing is also observed before subsequent actions. No focus assertion, exact-ID,
command-counter, retained-callback, no-write or draft assertion was removed.

Editor and Library dialog Back now use the exact native-root key helper and await
actual dismissal. Combined-scale editor dialogs open and close using native Enter;
the fixture closes the text-entry IME first so those actions exercise dialog
navigation. The same bounded synchronization covers sibling lifecycle-focus
assertions in both affected presentation classes. Production code, authoring and
Library semantics, identities, revisions, queries and localization are unchanged
in this repair. No arbitrary sleeps or automatic command retries were introduced.

The repaired probe **passed all nine original identities**, with zero failures,
errors or skips (REAL execution, 150.2 seconds). The exact authorized command was
run twice:

```powershell
D:\controller\.runtime\agent-tools\repair-probe.cmd
```

Both original probe reports are under
`D:/controller/.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/ac0c9364c19f45c0a0f3539a78ee5a5f/`,
respectively `tree-1/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml` and
`tree-2/attempt-1/reports/app/TEST-pixel2Api35-_app-.xml`; each matching log is
`01.log` in its attempt directory. Scope: the five editor cases for EN/RU scaling,
loading/retry, visibility/disposal and Discard, plus the four Library lifecycle
cases for typed duplicate archive, empty Folder, final destructive confirmation
and inspection/retry. These are the exact nine IDs supplied by Controller.

Cheap checks passed with exit 0 (1m 4s):

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain
git diff --check
```

Only AndroidTest fixtures changed, so JVM suites were not rerun; the preceding
421-test app JVM result applies to unchanged production code. No original-JUnit
identities were added, renamed or removed. Inventory remains **509/627/2**.
The long-run native layer exhaustion and the complete unfiltered five-API/startup/
independent GitHub gates remain Controller-owned verification obligations. The
scoped pass does not establish full-suite stability or manual accessibility certification.

## Cross-API CI repair (2026-10-24)

CI run `40059549209` tested head `846ed9f26b617b30e68ff485ebe103ef2482d222`
and tree `c19af2d55b374494635339e5680f402a75169191fbd189aa98fc56bb9434ca71`.
The supplied failure inventory contains these distinct cases per check (duplicate
artifact copies count once):

| API | Failure coverage |
| --- | --- |
| 26 and 31 | One Library organization case: removed-control recovery to Back. |
| 33 | Three Library route, three Activity editor, eight destructive Library and three organization cases. |
| 36 | Two Library route, fourteen Activity editor, eight destructive Library, two organization and seven expanded live Sequence cases. |

An earlier local pass does not close these CI failures. This repair addresses the
following shared boundaries without changing authoring, persistence or runtime
commands:

- `FolderNameAction` requested initial input focus from the parent composition,
  before the native dialog acquired its own window. It now observes that dialog's
  `LocalWindowInfo` and an attachment frame. Lifecycle dialogs likewise wait for
  their own window before focusing Cancel. Their effect lifetime and route guard
  own this focus request; an unrelated whole-state reference comparison no longer
  discards an otherwise valid focus request during a refresh.
- Library removal recovery could discard its pending marker while the focus group
  still temporarily reported focus. It now observes the group's focus changes and
  retains the marker until focus loss permits recovery, or a surviving control
  claims focus. The equivalent Activity editor mechanism is corrected as well,
  including its editable/discard-dialog admission dependencies.
- The remaining Library organization, discovery and production-route fixtures now
  await exact native-window and semantic-focus completion. `assertIsFocused`
  remains inside the shared observer; that observer never requests the expected
  control's focus. Dialog Enter/Back uses the exact-root native key helper; the
  route name-mutation helper observes initial input focus before editing and
  Confirm focus before sending Enter. Back dismissal explicitly closes the IME
  first and observes dialog removal. Exact typed actions, counters, retained
  callback rejection, drafts and canonical physical-reopen assertions remain.
  Keyboard-focused organization scenarios use semantic activation for non-key
  actions, avoiding pointer injection that would change the fixture's input mode.
- `KeyboardInputFixture` previously assumed an awake, unlocked display throughout
  a long suite. It now establishes those prerequisites, keeps the participating
  root awake during keyboard interaction and restores each root's previous
  `keepScreenOn` value on teardown. It does not change device timeout settings,
  application permissions or production windows. All exact-root window-focus and
  semantic-focus assertions remain. This shared fixture is also used by the
  reported Activity editor and expanded live Sequence cases. Native-window
  observation and key dispatch establish the same display prerequisites even
  when a test has not called keyboard entry first.

The sustained API 36 `RootViewWithoutFocusException` failures are consistent with
the missing display prerequisite. The supplied artifacts establish lost native
window focus, not a confirmed screen-off/keyguard event; that causal explanation
remains an inference until Controller executes the repaired matrix. These failures
are not reclassified as an external platform failure, and no new device pass or
full-suite stability claim is made here.

The new original-JUnit case
`LibraryOrganizationPresentationTest.keyboardEntryWakesSleepingDisplayAndRetainsNativeLandingWithoutCommands`
establishes initial keyboard focus, explicitly puts the display to sleep, observes
that prerequisite, reenters keyboard
mode and requires an awake display, real native Back focus, an awake-window flag
and zero commands. Its finally block wakes the display even on failure. The case
was added before the fixture repair; fail-first device execution was unavailable.
It is pinned in the mandatory inventory, bringing the current totals to
**510 app / 627 data / 2 startup per API**, with zero removed identities.

The authorized command `D:\controller\.runtime\agent-tools\repair-probe.cmd`
returned exit 1 with `No active repair-probe lease` before any device/testcase
execution. No emulator or managed-device task was launched directly. Controller
must run this regression and all reported cases on the repaired tree, followed by
the complete five-API suites and independent exact-head gates. Automated source
and APK checks do not establish manual TalkBack/Switch Access certification.

Final local verification completed with exit 0 (`BUILD SUCCESSFUL`, 3m 39s):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LibraryControllerTest' --tests '*ActivityTemplateEditorControllerTest' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
python -m unittest discover -s .github/scripts -p 'test_*.py'
git diff --check
```

The relevant JVM reports contain 28 Library controller and 20 Activity editor
controller cases, with zero failures, errors or skips. The final invocation reused
the matching successful JVM task. App ktlint, Detekt, Android lint and AndroidTest
compilation/APK assembly passed. Earlier formatting, line-length and method-length
findings were corrected before the successful invocation. All 17 runner/inventory
host tests passed; their printed device commands are mocked and launch no devices.
A source/inventory audit confirmed every pinned app identity, including parameterized
names, and exactly one addition with no removals. Controller's prompt retained its
supplied SHA-256. All intended changes remain uncommitted; no new repository path
was introduced.

## API 35 dialog-root repair follow-up (2026-10-24)

Controller's full local execution reported
`LibraryAccessibilityRouteTest.deleteContentsArchivesBothKindsAndPreservesCanonicalHistoryAfterPhysicalReopen`
with `RootViewWithoutFocusException` on a base Activity window. The full execution
also timed out; its data suite completed, but its app suite did not finish.
The authorized probe is restricted to this one original route identity.

The first probe attempt stalled before test discovery (zero executed cases).
Controller cleaned stale managed devices and retried automatically. The unchanged
baseline then passed the route case (REAL execution, 102.8 seconds; testcase time
17.207 seconds), so the supplied focus failure remains intermittent.

The remaining global `Espresso.closeSoftKeyboard()` calls in the affected Library,
Activity editor and expanded live Sequence fixtures used Espresso's default root
selection. They now share `KeyboardInputFixture.closeKeyboard(target)`, which
derives the attached native window from the supplied semantic control and performs
the normal Espresso keyboard-dismiss action in that exact root. This also retains
the fixture's awake-display prerequisites and native-window admission. The route's
non-input destructive dialog therefore no longer asks a global root selector to
choose a window before Back dismissal. Existing focus, one-shot dispatch,
stale-callback, zero-write and physical-reopen assertions remain intact.

This is a fixture reliability correction, not proof that root selection caused
every supplied cross-API failure. The preceding production focus repairs are
preserved. No test identity is added or removed; inventory remains 510 app / 627
data / 2 startup per API. Full unfiltered compatibility, independent GitHub gates
and manual TalkBack/Switch Access validation remain outside this scoped evidence.

The baseline log and JUnit report are under
`D:/controller/.runtime/repair-probes/wf-2933485f29755d0f9317fdc0a813f2f8/e02df2b3eb33404ea0e1879ae35dc670/tree-1/attempt-2/`
(`01.log` and `reports/app/TEST-pixel2Api35-_app-.xml`).

Local verification of the follow-up passed (`BUILD SUCCESSFUL`, 2m 40s):

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*LibraryControllerTest' --tests '*ActivityTemplateEditorControllerTest' --continue --console=plain
python -m unittest discover -s .github/scripts -p 'test_*.py'
git diff --check
```

The JVM task reused its matching cache result: 28 Library and 20 Activity editor
controller cases, zero failures/errors/skips. All 17 host runner/inventory cases
passed (device commands in their output are mocked). The repaired-tree authorized
probe uses the same exact command and one-case scope as the baseline; its result
and artifact location are reported in the structured completion. A scoped pass
does not certify full-suite or cross-API stability.
