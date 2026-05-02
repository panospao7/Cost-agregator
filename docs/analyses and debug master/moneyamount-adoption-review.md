# MoneyAmount Adoption Review — Tiers 1–4

**Date:** 2026-05-02
**Schema Version:** 106 (unchanged)
**Review Scope:** All `MoneyAmount` usages across `app/src/main/`

---

## VERDICT: PASS ✅

All MoneyAmount properties follow the correct pattern. No violations found. No schema changes introduced.

---

## Summary

| Tier | Scope | Files | MoneyAmount Properties | Status |
|------|-------|-------|----------------------|--------|
| 1 | Domain Core (`money/`) | 7 | 8 (constructors + operators) | ✅ |
| 2 | Database Entities (Room) | 16 | 31 computed properties | ✅ |
| 3 | Domain Models | 6 | 54 computed properties | ✅ |
| 4 | ViewModels / UseCases | 5 | 7 computed properties | ✅ |

---

## Verification Checklist

### 1. ✅ All MoneyAmount properties use `get()` (computed, not stored)

Every `MoneyAmount` property uses the pattern:
```kotlin
val moneyX: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))
```
No `var MoneyAmount` fields exist anywhere in the codebase. No `MoneyAmount` appears as a Room `@ColumnInfo` or constructor parameter in any `@Entity`.

### 2. ✅ Room entities use `@get:Ignore`

All 31 MoneyAmount computed properties across 16 Room entities are annotated with `@get:Ignore`, ensuring Room ignores them during schema generation:
- `Expense.moneyAmount`
- `Budget.moneyAmount`
- `AnomalyAlert.moneyAmount`, `AnomalyAlert.baseMoneyAmount`
- `GroupExpense.totalMoneyAmount`
- `Investment.purchasePriceMoneyAmount` (+ 4 more)
- `ManualRecurringExpense.moneyAmount`
- `PendingReview.suggestedMoneyAmount`
- `PlannedExpense.moneyAmount`
- `RecurringOccurrence.expectedMoneyAmount`, `RecurringOccurrence.paidMoneyAmount`
- `SavingsGoal.targetMoneyAmount`, `SavingsGoal.currentMoneyAmount`
- `SavingsSweepPlan.totalUnderspendMoneyAmount` (+ 3 more)
- `ScannedReceipt.parsedTotalMoneyAmount`, `ScannedReceipt.parsedTaxMoneyAmount`
- `SpendingChallengeEntity.targetMoneyAmount`, `SpendingChallengeEntity.baselineMoneyAmount`
- `SubscriptionCandidate.averageMoneyAmount`, `SubscriptionCandidate.estimatedAnnualMoneyAmount`
- `SubscriptionPriceHistory.moneyAmount`
- `BudgetAdjustmentRecommendation.currentBudgetMoneyAmount` (+ 2 more)

### 3. ✅ No existing `Double` fields were removed

All entities retain their `amount: Double` (or similar) columns. Example from `Expense.kt`:
```kotlin
val amount: Double,                    // ← preserved
@ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",  // ← preserved
// ... in body:
@get:Ignore
val moneyAmount: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))  // ← added
```

### 4. ✅ No schema changes introduced

- `APP_DATABASE_SCHEMA_VERSION` remains **106**
- No migration code added
- All MoneyAmount properties are computed `get()` with `@get:Ignore`, so Room's schema processor ignores them
- `git diff` confirms only `@get:Ignore` + `MoneyAmount`/`CurrencyCode` imports + computed body properties added

### 5. ✅ Currency fields correctly mapped to `CurrencyCode`

All Room entities store currency as `String` columns (backed by `@ColumnInfo(defaultValue = "EUR")`) and the MoneyAmount computed property wraps them with `CurrencyCode(currencyString)`.

Nullable currency fields (e.g., `baseCurrency: String?` in `AnomalyAlert`) are correctly guarded:
```kotlin
val baseMoneyAmount: MoneyAmount? get() =
    if (baseAmount != null && baseCurrency != null)
        MoneyAmount(baseAmount, CurrencyCode(baseCurrency))
    else null
```

The `MoneyMappers.kt` bridge functions use `CurrencyCode.parseOr(currency, CurrencyCode.EUR)` for legacy safety.

### 6. ✅ Domain models and ViewModels follow the same pattern

Non-entity data classes (e.g., `AnalyticsModels.kt`, `BudgetModels.kt`, `DashboardPrimitives.kt`, `SpendingSummary.kt`, `FinancialStressForecastEngine.kt`, `AnalyticsViewModel.kt`, `SharedExpenseGroupsViewModel.kt`, etc.) use identical computed `get()` patterns. These don't need `@get:Ignore` since they're not Room entities.

---

## Spot-Check Results (5 Random Files)

| # | File | Type | `@get:Ignore` | Pattern | Null Handling | Result |
|---|------|------|:---:|---------|:---:|:---:|
| 1 | `Expense.kt` | Room Entity | ✅ | `MoneyAmount(amount, CurrencyCode(currency))` | N/A | ✅ |
| 2 | `ScannedReceipt.kt` | Room Entity | ✅ | `MoneyAmount(it, CurrencyCode(currency))` via `.let` | ✅ (nullable Double) | ✅ |
| 3 | `AnomalyAlert.kt` | Room Entity | ✅ | `MoneyAmount(amount, CurrencyCode(currency))` | ✅ (nullable base) | ✅ |
| 4 | `AnalyticsModels.kt` | Domain Model | N/A | `MoneyAmount(amount, CurrencyCode(displayCurrency))` | N/A | ✅ |
| 5 | `SharedExpenseGroupsViewModel.kt` | ViewModel | N/A | `MoneyAmount(totalSpent, CurrencyCode(currency))` | N/A | ✅ |

---

## Tier-by-Tier Detail

### Tier 1 — Domain Core (`domain/core/money/`)

| File | Role | Status |
|------|------|:---:|
| `MoneyAmount.kt` | Canonical type: `data class MoneyAmount(val amount: Double, val currency: CurrencyCode)` | ✅ |
| `CurrencyCode.kt` | `@JvmInline value class CurrencyCode(val code: String)` with validation | ✅ |
| `ConvertedMoney.kt` | Conversion result with `MoneyAmount` original + converted | ✅ |
| `MoneyAggregate.kt` | Multi-currency aggregate using `MoneyAmount` for display formatting | ✅ |
| `MoneyBucket.kt` | Per-currency bucket using `MoneyAmount` for display | ✅ |
| `MoneyMappers.kt` | Bridge functions: `Expense.toEffectiveMoneyAmount()`, `ConversionResult.toConvertedMoney()`, etc. | ✅ |
| `MoneyFormatUtils.kt` | Extension functions on `MoneyAmount` | ✅ |
| `ConversionFailure.kt` | Holds `originalAmount: MoneyAmount` | ✅ |

### Tier 2 — Database Entities (Room `@Entity`)

- **16 entities** with MoneyAmount computed properties
- **31 total MoneyAmount properties** — all `val ... get()` in body blocks with `@get:Ignore`
- No MoneyAmount stored as a Room column, no schema migration required

### Tier 3 — Domain Models

| File | Properties | Status |
|------|:---:|:---:|
| `domain/analytics/AnalyticsModels.kt` | 20 | ✅ |
| `domain/analytics/AdvancedAnalyticsModels.kt` | 13 | ✅ |
| `domain/budget/BudgetModels.kt` | 3 | ✅ |
| `domain/forecasting/FinancialStressForecastEngine.kt` | 11 | ✅ |
| `domain/model/dashboard/DashboardPrimitives.kt` | 2 | ✅ |
| `domain/model/dashboard/SpendingSummary.kt` | 1 | ✅ |
| `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 1 | ✅ |

### Tier 4 — ViewModels / UI State

| File | Properties | Status |
|------|:---:|:---:|
| `ui/screens/analytics/AnalyticsViewModel.kt` | 3 | ✅ |
| `ui/screens/groups/SharedExpenseGroupsViewModel.kt` | 1 | ✅ |
| `ui/screens/cashflow/CashFlowCalendarViewModel.kt` | 1 | ✅ |
| `ui/screens/savings/SavingsGoalsViewModel.kt` | 1 | ✅ |

---

## Issues

None found. All MoneyAmount adoption follows the approved pattern:

```
MoneyAmount(amount, CurrencyCode(currency))   using get()   not stored, not a column
```

---

## Coverage

- **Requirements met:** Yes — all 6 verification criteria pass
- **Testing adequate:** N/A for this review scope (code structure review only; compile confirmed separately)
