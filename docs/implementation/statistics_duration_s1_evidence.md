# S1: exact duration traversal evidence

Scope: canonical Activity Series duration and Sequence Series active-duration distributions,
including existing Series Detail callers. Number/Category Field paths are unchanged.
No schema/index migration, cache, identity or historical attribution change.

## Baseline captured before production replacement

Room 2.8.4 generated `StatisticsDao_Impl` uses `performBlocking`, prepares the ordered
SELECT, then appends every `getLong(0)` to `MutableList<Long>`. The domain calculator
calls `sorted()`, retaining another complete reference list. SQL already orders samples.
Both canonical readers issue three SELECTs: catalog/Series validation, aggregate, durations.
Transaction-control statements are separate. Series Detail reuses these readers and
resolves Fields; Number Field retention remains deferred.

Controlled fixture: 3,000 completed timed rows per Series, each 1,000 ms; 2,000 have
primary date 2026-08-20, 1,000 have 2026-07-01. All are nondeleted and non-null in the
isolated probe. The existing mandatory `boundedLargeHistoryUsesFreshAggregateQueries`
fixture now covers both kinds, narrow and All time, without changing its test identity.

| Path / period | Matching executions | Non-null samples / SQL returned rows | Old DAO List size | New retained raw samples |
| --- | ---: | ---: | ---: | --- |
| Activity / Aug 20 | 2,000 | 2,000 | 2,000 | fixed scalar slots |
| Activity / All time | 3,000 | 3,000 | 3,000 | fixed scalar slots |
| Sequence / Aug 20 | 2,000 | 2,000 | 2,000 | fixed scalar slots |
| Sequence / All time | 3,000 | 3,000 | 3,000 | fixed scalar slots |

Expected totals are 2,000,000 / 3,000,000 ms; average, median, min and max are exactly
1,000 ms. Mixed eligibility tests separately establish that No-live increases execution
count without adding a sample.

## Reproduction and observed query plans

Run `python docs/implementation/statistics_duration_probe.py`. It reads actual DAO SQL
and creates the two duration tables and every existing index from the v10 Room export.
Foreign keys/lifecycle triggers are disabled in this isolated query-plan fixture; it is
not a production lifecycle test. It compares List/copy/sort with incremental scalar
consumption using exact host arithmetic. This is SQL/fixture evidence, not Kotlin execution.
The baseline adapter was inspected and all four plans executed before replacing production.

Observed on local host SQLite **3.53.1**, without ANALYZE, for both periods:

```text
SEARCH activity_executions USING INDEX activity_executions_series_completed (statistics_series_id=?)
USE TEMP B-TREE FOR ORDER BY
SEARCH sequence_executions USING INDEX sequence_executions_series_ended (statistics_series_id=?)
USE TEMP B-TREE FOR ORDER BY
```

SQL text/bindings are unchanged, so the plans apply to both adapters. Relevant indexes
are `(statistics_series_id, completed_at_ms)` and `(statistics_series_id, ended_at_ms)`.
Neither orders by duration. Other existing date indexes are Activity
`(primary_local_date, deleted_at_ms)` and Sequence `(primary_local_date)`. v10 also has
Activity `(context_type, status, deleted_at_ms, primary_local_date)` and Sequence
`(status, primary_local_date)`. The optional-period OR does not supply a date range to
the selected Series index in these plans. No context restriction is introduced.
Android planners may differ: capture the same four `EXPLAIN QUERY PLAN` statements
through `database.openHelper.readableDatabase.query` on the Controller device before
claiming device-specific plans.

For S rows in the selected Series and M eligible samples, the observed plan examines
O(S) rows and sorts O(M log M) with SQLite-owned temporary storage per native execution.
CursorWindow refills can re-execute scanning/sorting; this is not a guarantee of one
native scan for arbitrarily large results. The repository adds no pagination or
per-execution SQL, and device costs remain measurable rather than assumed.
The aggregate is a separate scan with existing grouping/distinct-date work. Narrow periods do not promise
an indexed date seek. No indispensable index change is needed for bounded application
retention. This slice explicitly allows SQLite sorting.

## Incremental API and resource ownership

`orderedDurations(expectedCount, Iterator<Long>)` stores count, BigInteger total,
endpoints and two middle values; it neither copies nor sorts. Average and midpoint use
BigInteger rational arithmetic before the existing Long-millisecond total overflow guard.
The count bounds prevent increment overflow. Negative counts/values, descending input
and count disagreement reject, including expected zero with a nonempty source. Expected
count comes from the independent aggregate query, never Cursor.count; no aggregate row
means expected zero.

[Room Query documentation](https://developer.android.com/reference/androidx/room/Query)
permits Cursor results. Inspected regenerated 2.8.4 `StatisticsDao_Impl.kt` after
`:data:compileDebugKotlin`: both duration adapters bind a `RoomSQLiteQuery`, call
`__db.query(_statement)` and return the Cursor directly, without a row loop/List/copy.
Also inspected `androidMain/androidx/room/RoomDatabase.android.kt` in the official
[2.8.4 runtime sources](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.8.4/room-runtime-android-2.8.4-sources.jar):
`query(SupportSQLiteQuery, CancellationSignal?)` delegates directly to
`openHelper.writableDatabase.query`. This route has no full-result adapter or
`DBUtil` copy. It uses the existing SupportSQLiteOpenHelper configuration, not a new
SQLiteDriver. Recheck this route when updating Room. Android SQLiteCursor buffers
CursorWindows; window refills and SQLite sorting are distinct from
retaining every raw sample in the application. SQL SELECT count is fixed, but native
refill work still grows. The promise is O(1) retained application sample state, not O(1)
total allocation, SQLite memory, I/O or elapsed time. BigInteger scalar width is bounded
by Long sample/count limits; the number of retained scalar objects is fixed.

Both paths retain `database.runInTransaction` around validation, aggregate and traversal.
`readDurationDistribution` owns the Cursor with `use`, including validation, overflow,
read and cancellation exceptions. No Cursor escapes; there are no writes. Synchronous
readers do not add coroutine cancellation polling; propagated cancellation exceptions
close resources. Production invocation remains under Dispatchers.IO.

## Verification and Controller measurement handoff

The local host probe passed all four cases. It also checks independent COUNT queries,
exact fixture expectations and one traced duration SELECT per adapter. Generated DAO
and runtime-source inspection confirmed incremental Cursor results.

Final focused local verification passed on 2026-10-18:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :domain:detekt :data:detekt :domain:ktlintCheck :data:ktlintCheck :data:assembleDebugAndroidTest --console=plain --continue
```

Gradle reported BUILD SUCCESSFUL. Passing JVM XML contains 239 domain tests (including
10 StatisticsModelsTest cases) and 19 data tests, with zero failures, errors or skips;
unchanged passing test tasks were up-to-date in the final invocation. Data AndroidTest
sources/APK compiled and packaged successfully. Both modules passed Detekt and ktlint.
The StatisticsRepository source/inventory identities match all 17 cases, bringing the
mandatory data inventory to 609. A source audit also verified that both duration SQL
statements and both aggregate SQL statements are identical to the baseline revision.
`git diff --check` passed. Initial iterator-contract and formatting findings were fixed
without disabling assertions or suppressing static-analysis rules.

Domain tests were added first. Pre-implementation
`:domain:test --tests '*StatisticsModelsTest'` failed compilation on the missing API.
Tests compare independent exact expectations and the unordered reference and include a
generated 100,000-sample iterator. A pair with midpoint sum above Long.MAX_VALUE also
exceeds the nonnegative total limit, so it must throw ArithmeticException rather than
returning that midpoint.

Mandatory Room additions cover mixed eligibility, odd/even medians, both Detail callers,
empty Series, real Cursor closure on cancellation/count failure and physical production
close/reopen followed by deletion. Existing child deletion/structural removal suites
remain mandatory. WAITING_NEXT is active_session policy with a RUNNING root, not a
SequenceExecution status; the unchanged terminal whitelist excludes these roots.
The large-history test compares one versus 3,000 rows, both readers and both periods,
asserting three application SELECTs (excluding asynchronous Room invalidation-log
maintenance) and explicit count/total/average/median/endpoints.

Device execution is Controller-owned and was not launched. No Android timing, heap
measurement or instrumentation pass is claimed. For actual baseline/comparison timing,
use the same disposable physical DB fixture on the same API/device: record matching
count and COUNT(active_duration_ms), QueryCallback SELECT count and all four plans.
Measure each canonical read after identical warm-up (e.g. five warm reads, twenty
recorded reads; report raw values and median, no latency threshold). Compare the parent
revision List reader and S1 using identical seed data, recording cold-open separately.
Profile allocations/live heap separately from CursorWindow/native SQLite storage.
Record API, device build, SQLite version, cardinality, period, cache/warm state and
instrumentation overhead. Larger optional histories belong outside mandatory API runs.
Do not log raw sample Lists in comparison reads. Local check results are in the S1
completion; AndroidTest compilation is not device execution evidence.
