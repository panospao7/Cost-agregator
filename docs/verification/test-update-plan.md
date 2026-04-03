## Technical Plan (Advanced)
### Scope
- In:
  - Expand verification coverage from metric groups **1-7** to **all 12 groups**, with implementation focus on missing groups **8-12**.
  - Update existing golden verification strategy (currently centered in `GoldenMasterVerificationTest.kt`) to include predictor/statistical contract checks and add specialized verification tests for environmental/lifestyle/shared domains.
  - Define deterministic test dataset extensions, expected-value tables, sequencing, and validation gates.
  - Reconcile semantic-contract-map expectations vs current implementation behaviors where they differ.
- Out:
  - Production refactors/fixes to make engines match semantic map where code currently diverges.
  - UI snapshot testing, instrumentation tests, and non-verification stress/perf suites.
  - Git/CI pipeline changes beyond adding/running test classes.

### Complexity Assessment
- Estimated files touched: **8-14**
  - Update: `app/src/test/java/com/yourname/expensetracker/verification/GoldenMasterVerificationTest.kt`
  - New (recommended):
    - `app/src/test/java/com/yourname/expensetracker/verification/StatisticalAnalysisVerificationTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/verification/CarbonFootprintVerificationTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/verification/LifestyleAnalysisVerificationTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseVerificationTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/verification/PredictorCrossEngineVerificationTest.kt` (optional split if Golden grows too large)
  - Optional test fixture helpers:
    - `app/src/test/java/com/yourname/expensetracker/verification/fixtures/ExtendedVerificationDataset.kt`
    - `app/src/test/java/com/yourname/expensetracker/verification/fixtures/ExpectedValues.kt`
  - Documentation:
    - `docs/verification/semantic-contract-map.md` (only if mismatches/clarifications are documented)
    - `docs/verification/engine-verification-matrix.md`
- Risk level: **high**
- Cross-module impact: **yes** (analytics, forecasting, cashflow, tax, carbon, lifestyle, group expenses, repository/use-case wiring in tests)

### Batch Plan
1. Batch name: Contract Reconciliation + Test Architecture Lock
   - files:
     - `docs/verification/test-update-plan.md` (this plan)
     - (optional update) `docs/verification/semantic-contract-map.md`
   - objective:
     - Freeze a single truth table for each Group 8-12 metric: **parity**, **divergence**, or **unique** test type.
     - Resolve documented-vs-implemented mismatches before coding tests (to avoid false negatives).
   - risks:
     - Semantic map contains formulas/components not matching current implementation (examples below).
     - Ambiguity on whether tests should assert “documented intent” or “current behavior.”
   - validation:
     - Sign-off checklist per metric with columns: `source component`, `formula`, `window`, `amount basis`, `expected relation`.
     - No unresolved “TBD” for critical metrics (🔴 priority items).

2. Batch name: Extend Golden Master for Core Analytics Contracts (Groups 8-9)
   - files:
     - `app/src/test/java/com/yourname/expensetracker/verification/GoldenMasterVerificationTest.kt`
     - optional helper fixture file(s) under `verification/fixtures`
   - objective:
     - Add predictor + statistical tests that are cross-engine and contract-oriented.
     - Keep deterministic clocks and deterministic simulation stubs where stochastic behavior exists.
   - risks:
     - Golden file bloat (>1000 lines) and readability degradation.
     - Monte Carlo determinism depends on fit quality and seeded randomness.
   - validation:
     - New tests grouped with prefixes:
       - `PARITY - predictors ...`
       - `DIVERGENCE - predictors ...`
       - `PARITY - anomalies ...`
       - `VERIFICATION - health/threshold ...`
     - Must pass with deterministic assertions and no flaky tolerance bands > 1% except simulation-specific checks.

3. Batch name: Statistical Dedicated Verification Split (if Golden exceeds maintainability threshold)
   - files:
     - `app/src/test/java/com/yourname/expensetracker/verification/StatisticalAnalysisVerificationTest.kt`
   - objective:
     - Move Group 9 tests out of Golden if class size/complexity becomes unsafe.
   - risks:
     - Duplicate setup code between Golden and new class.
   - validation:
     - Shared fixture helper reused; no duplicated inline dataset constants.
     - Class-level focus: anomaly methods (IQR/MAD/Contextual), health scoring, threshold P90.

4. Batch name: Environmental Verification (Group 10)
   - files:
     - `app/src/test/java/com/yourname/expensetracker/verification/CarbonFootprintVerificationTest.kt`
   - objective:
     - Verify category/merchant emissions and offset math against deterministic merchant/category factors.
   - risks:
     - Existing map says amount-basis `amount`, code uses `effectiveAmount` in calculator.
     - “Trees needed” metric is documented but not present in current model.
   - validation:
     - Assertions for exact totals from known factors.
     - Explicit divergence test or TODO marker for undocumented metric gaps (trees-needed).

5. Batch name: Lifestyle Verification (Group 11)
   - files:
     - `app/src/test/java/com/yourname/expensetracker/verification/LifestyleAnalysisVerificationTest.kt`
   - objective:
     - Verify lifestyle inflation, trend deltas, elasticity, and pattern-derived signals.
   - risks:
     - Semantic map terms (weekend ratio/impulse/routine as explicit fields) differ from current engine outputs.
     - Requires sufficient month history to avoid sparse-data false behavior.
   - validation:
     - Deterministic multi-month fixture with known income/spending growth trajectory.
     - Assertions for trend/elasticity/lifestyleInflationRate + presence/priority of recommendations.

6. Batch name: Shared Expense Verification (Group 12)
   - files:
     - `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseVerificationTest.kt`
   - objective:
     - Validate equal split, percentage split, and member net balances from controlled group expense set.
   - risks:
     - `SharedExpenseManager` currently exposes `calculateBalances`; settlement optimization APIs in semantic map are not present as public methods.
   - validation:
     - Assert split math via resulting balances (`paid - shouldPay`) for each member.
     - If settlement optimizer is unavailable, log as contract gap and add pending test scaffold (disabled with reason).

7. Batch name: Cross-Group Integration + Final Matrix Update
   - files:
     - `app/src/test/java/com/yourname/expensetracker/verification/GoldenMasterVerificationTest.kt` (or separate integration verification file)
     - `docs/verification/engine-verification-matrix.md`
   - objective:
     - Validate high-value chained contracts (Synthesis → Dashboard widget projection; anomalies stable across runs; shared effective amount behavior reflected in predictors).
   - risks:
     - Over-coupling integration tests to internal wiring.
   - validation:
     - Integration assertions limited to stable public outputs.
     - Verification matrix updated to mark Group 8-12 coverage complete.

### Dependencies
- **Batch 1** is a hard prerequisite for all coding batches.
- **Batch 2** depends on:
  - Existing Golden dataset setup in `GoldenMasterVerificationTest.kt`
  - Stable clock control (`timeProvider.now()`)
  - Stubbing strategy for Monte Carlo and recurrence-driven inputs.
- **Batch 4/5/6** can run in parallel after Batch 1.
- **Batch 7** depends on completion of batches 2, 4, 5, 6.

---

### Planned Test File Structure (Requested Output Item #1)
- Keep hybrid approach (recommended):
  - **Golden Master**: core predictor + statistical cross-engine contracts (Groups 8-9).
  - **Separate specialized files**:
    - `CarbonFootprintVerificationTest.kt` (Group 10)
    - `LifestyleAnalysisVerificationTest.kt` (Group 11)
    - `SharedExpenseVerificationTest.kt` (Group 12)
- Optional split trigger:
  - If Golden exceeds ~900 lines or setup duplication rises, move Group 9 to `StatisticalAnalysisVerificationTest.kt`.

### Test Data Definition (Requested Output Item #2)
- Base: existing March/February deterministic fixtures in Golden Master.
- Extend with deterministic scenario blocks:
  1. **Recurring + planned (Predictors)**
     - Recurring patterns: Rent €800/mo, Netflix €12.99/mo, Gym €30/mo
     - Planned: Car repair €200 (LIKELY), Vacation €500 (MUST in next month scenario)
     - Budget: €1500 monthly (overall)
  2. **Statistical outlier packs**
     - IQR pack: `[10, 11, 12, 13, 200]`
     - MAD pack: same set (ensures modified-Z outlier)
     - Contextual pack: same day/time-slot cluster with one extreme outlier
  3. **Threshold 90-day pack**
     - Sorted values set with known P90 interpolation; include low-value set to enforce €50 floor.
  4. **Carbon pack**
     - `SHELL €50`, `SKLAVENITIS €80`, `PLAISIO €100` purchases
  5. **Lifestyle 3-6 month pack**
     - Income: `€2800 -> €3000 -> €3200`
     - Spending: `€2000 -> €2300 -> €2700`
     - Discretionary keywords in merchant/notes for detector classification.
  6. **Shared group pack**
     - Members A/B/C
     - Expense #1: €120 EQUAL, paid by A
     - Expense #2: €90 CUSTOM_PERCENT (50/30/20), paid by B

### Expected Values Table (Requested Output Item #3)
| Group | Metric | Scenario | Expected Value / Relation |
|---|---|---|---|
| 8 | Linear projection | MTD spent=€618.49, day=20, daysInMonth=31 | `€958.66` (`618.49*31/20`) |
| 8 | Synthesis projection parity | Same inputs into Synthesis/Weather/Dashboard | identical projected tail value |
| 8 | Monte Carlo p50 parity/divergence | same simulator seed + dashboard knownUpcoming vs smart=0 | dashboard p50 > smart p50; dashboard p50 ~ linear band |
| 8 | Trend-adjusted divergence | BudgetForecast vs linear | values differ (`abs(diff) > epsilon`) |
| 8 | Cashflow predicted recurring | date-window includes recurring nextDate | predictedRecurring contains expected pattern dates |
| 8 | Bill reminders next date | mark paid + recurrency | nextDate increments by freq (weekly +7, monthly +1mo) |
| 8 | Tax estimate | GR config, monthly income €2500, deductible €300 | taxable=€2200, tax=€2200*0.09, VAT from purchases formula |
| 8 | Financial runway | discretionary €298.52, burn €30.9245/day | `9` days (int truncation), status CAUTION/CRITICAL by threshold |
| 9 | IQR anomaly | `[10,11,12,13,200]` | 200 flagged |
| 9 | MAD anomaly | same set | 200 flagged (modified Z > 3.5) |
| 9 | Contextual anomaly | same context with one extreme | extreme tx flagged + contextual note |
| 9 | Health score bounds | deterministic budget/pending/streak case | score within `[0,100]`, composite uses 20/30/50 weights |
| 9 | Spending threshold | P90 set + low-sample set | P90 result when sample>=10, else min €50 |
| 10 | Carbon by merchant/category | SHELL50, SKL80, PLAISIO100 | 115 + 20 + 80 = `215 kg CO2` |
| 10 | Carbon offset | total 215kg, €22/ton | `€4.73` |
| 11 | Lifestyle inflation rate | income 2800→3200, spending 2000→2700 | inflation = `35.0%-14.29%=20.71%` |
| 11 | Elasticity | same 3-month data | avg elasticity ~`2.36` (luxury-like >1) |
| 11 | Spending patterns | high weekend-heavy fixture | weekend ratio/pattern indicators above weekday baseline |
| 12 | Equal split | €120 / 3 | €40 each |
| 12 | Percentage split | €90 @ 50/30/20 | €45 / €27 / €18 |
| 12 | Group balance | two-expense scenario | A=+35, B=+23, C=-58 |
| 12 | Settlement optimization | same balances | minimum 2 settlements (if optimizer exposed) |

### Implementation Order (Requested Output Item #4)
1. Batch 1 contract reconciliation (blocker)
2. Batch 2 Golden core additions (critical parity/divergence)
3. Batch 6 Shared expense verification (critical parity)
4. Batch 4 Carbon tests
5. Batch 5 Lifestyle tests
6. Batch 7 cross-group integration + matrix update

### Test Execution Strategy (Requested Output Item #5)
- Fast loop by class:
  - `./gradlew :app:testDebugUnitTest --tests "*GoldenMasterVerificationTest"`
  - `./gradlew :app:testDebugUnitTest --tests "*StatisticalAnalysisVerificationTest"`
  - `./gradlew :app:testDebugUnitTest --tests "*CarbonFootprintVerificationTest"`
  - `./gradlew :app:testDebugUnitTest --tests "*LifestyleAnalysisVerificationTest"`
  - `./gradlew :app:testDebugUnitTest --tests "*SharedExpenseVerificationTest"`
- Full verification pass:
  - `./gradlew :app:testDebugUnitTest --tests "*verification*"`
- Determinism controls:
  - fixed `timeProvider.now()`
  - seeded/stubbed Monte Carlo where needed
  - no dependence on system locale/timezone in expected-value assertions.

### Success Criteria (Requested Output Item #6)
- 100% of Group 8-12 metrics mapped to at least one verification test case.
- Critical parity contracts (🔴) pass consistently in local/CI runs.
- Divergence contracts assert intentional non-equality with documented rationale.
- Known contract gaps are explicitly marked (pending/disabled with reason) instead of silently skipped.

---

### Rollback / Safety
- Add tests in isolated batches; do not modify existing passing assertions until new tests are green.
- If a batch introduces instability:
  1. Revert only new test class(es) from that batch.
  2. Keep fixture helpers if backward compatible.
  3. Re-run baseline verification classes to ensure no regressions.
- Use conservative assertion tolerances only for stochastic paths; exact arithmetic for deterministic formulas.

### Acceptance Criteria
- [ ] Golden verification expanded to cover Group 8 and Group 9 contracts (or split with equivalent coverage).
- [ ] Dedicated verification files exist and pass for Group 10, Group 11, Group 12.
- [ ] Expected values table is implemented in test constants/fixtures and traceable to formulas.
- [ ] Critical parity metrics validate cross-component consistency (not only single-component unit checks).
- [ ] Divergence metrics are explicitly asserted and documented.
- [ ] Engine verification matrix updated to reflect new coverage and remaining known gaps.
- [ ] Entire verification suite is deterministic and repeatable.

---

### Assumptions & Unknowns (must be resolved in Batch 1)
- Assumption: tests should validate **current implementation contracts** first, then separately flag semantic-map drift.
- Unknown: semantic map includes components/methods not present or not exposed in current code (e.g., settlement optimizer API, trees-needed carbon metric).
- Unknown: Bill reminder recurrence currently uses internal `calculateNextDate` rather than `RecurrenceCalculator`; parity expectation must be validated against real implementation.
- Unknown: Carbon amount basis in map vs implementation differs (`amount` vs `effectiveAmount` behavior appears mixed in docs/code).
- Unknown: Lifestyle “weekend/impulse/routine” metrics in map do not map 1:1 to current return model fields.
