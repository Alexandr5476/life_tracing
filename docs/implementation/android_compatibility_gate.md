# Android compatibility release gate

Delivery-plan item 7 and the S5 Parent contract define this gate. Product v0.16, domain v0.10, database v0.6 and the implementation clarifications remain authoritative. These checks preserve schema v10, durable identities, runtime semantics and existing permission UX.

## Automatic CI and required jobs

[CI](../../.github/workflows/ci.yml) automatically runs complete data and app instrumentation at **all five APIs** on pull requests to main, pushes to main, workflow dispatch and reusable workflow calls. Every row is required:

| Required check name | API | Managed device | Required modules |
| --- | --- | --- | --- |
| Build, test, and static analysis | JVM/build | — | domain, data, app |
| Managed-device instrumentation tests | 35 | pixel2Api35 | data then app |
| Managed-device instrumentation tests (API 26) | 26 | pixel2Api26 | data then app |
| Managed-device instrumentation tests (API 31) | 31 | pixel2Api31 | data then app |
| Managed-device instrumentation tests (API 33) | 33 | pixel2Api33 | data then app |
| Managed-device instrumentation tests (API 36) | 36 | pixel2Api36 | data then app |

The matrix uses separate CI runners per API, with fail-fast disabled. Within a row, [run-instrumentation.py](../../.github/scripts/run-instrumentation.py) runs data and app in separate sequential Gradle invocations, even after module failure, avoiding shared AVD setup/teardown races. It supplies the expected API and uses no class/method filters, no build cache, rerun-tasks, no configuration cache, no parallel tasks and at most two workers. API 26 explicitly provisions the emulator with sdkmanager from the configured SDK. AGP's old-API managed-device opt-in remains enabled; minSdk is 26 and targetSdk is 36.

Checkout and runner verification use the PR head SHA (or github.sha for other events). The runner rejects a checkout mismatch before Gradle. evidence.json distinguishes tested SHA from GitHub event SHA, which may identify a merge ref.

## Mandatory original-JUnit inventory

[instrumentation-inventory.json](../../.github/scripts/instrumentation-inventory.json) pins full classname and testcase names, including every parameterized variant. It requires 606 data and 314 app cases per API. Only the actualApi parameter is substituted for the selected device. The inventory is committed independently of discovery and reports; never regenerate it automatically from the current execution or lower it to accommodate missing evidence. Review inventory updates with additions or intentional renames of required tests.

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

## System alarm and notification evidence

The real alarm regression reads pending RTC_WAKEUP records from dumpsys alarm, scoped to this app and the production deadline action. It checks the requested epoch deadline, one-shot registration, actual exact/inexact batching window and disappearance after production cancellation. A negative control creates only the PendingIntent and requires no pending alarm. Historical statistics/removal records are excluded. The complete matrix requires fresh-install capability state: exact authorized at APIs 26/31/33 and unavailable at APIs 35/36, where real inexact fallback is observed. Unexpected capability state fails instead of silently losing a mode. Tests do not mutate exact access: Android revocation can kill instrumentation. Controller must use isolated fresh GMD installations without pre-granting exact access. Existing PendingIntent replacement, no-update control, logical-deadline and stale/duplicate regressions remain required.

The notification regression invokes the production publisher with a canonical running Activity from an isolated physical database, then completion, then cancellation. NotificationManager.activeNotifications must expose ID 1001, channel active_runtime, runtime/completion text, ongoing/only-alert-once flags, privacy and stopwatch category. Bounded observation waits for actual asynchronous system state. All durable database facts must remain identical. Below API 33 the test temporarily allows and restores POST_NOTIFICATION app-op. At API 33+ it scopes UiAutomation's real POST_NOTIFICATIONS shell-permission delegation and drops it in finally; no persisted grant/revoke or permission flags change. It checks restoration of original permission/capability and restores a preceding notification in its slot. Existing injected denied/disabled nonpublication tests remain required at every API.

These assertions must reject mutations suppressing production AlarmManager registration or AndroidRuntimeNotificationBackend.post(). Controller owns mutation execution on disposable checkouts and leased devices: suppress setExactAndAllowWhileIdle at API 31, suppress setAndAllowWhileIdle at API 35, and independently make post() a no-op at API 33. Run the corresponding new test, retain expected failure and original XML/log, discard the mutation, then execute the clean exact-head gate. Token creation and recording backends cannot substitute for evidence. No mutation is published.

## Evidence and execution status

Each API uploads these artifacts even on failure, retained for 30 days:

- `instrumentation-evidence-api<API>-attempt<ATTEMPT>`: `ci-evidence/api<API>/evidence.json`, data.log, app.log and byte-for-byte original per-module TEST-*.xml with SHA-256 digests.
- `instrumentation-reports-api<API>-attempt<ATTEMPT>`: the same evidence plus each module's build/reports, build/outputs/androidTest-results and managed-device additional output.

Original per-device XML is under `<module>/build/outputs/androidTest-results/managedDevice/debug/pixel2Api<API>/`. HTML is under `<module>/build/reports/androidTests/managedDevice/debug/pixel2Api<API>/`. Each module's AndroidCompatibilityDeviceTest name records actual SDK; system output/logs record fingerprint. evidence.json records commands, timestamps, exit codes, API observation, mandatory inventory certification, counts, SHA and report hashes. Compare both module observations with the selected API. Combined allDevices reports, compilation and cached ignored outputs are insufficient evidence.

Controller independently inspected [CI run 39078394582](https://github.com/Alexandr5476/life_tracing/actions/runs/39078394582) at reviewed head 29d8d5458e7d5d2c694b6b7fb5a3132f3751b6a2: every API ran both modules; data had 611 cases including five nonmandatory skips, app had 312, with zero failures/errors. This historical complete execution keeps original B1 closed. It predates the two new system-service assertions and does not certify this uncommitted correction. Current correction device/mutation execution and exact-head CI remain Controller-owned and pending; implementation compilation does not claim device PASS.

## Controller responsibilities and reproduction

Controller owns commit/push, device leases/provisioning/cleanup, canonical local execution, mutation evidence, exact-head GitHub checks and final Parent closure review. Require all six named CI checks and all ten module/API results on the corrected SHA. No approval or merge is authorized here. Preserve the precise API/image/ABI and error for provisioning failures. Failures after application/testcase entry are executed failures; a module never reached has no execution result.

Prerequisites are JDK 21, Android SDK platform 36/build tools, accepted licenses, hardware virtualization (KVM/WHPX), sufficient disk/network for each AOSP image and exclusive leases. For local execution run one API at a time, replacing 31 below with 26/33/35/36:

```powershell
.\gradlew.bat :data:pixel2Api31DebugAndroidTest :app:pixel2Api31DebugAndroidTest -Plifetracing.compatibility.expectedApi=31 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --rerun-tasks --no-build-cache --stacktrace
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
