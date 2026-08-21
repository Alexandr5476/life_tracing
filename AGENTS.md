# LifeTracing agent guide

`docs/spec/` contains the authoritative current product, domain-model, database-schema specifications, and implementation clarifications. Read the relevant versioned specifications together with `docs/spec/lifetracing_implementation_clarifications.md` before changing a feature. Specifications are not assumed infallible: if implementation reveals an ambiguity, contradiction, missing invariant, or technically harmful requirement, stop and surface it rather than blindly implementing or silently diverging. When resolution changes observable semantics or a durable persistence/domain decision, update the appropriate spec/addendum or explicitly record the decision before continuing; implementation details that do not change semantics may be chosen normally.

- Keep the layers separate: UI/Compose consumes domain-facing models, never Room entities. The domain module must stay free of Android, Compose, and Room dependencies.
- Persisted snapshots are immutable historical objects, not editor drafts. Replace a snapshot only on semantic **Save**, **Apply**, or **Done**—never for each keystroke.
- Tests are executable specifications. Every behavioral change needs appropriate tests; prefer behavioral/domain tests over implementation-detail tests.
- Every test establishes its own relevant prerequisites and must not depend on execution order, another test's mutable state, or unrelated application behavior. Integration tests may combine real components, but their fixture and required state remain explicit and self-contained.
- Test representable invalid and boundary states that can enter through persistence, migrations, mapping/deserialization, illegal transitions, concurrent/stale state, or regressions, so failures occur at the invariant boundary. This does not require exhaustive values, reflection/unsafe construction of unrepresentable states, or meaningless property/getter tests.
- Treat performance and storage growth as correctness-adjacent constraints without premature micro-optimization: avoid obviously unbounded reads, quadratic work over growing history, unnecessary persistent duplication, and long-lived large history copies; surface frozen designs that imply plausibly unacceptable multi-year costs, and optimize from clear complexity/query reasoning or measurement. Normal user-data growth is not garbage.
- JVM tests in `src/test` use JUnit 5/Jupiter; instrumented and Compose tests in `src/androidTest` use JUnit 4 with AndroidJUnitRunner/Compose JUnit4 APIs. Imports must match the source set.
- For a bug fix: (1) add a failing regression test, (2) fix production code, (3) run that test and the relevant suite. Do not weaken or delete tests to pass CI.
- Do not use reflection solely to test private code. Extract important private logic into a focused internal/domain component with a testable API instead.
- UI work must read `docs/design/README.md`; screenshots are references, reusable components take precedence, and user-visible strings use resources.
- UI motion uses `LifeTracingMotion`; do not rely on default spring/bouncy animation without an explicitly justified interaction need.
- Custom rounded interactive surfaces must clip press indication to the same shape as their visual surface.
- Never commit secrets, credentials, signing keys, local SDK paths, or `AGENTS.override.md`.
- Live runtime commands spanning `active_session` and Execution state commit atomically at one database transaction boundary; `active_session` is the v1 pointer/policy guard, while execution, occurrence, and interval rows remain source of truth.
- Android scheduling is advisory: durable `active_session`/Execution state is authoritative. Platform alarms may trigger reconciliation but may never force a stale transition, and alarm delivery time must not replace the calculated logical event timestamp.
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
