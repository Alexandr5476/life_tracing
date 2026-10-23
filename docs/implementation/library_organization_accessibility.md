# Non-destructive Library organization accessibility (Slice S2)

Product v0.16 §33, domain v0.10 §§17–19, schema v0.6 and implementation
clarifications remain authoritative. This extends the accepted
[S1 discovery implementation](library_accessibility.md); its typed actions,
navigation, search, filtering and Quick Start ownership remain in place.

## Controls and admission

Native Material buttons expose contextual operation, Template kind/name and
section/ordinal context. Folder and Tag descriptions include metadata ordinals
to distinguish identical names. These labels never authorize or identify a
command: callbacks retain LibraryTemplateId.Activity/Sequence, FolderId and TagId.
Pin and Tag controls expose selected state. Expansion exposes Expanded/Collapsed;
Pinned ordering also exposes selected state. Both move directions remain visible
with native disabled boundaries. Ordering operates on the complete active Pinned
collection even when a kind filter hides rows; the full typed-ID payload is
constructed only on activation.

Organization Loading/Failure and mutation-in-flight disable or remove mutation
controls. An attached callback lease permits one submission from its current
presentation context, checks the controller's live state and top-route ownership,
and is revoked on disposal. This prevents retained callbacks from submitting after
a refresh, removal, panel collapse or foreground-route change. The existing
controller single-mutation guard and transactional repository validation remain
authoritative. No optimistic state changes or additional commands were added.

Folder destinations use the existing FolderTreeValidator policy. Descendants and
the source are omitted; the canonical writer still rejects stale destinations.
Folder and Template relocation, Pinned changes and Tag assignment refresh through
the existing readers. The current Folder's breadcrumb follows the canonical path
on refresh, including an externally moved ancestor.

## Dialogs, feedback and focus

Create Folder, rename Folder and create-and-assign Tag have contextual heading,
native editable input, independently named Confirm/Cancel and disabled blank
confirmation. Draft editing, opening, cancellation and system dialog dismissal
dispatch no mutation. Confirmation uses the existing command and trims the draft.
Dialog-opening callbacks and initial input focus check current route visibility.

The S1 focus owner now accepts a transient restoration handle. Closing a dialog or
collapsing a panel restores its surviving invoker; a detached invoker recovers to
Back. Restoration waits for window focus and enabled controls and cannot affect a
background route. Expansion does not focus or activate the first child. Native
search/filter focus and the existing route-return landing remain unchanged.

A transient controller completion generation advances after the existing writer
returns successfully and refresh starts. It is not persisted. Success presentation
waits for canonical browse, active search and organization Content and for mutation
completion. Rejection, read failure and unrelated recomposition do not synthesize
success. Localized busy/success feedback uses an isolated polite live region only
on the visible route; existing failures and Retry remain available.

EN/RU strings cover every new description. Scoped accessible targets, FlowRow and
scrollable dialog input avoid fixed row heights. The scaling case combines Android
font 200% with interface/text 90%/90% and 120%/130%, in both languages.

## Changed files

- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryScreen.kt`
- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryOrganizationControls.kt` (new)
- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryAccessibility.kt`
- `app/src/main/java/com/alexandr5476/lifetracing/library/LibraryController.kt`
- `app/src/main/java/com/alexandr5476/lifetracing/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ru/strings.xml`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/library/LibraryOrganizationPresentationTest.kt` (new)
- `app/src/androidTest/java/com/alexandr5476/lifetracing/library/LibraryScreenPresentationTest.kt`
- `app/src/androidTest/java/com/alexandr5476/lifetracing/LibraryAccessibilityRouteTest.kt`
- `app/src/test/kotlin/com/alexandr5476/lifetracing/library/LibraryControllerTest.kt`
- `.github/scripts/instrumentation-inventory.json`
- `docs/implementation/library_accessibility.md`
- `docs/implementation/android_compatibility_gate.md`
- This record (new).

MainActivity supplies a live top-route predicate alongside S1's visibility state.
Its existing mismatched-Plan route effect now directly collects the same state as
its key, avoiding a lint indentation false positive without changing admission.
The original compact Library test selects the enabled up/down controls now that
disabled boundary buttons also render; its exact typed payload assertions remain.

## Mandatory instrumentation identities

Nine new methods in
`com.alexandr5476.lifetracing.library.LibraryOrganizationPresentationTest`:

- `pinnedBoundariesExpansionAndCollapseRetainNativeFocusWithoutCommands`
- `filteredPinnedMovesSubmitTheCompleteTypedOrderAndRespectMutationAdmission`
- `duplicateTemplateFolderAndTagControlsDispatchOnlyTypedTargetsAndExposeSelection`
- `folderMoveExcludesSourceAndDescendantsAndKeepsDuplicateDestinationsDistinct`
- `dialogsKeepDraftsValidateRestoreInvokerAndNeverMutateOnCancelOrDismiss`
- `renameAndCreateTagDialogsIdentifyExactDuplicateOwners`
- `disposedOrStaleCallbacksCannotSubmitAndFailureRecoveryDoesNotAnnounceFalseSuccess`
- `englishRussianOrganizationTargetsAndDialogsReflowAtCombinedScaleExtremes`
- `retainedMutationCallbackChecksLiveRouteOwnershipBeforeRecomposition`

Three new methods in `com.alexandr5476.lifetracing.LibraryAccessibilityRouteTest`:

- `nonDestructiveTemplateOrganizationReopensCanonicalMetadataAndFrozenHistory`
- `nestedFolderCreateRenameMoveAndCanonicalPathRefreshSurvivePhysicalReopen`
- `staleOrganizationDestinationIsRejectedWithoutPartialWritesAndRetryReloadsCanonicalState`

All previous inventory identities remain mandatory. S2 adds twelve app identities:
**477 app, 627 data and 2 independent startup cases per API**. Test inputs and
prerequisites are owned by each fixture; synchronization uses bounded state waits,
CompletableDeferred and native keyboard mode, with no arbitrary sleeps.

## Canonical reopen and failure assertions

The production-route cases use MainActivity, its retained LibraryController owner,
production repositories and an isolated physical database. Native accessibility
OnClick and keyboard Enter perform the operations. Tests close the Activity, graph
and connections, then reopen the same file and use canonical Library/Template
readers to assert:

- Complete cross-kind Pinned order after filtered reordering and unpinning.
- Both Template kinds moved between a Folder and root with exact typed IDs.
- Created/assigned Tag membership and removal of an existing assignment.
- Nested Folder creation, rename, relocation to a Folder/root and canonical paths
  after an ancestor moves while the old visual location remains active.
- Cancel/dismiss preserve every durable fixture fact.
- A loaded destination deleted before activation is rejected by the real writer;
  there is no partial write, completion generation or replay on Retry. Canonical
  refresh removes the stale destination.

Template model comparisons normalize only organization membership/user state;
revision, configuration, Step snapshot/Field provenance and StatisticsSeries
remain equal. Bounded raw fixture comparisons additionally preserve snapshots,
representative prior historical Execution, intervals, Plan and runtime/session
facts. Reopening preserves all durable facts, and no operation creates an active
session or unexpected Execution.

The existing repository regressions for complete-set Pinned rejection, cycles and
metadata revision/Series preservation remain mandatory. Existing controller tests
retain canonical refresh, search/filter preservation, explicit recovery and stale
publication coverage. New JVM methods are:

- `LibraryControllerTest.organizationCompletionGenerationWaitsForWriterAndNeverAdvancesOnFailureOrRetry`
- `LibraryControllerTest.closingOrganizationControllerRevokesLateWriterAndReadPublication`

## Local verification and boundaries

On 2026-10-23 this exact command completed with **BUILD SUCCESSFUL**:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LibraryControllerTest' --tests '*MainActivityNavigationTest' :app:ktlintCheck :app:detekt :app:lintDebug :app:assembleDebugAndroidTest --continue --console=plain
```

`LibraryControllerTest` passed 26 cases and `MainActivityNavigationTest` passed
26 cases, with zero failures, errors or skips. App ktlint and Detekt passed.
Android lint reported zero errors, 34 warnings and one hint. All AndroidTest
sources compiled and the test APK assembled. The new twelve instrumentation
cases, including physical-reopen assertions, were **compiled, not executed** in
this implementation turn. No managed-device or emulator command was launched.

```powershell
python -m unittest discover -s .github/scripts -p 'test_*.py'
git diff --check
```

All 17 runner/inventory host tests passed (their printed device commands are
mocked; they do not execute devices). Whitespace validation passed. A read-only
HEAD inventory comparison confirmed twelve additions, zero removals and exact
source methods for every new identity, with 477/627/2 app/data/startup totals.

Earlier local attempts found formatting/Detekt issues, a test-rule initialization
error and the MainActivity lint false positive noted above. These were corrected
before the successful final command. The route test activation helper also checks
for a scroll ancestor before scrolling, so dialog footer actions retain native
activation without requiring a catalog scroll container.

No repository, DAO, schema, migration, transaction, domain command or persistent
state changed. No per-row database reads, History scans or additional catalog
hydration were added. Labels use already loaded rendered-row/organization data;
full Pinned payload construction runs only on activation. Folder validation uses
the existing loaded parent map and canonical writer. Expanded state, drafts,
callback leases, completion feedback and focus handles remain ephemeral.

Archive and Folder deletion retain their existing controls and admission.
Their destructive confirmations, lifecycle and restoration, plus Activity/Sequence
Editor accessibility, remain deferred. Automated semantics and keyboard assertions
do not constitute manual TalkBack or Switch Access certification. No manual
assistive-service validation was performed. Controller owns unfiltered API
26/31/33/35/36 execution, independent startup and exact-head GitHub evidence;
compiling an instrumentation APK does not establish device PASS.
