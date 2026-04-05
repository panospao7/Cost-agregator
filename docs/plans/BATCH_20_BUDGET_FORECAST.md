# Batch 20 — Budget & Forecasting fixes (7 issues)

## Technical Plan (Advanced)

### Scope
- In:
  - Fix rolling budget window calculation so `ROLLING` budgets always resolve to the active period containing `now` (**ISSUE-1**).
  - Remove recurring-cost double counting in financial stress forecasting by separating recurring obligations from discretionary Monte Carlo input (**ISSUE-2**).
  - Zero-fill missing months in historical monthly series for budget forecasting averages/trend/confidence (**ISSUE-3**).
  - Zero-fill missing months in budget autopilot monthly series used for trend/volatility/recommendations (**ISSUE-4**).
  - Tighten BIWEEKLY recurrence matching so weekly cadence is not mislabeled as biweekly (**ISSUE-5**).
  - Stop projecting artificial extra discretionary spend on last day of month (`daysRemaining` floor) (**ISSUE-6**).
  - Filter fallback Block Party actual-spend source to spending transactions only (exclude non-PURCHASE/non-user spend) (**ISSUE-7**).
  - Add/adjust unit tests for all above regression paths.
- Out:
  - No DB schema/entity migration.
  - No UI redesign or component contract change.
  - No rewrite of Monte Carlo engine architecture (fixes remain scoped to current engine logic).
  - No changes to recurrence detection engine thresholds outside the BIWEEKLY display matching bug path.

### Complexity Assessment
- Estimated files touched: **10–13**
  - Core logic (5):
    - `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt`
    - `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
    - `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
    - `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`
    - `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
  - Tests (5–8):
    - `.../domain/budget/BudgetCalculatorTest.kt`
    - `.../domain/forecasting/FinancialStressForecastEngineTest.kt`
    - `.../domain/budget/BudgetForecastingEngineTest.kt`
    - `.../domain/budget/BudgetAutopilotEngineTest.kt`
    - `.../domain/logic/SynthesisEngineTest.kt`
    - (Optional) targeted stress/regression tests if needed in `SynthesisEngineStressTest.kt` or verification suites.
- Risk level: **high** (forecasting and budget status behavior changes can materially alter user-facing risk/budget outcomes)
- Cross-module impact: **yes** (budget repository status, dashboard widgets, autopilot recommendations, stress forecast card)

### Batch Plan
1. Batch name: **ISSUE-1 — Rolling budget window must track current cycle**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetCalculatorTest.kt`
     - (Optional) any status-level tests that depend on rolling window semantics
   - objective:
     - Ensure `calculatePeriodRange(..., now)` for `periodMode=ROLLING` returns the **current rolling interval containing `now`**, not the initial anchor window from budget creation.
   - risks:
     - Budget status can shift immediately for existing users with old anchors.
     - Boundary off-by-one around day/week/month transitions and DST.
   - validation:
     - New tests for rolling monthly/weekly windows with old anchors.
     - Verify returned range semantics remain `[start, end)`.

   **Root Cause Analysis**
   - **Location:** `BudgetCalculator.kt:43-47`
   - Current branch hard-codes rolling start to `budget.startDate`, producing stale windows:
     ```kotlin
     val start = budget.startDate
     val end = when (budget.period) {
         BudgetPeriod.MONTHLY -> TimePeriodUtils.addDays(start, 30)
         BudgetPeriod.WEEKLY -> TimePeriodUtils.addDays(start, 7)
         else -> calculatePeriodWindowForTime(...).end
     }
     ```
   - **Why it happens:** `ROLLING` path bypasses `calculatePeriodWindowForTime` for weekly/monthly and never advances to the cycle containing `now`.
   - **Impact:** budget health/remaining/alerts can be computed against an old period window, causing incorrect overspend/on-track states.

   **Implementation Strategy**
   1. Refactor `ROLLING` path to compute window via the same anchor-aware period-window function used elsewhere.
   2. Use `now` as evaluation time and `budget.startDate` only as recurrence anchor.
   3. Preserve `[start, end)` behavior expected by repository range filters (`date >= start && date < end`).
   4. Add tests for:
      - old-anchor rolling monthly window containing today,
      - rolling weekly alignment to anchor weekday,
      - month-end anchor coercion (e.g., anchor day 31).

   **Dependencies**
   - No functional dependency on other issues.
   - Indirectly affects all consumers of `BudgetRepository.getBudgetStatuses()`.

   **Risk Assessment**
   - Could change many users’ current budget percentages immediately.
   - Mitigation: tight regression tests + manual sanity check against known date fixtures.

   **Verification Plan**
   - Unit: `BudgetCalculatorTest` new coverage for `calculatePeriodRange` rolling mode.
   - Integration smoke: verify one repository-driven status scenario now maps to current cycle.

   **Estimated Effort**
   - **Medium**

2. Batch name: **ISSUE-2 — Remove stress-forecast recurring double counting**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt`
   - objective:
     - Ensure recurring obligations are counted exactly once in horizon projection and crunch probability.
   - risks:
     - Over-filtering could understate discretionary spend.
     - Under-filtering leaves residual double-counting.
   - validation:
     - Tests with synthetic recurring + discretionary history proving no duplicate recurring deduction.

   **Root Cause Analysis**
   - **Locations:**
     - `FinancialStressForecastEngine.kt:150-152` (explicit recurring subtraction)
     - `FinancialStressForecastEngine.kt:249-257` (Monte Carlo distribution built from all purchases)
   - Problematic projection path:
     ```kotlin
     projectedBalance = currentBalance + expectedIncome - recurringOutflows - mcResult.percentile50
     ```
     while MC input is built from all purchase history, which already includes recurring-like spend.
   - **Why it happens:** conceptual mismatch: MC is labeled “discretionary” but receives unfiltered purchases.
   - **Impact:** projected balances/min balances skew too low; crunch probability inflated.

   **Implementation Strategy**
   1. Decide and document canonical split: `recurringOutflows` (deterministic) + MC discretionary (stochastic).
   2. Pass recurring context into MC-prep path (e.g., patterns and/or normalized merchant keys).
   3. Exclude recurring-classified historical purchases from empirical discretionary daily totals.
   4. Keep explicit recurring subtraction in projection/crunch formulas.
   5. Add deterministic tests using fixed seed/history to verify recurring amounts are not charged twice.

   **Dependencies**
   - Independent from other issues.
   - Assumption dependency: recurring classification quality (merchant normalization and confidence threshold) is adequate.

   **Risk Assessment**
   - Merchant-name variance may reduce recurring exclusion precision.
   - Mitigation: normalize merchant identifiers consistently; test exact and variant merchant labels.

   **Verification Plan**
   - Unit tests:
     - history with recurring-only purchases + recurringOutflows should yield near-zero discretionary MC median,
     - mixed recurring/discretionary history preserves discretionary signal.
   - Manual check: compare pre/post horizon balances for a curated fixture; post-fix should be less pessimistic only by duplicated recurring component.

   **Estimated Effort**
   - **High**

3. Batch name: **ISSUE-3 — Zero-fill missing months in BudgetForecastingEngine history**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngineTest.kt`
   - objective:
     - Ensure monthly average/trend/variance calculations include zero-spend months inside the analysis window.
   - risks:
     - Behavior changes in confidence/risk level due lower average and potentially higher variance.
     - Window-definition ambiguity (90 days vs calendar months).
   - validation:
     - Tests proving missing month contributes `0.0` and changes average accordingly.

   **Root Cause Analysis**
   - **Location:** `BudgetForecastingEngine.kt:124-135`
   - Current logic aggregates only months present in transactions:
     ```kotlin
     val sortedMonthKeys = monthlyTotals.keys.sorted()
     val values = sortedMonthKeys.map { monthlyTotals[it] ?: 0.0 }
     ```
   - **Why it happens:** no generation of contiguous month buckets for the lookback range.
   - **Impact:** intermittent spend appears artificially steady/high because “no-spend” months are dropped.

   **Implementation Strategy**
   1. Build contiguous month key series for the chosen lookback window.
   2. Initialize monthly totals with zeros for each bucket.
   3. Overlay actual expenses into those buckets.
   4. Use bucket series (not sparse map keys) for average/stddev/trend and `monthsOfHistory`.
   5. Add tests covering one missing middle month and completely empty history.

   **Dependencies**
   - No hard dependency.
   - Optional soft dependency with ISSUE-4: share month-bucket helper approach to keep engines consistent.

   **Risk Assessment**
   - If window boundaries are inconsistent, tests may become timezone-sensitive.
   - Mitigation: use `timeProvider.now()` fixed fixtures and month-start aligned expectations.

   **Verification Plan**
   - Unit tests:
     - Jan + Mar spend with no Feb must produce average across all months including Feb=0.
     - No transactions should produce stable bounded outputs (no NaN/Infinity).
   - Manual check: compare forecast outputs before/after with sparse history account.

   **Estimated Effort**
   - **Medium**

4. Batch name: **ISSUE-4 — Zero-fill autopilot monthly series for trend/volatility**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngineTest.kt`
   - objective:
     - Include zero-spend months in historical spend vector to avoid skewed trend/volatility and recommendation bias.
   - risks:
     - Recommendation deltas may shift significantly for sparse categories.
     - Confidence score may be unintentionally boosted if zero buckets are treated as rich data.
   - validation:
     - Tests demonstrating zero-month inclusion changes outputs in expected direction.

   **Root Cause Analysis**
   - **Location:** `BudgetAutopilotEngine.kt:183-190`
   - Current path creates monthly totals only from observed months and returns `.values.toList()`.
   - **Why it happens:** sparse grouping map has no explicit bucket fill.
   - **Impact:** trend and CV calculations underrepresent inactivity gaps, skewing recommended budgets.

   **Implementation Strategy**
   1. Generate contiguous month buckets for autopilot history horizon.
   2. Fill missing months with 0 and return chronologically ordered series.
   3. Keep transaction filtering semantics (PURCHASE + mine) unchanged.
   4. Validate trend/CV behavior on sparse histories.
   5. If confidence inflation appears, gate with a follow-up TODO (non-blocking unless product requires immediate change).

   **Dependencies**
   - Soft dependency with ISSUE-3 (prefer same month-bucket policy).

   **Risk Assessment**
   - Could trigger more conservative/autocapped recommendations for categories with intermittent spend.
   - Mitigation: preserve existing ±15% delta caps; update tests to assert bounded behavior.

   **Verification Plan**
   - Unit tests:
     - sparse-month category should include zero month in series-derived recommendation,
     - no-history path remains finite and capped.
   - Manual check: inspect one category recommendation reason/trend before vs after in debug run.

   **Estimated Effort**
   - **Medium**

5. Batch name: **ISSUE-5 — Tighten BIWEEKLY recurrence matching**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineTest.kt`
     - (Optional) `.../SynthesisEngineStressTest.kt` targeted recurrence edge case additions
   - objective:
     - Ensure BIWEEKLY matches true 14-day cadence (with tolerance), not generic same-weekday weekly events.
   - risks:
     - Too strict matching may miss legitimate shifted biweekly occurrences.
   - validation:
     - Tests for weekly false-positive prevention and valid biweekly match retention.

   **Root Cause Analysis**
   - **Location:** `SynthesisEngine.kt:425-430`
   - Current check:
     ```kotlin
     dayOfWeekMatch && (daysDiff in -2L..16L)
     ```
   - **Why it happens:** 18-day window admits `daysDiff=7` (weekly), so weekly cadence can be marked BIWEEKLY.
   - **Impact:** Block Party recurring overlays/targets can be wrong on non-biweekly days.

   **Implementation Strategy**
   1. Replace single broad window with distance-to-14-day-cycle logic (nearest multiple of 14 within tolerance).
   2. Use calendar-day diff utility (`TimePeriodUtils.daysBetween`) to avoid DST millisecond drift.
   3. Keep weekday consistency check (or formally justify dropping it).
   4. Add tests:
      - day+7 should not match BIWEEKLY,
      - day+14 should match,
      - month-crossing biweekly occurrence still matches.

   **Dependencies**
   - Independent.
   - Must coordinate with ISSUE-7 tests because both alter Block Party day outcomes.

   **Risk Assessment**
   - Boundary handling for negative diffs (occurrences before anchor) can regress if not explicitly tested.
   - Mitigation: include both positive and negative offset test cases.

   **Verification Plan**
   - Unit tests via `calculateBlockPartyData` outputs (status/recurring impact by day).
   - Manual check on a known biweekly pattern month to ensure two expected bill days.

   **Estimated Effort**
   - **Medium**

6. Batch name: **ISSUE-6 — Remove artificial last-day discretionary projection**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineTest.kt`
   - objective:
     - On the last day of the month, projected discretionary remainder should be `0` days, not `1` day.
   - risks:
     - Minor snapshot changes in forecast outputs that some tests may implicitly expect.
   - validation:
     - Last-day fixture asserts `predictedDiscretionary == 0`.

   **Root Cause Analysis**
   - **Locations:** `SynthesisEngine.kt:101`, `SynthesisEngine.kt:143`
   - Current logic forces `daysRemaining >= 1`, then multiplies by daily discretionary.
   - **Why it happens:** defensive clamp intended to avoid divide-by-zero side effects, but here it introduces false extra day.
   - **Impact:** inflated end-of-month projection on final day.

   **Implementation Strategy**
   1. Change `daysRemaining` lower bound to `0` for projection context.
   2. Reconfirm no downstream division depends on `daysRemaining > 0` in this method.
   3. Add last-day regression test.

   **Dependencies**
   - Independent.
   - Can ship together with ISSUE-5/7 as same-file patchset.

   **Risk Assessment**
   - Very low; isolated arithmetic correction.
   - Mitigation: keep test narrow and deterministic with fixed `timeProvider`.

   **Verification Plan**
   - Unit test for last-day month fixture with non-zero typical daily discretionary baseline.
   - Ensure normal mid-month scenario remains unchanged.

   **Estimated Effort**
   - **Low**

7. Batch name: **ISSUE-7 — Filter fallback actual-spend source to PURCHASE-only**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
     - `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineTest.kt`
   - objective:
     - Ensure fallback `actualFromExpenses` uses spending transactions only (aligned with Block Party spend semantics).
   - risks:
     - If UI expected to show non-spending transactions in top items, behavior will narrow.
   - validation:
     - Tests with mixed PURCHASE/DEPOSIT/TRANSFER proving fallback actual excludes non-PURCHASE.

   **Root Cause Analysis**
   - **Locations:** `SynthesisEngine.kt:319-324`, `374-377`
   - `expensesByDay` is built from date range only, then used as fallback daily actual.
   - **Why it happens:** missing transaction-type ownership filter on fallback source path.
   - **Impact:** deposits/transfers can inflate day spend when `dailySpending` is unavailable.

   **Implementation Strategy**
   1. Filter day-bucket source to spending-eligible transactions (`PURCHASE`, mine-only) before grouping.
   2. Keep fallback precedence unchanged (`dailySpending` first, filtered expense-day sum second).
   3. Confirm top-transactions list policy (spending-only vs all) and align intentionally.
   4. Add regression test with mixed transaction types and empty `dailySpending`.

   **Dependencies**
   - Independent.
   - Test interactions with ISSUE-5 because both influence Block Party day states.

   **Risk Assessment**
   - Potential perception change if users expected all transaction types in Block Party detail.
   - Mitigation: document that Block Party represents spend, not cashflow.

   **Verification Plan**
   - Unit test:
     - mixed-day transactions should fallback to sum of qualifying purchases only.
   - Manual check:
     - simulate missing daily history and verify displayed daily actual matches purchase-only total.

   **Estimated Effort**
   - **Low**

### Dependencies
- Recommended sequence (safe, low-conflict):
  1. **ISSUE-1** (isolated file + high user impact on budgets)
  2. **ISSUE-3 + ISSUE-4** (shared monthly zero-fill concept; can share helper policy)
  3. **ISSUE-2** (independent, but higher complexity/risk)
  4. **ISSUE-5 + ISSUE-6 + ISSUE-7** (same file `SynthesisEngine.kt`, reduce merge churn)
- Assumptions to confirm before coding:
  - Month-bucket policy for ISSUE-3/4: trailing 90-day-derived month range vs fixed last N full calendar months.
  - Recurring exclusion strategy in ISSUE-2: merchant-normalized match is acceptable for first fix.
  - Block Party top transactions are intended to be spending-only when using fallback path.
- No dependency on prior batches is required for functional correctness, but full regression run should include dashboard/verification tests due downstream output changes.

### Rollback / Safety
- Apply one issue per commit (or grouped commits exactly as dependency sequence) for targeted rollback.
- Keep method signatures backward-compatible where possible; if signature changes are required (ISSUE-2), constrain scope to private methods.
- For risky behavior changes (ISSUE-2/3/4), compare pre/post outputs on fixed fixtures and keep snapshots for quick revert.
- If regressions occur:
  - Revert ISSUE-2 independently (high-risk logic branch).
  - Revert ISSUE-5/6/7 as a grouped Synthesis patch if Block Party behavior unexpectedly shifts.
- Safety gates before merge:
  - All touched unit test suites pass.
  - No NaN/Infinity in forecast outputs on sparse/no-data fixtures.
  - Budget/status windows still honor `[start, end)` semantics.

### Acceptance Criteria
- [ ] **ISSUE-1:** `BudgetCalculator.calculatePeriodRange()` returns rolling period containing `now` for rolling budgets (no stale anchor window).
- [ ] **ISSUE-2:** Stress forecast no longer double-counts recurring obligations (recurring deducted once; MC discretionary input excludes recurring-classified spend).
- [ ] **ISSUE-3:** Budget forecasting monthly series includes zero-spend months in lookback window; average/trend/confidence derive from contiguous buckets.
- [ ] **ISSUE-4:** Autopilot monthly series includes zero-spend months; trend/volatility are computed from contiguous chronological buckets.
- [ ] **ISSUE-5:** BIWEEKLY matcher rejects weekly (`+7d`) false positives and matches true biweekly cadence (`±14d` cycles with tolerance).
- [ ] **ISSUE-6:** On month last day, `predictedDiscretionary` does not include a fabricated extra day.
- [ ] **ISSUE-7:** Block Party fallback actual spend excludes non-PURCHASE/non-user transactions.
- [ ] Added/updated tests cover each issue and pass deterministically with fixed-time fixtures.
