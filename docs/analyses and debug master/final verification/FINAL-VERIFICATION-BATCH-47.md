# Final Verification — Batch 47: Domain Models — Remaining & Text/Widgets

> **[RESOLVED BY A.2]** The domain/data layer boundary violations have been resolved. All domain models now use domain DTOs instead of Room entities. Data-layer imports removed from domain code.
> **[RESOLVED BY A.3]** The non-deterministic default values issue (System.currentTimeMillis) has been fixed across the codebase.

## Scope
Requested `DashboardBlockStatus.kt` and `DashboardDayBudgetStatus.kt` are not present in the repository; the reviewed replacements are `DomainBlockStatus.kt` and `DomainDayBudgetStatus.kt`.

- `com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DomainBlockStatus.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DashboardCategoryBreakdown.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DomainDayBudgetStatus.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DashboardPrimitives.kt`
- `com/yourname/expensetracker/domain/model/dashboard/FinancialWeather.kt`
- `com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt`
- `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
- `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt`
- `com/yourname/expensetracker/domain/model/recommendation/RecommendationPriority.kt`
- `com/yourname/expensetracker/domain/model/recommendation/RecommendationStatus.kt`
- `com/yourname/expensetracker/domain/widget/model/WidgetStyle.kt`
- `com/yourname/expensetracker/domain/widget/service/WidgetStyleRepository.kt`
- `com/yourname/expensetracker/data/database/entity/Expense.kt`
- `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`
- `com/yourname/expensetracker/data/repository/RecommendationRepository.kt`
- `com/yourname/expensetracker/data/repository/WidgetStyleRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- `com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
- `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
- `com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt`
- `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt`
- `com/yourname/expensetracker/domain/engine/DashboardFollowThroughEngine.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- `com/yourname/expensetracker/service/TransactionFilterSerializer.kt`
- `com/yourname/expensetracker/service/RecommendationDeduplicator.kt`
- `com/yourname/expensetracker/service/NavigationTargetResolver.kt`
- `com/yourname/expensetracker/service/RecommendationCacheService.kt`
- `com/yourname/expensetracker/ui/MainActivity.kt`
- `com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt`
- `com/yourname/expensetracker/ui/mappers/TransactionFilterUiMapper.kt`
- `com/yourname/expensetracker/ui/mappers/DashboardWidgetUiMapper.kt`
- `com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `com/yourname/expensetracker/ui/components/BudgetBlockPartyCard.kt`
- `com/yourname/expensetracker/ui/components/RetroBudgetBlockPartyCard.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt:3-4` | Medium | Architecture | Domain code imports and returns `data.database.entity.Expense`/`TransactionType`, so the domain package is directly coupled to Room-layer types. | B | DOWNGRADED | Move the mapper to the data layer or introduce a pure domain transaction model for widget/analytics use. |
| 2 | `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt:7-26`<br>`com/yourname/expensetracker/domain/model/dashboard/DashboardPrimitives.kt:3-13` | Critical | Logic / Data Loss | `DashboardExpense` preserves `effectiveAmount`, but `toEntityExpense()` rebuilds an `Expense` without shared/split metadata. The rebuilt entity therefore recomputes `effectiveAmount` from incomplete fields and overcounts shared expenses. | B | CONFIRMED | Stop round-tripping through `Expense`, or carry the missing ownership/share fields so reconstruction is lossless. |
| 3 | `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt:3-4` | Medium | Architecture | `DomainTransactionFilter` depends on `data.database.entity.TransactionType` and `data.repository.OwnershipFilter`, so the domain model is not boundary-pure. | B | DOWNGRADED | Define domain-level filter enums/types and map them at repository/UI boundaries. |
| 4 | `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt:14` | Medium | Model Semantics / Testability | `correlationId` defaults to `System.currentTimeMillis()` inside a data class, so logically identical filters created at different times compare unequal and serialized/deserialized filters do not retain stable identity. | B | DOWNGRADED | Move correlation metadata outside the value object, or make the default deterministic and exclude it from semantic equality. |
| 5 | `com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt:4-8` | Low | Numeric Precision | The model mixes `Double` totals with `Float` histories/percentages, introducing avoidable precision loss in a financial domain type. | B | DOWNGRADED | Use `Double` consistently in domain/repository models and convert to `Float` only at chart/UI boundaries. |
| 6 | `com/yourname/expensetracker/domain/widget/model/WidgetStyle.kt:15-50`<br>`com/yourname/expensetracker/data/repository/WidgetStyleRepositoryImpl.kt:45-67` | Medium | Invariant / Consistency | `WidgetStyleConfig` accepts any string key, but persistence only restores keys in `StyledWidgets.all`. Unsupported IDs can exist in memory, then silently disappear after save/reload. | R | CONFIRMED | Validate widget IDs at the model/repository boundary or replace raw strings with a closed enum/value object. |
| 7 | `com/yourname/expensetracker/domain/model/dashboard/DashboardCategoryBreakdown.kt:10`<br>`com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt:153-160` | Low | Misleading API | `changeFromLastPeriod` is exposed on the model but is hardcoded to `0.0` in production mapping, so consumers cannot distinguish “not implemented” from a true zero change. | D | DOWNGRADED | Either calculate the field or remove/defer it until real data exists. |
| 8 | `com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt:7-11` | Low | Numeric Precision | `budgetAmount`/`spentAmount` are `Double` but `percentUsed` is `Float`, so percentage precision is lower than the underlying monetary values. | D | DOWNGRADED | Store `percentUsed` as `Double` or derive it on demand from the `Double` inputs. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:435-440` | Low | Data Semantics | `DomainExpenseSummary.categoryName` is populated with `categoryId?.toString()` instead of an actual category name, so the DTO carries mislabeled data. | Pass the real category name, or rename the field to `categoryId` to match the stored value. |
| 2 | `com/yourname/expensetracker/ui/mappers/DashboardWidgetUiMapper.kt:19-26` | Low | Model Misuse | Widget transaction summaries are converted into synthetic `Expense` entities with hard-coded `TransactionType.PURCHASE` and partial fields, reintroducing fake persistence semantics into the UI layer. | Map to a dedicated UI summary model instead of fabricating `Expense` objects. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R #5 / D #5` | `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt:27,32-33` | The UUID/timestamp defaults make construction non-deterministic, but this class behaves as a persisted entity/record; no verified bug depends on two separately created recommendations comparing equal. |
| 2 | `R #5 / D #6` | `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt:35` | The stale-`expiresAt` copy pitfall is theoretically real, but no in-repo `copy(createdAt = ...)` path or inconsistent mutation flow was found. Current creation/DB mapping sets both values explicitly. |
| 3 | `R #6` | `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt:31` | Storing serialized filter JSON in the model is a design trade-off, not a standalone bug. The concrete defects are the dedup/signature inconsistencies, which are captured separately below. |
| 4 | `D #7 / D Cross #4` | `com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt:5-15` | Production construction paths all copy already-derived values from `BudgetRepository` status objects; no inconsistent live construction path was verified in current code. |
| 5 | `D #8` | `com/yourname/expensetracker/domain/model/dashboard/FinancialWeather.kt:20` | `riskLevel: Int` is weakly typed, but verification did not find an invalid producer/consumer path causing wrong behavior in the current code. |
| 6 | `D #10` | `com/yourname/expensetracker/domain/widget/service/WidgetStyleRepository.kt:23-35` | `toggleWidgetStyle()` intentionally propagates `update()` failures. The reported “fallback MODERN is never returned on exception” is not a defect; the exception is the correct outcome. |
| 7 | `D #13` | `com/yourname/expensetracker/domain/model/dashboard/DomainDayBudgetStatus.kt:22-24` | For additive impact fields, `0.0` is a valid neutral default and no ambiguous production consumer was found. |
| 8 | `D #14` | `com/yourname/expensetracker/domain/model/dashboard/DomainBlockStatus.kt:3-10` | The underlying block-party pipeline already models status as a single enum, and current UI consumes one display status only. No concrete information-loss bug was verified here. |
| 9 | `D #15` | `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt:47-49` | The helper already supports explicit time injection; the default clock parameter is convenience and no faulty call path was verified. |
| 10 | `D Cross #2` | `com/yourname/expensetracker/service/TransactionFilterSerializer.kt:35-56,64-121` | Serialization drops `correlationId`, but no downstream logic actually relies on that field after filter JSON round-tripping, so no user-visible defect was confirmed. |
| 11 | `D Cross #3` | `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`<br>`com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt` | The duplication is real, but the mapper is currently symmetric and no concrete malfunction from having both models was verified. |
| 12 | `R Cross #4` | `com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt`<br>`com/yourname/expensetracker/domain/model/dashboard/DashboardCategoryBreakdown.kt` | These overlapping shapes create maintenance risk, but verification did not find a concrete runtime bug caused by the duplication itself. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `DashboardContractsAdapter.observeDashboardExpenses()` → `DashboardExpense` → `toEntityExpense()` → `ComputeDashboardWidgetsUseCase` / `InsightsEngine` / block-party widgets | Critical | Pipeline Drift / Data Corruption | The dashboard pipeline starts with the correct per-user `effectiveAmount`, then reconstructs incomplete `Expense` entities. This inflates shared-expense totals in spending pace and also contaminates widget-side derived data that reads `effectiveAmount` from the rebuilt entities. | `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`, `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt`, `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`, `com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt` | Keep downstream code on `DashboardExpense` or move to a lossless domain transaction model instead of rehydrating Room entities. |
| 2 | `DomainTransactionFilter` → `TransactionFilterSerializer` → `DashboardFollowThroughRecommendation.filterCriteria` → `RecommendationDeduplicator.computeSignature()` | High | Dedup Logic | The serializer preserves `ownership`, but `RecommendationDeduplicator` omits it from its signature, so recommendations that differ only by ownership can be collapsed incorrectly. | `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`, `com/yourname/expensetracker/service/TransactionFilterSerializer.kt`, `com/yourname/expensetracker/service/RecommendationDeduplicator.kt`, `com/yourname/expensetracker/domain/engine/DashboardFollowThroughEngine.kt` | Include every semantic filter field, including `ownership`, in the dedup signature or compare typed filters directly. |
| 3 | `DashboardFollowThroughRecommendation.filterCriteria` ↔ repository dedup vs in-memory dedup | Medium | Consistency | In-memory dedup parses JSON into normalized fields, but `RecommendationRepository` compares existing rows using `filterCriteria.hashCode()`. Semantically identical filters with different JSON ordering/formatting can bypass cross-call deduplication. | `com/yourname/expensetracker/service/RecommendationDeduplicator.kt`, `com/yourname/expensetracker/data/repository/RecommendationRepository.kt`, `com/yourname/expensetracker/service/TransactionFilterSerializer.kt` | Persist or compute one canonical signature and reuse it in both in-memory and repository-level dedup checks. |
| 4 | `WidgetStyleConfig.setStyle()` / `toggleStyle()` → `WidgetStyleRepositoryImpl.serializeConfig()` / `parseConfig()` | Medium | Consistency | The write path accepts arbitrary widget IDs, while the read path only restores the allowlisted set. Unsupported IDs therefore survive only until persistence, then disappear silently on reload. | `com/yourname/expensetracker/domain/widget/model/WidgetStyle.kt`, `com/yourname/expensetracker/domain/widget/service/WidgetStyleRepository.kt`, `com/yourname/expensetracker/data/repository/WidgetStyleRepositoryImpl.kt` | Enforce the supported widget set at the model/repository boundary and fail fast on unsupported IDs. |

## Summary
- Total verified issues: 8
- Confirmed: 8 (Critical: 1, High: 0, Medium: 4, Low: 3)
- False positives: 12
- Missed issues found: 2
- Files affected: 7/14

## Key Patterns
- The batch’s most important real defect is lossy rehydration: dashboard-specific models are converted back into incomplete Room entities and downstream calculations trust the reconstructed data.
- Several reported items were architectural smells rather than concrete bugs; the actual actionable issues are narrower than the original reports claimed.
- Domain/filter and widget-style models still lack strong invariants, which shows up as boundary leakage, unstable identity metadata, and write/read mismatches.
- Numeric precision choices (`Float` inside financial domain models) are still inconsistent, but these are low-severity compared with the shared-expense pipeline bug.
