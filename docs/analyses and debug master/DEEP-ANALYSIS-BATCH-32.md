# Deep Analysis — Batch 32: Repositories — Core & AI (@reviewer)

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
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `CategoryRepository.kt:27-63` | HIGH | Data integrity / race condition | `ensureDefaultCategories()` does a count-then-insert seed with no transaction or process-level lock, while multiple startup paths call it. Because `categories` has no unique constraint on `name`, concurrent callers can create duplicate default categories and duplicate `Uncategorized` rows. | Serialize seeding with a repository mutex plus `RoomDatabase.withTransaction`, and add a unique index/upsert strategy on category name so the operation is idempotent. |
| 2 | `BudgetRepository.kt:74-81` | HIGH | Logic / contract inconsistency | `getBudgetStatuses()` explicitly emits raw spend and ignores `SharedExpenseBudgetOffsetEngine`, even though the repository owns that dependency. Consumers such as budget alerts, dashboard snapshots, and financial-weather synthesis therefore read different spend/health values than the budget screen’s adjusted breakdown. | Make the repository compute one canonical adjusted status (or expose a dedicated adjusted-status flow) and migrate all downstream consumers to that single contract. |
| 3 | `DashboardContractsAdapter.kt:49-53` | MEDIUM | Stale data | The dashboard expense window is computed once from `System.currentTimeMillis()` when the flow is created. If the collector survives a month boundary, it keeps querying the old month and silently shows stale transactions until resubscription/restart. | Drive the period from `TimeProvider` plus a day/month rollover trigger, or `flatMapLatest` on a current-period flow so boundaries refresh automatically. |
| 4 | `ExpenseRepository.kt:397-405` | HIGH | Incorrect query contract | `getExpensesBetween()` looks like a full-range fetch, but it delegates to a DAO method whose default page size is 2000. Any caller that expects the full interval gets silently truncated data once the range exceeds 2000 rows. | Page internally until exhaustion, or rename the current API to a paged variant and add an explicit full-range helper so callers cannot accidentally truncate analytics data. |
| 5 | `ManualExpenseRepository.kt:151-152` | HIGH | TOCTOU / stale read | `budgetMonitor.checkBudgets()` is invoked inside the open expense transaction. The monitor launches asynchronously, so it can read pre-commit data; because it also rate-limits checks for 60 seconds, the first post-insert alert can be missed entirely. | Trigger budget checks only after the transaction commits successfully, or provide a suspend/transaction-aware budget refresh that runs on committed state. |
| 6 | `MerchantLocationRepository.kt:34-38,47-50` | MEDIUM | Cache invalidation | Cache hits call DAO increment methods that also rewrite `lastResolvedAt`. That turns TTL into “time since last read” instead of “time since last geocode”, so stale merchant coordinates can stay alive indefinitely if they are looked up often. | Keep access time separate from resolution time, or stop mutating the TTL field on reads and only update it when a location is actually re-resolved. |
| 7 | `MultiCurrencyRepository.kt:45-47,75-77,147-166,199-219,248-255` | HIGH | Incorrect totals | Spend-oriented currency calculations use `expense.amount` instead of `expense.effectiveAmount`. Shared expenses are therefore overcounted compared with the rest of the app’s spending logic, and converted totals diverge from budgets/analytics. | Use `effectiveAmount` for all user-spend totals, category/merchant aggregations, and per-expense conversions unless the API is explicitly meant to report gross transaction values. |
| 8 | `CurrencyRatesRepositoryImpl.kt:30-33,73-85` | HIGH | Incomplete data ingestion | Rate refresh persists only a hard-coded priority subset plus `EUR` and the home currency. Supported currencies outside that list (for example DKK/PLN/CZK/HUF/RON/BGN/ISK) can still fail conversion even when the ECB payload already contained them. | Build the persisted currency set from the app’s supported-currency list (or store the full provider response) instead of a hand-maintained priority subset. |
| 9 | `FinancialWeatherRepository.kt:62-89,141-167` | HIGH | Performance / incorrect dataset | Weather synthesis, recurring-pattern detection, and pace calculations are fed from `expenseRepository.getAllExpenses()`, which is capped to the latest 500 records. Users with larger histories get incomplete forecasting inputs and unstable recurring detection. | Replace the capped “all expenses” flow with range-scoped or paged DAO reads tailored to the actual forecast windows, or provide a dedicated uncapped aggregated pipeline for weather calculations. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | Startup seeding → categories → categorization | HIGH | Data integrity | `ensureDefaultCategories()` is called from multiple UI entry points, but the repository is not idempotent under concurrency. A first-run race can create duplicate category rows that then leak into budgets, filters, and category-based analytics. | Make seeding transactional/idempotent and move it behind a single app-start initializer instead of multiple ad-hoc UI calls. |
| 2 | Expense ingestion → budget status → alerts/dashboard/weather | HIGH | Contract drift | The budget pipeline is split: `BudgetRepository` publishes raw statuses, while `BudgetViewModel` separately computes adjusted shared-expense spend. Alerting and dashboard/weather consumers therefore act on a different definition of “spent” than the budget screen shows. | Centralize adjusted budget computation in one repository/use-case and have every downstream consumer depend on that same output. |
| 3 | Expense repository → analytics/forecast/savings engines | HIGH | Hidden truncation | Repository methods named like full-range reads (`getExpensesBetween`, `getAllExpenses`) are internally capped (2000 rows / 500 rows). Downstream engines consume them as if they were complete datasets, so correctness degrades with larger accounts. | Make size limits explicit in API names, and add paged or aggregated full-range variants for analytics-style consumers. |
| 4 | Rate refresh → currency conversion → reporting | HIGH | Inconsistent currency support | The refresh pipeline stores only a subset of currencies, while reporting repositories assume conversions are available for all supported app currencies. This creates avoidable “missing rate” failures in otherwise valid scenarios. | Derive storage, UI support, and conversion expectations from one shared supported-currency source of truth. |

## Summary
- Total issues: 9
- Critical: 0, High: 7, Medium: 2, Low: 0
- Files with issues: 8/28

## Key Patterns
- Several repositories expose APIs that look unbounded/reactive, but the underlying implementation is capped or one-shot. That mismatch is already leaking into forecasting, analytics, and dashboard behavior.
- Shared-expense handling is not consistently applied across repositories. Some code paths use `effectiveAmount`, while others still use raw `amount`, creating incompatible totals between features.
- Initialization and cache maintenance paths are weak on idempotency/invalidation: category seeding is race-prone, and merchant-location TTL semantics are extended by reads rather than refreshes.
- The currency stack lacks a single source of truth for “supported currencies”, so ingestion and reporting can disagree even when upstream provider data is available.
