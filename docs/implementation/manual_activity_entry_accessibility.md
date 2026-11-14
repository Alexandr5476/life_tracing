# Manual completed Activity accessibility (S2)

The History -> Add completed Activity route retains the product v0.16, domain
v0.10, schema v0.6 and implementation-clarification semantics. No repository,
schema, migration, historical command, overlap predicate or Statistics policy was
changed. Manual entries remain standalone completed historical facts with
MANUAL_HISTORY_ENTRY; they neither acquire a live session nor auto-fulfill Plan.
Timer target and entered duration remain separate. No-live retains null duration.

## Identity, reads and draft authority

The catalog still uses one existing indexed repository query per requested page,
with a maximum of 50 lightweight rows. A subsequent page replaces the previous
page; only the selected Template graph is read. Selection survives paging. Page
number, replacement loading, empty/content/failure and exhaustion are presented
separately. Catalog descriptions and selected-source headings, including read failures, include stable
Template IDs, including otherwise identical names. Native focus keys and actions
use Template, Field and Category-option IDs, never their labels or ordinals.

Rendering performs O(page + selected Fields + selected options) presentation work.
There is no per-row repository read, accumulated page list, eager hydration,
global-history copy or repeated Field-by-Field whole-form transformation. Native
focus survives unrelated publications through stable composition keys. The only
History overlap lookup remains the exact database preflight and the existing
write-boundary recheck, with the documented performance exception unchanged.

The existing strict minute-precision manual resolver still determines Instants.
Local timestamps are not normalized through DST gaps. Each repeated-hour endpoint
has independently selected native radio semantics and actual UTC-offset labels.
Changing that timestamp invalidates its offset authority. Resume refreshes the
visible applicable device zone; a changed zone clears old offset authority and
Save revalidates canonically. No timezone picker or alternate temporal policy was
introduced.

Actual inputs retain configured/default context, Field type, unit and precision.
Missing and present are explicit reversible controls. Zero, empty text and missing
remain distinct. Archived Category options remain unselectable. Numeric/Category
validation is still TemplateEntryValues; its failure now includes the existing
source Field ID for presentation. Future completion and reversed-interval issues
are associated with their endpoints. All v1 Fields remain optional as specified
by schema section 13.3; there is no required-Field metadata or new required policy.
Draft edits, focus, announcements and recomposition do not persist anything.

## Commands and focus

Every native pointer, accessibility and keyboard action shares a guarded controller
callback. Admission checks attachment, current top-route/session, foreground
lifecycle, catalog/selection authority and relevant draft/proposal state. Input
window focus is checked for keyboard focus requests, rather than accessibility
action admission. Compose observes lifecycle state so native enabled states and
landing focus update on resume even when the form itself has not changed;
callbacks retained across a pause remain revoked.
Replaced-page and disposed-route callbacks cannot retarget current controls.
Text callbacks can advance their own cursor for consecutive input events before
recomposition; a newer draft or selection revokes old authority. This is not a
one-shot lease for the entire form. Draft authority also advances when the same
source is explicitly reselected, so a coalesced equal-looking reset still replaces
obsolete callbacks with actionable current controls. A non-durable publication
counter also changes on controller actions: immediate Loading -> identical
Failure cycles cannot be coalesced into a view retaining obsolete Retry callbacks.
Catalog, source-read and Save retry regressions each reproduced this issue before
the shared fix; a production-route Compose test exercises consecutive failures.
Route composition is also keyed by controller identity, so a new session with
equal visible content acquires its own collector, focus and callback ownership.

Committing blocks edits, duplicate Save/Proceed and Back. Committed is also
terminal while navigation/History refresh delivery is pending: a successful writer
cannot be replayed in that window. The retained route session and existing
one-shot completion delivery survive Activity recreation. MainActivity changes
are confined to outgoing-route ownership and this completion/return boundary.
S1 History browsing and its committed Refresh admission are unchanged.

Back is the native keyboard landing target. Removed/disabled focused controls
recover through the existing History focus bookkeeping; surviving inputs and rows
keep their nodes. Input Tab/Shift-Tab traverses instead of inserting a tab into
multiline text. Validation, overlap, read failure, stale-source review, saving and
committed-return status are localized live regions. Recovery controls are native
buttons with distinct catalog/source/Save Retry descriptions. Hidden routes expose
disabled native controls. Validation/overlap/save-failure transitions reveal a reachable recovery
target without activating it. Focus never approves overlap or invokes a writer.
Wrapping layouts and shared physical minimum targets support EN/RU and app/system
scaling. Category/offset choices expose native roles and selected state with the
shared Choice button appearance, preserving focus when selection changes.

## Verification and limits

The JVM suite covers strict parsing, gap/overlap policy, independent endpoint
choices, zone revalidation, canonical error identity, bounded paging, explicit
stale-source review, write-boundary overlap races, commit admission and retained
callback rejection. The committed-window regression was observed failing before
the production fix. The same-source reselection regression was also observed
failing before advancing draft authority across reselection. An explicit failed
Save/retry test verifies retained values and one successful result. The related
offset-invalidation test now performs the edit
before commit rather than treating a completed command as an editable draft.

Registered production-route instrumentation covers native Tab/Enter and semantic
input, duplicate source/Field/option labels, read retry, bounded replacement
paging, stale revision/archival review, recreation, failed validation without
writes and outgoing callbacks. Representative timed, No-live, Timer overtime,
both DST occurrences and explicitly approved overlap journeys physically close
and reopen the database, then compare canonical History/Statistics, original
zone/timestamps/day, completion reason, snapshot/Series lineage, exact values,
source revisions, Plan facts and absence of an active session. Existing repository
and route-session suites remain the foundation for transactional rollback,
write-boundary races and in-flight recreation.

Local JVM/static/APK check outcomes are reported with the patch. Device execution
is intentionally Controller-owned; compiling an instrumentation test is not
execution evidence. GitHub managed-device CI remains an independent final gate.
No manual TalkBack or Switch Access walkthrough/certification was performed.
