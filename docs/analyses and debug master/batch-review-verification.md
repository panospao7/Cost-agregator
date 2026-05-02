# Batch A-E Fix Verification — Spot-Check Results

**Date:** 2026-05-02
**Reviewer:** Automated (deepseek-v4-pro)
**Method:** Static file read — no Gradle compilation

---

## Spot-Check Results

| # | File | Check | Result |
|---|------|-------|--------|
| 1 | `data/database/dao/PendingReviewDao.kt` | `approveAllPending()` deprecated? `insert()` using IGNORE? | **CONFIRMED FIXED** |
| 2 | `data/database/GroupTransactionCoordinator.kt` | `addExpenseToGroup()` wrapped in `withTransaction`? | **CONFIRMED FIXED** |
| 3 | `domain/budget/BudgetAutopilotEngine.kt` | Period normalization for WEEKLY/DAILY? | **CONFIRMED FIXED** |
| 4 | `domain/usecase/budget/CalculateBudgetStatusUseCase.kt` | `critical` count added? | **CONFIRMED FIXED** |
| 5 | `data/location/PhotonGeocodingService.kt` | `PrivacyGate` injected and checked? | **CONFIRMED FIXED** |
| 6 | `data/ai/provider/CloudWarrantyExtractionService.kt` | `PrivacyGate` injected and checked? | **CONFIRMED FIXED** |
| 7 | `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt` | Uses `ReceiptLinkService`? | **CONFIRMED FIXED** |
| 8 | `data/privacy/DataRetentionWorker.kt` | `WorkerSpec.DEFAULTS` check? | **CONFIRMED FIXED** |

---

## Detailed Evidence

### 1. PendingReviewDao.kt — CONFIRMED FIXED

- **Line 17:** `@Insert(onConflict = OnConflictStrategy.IGNORE)` — insert uses IGNORE conflict strategy with clear Javadoc explaining it skips duplicates silently rather than overwriting.
- **Lines 93–104:** `approveAllPending()` is annotated `@Deprecated("Use ReviewQueueRepository.approveReview()…", level = DeprecationLevel.ERROR)` — callers will get a **compile-time error**, not just a warning.

### 2. GroupTransactionCoordinator.kt — CONFIRMED FIXED

- **Line 179:** `database.withTransaction { … }` wraps the entire `addExpenseToGroup()` body, ensuring group-existence check, payer-membership validation, participant validation, and group-expense insert are all atomic.

### 3. BudgetAutopilotEngine.kt — CONFIRMED FIXED

- **Lines 102–115:** A `periodNormalizer` is computed based on `budget.period`:
  - **WEEKLY** → `1.0 / monthlyMultiplier(RecurrenceFrequency.WEEKLY)` (~1/4.33)
  - **DAILY** → `1.0 / 30.44`
  - **YEARLY** → `12.0`
  - **MONTHLY** → `1.0`
- The normalizer is applied at line 116: `recommendedBudget *= periodNormalizer`.

### 4. CalculateBudgetStatusUseCase.kt — CONFIRMED FIXED

- **Line 27:** `val critical = statuses.count { it.healthStatus == BudgetHealthStatus.CRITICAL }` — critical count is tracked.
- **Line 30:** `val healthy = total - exceeded - critical - warning` — critical budgets excluded from healthy count.
- **Line 36:** `criticalCount = critical` passed into `BudgetHealth` data class.
- **Line 40:** `critical > 0 -> BudgetHealthStatus.CRITICAL` — overall status reflects critical before warning.

### 5. PhotonGeocodingService.kt — CONFIRMED FIXED

- **Line 34:** `private val privacyGate: PrivacyGate` — injected via constructor.
- **Lines 44–48 (`search()`):** `privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)` — gate checked; returns `GeocodingLookupResult.Failure(GeocodingError.Disabled)` on denial.
- **Lines 62–66 (`searchMultiple()`):** same gate check with same denial handling.

### 6. CloudWarrantyExtractionService.kt — CONFIRMED FIXED

- **Line 40:** `private val privacyGate: PrivacyGate` — injected via constructor.
- **Lines 65–68 (`extractWarranty()`):** `privacyGate.check(PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION)` — gate checked; returns `null` (graceful degradation) on denial.

### 7. ReceiptMatchingViewModel.kt — CONFIRMED FIXED

- **Line 7:** `import …domain.receipt.lifecycle.ReceiptLinkService`
- **Line 37:** `private val receiptLinkService: ReceiptLinkService` — injected.
- **Lines 89–95 (`runAutoMatching()`):** `receiptLinkService.linkReceiptToExpense(…)` used for AutoMatch results.
- **Lines 156–162 (`manualMatch()`):** `receiptLinkService.linkReceiptToExpense(…)` used for manual matches.
- **Lines 180–186 (`rerunForReceipt()`):** `receiptLinkService.linkReceiptToExpense(…)` used on re-run.

### 8. DataRetentionWorker.kt — CONFIRMED FIXED

- **Lines 41–45:**
  ```kotlin
  val spec = WorkerSpec.DEFAULTS[WORK_NAME]
  if (spec != null && !spec.enabled) {
      Log.d(TAG, "Worker disabled via WorkerSpec.DEFAULTS, skipping")
      return Result.success()
  }
  ```
- The `WorkerSpec.DEFAULTS` map (in `domain/workers/WorkerSpec.kt`) contains an entry for `"data_retention"` with `enabled = true` by default. The check guards against a spec with `enabled = false`, returning early with `Result.success()`.

---

## Supporting Artifact Checks

- **WorkerSpec.kt** (`domain/workers/WorkerSpec.kt`): `DEFAULTS` map exists with 7 entries including `"data_retention"`, each with `name`, `version`, `enabled`, `constraints`, `repeatIntervalHours`, etc. (lines 46–103).
- **PrivacyGate.kt** (`domain/privacy/PrivacyGate.kt`): Interface with `suspend fun check(capability: PrivacyCapability, context: Map<String, String> = emptyMap()): PrivacyDecision`. Provides the contract that `PhotonGeocodingService` and `CloudWarrantyExtractionService` depend on.
- **GroupTransactionCoordinator.kt** (`data/database/GroupTransactionCoordinator.kt`): Implements the domain interface via `database.withTransaction` for ACID compliance in `createGroupWithMembers`, `addMemberToGroup`, `addExpenseToGroup`, `addExpenseWithLink`, `createSystemExpenseAndLinkToGroup`, `addExpenseToGroupAtomic`, and `deleteGroupAtomic`.

---

## Verdict

**All 8 spot-checks pass.** Every listed fix is confirmed present in the codebase as of the current worktree state.

**OVERALL VERDICT: PASS**
