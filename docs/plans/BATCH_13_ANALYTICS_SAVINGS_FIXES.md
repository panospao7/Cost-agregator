# Batch 13: Analytics & Savings Fixes (C1, H5, H6)

## Technical Plan (Advanced)

### Scope
- In:
  - **C1**: Add safety validation for `ROUND_UP` rule configuration in `AutomatedSavingsRuleEngine`.
  - **H5**: Stabilize runway score during early-month partial data in `FinancialHealthScoreV2`.
  - **H6**: Remove duplicated spending pace logic in `InsightsEngine` and converge to one canonical calculation path.
- Out:
  - UI redesign/copy changes for health or insights screens.
  - Database schema changes/migrations.
  - New analytics features beyond correctness/stability of existing metrics.
  - Broad dashboard pace refactor outside this batch (except compatibility checks to prevent divergence).

### Complexity Assessment
- Estimated files touched: **8–12**
  - Main code (expected):
    - `app/src/main/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt`
    - `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
    - `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
    - `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt` (likely for parity/integration adjustments)
  - Test code (expected):
    - `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2Test.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineValidationTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculatorValidationTest.kt`
    - Potential new tests under `domain/savings/` for `AutomatedSavingsRuleEngine` (currently absent)
- Risk level: **medium**
- Cross-module impact: **yes** (domain analytics + health + savings, with downstream impact on widgets/insights behavior)

### Assumptions & Unknowns (must be resolved before code freeze)
- **A1 (C1 policy):** For invalid `roundUpTo` values (`<= 0`, non-finite), should behavior be:
  1) fail-safe skip rule execution, or
  2) fallback to default increment (`5.0`)?
  - Recommended: **skip + log warning**, to avoid hidden unexpected transfers.
- **A2 (H5 baseline horizon):** Stabilization window should use trailing **3 months** (recommended) vs 1 or 6 months.
- **A3 (H5 neutral behavior):** If both current and historical spending baselines are insufficient, runway score should remain **neutral (50)**.
- **A4 (H6 no-baseline semantics):** Current pace behavior in code returns `NO_BASELINE` + `pacePercentage = 0`; some tests appear to assume `100%`. Product/analytics owner must confirm canonical expected behavior.

---

### Batch Plan

1. Batch name: **C1 — ROUND_UP safety validation hardening**
   - files:
     - `.../domain/savings/AutomatedSavingsRuleEngine.kt`
     - `.../test/.../domain/savings/*` (new/updated tests)
   - objective:
     - Prevent invalid `roundUpTo` values from producing undefined math and inconsistent savings transfers.
   - risks:
     - Behavior change for existing malformed rules.
     - Potential silent reduction in auto-savings events if many invalid configs exist.
   - validation:
     - Add focused unit tests for invalid/edge increment values and expected fail-safe behavior.

   #### C1 — Issue Plan
   1) **Root Cause Analysis**
   - Exact location: `AutomatedSavingsRuleEngine.kt:117-126`.
   - Current flow takes `roundUpTo = rule.roundUpTo ?: 5.0`, then immediately uses modulo/division math.
   - No guard for invalid values (`0`, negative, `NaN`, `Infinity`), which can produce non-finite results and misleading reason strings.
   - Impact:
     - Incorrect or skipped execution for `ROUND_UP` rules with bad config.
     - Potentially nonsensical calculations in runtime logs/reasons.

   2) **Implementation Strategy**
   - Step 1: Introduce a local validation/normalization decision point before modulo math.
   - Step 2: Enforce finite, strictly positive increment requirement.
   - Step 3: Define explicit fail-safe behavior for invalid configuration (recommended: no execution + warning log).
   - Step 4: Keep null behavior compatible (`null -> default 5.0`) unless product requires stricter validation.
   - Step 5: Add boundary tests for values: `null`, `0`, negative, tiny positive, integer/decimal positive, non-finite.

   - Logic sketch (non-implementation):
     ```text
     increment = configuredRoundUpOrDefault
     if increment is not finite OR increment <= 0 -> skip execution (safe)
     else compute remainder and round-up amount as today
     ```

   3) **Dependencies**
   - No dependency on other batch issues.
   - Depends on final product decision for invalid-config behavior (A1).

   4) **Risk Assessment**
   - What could go wrong:
     - Existing users with malformed rules may stop seeing transfers unexpectedly.
   - Mitigation:
     - Add warning telemetry/logging when invalid rules are skipped.
     - Optional migration/audit script in a later batch to identify malformed rule configs.

   5) **Verification Plan**
   - Unit tests:
     - Valid values produce expected round-up amounts.
     - Invalid values never produce `RuleExecution`.
     - Null value preserves default behavior.
   - Manual checks:
     - Simulate purchase with rule `roundUpTo=0` and confirm no transfer + warning.
     - Simulate purchase with rule `roundUpTo=5` and confirm unchanged output.

   6) **Estimated Effort**
   - **Low**

   - Completion criteria:
     - [ ] `ROUND_UP` evaluation is safe for all invalid numeric configurations.
     - [ ] Tests cover invalid and boundary increment scenarios.
     - [ ] No regression in valid round-up behavior.

2. Batch name: **H5 — Runway score stabilization for partial month data**
   - files:
     - `.../domain/health/FinancialHealthScoreV2.kt`
     - `.../test/.../domain/health/FinancialHealthScoreV2Test.kt`
   - objective:
     - Prevent early-month volatility and inflated runway scores caused by sparse/partial purchase data.
   - risks:
     - Over-smoothing may hide real spending acceleration.
     - Behavior changes could shift historical trend labels.
   - validation:
     - Deterministic tests across day-1/day-3/day-15/day-end scenarios and sparse bill timing.

   #### H5 — Issue Plan
   1) **Root Cause Analysis**
   - Exact location: `FinancialHealthScoreV2.kt:228-248` in `calculateRunwayScore(...)`.
   - Current logic uses period purchases sum as “monthly expenses” directly.
   - When health score runs mid-month (default period includes current month), early data is incomplete, so denominator is too small.
   - Result: runway months and runway score can spike unrealistically early in month and then collapse later.

   2) **Implementation Strategy**
   - Step 1: Extend runway computation inputs to include period context (`periodStart`, `periodEnd`, `now`) and optional historical baseline source.
   - Step 2: Compute **coverage ratio** = elapsed days / days in period.
   - Step 3: Estimate effective monthly expenses using stabilization blend:
     - `projectedCurrent = observedCurrent / coverage` (when coverage > 0)
     - `historicalMonthlyAvg = trailing full-month average purchases` (recommended 3 months)
     - `effectiveMonthlyExpense = blend(projectedCurrent, historicalMonthlyAvg, coverage)`
   - Step 4: If baseline quality is insufficient (very low coverage and no history), return neutral runway score (50).
   - Step 5: Keep runway scoring curve unchanged (0–6 months maps to 0–100) to avoid changing KPI interpretation.
   - Step 6: Add debug-level observability fields (coverage, projected, historical, effective) for tuning.

   - Formula sketch (non-implementation):
     ```text
     coverage = elapsedDays / totalDaysInPeriod
     projectedCurrent = observedPurchases / max(coverage, epsilon)
     effectiveMonthlyExpense = coverage * projectedCurrent + (1 - coverage) * historicalMonthlyAvg
     runwayMonths = totalSavings / effectiveMonthlyExpense
     runwayScore = clamp((runwayMonths / 6.0) * 100, 0, 100)
     ```

   3) **Dependencies**
   - No strict dependency on C1/H6.
   - Depends on historical lookback policy decision (A2) and neutral fallback decision (A3).

   4) **Risk Assessment**
   - What could go wrong:
     - Incorrect day coverage calculations (off-by-one) can bias scores.
     - Historical data with missing months may create skewed baseline.
   - Mitigation:
     - Explicit tests for month length and mid-month boundaries.
     - Ignore empty historical months or use robust averaging policy with clear docs.

   5) **Verification Plan**
   - Unit tests (new + updated in `FinancialHealthScoreV2Test`):
     - Early-month sparse spending no longer yields extreme runway score.
     - Mid/late-month scores converge near current logic.
     - Zero expenses + no baseline still returns neutral 50.
     - Leap month and 30/31-day months behave consistently.
   - Manual checks:
     - Compare same dataset scored on day 2 vs day 20; early-month variance should reduce materially.

   6) **Estimated Effort**
   - **Medium**

   - Completion criteria:
     - [ ] Runway score remains stable under early-month partial-data scenarios.
     - [ ] Unit coverage includes sparse, normal, and no-baseline cases.
     - [ ] Existing score semantics (0–100 mapping) remain intact and documented.

3. Batch name: **H6 — Canonicalize spending pace logic (remove duplication in InsightsEngine)**
   - files:
     - `.../domain/analytics/InsightsEngine.kt`
     - `.../domain/analytics/SpendingPaceCalculator.kt` (if interface/parity changes needed)
     - `.../test/.../domain/analytics/InsightsEngineValidationTest.kt`
     - `.../test/.../domain/analytics/SpendingPaceCalculator*Test.kt`
   - objective:
     - Ensure one pace formula path to prevent drift between calculators/engines.
   - risks:
     - Legacy tests may encode old divergent behavior.
     - Downstream widgets may expect one of the divergent implementations.
   - validation:
     - Add parity tests asserting InsightsEngine pace output equals canonical calculator output for shared fixtures.

   #### H6 — Issue Plan
   1) **Root Cause Analysis**
   - Exact location: `InsightsEngine.kt:405-481` (`buildSpendingPace`).
   - `InsightsEngine` contains full pace formula logic even though `SpendingPaceCalculator` already exists with near-identical formula (`SpendingPaceCalculator.kt:52-83`).
   - Additional similar logic exists in dashboard use case (`ComputeDashboardWidgetsUseCase.kt:398-427`), increasing drift risk.
   - Impact:
     - Different pace status/percentage possible across features depending on code path.
     - Harder maintenance and bug-fix propagation.

   2) **Implementation Strategy**
   - Step 1: Define `SpendingPaceCalculator` as canonical source for pace formula and status thresholds.
   - Step 2: Refactor `InsightsEngine.buildSpendingPace(...)` to delegate pace math to canonical calculator.
   - Step 3: Preserve InsightsEngine-specific enrichments (e.g., `averageMonthlyTotal`) without re-implementing formula logic.
   - Step 4: Add contract/parity tests:
     - same input expenses/time => same `pacePercentage` and `paceStatus` between engine and calculator path.
   - Step 5: Create follow-up ticket (outside this batch) to reconcile dashboard use-case pace duplication with canonical path.

   - Flow sketch (non-implementation):
     ```text
     InsightsEngine.buildSpendingPace
       -> call SpendingPaceCalculator.calculate(...)
       -> attach extra metadata (avg monthly total)
       -> return SpendingPace
     ```

   3) **Dependencies**
   - Independent from C1/H5 code changes.
   - Depends on clarified canonical no-baseline semantics (A4) before updating tests.

   4) **Risk Assessment**
   - What could go wrong:
     - Unintended behavior shift where prior divergence was relied upon.
     - Test flakiness if time boundaries differ between call sites.
   - Mitigation:
     - Fix test fixtures to deterministic dates/timeProvider.
     - Compare output fields directly in parity tests.

   5) **Verification Plan**
   - Unit tests:
     - InsightsEngine parity test with SpendingPaceCalculator for boundary values (90/110 thresholds).
     - No-baseline path consistency test.
     - Conservative projection behavior in first 3 days remains consistent.
   - Manual checks:
     - Validate insights card status text does not contradict calculator-based pace under same data.

   6) **Estimated Effort**
   - **Medium**

   - Completion criteria:
     - [ ] InsightsEngine no longer contains independent pace formula logic.
     - [ ] Canonical calculator parity tests pass for core/boundary scenarios.
     - [ ] Any remaining duplicated pace logic is explicitly tracked for follow-up.

4. Batch name: **Cross-issue regression and release hardening**
   - files:
     - Test files across savings/health/analytics modules
     - Optional docs/changelog entries
   - objective:
     - Validate the three fixes together and avoid KPI regressions.
   - risks:
     - Combined metric movement surprises (health trend changes due to stabilized runway).
   - validation:
     - Run targeted suites for analytics + health + savings.
     - Compare before/after snapshots on representative fixtures.

   - Completion criteria:
     - [ ] No new regressions in existing analytics and health tests.
     - [ ] Observed metric shifts are explained and accepted by product/analytics owner.

---

### Dependencies
- **Issue-level dependency map**
  - C1: independent.
  - H5: independent implementation; requires policy decisions A2/A3.
  - H6: independent implementation; requires policy decision A4.
- **Recommended sequencing**
  1. C1 (low-risk safety fix)
  2. H6 (structural dedupe before further pace-related edits)
  3. H5 (metric stabilization with fresh deterministic baseline tests)
  4. Cross-issue regression pass

### Rollback / Safety
- C1 rollback:
  - Revert validation gate if unexpected production suppression occurs, but keep telemetry to detect malformed rules.
- H5 rollback:
  - Feature-toggle or guarded fallback to prior runway method if score continuity impact is unacceptable.
  - Keep old/new runway values in logs during canary period for comparison.
- H6 rollback:
  - Revert InsightsEngine delegation only (leave calculator untouched) if parity issues are detected.
- General safety:
  - Avoid mixed semantics by landing tests in the same PR as behavior changes.
  - Release with staged verification on deterministic fixtures before broad rollout.

### Acceptance Criteria
- [ ] **C1:** Invalid `roundUpTo` values are safely handled and fully covered by tests.
- [ ] **H5:** Runway score no longer exhibits early-month instability on sparse partial data fixtures.
- [ ] **H6:** InsightsEngine uses canonical pace calculation path with parity tests proving no divergence.
- [ ] Decisions for A1/A2/A3/A4 are documented before merge.
- [ ] Targeted analytics/health/savings regression suites pass.
