# U-PR1: CancellationException Safety — Implementation Plan

**Issue:** U-CANCEL-01  
**Severity:** P1  
**PR:** U-PR1  
**Branch:** `master-refactor` at commit `f49188e2`  
**Date:** 2026-05-31  
**Affected Pipelines:** P1, P3, P4, P6, P7, P8, P9, P10, P11

---

## 1. Executive Summary

Kotlin coroutines use `CancellationException` (CE) as a cooperative cancellation signal. When a broad `catch (e: Exception)` block swallows CE without rethrowing, the coroutine appears to complete normally instead of propagating cancellation. This causes:

- **Zombie coroutines** that continue executing after their scope is cancelled
- **WorkManager workers** that never report `Result.retry()` or `Result.failure()` on cancellation, causing stale "running" state
- **Restore/backup operations** that cannot reliably drain background work because workers ignore the stop signal
- **Resource leaks** from coroutines that outlive their intended lifecycle

This plan establishes a project-wide contract: **every `catch (e: Exception)` in a suspend function must rethrow `CancellationException`**, enforced by an architecture guard test that scans source files.

---

## 2. Source Material Reviewed

| File | Lines Read | Purpose |
|------|-----------|---------|
| `service/NotificationCaptureService.kt` | 131–660 | P1 outer catch at line 579 |
| `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt` | 1–56 | P3 dispatchAfterSave catch |
| `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 97–664 | P3/P10 per-item catch (517), outer catch (622) |
| `domain/receipt/lifecycle/ReceiptLinkService.kt` | 96–450 | P3 unlinkReceiptFromExpense catch (line ~445) |
| `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` | 42–940 | P4 reconcile catch (469), regenerateReminder catches (555, 578, 607), lifecycle event catches (869, 904, 929) |
| `domain/forecasting/FinancialStressForecastEngine.kt` | 49–345 | P6 computeStressForecast catch (164), per-rule catch (306), materialized read catch (330) |
| `domain/budget/BudgetMonitor.kt` | 26–434 | P6 checkBudgets retry loop (142–already fixed), diagnostic catches (224, 269) |
| `data/repository/BudgetRepository.kt` | 70–830 | P6 CRUD catches (360, 543, 577, 615, 716, 745, 773, 813), computeAdjustedSpend (360) |
| `domain/workers/WorkerExecutionGuard.kt` | 52–200 | P9 runGuarded — already handles CE correctly |
| `domain/bank/BankApiIntegration.kt` | 141–280 | P10 syncTransactions per-transaction catch — already fixed |
| `data/email/EmailReceiptIngestionService.kt` | 51–315 | P11 processEmailReceipt catch (294) — already fixed |

---

## 3. Current Behavior and Root Cause

### Root Cause
Kotlin's `Exception` hierarchy includes `CancellationException` (which extends `IllegalStateException`). A `catch (e: Exception)` block catches CE alongside genuine errors. Without an explicit `if (e is CancellationException) throw e` guard, the cancellation signal is swallowed.

### Current Behavior by Category

**Category A — Already Fixed (rethrows CE):**
- `BudgetMonitor.checkBudgets` retry loop (line 142): ✅ `catch (e: CancellationException) { throw e }`
- `BudgetMonitor` diagnostic catches (lines 224, 269): ✅ `if (e is CancellationException) throw e`
- `BankStatementLifecycleProcessor` outer catch (line 622): ✅ checks CE, finalizes, rethrows
- `BankApiIntegration.syncTransactions` per-transaction (line 251): ✅ `if (e is CancellationException) throw e`
- `EmailReceiptIngestionService.processEmailReceipt` (line 294): ✅ `if (e is CancellationException) throw e`
- `WorkerExecutionGuard.runGuarded` (line 143): ✅ `if (e is CancellationException) { ... throw e }`

**Category B — Swallows CE (MUST FIX):**
- `NotificationCaptureService.captureNotification` outer catch (line 579)
- `ReceiptSideEffectDispatcher.dispatchAfterSave` (line 52)
- `BankStatementLifecycleProcessor` per-item catch (line 517)
- `ReceiptLinkService.unlinkReceiptFromExpense` (line ~445)
- `RecurringLifecycleCoordinator.reconcileAllLinkedExpensesAfterBulkUpdate` per-item (line 469)
- `RecurringLifecycleCoordinator.regenerateReminderDeliveries` (lines 555, 578, 607)
- `FinancialStressForecastEngine.computeStressForecast` outer catch (line 164)
- `FinancialStressForecastEngine` per-rule catch (line 306)
- `FinancialStressForecastEngine` materialized read catch (line 330)
- `BudgetRepository` CRUD methods (lines 577, 615, 716, 745, 773, 813)
- `BudgetRepository.computeAdjustedSpend` (line 360)

**Category C — Best-Effort Event Logging (LOW RISK but should fix):**
- `RecurringLifecycleCoordinator` lifecycle event catches (lines 423, 869, 904, 929)
- `EmailReceiptIngestionService` diagnostic emit catches (lines 117, 130, 147, 161, 176, 245, 260, 276, 290, 310)

---

## 4. Affected Pipeline Matrix

| Pipeline | File | Method/Location | Category | Risk if Unfixed |
|----------|------|-----------------|----------|-----------------|
| P1 | `NotificationCaptureService.kt:579` | `captureNotification` outer catch | B | Zombie coroutine in service scope; notification processing continues after service shutdown |
| P3 | `ReceiptSideEffectDispatcher.kt:52` | `dispatchAfterSave` | B | Side effects run after receipt-save scope cancelled |
| P3 | `BankStatementLifecycleProcessor.kt:517` | per-item catch in loop | B | Loop continues processing items after cancellation; partial import without proper finalization |
| P3 | `ReceiptLinkService.kt:~445` | `unlinkReceiptFromExpense` | B | Swallowed as `Result.failure`; caller cannot distinguish cancellation from error |
| P4 | `RecurringLifecycleCoordinator.kt:469` | `reconcileAllLinkedExpensesAfterBulkUpdate` | B | Loop continues reconciling after cancellation |
| P4 | `RecurringLifecycleCoordinator.kt:555,578,607` | `regenerateReminderDeliveries` | C | Best-effort event logging; low risk but violates contract |
| P6 | `FinancialStressForecastEngine.kt:164` | `computeStressForecast` outer | B | Returns degraded result instead of propagating cancellation; caller retains stale data |
| P6 | `FinancialStressForecastEngine.kt:306` | per-rule projection catch | B | Loop continues after scope cancelled |
| P6 | `FinancialStressForecastEngine.kt:330` | materialized read catch | B | Silently degrades instead of cancelling |
| P6 | `BudgetMonitor.kt:169` | diagnostic catch in retry loop | C | Low risk — already inside retry that handles CE |
| P6 | `BudgetRepository.kt:577,615,716,745,773,813` | CRUD methods | B | Returns `Result.Error` wrapping CE; callers cannot distinguish cancellation from DB error |
| P6 | `BudgetRepository.kt:360` | `computeAdjustedSpend` | B | Returns null fallback instead of propagating cancellation |
| P9 | `WorkerExecutionGuard.kt` | `runGuarded` | A (fixed) | N/A — already correct |
| P10 | `BankApiIntegration.kt:251` | `syncTransactions` per-tx | A (fixed) | N/A — already correct |
| P11 | `EmailReceiptIngestionService.kt:294` | `processEmailReceipt` | A (fixed) | N/A — already correct |

---

## 5. Architecture Contract

### Contract Statement

> **CANCEL-01:** Every `catch` block in a `suspend` function (or a function called from a coroutine context) that catches `Exception` or any supertype of `CancellationException` MUST rethrow `CancellationException` before performing any other error handling.

### Canonical Pattern

```kotlin
// Pattern 1: Explicit rethrow (preferred for clarity)
} catch (e: Exception) {
    if (e is CancellationException) throw e
    // ... handle real errors
}

// Pattern 2: Separate catch clause (acceptable)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // ... handle real errors
}

// Pattern 3: For loops where cancellation should break the loop
for (item in items) {
    try {
        process(item)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // ... per-item error handling
    }
}
```

### Exceptions to the Contract

1. **Non-suspend functions** that are never called from a coroutine context (e.g., `onCreate`, synchronous callbacks) — CE cannot reach them.
2. **`catch (_: Exception) {}`** blocks that are explicitly documented as "fire-and-forget diagnostic logging" AND are inside a scope that already handles CE at a higher level — these are Category C and should still be fixed for hygiene but are not P1.

---

## 6. Detailed Implementation Design

### Step 1: NotificationCaptureService (P1)

**File:** `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`  
**Line:** 579  
**Change:** Add CE rethrow at the top of the catch block.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Timber.e(e, "Failed to capture notification via coordinator from $packageName")
    // ... rest of existing error handling
}
```

### Step 2: ReceiptSideEffectDispatcher (P3)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt`  
**Line:** 52  
**Change:** Add CE rethrow.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Timber.e(e, "dispatchAfterSave failed for receipt %d", receipt.id)
}
```

### Step 3: BankStatementLifecycleProcessor per-item (P3/P10)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt`  
**Line:** 517  
**Change:** Add CE rethrow so the outer catch (which already handles CE correctly) can finalize the run.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    bankStatementImportItemDao.insert(...)
    // ... rest of existing per-item error handling
}
```

### Step 4: ReceiptLinkService.unlinkReceiptFromExpense (P3)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt`  
**Line:** ~445  
**Change:** Add CE rethrow before wrapping in `Result.failure`.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Result.failure(e)
}
```

### Step 5: RecurringLifecycleCoordinator.reconcileAllLinkedExpensesAfterBulkUpdate (P4)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`  
**Line:** 469  
**Change:** Add CE rethrow in the per-expense catch.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    failed++
}
```

### Step 6: RecurringLifecycleCoordinator.regenerateReminderDeliveries (P4)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`  
**Lines:** 555, 578, 607  
**Change:** Add CE rethrow in each best-effort event catch.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    /* best-effort event */
}
```

Also fix the lifecycle event catches at lines 423, 869, 904, 929 with the same pattern.

### Step 7: FinancialStressForecastEngine.computeStressForecast (P6)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`  
**Line:** 164  
**Change:** Add CE rethrow at the top of the outer catch.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    // FCST-17: Structured diagnostics ...
    val isRecoverable = ...
}
```

### Step 8: FinancialStressForecastEngine per-rule and materialized read (P6)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`  
**Lines:** 306, 330  
**Change:** Add CE rethrow in both catch blocks.

```kotlin
// Line 306
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Timber.w(e, "$TAG: projectOccurrences failed for ruleId=%d", ruleId)
    failedRuleIds.add(ruleId)
}

// Line 330
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Timber.w(e, "$TAG: reading materialized occurrences failed")
    emptyList()
}
```

### Step 9: BudgetRepository CRUD methods (P6)

**File:** `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`  
**Lines:** 577, 615, 716, 745, 773, 813  
**Change:** Add CE rethrow at the top of each catch block.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Timber.e(e, "Failed to ...")
    // ... existing error handling
}
```

### Step 10: BudgetRepository.computeAdjustedSpend (P6)

**File:** `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`  
**Line:** 360  
**Change:** Add CE rethrow.

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Timber.w(e, "Failed to compute adjusted spend for budget ${budget.id}; ...")
    null
}
```

### Step 11: Architecture Guard Test

**File:** `app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt`  
**Purpose:** Scan all `.kt` source files for `catch` blocks in suspend functions that catch `Exception` without rethrowing CE.

```kotlin
@Test
fun `every broad catch in suspend functions rethrows CancellationException`() {
    // Scan source files for pattern:
    // suspend fun ... { ... catch (e: Exception) { ... }
    // where the catch body does NOT contain "CancellationException"
    // Report violations.
}
```

---

## 7. Dependency and Ordering Analysis

```
Step 11 (guard test) depends on: all other steps complete
Steps 1–10: independent of each other, can be done in any order
```

**Recommended order:**
1. Steps 1–10 in parallel (all are single-line additions)
2. Step 11 last (validates all fixes)

**No cross-file dependencies.** Each fix is a single line insertion (`if (e is CancellationException) throw e`) at the top of an existing catch block. No API changes, no signature changes, no new imports needed (all files already reference `kotlinx.coroutines.CancellationException` or can use the fully-qualified name).

---

## 8. Tests Required

### 8.1 Architecture Guard (prevents regression)

| Test | File | Purpose |
|------|------|---------|
| `CancellationSafetyArchitectureGuardTest` | `architecture/CancellationSafetyArchitectureGuardTest.kt` | Source-scan: every `catch (e: Exception)` or `catch (_: Exception)` in a suspend function must contain `CancellationException` in its body |

### 8.2 Shared Cancellation Contract Test

| Test | File | Purpose |
|------|------|---------|
| `CancellationPropagationContractTest` | `contracts/CancellationPropagationContractTest.kt` | Verifies that key entry points propagate CE when their scope is cancelled mid-execution |

### 8.3 Per-Pipeline Tests

| Pipeline | Test | Validates |
|----------|------|-----------|
| P1 | `NotificationCaptureServiceFallbackTest` — add case | `captureNotification` propagates CE from `serviceScope` |
| P3 | `ReceiptSideEffectDispatcherTest` — new | `dispatchAfterSave` throws CE when scope cancelled |
| P3 | `BankStatementLifecycleProcessorTest` — add case | Per-item CE breaks loop, outer catch finalizes run as CANCELLED |
| P3 | `ReceiptLinkServiceTest` — add case | `unlinkReceiptFromExpense` throws CE, does not wrap in Result.failure |
| P4 | `RecurringLifecycleCoordinatorTest` — add case | `reconcileAllLinkedExpensesAfterBulkUpdate` throws CE mid-loop |
| P6 | `FinancialStressForecastEngineTest` — add case | `computeStressForecast` throws CE, does not return degraded result |
| P6 | `BudgetRepositoryStressTest` — add case | CRUD methods throw CE, do not return Result.Error |
| P6 | `BudgetMonitorStressTest` — verify existing | Confirm existing CE test still passes |

---

## 9. Documentation Updates

| Document | Update |
|----------|--------|
| `docs/FEATURES.md` | Add "Cancellation Safety" section under Architecture Contracts |
| `CHANGELOG.md` | Add entry for U-PR1 fix |
| This file | Mark as IMPLEMENTED after merge |

---

## 10. Regression Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| CE rethrow in per-item loop causes partial processing without finalization | Medium | High | Ensure outer catch (or `finally`) handles partial state. Already done for `BankStatementLifecycleProcessor` (outer catch finalizes run). For `RecurringLifecycleCoordinator`, the bulk result is lost — acceptable because cancellation means the caller doesn't need the result. |
| `ReceiptLinkService.unlinkReceiptFromExpense` now throws instead of returning `Result.failure` | Low | Medium | Callers already handle exceptions from suspend functions. The `Result.failure(CE)` was semantically wrong — callers would treat it as a DB error and potentially retry. |
| `BudgetRepository` CRUD methods now throw CE instead of returning `Result.Error` | Low | Medium | ViewModel callers use `viewModelScope` which is already cancelled when the screen is destroyed — the throw simply stops processing faster. |
| `FinancialStressForecastEngine` now throws instead of returning degraded result | Low | Low | The widget use case already has its own try-catch that handles missing data gracefully. |
| Architecture guard test produces false positives for non-suspend functions | Medium | Low | Guard test only scans functions marked `suspend`. Use AST-level or regex heuristic with allowlist for known-safe non-suspend catches. |

---

## 11. Rollout Strategy

1. **Phase 1 — Fix Category B locations** (Steps 1–10): Single commit, all fixes are one-line additions.
2. **Phase 2 — Add architecture guard test** (Step 11): Same PR, ensures no regression.
3. **Phase 3 — Add per-pipeline cancellation tests**: Same PR or follow-up, validates runtime behavior.
4. **Review gate:** PR requires all existing tests to pass + new guard test green.
5. **No feature flag needed:** These are correctness fixes with no user-visible behavior change under normal operation.

---

## 12. Pipeline-Local Follow-Up List

| Pipeline | Follow-Up | Priority |
|----------|-----------|----------|
| P1 | Verify `serviceScope` cancellation during `onDestroy` drains work-tracked coroutines | P2 |
| P3 | Audit `ReceiptLifecycleCoordinator` (1462 lines) for additional broad catches | P2 |
| P4 | Verify `RecurringLifecycleCoordinator` lifecycle event catches (Category C) don't mask CE in edge cases where the event DAO call itself is the cancelled operation | P3 |
| P6 | Audit `BudgetRepository` remaining methods (lines 70–340) for any catches not yet identified | P3 |
| P6 | Consider whether `computeAdjustedSpend` should propagate CE to `createBudgetStatus` or if the null fallback is acceptable when the budget status computation itself is cancelled | P2 |
| P9 | `WorkerExecutionGuard` is already correct — no follow-up needed | — |
| P10 | Already fixed — no follow-up needed | — |
| P11 | Already fixed — no follow-up needed | — |

---

## 13. Acceptance Criteria

- [ ] All Category B catch blocks contain `if (e is CancellationException) throw e` (or equivalent) as the first statement
- [ ] `CancellationSafetyArchitectureGuardTest` passes with zero violations
- [ ] Existing test suite passes with no regressions
- [ ] At least one per-pipeline cancellation propagation test exists for P1, P3, P4, P6
- [ ] `BankStatementLifecycleProcessor` per-item CE causes the outer catch to finalize the run as CANCELLED (existing behavior preserved)
- [ ] `BudgetRepository` CRUD methods throw CE instead of wrapping in `Result.Error`
- [ ] `FinancialStressForecastEngine.computeStressForecast` throws CE instead of returning degraded fallback

---

## 14. Human Validation Commands

```bash
# 1. Run full test suite to confirm no regressions
./gradlew testDebugUnitTest

# 2. Run architecture guard tests specifically
./gradlew testDebugUnitTest --tests "*.architecture.*"

# 3. Run cancellation-specific tests
./gradlew testDebugUnitTest --tests "*CancellationSafety*"
./gradlew testDebugUnitTest --tests "*CancellationPropagation*"

# 4. Run affected pipeline tests
./gradlew testDebugUnitTest --tests "*NotificationCaptureService*"
./gradlew testDebugUnitTest --tests "*ReceiptSideEffectDispatcher*"
./gradlew testDebugUnitTest --tests "*BankStatementLifecycleProcessor*"
./gradlew testDebugUnitTest --tests "*ReceiptLinkService*"
./gradlew testDebugUnitTest --tests "*RecurringLifecycleCoordinator*"
./gradlew testDebugUnitTest --tests "*FinancialStressForecastEngine*"
./gradlew testDebugUnitTest --tests "*BudgetRepository*"
./gradlew testDebugUnitTest --tests "*BudgetMonitor*"

# 5. Grep to verify no remaining unguarded catches in affected files
grep -n "catch.*Exception" app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
grep -n "catch.*Exception" app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt
grep -n "catch.*Exception" app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
grep -n "catch.*Exception" app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
grep -n "catch.*Exception" app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt
grep -n "catch.*Exception" app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

# 6. Verify the architecture guard catches new violations (intentional break test)
# Temporarily remove one CE rethrow and confirm the guard test fails
```

---

## Appendix: Files Modified (Summary)

| # | File | Changes |
|---|------|---------|
| 1 | `service/NotificationCaptureService.kt` | +1 line (CE rethrow) |
| 2 | `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt` | +1 line |
| 3 | `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | +1 line |
| 4 | `domain/receipt/lifecycle/ReceiptLinkService.kt` | +1 line |
| 5 | `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` | +8 lines (1 in reconcile + 3 in regenerate + 4 in lifecycle events) |
| 6 | `domain/forecasting/FinancialStressForecastEngine.kt` | +3 lines |
| 7 | `data/repository/BudgetRepository.kt` | +7 lines (6 CRUD + 1 computeAdjustedSpend) |
| 8 | `architecture/CancellationSafetyArchitectureGuardTest.kt` | NEW (~80 lines) |
| 9 | `contracts/CancellationPropagationContractTest.kt` | NEW (~120 lines) |

**Total production code change:** ~22 lines added (all single-line CE rethrow guards)  
**Total test code:** ~200 lines new
