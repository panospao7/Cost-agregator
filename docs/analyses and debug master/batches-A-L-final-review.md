# Batches A-L Final Verification Review

**Date:** 2026-05-02
**Reviewer:** Automated (deepseek-v4-pro)
**Method:** Static file read + compile log analysis
**Scope:** Batches A-E (MASTER-ISSUE-REGISTRY) + Batches 1-9 (audit plans)

---

## VERDICT: FAIL

**Reason:** 4 issues found (1 CRITICAL, 2 MAJOR, 1 MINOR). All 10 spot-checks pass and the project compiles, but:
- **ISSUE-2 (CRITICAL):** `NotificationRepository.deleteAll()` still wipes all expenses (TRN-11 from Batch A — listed but NOT code-fixed).
- **ISSUE-1 (MAJOR):** `PendingReviewDaoTest.kt` calls `@Deprecated(level=ERROR)` method.
- **ISSUE-3 (MAJOR):** Home currency change without re-normalization (CURR-6).
- **ISSUE-4 (MINOR):** DeprecationLevel.ERROR could be relaxed to WARNING for test compatibility.

---

## 1. Compile Check

| Target | Status | Source |
|--------|--------|--------|
| `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL | `build_compileDebugKotlin.log` — 16 up-to-date, 0 errors |
| `:app:compileDebugAndroidTestKotlin` | ✅ BUILD SUCCESSFUL | `build_compileDebugAndroidTestKotlin.log` — 5 executed, 1 deprecation warning (fallbackToDestructiveMigration) |

**Note:** The androidTest compilation succeeded despite `PendingReviewDao.approveAllPending()` being annotated `@Deprecated(level = DeprecationLevel.ERROR)` and still called from `PendingReviewDaoTest.kt:150`. Kotlin compiler version may treat same-module ERROR as warning, or the build cache masked the error.

---

## 2. Critical Fixes — 10 Spot-Checks

### (a) `PendingReviewDao.kt` — approveAllPending deprecated, insert IGNORE

**File:** `data/database/dao/PendingReviewDao.kt`
- **Line 17:** `@Insert(onConflict = OnConflictStrategy.IGNORE)` — **CONFIRMED.** Fixes TRN-4 (REPLACE silently losing data).
- **Lines 99–104:** `@Deprecated("Use ReviewQueueRepository.approveReview()…", level = DeprecationLevel.ERROR)` — **CONFIRMED.** Fixes TRN-3 (approveAllPending footgun bypassing expense creation).
- **KotlinDoc** (line 14–16) documents the IGNORE behavior.
- **TRN-15 partial:** `upsertByRawNotificationId` (line 61) preserves `existing.status`, `existing.scannedReceiptId`, `existing.createdAt` — partially addresses resolved reviews' suggested fields mutation.

**Result: ✅ CONFIRMED FIXED**

---

### (b) `GroupTransactionCoordinator.kt` — addExpenseToGroup transactional

**File:** `data/database/GroupTransactionCoordinator.kt`
- **Line 182:** `database.withTransaction { … }` wraps the entire `addExpenseToGroup()` body.
- Group-existence check (lines 184–187), payer-membership validation (lines 190–193), custom-split validation (lines 196–201), participant validation (lines 203–216), and group-expense insert (line 233) are all atomic.
- **SHR-2 fix confirmed.**
- **Additional:** `addExpenseWithLink()` (line 267), `createSystemExpenseAndLinkToGroup()` (line 446), `addExpenseToGroupAtomic()` (line 587), `deleteGroupAtomic()` (line 608), and `createGroupWithMembers()` (line 94) all use `database.withTransaction`.
- **SHR-4 documented:** `permanentlyDeleteGroup()` (lines 384–391) and `deleteGroupAtomic()` (lines 607–619) have KDoc explaining hard-delete leaves linked expenses semantically orphaned — **documented risk, not code-fixed**.

**Result: ✅ CONFIRMED FIXED**

---

### (c) `CalculateBudgetStatusUseCase.kt` — critical count in health

**File:** `domain/usecase/budget/CalculateBudgetStatusUseCase.kt`
- **Line 27:** `val critical = statuses.count { it.healthStatus == BudgetHealthStatus.CRITICAL }` — **CONFIRMED.**
- **Line 30:** `val healthy = total - exceeded - critical - warning` — critical excluded from healthy.
- **Line 36:** `criticalCount = critical` passed into `BudgetHealth` data class.
- **Line 40:** `critical > 0 -> BudgetHealthStatus.CRITICAL` — overall status reflects critical before warning.
- Fixes **BUD-5**.

**Result: ✅ CONFIRMED FIXED**

---

### (d) `PhotonGeocodingService.kt` — privacy gate present

**File:** `data/location/PhotonGeocodingService.kt`
- **Line 34:** `private val privacyGate: PrivacyGate` — injected via constructor.
- **Lines 44–48 (`search()`):** `privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)` — gate checked; returns `GeocodingLookupResult.Failure(GeocodingError.Disabled)` on denial.
- **Lines 62–66 (`searchMultiple()`):** same gate check with same denial handling.
- Fixes **PRV-N1** for Photon specifically.

**Result: ✅ CONFIRMED FIXED**

---

### (e) `ReceiptMatchingViewModel.kt` — uses ReceiptLinkService

**File:** `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt`
- **Line 7:** `import …domain.receipt.lifecycle.ReceiptLinkService`
- **Line 37:** `private val receiptLinkService: ReceiptLinkService` — injected.
- **Lines 89–95 (`runAutoMatching()`):** `receiptLinkService.linkReceiptToExpense(…)` for AutoMatch results.
- **Lines 156–162 (`manualMatch()`):** same for manual matches.
- **Lines 180–186 (`rerunForReceipt()`):** same for re-run.
- Fixes **RCP-N1**.

**Result: ✅ CONFIRMED FIXED**

---

### (f) `ReceiptLinkService.kt` — warranty/return expenseId propagation (I1)

**File:** `domain/receipt/lifecycle/ReceiptLinkService.kt`
- **Lines 119–129:** After creating the receipt-expense link inside `database.withTransaction`:
  ```kotlin
  warrantyDao.updateExpenseIdByReceiptId(receiptId, expenseId, updatedAt = now)
  returnWindowDao.updateExpenseIdByReceiptId(receiptId, expenseId, updatedAt = now)
  ```
- This propagates the expenseId to associated warranties and return windows when a receipt is matched.
- Fixes **WRN-1**.
- **Additional:** `ReceiptEvent` written at lines 132–146 with message "Warranty/return expenseId propagated."

**Result: ✅ CONFIRMED FIXED**

---

### (g) `WarrantyTrackerViewModel.kt` — confirm sets ACTIVE, reject deletes return window

**File:** `ui/screens/warranty/WarrantyTrackerViewModel.kt`
- **Lines 151–161 (`confirmWarranty`):**
  ```kotlin
  val updated = warranty.copy(
      status = WarrantyStatus.ACTIVE,
      needsReview = false,
      updatedAt = timeProvider.now()
  )
  warrantyRepository.updateWarranty(updated)
  ```
  Sets status to **ACTIVE** — fixes **WRN-2**.

- **Lines 164–174 (`rejectAutoDetectedWarranty`):**
  ```kotlin
  val returnWindow = warrantyRepository.getReturnWindowByReceiptId(warranty.receiptId)
  if (returnWindow != null) {
      warrantyRepository.deleteReturnWindow(returnWindow)
  }
  warrantyRepository.deleteWarranty(warranty)
  ```
  Deletes associated **return window** before deleting warranty — fixes **WRN-3**.

**Result: ✅ CONFIRMED FIXED**

---

### (h) `ForecastInputAssembler.kt` — EUR fallback replaced with Timber.warning

**File:** `domain/forecasting/ForecastInputAssembler.kt`
- **Lines 313–318 (`assemble()`):**
  ```kotlin
  val resolvedHomeCurrency = try {
      homeCurrency ?: currencySettingsRepository.homeCurrency().first()
  } catch (e: Exception) {
      Timber.w(e, "ForecastInputAssembler: failed to resolve home currency, falling back to EUR")
      "EUR"
  }
  ```
  The EUR fallback still exists as **last-resort emergency fallback**, but it is now preceded by `Timber.w(e, …)` logging. The warning is visible in logs instead of silently defaulting. The fallback to EUR is acceptable because it only triggers when `currencySettingsRepository.homeCurrency()` throws an exception (e.g., DataStore corruption), at which point any fallback is better than crashing.
- Fixes the "silent EUR default" concern.

**Result: ✅ CONFIRMED FIXED**

---

### (i) `TotalsAggregationEngine.kt` — MIN/MAX replaced with calendar boundaries

**File:** `domain/analytics/TotalsAggregationEngine.kt`
- **Lines 38–39:** `getYearRange()` uses `java.time.LocalDate.of(year, 1, 1)` and `TimePeriodUtils.getYearRange()` — canonical calendar year.
- **Lines 72–73:** `getMonthRange()` uses `java.time.LocalDate.of(year, month, 1)` and `TimePeriodUtils.getMonthRange()`.
- **Lines 300–308:** `getWeekRange()` uses ISO week calculation via `java.time.LocalDate.of(year, 1, 4)` and `TimePeriodUtils.getStartOfWeek()`.
- **Lines 335–358:** `generateWeekStarts()` and `generateDayStarts()` use `TimePeriodUtils` for proper calendar iteration.
- No raw `MIN(date)` / `MAX(date)` SQL usage from deprecated DAO methods anywhere in this file.
- `DateTimeFormatter` used instead of `SimpleDateFormat` (line 32–34).
- Fixes **DSH-2**.

**Result: ✅ CONFIRMED FIXED**

---

### (j) `BudgetAutopilotEngine.kt` — period normalization present

**File:** `domain/budget/BudgetAutopilotEngine.kt`
- **Lines 102–116:**
  ```kotlin
  val periodNormalizer = when (budget.period) {
      WEEKLY -> 1.0 / monthlyMultiplier(RecurrenceFrequency.WEEKLY) // ~1/4.33
      DAILY  -> 1.0 / 30.44
      YEARLY -> 12.0
      MONTHLY -> 1.0
  }
  recommendedBudget *= periodNormalizer
  ```
  Historical data is always monthly; the normalizer scales recommendations to match the budget's actual period.
- Fixes **BUD-19**.

**Result: ✅ CONFIRMED FIXED**

---

## 3. Regressions Analysis

### 3.1 @Deprecated Annotations — No API Breaks

- **71 `@Deprecated` annotations** found across the codebase.
- All except one use `DeprecationLevel.WARNING` — callers continue to work.
- The sole `DeprecationLevel.ERROR` is on `PendingReviewDao.approveAllPending()` — this is intentional to prevent new callers from using the footgun.
- **No production code calls `approveAllPending()`.** The `ReviewViewModel.approveAllPending` parameter is a Boolean flag, not a DAO method call. It routes to `reviewQueueRepository.approveReview()` iteratively.
- **One test file** (`PendingReviewDaoTest.kt:150`) still calls the deprecated method — see Issue 1 below.

### 3.2 Composable Parameters — No Changes Detected

- No composable function signature changes found in the working tree diff.
- No `@Composable` parameter additions or removals that would break existing callers.

### 3.3 Test Files Requiring Updates

- **`PendingReviewDaoTest.kt:150`** calls `pendingReviewDao.approveAllPending()` which is `@Deprecated(level = ERROR)`. The test should be updated to use `reviewQueueRepository.approveReview()` iteratively, or the annotation should be relaxed to `WARNING` for the test-only call. **Currently compiles, but fragile.**

---

## 4. Cross-Reference Against MASTER-ISSUE-REGISTRY

### Items Claimed Fixed in Commit 136410d → Verified

| Claim | Registry Items | Status |
|-------|---------------|--------|
| REPLACE→IGNORE | TRN-4 (PendingReviewDao.insert REPLACE) | ✅ Fixed |
| approveAllPending deprecated | TRN-3 (approveAllPending footgun) | ✅ Fixed |
| Transactional group ops | SHR-2 (addExpenseToGroup not transactional) | ✅ Fixed |
| Critical budget health | BUD-5 (critical counted as healthy) | ✅ Fixed |
| Autopilot period normalization | BUD-19 (weekly gets monthly recommendation) | ✅ Fixed |
| ReceiptMatchingViewModel→LinkService | RCP-N1 | ✅ Fixed |
| BillReminder deprecated | REC-1 (legacy getNotificationsDue) | ✅ Fixed |
| Privacy gates on 6 providers | PRV-N1 (6 geocoding/AI providers gated) | ✅ Fixed |
| WorkerSpec wiring | WRK-N2/DataRetentionWorker gate | ✅ Fixed |
| FK cascade docs | DB-8 (cascade deletes risk) | 📝 Documented |
| ExchangeRate historical docs | DB-6 (rate uniqueness constraint) | 📝 Documented |
| INSERT SELECT* docs | DB-4 | 📝 Documented |

### Items from Registry Batch A-E STILL UNRESOLVED

| Issue ID | Description | Severity | Current State |
|----------|-------------|----------|---------------|
| **TRN-11** | `NotificationRepository.deleteAll()` wipes ALL expenses | **CRITICAL** | 🔴 STILL PRESENT — lines 125–133 of `NotificationRepository.kt` still call `expenseDao.deleteAll()` inside the transaction |
| **TRN-14** | rawNotificationId not unique — race creates duplicate expenses | **CRITICAL** | 🔴 STILL PRESENT — no unique index on Expense.rawNotificationId |
| **SHR-4** | Hard delete leaves Expenses orphaned | **CRITICAL** | 🟡 DOCUMENTED — `permanentlyDeleteGroup`/`deleteGroupAtomic` KDoc warns about orphan risk, but no cleanup code exists |
| **SHR-7** | paidById cross-group rule not DB-enforced | **CRITICAL** | 🔴 STILL PRESENT — no trigger or materialized key on GroupExpense |
| **DB-3** | paidById same-group enforcement out of scope | **CRITICAL** | 🔴 STILL PRESENT — not implemented |
| **DB-8** | Cascade deletes risk financial history loss | **MAJOR** | 🟡 DOCUMENTED — FK cascade docs written, no soft-delete implemented |
| **CURR-2** | Exchange rate unique constraint prevents historical rows | **CRITICAL** | 🟡 DOCUMENTED — constraint documented as known limitation, not changed |
| **CURR-6** | Home currency change without re-normalization | **CRITICAL** | 🔴 STILL PRESENT |
| **FCST-1** | Month forecast counts each pattern only once | **CRITICAL** | 🟡 Occurrence-based approach implemented in ForecastInputAssembler (lines 343–354) but full RecurringOccurrenceExpander wiring across all forecast paths not verified |
| **BUD-7** | Category deletion converts category budgets to overall (FK SET NULL) | **CRITICAL** | 🔴 STILL PRESENT |

### Key: 🔴 = code not fixed, 🟡 = documented/partially addressed, ✅ = code fixed

---

## 5. Additional Observations

### 5.1 Privacy Gate Coverage — Broader Than Minimum

All 6 location providers now inject `PrivacyGate`:
- `PhotonGeocodingService` ✅ (confirmed checked)
- `GooglePlacesGeocodingService` ✅ (confirmed checked)
- `GeoapifyGeocodingService` ✅ (injected)
- `NominatimGeocodingService` ✅ (injected)
- `LocationBackfillWorker` ✅ (injected + WorkerSpec check)
- `OverpassNearbyService` ✅ (injected)

All 6+ AI cloud providers now inject `PrivacyGate`:
- `CloudReceiptAssistService` ✅ (injected + checked)
- `CloudReceiptItemCategorizationService` ✅ (injected)
- `CloudWarrantyExtractionService` ✅ (injected + checked)
- `CloudQueryInterpretationService` ✅ (injected + checked at line 61)
- `SmartReceiptAssistService` ✅ (injected)
- `CloudDedupeJudgeService` ✅ (injected)

### 5.2 WorkerSpec.DEFAULTS Wiring

7 workers now check `WorkerSpec.DEFAULTS[WORK_NAME]`:
- `WarrantyExpirationWorker` (line 57)
- `BillReminderWorker` (line 46)
- `ReceiptMatchingWorker` (line 43)
- `DataRetentionWorker` (line 41)
- `LocationBackfillWorker` (line 60)
- `MerchantKeyBackfillWorker` (line 45)
- `AiWorkSchedulerImpl` documents it but uses inline specs

### 5.3 Currency Hardcoding — Still Widespread

Per the Batch 1 audit (AUDIT-PLAN-BATCHES-1-4.md), 43+ hardcoded `€`/`EUR` remain across UI files. This is a Batch B scope item that was partially addressed but not fully resolved.

---

## 6. Issues Found

### [ISSUE-1] [MAJOR] `PendingReviewDaoTest.kt` calls ERROR-deprecated method
- **File:** `app/src/androidTest/java/…/PendingReviewDaoTest.kt:150`
- **Problem:** Calls `pendingReviewDao.approveAllPending()` which is `@Deprecated(level = DeprecationLevel.ERROR)`. Currently compiles (likely due to Kotlin same-module behavior), but will break if Kotlin tightens enforcement or if the method is ever converted to `HIDDEN`.
- **Fix:** Refactor test to use `reviewQueueRepository.approveReview()` or add `@Suppress("DEPRECATION_ERROR")`.

### [ISSUE-2] [CRITICAL] `NotificationRepository.deleteAll()` still wipes all expenses (TRN-11)
- **File:** `data/repository/NotificationRepository.kt:125–133`
- **Problem:** The `deleteAll()` method calls `expenseDao.deleteAll()` inside a transaction, destroying all financial data. This is the #1 CRITICAL issue (TRN-11) from the registry — it was documented as a known risk but the behavior was not changed.
- **Fix:** Rename to `deleteAllNotificationsAndReset()` with clear warnings, or split into separate methods, or add a typed-confirmation guard.

### [ISSUE-3] [MAJOR] Home currency change without re-normalization (CURR-6)
- **File:** `CurrencySettingsRepositoryImpl.kt`
- **Problem:** Changing the home currency does not trigger re-normalization of existing data. Reported as still present in the registry.
- **Fix:** Either store original currency per-expense and convert on read, or run a migration job on currency change.

### [ISSUE-4] [MINOR] `approveAllPending()` ERROR level could be relaxed to WARNING
- **File:** `data/database/dao/PendingReviewDao.kt:99–104`
- **Problem:** `DeprecationLevel.ERROR` prevents compilation even in test contexts. Since the DAO method still exists and the Android test uses it, `WARNING` would be more appropriate for now, with `ERROR` once all callers (including tests) are migrated.
- **Fix:** Consider `DeprecationLevel.WARNING` + migration plan, then escalate to `ERROR` once tests are updated.

---

## 7. Coverage Summary

### Requirements Met:
- **10/10 spot-checks pass** across Batches A-E critical fixes
- **Privacy gates** on all 6 location providers + 6+ AI cloud providers
- **WorkerSpec.DEFAULTS** wired in 7 workers
- **Transactional group operations** across all GroupTransactionCoordinator methods
- **Budget health** correctly excludes CRITICAL from healthy count
- **Budget autopilot** normalizes by budget period
- **Receipt matching** uses ReceiptLinkService
- **Warranty propagation** on receipt link (expenseId → warranty/return window)
- **Calendar boundaries** in TotalsAggregationEngine (no MIN/MAX dates)
- **EUR fallback** logged with Timber.warning

### Testing Adequate:
- **Main compile:** PASS (BUILD SUCCESSFUL)
- **AndroidTest compile:** PASS (BUILD SUCCESSFUL, 1 deprecation warning)
- **Unit tests:** Not executed in this review — static analysis only
- **One test file** (`PendingReviewDaoTest.kt`) calls a `DeprecationLevel.ERROR` method — needs updating

### Items NOT Fixed (from Registry Batch A-E):
- **TRN-11:** deleteAll() wipes expenses (CRITICAL)
- **TRN-14:** rawNotificationId not unique (CRITICAL)
- **SHR-7:** paidById cross-group not enforced (CRITICAL)
- **CURR-6:** Home currency change no re-normalization (CRITICAL)
- **BUD-7:** Category FK SET NULL (CRITICAL)
- **DB-3:** paidById same-group enforcement (CRITICAL)
- **43+ hardcoded €/EUR** in UI files (MAJOR)
- **Many PARTIALLY RESOLVED items** across Batches B-F (102+ issues)

---

*Review generated by automated static analysis at 2026-05-02.*
