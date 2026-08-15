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
