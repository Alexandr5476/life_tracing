# Android compatibility release gate

## Plan calendar and active-row accessibility (S1)

The mandatory inventory adds nine PlanScreenPresentationTest identities while
retaining all prior cases. They cover localized canonical calendar context, native
selection/actions, repeated management targets, strict temporal projection metadata,
keyboard recovery and EN/RU scale extremes. See [Plan calendar accessibility
evidence](plan_calendar_accessibility.md). Controller owns ci-fast, gmd-app and
the independent unfiltered compatibility gates; APK assembly supplies no device or
manual accessibility certification.

## Canonical-focus fixture and API26 graphics DMA repair (PR40, 2026-11-11)

Run `42942194350`, head `8f6d9186a8dcfb535dd2bdd658aa4215d04afc6e`, reports
two distinct failures. The original XML, testcase logs, native-focus artifact,
native tombstone and prepared/restored SurfaceFlinger readbacks were inspected.

API35's retained-destination case fails immediately after `controller.retry()`.
Controller Ready does not mean the deferred production keyboard landing has
finished: the complete 3,678-byte diagnostic (SHA-256
`c3a36799d39eb2280df58a7bd623bd67d10bf603cce8bea92d4ea6d7cf647172`)
shows Sequence Name (`Pointer workout`) focused instead of the requested Step
Manipulate button. The fixture now observes that canonical Name landing before
scrolling to and requesting the Step control. The native RequestFocus/Enter and
all retained-action, hidden-route, disposal and state-equality assertions remain.
The sibling hidden-reload fixture already observes Name on becoming visible;
destination-dialog helpers already observe their first production landing.
No production focus behavior or durable authoring semantics change.

API26's Russian compact Library case reaches a system-process crash at
02:42:07.923: system_server PID 2478, android.anim TID 2524, SIGSEGV/null
destination in `memcpy`. The retained full tombstone (`app-native-crash/04.log`,
SHA-256 `edcc2641dbb2ff1c3f20cd11700b66bf47417423779e7a600c2a286de17186c7`)
includes stack slots for `goldfish_dma_lock`, `goldfish_dma_write`,
`ExtendedRCEncoderContext::lockAndWriteDma` and `rcUpdateColorBufferDMA_enc`.
The unwinder itself has only two frames; the symbolic stack slots identify the
graphics upload path, not a complete unwound call chain. Both pre-test actual
SurfaceFlinger readbacks advertise `ANDROID_EMU_dma_v1`. This identifies the
faulting transfer mechanism; the underlying allocation/mapping failure and any
relationship to the earlier host emulator exit139 remain unresolved.

The existing private API26 CI profile now requests `GLDMA = off` in its own
`ANDROID_EMULATOR_HOME/advancedFeatures.ini`. Preparation, separate restoration
and all AGP/UTP invocations inherit that home; original environment values are
restored during cleanup. The requested file is retained with the profile's
artifact hashes. Before every suite, the runner verifies the private environment
and file. Preparation and actual snapshot restoration require a complete GLES
extension readback with the DMA-v1 capability absent and ANGLE/SwiftShader still
present. Missing, contradictory or DMA-enabled readbacks block admission.

This is a targeted mitigation of the observed native path. AOSP's
[gralloc implementation](https://android.googlesource.com/device/generic/goldfish-opengl/+/e877c8a2a0b1e2a092db0d89d7f4620aef561b7f/system/gralloc/gralloc.cpp)
has a non-DMA color-buffer upload fallback; its
[host feature gate](https://fuchsia.googlesource.com/third_party/android/device/generic/vulkan-cereal/+/ba29ba979efcfc2c2cd0f67d4060f451df459fcd/stream-servers/RenderControl.cpp)
controls DMA-v1 advertisement with GLDMA. The rendering backend, raster, snapshot
policy, memory guards, row deadlines, original inventories and Library assertions
remain unchanged. The alternative transfer path may affect timing and memory;
full hosted execution must establish its stability within the existing bounds.

Both unchanged exact-test probes passed (137.6 seconds), demonstrating neither
failure is reliably reproduced in isolation. Host-policy regressions cover the
DMA readback, private environment inheritance/restoration, changed configuration
and pre-suite admission failure. The prepared/restored DMA-admission regression
fails at both boundaries against the original profile. All 90 host-policy tests
pass; both original
prepared/restored CI readbacks are correctly rejected by the new gate. Windows
probes do not exercise the Linux37.2.13 CI profile. Fresh Controller execution and
independent exact-head CI remain mandatory; no local PASS closes either original
stability obligation.

## API26 native-exit evidence repair (PR40 follow-up, 2026-11-10)

Run `42803170467`, job `129785590584`, head
`0e1e1554841db3940ea10c7cd5d0cdde58094f21`, records an empty JUnit failure for
`StatisticsRepositoryTest.signedLongNumberLineagesBoundRankWorkAfterPhysicalReopen`.
The retained `data/.../pixel2Api26/emulator.1.fail.txt` ends with `EXIT CODE: 139`;
the emulator crash handler reports `no stack mapping`. The testcase log has no
assertion or application exception, and the data suite is incomplete. This does
not establish whether repository workload, the fixture, or the host emulator
triggered the native fault.

The existing host observer discarded SIGSEGV twice: its tracefs filter and event
parser both admitted only SIGKILL/SIGTERM. Its otherwise calibrated, nontruncated
signal log therefore cannot exclude a native emulator fault. Both boundaries now
share the observed Linux signal set, including SIGILL, SIGABRT, SIGBUS, SIGFPE and
SIGSEGV. Ready evidence records that set and the effective target scope. With
calibrated host PIDs, native fault records resolve the receiver's thread group:
known non-emulator targets are excluded so handled JVM faults cannot exhaust the
log; renamed emulator threads and already-exited/unresolved targets are retained.
Unmapped PID namespaces retain the kernel records without process attribution.
Events preserve the signal code and task identities, but a recorded signal alone
does not prove an unhandled fault or an upstream cause. Existing SIGTERM
calibration, bounded buffers/output, cleanup, command admission, failure
classification and test inventories remain unchanged.

The added fault-record regression fails for all five native fault signals before
the repair. Thread-group filtering and missing metadata also have regression
coverage. All 87 host-policy tests pass afterward. This is an evidence repair;
the original CI failure and stability obligation remain open pending fresh
Controller verification and independent CI. No production Statistics code or
instrumentation assertion is changed.
The host regressions use synthetic kernel records and process metadata; live
Linux tracefs fault capture remains unverified by the Windows repair probe.

## Hidden-IME fixture admission repair (PR40 follow-up, 2026-11-10)

Run `42750399074`, head `7ccaf04f66de709d81e38a0f2cf9975353451d85`, fails the
same Step-authoring Library journey on API26/API33 in `Fixture.invoke`'s new
IME-closing call after Activity recreation. Both original XML failures were read.
API26's complete diagnostic was recovered from all 80 acknowledged logcat frames:
81,689 bytes, SHA-256 `54074a09850d042f3063b30e86f9b3ab29ce9408e657f31495e67d551197f2eb`.
API33's retained diagnostic is 165,025 bytes, SHA-256
`faa38114216b4b3f1276b6162c404d5e0e63da06d56b5000c9fe46ae1e23a56c`.
Both show a hidden IME and no focused input window; neither records a SystemUI ANR.
These observations are not classified as an infrastructure retry.

The repository fixture imposed native keyboard-root admission even when the IME
was already hidden. The original journey uses semantic Done/SetText at that
boundary. A focused TextField is not sufficient evidence of a visible IME:
restricting the call by text focus passed recreation but failed later on the
override-only edit, with the same root-admission failure. That partial approach
was removed. `KeyboardInputFixture.closeKeyboard` now returns only when attached
root insets explicitly report the IME hidden. Visible or unavailable insets retain
the original native-window admission and Espresso dismissal. Native Enter/Back/Tab
and production-focus assertions retain their original admission requirements.
There is no retry, timeout increase, synthetic keyboard action or production change.

The original regression explicitly establishes that recreation retired its picker.
It then focuses the surviving Sequence Name input before invoking the retained
picker callback and semantic Done. The input matches both its label and fixture
value to distinguish expanded Step Name controls. Every original assertion and
persistence/reopen scenario remains.
Two additive mandatory fixture cases cover closing a hidden IME on an unfocused
background window while a real dialog owns native focus, and closing a visible
IME in its focused window without changing text. Current mandatory app inventory
is 585 named testcase identities; no original identity was removed.

Authorized exact-test probes reproduce both original failures on the unchanged
tree, advance to the later override failure with the discarded text-focus guard,
and pass both complete original journeys with the insets guard (206.2 seconds).
A separate explicit sibling scope passes native Enter Save, native manipulation
across recreation, the original journey and both new fixture cases on both APIs:
10 cases, zero failures/skips, 246.8 seconds. Reports are retained under
`.runtime/repair-probes/wf-d6c98ba32658580da1fb4d84f0005197/4e0f80b4b15f4bb3bea019861bf32a06/tree-4/attempt-1/`.
All 84 host-policy tests also pass with the additive inventory. Diagnostic probe
results do not supersede the original CI evidence; Controller's canonical checks
and fresh CI remain required. The underlying platform focus-transfer behavior
is unchanged.

Canonical local `gmd-full` attempt 1 subsequently exposed an invalid added
prerequisite: no editable text could be focused after recreation. Android had
legitimately focused the surviving Sequence Name input (`Step authoring`), while
the picker was already closed. The regression now explicitly establishes that
focused-input state instead of depending on platform focus restoration or prior
test input mode. No production focus behavior or IME admission policy changes.

The authorized API35 exact-test probe passes (136.4 seconds), including the later
override-only edits and physical database reopen. A sibling scope passes all five
cases without failures or skips: the original journey, native Enter Save,
manipulation across recreation, and both IME fixture cases (157.4 seconds).
Reports are retained under
`.runtime/repair-probes/wf-d6c98ba32658580da1fb4d84f0005197/fb897e47f479429888a69f25e6958113/tree-3/attempt-1/`.
These scoped results do not replace Controller's unfiltered local and independent
CI gates, including fresh API26/API33 evidence for the original failures.
Local app ktlint passes; Detekt, JVM tests and AndroidTest APK packaging complete
successfully with up-to-date outputs. All 84 host-policy tests pass again.

## PR40 CI repair: native fixture ordering and suite deadlines (2026-11-10)

Original run `42726834230`, head `b4876085a7734adb1b3198844fb099b5a97f4ba5`,
reports eight failed app cases across API31/API33. The original JUnit XML and all
six named native-window diagnostic files were read from the retained reports;
no emulator internals or replacement CI artifacts were used.

- API31 `LibraryAccessibilityRouteTest.nativeStepAuthoringAndOverrideOnlyEditsPhysicallyReopenWithoutRewritingFrozenOwnership`
  fails in the fixture's OnClick on a detached, formerly focused **Done** button.
  The fixture used a previously exposed lazy action while focus/IME viewport
  changes could still detach it. It now closes the IME through the stable editor
  root after transferring focus, reveals the current action after native dismissal
  completes, and performs one semantic OnClick. No action retry or
  retained-callback admission rule changes. The adjacent revision, snapshot,
  override, Plan, live-runtime and physical-reopen assertions remain intact.
- API33 `MainActivityRouteSessionTest.planOriginDeliversLiveToDailyAndNoLiveBackToRefreshedPlan`
  reaches the final heading assertion after no-live fulfillment and row removal.
  Plan retains the scroll used to reach the action; an existing heading need not
  be in that viewport. The fixture scrolls the heading into view before its original
  display/count assertions. Routing, fulfillment and execution-count checks remain.
- The six API33 focus failures are in the Sequence manipulation presentation and
  persistence fixtures. Each diagnostic reports an interactive, unlocked device,
  attached focused app window and keyboard mode; the chooser's first destination
  is focused while the requested Cancel or other destination is unfocused. The
  chooser intentionally suspends its initial landing for scroll/layout and window
  admission. The fixtures previously requested the next target after idle alone,
  racing that landing. Both native activation helpers and the sibling Library
  route invocation helper now observe production focus on the first destination
  after opening the chooser, before the next request or destination scroll.
  RequestFocus, focus assertions, native Enter/Tab, stale destination rejection,
  Undo/Redo/Discard, Apply/reload recovery and persistence assertions remain.
  No focus assertion retry, synthetic click replacement or production focus change.

The API35/API36 retained `evidence.json` records a 3300-second row budget, passing
complete data inventory, app `abort_reason=execution deadline exceeded`, and startup
`NOT_RUN`. Their app logs end at **410/584** and **401/584** respectively, each with
zero reported failures. This is an execution-budget stop after testcase progress,
not evidence of the older pre-test API36 startup ANR or of suite success.

All five API rows now share the **110-minute execution / 120-minute job** policy.
Preparation, data, app and independent startup share one bounded deadline, with
10 minutes reserved for setup, cleanup and uploads.

Follow-up CI42776221469 on `ce18f86e54cda2349163ec54fa4ec63fd6801fdd` passed
five jobs; API31 exhausted its remaining 3300-second budget after complete data
inventory and **574/586** app cases with zero reported failures. Its retained
evidence records `execution deadline exceeded` and startup `NOT_RUN`. API31
and API33 now receive the same bounded allowance as the other full-suite rows;
API33 passed that run and is included for a consistent matrix-wide budget.
This incomplete API31 run remains a failure pending fresh verification.
API26's bounded signal observer, memory guards and profile are unchanged; API36
keeps one Gradle worker. Full unfiltered suites, inventory validation, independent
startup, cleanup and report retention remain mandatory. Host regressions exercise
complete inventories beyond the old bound, decreasing per-invocation allowances,
expiry blocking startup, and the workflow's cleanup/upload allowance.

The eight original CI failures and API35/API36 incomplete suites require fresh
Controller verification and independent CI. Local checks or a focused probe do
not supersede that evidence or close the remote stability obligation.

Local repair validation: all 84 host-policy tests pass; the new deadline tests
failed against the old budgets before the runner/workflow fix. App ktlint, Detekt,
JVM tests and `:app:assembleDebugAndroidTest` pass. The authorized
`D:/controller/.runtime/agent-tools/repair-probe.cmd` declined execution with
"No active repair-probe lease"; no device testcase ran and no corrected device
PASS is claimed. No full Managed Device or remote CI check was launched.

## Library callback ownership regression inventory (W3, 2026-11-04)

The Library callback comparison fix adds three original-JUnit identities to
`LibraryActionAdmissionTest`, with no removals. Current mandatory counts are
**528 app, 627 data and 2 independent startup cases per API**. Details and exact
case names are in [Library accessibility](library_accessibility.md#w3-bounded-callback-ownership-comparisons-2026-11-04).
The native counting/focus/action regressions compiled and the AndroidTest APK
assembled; no device execution is claimed by this correction. Controller-owned
unfiltered API 26/31/33/35/36, exact-head CI and existing persistence/reload cases
remain required. Manual TalkBack/Switch Access remains unverified.

## API26 native system crash evidence (2026-11-04)

CI run `41762283141`, job `126352488365`, at head
`b80d1e504332a43e4120cfb65e8da31e5a6799b7` stops after 329 app cases.
The retained failure in `runtimeAddAllEditorsAndWritersAreDisabledDuringAdmittedCommand`
surfaces in `ActivityScenario.close`/`ContextImpl.sendBroadcast`. Its native log
first records system_server PID 2560, thread 2606 (`android.anim`), crashing at
03:08:44.195 with SIGSEGV/null dereference; the logged stack has `memcpy+465` and
an anonymous heap address. At 03:08:47.904 com.android.phone reports system death;
the testcase reports DeadSystemException afterward. This establishes the immediate
system-process failure, not the native defect or a particular triggering operation.
The host memory guard did not abort this invocation. Missing cases remain missing.

The log identifies `/data/tombstones/tombstone_00`, but no tombstone or system/event
buffer capture exists in the retained artifact manifest. The runner previously
triggered capture only for ANRs and removed the guest before startup. It now detects
DeadSystemException once per invocation, captures main/system/events/crash buffers,
reads native tombstones and both persistent native-crash DropBox tags, and retains
command status, stderr, partial timeout output and SHA-256 before cleanup. Selection
still requires one online emulator with the expected API. Each of six commands has
the existing five-second bound. Tombstone access uses a read-only `su 0` command
on the disposable AOSP guest; unsupported/denied access remains explicit evidence.
No root setting, permission, renderer, snapshot/raster profile or budget changes.

Capture errors cannot replace the original result. The runner continues draining
Gradle output and collecting JUnit; an observed system death cannot certify green
reports. Independent startup still runs after successful cleanup on its own guest.
Two regressions cover capture-before-cleanup, one-time capture, contradictory green
reports, preserved output, capture errors, complete native evidence, permission
denial, partial timeouts and admission rejection. The original failing fixture,
adjacent assertions and all mandatory inventories remain unchanged.

The unchanged isolated API26 repair probe passes (204.3 seconds), which does not
reproduce the CI crash after the preceding workload. Original XML, native log,
artifact/run manifests and host-memory samples are preserved with hashes under
`D:/controller/.runtime/codex-runs/wf-2933485f29755d0f9317fdc0a813f2f8/0afe418ce40262dc85ab1313/scratch/original-ci/`.
The system_server crash and remote stability obligation remain unresolved. This
patch repairs the confirmed native evidence omission; Controller owns fresh
canonical execution and exact-head CI.

## CI execution budgets and API36 startup contention (2026-11-04)

API26 now has a **110-minute shared execution budget inside a 120-minute job**.
This supersedes the historical 55/60-minute limits below. Preparation, data, app
and independent startup share one deadline; cleanup and report uploads have a
ten-minute allowance. The existing signal observer remains bounded at 115 minutes
and still exits on owner disappearance or normal cleanup. The 2026-11-10 repair
extends this policy to every API row after the later API31 deadline stop.
[GitHub permits six-hour hosted jobs](https://docs.github.com/en/actions/reference/limits).
CI41493383857 exhausted its 3300-second budget after all 627 mandatory data cases
and 373 app cases passed. The latest scaled-raster profile peaked at 2,058,256 KiB
QEMU RSS, with no memory guard crossing or observed OOM. Earlier OOM/8GiB failures
used different profiles. Full API26 completion is still unproven; the 8GiB/1.5GiB
memory guards, original inventory, raster checks and assertions remain required.

API36 now uses `--max-workers=1` for all three invocations. Its retained job log
shows GMD setup overlapping Kotlin/Dex tasks, including Dex work during the
SystemUI ANR window (91% guest CPU; 40% SurfaceFlinger kernel CPU). SystemUI waited
in `getGpuContextPriority` before any instrumentation case. Gradle's
[worker limit](https://docs.gradle.org/current/javadoc/org/gradle/StartParameter.html#getMaxWorkerCount())
serializes build workers, including device setup, to reduce startup contention.
Resource starvation is a hypothesis, not an established root cause. Existing
standalone and minimal-GMD controls passed with the same SwiftShader backend;
there is no justification here for another renderer change or private boot loop.
The real hosted run must establish whether this launch change resolves the ANR.
All health checks, deadlines, complete suites and cache exclusions remain active.

## API26 scaled raster draft (2026-11-02)

Run41389414006/head42db0045 used the task-snapshot profile successfully but still
hit the8GiB guard after513 app cases passed (expected526); startup did not run.
The profile is insufficient on its own. Its native app log also contains186
missing ColorBuffer events and186 corresponding invalid binds; duplicated UTP
logging is not counted again. Those errors have not been attributed to snapshots.

The API26-only `--api26-scaled-raster` option prepares a720x1280/dpi280 window
manager override in the disposable guest before saving default_boot. The original
GMD Pixel2 hardware profile remains1080x1920/dpi420. The effective raster and
physical density coverage **change**; this must not be described as testing the
original Pixel2 physical raster. Other API rows, including the canonical API35
row, retain the original raster. The logical real-display size remains exactly
411.428571x731.428571dp; the observed app area remains411.428571x683.428571dp.

This is supported by a bounded comparison of five unchanged original API/UI
cases covering the mandatory API/profile check, English/Russian scale extremes,
pointer dragging and Live layout reflow. Each arm passed5/5 in one instrumentation
invocation:31.66s/RSS growth239.98MiB versus30.91s/84.68MiB (64.715% less growth).
This is RSS/PSS evidence, not allocator in-use, full526-case evidence, a speedup
claim or proof that CI will finish. No original DPI-dependent assertion or case
is removed or weakened. Existing launcher resources are vector XML; that does not
make the changed physical density coverage equivalent to the original profile.

Disabling API26 Task Snapshots leaves its legacy recents-thumbnail path active:
AOSP8 ActivityRecord captures a legacy screenshot when ENABLE_TASK_SNAPSHOTS is
false. A private Settings-to-Home transition produced a Bitmap and thumbnail.
The proportional display override changed that PNG from648x1038 to432x692,
exactly4/9 the pixel area. This does not attribute the CI memory growth solely
to thumbnails or prove a leak. No app security, system image, SELinux, GC or
allocator policy changes are made.

Admission requires actual API/field/backend plus real720x1280/dpi280 and app
720x1196 readbacks before save and after a separately restored snapshot. Existing
mandatory data/app/startup tests also check the actual effective raster/density.
The flag requires guarded API26 SwANGLE and task-snapshot preparation; failures
block all suites. The shared3300-second row deadline,60-minute job,8GiB guard,
original unfiltered inventory and all assertions remain. This is an unreviewed
draft until native save/restore, regression/static checks and a new exact-file
review are recorded. API36's CI SystemUI startup ANR remains independently open;
a local actual-UTP diagnostic passed with its existing legacy SwiftShader backend.

## API26 task-snapshot preparation

The Linux API26 row prepares a private GMD `default_boot` before any product
instrumentation. This profile requires the guarded SwANGLE path and the measured
Emulator37.2.13. The ordinary GMD setup creates the Pixel2/API26 AOSP image; the
preparer sets `persist.enable_task_snapshots=false` in that disposable guest and
restarts only its zygote services before tests. It verifies old framework absence,
a new system_server lifetime, actual API and framework field, and actual ANGLE on
SwiftShader. A separately compiled read-only diagnostic APK is uninstalled before
saving. A new read-only emulator must actually load the saved snapshot and repeat
the field/backend checks. A loadability exit code alone is insufficient.

API26 uses one private `ANDROID_AVD_HOME` for preparation and all three GMD suites.
ADB uses5037 consistently with UTP. Setup, helper compilation, boot and restoration
consume the existing shared55-minute budget; the job remains60minutes and the
host memory guard remains8GiB/1.5GiB. Preparation/cleanup failures block admission.
The existing mandatory API identity test in data, app and independent startup
also asserts the actual framework flag when this explicit profile is requested.
All original cases and assertions remain; no test count is reduced. Other API rows
retain their existing device profile. No framework restart, GC signal, allocator
operation or system-image/SELinux change occurs during a product suite.

The justification is a controlled64-case A/B: disabling task snapshots reduced
host in-use growth291.95MiB to173.10MiB, with all cases passing. A subsequent65-case
distinct sample passed. Residual growth remains and speedup was not established.
Actual AGP8.10.1 setup/reuse and UTP profile admission were independently checked:
one diagnostic deliberately expecting true failed on actual false, then the same
diagnostic expecting false passed. These results do not establish full inventory
completion or hosted timing. New canonical and exact-head CI evidence are required.
Snapshot metadata can change during a read-only load, so its recorded hash is
evidence at save time, not an immutability gate across subsequent loads.

## API26 hosted shutdown observation

The API26 row enables an isolated Linux tracefs observer for TERM/KILL generation
and, when available, OOM victim selection. Before starting Gradle it must observe
a known SIGTERM sent only to its own disposable calibration child. Failure to
calibrate stops the experiment with an explicit error. Other API rows are unchanged.
The observer records bounded public task names/IDs, start identities and event
times in live job output and `ci-evidence/api26/host-signals-*/`; it never reads
process arguments or environment. It does not intercept or forward test signals.
Its private trace instance is removed on completion, owner exit or a one-hour
deadline. Buffer statistics and truncation markers expose incomplete observation.

Kernel task IDs can differ from `/proc` IDs in a PID namespace. Calibration records
whether direct mapping is valid; identities are enriched only when it is valid.
An observed sender identifies the immediate kernel task, not necessarily the
upstream cause of a hosted shutdown. A lost VM or unavailable final log remains
inconclusive. No support request is sent by this experiment.

The 60-minute job timeout, shared 55-minute row budget, renderer, unfiltered suites,
mandatory inventory and five required API rows remain unchanged. Local API26
completion with a longer diagnostic safety ceiling does not replace hosted CI
or establish that the same workload will finish inside the hosted row budget.

## Library review corrections W1/W2

Adds five native Library action-admission cases and one production-route retained
callback case, extending the existing background-archive/reopen case. Every prior
original-JUnit identity remains mandatory. The current inventory requires **521 app,
627 data and 2 independent startup cases per API**. See
[Library review evidence](library_accessibility.md#pr-39-review-corrections-w1w2).
The follow-up repair binds native semantics actions to their original presentation
callbacks; its authorized 17-case API 35 group passed, including all four canonical
failures. This is scoped diagnostic evidence. Controller retains all unfiltered API
and exact-head gates, and manual TalkBack/Switch Access remains unverified.

The later full API 35 attempt reports 120 native-window failures, all naming the same
SystemUI ANR window. An unchanged-source authorized probe passes all 120 original
identities, but the ANR cause and original full-suite stability obligation remain open.
See [attempt 2 evidence](library_accessibility.md#canonical-attempt-2-systemui-window-obstruction).
The diagnostic continuation adds five mandatory cases and a bounded, once-per-process
native SystemUI capture to the shared keyboard fixture. Its REAL API 35 probe passes
all 120 original failures plus the five new cases. This repairs evidence collection;
the original ANR/full-suite stability obligation remains open. See
[diagnostic repair evidence](library_accessibility.md#diagnostic-repair-retain-native-systemui-evidence).


## Activity Template authoring accessibility (Slice S4)

Adds fifteen Activity editor presentation identities and four isolated production-route
physical-reopen identities. The mandatory inventory is now **510 app, 627 data and
2 independent startup cases per API**, with zero prior removals. See
[S4 Activity editor evidence](activity_template_editor_accessibility.md) for typed
Field/option actions, native focus/keyboard/scaling, Save/Conflict admission,
physical-reopen canonical/provenance assertions and exact local check results.
The repository writers, schema and historical snapshot rules are unchanged. The
confirmed S4 no-op rule now compares Field/option identity and persisted positions
independently of incidental materialization order; real changes retain the existing
revision policy.
Controller owns all unfiltered API 26/31/33/35/36 managed-device gates, independent
startup and exact-head GitHub verification. AndroidTest compilation and automated
semantics are not device execution or manual TalkBack/Switch Access certification.

The 2026-10-24 CI repair retains every original identity and adds one sleeping-display
keyboard-fixture regression. It addresses Library dialog-window admission, asynchronous
focus recovery and keyboard-fixture display prerequisites. See the S4 record's
cross-API CI repair section for the supplied failures, diagnosis limits and local
verification. Full device execution remains Controller-owned.

CI run `40088219148` remains unclosed. The follow-up retains refused focus
requests, observes keyboard eligibility and recovers from tracked-target removal;
its regression additions and native-window diagnostics are recorded in the S4
record's pending-focus repair section. Local emulator setup failures are separate
from the supplied CI testcase failures and do not establish device PASS.
The continuation also closes a hidden Library name-dialog window and retires its
submission lease, while requiring native-window admission before fixture navigation.
See the S4 native-window ownership section for the unchanged-tree, zero-test probe
and the unresolved historical API 36 initial-window cause.
The runner also now retains full-suite native logcats before independent startup
replaces app output. Its two new host regressions reproduce that evidence loss;
all 19 runner tests pass. This preserves future diagnosis and does not close the
supplied native focus failures or change mandatory test admission.
The renewed scoped probe completed twelve zero-test executions across three
automatic attempts (47 observed emulator boots). API 26/31/33 stopped in snapshot
setup; API 36 exceeded the batch-wrapper command-length limit before Gradle.
The final fixture correction captures the complete WindowManager dump so Android
16 display focus ownership is retained, with an existing keyboard test extended
to verify the admitted application's owner. Fresh app static checks and AndroidTest
APK compilation pass; device execution of that assertion and the canonical
stability obligation remain open. See the S4 authorized-probe continuation for
the transport calculation, original failure classification and evidence paths.

After Controller recovered transport/startup, fresh runtime evidence narrowed
API 36 to one incorrect Search selector (55/56 scoped cases already passed).
The final repair establishes native keyboard mode per admitted window and records
focus-target detachment before Compose owner-focus invalidation, using a shared
Library/editor modifier hook. The authorized tree-6 probe now passes all 75
original scoped identities: API 26 1/1, API 31 1/1, API 33 17/17, API 36 56/56,
with zero failures/errors/skips and safe device cleanup. The 48 focused JVM tests
and ordinary app static/lint/APK checks also pass. See the S4
[final scoped repair](activity_template_editor_accessibility.md#restored-runtime-probes-and-final-scoped-repair)
for red/green evidence, exact report hashes and the historical diagnostic limit.
The unfiltered five-API suites, startup checks and independent exact-head CI
remain mandatory; the scoped local PASS does not close canonical CI stability.

S4 Controller attempt 1 stopped after 148 app cases with six failures and a device
SurfaceFlinger crash. The two authorized six-case repair probes progressed from
four to five passing cases and exposed an unchanged configuration after archived
Field/option materialization incrementing revision 2 to 3. The explicit human
decision resolved this stop boundary; the shared comparison is corrected and the
original assertion remains. See the S4 record's API 35 repair and no-op decision
sections for regression and verification results. Inventory stays 509/627/2;
full unfiltered compatibility and independent GitHub verification remain required.

S4 Controller attempt 2 passed all 15 Library route cases, all 6 editor persistence
cases and all 627 mandatory data cases, including physical reopen and the restored
no-op assertion. It stopped after 393 app cases with nine failures and a native
input dispatch timeout. The authorized nine-case repair probes progressed from
five to eight to **nine passing cases**. The final REAL probe passed all nine with
zero failures, errors or skips after scoped editor Tab/Shift+Tab navigation and
fixture synchronization/target-bound corrections. App JVM tests (421), ktlint,
Detekt and AndroidTest APK assembly also passed. See the S4 record for exact
commands, original identities and artifacts. Inventory remains **509/627/2**;
this focused PASS does not replace the complete Controller-owned gates.

S4 Controller attempt 3 stopped after 261 app cases with nine failures and a
SurfaceFlinger abort at its 4,096-layer limit; all 627 mandatory data cases passed.
The fresh nine-case baseline reproduced Library focus restoration after Cancel.
After fixtures began awaiting exact native-root and semantic-focus completion,
the repaired REAL probe passed **all nine**, with zero failures/errors/skips
(150.2 seconds). All focus, typed-action, stale-callback and no-write assertions
remain. App ktlint, Detekt and AndroidTest APK assembly passed; this repair changes
only test synchronization. See the S4 record for exact artifacts and platform-crash
evidence. Inventory is unchanged. Full-suite native stability and all complete
Controller-owned gates remain required.

## API 26 CI interruption: bounded diagnostic continuation (2026-10-27)

CI run `40152984828`, job `121058069021`, at head
`ae78358d21c3f233fbe6130dbd4611632725b240` stopped after 314 completed app
cases. Its 315th testcase,
`ExpandedLiveSequenceScreenTest#alreadyOpenOccurrenceMenuIsDismissedWhenStateAuthorizationChanges`,
has an empty failure entry and elapsed time of 374.641 seconds. The original XML
SHA-256 is `f2c4ff99b13ea7d10d647d46c3ac66c20076933d80c40cb037cb608f93b745e7`.
Native output stops at `03:16:06.761`, after the Activity's PAUSED event, without
a TestRunner finish, assertion stack, ANR or Java/native crash message. The host
logcat command eventually exits 255 at `03:22:20.929`. This does not establish
whether the test body completed or what interrupted teardown/device transport.

The repository collector had a confirmed diagnostic-loss defect: it retained only
two logcat filename patterns before independent startup reused app output. UTP
logs, instrumentation status/command logs, binary results and additional native
output were overwritten. Parsing a malformed first XML also prevented later
original XML files from being copied. The collector now snapshots all selected
device output, additional output and the merged result protobuf before parsing
any XML. It excludes transient `.lck` files, streams bytes and hashes, separates
startup artifacts, and writes `<module>-artifacts.json` before validation so hashes
remain available when validation rejects the invocation. Mandatory inventory,
commands and gate admission are unchanged. This shared collector covers data,
app and startup at every API.

Two host regressions reproduce the evidence loss against the original HEAD
collector; all **21** runner tests pass against the correction. Coverage includes
startup overwriting UTP, nested command/status logs and binary results, a large
binary-tailed logcat, malformed-first XML, additional native output, merged results,
empty device output, and preservation of the existing rejection rules. Original
red/green logs and results are in the current operation's
`scratch/collector-regression-*.log`, `scratch/collector-regression-results.json`
and `scratch/collector-regression-final-results.json`.

Authorized REAL diagnostic probes, using only `gmd-api26-app`, established:

- Both Expanded Sequence classes together: **64/64**, zero failures/errors/skips,
  313.5 seconds; lease `083d9f3b219e4bc694e16a2098ae1096`, `tree-2/attempt-1`.
- The **31 explicit classes** reached by the original CI report: **357/357**, zero
  failures/errors/skips, 1148.6 seconds; the same lease, `tree-3/attempt-1`. Its
  first **315 testcase identities and execution order exactly match** the failed
  CI report. The original failing testcase completes in 1.334 seconds. Local XML
  SHA-256: `f1c99e97d2608d75f3d2ce5f30c4d2a7c73d34500685d0afd96e5d4eec8d1480`.

Before any later invocation, original JUnit, UTP, binary/native/command output and
Controller command/cleanup logs were copied into the operation
`D:/controller/.runtime/codex-runs/wf-2933485f29755d0f9317fdc0a813f2f8/0fb2997f0793a64cadd888f5/scratch/`:
`class-probe-evidence/manifest.json` indexes 108 artifacts and
`prefix-probe-evidence/manifest.json` indexes 402. Each manifest records hashes;
the prefix manifest also records the original-order comparison. Scope JSON files
record both hypotheses and exact explicit class lists.

The underlying CI interruption remains **unresolved**. The local grouped and
original-prefix runs do not reproduce it. The retained local native logs report
GLES 2 while CI reports GLES 3; this observed environment difference does not
prove a graphics cause. No application assertion or behavior was changed. Further
repetition of these passing local scopes would add no justified evidence. Fresh
Controller-owned CI with the corrected diagnostic retention is needed to expose
the original runner/device failure if it recurs. These diagnostic passes grant
no canonical CI PASS credit or closure of the open stability obligation.

### Command I/O continuation

The next physical turn preserved the three partial file changes and verified every
hash in both retained probe manifests: all **510** artifacts still match, and the
original 315-case prefix/order comparison still holds. No passing device scope was
repeated.

A new host experiment confirmed another path through the same evidence-loss
boundary: an I/O exception while streaming Gradle output, waiting for the process,
or closing its output skipped collection and allowed startup to overwrite the
original app artifacts. One regression exercises all three phases with real
temporary JUnit, UTP, protobuf and command-log files and a simulated Gradle process.
Each phase reproduces missing retained artifacts before the repair.

The shared invocation loop now attempts collection after a command I/O error,
records `execution_error` separately from `evidence_error`, and rejects a command
error even when the process returned zero and the report inventory passed. Normal
test/validation failures still permit the independent startup invocation. All
**22** runner tests pass. These are host-only changes; no application assertion or
runtime behavior was changed, and the original CI interruption remains unresolved.

Fresh red/green logs, `transport-retention-results.json` and
`retained-evidence-validation.json` are under
`D:/controller/.runtime/codex-runs/wf-2933485f29755d0f9317fdc0a813f2f8/8b7c4afce6a347aa88216537/scratch/`.
This experiment confirms the repository's command-error collection defect, not
that CI run `40152984828` took that Python exception path. Fresh Controller-owned
CI remains necessary; no additional local PASS credit is claimed.

## Bounded unhealthy-device rejection (2026-10-27, controlled repair)

The completed diagnosis for head `8eed6675930dcaaab4b568b89c9f38a5eb86c925`
identifies the same startup ANR window (`cfaccaa`) in all 113 reported API 36
failures. SystemUI's `getGpuContextPriority` Binder wait precedes data
instrumentation; the downstream product assertions were not reached. Earlier
runner repairs captured this obstruction but continued executing until CI's job
timeout. The underlying SurfaceFlinger delay remains unexplained.

The runner now monitors the selected emulator during setup/snapshot boot and
instrumentation, independently of whether Gradle produces another output line.
It requires an initially exclusive emulator environment, checks serial, API and
AVD identity, observes boot readiness and rejects an
ANR window or persistent SystemUI ANR report. Empty `lastanr` is never health
evidence. Offline observations confer no target ownership; incomplete boot does
not confer health admission. A wrong API,
ambiguous devices or changed AVD fail admission. A failed health read remains
pending for a bounded recovery interval rather than immediately aborting. This is
periodic early rejection, not a claim that every platform fault can be detected
before the first testcase starts.

CI run `40585512758` on head `79517c57784a20e01fa2dc85a697b6eae30595d0`
exposed a runner regression on all five APIs before app tests: API 26/31/33
reported transient `adb getprop` transport closure, API 36 a five-second getprop
timeout, and API 35 a five-second window-service timeout before boot completion.
The runner itself then stopped Gradle with exit 143; these are not app assertion
failures or evidence that GitHub terminated those jobs. This finding does not
identify the cause of the older CI388 runner shutdowns.

Health probes now allow up to 300 seconds of continuous unavailability, matching
the existing GMD setup allowance. Disconnection, offline state and unavailable
properties cannot reset that bound; a complete healthy observation does. Concrete
ANR evidence, wrong API, ambiguous devices and changed AVD still fail immediately.
The abort reason is also printed in the job log. Regressions reproduce the exact
closed/timeout cases before and after readiness, successful recovery and permanent
unavailability; all 40 host tests pass on Windows and Linux. The complete local
API35 suite (632 data cases, five skipped; 510 app cases, no failures) and 113
focused API36 cases passed before this fix through Controller's direct Gradle
checks. Those passes did not exercise the CI Python monitor. New exact-head CI
remains mandatory to validate the complete GitHub execution path.

All invocations share a 55-minute execution budget, leaving room inside the
existing 60-minute CI job for diagnostics, cleanup and uploads. Each invocation
receives the remaining row budget. A separate 25-minute invocation cap and a
50-minute row cap were rejected during review: the verified successful API 26
CI387 attempt2 took 30:29 for data and 50:46 overall. A regression replays those
durations and requires all three suites to complete. SDK provisioning is bounded
to three minutes.
On a blocker or deadline the runner first retains the existing bounded platform
capture, then terminates its process group and verifies cleanup. On Linux it
also adopts/reaps orphaned descendants to cover detached single-use Gradle
daemons; cleanup escalates only its owned processes, preserving PID/start-time
identity. Emulator cleanup revalidates the selected API/AVD before `emu kill` and
checks disappearance. No ANR dismissal, testcase retry or platform recovery is
performed. Unverified devices are never killed.

An unhealthy/expired invocation or failed cleanup fails the row and marks later
mandatory invocations `NOT_RUN`, with no execution or inventory PASS credit.
Ordinary testcase failures still allow independent later suites after verified
cleanup. Before launch, previous selected-device output is moved under
`ci-evidence/api<API>/previous/<module>/`; a build/admission failure cannot certify
old green XML, including when startup reuses app's output paths. Current original
XML/native artifacts are still retained before inventory validation. Evidence
records include health observations, abort reason and cleanup outcomes and are
written after each invocation as well as at completion.

The AGP 8.10.1 task graph was inspected with a local **dry run only** of
`:data:pixel2Api36DebugAndroidTest`. It schedules packaging before device setup
in that graph. No compilation/boot separation experiment or task-rerun strategy
change is included: compilation overlap remains an unproven hypothesis. All
three commands retain full suites, `--rerun-tasks`, the SwiftShader renderer and
the original mandatory inventory at every API.

Host regressions cover quiet-process deadlines with real disposable Python
children, early rejection/capture, persistent ANR admission, identity checks,
owned cleanup/escalation, stale evidence isolation and truthful blocked-suite
reporting. Existing inventory, artifact-retention and orchestration regressions
remain required. Local `python -m unittest discover -s .github/scripts -p "test_*.py"`
passes **38 tests** on Windows and WSL Linux, including all 26 existing runner cases
and the successful API 26 duration regression. The initial four new
admission/deadline regressions failed before implementation. Host checks and
simulated Linux/adb cases do not certify the
Linux emulator or application journey. Controller owns fresh exact-head five-API
CI, independent startup and the unresolved API 26 cancellation investigation.
The original startup stall, unexecuted application cases and manual accessibility
walkthroughs remain unverified.

## API 36 SystemUI window obstruction (2026-10-27)

CI run `40425756902`, job `121930975075`, at head
`504471b8814c98b2a689567d8eb683a745efcff3` completed six app cases before the
first native-window admission failure. All **110** reported failures across **12**
classes reach the shared KeyboardInputFixture admission boundary and name exactly
the same owner: `Window{a73a7ce u0 Application Not Responding: com.android.systemui}`.
LifeTracing's Compose View is attached, but its native window lacks focus; the
focused-app record still identifies LifeTracing. The SystemUI ANR dialog explains
the shared focus obstruction. This is not evidence that the downstream product
assertions failed. The job was canceled after reaching 344 of 510 app tests.

The retained app `emulator.1.txt` records Emulator **37.2.12.0**, a successful
`default_boot` snapshot restore followed immediately by missing ColorBuffer/invalid
texture errors. Local capability reports **37.1.11**. These observations suggest
a platform/graphics investigation; they do not prove why SystemUI became
unresponsive or establish that a version change fixes it. Android's official
[snapshot troubleshooting](https://developer.android.com/studio/run/emulator-snapshots)
warns that software-rendered snapshots are unreliable. No speculative emulator
downgrade, test skip, dialog dismissal or application focus workaround was added.

The repository's existing AGP logcat command captures only `main` and `crash`,
omitting system/event buffers containing ANR reasons. The CI runner now captures
diagnostics **once per invocation**, at the first reported SystemUI ANR window,
while that emulator and dialog still exist. It selects exactly one online emulator,
verifies its API against the job, and reads `dumpsys activity lastanr`, system/event/
crash logcat, WindowManager and SurfaceFlinger state. Each of six commands has a
five-second timeout; partial output, stderr, failed commands and unavailable or
ambiguous targets are retained with timestamps and hashes. It never sends input,
dismisses the dialog, restarts a process, or retries a testcase.

Logs and their manifest are written immediately under
`ci-evidence/api<API>/<module>-system-ui/`, independently of final JUnit creation or
job cancellation. The observed blocker remains a failed gate even if contradictory
JUnit reports are green. Mandatory inventory and the three complete invocation
commands remain unchanged. Three new regressions fail before this repair;
all **25** runner tests now pass, covering one-time capture before startup,
target/API admission and timeout/command-error evidence. No device run was repeated.

The complete 110-case source/fixture index, common-owner check, original log hashes
and red/green host evidence are under
`D:/controller/.runtime/codex-runs/wf-2933485f29755d0f9317fdc0a813f2f8/7b61ea95b4e2b662b3d9e6d4/scratch/`.
The SystemUI ANR's underlying cause and the CI stability obligation remain open.
Controller owns fresh canonical execution and CI; prior local full-suite passes
do not close this remote failure.

### Persistent startup ANR trace gap

The next capture, from CI run `40445540358`, job `121995817380`, at head
`01c49f9c6645e1148bc4dfc849c3629654578c37`, narrows the obstruction to a saved
device-startup failure. System/event log `app-system-ui/04.log` records SystemUI
PID 983 failing to complete startup at **03:44:14**, with ANR report
`/data/anr/anr_2026-10-27-03-44-13-447`. The same log creates ANR window `d6b1ccf`.
This precedes the first data test invocation at **03:45:05**. The app emulator later
loads `default_boot`; its captured system log still contains those startup events,
and window `d6b1ccf` acquires input focus again at **04:00:25**. App tests begin at
**04:00:37**, then 112 cases across 12 classes fail native-window admission before
the job is canceled at 348/510. These observations identify an invalid boot-state
prerequisite; they do not show a downstream product assertion failing or prove
which emulator, graphics, snapshot or startup condition caused the ANR.

Despite the retained ANR event, `app-system-ui/03.log` reports **no ANR since boot**.
The earlier capture therefore still omits the blocked SystemUI thread stack. The
runner now also requests `dumpsys dropbox --print system_app_anr`, Android's
[persistent report dump](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/DropBoxManagerService.java).
It runs immediately after `lastanr`, without depending on that command's contents,
and before the other platform dumps. All seven commands retain the existing
five-second bound, partial-output/error handling, target/API checks and one-time
capture policy. An unavailable, expired or denied persistent report is retained
as evidence, not treated as a recovered or healthy device.

The new host regression first fails in all three subcases against the previous
collector. After the repair, all **26** runner tests pass, including an empty
`lastanr` with a saved startup stack, permission denial and timeout with a partial
stack; later window/SurfaceFlinger capture and artifact hashes are also checked.
Only the host runner suite was executed. No device probe was repeated: this patch
does not reproduce the remote startup failure or validate the new dump on a live
emulator. Controller owns fresh device/CI verification. The original startup ANR
and stability obligation remain **unresolved**; this patch repairs its confirmed
diagnostic omission without changing application behavior or test assertions.

### API 33 offset visibility and API 36 graphics startup (CI round 5)

CI run `40461748715`, head `36b426768159f81f4947b9757abe0580392d6a3a`,
adds one independent API 33 failure: the Russian selected start-offset button is
outside the viewport at maximum combined scale. The unchanged-tree repair probe
`fd80f59af5214d508e50622b1bf29df5/tree-1` first passes that isolated testcase,
then its automatic recovery attempt reproduces the same selected-choice assertion
in English at line 692. This reproduces within the original test itself; a prior
test's mutable fixture is not required. The retained CI log also shows IME input
activity during the measurement sequence.

The fixture requested focus on one choice and scrolled to another without waiting
for native/semantic focus or closing the form's IME. Selection also grows the
localized button label. It now awaits initial form focus, closes the IME in that
window, and aligns focus with each choice before scrolling and asserting visibility.
Exact unselected labels, selected labels, both selection assertions, passive-focus
checks, explicit zone review and retained local-text assertions remain enforced.
No product or persistence behavior changes.

The API 36 persistent report now supplies the missing startup stack: SystemUI PID
997 is blocked in `SurfaceControl.getGPUContextPriority` from
`SystemUIApplication.onCreate`, with a 13,611 ms outgoing binder transaction to
SurfaceFlinger PID 506. Its startup ANR is timestamped **06:35:47**, before app
testing, and its dialog `8d53f7c` subsequently blocks all 114 reported native-window
admissions. All affected methods in twelve classes still use the shared keyboard
fixture. Their downstream assertions were not reached by this failure.

The CI invocation still selected `swiftshader_indirect` on Emulator **37.2.12.0**.
Android's current [graphics configuration documentation](https://developer.android.com/studio/run/emulator-acceleration)
marks that mode deprecated since **36.4.9** and documents `swiftshader` as the
supported explicit SwiftShader GLES/Vulkan mode. The shared CI runner now selects
`swiftshader` for data, app and independent startup on all five APIs. A host
regression first fails on every API, then all **26** runner tests pass after the
change. Inventory, complete-suite admission, assertions and ANR failure gating
remain intact. This removes reliance on the legacy renderer; it does not prove
that the modern renderer alone prevents the captured graphics startup stall.

The baseline API 36 probe executed **zero** tests in both automatic attempts,
reporting an instrumentation-process crash before discovery without positive
repository or device-ownership evidence. It neither reproduces nor clears the
remote ANR. The leased Controller probes still request `swiftshader_indirect`;
they do not validate the changed Linux CI renderer or establish CI/local graphics
parity. Fresh Controller-owned CI with the updated command remains necessary.

Local focused Plan session/controller JVM tests pass (**26** cases), app ktlint and
Detekt pass, and the changed AndroidTest APK compiles/packages successfully. A
subsequent ktlint/APK check also passes after preserving the original selectors.
The red/green host logs, all-114 source/fixture index and justified class scope are
under `D:/controller/.runtime/codex-runs/wf-2933485f29755d0f9317fdc0a813f2f8/b4e6f66a949130ce6e374932/scratch/`.
The original remote failures and independent stability obligation remain open.

The repaired containing-class probe completed **REAL PASS** at
`fd80f59af5214d508e50622b1bf29df5/tree-2/attempt-2`: **10/10 API 33** and
**225/225 API 36**, with zero failures, errors or skips. The final reports contain
each of the original **115** failing identities exactly once and successful; report
hashes and this membership check are in `scratch/repaired-probe-evidence.json`.
Controller cleanup reports `safe=true`, complete enumeration and no owned live
processes or survivors for both devices.

Its first automatic attempt passed the ten Plan cases but could not install the
API 36 APK: the package service returned **Broken pipe**, followed by a missing
Android activity service. That zero-test result is not app execution or PASS.
Controller recovery restored device execution before the successful 225-case run;
the repeated Plan class also passed. An earlier API 33-only scope request was
refused before execution because the lease requires both checks; the accepted
class scope retains every original failure on both leased checks.

This local PASS verifies the repaired fixture and the existing affected app
classes on Windows. It still uses the leased legacy graphics flag and does not
establish Linux renderer equivalence, certify the changed CI command, explain the
underlying SurfaceFlinger startup stall, or close the original CI stability
obligation. Only this evidence record changed after the successful probe.

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

The matrix uses separate CI runners per API, with fail-fast disabled. Within a row, [run-instrumentation.py](../../.github/scripts/run-instrumentation.py) runs data, the full isolated app suite, and the independent production-startup app suite in separate sequential Gradle invocations, including after ordinary testcase failure with verified cleanup, avoiding shared AVD setup/teardown races. Unhealthy-device admission, execution deadlines or failed cleanup fail the row and explicitly leave subsequent mandatory suites NOT_RUN; they never count as successful verification. It supplies the expected API and uses no class/method filters, no build cache, rerun-tasks, no configuration cache, no parallel tasks and at most two workers. API 26 explicitly provisions the emulator with sdkmanager from the configured SDK. AGP's old-API managed-device opt-in remains enabled; minSdk is 26 and targetSdk is 36.

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

- `instrumentation-evidence-api<API>-attempt<ATTEMPT>`: `ci-evidence/api<API>/evidence.json`, data.log, app.log, startup.log, per-invocation `<module>-artifacts.json` hash manifests, and byte-for-byte original JUnit, UTP, binary results, command/status logs and additional native output. Snapshots and manifests precede XML validation, including for failed or malformed invocations.
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

## API 35 diagnostic transport retention (2026-11-01)

CI run `41288644023`, job `124795056893`, at head
`46b60069d2021e95da41a3965820fe380480b79e` completed all 524 app cases with one
failure: `NativeWindowDiagnosticsAndroidTest` could not find its final marker.
The shell command completed with exit zero. The complete testcase log contains
only frames 1 through 190 of 274; the assertion's filtered tail contains frames
175 through 190. The original XML and native log remain Controller evidence.
They do not establish whether missing frames were rejected during emission or
lost later: the previous formatter caught write failures without acknowledgment,
and the testcase did not retain those return codes in its failure message.

The shared report transport now writes and syncs the complete UTF-8 report to
AGP's additional-test-output directory before logging. Its inline reference records
the file path, byte count and SHA-256. Logcat emission admits at most 32 frames at
a time and checks readback before advancing; rejected writes retain their return
codes and are retried within the existing bounded diagnostic interval. A successful
receipt records acknowledged frames, write calls and rejected writes. A failed
acknowledgment remains explicit alongside the original failure and full artifact.
Process-specific capture IDs prevent older frames from satisfying readback.

A deterministic regression first reproduces the missing tail with a report above
512 KiB and finite transport capacity matching the observed 190-frame cutoff.
The repaired transport also covers rejected writes and a bounded missing-readback
case while preserving the full artifact and original failure. All eight focused
JVM diagnostics cases pass. The Android regression adds a self-contained Unicode
payload above 512 KiB, verifies the artifact byte for byte and requires every frame
to be acknowledged; its original final-marker and TestRunner-isolation assertions
remain intact. Inline assertions retain the 24,576-byte limit. The mandatory
inventory adds the two regressions without removing any original identity.

The first Android probe exported a 785,233-byte report and a receipt acknowledging
all 767 frames. It also exposed AGP 8.10's actual export path under
`build/intermediates/managed_device_android_test_additional_output/debugAndroidTest/`.
The repository collector previously searched only public output directories.
A new host regression reproduces the omission for data, app and independent
startup. The collector now retains and quarantines the selected device's intermediate
output too, preserving original bytes, SHA-256 and invocation separation before
startup can replace it. All 47 runner tests pass. Existing CI artifact uploads
already include the retained `ci-evidence` tree; no workflow change is needed.
The first probe's candidate receipt was invalidated by a concurrent documentation
edit, so its raw observations do not count as a successful scoped verification.

Product semantics, API 26 trace observation, job time limits and companion jobs
are unchanged. API 26 cancellation and API 36's missing Gradle transform metadata
remain unresolved, with no invented cause or PASS credit. Fresh Controller-owned
canonical execution and exact-head CI remain required; a scoped diagnostic PASS
does not close the existing stability obligation.

### API26 host-memory experiment (2026-11-01)

CI run 41305938631, API26 job 124846206522, recorded a kernel OOM victim
`qemu-system-x86` (PID 6204, anonymous RSS 14506104 KiB). Kernel SIGKILL and
mark_victim preceded systemd TERM of Runner.Worker by about 37 ms. Data passed
632 cases (5 skips); app stopped at 353/526 with 0 failures. Remaining cases and
startup were not executed. Why emulator memory grew remains unknown; this does
not establish the cause of earlier runs.

Only API26 enables `--profile-host-memory` alongside the calibrated signal sensor.
It records the installed emulator revision, owned qemu/Java RSS and anonymous
memory, host available memory every health probe, and bounded Android meminfo,
app PSS and SurfaceFlinger listed-layer counts about once per minute. Process
start IDs and ancestry exclude unrelated/reused PIDs; Android identity is checked
before read-only captures. No argv or environment is recorded.

The diagnostic guard fails the invocation when total owned emulator RSS reaches
8 GiB or host MemAvailable falls to 1.5 GiB, before the observed 13.8 GiB OOM.
It immediately uses normal owned cleanup without waiting for extra adb captures.
Telemetry errors fail explicitly; guest diagnostic errors remain visible.
Incomplete inventory remains failed, and later suites are NOT_RUN after abort.
This is a bounded profiling experiment, not a memory-leak fix. Existing 55-minute
row/60-minute job limits, renderer, tests, assertions and mandatory inventory are
unchanged. Other API rows are unchanged.

[Emulator 37.2.13 release notes](https://developer.android.com/studio/releases/emulator#37.2.13)
describe an OpenGL host-memory leak fix. However this job installed an archive
beginning `emulator-linux_x64-167...`; its full revision was not logged. No version
change is claimed as a fix. The next exact-head run must establish the installed
revision and host/guest memory trends before choosing a targeted mitigation.

### API26 isolated SwANGLE experiment (2026-11-01)

Run 41330080433 attempt 1, head `de76e88f160b4ecdc5109002025d59b9030f794b`,
recorded Emulator **37.2.13**, with GLES `swiftshader` and OpenGL ES SwiftShader
4.0.0.1 in both retained emulator logs. Data completed 632 cases (5 skips).
During app UI execution, host emulator RSS grew from about 2.6 GiB to 8.1 GiB;
the memory guard stopped the owned invocation at 210/526 cases, with no observed
test failures. App JUnit was not finalized; remaining app cases and startup did
not run. The failed gate is preserved. Cleanup and artifact upload succeeded;
this attempt has no recorded OOM event and all four trace buffers report zero
overruns/dropped events.

Over the corresponding guest samples, app PSS remained about 95–125 MiB, guest
MemAvailable about 1.4 GiB, and SurfaceFlinger listed 5–7 layers. This localizes
the large growth to the host emulator rather than guest application memory or
an increasing layer count. It does not identify the leaking allocation or prove
the cause of older runner stops. The advertised 37.2.13 fix is already installed.

The API26 row now explicitly opts into `--api26-swangle`. This changes only its
GLES path to ANGLE on SwiftShader Vulkan, using the documented
[`swangle` mode](https://developer.android.com/studio/run/emulator-acceleration).
Emulator 37.2.13's own `-help-gpu` confirms support; AGP 8.10.1 passes the selected
GPU option through. The flag requires the existing Linux API26 memory/signal
experiment. Other rows retain `swiftshader`; emulator/image versions, full
data/app/startup suites, assertions, mandatory inventory, resource guard and
55-minute row/60-minute job deadlines are unchanged. The requested renderer is
recorded, and retained emulator logs must confirm the actual backend in CI.

The exact Google-verified Linux archive's ANGLE libraries passed 64 real GLES
context/pbuffer create, clear, pixel-readback and destroy cycles, reporting ANGLE
on SwiftShader Vulkan with about 27–31 MiB RSS. This is a backend smoke, not an
Android test or proof that emulator memory growth is fixed. Two orchestration
regressions, including the initially failing experiment invocation, verify scope,
all three suites, unchanged deadline and original failure status. The memory
abort regression also covers the new path; all 56 host tests pass on Windows and
Linux. Fresh canonical checks and full exact-head CI remain required. Compare
actual backend, full inventory and host/guest memory trends before accepting the
mitigation; do not classify a repeated guard failure as a random infrastructure
failure or retry it blindly. API36's independent startup ANR remains unresolved.

### Teardown registration and API36 transform-cache boundaries (2026-11-02)

Run `41445414041` at head `9b0c628019e4fbf130e2b00779227d6b667948f3`
failed at two different boundaries. API26 setup completed successfully, but its
previously admitted emulator remained listed as offline during process teardown;
the cleanup API-property read failed. No helper, raster readback or product case
was reached. Cleanup now waits for that exact offline registration to disappear,
using only `adb devices`, and succeeds only on an empty list. A persistent,
revived, foreign or ambiguous registration still fails. The wait uses the existing
10-second interval and 5-second per-command timeout. Online API/AVD verification
and the guarded emulator kill remain intact. Eight host regressions first exposed
five errors in the old path; this does not prove that the failed CI registration
eventually disappeared.

API36 passed the original data suite (632 cases, five ignored fixtures) and then
failed before app/startup instrumentation on missing Gradle 8.14.3 transform
`metadata.bin`. The previous SystemUI startup ANR was not observed in this run.
A private non-Android Gradle fixture reproduced the missing-metadata exception;
recreating only its disposable transform workspace restored identical output.
This proves the recovery behavior, not the origin of the CI missing file.

Only the API36 matrix row excludes `caches/8.14.3/transforms` from setup-gradle's
shared cache. The pinned provider applies exclusions after restoring common
artifacts and before saving. Its actual post-restore code was exercised against
a private synthetic cache: the selected directory was removed while dependencies,
compiled scripts and unrelated paths stayed unchanged. The row therefore rebuilds
transforms within the job while preserving downloaded dependency and wrapper
caches. This is scoped cache policy on the ephemeral CI runner, with no deletion
of developer caches, no Gradle upgrade and no automatic test retry. It does not
address corruption arising later within the same job; that remains observable.

The original API26 profile, physical-raster assertions, full testcase inventory,
8 GiB guard and 55/60-minute deadlines remain required. Rebuilt transforms can
increase API36 preparation time. Fresh exact-head CI must establish all original
data/app/startup results on every API; local controls provide no full-matrix PASS
credit and do not replace Controller review or the new Lead response.

## S2 Add to Plan catalog accessibility boundary

The inventory adds seven native catalog presentation identities and one source-scoped
Room/controller write-free identity. All prior identities remain mandatory, including
PlanScreenPresentationTest, PlanExactDayPresentationTest, execution regressions and
MainActivity route-session cases. The existing Library typed bounded-keyset reader
and its no-graph-hydration regression are unchanged. Controller owns ci-fast,
gmd-app and independent exact-head GitHub compatibility execution. Local APK
packaging does not execute instrumented keyboard/focus assertions or certify manual
TalkBack/Switch Access operation. See [catalog evidence](plan_catalog_accessibility.md)
for exact ownership, typed-source, failure/pagination, localization/scaling coverage
and the remaining device/manual verification boundary.


## S3 scheduling form accessibility boundary

The app inventory adds seven original JUnit4 identities in
`PlanScheduleAccessibilityTest`. Prior S1/S2 calendar/catalog, strict ExactDay,
physical persistence, focused execution, MainActivity route-session, repository and
migration identities remain mandatory. New cases combine native keyboard/semantics
with production controller/repository writes and physical database reopen/canonical
reads, plus EN/RU and interface/text/system-font extremes. See
[scheduling evidence](plan_schedule_accessibility.md) for admission, preservation,
focus, temporal/failure coverage and the device/manual verification boundary.
Controller owns ci-fast, gmd-app and independent exact-head compatibility execution.
Host compilation/APK packaging grants no device PASS or manual TalkBack/Switch
Access certification.


S3 host verification (2026-11-11): the final direct CMD Gradle invocation exited 0;
70 focused Plan JVM cases have zero failures/errors/skips, app ktlint/Detekt/
lintDebug passed, and AndroidTest APK assembly passed. Every prior inventory
identity remains; the seven new methods match their registrations. EN/RU XML and
diff checks passed. These results are host evidence only; native EN/RU scaling,
physical reopen and accessibility action assertions still require Controller device
execution, and no manual TalkBack/Switch Access certification is claimed.


S3 API35 repair (2026-11-11): Controller attempt 1 completed 615 cases with four
failures, all at the shared scheduling test's reschedule-entry wait. The fixture
selected an out-of-week date without first navigating from October 19 to October
26. Its helper now uses native week/day navigation before the unchanged exact-row
Reschedule action and downstream assertions. All seven scheduling identities remain.
AndroidTest APK assembly, app ktlint and Detekt passed locally; inventory and diff
checks passed. The authorized containing-class probe returned "No active
repair-probe lease" before device/testcase admission. Original device failures
remain pending Controller runtime revalidation; no new device/CI PASS is claimed.
See [repair evidence](plan_schedule_accessibility.md) for the causal fixture details.


S3 causal visibility repair (2026-11-11): the four-case API35 rerun passed the
three reschedule/preservation/Close cases but failed Russian creation-context
visibility in the scale matrix. The fixture now establishes the current native
window/keyboard owner, moves focus out of editable fields and dismisses the IME
before its unchanged passive-text visibility assertions. Failure diagnostics retain
locale/scale, bounds, scrolling and native/Compose focus through the existing
acknowledged transport. Production behavior and all seven identities are unchanged.
AndroidTest compilation/APK assembly and app ktlint/Detekt passed. The authorized
probe again reported no active lease before device admission. The original Russian
visibility failure remains unverified, and focus/IME relocation is an assessment
rather than a confirmed runtime cause. Controller device/CI gates remain required.
