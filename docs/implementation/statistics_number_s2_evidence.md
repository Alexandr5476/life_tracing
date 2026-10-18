# S2: exact bounded-memory individual Number Fields

Scope: only canonical individual Activity/Sequence Number Field reads. Combined Series
Detail still collects samples and uses the collection calculator; its small-fixture
equality tests establish semantics only. No migration, index, identity or scaling change.

## Exact arithmetic

For a signed 64-bit value x, with bits b[0..63],
x = sum(b[i] * 2^i, i=0..62) - b[63] * 2^63.
Summing this identity commutes with the finite sums over executions. SQL returns each
bit's set count c[i], in [0, N]; Kotlin computes the same expression with BigInteger.
No SQL SUM, floating arithmetic, abs(Long.MIN_VALUE), signed Long intermediate total or
per-sample Kotlin arithmetic occurs. COUNT never exceeds N, even when N=Long.MAX_VALUE.
SQLite arithmetic right-shift plus &1 extracts every bit, including sign bit 63.
The domain tests also reconstruct constant-value histories at N=Long.MAX_VALUE.

Average is total/N reduced as ExactValue. Median sorts signed integers and selects one
or two values at offset (N-1)/2 with SQL LIMIT bounded to 0/1/2. Endpoint addition uses
BigInteger before reduction. Empty input returns zero total and absent average, median
and extrema. Zero values count as recorded; negative values retain their signs.

## Transaction and validation

The existing Room runInTransaction encloses catalog/Series validation, historical Field
metadata validation, a count-only denominator, bit summary and median query. Median
selection independently counts eligible values and rejects disagreement with summary.
Every bit projection repeats count/min/max; the mapper rejects missing/duplicate/out-of-order
positions and inconsistent repetitions. Domain validation rejects invalid counts, missing
bounds/endpoints, endpoint order/range, impossible total bounds (also constrained by
median positions) and contradictory small sample summaries. SQL counts non-integer storage; Room row construction rejects it before
narrowed min/max can produce a distribution. No read path mutates database state.

## Resource bounds and query shape

Exactly five application reads: Series catalog, Field metadata, relevant execution count,
bit summary and median selection. This count is independent of executions and samples.
Metadata/catalog costs are unchanged and depend on Fields/Series, not raw sample retention.
Number-specific retained Kotlin state: exactly 64 scalar bit rows, a 64-count list during
reconstruction, one summary and one selection row, and at most two median endpoints.
No full sample Cursor, snapshot graph, all-distinct values, page loop or per-record query.
Total magnitude is bounded by N * 2^63 (at most 127 signed bits for representable N).

The recursive bits CTE emits exactly 64 rows using only recursive CTEs, bitwise operators,
COUNT, MIN/MAX and LIMIT/OFFSET; no window functions or newer SQLite features are used.
The summary CTE always emits one aggregate row, including for an empty history; cross
joining the 64 bit positions preserves all zero-count rows. A correlated COUNT over the
eligible set runs once per bit. This intentionally trades 64 fixed indexed traversals for
avoiding the initial prototype's 64N-row expansion and temporary GROUP BY. There is one
additional traversal for scalar count/min/max/type validation. SQLite may materialize the
eligible set or inline its joins; neither changes the fixed number of traversals. For N
eligible samples and E candidate execution/Field-value join rows, the summary visits at
most O(64E) join candidates (plus existing index lookup costs), rather than O(N squared).
Only the 64 output rows need the summary query's ORDER BY temporary B-tree. The median
query performs an independent count and ordered LIMIT with one OFFSET, using a temporary
sort of up to N samples: O(N log N). Native sorting/materialization storage may grow with
history; application result memory does not. Fixed small output removes history-sized
CursorWindow traversal and its repeated query/skip amplification. No Android latency,
heap or refill measurement is inferred from DAO callback counts.

## Evidence and acceptance

`statistics_number_probe.py` extracts the actual Room annotation SQL and runs it against
the checked-in v10 tables/indexes, with foreign keys enabled. Controlled increasing
fixtures include sparse recording, repeats, high-cardinality values, mixed signs and both
Long extremes. Python arbitrary-precision integers/Fraction supply an independent oracle.
It asserts fixed result cardinality, numeric query counts, periods, empty selections,
count mismatch visibility and malformed storage detection, and prints query plans.

Domain tests were added before implementation: the focused command failed compilation
on the absent summary API (red phase). Room instrumentation adds four pinned cases for
eligibility/periods, increasing history, physical reopen plus supported historical deletion,
and isolated malformed storage. Existing child deletion, Field identity/archive/split and
Series Detail parity cases remain mandatory. Device execution belongs to Controller.

### Executed evidence

Final focused verification on 2026-10-18 completed successfully:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :domain:ktlintCheck :data:ktlintCheck :domain:detekt :data:detekt :data:lintDebug :data:assembleDebugAndroidTest --continue --console=plain
```

The full domain/data JVM suites executed successfully during this logical task: 241 and
22 tests respectively, with zero failures, errors or skips. After the final fixture
corrections, the command above succeeded with those unchanged-input JVM/static tasks
UP-TO-DATE and rebuilt the changed AndroidTest source/APK and associated lint analysis.
The earlier formatting failures were corrected before this successful verification.
The nonterminal Activity fixtures now explicitly use a timed snapshot; No-live samples
remain completed with missing duration. The growing-history assertion reads the SQL log
under its monitor and excludes Room's internal modification-log SELECT, matching the
existing duration query-count fixture.

The host probe commands `python docs/implementation/statistics_number_probe.py --rows 100`
and `--rows 100000` were rerun successfully against the final production annotation SQL.
The 10,000-execution probe also passed in this logical task. The inventory/source check
matched all 21 StatisticsRepositoryTest methods and confirmed 613 data, 323 app and two
startup mandatory cases. `git diff --check` passed. These are host/build results;
compilation is not device execution. Controller owns the full device/API matrix and CI.

The revised annotation SQL was executed on host SQLite 3.53.1 against v10 tables/indexes
at 100, 10,000 and 100,000 executions **per kind**. Every comparison passed, for AllTime,
a selected day and an empty period, with 64 summary rows, one scalar selection row and
three numeric queries in every case. The independent Python reference includes large
positive/negative values, both Long extremes, repeats, distinct values and sparse rows.

| Executions/kind | AllTime recorded | Selected day recorded | Summary rows | Selection rows | Numeric queries |
| --- | --- | --- | --- | --- | --- |
| 100 | 80 | 53 | 64 | 1 | 3 |
| 10,000 | 8,000 | 5,333 | 64 | 1 | 3 |
| 100,000 | 80,000 | 53,333 | 64 | 1 | 3 |

At 10,000 executions, AllTime total was **9223374898843379844** and selected-day total
**18446742654701937680**, both exact and beyond Long.MAX_VALUE. Room's pinned period
fixture separately includes two Long.MIN_VALUE and two Long.MAX_VALUE on dedicated days,
so both overflow directions are required on device. Domain tests execute both directions,
exact rational averages/medians and cancellation; no approximate comparison is used.

Representative captured AllTime plans (both kinds have the same shape):

```text
activity count:
  SEARCH executions USING INDEX activity_executions_series_completed (statistics_series_id=?)
activity bits:
  CO-ROUTINE bits / SETUP / SCAN CONSTANT ROW / RECURSIVE STEP / SCAN bits
  MATERIALIZE summary
  SEARCH executions USING INDEX activity_executions_series_completed (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_activity_execution_field_values_1 (activity_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_activity_snapshot_fields_1 (id=?)
  SCAN bits / SCAN summary
  CORRELATED SCALAR SUBQUERY 5
  SEARCH executions USING INDEX activity_executions_series_completed (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_activity_execution_field_values_1 (activity_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_activity_snapshot_fields_1 (id=?)
  USE TEMP B-TREE FOR ORDER BY
activity median:
  CO-ROUTINE median_values
  SEARCH executions USING INDEX activity_executions_series_completed (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_activity_execution_field_values_1 (activity_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_activity_snapshot_fields_1 (id=?)
  USE TEMP B-TREE FOR ORDER BY / SCAN median_values / SCALAR SUBQUERY 3
  SEARCH executions USING INDEX activity_executions_series_completed (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_activity_execution_field_values_1 (activity_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_activity_snapshot_fields_1 (id=?)
sequence count:
  SEARCH executions USING INDEX sequence_executions_series_ended (statistics_series_id=?)
sequence bits:
  CO-ROUTINE bits / SETUP / SCAN CONSTANT ROW / RECURSIVE STEP / SCAN bits
  MATERIALIZE summary
  SEARCH executions USING INDEX sequence_executions_series_ended (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_sequence_execution_field_values_1 (sequence_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_sequence_snapshot_fields_1 (id=?)
  SCAN bits / SCAN summary / CORRELATED SCALAR SUBQUERY 5
  SEARCH executions USING INDEX sequence_executions_series_ended (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_sequence_execution_field_values_1 (sequence_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_sequence_snapshot_fields_1 (id=?)
  USE TEMP B-TREE FOR ORDER BY
sequence median:
  CO-ROUTINE median_values
  SEARCH executions USING INDEX sequence_executions_series_ended (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_sequence_execution_field_values_1 (sequence_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_sequence_snapshot_fields_1 (id=?)
  USE TEMP B-TREE FOR ORDER BY / SCAN median_values / SCALAR SUBQUERY 3
  SEARCH executions USING INDEX sequence_executions_series_ended (statistics_series_id=?)
  SEARCH recorded_values USING INDEX sqlite_autoindex_sequence_execution_field_values_1 (sequence_execution_id=?)
  SEARCH fields USING INDEX sqlite_autoindex_sequence_snapshot_fields_1 (id=?)
```

Host plans establish traversal/index shape on that SQLite version. Controller's API-35
attempt 1 executed the StatisticsRepository tests: 20 passed and one eligibility fixture
failed before its period assertions, as detailed below. The passing cases include the
Number growing-history, malformed-storage and physical-reopen/deletion tests. The repaired
case, API-26 and the remaining compatibility matrix still require Controller verification.
No device task was launched by Codex. No latency, heap or CursorWindow refill measurements
are claimed. The existing S1/S1C1 inventory is retained; four added Number cases make the
data inventory 613 (app 323, startup 2 unchanged).

### Controller API-35 attempt 1: period fixture repair

The original Controller XML at
`D:/controller/.runtime/check-runs/wf-f27725cff1ae58f9a8286bac0a2b5d70/S2/attempt-1/reports/data/TEST-pixel2Api35-_data-.xml`
records the sole failing StatisticsRepository case as
`boundedNumberReadersPreserveSignedEligibilityAndPeriods`.
The exception is `Primary local date must derive from the original Sequence timezone`,
raised by SequenceExecutionValidator during fixture insertion, before Number aggregation.
The multi-date extreme-value loop selected August 21 and August 24 but inherited the
helper's August 20 start Instant. Both dates now explicitly use 10:00 UTC on their selected
day for the Sequence start and the No-live Activity completion. All four Sequence roots
and four Activity facts in that loop therefore preserve coherent original-zone dates.
The expected values, periods, missing counts and individual/Series Detail comparisons
remain unchanged. Inspection of every completedSequence call in this test file found
that the other non-August-20 call already supplies its matching start Instant.

After the correction, this focused command passed on 2026-10-18, rebuilding the changed
AndroidTest source and APK (Detekt was UP-TO-DATE):

```powershell
.\gradlew.bat :data:ktlintCheck :data:detekt :data:assembleDebugAndroidTest --continue --console=plain
```

`git diff --check` passed. No mandatory testcase was removed or renamed. The supplied
failure is retained as pre-repair execution evidence; compilation does not establish a
post-repair instrumentation pass. Controller must rerun the device verification.
