# D3 SubBatch D.13 Review

Scope audited: `MASTER-ISSUE-REGISTRY.md` → `### SubBatch D.13`

Reference context read first:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/AUDIT-PHASE-C-D.md`

## Summary

- Total issues audited: **19**
- **RESOLVED:** 10
- **PARTIALLY_RESOLVED:** 2
- **STILL_OPEN:** 7
- **FALSE_POSITIVE:** 0

## Issue-by-issue audit

### D13-01
- Registry issue: `FinancialForecast.generatedAt` uses `Instant.now()` instead of `TimeProvider`
- Status: **RESOLVED**
- Evidence: `FinancialForecast.kt:5-10` only declares the field; current forecast creation in `SynthesisEngine.kt:69-71` and `:254-256` sets `generatedAt = Instant.ofEpochMilli(timeProvider.now())`.
- Suggested registry wording:
  - `- `FinancialForecast.generatedAt` is now sourced from `timeProvider.now()` at forecast creation sites (`SynthesisEngine`) instead of `Instant.now()` (B46-missed) **[RESOLVED]**`

### D13-02
- Registry issue: `CalculateFinancialForecastUseCase` fabricated `SpendingPace` with `projectedTotal = monthSpent`, fixed `ON_PACE`
- Status: **RESOLVED**
- Evidence: `CalculateFinancialForecastUseCase.kt:168-227` now computes `projectedTotal` via `SpendingPaceProjection.calculateProjectedTotal(...)` and derives `paceStatus` from thresholded `pacePercentage`.
- Suggested registry wording:
  - `- `CalculateFinancialForecastUseCase` now builds `SpendingPace` from real owned-purchase history, using `SpendingPaceProjection.calculateProjectedTotal(...)` and dynamic `paceStatus` instead of fabricated `projectedTotal = monthSpent` / fixed `ON_PACE` (B48) **[RESOLVED]**`

### D13-03
- Registry issue: `CalculateFinancialForecastUseCase` passes `pastSumDaily = emptyList()`
- Status: **RESOLVED**
- Evidence: `CalculateFinancialForecastUseCase.kt:125,146-166` now builds cumulative daily spend with `buildPastSumDaily(...)` and passes that result into `synthesisEngine.synthesize(...)`.
- Suggested registry wording:
  - `- `CalculateFinancialForecastUseCase` now builds cumulative `pastSumDaily` from current-month owned purchases via `buildPastSumDaily(...)` instead of passing `emptyList()` (B48) **[RESOLVED]**`

### D13-04
- Registry issue: `DashboardContractsAdapter.observeDashboardExpenses()` snapshots month once
- Status: **RESOLVED**
- Evidence: `DashboardContractsAdapter.kt:52-59` now drives the query from `timeBoundaryTicker.dayBoundaryTicks()` and recalculates `(monthStart, monthEnd)` on each tick.
- Suggested registry wording:
  - `- `DashboardContractsAdapter.observeDashboardExpenses()` now re-derives the month window from `timeBoundaryTicker.dayBoundaryTicks()` and `TimePeriodUtils.getMonthRange(now)` instead of snapshotting the month once (B48) **[RESOLVED]**`

### D13-05
- Registry issue: `DashboardDataProvider` flows silently replace failures with empty/default
- Status: **STILL_OPEN**
- Evidence: `DashboardDataProvider.kt:42-57`, `:88-101`, and `:113-135` still use `.catch { emit(emptyList()/0/default objects) }` with no logging or surfaced error state.
- Suggested registry wording: no change.

### D13-06
- Registry issue: `GroupsModule` unused imports
- Status: **STILL_OPEN**
- Evidence: `GroupsModule.kt:10-11` still imports `SettlementCalculator` and `SharedExpenseManager`, but the module never references either symbol.
- Suggested registry wording: no change.

### D13-07
- Registry issue: `EmailIngestionModule` provides parser singletons but `EmailReceiptIngestionService` manually constructs them
- Status: **STILL_OPEN**
- Evidence: `EmailIngestionModule.kt:22-38` still provides parser bindings, while `EmailReceiptIngestionService.kt:111-114` still creates `AmazonReceiptParser()`, `UberReceiptParser()`, and `AppleReceiptParser()` directly.
- Suggested registry wording: no change.

### D13-08
- Registry issue: `ExportOptionsViewModel` constructs exporters directly instead of using Hilt-provided instances
- Status: **RESOLVED**
- Evidence: `ExportOptionsViewModel.kt:56-63` injects `XeroCSVExporter`, `QuickBooksIIFExporter`, and `FreshBooksExporter`; `ExportModule.kt:16-26` provides those bindings.
- Suggested registry wording:
  - `- `ExportOptionsViewModel` now receives `XeroCSVExporter`, `QuickBooksIIFExporter`, and `FreshBooksExporter` via Hilt; direct construction path is gone (B22-missed) **[RESOLVED]**`

### D13-09
- Registry issue: `LifecycleObserver.onStop()` cancels `TransactionClassifier` singleton scope
- Status: **RESOLVED**
- Evidence: `ExpenseTrackerApp.kt:87-93` now calls `transactionClassifier.get().onBackground()`, and `TransactionClassifier.kt:46-53` shows `onBackground()` only cancels child jobs, not the parent scope.
- Suggested registry wording:
  - `- `LifecycleObserver.onStop()` now calls `TransactionClassifier.onBackground()` instead of canceling the singleton scope; routine backgrounding no longer destroys classifier work scheduling (B22) **[RESOLVED]**`

### D13-10
- Registry issue: `BudgetMonitor.cleanup()` cancels `serviceJob` on every `onStop()`
- Status: **RESOLVED**
- Evidence: `ExpenseTrackerApp.kt:92-93` now calls `budgetMonitor.get().onBackground()`, and `BudgetMonitor.kt:51-58` shows that path only cancels children and clears transient state, leaving the scope reusable.
- Suggested registry wording:
  - `- `BudgetMonitor` is no longer destroyed on every `onStop()`; app lifecycle now calls non-destructive `onBackground()` and leaves the monitor scope reusable (B22) **[RESOLVED]**`

### D13-11
- Registry issue: `SavingsModule` engines depend on `data.repository.SavingsGoalRepository` instead of domain abstraction
- Status: **PARTIALLY_RESOLVED**
- Evidence: `SavingsModule.kt:77-85` already uses domain `SavingsGoalRepository` for `SavingsGamificationEngine`, but `SavingsModule.kt:21-37` and `:61-73` still wire `SmartSavingsEngine` and `AutomatedSavingsRuleEngine` to `data.repository.SavingsGoalRepository`; the engines themselves still import the data type in `SmartSavingsEngine.kt:9` and `AutomatedSavingsRuleEngine.kt:10`.
- Suggested registry wording:
  - `- `SavingsModule` is only partially migrated off `data.repository.SavingsGoalRepository`: `SavingsGamificationEngine` now uses the domain `SavingsGoalRepository`, but `SmartSavingsEngine` and `AutomatedSavingsRuleEngine` still depend on the data repository type (B22) **[PARTIALLY_RESOLVED]**`

### D13-12
- Registry issue: `AiSettingsRepositoryImpl.settings()` lacks `IOException` recovery
- Status: **RESOLVED**
- Evidence: `AiSettingsRepositoryImpl.kt:65-77` now catches `IOException`, logs it, and emits `emptyPreferences()`.
- Suggested registry wording:
  - `- `AiSettingsRepositoryImpl.settings()` now recovers from `IOException` via `catch { emit(emptyPreferences()) }` (B06, B34) **[RESOLVED]**`

### D13-13
- Registry issue: `DefaultAiCapabilityRouter` disabled-route reasons interpolate raw enum names
- Status: **PARTIALLY_RESOLVED**
- Evidence: `DefaultAiCapabilityRouter.kt:228` now uses `capability.displayName()` in one unavailable-reason path, but the early disabled-capability branch at `:32-34` still returns `"$capability is disabled in settings."`.
- Suggested registry wording:
  - `- `DefaultAiCapabilityRouter` is only partially fixed: some unavailable-route messages use `displayName()`, but the disabled-capability fast path still returns raw enum text (`"$capability is disabled in settings."`) (B06) **[PARTIALLY_RESOLVED]**`

### D13-14
- Registry issue: `GetAiRuntimeStatusUseCase.highestPriorityMessage` is first-match not severity-ranked
- Status: **STILL_OPEN**
- Evidence: `GetAiRuntimeStatusUseCase.kt:49-51` still sets `highestPriorityMessage` with `statuses.firstOrNull { it.message != null }` and does not rank by severity.
- Suggested registry wording: no change.

### D13-15
- Registry issue: `OnDeviceDedupeJudgeService` raw `Enum.valueOf()` calls
- Status: **STILL_OPEN**
- Evidence: `OnDeviceDedupeJudgeService.kt:94-99` still parses `DuplicateVerdict` and `AiTargetType` via raw `valueOf(...)`.
- Suggested registry wording: no change.

### D13-16
- Registry issue: `HybridReceiptAssistService.lastUsedImageInput` mutable singleton state
- Status: **RESOLVED**
- Evidence: `HybridReceiptAssistService.kt:34-39` documents that the shared mutable field path is gone and image-usage reporting now lives in per-request `ReceiptAssistSuggestion.usedImageInput`.
- Suggested registry wording:
  - `- `HybridReceiptAssistService` no longer keeps `lastUsedImageInput` shared mutable state; image-usage reporting is per-request via `ReceiptAssistSuggestion.usedImageInput` (B10) **[RESOLVED]**`

### D13-17
- Registry issue: `CloudPiiSanitizer.PHONE_REGEX` broad enough to redact non-phone numeric text
- Status: **STILL_OPEN**
- Evidence: `CloudPiiSanitizer.kt:9` still uses the broad pattern `\+?\d[\d\s().-]{6,}\d`, which can match generic long numeric text rather than phone-only formats.
- Suggested registry wording: no change.

### D13-18
- Registry issue: `CloudJsonParser.extractFirstJsonObject()` returns first brace-balanced block, not first valid JSON
- Status: **RESOLVED**
- Evidence: `CloudJsonParser.kt:12-18` and `:48-53` now parse each brace-balanced candidate with `JSONObject(candidate)` and only return validated JSON.
- Suggested registry wording:
  - `- `CloudJsonParser.extractFirstJsonObject()` now validates each brace-balanced candidate by parsing it as `JSONObject` before returning it, instead of returning the first balanced block blindly (B10) **[RESOLVED]**`

### D13-19
- Registry issue: `CloudCorrelation` keeps only 8 chars of UUID
- Status: **STILL_OPEN**
- Evidence: `CloudCorrelation.kt:5-6` still returns `UUID.randomUUID().toString().take(8)`, and that helper is still consumed by multiple cloud services.
- Suggested registry wording: no change.

## Registry Update Instructions

Apply the following updates under `### SubBatch D.13` in `MASTER-ISSUE-REGISTRY.md`:

1. **Replace line 992** with:
   - `FinancialForecast.generatedAt` is now sourced from `timeProvider.now()` at forecast creation sites (`SynthesisEngine`) instead of `Instant.now()` (B46-missed) **[RESOLVED]**

2. **Replace line 993** with:
   - `CalculateFinancialForecastUseCase` now builds `SpendingPace` from real owned-purchase history, using `SpendingPaceProjection.calculateProjectedTotal(...)` and dynamic `paceStatus` instead of fabricated `projectedTotal = monthSpent` / fixed `ON_PACE` (B48) **[RESOLVED]**

3. **Replace line 994** with:
   - `CalculateFinancialForecastUseCase` now builds cumulative `pastSumDaily` from current-month owned purchases via `buildPastSumDaily(...)` instead of passing `emptyList()` (B48) **[RESOLVED]**

4. **Replace line 995** with:
   - `DashboardContractsAdapter.observeDashboardExpenses()` now re-derives the month window from `timeBoundaryTicker.dayBoundaryTicks()` and `TimePeriodUtils.getMonthRange(now)` instead of snapshotting the month once (B48) **[RESOLVED]**

5. **Leave lines 996-998 unchanged** (`DashboardDataProvider`, `GroupsModule`, `EmailIngestionModule`) — all three are still open.

6. **Replace line 999** with:
   - `ExportOptionsViewModel` now receives `XeroCSVExporter`, `QuickBooksIIFExporter`, and `FreshBooksExporter` via Hilt; direct construction path is gone (B22-missed) **[RESOLVED]**

7. **Replace line 1000** with:
   - `LifecycleObserver.onStop()` now calls `TransactionClassifier.onBackground()` instead of canceling the singleton scope; routine backgrounding no longer destroys classifier work scheduling (B22) **[RESOLVED]**

8. **Replace line 1001** with:
   - `BudgetMonitor` is no longer destroyed on every `onStop()`; app lifecycle now calls non-destructive `onBackground()` and leaves the monitor scope reusable (B22) **[RESOLVED]**

9. **Replace line 1002** with:
   - `SavingsModule` is only partially migrated off `data.repository.SavingsGoalRepository`: `SavingsGamificationEngine` now uses the domain `SavingsGoalRepository`, but `SmartSavingsEngine` and `AutomatedSavingsRuleEngine` still depend on the data repository type (B22) **[PARTIALLY_RESOLVED]**

10. **Replace line 1003** with:
    - `AiSettingsRepositoryImpl.settings()` now recovers from `IOException` via `catch { emit(emptyPreferences()) }` (B06, B34) **[RESOLVED]**

11. **Replace line 1004** with:
    - `DefaultAiCapabilityRouter` is only partially fixed: some unavailable-route messages use `displayName()`, but the disabled-capability fast path still returns raw enum text (`"$capability is disabled in settings."`) (B06) **[PARTIALLY_RESOLVED]**

12. **Leave lines 1005-1006 unchanged** (`GetAiRuntimeStatusUseCase`, `OnDeviceDedupeJudgeService`) — both are still open.

13. **Replace line 1007** with:
    - `HybridReceiptAssistService` no longer keeps `lastUsedImageInput` shared mutable state; image-usage reporting is per-request via `ReceiptAssistSuggestion.usedImageInput` (B10) **[RESOLVED]**

14. **Leave line 1008 unchanged** (`CloudPiiSanitizer.PHONE_REGEX`) — still open.

15. **Replace line 1009** with:
    - `CloudJsonParser.extractFirstJsonObject()` now validates each brace-balanced candidate by parsing it as `JSONObject` before returning it, instead of returning the first balanced block blindly (B10) **[RESOLVED]**

16. **Leave line 1010 unchanged** (`CloudCorrelation`) — still open.
