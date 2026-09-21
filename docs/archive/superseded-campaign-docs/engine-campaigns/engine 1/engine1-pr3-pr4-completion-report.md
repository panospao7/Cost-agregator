# Engine 1 — PR3+PR4 Slice Completion Report

## Slice: PR3 — Warranty lifecycle events + PR4 — Low-confidence warranty review routing

### Self-review verdict
GREEN

### Old issues reconciled
- W03: FIXED — Lifecycle events added for all major warranty transitions
- E1-NOW-005: FIXED — Three-band cloud confidence model replaces dead single-threshold code

### New issues found
- addWarranty lifecycle event was not best-effort (inside transaction without runCatching) — FIXED during review
- autoDetected and extractionSource not set for cloud-extracted warranties — FIXED during review

### Files changed (production)
1. `app/src/main/java/com/yourname/expensetracker/data/database/entity/WarrantyLifecycleEvent.kt`
2. `app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`

### Files changed (tests)
3. `app/src/test/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepositoryTest.kt`

### Tests added/updated
- updateWarranty_writesUpdatedEvent
- deleteWarranty_writesDeletedEvent
- reconcileExpiredItems_writesBatchExpiredEventWhenWarrantiesExpired
- reconcileExpiredItems_writesNoEventWhenNothingExpired
- cloudWarrantyConfidence_0_8_autoCreatesWithoutReview
- cloudWarrantyConfidence_0_4_createsNeedsReviewDraft
- cloudWarrantyConfidence_0_1_discardsWithDiagnostic
- cloudWarrantyConfidence_0_75_exactBoundary_autoAccepts
- cloudWarrantyConfidence_0_30_exactBoundary_createsReviewDraft
- addWarranty_lifecycleEventFailure_doesNotFailPrimaryTransaction
- lifecycleEventFailure_doesNotFailPrimaryTransaction (updateWarranty path)

### Docs updated
- `docs/analyses and debug master/ENGINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/engine 1/engine1-pr3-pr4-completion-report.md` (this file)

### Affected pipelines
- Warranty tracking (lifecycle events now observable)
- Receipt side effects (AI warranty extraction confidence routing)
- Warranty expiration worker (EXPIRED batch events)
- Warranty tracking UI (more review drafts from cloud extraction)
- Backup/export (lifecycle events table included)

### Expected behavior changes
- Manual warranty creation, update, delete now write lifecycle events
- Warranty expiry reconciliation writes batch EXPIRED event
- Cloud-extracted warranties with confidence 0.30–0.75 now create review drafts instead of being silently discarded
- Cloud-extracted warranties with confidence >=0.75 auto-accept as before
- Cloud-extracted warranties with confidence <0.30 are discarded as before
- Lifecycle event write failures never block primary warranty operations

### Static debugger verdict
GREEN (after fixes: addWarranty wrapped in runCatching, autoDetected set, unused constant removed)

### Reviewer verdict
GREEN (after fixes: all lifecycle events best-effort, boundary tests added, resilience test added)

### Tester static verdict
GREEN (full band coverage + boundary tests + resilience tests)

### Known compile risks
None. No schema changes, no Hilt changes, no new public API surface.

### Human validation commands
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*WarrantyTrackerRepositoryTest*"
./gradlew :app:check --stacktrace
```

### Follow-up / deferred items
- PR5: NLP location query semantics (W15, E1-NOW-008)
- PR6: Bill negotiation provider wiring (W25, E1-NOW-007)
- PR7: Bill negotiation monthly-equivalent script fix (W09)
- PR8: Bill negotiation persistence with migration (W08)
- PR9: Deprecated/raw API guardrails
- Advisory: Consider dedicated ReturnWindowLifecycleEvent table in future schema PR
- Advisory: Verify aiCapabilityRouter.decide() mock arg count in tests matches actual signature
