# LifeTracing implementation clarifications

Read this addendum together with the frozen product v0.16, domain v0.10, and database v0.6 specifications. The historical versioned documents remain unchanged.

## Canonical completed-History display

The approved production History Parent narrows product-spec §42 only for canonical completed-History detail. Field and Category-option labels are frozen at execution/snapshot history: use the snapshot local override when present, otherwise its `name_at_creation` or `label_at_creation`. Completed History does not consult mutable ActivityTemplate or SequenceTemplate display metadata, so later source rename/archive/schema evolution cannot reinterpret a stored result. Stable source Field and option identities, Statistics grouping, and all non-History Template, Plan, and SequenceTemplate rename-propagation semantics remain unchanged.

History navigation derives its reachable newest date from the latest persisted completed root's `primaryLocalDate`, or current local date when later. It never treats the current device date as a hard cap, so a later original-zone date remains reachable after a device timezone change or wall-clock rollback. The lookup uses bounded existing-index reads and does not reinterpret persisted timestamps or load unbounded history. Completed-History paging remains the bounded canonical read.

The v10 index-only migration adds bounded exact newest-root access paths: `(context_type, status, deleted_at_ms, primary_local_date)` for standalone nondeleted completed Activity roots and `(status, primary_local_date)` for Sequence roots. The latter uses one lookup per terminal status. These indexes change neither durable History semantics nor stored values.

## Original-zone completed-History presentation

Canonical completed roots project the existing non-null `original_zone_id` and optional
`original_utc_offset_minutes` directly from bounded root discovery rows. Detail roots and
performed Sequence children retain the same stored provenance. No device-zone fallback,
persistence normalization, migration, or additional temporal lookup is permitted.

History and Daily completed timestamps show the original ZoneId and the actual UTC offset
of each displayed Instant in that zone. Each endpoint is interpreted independently across
DST transitions; the stored original offset describes the primary instant (timed Activity
start, No-live completion, Sequence start), not every endpoint. Invalid zone IDs or an
inconsistent non-null primary offset fail at canonical mapping/validation. Legacy null
offsets remain valid. Stored `primaryLocalDate` remains the selector/grouping authority.

Daily completed timed Activity and terminal Sequence ranges include localized calendar
dates on both endpoints when their actual Instants fall on different local dates in the
stored original ZoneId. Same-original-day ranges retain compact time-only labels with
endpoint-specific offsets. No-live completion remains one original-zone timestamp.
This presentation does not change primary-date attribution or any durable fact.

Sequence child Activity timestamps use the child's stored zone. Sequence-owned occurrence
and interval timestamps retain the root zone because those rows store no independent zone.
Deletion/tombstone visibility, identity, duration accounting, Statistics, paging and all
live-runtime/Plan timezone policies remain unchanged.

## Exact historical-overlap preflight performance exception

`overlapsCompletedHistory` remains the canonical exact in-database overlap predicate for completed standalone timed Activities: `started_at_ms <= proposedEnd` and `completed_at_ms >= proposedStart`. It uses the existing `(context_type, completed_at_ms)` access path, `EXISTS`, and short-circuit behavior; it neither loads History into application/UI memory nor performs an application-side History scan.

The frozen v1 schema has no interval-aware persisted access structure. Because valid historical Activities have no maximum duration, the predicate may examine an unbounded number of rows in a worst-case negative case: completion ordering can leave arbitrarily many later non-overlapping rows to test `started_at_ms`, while start ordering would analogously leave arbitrarily many earlier rows to test `completed_at_ms`. This is an explicit exception to the Parent's bounded/no-global-history-scan overlap-warning requirement. It does not permit a calendar cutoff, duration bound, false negative, or approximate overlap result.

A future schema/performance change may introduce an exact interval-aware persisted index (such as an R-tree-backed design). Until then, the current exact database predicate is intentional and must not be represented as worst-case bounded.

## Strict ExactDay scheduling and unchanged-target commands

New ExactDay scheduling input is the exact LocalDateTime, applicable ZoneId, and optional explicitly selected ZoneOffset committed by the command. Both Activity and Sequence creation and explicit rescheduling resolve that tuple again at the public command boundary using ZoneRules.getValidOffsets(), never LocalDateTime.atZone() default adjustment/selection or a trusted preview Instant.

New ExactDay local input must be exactly representable by the existing millisecond storage: its nanosecond fraction must be an integer multiple of 1,000,000. Form preflight and the public scheduling command conversion enforce the same policy for Activity/Sequence creation and explicit rescheduling before any durable effect. Finer fractions produce a specific localized, recoverable input error; they are never rounded or truncated. Millisecond fractions, including textual trailing zeros, retain their exact resolved Instant after persistence and database recreation. This admission rule does not revalidate an untouched existing absolute target or change the shared historical-time resolver, historical semantics, schema, or temporal source of truth.

Zero valid offsets rejects a DST gap. One valid offset resolves without selection, but any supplied offset must equal it. Two valid offsets require explicit selection of one of those offsets; absence returns ambiguity with both choices and an invalid or obsolete choice is rejected. Unsupported cardinality fails explicitly. Resolution exposes typed semantic outcomes suitable for later localized presentation. Invalid input produces no durable scheduling, snapshot-owned metadata, source, Recent, or linkage effects.

Persisted ExactDay scheduledAt is the authoritative absolute Instant, including existing seconds and milliseconds and legacy null creationZoneId. Reads never revalidate it as new local input. An explicit preserve-existing-schedule command accepts only the expected PlanActionIdentity and command timestamp, reloads the current Plan transactionally, and returns it unchanged without reconstructing local values or requiring offset selection.

Preservation and equivalent explicit rescheduling still enforce optimistic action identity, PLANNED status, absence of RUNNING/PAUSED linked root Execution, deferred Month actions, and command timestamp ordering. Invalid explicit temporal input is rejected before equivalence can produce success. FloatingDay equivalence is literal date equality; Week equivalence is literal weekStart equality; ExactDay equivalence is scheduledAt Instant equality regardless of proposed creation zone. Different target classes are never equivalent. An equivalent proposal returns the original Plan without executing a DAO reschedule update, retaining the original creationZoneId (including null), target precision, timestamps, identity, frozen snapshot, source revision, Statistics provenance, and all lifecycle/linkage facts. A changed device zone alone cannot revise the target.

A genuinely changed reschedule updates only the same Plan's target, creation-zone context for a new ExactDay target, and command timestamp within the existing transactional writer. It does not replace the frozen snapshot, source identity/revision, or unrelated durable facts and creates no reschedule-history record. Storage remains scheduled_instant_ms with no offset-choice column or schema change.

Production scheduling forms consume this strict resolution policy for preflight and retain the expected action identity as immutable existing-target provenance. Opening or dismissing a form is read-only. Submitting an untouched existing schedule dispatches the guarded preservation command, including after device-zone changes and for Instants with seconds or milliseconds omitted from the display. Any actual kind, date, or time edit permanently makes that form a new-input proposal, even if the visible value is subsequently restored. Overlap choices have no default; first/second labels follow the order of their resolved Instants, with visible UTC offsets. Edits to kind, date, time, or applicable zone clear the prior selection. Commands carry the exact local tuple and selected offset for authoritative repository revalidation.

New and edited ExactDay drafts capture and display one applicable device zone. Semantic invalidation and final submission both compare it with the current device zone. A mismatch clears offset authority and blocks submission until explicit review adopts the current zone for the retained local text; returning the device to the earlier zone does not silently clear that review requirement. Untouched existing targets need no zone review or overlap choice. Recoverable typed temporal failures keep the form editable and release the mutation lock without canonical mutation recovery. Stale or forbidden actions and unexpected persistence failures retain canonical recovery and never replay a mutation implicitly. Sharing the pure policy with historical-time handling preserves its parsing and manual historical Activity semantics.

### Plan optimistic-token admission and ephemeral form ownership

Accepted semantic changes through the Plan writer (changed reschedule, cancellation,
restoration, and frozen-snapshot replacement) must strictly advance `updated_at_ms`.
The command timestamp must be ordered and map to a later persisted millisecond than
its current token; otherwise the command rejects atomically before any write. No
synthetic timestamp or second revision source is introduced. Equivalent schedules
and guarded preservation remain write-free and retain their existing token. Terminal
fulfillment and runtime logical event timestamps retain their existing semantics.

Every creation/reschedule form receives a fresh controller-owned ephemeral UUID.
Edits preserve it; offset events carry it alongside the local tuple and input revision.
An event from a dismissed/replaced form is ignored and cannot authorize another draft,
including another source or another Plan with identical visible scheduling input.
This identity is never persisted.

Plan calendar visibility follows the top navigation entry, including PlanExecution
navigation, normalization, completion, and Back. Outgoing transition content does not
own lifecycle admission. Hidden reads and boundaries lose publication authority;
returning admits one fresh canonical projection and retains the selected date.

## First coded Plan UI scope

The older product-spec section 35 wording that makes Month selectable is narrowed for the first coded production Plan UI. Actionable production precision is Day and Week only.

- `PlanTarget.Month` remains valid durable domain and persistence state. Existing Month rows remain untouched and are not coerced into Day or Week reads.
- Add to Plan and reschedule must not dispatch Month writes.
- Month precision may be hidden or visibly disabled with a localized unavailable/coming-soon treatment.
- A Month scale or tab may exist only as a non-functional localized placeholder.
- Month creation, list/calendar/navigation/management, and reinterpretation remain deferred.
- Plan recurrence remains deferred.

## Start new statistics and Template revision

`Start new statistics from here` changes the Template's current `StatisticsSeriesId`, which is copied into future snapshots. It is therefore a semantic, snapshot-affecting Template change: the operation increments the Template revision exactly once in the same transaction that inserts the new Series and switches the Template pointer. Existing snapshots, Plans, Executions, and the old Series remain unchanged.

## Live Plan engagement and fulfillment

Stored Plan status remains only `PLANNED`, `FULFILLED`, or `CANCELLED`; v1 does not persist `IN_PROGRESS`.

A `PLANNED` Activity Plan is derived in progress while a linked top-level standalone `ActivityExecution` is `RUNNING` or `PAUSED`. A `PLANNED` Sequence Plan is derived in progress while its linked root `SequenceExecution` is `RUNNING` or `PAUSED`. This is a persistence/domain invariant derived from indexed `plan_entry_id` linkage, not process or UI state.

While engaged, the Plan cannot be cancelled, rescheduled, updated from its Template, or started again. It remains stored `PLANNED` until actual completion. Execution completion and `PLANNED -> FULFILLED` commit atomically using the Execution's logical completion timestamp. No-live quick completion creates its completed Execution and fulfills the Plan in one transaction without an intermediate live interval.

This prevents replacement of a Plan snapshot, target, or source revision beneath its running Execution. An explicit `End sequence early` makes a linked `PLANNED` Sequence Plan `FULFILLED` atomically with its fulfilling `SequenceExecution`; `PlanEntry.fulfilledAt` equals that execution's `endedAt`. The runtime domain transition only produces terminal `ENDED_EARLY`; the later repository transaction owns the atomic Plan update.

After any successful historical correction of a fulfilled Plan-linked terminal `SequenceExecution`, including structural `Close gap`, if the Sequence `endedAt` changes, the repository transaction must atomically set `PlanEntry.fulfilledAt` to that corrected `endedAt`. The same Plan remains `FULFILLED`, and its same `fulfilledSequenceExecutionId` continues to identify this Sequence; after success, `PlanEntry.fulfilledAt == SequenceExecution.endedAt`. Plan ID, target, precision, snapshot, source linkage/revision, and cancellation semantics remain unchanged. The Plan is never reopened or replaced. This rule applies only to Plan-linked Sequence correction, not standalone Activity correction.

## Standalone Activity historical mutation boundary

Canonical Activity correction is limited to completed, nondeleted, standalone `ActivityExecution` with `planEntryId == null`. A Plan-linked Activity correction command is invalid even when it is an exact no-op or changes only the Short Comment. Its execution, snapshot, values, Statistics contribution, source user state, and complete Plan state remain unchanged; v1 defines no `PlanEntry.fulfilledAt` consequence for such a correction.

A completed standalone Plan-linked Activity may still be soft-deleted. Deletion preserves its execution identity, snapshot, values, StatisticsSeries identity, `planEntryId`, and historical facts while setting only the Activity deletion metadata. The linked Plan remains the same `FULFILLED` value: `fulfilledAt`, fulfilled execution/snapshot linkage, source metadata, and Plan timestamps are not rewritten.

## Past overdue Plans

The v1 resolution for the deferred Past-day question in product spec §14.6 is that passing time never reschedules, copies, cancels, fulfills, or otherwise replaces a Plan. An unfulfilled passed Plan remains its original `PlanEntry`, with the same `PlanEntryId`, target, snapshot/source linkage, and stored `PLANNED` status. `overdue` is derived read/presentation metadata only; v1 adds neither `MISSED` nor persisted `OVERDUE`, an overdue cache, nor any new Plan lifecycle state.

A passed `FloatingDay` Plan remains in the ordinary Daily projection for its stored `date`, where it is overdue while still `PLANNED`; reading a later Today does not synthesize a copy. A passed `Week` Plan retains its original Monday `weekStart`, remains available through the existing Daily Week projection for dates in that week, and is overdue once that week has passed; a later Today outside that week does not receive a copy. An `ExactDay` Plan retains its absolute `scheduledAt` Instant. Current-zone Daily placement may move that same Instant to another local-day bucket after a timezone change; this is placement, never Plan rescheduling.

Fulfilled Plans remain their original Plans in their original planned context and are never overdue. Existing cancellation behavior is unchanged. These rules require no schema, DAO selector, migration, or durable-state change.

## ActivitySnapshot StatisticsSeries foreign key

When non-null, `activity_snapshots.statistics_series_id` references `statistics_series.id` with `ON DELETE RESTRICT`. The column remains nullable for true one-off Sequence child snapshots without a per-Activity Statistics Series.

A non-null StatisticsSeries ID is durable statistical identity. It must not dangle while an executable snapshot retains it, including after the source Template is archived or hard-purged.

## Sequence child history deletion tombstone

Deleting a performed child ActivityExecution from terminal Sequence history is logical deletion. The child row, pauses, values, frozen snapshot, event timestamps, duration, timezone attribution, and ownership links remain durable; only `deleted_at_ms` and the child `updated_at_ms` advance. The same occurrence changes from `COMPLETED` to `DELETED_EXECUTION` without changing provenance or timing, and the Sequence root advances only its `updated_at_ms` history-mutation token. Sequence intervals, boundaries, duration caches, values, and an already-coherent fulfilled Plan remain unchanged.

Canonical Sequence History retains the tombstone occurrence and its Activity configuration but omits the deleted child detail. Canonical Activity Statistics exclude the logically deleted child while Sequence and global root-derived statistics remain unchanged.

## Sequence structural history removal

Advanced structural removal preserves the durable occurrence and child rows. The target occurrence keeps its identity, provenance, runtime position, and historical event timestamps, becomes `DELETED_EXECUTION`, and sets `is_deleted_from_history = true`; its child remains the same logically deleted execution. Removing an existing child-deletion tombstone does not rewrite that child's earlier deletion metadata. Structurally removed occurrences are outside the effective Sequence timing graph, so no retained Sequence interval may reference them and their hidden timestamps do not constrain corrected root boundaries or duration caches.

`Leave gap` preserves the root boundaries and every non-target occurrence, child, and interval exactly, while removing all target-owned intervals. The former contribution is therefore derived as non-active wall time by the existing interval-union calculator. `Close gap` requires a complete explicit final interval graph, final root end, and explicit timing/pause facts for the complete later performed occurrence suffix. The root end determines one earlier-only translation. Every retained interval owned by a later visible occurrence uses that translation, including a closed transition countdown whose owner remains `NOT_STARTED` or `SKIPPED`; those unperformed owners gain no timestamps or child. Every moved performed occurrence, child execution, and child pause uses the same translation while preserving identity and duration. Ownerless intervals must be supplied explicitly and may only remain fixed or join that translation. The command validates the final graph and never infers a best-effort transform from overlap or crossing facts.

## Archived Category option cannot be a new-snapshot default

Archived Template Category options are excluded when a new ActivitySnapshot is created. A Template Category default must therefore be null or reference a currently active option of that same Field at semantic commit time.

Archiving the current default option in one semantic commit must also clear the default or select another active option. Snapshot creation rejects invalid Template input and never restores an archived option by copying it.

## Folder deletion authorization and bind policy

An empty-Folder deletion is authorized only by its canonical repository writer. In the same database transaction that deletes the Folder, the writer verifies that it has no direct child Folder and no directly placed reusable ActivityTemplate or SequenceTemplate row, including archived Templates hidden from active Library presentation. A stale empty inspection is rejected without moving, archiving, or deleting content and never falls back to `Move contents`.

`Delete contents` derives the complete Folder subtree once, archives both Template kinds in chunks whose total statement bindings do not exceed the repository SQLite safe-bind count, and deletes the subtree within the same outer transaction. The current archive UPDATE reserves one binding for `deletedAtMs`, so each chunk contains at most `safe-bind count - 1` Folder IDs. Any archive-chunk or Folder-delete failure rolls back the complete operation.

## ActivityExecution completion reason at the v4 boundary

`activity_executions.completion_reason` permits only `NULL` and `MANUAL_HISTORY_ENTRY`. `NULL` represents ordinary current completion for timed and no-live Activities; `MANUAL_HISTORY_ENTRY` is reserved for explicitly backdated history creation.

Additional technical or Sequence-related completion reasons require a future frozen specification and schema change. They are not persisted speculatively in v4.

## Sequence Step execution-setting overrides

`ActivityConfigSnapshot.locallyModified` remains a whole-Activity-snapshot propagation flag. It answers whether the Step's Activity snapshot was locally edited; it does not mean that every Activity execution setting was explicitly overridden for that Step.

Version 1 therefore stores a narrow, typed per-Step execution-settings override record. This is not generic per-property Activity snapshot diff tracking. A null override means "no explicit Step override; continue through the Activity/Sequence inheritance chain." A non-null override means "the user explicitly chose this value for this particular Step." In particular, zero and false are explicit values rather than absence.

Step overrides are mutable SequenceTemplate semantic configuration. A committed override change increments the SequenceTemplate revision once together with any other semantic changes in the same Save, Apply, or Done operation.

When immutable SequenceSnapshot persistence is introduced, it must preserve the same Step override intent in snapshot-owned form, separately from the child ActivitySnapshot. It must not infer or flatten override intent from `locallyModified`.

## Sequence Step duplicate-and-move

Duplicating an existing Activity Step during one SequenceTemplate Save/Apply/Done creates a second physical Step, not a second reference to the same immutable snapshot. The duplicate receives fresh `SequenceNodeId`, `ActivitySnapshotId`, snapshot-Field IDs, and snapshot-Category-option IDs. It copies the source Step's frozen Activity values/settings, source Template and revision linkage, StatisticsSeriesId, source Field/option provenance, `locallyModified`, and explicit Step execution overrides exactly as frozen at duplication time. The source must be an ActivityStep already owned by the Sequence being committed; a Repeat, foreign node, or unknown node is rejected. The original Step remains unchanged and the two Steps are independent owners after commit.

Duplication never rereads or refreshes from the mutable ActivityTemplate. An unmodified linked duplicate remains independently eligible for normal linked-snapshot propagation; a locally modified duplicate remains locally modified and is skipped by `ONLY_UNMODIFIED`. Whole-Repeat duplication is not part of this rule.

## SequenceSnapshot foreign keys and frozen overrides

`sequence_snapshots.statistics_series_id`, when non-null, references `statistics_series.id` with `ON DELETE RESTRICT`. This preserves the Sequence's durable statistical identity after its source Template is hard-purged.

`sequence_snapshot_nodes.activity_snapshot_id` references `activity_snapshots.id` with `ON DELETE RESTRICT`; the referenced immutable Activity snapshot is reused rather than copied. Frozen explicit Step override intent is stored in `sequence_snapshot_step_overrides`.

## Sequence occurrence completion reason

`sequence_occurrences.completion_reason` permits `NULL`, `NATURAL_TIMER_END`, `MANUAL_FINISH`, `ADVANCED_TO_NEXT`, `JUMP`, and `SEQUENCE_ENDED_EARLY`.

- `NATURAL_TIMER_END` means the Timer reached its valid finishing boundary.
- `MANUAL_FINISH` means the user explicitly completed the current Step normally.
- `ADVANCED_TO_NEXT` means an already-started current Step was finalized by advancing to the next Step.
- `JUMP` means an already-started current Step was finalized by jumping to another occurrence.
- `SEQUENCE_ENDED_EARLY` means an already-started current Step was finalized while its parent Sequence ended early.
- `NULL` means the reason is not applicable, unavailable, not yet known, or not specific enough for historical/technical data.

An untouched skipped occurrence has `status = SKIPPED` and `completion_reason = NULL` because it was never started. These Sequence-specific technical reasons are not persisted in `activity_executions.completion_reason`; a normal Sequence-child ActivityExecution keeps that value `NULL` under the existing v4 contract.

Future occurrence reasons require a domain code, a later migration of the SQL `CHECK`, and mapper/tests. The persisted `TEXT` identity does not need redesign.

## Repeat iteration persistence

`sequence_occurrences.repeat_iteration` is 1-based. Repeat ×N persists iterations `1..N`, never `0..N-1`; Repeat ×3 therefore stores iterations 1, 2, and 3.

## Runtime Sequence occurrence cardinality

A single SequenceExecution supports at most `MAX_SUPPORTED_RUNTIME_OCCURRENCES = 10_000` materialized occurrences. New Sequence authoring commits reject a greater total. The start/materialization boundary independently computes the total from frozen top-level Steps and Repeat child counts with checked `Long` arithmetic, and rejects a greater total before generating occurrence IDs, allocating occurrence collections, iterating Repeat instances, or persisting runtime state. This is a technical runtime limit only: Repeat provenance, identity, 1-based iteration, runtime position, and the persisted `repeat_count INTEGER` schema remain unchanged. Existing persisted Templates, snapshots, Plans, and migration-originated configurations may remain representable but are rejected normally if started above the bound.

## Live-runtime countdown boundary

Activity/Sequence pre-start countdown is a preflight state outside durable Execution runtime in v1.

- A Sequence's effective first-Step countdown is its explicit Step override, otherwise `sequenceStartCountdown`.
- That countdown occurs before `SequenceExecution.startedAt`; the SequenceExecution and `active_session` row are created only at the actual Sequence start boundary.
- A standalone Activity's `startCountdown` likewise occurs before its RUNNING ActivityExecution is created.
- Process death during preflight cancels the countdown because no Execution or `active_session` exists yet.

This does not apply to inter-Step countdowns. A transition countdown inside a running Sequence is persisted and recoverable. V1 does not add a `COUNTDOWN` active-session state or a not-yet-started live Execution.

## Live Sequence interval classification

V1 classifies live Sequence intervals as follows:

- timed current Step: `ACTIVE_STEP`;
- No-live current Step with `noLiveTimeAccounting = ACTIVE`: `ACTIVE_STEP`;
- No-live current Step with `noLiveTimeAccounting = PAUSE`: `STEP_PAUSE`;
- explicit Sequence pause: `EXPLICIT_PAUSE`;
- Auto-advance OFF without a current Step: `IMPLICIT_IDLE`;
- inter-Step countdown: `TRANSITION_COUNTDOWN`.

Every runtime-created `TRANSITION_COUNTDOWN` interval points through `occurrence_id` to the next occurrence whose start is being counted down. Pause/resume/recovery derives the countdown target and consumed duration from these intervals; no separate pointer or `remaining_ms` cache is stored. `STEP_PAUSE` currently means No-live-as-pause only; the deferred advanced pause-behavior setting is not inferred.

## Android runtime scheduling boundary

Android runtime recovery uses no foreground service, wakelock loop, repeating alarm, or WorkManager polling. Persisted Activity and Sequence state remains timestamp-based: durable `active_session` and Execution state are authoritative, late platform delivery never replaces a calculated logical event timestamp, and reconciliation after process death or background delay is deterministic.

While the process exists, the next deterministic live deadline is also armed as one boot-local, monotonic, elapsed-realtime-based one-shot. It does not poll or keep the process alive. The external `AlarmManager` alarm remains the advisory background/process-death backup, and both signals pass through the same stale-deadline validation and durable reconciliation boundary.

Without a foreground service, Android does not guarantee timely delivery of every rapidly recurring short deadline while the process is dead or the device is deeply idle; allow-while-idle alarms may be throttled. The v1 guarantee is exact durable state after reconciliation, best-effort timely background wake and feedback, and precise in-process live transitions—not that every sub-minute Sequence boundary wakes a deeply idle device. If product requirements later demand interval-timer-style screen-off feedback at every short boundary, a foreground-service strategy must be evaluated separately.

In-process display progression uses an immutable baseline built from a wall/`elapsedRealtime()` anchor that includes deep sleep and is discarded on process death, reboot, or a wall/timezone-change broadcast. Baseline construction may inspect persisted history once; each display tick uses only the current monotonic value and O(1) checked arithmetic. Only epoch timestamps are persisted. `BOOT_COMPLETED` recovery runs after credential-protected storage is available; `LOCKED_BOOT_COMPLETED` is intentionally unsupported.

When exact-alarm special access is unavailable, the adapter uses one `setAndAllowWhileIdle()` `RTC_WAKEUP` fallback. Delivery may be delayed, while later reconciliation still persists exact logical event timestamps. No background component opens Settings or requests notification permission.

Deadline feedback is a non-durable effect. Alarm and user-present foreground recovery, including foreground process start, emit at most the latest applied semantic deadline from that bounded reconciliation; earlier catch-up events are suppressed. Boot and time-change recovery reconcile and reschedule without sound or vibration. The sound contract is currently backed by a no-op adapter until a product-owned cue asset is selected; vibration is implemented through the platform vibrator API.

## Daily and Plan temporal projection ownership

Canonical Day/Week reads capture one effective ZoneId and query timestamp. The resulting ephemeral calendar context governs half-open local-date Instant windows, ExactDay placement/time/UTC offset, overdue, Daily today/active eligibility, and week density. Application callers may supply the captured zone with the query; direct repository callers capture the provider once. Canonical results retain that context. ExactDay rows carry the actual occurrence offset in that zone; ordinary calendar clock labels include it, and equal local clock labels order by absolute scheduled Instant before stable identity. Creation-zone context is never a display-offset authority.

Visible controllers serialize request admission, calendar navigation, route lifecycle, final read publication, and boundary arming. Publication requires the current request, selected calendar context, semantic generation, and effective zone. Obsolete successes and failures cannot replace newer state or re-arm boundaries. Hidden routes cancel their one-shot boundary and invalidate pending reads; re-entry reads current context while preserving the selected date. A delayed projection crossing midnight or an ExactDay strict-overdue boundary schedules an immediate one-shot reread using its captured boundary. System-time/timezone and foreground signals continue through the existing runtime coordinator, including without active runtime.

This changes only read-time metadata and publication: no target, snapshot, lifecycle, linkage, historical attribution, Statistics identity, or stored timestamp is rewritten, and no migration or temporal cache is introduced.

## Exact individual Number Field aggregation (S2)

Database schema v0.6 sections 26.4 and 44 describe ordinary integer SUM and application
median computation. For individual Number Field Statistics, signed scaled Long inputs
require exact BigInteger totals even beyond either Long boundary and through mixed-sign
cancellation. SQLite SUM(number_scaled), TOTAL and floating AVG cannot supply that total.
The S2 reader instead counts set bits at all 64 positions, reconstructs the signed
two's-complement sum in BigInteger, and selects at most two exact ordered median values
in SQL. All facts remain within the existing repository read transaction. Statistical
eligibility, stable source Field/Series identities, scaling, missing/coverage and
original-zone date attribution are unchanged. No schema/index change.

## Exact grouped Number Fields in Series Detail (S3)

Series Detail applies the same S2 arithmetic/validation to grouped stable source
Field summaries. SQL returns one fixed-width row per recorded Field, containing count,
min/max, integer-storage validation and the 64 decimal bit counts. Kotlin reconstructs
exact signed totals through ExactNumberSummary and ExactSignedLongSum.

Individual Number Field reads retain their bounded source-Field/value probe and exact
scalar ordered selection. The probe stops at twice the selected eligible execution/value
candidate count Q plus one; it includes unused metadata through LEFT JOIN. Reaching the
sentinel selects execution-first access, protecting tiny new Series and narrow periods
from large old lineages. Otherwise Field-first access protects exclusive dense/sparse
lineages. Unrelated global history cardinality never gates the choice.

For P populated Fields, Series Detail first allocates each Field an equal probe capped at
min(floor(2Q / P) + 1, candidateLimit(Q)), using the existing saturated individual bound
candidateLimit(Q) = min(2Q + 1, Long.MAX_VALUE). Every Field is inspected independently
of display-name order or another Field's saturated lineage. If more than three Fields
remain unresolved, a second fair stage tests the capped candidate's existence with
LIMIT 1 OFFSET (cap - 1). Its cap is min(floor(kQ / P) + 1, candidateLimit(Q)), where
k is 10 for up to sixteen unresolved Fields and 15 for larger sets. Both stages use the
original P denominator. The second stage walks at most its cap, including unused
metadata; it does not count or return the preceding candidates. Combined probe visits
are bounded by 17Q + 2P. Admitted lineage cardinalities total at most 15Q, and scalar
median SQL may traverse each lineage twice. These cost allowances retain moderately
shared lineages without unbounded independent execution-first scans. Admitted Fields
keep scalar Field-first reads. Up to three unresolved Fields use execution-first scalar medians directly.
For 4..16 unresolved Fields, try at most three shared rank passes, retaining resolved
results, then finish any remainder with scalar ordered selections. This keeps the cheap
three-pass duplicate-heavy path while bounding repeated traversal on broad signed values.
Larger unresolved sets also stop after three shared rank passes, retaining resolved Fields.
Remaining Fields use exact magnitude/byte histograms rather than up to 65 full rank scans.
The first histogram partitions signed integers at negative/positive powers of two and zero
(128 bins, with zero and one individually represented). Each bin returns only count and
extrema. Locate both adjacent median ranks cumulatively; a rank at a bin edge is its
minimum/maximum. Unresolved adjacent ranks share at most one bin per Field. Subsequent
histograms restrict each Field to that bin and partition the highest differing byte.
All remaining Fields share one byte shift per pass, removing at least eight bits from
the maximum unresolved width. At most eight byte passes follow the magnitude pass.
No floating-point arithmetic, SQLite SUM, window functions, sample cursors or persistent
intermediate state are used. The fixed traversal bound is three trial plus nine histogram
passes, not a general relative VM-work bound for arbitrary distributions.

Rank and histogram queries start at the selected execution/value subset. Balanced binary
CASE lookup of opaque UTF-8 hex source IDs costs O(log P) per candidate and uses only
four period/Series binds. Each histogram returns at most 256 count/extrema rows per
unresolved Field. Application state/rows are O(256P), independent of execution/sample
cardinality Q. SQLite GROUP BY may use an ephemeral native B-tree over selected values;
this ordinary exact aggregation is bounded by O(Q log Q) per pass, never P independent
full execution scans. For at most sixteen Fields, existing bounded scalar completion
remains. Indexed probes and admission allowances above are unchanged. See the W1 signed
boundary section in docs/implementation/statistics_pr37_review_evidence.md for measured
SQL VM work and plan evidence; earlier crossover measurements are fixture observations,
not general bounds.

Empty catalog Fields retain the canonical empty distribution and the containing Series'
execution denominator. Category/Text, source/snapshot provenance, eligibility, ordering
and period semantics are unchanged. All summaries, probes and rank passes remain in the
complete Series Detail Room transaction. There is no schema, index, cache or durable-state
change. Query count can scale with populated result Fields, never executions or samples.
See docs/implementation/statistics_pr37_review_evidence.md for current evidence;
docs/implementation/statistics_number_s3_evidence.md preserves earlier S3 evidence.
