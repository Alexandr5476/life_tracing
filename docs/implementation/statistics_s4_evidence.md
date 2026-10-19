# S4: Statistics integration and Parent closure evidence

Contract basis: the supplied S4 brief, product v0.16 §31.1–31.17, domain v0.10
§15–16/23–25/28, schema v0.6 §26/27/39/44/54, implementation clarifications
(including S2/S3), delivery-plan item 7 and the current compatibility gate.
No separate Parent Contract file was found in the tracked repository. Final Parent
review must reconcile any additional Parent clauses supplied outside this worktree.

This slice changes tests, the pinned inventory and evidence only. S1C1, S2 and S3
production algorithms, schema v10, indexes, identities, immutable snapshots and
historical mutation commands are unchanged. There is no Statistics cache.

## Complete production read graph

Every public canonical reader below enters `StatisticsRepository.transaction`, which
uses `LifeTracingDatabase.runInTransaction(Callable)`. Private `Locked` helpers stay
inside that same transaction, including every median in complete Series Detail.
The audit covered the current files, not only the earlier optimization diffs.

| Entry point | Canonical DAO reads and result dimensions |
| --- | --- |
| `global` | `global`: one scalar row over eligible top-level roots |
| `overview` | `globalLocked` plus `seriesSummariesLocked` in one transaction |
| `seriesCatalog` | `seriesCatalog`: one row per durable Series; source counts use existing Template Series indexes |
| `seriesSummaries` | Activity/Sequence aggregates, one-off aggregate and catalog; four SELECTs, output by Series |
| `activitySeries` | Catalog, Activity aggregate, Activity duration selection; three SELECTs |
| `sequenceSeries` | Catalog, Sequence aggregate, Sequence duration selection; three SELECTs |
| `seriesDetail` | Catalog, corresponding aggregate/duration selection, Field metadata, grouped Number summary, one median per populated Number Field, grouped Category metadata/counts, Text descriptors |
| `fieldCatalog` | Catalog plus corresponding historical/source Field metadata; one-off returns no reusable Fields |
| `numberFieldStatistics` | Catalog, Field metadata, count-only execution denominator, 64 bit rows, one scalar median row; five SELECTs |
| `categoryFieldStatistics` | Catalog, Field metadata, execution aggregate denominator, option metadata and grouped counts; five SELECTs |

The history-derived scalar distribution queries are both `*SeriesAggregates`,
`oneOffAggregate`, both `*DurationSelection`, both `*NumberBitCounts`, both
`*NumberSelection`, both `*NumberSummaries` and both `*SeriesNumberSelection`.
Global is scalar aggregation without a raw distribution. Category readers return
grouped identity counts, not execution samples. Field/option metadata is grouped
by stable provenance. Source-less Category fallback identities can increase the
legitimate result dimension; the Number O(F) claim is not a bound on every Category
catalog or the entire application.

No production duration/Number DAO returns raw historical sample collections or a
history-sized Cursor. The remaining collection-based domain calculators are small
reference/consumer APIs; the repository calls their bounded-summary overloads.
Series Detail calls the optimized private readers directly. Overview/summaries
consume aggregates without invoking a raw-value alternative. No interval graph or
complete execution/snapshot graph is loaded for Statistics.

Eligibility is preserved: global uses completed nondeleted standalone Activities
and terminal Sequence roots; Activity Series include eligible children, without
adding children to global time. Child logical deletion remains authoritative even
after structural removal. No-live counts/Fields remain present with absent duration.
The one-off bucket has aggregate semantics only; source-less children retain null
Activity Series identity. Plan linkage is explicit persisted linkage. Archive and
display labels do not change grouping identity. All period predicates use persisted
`primary_local_date`, with the existing inclusive domain boundaries.

## Canonical mixed-history and physical lifecycle evidence

`StatisticsRepositoryTest.mixedStatisticsSurviveReopenCorrectionAndDeletionWithIndependentExpectations`
uses a uniquely named physical database built through the production builder.
Its helper establishes all prerequisites independently. The finished fixture has:

- Timed standalone, No-live, fulfilled Plan-linked and other-Series Activities;
  RUNNING/PAUSED rows and soft-deleted standalone history.
- COMPLETED and ENDED_EARLY roots, linked children, a true source-less child,
  a retained deleted-child tombstone and a distinct structurally removed occurrence.
- An archived Activity source, sparse/dense/empty Number Fields, source and snapshot
  fallback Category options with equal labels, and Text descriptors. Sequence Detail
  includes Number, Category and Text metadata.
- August 20/21 membership and a November 1 New York DST-overlap event with its
  original offset. Reads are repeated under Kiritimati and New York device defaults.

Independent ledger before mutations (minutes):

| Projection | Expected facts |
| --- | --- |
| Global | Standalone 30 + Plan 5 + other Series 7 + roots 60 + 20 + retained-tombstone root 10 + removed root 0 = **132**; eight top-level executions; three active days; pause/idle 25 |
| Activity Series | Four executions, three duration samples [5, 30, 60]; total 95, average 95/3, median 30, min 5, max 60 |
| Sequence Series | Four roots, samples [0, 10, 20, 60]; total 90, median 15, min 0 |
| Sparse Activity Number | [Long.MIN_VALUE, 0, Long.MAX_VALUE]; total -1, average -1/3, median 0; three recorded / four relevant |
| Dense Activity Number | [1, 2, 3, 4]; total 10, median 5/2; no missing values |
| Empty Activity Number | Zero recorded, four missing; absent median, not a recorded zero |
| Activity Category | Source identity count two, snapshot fallback count one; one missing |
| Sequence Number / Category | Two recorded / four relevant; Number [0, -2], total -2, average/median -1; one stable source Category option |
| Periods | August 20 global 105; August 21 global 20; August week/month/custom 125; Year includes all 132 |

The test compares Global, Overview, summaries, catalogs, both Series kinds, complete
Details and individual Number/Category readers. These comparisons supplement the
ledger; agreement between readers alone is not the oracle. It verifies empty-period
schema completeness and individual/combined Field parity.

All owned connections close before production reopening. Whole-table facts and all
projections must survive reopening exactly, including Series/Field/option provenance.
After reopening, `ActivityCommandRepository.correctHistory` moves the 30-minute fact
to August 21, makes it 40 minutes and replaces its sparse Number with eight. Global
becomes 142; the origin day loses 30 and destination gains 40. Its Number total exceeds
Long.MAX_VALUE exactly. Supported standalone soft deletion removes the No-live zero;
`SequenceHistoryCommandRepository.deleteChildHistory` removes the eligible child
contribution while preserving global/root history. Remaining Activity durations are
[5, 40], Number eight has one missing execution, and dense values [1, 4] total five.
Unaffected Series/Sequence Detail and fulfilled Plan state stay fixed. Another complete
close/reopen/read follows the mutations, ruling out reliance on transient aggregates.

Existing pinned tests retain singleton, duplicates, odd/even, all-No-live, overflow,
invalid integer storage, archived/deleted Field identity, Series splits and Category
lineage coverage. Particularly relevant are `orderedDurationReadersPreserveEligibilityPeriodsAndDetail`,
`durationSelectionRejectsCountMismatchAndInvalidCachedValues`,
`boundedNumberReadersPreserveSignedEligibilityAndPeriods`,
`numberReadersRejectNonIntegerStorage` and both mixed-Field batching cases.

## Deterministic concurrent mutations and failure cleanup

Three new mandatory cases use the same independent fixture:

- `concurrentStandaloneCorrectionKeepsEveryStatisticsProjectionInOneTransaction`
- `concurrentStandaloneDeletionKeepsEveryStatisticsProjectionInOneTransaction`
- `concurrentChildDeletionKeepsEveryStatisticsProjectionInOneTransaction`

Each exercises Overview, summaries, Activity Series, Sequence Series, Activity Detail,
Sequence Detail, individual Number and individual Category: 24 controlled interleavings
in total. Correction/child deletion select August 20; No-live deletion uses AllTime so
its denominator and recorded-zero removal are observable. Global alone is one SQL
statement; Overview tests its consistency with the other returned projections.

Two independent Room owners open the same disposable physical database. A synchronous
test-only `StatisticsReadTestOpenHelperFactory` delegates to the real framework helper,
including production creation/migration callbacks and journal behavior. It gates the
reader's second SELECT after the first consumed SELECT, asserts that the underlying
connection is in a transaction, then signals that the writer has attempted its own
transaction. Thread identities scope these hooks, excluding initialization/invalidation
traffic. Latches establish ordering; 30-second waits bound failures, not race timing.

The reader returns the complete old object, then the real supported writer completes.
Both owners must subsequently read the same committed new state, with explicit Number
denominator/total/median assertions. Root projections legitimately remain unchanged for
child deletion. A writer reservation can serialize the writer behind Room's canonical
read transaction: the test deliberately does not await writer commit while holding that
reservation. This proves the existing boundary without introducing production locks or
claiming that Android permits simultaneous writer commits inside an IMMEDIATE read.

QueryCallback is intentionally not used for these gates or injected exceptions: the
installed Room 2.8.4 dispatches it through a coroutine. The synchronous wrapper observes
actual calls and changes no SQL/results/transaction policy. Cleanup always releases the
reader, interrupts and joins both workers, closes both owners and deletes the database.

`statisticsReadFailureAndCancellationReleaseTransactionWithoutDurableWrites` injects a
read exception and CancellationException at a median query after earlier reads. It
requires the original exception, no remaining transaction, equality of every durable
table and successful complete rereading afterward. This is deterministic boundary
injection, not a claim of measuring native SQLite cancellation latency.

## Production presentation ownership

`LifeTracingApplication` constructs the production StatisticsRepository and injects
`overview` and `seriesDetail` through `withContext(Dispatchers.IO)` into the two
controllers. `MainActivity` obtains the Overview controller from its ViewModel owner
and Detail controllers from the Series-keyed route-session owner. Compose routes only
collect presentation state and dispatch actions. No calculation runs synchronously in
Compose. The UI scope resumes publications on Main, serializing them with route actions;
Detail additionally guards publication/closure with its existing lock.

Actual JVM evidence: eight `StatisticsControllerTest`, five
`StatisticsSeriesDetailControllerTest` and two route-session owner tests passed in the
full app suite. Existing cases cover initial loading/content, empty Overview, failure
and retry, exact period forwarding, refresh, stale success/failure, close and Detail
cancellation. New cases cover Overview cancellation, route exit/reentry with pending
success/failure, and replacement/release of Detail sessions followed by late results.
A stale session release cannot close its replacement; reentry creates a fresh controller.
Close invalidates publication and further requests; it does not promise to interrupt an
already running synchronous SQLite computation or introduce a new cancellation policy.

Unchanged pinned Compose evidence includes:
`loading_failure_retry_empty_and_zero_count_series_are_presented`,
`all_period_kinds_dispatch_values_and_week_is_monday_anchored`,
`systemOneOffHeadingUsesRussianResourceInsteadOfPersistedEnglishName`,
`detailPeriodSelectionSupportsMondayWeeksInclusiveCustomAndAllTime` and
`noDurationSamplesAndSampledZeroRemainDistinctAndLifecycleMetadataIsVisible`.
These Android tests still require Controller execution; APK compilation is not their
execution. Resources, RU/EN behavior, screen layout and motion are unchanged.

## Consolidated reproducible host comparison

The earlier [duration](statistics_duration_s1_evidence.md),
[individual Number](statistics_number_s2_evidence.md) and
[grouped Number](statistics_number_s3_evidence.md) evidence remains applicable.
The new probe extracts the actual current DAO SQL and v10 exported indexes:

```powershell
python docs/implementation/statistics_s4_probe.py --rows 3000 --fields 4 --plans
python docs/implementation/statistics_s4_probe.py --rows 10000 --fields 4 --plans
# Optional isolated larger measurement, never a mandatory per-API latency testcase:
python docs/implementation/statistics_s4_probe.py --rows 30000 --fields 6 --plans
```

The measurement fixture includes timed/No-live caches, terminal Sequences, child
contributions, true source-less children, archived and split Series, sparse/dense/empty
Numbers, Categories, corrected dates/values and deleted Activities. Each execution gets
separate frozen Field lineage. Narrow, AllTime and empty periods run against identical
committed states for baseline/optimized queries. Foreign keys are checked. As in the
earlier probes, this is an isolated SQL-plan/cache fixture, not a complete runtime graph
or the domain-valid physical Room fixture. It does not replace mutation-command tests.

Baseline raw scalar SELECTs come from the same eligible CTEs and are retained in Python
only as reference samples; Python arbitrary integers/Fraction supply exact expected
distributions. Thus baseline and optimized algorithms operate on equivalent states.
This comparison isolates the removed materialization boundary, not a historical APK
benchmark. Baseline fetching, aggregate/selection work, query counts, row counts and
Python allocations are printed separately; it does not time the old Kotlin sorting code.

| Quantity | Duration | Individual Number | Combined Detail Numbers |
| --- | --- | --- | --- |
| Matching executions | Independently reported aggregate count E | Relevant execution denominator; samples may be sparse | Same containing Series denominator |
| Historical scalar samples | M non-null durations | N recorded signed values | Sum of per-Field recorded values |
| Baseline raw result rows | M | N | N across Fields |
| Optimized raw scalar retention | At most two transient endpoints | 64 counts plus at most two endpoints | Fixed-width summary per populated Field plus transient endpoints |
| Optimized SQL results | Aggregate row plus one selection row | Count row, 64 bit rows, one selection row | P summaries and P scalar median rows, P ≤ F |
| Logical statement count | Two duration statements; canonical reader three | Three numeric statements; canonical reader five | 1 + P numeric statements; mixed Number/Category complete Detail 7 + P |
| Application result state | O(1) for duration distribution | O(64), independent of N | O(64F) = O(F), independent of history samples |
| Native work | Fixed scans plus duration ordering | 64 fixed eligible traversals plus summary/count/median | One grouped pass with 64 accumulators, then indexed median work per populated Field |

The probe asserts cardinalities and exact results without elapsed-time thresholds.
`tracemalloc` measures incremental Python allocation during fetch, not Kotlin heap,
total process RSS, native SQLite memory, sorting storage or the fixture's retained data.
The application memory claims derive from production adapter/result shapes. A constant
logical statement count is never presented as constant database work.

Executed on host SQLite 3.53.1 on 2026-10-19, with four populated Number Fields and
one empty Number Field, both final commands above passed. Matching execution counts
include No-live; duration and Number counts deliberately differ:

| Seed rows per kind | Projection / period | Matching executions | Duration samples | Number samples across Fields | Summary rows / median rows / numeric statements |
| --- | --- | ---: | ---: | ---: | --- |
| 3,000 | Activity AllTime | 2,572 | 2,234 | 8,743 | 4 / 4 / 5 |
| 3,000 | Activity August 20 | 1,760 | 1,527 | 5,983 | 4 / 4 / 5 |
| 3,000 | Sequence AllTime | 2,823 | 2,823 | 9,598 | 4 / 4 / 5 |
| 3,000 | Sequence August 20 | 1,931 | 1,931 | 6,565 | 4 / 4 / 5 |
| 10,000 | Activity AllTime | 8,574 | 7,446 | 29,152 | 4 / 4 / 5 |
| 10,000 | Activity August 20 | 5,866 | 5,093 | 19,945 | 4 / 4 / 5 |
| 10,000 | Sequence AllTime | 9,411 | 9,411 | 31,997 | 4 / 4 / 5 |
| 10,000 | Sequence August 20 | 6,439 | 6,439 | 21,892 | 4 / 4 / 5 |

Every duration selection exposed one row; each individual bit summary exposed 64 rows
and each individual median one row. Empty periods had zero populated summaries/medians,
one grouped statement and one empty duration-selection row.

Illustrative AllTime observations below include allocation tracking and concurrent host
build load. They are single query observations, not stable benchmark estimates or a
before/after Android speedup. Grouped timing excludes its subsequent per-Field medians:

| Seed rows / kind | Dense Number raw fetch ms | Individual bit query ms | Grouped summary ms | Raw fetch retained Python bytes / bit-result bytes |
| --- | ---: | ---: | ---: | --- |
| 3,000 Activity | 11.363 | 335.711 | 24.721 | 332,476 / 26,671 |
| 10,000 Activity | 129.624 | 3,906.318 | 294.736 | 1,108,028 / 26,671 |
| 3,000 Sequence | 46.743 | 1,818.146 | 102.076 | 362,701 / 13,940 |
| 10,000 Sequence | 250.370 | 6,965.837 | 446.566 | 1,209,733 / 20,332 |

These observations expose a material remaining latency risk despite bounded application
results. In particular, individual S2 reads pay for all 64 traversals even for an empty
configured Field. They do not establish quadratic scanning: the installed plans below
correlate over exactly 64 bit rows and use indexed execution/value/Field lookups. Query
shape supplies that complexity argument; noisy elapsed ratios cannot replace it.

For Android reproduction, use exclusively leased disposable production databases with
the same seed and committed state for baseline/current revisions. Record device/API,
SQLite version, indexes/plans, seed and matching counts first. Capture raw cold and
warm repetitions separately with allocation tracking disabled for latency; measure
Java/Kotlin heap separately from native SQLite/Perfetto allocation and scan/sort data.
Trace DAO calls separately from native statement work and cursor fills. Run the optional
large fixture once in this isolated measurement path, not inside every mandatory case.
Retain raw observations without a pass/fail time threshold. No such Android measurements
were performed here.

## Index and native-work audit

Both AllTime and narrow plans use existing Series execution indexes:
`activity_executions_series_completed` / `sequence_executions_series_ended`.
Optional date bounds remain residual predicates on these plans. Global/root/date
aggregates can instead use deletion/status/date indexes and temporary GROUP BY and
COUNT(DISTINCT) B-trees. Available Number value indexes do not eliminate the requested
Series/Field eligibility joins or all median sorting.

Observed host plans show:

```text
Duration median: Series execution index; TEMP B-TREE FOR ORDER BY;
                scalar independent count; one bounded output row
Individual bits: 64-row recursive bits; MATERIALIZE summary;
                 CORRELATED SCALAR SUBQUERY over the fixed bit rows;
                 Series -> execution values by execution PK prefix -> Field PK
Grouped Number: Series -> execution values -> Field PK; TEMP B-TREE FOR GROUP BY
Detail median: source_field_id index -> snapshot_field_id value index -> execution PK;
               TEMP B-TREE FOR ORDER BY; independent scalar count
```

The individual bit correlation is executed for exactly 64 positions, not once per
historical sample. With candidate join rows V, include O(64V) visits and indexed lookup
costs; fixed overhead can still be expensive. Grouped summaries evaluate 64 COUNT
expressions without a 64N-row cross product. Native grouping/sorting can retain O(N)
temporary state. Median work is O(n_f log n_f) per populated Field, with total sort work
bounded by O(N log N), plus lineage visits H + K to snapshots/values across dates and
Series splits. Existing source-Field and snapshot-Field value indexes partition those
visits. This avoids an uncontrolled per-Field full-Series scan in the S3 median path.

No history-dependent query/pagination loop or per-sample correlated rank query was found.
The bounded median projections also remove history-sized SQLiteCursor window progression:
Android cursor fills may restart/skip native work when traversing large results. Fixed
small scalar rows cannot grow across result windows with history size. Grouped summaries
may span windows as F itself grows; that is result-dimensional work, not a raw-sample
Cursor guarantee. Android-specific plans, native allocations and cursor behavior still
require observation on the selected API/SQLite build.

## Execution status and remaining Parent acceptance risks

Actually executed local commands and observations are recorded below. Controller aliases
are labels, not shell commands. No managed-device task, emulator, Git mutation or GitHub
CI polling was launched in this slice.

```powershell
# Broad focused verification: BUILD SUCCESSFUL.
.\gradlew.bat :domain:test :data:testDebugUnitTest :app:testDebugUnitTest `
  :data:ktlintCheck :app:ktlintCheck :domain:detekt :data:detekt :app:detekt `
  :data:lintDebug :app:lintDebug :data:assembleDebugAndroidTest :app:assembleDebugAndroidTest `
  --continue --console=plain

# Final data sources/APK and focused arithmetic verification: BUILD SUCCESSFUL.
.\gradlew.bat :domain:test --tests '*StatisticsModelsTest' `
  :data:testDebugUnitTest --tests '*ExactNumberSummaryTest' `
  :domain:ktlintCheck :data:ktlintCheck :data:detekt :data:lintDebug `
  :data:assembleDebugAndroidTest --continue --console=plain

python -m unittest discover -s .github/scripts -p test_run_instrumentation.py
python docs/implementation/statistics_duration_probe.py
python docs/implementation/statistics_number_probe.py
python docs/implementation/statistics_number_s3_probe.py
git diff --check
```

- The full focused Gradle invocation passed: domain/data/app JVM tasks, data/app ktlint,
  domain/data/app Detekt, data/app lint and both AndroidTest APK assemblies. The full app
  suite executed 400 JVM cases, including all 15 Statistics controller/owner cases;
  domain/data full-suite tasks were UP-TO-DATE, not new executions.
- A final focused command checks StatisticsModels and ExactNumberSummary, domain/data
  formatting, data Detekt/lint and the final data AndroidTest APK after synchronous
  instrumentation was installed. It passed (115 tasks: 11 executed, one from cache,
  103 up-to-date). ExactNumberSummary executed four cases; StatisticsModels restored
  its twelve passing cases FROM-CACHE. This is not a fresh domain test execution.
- All three original S1–S3 host probes passed at their defaults. The instrumentation
  evidence-script suite passed all 17 unit tests; its printed Gradle/device reports are
  mocks, not device execution. Inventory comparison preserves every original identity:
  data 615 -> 620, app 323 -> 323, independent startup 2 -> 2.
- Current S4 five-API execution and exact-head CI have no Controller-recorded outcome in
  this turn. Earlier compatibility/S3 records are historical evidence for earlier heads.
- Android device timings, Java/Kotlin heap and native SQLite memory were not measured.
  Host timings must not be relabeled as Android performance or a latency acceptance pass.

Remaining Parent acceptance risks are: execute all five original unfiltered inventories
and independent startup on the resulting head; verify the new physical/concurrency/failure
cases on real Android SQLite; obtain exact-head `ci-fast` and GitHub verification; and
assess environment-sensitive latency/native temporary storage, especially the fixed
64-traversal individual Number reader and large source-Field lineage in narrow periods.
No schema/index migration is demonstrated indispensable by this audit. No unresolved
durable/user-visible semantic decision or uncontrolled quadratic production query was
identified. Parent/Decomposer acceptance remains an independent repository-wide review.
