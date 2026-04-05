# Batch 2 — Budget & Forecasting fixes (H3, H4, M4)

## Technical Plan (Advanced)

### Scope
- In:
  - Fix forecast spending pace input so `monthSpent` excludes deposits, transfers, and future-dated transactions (**H3**).
  - Fix stress-forecast failure behavior so fallback does **not** present as healthy/LOW risk (**H4**).
  - Update stale test that currently locks in pre-fix behavior (**M4**).
  - Add/adjust unit tests for the corrected behavior and failure fallback semantics.
- Out:
  - No schema/database migration.
  - No UI redesign (only behavior changes flowing through existing models).
  - No changes to Monte Carlo algorithm logic outside fallback behavior.

### Complexity Assessment
- Estimated files touched: **3–5**
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt` (expected)
  - (Optional) any impacted dashboard/widget consistency tests if assumptions need updates
- Risk level: **medium**
- Cross-module impact: **yes** (domain forecasting output consumed by dashboard widgets)

### Batch Plan
1. Batch name: **H3 — Correct `monthSpent` filtering in financial forecast use case**
   - files:
     - `CalculateFinancialForecastUseCase.kt` (primary)
     - `CalculateFinancialForecastUseCaseTest.kt` (paired validation)
   - objective:
     - Ensure `SpendingPace.currentMonthSpent` in forecast synthesis reflects true month-to-date user spending only.
   - risks:
     - Boundary semantics (`<= now` vs `< now`) can unintentionally drop transactions at `now` timestamp.
     - Domain rule mismatch if another module expects withdrawals included.
   - validation:
     - Unit tests assert exclusion of deposits/transfers/future and inclusion of valid month-to-date purchases.
     - Cross-check with existing canonical rule in `SpendingPaceCalculator` (purchase-only, not-not-mine, bounded by current time window).

   **Root Cause Analysis**
   - **Location:** `CalculateFinancialForecastUseCase.kt:101-103`.
   - Current logic:
     ```kotlin
     val monthSpent = expenses
         .filter { it.date >= monthStart }
         .sumOf { it.effectiveAmount }
     ```
   - Why it happens:
     - Missing transaction-type filter, so `DEPOSIT` and `TRANSFER` inflate spend.
     - Missing upper time bound, so future transactions are counted.
     - Uses `effectiveAmount` (good for shared/not-mine handling) but that alone is insufficient for spend classification.
   - Impact:
     - Inflated `currentMonthSpent` passed into `synthesisEngine`, distorting projected pace/risk and downstream forecast narratives.

   **Implementation Strategy**
   1. Establish month window `[monthStart, now]` (or equivalent bounded window) before summation.
   2. Apply spend inclusion criteria in predicate:
      - in window,
      - `transactionType == PURCHASE`,
      - `!isNotMine` (or rely on `effectiveAmount == 0` for not-mine but keep explicit consistency with canonical calculator).
   3. Sum `effectiveAmount` only after filtered set is finalized.
   4. Keep behavior aligned with `SpendingPaceCalculator` to avoid dual definitions of “month spent”.
   5. Update tests (M4 linkage) so expected value reflects corrected rule.

   **Dependencies**
   - Functional dependency: none.
   - Coordination dependency: **M4 test update must ship with this change** to prevent false-red builds.

   **Risk Assessment**
   - What could go wrong:
     - Off-by-one-time errors for transactions at exact `now`.
     - Unexpected behavior if product wants withdrawals counted as spend.
   - Mitigation:
     - Add explicit timestamp-boundary assertion in test data.
     - Confirm rule ownership with product/domain owner (purchase-only vs purchase+withdrawal).

   **Verification Plan**
   - Unit tests:
     - Existing use case test updated to assert corrected value.
     - Add case if needed for boundary (`date == now` included; `date > now` excluded).
   - Manual checks:
     - Trigger use case with mixed transaction types and inspect captured `SpendingPace.currentMonthSpent`.

   **Estimated Effort**
   - **Medium**

2. Batch name: **H4 — Failure fallback must not report LOW risk**
   - files:
     - `FinancialStressForecastEngine.kt` (primary)
     - `FinancialStressForecastEngineTest.kt` (new/updated tests)
   - objective:
     - Ensure computation failures surface as non-healthy risk posture with explicit “forecast unavailable” messaging.
   - risks:
     - Overly conservative fallback may alarm users during transient failures.
     - Inconsistent fallback fields (e.g., low probability with high risk) can create contradictory UI.
   - validation:
     - Exception-path unit test verifies fallback risk is not `LOW`.
     - Validate recommendation text communicates degraded mode.

   **Root Cause Analysis**
   - **Location:** `FinancialStressForecastEngine.kt:112-116` and `:427-439`.
   - Current catch block returns:
     ```kotlin
     overallRiskLevel = StressRiskLevel.LOW
     horizons = createDefaultHorizons() // each horizon LOW
     ```
   - Why it happens:
     - Error fallback is hardcoded as healthy baseline instead of “unknown/degraded”.
   - Impact:
     - Dashboard can show green/healthy state after forecast failure, giving false reassurance.

   **Implementation Strategy**
   1. Introduce explicit error fallback semantics in engine (e.g., `createErrorFallbackResult()` or parameterized default creator).
   2. Set fallback `overallRiskLevel` to a non-LOW tier (recommended: **MODERATE** unless product requests stricter).
   3. Ensure horizon-level fields are internally consistent with selected fallback tier.
   4. Keep `earliestCrunchDate = null` (avoid fabricated certainty on failure).
   5. Keep/strengthen recommendation text to indicate calculation failure and guidance to retry/check data.
   6. Add a dedicated test forcing an exception in one dependency and asserting fallback semantics.

   **Dependencies**
   - Independent of H3/M4 logic.
   - Optional product dependency: confirm desired non-LOW tier (MODERATE vs ELEVATED/HIGH).

   **Risk Assessment**
   - What could go wrong:
     - Risk “color” on UI shifts from green to warning during temporary outages.
     - Existing tests or metrics that implicitly assumed LOW on failure may fail.
   - Mitigation:
     - Use clear degraded-mode recommendation text to distinguish “engine unavailable” from true financial distress.
     - Update any affected tests/fixtures in same change set.

   **Verification Plan**
   - Unit tests:
     - Simulate exception (e.g., mocked repo/engine throw) and verify:
       - `overallRiskLevel != LOW`
       - fallback horizons present and aligned
       - recommendation includes failure guidance
   - Manual checks:
     - Trigger failure in debug and confirm widget no longer displays healthy green state.

   **Estimated Effort**
   - **Medium**

3. Batch name: **M4 — Replace stale test that codifies pre-fix behavior**
   - files:
     - `CalculateFinancialForecastUseCaseTest.kt`
   - objective:
     - Make tests assert intended post-fix spending semantics, not legacy inflated totals.
   - risks:
     - If updated test still uses ambiguous naming/fixtures, regressions can reappear.
   - validation:
     - Test name + assertions explicitly encode inclusion/exclusion rules.

   **Root Cause Analysis**
   - **Location:** `CalculateFinancialForecastUseCaseTest.kt:67-108`.
   - Current test name and assertion:
     - Name says it “includes all effective amounts from month start”.
     - Assertion expects `1403.0`, which includes deposit + transfer + future purchase.
   - Why it happens:
     - Test was written against old behavior and now protects incorrect logic.
   - Impact:
     - Correct H3 fix will fail this test, creating pressure to preserve buggy implementation.

   **Implementation Strategy**
   1. Rename test to describe corrected rule (month-to-date purchases only, excluding non-spend types/future).
   2. Keep fixture diversity (purchase, deposit, transfer, not-mine, prior month, future) to guard behavior.
   3. Update expected value to corrected total (based on fixture: `50 + 30 = 80.0`).
   4. (Optional) split into two tests if readability improves:
      - classification by transaction type,
      - time-window exclusion for future transactions.

   **Dependencies**
   - **Depends on H3 rule definition** (must be finalized first).

   **Risk Assessment**
   - What could go wrong:
     - Fragility due to time assumptions (`Calendar` and local timezone).
   - Mitigation:
     - Keep deterministic `now` via mocked `timeProvider.now()` and fixed fixture timestamps.

   **Verification Plan**
   - Unit tests:
     - Updated assertion for corrected month spent.
     - Ensure existing priority-mapping test remains green (no collateral regression).
   - Manual checks:
     - None required beyond test suite for this issue.

   **Estimated Effort**
   - **Low**

### Dependencies
- Sequence recommendation:
  1. **H3 implementation** (logic correction),
  2. **M4 test update** (synchronize expected behavior),
  3. **H4 fallback correction** (independent; can run in parallel with H3/M4 if split across engineers).
- Assumptions/unknowns to resolve before coding:
  - Canonical definition of “spent” in this use case is purchase-only (consistent with `SpendingPaceCalculator`).
  - Desired non-LOW fallback tier for stress forecast failure (recommended default: MODERATE).

### Rollback / Safety
- Keep changes isolated to domain use-case/forecasting logic and tests (no persistence contract change).
- If regression detected:
  - Revert H4 fallback tier/text independently (single-file rollback feasible).
  - Revert H3 predicate change independently while retaining test branch for forensic comparison.
- Safety checks during rollout:
  - Compare pre/post `currentMonthSpent` on fixed fixture payloads.
  - Confirm no runtime exceptions introduced in dashboard stress widget rendering path.

### Acceptance Criteria
- [ ] `CalculateFinancialForecastUseCase` computes `monthSpent` using bounded month-to-date spending semantics and excludes deposits/transfers/future transactions.
- [ ] `CalculateFinancialForecastUseCaseTest` no longer expects pre-fix inflated sum; assertion reflects corrected month spent.
- [ ] `FinancialStressForecastEngine` error fallback returns **non-LOW** overall risk with consistent horizon fallback values.
- [ ] Exception-path unit test exists for `computeStressForecast()` fallback semantics.
- [ ] All related unit tests pass without introducing flaky time-boundary behavior.
