# Android compatibility release gate

## Template archive and Folder deletion accessibility (Slice S3)

Adds nine lifecycle presentation identities and four isolated production-route
physical-reopen identities. The mandatory inventory now requires **490 app,
627 data and 2 independent startup cases per API**; all earlier identities remain.
See [S3 lifecycle evidence](library_destructive_accessibility.md) for exact tests,
dialog/focus admission, canonical archive/subtree/rejection assertions and local
verification. Existing large-subtree safe-bind, hidden occupancy and rollback
repository regressions remain mandatory. Controller owns all unfiltered API
26/31/33/35/36 gates, independent startup and exact-head GitHub evidence. Compilation
and automated semantics assertions do not certify manual TalkBack/Switch Access.

Controller API 35 attempt 2 completed all 490 app cases with six failures; all
eleven Library production-route cases, including the four S3 physical-reopen
journeys, passed. The authorized repair probe subsequently passed all six failing
identities after dialog-owner, native-window synchronization, locale and scaled
target-layout corrections. See the S3 record for all four probes and their exact
artifacts. The full repaired-tree five-API matrix and independent exact-head gates
remain Controller-owned requirements; the mandatory inventory is unchanged.

## Non-destructive Library organization accessibility (Slice S2)

Adds nine organization presentation identities and three isolated production-route
physical-reopen identities. The mandatory inventory now contains **477 app,
627 data and 2 independent startup cases per API**, with zero prior removals.
See [S2 organization evidence](library_organization_accessibility.md) for exact
test names, local checks, typed mutation admission, focus and canonical reopen
assertions. All unfiltered API 26/31/33/35/36 gates, independent production startup
and exact-head GitHub evidence remain Controller-owned requirements. Source/APK
compilation and automated semantics assertions do not certify manual TalkBack or
Switch Access operation.

## Library discovery accessibility (Slice S1)

Adds eight Library presentation identities and four isolated production-route
identities. The pinned inventory now requires **465 app, 627 data and 2 independent
startup cases per API**. Every previous identity remains mandatory. See
[library_accessibility.md](library_accessibility.md) for target boundaries, duplicate
typed identities, keyboard recovery, localization/scaling, exact testcase names and
local verification. Controller owns fail-first/corrected device evidence and the
unfiltered API 26/31/33/35/36 gates plus exact-head GitHub CI. AndroidTest compilation
and automated semantics are not manual TalkBack/Switch Access certification.

Delivery-plan item 7 and the S5 Parent contract define this gate. Product v0.16, domain v0.10, database v0.6 and the implementation clarifications remain authoritative. These checks preserve schema v10, durable identities, runtime semantics and existing permission UX.

S3 expanded Sequence accessibility evidence is described in [expanded_sequence_accessibility.md](expanded_sequence_accessibility.md).
S4 focused Plan Execution accessibility evidence and pending manual validation are described in [plan_execution_accessibility.md](plan_execution_accessibility.md).

S5 cross-route evidence and the assistive-service walkthrough are described in [primary_execution_accessibility.md](primary_execution_accessibility.md).

## Automatic CI and required jobs

[CI](../../.github/workflows/ci.yml) automatically runs complete data and app instrumentation at **all five APIs** on pull requests to main, pushes to main, workflow dispatch and reusable workflow calls. Every row is required:

| Required check name | API | Managed device | Required modules |
| --- | --- | --- | --- |
| Build, test, and static analysis | JVM/build | — | domain, data, app |
| Managed-device instrumentation tests | 35 | pixel2Api35 | data, app, startup |
| Managed-device instrumentation tests (API 26) | 26 | pixel2Api26 | data, app, startup |
| Managed-device instrumentation tests (API 31) | 31 | pixel2Api31 | data, app, startup |
| Managed-device instrumentation tests (API 33) | 33 | pixel2Api33 | data, app, startup |
| Managed-device instrumentation tests (API 36) | 36 | pixel2Api36 | data, app, startup |

The matrix uses separate CI runners per API, with fail-fast disabled. Within a row, [run-instrumentation.py](../../.github/scripts/run-instrumentation.py) runs data, the full isolated app suite, and the independent production-startup app suite in separate sequential Gradle invocations, even after module failure, avoiding shared AVD setup/teardown races. It supplies the expected API and uses no class/method filters, no build cache, rerun-tasks, no configuration cache, no parallel tasks and at most two workers. API 26 explicitly provisions the emulator with sdkmanager from the configured SDK. AGP's old-API managed-device opt-in remains enabled; minSdk is 26 and targetSdk is 36.

Checkout and runner verification use the PR head SHA (or github.sha for other events). The runner rejects a checkout mismatch before Gradle. evidence.json distinguishes tested SHA from GitHub event SHA, which may identify a merge ref.

## PR38 W1 canonical reload correction

The mandatory inventory adds five expanded Sequence screen cases and five controller-backed
physical-database cases, covering RUNNING_CURRENT, PAUSED_CURRENT, WAITING_NEXT,
RUNNING_TRANSITION_COUNTDOWN and PAUSED_TRANSITION_COUNTDOWN. It now requires **441 app,
627 data and 2 independent startup cases per API**; every previous identity remains required.
The new cases hold and fail a refresh after successful loading, exercise retained callbacks
and open menus/dialogs, recover against a changed canonical state, and verify durable facts
after physical close/reopen. Screen cases check merged/unmerged actions and native keyboard
Enter activation of Back, Retry and the newly authorized command.

See the W1 record in [expanded_sequence_accessibility.md](expanded_sequence_accessibility.md).
The Controller must execute the unfiltered API 26/31/33/35/36 gates and exact-head GitHub CI.
AndroidTest compilation and the mocked runner tests do not establish device execution.
Manual TalkBack/Switch Access validation remains unperformed.

## PR38 W2 same-runtime foreground revalidation

Adds seven expanded Sequence screen and three controller-backed physical-database cases.
The inventory now requires **451 app, 627 data and 2 independent startup cases per API**.
All W1 identities, the existing EN/RU scaling cases and all five API gates remain required.
Held semantic-generation refreshes cover retained Runtime Add values/placement, native
Number/Text and deep-occurrence keyboard focus/scroll, safe invalidation, explicit dismissal,
disposed callbacks and strict confirmation invalidation. Physical reopen checks retain
execution/occurrence/snapshot identity, Plan linkage and unrelated history; restoration
itself performs no writes. See the W2 record in
[expanded_sequence_accessibility.md](expanded_sequence_accessibility.md).

Device fail-first/corrected execution and exact-head CI remain Controller-owned. Local
AndroidTest compilation is not execution evidence. Manual TalkBack/Switch Access validation
remains unperformed.

## PR38 W2 pre-disposal Runtime Add admission correction

Adds mandatory physical-database one-off and Template-selection admission races;
the existing semantic-generation foreground-refresh case remains required. The new
cases invoke the captured enabled semantics callback in the same UI turn as refresh,
before dialog disposal. Both retain the populated
Timer draft and placement, reopen the database before admission to prove no write,
then explicitly admit exactly one Add after identical canonical revalidation.
The inventory now requires **453 app, 627 data and 2 startup cases per API**.
Existing invalidation cases also exercise captured Add callbacks for changed current,
invalid placement, failed/stale reads and explicit Cancel/Back. All W1/W2 cases remain
mandatory. Device fail-first/corrected execution, unfiltered five-API suites and
exact-head CI remain Controller-owned; compilation is not device execution evidence.

## Mandatory original-JUnit inventory

[instrumentation-inventory.json](../../.github/scripts/instrumentation-inventory.json) pins full classname and testcase names, including every parameterized variant. It requires 627 data, 453 isolated app, and 2 independent startup cases per API. S3C1 adds two expanded Sequence presentation cases and two physical value-save/completion reopen cases; the existing Runtime Add reopen case now selects between duplicate catalog names. Only the actualApi parameter is substituted for the selected device. The inventory is committed independently of discovery and reports; never regenerate it automatically from the current execution or lower it to accommodate missing evidence. Review inventory updates with additions or intentional renames of required tests.

S4 adds 17 mandatory app identities: eight focused Plan presentation/semantics/localization/scale cases and nine controller-backed physical-database persistence cases. No existing mandatory identity is removed. Local verification on 2026-10-20 passed 405 app JVM cases (zero failures/errors/skips), app ktlint, Detekt, lintDebug and AndroidTest APK compilation/assembly; the exact command and manual walkthrough are recorded in the S4 document. Local JVM/static checks and AndroidTest compilation/packaging are implementation evidence only; the unfiltered five-API suites, independent startup and exact-head GitHub CI remain Controller-owned gates.

S5 adds 14 mandatory app identities: 13 production-navigation/physical-reopen journeys
and one Daily keyboard transition regression. The S5 inventory required **424 app,
627 data and 2 independent startup cases per API**. All S1?S4 identities remain pinned.
Controller check aliases remain `full` and `gmd-full`; they are labels, not Gradle tasks.
Controller owns authoritative checks, independent production startup, and exact-head
GitHub execution at APIs 26, 31, 33, 35 and 36. S5 does not close the Parent: the
Decomposer must perform the separate full actual-repository closure audit.

The first S5 API 35 run reached all 424 app cases and failed nine new production
journey cases. The shared fixture repair preserves every identity and assertion;
the authorized scoped probe subsequently passed all nine. See the linked S5 record
for timestamp-token, route-owner, Back synchronization and fixed-scale Number details.
This scoped PASS does not replace the required unfiltered suites or exact-head CI.

S5C1 adds seven mandatory app identities: four Daily focus/disambiguation cases,
one Plan disambiguation case, and two duplicate-Plan production-route/reopen journeys.
The S5C1 inventory required **431 app, 627 data, and 2 independent startup cases
per API**. Every previous mandatory identity remains required. Duplicate labels and
native focus changes are presentation only; see the S5C1 record in
[primary_execution_accessibility.md](primary_execution_accessibility.md).
Controller must execute `full` and `gmd-full`, independent production startup, and the
unfiltered exact-head GitHub matrix at APIs 26/31/33/35/36. These S5C1 gates are pending;
source/APK compilation cannot certify device execution.

The first S5C1 API 35 `gmd-full` at head
`5d24fa18e737fcae8d91b6732369f6a38b141e2c` completed all 431 app cases with one
date-change focus fixture failure. Data XML reports 627 passes and five existing
ignored skips, zero failures/errors. A diagnostic probe confirmed native fallback
focus during Loading; the corrected fixture establishes surviving History focus
before terminal publication and verifies its preservation. The authorized second
probe passed the exact failing case (one test, zero failures/errors/skips). See the
linked S5C1 record for original XML paths and local checks. All inventory identities
and counts are retained. The full unfiltered rerun and independent final gates remain
Controller-owned; the scoped probe is not a release-gate PASS.

Every required case must appear exactly once and execute successfully in original JUnit XML. Missing suites/cases, skips, failures, errors, malformed identity/XML, duplicate required identity, inconsistent XML counts, unsuccessful Gradle exit, or wrong/missing observed API reject the module. An API marker by itself cannot pass. Nonmandatory ignored fixtures may remain skipped (the data suite currently emits five); skips are counted and retained. No unrelated skip is rejected globally. Any reported failure or error still fails the module.

The pinned inventory includes all existing nonignored instrumentation, encompassing:

| Boundary | Required coverage |
| --- | --- |
| Production database | ProductionDatabaseEntryPathTest: physical fresh v10, authentic v1 full registered chain, integrity/seed parity, canonical close/reopen and nondestructive failed upgrades |
| Historical upgrades | All adjacent migration tests and HistoricalFrozen/Terminal/Active/PlanProductionMigrationTest: historical configurations, provenance, ownership, terminal accounting and Plan fulfillment |
| Canonical readers | Library, Daily, History, Plan, Statistics and active-runtime repository suites, missing No-live durations and no double-counted Sequence children |
| Runtime recovery | RuntimeCoordinatorRecoveryTest, RuntimeReceiverRecoveryTest, RuntimePreflightRecoveryTest and StandaloneActivityRecoveryTest: paused/current/no-current recovery, logical deadlines, stale/duplicate signals and original-zone facts |
| Production app | LifeTracingAppTest, ProductionLauncherCoordinationTest, PlanExecutionPersistenceTest and navigation/persistence regressions |
| Platform services | RuntimeCapabilityAndroidTest and RuntimePlatformAndroidTest: real AlarmManager registration/cancellation, notification posting/replacement/cancellation, denial, PendingIntent payload/identity and vibration |
| Actual device | Both AndroidCompatibilityDeviceTest classes: observed SDK, expected-API assertion and fingerprint logging |

S1C1 replaces the obsolete duration Cursor cleanup case with
`StatisticsRepositoryTest.durationSelectionRejectsCountMismatchAndInvalidCachedValues`.
The bounded scalar selection no longer exposes a Cursor; the replacement pins independent
count/median consistency and invalid persisted durations. The other two S1 additions and
the pre-S2 mandatory data count of 609 remain unchanged. S2 adds four Number reader cases
(eligibility/periods, growing history/query cardinality, physical reopen/deletion and
malformed storage), bringing the S2 mandatory data count to 613. S3 adds two Series Detail cases
(raw-sample rejection and increasing-history result/query bounds), bringing the S3
mandatory data count to 615. Existing mixed-Field fixtures now cover exact signed totals,
empty Number/Category Fields and Text descriptors; the physical reopen/deletion and
malformed-storage cases also exercise canonical Series Detail. The five-API gate and
independent startup inventory remain mandatory; host probes and APK compilation do not
certify device execution.

## S4 Statistics integration additions

S4 adds five mandatory `StatisticsRepositoryTest` identities: mixed physical reopen with
supported correction/deletion, separate-connection correction/deletion/child-deletion
interleavings, and read failure/cancellation without durable writes. Data now requires
620 mandatory cases (625 including the five existing nonmandatory skips); app and
independent startup remain 323 and two. Every original case is retained. See
[the S4 closure evidence](statistics_s4_evidence.md) for the independent ledger,
transaction gates, read graph, host-only comparison and remaining acceptance risks.
Compilation and host probes do not certify these new Android cases. Controller must
execute the complete unfiltered inventory at all five APIs and exact-head GitHub CI.

## System alarm and notification evidence

The real alarm regression reads pending RTC_WAKEUP records from dumpsys alarm, scoped to this app and the production deadline action. It checks the requested epoch deadline, one-shot registration, actual exact/inexact batching window, framework idle-allowance flags and disappearance after production cancellation. The pending alarm must carry FLAG_ALLOW_WHILE_IDLE (0x04) on the authorized exact path, including API 31, and FLAG_ALLOW_WHILE_IDLE_COMPAT (0x20) on the API 35/36 inexact fallback. Unrestricted exemptions (0x08) fail this controlled fixture because they could mask a regression to set()/setExact(). These constants and the S+ inexact conversion follow [AOSP AlarmManager](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/apex/jobscheduler/framework/java/android/app/AlarmManager.java) and [AlarmManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java). Batching-window assertions alone cannot establish idle allowance. A negative control creates only the PendingIntent and requires no pending alarm. Historical statistics/removal records are excluded. The complete matrix requires fresh-install capability state: exact authorized at APIs 26/31/33 and unavailable at APIs 35/36, where real inexact fallback is observed. Unexpected capability state fails instead of silently losing a mode. Tests do not mutate exact access: Android revocation can kill instrumentation. Controller must use isolated fresh GMD installations without pre-granting exact access. Existing PendingIntent replacement, no-update control, logical-deadline and stale/duplicate regressions remain required.

The notification regression invokes the production publisher with a canonical running Activity from an isolated physical database, then completion, then cancellation. NotificationManager.activeNotifications must expose ID 1001, channel active_runtime, runtime/completion text, ongoing/only-alert-once flags, privacy and stopwatch category. Bounded observation waits for actual asynchronous system state. All durable database facts must remain identical. Below API 33 the test temporarily allows and restores POST_NOTIFICATION app-op. At API 33+ it scopes UiAutomation's real POST_NOTIFICATIONS shell-permission delegation and drops it in finally; no persisted grant/revoke or permission flags change. It checks restoration of original permission/capability. A preceding runtime notification now rejects the fixture before any permission or publication changes; it is never cancelled and reposted. Existing injected denied/disabled nonpublication tests remain required at every API.

These assertions must reject mutations suppressing production AlarmManager registration or AndroidRuntimeNotificationBackend.post(). Controller owns mutation execution on disposable checkouts and leased devices: suppress setExactAndAllowWhileIdle at API 31, suppress setAndAllowWhileIdle at API 35, and independently make post() a no-op at API 33. Run the corresponding new test, retain expected failure and original XML/log, discard the mutation, then execute the clean exact-head gate. Token creation and recording backends cannot substitute for evidence. No mutation is published.

## Manifest receiver and cold production acquisition

RuntimeManifestDeadlineAndroidTest requires the real manifest boundary on every API. The manifest test sends an unmodified production-created PendingIntent with no receiver-permission suppression, no dynamic receiver and no injected launcher/coordinator. It asserts the explicit RuntimeDeadlineReceiver component, action and absence of FLAG_RECEIVER_REGISTERED_ONLY. The instrumentation runner supplies a LifeTracingApplication subclass that scopes database filenames and lazy graph ownership to a disposable physical database; the owner invokes the actual production graph factory. The first stale PendingIntent cold-acquires that graph through the manifest receiver. Production repositories, clocks and platform adapters remain real. A stale token preserves future live facts; a matching late token completes at its logical deadline; duplicate/stale tokens preserve completed facts. A new production Room connection validates canonical History and active-runtime state before cleanup cancels the owned scope, closes its runtime Room connection and deletes the database and sidecars. No cleanup completes an execution. Outside a disposable scope the test Application delegates to normal application ownership and filenames.

Isolation regressions snapshot every user table in the ordinary database through a read-only SQLite connection (or assert it stays absent), plus the main database file inventory and original graph owner, before/after acceptance. Baseline inspection never runs Room creation/migration callbacks; transient SQLite journal sidecars are excluded from that inventory, while each disposable file and its sidecars must be deleted. Separate disposable baselines contain unrelated Plan-linked RUNNING and PAUSED sessions. The same acceptance helper runs twice against each baseline, with complete table equality and canonical runtime/PLANNED Plan equality checked after physical reopening. Injected exceptions immediately after the fixture's live write and after manifest delivery prove cleanup deletes an unfinished fixture, cancels an armed graph and restores the baseline/owner. Teardown joins graph cancellation in a non-cancellable context before closing and deleting the file. These tests are mandatory in the CI testcase inventory. Their assertions detect the former shared-database fixture's retained Library/History/Statistics rows and non-owned active-session completion/Plan fulfillment. Device fail-first and corrected execution belong to the Controller; AndroidTest compilation alone is not execution evidence.

The separate cold-acquisition test starts with a closed, uniquely named physical fixture and an unused RuntimeGraphOwner backed by the actual production graph factory. Only the database filename is redirected; repositories, clocks and Android adapters remain production components. It waits for production broadcast dispatch completion, cancels the graph scope, closes the runtime repository's actual Room owner, and validates canonical History and active-runtime state after reopening. An internal factory observer exposes that owner solely for test cleanup; it substitutes no dependencies and leaves normal construction unchanged. Cleanup does not intercept Context.openOrCreateDatabase(), which newer Android SQLiteOpenHelper implementations bypass. Instrumentation does not terminate its own process, and these separate assertions do not claim a process-death end-to-end test. Existing injected graph/goAsync/failure tests remain required.

Controller must execute three independent fail-first mutations in disposable checkouts: add FLAG_RECEIVER_REGISTERED_ONLY in RuntimeDeadlineIntentCodec.intent(); replace setExactAndAllowWhileIdle() with setExact() at API 31; replace setAndAllowWhileIdle() with set() at API 35. The manifest test and realAlarmManagerRegistersTheAuthorizedModeAndCancellationRemovesIt respectively must fail. Restore all production calls/flags, retain original XML/log evidence, then run all five clean API jobs and exact-head CI. This correction turn adds assertions; device and mutation results remain Controller-owned and are not inferred from compilation.

## Manifest-selected Application startup (checkpoint 61 F1)

The ordinary app suite retains RuntimeIsolationTestRunner and its disposable receiver/database fixtures.
Its installed-target metadata assertion now requires LifeTracingApplication independently of the substituted
Application instance, using the API-appropriate PackageManager overload. Metadata alone is insufficient:
every API row also runs a separate app invocation with
`-Plifetracing.compatibility.productionStartup=true`. That invocation uses unmodified AndroidJUnitRunner,
which instantiates the manifest-selected Application; no newApplication override or Application subclass
participates. Gradle selects only ProductionApplicationStartupTest, the device API marker, and existing
platform-isolation helpers copied by a tracked-input Sync task into a generated source directory. There are no method/class runner filters. The debug package suffix is
`.debug.startup` for this invocation only, so its ordinary `lifetracing.db` and platform slots belong to
a disposable installation, independently of the regular `.debug` package.

Only this invocation's debug manifest overlay disables RuntimeRecoveryReceiver. CI API 35 logcat
showed `runtime_recovery_started` before test discovery on a newly installed package: installation
freshness does not prevent boot/time/permission recovery broadcasts from opening the database.
The overlay excludes those competing recovery triggers before the process starts, while leaving the
manifest Application, MainActivity and foreground lifecycle wiring unchanged. Ordinary debug and
release manifests retain the enabled receiver; the ordinary app suite covers receiver recovery.
The startup case asserts that the receiver is disabled before checking database and platform vacancy.

The test requires the installed declaration and actual framework Application to be exactly
LifeTracingApplication. It requires a fresh database and vacant platform slots before acquiring the
existing package-wide lease. Production repositories persist an overdue RUNNING Timer, close their
physical Room connection, then launch the real MainActivity. Only the production Application lifecycle
callback triggers recovery: the test never calls onForeground(), dispatches a receiver, or arms an alarm.
A bounded semantic-generation observation waits for an advance beyond the value captured before launch, so an earlier reconciliation cannot satisfy the wait. After closing the Activity, a new physical
Room connection verifies canonical History identity, snapshot, original zone, completion at the stored
logical deadline, duration, and cleared active ownership. A second foreground launch preserves an overdue
but PAUSED Timer and its active slot, observed after bounded semantic-generation publication. Both
fixtures establish their own explicit prerequisites within this single isolated acceptance case.

Finally the graph scope is cancelled and joined before the existing platform lease releases its slots.
Seed/verification connections are closed; the framework-owned production graph's connections remain
owned by the dedicated process until teardown. The test never deletes an open production database.
The GMD invocation owns installation/uninstall; a manual retry must use a fresh dedicated installation.
This is framework lifecycle and physical connection-reopen evidence, not a process-kill test.

The CI script retains the startup log, original JUnit XML under `startup/`, and a separately mandatory
`startup` record in evidence.json. It must pass even when the full isolated suite succeeds. To reproduce,
run the ordinary API command above, then run the same app GMD task in a separate invocation with the
productionStartup property (and the same expectedApi/device flags). To compile without a device:
`./gradlew :app:assembleDebugAndroidTest -Plifetracing.compatibility.productionStartup=true`.
Controller must mutate only the production manifest Application to `android.app.Application` in a
disposable checkout, run this startup invocation and retain its failing original XML/log. Restore the
correct declaration, then rerun the clean five-API exact-head gate. Compilation does not establish
mutation or device execution evidence. Canonical finding: finding-030f00ac-7ce7-52a3-9fc4-0923e33ae898.

## Package-wide platform isolation (checkpoint 57 W1)

Disposable database ownership does not isolate AlarmManager or NotificationManager. Before installing a manifest environment, a test-only platform lease checks the production token with FLAG_NO_CREATE and checks the untagged runtime notification slot. Either existing resource fails closed before database redirection, graph creation, token update, permission changes or teardown. Failed acquisition performs no cancellation. The lease also rejects concurrent fixture ownership; accepted fixtures require an exclusively leased installation with no concurrent foreground/application runtime producer. Cleanup runs after graph cancellation and repository closure, and cancels only slots found empty at acquisition and reserved for that fixture. Production identities, codecs, schedulers, publishers and manifest receiver routing are unchanged.

Database-only baselines cannot acquire a runtime graph. Four mandatory regressions establish a real preexisting timed Activity alarm plus notification, a Plan-linked timed alarm plus notification, a stopwatch notification without an alarm, and an unscheduled production token. They attempt normal acceptance and injected failures after the live write and after manifest delivery. Each attempt must reject before either hook; finally assertions check the original token identity and delivered payload, actual AlarmManager object/epoch target/window/idle flags/operation, notification key/postTime/payload/visibility, every durable table, canonical runtime and Plan after reopening, and cold baseline graph ownership. The setup cleans only its own token and notification. Existing empty-slot success and failure tests continue exercising actual manifest delivery and disposable-file cleanup, preserving the previous F1 coverage.

The same lease protects sibling platform/capability tests and the separate cold-production-graph test. The notification permission helper rejects an occupied notification before changing permissions; it no longer captures, cancels and reposts a preceding notification. No unconditional @After alarm/notification cancellation remains in these test paths.

Controller must retain fail-first device evidence by removing only the requireVacant(context) call in RuntimePlatformFixture.acquire in a disposable checkout, leaving cleanup and production adapters intact. Each occupied-state regression must fail (the timer/token cases expose FLAG_UPDATE_CURRENT replacement and alarm cancellation; stopwatch exposes unconditional notification cancellation). Also independently remove only the requireNotificationVacant(context) call in requireVacant to establish notification-only sensitivity. Each clean regression also runs successful manifest acceptance after removing its setup-owned resources, proving rejected acquisition releases ownership without relying on test order. Restore preflight, then execute the original unfiltered five-API inventory and exact-head CI. Local compilation is not fail-first or corrected device execution evidence; those runs remain Controller-owned.

## Evidence and execution status

Each API uploads these artifacts even on failure, retained for 30 days:

- `instrumentation-evidence-api<API>-attempt<ATTEMPT>`: `ci-evidence/api<API>/evidence.json`, data.log, app.log, startup.log and byte-for-byte original per-invocation TEST-*.xml with SHA-256 digests.
- `instrumentation-reports-api<API>-attempt<ATTEMPT>`: the same evidence plus each module's build/reports, build/outputs/androidTest-results and managed-device additional output.

Original per-device XML is under `<module>/build/outputs/androidTest-results/managedDevice/debug/pixel2Api<API>/`. HTML is under `<module>/build/reports/androidTests/managedDevice/debug/pixel2Api<API>/`. Each module's AndroidCompatibilityDeviceTest name records actual SDK; system output/logs record fingerprint. evidence.json records commands, timestamps, exit codes, API observation, mandatory inventory certification, counts, SHA and report hashes. Compare all three invocation observations with the selected API. Combined allDevices reports, compilation and cached ignored outputs are insufficient evidence.

Controller independently inspected [CI run 39078394582](https://github.com/Alexandr5476/life_tracing/actions/runs/39078394582) at reviewed head 29d8d5458e7d5d2c694b6b7fb5a3132f3751b6a2: every API ran both modules; data had 611 cases including five nonmandatory skips, app had 312, with zero failures/errors. This historical complete execution keeps original B1 closed. It predates the two new system-service assertions.

At the 2026-10-18 checkpoint-50 review, Controller verified [CI run 39083767523](https://github.com/Alexandr5476/life_tracing/actions/runs/39083767523) for exact head 1d9eb6f9a7ba556673258aecd8e572ce45a63352. All five API jobs and the build/static/JVM job passed. At each API, data reported 611 cases (606 mandatory PASS and five nonmandatory skipped fixtures); app reported 314 mandatory PASS with zero skips. There were zero failures/errors, and both new system-service regressions passed at all five APIs. Controller independently verified the tested SHA, observed SDK, original XML digests and runner evidence.

For each subsequent head, inspect the latest exact-head GitHub Actions jobs and their original report/evidence artifacts as the authoritative execution record. Match evidence.json's tested SHA to the head under review and require all checks below; these dated historical runs do not certify later commits. Earlier implementation-turn pending or NOT_RUN statuses describe only those earlier turns. Controller retains responsibility for verification of each corrected head, device/mutation execution and final closure.

## Controller responsibilities and reproduction

Controller owns commit/push, device leases/provisioning/cleanup, canonical local execution, mutation evidence, exact-head GitHub checks and final Parent closure review. Require all six named CI checks and all fifteen invocation/API results (data, app, startup at each API) on the corrected SHA. No approval or merge is authorized here. Preserve the precise API/image/ABI and error for provisioning failures. Failures after application/testcase entry are executed failures; a module never reached has no execution result.

Prerequisites are JDK 21, Android SDK platform 36/build tools, accepted licenses, hardware virtualization (KVM/WHPX), sufficient disk/network for each AOSP image and exclusive leases. For local execution run one API at a time, replacing 31 below with 26/33/35/36:

```powershell
cmd.exe /d /s /c "gradlew.bat :data:pixel2Api31DebugAndroidTest :app:pixel2Api31DebugAndroidTest -Plifetracing.compatibility.expectedApi=31 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --rerun-tasks --no-build-cache --stacktrace"
```

Run the independent startup path in a **separate** invocation on the same selected API:

```powershell
cmd.exe /d /s /c "gradlew.bat :app:pixel2Api31DebugAndroidTest -Plifetracing.compatibility.productionStartup=true -Plifetracing.compatibility.expectedApi=31 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --rerun-tasks --no-build-cache --stacktrace"
```

For automatic Linux certification the workflow supplies EXPECTED_TEST_SHA and GitHub metadata and executes `python3 .github/scripts/run-instrumentation.py --api <API>`. Preserve original results and apply the same inventory and API/SHA checks to local results. Do not use filters for release acceptance.

Non-device checks (no emulator):

```powershell
python -m unittest discover -s .github/scripts -p "test_*.py"
.\gradlew.bat :data:assembleDebugAndroidTest :app:assembleDebugAndroidTest
.\gradlew.bat ktlintCheck detekt lintDebug :domain:test :data:testDebugUnitTest :app:testDebugUnitTest coverageReport coverageVerify :app:assembleDebug --stacktrace
```

## Archived implementation records

The [pre-checkpoint-49 document](archive/android_compatibility_gate_pre_checkpoint49.md) preserves earlier implementation-turn NOT_RUN tables and the W1-W3 handoff verbatim. Those statuses and instructions are historical, superseded by this document and current workflow. They are not current outcomes and do not reopen closed B1.

## PR37 Statistics query review correction

Adds mandatory `StatisticsRepositoryTest.numberQueriesRestrictTinySeriesAndNarrowPeriodsBeforeFieldLineageAfterReopen`. Data now requires 621 mandatory cases (626 including five existing nonmandatory skips); app/startup counts and all five API gates are unchanged. The existing growing Number-reader regression also asserts noncorrelated bit-count plans and execution-first median plans. The new case also asserts the guarded dense plan for broad history; all preexisting eligibility, exactness, reopen and mutation/concurrency cases remain required. Host probes and APK compilation do not certify device execution. Controller must run the full unfiltered suites and independent startup at all five APIs plus exact-head CI after correction.

## PR37 F1 shared-lineage correction

Adds mandatory `StatisticsRepositoryTest.sharedNumberLineagesUseOneGroupedTraversalAfterPhysicalReopen`.
Data now requires 622 mandatory cases (627 including the five existing nonmandatory skips).
All prior B1/W1/B2, concurrency, cancellation, mutation and post-reopen cases remain required.
The new physical Room fixture holds selected execution cardinality at 100 and varies
populated Number Fields across 4/8/16/24, for Activity and Sequence. Every Field shares
6,000 old-Series values through separate old/new immutable snapshot Field IDs. It checks
exact signed distributions, missing/coverage, stable identities, three shared rank passes
independent of Field count, four binds and plans from actually executed SQL. Host VM-work
measurements complement these deterministic assertions. Compilation and host checks do
not certify instrumentation; Controller owns fail-first/corrected device execution and
the full unfiltered five-API exact-head gate.

## PR37 W1 small-fallback correction

Adds mandatory `StatisticsRepositoryTest.smallNumberFallbackUsesScalarMediansAfterPhysicalReopen`.
Data now requires 623 mandatory cases (628 including the five existing nonmandatory skips).
The physical Room regression covers Activity and Sequence with 1,000 selected executions,
7,000 old executions and separate frozen snapshots sharing stable source Field identities.
It covers one/two shared Fields and a successful exclusive Field-first selection followed
by one saturated shared-lineage fallback. Signed broad-range samples include both Long
extremes, overflow-sized exact totals, even/odd medians and missing coverage. Canonical
Series Detail and individual Field reads must agree with independent expectations after
reopen. Executed SQL must use at most two scalar fallback selections and no grouped rank
passes, with indexed plans and capped probes. All existing F1 4/8/16/24-Field, B1/W1/B2,
transaction, correction/deletion and compatibility protections remain required. The
[host work probe](statistics_small_fallback_probe.py) supplies deterministic VM evidence;
Controller retains physical fail-first/corrected execution and the complete five-API gate.

## PR37 round-two F1/F2 query-strategy correction

Adds mandatory `StatisticsRepositoryTest.numberProbeFairnessSurvivesDisplayRenameAndPhysicalReopen`.
Data now requires 624 mandatory cases (629 including five existing nonmandatory skips).
The existing small-fallback case retains one/two-Field scenarios and adds broad signed
3/4/8/16/17/24-Field histories for both Activity and Sequence. The original duplicate-heavy
4/8/16/24-Field case still requires three shared passes and now checks every fair probe.
The new physical case has 1,000 selected executions, 24 Fields, 50,000 old values only
for the expensive Field, archived sources and distinct old/new snapshot Field IDs.
Renaming current source metadata puts the expensive Field first and last without changing
historical facts. Both orders must retain 23 indexed Field-first medians and one scalar
fallback, individual-reader parity, exact distributions and original-date Day selection.
All prior testcase identities and the unfiltered five-API gates remain mandatory.
Controller owns physical fail-first/corrected execution and independent exact-head CI;
local APK compilation and host VM evidence do not certify device execution.

## PR37 W1 fair-probe boundary correction

Adds mandatory `StatisticsRepositoryTest.moderateNumberLineagesAvoidFairProbeCliffAfterPhysicalReopen`.
The pinned inventory now contains 625 mandatory data cases (630 including the same five
nonmandatory skips), including all 33 StatisticsRepositoryTest methods. This test uses
physical databases for both Activity and Sequence at 16/17/24 populated Fields,
1,000 selected executions and 999/1,000/1,001/1,002 old executions, plus sparse values
and 999/1,001 selected-execution neighbors. It checks actual canonical SQL dispatch,
indexed plans, bounded probes, independent exact arithmetic, individual-reader parity,
source rename/archive, distinct old/new snapshot Field IDs, dates and historical rows.
The existing broad fallback fixture now uses 16,000 old executions at 8+ Fields so it
continues to test saturation of the larger, cost-bounded allowance; its selected values,
three-pass/scalar and large grouped assertions remain intact. Shared duplicate-heavy
and expensive-first/last fairness regressions remain mandatory. Host VM measurements
are isolated in `statistics_lineage_boundary_probe.py`, without CI latency thresholds.
Controller still owns unfiltered execution on all five APIs and exact-head GitHub CI;
compiling a test APK does not certify Room test execution.


## PR37 W1 signed-Long rank completion correction

Adds mandatory `StatisticsRepositoryTest.signedLongNumberLineagesBoundRankWorkAfterPhysicalReopen`.
The pinned inventory contains 626 mandatory data cases (631 including the same five
nonmandatory skips), including all 34 StatisticsRepositoryTest methods. Physical fixtures
cover both kinds, P=16/17/24, 200 selected executions at the adjacent second-stage
lineage boundaries (2800/2801 old executions at P=17/24, 1800/1801 at P=16), selected
199/201 neighbors, sparse values, and clustered signed values requiring byte refinement.
The 200-sample adversarial distribution contains 99 Long.MIN_VALUEs, zero, all positive
powers 2^0..2^62, Long.MAX_VALUE and repeated ones. Assertions check exact arithmetic,
coverage/missing, stable source identity through distinct frozen Fields, immutable rows,
canonical individual/detail parity, day attribution, actual SQL traversal/returned-row
bounds and indexed EXPLAIN plans after physical close/reopen. Existing 1/2/3-Field,
partial-trial, fair expensive-first/last, exclusive dense/sparse, tiny-Series and narrow
period tests remain mandatory. The large broad fallback assertion now requires three
rank passes plus bounded exact histograms and zero independent scalar completion.
Host reproduction and VM-work comparisons are documented in the review evidence;
Controller still owns device execution, all five unfiltered API suites and exact-head CI.

## PR37 W1 mixed post-trial completion correction

Adds mandatory `StatisticsRepositoryTest.mixedPostTrialNumberRemaindersUseScalarMediansAfterPhysicalReopen`.
The pinned inventory now contains 627 mandatory data cases (632 including the same five
nonmandatory skips), including all 35 StatisticsRepositoryTest methods. Physical fixtures
cover Activity and Sequence with 17/24 initially unresolved Fields, 200 selected and
3,001 old-Series executions, distinct old/new snapshot Fields, archived sources, sparse
odd Fields and one/two/three broad signed Fields remaining after three shared rank trials.
Executed SQL must reject Field-first access in both fair stages, resolve the constant
Fields in the first rank trial, run exactly one indexed execution-first scalar selection
per remaining Field and never run a histogram for this small remainder. Tests compare
independent BigInteger totals, exact odd/even medians, extrema, coverage and missing
counts with canonical individual/detail reads after physical reopen, verify historical
date attribution and unchanged history, and inspect EXPLAIN for executed statements.
All earlier signed-rank, nine-byte-pass, fair-probe, eligibility and coherence regressions
remain mandatory. The host mixed-trial probe supplies reproducible native VM evidence;
Controller owns fail-first/corrected Room execution and the unfiltered five-API CI gate.

## S1 Daily accessibility boundary

The pinned inventory adds seven Daily presentation cases and one physical-database
Daily route/controller lifecycle case (331 mandatory app cases). All prior cases
remain mandatory. The independent production-startup invocation and all five API
gates (26, 31, 33, 35, 36) remain required, with unfiltered app/data execution.
See [Daily accessibility evidence and walkthrough](daily_accessibility.md) for
coverage, local verification boundaries, and the pending manual TalkBack procedure.
Controller owns authoritative app-unit, gmd-app, lint and exact-head CI evidence.
APK compilation and semantics assertions do not certify TalkBack operation.

## S2 Start Activity accessibility boundary

The pinned inventory adds nine launcher presentation cases and ten isolated physical-
database route/controller cases, bringing the mandatory app count to **350 per API**.
S2C1 adds two presentation regressions and four isolated nested-Browse cancellation
cases, bringing the current mandatory app count to **356 per API**. The command-state
matrix covers both Back mechanisms; physical reopen cases cover Checking/Preflight
cancellation and a later independent successful launch. Passive remaining-time
semantics cover exact-zero and overdue display without a live region or misleading
indeterminate progress. Data remains 627 and the independent production-startup
invocation remains two.
All original testcase identities and the five unfiltered API gates remain mandatory.
The existing production Sequence launch, organization, stale-target, transaction and
route-session tests are retained.

See [Start Activity accessibility evidence and walkthrough](start_activity_accessibility.md)
for semantic activation, exact identity/snapshot reopening, quick zero/Missing/default,
one-off, pinned immutability, DST/overlap, commit gating, localization/scaling, shared-
component impact and manual validation boundaries. Daily reuses its existing target
provider through a shared extraction; S1 semantics remain required. Controller owns
authoritative app-unit, gmd-app, lint and exact-head GitHub verification on all five APIs.
Local APK compilation does not establish device or manual TalkBack execution.

The initial Controller API 35 run completed all 350 app cases with four failures.
The scoped repair tightens button selectors and establishes keyboard-mode focus
prerequisites without removing assertions or testcase identities. The authorized
repair probe returned "No active repair-probe lease" before executing any testcase;
Controller instrumentation confirmation is pending. See the linked evidence for
the repair and local check results. A subsequent focused run confirmed the Timer
selector repair but failed three cases in composition-time input-mode setup.
The shared keyboard fixture now enters native keyboard mode after owner attachment
and restores the previous mode on teardown. Its APK compilation and static checks
passed; the new repair-probe invocation also had no active lease, so device
confirmation remains pending.
