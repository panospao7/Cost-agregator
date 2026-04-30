# Recurring / Subscriptions / Bill Reminders Deep Analysis

Branch: `master-refactor`

Scope reviewed:

- recurring expense detection
- manual recurring expenses
- planned expenses
- subscription analysis
- bill reminders
- forecast recurring-pattern merge path

## Executive verdict

This area has useful building blocks, but it is not yet financially safe as a unified recurring/bills/subscriptions system.

The main problem is conceptual:

> The code stores “recurring rules”, “detected recurring patterns”, “planned expenses”, “subscriptions”, and “bill reminders” as related but separate concepts, without a strict lifecycle tying them together.

The highest-risk bugs are:

1. reminders can fire repeatedly with no sent/dismissed state
2. marking a bill paid does not create or link an actual payment expense
3. overdue recurring items only advance by one interval
4. subscriptions raw-sum amounts without considering frequency
5. planned expenses lack currency/source/paid state
6. recurring detection misses annual/semiannual subscriptions
7. manual and detected recurring patterns can duplicate in forecast paths
8. subscription price changes are recorded but may not update current subscription amount

---

# Architecture observed

## Core entities

### `ManualRecurringExpense`

Used for:

- manual recurring bills
- subscriptions

Important fields:

- `merchant`
- `amount`
- `currency`
- `frequency`
- `nextDate`
- `isSubscription`
- `subscriptionCategory`
- `usageTargetPerMonth`
- `cancellationUrl`
- `isActive`

Missing important lifecycle fields:

- `lastPaidDate`
- `lastGeneratedExpenseId`
- `lastReminderSentAt`
- `paidThroughDate`
- `anchorDayOfMonth`
- `endDate`
- `categoryId`
- `source`
- `accountId`
- `autoCreateExpense`
- `autoReminderEnabled`

### `PlannedExpense`

Fields:

- `description`
- `amount`
- `date`
- `categoryId`
- `isRecurring`
- `priority`

Missing important fields:

- `currency`
- source recurring rule id
- source subscription id
- paid/resolved state
- generated instance key
- linked actual expense id
- recurrence occurrence date
- skipped/dismissed state

This is a big issue because forecasts and future bills depend on planned expenses being financially meaningful.

---

# Critical / high-priority findings

## 1. Bill reminders can repeatedly notify forever

### Where

`BillReminderManager.getNotificationsDue()`

### Problem

A notification is due whenever the bill is urgent/critical/warning according to date. There is no persisted notification state, such as:

- last reminder sent date
- reminder stage already sent
- dismissed until
- snoozed until
- notification id
- recurrence occurrence id

For overdue bills, `CRITICAL` always returns true.

### Impact

If a worker calls `getNotificationsDue()` daily or multiple times per day, the same overdue bill can notify repeatedly forever.

Example:

- Rent due April 1.
- User does not mark paid.
- On April 2, 3, 4, 5, etc., it remains `CRITICAL`.
- Nothing prevents duplicate notifications.

### Severity

**Critical**

### Fix

Introduce a persisted reminder occurrence table:

```kotlin
RecurringReminderOccurrence(
    recurringExpenseId,
    dueDate,
    stage,
    lastSentAt,
    dismissedAt,
    snoozedUntil,
    notificationId
)
```

Use uniqueness:

```text
recurringExpenseId + dueDate + stage
```

Do not notify again for the same stage unless explicitly snoozed/re-enabled.

---

## 2. `markBillPaid()` does not create or link an actual payment

### Where

`BillReminderManager.markBillPaid()`

### Problem

Marking paid only advances `nextDate`.

It does not:

- create an `Expense`
- link to an existing expense
- mark a planned expense as resolved
- store payment date
- store paid amount
- store paid occurrence
- record whether payment was skipped

### Impact

The app can say a bill is paid, but dashboard/history may have no real expense corresponding to the payment.

This breaks:

- audit trail
- budget accuracy
- cash-flow history
- subscription usage of real transactions
- dedupe between bank notification and bill payment

### Severity

**Critical**

### Fix

Payment should be modeled as an occurrence:

```kotlin
RecurringOccurrence(
    recurringExpenseId,
    dueDate,
    status = PAID / SKIPPED / MISSED,
    linkedExpenseId,
    paidAt,
    paidAmount,
    paidCurrency
)
```

`markBillPaid()` should either:

1. link to an existing expense, or
2. create a real expense, or
3. mark occurrence as manually paid without expense but with explicit audit state.

---

## 3. Overdue recurring bills advance by only one interval

### Where

`BillReminderManager.markBillPaid()`

Current behavior:

```text
nextDate = calculateNextDate(expense.nextDate, expense.frequency)
```

### Problem

If a bill is many months overdue, marking it paid only advances one period.

Example:

- Monthly bill nextDate = January 1.
- User opens app in April.
- Marks paid once.
- nextDate becomes February 1, still overdue.
- Reminder remains critical.

### Impact

Users may need to tap “paid” repeatedly to catch up.

### Severity

**High**

### Fix

Decide semantics:

Option A — “Pay current overdue occurrence only”  
Advance one interval and keep next missed occurrence visible.

Option B — “Paid through today”  
Loop until `nextDate > today`.

Option C — ask user:
- “Mark one missed bill paid”
- “Mark all past occurrences paid/skipped”

At minimum, avoid surprise by making the behavior explicit.

---

## 4. Irregular recurring items can get stuck forever

### Where

`RecurrenceCalculator.calculateNextDate()`

For `IRREGULAR`, next date remains unchanged.

### Problem

If an irregular recurring item is active and due, `markBillPaid()` will not advance it.

### Impact

It can remain permanently overdue and notify forever.

### Severity

**High**

### Fix

For irregular bills:

- require user to choose next date after marking paid, or
- deactivate after payment, or
- do not allow reminders without explicit next date.

---

## 5. Subscription monthly totals ignore frequency

### Where

`SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()`

### Problem

It sums `analysis.currentPrice`, which is just `ManualRecurringExpense.amount`.

But amount appears to be per recurrence frequency.

So:

- monthly subscription: `10` means 10/month
- annual subscription: `120` means 120/year
- quarterly subscription: `30` means 30/quarter

The method treats all as monthly.

### Impact

Annual subscriptions are massively over-counted in monthly totals.

Example:

- Spotify monthly €10
- Annual VPN €120/year

Expected monthly cost:

```text
10 + 10 = 20
```

Current likely total:

```text
10 + 120 = 130
```

### Severity

**Critical**

### Fix

Use:

```kotlin
RecurrenceCalculator.toMonthlyAmount(subscription.amount, subscription.frequency)
```

for monthly subscription cost and savings estimates.

---

## 6. Subscription cost-per-use uses raw amount, not monthly equivalent

### Where

`SubscriptionManagerEngine.calculateUsageStats()`

### Problem

Cost per use is:

```text
subscription.amount / averageUsesPerMonth
```

But if the subscription is annual, quarterly, or weekly, the numerator is not monthly cost.

### Impact

Annual subscriptions look far more expensive per use than they are.

Example:

- Annual subscription = €120/year
- average 10 uses/month

Expected:

```text
€10 monthly equivalent / 10 = €1/use
```

Current:

```text
€120 / 10 = €12/use
```

### Severity

**High**

### Fix

Use monthly equivalent amount for usage analytics.

---

## 7. Subscription price changes are recorded but may not update current price

### Where

`SubscriptionManagerEngine.recordPriceChange()`

### Problem

The method inserts a `SubscriptionPriceHistory` row but does not update `ManualRecurringExpense.amount`.

`SubscriptionAnalysis.currentPrice` is still `subscription.amount`.

### Impact

The subscription can have price history saying the price changed, while the active subscription still reports the old current price.

### Severity

**High**

### Fix

Recording a price change should be transactional:

1. read current subscription
2. insert price history row
3. update `ManualRecurringExpense.amount`
4. update currency if relevant
5. optionally store previous amount in price-history row

---

## 8. First subscription price change may not produce a visible `PriceChange`

### Where

`SubscriptionManagerEngine.getPriceHistory()`

### Problem

`getPriceHistory()` builds changes from adjacent history rows:

```text
history[i - 1] -> history[i]
```

If only one price-history row exists, no `PriceChange` is produced.

But `recordPriceChange()` inserts only the new price, not an old-price baseline.

### Impact

The first price increase can be invisible in analysis/recommendations.

### Severity

**High**

### Fix

When creating a subscription, insert initial baseline price history.

When recording a price change, store:

- old amount
- new amount
- old currency
- new currency
- effective date

or ensure adjacent-history comparison always has a baseline.

---

## 9. Planned expenses are not safe for recurring forecasts

### Where

`PlannedExpense.kt`
`PlannedExpenseRepository.kt`
`PlannedExpenseDao.kt`

### Problems

`PlannedExpense` has:

- no currency
- no paid/resolved status
- no source recurring rule id
- no linked actual expense id
- no generated occurrence key
- no uniqueness for generated recurring instances

### Impact

Future projections can double-count or become impossible to reconcile.

Example:

- Recurring rule generates a planned rent item.
- Bank notification creates actual rent expense.
- Planned rent remains in future/planned list.
- Forecast may count both.

### Severity

**Critical for forecasts/cash flow**

### Fix

Add lifecycle fields:

```kotlin
currency
sourceType
sourceId
occurrenceDate
status = PLANNED / PAID / SKIPPED / CANCELLED / MATCHED
linkedExpenseId
generatedKey
```

Add unique index:

```text
sourceType + sourceId + occurrenceDate
```

---

## 10. Recurring detection misses annual/semiannual patterns

### Where

`RecurringExpenseEngine.getPatterns()`
`RecurringExpenseEngine.detectPatternsFromSnapshots()`

### Problem

Detection only looks at the last 12 months and requires at least 3 occurrences.

That makes annual subscriptions nearly impossible to detect:

- annual bill has 1 occurrence in 12 months
- semiannual bill has 2 occurrences in 12 months
- both fail the 3-occurrence requirement

Also, stale check drops patterns whose last occurrence is more than six months old. That can wrongly drop annual bills.

### Impact

Annual insurance, subscriptions, tax payments, licenses, domain renewals, etc. may not be detected.

### Severity

**High**

### Fix

Use frequency-specific history windows and occurrence thresholds:

- weekly/monthly: 3+ occurrences in 12 months
- quarterly: 3+ occurrences in 18 months
- semiannual: 2+ occurrences in 24 months
- annual: 2+ occurrences in 36 months

Use frequency-aware staleness:

- annual pattern is not stale after 6 months.

---

## 11. Manual and detected recurring patterns can duplicate in forecast merge path

### Where

`RecurringExpenseEngine.getPatternsFromSnapshots()`
`MergedRecurringPatternsProvider.getPatternsFromSnapshots()`

### Observation

`RecurringExpenseEngine.getPatternsFromSnapshots()` excludes manually-entered merchants from detection.

But `MergedRecurringPatternsProvider.getPatternsFromSnapshots()` calls detection with `excludedMerchantKeys = emptySet()`, then merges manual and detected patterns later.

This may be okay if `ForecastInputAssembler.mergeRecurringPatterns()` dedupes perfectly. But it means there are two different merge policies:

1. engine-level exclusion
2. forecast-assembler merge/dedupe

### Impact

If assembler dedupe is incomplete, forecasts can include the same bill twice:

- manual Netflix
- detected Netflix from transactions

### Severity

**High**

### Fix

Use one canonical merge path everywhere.

Recommended:

```text
manual patterns first
detected patterns excluding manual merchant/date/currency keys
then final dedupe by merchantKey + amount bucket + frequency
```

Also expose diagnostics when a detected pattern is suppressed by a manual override.

---

## 12. Recurring detection uses first currency/category in group

### Where

`RecurringExpenseEngine.detectPatternsFromSnapshots()`

### Problem

For a detected merchant group, the output currency and category are taken from the first sorted expense.

If the same merchant has multiple currencies or categories, the pattern inherits whichever happens to come first.

### Impact

- USD subscription can be labeled EUR.
- Category can be stale or wrong.
- Forecast and dashboard can compare wrong currency.

### Severity

**High with multi-currency**

### Fix

Group by:

```text
merchantKey + currency + maybe category/domain subscription class
```

or normalize to base currency before amount stability checks.

---

## 13. Bill reminder monthly total raw-sums currencies

### Where

`BillReminderManager.getMonthlyBillsTotal()`

### Problem

It sums monthly equivalents across all recurring expenses without conversion.

### Impact

€20 + $20 becomes `40`.

### Severity

**High / Critical if multi-currency is user-facing**

### Fix

Return currency buckets or converted money totals.

---

## 14. Manual recurring expenses lack category

### Where

`ManualRecurringExpense.kt`
`RecurringExpenseRepository.createRecurringExpenseEntity()`

### Problem

Manual recurring expenses do not store `categoryId`.

Detected patterns can derive category from transactions, but manual ones cannot.

### Impact

Budget/forecast category allocation is weaker for manual bills.

Example:

- Manual “Rent” recurring expense exists.
- It has no category.
- Housing budget forecast cannot include it reliably.

### Severity

**Medium / High**

### Fix

Add nullable `categoryId` to `ManualRecurringExpense`.

---

## 15. `getByMerchant()` is exact-match and can collide/fragment

### Where

`RecurringExpenseRepository.getByMerchant()`
`ManualRecurringExpenseDao.getByMerchant()`

### Problem

Lookup is by exact merchant string.

### Impact

These can become separate records:

- `Netflix`
- `NETFLIX.COM`
- `Netflix Europe`
- `Netflix *1234`

Also exact merchant alone is insufficient if there are two recurring charges from the same merchant with different amounts.

### Severity

**Medium / High**

### Fix

Use canonical merchant key plus amount/frequency.

Add:

```text
merchantKey
amountBucket
currency
frequency
```

---

## 16. No generated-instance uniqueness for recurring planned expenses

### Where

Planned expense model/repository

### Problem

The planned expense table has no source occurrence key.

### Impact

If a worker or forecast path generates planned expenses repeatedly, duplicates are possible.

### Severity

**High if generation exists or is planned**

### Fix

Add unique generated key:

```text
sourceType + sourceId + occurrenceDate
```

Use `INSERT OR IGNORE` / upsert semantics.

---

## 17. Date windows use rolling 30-day months in subscription usage

### Where

`SubscriptionManagerEngine.calculateUsageStats()`

### Problem

“This month” is implemented as last 30 days, not calendar month.

### Impact

Usage stats can disagree with dashboard calendar periods.

This is not always wrong, but naming should be clear.

### Severity

**Medium**

### Fix

Either rename to rolling 30-day usage, or use calendar month boundaries.

---

## 18. Subscription recommendations hardcode euro in user-facing text

### Where

`SubscriptionManagerEngine.generateRecommendations()`

### Problem

Descriptions use `€` directly.

### Impact

USD/GBP/etc. subscriptions show misleading currency.

### Severity

**Medium**

### Fix

Use the subscription currency and app money formatter.

---

## 19. Subscription recommendation savings can double-count

### Where

`SubscriptionManagerEngine.calculatePotentialSavings()`

### Problem

It sums all recommendation savings.

A single subscription may have multiple recommendations:

- underutilized
- unused
- high cost per use

Summing all can overstate potential savings.

### Impact

A €10/month subscription might produce €20+ potential savings.

### Severity

**Medium / High UX**

### Fix

Take max actionable savings per subscription, not sum of all recommendations.

---

## 20. Recurring pattern amount stability uses effective amount

### Where

`RecurringExpenseEngine.detectPatternsFromSnapshots()`

### Observation

Detection uses `effectiveAmount`.

This is good for personal spend, but it can be problematic for shared expenses or partially-owned subscriptions.

### Impact

If shared ownership changes over time, a stable gross subscription can look unstable.

### Severity

**Medium**

### Fix

Decide if recurring detection should use:

- gross transaction amount, or
- personal effective amount

For user bills, personal effective amount may be right. For merchant subscription detection, gross may be more stable.

---

# Strong parts

## 1. Purchase-only recurring detection

`RecurringExpenseEngine` filters to purchase transactions, avoiding deposits/transfers.

Good.

## 2. Manual recurring overrides exist

Manual recurring entries are treated as confidence `1.0`.

Good UX foundation.

## 3. Recurrence logic centralized

`RecurrenceCalculator` centralizes:

- monthly equivalent
- next date
- previous date
- labels
- interval addition

Good.

## 4. Date-only normalization exists

Recurring dates are normalized to day-level semantics.

Good.

## 5. Active-only repository contract

`RecurringExpenseRepository.getAll()` returns active rows only.

Good for reminders and forecast defaults.

## 6. Pattern detection has staleness and amount-variance guards

Good direction, although the thresholds need frequency-specific tuning.

---

# Recommended fix order

## PR 1 — Add recurring occurrence lifecycle

Add:

```kotlin
RecurringOccurrence
```

Fields:

- recurringExpenseId
- dueDate
- status
- linkedExpenseId
- paidAt
- paidAmount
- paidCurrency
- skippedAt
- reminder state

This fixes paid/reminder auditability.

## PR 2 — Make reminders idempotent

Add reminder occurrence tracking:

- sent stages
- snooze
- dismiss
- notification id

No duplicate notifications for the same bill/date/stage.

## PR 3 — Fix monthly subscription math

Use frequency-aware monthly amount for:

- total monthly subscription cost
- cost per use
- potential savings
- health score
- bill monthly total

## PR 4 — Add currency and lifecycle to planned expenses

Add:

- currency
- status
- sourceType/sourceId
- linkedExpenseId
- generatedKey

## PR 5 — Fix price-change transaction

Recording price changes should update the subscription amount and store a baseline.

## PR 6 — Improve recurring detection windows

Use frequency-aware detection:

- annual/semiannual support
- frequency-aware staleness
- merchant+currency grouping

## PR 7 — Unify manual/detected recurring merge

One canonical dedupe path for dashboard/forecast/reminders.

## PR 8 — Add category to manual recurring expenses

Manual bills need budget category participation.

---

# Regression tests to add

1. Overdue monthly bill marked paid from January in April does not remain unexpectedly critical unless one-occurrence mode is selected.
2. Irregular bill cannot be marked paid without a new due date.
3. Same overdue bill does not notify twice for same stage/date.
4. Marking bill paid creates or links a payment occurrence.
5. Annual subscription €120/year contributes €10/month.
6. Subscription cost-per-use uses monthly equivalent.
7. First price change appears in price-change analysis.
8. Price change updates current subscription amount.
9. Planned expense generated from recurring rule has unique occurrence key.
10. Actual bank expense matching planned bill marks planned occurrence paid.
11. Annual recurring pattern can be detected from two yearly transactions.
12. Semiannual recurring pattern can be detected from two transactions.
13. Manual Netflix and detected Netflix do not both appear in forecast.
14. Mixed-currency recurring merchant does not inherit first row currency blindly.
15. Subscription recommendation text uses actual currency.
16. Potential savings uses max per subscription, not sum of overlapping recommendations.
17. Manual recurring expense participates in category budget if category is set.

---

# Top three fixes

If you only fix three things first:

1. **Make reminders/payment occurrences idempotent and persistent.**
2. **Fix frequency-aware subscription/bill monthly math.**
3. **Add lifecycle/source/currency fields to planned expenses.**

Those three remove the biggest financial correctness risks.

---

# Sources reviewed

- `BillReminderManager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt

- `ManualRecurringExpense.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt

- `PlannedExpense.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt

- `RecurringExpenseRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt

- `PlannedExpenseRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/PlannedExpenseRepository.kt

- `PlannedExpenseDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt

- `ManualRecurringExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt

- `RecurrenceCalculator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt

- `RecurringExpenseEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt

- `SubscriptionManagerEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt

- `SubscriptionManagementRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/SubscriptionManagementRepository.kt

- `MergedRecurringPatternsProvider.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/MergedRecurringPatternsProvider.kt

- `ForecastInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt