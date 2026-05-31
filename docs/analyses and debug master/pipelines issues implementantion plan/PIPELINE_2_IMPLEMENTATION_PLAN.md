# Pipeline 2 — Transaction Lifecycle: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 2 — Transaction Lifecycle  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 2 — Transaction Lifecycle
Verdict: YELLOW
Summary:
- 5 old issues fully FIXED
- 3 P1 issues FIXED by universal (NEW-P2-001/002/003 via U-PR2, NEW-P2-009 via U-PR8)
- 12 pipeline-local issues remain OPEN (4 P2, 6 P3, plus 2 P2 from debug report)
- Core lifecycle coordinator is solid; remaining work is edge-case hardening
- Key gaps: non-atomic duplicate check, lifecycle bypass in category assignment, stale baseAmount on conversion failure
- No issues blocked by pending universal PRs
```

---

## 2. Sources Reviewed

**Docs:**
- `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_2_CONSOLIDATED_ISSUES.md`
- `new debugging session/pipeline2_static_debug_report_b6abe0a.md`

**Source files:**
- `TransactionLifecycleCoordinator.kt`, `ExpenseWriteStore.kt`, `ExpenseDao.kt`
- `DefaultExpenseCategoryAssignmentService.kt`, `NotificationRepository.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 2 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | No direct impact — Pipeline 2 coordinator already rethrows CE | No | N/A |
| U-PR2 (TOCTOU) | **Fixes** NEW-P2-001/002/003 — atomicReadModifyWrite pattern applied | No | ✅ Fixed |
| U-PR3 (Money/Currency) | No direct impact | No | N/A |
| U-PR4 (Barrier) | Pipeline 2 already uses writeBarrier | No | ✅ Compatible |
| U-PR5 (Privacy) | No direct impact on transaction lifecycle | No | N/A |
| U-PR6 (Worker Guard) | No direct impact | No | N/A |
| U-PR7 (TimeProvider) | No direct impact | No | N/A |
| U-PR8 (Side Effects) | **Fixes** NEW-P2-009 — planner now uses correct trigger type | No | ✅ Fixed |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P2-P1-01 | ✅ FIXED | U-PR4 compatible | None |
| P2-P1-02 | ✅ FIXED | None | None |
| P2-P1-03 | ✅ FIXED | None | None |
| P2-P1-04 | ✅ FIXED | None | None |
| P2-P1-05 | ✅ FIXED | None | None |
| NEW-P2-001 | ✅ FIXED | U-PR2 | None |
| NEW-P2-002 | ✅ FIXED | U-PR2 | None |
| NEW-P2-003 | ✅ FIXED | U-PR2 | None |
| NEW-P2-004 | ✅ FIXED | U-PR2 | Already inside transaction (verified) |
| NEW-P2-005 | 🔴 OPEN | None | Route through lifecycle or add barrier |
| NEW-P2-006 | 🔴 OPEN | None | Add audit event for bulk delete |
| NEW-P2-007 | ✅ FIXED | None | baseAmount cleared to 0.0 on conversion failure (P2-PR1 landed) |
| NEW-P2-008 | 🔴 OPEN | None | Restrict DAO method or regenerate dedupeKey |
| NEW-P2-009 | ✅ FIXED | U-PR8 | None |
| NEW-P2-010 | ✅ FIXED | None | Event guard unified — only writes if affectedCount > 0 (P2-PR1 landed) |
| NEW-P2-011 | 🔴 OPEN | None | Add correlationId parameter |
| NEW-P2-012 | 🔴 OPEN | None | Add correlationId parameter |
| NEW-P2-013 | 🔴 OPEN | None | Add correlationId parameter |
| NEW-P2-014 | 🔴 OPEN | None | Regenerate merchantKey/dedupeKey on merchant update |
| NEW-P2-015 | 🔴 OPEN | None | Add timestamp to bulk idempotency keys |
| NEW-P2-016 | 🔴 OPEN | None | Add timeout to Flow.first() |

---

## 5. New Issues / Regressions

No regressions introduced by universal fixes. All universal changes are compatible with Pipeline 2's existing patterns.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P2-005 | P2 | DefaultExpenseCategoryAssignmentService bypasses lifecycle | Lifecycle bypass | P2-PR2 |
| NEW-P2-006 | P2 | NotificationRepository.deleteAll() bypasses audit trail | Lifecycle bypass | P2-PR2 |
| NEW-P2-008 | P2 | DAO updateMerchantForMerchant nulls dedupeKey | Data integrity | P2-PR2 |
| NEW-P2-014 | P3 | updateMerchant doesn't update merchantKey/dedupeKey | Data integrity | P2-PR3 |
| NEW-P2-011 | P3 | updateLocation missing correlationId | Observability | P2-PR3 |
| NEW-P2-012 | P3 | updateMerchant missing correlationId | Observability | P2-PR3 |
| NEW-P2-013 | P3 | updateType missing correlationId | Observability | P2-PR3 |
| NEW-P2-015 | P3 | Bulk idempotency keys non-unique across time | Idempotency | P2-PR3 |
| NEW-P2-016 | P3 | Flow.first() could hang indefinitely | Robustness | P2-PR3 |



---

## 7. PR Organization

### P2-PR1 — Core Correctness (Atomicity & Data Integrity)

```
PR name: fix(p2): atomic duplicate check, stale baseAmount, event-write consistency
Goal: Fix data integrity gaps in update paths
Issues fixed: NEW-P2-004, NEW-P2-007, NEW-P2-010
Universal dependencies: U-PR2 (already landed — TOCTOU pattern available)
Files likely touched:
  - ExpenseWriteStore.kt
  - TransactionLifecycleCoordinator.kt
Implementation steps:
  1. NEW-P2-004: Move duplicate check inside the atomicReadModifyWrite transaction block (U-PR2 pattern already provides this structure)
  2. NEW-P2-007: On CurrencyConverter failure, set baseAmount=null and baseCurrency=null (not stale values); add conversionStatus field or flag
  3. NEW-P2-010: Unify bulkUpdateCategory overloads to use same event-write guard; extract shared guard method
Tests:
  - concurrent_update_duplicate_check_is_atomic
  - conversion_failure_clears_stale_baseAmount
  - bulk_category_update_writes_event_consistently
Risks: Low — operates within existing transaction patterns
Acceptance criteria:
  - Duplicate check cannot observe stale state
  - Failed conversion never leaves old baseAmount
  - Both bulkUpdateCategory overloads write events identically
```

### P2-PR2 — Lifecycle Bypass Hardening

```
PR name: fix(p2): close lifecycle bypass paths in category assignment and bulk delete
Goal: Ensure all expense mutations go through barrier/audit
Issues fixed: NEW-P2-005, NEW-P2-006, NEW-P2-008
Universal dependencies: None
Files likely touched:
  - DefaultExpenseCategoryAssignmentService.kt
  - NotificationRepository.kt
  - ExpenseDao.kt
Implementation steps:
  1. NEW-P2-005: Add writeBarrier.checkWritesAllowed() to DefaultExpenseCategoryAssignmentService; route through coordinator's bulkUpdateCategory or add lifecycle event
  2. NEW-P2-006: Add BULK_DELETED audit event in NotificationRepository.deleteAll() path; or route through coordinator
  3. NEW-P2-008: Either restrict updateMerchantForMerchant to internal/test use, or regenerate dedupeKey inside the method after merchant update
Tests:
  - category_assignment_service_respects_write_barrier
  - delete_all_writes_audit_event
  - merchant_bulk_update_preserves_dedupeKey
Risks: Low — additive guards
Acceptance criteria:
  - No expense mutation path bypasses write barrier
  - All bulk mutations produce at least one audit event
  - dedupeKey never nulled by merchant rename
```

### P2-PR3 — Observability & Cleanup

```
PR name: fix(p2): add correlationIds, fix merchantKey propagation, timeout safety
Goal: Improve traceability and fix minor correctness issues
Issues fixed: NEW-P2-011, NEW-P2-012, NEW-P2-013, NEW-P2-014, NEW-P2-015, NEW-P2-016
Universal dependencies: None
Files likely touched:
  - ExpenseWriteStore.kt
  - CurrencySettingsRepository.kt
Implementation steps:
  1. NEW-P2-011/012/013: Add optional correlationId parameter to updateLocation, updateMerchant, updateType; pass through to TransactionEvent metadata
  2. NEW-P2-014: In updateMerchant, regenerate merchantKey via MerchantKeyGenerator and recompute dedupeKey
  3. NEW-P2-015: Append timestamp or UUID suffix to bulk idempotency keys: "bulk:$categoryId:$timestamp"
  4. NEW-P2-016: Replace bare Flow.first() with withTimeoutOrNull(5000) { flow.first() } ?: fallbackCurrency
Tests:
  - update_events_carry_correlationId
  - merchant_update_regenerates_merchantKey
  - bulk_idempotency_keys_unique_across_invocations
  - currency_flow_timeout_returns_fallback
Risks: Very low — additive/defensive
Acceptance criteria:
  - All update events have correlationId when caller provides one
  - merchantKey always consistent with merchant name
  - No indefinite hang on currency settings read
```

---

## 8. Detailed Implementation Plan

### P2-PR1 Step-by-Step

1. **Open** `ExpenseWriteStore.kt` (or `TransactionLifecycleCoordinator.kt` depending on where updateExpense lives)
   - Find the duplicate check in `updateExpense()` — it currently runs BEFORE the transaction
   - Move it INSIDE the `atomicReadModifyWrite` block (which U-PR2 already provides)
   - The existing row is already loaded inside the transaction; use it for duplicate comparison

2. **Find** currency conversion call in update path
   - On conversion failure (exception or null result), set:
     ```kotlin
     expense.baseAmount = null
     expense.baseCurrency = null
     ```
   - Do NOT retain the old baseAmount from the previous conversion

3. **Find** both `bulkUpdateCategory` overloads
   - Extract shared event-write logic into a private helper
   - Both overloads call the same helper

### P2-PR2 Step-by-Step

1. **Open** `DefaultExpenseCategoryAssignmentService.kt`
   - Add `writeBarrier.checkWritesAllowed("CategoryAssignment")` at entry
   - If it calls `expenseDao` directly, add a lifecycle event write or route through coordinator

2. **Open** `NotificationRepository.kt`
   - Find `deleteAll()` or equivalent bulk delete
   - Add `writeBarrier.checkWritesAllowed()` + write a `BULK_DELETED` TransactionEvent

3. **Open** `ExpenseDao.kt`
   - Find `updateMerchantForMerchant()`
   - Add `dedupeKey` regeneration: after setting new merchant, recompute dedupeKey for affected rows
   - Or: annotate with `@RestrictedExpenseDaoMutation` and ensure no production caller uses it without key regeneration

### P2-PR3 Step-by-Step

1. **Open** `ExpenseWriteStore.kt`
   - Find `updateLocation()`, `updateMerchant()`, `updateType()`
   - Add `correlationId: String? = null` parameter to each
   - Pass to TransactionEvent metadata: `metadata = mapOf("correlationId" to correlationId)`

2. **In** `updateMerchant()`:
   - After setting new merchant name, add: `val newKey = MerchantKeyGenerator.generate(newMerchant)`
   - Update both merchantKey and dedupeKey on the expense

3. **Open** `CurrencySettingsRepository.kt`
   - Find `Flow.first()` usage
   - Replace with `withTimeoutOrNull(5_000L) { flow.first() } ?: DEFAULT_CURRENCY`

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 2 Adapter/Follow-up |
|---|---|
| U-PR2 (TOCTOU) | ✅ Already landed — no additional adapter needed. Pipeline 2 duplicate check (NEW-P2-004) should leverage the same atomicReadModifyWrite pattern. |
| U-PR8 (Side Effects) | ✅ Already landed — trigger type fix confirmed working. |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 2 targeted tests
./gradlew :app:testDebugUnitTest --tests "*TransactionLifecycle*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ExpenseWriteStore*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TransactionSideEffect*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CategoryAssignment*" --stacktrace

# Architecture guard
./gradlew :app:testDebugUnitTest --tests "*RestrictedExpenseDaoMutation*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P2-PR1: Duplicate check atomic; stale baseAmount cleared; event-write guard unified
- [ ] P2-PR2: All mutation paths respect write barrier; bulk delete audited; dedupeKey preserved
- [ ] P2-PR3: CorrelationIds propagated; merchantKey regenerated; Flow.first() timeout-safe
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Architecture guard passes (no new @RestrictedExpenseDaoMutation violations)
- [ ] Pipeline 2 status upgraded to GREEN in master tracker
