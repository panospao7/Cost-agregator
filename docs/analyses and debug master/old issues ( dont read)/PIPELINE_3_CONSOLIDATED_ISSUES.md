# Pipeline 3 — Receipt/OCR/Email: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 3 FIXED, 2 PARTIAL, 7 TODO ONLY, 8 NEW open issues  
> **Total open items:** 17 (7 TODO + 2 PARTIAL + 8 NEW)

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P3-P0-01 | P0 | Scanned receipts saved with `createdAt = 0` | ✅ FIXED | ✅ **FIXED** | All paths set `createdAt` at lifecycle boundary |
| P3-P1-01 | P1 | Receipt save/update/event not atomic | ✅ FIXED | ✅ **FIXED** | `processReceiptInput()` uses atomic DB transaction |
| P3-P1-02 | P1 | `ReceiptLinkService` lacks restore guard | ✅ FIXED | ✅ **FIXED** | Write barrier guards on link/unlink |
| P3-P1-03 | P1 | Matching result computed but not persisted | 📝 TODO ONLY | ✅ **FIXED** | NoMatch writes MATCH_NOT_FOUND event; auto-match links; suggested updates receipt |
| P3-P1-04 | P1 | Receipt-created expense + link not atomic | ⚠ PARTIAL | ⚠ **PARTIAL** | Coordinator is single owner; legacy paths exist with ERROR deprecation |
| P3-P1-05 | P1 | Direct repository methods bypass lifecycle | ⚠ PARTIAL | ⚠ **PARTIAL** | Write barrier guards exist but some direct DAO paths remain |
| P3-P1-06 | P1 | `ScannedReceiptDao.insert()` IGNORE conflict not checked | 📝 TODO ONLY | ✅ **FIXED** | ReceiptInsertResolver handles conflict resolution |
| P3-P1-07 | P1 | Currency fallback hardcoded EUR in OCR parse | 📝 TODO ONLY | ✅ **FIXED** | ProcessReceiptUseCase injects UserCurrencyProvider (P3-PR1) |
| P3-P1-08 | P1 | Parse failures classified as `OCR_COMPLETED` | 📝 TODO ONLY | ✅ **FIXED** | PARSE_FAILED correctly set in ReceiptRepository |
| P3-P1-09 | P1 | Batch receipt import no longer creates pending reviews | 📝 TODO ONLY | 📝 **TODO ONLY** | `autoCreateReview = false` in batch path |
| P3-P1-10 | P1 | Bank statement lifecycle dedupe weaker than legacy | 📝 TODO ONLY | 📝 **TODO ONLY** | Checks only pending reviews |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P3-001 | P1 | CancellationException swallowed in `ReceiptSideEffectDispatcher` | ReceiptSideEffectDispatcher.kt | ✅ FIXED (U-PR1) |
| NEW-P3-002 | P1 | CancellationException swallowed in `BankStatementLifecycleProcessor` per-item | BankStatementLifecycleProcessor.kt | ✅ FIXED (U-PR1) |
| NEW-P3-003 | P1 | CancellationException swallowed in `ReceiptLinkService.unlinkReceiptFromExpense` | ReceiptLinkService.kt | ✅ FIXED (U-PR1) |
| NEW-P3-004 | P2 | Double `attachReceipt` call in `BankStatementLifecycleProcessor` | BankStatementLifecycleProcessor.kt | ✅ FIXED (P3-PR3) |
| NEW-P3-005 | P2 | Race in post-OCR duplicate path | ReceiptLifecycleCoordinator.kt | 🔴 OPEN |
| NEW-P3-006 | P2 | Privacy leak — merchant/category logged in production | ReceiptLifecycleCoordinator.kt | 🔴 OPEN |
| NEW-P3-007 | P2 | `deleteReceipt` writes event for non-existent receipt | ReceiptLifecycleCoordinator.kt | 🔴 OPEN |
| NEW-P3-008 | P3 | `homeCurrency()` inside `withContext` may cause thread starvation | ReceiptLifecycleCoordinator.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 3 |
| ⚠ PARTIAL (old issues) | 2 |
| 📝 TODO ONLY (old issues) | 7 |
| 🔴 OPEN (new issues) | 8 |
| **Total open work** | **17** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **NEW-P3-001** — CancellationException swallowed in ReceiptSideEffectDispatcher
2. **NEW-P3-002** — CancellationException swallowed in BankStatementLifecycleProcessor
3. **NEW-P3-003** — CancellationException swallowed in ReceiptLinkService
4. **P3-P1-03** — Matching result not persisted
5. **P3-P1-04** — Receipt+expense not atomic (legacy paths)
6. **P3-P1-05** — Direct bypass paths (backfill/debug)
7. **P3-P1-06** — IGNORE conflict not checked
8. **P3-P1-07** — EUR fallback in OCR parse
9. **P3-P1-08** — Parse failure status wrong
10. **P3-P1-09** — Batch no pending reviews
11. **P3-P1-10** — Statement dedupe weaker than legacy

### P2 (should fix)
12. **NEW-P3-004** — Double attachReceipt call
13. **NEW-P3-005** — Race in post-OCR duplicate path
14. **NEW-P3-006** — Privacy leak in production logs
15. **NEW-P3-007** — Event written for non-existent receipt

### P3 (cleanup)
16. **NEW-P3-008** — homeCurrency() thread starvation risk

---

## Do-Not-Fix-Locally (wait for universal PR)

| Issue | Wait for |
|-------|----------|
| NEW-P3-001/002/003 (CancellationException) | U-PR1 — shared detekt rule + helper |
| NEW-P3-008 (homeCurrency Flow) | U-PR6 — timeout wrapper for settings flows |
