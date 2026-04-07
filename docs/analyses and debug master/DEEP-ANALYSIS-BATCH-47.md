# Deep Analysis — Batch 47: Domain Models — Remaining & Text/Widgets (@reviewer)

## Scope
- domain/model/dashboard/BudgetStatusSnapshot.kt
- domain/model/dashboard/DashboardBlockStatus.kt *(not present in repository)*
- domain/model/dashboard/DashboardCategoryBreakdown.kt
- domain/model/dashboard/DashboardDayBudgetStatus.kt *(not present in repository)*
- domain/model/dashboard/DashboardExpenseMapper.kt
- domain/model/dashboard/DashboardPrimitives.kt
- domain/model/dashboard/FinancialWeather.kt
- domain/model/dashboard/SpendingSummary.kt
- domain/model/navigation/DomainTransactionFilter.kt
- domain/model/recommendation/DashboardFollowThroughRecommendation.kt
- domain/model/recommendation/RecommendationPriority.kt
- domain/model/recommendation/RecommendationStatus.kt
- domain/widget/model/WidgetStyle.kt
- domain/widget/service/WidgetStyleRepository.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | domain/model/dashboard/DashboardExpenseMapper.kt:3-7 | CRITICAL | Architecture | `domain/model` depends directly on `data.database.entity.Expense` and `TransactionType`, and the mapper returns a Room entity from inside the domain package. That is a direct domain→data layer violation. | Move this mapper into the data layer or introduce a pure domain transaction model; keep `domain/model` free of `data.*` imports and Room entity knowledge. |
| 2 | domain/model/dashboard/DashboardExpenseMapper.kt:16-25<br>domain/model/dashboard/DashboardPrimitives.kt:3-12 | HIGH | Logic / Data Loss | `DashboardExpense` carries `effectiveAmount`, but `toEntityExpense()` reconstructs `Expense` with `amount = amount` and without share/split metadata (`isSharedExpense`, `myShareAmount`, `mySharePercentage`, split template fields, etc.). Shared expenses converted through this path regain their full raw amount, so downstream widget/analytics calculations can overcount spending. | Stop converting dashboard DTOs back into `Expense`, or extend `DashboardExpense` so it can reconstruct `Expense` losslessly using the original sharing/split fields. |
| 3 | domain/model/navigation/DomainTransactionFilter.kt:3-4 | CRITICAL | Architecture | The domain filter imports `TransactionType` from `data.database.entity` and `OwnershipFilter` from `data.repository`, so the domain model is coupled to persistence/repository packages instead of owning its own semantic types. | Define domain-level filter enums/types in the domain package and map them at repository/UI boundaries. |
| 4 | domain/model/navigation/DomainTransactionFilter.kt:14 | MEDIUM | Testability / Model Semantics | `correlationId` defaults to `System.currentTimeMillis()` inside a data class. Two logically identical filters are unequal by default, and creation/restoration becomes time-dependent for tests and navigation state. | Move correlation/tracing metadata out of the semantic filter, or inject an ID provider and exclude it from value-equality semantics. |
| 5 | domain/model/recommendation/DashboardFollowThroughRecommendation.kt:27,32-35 | HIGH | Copy Semantics / Testability | The model generates UUID/timestamps inside the constructor and derives `expiresAt` from `createdAt` only at initial construction. `copy(createdAt = ...)` keeps the old `expiresAt`, so the model can represent internally inconsistent TTL data. | Use a factory/service with injected clock and ID generator, and recompute expiry in dedicated creation/update helpers whenever `createdAt` changes. |
| 6 | domain/model/recommendation/DashboardFollowThroughRecommendation.kt:31 | MEDIUM | Architecture | The domain recommendation stores `filterCriteria` as serialized JSON instead of a typed filter object. That forces reparsing for dedup/navigation logic and couples domain behavior to a storage/transport format. | Store a typed `DomainTransactionFilter` in the domain model and serialize only at persistence/network boundaries. |
| 7 | domain/model/dashboard/SpendingSummary.kt:4-8 | MEDIUM | Data Model | The summary mixes `Double` totals with `Float` percentage/history series. For financial data this introduces avoidable precision loss and bakes a UI-friendly numeric type into a domain model. | Use `Double` (or a dedicated money type) consistently in the domain model, converting to `Float` only in UI chart adapters if needed. |
| 8 | domain/widget/model/WidgetStyle.kt:15-39,45-50 | MEDIUM | Model Invariants | `WidgetStyleConfig` accepts any arbitrary `widgetId`, even though only `StyledWidgets.all` is supported. Unsupported IDs can be written and later silently dropped by the repository parser, producing inconsistent write/read behavior. | Model widget IDs as an enum/value object or validate IDs in `setStyle`/`toggleStyle` and reject unsupported entries early. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `DashboardContractsAdapter.observeDashboardExpenses()` → `DashboardExpense` → `DashboardExpenseMapper.toEntityExpense()` → `ComputeDashboardWidgetsUseCase` / `InsightsEngine` | HIGH | Logic / Pipeline Drift | The dashboard pipeline starts with both raw and effective amounts, then converts back to `Expense` through a lossy reverse mapper. Shared-expense metadata is dropped before downstream engines run, so pipeline results can diverge from repository-level spending calculations. | Keep downstream engines on `DashboardExpense`, or pass a lossless domain transaction model instead of reconstructing `Expense`. |
| 2 | `DomainTransactionFilter` → `TransactionFilterSerializer` → `DashboardFollowThroughRecommendation.filterCriteria` → `RecommendationDeduplicator.computeSignature()` | HIGH | Logic | `DomainTransactionFilter` includes `ownership`, and the serializer preserves it, but `RecommendationDeduplicator` does not include `ownership` in its signature. Recommendations that differ only by ownership can be incorrectly collapsed as duplicates. | Include every semantic filter field (including `ownership`) in dedup signatures, or compare typed filters directly. |
| 3 | `DashboardFollowThroughRecommendation.filterCriteria` ↔ `RecommendationRepository.computeSignature()` ↔ `RecommendationDeduplicator.computeSignature()` | MEDIUM | Consistency | In-memory dedup normalizes parsed fields, but repository-level dedup against existing DB rows uses `filterCriteria.hashCode()`. Semantically identical filters with different JSON ordering/formatting can bypass cross-call deduplication. | Canonicalize filters before persistence, or persist and compare a structured/canonical signature derived from a typed filter. |
| 4 | `domain/model/dashboard/SpendingSummary` ↔ `data/repository/AnalyticsRepository.SpendingSummary`<br>`domain/model/dashboard/DashboardCategoryBreakdown` ↔ `domain/analytics/DashboardCategoryBreakdown` | MEDIUM | Duplication / Drift Risk | The batch introduces overlapping dashboard model classes that duplicate or partially duplicate shapes already defined elsewhere. The adapter layer already has to mirror fields, and the two category breakdown types are already diverging. | Consolidate each concept into a single shared model per boundary, or introduce explicitly named mapper DTOs so overlap is intentional and narrow. |
| 5 | `WidgetStyleConfig.setStyle()` / `toggleStyle()` → `WidgetStyleRepositoryImpl.serializeConfig()` / `parseConfig()` | MEDIUM | Consistency | The writer accepts any string key, but the parser restores only keys in `StyledWidgets.all`. Unsupported widget IDs survive in memory until persisted, then disappear on reload. | Enforce the supported widget set at the model/service boundary and make unsupported IDs fail fast. |

## Summary
- Total issues: 13
- Critical: 2, High: 4, Medium: 7, Low: 0
- Files with issues: 6/12 existing source files reviewed *(12 of 14 requested paths existed; `DashboardBlockStatus.kt` and `DashboardDayBudgetStatus.kt` were not present in the repository)*

## Key Patterns
- **Domain layer leakage:** multiple domain models depend directly on `data.*` types or serialized persistence formats.
- **Lossy mirror models:** dashboard-specific DTOs are being converted back into richer entities even though they do not carry enough state to do so safely.
- **Non-deterministic model defaults:** `System.currentTimeMillis()` / `UUID.randomUUID()` are embedded in domain constructors, which hurts reproducibility and copy/update semantics.
- **Duplicate shapes across subsystems:** spending/category models are defined in multiple places with overlapping fields, increasing drift risk.
- **Missing invariants at model boundaries:** supported widget IDs and filter semantics are not enforced where the models are defined.
