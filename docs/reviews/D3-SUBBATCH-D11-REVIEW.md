# D3 SubBatch D.11 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.11 is stale: 3 rows are resolved and 2 rows are partially resolved in current code, but all 13 rows are still listed as open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacements under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 13 SubBatch D.11 registry rows against the current worktree and the referenced Phase C/D audit context; status calls below are based on direct source inspection
- Testing adequate: no - no tests were run in this pass; conclusions are based on source inspection only

## SubBatch D.11 Audit

1. `CarbonFootprintViewModel` collapses exceptions into `report = null` — add explicit error state (B19-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `CarbonFootprintViewModel.loadReport()` still catches generic exceptions and sets `_report.value = null` (`CarbonFootprintViewModel.kt:47-50`), with no dedicated error field/state.  
   **Suggested registry wording if status should change:** No change.

2. `Loading states` ignore scaffold padding in multiple screens — apply padding (B19)  
   **Status:** PARTIALLY_RESOLVED  
   **Evidence:** several screens now apply scaffold padding before rendering loading UI (for example `CurrencyManagementScreen.kt:93-97`, `SubscriptionManagementScreen.kt:107-110`, `TaxConfigurationScreen.kt:70-74`, `WarrantyTrackerScreen.kt:83-87`), but `CarbonFootprintScreen` still shows its loading spinner in `Box(modifier = Modifier.fillMaxSize())` without applying `padding` (`CarbonFootprintScreen.kt:90-97`).  
   **Suggested registry wording:**
   ```
   - `Loading states` ignore scaffold padding in multiple screens — apply padding (B19) **[PARTIALLY_RESOLVED - several screens now render loading states inside scaffold-padded containers, but `CarbonFootprintScreen` still shows its loading spinner without applying scaffold padding]**
   ```

3. `Hardcoded English copy` in multiple screens — extract to string resources (B19)  
   **Status:** PARTIALLY_RESOLVED  
   **Evidence:** many UI strings are now resource-backed, but hardcoded English text remains in `SpendingChallengesScreen.kt` (`"Active challenges unavailable"`, `"This build does not have a persisted active-challenges source yet."`, `"Baseline ..."`, `"Reduce spend by ..."`, `"Target: ..."` at `219`, `226`, `281-287`) and `ReceiptScanScreen.kt` (`"Camera permission denied"`, `"Enable camera permission..."`, `"Open Settings"`, `"Retry"` at `345-360`, `984`).  
   **Suggested registry wording:**
   ```
   - `Hardcoded English copy` in multiple screens — extract to string resources (B19) **[PARTIALLY_RESOLVED - many UI strings are now resource-backed, but hardcoded English copy remains in `SpendingChallengesScreen.kt` and `ReceiptScanScreen.kt`]**
   ```

4. `NoSpendStreakCard` hardcodes `Locale.GERMANY` — use `Locale.getDefault()` (B19)  
   **Status:** RESOLVED  
   **Evidence:** `NoSpendStreakCard` no longer uses `Locale.GERMANY`; it formats the saved amount via `CurrencyFormatter.format(saved)` (`SpendingChallengesScreen.kt:350-355`), and `CurrencyFormatter` uses `Locale.getDefault()` (`CurrencyFormatter.kt:15-17`, `42-56`).  
   **Suggested registry wording:**
   ```
   - `NoSpendStreakCard` hardcodes `Locale.GERMANY` — use `Locale.getDefault()` (B19) **[RESOLVED - `NoSpendStreakCard` no longer hardcodes `Locale.GERMANY`; savings text now formats through `CurrencyFormatter`, which uses the default locale]**
   ```

5. `Starter prompt chips` display localized labels but inject hardcoded English queries — back with localized query strings (B19-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `AssistantSheet.StarterPrompts()` renders localized labels from `stringResource(...)`, but the click handlers still submit hardcoded English prompts like `"How much did I spend this month?"` and `"Top merchants this month"` (`AssistantSheet.kt:257-264`).  
   **Suggested registry wording if status should change:** No change.

6. `Active challenges` branch renders placeholder text — replace with real challenge card (B19-missed)  
   **Status:** RESOLVED  
   **Evidence:** the non-empty active-challenges branch now renders `ActiveChallengeCard` for each challenge (`SpendingChallengesScreen.kt:179-185`), and that card shows real challenge name, type, progress, and target details (`241-292`) instead of placeholder rows.  
   **Suggested registry wording:**
   ```
   - `Active challenges` branch renders placeholder text — replace with real challenge card (B19-missed) **[RESOLVED - non-empty active-challenges state now renders `ActiveChallengeCard` rows with challenge name, type, progress, and target details]**
   ```

7. `balance == 0.0` exact float equality — compare with tolerance (B18)  
   **Status:** STILL_OPEN  
   **Evidence:** `SharedExpenseGroupsScreen.MemberBalanceCard()` still computes `val isZero = balance == 0.0` (`SharedExpenseGroupsScreen.kt:498-499`), so near-zero rounding residue can still be misclassified as owing/getting-back instead of settled.  
   **Suggested registry wording if status should change:** No change.

8. `AddExpenseViewModel.reset()` doesn't cancel debounced search job — cancel in reset (B17)  
   **Status:** STILL_OPEN  
   **Evidence:** merchant autocomplete still uses `searchJob` for debounced search (`AddExpenseViewModel.kt:78`, `91-107`), but `reset()` only overwrites `_state` and never cancels `searchJob` (`411-413`).  
   **Suggested registry wording if status should change:** No change.

9. `AddExpenseSheet` prefill keyed with `LaunchedEffect(Unit)` — key to `initialAmount`/`initialMerchant` (B17)  
   **Status:** STILL_OPEN  
   **Evidence:** the prefill effect is still `LaunchedEffect(Unit)` and calls `setInitialValues(initialAmount, initialMerchant)` only once (`AddExpenseSheet.kt:99-103`), so changed initial props will not retrigger prefill on recomposition.  
   **Suggested registry wording if status should change:** No change.

10. `ReceiptScanScreen` uses `collectAsState()` — use `collectAsStateWithLifecycle()` (B19)  
    **Status:** STILL_OPEN  
    **Evidence:** the screen still collects both `state` and `categories` with `collectAsState()` (`ReceiptScanScreen.kt:81-82`); there is no `collectAsStateWithLifecycle()` usage in this screen.  
    **Suggested registry wording if status should change:** No change.

11. `Camera permission denial copy` hardcoded English — move to `strings.xml` (B19)  
    **Status:** STILL_OPEN  
    **Evidence:** the denial card still uses raw English strings for the title, body, and button label (`ReceiptScanScreen.kt:345-360`).  
    **Suggested registry wording if status should change:** No change.

12. `Retry button` hardcoded `"Retry"` string — extract to resource (B19)  
    **Status:** STILL_OPEN  
    **Evidence:** the item-analysis error card still renders `Text("Retry")` directly (`ReceiptScanScreen.kt:980-985`).  
    **Suggested registry wording if status should change:** No change.

13. `Currency.getInstance(currencyCode)` unguarded — wrap in `runCatching` (B19)  
    **Status:** RESOLVED  
    **Evidence:** `VisualSplitEditorScreen.buildCurrencyFormat()` now resolves currency with `runCatching { Currency.getInstance(currencyCode) }.getOrDefault(fallbackCurrency)` (`VisualSplitEditorScreen.kt:654-658`), so invalid currency codes no longer crash the formatter path.  
    **Suggested registry wording:**
    ```
    - `Currency.getInstance(currencyCode)` unguarded — wrap in `runCatching` (B19) **[RESOLVED - guarded currency resolution is now used in `VisualSplitEditorScreen.buildCurrencyFormat()`, with a safe fallback when the code is invalid]**
    ```

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.11` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `Loading states` ignore scaffold padding in multiple screens — apply padding (B19)
   ```
   with
   ```
   - `Loading states` ignore scaffold padding in multiple screens — apply padding (B19) **[PARTIALLY_RESOLVED - several screens now render loading states inside scaffold-padded containers, but `CarbonFootprintScreen` still shows its loading spinner without applying scaffold padding]**
   ```

2. Replace
   ```
   - `Hardcoded English copy` in multiple screens — extract to string resources (B19)
   ```
   with
   ```
   - `Hardcoded English copy` in multiple screens — extract to string resources (B19) **[PARTIALLY_RESOLVED - many UI strings are now resource-backed, but hardcoded English copy remains in `SpendingChallengesScreen.kt` and `ReceiptScanScreen.kt`]**
   ```

3. Replace
   ```
   - `NoSpendStreakCard` hardcodes `Locale.GERMANY` — use `Locale.getDefault()` (B19)
   ```
   with
   ```
   - `NoSpendStreakCard` hardcodes `Locale.GERMANY` — use `Locale.getDefault()` (B19) **[RESOLVED - `NoSpendStreakCard` no longer hardcodes `Locale.GERMANY`; savings text now formats through `CurrencyFormatter`, which uses the default locale]**
   ```

4. Replace
   ```
   - `Active challenges` branch renders placeholder text — replace with real challenge card (B19-missed)
   ```
   with
   ```
   - `Active challenges` branch renders placeholder text — replace with real challenge card (B19-missed) **[RESOLVED - non-empty active-challenges state now renders `ActiveChallengeCard` rows with challenge name, type, progress, and target details]**
   ```

5. Replace
   ```
   - `Currency.getInstance(currencyCode)` unguarded — wrap in `runCatching` (B19)
   ```
   with
   ```
   - `Currency.getInstance(currencyCode)` unguarded — wrap in `runCatching` (B19) **[RESOLVED - guarded currency resolution is now used in `VisualSplitEditorScreen.buildCurrencyFormat()`, with a safe fallback when the code is invalid]**
   ```

6. Leave the other 8 SubBatch D.11 bullets unchanged; they are still open in current code.
