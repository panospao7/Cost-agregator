# Budgets / Categories / Alerts / Recommendations Deep Analysis

Branch: `master-refactor`

Scope reviewed:

- budget entity/DAO/repository
- budget status calculation
- budget rollover
- budget monitor/alerts
- budget screen/view model
- budget forecasting/autopilot/recommendation engines
- category entity/DAO/repository
- merchant-category learning
- category updates from expense edits
- relevant expense aggregate queries

This is a static review.

---

# Executive verdict

The budget layer has several good foundations:

- budget status uses SQL aggregates instead of row scans
- spending filters are mostly purchase-only
- shared expenses use `effectiveAmount`
- active-budget switching is transactional in DAO helpers
- period handling is centralized in `BudgetCalculator`
- notification cooldowns are period-aware
- autopilot avoids capped row reads

But this area is not yet financially safe.

The largest issue is that **budget money is still raw `Double` with no currency**, while budget spend aggregates also raw-sum expense amounts. This repeats the currency problem from earlier analyses.

The second largest issue is that **budget status mutates the budget amount itself for rollover**, causing the UI edit path to potentially save a temporary rollover-adjusted limit as the permanent base budget.

Highest-risk themes:

1. budgets have no currency
2. budget spend raw-sums mixed currencies
3. rollover replaces `Budget.amount` inside `BudgetStatus`
4. alerts use raw budget status while UI cards may show adjusted shared-expense status
5. critical budgets are counted as healthy in one use case
6. category deletion can turn category budgets into overall budgets
7. merchant-category learning globally overwrites future categorization from a single edit
8. budget recommendations ignore budget period and currency

---

# Critical / high-priority findings

## 1. Budgets have no currency

### Where

- `Budget.kt`
- `BudgetStatus`
- `BudgetRepository`
- `BudgetMonitor`
- `BudgetForecast`
- `BudgetAutopilotEngine`

### Problem

`Budget.amount` is just a `Double`.

There is no:

- budget currency
- base amount
- base currency
- conversion status
- exchange-rate snapshot

### Impact

A user can have:

```text
Budget: 500
Expenses: €200 + $200
```

and budget status treats this as:

```text
spent = 400
```

with no declared currency.

This affects:

- budget progress
- alerts
- suggestions
- autopilot
- forecasts
- safe-to-spend
- dashboard budget widgets

### Severity

**Critical if multi-currency is enabled**

### Fix

Add budget money fields:

```kotlin
amount
currency
baseAmount
baseCurrency
conversionStatus
```

or use a domain `Money` model.

Budget spend must be converted into the budget currency before comparison.

---

## 2. Budget aggregate queries raw-sum mixed currencies

### Where

- `ExpenseDao.getCategorySpentInPeriod`
- `ExpenseDao.getTotalForPeriod`
- `ExpenseDao.getTotalSpentBetween`
- `ExpenseDao.getMonthlySpendingTotalsBetween`
- `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween`
- `BudgetRepository.getAggregateSpent`
- `BudgetForecastingEngine`
- `BudgetAutopilotEngine`

### Problem

Budget calculations use:

```sql
SUM(EFFECTIVE_AMOUNT_SQL)
```

without grouping by currency.

The grouped-by-currency DAO helpers exist, but the core budget path does not use them.

### Impact

Budget totals, rollover, alerts, recommendations, and forecasts can all be numerically wrong.

### Severity

**Critical**

### Fix

Budget path should use:

```text
expense totals grouped by currency
→ convert each bucket to budget currency
→ sum converted values
```

Minimum safe contract:

```kotlin
BudgetSpendTotal(
    amount: Double,
    currency: CurrencyCode,
    conversionFailures: List<CurrencyCode>
)
```

If conversion fails, budget status should show “incomplete” instead of silently comparing raw values.

---

## 3. Rollover mutates `Budget.amount` in `BudgetStatus`

### Where

`BudgetRepository.createBudgetStatus()`

When rollover is enabled:

```kotlin
return BudgetStatus(
    budget = budget.copy(amount = limit),
    ...
)
```

### Problem

The repository replaces the persisted base budget amount with the effective rollover limit inside the returned `BudgetStatus`.

Then `BudgetScreen` opens the edit dialog using:

```kotlin
editingBudget = budgetStatus
AddEditBudgetDialog(initialBudget = status.budget)
```

So the edit UI can receive the rollover-adjusted amount instead of the true base budget.

### Example

Base budget:

```text
Groceries: €300
```

User rolled over €80 surplus.

Status returns:

```text
budget.amount = €380
```

User opens edit dialog and saves.

The app can persist:

```text
base budget = €380
```

even though €380 was only this period’s effective limit.

### Severity

**Critical**

### Fix

Do not mutate `Budget.amount`.

Change model to:

```kotlin
BudgetStatus(
    budget: Budget,
    baseLimit: Double,
    effectiveLimit: Double,
    rolloverCarry: Double,
    spentAmount: Double,
    ...
)
```

UI edit must always use the original `Budget`.

---

## 4. Budget alerts use raw status, while UI may show adjusted shared-expense status

### Where

- `BudgetMonitor`
- `BudgetRepository.getBudgetStatuses()`
- `BudgetViewModel.calculateAdjustedSpend()`
- `BudgetScreen.BudgetCard`

### Problem

The UI calculates adjusted spend using `SharedExpenseBudgetOffsetEngine` and shows:

```text
adjustedSpendBreakdown.effectiveSpend
```

But `BudgetMonitor` processes only the raw `BudgetStatus.spentAmount`.

### Impact

A user can see one thing in the budget screen and get alerts for another.

Example:

```text
Raw status: 95% used
Adjusted shared/reimbursement status: 60% used
```

The app may send a critical alert while the UI card says the budget is fine.

Or the reverse: adjusted status exceeds budget but raw alert path does not notify.

### Severity

**Critical**

### Fix

Create one canonical budget-status engine that can produce:

```text
rawSpend
adjustedSpend
budgetComparableSpend
```

Alerts and UI must use the same `budgetComparableSpend`.

---

## 5. Critical budgets are counted as healthy in `CalculateBudgetStatusUseCase`

### Where

`CalculateBudgetStatusUseCase.getBudgetHealth()`

Current logic:

```kotlin
val exceeded = statuses.count { it.healthStatus == EXCEEDED }
val warning = statuses.count { it.healthStatus == WARNING }
val healthy = total - exceeded - warning
```

### Problem

`CRITICAL` budgets are not counted as warning or exceeded, so they fall into `healthy`.

### Impact

A dashboard or summary using this use case can report a critical budget as healthy.

### Severity

**High**

### Fix

Count critical separately:

```kotlin
val critical = statuses.count { it.healthStatus == CRITICAL }
val warning = statuses.count { it.healthStatus == WARNING }
val healthy = total - exceeded - critical - warning
```

Overall status should be:

```text
EXCEEDED > CRITICAL > WARNING > ON_TRACK
```

---

## 6. Budget summary card uses raw health, while budget cards use adjusted health

### Where

`BudgetScreen`

The summary card counts:

```kotlin
status.healthStatus
```

But each card recomputes display status using:

```kotlin
adjustedSpend?.effectiveSpend ?: status.spentAmount
```

### Impact

The summary at the top can disagree with the individual cards.

Example:

- Summary says: `1 exceeded`
- Card says: `on track` after reimbursements

### Severity

**High**

### Fix

Budget UI state should expose one display-ready status:

```kotlin
displaySpend
displayPercentUsed
displayHealthStatus
rawHealthStatus
adjustedHealthStatus
```

Then every UI component should use the same display status.

---

## 7. Category deletion can turn category budgets into overall budgets

### Where

`Budget.kt`

Budget has FK:

```text
categoryId → Category.id ON DELETE SET NULL
```

### Problem

A category budget is represented by:

```text
categoryId = some category
```

An overall budget is represented by:

```text
categoryId = null
```

If a category is deleted, the budget’s `categoryId` becomes null. That means a category budget silently becomes an overall budget.

### Impact

Possible outcomes:

- duplicate active overall budgets
- old category budget now competes with real overall budget
- dashboard budget health changes unexpectedly
- user loses context for the original category budget

### Severity

**Critical**

### Fix

Do not use `SET NULL` for category budgets unless you also preserve intent.

Options:

1. `ON DELETE RESTRICT` if category has budgets.
2. Soft-delete categories.
3. Add budget field:

```kotlin
budgetScope = OVERALL / CATEGORY
```

Then if category is deleted, status becomes:

```text
CATEGORY_DELETED
```

not overall.

---

## 8. Active-budget uniqueness is not DB-enforced

### Where

- `Budget.kt`
- `BudgetDao`

The entity comment says active-budget uniqueness is enforced transactionally in DAO/repository logic.

### Problem

DAO helpers do enforce this for normal paths:

- `insertAndActivateOverall`
- `insertAndActivateCategory`
- `updateAndEnforceActiveScope`
- `setActiveAndEnforceScope`

But the database itself does not enforce:

- one active overall budget
- one active budget per category

Also raw DAO methods still exist:

```kotlin
insert()
update()
insertAll()
```

### Impact

Any direct DAO write, test helper, migration, restore, or future code path can create conflicting active budgets.

`getActiveBudgets()` returns all active budgets, so budget status can show duplicates.

### Severity

**High**

### Fix

Add DB-level triggers or Room-compatible unique constraints.

At minimum, add integrity scanner:

```text
duplicate active overall budgets
duplicate active category budgets
category budget with deleted category
```

---

## 9. Budget validation is split between UI and repository

### Where

- `BudgetViewModel.validateThresholds()`
- `BudgetRepository.addBudget()`
- `BudgetRepository.updateBudget()`

### Problem

Repository validates:

- amount > 0
- startDate > 0 on add only

ViewModel validates thresholds.

But repository does not consistently validate:

- finite amount
- NaN / infinity
- threshold range
- `notifyAtCritical > notifyAtWarning`
- valid `periodMode`
- startDate on update
- active category exists
- currency, because no currency exists

### Impact

Direct repository callers can persist invalid budgets.

Examples:

```text
amount = NaN
warning = 1.5
critical = 0.2
periodMode = "banana"
startDate = 0 on update
```

### Severity

**High**

### Fix

Create:

```kotlin
BudgetDraftValidator
```

Use it in:

- add
- update
- restore/debug snapshot
- migration repair
- import
- tests

---

## 10. Invalid `periodMode` silently becomes calendar mode

### Where

`BudgetCalculator.calculatePeriodRange()`

Behavior:

```kotlin
when (periodMode.uppercase()) {
    "ROLLING" -> rolling
    else -> calendar
}
```

### Problem

Any unknown value becomes calendar mode.

### Impact

Corrupt settings or bad migration can silently change budget period semantics.

### Severity

**Medium / High**

### Fix

Use enum:

```kotlin
enum class BudgetPeriodMode { ROLLING, CALENDAR }
```

Reject unknown values or mark budget invalid.

---

## 11. Rollover calculation can become very expensive for old daily/weekly budgets

### Where

`BudgetRepository.createBudgetStatus()`

Rollover loops through all completed periods since `budget.startDate`:

```kotlin
while (currentWindow.end <= window.start) {
    periods.add(currentWindow)
    currentWindow = calculatePeriodWindowForTime(...)
}
for (period in periods) {
    getAggregateSpent(...)
}
```

### Impact

A daily budget created years ago can issue hundreds or thousands of aggregate queries every time statuses are recalculated.

Example:

```text
daily budget from 2023 → 1000+ periods → 1000+ SQL sums
```

### Severity

**High performance/battery**

### Fix

Store rollover state per period:

```text
BudgetPeriodLedger(
    budgetId,
    periodStart,
    periodEnd,
    baseLimit,
    spent,
    carryIn,
    carryOut
)
```

Or compute aggregate rollover in SQL batches, not one query per cycle.

---

## 12. Rollover only carries surplus, not deficits

### Where

`BudgetRepository.createBudgetStatus()`

Surplus calculation:

```kotlin
val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
effectiveLimit = budget.amount + surplus
```

### Problem

Overspending in a previous period does not reduce the next period.

This may be intentional, but “compounding rollover” can also mean both surplus and deficit carry.

### Impact

User expectation mismatch.

Example:

```text
Budget €300
Last month spent €350
Next month limit remains €300
```

Some users expect:

```text
next month effective limit = €250
```

### Severity

**Medium / High depending on intended UX**

### Fix

Make policy explicit:

```text
carrySurplusOnly
carrySurplusAndDeficit
noRollover
```

Show carry calculation in UI.

---

## 13. Budget monitor treats undelivered notifications as delivered

### Where

- `BudgetMonitor.processBudgetStatus()`
- `AndroidNotificationService.sendBudgetAlert()`

### Problem

`sendBudgetAlert()` returns `Unit`.

If notifications are disabled, Android service silently returns.

But `BudgetMonitor` still updates:

```kotlin
lastWarningNotifiedAt
lastCriticalNotifiedAt
lastExceededNotifiedAt
```

### Impact

If user has notifications disabled, the app records that an alert was sent.

When the user later enables notifications, alerts may be suppressed by cooldown or period state.

### Severity

**High**

### Fix

Make budget alerts return delivery result:

```kotlin
sendBudgetAlertWithResult(): DeliveryResult
```

Only update `last...NotifiedAt` after confirmed delivery.

If not delivered, record:

```text
lastBudgetAlertSuppressedReason = NOTIFICATION_PERMISSION_DISABLED
```

---

## 14. Budget alert text hardcodes euro

### Where

`BudgetMonitor.sendNotification()`

Message:

```text
"You've spent €%.2f..."
```

### Problem

Hardcoded euro.

### Impact

Wrong for USD/GBP/etc.

### Severity

**High with multi-currency**

### Fix

Use app money formatter and budget currency.

---

## 15. Budget alert IDs can collide after `Long → Int`

### Where

`BudgetMonitor.processBudgetStatus()`

```kotlin
budget.id.toInt()
```

### Problem

Budget IDs are `Long`; notification IDs are `Int`.

Large IDs can overflow/collide.

### Severity

**Low / Medium**

### Fix

Use stable hash namespace:

```kotlin
notificationId = NotificationIds.budgetAlert(budget.id, stage)
```

---

## 16. Budget status cache can send stale alerts

### Where

`BudgetMonitor.getCachedBudgetStatuses()`

Budget statuses are cached for 30 seconds.

### Problem

If a budget or expense changes and `checkBudgets()` runs within cache validity, it can use stale status.

### Impact

A user can edit a budget upward or delete an expense, but still get an alert based on the prior status.

### Severity

**Medium**

### Fix

Do not use cached statuses for alert delivery, or invalidate cache on budget/expense table changes.

---

# Budget suggestions / autopilot / forecast findings

## 17. Budget suggestions raw-sum currencies and hardcode euro in reason

### Where

`BudgetRepository.getSuggestions()`

Suggestion uses:

```kotlin
expenseDao.getCategorySpentTotalsInPeriod(...)
```

and builds reason:

```text
Based on your €X monthly average spend.
```

### Problem

- raw-sums category totals
- no currency
- hardcoded euro
- no conversion failure handling

### Severity

**High**

### Fix

Use currency-aware category totals and app money formatter.

---

## 18. Budget suggestions can recommend budgets for noisy categories

### Where

`BudgetRepository.getSuggestions()`

It uses as little as 7 days of data and extrapolates to a monthly average.

### Impact

A few unusual purchases during the first week can produce a budget suggestion.

### Severity

**Medium / High**

### Fix

Require either:

- minimum number of transactions, or
- minimum number of active spending days, or
- show confidence label.

Example:

```text
Suggestion confidence: low, based on 7 days of data
```

---

## 19. Budget autopilot ignores budget period

### Where

`BudgetAutopilotEngine`

It calculates monthly historical spend and recommends:

```text
recommendedBudget = monthly trend-adjusted spend × safety factor
```

Then applies that number directly to `Budget.amount`.

### Problem

A weekly budget and a monthly budget get the same monthly-style recommendation.

### Example

Weekly budget:

```text
€100/week
```

Historical monthly spend:

```text
€400/month
```

Autopilot may recommend around:

```text
€400
```

and apply it as the weekly budget.

### Severity

**Critical**

### Fix

Normalize recommendations to the budget period:

```kotlin
monthlyEquivalent → budgetPeriodEquivalent
```

For example:

```text
weekly = monthly / 4.345
daily = monthly / average days per month
yearly = monthly × 12
```

---

## 20. Budget autopilot can recommend overall and category budgets together without hierarchy control

### Where

`BudgetAutopilotEngine.generateRecommendations()`

It generates recommendations for every active budget, including:

- overall budget
- category budgets

### Problem

Overall and category budgets are not independent. If category budgets are adjusted upward but overall is not, the budget hierarchy can become inconsistent.

### Impact

User can apply all and end up with:

```text
category budgets total > overall budget
```

without warning.

### Severity

**High**

### Fix

Introduce budget hierarchy policy:

```text
overall budget is cap
category budgets are envelopes
category totals may not exceed overall unless explicitly allowed
```

Autopilot should reconcile all recommendations before applying.

---

## 21. Autopilot apply-all is not transactional

### Where

`BudgetViewModel.applyAllAutopilotRecommendations()`

It loops recommendations and updates budgets one by one.

### Impact

Some updates can succeed and others fail.

The UI then clears all recommendations regardless of partial failure.

### Severity

**High**

### Fix

Apply as a repository transaction:

```kotlin
applyBudgetRecommendationBatch(recommendations): BatchResult
```

Return:

```text
applied
failed
skipped
conflicts
```

Do not clear failed recommendations.

---

## 22. Autopilot apply uses stale active budget snapshot

### Where

`BudgetViewModel.applyAllAutopilotRecommendations()`

It reads active budgets once:

```kotlin
val activeBudgets = budgetRepository.getActiveBudgets()
```

then applies recommendations.

### Problem

Budget active/category state can change during the loop.

### Severity

**Medium**

### Fix

Use a transaction and re-read each target under lock.

---

## 23. BudgetForecastingEngine has incomplete accuracy update

### Where

`BudgetForecastingEngine.updateForecastAccuracy()`

It contains placeholder logic and does not update actual forecast accuracy.

Also it appears to call:

```kotlin
getForecastsForBudget(forecastId)
```

even though the parameter is a forecast ID, not budget ID.

### Impact

Forecast accuracy metrics can remain empty forever.

This weakens:

- confidence calibration
- recommendation quality
- user trust
- future model tuning

### Severity

**High**

### Fix

Add DAO:

```kotlin
getForecastById(forecastId)
```

Then compute:

```kotlin
accuracy = 1 - abs(predicted - actual) / max(actual, predicted, epsilon)
```

and persist:

```text
actualSpending
forecastAccuracy
isActive=false if period closed
```

---

## 24. Forecast date-range query treats period end as inclusive

### Where

`BudgetForecastDao.getForecastForDate()`

Query:

```sql
targetPeriodStart <= :date AND targetPeriodEnd >= :date
```

### Problem

The app generally uses half-open ranges:

```text
[start, end)
```

So the end should be exclusive.

### Impact

A date exactly equal to `targetPeriodEnd` can match the previous period and the next period.

### Severity

**Medium / High**

### Fix

Use:

```sql
targetPeriodStart <= :date AND targetPeriodEnd > :date
```

---

## 25. Budget forecast uniqueness is app-layer only

### Where

`BudgetForecastDao.insertWithDeactivation()`

It deactivates existing active forecasts before insert, but DB schema does not enforce one active forecast per budget/period.

### Impact

Direct insert or race can create duplicate active forecasts.

### Severity

**Medium / High**

### Fix

Use trigger or runtime integrity scanner if Room cannot model the partial unique index.

---

## 26. Budget forecasts have no currency

### Where

`BudgetForecast.kt`

Fields:

```text
predictedSpending
predictedRemaining
actualSpending
```

are raw `Double`.

### Impact

Forecast history becomes meaningless for mixed-currency budgets.

### Severity

**High**

### Fix

Store forecast currency and conversion metadata.

---

## 27. BudgetRecommendationEngine hardcodes euros and has broken risk emoji

### Where

`BudgetRecommendationEngine`

Problems:

- summary text uses `€`
- high/critical emoji return empty strings

### Impact

Wrong currency display and broken UI indicators.

### Severity

**Medium**

### Fix

Use app money formatter and valid icons.

---

# Category / merchant-categorization findings

## 28. Category names are not unique

### Where

- `Category.kt`
- `CategoryDao`

`Category` has no unique index on `name`.

`CategoryDao.insert()` uses `IGNORE`, but with no unique constraint, duplicates are not ignored.

### Impact

A user can have:

```text
Groceries
groceries
Groceries 
```

This breaks:

- category lookup by name
- budget/category matching
- assistant queries
- export/report grouping
- merchant learning

### Severity

**High**

### Fix

Add normalized category key:

```kotlin
normalizedName
```

with unique index.

Reject or merge duplicates during migration.

---

## 29. `CategoryDao.getByName()` is exact/case-sensitive

### Where

`CategoryDao.getByName(name)`

### Problem

It searches:

```sql
WHERE name = :name
```

while `CategoryRepository.getCategoryByName()` does case-insensitive in memory.

### Impact

Different code paths can disagree.

### Severity

**Medium**

### Fix

Use normalized category key everywhere.

---

## 30. Default categories are not protected at DAO level

### Where

`Category.kt`
`CategoryDao.delete()`

`Category.isDefault` exists, but DAO delete does not prevent deleting default categories.

### Impact

A direct DAO or future UI path can delete:

- Uncategorized
- Groceries
- Transport
- etc.

This can break fallback categorization and budgets.

### Severity

**High**

### Fix

Use repository-only delete with guard:

```text
if category.isDefault reject
if category has expenses/budgets require migration target
```

Add DB trigger if needed.

---

## 31. Deleting a category can delete merchant mappings

### Where

`MerchantCategory.kt`

FK:

```text
categoryId → Category.id ON DELETE CASCADE
```

### Problem

Deleting a category removes merchant-category mappings.

### Impact

If category is recreated later with the same name but new ID, learned merchant mappings are gone.

### Severity

**Medium / High**

### Fix

Prefer soft-delete categories or remap before delete.

---

## 32. Merchant-category learning globally overwrites category from one edit

### Where

- `ExpenseRepository.updateExpenseCategory()`
- `ExpenseRepository.updateExpenseCategoryBulk()`
- `CategorizationEngine.learnMerchantCategory()`
- `MerchantCategoryDao.insert(REPLACE)`

### Problem

When a user changes one expense’s category, the app learns:

```text
merchant → category
```

globally.

That may be correct for stable merchants, but not for multi-category merchants.

Examples:

- Amazon
- Walmart
- pharmacy
- department stores
- Apple
- Google
- PayPal
- Stripe

A single edit can change future categorization for all future transactions from that merchant.

### Impact

Budget/category totals can drift over time because future transactions are auto-categorized too broadly.

### Severity

**High**

### Fix

Learning should be scoped and confidence-based:

```text
single correction → weak signal
bulk correction → stronger signal
merchant + amount band
merchant + text context
merchant + source app
merchant + user-confirmed rule
```

Ask user:

> “Apply this category to future transactions from this merchant?”

Do not automatically global-learn from every single edit.

---

## 33. Bulk category update is not transactional

### Where

`ExpenseRepository.updateExpenseCategoryBulk()`

It performs:

1. update all expense categories for merchant key
2. learn merchant pattern
3. insert correction

inside a mutex but not a DB transaction.

### Impact

Partial state possible:

- expenses updated but learning failed
- learning updated but correction insert failed
- correction inserted but some expense update failed

### Severity

**High**

### Fix

Wrap in `database.withTransaction`.

---

## 34. Single category update is transactional, but classifier model training is not used there

### Where

`ExpenseRepository.updateExpenseCategory()`
`HybridExpenseClassifier.learnFromCorrection()`

Single update learns merchant pattern and writes a correction, but does not call `HybridExpenseClassifier.learnFromCorrection()`.

### Impact

The Naive Bayes model may not learn from normal manual corrections unless another path calls it.

### Severity

**Medium**

### Fix

Centralize category correction handling:

```kotlin
CategoryCorrectionCoordinator.applyCorrection(...)
```

Responsibilities:

- update expense
- optionally apply to merchant/future
- update merchant rules
- train ML model
- write correction ledger
- invalidate classifier snapshots

---

## 35. Category cannot be cleared through one repository overload

### Where

`ExpenseRepository.updateExpenseCategory(expenseId, categoryId: Long?)`

```kotlin
if (categoryId == null) return
```

### Problem

A caller cannot clear a category using this method.

### Impact

UI or assistant path trying to set “uncategorized/null” silently does nothing.

### Severity

**Medium**

### Fix

Either:

- allow null and update DB to null, or
- explicitly use the Uncategorized category ID.

Do not silently return.

---

## 36. Merchant canonical category lookup can be nondeterministic

### Where

`MerchantCategoryDao.getCategoryByNormalizedCanonical()`

`normalizedCanonicalName` is indexed but not unique.

DAO returns a single `MerchantCategory?`:

```sql
SELECT * WHERE normalizedCanonicalName = :normalizedCanonicalName
```

### Problem

If multiple merchant patterns share the same normalized canonical name, Room can return an arbitrary row.

### Impact

Same merchant can categorize differently depending on row order.

### Severity

**Medium / High**

### Fix

Make normalized canonical mapping unique if it is meant to be unique.

If multiple mappings are allowed, query all and choose deterministically by:

- user-defined priority
- confidence
- timesUsed
- updatedAt

---

## 37. Merchant-category mappings lack source/audit fields

### Where

`MerchantCategory.kt`

Fields:

```text
merchantPattern
categoryId
confidence
timesUsed
normalizedCanonicalName
```

Missing:

- source: seed/user/AI/import
- createdAt
- updatedAt
- userConfirmed
- lastUsedAt
- scope
- previous mapping history

### Impact

The app cannot distinguish:

- built-in rule
- user correction
- ML suggestion
- AI result
- imported mapping

So conflict resolution is weak.

### Severity

**High**

### Fix

Add mapping metadata and correction ledger.

---

# Strong parts

## 1. Budget spend uses `effectiveAmount`

Budget queries use the shared SQL expression that handles:

- not mine → zero
- shared explicit share
- shared percentage
- full amount fallback

Good.

## 2. Budget spend is purchase-only in main queries

Core budget queries filter `transactionType = PURCHASE`.

Good.

## 3. Budget period calculation is centralized

`BudgetCalculator` is a good foundation and documents rolling vs calendar semantics.

## 4. Active budget switching is transactional in normal DAO helpers

The normal repository paths use transactional helpers to deactivate conflicting active budgets.

Good.

## 5. Autopilot and forecasting use aggregate monthly SQL

This avoids capped row scans.

Good.

## 6. Budget monitor has threshold-specific cooldowns

Warning/critical/exceeded timestamps are separate.

Good foundation.

---

# Recommended fix order

## PR 1 — Add budget money/currency foundation

Add:

```text
Budget.currency
Budget.baseAmount/baseCurrency if needed
BudgetStatus.currency
BudgetForecast.currency
```

Move all budget comparisons to converted budget currency.

## PR 2 — Stop mutating `Budget.amount` for rollover

Add separate fields:

```text
baseLimit
effectiveLimit
rolloverCarry
```

Update UI to edit only base budget.

## PR 3 — Unify raw/adjusted budget status

Create one canonical budget status calculation that powers:

- UI cards
- summary card
- notifications
- dashboard widgets
- AI briefings
- safe-to-spend

## PR 4 — Fix budget health counting

Handle `CRITICAL` correctly everywhere.

## PR 5 — Harden category deletion and budget category semantics

Do not allow category budget → overall budget mutation through `ON DELETE SET NULL`.

Use soft delete or restrict delete.

## PR 6 — Add DB-level budget invariant protection

Use triggers or schema-compatible unique constraints for:

- one active overall budget
- one active category budget

Add integrity scanner.

## PR 7 — Create `CategoryCorrectionCoordinator`

Centralize:

- single edit
- bulk edit
- future-learning consent
- merchant mapping update
- ML training
- correction ledger
- cache invalidation

## PR 8 — Make autopilot period-aware and transactional

Recommendations must match budget period and apply as a batch transaction.

## PR 9 — Fix notification delivery semantics

Budget alerts should only update sent timestamps after confirmed delivery.

## PR 10 — Add category uniqueness

Add normalized unique category key and migration to merge duplicates.

---

# Regression tests to add

1. `€100 + $100` does not produce budget spent `200` without conversion.
2. Budget in EUR compares USD expenses after conversion.
3. Rollover status preserves original `Budget.amount`.
4. Editing rollover budget shows base amount, not effective limit.
5. Critical budget is not counted as healthy.
6. Budget summary card and budget card use the same display health.
7. Budget monitor uses adjusted/shared spend if UI does.
8. Notification disabled does not update `lastWarningNotifiedAt`.
9. Budget alert text uses budget currency.
10. Two active overall budgets are rejected or detected.
11. Two active budgets for same category are rejected or detected.
12. Deleting a category with active budget is blocked or leaves budget in `CATEGORY_DELETED`, not overall.
13. Invalid budget thresholds are rejected at repository level.
14. Invalid `periodMode` is rejected.
15. Old daily rollover budget does not run thousands of SQL queries per status refresh.
16. Weekly budget autopilot recommendation is weekly, not monthly.
17. Apply-all autopilot is atomic and reports partial failures.
18. Duplicate category names are rejected/merged.
19. Default categories cannot be deleted through repository path.
20. Single expense category edit does not automatically create a global merchant rule unless user confirms.
21. Bulk category update is transactional.
22. Category can be cleared or explicitly set to Uncategorized.
23. Multiple merchant mappings for same canonical name resolve deterministically.
24. Forecast accuracy update persists actual spending and accuracy.
25. `getForecastForDate()` treats `targetPeriodEnd` as exclusive.

---

# Top three fixes

If you only fix three things first:

1. **Add budget currency and stop raw mixed-currency sums.**
2. **Separate base budget amount from rollover effective limit.**
3. **Make alerts, summary, and cards use the same canonical adjusted budget status.**

Those remove the biggest financial correctness risks.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `Budget.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt

- `BudgetDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetDao.kt

- `BudgetRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

- `BudgetCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt

- `BudgetModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetModels.kt

- `BudgetMonitor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt

- `BudgetViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt

- `BudgetScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt

- `BudgetForecast.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt

- `BudgetForecastDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt

- `BudgetForecastingEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt

- `BudgetAutopilotEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt

- `BudgetRecommendationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngine.kt

- `BudgetRecommendationInputs.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationInputs.kt

- `BudgetHistorySeriesBuilder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetHistorySeriesBuilder.kt

- `SharedBudgetManager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt

- `Category.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Category.kt

- `CategoryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/CategoryDao.kt

- `CategoryRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt

- `MerchantCategory.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCategory.kt

- `MerchantCategoryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantCategoryDao.kt

- `MerchantCategoryRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantCategoryRepository.kt

- `CategorizationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt

- `HybridExpenseClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `ExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `NotificationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/service/NotificationService.kt

- `AndroidNotificationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt

- `CalculateBudgetStatusUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/budget/CalculateBudgetStatusUseCase.kt