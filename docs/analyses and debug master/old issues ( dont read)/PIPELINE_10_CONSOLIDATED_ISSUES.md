# Pipeline 10 — Bank Integration: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 1 FIXED, 2 PARTIAL, 8 TODO, 4 NEW open issues  
> **Total open items:** 14

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P10-P0-01 | P0 | Bank API integration is demo-only stub | ✅ FIXED | ✅ **FIXED** | `BuildConfig.DEBUG` + `BankApiConfig.isStubMode` double guard in `requireStubMode()` |
| P10-P0-02 | P0 | Bank connection UI ViewModel is no-op | 📝 TODO ONLY | 📝 **TODO ONLY** | `BankConnectionsViewModel` injects no repository; all methods commented out |
| P10-P1-01 | P1 | `completeConnection()` doesn't persist entity | 📝 TODO ONLY | 📝 **TODO ONLY** | Returns `BankConnection` without `dao.insert()`; `createdAt = 0` |
| P10-P1-02 | P1 | No OAuth state/PKCE/callback validation | 📝 TODO ONLY | 📝 **TODO ONLY** | No durable OAuth session, state, PKCE verifier |
| P10-P1-03 | P1 | Sync has no durable run ledger or checkpoint | 📝 TODO ONLY | 📝 **TODO ONLY** | No `BankSyncRun`/`BankTransactionImport`; no cursor/checkpoint |
| P10-P1-04 | P1 | No low-confidence review route for bank transactions | 📝 TODO ONLY | 📝 **TODO ONLY** | All transactions auto-imported as approved expenses |
| P10-P1-05 | P1 | Bank metadata not preserved on imported expenses | 📝 TODO ONLY | 📝 **TODO ONLY** | `CreateExpenseRequest` has no `bankConnectionId`/`accountId`/`syncRunId` |
| P10-P1-06 | P1 | Token refresh doesn't persist new tokens | 📝 TODO ONLY | 📝 **TODO ONLY** | `refreshToken()` returns true; doesn't call provider or persist |
| P10-P1-07 | P1 | No restore/write barrier around bank writes | ⚠ PARTIAL | ⚠ **PARTIAL** | `BankApiIntegration` has barrier; raw DAO unguarded |
| P10-P1-08 | P1 | Bank statement import dedupe weaker than expense dedupe | ⚠ PARTIAL | ⚠ **PARTIAL** | Statement dedupe improved but not shared with expense dedupe |
| P10-P1-09 | P1 | Bank import creates expenses one-by-one without sync tx semantics | 📝 TODO ONLY | 📝 **TODO ONLY** | No outer sync transaction, no import row state |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P10-001 | P2 | `BankApiConfig.isStubMode` mutable global | BankApiConfig.kt | ✅ FIXED (P10-PR1) |
| NEW-P10-002 | P1 | BankTokenCipher swallows `KeyPermanentlyInvalidatedException` | BankTokenCipher.kt | ✅ FIXED (P10-PR1) |
| NEW-P10-003 | P2 | BankStatementLifecycleProcessor per-item swallows CancellationException | BankStatementLifecycleProcessor.kt | ✅ FIXED (U-PR1) |
| NEW-P10-004 | P3 | `generateMockTransactions` non-reproducible | BankApiIntegration.kt | ✅ FIXED (P10-PR1) |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 1 |
| ⚠ PARTIAL (old issues) | 2 |
| 📝 TODO ONLY (old issues) | 8 |
| 🔴 OPEN (new issues) | 4 |
| **Total open work** | **14** |

---

## Priority Order for Remaining Work

### P0 (critical)
1. **P10-P0-02** — Bank connection UI ViewModel is no-op (feature entirely non-functional)

### P1 (must fix)
2. **NEW-P10-002** — BankTokenCipher swallows `KeyPermanentlyInvalidatedException` (silent auth failure)
3. **P10-P1-01** — `completeConnection()` doesn't persist entity
4. **P10-P1-02** — No OAuth state/PKCE/callback validation
5. **P10-P1-03** — Sync has no durable run ledger or checkpoint
6. **P10-P1-04** — No low-confidence review route for bank transactions
7. **P10-P1-05** — Bank metadata not preserved on imported expenses
8. **P10-P1-06** — Token refresh doesn't persist new tokens
9. **P10-P1-07** — Restore/write barrier incomplete (raw DAO unguarded)
10. **P10-P1-08** — Bank statement dedupe not shared with expense dedupe
11. **P10-P1-09** — Bank import one-by-one without sync tx semantics

### P2 (should fix)
12. **NEW-P10-001** — `BankApiConfig.isStubMode` mutable global (testability/safety)
13. **NEW-P10-003** — BankStatementLifecycleProcessor per-item swallows CE

### P3 (cleanup)
14. **NEW-P10-004** — `generateMockTransactions` non-reproducible
