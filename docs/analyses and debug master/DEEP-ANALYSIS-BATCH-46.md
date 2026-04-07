# Deep Analysis — Batch 46: Domain Models — Dashboard & Recommendation (@reviewer)

## Scope
- `domain/model/BlockPartyDay.kt`
- `domain/model/CategoryBreakdown.kt`
- `domain/model/CategoryInfo.kt`
- `domain/model/FinancialForecast.kt`
- `domain/model/PeriodDrillDownState.kt`
- `domain/model/PeriodRange.kt`
- `domain/model/PeriodTotal.kt`
- `domain/model/PlannedExpense.kt`
- `domain/model/RecurringPattern.kt`
- `domain/model/Result.kt`
- `domain/model/SavingsGoal.kt`
- `domain/model/UpcomingItem.kt`
- `domain/model/UiText.kt`
- `domain/model/budget/MonteCarloBudgetImpact.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/model/BlockPartyDay.kt:3,17` | CRITICAL | Architecture violation | `BlockPartyDay` is a domain model but imports and exposes `data.database.entity.Expense` directly via `topTransactions`. That inverts the clean-architecture dependency direction and couples every dashboard consumer of this model to Room/entity details such as persistence fields and entity-only helpers. | Replace `List<Expense>` with a small domain DTO/value object (for example `TopTransactionSummary`) and map entity data at the repository/use-case boundary. |
| 2 | `domain/model/FinancialForecast.kt:10` | MEDIUM | Localization / contract inconsistency | `FinancialForecast` carries `actionableInsights: List<String>` even though the same package already has `UiText`, and `NarrativeSection` in this file already uses `UiText` for titles. This hard-wires English/domain-generated copy into the contract and makes forecast text inconsistent with the rest of the dashboard text pipeline. | Change `actionableInsights` to `List<UiText>` or a typed insight model/message-key enum and resolve strings in presentation. |
| 3 | `domain/model/FinancialForecast.kt:16` | MEDIUM | Sentinel value anti-pattern | `ForecastHorizon.REST_OF_MONTH` encodes “calendar-derived duration” as `days = 0`. `0` is a real numeric value, so callers cannot distinguish “no days” from “variable horizon” without special-case knowledge, which is exactly the kind of hidden sentinel the batch asked to avoid. | Model the variable case explicitly (nullable `days`, separate property like `isCalendarBound`, or a sealed horizon type). |
| 4 | `domain/model/PeriodRange.kt:3-9` | MEDIUM | Missing domain invariant | `PeriodRange` accepts any `start`/`end` pair and derives `duration = end - start` without validation. Invalid ranges (`end < start`) silently produce negative durations and misleading `contains()` behavior, which is dangerous because this type is reused for filtering/query windows. | Enforce `require(end >= start)` in `init`, or normalize inputs before constructing the model. |
| 5 | `domain/model/PlannedExpense.kt:6` | MEDIUM | Missing validation | `PlannedExpense.amount` is unrestricted even though the entire forecasting/budgeting pipeline treats this type as future spend and sums it directly into committed/likely totals. A negative “expense” would incorrectly reduce obligations and inflate safe-to-spend calculations. | Enforce non-negative amounts in the model/factory, or introduce a separate model for planned income/credits instead of overloading `PlannedExpense`. |
| 6 | `domain/model/RecurringPattern.kt:19-29` | MEDIUM | Incorrect temporal abstraction | `RecurrenceFrequency.intervalInMs` converts monthly/quarterly/semiannual/annual recurrences into fixed 30/90/180/365 day millisecond intervals. Those are not calendar-correct and will drift across month lengths, leap years, and DST. Even if currently unused, the model exposes a misleading API. | Remove `intervalInMs` from calendar-based frequencies or replace it with a calendar-aware helper outside the model (for example via `RecurrenceCalculator`/`TimePeriodUtils`). |
| 7 | `domain/model/SavingsGoal.kt:10` | MEDIUM | Sentinel default / cross-layer inconsistency | Domain `SavingsGoal` defaults `createdAt` to `0L`, while the persisted entity defaults to the current time. Any domain-created goal that omits `createdAt` becomes an epoch timestamp, which can corrupt sorting/recency logic and makes the domain/entity contracts inconsistent. | Do not default to `0L`; require the value at mapping/construction time, or supply it from an injected clock at the creation boundary. |
| 8 | `domain/model/UpcomingItem.kt:13` | HIGH | Identity collision | `UpcomingItem.Recurring.id` is derived only from `merchantName` (`"recurring_${pattern.merchantName}"`). Multiple recurring rules for the same merchant will collide, which is especially risky for keyed Compose lists, dismissal state, recommendation deduplication, and diffing. The model already has `pattern.id`, but ignores it. | Use `pattern.id` when available; otherwise build a composite from stable fields such as merchant, frequency, category, and `nextExpectedDate`. |
| 9 | `domain/model/budget/MonteCarloBudgetImpact.kt:23-24,40-47` | HIGH | Domain/UI coupling + currency bug | The model stores preformatted UI strings (`displayMessage`, `formattedOverrun`) and its helper hardcodes the euro symbol. That makes the domain contract locale- and currency-specific, so non-EUR users can receive incorrect symbols/messages and the presentation layer cannot format values according to user settings. | Keep only raw numeric/domain values in the model, move message/currency formatting to presentation, and use the app’s selected currency/locale formatter. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `domain/model/CategoryBreakdown.kt` ↔ `domain/analytics/AnalyticsModels.kt` ↔ UI components/screens | HIGH | Overlapping model classes | There are two different `CategoryBreakdown` types in the codebase (`domain.model.CategoryBreakdown` and `domain.analytics.CategoryBreakdown`), and UI code imports both depending on screen/component. This duplicates semantics for the same concept and makes imports, adapters, and future refactors error-prone. | Consolidate to one shared contract or clearly rename/scope the analytics-specific variant with explicit mapper boundaries. |
| 2 | `domain/model/PeriodRange.kt` ↔ `domain/analytics/AdvancedAnalyticsModels.kt` ↔ AI/analytics use cases | HIGH | Overlapping time-range contract | The app maintains two different `PeriodRange` classes with different fields/semantics. That split already forces separate imports in analytics and AI query flows and increases the risk of passing the wrong range type or re-implementing identical helpers twice. | Standardize on one canonical period-range model or introduce an explicit conversion layer with distinct names instead of duplicated type names. |
| 3 | `FinancialForecast` / `MonteCarloBudgetImpact` / `UiText` → dashboard/recommendation UI | MEDIUM | Inconsistent text pipeline | Some dashboard domain models use `UiText`, while others still expose raw English `String` fields (`actionableInsights`, `displayMessage`, `formattedOverrun`). That creates two parallel localization strategies inside the same feature family. | Use a single text contract for domain-to-UI communication (prefer `UiText` or typed message keys) and keep formatting in presentation. |
| 4 | `SynthesisEngine` → `FinancialForecast.generatedAt` | LOW | Testability | The model itself is deterministic, but its primary producer currently stamps `generatedAt` via `Instant.now()` instead of the injected `TimeProvider`, so forecast outputs are not fully deterministic under test. | Feed `generatedAt` from `TimeProvider`/an injected clock and construct `Instant` from that controlled source. |

## Summary
- Total issues: 9
- Critical: 1, High: 2, Medium: 6, Low: 0
- Files with issues: 8/14

## Key Patterns
- Several “domain” models still leak non-domain concerns: `BlockPartyDay` depends on a data entity, while `FinancialForecast` and `MonteCarloBudgetImpact` embed UI/localized string concerns.
- The batch contains multiple sentinel/default-value smells (`REST_OF_MONTH.days = 0`, `SavingsGoal.createdAt = 0L`) instead of explicit modeling.
- Time/range abstractions are inconsistent: `PeriodRange` lacks invariant enforcement, and `RecurringPattern` exposes a fixed-day millisecond interval for calendar-based recurrences.
- There is meaningful model duplication across adjacent packages (`CategoryBreakdown`, `PeriodRange`), which increases mapper churn and import ambiguity.
- Test coverage is uneven: there is direct test coverage for `CategoryBreakdown`/`CategoryInfo`, `PeriodTotal`, and the Monte Carlo/forecast use cases, but no direct regression coverage for `BlockPartyDay`, `PeriodRange`, `PlannedExpense`, `RecurringPattern`, `SavingsGoal`, `UpcomingItem`, `UiText`, or the reported identity/sentinel/validation edge cases.
