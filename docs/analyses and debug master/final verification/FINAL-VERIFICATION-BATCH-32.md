# Final Verification — Batch 32: Repositories — Core & AI

## Scope
### Scoped repository files
- `com/yourname/expensetracker/data/repository/AiArtifactRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/AiEngagementRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
- `com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- `com/yourname/expensetracker/data/repository/BusinessExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/CategoryRepository.kt`
- `com/yourname/expensetracker/data/repository/CurrencyDataRepository.kt`
- `com/yourname/expensetracker/data/repository/CurrencyRatesRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `com/yourname/expensetracker/data/repository/DashboardRepository.kt`
- `com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/ExportDataRepository.kt`
- `com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`
- `com/yourname/expensetracker/data/repository/GroupsRepository.kt`
- `com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/LocationResolverPortsAdapters.kt`
- `com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/MerchantCategoryRepository.kt`
- `com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt`
- `com/yourname/expensetracker/data/repository/MerchantNormalizationRepository.kt`
- `com/yourname/expensetracker/data/repository/MerchantRulesRepository.kt`
- `com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt`
- `com/yourname/expensetracker/data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt`

### Supporting validation files read during verification
- `com/yourname/expensetracker/data/database/dao/CategoryDao.kt`
- `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt`
- `com/yourname/expensetracker/data/database/entity/Category.kt`
- `com/yourname/expensetracker/data/database/entity/Expense.kt`
- `com/yourname/expensetracker/data/database/entity/GroupExpense.kt`
- `com/yourname/expensetracker/data/database/entity/MerchantCategory.kt`
- `com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- `com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
- `com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt`
- `com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`
- `com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- `com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- `com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt`
- `com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
- `com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt`
- `com/yourname/expensetracker/ui/screens/currency/CurrencyManagementViewModel.kt`
- `com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/repository/CategoryRepository.kt:27-63` | High | Data integrity / race condition | `ensureDefaultCategories()` still does count-then-seed without a transaction or repository lock, and the category table still has no uniqueness on `name`. With three startup entry points calling it, duplicate default categories and duplicate `Uncategorized` rows remain possible. | B | CONFIRMED | Wrap seeding in `RoomDatabase.withTransaction`, guard it with a mutex, and enforce a unique key/upsert strategy on category name. |
| 2 | `com/yourname/expensetracker/data/repository/BudgetRepository.kt:74-81` | High | Contract drift | `getBudgetStatuses()` computes raw spend and ignores the injected `SharedExpenseBudgetOffsetEngine`; `BudgetViewModel` then overlays a different adjusted spend for the budget screen, while alerts/dashboard/weather still consume the raw repository contract. | B | CONFIRMED | Move adjusted shared-expense spend into the repository/use-case contract and make all consumers depend on the same output. |
| 3 | `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt:49-53` | Medium | Stale data | `observeDashboardExpenses()` snapshots the month range once from `System.currentTimeMillis()`. A long-lived collector will keep querying the old month after rollover until the flow is recreated. | B | CONFIRMED | Drive the range from `TimeProvider` plus a rollover-aware trigger/flow and rebuild the query with `flatMapLatest`. |
| 4 | `com/yourname/expensetracker/data/repository/ExpenseRepository.kt:397-405` | High | Hidden truncation | `getExpensesBetween()` still forwards to a DAO method whose default limit is 2000, so callers expecting a full interval silently receive partial data. | B | CONFIRMED | Page internally to exhaustion or rename the current API to make the limit explicit and add a true full-range variant. |
| 5 | `com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt:151-152` | High | Race condition | `budgetMonitor.checkBudgets()` is still fired inside the open transaction. Because the monitor launches asynchronously and rate-limits checks for 60 seconds, it can read pre-commit state and then skip the committed expense. | B | CONFIRMED | Trigger budget monitoring only after a successful commit, or provide a suspend post-commit budget refresh path. |
| 6 | `com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt:34-38,47-50` | Medium | Cache invalidation | Cache hits still call DAO methods that update `lastResolvedAt`, so TTL is effectively based on last access instead of last geocode resolution. Frequently-read stale coordinates can persist indefinitely. | B | CONFIRMED | Stop mutating `lastResolvedAt` on reads, or split resolution time from access time. |
| 7 | `com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt:45-47,75-77,147-166,199-219,248-255` | High | Incorrect totals | Multi-currency totals still aggregate `expense.amount` rather than `expense.effectiveAmount`, so shared expenses are overcounted relative to the rest of the app's spending model. | B | CONFIRMED | Use `effectiveAmount` for spend-oriented conversions and aggregations unless an API is explicitly defined as gross-transaction reporting. |
| 8 | `com/yourname/expensetracker/data/repository/CurrencyRatesRepositoryImpl.kt:30-33,73-85` | High | Incomplete data ingestion | Rate refresh still persists only the hard-coded priority list plus `EUR` and the home currency. The underlying issue is real, although the debugger's `TRY` example was wrong because `TRY` is already included; the actual misses are currencies like `DKK`, `PLN`, `CZK`, `HUF`, `RON`, `BGN`, and `ISK` that are supported elsewhere in the app. | B | CONFIRMED | Build the persisted set from a single supported-currency source of truth (or store the full provider payload). |
| 9 | `com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt:62-89,141-167` | High | Incomplete dataset / performance | Financial weather still consumes `expenseRepository.getAllExpenses()`, which is capped to the latest 500 expenses. Forecasting, recurring detection, and pace calculations therefore degrade on larger histories. | B | CONFIRMED | Replace the capped source with range-based, paged, or aggregated reads sized for the actual forecast window. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/repository/BudgetRepository.kt:45-52` | High | Stale reactive window | `getBudgetStatuses()` captures `twentyFiveMonthsAgo` and `endExclusive` once when the flow is created. Long-lived collectors stop admitting new expenses after the day rolls over, so budget status, alerts, and dashboard consumers can go stale until re-subscription. | Rebuild the expense query from a rollover-aware time flow (`TimeProvider` + ticker/day boundary trigger) and `flatMapLatest` into `getExpensesBetweenFlow(...)`. |
| 2 | `com/yourname/expensetracker/data/repository/BudgetRepository.kt:52` | High | Hidden truncation | `getBudgetStatuses()` reads `expenseDao.getExpensesBetweenFlow(...)` without overriding the DAO's default `limit = 2000`, so yearly budgets and rollover calculations silently drop older purchases for heavy accounts. | Add an uncapped budget-specific DAO/query path or page before computing statuses and rollover. |
| 3 | `com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt:45-46,72-73,99-100,143-144,195-196,248-249` | High | Hidden truncation | Every multi-currency reporting method calls `expenseDao.getExpensesBetween(...)` directly and inherits the DAO's default 2000-row cap, so large reporting windows return incomplete converted totals. | Page to exhaustion or add dedicated aggregate DAO queries that do not depend on a capped raw-expense fetch. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| - | None | - | No original issue was discarded. The only inaccurate detail found was the debugger report's `TRY` example under the currency-rates issue; the underlying issue itself is still real for other supported currencies. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Startup seeding → categories → categorization | High | Data integrity | Category seeding is invoked from multiple UI entry points, but the repository remains non-idempotent under concurrency. First-run races can create duplicate categories that then leak into filters, analytics, and merchant mappings. | `data/repository/CategoryRepository.kt`, `ui/screens/home/HomeViewModel.kt`, `ui/screens/categories/CategoryViewModel.kt`, `ui/screens/debug/DebugViewModel.kt` | Move seeding behind a single initializer and make it transactional/idempotent. |
| 2 | Budget repository → alerts/dashboard/weather/budget UI | High | Contract drift | The repository publishes raw spend, while the budget screen overlays a different adjusted shared-expense number. Alerts and derived dashboard/weather logic can therefore disagree with what the budget card shows. | `data/repository/BudgetRepository.kt`, `domain/budget/BudgetMonitor.kt`, `data/repository/DashboardContractsAdapter.kt`, `data/repository/FinancialWeatherRepository.kt`, `ui/screens/budget/BudgetViewModel.kt`, `ui/screens/budget/BudgetScreen.kt` | Define one canonical budget-status contract and have every consumer use it. |
| 3 | Time-bound flows → long-lived collectors | High | Stale data | Multiple reactive pipelines capture time windows once and then keep collecting forever. `observeDashboardExpenses()` freezes on the subscription month, and `getBudgetStatuses()` freezes both its query end bound and history start bound at creation time. | `data/repository/DashboardContractsAdapter.kt`, `data/repository/BudgetRepository.kt`, `domain/usecase/dashboard/DashboardDataProvider.kt` | Recompute time-bounded queries from a rollover-aware clock flow instead of capturing timestamps once. |
| 4 | Shared group expenses → adjusted budget spend | High | Logic error | Group expense creation inserts a full system `Expense`, and `SharedExpenseBudgetOffsetEngine` then adds the user's group share on top of that full personal expense. The adjusted budget pipeline can therefore overcount linked group expenses, especially when the payer is another group member. | `ui/screens/groups/SharedExpenseGroupsViewModel.kt`, `domain/groups/SharedExpenseBudgetOffsetEngine.kt`, `data/repository/BudgetRepository.kt` | Persist group-linked expenses with correct ownership/share metadata, or exclude linked group expenses from `personalSpend` before adding the accrual share. |
| 5 | Hidden pagination → forecasting/reporting | High | Data truncation | The batch has three separate silent caps: `ExpenseRepository.getExpensesBetween()` (2000), `BudgetRepository.getBudgetStatuses()` via `getExpensesBetweenFlow()` (2000), and `ExpenseRepository.getAllExpenses()` (500). Forecasting, budgeting, analytics, and currency reporting all consume these APIs as if they were complete datasets. | `data/repository/ExpenseRepository.kt`, `data/repository/BudgetRepository.kt`, `data/repository/FinancialWeatherRepository.kt`, `data/repository/MultiCurrencyRepository.kt`, `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`, `domain/cashflow/CashFlowCalculator.kt`, `ui/screens/analytics/AnalyticsViewModel.kt` | Make caps explicit in API names and provide uncapped/paged/aggregated variants for analysis-style consumers. |

## Summary
- Total verified issues: 9
- Confirmed: 9 (Critical: 0, High: 7, Medium: 2, Low: 0)
- False positives: 0
- Missed issues found: 3
- Files affected: 9/28

## Key Patterns
- The dominant defect pattern is **hidden pagination**: methods that look like full-history reads are actually capped, and downstream analytics/forecasting code treats them as complete.
- The second pattern is **time-window capture in long-lived flows**: month/day/history bounds are computed once and then silently go stale after rollovers.
- **Shared-expense semantics are inconsistent** across repositories and downstream consumers, causing incompatible spend numbers between budgets, dashboards, weather, and currency reporting.
- The currency stack still lacks **one source of truth for supported currencies**, so ingestion, UI exposure, and conversion expectations drift apart.
