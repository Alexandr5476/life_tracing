# LifeTracing agent guide

`docs/spec/` contains the authoritative product, domain-model, and database-schema specifications. Read the relevant specification before changing a feature; never silently weaken or change requirements to make implementation easier.

- Keep the layers separate: UI/Compose consumes domain-facing models, never Room entities. The domain module must stay free of Android, Compose, and Room dependencies.
- Persisted snapshots are immutable historical objects, not editor drafts. Replace a snapshot only on semantic **Save**, **Apply**, or **Done**—never for each keystroke.
- Tests are executable specifications. Every behavioral change needs appropriate tests; prefer behavioral/domain tests over implementation-detail tests.
- For a bug fix: (1) add a failing regression test, (2) fix production code, (3) run that test and the relevant suite. Do not weaken or delete tests to pass CI.
- Do not use reflection solely to test private code. Extract important private logic into a focused internal/domain component with a testable API instead.
- UI work must read `docs/design/README.md`; screenshots are references, reusable components take precedence, and user-visible strings use resources.
- Never commit secrets, credentials, signing keys, local SDK paths, or `AGENTS.override.md`.
- A task is complete only when all relevant checks pass.

Canonical commands (PowerShell):

```powershell
.\gradlew.bat build
.\gradlew.bat :domain:test :data:testDebugUnitTest :app:testDebugUnitTest
.\gradlew.bat ktlintCheck detekt lintDebug
.\gradlew.bat coverageReport coverageVerify
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :data:pixel2Api35DebugAndroidTest :app:pixel2Api35DebugAndroidTest  # Managed-device instrumentation suites
git config core.hooksPath .githooks
```
