# D3 SubBatch D.9 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.9 is stale: 9 rows are resolved and 1 row is partially resolved in current code, but all 17 rows are still listed as open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacements under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 17 SubBatch D.9 rows against current code, classified each, and provided evidence plus exact replacement wording for every status change
- Testing adequate: no - no tests were run in this pass; conclusions are based on direct source inspection of the current worktree

## SubBatch D.9 Audit

1. `ConfidenceRouter` ensureSourceStats timestamps with `System.currentTimeMillis()` — use `timeProvider.now()` (B41)  
   **Status:** STILL_OPEN  
   **Evidence:** `ConfidenceRouter.ensureSourceStats()` still inserts `SourceStats(packageName = packageName)` (`ConfidenceRouter.kt:332-335`), and `SourceStats.lastSeen` still defaults to `System.currentTimeMillis()` (`SourceStats.kt:14`).  
   **Suggested registry wording if status should change:** No status change.

2. `CrossSourceDeduplication.isCrossSourceDuplicate()` doesn't compare real transaction data — redesign API (B41)  
   **Status:** STILL_OPEN  
   **Evidence:** `isCrossSourceDuplicate()` still accepts only `(amount, merchant, date, newSource, existingSources)` and never receives candidate transactions (`CrossSourceDeduplication.kt:45-69`); its bank-source fallback still treats any non-blank merchant as sufficient (`CrossSourceDeduplication.kt:233-255`).  
   **Suggested registry wording if status should change:** No status change.

3. `TransactionClassifier` save/load failures log only message, not exception — use `Timber.e(e, ...)` (B41)  
   **Status:** STILL_OPEN  
   **Evidence:** `saveToDisk()` still logs `Timber.e("Failed to save ML model")` (`TransactionClassifier.kt:394-396`) and `loadFromDisk()` still logs `Timber.e("Failed to load ML model")` (`TransactionClassifier.kt:448-450`) without the caught exception.  
   **Suggested registry wording if status should change:** No status change.

4. `FeatureExtractor.extractFromNotification()` uses wall clock — accept explicit timestamp (B41)  
   **Status:** RESOLVED  
   **Evidence:** `extractFromNotification()` now accepts an explicit `eventTimeMillis` parameter and documents that callers must supply a real event/clock timestamp (`FeatureExtractor.kt:57-72`); it now builds the calendar from that parameter instead of reading `System.currentTimeMillis()` internally (`FeatureExtractor.kt:74-91`).  
   **Suggested registry wording:**
   ```
   - `FeatureExtractor.extractFromNotification()` uses wall clock — accept explicit timestamp (B41) **[RESOLVED - the extractor now accepts an explicit `eventTimeMillis` parameter and no longer reads `System.currentTimeMillis()` internally]**
   ```

5. `MerchantNormalizer` alias persistence stores original `rawName` bypassing length guard — persist sanitized name (B41)  
   **Status:** STILL_OPEN  
   **Evidence:** `normalize()` still truncates oversized input only into local `sanitized`/`cleaned` values (`MerchantNormalizer.kt:62-70`), but alias persistence still writes the original `rawName` in all `linkAliasToCanonical(...)` paths (`MerchantNormalizer.kt:99-109`, `151-152`).  
   **Suggested registry wording if status should change:** No status change.

6. `MerchantNormalizer` logs raw merchant names — hash/anonymize (B42)  
   **Status:** STILL_OPEN  
   **Evidence:** `learnMerchantAlias()` still logs `Timber.i("Learned alias: $rawName -> $brandName")` (`MerchantNormalizer.kt:151-154`), exposing raw merchant/user-entered names in logs.  
   **Suggested registry wording if status should change:** No status change.

7. `HybridExpenseClassifier.initialized` read outside mutex, not `@Volatile` — make `@Volatile` or move inside mutex (B42)  
   **Status:** STILL_OPEN  
   **Evidence:** `initialized` is still a plain `var` (`HybridExpenseClassifier.kt:37-39`) and is still read outside the mutex in `classify()` (`HybridExpenseClassifier.kt:66`) before calling `initialize()`.  
   **Suggested registry wording if status should change:** No status change.

8. `AreaSpendingEngine` grid cells keep first parsed area name — track frequencies, keep most common (B42)  
   **Status:** RESOLVED  
   **Evidence:** the engine now tracks per-cell area candidates with counts and spend (`AreaSpendingEngine.kt:41-54`, `79-82`) and resolves the representative label via `selectRepresentativeAreaName(...)` using count/spend ranking (`AreaSpendingEngine.kt:87-111`, `131-141`) instead of keeping the first parsed name.  
   **Suggested registry wording:**
   ```
   - `AreaSpendingEngine` grid cells keep first parsed area name — track frequencies, keep most common (B42) **[RESOLVED - grid cells now accumulate area-name frequencies/total spend and choose the most representative candidate via `selectRepresentativeAreaName()`]**
   ```

9. `TravelDetectionEngine` destination hints use `split(",").getOrNull(1)` — fall back to first component (B42)  
   **Status:** RESOLVED  
   **Evidence:** `parseDestinationHint()` now returns the second address component when present, but falls back to the first component for single-part addresses (`TravelDetectionEngine.kt:155-166`).  
   **Suggested registry wording:**
   ```
   - `TravelDetectionEngine` destination hints use `split(",").getOrNull(1)` — fall back to first component (B42) **[RESOLVED - destination parsing now returns the second address component when present and falls back to the first component for single-part addresses]**
   ```

10. `SavingsGamificationEngine.goal_crusher` uses `goals.firstOrNull()` — use max normalized progress (B03)  
    **Status:** RESOLVED  
    **Evidence:** `getAchievements()` now computes `bestProgressGoal` with `goals.maxByOrNull { it.currentAmount / it.targetAmount.coerceAtLeast(0.01) }` (`SavingsGamificationEngine.kt:57-59`) and uses that normalized best-progress goal for `goal_crusher` progress (`SavingsGamificationEngine.kt:114-119`).  
    **Suggested registry wording:**
    ```
    - `SavingsGamificationEngine.goal_crusher` uses `goals.firstOrNull()` — use max normalized progress (B03) **[RESOLVED - goal-crusher progress now uses the goal with the highest normalized completion ratio via `maxByOrNull { currentAmount / targetAmount.coerceAtLeast(0.01) }`]**
    ```

11. `SavingsGamificationEngine.unlockedAt` recomputed on each call — persist first-unlock timestamps (B03)  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `getAchievements()` still derives `unlockedAt` values on demand from current goals/contributions (`SavingsGamificationEngine.kt:51-60`), and helper methods still recompute first-threshold / first-completion timestamps from historical events each call (`SavingsGamificationEngine.kt:179-223`, `267-304`); however, those timestamps now come from persisted goal/contribution history rather than the current wall clock.  
    **Suggested registry wording:**
    ```
    - `SavingsGamificationEngine.unlockedAt` recomputed on each call — persist first-unlock timestamps (B03) **[PARTIALLY_RESOLVED - unlock times are now derived from persisted goal/contribution timestamps instead of `now`, but achievement unlock state is still recomputed on each call rather than stored as dedicated first-unlock metadata]**
    ```

12. `goal.currentAmount / goal.targetAmount` unguarded for zero target — guard division (B03)  
    **Status:** RESOLVED  
    **Evidence:** normalized goal-progress calculations now guard targets with `targetAmount.coerceAtLeast(0.01)` in both `bestProgressGoal` selection (`SavingsGamificationEngine.kt:57-59`) and `goal_crusher` progress / unlock evaluation (`SavingsGamificationEngine.kt:117-119`, `217-218`).  
    **Suggested registry wording:**
    ```
    - `goal.currentAmount / goal.targetAmount` unguarded for zero target — guard division (B03) **[RESOLVED - goal-progress calculations now guard zero/invalid targets with `targetAmount.coerceAtLeast(0.01)`]**
    ```

13. `FinancialHealthCalculator.calculateBudgetHealthScore()` accepts `periodExpenses` but never uses it — remove parameter or make period-aware (B03)  
    **Status:** RESOLVED  
    **Evidence:** `calculateBudgetHealthScore()` now accepts only `budgetStatuses` (`FinancialHealthCalculator.kt:232-235`); there is no unused `periodExpenses` parameter left in the current implementation.  
    **Suggested registry wording:**
    ```
    - `FinancialHealthCalculator.calculateBudgetHealthScore()` accepts `periodExpenses` but never uses it — remove parameter or make period-aware (B03) **[RESOLVED - `calculateBudgetHealthScore()` no longer accepts an unused `periodExpenses` parameter]**
    ```

14. `FinancialHealthCalculator.calculateTodayScore()` increments `noSpendStreak` locally — trust supplied streak (B03)  
    **Status:** RESOLVED  
    **Evidence:** today-score bonus logic now passes the caller-supplied `noSpendStreak` through unchanged and only applies it when `spentToday == 0.0` (`FinancialHealthCalculator.kt:106-110`); there is no local increment of the streak anymore.  
    **Suggested registry wording:**
    ```
    - `FinancialHealthCalculator.calculateTodayScore()` increments `noSpendStreak` locally — trust supplied streak (B03) **[RESOLVED - today-score bonus logic now uses the caller-supplied `noSpendStreak` and only applies it when `spentToday == 0.0`]**
    ```

15. `FinancialHealthCalculator` week calculations use locale-dependent `Calendar.firstDayOfWeek` — reuse `TimePeriodUtils` (B03)  
    **Status:** RESOLVED  
    **Evidence:** `calculateWeekScore()` now derives weekly bounds from `TimePeriodUtils.getWeekRange(now)` (`FinancialHealthCalculator.kt:133-145`), and `TimePeriodUtils.getWeekRange(...)` explicitly computes Monday-based ranges without relying on locale `firstDayOfWeek` (`TimePeriodUtils.kt:163-188`).  
    **Suggested registry wording:**
    ```
    - `FinancialHealthCalculator` week calculations use locale-dependent `Calendar.firstDayOfWeek` — reuse `TimePeriodUtils` (B03) **[RESOLVED - weekly ranges now come from locale-independent `TimePeriodUtils.getWeekRange(...)`]**
    ```

16. `FinancialHealthScoreV2` trend compares against `getMostRecent()` without excluding current period — compare against latest different period (B03)  
    **Status:** RESOLVED  
    **Evidence:** trend calculation now calls `healthScoreHistoryDao.getMostRecentBefore(currentPeriodStart, currentPeriodEnd)` (`FinancialHealthScoreV2.kt:416-431`), and the DAO query explicitly excludes the current `(periodStart, periodEnd)` pair (`HealthScoreHistoryDao.kt:46-47`).  
    **Suggested registry wording:**
    ```
    - `FinancialHealthScoreV2` trend compares against `getMostRecent()` without excluding current period — compare against latest different period (B03) **[RESOLVED - trend lookup now uses `healthScoreHistoryDao.getMostRecentBefore(periodStart, periodEnd)` to exclude the current period]**
    ```

17. `FinancialHealthCalculator.budgetStatuses.all { }` vacuously true for empty list — require `isNotEmpty()` (B03)  
    **Status:** STILL_OPEN  
    **Evidence:** all three period-score paths still pass `budgetStatuses.all { it.healthStatus == BudgetHealthStatus.ON_TRACK }` directly into `calculateBonusPoints(...)` (`FinancialHealthCalculator.kt:106-110`, `159-163`, `212-216`), so an empty budget list still earns the `allBudgetsOnTrack` bonus.  
    **Suggested registry wording if status should change:** No status change.

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.9` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `FeatureExtractor.extractFromNotification()` uses wall clock — accept explicit timestamp (B41)
   ```
   with
   ```
   - `FeatureExtractor.extractFromNotification()` uses wall clock — accept explicit timestamp (B41) **[RESOLVED - the extractor now accepts an explicit `eventTimeMillis` parameter and no longer reads `System.currentTimeMillis()` internally]**
   ```

2. Replace
   ```
   - `AreaSpendingEngine` grid cells keep first parsed area name — track frequencies, keep most common (B42)
   ```
   with
   ```
   - `AreaSpendingEngine` grid cells keep first parsed area name — track frequencies, keep most common (B42) **[RESOLVED - grid cells now accumulate area-name frequencies/total spend and choose the most representative candidate via `selectRepresentativeAreaName()`]**
   ```

3. Replace
   ```
   - `TravelDetectionEngine` destination hints use `split(",").getOrNull(1)` — fall back to first component (B42)
   ```
   with
   ```
   - `TravelDetectionEngine` destination hints use `split(",").getOrNull(1)` — fall back to first component (B42) **[RESOLVED - destination parsing now returns the second address component when present and falls back to the first component for single-part addresses]**
   ```

4. Replace
   ```
   - `SavingsGamificationEngine.goal_crusher` uses `goals.firstOrNull()` — use max normalized progress (B03)
   ```
   with
   ```
   - `SavingsGamificationEngine.goal_crusher` uses `goals.firstOrNull()` — use max normalized progress (B03) **[RESOLVED - goal-crusher progress now uses the goal with the highest normalized completion ratio via `maxByOrNull { currentAmount / targetAmount.coerceAtLeast(0.01) }`]**
   ```

5. Replace
   ```
   - `SavingsGamificationEngine.unlockedAt` recomputed on each call — persist first-unlock timestamps (B03)
   ```
   with
   ```
   - `SavingsGamificationEngine.unlockedAt` recomputed on each call — persist first-unlock timestamps (B03) **[PARTIALLY_RESOLVED - unlock times are now derived from persisted goal/contribution timestamps instead of `now`, but achievement unlock state is still recomputed on each call rather than stored as dedicated first-unlock metadata]**
   ```

6. Replace
   ```
   - `goal.currentAmount / goal.targetAmount` unguarded for zero target — guard division (B03)
   ```
   with
   ```
   - `goal.currentAmount / goal.targetAmount` unguarded for zero target — guard division (B03) **[RESOLVED - goal-progress calculations now guard zero/invalid targets with `targetAmount.coerceAtLeast(0.01)`]**
   ```

7. Replace
   ```
   - `FinancialHealthCalculator.calculateBudgetHealthScore()` accepts `periodExpenses` but never uses it — remove parameter or make period-aware (B03)
   ```
   with
   ```
   - `FinancialHealthCalculator.calculateBudgetHealthScore()` accepts `periodExpenses` but never uses it — remove parameter or make period-aware (B03) **[RESOLVED - `calculateBudgetHealthScore()` no longer accepts an unused `periodExpenses` parameter]**
   ```

8. Replace
   ```
   - `FinancialHealthCalculator.calculateTodayScore()` increments `noSpendStreak` locally — trust supplied streak (B03)
   ```
   with
   ```
   - `FinancialHealthCalculator.calculateTodayScore()` increments `noSpendStreak` locally — trust supplied streak (B03) **[RESOLVED - today-score bonus logic now uses the caller-supplied `noSpendStreak` and only applies it when `spentToday == 0.0`]**
   ```

9. Replace
   ```
   - `FinancialHealthCalculator` week calculations use locale-dependent `Calendar.firstDayOfWeek` — reuse `TimePeriodUtils` (B03)
   ```
   with
   ```
   - `FinancialHealthCalculator` week calculations use locale-dependent `Calendar.firstDayOfWeek` — reuse `TimePeriodUtils` (B03) **[RESOLVED - weekly ranges now come from locale-independent `TimePeriodUtils.getWeekRange(...)`]**
   ```

10. Replace
    ```
    - `FinancialHealthScoreV2` trend compares against `getMostRecent()` without excluding current period — compare against latest different period (B03)
    ```
    with
    ```
    - `FinancialHealthScoreV2` trend compares against `getMostRecent()` without excluding current period — compare against latest different period (B03) **[RESOLVED - trend lookup now uses `healthScoreHistoryDao.getMostRecentBefore(periodStart, periodEnd)` to exclude the current period]**
    ```

11. Leave the other 7 SubBatch D.9 bullets unchanged; they are still open in current code.

## Batch 6 Registry Sync Addendum

- D9-1 (`ConfidenceRouter.ensureSourceStats()` wall-clock default): **RESOLVED BY D3-TIME-DETERMINISM**.
- Revalidation outcome: `SourceStats.lastSeen` no longer has a wall-clock default; targeted `SourceStats` creation paths now pass explicit `timeProvider.now()` timestamps.
