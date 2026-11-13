# Standalone Activity historical correction accessibility (S3)

History correction uses the exact ActivityExecutionId through route, retained
session, controller, canonical detail, draft and existing writer. Only completed,
nondeleted, standalone, non-Plan-linked Activity detail admits correction.
Plan-linked detail explains ineligibility and retains Delete. No domain command,
repository, transaction, schema, migration, Statistics projection, timezone or
overlap policy changed. S1 browsing, S2 manual entry, Activity deletion policy and
Sequence historical behavior remain intact.

## Native controls and admission

Pointer, keyboard and accessibility actions share guarded controller callbacks.
Admission checks attachment, exact route/session, resumed lifecycle, publication,
detail/draft identity, command phase and applicable Field/option. Each input can
advance its own publication cursor for consecutive text events before recomposition.
Cancel, replacement detail, dismissed warning, hidden/replaced routes and disposal
revoke older callbacks. Transient publication counters keep immediate equal-looking
retry failures actionable without reviving old callbacks. Correction reentry cannot
replace an existing draft or pending warning.

Inputs and choices use native controls. Frozen Field/option IDs are their keys;
contextual labels distinguish duplicate names. Fields expose type, configured
context, actual value, Number unit/precision, reversible presence and associated
validation. Category and offset choices expose native selected/radio semantics.
Zero, empty Text and Missing remain distinct. EN/RU status/recovery strings, wrapping
actions and shared physical minimum targets support app/system scaling.

Untouched actual Numbers retain their exact stored scaled integer even when frozen
display precision rounds their text. Missing/restore and an unchanged native text
event preserve that fact. Explicit Number edits still use the existing parser and
frozen precision. The regression reproduced 12555 becoming 12600 during comment-only
Save before the fix. Unchanged Short Comment preserves its original representation;
actual comment edits still use immutable snapshot replacement through the existing
writer. No focus or draft edit writes a snapshot.

HistoricalLocalDateTime and the controller builder remain authoritative. Original
ZoneId and millisecond precision are retained. Stored repeated-hour offsets
initialize independent endpoint choices; editing an endpoint clears its authority.
Choices identify endpoint, first/second occurrence and actual UTC offset. Gaps,
malformed dates, future completion and reversed intervals remain validation failures.

## Lifecycle, recovery and focus

The exact overlap preflight excludes the edited execution. Cancel writes nothing;
editing requires fresh validation/preflight. Only the current validated warning can
approve a correction, and duplicate Save/Proceed cannot duplicate its writer.
Mutation blocks edits and Back. Stale rejection discards the proposal, reloads
canonical facts and requires explicit correction reentry. Ordinary failure keeps
the draft and offers explicit Save retry.

After commit, refreshGeneration advances before canonical reload. Localized states
distinguish saved/reloading, saved/read-failed and saved/reloaded. Retry after a
committed read failure only reads detail. Native/system Back drain the authoritative
generation before releasing the exact session, covering commit followed by Back
before Compose observes the generation. Retained delivery remains exactly once
through recreation; a read-gated regression first reproduced zero deliveries.

Entry focuses the first real timestamp input, completion for No-live. Stable keys
preserve surviving Field focus; removed/disabled controls recover to current
available targets. An initially disabled Back target retries pending keyboard
landing when mutation finishes. Tab/Shift-Tab traverse native inputs; focus never
invokes a command. Deferred requests recheck foreground ownership. Accessibility
focus and new publication/edit/recovery markers are not persisted.

## Bounds

HistoryReadRepository.getActivityDetail still hydrates only the selected execution
and snapshot aggregate. Its existing mapping indexes actual values once and maps
owned option lists. Rendering and focus bookkeeping are linear in selected Fields
and options; each option-label map is built once per Field. Publication/route checks
are constant-time, and Category activation checks only that bounded aggregate.
There are no per-Field reads, eager History hydration, accumulated History copies or
quadratic whole-form transformation per rendered Field. Existing query/bind
boundaries and the documented exact-overlap worst-case performance exception remain.

## Verification and limits

Before the first Controller device execution, local verification passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' --tests '*statistics.*' --tests '*plan.*' --tests '*LauncherNumberFormatTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
```

App JVM reports contain 253 tests with zero failures/errors/skips. Domain test and
data unit-test tasks also passed using up-to-date results: 245 and 36 cases.
Inventory identity checks, EN/RU resource XML validation and git diff --check passed.
Draft-reentry, missed-refresh and numeric precision regressions were observed
failing before their fixes. Shared JVM fixtures create fresh state per test.

Ten new JUnit4 presentation methods cover native semantics, keyboard/focus,
validation, presence, selected choices, EN/RU scaling, foreground/disposal,
warning admission, stale unavailable detail and commit/read recovery. Ten new
PrimaryExecutionJourneyTest methods use real MainActivity/NavDisplay, production
controllers and existing physical repositories, with counted ports and explicit
deterministic failure/commit gates. They encode physical close/reopen comparisons
through canonical History/Statistics for timed cross-midnight milliseconds, No-live,
both DST offsets, approved overlap, comment-only/no-op, stale competing facts and
save/read failures, including immutable provenance and unrelated Plan/Sequence facts.
The multi-day fixtures supply explicit all-time counts; older reopen assertions
retain their defaults. All 20 methods are registered in instrumentation-inventory.json.

The first Controller API35 run executed 683 tests and reported 12 failures. All ten
new physical correction journeys crashed in History's shared Number formatter:
exact-only BigDecimal scale conversion rejected stored 12555 at display precision 1.
History now uses the established HALF_UP presentation formatter for both configured
and actual Activity/Sequence values. Storage and correction parsing remain unchanged.
HistoryNumberFormatTest reproduced the ArithmeticException before the fix and passes
afterward, covering finer stored precision, negative values, default precision and zero.
The native-input fixture also renders a finer configured value as 12.6 kg.

The native-input failure's full diagnostic showed Back focused while the timestamp
input remained enabled. History's deferred keyboard landing remained pending after
the editor acquired native focus, so changing input mode could move focus to Back.
Any History target acquiring focus now cancels that pending landing; the deferred
request also rechecks this authority after its frame wait. This shared fix covers
Activity correction and sibling History inputs. The regression still enters keyboard
mode after correction opens and asserts that the timestamp keeps native focus.

The pending-session fixture had attempted keyboard admission when every route action
was disabled. It now admits keyboard mode on a native setup control before the detail
route's first composition while saving. It still asserts disabled Back followed by
automatic Back focus after canonical reload, without requesting that target's focus.

The focused repair-local History, launcher Number-format and MainActivityNavigation
JVM run passed all 143 tests with no failures, errors or skips. App ktlint and Detekt
passed. Production and AndroidTest sources compile and both APKs package.

The next Controller API35 run executed 683 tests with ten failures. The previous
Number crash and both native-focus failures were absent. All ten correction journeys
reached physical reopen and failed the same untouched-sibling equality assertion:
the command result listed values in schema order, while canonical reads ordered by
snapshot Field ID. Every other execution fact and the complete typed value multiset
matched in all ten reports. The fixture now captures the sibling through the canonical
reader before correction, first checks every command-returned fact using canonical
value ordering, then compares the full canonical aggregate after physical reopen.
Saved execution, competing correction, snapshot, Plan, Sequence and Statistics
assertions retain their existing checks; their baselines already use canonical reads
or the correction writer's ID ordering. No production or persistence policy changed.

The subsequent focused Controller run executed the ten correction journeys. The
unchanged-comment journey passed; the other nine advanced to the same unrelated-table
assertion. Its exclusions covered snapshot rows, Fields and Category options but
omitted snapshot settings. Each reported difference was exactly one old settings row
replaced by an identical settings payload owned by the replacement snapshot. The
fixture now explicitly compares the entire settings table with only that owner ID
replaced, independent of insertion order. This also requires the unreferenced old
settings row to be pruned and every unrelated settings row to remain unchanged.
The full no-op database equality and downstream facts, provenance and Statistics
assertions remain intact. Rechecking all nine recorded failures confirmed the exact
settings payload and unrelated rows satisfy this corrected expectation.
App ktlint and Detekt checks succeeded, and the changed AndroidTest Kotlin sources
compiled and the AndroidTest APK packaged successfully.

The repair probe command again refused execution with "No active repair-probe lease";
no probe testcase started. Fresh Controller execution is still required to verify the
repaired assertion and downstream assertions. No raw emulator/device task was launched.
Independent GitHub CI remains mandatory. Manual TalkBack, Switch Access and human
hardware-keyboard walkthrough/certification were not performed.
