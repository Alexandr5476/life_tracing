# PR37 B1/W1 query correction

This increment preserves v1 eligibility, stable source Field/Series identities,
primaryLocalDate attribution, coverage, exact signed totals and rational distributions.
There is no schema/index migration, durable cache, history mutation or UI change.
This record supersedes the S2 64-row individual summary and the unconditional S3
Field-first median strategy. Earlier S2/S3/S4 execution records remain historical.

## B1: one aggregate traversal

Both individual Number bit-count queries now return one StatisticsNumberSummaryRow.
Each query evaluates 64 COUNT(CASE...) accumulators in the same eligible-row loop,
along with count, typeof validation and min/max. WHERE source_field_id = :fieldId
restricts the aggregate to the requested Field; it does not group unrelated Fields.
There is no recursive bit table, correlated eligible-history count or 64N cross product.

The bounded bit_counts column serializes 64 counts, never samples (at most 1,279
characters). ExactNumberSummary uses the existing grouped parser and
ExactSignedLongSum reconstruction, including the negative sign-bit weight. Empty
individual reads return count zero, 64 zero counts and null extrema. Grouped empty
Fields remain absent from the grouped query and receive the canonical empty result.
Integer-storage validation, BigInteger totals and ExactValue average/median remain
unchanged. Individual readers still execute five canonical reads, including metadata.

## W1: selectivity with a bounded dense alternative

The ordinary Activity/Sequence Series Number selection queries now force this path:

```text
execution Series index; status/deletion/primaryLocalDate eligibility
 -> execution-value primary-key prefix
 -> the recorded value's snapshot-Field primary key
 -> source Field filter and ordered scalar median
```

Unrelated Series executions never enter the value/Field joins. Executions outside a
requested period are rejected before their value/Field lookups. The Series-index
date predicate remains residual: the database may inspect candidate executions in
that Series, but no longer traverses their excluded snapshot/value lineage first.
The individual median sibling queries have the same protected execution-first order.
No new execution/snapshot ownership predicate was added to statistical eligibility.

Forcing this path on every dense multi-Field read repeated too many selected-value
lookups. Series Detail therefore retains an explicitly guarded dense alternative:

1. Read the physical execution count H once for the relevant kind, inside the existing
   canonical transaction. SQLite uses its B-tree Count opcode; this returns one Long,
   without reading execution objects. It is not a persistent aggregate or an O(1)
   latency claim: native page work may still grow with the database.
2. For a populated result Field with n selected samples, consider the dense path only
   when n >= ceil(H / 2). This comparison avoids overflowing multiplication/addition.
3. Only then count that source Field's snapshot lineage, stopping at n + 1 rows.
   Accept the Field-first alternative only if the count is <= n. The cap saturates
   safely at Long.MAX_VALUE. Selective reads do not issue this metadata query.

Under the existing one-value-per-source-Field execution invariant, the accepted
alternative visits at most n Field metadata rows and at most H <= 2n execution/value
candidates for that Field. Unused snapshots cannot cause an uncontrolled lineage
scan: the metadata check stops and selects execution-first instead. No returned
identifier collection, sample list, new statistical predicate or durable state is used.
Physical deleted/nonterminal/unrelated executions conservatively increase H; they
cannot make the dense alternative pass too easily. All eligibility predicates in
the two median alternatives are identical.

The dense plan retains the existing source-Field index, snapshot-Field value index
and execution primary key. Both alternatives return one scalar row with at most two
endpoints and an independently checked eligible count. SQL sorting may require
history-sized native temporary storage; application sample retention remains fixed.
Execution-first work scales with selected execution/value candidates. Dense work
scales with its bounded Field lineage; median sorting remains O(n log n). The
count/selection CTE can be evaluated twice by SQLite; neither is correlated per bit
or per execution. No persistent index or cache is required.

With P populated result Number Fields, Number-specific Series Detail reads are:
one grouped summary, one physical count, P scalar medians and K capped metadata
counts, where 0 <= K <= P. Thus at most 2 + 2P reads; an empty summary needs only its
grouped read, and a catalog without Number Fields needs none. Query counts depend
on result dimensions, never execution/sample cardinality. Largest median bindings
remain nine; individual summary uses six, lineage count two, physical count zero.

## Fail-first and corrected host evidence

The new probe creates a private physical SQLite database from the checked-in v10
tables/indexes, restores the exported partial Main Value index predicates, enables
foreign keys, extracts actual Room annotation SQL, closes/reopens the connection,
and checks independent Python integer/Fraction expectations. Its fixture/reference
collections are deliberately test-owned; they are not production retained samples.

Before changing production SQL, both regressions failed against the reviewed SQL:
B1 reported CORRELATED SCALAR SUBQUERY 5 under the recursive bits loop; W1 reported
the source-Field index as the first historical search, before execution eligibility.
After the correction these fail-first results were reproduced independently for
Activity and Sequence using the immutable reviewed revision:

```powershell
python docs/implementation/statistics_review_probe.py --rows 100 --kind activity --finding B1 --structural-only --query-ref 891dfae408bf046b843accf737560df7078bde3e
python docs/implementation/statistics_review_probe.py --rows 100 --kind activity --finding W1 --structural-only --query-ref 891dfae408bf046b843accf737560df7078bde3e
python docs/implementation/statistics_review_probe.py --rows 100 --kind sequence --finding B1 --structural-only --query-ref 891dfae408bf046b843accf737560df7078bde3e
python docs/implementation/statistics_review_probe.py --rows 100 --kind sequence --finding W1 --structural-only --query-ref 891dfae408bf046b843accf737560df7078bde3e
```

Each command must fail its named plan assertion. Corrected structural checks pass:

```powershell
python docs/implementation/statistics_review_probe.py --rows 1000 --fields 16 --structural-only
```

They require one individual aggregate row and exactly 64 fixed accumulators, no
correlated subquery, execution-first selective/individual plans, indexed dense
plans, the native Count opcode, and the indexed LIMIT-bounded metadata count.

Baseline/optimized comparisons use the same fixture and three repeats per query:

```powershell
python docs/implementation/statistics_review_probe.py --rows 1000 --fields 16 --repeats 3
python docs/implementation/statistics_review_probe.py --rows 10000 --fields 4 --repeats 3
python docs/implementation/statistics_review_probe.py --rows 10000 --fields 16 --repeats 3
python docs/implementation/statistics_number_probe.py --rows 1000
python docs/implementation/statistics_number_s3_probe.py --rows 128 --fields 64
python docs/implementation/statistics_s4_probe.py --rows 128 --fields 4
```

The review fixture contrasts a large old Series, a three-execution new Series
sharing the same stable Field identities, a one-execution day inside the old
lineage, and an empty day. It includes sparse values, both signed extremes,
negative/positive values, odd/even medians and No-live Activities. The S4 fixture
adds distinct frozen snapshots, Sequence children, deleted facts, other Series,
corrections, original dates, archived sources and Category Fields. Existing Room
cases retain physical reopen, mutation/deletion and transaction interleavings.

Host SQLite 3.53.1 observations (VM counts rounded to progress-callback quanta of
1,000 instructions, not exact cross-version operation counts):

| 1,000 executions/kind, 16 Fields | Reviewed VM work | Corrected VM work |
| --- | ---: | ---: |
| Activity individual summary, old Series | about 10,738,000 | 475,000–476,000 |
| Sequence individual summary, old Series | 10,640,000–10,641,000 | 474,000–475,000 |
| Activity tiny new Series median | about 16,000 | about 1,000 |
| Sequence tiny new Series median | about 16,000 | about 1,000 |
| Activity dense median | 30,000–31,000 | 30,000–31,000 plus scalar preflights |
| Sequence dense median | about 28,000 | about 28,000 plus scalar preflights |

At 10,000 executions/kind and 16 Fields, the tiny-Series reviewed median grows to
about 160,000 instructions while the corrected median remains about 1,000. Dense
median work remains approximately 303,000 (Activity) / 279,000 (Sequence), with the
same indexed path plus bounded scalar preflights. Individual summary work drops
from approximately 107.6/106.7 million to 4.77/4.75 million instructions.

The probe prints every observed latency, selected strategy, result cardinality and
preflight cost separately. Representative initial 1,000/16 observations were
247–256 ms versus 5.4–6.7 ms for the Activity summary, and 231–245 ms versus
5.8–6.4 ms for Sequence. Loaded-host repeats varied substantially, including dense
latency reversals despite unchanged VM work. These are observations, not Android
latency claims or CI thresholds. Run the private-database probe without concurrent
build work when comparing wall-clock values. Result cardinality, exact arithmetic,
strategy guards and query plans are the deterministic assertions.

## Room inventory and local verification boundary

The new mandatory Room case is
StatisticsRepositoryTest.numberQueriesRestrictTinySeriesAndNarrowPeriodsBeforeFieldLineageAfterReopen.
It seeds 8,000 old executions per kind, a tiny new Series sharing source Field
identity, a singleton period, and broad dense history. It reads after two physical
reopens, compares full exact distributions independently, checks coverage/missing
counts, and inspects the actual callback SQL/bindings with EXPLAIN QUERY PLAN.
It requires selective reads for tiny/narrow cases, the guarded indexed dense path
for broad history, and scalar capped metadata counts. The existing growing Number
reader now rejects correlated bit scans and checks the fixed 64-count projection.
The existing Series Detail growth test pins the capped metadata read count.

Two added JVM cases cover empty/nonempty single-row arithmetic, integer-storage
rejection, cardinality policy boundaries and Long overflow. All 25 data JVM tests
have executed successfully with zero failures/errors/skips. The 17 compatibility
runner unit tests passed; they mock Gradle/device output and do not run emulators.
The pinned inventory now has 621 data, 323 isolated app and two startup cases per
API, preserving every previous required identity and all five API jobs.

Local data production/AndroidTest compilation and APK packaging succeeded. After
correcting the final test-formatting line, this focused command exited zero with
BUILD SUCCESSFUL on 2026-10-19:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :data:ktlintCheck :data:detekt :data:lintDebug :data:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*Statistics*' --continue --console=plain
```

The AndroidTest Kotlin source and APK rebuilt, and lintDebug completed. Domain/data
JVM tests, app Statistics JVM tests and data Detekt were UP-TO-DATE; AndroidTest
ktlint was FROM-CACHE. The prior executed JVM reports contain 25 data and 17 app
Statistics cases, all successful with zero errors/skips. The continuation also
reran the 1,000-row/16-Field structural probe and 128-row/64-Field exact grouped
probe successfully. No new device execution is claimed.

Controller must still run the full build/static/JVM and unfiltered five-API data/app/startup suites
plus independent exact-head GitHub CI. No device task, commit, push or PR operation
was performed by these implementation turns. Host probes, cached reports and APK
compilation do not certify device passes.
