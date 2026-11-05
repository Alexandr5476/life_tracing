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

## Accessible structural manipulation (S2)

S2 adds a native alternative for the existing Step and Repeat manipulation commands.
Each lazy row has a separate Material Manipulate/Select button. Its description
includes the localized node kind, name, sibling position and parent Repeat position;
equal names and Repeat counts remain distinct. Selected and Existing/New draft state
are native semantics. Passive Step content retains pointer tap/long press without
advertising a no-op parent OnClick or merging the row's independent native actions.

The manipulation toolbar exposes mode, current selection, exact session modified/
unchanged state, Move, eligible Duplicate-and-Move, Undo, Redo, Apply and session
Discard. A missing selection is described as unavailable and cannot authorize an
operation. Existing/new, pending source, pending duplicate and Repeat admission uses
DraftIdentity and original snapshot ownership, never a displayed label or ordinal.
Discard restores the session-entry draft and raw-input map, including pre-entry
edits; it does not discard the editor. Only existing Apply can persist changes.

SequenceManipulationDestinations builds the final insertion slots in one traversal
of the current bounded draft. Move filters the source from its sibling container
before enumerating positions; Duplicate keeps it. Steps receive every top-level
and Repeat-child slot, including empty, first, between and last slots. Repeats receive
only top-level slots. A native AlertDialog presents the choices with a LazyColumn;
labels describe container, final insertion position and both neighboring identities'
localized contexts. Native focus/Tab can reach offscreen slots. Callbacks carry the
actual container identity and structural position without parsing their labels.

The controller issues one single-use offer at a time. It retains identity/position/
node-kind topology, not an Activity configuration graph. Choosing checks that exact
offer, selected source, current topology and offered choice under the existing command
lock, then invokes moveManipulation or duplicateManipulation. Structural operations,
structural Undo/Redo, changed selection, dismissal, Apply admission, Discard, canonical
reload and close retire the offer. Structure changes followed by Undo cannot revive
it. Text/configuration edits with the same topology do not invalidate insertion slots.
UI leases additionally retire native callbacks on modal disposal, hiding, route disposal,
generation or mode change. Dialogs are keyed by their offer, preserving S1C1's protection
against Material rebinding a retained native callback to a later operation.

The shared session now rejects a foreign/replaced existing snapshot as a duplicate
source. Its history recorder also ignores selection-only snapshots: a no-op move
selecting another row creates no Undo entry and preserves Redo. Both strengthen the
required admission/history boundary without introducing a structural operation or
durable identity rule. The S1C1 explicit-null Short Comment delta remains unchanged.
The existing repository still allocates independent duplicate node, snapshot, Field
and option identities only at Apply and copies frozen configuration/provenance.

Keyboard landing in manipulation uses Move; canonical-read recovery uses Retry.
Dismissal restores an attached origin or a surviving relevant control without taking
focus from an existing foreground owner. Logical row membership uses a shared current
identity map: removal can recover focus while lazy detachment of a surviving Step
does not reveal it again. Selection, Undo/Redo, Apply and Discard reuse the established
mode/generation and retained-session machinery. Pointer handles and their test tags
remain; a handle reads the current draft when dragging after a native operation so
its same-container adjustment cannot retain the old source container. Row action groups
wrap at larger scales and share existing control rows at baseline size, keeping
Sequence spacing and Repeat drop interiors compact for pointer users. Existing drag
destination semantics, cancellation and edge-scroll policy are unchanged. No motion
or custom interactive surface was introduced. EN/RU resources cover all added prose.

### Evidence and changed boundaries

Changed production files: SequenceTemplateEditorScreen.kt,
SequenceTemplateEditorController.kt, SequenceManipulation.kt, the new
SequenceManipulationAccessibility.kt and SequenceManipulationDestinations.kt, and
values/strings.xml plus values-ru/strings.xml. The accepted action lease, route owner,
MainActivity production route, domain models and TemplateAuthoringRepository writer
are reused. No database schema, migration, durable command or historical/runtime
mutation code changes.

Jupiter evidence in the new SequenceManipulationDestinationsTest enumerates every
source/operation/slot combination against SequenceManipulationSession and checks
exact hierarchy, all sibling positions, neighbors and Undo/Redo. It covers all six
movement shapes, same-container Move/Duplicate differences and empty Repeat slots.
Rejected invalid positions, removed containers, nested Repeat, missing/foreign/stale
sources and pending FromTemplate copies leave history untouched. Controller additions
cover missing selection admission, forged/dismissed/stale/old-session offers,
selection changes, canonical removal/reload, frozen duplicate eligibility, duplicate
selection through Undo/Redo, pre-entry edits, valid/invalid raw input and null comments.
All original history, performance, failure/retry and committed-read recovery cases
remain. SequenceManipulationTest adds the selection-only no-op/Redo regression.

Seven additional SequenceTemplateEditorScreenPresentationTest identities use native
semantics/keyboard input for entry from Step/Repeat, contextual selection, all movement
shapes, Duplicate, Undo/Redo, session Discard, retired destination callbacks, lazy
offscreen Tab traversal, mixed native/pointer movement and EN/RU combined scaling
(system font 200%, app 90%/90% and 120%/130%, minimum target sizes). The original
committed-reload regression now enters, moves, Applies and retries through native
keyboard actions and checks recovery focus, preserving its one-writer/delivery and
retained-callback assertions. Original pointer/cancellation/edge-scroll identities
remain pinned and unchanged.

SequenceManipulationAccessibilityPersistenceTest adds a native real-repository journey
with two equal-count Repeats, top/child Steps, frozen Number/Category schema, source
linkage/revision, StatisticsSeries, locallyModified and explicit overrides, followed
by different mutable source defaults. Move and Duplicate remain uncommitted until
one native Apply. It counts one writer and one retained-session Apply delivery, rejects
retired destination/disabled duplicate callbacks without durable changes, closes and
physically reopens the database, and verifies exact order and independent fresh
duplicate ownership. It compares original snapshot rows, unrelated durable tables,
historical Sequence snapshots/Executions, a Plan and active runtime before/after.
LibraryAccessibilityRouteTest adds native manipulation across actual MainActivity
recreation, one Apply/Library refresh, native Back and physical canonical reopen.

The mandatory original-JUnit inventory gains nine app identities: 548 → 557 app,
627 data and two startup cases per API. No original identity is removed or weakened.
Inventory/source comparison matches all 42 Sequence presentation, 20 Library route
and one new native persistence methods, without an unpinned or absent method.

### Verification and limits

Fail-first runs reproduced both controller missing-selection admission and the
session no-op-selection history bug before their production fixes. The latter
failed its canUndo assertion; it also requires Redo to survive. After correction the final
focused verification passed all 47 Jupiter cases (31 controller, nine manipulation,
four destination and three route-owner cases), app ktlint, Detekt, lintDebug and
AndroidTest APK assembly:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceManipulation*' --tests '*SequenceTemplateEditor*' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain --no-parallel --max-workers=2
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The host runner suite passed 69 mocked tests; its printed device commands are
fixtures, not emulator executions. Earlier local iterations caught and corrected
test fixture/cast errors, formatting/length findings and Compose resource-access
lint. AndroidTest compilation/assembly proves source/APK compatibility only. None
of the new or extended Android testcases has run on a device in this turn.

Destination enumeration and context indexing take O(N + slots) time/storage for the
bounded current draft. Context/liveness lookup during row traversal is O(1); no
per-row Sequence scan or repository read is introduced. Ordinary scalar text edits
reuse node references; offer invalidation compares structure when relevant nodes
change. Admission/choice and Undo/Redo checks scan topology per command.
Lazy rows and destinations stay lazy, manipulation history stays delta-based, and
no destination/focus state is persisted or retained per history operation.

Controller-owned unfiltered checks, coverage and managed-device gates, plus independent
GitHub device CI, remain mandatory and unperformed here. Manual TalkBack, Switch
Access and physical hardware-keyboard service certification remain unverified.


## S2 canonical API 35 repair (2026-11-05)

Controller execution `S2-0741c5a9875346ebbfc444631623ef5d`, attempt 1,
reached 558 app testcases and reported two failures. These are repository test/
layout failures, not an infrastructure-only result. The archived XML was inspected;
the original canonical stability obligation remains OPEN until a fresh device run.

The native physical-persistence fixture configured its reusable source as Stopwatch
while every Step explicitly overrode Timer zero behavior to OVERTIME. The established
writer correctly rejected that invalid combination during initial Sequence creation,
before any native manipulation ran. The fixture now seeds a five-minute Timer and
explicitly checks its frozen mode, target and Step overrides before the journey.
All override, frozen source/provenance, independent duplicate ownership, physical
reopen, one writer/delivery and historical/runtime preservation assertions remain.
No validator, persistence rule or production execution behavior was relaxed.

The destination dialog placed the complete operation and structural source context
in its fixed title slot. Long Russian text at the combined maximum scale could leave
the choices without usable space. The shared Move/Duplicate dialog now uses a short
localized heading and puts the full untruncated context in the scrolling lazy body.
The fixed heading also exposes that full context as its native description. Keyboard
admission scrolls to the first actual choice before requesting its focus, so a long
context item cannot leave the FocusRequester unattached or keep the choice offscreen.
Destination identities, insertion semantics and single-use authority are unchanged.

Both EN/RU scaling regressions now exercise Repeat Move and eligible child-Step
Duplicate at both 90%/90% and 120%/130%, with system font 200%. They retain the
native Button role, first/last destination reachability, minimum target-size and
Cancel checks, and additionally require a usable lazy viewport and contextual heading.
The first choice is explicitly revealed by its native lazy-list key after the scrolling
context item. Failure diagnostics record locale, scales and destination bounds.
All original JUnit method identities and mandatory inventory entries remain intact.

The exact authorized `D:\controller\.runtime\agent-tools\repair-probe.cmd` was
attempted after correcting the invalid fixture and adding layout diagnostics. It was
refused before Gradle/device/test startup: `No active repair-probe lease.` The response
also said the workflow was not an authorized focused-device repair session. No raw
device task, emulator, Controller-state edit or workaround was attempted. The lease
refusal does not reclassify or close the supplied two canonical testcase failures;
neither repaired native case has been rerun in this continuation.

Local verification passed all 47 focused Jupiter cases (zero failures/errors/skips),
app ktlint, Detekt, lintDebug and AndroidTest APK assembly. After strengthening the
shared scaling regression, the final static/lint/APK command also passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceManipulation*' --tests '*SequenceTemplateEditor*' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain --no-parallel --max-workers=2
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain --no-parallel --max-workers=2
git diff --check
```

These checks prove local JVM and Android source/APK compatibility, not runtime
red/green or CI parity. Controller must renew the native repair verification and
then its unfiltered managed-device and independent GitHub gates. Manual TalkBack,
Switch Access and physical hardware-keyboard certification remain unverified.

## S2 canonical journey refresh repair (2026-11-05)

The second supplied API 35 execution (`S2-a6d5fbc3df004843a01f1ae441618fde`,
attempt-1 artifacts, tree `caab833a10b72700cf9429c20ef9d2a5d8b025ec27da33df1b17c2285e6745d6`)
passed both previously failing S2 cases: frozen duplicate persistence and Russian
combined scaling. Its app XML records 558 cases and one failure; data records 632
cases and no failures. The remaining failure is the original
`PrimaryExecutionJourneyTest.launcherSequencePausedWaitingBackReentryAndNormalCompletionKeepOccurrencesAfterReopen`:
Expand disappeared between its reachability assertion and native OnClick lookup
after returning from the paused expanded route.

Daily's date header and focus remain available while its async refresh replaces
the active card with Loading. The fixture's header/focus wait therefore did not
establish active-card readiness after Back. Expand now waits for the existing
runtime-ready predicate. The fixture's shared native-action helper additionally
reacquires a removed control within the existing bounded wait. It retries only
before OnClick starts, and only if the initially matched semantics identity has
disappeared. A still-present control's assertion failure, ambiguous matches, rejected
OnClick or any exception after dispatch still fails immediately. No dispatched
command is replayed. The adjacent Sequence overflow, Plan and History action
helpers use this same guard because they had the same wait/scroll/click gap.
Production runtime, refresh, navigation, controller and persistence code is unchanged.
The original journey's focus, frozen identities, occurrence hierarchy, physical
reopen and execution-count assertions remain intact.

The new native regression
`nativeActionRefreshBeforeDispatchReacquiresWithoutReplayingDispatchedFailure`
deterministically removes a native Button between reachability and action lookup,
checks that no callback ran, restores a replacement and clicks it once. It also
requires a still-present acquisition failure to propagate and a dispatched callback
that removes itself and throws to propagate without replay. Synchronization uses
Compose's UI/idle boundaries, with no sleeps. The mandatory inventory retains all
original identities and adds this one case (558 app identities; 16 Primary journeys).

Local verification passed app ktlint and Detekt plus AndroidTest APK assembly.
The Gradle AndroidTest ktlint source-set tasks report NO-SOURCE in this repository,
so the changed journey source was also checked/formatted in scratch with the
resolved ktlint CLI and repository editor configuration. After that final change,
APK assembly and all 39 focused JVM tests passed (21 DailyController and 18
ExpandedLiveSequenceController; no failures, errors or skips). The 69 mocked host
instrumentation-runner tests, source/inventory comparison and `git diff --check`
also passed. Commands:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain --no-parallel --max-workers=2
.\gradlew.bat :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*Daily*' --tests '*ExpandedLiveSequence*' --console=plain --no-parallel --max-workers=2
python -m unittest discover -s .github/scripts -p test_run_instrumentation.py
```

Both the exact authorized repair probe and the justified PrimaryExecutionJourneyTest
class scope were refused before Gradle/device/test startup with
`No active repair-probe lease.` No direct device task or workaround was attempted.
The native regression and repaired original journey have compiled but have not run
in this continuation. The supplied native failure's stability obligation remains
open for Controller rerun; local source/APK and JVM results do not establish native
red/green, CI parity, or manual accessibility-service certification.

## S3 — Add Step and local Step configuration

The existing authoring models, append commands, semantic Save/Apply/Done boundary,
repository transactions and snapshot/provenance rules remain unchanged. No schema,
migration, propagation mode or durable status was added.

Add Step uses a native, lazy dialog with a single-use insertion offer bound to the
exact Repeat identity (or top-level append), editor generation and manipulation
mode. Selection keeps FromTemplate and its exact Template ID, or creates a fresh
local draft; it never materializes a snapshot. Cancellation, dismissal, disposal,
hiding, obsolete generations and missing Repeat targets revoke selection and
pagination. Reads remain first-page/continuation bounded; editor opening never
reads the picker catalog. Native loading, empty, failure/retry, destination and
choice metadata are localized. Dialog focus starts on Cancel in keyboard mode;
return focus belongs to the surviving host and never replaces a valid owner.

Step disclosure has a synchronous presentation epoch and stable identity-based
expansion state. Native disclosure/content nodes are keyed to their leases so
Material cannot rebind a retained semantic callback to a later disclosure. Lazy
detachment remains separate from logical removal in focus bookkeeping. Current
Step configuration resolves physical ownership across containers, with no captured
parent or Field/option mutation indices. Field and option leases additionally
check identity, type, membership and source provenance. Number parsing reads current
precision. Name/label overrides, type/unit replacement, Missing/zero, defaults and
Main Value ownership retain their domain rules. Clearing a nonselected Main Value
Field leaves another Field's selection intact.

Tracking, Timer, settings, defaults and override choices expose native selected,
checked and error states, contextual focus targets and raw validation text. Timer
and override controls have mode-specific lifetimes. Pending FromTemplate exposes
its existing commit guidance; pending Duplicate stays noneditable. Null Boolean
and countdown inheritance remain distinct from explicit false and zero. Overrides
do not change Activity configuration or locallyModified.

A native tracking-mode or countdown-inheritance action groups its raw-input cleanup
and configuration edit into one existing manipulation delta. Nested raw-input
admission restores the outer recording flag. Undo/Redo restore both values together;
Discard retains the exact session-entry draft and raw-input baseline. This changes
no writer or persistent command. The added Jupiter undo regression first failed
with two operations instead of one. A Main Value regression first failed because
clearing an unselected Field erased the selected Field.

Evidence added: focused Jupiter action/lifetime/identity/default/history regressions;
native picker, pagination, loading/failure, same-name targeting, movement, disclosure,
focus, raw errors and EN/RU combined-scaling regressions; and a production MainActivity
journey using a disposable physical database. The latter creates both Step kinds at
both levels, edits local Number/Category/Text configuration and explicit overrides,
recreates the Activity with an open picker, saves, checks an existing override-only
commit, then replaces linked configuration while terminal history, a Plan and active
runtime exist. Physical reopen compares canonical authoring and durable ownership.
The original-JUnit inventory is extended without removing existing identities:
567 app, 627 data and 2 startup identities. Eight new native presentation methods
and one production physical-reopen method are mandatory. Configuration provenance
is exposed on the native Step heading without increasing collapsed row height.

Native device execution, full unfiltered gates, GitHub CI and manual TalkBack,
Switch Access and physical-keyboard certification remain Controller-owned/unverified.
Local JVM/static/APK results are recorded in the completion report; APK compilation
is not native presentation or persistence runtime PASS.

S3 final local verification passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceStepActionsTest' --tests '*SequenceTemplateEditor*' --tests '*SequenceManipulation*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain --no-daemon --max-workers=2
```

The final run completed in 3m 53s: 59 JVM cases, zero failures/errors/skips
(12 Step actions, 31 Sequence controller, 3 route-owner, 9 manipulation and
4 destination cases). App ktlint, Detekt, lintDebug and AndroidTest APK assembly
passed. The host inventory-acceptance regression passed, and comparison with HEAD
found no lost mandatory identities. `git diff --check` passed. The native cases,
physical-reopen journey and combined scaling compiled into the test APK but were
not executed. No managed-device, full-suite, coverage, CI or manual certification
PASS is inferred. All requested edits remain uncommitted for Controller verification.

## S3 canonical API 35 repair — explicit zero and compact Repeat insertion

The Controller run S3-238423581dc947e39620e36faf3dfa18 reached real tests and
reported four app failures. Two expose the same null/zero presentation defect:
the inherited Step countdown was rendered as `0`, so native replacement with the
same text did not produce a Compose value-change callback. The draft therefore
retained inherited null, including after physical commit. Two original Sequence
presentation tests could no longer discover the compact Repeat insertion label
because S3 had rendered the full structural context as visible button text.

Decision recorded before the repair: an unentered inherited Step countdown has
blank input and retains the existing Inherit indicator. Native entry of `0` is
then a real edit that stores explicit Duration.ZERO. Choosing Inherit clears raw
input and restores blank presentation; focus, inspection and untouched blank
presentation never write. Invalid entered blank/text still follows the existing
raw-input validation boundary. Other duration controls retain their existing
numeric defaults. This corrects presentation of the frozen null/zero distinction;
it introduces no new override state, command or persistence rule.

Repeat insertion restores the compact visible Add step to Repeat ×N label. Its
localized accessibility description carries the current Repeat position/context,
while insertion continues to use the exact draft Repeat identity. Original compact
label and laziness regressions are preserved. S3 native/physical fixtures select
contextual Repeat controls by their accessibility description. Native countdown
assertions now check unentered and restored inheritance as well as explicit zero;
the physical journey checks zero both before submission and after the override-only
canonical reload. The supplied four native failures remain open until the repair
probe reruns the corrected candidate.

Repair verification: the same focused JVM/static/lint/AndroidTest APK command above
passed in 3m 12s, with 59 JVM cases and zero failures/errors/skips. All three affected
native classes retain their mandatory method inventories; no original testcase or
assertion was removed. `git diff --check` passed.

The authorized repair probe was invoked with a justified class scope: all eight
SequenceStepAccessibilityTest cases, the failing production physical-reopen journey,
and both original failing Repeat-label cases. It was refused before Gradle/device/
test startup with `No active repair-probe lease. This workflow state is not an
authorized focused-device repair session.` No raw device command or lease workaround
was attempted. The four supplied native failures remain OPEN pending Controller
runtime verification. Local compilation/JVM success is not a repaired native PASS,
unfiltered stability PASS, CI parity or manual accessibility certification.


## S4 — Accessible committed Step source actions

The expanded committed Step retains its existing three repository-backed commands.
Native source buttons now use the shared editor focus targets, contextual EN/RU
operation descriptions and real enabled state. Descriptions identify the current
Step by physical placement (including Repeat ordinal), linkage, current source
availability/revision, frozen source revision and admission/recovery reason.
Archived status retains its known revision instead of collapsing into missing.
A projection with a foreign source ID or revision below the existing domain
`ActivityTemplateRevisionPolicy.INITIAL_REVISION` is unavailable for source updates;
Save as New retains its existing eligibility. Writer revision contracts are unchanged.
The established archived/missing/unlinked presentation still offers only Save as
New, with explicit prose explaining why source updates are unavailable. Collapsed
rows, pending Step behavior, S3 countdown null/zero intent and the lazy list remain.

Each source callback retains a scalar Step/snapshot/source/status token and the
existing disclosure/route/load/mode lease. Admission resolves current committed
ownership under the controller command lock. Draft/raw-input changes and admitted
Save/source commands revoke old source tokens synchronously. Native controls are
keyed separately by operation and token, so retained Material OnClick actions
cannot be rebound to another operation. Disabled presentations cannot later become
enabled callbacks. No presentation inspection allocates identity, edits a draft,
loads a catalog or dispatches a writer.

Writer adapters and transaction/revision rules are unchanged. Update from Source
uses the current source through the existing writer; Update Source preserves the
Step snapshot and does not propagate; Save as New uses the existing independent
identity and relink writer. Progress, failure and successful canonical publication
identify the operation and current Step in a scoped polite live region. Source
revision conflicts, archived/missing sources and ordinary writer failures have
distinct localized feedback; source conflicts never claim that the Sequence itself
changed. Ordinary source failures expose explicit read-only Reload through the
existing reader path, without retrying a writer. Its presentation lease binds to
the particular failure and source-command epoch; an earlier Reload cannot discard
or recover a later attempt. Existing
canonical-read Retry remains separate from source writers and Done. Recovery locks
editing and Back; the retained route session still delivers appliedGeneration once.
Focus uses shared removal/landing policy when a source control retires, without
claiming focus from a surviving editor control.

The loader remains one canonical graph read plus one shared distinct-source
publication. Sequence nodes are queried by one owner ID; snapshot IDs are deduped
and read in chunks of 900. Snapshot Fields/options are joined by snapshot IDs, so
Field/option fan-out does not create an unbounded derived bind list. Distinct source
Template IDs are independently chunked at 900 by the existing source-status reader;
no full Activity aggregates, picker catalog or history scan supplies source labels.
Existing data regressions `sourceStatusProjectionDeduplicatesChunksAndDoesNotHydrateActivityAggregates`
and `canonicalAuthoringReadHydratesSnapshotsByBindChunksRatherThanSteps` pin those
query boundaries. The new JVM fan-out case uses 1,102 Steps / 1,001 distinct sources
and verifies one shared publication and no additional reads or state changes during
300 source-action availability inspections. No new repository writer, schema,
migration, propagation mode or retained duplicate authoring graph is introduced.

New executable evidence includes scalar-token admission, archived/missing/unlinked
availability, pending/local/duplicate/removed rejection, reverted structural/raw
changes, mode/load/source relink/route retirement, all three gated writers, ordinary
failure/source conflict/archive rejection and fresh explicit retry. New Compose
cases cover native source controls, exact same-name Step/Repeat context, retained
OnClick revocation, three committed-read recoveries, Tab/Shift+Tab and EN/RU combined
scale extremes with 200% system font scale and native target bounds.

Production MainActivity journeys exercise all three commands, Activity recreation
and exactly-once Library delivery. The physical-reopen journey seeds two linked
same-name Steps, Number/Category/Text Fields/options, Main Value, missing/zero
defaults, explicit false/zero overrides, a historical Sequence Execution/snapshot,
a Plan, an unrelated same-name ActivityTemplate and an active runtime. It checks
local semantic commit, Update Source snapshot preservation, full source refresh,
independent Save as New identities/provenance, non-target preservation and frozen
facts after closing the Activity, runtime graph and Room database and reopening the
same file. Three additional production recovery cases gate actual repository
writers, fail the subsequent canonical read, recreate the Activity during recovery,
retry only the read and recreate again after one applied-generation delivery. A real
archive-between-read-and-dispatch journey rejects both update directions atomically
and then saves as a new independent source. All new Android test identities are
pinned in the mandatory instrumentation inventory; original identities/assertions
remain intact.

### S4 local verification results

Both fail-first probes reproduced the intended assertion failure before production
fixes: archived source revision loss and invalid/foreign source-status admission.
The final focused app JVM run passed **66 tests**, zero failures/errors/skips:
33 Sequence controller, 5 source-action, 12 Step-action, 9 manipulation, 4 destination
and 3 retained Sequence route-session cases. All original JUnit identities remain.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SequenceTemplateEditorControllerTest' --tests '*SequenceStep*Test' --tests '*SequenceManipulation*Test' --tests '*SequenceTemplateEditorRouteSessionOwnerTest' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
.\gradlew.bat :app:ktlintCheck --console=plain
python -B -m unittest discover -s .github/scripts -p test_run_instrumentation.py
git diff --check
```

The last combined run passed all 66 JVM cases, app Detekt/lint and both AndroidTest
APK tasks; its sole failure was ktlint multiline-expression wrapping on the new
Reload lease. That whitespace-only correction passed the subsequent ktlint command
(exit zero, 58 seconds). Earlier complete combined runs also passed. No test was
removed, disabled or weakened to resolve a check.

The host instrumentation runner passed **69 mocked tests**; its simulated device
commands are not device executions. Inventory/source checks preserve the original
methods and add **13 app identities** (8 source-accessibility and 5 production-route
journeys), yielding **580 app / 627 data / 2 startup** mandatory cases per API. All
13 added methods are present in the final AndroidTest APK DEX files. The 1,102-Step /
1,001-source JVM case passed, including one shared source-reader call and no extra
reader calls/state mutation across 300 availability inspections. SQL chunk/join
bounds were inspected in the existing production readers; no new SQL timing or
physical-query execution result is claimed.

The physical-persistence journeys and actual historical-value preservation
assertions compiled into the APK but were not executed locally. The physical fixture
stores a historical Number result of 7 against a zero default and checks that source
creation retains the zero default, historical actuals, Field/option provenance and
frozen History detail after reopening. Controller must execute those assertions.
Device execution (including the physical reopen assertions), full / gmd-full
Controller gates and independent GitHub managed-device CI remain required.
AndroidTest APK compilation alone is not device execution. Manual TalkBack,
Switch Access and physical hardware-keyboard certification are not claimed.
S4 does not complete the Parent: its full repository-closure audit remains next.


### S4 canonical API 35 repair, attempt 1

Controller run `S4-ab54b19f950244eeae8ed3f75a12b741/attempt-1` reached the actual
app/data instrumentation suites and reported two app assertion failures. This is
not an infrastructure-only result. The physical source journey reached Update
Source and Activity recreation, then searched for Edit Step even though production
uses `rememberSaveable(controller)` for expanded Step identities. The fixture now
asserts the surviving native Close Step disclosure without activating it, then
continues Update from Source and Save as New. Its identity, provenance, exact-once,
historical/runtime and physical-reopen assertions remain unchanged.

The source scaling case measured `boundsInRoot` against the 48 dp native target
minimum. Production uses Material Button plus LifeTracingAccessibleTargets, which
reserves touch space beyond the compact visual surface. The test now uses
`touchBoundsInRoot` for the unchanged physical minimum, matching existing Sequence
editor scaling regressions, while retaining visual viewport-boundary assertions.
Bounds failures now report language, interface/text scale, operation, visual/touch
rectangles and physical minimum. Sibling inspection found no other new source-target
minimum using visual bounds and no other source-journey recreation followed by an
incorrect Edit Step activation. Original JUnit identities/inventory entries remain.

The exact authorized repair-probe command was attempted and refused before device
or testcase startup: “No active repair-probe lease. This workflow state is not an
authorized focused-device repair session.” No raw managed-device command, emulator
launch or Controller-state workaround was attempted. The two original failures
remain pending Controller runtime verification; local APK compilation is not a
runtime PASS.

Repair local verification passed (exit zero, 2m 22s):

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:lintDebug --continue --console=plain
git diff --check
```

Both failing JUnit method identities remain pinned in the unchanged mandatory
inventory. The repaired test sources and APK compiled successfully; the canonical
two-case runtime rerun, full Controller gates and independent GitHub managed-device
CI remain outstanding. This repair changes test fixture expectations and diagnostics
only; no source writer, persistence or production disclosure/target policy changed.
