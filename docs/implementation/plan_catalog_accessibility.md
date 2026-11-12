# Add to Plan catalog accessibility and ownership (S2)

This boundary implements the existing product v0.16 section 35 catalog-to-scheduling
journey under the frozen Plan source/snapshot rules, domain v0.10 section 14,
database v0.6, implementation clarifications and shared design controls. Scheduling
form accessibility remains deferred. No business command, source eligibility,
snapshot creation policy, DAO, schema or durable identifier changes.

## Production behavior

`PlanCatalogDialog.kt` replaces the unscrollable catalog in `PlanScreen.kt`.
The existing Add to Plan button opens a localized native dialog and keyboard focus
lands on editable Search after its window accepts focus. Loaded results, contextual
query/status text, Retry and Load more share a vertical scroll surface. Close stays
in the native dialog button area. Shared accessible minimum targets apply inside
the dialog at reduced app interface size too. Text wraps without fixed row heights.

Every source has one native Material Button/OnClick, keyed by its complete typed
`LibraryTemplateId`. Its visible/accessibility name includes the source name and
localized Activity/Sequence kind. Repeated name-and-kind descriptions receive a
localized ordinal within the loaded collection. Two linear passes compute these
presentation names; no repository lookup or source hydration occurs. Underlying
Activity/Sequence ID strings may collide. Names, ordinals and tags never choose a
source. The original published item and current presentation context are dispatched.

`PlanController.kt` retains its read-generation guard and adds independent ephemeral
`PlanCatalogContext(dialogId, collectionId)` admission to SearchCatalog,
LoadMoreCatalog, SelectCatalogItem and DismissCatalog. Close belongs to the
continuously attached dialog; result, pagination and Retry callbacks require their
original collection. Search callbacks capture that collection as their producer
identity and assign increasing ephemeral event sequences. Consecutive native edits
from one callback before recomposition remain responsive. Accepting a callback from
newer query state revokes the older producer; delayed or repeated events cannot
overwrite the newer query, even with a larger sequence. Retry retains the current
query and revokes the previous search producer. A fresh dialog owns a fresh context.
A first-page/search request replaces collection ownership synchronously,
clears old results and pagination, and retains the literal query through loading,
failure and retry. Append requests retain collection ownership and already loaded
items. Selection also requires exact typed published-item membership. Old callbacks
cannot gain authority from a newer collection containing the same source.

Success, failure, dismissal and route visibility remain serialized by the existing
controller lock. Dismissal, selection and dialog replacement invalidate pending
catalog reads. Hidden/closed controllers reject catalog interactions and opening.
Route exit clears transients through the existing PlanControllerOwner lifecycle;
reentry never restores a catalog. Selection atomically removes catalog authority and
opens the existing creation form with the exact source, null existing-Plan identity,
fresh form UUID, FloatingDay, selected Plan date, captured applicable zone and
untouched schedule/input state. Only later existing submission can create a Plan.
Canonical writer validation still owns source changes or unavailability after selection.

The canonical Library reader is unchanged: 30 visible results plus one lookahead,
name/kind/typed-ID keyset cursor, bounded lightweight projection, no graph hydration.
Append deduplicates complete typed source identities while preserving the existing
published items. Initial/search loading, confirmed empty/populated results, first-page
failure, append failure and retry-in-progress have resource-backed status copy.
Append Retry repeats the append cursor rather than replacing the first page.
The single polite status live region changes with meaningful status/text, with no
clock or recomposition counter causing repeated announcements.

`PlanCalendarFocus.kt` adds only the Add entry requester and catalog-return recovery.
Ordinary Close/native dismissal restores that surviving entry after the Plan window
regains focus. Scheduling, another dialog and hidden routes suppress restoration.
The catalog keeps Search and surviving typed result nodes attached through
publication/reordering. Appending does not reset surviving focus. Load more and
Retry remain attached while their request is pending. If the focused result or
completed Retry disappears, Search recovers; a removed final-page Load more recovers
to Close. Focus movement never dispatches selection or persistence. S1 calendar and
active-row behavior are retained.

## Executable evidence

PR41 review corrections B1/W1 add three fail-first `PlanControllerTest` regressions
for unpublished reschedule rows, modal ownership, and delayed search/retry callbacks.
The rapid-input regression also rejects repeated or out-of-order events. Existing
`PlanCatalogPresentationTest.keyboardQueryReplacementKeepsSearchFocusAndRejectsLateReadPublication`
now replays an obsolete production search event and retry after native keyboard
input advances the query. Existing parameterized
`PlanExactDayPersistenceTest.untouchedHighPrecisionTargetSurvivesPreservationZoneTravelDismissalAndReopening`
asserts rejected reschedule callbacks across day/week navigation, canonical
replacement and modal ownership, with no commands or durable-fact changes before
the existing physical-reopen assertions. These original JUnit identities are already
in the mandatory inventory; no identity was added or removed.

All three new JVM regressions failed against the original implementation. Local
verification and AndroidTest APK assembly do not execute the native or Room-backed
device assertions. Controller-owned device suites and exact-head CI remain required;
manual TalkBack/Switch Access walkthroughs remain unverified.

`PlanControllerTest` adds seven deterministic regressions, with explicit deferred gates
for asynchronous work:

- `dismissedCatalogCannotBeReopenedOrSelectedByObsoleteCallbacks`: reproduced a
  failure before the production fix; obsolete Search/selection after dismissal.
- `obsoleteCatalogActionsCannotOwnNewQueryDialogFormOrHiddenRoute`: obsolete result,
  pagination and Retry after query replacement, and all four action types across
  dismissal, reopening, scheduling handoff,
  reschedule-dialog replacement and route exit/reentry, including identical sources
  in the new collection and stale dismissal of a newer dialog.
- `pendingCatalogDismissalRejectsReadsAndInteractionsAfterReentry`: dismissed pending
  first page cannot publish or create a form after lifecycle changes.
- `boundedTypedCatalogPagesRetryWithoutLosingSourcesOrSelecting`: four exact
  31-item requests, typed Activity/Sequence collisions, exact append cursor,
  first/append failure, preserved raw query, deduplication and no mutation.
- `duplicateNamesAndCollidingIdValuesHandoffExactTypedSourceToFreshUntouchedForms`:
  both kinds with identical names/ID values, exact `form.source.id`, selected date,
  FloatingDay, null identity, UTC capture, untouched input, fresh UUID and no mutation;
  an unpublished source and obsolete second activation are rejected.
- `currentLoadedSourceRemainsSelectableDuringAppendAndAppendCannotReopenFormHandoff`:
  current loaded source remains selectable during a gated append; late append cannot
  resurrect the catalog or replace the form.

- `survivingSearchAndCloseCallbacksKeepDialogOwnershipWhileResultsRequireCurrentCollection`:
  reproduced dropped rapid input before the correction; two edits dispatched before
  recomposition retain the latest query, reject old results and allow the surviving
  Close callback. These controls retain dialog ownership until actual dismissal.

The prior delayed-query and delayed-append/reset/route-exit tests remain. Existing
ExactDay fixtures now explicitly open a catalog and await their published source
before selection; none bypass the admission boundary. Their temporal assertions
and submission behavior remain unchanged.

Seven new original JUnit4 `PlanCatalogPresentationTest` identities are registered in
`.github/scripts/instrumentation-inventory.json`:

- Native Tab entry and Enter, Search focus, merged/unmerged Button/OnClick,
  localized EN/RU kind/ordinal names, identical underlying Activity/Sequence ID
  strings, source traversal without selection, exact Activity/Sequence form handoff.
- Native keyboard query replacement with independently gated old/new reads,
  retained raw query/Search focus and exact typed Enter activation after publication.
- More than one page, native Load more, exact 31-item requests/cursor, unique typed
  controls, stable node/focus across append and reorder, removed-result recovery,
  and final-page pagination focus moving to Close.
- Native first-page and append Retry, retained raw query, pending Retry focus,
  recoverable localized failure states and no replayed selection/mutation.
- Native Close/Back, Add focus restoration, dismissal while a first page is pending,
  reopening before old publication, stale dismissal rejection and route exit while
  another read is pending.
- EN/RU at interface/text 90/90 with system font 1.0, 120/130 with system font 2.0,
  and 90/130 with system font 2.0; long names, all loaded result controls,
  Search/Retry/Load more/Close scrolling, actual native Tab focus and 48dp targets.
- Confirmed empty search in both languages, removal of old results, retained Search
  focus and native Close without selection.

`PlanControllerPersistenceTest` adds one registered Room-backed identity checking
both duplicate-name kinds through the canonical Library reader/controller, exact
source handoff, and unchanged source rows, source-owned snapshots, Plan rows and
Recent after navigation, selection, dismissal and obsolete callbacks. Its fixtures
create all relevant sources explicitly and compare source-scoped state.
`MainActivityRouteSessionTest` strengthens the existing production Plan lifecycle
case with captured-catalog Search/Dismiss rejection after route reentry.
All original instrumentation identities are retained; the inventory gains eight.
The existing repository typed bounded-keyset/no-graph-hydration regression remains
unchanged and mandatory.

## Verification boundary

The original dismissal and rapid-input regressions failed before their fixes. The focused Plan JVM suite
contains 63 cases, including execution-controller and execution-route regressions.
Local verification command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.alexandr5476.lifetracing.plan.*' :app:assembleDebugAndroidTest :app:ktlintCheck :app:detekt :app:lintDebug --continue --console=plain
```

All 63 focused JVM cases passed with zero failures, errors or skips. App ktlint,
Detekt, lintDebug and AndroidTest APK assembly passed. The inventory comparison
preserved every original identity and registered all eight additions; `git diff
--check` passed. No device result is inferred from these local checks.

AndroidTest compilation/APK packaging does not execute the native assertions.
No emulator or managed-device task was launched. Controller owns ci-fast, gmd-app,
canonical device records and independent exact-head GitHub compatibility gates.
Automated evidence does not establish manual TalkBack or Switch Access certification.
A manual walkthrough should cover Add, Search, identical-name kinds/ordinals, native
selection, empty/error status, both Retry paths, pagination, Close/Back and scaling
in both languages; scheduling form accessibility remains outside this slice.

## Controller API35 catalog repair (2026-11-11)

Controller's first gmd-app execution completed 608 cases with seven failures and no
skips; all failures were the new catalog presentation cases. This is an application/
fixture failure record, not an infrastructure retry or device PASS.

Retained native diagnostics show Search containing literal Tab (`ab\t` and `\t`)
after traversal. The multiline Material field consumed Tab as text, replacing the
query and, in the retry fixture, removing Retry. Search now consumes native Tab
Down/Up as forward/backward focus traversal, using the established editor approach.
The exact raw query is asserted after traversal in the existing keyboard regression.

Dialog content has its own Android composition root. The catalog now reapplies
captured app context, configuration and density inside title, body and button slots.
This preserves EN/RU and appearance scaling within an already open dialog. Shared
minimum interactive targets are also reapplied inside each slot, after Material's
dialog button-area override; Close must meet the same physical 48dp requirement as
results, Retry and Load more. The unchanged minimum-size assertion now reports the
language, scale, tag and bounds if it fails.

The removed-result diagnostic showed automatic framework fallback on another result
while Search remained offscreen. Attachment recovery now stays pending across that
fallback, scrolls Search into view and explicitly transfers native focus. A surviving
result still retains focus through append and reorder. Search/Close recovery does
not authorize selection or any durable command.

The adjacent scheduling handoff assertion now checks the scheduling window's actual
native ownership. The retained diagnostics establish that background and dialog
roots can both report local Focused=true; that local bit cannot prove a background
focus-restoration request. Exact typed source, fresh form and write-free assertions
remain. All seven original catalog identities and their native activation assertions
remain mandatory. Controller retains authorized repair-probe results; fresh canonical
gmd-app and independent exact-head CI remain required. Scheduling-dialog accessibility
and manual TalkBack/Switch Access certification remain outside this repair.

Local repair checks passed: all 63 focused Plan JVM cases (zero failures/errors/
skips), app ktlint, Detekt, lintDebug and AndroidTest APK assembly. The authorized
`D:\controller\.runtime\agent-tools\repair-probe.cmd` returned "No active
repair-probe lease" before any device or testcase admission. No raw managed-device
command was substituted. The original seven instrumentation failures therefore
remain open for Controller runtime revalidation; compilation and these repairs are
not a native-test PASS. The catalog inventory identity comparison and
`git diff --check` also passed.

## Controller API35 native target measurement correction (2026-11-11)

The subsequent causal class execution completed all seven catalog cases: six passed,
and only the scale-extremes case failed. At EN interface/text 90/90 and system font
1.0, it reported a 95px visual button rectangle against the 126px minimum target.
The assertion measured `boundsInRoot`, which is the visual surface rather than the
native touch target. Material permits a 40dp surface surrounded by its expanded
interactive target. The existing S1 calendar/management scaling regression already
uses `touchBoundsInRoot` for this same requirement.

The catalog now uses that established native measurement for every loaded result,
Retry, Load more and Close. The physical 48dp requirement is unchanged and applies
to both width and height; failures include native touch and visual bounds together.
All EN/RU scale combinations, scroll/display checks, native Button/OnClick assertions,
Tab focus traversal and no-selection/no-mutation assertions remain. Production
minimum-target providers and Material controls retain their existing behavior.
This correction does not establish a fresh device PASS: Controller owns the repair
probe result and subsequent canonical verification.

For this measurement correction, app AndroidTest APK assembly, ktlint and Detekt
passed, along with the seven-identity inventory comparison and `git diff --check`.
The authorized repair probe again returned "No active repair-probe lease" before
device/testcase admission. The remaining scale-extremes case therefore still needs
Controller runtime confirmation; the six passing cases in the supplied causal
execution do not establish a full gmd-app or independent CI PASS.
