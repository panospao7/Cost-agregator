# Final Verification — Batch 24: Models & Data Classes

> **[RESOLVED BY A.2]** The domain/data layer boundary violations have been resolved. All domain models now use domain DTOs instead of Room entities. Data-layer imports removed from domain code.

## Scope
- `com/yourname/expensetracker/domain/model/SavingsGoal.kt`
- `com/yourname/expensetracker/domain/model/FinancialForecast.kt`
- `com/yourname/expensetracker/domain/model/UiText.kt`
- `com/yourname/expensetracker/domain/model/UpcomingItem.kt`
- `com/yourname/expensetracker/domain/model/Result.kt`
- `com/yourname/expensetracker/domain/model/RecurringPattern.kt`
- `com/yourname/expensetracker/domain/model/PlannedExpense.kt`
- `com/yourname/expensetracker/domain/model/PeriodTotal.kt`
- `com/yourname/expensetracker/domain/model/PeriodRange.kt`
- `com/yourname/expensetracker/domain/model/PeriodDrillDownState.kt`
- `com/yourname/expensetracker/domain/model/CategoryInfo.kt`
- `com/yourname/expensetracker/domain/model/CategoryBreakdown.kt`
- `com/yourname/expensetracker/domain/model/BlockPartyDay.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DashboardCategoryBreakdown.kt`
- `com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt`
- `com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt`
- `com/yourname/expensetracker/domain/model/dashboard/FinancialWeather.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DashboardPrimitives.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DomainDayBudgetStatus.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DomainBlockStatus.kt`
- `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
- `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt`
- `com/yourname/expensetracker/domain/model/recommendation/RecommendationStatus.kt`
- `com/yourname/expensetracker/domain/model/recommendation/RecommendationPriority.kt`
- `com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt`
- `com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- `com/yourname/expensetracker/domain/ai/model/AiRuntimeStatusModels.kt`
- `com/yourname/expensetracker/domain/ai/model/AiArtifactPresentation.kt`
- `com/yourname/expensetracker/domain/ai/model/OnDeviceRuntimePresentation.kt`
- `com/yourname/expensetracker/domain/ai/model/AiLoadState.kt`
- `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- `com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt`
- `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt`
- `com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt`
- `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- `com/yourname/expensetracker/domain/ai/model/NotificationParsingModels.kt`
- `com/yourname/expensetracker/domain/ai/model/SemanticDuplicateModels.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/model/BlockPartyDay.kt:3-17` | High | Architecture | `BlockPartyDay` is a domain model that directly exposes `data.database.entity.Expense` through `topTransactions`, coupling the domain contract to the Room schema. | B | DOWNGRADED | Replace `Expense` with a domain DTO (for example `DomainExpenseSummary`) and map at the repository/adapter boundary. |
| 2 | `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt:3-26` | High | Architecture | A file under `domain/model` maps `DashboardExpense` back into a data-layer `Expense`, inverting the dependency direction and keeping dashboard-domain code dependent on persistence types. | B | DOWNGRADED | Move this mapper out of `domain.model` into a data/adapter layer and keep dashboard-domain types persistence-free. |
| 3 | `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt:3-14` | Medium | Architecture | `DomainTransactionFilter` depends on `data.database.entity.TransactionType` and `data.repository.OwnershipFilter`, so the domain filter vocabulary is not domain-owned. | B | DOWNGRADED | Introduce domain enums/value objects and translate them at the boundary. |
| 4 | `com/yourname/expensetracker/domain/ai/model/AiArtifactPresentation.kt:3-23` | Low | Architecture | `AiArtifactPresentation` lives under `domain.ai.model` but defines an extension on `AiArtifactEntity`, so a persistence model leaks into a domain-model package. | R | DOWNGRADED | Move the `AiArtifactEntity` extension to an adapter/presentation layer and keep `domain.ai.model` entity-free. |
| 5 | `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt:3-60` | Medium | Architecture | `ReviewPriorityFactors.fromReview` accepts `PendingReview`, making AI-domain scoring logic depend directly on a Room entity shape. | R | DOWNGRADED | Accept a pure domain snapshot and map `PendingReview` before calling the scorer. |
| 6 | `com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt:3-17` | Medium | Architecture | `ReceiptItemCategorizationInput` exposes `List<Category>` from the data layer, coupling the AI-domain contract to Room entities. | R | DOWNGRADED | Define a domain category option DTO and map database entities before building the input. |
| 7 | `com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt:62-64` | Medium | Architecture | `CategorizationResult.AlreadyAnalyzed` returns `ReceiptItemCategorization` entities directly, leaking persistence objects through a domain result type. | R | DOWNGRADED | Return a domain snapshot DTO instead of the Room entity list. |
| 8 | `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt:44-48` | Medium | Architecture | `ExpenseQueryFilters.transactionTypes` embeds data-layer `TransactionType`, so the AI query contract is still coupled to database enums. | R | DOWNGRADED | Use a domain-owned transaction kind and map it at the repository boundary. |
| 9 | `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt:91-98` | Medium | Architecture | `CategorizationAssistInput` uses data-layer `TransactionType`, repeating the same domain-to-data dependency leak inside AI contracts. | R | DOWNGRADED | Replace it with a domain enum/value object. |
| 10 | `com/yourname/expensetracker/domain/ai/model/SemanticDuplicateModels.kt:3-23` | Medium | Architecture | `DuplicateCheckCandidate` depends on data-layer `TransactionType`, tying semantic-dedupe contracts to persistence details. | R | DOWNGRADED | Replace it with a domain-owned transaction kind. |
| 11 | `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt:32-35` | High | Logic | `expiresAt` is defaulted from `createdAt` only at construction time. A later `copy(createdAt = ...)` leaves `expiresAt` stale and breaks the TTL invariant. | B | CONFIRMED | Use a factory/computed property, or validate/recompute `expiresAt` whenever `createdAt` changes. |
| 12 | `com/yourname/expensetracker/domain/model/UpcomingItem.kt:10-17` | Medium | Identity | `UpcomingItem.Recurring.id` is derived only from `merchantName`, so distinct recurring items from the same merchant collide and are unsafe as stable list keys. | B | CONFIRMED | Build the ID from a stable backing rule ID, or compose it from merchant + date + category + rule ID. |
| 13 | `com/yourname/expensetracker/domain/model/FinancialForecast.kt:13-16` | Medium | Sentinel value | `ForecastHorizon.REST_OF_MONTH` uses `days = 0` as a sentinel, so consumers reading `.days` directly get a fake duration. | B | CONFIRMED | Model rest-of-month separately, or expose an API that computes effective days from a reference date. |
| 14 | `com/yourname/expensetracker/domain/model/SavingsGoal.kt:3-10` | Medium | Missing invariants | `SavingsGoal` allows blank names, negative/non-finite amounts, `currentAmount > targetAmount`, and uses `createdAt = 0L` as a sentinel timestamp. | B | CONFIRMED | Add `init` validation and make `createdAt` explicit or nullable instead of using `0L`. |
| 15 | `com/yourname/expensetracker/domain/model/PeriodRange.kt:3-9` | Medium | Missing validation | `PeriodRange` allows `end < start`, producing negative `duration` values and broken `contains` semantics. | B | CONFIRMED | Add `require(end >= start)` and document the interval as half-open `[start, end)`. |
| 16 | `com/yourname/expensetracker/domain/model/RecurringPattern.kt:5-16` | Medium | Missing invariants | `RecurringPattern` allows negative/non-finite amounts, negative variance days, and out-of-range confidence/variance percentages despite comments implying constrained values. | R | CONFIRMED | Add `init` checks for finite amounts, non-negative variance, and bounded confidence/percentage values. |
| 17 | `com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt:5-14` | Medium | Missing invariants | `BudgetStatusSnapshot` has no guards for date order or finite numeric fields, so impossible states can move through the domain layer unchecked. (`percentUsed > 100` is not inherently invalid for overspend, so the original severity was overstated.) | B | DOWNGRADED | Add `init` validation for `periodStart <= periodEnd` and finite amounts; document `percentUsed` semantics instead of assuming a hard upper bound. |
| 18 | `com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt:45-46` | Low | Locale / formatting | `formatCurrency` hardcodes the euro symbol and uses `String.format` without an explicit locale, so output varies by device locale and misrepresents non-EUR budgets. | B | CONFIRMED | Use `NumberFormat` with an explicit `Currency`, or move formatting out of the domain model. |
| 19 | `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt:62-63` | Low | Testability | `calculateTimeSensitivity` reads `System.currentTimeMillis()` directly, making scoring time-dependent and non-deterministic in tests. | B | CONFIRMED | Inject a clock or pass `nowMillis` into the calculation. |
| 20 | `com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt:17-25` | Medium | Missing invariants | `WarrantyExtractionResult` allows negative `warrantyMonths`, negative `returnDays`, and out-of-range `confidence`. | R | CONFIRMED | Add `init` checks for non-negative durations and `confidence in 0f..1f`. |
| 21 | `com/yourname/expensetracker/domain/ai/model/NotificationParsingModels.kt:33-40` | Medium | Missing invariants | `NotificationParseResult` documents a positive amount and bounded confidence but enforces neither. | R | CONFIRMED | Add validation for positive finite amounts and `confidence in 0f..1f`. |
| 22 | `com/yourname/expensetracker/domain/model/RecurringPattern.kt:19-29` | Low | Sentinel / trap API | `RecurrenceFrequency.IRREGULAR.intervalInMs` returns `0L` because `days = 0`, leaving a footgun for callers that treat the property as a real interval. | D | CONFIRMED | Make the interval nullable/unsupported for `IRREGULAR`, or model irregular recurrence separately. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt:16-26` | High | Data loss / logic | `DashboardExpense` keeps `effectiveAmount` but not the shared-expense fields (`isSharedExpense`, `myShareAmount`, `mySharePercentage`) that `Expense.effectiveAmount` is derived from. Reconstructing an `Expense` here silently changes `effectiveAmount` for shared transactions, so downstream analytics can overcount spending. | Stop converting `DashboardExpense` back to `Expense`, or extend `DashboardExpense` to carry the missing ownership/share fields and map them through losslessly. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R#17 / D#19` | `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt:14` | `correlationId` appears to be an intentional trace/event identifier: the UI `TransactionFilter` also defaults it, and `MainActivity` explicitly preserves/restores it. The real problem is that the serializer pipeline drops it, not that the default is time-based. |
| 2 | `D#8` | `com/yourname/expensetracker/domain/model/SavingsGoal.kt` | Having `SavingsGoal` in both domain and data packages is a naming inconvenience, but no incorrect import or behavior bug was found. Existing fully-qualified usages compile and behave correctly. |
| 3 | `D#10` | `com/yourname/expensetracker/domain/model/UiText.kt:45,60` | The overloads have distinct JVM signatures (`from(int, Object...)` vs `from(String)`), so there is no actual Java/Kotlin call ambiguity. |
| 4 | `D#11` | `com/yourname/expensetracker/domain/model/Result.kt:10-18` | `Duplicate` being a `data object` while `Loading` is a plain `object` is a consistency/style issue only. `Loading` already overrides `toString()`, so there is no functional defect. |
| 5 | `D#12 / D#13 / R Cross#5` | `com/yourname/expensetracker/domain/model/FinancialForecast.kt:8`, `com/yourname/expensetracker/domain/model/CategoryBreakdown.kt:7`, `com/yourname/expensetracker/domain/model/dashboard/DashboardCategoryBreakdown.kt:9`, related AI models | The mixed `Float`/`Double` usage is inconsistent, but no faulty computation or precision bug was demonstrated in this batch. This is a convention problem, not a verified defect. |
| 6 | `D#16` | `com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt:7-8` | Different history lengths are valid because current and previous months can have different day counts. The repository intentionally builds month-sized arrays, so enforcing equal lengths would reject legitimate data. |
| 7 | `D#18` | `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt:50-51` | `minAmount > maxAmount` would produce an empty filter, but no actual caller or invariant in this batch requires construction-time rejection. The report did not establish a concrete defect. |
| 8 | `D#21` | `com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt:56,75` | The live serialization path writes stringified category IDs, not category names. The payload class is also unused in the current pipeline, so the reported collision/data-loss scenario is not present. |
| 9 | `D#23` | `com/yourname/expensetracker/domain/ai/model/AiLoadState.kt:18` | This is a theoretical variance concern only. Current usages are immutable value objects, and no unsafe behavior exists in the codebase. |
| 10 | `R#18 / D#24` | `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt:140-155` | `id = 0` mirrors the auto-generated Room ID convention, and explicit `createdAt`/`sessionId` requirements are appropriate for these persisted chat models. No buggy construction pattern was found. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Recommendation filter serialization | Medium | Traceability / drift | `DomainTransactionFilter` carries `correlationId`, but `TransactionFilterSerializer` neither serializes nor deserializes it. Recommendation-generated filters therefore lose end-to-end trace continuity when they cross the JSON boundary. | `domain/model/navigation/DomainTransactionFilter.kt`, `service/TransactionFilterSerializer.kt`, `ui/mappers/TransactionFilterUiMapper.kt`, `service/NavigationTargetResolver.kt` | Either serialize `correlationId` explicitly or remove it from the shared filter model and keep it as boundary-only metadata. |
| 2 | Budget-day model pipeline | Medium | Contract duplication | The codebase contains both legacy `BlockPartyDay` (entity-leaking) and `DomainDayBudgetStatus` (domain-safe) models for the same concept, so callers can keep using the wrong contract and reintroduce data-layer coupling. | `domain/model/BlockPartyDay.kt`, `domain/model/dashboard/DomainDayBudgetStatus.kt` | Retire `BlockPartyDay` and standardize all callers on `DomainDayBudgetStatus`. |
| 3 | Analytics/dashboard breakdown DTOs | Low | Model drift | `CategoryBreakdown`/`DashboardCategoryBreakdown` are duplicated across `domain.model`, `domain.model.dashboard`, and `domain.analytics` with overlapping semantics, increasing adapter churn and drift risk. | `domain/model/CategoryBreakdown.kt`, `domain/model/dashboard/DashboardCategoryBreakdown.kt`, `domain/analytics/AnalyticsModels.kt`, `domain/analytics/AdvancedAnalyticsDashboard.kt` | Consolidate or clearly rename canonical DTOs by responsibility. |
| 4 | Query-to-navigation filters | Medium | Vocabulary duplication | `ExpenseQueryFilters`, `DomainTransactionFilter`, and UI `TransactionFilter` represent the same concept with slightly different shapes and data-layer enum dependencies, forcing repeated translation logic and increasing drift. | `domain/ai/model/FinancialQueryModels.kt`, `domain/model/navigation/DomainTransactionFilter.kt`, `ui/screens/transactions/TransactionFilter.kt`, `domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt` | Define one domain-owned filter vocabulary and project specialized forms from it at the boundaries. |

## Summary
- Total verified issues: 22
- Confirmed: 22 (Critical: 0, High: 3, Medium: 15, Low: 4)
- False positives: 10
- Missed issues found: 1
- Files affected: 19/38

## Key Patterns
- The dominant real problem in this batch is **boundary leakage**: multiple domain and AI-domain model files still import Room entities or repository-layer enums directly.
- Several models rely on comments instead of **constructor invariants**, so invalid dates, amounts, confidence scores, and durations can be instantiated freely.
- A smaller but repeated pattern is **sentinel numeric state** (`0`, `0L`) standing in for “special” semantics, which creates trap APIs such as `REST_OF_MONTH.days` and `IRREGULAR.intervalInMs`.
- The dashboard/filtering surface shows **DTO drift**: overlapping models and lossy remapping already caused one missed concrete bug (`DashboardExpense` → `Expense` losing shared-expense semantics).
