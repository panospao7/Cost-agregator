# D3 SubBatch D.12 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.12 is stale: 3 rows are resolved and 2 rows are partially resolved in current code but are still listed as open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacement lines under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 14 SubBatch D.12 issues against current code, classified each, captured brief code evidence, and prepared exact registry replacement text for every row whose status should change
- Testing adequate: no - no tests were run in this pass; conclusions are based on direct source inspection of the current worktree and the referenced Phase C/D audit

## SubBatch D.12 Audit

1. `Percentage/amount fields` coerce input through `Double.toString()` — store editable text separately (B19)  
   **Status:** STILL_OPEN  
   **Evidence:** `VisualSplitEditorScreen.kt` still renders editable values with `(participant.percentage ?: percentage).toString()` and `(participant.amount ?: assignedAmount).toString()` (`596`, `616`), so in-progress user input is still round-tripped through numeric string coercion during editing.  
   **Suggested registry wording if status should change:** No change.

2. `LifestyleInflationViewModel` exceptions swallowed into `report = null` — add explicit error state (B19)  
   **Status:** STILL_OPEN  
   **Evidence:** `LifestyleInflationViewModel.kt` still catches generic exceptions and sets `_report.value = null` (`41-45`); the viewmodel exposes only `report` and `isLoading`, with no dedicated error state.  
   **Suggested registry wording if status should change:** No change.

3. `SavingsPromptCard` hardcoded English copy — move to resources (B19)  
   **Status:** STILL_OPEN  
   **Evidence:** `LifestyleInflationScreen.kt` still hardcodes user-facing text in `SavingsPromptCard`, including `"Boost Your Savings"`, `"Lifestyle inflation detected ..."`, and `"Increase Savings Rate"` (`821-845`) instead of using `stringResource(...)`.  
   **Suggested registry wording if status should change:** No change.

4. `ReviewPriorityFactors.fromReview()` uses `System.currentTimeMillis()` — pass `now` (B34)  
   **Status:** RESOLVED  
   **Evidence:** `ReviewPriorityModels.kt` now defines `fromReview(review: ReviewPriorityInput, nowMs: Long)` and uses that passed-in timestamp for time sensitivity (`50-56`); the reviewed production scorer path passes `timeProvider.now()` rather than reading wall clock internally.  
   **Suggested registry wording:**
   ```
   - `ReviewPriorityFactors.fromReview()` uses `System.currentTimeMillis()` — pass `now` (B34) **[RESOLVED - `fromReview()` now requires `nowMs`, and reviewed production callers pass an injected clock value instead of reading wall time internally]**
   ```

5. `ReviewPriorityFactors.calculateTimeSensitivity` reads `System.currentTimeMillis()` — inject clock (B24)  
   **Status:** RESOLVED  
   **Evidence:** `ReviewPriorityModels.kt` now implements `calculateTimeSensitivity(createdAt: Long, nowMs: Long)` and derives age from the supplied `nowMs` parameter (`62-69`); there is no remaining wall-clock read in this calculation path.  
   **Suggested registry wording:**
   ```
   - `ReviewPriorityFactors.calculateTimeSensitivity` reads `System.currentTimeMillis()` — inject clock (B24) **[RESOLVED - `calculateTimeSensitivity(createdAt, nowMs)` now takes the evaluation time as a parameter instead of reading wall time internally]**
   ```

6. `CaptureAssistInput.amount` accepts `NaN`/`Infinity`/zero/negative — require finite positive (B34)  
   **Status:** STILL_OPEN  
   **Evidence:** The active model is `CategorizationAssistInput`, which still declares raw `val amount: Double` with no validation in `CaptureAssistModels.kt` (`94-108`), and `CategorizationAssistInputBuilder.kt` still passes raw values such as `review.suggestedAmount` and `draftAmount ?: receipt.parsedTotal ?: 0.0` (`47-52`, `92-97`). Invalid/non-positive values are still not rejected.  
   **Suggested registry wording if status should change:** No change.

7. `ReviewExplanationInputBuilder` imports `data.ai.provider.internal.sha256Prefix` — move hashing to domain/common (B36)  
   **Status:** STILL_OPEN  
   **Evidence:** `ReviewExplanationInputBuilder.kt` still imports `com.yourname.expensetracker.data.ai.provider.internal.sha256Prefix` on line 3, so the domain use case still depends on a data-layer internal hashing helper.  
   **Suggested registry wording if status should change:** No change.

8. `DashboardBriefingInputBuilder.SimpleDateFormat` shared mutable state — use `DateTimeFormatter` (B35)  
   **Status:** RESOLVED  
   **Evidence:** `DashboardBriefingInputBuilder.kt` now imports `java.time.format.DateTimeFormatter` and stores `private val dateKeyFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")` (`13`, `28`); there is no `SimpleDateFormat` state in this builder anymore.  
   **Suggested registry wording:**
   ```
   - `DashboardBriefingInputBuilder.SimpleDateFormat` shared mutable state — use `DateTimeFormatter` (B35) **[RESOLVED - the builder now uses an immutable `DateTimeFormatter` field and no longer holds shared `SimpleDateFormat` state]**
   ```

9. `RecurrenceFrequency.IRREGULAR.intervalInMs` returns `0L` — make nullable or model separately (B24)  
   **Status:** STILL_OPEN  
   **Evidence:** `RecurringPattern.kt` still defines `IRREGULAR(0)` and `intervalInMs` as `days * 86_400_000L`, so `RecurrenceFrequency.IRREGULAR.intervalInMs` still evaluates to `0L` (`32-43`).  
   **Suggested registry wording if status should change:** No change.

10. `MonteCarloBudgetImpact.formatCurrency` hardcodes euro symbol — use `NumberFormat` with explicit `Currency` (B24)  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `MonteCarloBudgetImpact.kt` no longer uses a literal `€`; it now delegates to `CurrencyFormatter.format(amount)` (`42-48`). However `formatCurrency(...)` still accepts only `amount`, and `CurrencyFormatter` defaults to `EUR`, so callers such as `GetMonteCarloBudgetImpactUseCase.kt` still cannot pass an explicit budget currency (`60-61`).  
    **Suggested registry wording:**
    ```
    - `MonteCarloBudgetImpact.formatCurrency` hardcodes euro symbol — use `NumberFormat` with explicit `Currency` (B24) **[PARTIALLY_RESOLVED - literal `€` formatting was removed by delegating to `CurrencyFormatter`, but the API still takes only `amount` and therefore still defaults to `EUR` instead of an explicit currency]**
    ```

11. `CategoryBreakdown`/`DashboardCategoryBreakdown` duplicated across packages — consolidate (B24)  
    **Status:** STILL_OPEN  
    **Evidence:** Duplicate model families still exist in multiple packages: `domain/model/CategoryBreakdown.kt`, `domain/analytics/AnalyticsModels.kt`, `domain/model/dashboard/DashboardCategoryBreakdown.kt`, and `domain/analytics/AdvancedAnalyticsDashboard.kt` each define overlapping category-breakdown types.  
    **Suggested registry wording if status should change:** No change.

12. `PeriodRange` duplicated across `domain.model` and `domain.analytics` — rename one or add conversion layer (B46)  
    **Status:** STILL_OPEN  
    **Evidence:** `domain/model/PeriodRange.kt` and `domain/analytics/AdvancedAnalyticsModels.kt` still both define `PeriodRange` with different shapes and semantics, and no dedicated conversion layer was found.  
    **Suggested registry wording if status should change:** No change.

13. `SavingsGoal` domain and entity definitions differ — keep Room entities internal to data layer (B46)  
    **Status:** STILL_OPEN  
    **Evidence:** Separate `SavingsGoal` definitions still exist in `domain/model/SavingsGoal.kt` and `data/database/entity/SavingsGoal.kt`, and domain code still directly consumes the data-layer entity in places such as `FinancialHealthScoreV2.calculateRunwayScore(..., savingsGoals: List<com.yourname.expensetracker.data.database.entity.SavingsGoal>, ...)`.  
    **Suggested registry wording if status should change:** No change.

14. `NarrativeGenerator` imports app `R`, constructs formatted strings in domain — emit `UiText`/message keys (B46-missed)  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `NarrativeGenerator.kt` no longer imports app `R` and now emits `UiText.fromKey(...)`/domain text keys throughout (`8-10`, `29-74`, `90-178`). However it still performs presentation-facing currency formatting in the domain layer via `CurrencyFormatter.format(...)` (`27`, `95`, `138`, `156`, `176`), so the boundary cleanup is improved but not fully complete.  
    **Suggested registry wording:**
    ```
    - `NarrativeGenerator` imports app `R`, constructs formatted strings in domain — emit `UiText`/message keys (B46-missed) **[PARTIALLY_RESOLVED - app `R` imports and raw resource access are gone and the generator now emits `UiText`/domain text keys, but it still performs presentation-facing currency formatting in the domain layer]**
    ```

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.12` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `ReviewPriorityFactors.fromReview()` uses `System.currentTimeMillis()` — pass `now` (B34)
   ```
   with
   ```
   - `ReviewPriorityFactors.fromReview()` uses `System.currentTimeMillis()` — pass `now` (B34) **[RESOLVED - `fromReview()` now requires `nowMs`, and reviewed production callers pass an injected clock value instead of reading wall time internally]**
   ```

2. Replace
   ```
   - `ReviewPriorityFactors.calculateTimeSensitivity` reads `System.currentTimeMillis()` — inject clock (B24)
   ```
   with
   ```
   - `ReviewPriorityFactors.calculateTimeSensitivity` reads `System.currentTimeMillis()` — inject clock (B24) **[RESOLVED - `calculateTimeSensitivity(createdAt, nowMs)` now takes the evaluation time as a parameter instead of reading wall time internally]**
   ```

3. Replace
   ```
   - `DashboardBriefingInputBuilder.SimpleDateFormat` shared mutable state — use `DateTimeFormatter` (B35)
   ```
   with
   ```
   - `DashboardBriefingInputBuilder.SimpleDateFormat` shared mutable state — use `DateTimeFormatter` (B35) **[RESOLVED - the builder now uses an immutable `DateTimeFormatter` field and no longer holds shared `SimpleDateFormat` state]**
   ```

4. Replace
   ```
   - `MonteCarloBudgetImpact.formatCurrency` hardcodes euro symbol — use `NumberFormat` with explicit `Currency` (B24)
   ```
   with
   ```
   - `MonteCarloBudgetImpact.formatCurrency` hardcodes euro symbol — use `NumberFormat` with explicit `Currency` (B24) **[PARTIALLY_RESOLVED - literal `€` formatting was removed by delegating to `CurrencyFormatter`, but the API still takes only `amount` and therefore still defaults to `EUR` instead of an explicit currency]**
   ```

5. Replace
   ```
   - `NarrativeGenerator` imports app `R`, constructs formatted strings in domain — emit `UiText`/message keys (B46-missed)
   ```
   with
   ```
    - `NarrativeGenerator` imports app `R`, constructs formatted strings in domain — emit `UiText`/message keys (B46-missed) **[PARTIALLY_RESOLVED - app `R` imports and raw resource access are gone and the generator now emits `UiText`/domain text keys, but it still performs presentation-facing currency formatting in the domain layer]**
    ```

## Batch 6 Registry Sync Addendum

- D12-9 (`RecurrenceFrequency.IRREGULAR.intervalInMs = 0L` sentinel semantics): **RESOLVED BY D3-TIME-DETERMINISM**.
- Revalidation outcome: irregular/calendar recurrence semantics are now explicit in production logic; sentinel `0L` interval behavior is deprecated/removed from runtime decision paths.

6. Leave the other 9 SubBatch D.12 bullets unchanged; they are still open in current code.
