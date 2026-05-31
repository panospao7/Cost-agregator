# Pipeline 10 — Bank Integration: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 10 — Bank Integration  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 10 — Bank Integration
Verdict: RED
Summary:
- 1 old issue FIXED (demo guard), 2 PARTIAL, 8 TODO ONLY
- 1 issue FIXED by universal (NEW-P10-003 via U-PR1)
- 13 pipeline-local issues remain (1 P0, 9 P1, 2 P2, 1 P3)
- This pipeline is fundamentally incomplete — most features are stubs/TODO
- P0: ViewModel is no-op (entire bank UI non-functional)
- Key real bugs: token cipher swallows key invalidation, barrier gaps
- Most P1 issues are unimplemented features (OAuth, sync ledger, review route)
- Bank integration is demo-only; production readiness requires major implementation
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_10_CONSOLIDATED_ISSUES.md`

**Source files:** `BankApiIntegration.kt`, `BankTokenCipher.kt`, `BankApiConfig.kt`, `BankConnectionsViewModel.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 10 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | Fixes NEW-P10-003 | No | ✅ Fixed |
| U-PR5 (Privacy) | Bank statement privacy/export policy | Yes — adapter | ⏳ Blocked |
| U-PR7 (TimeProvider) | BankApiIntegration already uses TimeProvider | No | ✅ Compatible |
| Others | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P10-P0-01 | ✅ FIXED | None | None |
| P10-P0-02 | 📝 TODO | None | Implement ViewModel |
| P10-P1-01 through P10-P1-06 | 📝 TODO | None | Full implementation needed |
| P10-P1-07 | ⚠ PARTIAL | U-PR4 | Add barrier to raw DAO paths |
| P10-P1-08 | ⚠ PARTIAL | None | Share dedupe with expense pipeline |
| P10-P1-09 | 📝 TODO | None | Implement sync transaction semantics |
| NEW-P10-001 | 🔴 OPEN | None | Make immutable |
| NEW-P10-002 | 🔴 OPEN | None | Surface key invalidation to user |
| NEW-P10-003 | ✅ FIXED | U-PR1 | None |
| NEW-P10-004 | 🔴 OPEN | None | Use seeded random |

---

## 5. New Issues / Regressions

No regressions from universal fixes.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P10-002 | P1 | BankTokenCipher swallows key invalidation | Security | P10-PR1 |
| P10-P1-07 | P1 | Raw DAO unguarded by barrier | Safety | P10-PR1 |
| NEW-P10-001 | P2 | isStubMode mutable global | Testability | P10-PR1 |
| NEW-P10-004 | P3 | Mock transactions non-reproducible | Testing | P10-PR1 |
| P10-P0-02 | P0 | ViewModel no-op | Feature | P10-PR2 (feature) |
| P10-P1-01 | P1 | completeConnection doesn't persist | Feature | P10-PR2 (feature) |
| P10-P1-02 | P1 | No OAuth/PKCE | Feature | P10-PR2 (feature) |
| P10-P1-03 | P1 | No sync ledger/checkpoint | Feature | P10-PR3 (feature) |
| P10-P1-04 | P1 | No review route | Feature | P10-PR3 (feature) |
| P10-P1-05 | P1 | Bank metadata not preserved | Feature | P10-PR3 (feature) |
| P10-P1-06 | P1 | Token refresh doesn't persist | Feature | P10-PR2 (feature) |
| P10-P1-08 | P1 | Dedupe not shared | Feature | P10-PR3 (feature) |
| P10-P1-09 | P1 | No sync tx semantics | Feature | P10-PR3 (feature) |

---

## 7. PR Organization

### P10-PR1 — Bug Fixes (Can Land Now)

```
PR name: fix(p10): token cipher key invalidation, barrier gaps, immutable config, seeded mocks
Goal: Fix real bugs in existing bank code
Issues fixed: NEW-P10-002, P10-P1-07, NEW-P10-001, NEW-P10-004
Universal dependencies: None
Files likely touched:
  - BankTokenCipher.kt
  - BankApiIntegration.kt (barrier)
  - BankApiConfig.kt
  - BankApiIntegration.kt (mock generation)
Implementation steps:
  1. NEW-P10-002: In BankTokenCipher, catch KeyPermanentlyInvalidatedException specifically; surface to caller as TokenInvalidatedResult; caller should prompt user to re-authenticate
  2. P10-P1-07: Add writeBarrier.checkWritesAllowed() to all raw DAO mutation paths in bank integration
  3. NEW-P10-001: Make isStubMode a val (immutable); set from BuildConfig at initialization; remove mutable setter
  4. NEW-P10-004: Use seeded Random(42) in generateMockTransactions for reproducible test data
Tests:
  - key_invalidation_surfaces_to_caller
  - bank_writes_blocked_during_restore
  - stub_mode_immutable_after_init
  - mock_transactions_reproducible
Risks: Low — targeted fixes to existing code
Acceptance criteria:
  - Key invalidation produces user-visible re-auth prompt (not silent failure)
  - All bank DB writes respect barrier
  - No runtime mutation of stub mode
  - Mock data deterministic
```

### P10-PR2 — Connection Lifecycle (Feature Implementation)

```
PR name: feat(p10): bank connection persistence, OAuth/PKCE, token refresh
Goal: Implement bank connection lifecycle (currently stub)
Issues fixed: P10-P0-02, P10-P1-01, P10-P1-02, P10-P1-06
Universal dependencies: None
Files likely touched:
  - BankConnectionsViewModel.kt
  - BankApiIntegration.kt
  - BankConnectionDao.kt
  - New: OAuthSessionManager.kt
Implementation steps:
  1. P10-P0-02: Wire ViewModel to BankApiIntegration repository; implement connection list, add, remove flows
  2. P10-P1-01: In completeConnection(), persist BankConnection entity via dao.insert(); set createdAt = timeProvider.now()
  3. P10-P1-02: Implement OAuthSessionManager with durable state, PKCE verifier generation, callback validation
  4. P10-P1-06: In refreshToken(), call provider's refresh endpoint; persist new access/refresh tokens via BankTokenCipher
Tests:
  - connection_persisted_after_complete
  - oauth_state_validated_on_callback
  - token_refresh_persists_new_tokens
Risks: High — new feature implementation; needs design review
Acceptance criteria:
  - Bank connections visible in UI after creation
  - OAuth flow validates state parameter
  - Token refresh produces valid persisted tokens
NOTE: This is feature work — may be deferred if bank integration is not priority
```

### P10-PR3 — Sync & Import (Feature Implementation)

```
PR name: feat(p10): sync ledger, review route, metadata preservation, shared dedupe
Goal: Implement bank sync infrastructure (currently stub)
Issues fixed: P10-P1-03, P10-P1-04, P10-P1-05, P10-P1-08, P10-P1-09
Universal dependencies: None
Files likely touched:
  - New: BankSyncRun.kt, BankTransactionImport.kt entities
  - BankApiIntegration.kt
  - CreateExpenseRequest.kt
Implementation steps:
  1. P10-P1-03: Create BankSyncRun entity + DAO; record sync start/end/status/cursor
  2. P10-P1-04: Route low-confidence bank transactions to PendingReview (same as notification pipeline)
  3. P10-P1-05: Add bankConnectionId, accountId, syncRunId to CreateExpenseRequest; persist in expense metadata
  4. P10-P1-08: Share DuplicateDetectionPolicy with bank import path
  5. P10-P1-09: Wrap batch import in database.withTransaction; record per-row import status
Tests:
  - sync_run_recorded_with_checkpoint
  - low_confidence_bank_tx_creates_review
  - bank_metadata_preserved_on_expense
  - bank_dedupe_matches_expense_dedupe
Risks: High — new feature implementation; needs schema migration
Acceptance criteria:
  - Sync runs are durable and resumable
  - Low-confidence transactions go to review queue
  - Bank source traceable on imported expenses
NOTE: This is feature work — may be deferred
```

---

## 8. Detailed Implementation Plan

### P10-PR1 Step-by-Step (Priority — can land immediately)

1. **Open** `BankTokenCipher.kt` — find catch block that handles `KeyPermanentlyInvalidatedException`; instead of swallowing, return sealed result: `TokenDecryptResult.KeyInvalidated`
2. **Open** `BankApiIntegration.kt` — find all DAO mutation calls; add `writeBarrier.checkWritesAllowed()` before each
3. **Open** `BankApiConfig.kt` — change `var isStubMode` to `val isStubMode = BuildConfig.DEBUG`
4. **Find** `generateMockTransactions` — add `Random(seed = 42)` parameter

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 10 Adapter/Follow-up |
|---|---|
| U-PR5 (Privacy) | Required: Apply bank statement storage mode per RawContentPolicy; ensure bank raw data respects retention |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 10 targeted tests
./gradlew :app:testDebugUnitTest --tests "*Bank*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BankToken*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P10-PR1: Token invalidation surfaced; barrier complete; config immutable; mocks reproducible
- [ ] P10-PR2: Connection lifecycle functional (if prioritized)
- [ ] P10-PR3: Sync infrastructure implemented (if prioritized)
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Pipeline 10 status: GREEN for bug fixes; feature work tracked separately
