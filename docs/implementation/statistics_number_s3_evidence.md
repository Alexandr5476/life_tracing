# S3: bounded exact Number Fields in Series Detail

The canonical Activity and Sequence Series Detail readers now return grouped Number
summaries. There are no production Number sample DAO methods, sample-row types, sample
Lists or Kotlin sample groupBy operations. Individual S2 readers retain their SQL and
arithmetic. No schema, index, migration, durable cache or application-layer change.

## Arithmetic and result bounds

Each grouped SQL projection contains COUNT, storage-type validation, MIN/MAX and exactly
64 fixed COUNT(CASE WHEN ((number_scaled >> bit) & 1) = 1 THEN 1 END) accumulators. The
64 decimal counts are concatenated with commas into one bounded scalar column. This is
serialization of fixed-width aggregates, not GROUP_CONCAT of samples. No SUM of values,
TOTAL, REAL AVG, floating arithmetic or 64N-row cross product is used.

COUNT is nonnegative and at most Long.MAX_VALUE, so its decimal representation has at
most 19 characters. A row's 64 counts and 63 separators occupy at most 1,279 characters.
The projection has one row per populated stable source Field: P <= F rows and 64P scalar
counts. Kotlin rejects oversized/missing/noninteger counts, invalid storage, duplicate or
unknown Field identities, nonpositive grouped counts and contradictory summaries.
ExactNumberSummary.readGrouped converts one row at a time into the same 64 bit rows
validated by S2 ExactNumberSummary.read. ExactSignedLongSum reconstructs:

```text
total = sum(count[bit] * 2^bit, bit = 0..62) - count[63] * 2^63
```

Totals remain BigInteger; average and odd/even median remain ExactValue rationals.
Long.MIN_VALUE, Long.MAX_VALUE, mixed signs and zero retain their original scale.
Median addition uses the existing BigInteger calculator. The median query independently
counts eligible samples and returns one scalar row with zero/one/two selected endpoints;
the shared S2 validator rejects count, endpoint and range disagreement.

Only populated Fields require median SQL. Catalog Number Fields absent from the summary
receive the validated canonical empty distribution, zero recorded values and the complete
containing Series execution denominator. Missing values are never zero-valued samples.
At peak, application Number aggregation retains O(64F) bounded scalars/text, O(F) exact
summaries/descriptors and at most two transient median endpoints. It never retains O(N)
recorded values. The test/probe reference collections deliberately retain fixture values
as independent oracles; these collections are not production read paths.

## SQL work and median trade-off

Let E be candidate executions in the requested Series, N its eligible recorded Number
samples, F distinct result Number Fields, and P populated Number Fields (P <= F). The
summary searches the existing Series index, joins execution values by their execution
primary-key prefix, joins snapshot Fields by primary key, and groups by source Field ID.
The period predicate uses persisted primary_local_date; it is a residual predicate, not
a new date index. Status, Activity soft deletion and nonnull source/value predicates are
identical to the former combined readers. Both standalone and Sequence-child Activities
remain eligible; No-live Activities contribute Fields without duration samples.

One summary traversal evaluates 64 fixed COUNT expressions per eligible Number value.
SQLite uses a temporary B-tree for GROUP BY, with up to N input rows, not 64N rows. Its
conservative work is O(N log N + 64N), plus Series/Field join candidates and index lookups.
The execution-value join also inspects non-Number or missing values in those executions;
if V is that total candidate value count, include O(E + V) lookup/visit work. With a fixed
whole Field schema V is O(E); growing result schemas can increase V. No constant database
work or constant native storage claim follows from the bounded output.

Series Detail uses one bounded median selection per populated Field. CROSS JOIN fixes
Field -> value -> execution loop order. Existing source_field_id and snapshot_field_id
indexes partition these traversals by stable Field identity; execution eligibility is
then checked through the execution primary key. This avoids the old S2 selection's broad
Series-first traversal once per Field. The independent count and sort perform at most
two traversals of each Field lineage, or one materialization on SQLite versions choosing
to materialize the multiply referenced eligible CTE. The sort is O(n_f log n_f), and the
single LIMIT/OFFSET selects the middle one/two values; there is no pagination loop.

Indexing by source Field also visits snapshots/values outside the requested period or
Series after a split. Let H be all snapshot Field rows for requested populated identities,
and K their stored value rows across those lineages (including ineligible history). This
additional work is O(H + K) visits plus indexed execution lookups, rather than O(FN): each
snapshot/value belongs to exactly one stable source Field and is visited a fixed number
of times across all median selections. A narrow period can therefore do more work than
its N suggests, but increasing F does not repeatedly scan the same broad Series subset.
In a single-Series fixture K is O(EF) at worst and normally tracks stored values; all
median sorts together cost sum(n_f log n_f) <= N log N. Grouping and sorting may use native
history-sized temporary storage. Android latency, heap and CursorWindow refill behavior
are not inferred from host plans or query counts.

A compact batch median based on window functions cannot assume API 26 support. A
correlated rank/count calculation can be quadratic. Fixed-width per-Field LIMIT clauses
cannot use each outer group's count as a correlated LIMIT/OFFSET on the minimum SQLite
version. Dynamically unioning F queries would still repeat those indexed traversals and
add bind/statement-size batching complexity. The chosen O(P) DAO calls expose that cost
without extra aggregation, unbounded IDs or raw cursor traversal. All calls have a fixed
maximum of five named inputs (nine positional bindings in generated Room code),
far below API 26's 999-variable limit; there is no ID fan-out boundary.

Number-specific logical queries are 1 + P when any Number Fields exist, zero otherwise.
The preexisting Series/duration/catalog and grouped Category queries remain fixed in
execution/sample count. No complete individual repository reader is called per Field.
The two original mixed-field batching tests retain their semantic assertions and now
assert that the change in history reads equals the change in populated Number Fields,
with an explicit fixed overhead bound. This replaces only their obsolete field-count-
independent complexity assertion under S3's permission for result-dimensional scaling.

## Coherence, identity and production wiring

The complete existing seriesDetail transaction still encloses Series and duration reads,
Field catalog resolution, grouped Number summaries, every median, Category metadata and
counts. No new transaction or read occurs outside it, and no read mutates durable state.
Failures propagate without writing a partial result or cache. This preserves one Room
persisted view through correction, deletion or Series evolution.

The canonical catalog remains the output schema and ordering authority. Equal labels
never merge source identities. Current display names, units, precision, archived Fields,
snapshots and Series split boundaries remain governed by the existing metadata queries.
Category metadata/count queries are unchanged and still batched per Series; unrecorded
options and snapshot fallback identities remain visible. Text descriptors are unchanged.

LifeTracingApplication still injects statisticsRepository.seriesDetail through
withContext(Dispatchers.IO) into StatisticsSeriesDetailController. Route session ownership,
loading/failure, period changes, stale-result suppression and disposal code is unchanged.
No synchronous main-thread production query was introduced.

## Reproducible evidence

The probe extracts the actual Room annotation SQL, creates the checked-in v10 tables and
indexes and enables foreign keys. The Room JSON export uses unconditional stand-ins for
partial Main Value indexes; the probe restores their production WHERE is_main_value = 1
predicate so multiple Fields can exist, matching the manual DDL used by the production
builder. No queried access index is added or changed. The probe checks exact Python
integer/Fraction references, empty periods, fixed summaries, selected endpoints, malformed
storage, no production sample-row reader and query counts. Its own fixture/reference
memory is not an application reader measurement.

```powershell
python docs/implementation/statistics_number_s3_probe.py --rows 100 --fields 4
python docs/implementation/statistics_number_s3_probe.py --rows 10000 --fields 4
python docs/implementation/statistics_number_s3_probe.py --rows 100000 --fields 4
python docs/implementation/statistics_number_s3_probe.py --rows 128 --fields 1
python docs/implementation/statistics_number_s3_probe.py --rows 128 --fields 16
python docs/implementation/statistics_number_s3_probe.py --rows 128 --fields 64
python docs/implementation/statistics_number_probe.py --rows 10000
```

On host SQLite 3.53.1, both kinds passed all these workloads for AllTime, Day and an empty
period. Results before the final probe-only addition of period-specific plan printing:

| Executions/kind | Populated Fields | AllTime samples | Summary rows | Median rows | Number queries |
| --- | --- | --- | --- | --- | --- |
| 100 | 4 | 320 | 4 | 4 | 5 |
| 10,000 | 4 | 32,000 | 4 | 4 | 5 |
| 100,000 | 4 | 320,000 | 4 | 4 | 5 |
| 128 | 1 | 102 | 1 | 1 | 2 |
| 128 | 16 | 1,638 | 16 | 16 | 17 |
| 128 | 64 | 6,553 | 64 | 64 | 65 |

Every fixture also configures one empty Number Field. Empty periods return zero grouped
rows and no median query, while the repository keeps all catalog dimensions and missing
coverage. The sample counts in the Field-scaling rows follow the explicit sparse fixture.

Captured AllTime plans (Day has the same index paths; the final probe prints both):

```text
Activity grouped:
 SEARCH executions USING INDEX activity_executions_series_completed (statistics_series_id=?)
 SEARCH recorded_values USING INDEX sqlite_autoindex_activity_execution_field_values_1 (activity_execution_id=?)
 SEARCH fields USING INDEX sqlite_autoindex_activity_snapshot_fields_1 (id=?)
 USE TEMP B-TREE FOR GROUP BY
Activity median:
 CO-ROUTINE median_values
 SEARCH fields USING INDEX activity_snapshot_fields_source_field_id (source_field_id=?)
 SEARCH recorded_values USING INDEX activity_execution_values_snapshot_field_id (snapshot_field_id=?)
 SEARCH executions USING INDEX sqlite_autoindex_activity_executions_1 (id=?)
 USE TEMP B-TREE FOR ORDER BY
 SCAN median_values
 SCALAR SUBQUERY 3
 SEARCH fields USING INDEX activity_snapshot_fields_source_field_id (source_field_id=?)
 SEARCH recorded_values USING INDEX activity_execution_values_snapshot_field_id (snapshot_field_id=?)
 SEARCH executions USING INDEX sqlite_autoindex_activity_executions_1 (id=?)
Sequence grouped:
 SEARCH executions USING INDEX sequence_executions_series_ended (statistics_series_id=?)
 SEARCH recorded_values USING INDEX sqlite_autoindex_sequence_execution_field_values_1 (sequence_execution_id=?)
 SEARCH fields USING INDEX sqlite_autoindex_sequence_snapshot_fields_1 (id=?)
 USE TEMP B-TREE FOR GROUP BY
Sequence median:
 CO-ROUTINE median_values
 SEARCH fields USING INDEX sequence_snapshot_fields_source_field_id (source_field_id=?)
 SEARCH recorded_values USING INDEX sequence_execution_values_snapshot_field_id (snapshot_field_id=?)
 SEARCH executions USING INDEX sqlite_autoindex_sequence_executions_1 (id=?)
 USE TEMP B-TREE FOR ORDER BY
 SCAN median_values
 SCALAR SUBQUERY 3
 SEARCH fields USING INDEX sequence_snapshot_fields_source_field_id (source_field_id=?)
 SEARCH recorded_values USING INDEX sequence_execution_values_snapshot_field_id (snapshot_field_id=?)
 SEARCH executions USING INDEX sqlite_autoindex_sequence_executions_1 (id=?)
```

## Regression and local verification boundary

Fail-first: the integration SQL-shape test was added before production changes. Its host
companion evaluated the former production annotation SQL on the v10 multi-Field fixture
(128 executions, four populated Fields) and failed with `unbounded raw Number rows:
409 > F=4`. This is a result-cardinality oracle, not a timing threshold. The initial host
fixture first exposed the JSON export's Main Value stand-in and was corrected to the
production partial predicate before reaching this intended failure. Instrumentation
fail-first/corrected execution remains Controller-owned; no device task was launched.

Both mixed-field integration fixtures now compare every Number/Category result with the
canonical individual reader. They retain source/snapshot fallback option identities and
add equal-label distinct Fields, overflow in both directions, exact extreme midpoints,
empty Number/Category dimensions, Text descriptors, archived sources and catalog order.
Existing S2 tests retain eligibility, period, nonterminal/deleted/other-Series exclusion,
No-live facts, sparse history, exact odd/even medians and archive/split behavior. Empty
periods and malformed storage now explicitly exercise Series Detail as well.

Two new mandatory Room cases reject the raw query and verify fixed-schema growth at
1/32/512 executions for both kinds, scalar row bounds and sample-independent query counts.
The existing physical Number reopen/deletion case now reads complete mixed Number/Category
Series Detail before/after reopen, deletes a standalone fact through the supported DAO
boundary and verifies fresh Number/coverage/Category recalculation plus unaffected Sequence
output. JVM tests exercise shared grouped projection validation and exact reference parity.
The pinned inventory now requires 615 data, 323 app and two startup cases at each of the
same five APIs; no mandatory case was removed or renamed.

The initial local Gradle command executed all 23 data JVM tests successfully, passed
ktlint and data lint, and compiled/packaged the data AndroidTest APK. Its only failures
were Detekt's DAO length and unnamed scalar-width constant; this diff addresses both.
The domain task in that invocation was UP-TO-DATE (existing 241-test report, not a fresh
execution). All 17 instrumentation-evidence script unit tests passed; their mocked
Gradle output is not device execution. Corrected focused verification and its exact exit
status are reported in the implementation completion alongside this reproducible command:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :domain:ktlintCheck :data:ktlintCheck :domain:detekt :data:detekt :data:lintDebug :data:assembleDebugAndroidTest --continue --console=plain
```

Controller owns `ci-fast`, `androidtest-apks`, all five API jobs, independent startup and
GitHub exact-head verification. No Android timing, device pass, full CI parity or
parent-wide concurrency/performance closure is claimed here. Source changes remain
uncommitted for Controller acceptance.

## Controller API-35 attempt 1: overnight Plan execution fixture repair

Controller's original API-35 XML under
`D:/controller/.runtime/check-runs/wf-f27725cff1ae58f9a8286bac0a2b5d70/S3-fdeae4858095410f8dc6341677e7c15b/attempt-1/reports/`
records 620 data cases (615 mandatory plus five skipped fixtures), with no failures;
all 23 StatisticsRepository cases passed. Of 323 app cases, the sole failure was
`PlanExecutionPersistenceTest.productionPlanTimeEntryDispatchPersistsLiveAndHistoryForCanonicalReaders`.

The test ran at approximately 00:07 UTC on October 19. Its live start was backdated
15 minutes into October 18, but its Daily active assertion selected that start date
while passing an October 19 query timestamp. Daily intentionally exposes active runtime
only on Today derived from the query timestamp and effective zone. The null active
projection was therefore correct; the fixture's cast was incorrect. The existing data
case `overnightActivityProjectsOnTodayNotItsStartDate` independently pins this policy.

The repaired assertion derives selected date and query timestamp from the same captured
observation, and repeats the read at the next UTC midnight to exercise rollover without
depending on execution time. Both observations require the same live execution and
unchanged historical start/date, and reject active projection on the previous day.
Completion, overlap approval, historical duration, fulfillment, canonical History page
and Statistics assertions remain intact; completed History still selects its historical
start date. A sibling Sequence launcher assertion now also derives its selected date
from its actual `now + 2 seconds` query timestamp, covering the same boundary mechanism.

No production behavior, mandatory testcase identity or compatibility requirement changed.
The original executed failure is retained as evidence. Corrected device execution remains
Controller-owned; APK compilation does not establish a post-repair instrumentation pass.


After the fixture repair, the following local command passed on 2026-10-19:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PlanExecutionControllerTest' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain
```

All 15 PlanExecutionController JVM tests passed with zero failures/errors/skips. App
ktlint passed, Detekt was UP-TO-DATE, and the changed AndroidTest source/APK rebuilt
successfully. A host UTC date-arithmetic check covered the reported 00:07 window and a
23:59:59 command crossing midnight. `git diff --check` passed. These local checks do not
execute the changed instrumentation assertions; Controller must rerun device verification.
