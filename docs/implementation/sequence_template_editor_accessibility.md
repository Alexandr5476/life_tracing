# Accessible Sequence Template authoring shell (S1)

Product v0.16 sections 8–10, 13, 33 and 46, domain v0.10, schema v0.6,
implementation clarifications and the design README remain authoritative.
This slice covers the editor lifecycle, Sequence-level configuration and
Sequence-level Custom Fields. Step/Repeat manipulation, the Add Step picker,
expanded local Activity configuration, snapshot Fields/options and source actions
retain their existing paths and are not certified accessible by S1.

## Presentation and identity

MainActivity passes top-route visibility to the existing retained Sequence route
session. Its owner, target and exactly-once committed Library refresh policy are
unchanged. New/Existing context comes from the immutable editor target, never its
name. Native headings, pane context and pristine/modified state use resources.
Loading has Back; load failure has Retry and Back. Saving, Committed, Conflict,
ordinary Save failure and committed canonical-reload failure have distinct localized
feedback. Only status text uses polite live regions, scoped to visible content.
Conflict exposes explicit Reload; ordinary Save failure keeps editable draft data
for another explicit Done. Recovery Retry reads canonical data without replaying
an already successful writer.

All Sequence-level inputs use native editing semantics and Tab/Shift+Tab traversal.
Settings exposes expanded/collapsed state; its individual native checkboxes expose
actual checked values. Countdown raw invalid text, ranges, dirty state and Save
admission are unchanged. Number precision/default input keeps the existing rejection
policy and now supplies localized error/guidance; Missing remains distinct from
explicit zero. Focus, disclosure and field inspection never save data.

SequenceTemplateEditorFields keys Field and option content by the existing draft
identities. Labels describe operation, localized type, current ordinal and current
name; option labels add current ordinal and label. Duplicate names/labels remain
legal. SequenceTemplateEditorActions resolves captured identities against current
controller data and checks type compatibility and option membership before changing
anything. Number parsing uses current precision. Removal preserves ordering and
clears a removed selected option's default. Main Value remains unique and checked.
No presentation operation allocates replacement identity for an existing Field;
submittedDraft remains the semantic type/unit replacement boundary.

Actions are ephemeral presentation leases, revoked by hidden content, disposal,
closure or a newer load/canonical generation. Discard leases additionally bind to
the open confirmation generation, so a dismissed dialog cannot act on a later one.
Controller admission rejects edits, text-input changes, source/manipulation commands,
Back and Save while Saving, Committed, recovery pending or a discard dialog is open
(as applicable). Dismissal and confirmation remain explicit commands. Nonstructural
edits during manipulation remain supported. No-op transformations do not clear
failure state or append manipulation history.

Successful and failed asynchronous loads publish only under the command lock and
matching generation. Canonical reloads advance that generation. Picker publications
share this authority; the deferred picker UI itself is unchanged. Retry reserves its
recovery command atomically. There is no new schema, migration, durable identity,
repository writer, transaction policy or persistent accessibility state.

## Focus and layout

The existing Activity editor focus utility is generalized with equivalent Activity
call sites. Visible Loading, Failure, Ready and committed-reload recovery land on
Back, Retry, Name and recovery Retry, respectively. A lazy landing target is revealed
before requesting focus. Logical target lifetime is separate from lazy detachment:
scrolling a surviving input offscreen never pulls the list back to Name. Stable keys
preserve surviving Field/option focus; actual removal recovers only when no surviving
editor control has acquired ownership. Hidden/disposed content cannot claim focus.

The native discard dialog initially focuses Continue Editing. Native dismissal and
its buttons use the controller's admission checks; dismissal restores its surviving
originating control or Name. Dialog content carries effective context, configuration,
density and LifeTracingAccessibleTargets. Choice groups wrap; controls stack and
text scrolls. The Sequence node list remains lazy. No new animation is introduced.

## Executable evidence

The original-JUnit inventory adds **16 app identities**, preserving every existing
identity: **544 app, 627 data, 2 startup cases per API**. Thirteen new methods in
SequenceTemplateEditorScreenPresentationTest cover native context/headings, entry
and phase focus, SetText and bidirectional Tab, Settings states/countdown errors,
duplicate Field/option targeting, removal/type changes and retained callbacks,
Main Value/default state, Missing versus zero, focus survival/removal/lazy scrolling,
discard dismissal, hidden/disposed leases, gated Save/reload recovery, ordinary
failure retry, and EN/RU combined appearance extremes (system font 200%, app
90%/90% and 120%/130%, native target sizing and non-overlap).

Three production MainActivity/Library journeys in LibraryAccessibilityRouteTest:

- `nativeNewSequenceAuthoringCommitsOnceAndPhysicallyReopensCanonicalConfiguration`
  uses native Sequence configuration and Number/Category/Text controls, Activity
  recreation, keyboard Done and retained duplicate activation. It closes the host,
  runtime graph and database, physically reopens the same file and verifies canonical
  identity/revision, configuration, Field/option identities/order/defaults, Library
  discovery and absent Execution/Plan/active-session writes.
- `nativeExistingSequenceFieldsPreserveCompatibleIdentityAndFrozenStepsOnPhysicalReopen`
  checks compatible Number/Category identity, option removal/rename/default,
  type/unit replacement, Main Value, Sequence settings and a restored-unit no-op.
  Canonical reads and frozen durable-fact comparisons preserve Steps, ActivitySnapshots,
  StatisticsSeries and unrelated runtime/Plan/history data through physical reopen.
- `productionSequenceConflictExplicitReloadAndDiscardLeaveNoPartialWriteOnReopen`
  admits a real concurrent writer, preserves the rejected draft/session across
  recreation, explicitly reloads and discards subsequent changes, then physically
  reopens and compares every durable fact with the concurrent state.

Six new Jupiter controller methods cover old successful/failed reads, closed-route
publication, dismissed/reopened dialog callbacks and Committed admission, typed
Field/option presentation leases, ordinary Save retry with raw countdown validation,
gated Saving/committed-read recovery without repeating the writer, and obsolete
Picker success/failure/closed-route publication. Existing
controller/manipulation/route-owner tests remain. The canonical graph-read regression
uses Apply for its intermediate commit so its subsequent source-action assertion runs
in an editable session instead of reusing a terminal Done session.

## Verification

Fail-first: `:app:testDebugUnitTest --tests '*SequenceTemplateEditorControllerTest'`
ran 20 cases with the two expected regression failures (obsolete load publication
and dismissed/Committed callback admission). After fixes the complete app JVM suite
ran 430 cases, including all 24 Sequence controller cases, with no failures/errors/skips.

The complete app JVM suite, app ktlint, Detekt, lintDebug and AndroidTest APK assembly
completed successfully (exit zero):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
```

After test-only refinements for lazy control discovery, selected-option removal,
retained Main Value/current-precision callbacks and scaled native-dialog checks,
the final source/APK/static pass also completed successfully (exit zero):

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The host runner suite passed **69 mocked tests**. Its printed device commands are
simulated runner fixtures, not emulator launches. Inventory comparison confirms all
18 original Sequence controller, 18 original Sequence presentation and 16 original
Library route test identities remain, with 6, 13 and 3 additions respectively.
The final AndroidTest APK includes all 16 new pinned app instrumentation identities.
No full CI parity, coverage gate or independent GitHub device-gate PASS is claimed.

Managed-device tasks are Controller-owned and were not launched locally. AndroidTest
compilation/APK evidence does not claim device execution. Manual TalkBack, Switch
Access and physical hardware-keyboard certification were not performed.

## Canonical API 35 repair, attempt 1

The Controller run reached all app tests and reported seven failures; this is not
an infrastructure-only failure. Lazy editor discovery is now used by Sequence
Library commands, retained-route Apply and source-action presence/activation checks.
Sequence keyboard landing is admitted only in keyboard mode and outside the deferred
manipulation session, preserving pointer scrolling/drag ownership. Activity keeps its
existing mode policy. Dialog origin is captured before disabling editor controls, so
native fallback while the dialog opens cannot replace the restoration target.

The removed-Field diagnostic showed Settings already focused. The regression now
checks that this surviving native owner remains focused across an unrelated edit
and can traverse with Tab; forcing Name would violate the no-focus-stealing rule.
The gated Save journey establishes keyboard mode while its editable form exists,
then observes production recovery focus rather than trying to acquire a native root
with disabled/offscreen controls. Original test identities and assertions of durable
behavior are retained. Repair-probe and local-check results follow after verification.

The edge-drag log records automatic IME show/hide at entry/manipulation. Suppressing
normal-form focus during pointer entry and manipulation removes that extra scrolling
and viewport change without changing drop destinations, gesture budgets or structural
commands. Source-action discovery also includes its initial Edit Step activation,
so no assumption about initial lazy precomposition remains in that failing journey.

The authorized `D:\controller\.runtime\agent-tools\repair-probe.cmd` command
refused execution with “No active repair-probe lease.” CLI inspection confirms this
requires Controller-supplied endpoint/token environment; no device task was launched
and no lease or Controller state was changed. All seven canonical device cases remain
pending a Controller rerun; local compilation does not establish their runtime PASS.

The first Field-focus repair also tried clearing native focus before removing a
focused Field. The causal rerun below disproved its assumption that this guarantees
no native owner: Android synchronously assigned Back. The current regression preserves
that actual owner and verifies native Tab traversal, without requesting an expected
fallback target. Focus-manager capture uses an AtomicReference populated by a
composition SideEffect, not a racing lateinit callback. No original JUnit identity
or inventory entry is removed.

Repair local verification completed successfully:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceTemplateEditor*' --tests '*ActivityTemplateEditor*' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

The focused JVM selection passed **48 cases**, zero failures/errors/skips. The final
AndroidTest APK, app ktlint, Detekt and lintDebug passed after the last test-only
changes. Save and both Back activations in the repaired keyboard journeys use Enter,
so a touch click cannot silently change input mode before a recovery assertion.
The initially caught Detekt parameter-count finding was resolved with a focused,
explained suppression on the shared focus-policy helper; no test was suppressed.
Pinned test inventories are unchanged, with no missing original test identities.

No repaired device testcase was executed: the repair-probe lease refusal occurred
before device/app/test startup. It does not reclassify the original seven app assertion
failures as infrastructure failures or establish a new canonical PASS. Controller
must restore the probe environment and rerun the seven cases, followed by its gates.

## Causal API 35 repair, attempt 1

The Controller's seven-case causal run (`S1-causal-036f704c6cd543e69e00360797ee3f23`)
passed discard-dialog dismissal and reported six remaining app failures. The following
repairs are based on those failures and bounded source/fixture inspection; they have
not yet been confirmed by a device rerun:

- Canonical source publication changes the load generation. Its presentation key
  correctly revokes old callbacks but also recreated the Step disclosure state.
  Expanded Step identities now live above that boundary and are pruned against the
  current rows. The same source-action regression explicitly requires Close Step
  before inspecting each set of source commands. Disclosure actions keep the current
  presentation lease; no draft or repository policy changes.
- Material's retained native OnClick can observe the current callback after a button
  is rebound. Save and committed-read Retry now have separate Compose keys. The same
  fix covers the sibling manipulation Apply/Retry toolbar. Both original tests retain
  the pre-commit native action and assert that it neither starts a canonical retry
  nor executes another writer after committed-reload failure.
- The Application cached the first runtime graph used by any Activity. An isolated
  Library fixture can dispose that graph before the next MainActivity test, leaving
  foreground publication on its cancelled scope. Activity-start delivery now captures
  the current graph once, using the existing owner. Production ownership stays shared.
  The existing recreation helper keeps both its new-Activity and semantic-generation
  requirements, with diagnostic context added for either missing condition.
- The Field-removal diagnostic reports Back already focused, including after explicit
  clearFocus. The test accepts a real surviving native owner and checks Tab traversal.
  Production removal recovery checks actual root focus rather than merely whether
  the last remembered handle remains attached after losing focus.
- The Library native-command fixture transfers native focus to the selected button
  before its final scroll and activation. This releases the previously edited input's
  bring-into-view ownership while retaining the displayed-control assertion.
- The edge-drag fixture must stop when the Repeat drop interior enters its small
  viewport. Its former condition required a tall Repeat to clear an additional
  64 dp bottom inset and could scroll past the reachable target. It now uses the
  visible intersection; the same 40-event budget, real-list scroll assertion, native
  pointer release, exact destination, one operation and frozen-child checks remain.

The authorized repair probe was attempted and refused before device/app/test startup:
`No active repair-probe lease. This workflow state is not an authorized focused-device
repair session.` No raw device task, emulator, Controller-state change or credential
workaround was attempted. This refusal does not reclassify the six app assertions as
infrastructure failures. The earlier repaired-case runtime hypotheses were not a PASS.

Local verification for this repair uses:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceTemplateEditor*' --tests '*ActivityTemplateEditor*' --tests '*AndroidRuntimeCoordinatorTest' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

The focused JVM selection passed 74 cases (48 editor/controller/session-owner and
26 runtime-coordinator cases), with no failures/errors. App ktlint, Detekt, lintDebug
and AndroidTest APK assembly passed before the final sibling Apply regression;
final verification results are recorded below. No JUnit identities or inventory
entries were added or removed in this repair. Device execution, full gates and manual
TalkBack/Switch Access certification remain unperformed in this repair environment.

Final post-repair verification passed, including the retained-Apply sibling and its
obsolete-generation guard:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

The two focused JVM runs each passed 74 cases, zero failures/errors/skips. Final app
static analysis, lint and AndroidTest APK assembly passed after all Kotlin edits.
Inventory/source comparison confirms Sequence presentation 18 original / 31 current,
Library accessibility 16 / 19, and MainActivity route sessions 20 / 20, with no deleted
original or unlisted identities. The same six canonical device cases and the extended
retained-Apply sibling require Controller runtime verification. No new device PASS,
full-suite PASS, CI parity or manual accessibility-service certification is claimed.

## Causal API 35 repair, attempt 2 — remaining edge drag

The Controller run `S1-causal-541a421c85664dd19666376afb02488d` executed the six
previously failing cases: five passed; `edgeHeldHandleDragScrollsToOffscreenRepeatAndMovesFrozenChildOnce`
still failed because the target Repeat was not displayed. The earlier target-intersection
hypothesis did not resolve it. The scroll-progress assertion passed, but this execution
retained no per-frame geometry or scroll position at the point scrolling stopped.
There is insufficient evidence to distinguish lazy source recycling/gesture loss,
coordinate drift in a moving handle, premature edge-boundary admission, or overscroll.
No further production change is justified by this evidence alone.

Both original edge-held drag tests now record bounded frame traces: injected root
position, scroll value/maximum, current page/source/drop-row tags, raw origin/size and
clipped bounds. Assertion failures retain this trace before cancellation through the
existing durable diagnostic artifact and acknowledged NDIAG1 logcat transport, then
rethrow with the original assertion as cause. Downstream exact-draft, frozen snapshot,
operation-count and Undo assertions are retained and report the same trace. The event
budgets, viewport, gesture path, reachability assertion and expected outcomes are
unchanged. No original test identity or inventory entry is removed or suppressed.

The authorized repair probe was attempted again and refused before device/app/test
startup: `No active repair-probe lease.` No raw device task or Controller lease/state
change was attempted. The remaining app assertion is unresolved and is not reclassified
as an infrastructure failure. This is a diagnostic evidence repair, not a claimed fix
for the remaining drag behavior. Controller must rerun that case to obtain the trace.

Diagnostic-repair verification:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
# From .github/scripts (host-only artifact-retention fixtures):
python -B -m unittest test_run_instrumentation.InstrumentationEvidenceTests.test_native_diagnostics_survive_startup_output_replacement test_run_instrumentation.InstrumentationEvidenceTests.test_agp_intermediate_diagnostic_artifacts_survive_startup_and_are_quarantined test_run_instrumentation.InstrumentationEvidenceTests.test_native_diagnostics_survive_missing_malformed_or_unsuccessful_reports
git diff --check
```

App ktlint, Detekt, lintDebug and AndroidTest APK assembly passed. All three selected
host retention tests passed. Source comparison confirms the same 31 Sequence
presentation JUnit identities; no inventory changes were needed. No device testcase
ran during this repair, so trace capture inside the failing gesture and its behavioral
root cause remain unverified. No production drag code was changed in this attempt.

## Causal API 35 repair, attempt 3 — prefetched geometry is not displayed state

The retained 2,478-byte trace from `S1-causal-9cdb343f003146dea63970682a981068`
identified a concrete fixture error. Before pointer down, the existing assertion says
Repeat r2 is not displayed, yet its prefetched semantics reports origin `(63, 63)`,
size `954 x 315` and positive clipped bounds. Those same coordinates remain unchanged
through slop and frame 0 while the real list scroll increases from 4066 to 4150 to
4234. The trace ends at frame 0, proving the bounds-only loop exited early on the
unplaced item's geometry; it did not exhaust the 40-event scrolling budget.

The loop now requires Compose `isDisplayed()` before considering the visible drop
interior. A separate assertion requires a displayed interior within the same original
40-event budget. The original target-display, native pointer release, exact frozen
draft, one-operation and Undo assertions remain. The trace includes native source
and target displayed states so cached coordinates cannot obscure placement again.
A scoped search found no sibling bounds-only reachability loop; both edge tests keep
the shared diagnostic capture. No production drag code or authoring semantics change.

The authorized repair probe again refused before device startup with `No active
repair-probe lease.` This attempt corrects the trace-confirmed test fixture error;
it does not claim runtime PASS or speculate about a separate production drag fault.
Controller still owns the focused rerun and independent device gates.

Attempt 3 local verification completed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

The final invocation passed all listed tasks after splitting one overlong diagnostic
string caught by the initial ktlint invocation. AndroidTest compilation confirms the
native displayed-state query against the project's Compose API. All 31 Sequence
presentation test identities remain unchanged and pinned. The decoded artifact was
verified at 2478 bytes and SHA-256
`241e090bcc3af3e09c6717bb539ce9d682d88fe3f4aa89cb51d3d1c39d33c6b8`.
Only the instrumented fixture and this evidence document changed in this repair;
no JVM production change required another controller-suite run. No device tests or
manual accessibility-service validation ran during this repair. The supplied device
failure remains pending a Controller rerun; APK/static success is not runtime PASS.

## Full API 35 rerun — foreground discard-dialog command discovery

The Controller full run `S1-950e07b23f04419fb0ece68ff5b588cf` reported one failing
app case out of 545: `productionSequenceConflictExplicitReloadAndDiscardLeaveNoPartialWriteOnReopen`.
The earlier edge-held drag case passed in this run. Discard failed during fixture
lookup, before activation: the shared command helper unconditionally searched the
Sequence LazyColumn whenever a retained Sequence editor session existed. That session
correctly remains alive behind its discard dialog, whose buttons belong to another
native Compose root and cannot be descendants of the editor list.

The fixture now gives an existing native dialog root priority and scopes commands to
that dialog's descendants. If a dialog is open, it cannot fall back to an equally
named background editor command. Editor-list discovery and input-focus transfer
remain for foreground Sequence-form controls. This shared fix also covers the existing
Activity discard/Continue Editing callers, without changing production dialog,
controller, route ownership or persistence policy.

The failing original journey now explicitly asserts the native dialog, activates
Continue Editing, checks the same retained session and unchanged draft/durable facts,
then reopens the dialog and activates Discard. Its conflict, explicit reload,
route-completion and physical database reopen assertions remain. No original test
identity or inventory entry is removed or suppressed.

The authorized repair probe refused before device/app startup with `No active
repair-probe lease.` No raw device task or lease/state workaround was attempted.
This refusal is separate from the supplied repository test-fixture failure; no new
runtime PASS is claimed. Controller owns the focused rerun and independent gates.

Local verification for this repair passed:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

All 19 Library presentation/navigation JUnit identities remain unchanged and pinned.
Only the instrumented fixture and this evidence document changed in this repair;
the production/controller JVM code was unchanged. AndroidTest source/APK compilation,
ktlint, Detekt and lint passed. The repaired native dialog journey and its physical
reopen assertions still require Controller runtime verification. No device tests,
full-gate PASS or manual TalkBack/Switch Access certification are claimed for this
repair.

## S1C1 — presentation admission and retained Apply acknowledgement

Sequence native actions now bind to a monotonic manipulation-mode epoch in addition
to the load and discard-dialog generations. Entering or ending a manipulation
session permanently retires its previous presentation actions; selection and ordinary
keystrokes do not change that epoch. Normal-form leases independently require normal
editing, including callbacks exposed while the form is disabled. Native toolbar and
Sequence-form controls have mode keys so Material cannot rebind a retained native
callback to a later mode's authority. Done, manipulation Apply, manipulation Discard
and editor Discard retain their separate controller semantics. Direct controller
nonstructural manipulation edits and Undo/Redo remain supported. Visibility changes
also retire the previous leases, so showing the same route cannot reactivate native
callbacks captured before hiding it; the existing hidden/disposed journey checks this
before advancing the load generation.

Settings owns a disclosure lease above lazy item composition. Collapse immediately
revokes it, and reopening creates a new presentation. Viewport detachment neither
revokes this lease nor marks its native targets logically absent. The shared focus
handle can report logical presence separately from attachment; Sequence explicitly
observes disclosure changes, including collapse of a previously detached control.
Recovery still defers to a surviving native focus owner before revealing Name. No
Activity editor call-site policy changes, persistence command or new motion is added.

MainActivity acknowledges each appliedGeneration on the retained Sequence route
session before refreshing Library. Its constant-space high-water mark survives
Activity recreation and ends when the session is released. Apply and source-action
canonical refresh remain distinct eligible generations; explicit load success/failure
now preserve this session-monotonic counter. No acknowledgement is stored in Room.
The existing exactly-once committed exit policy remains the Done boundary.

The fail-first JVM regression demonstrated obsolete normal-form mutation/Save under
manipulation. Added coverage exercises mode admission and revival, supported direct
controller history, retained-session acknowledgement/release, native form/Field/
Settings/Done callbacks, discarded Apply and reentry, Settings collapse with native
focus/traversal, and expanded Settings scrolling. Existing source-action and committed
Apply/Retry journeys also retain their notification acknowledgements across fresh
route compositions. The production MainActivity journey checks one Library request
per Apply across two Activity recreations, stable session identity and one Done exit;
the production Library persistence journey rejects collapsed callbacks before its
existing physical reopen checks. Original JUnit identities and assertions remain;
four added Compose identities are pinned in the five-API inventory.

The fail-first command ran the new mode-admission regression against the original
production code and produced its expected assertion failure:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceTemplateEditorControllerTest.presentationActionsCannotCrossManipulationAdmissionOrExitButControllerEditsRemainSupported' --console=plain
```

Final local verification passed (exit zero):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
# After the final valid-Activity prerequisite refinement in the native Done fixture:
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
# From .github/scripts; mocked reports, with no device launch:
python -B -m unittest test_run_instrumentation.InstrumentationEvidenceTests.test_complete_inventory_accepts_nonmandatory_ignored_fixtures_on_every_api
git diff --check
```

The complete app JVM suite passed **433 cases**, zero failures/errors/skips. App
ktlint, Detekt, lintDebug and AndroidTest APK assembly passed after the final Kotlin
edits. The host inventory test passed for data/app/startup report fixtures across all
five APIs. Source comparison preserves every original changed-class JUnit identity:
Sequence controller **24 → 26**, route-session owner **2 → 3**, Sequence presentation
**31 → 35**, MainActivity route sessions **27 → 27**, and Library accessibility
**19 → 19**. All original pinned identities remain; the app inventory now pins 548.

Device execution (API 26/31/33/35/36), full/gmd-full Controller gates and independent
GitHub CI remain Controller-owned and unperformed here. APK compilation does not
establish runtime PASS for the new native regressions or production navigation
journeys. No full CI parity or coverage-gate PASS is claimed. Manual TalkBack and
Switch Access validation remains unverified. Full S1 acceptance must be reviewed
again before another Parent slice.

## S1C1 API 35 repair — fixture prerequisites and native Undo delivery

The Controller run `S1C1-895b7a04f92146b384a2c62ea5ed41c2` reached 549 app cases
and reported two failures. These are repository test failures, not infrastructure-only
failures. Apply's fixture referenced snapshot `snapshot`, although `pointerAuthoring`
supplies only `snapshot-a`, `snapshot-b` and `snapshot-c`. Authoring mapping correctly
rejects that missing snapshot. The fixture now links to its actual snapshot; its fake
writer also preserves the submitted pre-entry comment in canonical state and advances
the revision. The journey asserts that same comment after canonical Apply recovery.

The normal-form journey passed its retained-callback admission assertions, then its
current Undo touch activation left the comment unchanged. That fixture neither waited
for native enabled-state publication nor explicitly dispatched the native command.
It now waits for enabled Undo, asserts it is displayed, dispatches its current native
OnClick and requires the same full-draft restoration. Undo/Redo availability and
operation-count assertions also establish the history prerequisites and result.
No production command or history policy changes. This corrects the native callback
fixture; it does not establish why the former coordinate-based activation missed Undo.

The shared Ready prerequisite now waits for a terminal load publication and asserts
Ready with the actual load result, exposing malformed fixtures directly instead of
reporting only a timeout. The remaining pointer fixtures reference their supplied
snapshots; no original test identity or assertion is removed or suppressed.

Both authorized repair-probe attempts refused before device/app/test startup with
`No active repair-probe lease.` No raw device task or lease/state workaround was used.
The two original runtime failures remain pending a Controller rerun; no repaired
device PASS, full-gate PASS or manual accessibility-service validation is claimed.
The focused JVM selection passed **33 cases** (26 Sequence controller and 7
manipulation), with zero failures/errors/skips. The initial combined invocation
caught formatting findings in the new chained assertions; those were corrected.
Final app ktlint, Detekt, lintDebug and AndroidTest APK assembly passed (exit zero):

```powershell
# Initial invocation: JVM selection passed; new assertion formatting then corrected.
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceTemplateEditorControllerTest' --tests '*SequenceManipulationTest' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
# Final source/APK/static pass:
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

Source comparison confirms all 31 original Sequence presentation identities remain,
with the same 35 current identities exactly matching the pinned inventory. This
repair changes only the instrumented fixture and this evidence; production admission,
controller history, persistence and route-delivery code remain unchanged.

## S1C1 causal repair — nullable comment deltas

The Controller run `S1C1-causal-0bf4ebe643fa423888c215f8fb3c3274` passed the repaired
Apply case and still failed full-draft restoration after enabled native Undo OnClick.
This disproves the prior activation hypothesis. The history scalar-change helper used
`after.takeIf { it != before }?.let(::ValueChange)`, which discarded a changed value
when the target was null. Undo could consume its entry while leaving the comment in
place; Redo of an explicit comment clearing had the same defect.

The new `commentHistoryRestoresMissingValuesOnUndoAndRedo` JVM regression failed
before the production fix with the same draft-restoration assertion. The helper now
compares first and creates `ValueChange(null)` for an actual change to null. The
existing delta application already distinguishes that wrapper from an absent change.
All scalar callers were reviewed: Short Comment is the nullable scalar; map-entry
deltas already preserve null targets. Delta shapes, structural operations, ordering,
snapshot provenance, writer boundaries and history accounting remain unchanged.

The session regression covers Undo to a missing comment and Redo of explicit clearing,
plus exact draft and history-state assertions. A controller regression exercises
Undo/Redo through the same current presentation lease with an initially null comment.
The original instrumented failure and its adjacent history assertions remain intact.

Both authorized probe attempts in this repair refused before startup with
`No active repair-probe lease.` No raw device command or lease/state workaround was
used. The JVM reproduction identifies the repository cause; it does not establish a
new device PASS. Controller runtime confirmation, full gates and independent CI
remain required.

Fail-first verification ran one case and failed at the exact missing-comment draft
restoration assertion before the production helper changed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceManipulationTest.commentHistoryRestoresMissingValuesOnUndoAndRedo' --console=plain
```

Post-fix verification passed (exit zero): **35 JVM cases** (27 Sequence controller,
8 manipulation), zero failures/errors/skips, plus app ktlint, Detekt, lintDebug and
AndroidTest APK assembly:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceTemplateEditorControllerTest' --tests '*SequenceManipulationTest' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

All original session/controller/presentation test identities remain. The same 35
Sequence presentation identities and pinned instrumentation inventory are unchanged.
