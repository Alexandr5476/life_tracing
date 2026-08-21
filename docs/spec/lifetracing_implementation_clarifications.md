# LifeTracing implementation clarifications

Read this addendum together with the frozen product v0.16, domain v0.10, and database v0.6 specifications. The historical versioned documents remain unchanged.

## ActivitySnapshot StatisticsSeries foreign key

When non-null, `activity_snapshots.statistics_series_id` references `statistics_series.id` with `ON DELETE RESTRICT`. The column remains nullable for true one-off Sequence child snapshots without a per-Activity Statistics Series.

A non-null StatisticsSeries ID is durable statistical identity. It must not dangle while an executable snapshot retains it, including after the source Template is archived or hard-purged.

## Archived Category option cannot be a new-snapshot default

Archived Template Category options are excluded when a new ActivitySnapshot is created. A Template Category default must therefore be null or reference a currently active option of that same Field at semantic commit time.

Archiving the current default option in one semantic commit must also clear the default or select another active option. Snapshot creation rejects invalid Template input and never restores an archived option by copying it.

## ActivityExecution completion reason at the v4 boundary

`activity_executions.completion_reason` permits only `NULL` and `MANUAL_HISTORY_ENTRY`. `NULL` represents ordinary current completion for timed and no-live Activities; `MANUAL_HISTORY_ENTRY` is reserved for explicitly backdated history creation.

Additional technical or Sequence-related completion reasons require a future frozen specification and schema change. They are not persisted speculatively in v4.

## Sequence Step execution-setting overrides

`ActivityConfigSnapshot.locallyModified` remains a whole-Activity-snapshot propagation flag. It answers whether the Step's Activity snapshot was locally edited; it does not mean that every Activity execution setting was explicitly overridden for that Step.

Version 1 therefore stores a narrow, typed per-Step execution-settings override record. This is not generic per-property Activity snapshot diff tracking. A null override means "no explicit Step override; continue through the Activity/Sequence inheritance chain." A non-null override means "the user explicitly chose this value for this particular Step." In particular, zero and false are explicit values rather than absence.

Step overrides are mutable SequenceTemplate semantic configuration. A committed override change increments the SequenceTemplate revision once together with any other semantic changes in the same Save, Apply, or Done operation.

When immutable SequenceSnapshot persistence is introduced, it must preserve the same Step override intent in snapshot-owned form, separately from the child ActivitySnapshot. It must not infer or flatten override intent from `locallyModified`.

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

Android runtime recovery uses no foreground service, wakelock loop, repeating alarm, or WorkManager polling. Durable `active_session` and Execution state remain authoritative; one advisory next-deadline `AlarmManager` alarm is reconstructed after each committed reconciliation, foreground return, boot, or system wall/timezone change. Alarm delivery never directly applies a transition and its delivery timestamp never replaces the core's calculated logical deadline.

In-process display progression uses a wall/`elapsedRealtime()` anchor that includes deep sleep and is discarded on process death, reboot, or a wall/timezone-change broadcast. Only epoch timestamps are persisted. `BOOT_COMPLETED` recovery runs after credential-protected storage is available; `LOCKED_BOOT_COMPLETED` is intentionally unsupported.

When exact-alarm special access is unavailable, the adapter uses one `setAndAllowWhileIdle()` `RTC_WAKEUP` fallback. Delivery may be delayed, while later reconciliation still persists exact logical event timestamps. No background component opens Settings or requests notification permission.

Deadline feedback is a non-durable effect. Alarm and user-present foreground recovery, including foreground process start, emit at most the latest applied semantic deadline from that bounded reconciliation; earlier catch-up events are suppressed. Boot and time-change recovery reconcile and reschedule without sound or vibration. The sound contract is currently backed by a no-op adapter until a product-owned cue asset is selected; vibration is implemented through the platform vibrator API.
