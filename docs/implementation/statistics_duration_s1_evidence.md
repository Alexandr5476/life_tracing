# S1C1: exact bounded-result duration statistics

This document supersedes S1's full ordered-Cursor algorithm and its performance claims.
Scope is Activity Series duration and Sequence Series active duration, including their
canonical Series Detail callers. Number/Category Fields, Global aggregation, identities,
periods, schema v10 and existing indexes are unchanged.

## Why S1 needed correction

The pre-S1 Room adapter retained M duration values in a List and the domain calculator
copied/sorted them. S1 removed these Lists but exposed all M ordered rows through
SQLiteCursor. One DAO invocation did not imply one native scan/sort. AOSP
[SQLiteCursor.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/database/sqlite/SQLiteCursor.java)
shows onMove/getCount/fillWindow requesting new fills as positions leave the window.
The Android Developers article
[Large Database Queries on Android](https://medium.com/androiddevelopers/large-database-queries-on-android-cb043ae626e8)
explains why those fills restart work and skip earlier results. A 3,000-row fixture or
QueryCallback call count could not establish S1's behavior across multiple windows.
S1's warning about refills was therefore insufficient to prove nonquadratic native work.

## Production algorithm

All reads remain inside the same existing Room runInTransaction boundary:

1. Validate Series through the existing catalog reader.
2. Read the existing compact aggregate, extended with MIN/MAX(active_duration_ms) and an
   invalid-duration count. Execution-count eligibility is unchanged. All three projections
   (Activity, Sequence and oneOffAggregate) include the new columns.
3. Execute one bounded order-statistic query. Its eligible CTE uses exactly the original
   duration predicates. An independently evaluated COUNT(*) counts all eligible samples.
   A second CTE orders by duration with LIMIT 1 for odd counts, LIMIT 2 for even counts,
   LIMIT 0 for empty counts, and one OFFSET at (count - 1) / 2. A scalar aggregate over
   those median values returns selected_count and the endpoint MIN/MAX. This always
   exposes exactly one row containing four scalars to Room, even for empty input.
4. Compare the independent sample count with the prior aggregate's count (zero if absent),
   verify selected_count and endpoints, then construct the existing DurationDistribution
   through the pure summary overload of StatisticsDistributionCalculator.durations.

The LIMIT is bounded in SQL for every supplied count, including invalid nonpositive
counts. There is no caller-controlled unbounded limit, page loop or full duration Cursor.
A missing aggregate is not trusted as proof of emptiness: selection still counts the
eligible set and rejects disagreement. Positive counts require the correct number of
non-null ordered endpoints. Empty distributions require zero total and absent bounds.
The domain boundary checks nonnegative bounds, endpoint order/range, parity, total bounds
and exact small-distribution consistency. The obsolete orderedDurations API is removed;
the small unordered collection calculator remains a reference and existing consumer API.

## Exact arithmetic and persistence validation

Median endpoints are added as BigInteger before reducing the rational; average uses
BigInteger total/count. No floating-point AVG, REAL conversion, percentile approximation
or truncated midpoint is used. The domain retains the existing Long-millisecond total
overflow rejection, including Long.MAX_VALUE boundary and positive-overflow tests.

[SQLite SUM semantics](https://sqlite.org/lang_aggfunc.html#sumunc) preserve integer totals
when all non-null inputs are integers and throw on integral overflow. Because valid
cached durations are nonnegative integers, a total within Long never has an overflowing
intermediate sum; a larger total fails. SQL also projects COUNT of non-null samples whose
storage type is not integer or whose value is negative. Aggregate-row construction rejects
any such input, even if an invalid REAL caused SUM to use approximate arithmetic internally.
Thus a malformed cache cannot produce a successful rounded distribution. This reasoning
is duration-specific and does not apply to signed Number Fields. Repository overflow still
fails at SQLite's aggregate boundary, as it did before S1; no overflow is narrowed or wrapped.

Min/max ignore missing durations, so all-No-live Activity Series retain execution counts
with an empty duration distribution. Standalone and eligible Sequence children share the
same Activity predicate; logical deletion and structural removal stay excluded. Sequence
uses COMPLETED/ENDED_EARLY only. Persisted primary_local_date selects periods; no current
zone conversion or durable fact/source/identity mutation occurs.

## Work and retained-memory bounds

Let S be rows in the selected Series and M be eligible non-null durations. Existing Series
indexes are Activity (statistics_series_id, completed_at_ms) and Sequence
(statistics_series_id, ended_at_ms). Date indexes remain Activity
(primary_local_date, deleted_at_ms), Sequence (primary_local_date), plus v10's Activity
(context_type, status, deleted_at_ms, primary_local_date) and Sequence
(status, primary_local_date). None orders by active duration. No index migration is added.

The compact aggregate retains its existing optional Series filter and DISTINCT-day
work. Its observed indexes can examine A candidate rows across other Series; no O(S)
Series seek is promised for that unchanged aggregate. Its fixed scan/group/distinct work
is bounded conservatively by O(A log A). The duration selection's uncorrelated COUNT and
median CTE perform a fixed number of scans, and
at most a fixed number of sorts, bounded by O(S + M log M). An ordinary CTE may be
materialized or inlined depending on SQLite version. Both choices use a fixed number
of consumers: there is no per-row correlated subquery or per-page loop. OFFSET is applied
once within the SQL rank selection, not repeatedly for increasingly distant pages.
SQLite may keep O(M) temporary rank/sort data; this is explicitly database storage.

| Quantity | Pre-S1 List / S1 full Cursor | S1C1 |
| --- | --- | --- |
| Duration DAO calls | aggregate + full values | aggregate + bounded selection |
| Duration rows returned | aggregate row + M samples | aggregate row + one scalar row |
| Application raw-sample retention | O(M) / O(1) | O(1), at most two median endpoints |
| Native duration work | fixed List adapter scan / potentially repeated CursorWindow rescans | fixed scans/sorts, no result-window progression |
| Canonical application SELECT/WITH count | three | three, independent of history size |

The existing Series catalog is separate from these duration-query cardinality bounds.
The four-small-integer selection row cannot overflow a result window because history
size does not change its byte size. Room maps that compact result and owns/closes its
statement; no Cursor escapes into repository code. There is no reliance on configuring
CursorWindow size or API-28-only window/forward-only controls. Ordinary WITH, aggregates,
CASE and LIMIT/OFFSET are supported on the API-26 SQLite stack; no window functions or
AS MATERIALIZED syntax is required. All components share the original transaction and
return unchanged public Statistics models. Dispatchers.IO production wiring is unchanged.

## Reproduction and query plans

Run from the repository:

```powershell
python docs/implementation/statistics_duration_probe.py
python docs/implementation/statistics_duration_probe.py --rows 200003 --varied
```

The probe uses the actual DAO SQL and checked-in v10 Room schema/index export, including
statistics_series for the aggregate joins. Foreign-key/lifecycle validation is disabled
in this isolated host query-plan fixture; physical Room lifecycle tests remain separate.
It runs narrow Aug 20 and All time for both kinds, traces exactly two duration statements,
compares every result with an independent full-list host reference, checks returned row
and selected-endpoint counts, and prints EXPLAIN QUERY PLAN for eligible predicates,
aggregate and median selection. It also checks independent-count behavior with a zero
expected count against nonempty history, and integral SUM boundary/overflow behavior.
The optional larger varied fixture exercises odd counts, duplicates and zero; it is not
added as an expensive mandatory five-API latency test. Baseline host Lists are reference
fixtures, not part of the production algorithm or a measurement of Android heap.

For the default fixture each kind has 3,000 completed duration samples (2,000 narrow),
each 1,000 ms. Expected totals are 3,000,000 and 2,000,000 ms, and average/median/min/max
are 1,000 ms. Both periods expose one selection row, selected_count=2. The larger odd
fixture exposes the same one selection row with selected_count=1.

Observed on host SQLite 3.53.1 without ANALYZE, for both kinds and both periods:

```text
Eligible Activity: SEARCH ... USING INDEX activity_executions_series_completed (statistics_series_id=?)
Eligible Sequence: SEARCH ... USING INDEX sequence_executions_series_ended (statistics_series_id=?)
Median: CO-ROUTINE median_values
        SEARCH ... USING INDEX <same Series index> (statistics_series_id=?)
        USE TEMP B-TREE FOR ORDER BY
        SCAN median_values
        SCALAR SUBQUERY 3
        SEARCH ... USING INDEX <same Series index> (statistics_series_id=?)
```

There is no correlated scalar subquery. The aggregate plans use
activity_executions_deleted_at or sequence_executions_status_primary_date, join
statistics_series through its primary-key index, and use temporary B-trees for GROUP BY
and COUNT(DISTINCT). These are fixed aggregate costs, not costs multiplied by result
windows. The optional date predicate does not become a range on the selected Series
index. Android SQLite can choose materialization or different indexes: rerun the probe's
printed queries/plans through Room on the Controller device before asserting device plans.

Host reference comparisons passed for 3,000 rows per kind and 200,003 varied rows per
kind, including narrow counts 2,000 and 133,335. Both larger periods selected one median
value, returned one selection row, and matched exact full-list expectations. Integral SUM
returned Long.MAX_VALUE at the boundary and rejected Long.MAX_VALUE + 1. No Android/native
elapsed-time or heap measurements are inferred from these host runs.

## Tests and compatibility

Domain summary tests were introduced first and the focused pre-implementation test
command failed compilation on the absent overload. They preserve small-fixture reference
comparisons and independent fractional/boundary expectations, test negative values,
missing/invalid endpoints, inconsistent totals/counts and BigInteger overflow rejection.

The mandatory 3,000-row Room fixture checks both canonical readers, fixed logical query
count and the bounded selection projection. Existing S1 eligibility, Detail, child deletion,
structural removal, physical close/reopen and subsequent deletion regressions remain.
All-No-live, overview/summary and one-off projections now have explicit coverage. Persisted
negative and REAL cached durations reject at the aggregate mapping boundary.

The obsolete S1 Cursor cleanup identity is intentionally replaced by
`durationSelectionRejectsCountMismatchAndInvalidCachedValues`, testing the new consistency
boundary. The other two S1 additions are preserved. Inventory and compatibility guide
record the replacement; the mandatory data count remains 609.

Device tests, five-API acceptance, Android timings and heap/refill measurements remain
Controller-owned and were not launched locally. For device evidence, use identical
seeded disposable databases on the same device/API for the S1 and S1C1 revisions. Capture
SQLite version/plans, cardinalities, DAO traces and native SQLite/Perfetto evidence
separately; DAO invocation counts alone do not measure native re-execution. Record cold
reads separately from identical warm-up/repeated reads and report raw elapsed values,
without a latency threshold. The S1C1 bounded row shape provides the structural proof
independently of environment-sensitive timing. AndroidTest compilation is not device
execution evidence.

## Focused local verification

The domain and data JVM suites, both module Detekt tasks and AndroidTest APK assembly
passed during implementation. The full focused command, including ktlint, is:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :domain:detekt :data:detekt :domain:ktlintCheck :data:ktlintCheck :data:assembleDebugAndroidTest --console=plain --continue
```

Initial formatting findings were corrected without suppressions. Final command outcomes
are reported in the completion; successful source/APK compilation is not an instrumentation
execution claim. The independent five-API and GitHub gates remain with the Controller.
