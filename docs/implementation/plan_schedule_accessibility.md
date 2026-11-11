# Plan scheduling accessibility and form ownership (S3)

## CI focus ordering repair (2026-11-11, run 43080921916)

The API35 scaling failure's complete native artifact shows `plan-add` focused
while the fixture awaits `plan-next-week`, whose bounds have moved above the
viewport. After closing the creation form, the fixture immediately seeded a Plan
and requested week navigation without observing the production return to Add.
The calendar's deferred dialog-return effect can therefore supersede that request
and scroll back to Add. The fixture now awaits the existing Add landing first.

Scheduling, lifecycle and catalog focus helpers now admit the native window and
keyboard mode before scrolling/requesting a control, and assert RequestFocus's
result. Previously admission happened inside awaitFocus, after the request.
The adjacent catalog dismiss/reopen journeys also await the existing Add landing.
No target is repeatedly refocused to conceal a failure, and all original native
keyboard, visibility, localization, scaling and persistence assertions remain.

See [the lifecycle repair](plan_lifecycle_accessibility.md) for the API33 failure
from this execution. These are fixture ordering corrections based on retained
focus evidence and the production effects, not proof of repaired runtime behavior.
The authorized repair probe returned `No active repair-probe lease` before device
admission. Both original remote failures and the CI/local stability obligation
remain open for fresh Controller verification; no local device or CI PASS is claimed.

Host verification for this repair passed (Gradle exit 0):
`gradlew.bat :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt --continue --console=plain`.
All changed AndroidTest sources compiled and packaged. `git diff --check` passed.
No production/JVM behavior or instrumentation testcase identity changed.

This implements the production catalog -> scheduling -> Save and mutable Plan ->
Reschedule -> scheduling -> Save paths under product v0.16 sections 15/35/37–40,
domain v0.10 section 14, database v0.6 and the strict ExactDay/form-ownership
clarifications. No writer, transaction, schema, target precision, snapshot policy
or durable identifier changes. S1 calendar, S2 catalog and focused execution stay
on their existing controller and native action boundaries.

## Admission and preservation

Every scheduling action carries the originating controller form UUID. Kind/date/
time edits and Close require that same currently visible form; edits intentionally
do not require the rendered input revision, so consecutive keyboard callbacks
before recomposition retain ordinary input responsiveness. Save and explicit zone
review additionally require the rendered input revision. Offset events retain their
existing form/revision/local tuple/zone checks. No absent-owner fallback exists in
production. Test-only ownership properties use an unrelated UUID for deliberate
no-form events; they grant no authority. Replaced/dismissed/hidden forms cannot
edit, authorize, dismiss or submit another form. Hidden controllers also reject
opening Reschedule. Each new form is keyed by its fresh UUID in Compose.

Rescheduling captures frozen title/kind solely for presentation. The immutable
original PlanActionIdentity remains command authority. Untouched forms dispatch
PreserveSchedule, including omitted seconds/milliseconds, legacy null creation
zone and device-zone travel. No-op kind selection/focus/scroll does not edit.
Actual kind/date/time edits permanently revoke preservation, even after restoring
text. Edited proposals still pass PlanScheduleInput and public writer validation;
equivalence, optimistic identity, engagement and lifecycle remain writer decisions.

New and edited ExactDay forms retain literal date/time strings and the captured
zone. Gaps, malformed dates/times, invalid Week anchors, ambiguity, invalid offsets
and submillisecond precision remain blocking typed errors. Overlaps have no default:
first/second choices follow Instant order and expose actual UTC offsets and native
selection. Input/zone changes clear offset authority. Zone changes require explicit
review; untouched existing targets do not require review or new occurrence choice.

## Native presentation and focus

PlanScheduleDialog uses shared Material controls, a wrapping selectable radio group
for Day/Exact day/Week, native editable fields, inline radio occurrence choices and
native Save/Close. There are no custom activation actions or Month controls.
Context identifies creation/rescheduling, exact typed source or frozen title/kind,
original target, precision, untouched/edited state and applicable zone. Original
ExactDay context shows the stored Instant rather than reconstructing a proposal.
English/Russian resources and app context/configuration/density are reapplied in
each native dialog slot, including shared minimum touch targets.

The bounded body scrolls; native Tab/Shift+Tab traverse instead of inserting tab
characters in date/time. Opening lands on Date after the dialog owns window focus.
Surviving control instances retain focus through selection and validation. A removed
focused occurrence/review control returns to Date; admitted mutation moves keyboard
focus to the enabled Close control. Fields/kinds/occurrences/Save are disabled during
mutation. Invalid fields expose native error semantics and localized persistent
feedback; isolated polite live regions announce changed errors and Saving without
recomposition counters or temporal polling.

Close/native dismissal is scoped to the originating form. Catalog-created forms
return to surviving Add; rescheduling returns to the selected day, canonical Retry
or Today safe control. Restoration waits for the Plan window and is suppressed by
another dialog or a hidden route. S1 removed-row recovery and S2 catalog focus remain.
This is native keyboard focus ownership, not virtual screen-reader focus restoration.
Dismissal during an admitted Save removes only the form: the accepted writer remains
single-shot. Canonical recovery and explicit Retry never replay Save. Route exit
clears forms and invalidates publication through the existing owner lifecycle.

## Executable coverage

The stale-edit regression failed before the ownership fix. Added Jupiter cases cover
all creation/reschedule replacement directions (including duplicate visible input),
dismiss/reopen, old edits/Save/Close/review/offset, hidden/reentered routes, revision-
scoped Save/review, rapid raw text edits, every untouched target type, irreversible
edit state, and pending Save + dismissal + failed canonical read + Retry without
writer replay. Existing strict temporal, stale/engaged, failure, execution/navigation,
repository and migration regressions remain mandatory.

Seven original JUnit4 PlanScheduleAccessibilityTest identities are registered in the
instrumentation inventory. They use the production PlanScreen/PlanController and
executePlanMutation with real repositories in a unique file-backed database. Native
catalog selection and Plan-row Reschedule enter the form; keyboard/native Save
connects to physical close/reopen and canonical focused reads for FloatingDay Activity,
Week Sequence, exact milliseconds, both overlap Instants, untouched precision/time-
stamp preservation after zone travel, changed reschedule preserving the same Plan
and frozen snapshot (including Statistics provenance), and pending-save dismissal.
Invalid gap/fraction drafts compare complete fixture facts before any write.

Presentation cases check merged/unmerged native RadioButton/Button selection and
OnClick with no custom actions, raw editable values/error semantics, actual Tab and
Shift+Tab, EN/RU occurrence/context labels, focus-only write freedom, Close/Back,
explicit zone review, hidden-route clearing and obsolete callbacks. Scale coverage:
interface/text 90/90 + system font 1.0; 120/130 + 2.0; 90/130 + 2.0, in both languages.
Every essential enabled body control is scrolled/displayed/focused and its physical
48dp touch bounds checked. Save/Close stay in the native button area.

## Verification boundary

Local JVM/static/APK results are recorded in the completion report. AndroidTest
compilation and APK packaging do not execute these native assertions. No emulator
or managed-device task is launched in this implementation turn. Controller owns
ci-fast, gmd-app, exact-head CI and complete compatibility/startup execution.
Retained PlanExactDayPersistenceTest covers both source kinds, strict errors,
equivalent write-free proposals, stale/engaged rejection and physical reopen.
MainActivity route-session and focused execution cases remain mandatory.

Manual TalkBack and Switch Access certification has not been performed. A manual
walkthrough should repeat both entry paths in EN/RU at the above scales: traverse
kinds/fields/actions, correct malformed/gap/fraction input, select each explicit DST
occurrence, review a zone change, dismiss/reopen, and return from focused execution.
Record service/device settings and observed results separately from automated tests.

## Host verification on 2026-11-11

The final direct CMD process exited **0**, with Gradle `BUILD SUCCESSFUL`:

```powershell
cmd.exe /d /s /c "gradlew.bat :app:testDebugUnitTest --tests com.alexandr5476.lifetracing.plan.* :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt :app:lintDebug --continue --console=plain"
```

The focused JVM run executed 70 cases with zero failures, errors or skips; the
final combined invocation restored that same valid result from cache. Counts:
PlanControllerTest 21, PlanExactDayControllerTest 17, PlanScheduleOwnershipTest 6,
PlanExecutionControllerTest 15, PlanExecutionRouteSessionTest 11. The six ownership
cases share an explicit test-only controller fixture; they do not expose production
private logic. The dismissed-edit regression was observed failing before the fix.

App ktlint, Detekt and lintDebug passed. All AndroidTest sources compiled and the
test APK packaged, including the seven new registered cases. The tests deliberately
invoke standard accessibility OnClick for kind/occurrence/review/Save/Close as well
as native Enter/Back/Tab/Shift+Tab; this is compiled automation, not device execution.
Retained temporal presentation cases now scroll fields and feedback into view before
editing/asserting. Their original assertions, strict errors and mutation counts remain.

Inventory validation preserved every previous identity and matched all seven new
original JUnit methods. EN/RU resource XML and `git diff --check` passed. Existing
data PlanExactDayCommandTest includes legacy-null creation-zone preservation and
write-free equivalent proposals; its original identities remain mandatory.

No managed-device suite, manual TalkBack or Switch Access check was executed.
Physical persistence/reopen, native keyboard/semantics and locale/scaling assertions
in AndroidTest remain awaiting Controller gmd-app/full compatibility execution.
These host results do not grant ci-fast parity, exact-head CI PASS or merge approval.

## Controller API35 reschedule-fixture repair (2026-11-11)

Controller attempt 1 at head b6c7a8579318b770d92267bcf166076e42dff52b completed
615 cases with four failures, zero errors and zero skips. All four failures were
PlanScheduleAccessibilityTest reschedule entry waits at openReschedule. The
creation, explicit overlap/gap and pending-Save cases passed. This remains a
failed device run, not an infrastructure retry or a fresh PASS.

The explicit fixture clock selects Saturday 2026-10-24 in the October 19 week,
while seed() creates an ExactDay target on Monday 2026-10-26. SelectDate only
admits dates in the displayed week. The old helper sent that out-of-week action
and then waited for a canonical row that could not be selected; increasing the
timeout would not establish the missing navigation prerequisite.

The shared helper now uses the native Next/Previous week controls to reach the
target week, asserts that week, waits for its canonical calendar, and activates
the exact native day control before waiting for the exact Plan row and activating
its Reschedule action. The existing timeouts, Plan identity, precision/timestamp,
frozen snapshot/provenance, localization, Close/focus and route assertions remain.
The repeated reschedule in the Close case also reselects/reloads its current day
through the native control. No controller admission or writer behavior changed.
All seven original scheduling instrumentation identities remain registered.

Repair host verification passed (Gradle exit 0):

```powershell
cmd.exe /d /s /c "gradlew.bat :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt --continue --console=plain"
```

AndroidTest Kotlin and APK packaging, app ktlint and Detekt passed; inventory
identity comparison and git diff --check passed. No JVM behavior changed, so the
previous 70-case host result is retained rather than claimed as a new execution.

The authorized repair-probe command was attempted with the containing seven-case
class scope, covering all four failures and the previously passing sibling paths.
It returned "No active repair-probe lease" before device/testcase admission. No
raw managed-device command was substituted. The four original failures and all
downstream native/persistence assertions still require Controller runtime
confirmation; these host checks do not establish a repaired device PASS.

## Controller causal visibility repair and diagnostic boundary (2026-11-11)

The subsequent causal API35 execution ran the four previously failing cases.
Changed rescheduling/provenance, untouched precision/timestamp preservation after
zone travel, and Close/route/focus passed. The scaling case failed earlier at the
Russian creation-context assertIsDisplayed after performScrollTo. The original
615-case execution had reached that case's later reschedule prerequisite, so this
new failure does not establish a consistently broken Russian layout. It remains
a repository-owned test assertion failure, not an infrastructure classification.

The retained log records new native dialog view identities and IME show/hide
activity around the failure. The fixture changed locale/density and immediately
scrolled passive text, without re-establishing its native keyboard/window owner.
The report omitted the precise scale tuple, text/body bounds, scroll position and
Compose focus state, so it cannot prove whether focus/IME relocation or layout
caused the failed visibility assertion.

The common passive-text assertion now admits the current dialog's native keyboard
window, establishes Date focus, moves focus to the surviving Close button without
activating it, and uses the existing keyboard fixture to dismiss the IME before
scrolling/reading. The same prerequisite applies to creation context, corrected
validation feedback and reschedule context. Every original localized text lookup,
assertIsDisplayed, scale combination, native control/focus/target check and no-write
assertion remains. No timeout increase, arbitrary sleep, skipped configuration or
production change is introduced.

If visibility still fails, the original assertion remains its cause and the
existing acknowledged nativeFailureReport transport retains the exact locale/scale,
expected text, native window focus/size/mode, text/body bounds, vertical scroll
value/range, Close semantics and bounded form semantics. This addresses the
confirmed missing configuration/geometry/focus evidence without asserting an
unverified production root cause.

Repair host command passed with Gradle exit 0:

```powershell
cmd.exe /d /s /c "gradlew.bat :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt --continue --console=plain"
```

All AndroidTest Kotlin sources compiled, the test APK packaged, app ktlint/Detekt
passed, seven instrumentation identities matched their registrations, and diff
checks passed. No production/JVM behavior changed; previous JVM results are not
claimed as a new execution.

The authorized repair-probe command returned "No active repair-probe lease" before
any device/testcase admission. No raw device task was substituted. Native focus/IME
synchronization is the best current fixture-level assessment, not a confirmed
runtime root cause. The Russian visibility failure and remaining scaling/persistence
assertions require Controller runtime validation; no repaired device/CI PASS or
manual TalkBack/Switch Access certification is claimed.
