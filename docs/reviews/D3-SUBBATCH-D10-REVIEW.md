# D3 SubBatch D.10 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.10 is stale: 6 rows are resolved and 1 row is partially resolved in current code but are still listed as open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacement lines under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 16 SubBatch D.10 issues against current code, classified each, captured brief evidence, and provided exact registry replacement text where status changes
- Testing adequate: no - no tests were run in this pass; conclusions are based on direct source inspection of the current worktree

## SubBatch D.10 Audit

1. `FinancialHealthCalculator` legacy score capped at 70, `EXCELLENT (85-100)` unreachable — rebalance weights (B41)  
   **Status:** STILL_OPEN  
   **Evidence:** `FinancialHealthCalculator.kt` still caps each period at `budgetHealth <= 25`, `spendingControl <= 25`, `cleanliness <= 10`, and an effective bonus max of `10`, so the per-period ceiling remains `70` (`232-250`, `253-331`, `334-366`). `HealthScoreResult.getCompositeStatus()` still labels `85..100` as `EXCELLENT` (`463-469`), making that range unreachable.  
   **Suggested registry wording if status should change:** No change.

2. `FinancialHealthScoreV2` on exception returns synthetic score of 50 — add explicit fallback flag (B41)  
   **Status:** RESOLVED  
   **Evidence:** `FinancialHealthScoreV2.calculateHealthScore()` now rethrows `CancellationException` and also rethrows generic exceptions after logging (`183-188`); it no longer returns a synthetic score on failure.  
   **Suggested registry wording:**
   ```
   - `FinancialHealthScoreV2` on exception returns synthetic score of 50 — add explicit fallback flag (B41) **[RESOLVED - top-level `calculateHealthScore()` now rethrows non-cancellation exceptions after logging instead of returning a synthetic score]**
   ```

3. `RecurringIncomeTracker` confidence compares ms-squared variance against tiny threshold — normalize to days (B41)  
   **Status:** STILL_OPEN  
   **Evidence:** recurring detection still computes variance from raw millisecond intervals (`RecurringIncomeTracker.kt:57-61`), then `calculateConfidence()` still compares that ms-squared value to a fixed `1_000_000_000` threshold (`153-156`) instead of normalizing variance to day-scale semantics.  
   **Suggested registry wording if status should change:** No change.

4. `RecurringIncomeTracker.getStartOfMonth()` leaves milliseconds untouched — set `MILLISECOND = 0` (B41)  
   **Status:** RESOLVED  
   **Evidence:** `getStartOfMonth()` now explicitly clears `Calendar.MILLISECOND` before returning (`RecurringIncomeTracker.kt:159-167`).  
   **Suggested registry wording:**
   ```
   - `RecurringIncomeTracker.getStartOfMonth()` leaves milliseconds untouched — set `MILLISECOND = 0` (B41) **[RESOLVED - `getStartOfMonth()` now explicitly clears `Calendar.MILLISECOND`]**
   ```

5. `SpendingChallengeManager.durationDays * 24 * 60 * 60 * 1000L` overflow — cast to `Long` first (B38)  
   **Status:** RESOLVED  
   **Evidence:** challenge end dates and reduce-spending baseline windows now use `durationDays.toLong() * DAY_MS` (`SpendingChallengeManager.kt:112`, `181`), so the multiplication no longer depends on `Int` width.  
   **Suggested registry wording:**
   ```
   - `SpendingChallengeManager.durationDays * 24 * 60 * 60 * 1000L` overflow — cast to `Long` first (B38) **[RESOLVED - challenge date math now uses `durationDays.toLong() * DAY_MS` for both challenge end dates and reduce-spending baselines]**
   ```

6. `SpendingChallengeManager.daysRemaining` can go negative — clamp with `coerceAtLeast(0)` (B38)  
   **Status:** RESOLVED  
   **Evidence:** `getChallengeProgress()` now computes `daysRemaining` with `.coerceAtLeast(0)` (`SpendingChallengeManager.kt:137`).  
   **Suggested registry wording:**
   ```
   - `SpendingChallengeManager.daysRemaining` can go negative — clamp with `coerceAtLeast(0)` (B38) **[RESOLVED - `daysRemaining` is now clamped with `.coerceAtLeast(0)` in `getChallengeProgress()`]**
   ```

7. `SpendingChallengeManager` IDs use `System.currentTimeMillis()` — use UUID (B38)  
   **Status:** RESOLVED  
   **Evidence:** `createChallenge()` now creates `SpendingChallenge(id = 0, ...)` (`SpendingChallengeManager.kt:106-120`), and persistence flows through Room auto-generated IDs (`SpendingChallengeRepository.kt:29-37`; `SpendingChallengeDao.kt:31-32`) instead of timestamp-based IDs.  
   **Suggested registry wording:**
   ```
   - `SpendingChallengeManager` IDs use `System.currentTimeMillis()` — use UUID (B38) **[RESOLVED - challenge creation now persists `id = 0` and relies on Room auto-generated IDs instead of timestamp-based IDs]**
   ```

8. `CategorizationEngine` reloads cache fragments via three accessors per call — fetch one snapshot (B38)  
   **Status:** PARTIALLY_RESOLVED  
   **Evidence:** cache refresh is now centralized in `getCacheData()` under one mutex (`CategorizationEngine.kt:413-428`), but `categorize()` still calls `getCache()`, `getPatternsSet()`, and `getCategoryMap()` separately (`97-99`), and `debugCategorize()` does the same (`220-222`) instead of reusing one local snapshot.  
   **Suggested registry wording:**
   ```
   - `CategorizationEngine` reloads cache fragments via three accessors per call — fetch one snapshot (B38) **[PARTIALLY_RESOLVED - cache refresh is now centralized in `getCacheData()`, but `categorize()` / `debugCategorize()` still call separate wrapper accessors instead of reusing one local snapshot]**
   ```

9. `CategorizationEngine` fuzzy matcher prefilters by first two chars — loosen prefix heuristic (B38)  
   **Status:** STILL_OPEN  
   **Evidence:** `findFuzzyMatch()` still builds `val prefix = normalized.take(2)` and only considers candidates whose pattern/canonical name starts with that prefix (`CategorizationEngine.kt:512-516`).  
   **Suggested registry wording if status should change:** No change.

10. `CategorizationEngine.getCategoryIdByName()` reads outside snapshot — use snapshot's name-to-id map (B38)  
    **Status:** STILL_OPEN  
    **Evidence:** `getCategoryIdByName()` fetches `cacheData = getCacheData()` but then ignores it and still reads `cachedCategoryNameToId?.get(categoryName)` from shared state (`CategorizationEngine.kt:443-448`) rather than from a local snapshot map.  
    **Suggested registry wording if status should change:** No change.

11. `WarrantyTrackerScreen` expired warranties never show expired badge — render when `isExpired || isExpiringSoon` (B18)  
    **Status:** STILL_OPEN  
    **Evidence:** `WarrantyCard` computes `isExpiringSoon = daysRemaining in 0..30` and `isExpired = daysRemaining < 0` (`WarrantyTrackerScreen.kt:428-430`), but the badge branch is guarded by `else if (isExpiringSoon)` only (`494-509`), so expired items never reach the expired-badge text path.  
    **Suggested registry wording if status should change:** No change.

12. `ReviewScreen` `showTrustSignal` never toggled — add expand/collapse or remove (B18-missed)  
    **Status:** STILL_OPEN  
    **Evidence:** `ReviewScreen.kt` only declares `var showTrustSignal by remember { mutableStateOf(false) }` (`759`) and reads it in `AnimatedVisibility` (`964`); there is no event path that ever sets it to `true` or toggles it.  
    **Suggested registry wording if status should change:** No change.

13. `WarrantyTrackerViewModel` auto-detected filter chip can't be toggled off — make chip toggle its own boolean (B18)  
    **Status:** STILL_OPEN  
    **Evidence:** `filterByAutoDetected()` still unconditionally sets `showAutoDetectedOnly = true` (`WarrantyTrackerViewModel.kt:93-100`), so tapping the already-selected chip cannot clear that filter.  
    **Suggested registry wording if status should change:** No change.

14. `CurrencyManagementScreen` conversion dialog leaves Convert enabled for invalid amounts — disable or show validation error (B18)  
    **Status:** STILL_OPEN  
    **Evidence:** the dialog confirm `Button` has no `enabled` guard (`CurrencyManagementScreen.kt:747-756`); invalid input is just silently ignored via `amount.toDoubleOrNull()?.let { ... }` (`749-752`), with no disabled state or validation message.  
    **Suggested registry wording if status should change:** No change.

15. `SubscriptionManagementViewModel` no-spend status loaded once in `init` — observe reactively (B19)  
    **Status:** RESOLVED  
    **Evidence:** the current subscription-management path no longer maintains a no-spend status at all: `SubscriptionManagementUiState` contains only subscription/candidate fields (`SubscriptionManagementViewModel.kt:28-38`), `init` only calls `loadSubscriptions()` (`66-68`), and `SubscriptionManagementScreen` renders subscription/candidate sections only (`SubscriptionManagementScreen.kt:154-250`).  
    **Suggested registry wording:**
    ```
    - `SubscriptionManagementViewModel` no-spend status loaded once in `init` — observe reactively (B19) **[RESOLVED - the current subscription management viewmodel/screen path no longer computes or renders a no-spend status, so the stale init-loaded state is gone]**
    ```

16. `CarbonFootprintScreen` negative `parisAgreementGap` passed to formatter — use `abs(gap)` (B19)  
    **Status:** STILL_OPEN  
    **Evidence:** `CarbonFootprintScreen` still passes raw `gap` into the below-target string (`CarbonFootprintScreen.kt:459-463`), and the string resource is `%1$d%% below target` (`strings.xml:497`), so negative gaps render as values like `-90% below target`.  
    **Suggested registry wording if status should change:** No change.

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.10` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `FinancialHealthScoreV2` on exception returns synthetic score of 50 — add explicit fallback flag (B41)
   ```
   with
   ```
   - `FinancialHealthScoreV2` on exception returns synthetic score of 50 — add explicit fallback flag (B41) **[RESOLVED - top-level `calculateHealthScore()` now rethrows non-cancellation exceptions after logging instead of returning a synthetic score]**
   ```

2. Replace
   ```
   - `RecurringIncomeTracker.getStartOfMonth()` leaves milliseconds untouched — set `MILLISECOND = 0` (B41)
   ```
   with
   ```
   - `RecurringIncomeTracker.getStartOfMonth()` leaves milliseconds untouched — set `MILLISECOND = 0` (B41) **[RESOLVED - `getStartOfMonth()` now explicitly clears `Calendar.MILLISECOND`]**
   ```

3. Replace
   ```
   - `SpendingChallengeManager.durationDays * 24 * 60 * 60 * 1000L` overflow — cast to `Long` first (B38)
   ```
   with
   ```
   - `SpendingChallengeManager.durationDays * 24 * 60 * 60 * 1000L` overflow — cast to `Long` first (B38) **[RESOLVED - challenge date math now uses `durationDays.toLong() * DAY_MS` for both challenge end dates and reduce-spending baselines]**
   ```

4. Replace
   ```
   - `SpendingChallengeManager.daysRemaining` can go negative — clamp with `coerceAtLeast(0)` (B38)
   ```
   with
   ```
   - `SpendingChallengeManager.daysRemaining` can go negative — clamp with `coerceAtLeast(0)` (B38) **[RESOLVED - `daysRemaining` is now clamped with `.coerceAtLeast(0)` in `getChallengeProgress()`]**
   ```

5. Replace
   ```
   - `SpendingChallengeManager` IDs use `System.currentTimeMillis()` — use UUID (B38)
   ```
   with
   ```
   - `SpendingChallengeManager` IDs use `System.currentTimeMillis()` — use UUID (B38) **[RESOLVED - challenge creation now persists `id = 0` and relies on Room auto-generated IDs instead of timestamp-based IDs]**
   ```

6. Replace
   ```
   - `CategorizationEngine` reloads cache fragments via three accessors per call — fetch one snapshot (B38)
   ```
   with
   ```
   - `CategorizationEngine` reloads cache fragments via three accessors per call — fetch one snapshot (B38) **[PARTIALLY_RESOLVED - cache refresh is now centralized in `getCacheData()`, but `categorize()` / `debugCategorize()` still call separate wrapper accessors instead of reusing one local snapshot]**
   ```

7. Replace
   ```
   - `SubscriptionManagementViewModel` no-spend status loaded once in `init` — observe reactively (B19)
   ```
   with
   ```
   - `SubscriptionManagementViewModel` no-spend status loaded once in `init` — observe reactively (B19) **[RESOLVED - the current subscription management viewmodel/screen path no longer computes or renders a no-spend status, so the stale init-loaded state is gone]**
   ```

8. Leave the other 9 SubBatch D.10 bullets unchanged; they are still open in current code.

## Batch 6 Registry Sync Addendum

- D10-3 (`RecurringIncomeTracker` ms-squared variance confidence threshold): **RESOLVED BY D3-TIME-DETERMINISM**.
- Revalidation outcome: recurring-income confidence now uses day-scale interval variance semantics.
