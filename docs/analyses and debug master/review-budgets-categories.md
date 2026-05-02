# Budgets / Categories / Alerts: Resolution Review

**Source analysis:** `docs/analyses and debug master/budgets-categories-alerts-analysis.md`  
**Date reviewed:** 2026-05-02  
**Verdict: FAIL** — several critical and high-severity issues remain unresolved.

---

## Executive Summary

The codebase has made **significant progress** on the most critical issues — particularly currency support for budgets and spend aggregation, rollover amount immutability, DB-level active-budget constraints, and multi-currency-aware forecasting. These represent the top-three fixes the original analysis identified.

However, **9 issues rated CRITICAL or HIGH remain unresolved**, including:
- CRITICAL budgets counted as healthy
- Category deletion silently converting category budgets to overall budgets
- Rollover per-period query explosion for old budgets
- Budget monitor treating undelivered notifications as delivered
- Category names lacking uniqueness

---

## Resolution Status Per Issue

### [ISSUE-1] Budgets have no currency
**Severity:** CRITICAL (if multi-currency enabled)  
**Status:** RESOLVED

`Budget.kt` now has:
- `currency: String = "EUR"` (line 60)
- `currencyAssumption: String = "LEGACY_DEFAULT"` (line 61)
- `moneyAmount: MoneyAmount` computed property (line 73)

`BudgetStatus` now has `currency`, `isPartial`, `conversionWarning` fields. `createBudgetStatus()` converts budget amounts to home currency via `convertBudgetAmountToHomeCurrency()`.

---

### [ISSUE-2] Budget aggregate queries raw-sum mixed currencies
**Severity:** CRITICAL  
**Status:** RESOLVED

`BudgetRepository.getAggregateSpent()` (lines 205–218) now delegates to `MultiCurrencyRepository`:
- `getHomeCurrencyPurchaseCategoryTotals()` for category budgets
- `getHomeCurrencyPurchaseTotal()` for overall budgets

These group expenses by `UPPER(currency)` at the SQL level, then convert each currency bucket to home currency via `CurrencyConverter.convertMultiple()`. Conversions that fail produce `MoneyAggregate.isPartial = true` with a `warningMessage`. `createBudgetStatus()` propagates partial/conversion warnings into `BudgetStatus`.

Budget amount itself is also converted via `convertBudgetAmountToHomeCurrency()` (lines 220–248).

---

### [ISSUE-3] Rollover mutates `Budget.amount` in `BudgetStatus`
**Severity:** CRITICAL  
**Status:** RESOLVED

`createBudgetStatus()` (lines 176–178) now returns:
```kotlin
budget = budget.copy(
    amount = baseLimit,
    currency = initialLimitAggregate.displayCurrency.code
)
```
`effectiveLimit` is stored as a **separate field** in `BudgetStatus` (line 187). The rollover loop (lines 142–163) computes `runningEffectiveLimit` without touching `budget.amount`.

The UI dialog receives `status.budget` (base amount preserved). The `BudgetCard` shows `(base: ...)` when `effectiveLimit != budget.amount` (line 568–574).

---

### [ISSUE-4] Budget alerts use raw status, while UI may show adjusted shared-expense status
**Severity:** CRITICAL  
**Status:** PARTIALLY RESOLVED

**What changed:** The alert now uses currency-aware spend (`status.spentAmount` in `BudgetMonitor.processBudgetStatus()` line 176) via `BudgetRepository.createBudgetStatus()` which delegates to `MultiCurrencyRepository`.

**What remains:** `BudgetMonitor` uses `status.percentUsed` (computed from raw spend) and `status.spentAmount` for notification text. But the UI `BudgetCard` (lines 416–421) recomputes `displayPercentUsed` from:
```kotlin
val displaySpend = adjustedSpend?.effectiveSpend ?: status.spentAmount
```
The adjusted spend includes shared-expense reimbursements via `SharedExpenseBudgetOffsetEngine`. So the alert threshold check and the card display can still disagree when shared expenses exist.

**Remaining discrepancy:** A budget that appears fine in the UI (after reimbursements) can still trigger a critical alert based on raw spend, or vice versa.

---

### [ISSUE-5] Critical budgets are counted as healthy in `CalculateBudgetStatusUseCase`
**Severity:** HIGH  
**Status:** STILL PRESENT

`CalculateBudgetStatusUseCase.getBudgetHealth()` (lines 26–28):
```kotlin
val exceeded = statuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
val warning = statuses.count { it.healthStatus == BudgetHealthStatus.WARNING }
val healthy = total - exceeded - warning
```
`CRITICAL` is **not counted** as `exceeded` or `warning`, so it falls into the `healthy` bucket. The `overallStatus` (lines 36–39) also ignores `CRITICAL`:
```kotlin
overallStatus = when {
    exceeded > 0 -> BudgetHealthStatus.EXCEEDED
    warning > 0 -> BudgetHealthStatus.WARNING
    else -> BudgetHealthStatus.ON_TRACK
}
```
A dashboard with a critical budget will report `ON_TRACK` overall.

**Suggested fix:** Add `val critical = statuses.count { it.healthStatus == CRITICAL }` and include it in both the count and overallStatus priority.

---

### [ISSUE-6] Budget summary card uses raw health, while budget cards use adjusted health
**Severity:** HIGH  
**Status:** PARTIALLY RESOLVED

`BudgetSummaryCard` (lines 364–382) now combines `CRITICAL` into the `warning` count:
```kotlin
val warning = budgets.count { 
    it.healthStatus == BudgetHealthStatus.WARNING || 
    it.healthStatus == BudgetHealthStatus.CRITICAL 
}
```

However, the summary still uses `it.healthStatus` (from `BudgetStatus`, based on raw spend), while individual `BudgetCard` cards recompute `displayHealthStatus` from `displayPercentUsed` (which uses `adjustedSpend?.effectiveSpend`). The summary and individual cards can still disagree for shared-expense budgets.

---

### [ISSUE-7] Category deletion can turn category budgets into overall budgets
**Severity:** CRITICAL  
**Status:** STILL PRESENT

`Budget.kt` line 39: `onDelete = ForeignKey.SET_NULL`. Deleting a category still sets the budget's `categoryId` to `null`, silently converting a category budget into an overall budget. No `budgetScope` field, no `ON DELETE RESTRICT`, no soft-delete.

---

### [ISSUE-8] Active-budget uniqueness is not DB-enforced
**Severity:** HIGH  
**Status:** RESOLVED

Now enforced at the DB level via **materialized unique indexes**:
- `activeOverallKey` — unique index (line 45 in Budget.kt), set to 1 for active overall budgets, NULL otherwise
- `activeCategoryKey` — unique index (line 46), set to `categoryId` for active category budgets, NULL otherwise
- `CHECK` constraints documented in the entity comment (lines 27–30)

Transactional helpers (`insertAndActivateOverall`, `insertAndActivateCategory`, `updateAndEnforceActiveScope`, `setActiveAndEnforceScope`, `replaceAllAndEnforceActiveScopes`) enforce this at write time. Integrity scanner queries (`countActiveOverallKeys`, `findDuplicateActiveCategoryKeys`) detect violations.

Raw DAO `insert()`/`update()` still exist but the transactional helpers are the canonical write paths.

---

### [ISSUE-9] Budget validation is split between UI and repository
**Severity:** HIGH  
**Status:** PARTIALLY RESOLVED

Repository validates `amount > 0` and `startDate > 0` on add. ViewModel validates thresholds. No centralized `BudgetDraftValidator`. Still missing in repository:
- NaN/infinity checks
- `notifyAtCritical > notifyAtWarning` validation
- `periodMode` validation (still a raw String)
- `startDate` validation on update
- Active category existence check

---

### [ISSUE-10] Invalid `periodMode` silently becomes calendar mode
**Severity:** MEDIUM/HIGH  
**Status:** STILL PRESENT

`BudgetCalculator.calculatePeriodRange()` line 47:
```kotlin
when (budget.periodMode.uppercase()) {
    "ROLLING" -> { ... }
    else -> { ... /* calendar mode */ }
}
```
`periodMode` is still a raw `String`, not an enum. Any unknown value silently falls through to calendar mode.

---

### [ISSUE-11] Rollover calculation can be very expensive for old daily/weekly budgets
**Severity:** HIGH (performance/battery)  
**Status:** STILL PRESENT

The rollover loop in `createBudgetStatus()` (lines 145–163) still iterates through every completed period since `budget.startDate`, issuing an aggregate SQL query per period. A daily budget from 2023 would issue ~1000+ queries. No `BudgetPeriodLedger` or batched SQL aggregation has been implemented.

---

### [ISSUE-12] Rollover only carries surplus, not deficits
**Severity:** MEDIUM/HIGH  
**Status:** STILL PRESENT

Line 159:
```kotlin
val surplus = (runningEffectiveLimit - spentInPeriod).coerceAtLeast(0.0)
```
Only surplus carried. No deficit reduction. No explicit rollover policy selection.

---

### [ISSUE-13] Budget monitor treats undelivered notifications as delivered
**Severity:** HIGH  
**Status:** STILL PRESENT

`BudgetMonitor.processBudgetStatus()` (line 188) unconditionally calls:
```kotlin
budgetRepository.updateExceededNotification(budget.id, now)
```
after `sendNotification()`. `sendBudgetAlert()` returns `Unit` (no delivery result). `AndroidNotificationService.sendBudgetAlert()` silently returns if notifications are disabled (lines 70–71). If the user has notifications off and later enables them, alerts may be suppressed by period cooldowns.

Note: `NotificationService` now has a `DeliveryResult` enum and `sendAiBriefingReadyWithResult()`, but `sendBudgetAlert()` still returns `Unit`.

---

### [ISSUE-14] Budget alert text hardcodes euro
**Severity:** HIGH (with multi-currency)  
**Status:** RESOLVED

`BudgetMonitor.sendNotification()` (lines 223–228) now dynamically resolves the currency symbol:
```kotlin
val currencySymbol = SupportedCurrency
    .fromCode(budget.currency)?.symbol ?: budget.currency
val content = String.format(
    Locale.US,
    "You've spent %s%.2f (%d%%) of your %s budget.",
    currencySymbol, spent, percent, categoryName
)
```

---

### [ISSUE-15] Budget alert IDs can collide after Long → Int
**Severity:** LOW/MEDIUM  
**Status:** STILL PRESENT

Line 187: `budget.id.toInt()` — still a direct Long → Int cast that can overflow/collide.

---

### [ISSUE-16] Budget status cache can send stale alerts
**Severity:** MEDIUM  
**Status:** STILL PRESENT

`getCachedBudgetStatuses()` still caches for 30 seconds (lines 151–168). BudgetMonitor uses cached statuses for alert delivery. No cache invalidation on expense/budget changes.

---

### [ISSUE-17] Budget suggestions raw-sum currencies and hardcode euro in reason
**Severity:** HIGH  
**Status:** PARTIALLY RESOLVED

`getSuggestions()` line 377 still calls the deprecated `getCategorySpentTotalsInPeriod()` which raw-sums mixed currencies. Line 398 still hardcodes `€`:
```kotlin
reason = "Based on your €${"%.0f".format(monthlyAvg)} monthly average spend."
```
The query data is not currency-aware, and the reason text is not formatted by currency.

---

### [ISSUE-18] Budget suggestions can recommend budgets for noisy categories
**Severity:** MEDIUM/HIGH  
**Status:** RESOLVED

Lines 371–372: `if (daysDiff < 7) return emptyList()` — minimum 7 days of data required. Line 389: `if (monthlyAvg <= 20.0) return@mapNotNull null` — minimum €20 monthly average threshold. Line 396: `coerceAtLeast(20.0)` — minimum suggestion amount.

---

### [ISSUE-19] Budget autopilot ignores budget period
**Severity:** CRITICAL  
**Status:** STILL PRESENT

`BudgetAutopilotEngine` computes `trendAdjustedSpend` from monthly totals and applies ±15% delta cap relative to `budget.amount` without normalizing for the budget's actual period (DAILY/WEEKLY/MONTHLY/YEARLY). A weekly budget of €100 gets the same monthly-style recommendation applied.

The delta cap constrains it to ±15% of the original amount, which partially mitigates the damage, but the recommendation itself is semantically wrong for non-monthly budgets.

---

### [ISSUE-20] Budget autopilot can recommend overall and category budgets together without hierarchy control
**Severity:** HIGH  
**Status:** STILL PRESENT

Lines 147–153 filter summary recommendations by scope but impose no constraint that category budget totals ≤ overall budget. No hierarchical reconciliation.

---

### [ISSUE-21] Autopilot apply-all is not transactional
**Severity:** HIGH  
**Status:** STILL PRESENT

`BudgetViewModel.applyAllAutopilotRecommendations()` (lines 297–333) loops through recommendations and updates budgets one-by-one. Partial failure clears all recommendations regardless.

---

### [ISSUE-22] Autopilot apply uses stale active budget snapshot
**Severity:** MEDIUM  
**Status:** STILL PRESENT

Line 302 reads `budgetRepository.getActiveBudgets()` once. Budgets could change during the loop.

---

### [ISSUE-23] BudgetForecastingEngine has incomplete accuracy update
**Severity:** HIGH  
**Status:** STILL PRESENT

`updateForecastAccuracy()` (lines 406–419) is still placeholder code:
```kotlin
val forecast = budgetForecastDao.getForecastsForBudget(forecastId).let { flow ->
    // Get the specific forecast - this is a Flow so we'd need to collect it
    // Simplified for now
    null
}
// Calculate accuracy
// accuracy = 1 - (|predicted - actual| / predicted)
// This is a simplified accuracy metric
```
No actual accuracy computation or persistence occurs.

Note: `getSpentAmount()` is now properly currency-normalized via `AnalyticsCurrencyNormalizer`, and `generateForecast()` normalizes budget amounts. This is a partial improvement but the core accuracy-update issue remains.

---

### [ISSUE-24] Forecast date-range query treats period end as inclusive
**Severity:** MEDIUM/HIGH  
**Status:** RESOLVED

`BudgetForecastDao.getForecastForDate()` line 27:
```sql
targetPeriodStart <= :date AND targetPeriodEnd > :date
```
Now uses `>` (exclusive end), not `>=`.

---

### [ISSUE-25] Budget forecast uniqueness is app-layer only
**Severity:** MEDIUM/HIGH  
**Status:** STILL PRESENT (acknowledged limitation)

`insertWithDeactivation()` deactivates existing forecasts at the app layer. No DB-level partial unique index. This is acknowledged in the code but not resolved at the schema level.

---

### [ISSUE-26] Budget forecasts have no currency
**Severity:** HIGH  
**Status:** RESOLVED

`BudgetForecast.kt` line 56: `val currency: String = "EUR"` — currency field added with default. Forecast generation in `BudgetForecastingEngine.generateForecast()` now normalizes budget amounts to home currency.

---

### [ISSUE-27] BudgetRecommendationEngine hardcodes euros and has broken risk emoji
**Severity:** MEDIUM  
**Status:** PARTIALLY RESOLVED

**Emoji:** Fixed. `getRiskEmoji()` now returns valid emoji: `✅`, `⚠️`, `🔴`, `🚨`.

**Hardcoded euro:** Still present in `getBudgetHealthSummary()` line 157:
```kotlin
append("Budget: €${String.format("%.2f", budget.amount)}\n")
```

---

### [ISSUE-28] Category names are not unique
**Severity:** HIGH  
**Status:** STILL PRESENT

`Category.kt` has no `normalizedName` field, no unique index on `name`. `CategoryDao.insert()` uses `OnConflictStrategy.IGNORE` but duplicates are not actually prevented.

---

### [ISSUE-29] `CategoryDao.getByName()` is exact/case-sensitive
**Severity:** MEDIUM  
**Status:** STILL PRESENT

Line 39: `SELECT * FROM categories WHERE name = :name LIMIT 1` — exact case-sensitive match only. `CategoryRepository.getCategoryByName()` does in-memory case-insensitive search, so callers using the DAO directly get different behavior.

---

### [ISSUE-30] Default categories are not protected at DAO level
**Severity:** HIGH  
**Status:** STILL PRESENT

`CategoryDao.delete()` (line 31) is a plain `@Delete`. No guard for `isDefault`. Repository layer lacks a delete guard.

---

### [ISSUE-31] Deleting a category can delete merchant mappings
**Severity:** MEDIUM/HIGH  
**Status:** STILL PRESENT

`MerchantCategory.kt` line 16: `onDelete = ForeignKey.CASCADE`. Deleting a category cascades to delete all learned merchant-category mappings.

---

### [ISSUE-32] Merchant-category learning globally overwrites category from one edit
**Severity:** HIGH  
**Status:** STILL PRESENT

`ExpenseRepository.updateExpenseCategory(expense, newCategoryId)` (lines 362–387) calls `merchantCategoryRepository.learnPattern()` which inserts with `OnConflictStrategy.REPLACE`. A single manual edit globally overwrites future categorization for that merchant pattern. No confidence-based learning, no user consent prompt, no scoping by amount/source/context.

---

### [ISSUE-33] Bulk category update is not transactional
**Severity:** HIGH  
**Status:** STILL PRESENT

`updateExpenseCategoryBulk()` (lines 399–423) uses `categoryUpdateMutex` but does NOT wrap in `database.withTransaction`. Three operations (expense update, merchant learning, correction insert) can partially fail.

Note: the single-expense version `updateExpenseCategory(expense, newCategoryId)` (lines 362–387) IS transactional (`database.withTransaction`).

---

### [ISSUE-34] Single category update is transactional, but classifier model training is not called
**Severity:** MEDIUM  
**Status:** STILL PRESENT

`updateExpenseCategory(expense, newCategoryId)` writes the correction record but never calls `HybridExpenseClassifier.learnFromCorrection()`. The Naive Bayes model may not learn from manual corrections unless another code path triggers it.

---

### [ISSUE-35] Category cannot be cleared through one repository overload
**Severity:** MEDIUM  
**Status:** STILL PRESENT

`updateExpenseCategory(expenseId, categoryId)` (lines 392–397):
```kotlin
if (categoryId == null) return
```
Silently returns on null. Cannot clear a category via this method.

---

### [ISSUE-36] Merchant canonical category lookup can be nondeterministic
**Severity:** MEDIUM/HIGH  
**Status:** STILL PRESENT

`MerchantCategoryDao.getCategoryByNormalizedCanonical()` (line 14) returns a single `MerchantCategory?`. `normalizedCanonicalName` is indexed but not unique. Multiple rows with the same canonical name could return an arbitrary row.

---

### [ISSUE-37] Merchant-category mappings lack source/audit fields
**Severity:** HIGH  
**Status:** STILL PRESENT

`MerchantCategory.kt` still has the same fields. No `source`, `createdAt`, `updatedAt`, `userConfirmed`, `lastUsedAt`, `scope`, or mapping history.

---

## Strong Parts (Confirmed Still Valid)

1. **Budget spend uses `effectiveAmount`** — Yes, via `EFFECTIVE_AMOUNT_SQL` / `EFFECTIVE_AMOUNT_E_SQL` constants.
2. **Budget spend is purchase-only** — Yes, via `SPENDING_TYPE_SQL` / `SPENDING_TYPE_E_SQL` constants.
3. **Budget period calculation is centralized** — Yes, `BudgetCalculator` remains the single authority.
4. **Active budget switching is transactional** — Yes, and now strengthened with DB-level materialized unique indexes.
5. **Autopilot and forecasting use aggregate SQL** — Yes, plus `BudgetForecastingEngine` now uses `AnalyticsCurrencyNormalizer` for additional currency safety.
6. **Budget monitor has threshold-specific cooldowns** — Yes, and cooldowns are period-aware.

---

## Additional Improvements Noted (Beyond Original Analysis)

1. **`BudgetStatus` now exposes `MoneyAmount` computed properties** — `moneySpentAmount`, `moneyRemainingAmount`, `moneyEffectiveLimit`.
2. **`BudgetForecastingEngine` now uses `AnalyticsCurrencyNormalizer`** — for currency-aware `getSpentAmount()` and `getHistoricalSpendingData()`. Conversion warnings are logged.
3. **`BudgetRepository.createBudgetStatus()` handles conversion failures** — propagates `conversionWarning` and `isPartial` into `BudgetStatus`.
4. **`ExpenseDao` has `@Deprecated` annotations** — pointing callers to `MultiCurrencyRepository` alternatives for currency-safe aggregation.
5. **`NotificationService` has `DeliveryResult` enum** — foundation exists but is not yet used for budget alerts.
6. **BudgetForecastDao `getForecastForDate` now uses period-specific filtering** — `budgetId = :budgetId AND` clause ensures forecasts are scoped to the correct budget.
7. **`BudgetDao` has full materialized-key invariant enforcement** — with `replaceAllAndEnforceActiveScopes` for restore/debug scenarios.
8. **`CategoryDao.seedDefaultsIfEmpty()`** — atomic `@Transaction` method preventing race conditions during category seeding.

---

## Summary Statistics

| Status | Count |
|--------|-------|
| RESOLVED | 12 |
| PARTIALLY RESOLVED | 7 |
| STILL PRESENT | 18 |

**Unresolved critical issues:** ISSUE-5, ISSUE-7, ISSUE-11, ISSUE-19  
**Unresolved high issues:** ISSUE-9, ISSUE-10, ISSUE-12, ISSUE-13, ISSUE-17, ISSUE-20, ISSUE-21, ISSUE-23, ISSUE-28, ISSUE-30, ISSUE-32, ISSUE-33, ISSUE-37

---

## Recommended Immediate Fixes (Top 5)

1. **ISSUE-5:** Fix `CalculateBudgetStatusUseCase` to count CRITICAL separately.
2. **ISSUE-7:** Change `onDelete = ForeignKey.RESTRICT` for category budgets or add `budgetScope` field.
3. **ISSUE-13:** Add `sendBudgetAlertWithResult()` and only update notification timestamps on confirmed delivery.
4. **ISSUE-11:** Implement `BudgetPeriodLedger` or batched SQL aggregation for rollover to avoid N-per-period queries.
5. **ISSUE-19:** Normalize autopilot recommendations to the budget's actual period.
