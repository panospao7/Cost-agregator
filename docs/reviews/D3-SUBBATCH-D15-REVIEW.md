# D3 SubBatch D.15 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.15 is stale: 6 rows are resolved and 1 row is partially resolved in current code but are still listed as open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacement lines under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 14 SubBatch D.15 issues against current code, classified each, captured brief evidence, and provided exact registry replacement text where status changes
- Testing adequate: no - no tests were run in this pass; conclusions are based on direct source inspection of the current worktree

## SubBatch D.15 Audit

1. `DayOfWeekAnalyzer` results sorted by total spend instead of weekday order — sort by day-of-week index (B36-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `DayOfWeekAnalyzer.analyze()` still builds Monday→Sunday entries but returns them with `.sortedByDescending { it.totalSpent }` (`DayOfWeekAnalyzer.kt:32-45`), so chronological weekday order is still lost.  
   **Suggested registry wording if status should change:** No change.

2. `TransferDirectionAnalytics` corrections only update accuracy counters, not incoming/outgoing totals or source/destination lists — rebuild full stats on correction (B36-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `recordUserCorrection(...)` only calls `adjustCorrectDetections(delta)` (`TransferDirectionAnalytics.kt:183-222`). It never adjusts `autoDetectedIncoming`, `autoDetectedOutgoing`, `incomingSources`, or `outgoingDestinations`, so corrected direction totals/lists remain stale.  
   **Suggested registry wording if status should change:** No change.

3. `ExpenseDao` weekly aggregates expose `MIN(date)/MAX(date)` transaction timestamps as week boundaries — use explicit period start/end from calendar (B36-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `getWeeklyTotalsForPeriod()` still selects `MIN(date) as startDate` and `MAX(date) as endDate` while grouping by `strftime('%Y-%W', ...)` (`ExpenseDao.kt:1549-1562`), so the returned week boundaries are still first/last transaction timestamps rather than canonical calendar week bounds.  
   **Suggested registry wording if status should change:** No change.

4. `CrossSourceDeduplication` candidate ranking ignores time distance and merchant similarity when multiple candidates pass hard filters — weight by time delta and merchant score (B40-missed)  
   **Status:** RESOLVED  
   **Evidence:** both `findPendingReviewDuplicate()` and `findExpenseDuplicate()` now build `DuplicateDetectionPolicy.ScoredCandidate(...)` entries with `timeDeltaMs`, `amountDelta`, and `merchantConfidence`, then return `DuplicateDetectionPolicy.bestCandidate(...)` (`CrossSourceDeduplication.kt:106-145`, `178-208`). `DuplicateDetectionPolicy.rankCandidates()` now orders by time delta, amount delta, then descending merchant confidence/location boost (`DuplicateDetectionPolicy.kt:185-216`).  
   **Suggested registry wording:**
   ```
   - `CrossSourceDeduplication` candidate ranking ignores time distance and merchant similarity when multiple candidates pass hard filters — weight by time delta and merchant score (B40-missed) **[RESOLVED - duplicate selection now builds `ScoredCandidate`s and routes tie-breaks through `DuplicateDetectionPolicy.bestCandidate()`, which ranks by time delta, amount delta, and merchant confidence]**
   ```

5. `BudgetAutopilotEngine` history fetched through `ExpenseRepository.getExpensesBetween()` — inherits 2000-row cap; add uncapped variant (B37-missed)  
   **Status:** RESOLVED  
   **Evidence:** `getHistoricalSpendForBudget()` no longer uses repository row reads; it now pulls aggregate monthly totals from `expenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` (`BudgetAutopilotEngine.kt:177-200`), which avoids the old capped raw-history path.  
   **Suggested registry wording:**
   ```
   - `BudgetAutopilotEngine` history fetched through `ExpenseRepository.getExpensesBetween()` — inherits 2000-row cap; add uncapped variant (B37-missed) **[RESOLVED - historical spend now comes from `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` aggregate SQL instead of capped raw row reads]**
   ```

6. `BudgetForecastingEngine` historical reads call `getExpensesByCategory()`/`getExpensesByTypeBetween()` without overriding default limit — add uncapped variant (B37-missed)  
   **Status:** RESOLVED  
   **Evidence:** `getHistoricalSpendingData()` now uses `expenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` (`BudgetForecastingEngine.kt:114-150`) and no longer relies on capped `getExpensesByCategory()` / `getExpensesByTypeBetween()` history reads.  
   **Suggested registry wording:**
   ```
   - `BudgetForecastingEngine` historical reads call `getExpensesByCategory()`/`getExpensesByTypeBetween()` without overriding default limit — add uncapped variant (B37-missed) **[RESOLVED - historical spend now uses `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` aggregate SQL instead of capped raw history queries]**
   ```

7. `BudgetAutopilotEngine` and `BudgetForecastingEngine` use different month bucketing and timezone rules — centralize through shared period calculator (B37-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** the two engines still maintain separate local month-key helpers (`BudgetAutopilotEngine.kt:373-405`, `BudgetForecastingEngine.kt:355-394`) and still bucket history differently: autopilot infills only from `sortedMonthKeys.first()` to `sortedMonthKeys.last()` (`BudgetAutopilotEngine.kt:215-218`), while forecasting builds the range from `formatMonthKey(threeMonthsAgo)` to `formatMonthKey(now)` (`BudgetForecastingEngine.kt:142-150`). Their month-history windows are still not centralized or identical.  
   **Suggested registry wording if status should change:** No change.

8. `InsightsEngine` composes analytics engines but reimplements their logic inline — delegate to engines to prevent drift (B36-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `InsightsEngine` injects `monthlyComparisonCalculator`, `categoryInsightEngine`, `merchantInsightEngine`, and `dayOfWeekAnalyzer` (`InsightsEngine.kt:23-33`), but `generateInsights()` still calls local `buildMonthlyComparison()`, `buildCategoryInsights()`, `buildMerchantInsights()`, and `buildDayOfWeekPattern()` helpers (`InsightsEngine.kt:51-72`) whose implementations re-query/recompute the analytics inline (`InsightsEngine.kt:240-332`, `366-395`, `598-619`).  
   **Suggested registry wording if status should change:** No change.

9. `SplitCalculator` converts money to cents with `Int` — amounts above ~€21.47M overflow; use `Long` (B43-missed)  
   **Status:** RESOLVED  
   **Evidence:** cent conversion now uses `private fun toCents(amount: Double): Long` and the split calculations carry `Long` cent values (`SplitCalculator.kt:109-114`, `233-285`, `297-307`), so the old `Int` overflow path is gone.  
   **Suggested registry wording:**
   ```
   - `SplitCalculator` converts money to cents with `Int` — amounts above ~€21.47M overflow; use `Long` (B43-missed) **[RESOLVED - cent conversion and split math now use `Long` (`toCents(): Long`, `baseCents`, `centsByMember`), removing `Int` overflow for high-value amounts]**
   ```

10. `ReviewPriorityScorer` batch scoring pre-computes `duplicateRisk` but `calculateBaseScore()` uses placeholder `0.5f` — feed computed risk into single-item scoring path (B34-missed)  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `scoreSingle()` now delegates to `scoreReviews(listOf(review))` (`OnDeviceReviewPriorityScorer.kt:103-111`), so the normal single-item scoring path reuses the batch duplicate-risk calculation. But `calculateBaseScore()` still constructs `ReviewPriorityFactors.fromReview(...)` (`OnDeviceReviewPriorityScorer.kt:114-117`), and that factory still hardcodes `duplicateRisk = 0.5f` (`ReviewPriorityModels.kt:50-58`). `PrioritizeReviewItemsUseCase.quickScore()` still calls `calculateBaseScore()` directly (`PrioritizeReviewItemsUseCase.kt:75-76`).  
    **Suggested registry wording:**
    ```
    - `ReviewPriorityScorer` batch scoring pre-computes `duplicateRisk` but `calculateBaseScore()` uses placeholder `0.5f` — feed computed risk into single-item scoring path (B34-missed) **[PARTIALLY_RESOLVED - `scoreSingle()` now delegates to `scoreReviews(listOf(review))`, but `calculateBaseScore()` still relies on `ReviewPriorityFactors.fromReview(...)` with placeholder `duplicateRisk = 0.5f`, so quick/base scoring still diverges]**
    ```

11. `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records impression — record impression before returning so cooldown starts at show time (B33-missed)  
    **Status:** RESOLVED  
    **Evidence:** `evaluateAndPrompt()` now calls `promptStateRepository.recordPrompt(PROMPT_TYPE)` immediately before returning the recommendation (`LifestyleSavingsPromptUseCase.kt:122-135`), so the cooldown now starts when the prompt is shown.  
    **Suggested registry wording:**
    ```
    - `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records impression — record impression before returning so cooldown starts at show time (B33-missed) **[RESOLVED - `evaluateAndPrompt()` now calls `promptStateRepository.recordPrompt(PROMPT_TYPE)` before returning the recommendation, so cooldown starts at show time]**
    ```

12. `RecommendationDeduplicator.computeSignature()` omits `ownership` — include ownership in signature (B47-missed)  
    **Status:** RESOLVED  
    **Evidence:** `computeSignature()` now includes `filter.ownership?.let { filterParts.add("ownership=${it.name}") }` (`RecommendationDeduplicator.kt:83-99`), so ownership-distinct recommendations no longer collapse to the same signature.  
    **Suggested registry wording:**
    ```
    - `RecommendationDeduplicator.computeSignature()` omits `ownership` — include ownership in signature (B47-missed) **[RESOLVED - `computeSignature()` now includes `filter.ownership` in the serialized filter signature, so ownership-distinct recommendations are no longer deduplicated together]**
    ```

13. `AdvancedAnalyticsEngine` merchant analytics groups by raw `merchant`, re-filters full 6-month history per merchant — aliases fragment results, O(merchants × history) runtime (B01)  
    **Status:** STILL_OPEN  
    **Evidence:** `getMerchantAnalytics()` still does `currentPurchases.groupBy { it.merchant }` (`AdvancedAnalyticsEngine.kt:253-255`) and, for each group, scans the full historical list with `historicalExpenses.filter { ... it.merchant.equals(merchant, ignoreCase = true) }` (`AdvancedAnalyticsEngine.kt:261-266`), so alias fragmentation and O(merchants × history) behavior remain.  
    **Suggested registry wording if status should change:** No change.

14. `AdvancedAnalyticsDashboard` `generateDashboardData()` hardcodes `Dispatchers.IO` (B01)  
    **Status:** STILL_OPEN  
    **Evidence:** `generateDashboardData()` still wraps its body in `withContext(Dispatchers.IO)` (`AdvancedAnalyticsDashboard.kt:84-87`) instead of using an injected dispatcher.  
    **Suggested registry wording if status should change:** No change.

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.15` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `CrossSourceDeduplication` candidate ranking ignores time distance and merchant similarity when multiple candidates pass hard filters — weight by time delta and merchant score (B40-missed)
   ```
   with
   ```
   - `CrossSourceDeduplication` candidate ranking ignores time distance and merchant similarity when multiple candidates pass hard filters — weight by time delta and merchant score (B40-missed) **[RESOLVED - duplicate selection now builds `ScoredCandidate`s and routes tie-breaks through `DuplicateDetectionPolicy.bestCandidate()`, which ranks by time delta, amount delta, and merchant confidence]**
   ```

2. Replace
   ```
   - `BudgetAutopilotEngine` history fetched through `ExpenseRepository.getExpensesBetween()` — inherits 2000-row cap; add uncapped variant (B37-missed)
   ```
   with
   ```
   - `BudgetAutopilotEngine` history fetched through `ExpenseRepository.getExpensesBetween()` — inherits 2000-row cap; add uncapped variant (B37-missed) **[RESOLVED - historical spend now comes from `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` aggregate SQL instead of capped raw row reads]**
   ```

3. Replace
   ```
   - `BudgetForecastingEngine` historical reads call `getExpensesByCategory()`/`getExpensesByTypeBetween()` without overriding default limit — add uncapped variant (B37-missed)
   ```
   with
   ```
   - `BudgetForecastingEngine` historical reads call `getExpensesByCategory()`/`getExpensesByTypeBetween()` without overriding default limit — add uncapped variant (B37-missed) **[RESOLVED - historical spend now uses `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` aggregate SQL instead of capped raw history queries]**
   ```

4. Replace
   ```
   - `SplitCalculator` converts money to cents with `Int` — amounts above ~€21.47M overflow; use `Long` (B43-missed)
   ```
   with
   ```
   - `SplitCalculator` converts money to cents with `Int` — amounts above ~€21.47M overflow; use `Long` (B43-missed) **[RESOLVED - cent conversion and split math now use `Long` (`toCents(): Long`, `baseCents`, `centsByMember`), removing `Int` overflow for high-value amounts]**
   ```

5. Replace
   ```
   - `ReviewPriorityScorer` batch scoring pre-computes `duplicateRisk` but `calculateBaseScore()` uses placeholder `0.5f` — feed computed risk into single-item scoring path (B34-missed)
   ```
   with
   ```
   - `ReviewPriorityScorer` batch scoring pre-computes `duplicateRisk` but `calculateBaseScore()` uses placeholder `0.5f` — feed computed risk into single-item scoring path (B34-missed) **[PARTIALLY_RESOLVED - `scoreSingle()` now delegates to `scoreReviews(listOf(review))`, but `calculateBaseScore()` still relies on `ReviewPriorityFactors.fromReview(...)` with placeholder `duplicateRisk = 0.5f`, so quick/base scoring still diverges]**
   ```

6. Replace
   ```
   - `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records impression — record impression before returning so cooldown starts at show time (B33-missed)
   ```
   with
   ```
   - `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records impression — record impression before returning so cooldown starts at show time (B33-missed) **[RESOLVED - `evaluateAndPrompt()` now calls `promptStateRepository.recordPrompt(PROMPT_TYPE)` before returning the recommendation, so cooldown starts at show time]**
   ```

7. Replace
   ```
   - `RecommendationDeduplicator.computeSignature()` omits `ownership` — include ownership in signature (B47-missed)
   ```
   with
   ```
   - `RecommendationDeduplicator.computeSignature()` omits `ownership` — include ownership in signature (B47-missed) **[RESOLVED - `computeSignature()` now includes `filter.ownership` in the serialized filter signature, so ownership-distinct recommendations are no longer deduplicated together]**
   ```

8. Leave the other 7 SubBatch D.15 bullets unchanged; they are still open in current code.

## Batch 6 Registry Sync Addendum

- D15-1 (`DayOfWeekAnalyzer` weekday order): **RESOLVED BY D3-TIME-DETERMINISM**.
- D15-3 (`ExpenseDao` weekly boundary semantics): **RESOLVED BY D3-TIME-DETERMINISM** (repository normalization to canonical Monday week ranges).
- D15-7 (`BudgetAutopilotEngine` / `BudgetForecastingEngine` month bucketing divergence): **RESOLVED BY D3-TIME-DETERMINISM**.
