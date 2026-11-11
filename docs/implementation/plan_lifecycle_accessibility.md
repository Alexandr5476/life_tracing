# Plan lifecycle management and cancelled-plan restoration (S4)

## CI scaling focus ordering repair (2026-11-11, run 43080921916)

The API33 scaling failure's complete native artifact shows the Cancelled dialog's
Close control focused while the fixture awaits `plan-restore-scale-0`. The native
window is attached, focused and in keyboard mode. The background calendar also
retains local focus on its Cancelled entry; that background state is not native
input ownership. This evidence does not indicate a locked/unfocused emulator.

The fixture changed density/locale and immediately scrolled/requested Restore.
A replacement dialog has its own deferred production landing on Close. Each scale
iteration now admits that window and observes Close before requesting the first
row. The helper also admits native keyboard mode before any explicit focus request,
rather than afterwards. All thirty Restore targets, native Tab traversal, More,
Retry, Close, EN/RU scale combinations, physical target and no-write assertions
remain. Production focus effects and Plan behavior are unchanged.

See [the scheduling repair](plan_schedule_accessibility.md) for the sibling API35
return-focus ordering defect and probe limitation. Runtime confirmation of the
fixture-level assessment and the original remote failures remains Controller-owned.

This presentation boundary follows product v0.16 sections 15/37-40, domain v0.10
section 14, database v0.6 sections 11-12/19-20 and the optimistic-token,
engagement, overdue and temporal-projection clarifications. Existing commands,
writer transactions, canonical readers, statuses, schema and snapshot policies
are unchanged.

## Admission and canonical recovery

PlanController retains read generations and adds ephemeral
PlanCancelledContext(dialogId, collectionId). Close belongs to the continuous
dialog; Restore requires its current collection and the exact published PlanReadRow
object. The writer still receives the existing PlanActionIdentity. Pagination and
Retry also require the original bounded offset and appropriate load state. Initial
reads/canonical replacement revoke collection authority; append preserves loaded
rows and rejects overlapping requests. Dismissal/route exit revoke dialog authority.
Late results and old callbacks cannot own a reopened identical collection.

Cancel/Update require a published mutable active row and visible calendar ownership;
Update additionally requires CHANGED. Hidden UI callbacks cannot open Cancelled or
Cancel/Update/Restore. Another modal blocks Cancelled entry/actions; Catalog and
Reschedule cannot open over Cancelled. Existing catalog/form replacement semantics
and S1/S2/S3 ownership remain. Names, ordinals and focus tokens never select IDs.

Admitted writers remain single-shot. Cancel/Restore still require fresh Week and
Cancelled publication even when the dialog is closed. Internal recovery has no
dialog-context admission gate. Failed reloads retain the mutation lock. Dialog
Retry recovers both surfaces when necessary, including a failed Week with successful
Cancelled read. Route exit invalidates publication; reentry resumes recovery.
Dismissal never replays an admitted writer.

## Native presentation, pagination and focus

PlanCancelledDialog uses native Material dialog/buttons and shared physical minimum
targets. App context, EN/RU configuration and additive interface/text/system density
are reapplied in native slots. The body scrolls; Close remains enabled during reads
and mutation recovery. PlanEntryId keys preserve row nodes; traversal-group Cards
do not merge Restore controls or add an OnClick. Each Restore has one native action.

Rows show frozen title, kind, original target/precision, Cancelled, independent
source state, comment and supplied Activity tracking/timer context. ExactDay uses
the existing absolute Instant fallback, never creation-zone reinterpretation.
Two linear passes supply localized contextual Restore names and duplicate ordinals.
No per-row query, full graph hydration, UI DAO composition, new clock or history scan
was added.

Initial loading/failure, confirmed empty/results, append loading/failure and retry
are distinct. Append retains loaded rows. Retry repeats its failed offset; raw page
lengths advance the cursor while duplicate rendered PlanEntryIds are suppressed.
Canonical recovery replaces the collection rather than stitching a UI-only removal.
Initial replacement clears obsolete rows so failure does not claim they are current.

Keyboard entry lands on Close after native window admission. Tab/Shift+Tab traverse
the scrollable controls. Surviving rows keep focus during append; removed/disabled
focused controls recover to Close without activating Restore. Ordinary dismissal
returns to the surviving Cancelled Plans entry after Plan regains its window.
Hidden routes/other modals suppress return. Active-row removal retains S1 recovery.
Only changed status/failure text is a polite live region, without polling or
recomposition counters.

## Executable evidence and verification boundary

PlanLifecycleControllerTest adds deterministic queued-dispatcher/deferred-gate
regressions for stale Close/Restore/More/Retry, reopened identical rows, other modals,
pending first/append reads, route exit/reentry, exact bounded offsets, duplicate
append admission, failure followed by success, exact published-row membership,
canonical replacement, active-action admission and recovery without writer replay.
The initial stale-Restore case failed before the production correction. Retained
hidden-Cancelled recovery, stale Restore and S1/S2/S3 tests remain mandatory.

Ten original JUnit4 PlanLifecycleAccessibilityTest methods are registered in the
inventory. They cover merged/unmerged native Button/OnClick, localized duplicate
names/exact identity, focus-only write freedom, scroll/keyboard, empty/loading/
failure/retry, bounded append/focus, native dismissal, route ownership, forbidden
actions and two-surface recovery. Full-page long-title scaling covers EN/RU at
interface/text 90/90 + system font 1, 120/130 + 2 and 90/130 + 2. Restore/More/Close
and Retry are scrolled/focused and checked against the physical 48dp policy.

Real file-backed production repositories/controller controls cover equal-title,
equal-target Activities and a Sequence through Cancel, physical close/reopen,
canonical Cancelled read, exact Restore, second physical reopen, original Week
placement and derived overdue. Equality includes ExactDay Instant/creation zone.
Explicit updates of both kinds physically reload target, revision, title/comment,
Series, unchanged child provenance and independent fulfilled history. A real
rejecting SQLite writer checks complete durable fixture equality after reopen
without replay. Two additional data instrumentation identities check nonadvancing
millisecond rejection and gated conflicting Cancel/Update writers. Every prior
repository/provenance, migration, Daily, execution and MainActivity identity remains.

Host verification results are recorded in the completion report. APK compilation
does not execute native keyboard/scaling or physical-reopen assertions. No emulator
or device task is launched here. Controller owns ci-fast, gmd-app, independent
exact-head CI and full compatibility execution. Manual TalkBack/Switch Access
certification remains deferred.

## API35 repair evidence (2026-11-11)

Controller attempt 1 completed 625 app cases with three failures. The retained
XML identifies an ambiguous heading selector, an incorrect frozen-Series-label
expectation and a recovery ordering defect. Heading assertions now require native
Heading semantics as well as localized text. The physical-update fixture explicitly
expects the two authoring-driven Series display-name changes while comparing every
other Series/history fact, both before Update and after physical reopen.

A new synchronous-reader JVM regression reproduces the recovery failure before the
fix. Retry previously allowed Week publication to unlock against an earlier successful
Cancelled surface before scheduling its fresh read. Retry now invalidates both
publication flags before launching either reader and retains the required surface
set for the request. The native case gates the fresh Cancelled retry and requires
the mutation lock to remain held until it publishes, without replaying Restore.
All ten instrumentation testcase identities and existing assertions remain.
Fresh Controller device/CI execution is still required; host checks alone provide
no runtime PASS credit.

Repair host verification: all 81 focused Plan JVM cases pass, including the new
fail-first ordering regression; app ktlint, Detekt and AndroidTest APK assembly pass.
The authorized containing-class repair probe returned "No active repair-probe lease"
before device/testcase admission. The original three device failures still require
fresh Controller revalidation; no repaired runtime/CI PASS is claimed.
