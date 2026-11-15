# Historical Activity deletion accessibility (S4)

The accessible journey uses the existing exact-ID Activity history controller,
retained Navigation3 route session, and ActivityCommandRepository.softDeleteHistory.
No repository writer, transaction, schema, migration, deletion policy, Statistics
policy or durable confirmation state changes. Plan-linked Activity correction
remains forbidden, including Comment-only correction. A completed standalone
Plan-linked Activity can be deleted independently of correction eligibility.

## Confirmation and admission

Delete and Confirm expose localized target-specific native action names. Their
historical description reuses the bounded loaded root: frozen title, Activity
record identifier with the full execution ID, original-zone endpoints, completion,
tracking and duration context. Identical names, times, Fields and Series cannot
retarget a command. Confirmation explains exclusion from History, Daily and
Statistics, preservation of the reusable Template, and preservation of a linked
fulfilled Plan and its original fulfillment time.

The controller and native callbacks share transient eligibility predicates.
RequestDelete cannot discard a correction draft or overlap proposal. ConfirmDelete
requires the exact requested execution and updatedAt captured on RequestDelete.
Cancel/Back and reload revoke confirmation. Mutation blocks resubmission and
Back; session release refuses a pending writer. Ordinary failure retains this
same confirmation for explicit Retry or Cancel. Old publications, hidden/replaced
routes, disposal and mismatched identities cannot reuse native callbacks.
An action rejected after route ownership is lost permanently revokes that rendered
lease. A transient generation supplies fresh controls on the next composition,
including hide/return before a hidden frame; retained correction and deletion
callbacks cannot revive merely because ownership becomes true again.

Stale rejection discards confirmation, reloads canonical detail and requires a
fresh explicit request. A concurrently deleted target becomes Unavailable.
Canonical History refresh is also delivered for stale recovery, including a
competing correction that moved the root, so return cannot keep an obsolete row.
There is no automatic writer retry.

Success sets terminal deleted state and removes executable detail. Refresh
advances once before navigation; Back drains that authoritative generation if it
runs before Compose observes commit. The retained session deduplicates delivery.
Hidden routes defer completion navigation until current, visible and resumed;
MainActivity also checks exact foreground route and session before release/pop.
Read failure on refreshed History uses canonical Retry, never the committed writer.

## Native focus and presentation

Native Material buttons retain roles, enabled states and shared minimum targets.
Confirmation enters on Cancel; failure focuses explicit Retry. Cancel and both
Back paths return to the current detail/Delete control. Unavailable/read recovery
uses available navigation/Retry. Pending text is a localized polite live region;
confirmation/status semantics do not change on unrelated recomposition. Actions
wrap in a FlowRow inside the existing scrollable surface. EN/RU, app scale and
Android font scaling use existing providers. Focus does not dispatch commands.

## Structural and persistence evidence

The sole deletion port in LifeTracingRuntimeGraph calls the existing
ActivityCommandRepository.softDeleteHistory, inside its existing database
transaction. ActivityExecutionDao verifies standalone/completed/nondeleted,
exact ID, expected updatedAt and advancing deletion time, then updates only
soft-delete and concurrency metadata. It does not write a snapshot, values,
Template or Plan. Existing repository integration tests cover invalid targets,
rollback/concurrency, live-session independence, archived sources and Plan linkage.

HistoryReadRepository.getActivityDetail performs one selected aggregate and
snapshot read. Description formatting needs no Field reads or repository calls.
History retains bounded 101-root requests/100-root replacement pages and indexed
newest-primary-date discovery. Canonical History/Daily and Statistics SQL retain
their deleted_at exclusions, standalone/root accounting and stable Series/Field
identity. No UI-side tombstone filtering or new cache invalidation is added.
Existing Sequence snapshot loading chunks parent IDs; options are joined through
those parent IDs rather than expanding Field IDs into an unbounded bind list.
The exact historical-overlap preflight exception is unchanged.

## Verification coverage and limits

ActivityDeletionAdmissionTest first reproduced four baseline failures: confirmation
bypass, draft loss, writer session release and stale refresh omission. Focused
regressions cover direct controller admission, exact token/ID, duplicates during
pending writes, terminal state, explicit retry foundations, mismatch, retained
callbacks and same-turn commit/Back refresh delivery. HistoryControllerTest covers
newest-root rediscovery and last-root canonical Empty. Existing S1-S3 and Sequence
JVM suites remain in the focused check.

Registered JUnit4 tests use production MainActivity/NavDisplay and isolated real
repositories. They cover native Enter, Cancel, native/system Back, same-looking
siblings, blocked-writer recreation, explicit failure retry, stale/unavailable,
committed History read failure, archived sources, No-live, Plan-linked deletion
and last-root Empty. Physical database close/reopen checks identity, snapshots,
values, timestamps/provenance, complete Plan equality, no replacement rows,
canonical History/Daily/Statistics and retained sibling/Sequence contributions.
The native presentation matrix covers EN/RU, app/system scale extremes, physical
48dp touch targets, focus-only nonmutation, hidden/disposed and terminal routes.
All new instrumentation identities are registered in the inventory.

AndroidTest compilation is not execution evidence. Controller owns all device
execution and independent mandatory CI gates. No manual TalkBack or Switch Access
walkthrough, application-wide certification or Parent closure is claimed.

## Local check results

The combined local check completed successfully with domain/data tasks using their
up-to-date results (245 domain and 36 data JVM cases, zero failures/errors/skips):

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*plan.*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
```

The final hide/return callback regression was separately run against the prior
guard and failed before the fix. After that shared correction/deletion admission
fix, this final command passed with 245 app JVM cases, zero failures/errors/skips,
including all nine deletion admission and fifteen correction admission tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*plan.*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --continue --console=plain
```

All eleven new instrumentation methods are registered; inventory/source checks,
EN/RU XML parsing and git diff --check passed. Device execution, coverage and the
complete repository acceptance/CI gates remain Controller-owned and unverified
by these local checks. The patch is ready for that verification, not Parent closure.
