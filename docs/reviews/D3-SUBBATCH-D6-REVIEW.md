# D3 SubBatch D.6 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.6 is stale: 9 rows are resolved in current code but still listed as open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacement lines under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 15 SubBatch D.6 rows against current code, classified each, and supplied exact registry replacement text where status changes are warranted
- Testing adequate: no - no test suite was run in this pass; conclusions are based on direct source inspection of the current worktree, with one existing unit test referenced as supporting evidence for the Monte Carlo messaging issue

Sources read:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/AUDIT-PHASE-C-D.md`

## Summary

- Total issues audited: **15**
- **RESOLVED:** 9
- **PARTIALLY_RESOLVED:** 0
- **STILL_OPEN:** 6
- **FALSE_POSITIVE:** 0

## SubBatch D.6 Audit

1. `ComputeMoneyRadarUseCase` independent fetches run sequentially — use `async`/`await` (B48)  
   **Status:** RESOLVED  
   **Evidence:** `ComputeMoneyRadarUseCase.compute()` now launches `getDueBills(now)`, `getUnresolvedAnomalies(now)`, and `getBudgetRisk(now)` via `async` and then awaits all three (`ComputeMoneyRadarUseCase.kt:139-149`).  
   **Suggested registry wording:**
   ```
   - `ComputeMoneyRadarUseCase` independent fetches run sequentially — use `async`/`await` (B48) **[RESOLVED - `compute()` now fetches due bills, anomaly alerts, and budget risk concurrently via `async`/`await`]**
   ```

2. `DetectDuplicateExpenseUseCase` userCorrectionRepository injected but unused — remove or integrate (B48)  
   **Status:** STILL_OPEN  
   **Evidence:** `DetectDuplicateExpenseUseCase` still injects `UserCorrectionRepository` in the constructor (`DetectDuplicateExpenseUseCase.kt:11-15`), but the field is never referenced anywhere in the class body (`DetectDuplicateExpenseUseCase.kt:16-80`).  
   **Suggested registry wording:** No change.

3. `GetMonteCarloBudgetImpactUseCase` messages say "exceed by €0.00" — choose messages from riskTier + expectedOverrun (B48-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `GetMonteCarloBudgetImpactUseCase` still always formats `expectedOverrun` and interpolates it into MEDIUM/HIGH/CRITICAL messages (`GetMonteCarloBudgetImpactUseCase.kt:49-61`, `112-118`). The existing test suite still confirms a HIGH-risk case with `expectedOverrun = 0.0` is valid (`GetMonteCarloBudgetImpactUseCaseTest.kt:59-72`), so the code can still produce "exceed by €0.00"-style messaging.  
   **Suggested registry wording:** No change.

4. `BlockPartyDay` imports Room `Expense` entity — replace with domain DTO (B46)  
   **Status:** RESOLVED  
   **Evidence:** `BlockPartyDay.kt` no longer imports Room entities; it now defines and uses domain `TransactionSummary` for `topTransactions` (`BlockPartyDay.kt:17-39`, `41-53`).  
   **Suggested registry wording:**
   ```
   - `BlockPartyDay` imports Room `Expense` entity — replace with domain DTO (B46) **[RESOLVED - block-party previews now use domain `TransactionSummary` instead of Room `Expense`]**
   ```

5. `FinancialForecast.actionableInsights` is `List<String>` while feature family uses `UiText` — use `List<UiText>` (B46)  
   **Status:** RESOLVED  
   **Evidence:** `FinancialForecast.actionableInsights` is now typed as `List<UiText>` (`FinancialForecast.kt:5-10`).  
   **Suggested registry wording:**
   ```
   - `FinancialForecast.actionableInsights` is `List<String>` while feature family uses `UiText` — use `List<UiText>` (B46) **[RESOLVED - `FinancialForecast.actionableInsights` now uses `List<UiText>`]**
   ```

6. `ForecastHorizon.REST_OF_MONTH` uses `days = 0` as sentinel — model calendar-bound case explicitly (B46)  
   **Status:** STILL_OPEN  
   **Evidence:** `ForecastHorizon.REST_OF_MONTH` is still declared as `REST_OF_MONTH(0, "Rest of Month")` with an inline comment that `0 means calculate based on calendar` (`FinancialForecast.kt:20-24`).  
   **Suggested registry wording:** No change.

7. `PeriodRange` accepts `end < start` — add `require(end >= start)` (B46)  
   **Status:** RESOLVED  
   **Evidence:** `PeriodRange` now enforces `require(end >= start)` in its init block (`PeriodRange.kt:3-9`).  
   **Suggested registry wording:**
   ```
   - `PeriodRange` accepts `end < start` — add `require(end >= start)` (B46) **[RESOLVED - `PeriodRange` now enforces `end >= start` in its init block]**
   ```

8. `PlannedExpense.amount` has no non-negative invariant — enforce `amount >= 0` (B46)  
   **Status:** RESOLVED  
   **Evidence:** `PlannedExpense` now requires `amount.isFinite() && amount > 0.0` in its init block (`PlannedExpense.kt:12-15`), which is stricter than the original non-negative requirement.  
   **Suggested registry wording:**
   ```
   - `PlannedExpense.amount` has no non-negative invariant — enforce `amount >= 0` (B46) **[RESOLVED - `PlannedExpense` now requires a positive finite `amount`]**
   ```

9. `RecurrenceFrequency` mixes approximate fixed-day values for calendar frequencies — remove `intervalInMs` for calendar-based (B46)  
   **Status:** STILL_OPEN  
   **Evidence:** `RecurringPattern.kt` still models `MONTHLY(30)`, `QUARTERLY(90)`, `SEMI_ANNUALLY(180)`, and `ANNUALLY(365)`, and still exposes `intervalInMs` derived from those fixed day counts (`RecurringPattern.kt:32-43`).  
   **Suggested registry wording:** No change.

10. `UpcomingItem.Recurring.id` uses only `merchantName` — use `pattern.id` or composite key (B46)  
    **Status:** STILL_OPEN  
    **Evidence:** `UpcomingItem.Recurring` still derives `id` as `"recurring_${pattern.merchantName}"` and does not use `pattern.id` or any disambiguating composite (`UpcomingItem.kt:10-18`).  
    **Suggested registry wording:** No change.

11. `MonteCarloBudgetImpact` stores preformatted UI strings, hardcodes EUR — keep raw values only (B46)  
    **Status:** STILL_OPEN  
    **Evidence:** `MonteCarloBudgetImpact` still stores UI-facing `displayMessage` and `formattedOverrun` strings in the domain model (`MonteCarloBudgetImpact.kt:19-27`), and `GetMonteCarloBudgetImpactUseCase` still populates them by calling `MonteCarloBudgetImpact.formatCurrency(expectedOverrun)` without supplying a currency code (`GetMonteCarloBudgetImpactUseCase.kt:59-70`), which falls back to the formatter's default EUR currency (`CurrencyFormatter.kt:12-17`).  
    **Suggested registry wording:** No change.

12. `DashboardExpenseMapper` imports Room `Expense`/`TransactionType` — move mapper to data layer (B47)  
    **Status:** RESOLVED  
    **Evidence:** `DashboardExpenseMapper.kt` now imports only domain `TransactionSummary` and maps `DashboardExpense` to that DTO; there are no Room `Expense` or `TransactionType` imports left (`DashboardExpenseMapper.kt:1-20`).  
    **Suggested registry wording:**
    ```
    - `DashboardExpenseMapper` imports Room `Expense`/`TransactionType` — move mapper to data layer (B47) **[RESOLVED - mapper no longer imports Room types and now maps `DashboardExpense` to domain `TransactionSummary`]**
    ```

13. `DomainTransactionFilter` depends on `TransactionType` and `OwnershipFilter` from data layer — define domain-level enums (B47)  
    **Status:** RESOLVED  
    **Evidence:** `DomainTransactionFilter` now uses `DomainTransactionType` and `DomainOwnershipFilter` (`DomainTransactionFilter.kt:3-13`), and those domain enums are defined separately from data-layer types (`DomainTransactionType.kt:11-30`, `DomainOwnershipFilter.kt:11-17`).  
    **Suggested registry wording:**
    ```
    - `DomainTransactionFilter` depends on `TransactionType` and `OwnershipFilter` from data layer — define domain-level enums (B47) **[RESOLVED - `DomainTransactionFilter` now depends on domain-owned transaction and ownership enums]**
    ```

14. `DomainTransactionFilter.correlationId` defaults to `System.currentTimeMillis()` — move outside value object (B47)  
    **Status:** RESOLVED  
    **Evidence:** `DomainTransactionFilter.correlationId` now defaults to `0L` instead of a wall-clock timestamp (`DomainTransactionFilter.kt:5-14`), and deserialization also defaults missing values to `0L` (`TransactionFilterSerializer.kt:110-123`).  
    **Suggested registry wording:**
    ```
    - `DomainTransactionFilter.correlationId` defaults to `System.currentTimeMillis()` — move outside value object (B47) **[RESOLVED - `correlationId` now defaults to `0L` instead of a wall-clock timestamp]**
    ```

15. `SpendingSummary` mixes `Double` totals with `Float` histories — use `Double` consistently (B47)  
    **Status:** RESOLVED  
    **Evidence:** `SpendingSummary.dailyHistory` and `previousDailyHistory` are now both `List<Double>` (`SpendingSummary.kt:3-8`).  
    **Suggested registry wording:**
    ```
    - `SpendingSummary` mixes `Double` totals with `Float` histories — use `Double` consistently (B47) **[RESOLVED - `SpendingSummary` now uses `Double` consistently for totals and history series]**
    ```

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.6` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `ComputeMoneyRadarUseCase` independent fetches run sequentially — use `async`/`await` (B48)
   ```
   with
   ```
   - `ComputeMoneyRadarUseCase` independent fetches run sequentially — use `async`/`await` (B48) **[RESOLVED - `compute()` now fetches due bills, anomaly alerts, and budget risk concurrently via `async`/`await`]**
   ```

2. Replace
   ```
   - `BlockPartyDay` imports Room `Expense` entity — replace with domain DTO (B46)
   ```
   with
   ```
   - `BlockPartyDay` imports Room `Expense` entity — replace with domain DTO (B46) **[RESOLVED - block-party previews now use domain `TransactionSummary` instead of Room `Expense`]**
   ```

3. Replace
   ```
   - `FinancialForecast.actionableInsights` is `List<String>` while feature family uses `UiText` — use `List<UiText>` (B46)
   ```
   with
   ```
   - `FinancialForecast.actionableInsights` is `List<String>` while feature family uses `UiText` — use `List<UiText>` (B46) **[RESOLVED - `FinancialForecast.actionableInsights` now uses `List<UiText>`]**
   ```

4. Replace
   ```
   - `PeriodRange` accepts `end < start` — add `require(end >= start)` (B46)
   ```
   with
   ```
   - `PeriodRange` accepts `end < start` — add `require(end >= start)` (B46) **[RESOLVED - `PeriodRange` now enforces `end >= start` in its init block]**
   ```

5. Replace
   ```
   - `PlannedExpense.amount` has no non-negative invariant — enforce `amount >= 0` (B46)
   ```
   with
   ```
   - `PlannedExpense.amount` has no non-negative invariant — enforce `amount >= 0` (B46) **[RESOLVED - `PlannedExpense` now requires a positive finite `amount`]**
   ```

6. Replace
   ```
   - `DashboardExpenseMapper` imports Room `Expense`/`TransactionType` — move mapper to data layer (B47)
   ```
   with
   ```
   - `DashboardExpenseMapper` imports Room `Expense`/`TransactionType` — move mapper to data layer (B47) **[RESOLVED - mapper no longer imports Room types and now maps `DashboardExpense` to domain `TransactionSummary`]**
   ```

7. Replace
   ```
   - `DomainTransactionFilter` depends on `TransactionType` and `OwnershipFilter` from data layer — define domain-level enums (B47)
   ```
   with
   ```
   - `DomainTransactionFilter` depends on `TransactionType` and `OwnershipFilter` from data layer — define domain-level enums (B47) **[RESOLVED - `DomainTransactionFilter` now depends on domain-owned transaction and ownership enums]**
   ```

8. Replace
   ```
   - `DomainTransactionFilter.correlationId` defaults to `System.currentTimeMillis()` — move outside value object (B47)
   ```
   with
   ```
   - `DomainTransactionFilter.correlationId` defaults to `System.currentTimeMillis()` — move outside value object (B47) **[RESOLVED - `correlationId` now defaults to `0L` instead of a wall-clock timestamp]**
   ```

9. Replace
   ```
   - `SpendingSummary` mixes `Double` totals with `Float` histories — use `Double` consistently (B47)
   ```
   with
   ```
   - `SpendingSummary` mixes `Double` totals with `Float` histories — use `Double` consistently (B47) **[RESOLVED - `SpendingSummary` now uses `Double` consistently for totals and history series]**
   ```

10. Leave the other 6 SubBatch D.6 bullets unchanged; they are still open in current code:
    - `DetectDuplicateExpenseUseCase` userCorrectionRepository injected but unused — remove or integrate (B48)
    - `GetMonteCarloBudgetImpactUseCase` messages say "exceed by €0.00" — choose messages from riskTier + expectedOverrun (B48-missed)
    - `ForecastHorizon.REST_OF_MONTH` uses `days = 0` as sentinel — model calendar-bound case explicitly (B46)
    - `RecurrenceFrequency` mixes approximate fixed-day values for calendar frequencies — remove `intervalInMs` for calendar-based (B46)
    - `UpcomingItem.Recurring.id` uses only `merchantName` — use `pattern.id` or composite key (B46)
    - `MonteCarloBudgetImpact` stores preformatted UI strings, hardcodes EUR — keep raw values only (B46)

## Batch 6 Registry Sync Addendum

- D6-6 (`ForecastHorizon.REST_OF_MONTH` sentinel semantics): **RESOLVED BY D3-TIME-DETERMINISM**.
- D6-9 (`RecurrenceFrequency` calendar/fixed interval sentinel semantics): **RESOLVED BY D3-TIME-DETERMINISM**.
