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

## F1: many populated Fields sharing old Series lineages (2026-10-19)

This section supersedes the per-Field fallback work bound above. The audited head was
`1d481f32963c07935cab90f9d32b219f15bb6f06`; this correction leaves individual-Field
selection, duration and Category SQL unchanged. No migration or persistent index/cache.

The former policy capped *each* lineage probe at 2Q+1, then independently traversed Q
selected execution/value candidates for each fallback Field. Both the repeated probes
and medians could cost O(PQ). The replacement shares one 2Q+1 probe budget. Successful
exclusive lineages retain their existing Field-first scalar queries. On the first
saturated probe, all unresolved Fields use grouped exact rank selection.

Each rank pass uses one execution -> execution-value -> snapshot-Field indexed traversal,
the existing Series/status/deletion/date predicates, a balanced binary CASE pivot lookup
and GROUP BY source Field identity. A linear CASE chain or joining a small VALUES pivot
relation would reintroduce P comparisons per value; neither is used. UTF-8 hex literals
encode opaque Field IDs without SQL interpolation of raw IDs. Only Series and date bounds
are bound (four arguments); neither the bind cap nor SQL expression nesting becomes
linear in Field count. The CASE tree has O(log P) depth and O(P) SQL text size.

For each unresolved Field, COUNT(value <= pivot), MAX(value <= pivot), MIN(value > pivot)
select the exact lower median rank; the same count supplies the upper endpoint for even
samples. BigInteger computes the midpoint without signed-Long overflow. Each pass halves
the remaining value interval or resolves it. At most 64 halvings plus one final exact-value
pass are possible. Total fallback work is O(65 * Q * log P), plus temporary grouping and
indexed execution discovery. SQLite may use a temporary grouping B-tree, with ordinary
O(Q log Q) sorting; it has no per-Field correlated rescan. Returned projections, rank state,
SQL text and retained endpoint results depend on configured/result Fields, never execution
or sample cardinality. There is no raw-sample cursor/List in this path. All queries stay
inside the original complete repository Room transaction.

### Fail-first and deterministic work comparison

Reproduce with `python docs/implementation/statistics_shared_lineage_probe.py`.
`--baseline-only` executes the prior fallback statements on the fixture and deliberately
asserts the shared traversal requirement: it fails at P=4 with four traced complete
execution/value selection statements. The original physical Room regression was added
before production changes. Controller must retain its fail-first device run against the
reviewed production implementation; no device task was invoked in this correction turn.

The host probe uses actual v10 tables/indexes and the production grouped SQL template
with the same balanced pivot expansion. It persists and reopens each SQLite file, holds
selected executions at 100, and varies populated Fields. Each Field has 6,000 old-Series
values and distinct immutable old/new snapshot Field IDs sharing stable source identity.
Sources are archived. Selected values include both signed Long extremes, zero, missing
values, odd medians and even rational medians. Every distribution is compared with an
independent sorted integer/Fraction reference. VM instructions use the SQLite progress
handler at every instruction; no wall-clock threshold is asserted. Counts below include
all lineage probes and median/rank reads (common summaries/candidate counts excluded):

| Kind | Fields | Former VM instructions | Shared VM instructions | Shared rank passes |
| --- | ---: | ---: | ---: | ---: |
| Activity | 4 | 72,126 | 70,546 | 3 |
| Activity | 8 | 265,192 | 156,188 | 3 |
| Activity | 16 | 1,014,300 | 338,986 | 3 |
| Activity | 24 | 2,247,376 | 533,680 | 3 |
| Sequence | 4 | 70,522 | 69,937 | 3 |
| Sequence | 8 | 261,992 | 155,579 | 3 |
| Sequence | 16 | 1,007,908 | 338,377 | 3 |
| Sequence | 24 | 2,237,792 | 533,071 | 3 |

Host SQLite 3.53.1. Every grouped plan has exactly three indexed SEARCH nodes, led by
`activity_executions_series_completed` / `sequence_executions_series_ended`, then the
execution-value composite primary key and snapshot-Field primary key. It uses a temporary
B-tree for grouping, with no correlated subquery or historical source-Field lineage scan.
The deterministic scaling assertion distinguishes logarithmic lookup growth from P
complete traversals. This is work evidence, not Android latency or native heap evidence.
A wide continuous numeric range may require more shared passes than this three-pass
fixture; the 65-pass upper bound is explicit, and no device timing threshold is invented.

### Regression scope and verification ownership

The new mandatory physical Room test covers both kinds and all four Field counts after
close/reopen through the canonical repository. It verifies count/missing/coverage, exact
signed totals/averages/odd/even medians/min/max and stable Field identities against a
small reference, then inspects actually executed SQL and plans. It rejects any former
per-Field execution-first median, requires one capped lineage probe, three shared rank
passes independent of P, and four binds. Inventory is 622 required data cases, 627
including five existing nonmandatory skips; all earlier B1/W1/B2 and mutation/concurrency,
failure/cancellation cases remain pinned. No existing testcase identity is removed.

Local host checks passed: the new shared-lineage probe, `statistics_review_probe.py
--rows 800 --fields 24 --repeats 1`, `statistics_b2_probe.py --rows 800 --fields 24
--repeats 1` and `statistics_s4_probe.py --rows 800 --fields 24`. The older probes continue
to exercise their scoped scalar SQL/selective paths; the new probe supplies simultaneous
multi-Field fallback evidence. They do not substitute for canonical Room execution.
Controller owns full affected suites, device fail-first/corrected execution, commits,
push and the independent unfiltered five-API exact-head CI. No merge was performed.

Local focused verification completed successfully with:
`gradlew.bat :domain:test :data:testDebugUnitTest :data:ktlintCheck :data:detekt
:data:assembleDebugAndroidTest --console=plain`.
The domain result was up to date; data JVM tests ran successfully, including all ten
ExactNumberSummaryTest cases. Changed AndroidTest sources compiled and the data test APK
was packaged. Earlier local runs identified formatting violations; the final command
passed after correction. `git diff --check` also passed. No device or GitHub CI result
is inferred from these checks.

## W1: one/two unresolved Number Fields (2026-10-19)

Reviewed head: `3cfc9e45c3986ca8692a252f6040b89ea078fe72`. This focused correction
supersedes F1's unconditional grouped fallback for small unresolved sets. After the
existing shared probe budget, zero remaining Fields need no work, one/two remaining
Fields use the existing execution-first scalar median, and three or more retain shared
grouped rank selection. The scalar override skips further lineage probes. The existing
single-populated-Field fast path and every individual Number Field reader are unchanged.

The cap is fixed at two, independent of total configured Fields, execution count and
history size. Each scalar median has an indexed eligible execution/value traversal,
an independent count traversal and native ordered selection. At most two such statements
(four eligible traversals plus native sorting) replace up to 65 grouped passes. Their
cost is O(T + V) per traversal plus exact native sorting; this does not reintroduce P
independent traversals or O(E * P^2) work as P grows. At three or more unresolved Fields
the F1 bound, balanced pivot lookup, fixed bind usage and result-sized application state
remain unchanged. No raw samples enter application collections, SQL/schema/index changes
or durable state are introduced, and all reads retain the enclosing Room transaction.

### Reproduction and deterministic evidence

`statistics_small_fallback_probe.py` creates private physical databases from the actual
exported v10 tables/indexes, enables foreign keys, seeds distinct old/new frozen snapshots
sharing source Field IDs, archives sources, commits and closes/reopens before reading.
The default fixture has 1,000 selected executions and 7,000 old executions per kind.
It covers a single populated shared Field (the unchanged fast path), two shared Fields,
and an exclusive first Field followed by one shared Field. The latter exercises a
successful Field-first query before a saturated probe. The broad signed samples reproduce
`java.util.Random.nextLong`, include both Long extremes, and require eight/nine grouped
passes instead of F1's three. Python integer/Fraction expectations independently verify
count, exact overflow-sized total, average, even/odd median, extrema and source identity;
coverage is 1,000/1,000 or 999/1,000. Individual summary/selection SQL gives the same result.

```powershell
python docs/implementation/statistics_small_fallback_probe.py
python docs/implementation/statistics_small_fallback_probe.py --rows 10000 --case mixed
```

The probe extracts actual production scalar SQL and grouped rank SQL. It mirrors the
bounded dispatch, recognizing the fixed small-set branch in the repository source;
it does not execute Kotlin. The Room regression below checks actual production dispatch.
The trace phase executes each selection once, separately from VM measurement and plan
inspection. Each measured VM instruction is counted using SQLite's progress handler
with interval one. Scalar plans start at the existing Series execution index, then
execution-value composite primary key, then snapshot Field primary key. Native sorting
uses an ORDER BY temporary B-tree. Successful Field-first plans use the existing source
Field and snapshot-Field value indexes. No correlated subquery or per-sample metadata
query is introduced. Scalar output stays one row per Field.

Host SQLite 3.53.1, fallback selection work only (common summaries, selected candidate
counts, shared probes and successful Field-first selections excluded from both columns):

| Kind | Selected executions | Remaining Fields | Previous grouped VM | Scalar VM | Previous passes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Activity | 1,000 | 2 | 913,274 | 136,154 | 9 |
| Sequence | 1,000 | 2 | 895,247 | 128,156 | 9 |
| Activity, mixed | 1,000 | 1 | 517,546 | 68,068 | 9 |
| Sequence, mixed | 1,000 | 1 | 499,519 | 64,069 | 9 |
| Activity, mixed | 10,000 | 1 | 7,471,058 | 680,446 | 13 |
| Sequence, mixed | 10,000 | 1 | 7,211,019 | 640,447 | 13 |

The 10,000-execution host case uses 70,000 old executions per kind. These are structural
host observations, not Android latency claims or wall-clock CI thresholds. Both corrected
commands passed. The unchanged single-Field case also passed with one scalar query.
Against the immutable reviewed repository source the following command failed for each
combination of kind (`activity`, `sequence`) and case (`two`, `mixed`), with nine traced
grouped reads instead of zero:

```powershell
python docs/implementation/statistics_small_fallback_probe.py --repository-ref 3cfc9e45c3986ca8692a252f6040b89ea078fe72 --kind activity --case two
```

### Physical Room regression and retained protections

`StatisticsRepositoryTest.smallNumberFallbackUsesScalarMediansAfterPhysicalReopen` was
added before production correction. It covers both kinds and all three small-set cases
with 1,000 selected / 7,000 old executions in isolated physical Room files. Expectations
are independently calculated with sorted test-owned values, BigInteger and ExactValue.
It checks stable typed Field identities, counts/missing/coverage, totals, rational average,
odd/even median and min/max; canonical `numberFieldStatistics` must match Series Detail.
Observed production SQL must contain no grouped rank read, exactly the expected scalar
selections, one shared saturated probe (two probes in the mixed case), and indexed plans.
The original F1 fixture helper keeps its default 100/6,000 cardinalities and values.

The new case is pinned in the mandatory inventory: 623 data cases, 628 including the
five existing nonmandatory skips. No earlier case is removed or weakened. Controller
owns fail-first Room execution against the reviewed production head, corrected physical
execution, all transaction interleavings/correction/deletion cases and the unfiltered
five-API exact-head CI. No device task was run in this correction turn.

The retained host checks passed: `statistics_shared_lineage_probe.py` (4/8/16/24 Fields,
three passes and the same VM counts recorded above), `statistics_review_probe.py
--rows 800 --fields 24 --repeats 1`, `statistics_b2_probe.py --rows 800 --fields 24
--repeats 1`, and `statistics_s4_probe.py --rows 800 --fields 24`. The inventory harness's
17 mocked unit tests also passed; their printed device commands are mocks and launch
no emulator. These checks do not certify physical Room or device execution.

Final focused local verification passed with:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :data:ktlintCheck :data:detekt :data:lintDebug :data:assembleDebugAndroidTest --console=plain
```

Data executed 29 JVM tests with no failures/errors/skips, including all ten exact-number
summary/rank tests. The unchanged domain suite was up to date (241 cases). The changed
AndroidTest sources compiled and the data test APK was packaged; ktlint, Detekt and
Android lint passed. Earlier Detekt method-length and ktlint formatting findings were
corrected before the successful final command. The B1/W1 structural host probe also
passed separately with `--rows 800 --fields 24 --structural-only`. All 31 Statistics
Room testcase names match the pinned inventory. `git diff --check` passed. Changes
remain uncommitted on the reviewed branch; no commit, push, PR or CI operation occurred.

## Round-two F1/F2: bounded small selection and fair probes (2026-10-19)

Reviewed head: `f12f55d51b57a60485cc7f6436cc2ad36561f96b`.
This section supersedes the earlier all-or-nothing probe allocation and unconditional
three-or-more-Field rank dispatch. It changes only the shared read policy and permits a
bounded partial rank trial. Scalar SQL, grouped rank SQL, indexes, persisted data and
individual Field policy remain unchanged. Every read remains in the original complete
Room transaction; no cache, migration, new identity or statistical semantics.

Each populated Field gets the same probe cap: floor((candidateLimit(Q)-1)/P)+1.
Success/failure consumes one Field slot, never another Field's allowance. The total is
at most 2Q+P candidates including every sentinel, independent of old-lineage cardinality.
Presentation ordering therefore cannot starve cheap Fields. This intentionally does not
reallocate successful Fields' unused allowances according to display order.

Up to three unresolved Fields use scalar selection directly. For 4..16 unresolved
Fields, run at most three shared rank passes and retain any resolved results, then use
at most sixteen scalar exact selections for the remainder. Duplicate-heavy 4/8/16/24
fixtures still converge in three passes with no scalar fallback. More than sixteen
unresolved Fields retain the full shared algorithm, bounded by 65 rank passes. The
constant scalar cap prevents unbounded O(PQ) fallback work; native sorting for the capped
scalar selections remains ordinary O(Q log Q) SQLite work. Application state is O(P),
with no history-sized sample cursor/List. Grouped queries still bind four values and
return at most P scalar rows; scalar/probe bind counts stay fixed.

The cap is supported by measurements across 3/4/8/16 and adjacent 17/24/32/40 Fields,
rather than assuming every large numeric range converges in three passes. On 500 selected
Activity executions, the full broad-range grouped/scalar VM ratios are 4.65 at three
Fields, 4.27 at four, 2.95 at eight, 1.77 at sixteen, 1.70 at seventeen and 1.30 at 24;
by 32 they are near parity. Paying a three-pass trial plus scalar work ceases to be
beneficial beyond the chosen small cap. Three unresolved Fields skip the trial entirely,
fixing the audited three-Field case with exactly three scalar selections. This policy does not claim globally optimal
SQL for every value distribution; it bounds both the small-case repeated work and the
large-case independent traversals while preserving the earlier cheap grouped path.

### Reproduction and fail-first evidence

```powershell
python docs/implementation/statistics_fair_fallback_probe.py --rows 500 --case wide
python docs/implementation/statistics_fair_fallback_probe.py --case fair
python docs/implementation/statistics_fair_fallback_probe.py --rows 1000 --fields 3
python docs/implementation/statistics_fair_fallback_probe.py --repository-ref f12f55d51b57a60485cc7f6436cc2ad36561f96b --rows 500 --kind activity --case wide
python docs/implementation/statistics_fair_fallback_probe.py --repository-ref f12f55d51b57a60485cc7f6436cc2ad36561f96b --kind activity --case fair
```

Before production correction, the corresponding cases failed at the intended F1 and F2
policy assertions (the immutable-reference commands above reproduce that strategy). The first traced eight grouped passes for three unresolved broad
Fields; the second traced one saturated probe, no Field-first medians and eleven rank
passes. This host harness extracts actual production SQL and mirrors the dispatch; it
does not execute Kotlin. The physical Room tests were extended before production fixes;
Controller owns fail-first device execution against reviewed production and corrected
execution. No emulator or device task was invoked locally.

All fixtures use real exported v10 tables/indexes with foreign keys, distinct old/new
frozen snapshot Field IDs sharing typed source identity, archived sources and physical
commit/close/reopen. The wide fixture keeps each old lineage longer than the full 2Q
budget, so all requested Fields are simultaneously unresolved. It compares scalar,
full grouped and chosen endpoints with independent sorted integer/Fraction references,
including odd/even missing counts, signed extremes, overflow-sized totals, rational
averages/medians and minima/maxima. Probe visits include failed sentinels. Scalar and
Field-first plans must start at the existing execution and source-Field indexes
respectively; grouped passes require exactly three indexed SEARCH nodes, no correlated
scan and one bounded result row per unresolved Field. SQLite progress callbacks count
every VM instruction. Common summaries/candidate counts/catalog/duration/Category reads
are excluded from both sides. These are native work comparisons, not Android timings
or arbitrary wall-clock CI thresholds. Oracle sample lists are test-owned.

### Wide-range work comparison

Both kinds passed all 3/4/8/16/17/24/32/40 comparisons. The final direct-three path was
rerun separately at 1,000 selected executions after selecting that fast path; the
4..40 dispatch and measurements are unchanged. Each row below includes probes and
chosen median work, excluding the common reads described above:

| Selected / Fields | Activity reviewed / corrected VM | Sequence reviewed / corrected VM | Corrected ranks / scalar fallbacks |
| --- | ---: | ---: | ---: |
| 1,000 / 3 | 1,631,625 / 318,370 | 1,613,598 / 306,365 | 0 / 3 |
| 500 / 4 | 959,357 / 629,919 | 950,342 / 618,906 | 3 / 4 |
| 500 / 8 | 2,291,248 / 1,654,197 | 2,282,233 / 1,635,188 | 3 / 8 |
| 500 / 16 | 5,077,082 / 4,751,735 | 5,068,067 / 4,716,734 | 3 / 16 |
| 500 / 17 | 5,460,180 / 5,460,981 | 5,451,189 / 5,451,954 | 9 / 0 |
| 500 / 24 | 8,157,296 / 8,158,352 | 8,148,305 / 8,149,325 | 9 / 0 |
| 500 / 32 | 11,135,659 / 11,137,001 | 11,126,668 / 11,127,974 | 9 / 0 |
| 500 / 40 | 14,078,596 / 14,080,220 | 14,069,605 / 14,071,193 | 9 / 0 |

At 1,000/3, exact scalar work alone is 264,240/252,243 instructions for Activity/Sequence;
full grouped work is 1,577,365/1,559,374 over nine passes. All endpoints match independent
expectations. At seventeen and above the additional bounded fair-probe setup is below
0.02%, with the same shared median work, rather than moving the repeated rank regression
to the next Field count. The small-set trial is an explicit compromise: it preserves the
previous three-pass duplicate-heavy wins while improving broad 4/8/16-Field workloads;
it is not represented as universally cheaper than the counterfactual scalar-only path.
The original duplicate-heavy 4/8/16/24 host regression also passes unchanged.

### Fairness work comparison

Host SQLite 3.53.1, 1,000 selected executions, 24 populated Fields, 50,000 old values
only for the expensive Field. Current source labels place it first and last without
rewriting history. Both corrected orders use 24 fair probes, 23 Field-first scalar
medians, one execution-first scalar median and no rank passes:

| Kind / order | Reviewed VM | Corrected VM |
| --- | ---: | ---: |
| Activity / expensive first | 18,188,456 | 1,610,205 |
| Activity / expensive last | 1,816,793 | 1,610,099 |
| Sequence / expensive first | 18,166,423 | 1,514,261 |
| Sequence / expensive last | 1,720,865 | 1,514,171 |

The roughly 0.007% corrected order difference is preparation/reprepare bookkeeping,
with identical selected query shapes and facts; the probe rejects a 0.1% work change.
Expensive-first work improves about 11.3x/12.0x for Activity/Sequence. The physical Room
case additionally streams a SHA-256 fingerprint of all execution, recorded-value,
snapshot and snapshot-Field rows before reopen and after each rename/read, compares
stable typed IDs and exact distributions, preserves individual-reader parity, and checks
Day attribution against All time. It inspects actually executed SQL and its plans.

The original small-fallback physical case now also covers 3/4/8/16/17/24 broad Fields,
asserts total probe visits <=2Q+P and compares Day reads. All earlier case identities,
including duplicate-heavy shared 4/8/16/24, one/two Fields, exclusive dense/sparse,
unused metadata, narrow periods, mutation, transaction consistency and cancellation,
remain pinned. The inventory contains 624 mandatory data cases (629 including the five
existing nonmandatory skips), including all 32 StatisticsRepositoryTest methods.
Controller retains the complete unfiltered five-API suites and independent exact-head CI.

### Final local verification

The final production/read-policy verification passed:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :data:ktlintCheck :data:detekt :data:lintDebug :data:assembleDebugAndroidTest --console=plain
```

Data ran 30 JVM tests with zero failures/errors/skips. Domain was up to date (241
successful cases in its reports). ktlint, Detekt, Android lint, changed AndroidTest
compilation and data test APK packaging passed. A final strengthening of the partial
rank unit test mixes an already-resolved constant Field with broad unresolved Fields;
the follow-up below passed after that test-only edit:

```powershell
.\gradlew.bat :data:testDebugUnitTest :data:ktlintCheck :data:detekt --console=plain
```

Earlier local runs found Detekt constants/line-length and ktlint formatting issues;
these were corrected before the successful final commands. No test was removed or
weakened. The data AndroidTest APK contains the corrected direct-three production
strategy and both physical regressions. APK compilation is not device execution.

Additional retained checks passed:

```powershell
python docs/implementation/statistics_small_fallback_probe.py --rows 1000
python docs/implementation/statistics_shared_lineage_probe.py
python docs/implementation/statistics_b2_probe.py --rows 800 --fields 24 --repeats 1
python docs/implementation/statistics_review_probe.py --rows 800 --fields 24 --structural-only
python -m unittest discover -s .github/scripts -p test_run_instrumentation.py
```

The last command ran 17 mocked harness tests; its printed device commands are mocks,
not emulator launches. The explicit immutable-head Sequence F1 check also failed at
the intended regression assertion with eight grouped passes:
`statistics_fair_fallback_probe.py --repository-ref f12f55d51b57a60485cc7f6436cc2ad36561f96b --rows 500 --kind sequence --fields 3`.
All 32 Statistics Room method names exactly match the pinned inventory, and
`git diff --check` passed. No managed-device task, GitHub polling, commit, push, PR
operation or merge was performed. Changes are left uncommitted for Controller review,
physical fail-first/corrected execution, and the mandatory independent five-API gate.
