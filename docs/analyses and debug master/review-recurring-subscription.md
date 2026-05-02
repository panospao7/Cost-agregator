# Recurring / Subscriptions / Bill Reminders — Cross-Check Review

**Date:** 2026-05-02
**Analysis reviewed:** `docs/analyses and debug master/recurring-subscription-reminder-analysis.md`
**Current codebase:** `app/src/main/java/com/yourname/expensetracker`

---

## Executive Summary

Since the analysis was written, significant architectural progress has been made. A **Recurring Lifecycle subsystem** (`RecurringOccurrence`, `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer`, `RecurringReminderDelivery`) has been built that addresses the most critical issues (occurrence lifecycle, idempotent reminders, planned‑expense uniqueness). However, the **legacy code paths** (`BillReminderManager.markBillPaid()`, `BillReminderManager.getNotificationsDue()`) have NOT been retired or updated to use the new subsystem, so several original bugs remain reachable.

Of the 20 issues in the original analysis:
- **6 are RESOLVED** (Issues #5, #6, #9, #11, #16, #17)
- **3 are PARTIALLY RESOLVED** (Issues #1, #2, #13)
- **11 are STILL PRESENT** (Issues #3, #4, #7, #8, #10, #12, #14, #15, #18, #19, #20)

---

## Detailed Per-Issue Verdict

### [ISSUE-1] Bill reminders can repeatedly notify forever

**Original severity:** Critical
**Current verdict:** **PARTIALLY RESOLVED**

**What changed:**
- `RecurringReminderDelivery` entity now exists with fields: `status` (SCHEDULED/SENT/DISMISSED/SNOOZED/FAILED), `lastSentAt`, `dismissedAt`, `snoozedUntil`, `notificationId`.
- Unique index on `(occurrenceId, reminderWindow)` prevents duplicate deliveries.
- `RecurringLifecycleCoordinator.getDueReminders()` queries SCHEDULED deliveries.
- `RecurringLifecycleCoordinator.markReminderSent()` marks deliveries as SENT.
- `RecurringOccurrenceMaterializer.materialize()` only creates deliveries when none exist for that occurrence+window.

**What remains:**
- The legacy `BillReminderManager.getNotificationsDue()` path (lines 102-113) is still active for "backward compatibility" (per docstring at lines 34-46). It has NO persistence of notification state — it just returns reminders by urgency, and nothing prevents repeated notifications.
- If any code calls `getNotificationsDue()` (e.g., old workers, direct UI calls), the same overdue bill could still notify repeatedly.

**Suggested fix:** Either retire `getNotificationsDue()` entirely after a `ReminderDispatchWorker` is in place, or gate it behind a check against `RecurringReminderDelivery`.

---

### [ISSUE-2] `markBillPaid()` does not create or link an actual payment

**Original severity:** Critical
**Current verdict:** **PARTIALLY RESOLVED**

**What changed:**
- `RecurringLifecycleCoordinator.linkExpenseToOccurrence()` (lines 115-172) can link an existing expense to a PLANNED occurrence, marking it PAID with full audit data (paidAmount, paidCurrency, paidAt).
- `RecurringLifecycleCoordinator.updateOccurrenceStatus()` can mark occurrences as SKIPPED/MISSED/CANCELLED.
- `RecurringOccurrence` entity tracks `linkedExpenseId`, `paidAt`, `paidAmount`, `paidCurrency`.

**What remains:**
- `BillReminderManager.markBillPaid()` (lines 118-127) STILL only advances `nextDate` by one interval. It does NOT create an expense, link to one, or mark any occurrence as PAID.
- Any caller still using `markBillPaid()` gets the original broken behavior.

**Suggested fix:** `markBillPaid()` should delegate to `RecurringLifecycleCoordinator`: generate occurrences, find the PLANNED occurrence matching the current due date, and either link to an existing expense or mark it manually PAID (with an `linkedExpenseId = null` variant). Then advance `nextDate` only after confirming the occurrence transition.

---

### [ISSUE-3] Overdue recurring bills advance by only one interval

**Original severity:** High
**Current verdict:** **STILL PRESENT**

**Current code** (`BillReminderManager.kt` line 121):
```kotlin
val nextDate = RecurrenceCalculator.calculateNextDate(expense.nextDate, expense.frequency)
```
No change. If a monthly bill is 4 months overdue, marking it paid once advances `nextDate` by only 1 month, leaving it 3 months overdue.

**Suggested fix:** In `RecurringLifecycleCoordinator`, implement "pay through today" semantic: after marking an occurrence paid, loop `nextDate` forward until `nextDate > today`. The expander's `advanceDate()` can be reused.

---

### [ISSUE-4] Irregular recurring items can get stuck forever

**Original severity:** High
**Current verdict:** **STILL PRESENT**

**Current code** (`RecurrenceCalculator.kt` line 74):
```kotlin
RecurrenceFrequency.IRREGULAR -> normalizedCurrentDate
```
In the legacy `markBillPaid()` path, an irregular bill remains stuck.  
The new `RecurringOccurrenceExpander.expand()` correctly returns empty for IRREGULAR, but the old path is unchanged.

**Suggested fix:** For irregular, `markBillPaid()` should either require explicit user input for the new `nextDate` or deactivate the rule after payment.

---

### [ISSUE-5] Subscription monthly totals ignore frequency

**Original severity:** Critical
**Current verdict:** **RESOLVED**

`SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()` (lines 392-402) now calls `RecurrenceCalculator.toMonthlyAmount()`:
```kotlin
total += RecurrenceCalculator.toMonthlyAmount(
    analysis.subscription.amount,
    analysis.subscription.frequency
)
```
Annual €120 now correctly contributes €10/month; quarterly €30 contributes €10/month.

---

### [ISSUE-6] Subscription cost-per-use uses raw amount

**Original severity:** High
**Current verdict:** **RESOLVED**

`SubscriptionManagerEngine.calculateUsageStats()` (lines 255-261) now normalizes to monthly:
```kotlin
val monthlyAmount = RecurrenceCalculator.toMonthlyAmount(subscription.amount, subscription.frequency)
monthlyAmount / averageUsesPerMonth
```
Annual subscription cost-per-use is correctly calculated against its monthly equivalent.

---

### [ISSUE-7] Subscription price changes recorded but may not update current price

**Original severity:** High
**Current verdict:** **STILL PRESENT**

`SubscriptionManagerEngine.recordPriceChange()` (lines 166-185) inserts a `SubscriptionPriceHistory` row but does NOT update `ManualRecurringExpense.amount`.  
`SubscriptionAnalysis.currentPrice` (line 137) returns `subscription.amount` — the original value.  
So a subscription whose price increased to €15 will still show `currentPrice = 10` in analysis.

**Suggested fix:** Make `recordPriceChange()` transactional: insert price history, then update `ManualRecurringExpense.amount` (and optionally currency) via the DAO. Also store `oldAmount` in the `SubscriptionPriceHistory` row for audit.

---

### [ISSUE-8] First subscription price change may not produce a visible `PriceChange`

**Original severity:** High
**Current verdict:** **STILL PRESENT**

`getPriceHistory()` (lines 190-211) builds `PriceChange` items from adjacent `history[i-1] → history[i]`. With only 1 row, no `PriceChange` is produced.  
`recordPriceChange()` still only inserts the NEW price with no baseline. The `SubscriptionPriceHistory` entity has no `oldAmount` field.

**Suggested fix:** Insert an initial baseline price-history row when a subscription is created. Or store `previousAmount` in each `SubscriptionPriceHistory` row so a single row contains both old and new values.

---

### [ISSUE-9] Planned expenses are not safe for recurring forecasts

**Original severity:** Critical
**Current verdict:** **RESOLVED**

The `PlannedExpense` entity (DB) now has all requested lifecycle fields:
- `currency`, `currencyAssumption`
- `sourceOccurrenceKey`, `sourceRecurringRuleId`
- `status` (PLANNED/FULFILLED/SKIPPED/CANCELLED)
- `linkedActualExpenseId`
- `merchantKey`
- `updatedAt`
- `openSourceOccurrenceKey` with a unique index

The DAO supports: `getBySourceOccurrenceKey()`, `updateStatus()`, `linkToActualExpense()`, `refreshOpenOccurrenceKey()`.  
`ForecastInputAssembler.assemble()` cross-deduplicates planned expenses against materialized occurrences.

**Minor gap:** The domain model `PlannedExpense` (in `domain/model/`) does NOT carry `currency` or `status`. The `mapPlannedExpenses()` method in `ForecastInputAssembler` drops these fields. They are not needed for the current forecast path, but any direct user of the domain model loses currency info.

---

### [ISSUE-10] Recurring detection misses annual/semiannual patterns

**Original severity:** High
**Current verdict:** **STILL PRESENT**

`RecurringExpenseEngine.getPatterns()` still uses a 12-month lookback (line 36).  
`detectPatternsFromSnapshots()` still requires ≥3 occurrences (line 106).  
The staleness check at 6 months (line 135-136) can drop annual patterns whose last occurrence was 11 months ago.

The frequency-detection day ranges now include semiannual (136-270) and annual (271-400), which is good, but the upstream filtering prevents detection.

**Suggested fix:** Use frequency-specific windows: 36-month lookback for annual, 24-month for semiannual, with 2-occurrence minimum for annual/semiannual. Use frequency-aware staleness (annual patterns are not stale after 6 months).

---

### [ISSUE-11] Manual and detected recurring patterns can duplicate in forecast merge path

**Original severity:** High
**Current verdict:** **RESOLVED**

`ForecastInputAssembler.mergeRecurringPatterns()` now uses a robust `recurringRuleSignature` (lines 204-211) composed of `merchantKey + frequency + amountMinor + currency` for deduplication. Detected patterns matching any manual signature are excluded. This is a canonical dedupe path shared by the forecast assembler.

(Note: Two code paths still exist — `RecurringExpenseEngine.getPatternsFromSnapshots()` excludes manual merchants, while `MergedRecurringPatternsProvider` uses empty exclusion then relies on assembler dedup. The assembler dedup now handles both paths correctly, so the risk is mitigated.)

---

### [ISSUE-12] Recurring detection uses first currency/category in group

**Original severity:** High with multi-currency
**Current verdict:** **STILL PRESENT**

`detectPatternsFromSnapshots()` line 146: `currency = sorted.first().currency`  
Grouping is by `canonicalMerchantKey` (merchant-only), not merchant+currency. If "Amazon" appears with EUR and USD transactions, the pattern inherits whichever currency comes first after sorting.

**Suggested fix:** Group by `canonicalMerchantKey + currency`. Or normalize all amounts to base currency before detection.

---

### [ISSUE-13] Bill reminder monthly total raw-sums currencies

**Original severity:** High / Critical if multi-currency
**Current verdict:** **PARTIALLY RESOLVED**

`BillReminderManager.getMonthlyBillsTotal()` now uses `toMonthlyAmount()` for frequency normalization (good). But it still sums `Double` values across different currencies:
```kotlin
total += RecurrenceCalculator.toMonthlyAmount(expense.amount, expense.frequency)
```
€20 + $20 = 40 — still broken for multi-currency users.

**Suggested fix:** Return a `Map<CurrencyCode, Double>` or convert to home currency before summing.

---

### [ISSUE-14] Manual recurring expenses lack category

**Original severity:** Medium/High
**Current verdict:** **STILL PRESENT**

`ManualRecurringExpense` still has no `categoryId` field.  
`RecurringLifecycleCoordinator.generateOccurrences()` passes `categoryId = null` with a comment: "ManualRecurringExpense does not carry a categoryId" (line 86).  
`ForecastInputAssembler.mapConfirmedRecurringPatterns()` also produces patterns without categoryId.

**Suggested fix:** Add nullable `categoryId` to `ManualRecurringExpense` and propagate through all mapping paths.

---

### [ISSUE-15] `getByMerchant()` is exact-match and can collide/fragment

**Original severity:** Medium/High
**Current verdict:** **STILL PRESENT**

`ManualRecurringExpenseDao.getByMerchant()` still uses:
```sql
WHERE merchant = :merchant LIMIT 1
```
Exact string match. "Netflix" and "NETFLIX.COM" become separate records.

**Suggested fix:** Use `MerchantKeyGenerator` lookup or add a `canonicalMerchantKey` column. Also include amount/frequency in the uniqueness criteria to distinguish two different recurring charges from the same merchant.

---

### [ISSUE-16] No generated-instance uniqueness for recurring planned expenses

**Original severity:** High
**Current verdict:** **RESOLVED**

`PlannedExpense` entity has `sourceOccurrenceKey` with materialized `openSourceOccurrenceKey` (unique index).  
DAO uses `OnConflictStrategy.IGNORE` on insert.  
`refreshOpenOccurrenceKey()` maintains the materialized invariant.  
`ForecastInputAssembler.assemble()` cross-deduplicates against `RecurringOccurrence` keys.

---

### [ISSUE-17] Date windows use rolling 30-day months in subscription usage

**Original severity:** Medium
**Current verdict:** **RESOLVED**

`calculateUsageStats()` now uses `TimePeriodUtils.addMonths(now, -1)` for calendar-month boundaries instead of raw-day arithmetic.

---

### [ISSUE-18] Subscription recommendations hardcode euro in user-facing text

**Original severity:** Medium
**Current verdict:** **STILL PRESENT**

`generateRecommendations()` still uses literal `€` in format strings at lines 299, 326, 339:
```kotlin
"€${String.format("%.2f", increase.oldAmount)}"
```
Multi-currency users see wrong currency symbols.

**Suggested fix:** Use `subscription.currency` and a proper `MoneyFormatter` to produce currency-aware strings.

---

### [ISSUE-19] Subscription recommendation savings can double-count

**Original severity:** Medium/High UX
**Current verdict:** **STILL PRESENT**

`calculatePotentialSavings()` (lines 424-433) sums ALL recommendation `potentialSavings` across all recommendations:
```kotlin
for (rec in analysis.recommendations) {
    potentialSavings += rec.potentialSavings
}
```
A single subscription with 3 recommendations (underutilized + unused + high-cost) can produce triple-counted savings.

**Suggested fix:** Take `max` per subscription, not sum across overlapping recommendations.

---

### [ISSUE-20] Recurring pattern amount stability uses effective amount

**Original severity:** Medium
**Current verdict:** **STILL PRESENT**

`detectPatternsFromSnapshots()` line 112 still uses `it.effectiveAmount`. This is a design choice — the analysis noted it's acceptable for personal spend but can be problematic for shared expenses. No change here, which is fine.

---

## New Issues Discovered During Review

### [ISSUE-21] [MAJOR] Domain model `PlannedExpense` drops `currency` field

**File:** `app/src/main/java/com/yourname/expensetracker/domain/model/PlannedExpense.kt`

The DB entity has `currency` and `status` fields, but the domain model does not. `ForecastInputAssembler.mapPlannedExpenses()` (lines 100-117) maps entity→domain without currency. Any code consuming the domain `PlannedExpense` cannot determine currency, breaking multi-currency displays and calculations.

**Suggested fix:** Add `currency` and optionally `status` to the domain model.

---

### [ISSUE-22] [MAJOR] Legacy `markBillPaid()` does not update `updatedAt` or persist any timestamp

**File:** `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt` line 123

```kotlin
val updated = expense.copy(nextDate = nextDate)
```
`ManualRecurringExpense` has no `updatedAt` field at all (only `createdAt`). There is no audit trail of when a bill was marked paid in the legacy path.

**Suggested fix:** Add `updatedAt` to `ManualRecurringExpense`. Have `markBillPaid()` set it and also write a `RecurringLifecycleEvent` row.

---

### [ISSUE-23] [MAJOR] `recordPriceChange()` should update the subscription amount but doesn't

**File:** `app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt` lines 166-185

This is the same root cause as Issue #7 but worth calling out explicitly: `recordPriceChange()` inserts a history row but never calls `recurringExpenseRepository.update()` to change the subscription's `amount`. The `SubscriptionAnalysis.currentPrice` remains stale.

---

### [ISSUE-24] [MINOR] Duplicate notification risk if both legacy and new reminder paths execute

**Files:** `BillReminderManager.kt`, `RecurringLifecycleCoordinator.kt`

The `BillReminderManager.getNotificationsDue()` docstring says it "remains active for backward compatibility" while a `ReminderDispatchWorker` using `RecurringLifecycleCoordinator.getDueReminders()` is recommended. If both paths execute (e.g., a legacy worker + a new worker), the user could receive duplicate notifications for the same bill.

**Suggested fix:** Once the `ReminderDispatchWorker` is in place, remove or disable the legacy `getNotificationsDue()` path.

---

### [ISSUE-25] [MINOR] `ForecastInputAssembler.mapPlannedExpenses()` does not set `isRecurring` correctly for occurrence-linked planned expenses

**File:** `ForecastInputAssembler.kt` line 109

The mapping sets `isRecurring = entity.isRecurring`. If a planned expense was generated from a recurring rule via the lifecycle coordinator, `isRecurring` may be `false` on the entity (since it's set by legacy code). This could cause UI misdisplay.

**Suggested fix:** Consider `isRecurring = entity.isRecurring || entity.sourceRecurringRuleId != null`.

---

## Coverage Assessment

- **Requirements met:** Partially. The architectural foundation for occurrence lifecycle, idempotent reminders, and planned-expense uniqueness is solid. However, 11 of 20 original issues remain unaddressed in the active code paths, and the legacy `BillReminderManager` methods have not been updated to use the new subsystem.
- **Testing adequate:** No. The original analysis requested 17 regression tests. None of the source files show test coverage for the recurring lifecycle subsystem or the legacy paths. No test files were observed in the scope reviewed.

---

## Recommended Next Steps

1. **Retire `BillReminderManager.markBillPaid()` and `getNotificationsDue()`** — delegate to `RecurringLifecycleCoordinator`.
2. **Fix `recordPriceChange()` to update `ManualRecurringExpense.amount`** (transactional).
3. **Add baseline price-history row on subscription creation** (fix Issue #8).
4. **Add `categoryId` to `ManualRecurringExpense`** and propagate through all paths.
5. **Add `currency` to domain model `PlannedExpense`**.
6. **Make `getMonthlyBillsTotal()` currency-aware**.
7. **Fix detection windows for annual/semiannual patterns.**
8. **Add regression tests for the 17 scenarios listed in the original analysis.**

---

## Final Verdict

```markdown
VERDICT: FAIL
```

The codebase has progressed significantly — particularly the new `RecurringLifecycleCoordinator` and `RecurringReminderDelivery` subsystems. However, there are **11 unresolved issues** from the original analysis (including 2 Critical-equivalent: `markBillPaid` and price-change atomicity), plus **5 new issues** discovered during review. The legacy `BillReminderManager` code paths remain active and unremediated, which is the most immediate risk.
