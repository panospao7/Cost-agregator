# Pipeline 2 — Transaction Lifecycle: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 5 FIXED, 0 PARTIAL, 16 NEW open issues  
> **Total open items:** 16

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P2-P1-01 | P1 | `updateBusinessTaxFields()` misses restore guard | ✅ FIXED | ✅ **FIXED** | Uses `DatabaseWriteBarrier` |
| P2-P1-02 | P1 | Failed creates invisible in `transaction_events` | ✅ FIXED | ✅ **FIXED** | Writes CREATE_ATTEMPTED/VALIDATION_FAILED/INSERT_CONFLICT |
| P2-P1-03 | P1 | `STRICT_EXTERNAL_ID` returns weak `InsertConflict` | ✅ FIXED | ✅ **FIXED** | Resolves to DuplicateSkipped with existing ID |
| P2-P1-04 | P1 | Debug/restore methods bypass lifecycle | ✅ FIXED | ✅ **FIXED** | Guarded by `BuildConfig.DEBUG` |
| P2-P1-05 | P1 | Public DAO mutation surface enables lifecycle bypass | 📝 TODO ONLY | ✅ **FIXED** | `@RestrictedExpenseDaoMutation` + CI architecture test |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P2-001 | P1 | TOCTOU race in `updateExpense` — beforeSnapshot outside transaction | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-002 | P1 | Same TOCTOU in 6 other update methods | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-003 | P1 | `deleteExpense(Expense)` uses stale caller entity for snapshot | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-004 | P2 | Non-atomic duplicate check in `updateExpense` | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-005 | P2 | `DefaultExpenseCategoryAssignmentService` bypasses lifecycle | DefaultExpenseCategoryAssignmentService.kt | 🔴 OPEN |
| NEW-P2-006 | P2 | `NotificationRepository.deleteAll()` bypasses audit trail | NotificationRepository.kt | 🔴 OPEN |
| NEW-P2-007 | P2 | Currency conversion failure leaves stale `baseAmount` | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-008 | P2 | DAO exposes `updateMerchantForMerchant` that nulls dedupeKey | ExpenseDao.kt | 🔴 OPEN |
| NEW-P2-009 | P2 | Planner hardcodes `EXPENSE_CREATED` trigger for update paths | SideEffectPlanner.kt | 🔴 OPEN |
| NEW-P2-010 | P2 | Inconsistent event-write guard between `bulkUpdateCategory` overloads | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-011 | P3 | `updateLocation` missing correlationId | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-012 | P3 | `updateMerchant` missing correlationId in event | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-013 | P3 | `updateType` missing correlationId in event | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-014 | P3 | `ExpenseWriteStore.updateMerchant` doesn't update merchantKey/dedupeKey | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-015 | P3 | Bulk idempotency keys non-unique across time | ExpenseWriteStore.kt | 🔴 OPEN |
| NEW-P2-016 | P3 | `Flow.first()` could hang indefinitely for currency settings | CurrencySettingsRepository.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 5 |
| 🔴 OPEN (new issues) | 16 |
| **Total open work** | **16** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **NEW-P2-001** — TOCTOU race in updateExpense (beforeSnapshot outside transaction)
2. **NEW-P2-002** — Same TOCTOU in 6 other update methods (batch fix with 001)
3. **NEW-P2-003** — deleteExpense uses stale caller entity for snapshot

### P2 (should fix)
4. **NEW-P2-004** — Non-atomic duplicate check in updateExpense
5. **NEW-P2-005** — DefaultExpenseCategoryAssignmentService bypasses lifecycle
6. **NEW-P2-006** — NotificationRepository.deleteAll() bypasses audit trail
7. **NEW-P2-007** — Currency conversion failure leaves stale baseAmount
8. **NEW-P2-008** — DAO exposes updateMerchantForMerchant that nulls dedupeKey
9. **NEW-P2-009** — Planner hardcodes EXPENSE_CREATED trigger for update paths
10. **NEW-P2-010** — Inconsistent event-write guard between bulkUpdateCategory overloads

### P3 (cleanup)
11. **NEW-P2-011** — updateLocation missing correlationId
12. **NEW-P2-012** — updateMerchant missing correlationId in event
13. **NEW-P2-013** — updateType missing correlationId in event
14. **NEW-P2-014** — updateMerchant doesn't update merchantKey/dedupeKey
15. **NEW-P2-015** — Bulk idempotency keys non-unique across time
16. **NEW-P2-016** — Flow.first() could hang indefinitely for currency settings

---

## Do-Not-Fix-Locally (wait for universal PR)

| Issue | Wait for |
|-------|----------|
| NEW-P2-001/002/003 (TOCTOU) | U-PR2 — shared snapshot-inside-transaction pattern |
| NEW-P2-016 (Flow.first() hang) | U-PR6 — timeout wrapper for settings flows |
