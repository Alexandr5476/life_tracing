# S5 Android compatibility gate

The supplied S5 Parent contract and delivery-plan item 7 define this gate. Durable behavior remains product v0.16, domain v0.10, database v0.6 and the implementation clarifications. No schema, identity, permission-request UX, signing, or CI orchestration changes are required.

The root Gradle configuration defines the same AOSP Pixel 2 devices for `:data` and `:app`. API 31 is the Android 12 target; no API 32 substitution has been made. API 35 retains its established name, hardware profile and image source. AGP 8.10.1 rejects API 26 managed test-task creation by default; `gradle.properties` enables its supported infrastructure opt-in, `android.experimental.testOptions.managedDevices.allowOldApiLevelDevices=true`. This exposes the target without changing minSdk 26 or targetSdk 36. App setup for each shared AVD must run after that device's data instrumentation when both tasks are requested. Run one API at a time with the serialization flags below; there is intentionally no aggregate task that launches several emulators.

| Required API | Device configuration | Data task | App task | Data on corrected head | App on corrected head |
| --- | --- | --- | --- | --- | --- |
| 26 | `pixel2Api26` | `:data:pixel2Api26DebugAndroidTest` | `:app:pixel2Api26DebugAndroidTest` | NOT_RUN | NOT_RUN |
| 31 | `pixel2Api31` | `:data:pixel2Api31DebugAndroidTest` | `:app:pixel2Api31DebugAndroidTest` | NOT_RUN | NOT_RUN |
| 33 | `pixel2Api33` | `:data:pixel2Api33DebugAndroidTest` | `:app:pixel2Api33DebugAndroidTest` | NOT_RUN | NOT_RUN |
| 35 | `pixel2Api35` | `:data:pixel2Api35DebugAndroidTest` | `:app:pixel2Api35DebugAndroidTest` | NOT_RUN | NOT_RUN |
| 36 | `pixel2Api36` | `:data:pixel2Api36DebugAndroidTest` | `:app:pixel2Api36DebugAndroidTest` | NOT_RUN | NOT_RUN |

Controller reports both API 35 modules PASS on reviewed head `6bb2c3220b851a20de847f33e20e57df6b374c04`. That result predates these corrections and does not verify the new regressions. APIs 26, 31, 33 and 36 remain NOT_RUN on the reviewed head as well. No device execution is claimed by this correction.

All device execution is Controller-owned. These statuses are the implementation-turn record, not a claim about old reports already present in ignored build directories. The local SDK inspection found only the API 35 system-image directory. Other images have not been provisioned or attempted during this turn; absence from the local SDK is not evidence that a target is unavailable. A failed provisioning attempt must record UNAVAILABLE with the exact API, image/package/ABI selected by Gradle, host and provisioning error, retaining its configuration. A failure after testcase/application entry is an executed FAIL. Record PASS only for a complete successful run of both modules on the verified API with all required suites present and no skipped required scenarios. Parent closure needs five independently recorded PASS results plus mandatory GitHub verification; S1-S4 and GitHub PASS evidence remains outstanding.

## Controller execution

Prerequisites: JDK 21, Android SDK platform 36/build tools, accepted SDK licenses, Android Emulator with hardware virtualization (WHPX locally or KVM on Linux), network/disk for each selected AOSP image, and exclusive Controller device leases. Use the same project dependency/tool versions and production database builder on every image. No unconditional permission grant is required.

Discover configurations without launching or setting up devices:

```powershell
.\gradlew.bat :data:tasks :app:tasks --all --console=plain
```

Run each command independently and sequentially under an exclusive Controller device lease. Do not apply class/method filters for acceptance:

```powershell
.\gradlew.bat :data:pixel2Api26DebugAndroidTest :app:pixel2Api26DebugAndroidTest -Plifetracing.compatibility.expectedApi=26 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --stacktrace
.\gradlew.bat :data:pixel2Api31DebugAndroidTest :app:pixel2Api31DebugAndroidTest -Plifetracing.compatibility.expectedApi=31 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --stacktrace
.\gradlew.bat :data:pixel2Api33DebugAndroidTest :app:pixel2Api33DebugAndroidTest -Plifetracing.compatibility.expectedApi=33 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --stacktrace
.\gradlew.bat :data:pixel2Api35DebugAndroidTest :app:pixel2Api35DebugAndroidTest -Plifetracing.compatibility.expectedApi=35 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --stacktrace
.\gradlew.bat :data:pixel2Api36DebugAndroidTest :app:pixel2Api36DebugAndroidTest -Plifetracing.compatibility.expectedApi=36 -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect -Pandroid.experimental.testOptions.managedDevices.setupTimeoutMinutes=5 --no-daemon --no-parallel --max-workers=2 --console=plain --no-configuration-cache --stacktrace
```

Controller must commit/push this correction and attach the resulting exact SHA to all ten module/API results and the independent GitHub CI results. Preserve individual XML/HTML, observed SDK/fingerprint, suite/test counts, skips, logs, command and exit code. Compilation, task discovery, cached reports and Controller check-label acknowledgements are not device execution evidence. A module not reached stays NOT_RUN even if its peer passes. Provisioning failure is UNAVAILABLE only with the concrete setup error; testcase/application/assertion failures are FAIL. B1 stays open until both modules PASS at all five APIs on the corrected head and exact-head GitHub checks pass.

`AndroidCompatibilityDeviceTest` in each module reads `Build.VERSION.SDK_INT` at execution, records `actualApi` in the parameterized testcase name and logs the fingerprint. The expected-API property passes an instrumentation argument to both modules and makes a wrong-image run fail. Ordinary instrumentation without that property still records the actual API and remains usable on all supported APIs. API 35's existing unparameterized invocation remains valid.

For both `data` and `app`, preserve these per-device artifacts immediately after each run:

- HTML: `<module>/build/reports/androidTests/managedDevice/debug/pixel2Api<API>/index.html`.
- XML, logs, UTP results: `<module>/build/outputs/androidTest-results/managedDevice/debug/pixel2Api<API>/`.
- Command, exit status, provisioning log, observed API/fingerprint, suite counts, failures and skips.

Do not use the combined `allDevices` report alone as per-API evidence. Publish independent data/app results and an overall row status; record NOT_RUN for any module not reached. Controller may run the same commands in its GitHub verification environment; this document adds no workflow, check alias, or second CI system. Existing GitHub API 35 instrumentation remains a mandatory independent gate.

## Required acceptance suites on every device

Data runs the unchanged S1-S4 suites, including accepted S2C1 and S4C1 corrections:

| Suite | Acceptance |
| --- | --- |
| `ProductionDatabaseEntryPathTest` | Fresh physical v10, authentic v1, registered complete migrations, structural integrity/seed identity, canonical close/reopen reads and non-destructive repeated migration failure |
| `HistoricalFrozenProductionMigrationTest` | Authentic v3/v6/v7 frozen snapshots, source/provenance and canonical history/statistics reads |
| `HistoricalTerminalProductionMigrationTest` | Authentic v7/v8 COMPLETED and ENDED_EARLY roots; skipped Repeat occurrence without child; retained child-deletion tombstone; structurally removed runtime-added occurrence; all-table facts and canonical History/Daily/Statistics after production migration and reopen |
| `HistoricalActiveProductionMigrationTest` | Authentic v8 ownership, paused/running Activity and Sequence current/no-current recovery, logical deadlines and canonical reopen |
| `HistoricalPlanProductionMigrationTest` | Authentic v9 frozen Plan linkage/engagement/fulfillment, canonical readers and physical reopen |
| `AndroidCompatibilityDeviceTest` | Observed device API and expected-target admission |

App runs the complete instrumentation suite, especially:

| Suite | Acceptance |
| --- | --- |
| `RuntimePlatformAndroidTest` | API-appropriate PackageManager overloads with identical permission/receiver assertions; stable PendingIntent identity, actual sent replacement payload and a no-update negative control; low-importance silent channel/privacy boundary |
| `RuntimeCapabilityAndroidTest` | Real scheduler and notification capability inspection; API 26/31/33 framework boundaries; deterministic allowed/denied/exact-SecurityException fallback; real future-alarm replacement/cancellation; denied posting; real vibrator invocation through the supported platform service without assuming hardware; independent feedback flags |
| `RuntimeCoordinatorRecoveryTest` | File-backed running/paused/current/no-current recovery; before/at/after deadlines; boot/timezone/DST; stale/duplicate callbacks; postcommit platform failures; original-zone attribution |
| `RuntimeReceiverRecoveryTest` | Production receiver/goAsync dispatch and runtime-graph ownership across cold physical reopen; duplicate delivery; graph/dispatch failure completion; denied alarms/notifications followed by foreground recovery and another reopen preserving every durable fact |
| `RuntimePreflightRecoveryTest` | Abandoned preflight never manufactures durable runtime |
| `LifeTracingAppTest`, `ProductionLauncherCoordinationTest`, `PlanExecutionPersistenceTest` | Production application shell/graph, serialized command coordination and explicit Plan persistence/linkage |
| `AndroidCompatibilityDeviceTest` | Independently observed app-device API |

Alarm assertions do not wait for exact delivery. Real platform alarms are scheduled far in the future and canceled in teardown. Fake alarm backends deterministically exercise denial/revocation races. PendingIntent checks send the actual system token and decode the Intent supplied by Android's delivery callback, using a bounded latch and no fill-in Intent. The callback observes the system token payload; an undeclared receiver permission suppresses dispatch into the unrelated application database graph. Durable stale/current admission is exercised separately against an isolated physical database. A no-update control retains the obsolete payload; the production codec must replace it. An isolated physical Sequence fixture feeds the captured obsolete and replacement payloads through the production coordinator: stale delivery preserves every fact, current delivery advances at logical time, and duplicates remain harmless. Removing FLAG_UPDATE_CURRENT must fail the replacement-payload assertion; the Controller-owned device mutation run is still outstanding. Notification-denial assertions inject the production publisher's delivery/capability backend; they run on every API and verify zero posting attempts, including after cold reconstruction with a live runtime. Real capability checks inspect the actual platform state without changing permissions. Emulator permission mutation is intentionally unnecessary, so permission restoration is not needed and there is no silently skipped denial scenario. Notification instances and PendingIntents are canceled in teardown. Vibration smoke invokes AndroidRuntimeVibrator.vibrate() on every image, reaching Vibrator before API 31 and VibratorManager.defaultVibrator from API 31 onward. It requires successful platform invocation, not vibration hardware or audible output. Separate injected feedback counters retain every sound/vibration flag-pair assertion.

Exact-alarm access-change broadcasts are now admitted only at API 31+, where the action exists. Boot, system time and timezone remain supported at every API. Malformed/unsupported broadcasts still fail closed. The notification backend extraction preserves production channel construction, capability policy and delivery; all domain writes remain outside the adapter.

## Non-device verification

AndroidTest source/APK compilation is compile evidence only:

```powershell
.\gradlew.bat :data:assembleDebugAndroidTest :app:assembleDebugAndroidTest
.\gradlew.bat ktlintCheck detekt lintDebug :domain:test :data:testDebugUnitTest :app:testDebugUnitTest coverageReport coverageVerify :app:assembleDebug --stacktrace
```

`RuntimeBroadcastAdmissionTest` provides JVM regression evidence for the pre-31 fail-closed boundary and unchanged ordinary allowlist. These checks do not imply a device PASS. Final Parent repository audit and actual cross-API/CI execution remain separate acceptance responsibilities.

Pre-correction implementation verification on 2026-10-14 (reviewed head; does not verify the changes requested in W1-W3): configuration discovery passed for all five named devices in both modules, including the expected-API argument configuration. The complete non-device command above, plus both AndroidTest APK assembly tasks, passed: ktlint, Detekt, Android lint, JVM tests, coverage generation/verification, and debug application/APK packaging. JVM XML reports contain 233 domain, 19 data and 396 app tests with zero failures, errors or skips; the two new broadcast-admission regressions passed. `git diff --check` passed. No managed-device setup or instrumentation task was executed; every device row remains NOT_RUN.


## PR 36 correction handoff (W1-W3, B1 remains open)

New device regressions to retain in exact-head reports:

- App: `RuntimePlatformAndroidTest.pendingIntentReplacementDeliversUpdatedPayloadAndNoUpdateControlRetainsObsoletePayload`.
- App: `RuntimeCapabilityAndroidTest.realSchedulerInspectsSupportedCapabilityAndReplacesAndCancelsOneAlarm` and `realVibratorInvokesTheSupportedPlatformApiWithoutRequiringHardware`; deterministic flag-routing coverage remains separate.
- App: `RuntimeReceiverRecoveryTest.deliveredReplacementTargetsCurrentSequenceAndObsoletePayloadCannotAdvanceIt` changes all three deadline identity fields, checks every durable fact on stale/duplicate delivery and reopens the Sequence file afterward.
- Data: all four `HistoricalTerminalProductionMigrationTest` methods (v7/v8, COMPLETED/ENDED_EARLY). The skipped frozen Repeat iteration has no child; the other Repeat iteration retains a child-deletion tombstone; the runtime-added structurally removed occurrence and child remain stored but disappear from canonical History and per-Activity accounting. Leave-gap removal retains historical timestamps and removes owned intervals without synthesizing an idle interval. Canonical root/global accounting retains the gap as pause/idle time.

The final local verification command includes the complete non-device command above plus `:data:assembleDebugAndroidTest :app:assembleDebugAndroidTest`. Its result belongs to the correction completion record, not the old reviewed-head evidence. No emulator or device task is authorized in this implementation turn. New device regressions and the optional FLAG_UPDATE_CURRENT mutation experiment remain NOT_RUN until Controller executes them. For the mutation experiment, use a disposable verification checkout, remove only FLAG_UPDATE_CURRENT, run the replacement-payload test on a leased device and retain the expected assertion failure; then verify the restored exact corrected head. Do not publish the mutation or treat its FAIL as an acceptance result.

Controller owns provisioning, all ten module/API runs, commit/push, and independent exact-head GitHub build/static/JVM and API 35 instrumentation gates. This correction neither approves nor merges PR 36 and does not close the Parent or B1 without those results.
