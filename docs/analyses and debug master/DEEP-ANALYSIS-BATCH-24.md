# Deep Analysis — Batch 24: Models & Data Classes (@reviewer)

## Scope
- `app/src/main/java/com/yourname/expensetracker/domain/model/SavingsGoal.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/FinancialForecast.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/UiText.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/UpcomingItem.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/Result.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/RecurringPattern.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/PlannedExpense.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/PeriodTotal.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/PeriodRange.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/PeriodDrillDownState.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/CategoryInfo.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/CategoryBreakdown.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/BlockPartyDay.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardCategoryBreakdown.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/FinancialWeather.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardPrimitives.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DomainDayBudgetStatus.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DomainBlockStatus.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/recommendation/RecommendationStatus.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/recommendation/RecommendationPriority.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiRuntimeStatusModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiArtifactPresentation.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/OnDeviceRuntimePresentation.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiLoadState.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/NotificationParsingModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/SemanticDuplicateModels.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/model/BlockPartyDay.kt:3-17` | CRITICAL | Architecture violation | `BlockPartyDay` imports `data.database.entity.Expense` and exposes `List<Expense>` from a domain model, so the domain contract is tied to the Room entity schema. | Replace `Expense` with a domain DTO and do entity to domain mapping outside `domain.model`. |
| 2 | `domain/model/dashboard/DashboardExpenseMapper.kt:3-25` | CRITICAL | Architecture violation | A file under `domain/model` converts `DashboardExpense` directly into data-layer `Expense` and `TransactionType`, inverting the dependency direction. | Move this mapper into the data or adapter layer and map from domain-owned types there. |
| 3 | `domain/model/navigation/DomainTransactionFilter.kt:3-11` | CRITICAL | Architecture violation | `DomainTransactionFilter` depends on `data.database.entity.TransactionType` and `data.repository.OwnershipFilter`, so the domain filter cannot be reused independently of repository internals. | Introduce domain-owned filter enums and translate them at the repository boundary. |
| 4 | `domain/ai/model/AiArtifactPresentation.kt:3-11` | CRITICAL | Architecture violation | `AiArtifactPresentation` lives in `domain.ai.model` but extends `AiArtifactEntity`, leaking a persistence model into the domain contract. | Move the `AiArtifactEntity` extension into an adapter layer and keep this package entity-free. |
| 5 | `domain/ai/model/ReviewPriorityModels.kt:3-50` | CRITICAL | Architecture violation | `ReviewPriorityFactors.fromReview` takes `PendingReview`, so AI-domain scoring logic depends directly on a Room entity shape. | Accept a pure domain review snapshot and map `PendingReview` before calling this code. |
| 6 | `domain/ai/model/ReceiptItemCategorizationModels.kt:3-17` | CRITICAL | Architecture violation | `ReceiptItemCategorizationInput` exposes `List<Category>` from the data layer, coupling the AI-domain contract to the database entity model. | Define a domain category option DTO and convert database entities before building the input. |
| 7 | `domain/ai/model/ReceiptItemCategorizationModels.kt:62-64` | CRITICAL | Architecture violation | `CategorizationResult.AlreadyAnalyzed` returns `List<ReceiptItemCategorization>` from the data layer, leaking persistence entities through a domain result type. | Return a domain snapshot DTO instead of the Room entity list. |
| 8 | `domain/ai/model/FinancialQueryModels.kt:3-51` | CRITICAL | Architecture violation | `ExpenseQueryFilters` embeds data-layer `TransactionType`, so AI query interpretation is still coupled to database enums. | Replace it with a domain transaction kind and map at the repository layer. |
| 9 | `domain/ai/model/CaptureAssistModels.kt:3-104` | CRITICAL | Architecture violation | `CategorizationAssistInput` uses data-layer `TransactionType`, repeating the same domain to data dependency leak inside AI contracts. | Use a domain enum or value object instead of the Room enum. |
| 10 | `domain/ai/model/SemanticDuplicateModels.kt:3-23` | CRITICAL | Architecture violation | `DuplicateCheckCandidate` depends on data-layer `TransactionType`, keeping semantic dedupe contracts tied to persistence details. | Replace it with a domain-owned transaction kind. |
| 11 | `domain/model/recommendation/DashboardFollowThroughRecommendation.kt:27-35` | HIGH | Non-deterministic defaults | `id`, `createdAt`, and `updatedAt` default to `UUID.randomUUID` and `System.currentTimeMillis`, while `expiresAt` is derived only once at construction. A later `copy(createdAt = ...)` leaves `expiresAt` stale and breaks the TTL invariant. | Remove random and time defaults from the data class, inject clock and ID generation in a factory, and recompute `expiresAt` whenever `createdAt` changes. |
| 12 | `domain/model/UpcomingItem.kt:13` | HIGH | Unstable identity | `UpcomingItem.Recurring.id` is built from `merchantName` only, so multiple recurring items from the same merchant collide and become unsafe for Compose keys or diffing. | Use a stable unique ID from the recurring rule or compose the key from merchant, date, category, and backing rule ID. |
| 13 | `domain/model/FinancialForecast.kt:13-17` | MEDIUM | Sentinel value | `ForecastHorizon.REST_OF_MONTH` stores `days = 0` and relies on callers to interpret zero as special meaning. That is a classic sentinel that blurs valid values with control flow. | Model rest-of-month as its own enum case without a fake numeric day count, or expose a nullable or computed duration API. |
| 14 | `domain/model/SavingsGoal.kt:3-10` | MEDIUM | Missing invariants / sentinel value | `SavingsGoal` has no validation for blank names, negative amounts, or `currentAmount > targetAmount`, and `createdAt` defaults to `0L`, using a sentinel timestamp in a core domain model. | Add `init` validation and make `createdAt` explicit or nullable instead of using zero. |
| 15 | `domain/model/PeriodRange.kt:3-9` | MEDIUM | Missing validation | `PeriodRange` allows `end < start`, producing negative `duration` values and silent false negatives from `contains`. | Add `require(end >= start)` and document whether the end is inclusive or exclusive. |
| 16 | `domain/model/RecurringPattern.kt:5-16` | MEDIUM | Missing validation | `RecurringPattern` allows negative amounts, negative variance days, out-of-range confidence, and non-finite doubles even though comments imply constrained values. | Add `init` checks for finite amounts, non-negative variance, and `confidence` and `amountVariancePercent` bounds. |
| 17 | `domain/model/navigation/DomainTransactionFilter.kt:14` | MEDIUM | Non-deterministic default | `correlationId` defaults to `System.currentTimeMillis`, making two otherwise identical filters unequal and harder to cache, compare, or test. | Make `correlationId` explicit or nullable and generate it only at the navigation boundary that needs tracing. |
| 18 | `domain/ai/model/FinancialQueryModels.kt:140-155` | MEDIUM | Sentinel value | `AiChatSession.id` and `AiChatMessage.id` default to `0`, using sentinel IDs inside domain models even though absence is better represented by null or by separate create commands. | Make IDs nullable until persistence assigns them, or split create and persisted models. |
| 19 | `domain/model/dashboard/BudgetStatusSnapshot.kt:5-14` | MEDIUM | Missing validation | `BudgetStatusSnapshot` has no guards for `periodStart <= periodEnd`, finite amounts, or `percentUsed` bounds, so impossible states can move through the domain layer unchecked. Its mixed numeric-type drift on `percentUsed` was later corrected under A.6, but the invariant-validation concern remains open. | Add `init` validation for date order, finite numbers, and a defined percent range. **[RESOLVED BY A.6 — numeric type only]** |
| 20 | `domain/model/budget/MonteCarloBudgetImpact.kt:45-46` | LOW | Locale bug | `formatCurrency` hardcodes the euro symbol and calls `String.format` without an explicit locale, so output changes by device locale and misrepresents non-EUR budgets. | Use `NumberFormat` with an explicit `Currency`, or keep formatting out of the domain model entirely. |
| 21 | `domain/ai/model/ReviewPriorityModels.kt:62-63` | LOW | Testability | `calculateTimeSensitivity` reads `System.currentTimeMillis` directly, so scoring is time-dependent and cannot be deterministically tested without clock control. | Inject a `Clock` or accept `nowMillis` as a parameter. |
| 22 | `domain/ai/model/WarrantyExtractionModels.kt:17-25` | MEDIUM | Missing validation | `WarrantyExtractionResult` allows negative `warrantyMonths`, negative `returnDays`, and out-of-range `confidence`, despite comments implying constrained values. | Add `init` checks for non-negative durations and `confidence` within 0 to 1. |
| 23 | `domain/ai/model/NotificationParsingModels.kt:33-40` | MEDIUM | Missing validation | `NotificationParseResult` documents an always-positive amount and bounded confidence, but the model enforces neither, allowing invalid parser outputs into the pipeline. | Add `init` validation for positive finite amounts and `confidence` bounds. |

## Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `domain/model/CategoryBreakdown.kt` and `domain/analytics/AnalyticsModels.kt` | MEDIUM | Two different `CategoryBreakdown` classes exist in the domain layer with overlapping semantics but different fields, making imports ambiguous and increasing accidental misuse risk. | Consolidate into one shared type or rename them to clearly distinct responsibilities. |
| 2 | `domain/model/dashboard/DashboardCategoryBreakdown.kt` and `domain/analytics/AdvancedAnalyticsDashboard.kt` | MEDIUM | `DashboardCategoryBreakdown` is duplicated in two packages with near-identical structure, creating parallel DTOs for the same concept. | Keep one canonical model and adapt only where the shape genuinely differs. |
| 3 | `domain/model/navigation/DomainTransactionFilter.kt` and `domain/ai/model/FinancialQueryModels.kt` | MEDIUM | The codebase has two overlapping transaction filter models with different enum sources and field shapes, which encourages duplicated translation logic and drift. | Define one domain filtering vocabulary and derive specialized projections from it. |
| 4 | `domain/model/BlockPartyDay.kt` and `domain/model/dashboard/DomainDayBudgetStatus.kt` | MEDIUM | These two models represent the same budget-day concept, but one still leaks data entities while the newer one is domain-safe, so callers can choose incompatible versions. | Remove or migrate the legacy model and keep a single domain-safe budget day contract. |
| 5 | `FinancialForecast`, `RecurringPattern`, `DashboardCategoryBreakdown`, `BudgetStatusSnapshot`, `SpendingSummary`, and several AI models | MEDIUM | Confidence and percentage fields alternate between `Float` and `Double` across closely related models, forcing repeated casts and making API expectations inconsistent. A.6 later addressed this drift narrowly for `SpendingSummary`, `BudgetStatusSnapshot`, and `CategoryBreakdown`; other listed model families remained outside that epic's exact scope. | Standardize on one numeric type for ratios and confidence values across domain and AI models. **[ADDRESSED BY A.6 — scoped fields only]** |

## Summary
- Total issues: 23
- Critical: 10, High: 2, Medium: 9, Low: 2
- Files with issues: 19/38

## Key Patterns
- Clean architecture boundaries are still porous: several domain and AI-domain models import Room entities or repository enums directly.
- Sentinel values remain common, especially `0`, `0L`, and special numeric enum payloads that stand in for missing or derived state.
- Many reviewed data classes rely on comments instead of `init` validation, so invalid amounts, percentages, dates, and confidence scores can be instantiated freely.
- Identity and time handling are not consistently stable or testable: some models synthesize IDs from non-unique fields, while others embed `System.currentTimeMillis` or random UUID defaults.
- The model surface has duplication drift, with multiple near-identical breakdown and filter types across adjacent components.
