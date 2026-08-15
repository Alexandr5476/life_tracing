# LifeTracing

LifeTracing is an Android application for recording, planning, and analysing personal activity execution. This repository currently provides the tested infrastructure for the later domain and Room implementation; it deliberately contains no product features yet.

## Authoritative specifications

Read the relevant document in [`docs/spec`](docs/spec) before implementing a feature:

- `lifetracing_product_spec_v0.16.md`
- `lifetracing_domain_model_v0.10.md`
- `lifetracing_database_schema_v0.6.md`

## Modules

- `:domain` — pure Kotlin domain models, rules, services, and fast JVM tests.
- `:data` — Android persistence boundary: future Room entities, DAOs, mappers, and repository implementations. It may depend on `:domain`.
- `:app` — Android app and Compose UI. It may depend on both modules but must not expose persistence entities to UI.

## Prerequisites

- JDK 21 (Gradle/Kotlin toolchain)
- Android SDK Platform 36 and build tools, configured by Android Studio or `ANDROID_HOME`
- Git for the optional local hook

The app uses `minSdk 26`: Android 8.0 is a practical modern baseline and includes the `java.time` APIs needed by later time-oriented domain work. Android targets Java 17 bytecode for runtime compatibility while builds use a JDK 21 toolchain.

## Build and verify

```powershell
.\gradlew.bat build
.\gradlew.bat :domain:test :data:testDebugUnitTest :app:testDebugUnitTest
.\gradlew.bat ktlintCheck detekt lintDebug
.\gradlew.bat coverageReport coverageVerify
.\gradlew.bat :app:assembleDebug
```

Compose instrumentation infrastructure uses a Gradle Managed Device:

```powershell
.\gradlew.bat :data:pixel2Api35DebugAndroidTest :app:pixel2Api35DebugAndroidTest
```

Activate the optional fast pre-push check once per clone:

```powershell
git config core.hooksPath .githooks
```

## CI and releases

GitHub Actions runs fast build, JVM tests, ktlint, detekt, Android lint, enforced domain coverage verification, coverage artifacts, and a debug APK for pull requests to `main` and all branch pushes. Instrumented tests have a maintainable managed-device job available through workflow dispatch for both `:data` and `:app`, so they can be promoted to required checks when execution time is acceptable.

`coverageVerify` delegates to Kover's native `:domain:koverVerify` task and currently enforces at least 80% domain line coverage. Future domain rules can add package, class, or branch bounds without applying a whole-project threshold to Android/Compose glue.

Version tags matching `v*.*.*` run the release foundation. Signing and publishing remain intentionally gated on GitHub Secrets until a real keystore is provisioned; no credentials are in this repository.
