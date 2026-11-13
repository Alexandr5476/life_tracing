# Completed History accessibility (S1)

This slice follows product v0.16, domain v0.10, schema v0.6, the implementation
clarifications and the shared design controls. It changes presentation and route
action admission only. Execution IDs, historical writers, snapshots, Plan
fulfillment, Statistics and repository queries are unchanged.

## Presentation

History keeps the canonical newest-date discovery, adjacent 28-day windows,
101-root repository request and 100-root replacement page. The controller now
publishes its already-calculated newest date for destination-period labels.
Persisted primaryLocalDate remains the grouping authority after zone changes or
clock rollback. Compose preserves the reader's date/root order.

Each native Material row has one detail action. Its description includes Activity
or Sequence kind, frozen title/comment, a localized record identifier containing
the full stable execution ID, historical timing, duration/tracking facts and
terminal status. Full IDs deliberately disambiguate even otherwise identical
records without assigning authority to a visual ordinal. Typed kind/ID Compose
keys preserve execution focus identity through reordering and rematerialization.
No custom secondary accessibility click actions are introduced.

Timing reuses historicalTimestamp for every endpoint with its original ZoneId
and effective offset. Both dates remain explicit, including cross-midnight and
repeated-hour intervals. Completion-only No-live records have no fabricated start;
missing duration remains distinct from zero. Timer target remains separate from
actual active duration.

History dates and detail titles are headings. Loading, empty, read failure,
unavailable detail and successful content expose localized state text; live-region
properties change with the actual read state, not unrelated recomposition.
Activity detail combines each frozen Field's actual and configured values in one
readable group, preserving missing values. Correction/delete and Sequence mutation
controls retain their production commands.

Navigation uses wrapping FlowRows, a separate period heading and the shared
physical minimum-target provider. Labels and feedback use English/Russian
resources. Native roles, disabled state, press indication and keyboard focus stay
with Material controls.

## Admission and focus

HistoryNavigationAdmission captures one rendered publication by reference. Every
Back, Add, Older/Newer, Load more, Retry and root-opening callback checks attachment,
current publication, current top-route ownership and availability when activated.
Native semantics and pointer handlers share the same guarded callback. Submission
revokes sibling callbacks immediately, before outgoing content disposal. A new
rendered lease permits interaction after a same-turn open/Back even when no hidden
frame was composed; retained callbacks keep their revoked lease.

MainActivity supplies a live top-of-stack predicate, including exact Activity and
Sequence detail destinations. Removed detail entries also stop before session
acquisition: NavDisplay's outgoing composition cannot recreate an Activity or
Sequence session after Back has released it. Entries still on the stack retain
their existing mutation and committed-refresh delivery. HistoryController rejects browsing after route exit
and clears retry/cursor authority. Underlying History stays active during detail
navigation. Existing committed-mutation Refresh delivery is deliberately independent
of foreground browsing admission and remains unchanged.

HistoryFocus tracks native owner tokens and detachment/disabled-target recovery.
Visible entry/return lands at Back after window/frame availability. A surviving
row/control receives no passive focus request. Removed results and continuation
controls recover to Back, then Retry if their pending read fails. Every deferred
focus request rechecks live route ownership. Focus changes never invoke callbacks,
navigation or historical writers. Detail initial landing uses the same mechanism;
editor/modal policies remain deferred.

## Bounds and verification

Description formatting and grouping take O(n) work over the presented page
(n <= 100), plus its text length. Each callback performs constant-time reference
and route checks. Focus adds one native token per rendered target and constant
owner/recovery state. There is no per-row database access, detail hydration,
global sorting, global History scan or accumulated page copy.

Focused JVM regressions cover route exit/reentry, stale publication/route/disposal
admission, exact typed routing, clock rollback, legitimate background Refresh and
the existing discovery/continuation/provenance cases. Existing Activity mutation,
manual-entry, Sequence proposal, navigation and timestamp suites remain included.

Seven original JUnit4 methods in HistoryAccessibilityPresentationTest and three
in PrimaryExecutionJourneyTest are registered in instrumentation-inventory.json.
They cover duplicate typed records, one native action, native Enter, stable node
focus on reorder, obsolete callbacks before recomposition, removal/Retry recovery,
detail loading/unavailable/failure/values, EN/RU scale extremes and real isolated
MainActivity/NavDisplay journeys. Production journeys use Tab from Daily, exact
Activity/Sequence IDs, retained callbacks during detail and exit, same-turn Back,
Activity recreation, canonical paging and unchanged durable fixture facts.
The English recreation journey uses MainActivity's production content and temporarily
sets/restores scale preferences; replacing its content with a localized test wrapper
would give the saved navigation stack a different composition path on recreation.
Both detail kinds assert session release on return before testing retained callbacks.
The failed-read journey substitutes only the bounded read port and retains the
production controller and repository reader.

Automated device execution is Controller-owned. APK compilation does not execute
these assertions. No emulator/device task was launched by the agent and no TalkBack or Switch
Access walkthrough/certification was performed. Controller's full CI and managed
device gates remain required. Manual service validation should separately cover
EN/RU record descriptions, headings, original-zone endpoints, Retry, route returns
and combined app/system scaling.

## Local verification

The final focused check completed successfully:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data:assembleDebugAndroidTest --continue --console=plain
```

JVM reports contain 117 tests, zero failures, errors or skips. App ktlint, Detekt
and lint passed; production and both instrumentation APKs assembled. Inventory
validation found all ten new instrumentation identities, both resource files
parsed successfully, and git diff --check passed. The exit regression was first
run against the baseline and failed before the production guard was added.
These results do not establish device execution, CI equivalence or accessibility
service certification.

## Controller API35 fixture repair (2026-11-13)

Controller attempt 1 completed 649 app cases with five failures. Both duplicate
journeys failed in their shared fixture before navigation: the Sequence started
before its snapshot creation timestamp. The fixture now creates source Templates
first and gives the Sequence snapshot and start the same earlier timestamp, while
retaining the duplicate terminal timestamps and exact-ID navigation assertions.

The paging journey's injected controller used main-thread Room reads, including
newest-date discovery. Both read ports now use Dispatchers.IO, as the production
graph does. The initial failure explicitly asserts that the intended bounded root
read was reached and that its injected error owns Retry.

The scale matrix now measures native touchBoundsInRoot, matching the established
Plan target checks. It retains the physical 48dp minimum, displayed-state and
native-focus assertions and adds locale/scale/target diagnostics. The original
repeated-hour No-live regression now expects the localized completion label at
both offsets; its adjacent missing-versus-zero assertion checks the actual
localized zero-duration label.

No production implementation, query, writer or instrumentation identity changed
in this repair. The focused JVM/ktlint/Detekt and instrumentation APK command
passed (JVM results restored from Gradle cache):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest --continue --console=plain
```

The authorized repair-probe command exited before test execution with
"No active repair-probe lease." Consequently these five device cases have not
been re-executed after repair. Their original failures remain open for Controller
verification; neither this local compilation nor the unavailable probe is a device
PASS or CI PASS. No direct managed-device task was launched by the agent.

## Controller follow-up: History entry synchronization

Controller's subsequent five-case API35 run passed the paging, scale and
repeated-hour cases. Both duplicate journeys progressed beyond fixture setup but
then read HistoryControllerOwner before NavDisplay had composed the destination.

Their shared fixture now waits for the native History Back control, verifies its
keyboard landing, then reads the existing controller on the UI thread. The
factory still throws if production failed to create the controller; the test
does not create or substitute one. Content readiness, exact-ID navigation,
same-turn Back, stale callbacks, recreation and durable-fact assertions remain.

The only other owner access in this boundary is the intentional injected-reader
setup, which already runs on the UI thread before entering History. No production
code or instrumentation identities changed. The authorized probe again reported
"No active repair-probe lease" before executing tests, so these two repaired
journeys still require Controller device verification. The three reported passes
do not establish a full-suite or CI PASS.

## Controller follow-up: detail return and recreation

The next two-case API35 run reached detail return and recreation. The English
journey lost its native Back target after replacing MainActivity content with a
test composition whose saveable navigation path differs from production startup.
That journey now sets/restores production scale preferences around the fixture
and keeps MainActivity's own content throughout recreation. The Russian font and
localization wrapper remains confined to its journey, which does not recreate.

Detail entries also reacquired released sessions when NavDisplay recomposed
outgoing content after Back. Both Activity and Sequence entries now check exact
route membership before acquiring a session. Sessions and committed-refresh
delivery for entries still on the stack retain their existing behavior. The
journeys assert session release immediately after return, before invoking retained
row callbacks, and check Sequence session release at the adjacent return boundary.

Local verification passed with 117 JVM tests (zero failures/errors), app ktlint,
Detekt and AndroidTest APK compilation/package:

```powershell
.\gradlew.bat :app:ktlintCheck :app:detekt :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*history.*' --tests '*MainActivityNavigationTest*' --tests '*HistoricalTimestampTest*' --continue --console=plain
```

The authorized probe again rejected execution with "No active repair-probe lease."
Neither failing device journey has been re-executed for this patch; the supplied
assertion failures remain open for Controller verification. No direct device task
or manual accessibility-service walkthrough was performed.
