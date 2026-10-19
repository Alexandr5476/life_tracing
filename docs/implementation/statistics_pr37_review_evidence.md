# PR37 B1/W1 and B2 query corrections

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

The first W1 correction guarded Field-first access using a majority of all physical
executions and a capped metadata count. B2 replaces that policy: unrelated Series
could suppress the cheaper Field index, and snapshot count alone did not measure
value fanout from a shared snapshot. The following is the current production policy.

1. For a nonempty Number request, count selected execution/value candidates Q once.
   Use the same Series, status, deletion and primaryLocalDate eligibility as the
   median, then LEFT JOIN execution values through the covering execution-ID primary
   key prefix. Each execution contributes max(1, stored value row count). No snapshot
   Field lookups or value objects are needed. The Series Detail read shares Q across
   all result Fields; an individual read obtains Q once too.
2. For each populated Number Field, probe its source-Field index and LEFT JOIN its
   snapshot-Field value index, with LIMIT 2Q+1 inside the counted subquery. A Field
   with no value rows still contributes one candidate, so both unused snapshot
   metadata and shared-snapshot value fanout consume the budget. The query returns
   only one scalar count; LIMIT stops native traversal, not just returned rows.
3. If the probe reaches the sentinel, use execution-first median selection.
   Otherwise use Field-first selection. The cap saturates at Long.MAX_VALUE without
   overflow; saturation at the sentinel conservatively selects execution-first.
   Empty summaries issue neither cost/probe queries nor a median query.

This is a bounded cost comparison, independent of global physical history size and
recorded sample density. The factor-two allowance permits modest unrelated lineage
while bounding the Field path's candidate work by selected execution/value work.
It is an access choice, not an eligibility approximation. Dense/sparse exclusive
lineages select Field-first even when many unrelated Series exist. Tiny new Series
and narrow periods sharing a large old lineage hit the sentinel and retain W1's
execution-first plan. A sparse Field with a genuinely small lineage can still
choose Field-first inside a tiny Series, because that path is cheap.

Let E be selected eligible executions, V their stored value rows, and
Q = sum(max(1, values per execution)); Q is between max(E,V) and E+V. The initial
cost query is O(T+V) indexed row work, with no per-value snapshot lookup, where
T includes the Series-index execution candidates rejected by date/status/deletion. Each Field
probe visits at most 2Q+1 joined candidates, covering both metadata and values. An
accepted Field-first median visits at most 2Q candidates, with at most one
execution-PK eligibility lookup per value, and sorts the eligible samples in SQL.
A rejected probe does not traverse the remaining unrelated lineage. Execution-first
selection retains O(T+V) candidate work, including Series-index eligibility rejection. Both
median queries can evaluate the eligible CTE twice; neither uses correlated scans.
Index lookup factors and exact sorting cost remain; this is not constant-time SQL.

For P exclusive dense Fields with E recorded values each, Q is O(EP), the shared
cost query is O(EP), and the P probes/medians are O(EP) before sorting. There are no
P repetitions of all P execution values. Sparse exclusive Fields probe only their
own metadata/value candidates. If every Field shares a vastly larger lineage, the
bounded probes and execution-first medians can still cost O(PQ); that is the
deliberate bounded fallback, not an unconditional scan of unrelated history.

Number-specific Series Detail statements are one grouped summary, one selected
candidate count, and one probe plus one scalar median per populated Field: 2+2P.
Empty summaries need only the grouped read. Individual nonempty Number reads now
execute seven canonical statements including metadata and coverage, independent of
execution/sample count. Their summary remains the closed B1 fixed 64-accumulator
aggregate. Largest median bindings remain nine and each probe binds two arguments.

All queries remain within their existing complete repository Room transaction;
no preflight is performed outside it. Existing exact bit arithmetic, rational
average/median calculation, missing coverage, identity, display resolution and
eligibility predicates are unchanged. SQL may use history-sized native sort
storage; application projections retain only one cost scalar, one probe scalar,
and one median row per requested Field. No new schema, index or durable cache.

## Earlier B1/W1 execution record

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

## Earlier B1/W1 Room inventory and verification record

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

## B2 regression and verification record

The existing pinned
StatisticsRepositoryTest.numberQueriesRestrictTinySeriesAndNarrowPeriodsBeforeFieldLineageAfterReopen
now additionally seeds 800 minority-Series executions with 23 dense Number Fields
and one singleton sparse Field, separately for Activity and Sequence. The 8,000 old
executions per kind provide unrelated Series. Both physical reopens read complete
canonical Series Detail and every individual Number Field. Assertions require one
shared selected-value count, 24 bounded probes, and 48 Field-first medians across
the two read forms, with exact independent signed-extreme distributions, -1/2 even
medians, missing coverage and archived sources. Actual callback SQL/bindings are
checked with EXPLAIN QUERY PLAN; probe limits include real indexed value fanout.
The original shared old/new lineage and narrow-day assertions remain intact.

The mandatory inventory identities/counts remain unchanged: 621 data, 323 isolated
app and two startup cases at each of the five APIs. Existing canonical physical
reopen, supported historical correction/deletion, child-deletion, deterministic
mutation interleaving, cancellation and failure cases continue using the same
transactional repository, now including both new cost queries and the bounded probe.
No instrumentation case or required CI job was removed, renamed or filtered.

Fail-first evidence was observed before changing production: the focused JVM median
policy regression failed for (800 requested samples, 3,200 global executions,
800 exclusive lineage rows), and the new B2 host probe rejected the baseline's
execution-first minority path. The immutable baseline can be checked for both kinds:

```powershell
python docs/implementation/statistics_b2_probe.py --rows 800 --fields 24 --kind activity --query-ref b074d9a2477e42e73c6c82860ae5b7b8dfee1129
python docs/implementation/statistics_b2_probe.py --rows 800 --fields 24 --kind sequence --query-ref b074d9a2477e42e73c6c82860ae5b7b8dfee1129
```

Both must fail the B2 path assertion. Add --sparse-first to either command to
independently reproduce the singleton sparse-Field failure before visiting any dense
Field. Both sparse-first baseline variants also exited one at that intended assertion;
the corrected sparse-first 800/24 run passed both kinds and all scenarios.
The probe extracts actual Room SQL from v10,
uses a private physical database with foreign keys and checked-in indexes, closes
and reopens it, and compares all Number distributions with independent Python
integer/Fraction calculations. It covers minority Series, sparse Fields, a tiny new
Series sharing large old Field/value lineage, a narrow day, and a large unused
snapshot-metadata lineage that must trigger an early stop. It records the cost query,
probe and median separately, including covering-index plans and bounded result rows.
Its allocations belong to the fixture/reference oracle, not production readers.

Reproducible comparisons (no concurrent build work for representative timing):

```powershell
python docs/implementation/statistics_b2_probe.py --rows 800 --fields 12 --repeats 3
python docs/implementation/statistics_b2_probe.py --rows 800 --fields 24 --repeats 3
python docs/implementation/statistics_b2_probe.py --rows 3200 --fields 24 --repeats 3
```

VM-work comparisons are host structural evidence, not Android latency guarantees.
There are no wall-clock thresholds in mandatory CI. Device suites and independent
exact-head CI remain Controller-owned; APK compilation does not certify execution.

All three comparisons above passed on host SQLite 3.53.1 on 2026-10-19, after the
focused Gradle run had finished. Each fixture has three unrelated executions for
every selected execution. Counts below are approximate VM instructions, rounded to
the progress callback's 1,000-instruction quantum. Corrected work includes the new
shared cost query and all Field probes plus medians. Both sides exclude unchanged
grouped bit summaries, catalog, Category and duration reads; these are median-boundary
comparisons, not complete UI latency measurements.

| Selected executions / populated Fields | Activity reviewed / corrected VM | Sequence reviewed / corrected VM | Number statements / fixed summary rows |
| --- | ---: | ---: | ---: |
| 800 / 12 | 1,938,000 / 369,000 | 1,909,000 / 346,000 | 26 / 12 |
| 800 / 24 | 7,568,000 / 764,000 | 7,511,000 / 719,000 | 50 / 24 |
| 3,200 / 24 | 30,267,000 / 3,036,000 | 30,038,000 / 2,856,000 | 50 / 24 |

Doubling configured Fields roughly quadruples reviewed median work but doubles the
corrected work. Quadrupling history at fixed Fields scales both native workloads
about fourfold while statement count and application result cardinality stay fixed.
All minority medians, including singleton sparse Fields, use the indexed Field-first
plan, never repeated execution-to-all-Field value traversal. At 800/24, the Activity
individual dense median drops from about 315,000 instructions to 24,000 plus 6,000
for its probe and 68,000 for the cost query. The sparse median drops from about
310,000 to under 1,000 plus a probe under 1,000 and the same 68,000 cost query.
The covering cost query is shared once by the bundled read, not repeated per Field.

Representative Activity 800/24 elapsed observations were 8.18–8.44 ms for the reviewed
dense median versus 0.85–1.15 ms for corrected median selection, with the shared cost
query measured separately at 0.99–1.36 ms. At 3,200/24 those observations were
43.85–43.89 ms versus 7.18–7.78 ms, with 5.36–6.39 ms for the cost query. Probe VM
work is included above; these component timings are not summed into an end-to-end
claim. Sequence and sparse timings varied substantially despite stable VM work.
The probe prints every repeat so loaded-host timing reversals remain visible.

The tiny/shared and narrow cases passed execution-first assertions for the large
old lineages at all three sizes. The genuinely exclusive singleton sparse lineage
can still use Field-first. The unused-metadata fixture exceeds the 2Q+1 budget and
requires bounded probing followed by execution-first, so the evidence includes
fallback overhead rather than assuming every probe is beneficial. Foreign-key
checks, physical reopen and all independent exact distributions passed in every run.

The immutable reviewed Activity and Sequence 800/24 commands above both exited one
at the intended B2 minority-path assertion. Before production changes, the focused
JVM fail-first command also exited one with its new policy assertion:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests '*ExactNumberSummaryTest.medianReadPolicy*' --console=plain
```

After fixing the policy, query shapes and static-check issues, this final focused
verification exited zero with BUILD SUCCESSFUL:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :data:ktlintCheck :data:detekt :data:lintDebug :data:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*Statistics*' --continue --console=plain
```

Data production and AndroidTest compilation, APK packaging, Detekt, ktlint and lint
passed. The final run reused successful JVM tasks; their original reports show 25
data and 17 selected app cases, zero failures/errors/skips. The unchanged domain
suite was UP-TO-DATE (241 cases in its existing successful reports). No device pass
is inferred. The pinned StatisticsRepositoryTest inventory still matches all 29
method identities exactly. git diff --check passed.

Additional final host checks passed: statistics_review_probe.py at 1,000/16
(both the structural-only check and one-repeat comparison),
statistics_number_s3_probe.py at 128/64, and statistics_s4_probe.py at 128/4.
The updated shared probe helper was also explicitly checked to choose selective
and Field-first paths for both kinds. The existing five-API gate remains mandatory.
No managed-device task, commit, push or PR operation was performed.
