# LifeTracing v1 delivery plan

This is an implementation-order document only. Authoritative behavior remains in the frozen specifications and implementation clarifications. The order may change if implementation dependencies require it.

1. Template / Sequence authoring application layer — this change.
2. Manual / one-off Activity + Activity History commands:
   - backdated live Activity;
   - completed manual history;
   - one-off authoring/execution;
   - Activity history correction/delete.
3. Sequence secondary runtime commands:
   - Go now;
   - Make next;
   - Runtime Add;
   - Do again;
   - End sequence early;
   - resolve linked-Plan `ENDED_EARLY` fulfillment semantics before cementing that behavior.
4. Daily / History bounded read projections:
   - Plan;
   - completed history;
   - active runtime;
   - effective display metadata;
   - no UI-side DAO composition.
5. Sequence historical correction/editing:
   - tombstone child execution;
   - explicit structural removal;
   - interval/timeline correction;
   - cache recomputation.
6. UI vertical slices:
   - shell / Navigation3 / Daily;
   - Start Activity;
   - Library + Activity editor;
   - Sequence editor + live Sequence;
   - Plan;
   - History/manual correction;
   - Statistics;
   - Settings/archive-management/polish.
7. Release hardening:
   - process death/restart;
   - background timers;
   - timezone/DST;
   - API-level checks;
   - migrations/fresh install;
   - large-history responsiveness;
   - accessibility;
   - RU/EN;
   - themes/scaling;
   - sound/vibration;
   - permissions/capability UX;
   - permanent signing key and first-release identity review.
