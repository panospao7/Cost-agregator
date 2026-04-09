# Deep Analysis — Batch 32: Repositories — Core & AI (@debugger)

## Scope
- data/repository/AiArtifactRepositoryImpl.kt
- data/repository/AiChatRepositoryImpl.kt
- data/repository/AiEngagementRepositoryImpl.kt
- data/repository/AiSettingsRepositoryImpl.kt
- data/repository/AnalyticsRepository.kt
- data/repository/BudgetRepository.kt
- data/repository/BusinessExpenseRepository.kt
- data/repository/CategoryRepository.kt
- data/repository/CurrencyDataRepository.kt
- data/repository/CurrencyRatesRepositoryImpl.kt
- data/repository/CurrencySettingsRepositoryImpl.kt
- data/repository/DashboardContractsAdapter.kt
- data/repository/DashboardRepository.kt
- data/repository/DatabaseBackupRepositoryImpl.kt
- data/repository/ExpenseRepository.kt
- data/repository/ExportDataRepository.kt
- data/repository/FinancialWeatherRepository.kt
- data/repository/GroupsRepository.kt
- data/repository/GroupsRepositoryImpl.kt
- data/repository/LocationResolverPortsAdapters.kt
- data/repository/ManualExpenseRepository.kt
- data/repository/ManualRecurringExpenseRepository.kt
- data/repository/MerchantCategoryRepository.kt
- data/repository/MerchantLocationRepository.kt
- data/repository/MerchantNormalizationRepository.kt
- data/repository/MerchantRulesRepository.kt
- data/repository/MultiCurrencyRepository.kt
- data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | CategoryRepository.kt:27 | **HIGH** | Race Condition | Default category seeding is race-prone and non-idempotent; concurrent callers can create duplicate default categories because `ensureDefaultCategories()` does count-then-insert without a transaction/lock and `categories` has no unique name constraint. | 1. Two concurrent app launches. 2. Both check category count = 0. 3. Both insert default categories. 4. Duplicates created. | Wrap seeding in a DB transaction plus repository mutex and enforce uniqueness/upsert by category name. |
| 2 | BudgetRepository.kt:74 | **HIGH** | Data Integrity | Budget status pipeline publishes raw spend while the budget screen separately computes shared-expense-adjusted spend, so alerts/dashboard/weather can act on different numbers than the budget UI shows. | 1. Shared expense of €100 with 50% split. 2. Budget alert triggers on €100. 3. Budget UI shows €50. 4. User confused by discrepancy. | Centralize adjusted budget spend inside the repository/use case and have all consumers use the same contract. |
| 3 | DashboardContractsAdapter.kt:49 | **MEDIUM** | Logic Error | Dashboard expense flow snapshots the current month once at subscription time; long-lived collectors will show the previous month after rollover until recreated. | 1. Subscribe to dashboard expenses on Jan 15. 2. Month rolls to Feb. 3. Collector still shows January data. | Derive the month window from a rollover-aware flow or `TimeProvider`-driven invalidation. **[RESOLVED BY A.5]** |
| 4 | ExpenseRepository.kt:397 | **HIGH** | Data Loss | `ExpenseRepository.getExpensesBetween()` looks like a full-range API but delegates to a DAO method capped at 2000 rows by default, causing silent truncation for large ranges. | 1. User has 5000 expenses. 2. Call `getExpensesBetween(yearStart, yearEnd)`. 3. Only 2000 returned. 4. Analytics/exports missing 3000 expenses. | Page internally to exhaustion or rename the API to make pagination explicit and add a true full-range variant. |
| 5 | ManualExpenseRepository.kt:151 | **HIGH** | Race Condition | Budget checks are triggered before the manual expense transaction commits; the async monitor can read stale state and then skip the committed expense due to rate limiting. | 1. User adds €500 expense. 2. Budget check triggered asynchronously. 3. Check runs before commit. 4. Rate limiter skips next check. 5. Budget alert never fires. | Invoke budget monitoring only after successful commit or provide a post-commit check hook. |
| 6 | MerchantLocationRepository.kt:34 | **MEDIUM** | Logic Error | Merchant location cache reads extend `lastResolvedAt`, so frequently-read stale coordinates never expire and TTL becomes "time since last access" instead of "time since resolution". | 1. Merchant location resolved 30 days ago. 2. Read daily, extending `lastResolvedAt`. 3. Location never expires despite being stale. | Stop mutating the TTL field on reads or store access time separately. |
| 7 | MultiCurrencyRepository.kt:45 | **HIGH** | Logic Error | Multi-currency spend calculations use raw `amount` instead of `effectiveAmount`, overcounting shared expenses relative to the rest of the app. | 1. Shared expense €100 with 25% share. 2. Multi-currency report shows €100. 3. Rest of app shows €25. | Convert/aggregate `effectiveAmount` for user-spend reporting APIs. |
| 8 | CurrencyRatesRepositoryImpl.kt:30 | **HIGH** | Data Loss | Currency refresh stores only a hard-coded priority subset of ECB currencies, so supported app currencies outside that list can still fail conversion despite being present in provider data. | 1. ECB provides rate for TRY (Turkish Lira). 2. App supports TRY. 3. Hardcoded list doesn't include TRY. 4. TRY conversion fails. | Persist all supported app currencies or the full provider payload from a shared source-of-truth list. |
| 9 | FinancialWeatherRepository.kt:62 | **HIGH** | Data Loss | Financial weather/forecast inputs use `getAllExpenses()` which is capped to 500 rows, producing incomplete recurring detection and forecast calculations for larger histories. | 1. User has 2000 expenses. 2. Forecast uses only latest 500. 3. Recurring patterns in older expenses missed. 4. Forecast inaccurate. | Switch to range-based or paged reads sized for the forecast horizon. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | BudgetRepository ↔ Budget UI | **HIGH** | Spend Discrepancy | Raw spend vs shared-expense-adjusted spend discrepancy between budget alerts and budget UI. | Centralize adjusted spend calculation. |
| C2 | ExpenseRepository ↔ All consumers | **HIGH** | Silent Truncation | `getExpensesBetween()` silently caps at 2000 rows, affecting analytics, exports, forecasts, and weather calculations. | Page to exhaustion or make limit explicit. |
| C3 | MultiCurrencyRepository ↔ Shared Expenses | **HIGH** | Overcounting | Multi-currency reports use raw `amount` instead of `effectiveAmount`, overcounting shared expenses. | Use `effectiveAmount` consistently. |
| C4 | FinancialWeatherRepository ↔ ExpenseRepository | **HIGH** | Incomplete History | Weather/forecast capped at 500 expenses, missing recurring patterns in older data. | Use range-based reads sized for forecast horizon. |
| C5 | ManualExpenseRepository ↔ BudgetMonitor | **HIGH** | Race Condition | Budget check triggered before expense commit, causing missed alerts due to stale reads and rate limiting. | Trigger budget monitoring after commit. |

## Summary
- **Total issues: 14** (9 file-level + 5 cross-component)
- **Critical: 0**, **High: 8**, **Medium: 2**, **Low: 0**
- **Files with issues: 9/28**

## Key Patterns

### 1. Silent Data Truncation
Multiple repository methods silently cap results: `getExpensesBetween()` at 2000 rows, `getAllExpenses()` at 500 rows. This affects analytics, exports, forecasts, and weather calculations without any indication to callers.

### 2. Shared Expense Inconsistency
Multiple places use raw `amount` instead of `effectiveAmount` for shared expenses: budget status pipeline, multi-currency reports. This creates systemic overcounting relative to the rest of the app.

### 3. Race Conditions in Async Operations
Budget checks triggered before expense commits, category seeding without transactional guarantees — these race conditions cause missed alerts and duplicate data.

### 4. Stale Data from Month Snapshot
Dashboard expense flow snapshots the month at subscription time, causing stale data after month rollover for long-lived collectors. **[RESOLVED BY A.5]**

### 5. TTL Extension on Read
Merchant location cache extends TTL on reads, preventing stale data from ever expiring if read frequently.
