# Batch 21 — Home & Dashboard (H1-H3, M1-M2, L1 + 1 unmapped)

## Technical Plan (Advanced)
### Scope
- In:
  - Home/navigation state resilience, dashboard widget UX consistency, and dashboard text/localization hygiene for:
    - `H1`, `H2`, `H3`, `M1`, `M2`, `L1`
    - `UNMAPPED-21A` (see assumption below)
  - Regression coverage in Home/Dashboard UI tests and targeted ViewModel tests.
- Out:
  - No feature redesign of dashboard widget set.
  - No backend/domain algorithm rewrites.
  - No migration of unrelated screens.

### Complexity Assessment
- Estimated files touched: **7–11**
  - `ui/MainActivity.kt`
  - `ui/components/FinancialStressForecastCard.kt`
  - `ui/components/ForecastTimeline.kt`
  - `ui/screens/home/HomeScreen.kt`
  - `ui/components/BudgetBlockPartyCard.kt`
  - `ui/screens/home/HomeViewModel.kt`
  - tests under `ui/screens/home/*`, `ui/components/*`
- Risk level: **medium**
- Cross-module impact: **yes** (navigation state + shared dashboard components)

### Batch Plan
1. Batch name: **H1 — Preserve `activeTransactionFilter` across config/process recreation**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
     - `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeScreenWidgetTest.kt` (or new MainActivity navigation state test)
   - objective:
     - Ensure filter state survives rotation and does not reset unexpectedly when navigating between Home/Transactions.
   - risks:
     - Restored filter payload schema drift can break backward restoration.
   - validation:
     - Rotation/config recreation scenario test verifies filter continuity.

   **Root Cause Analysis**
   - Location: `MainActivity.kt` (`transactionFilterSaver` and `activeTransactionFilter` around lines ~176–230, ~375–391).
   - Why it happens: historical reliance on non-saveable transient state caused loss of filter after configuration changes.
   - Impact: user loses drill-down context after rotation/process recreation.

   **Implementation Strategy**
   1. Freeze the save/restore schema for `TransactionFilter` and document field order.
   2. Add explicit null/invalid-field fallback behavior (graceful restore, no crash).
   3. Add navigation contract assertion: filter clears only on explicit tab intent (not incidental recomposition).

   **Dependencies**
   - No hard dependency.

   **Risk Assessment**
   - Medium: saver schema changes can break old state bundles.
   - Mitigation: compatibility restore path + regression tests with partial payloads.

   **Verification Plan**
   - Unit/UI state test: save/restore round-trip for `TransactionFilter`.
   - Manual: Home → Transactions (with filter), rotate device, verify filter banner still present.

   **Estimated Effort**
   - **Medium**

2. Batch name: **H2 — Keep selected forecast horizon stable after data refresh**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt`
     - component test file (new if needed)
   - objective:
     - Prevent invalid index and selection jumps when `result.horizons` changes.
   - risks:
     - Over-clamping can hide intended default-selection behavior.
   - validation:
     - Test for horizon list shrinking/expanding keeps selected index valid.

   **Root Cause Analysis**
   - Location: `FinancialStressForecastCard.kt` (`selectedHorizon` + clamp logic around lines ~30–41).
   - Why it happens: refresh can change horizon array length while previous selected index is out-of-range.
   - Impact: wrong horizon details shown or potential crash path.

   **Implementation Strategy**
   1. Keep state key tied to `result.horizons` identity.
   2. Clamp selected index every time horizon list changes.
   3. Add contract: empty horizons => safe no-detail rendering.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.
   - Mitigation: explicit edge-case tests (`0`, `1`, `n` horizons).

   **Verification Plan**
   - Compose test: swap `result.horizons` from 3->1 and assert selected panel remains valid.
   - Manual: trigger refresh repeatedly, observe horizon tab stability.

   **Estimated Effort**
   - **Low**

3. Batch name: **H3 — Ensure budget-limit legend item always matches rendered series**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/components/ForecastTimeline.kt`
     - chart component tests
   - objective:
     - Keep legend and plotted series in parity, including conditional budget line.
   - risks:
     - Legend/series divergence during future chart refactors.
   - validation:
     - Snapshot/assertion for both `budgetLimit > 0` and `budgetLimit <= 0`.

   **Root Cause Analysis**
   - Location: `ForecastTimeline.kt` around series build (`hasValidBudget`) and legend rendering (`LegendItem` rows).
   - Why it happens: separate conditions for chart series and legend item can drift.
   - Impact: users misread forecast chart when budget line is plotted but legend absent (or inverse).

   **Implementation Strategy**
   1. Use one canonical flag for both series creation and legend rendering.
   2. Add guard test for parity.
   3. Add small code comment documenting invariant.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Component tests for two states (`hasValidBudget=true/false`).
   - Manual visual QA in forecast card.

   **Estimated Effort**
   - **Low**

4. Batch name: **M1 — Replace remaining hardcoded dashboard copy with resources**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
     - `app/src/main/res/values/strings.xml`
   - objective:
     - Eliminate non-localized hardcoded strings in dashboard prompt cards.
   - risks:
     - Missing translations / key mismatches.
   - validation:
     - Static grep + UI smoke for string resolution.

   **Root Cause Analysis**
   - Location: `HomeScreen.kt` (e.g., `LifestyleSavingsPromptCard`, lines ~1532–1568).
   - Why it happens: fast iteration added inline literals.
   - Impact: localization debt + inconsistent copy management.

   **Implementation Strategy**
   1. Move literals to `strings.xml`.
   2. Keep formatted placeholders for percentages/amounts.
   3. Validate content descriptions remain meaningful.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Unit/UI text test for resources.
   - Manual language switch sanity check.

   **Estimated Effort**
   - **Low**

5. Batch name: **M2 — Localize hardcoded legend/state labels in Block Party card**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/components/BudgetBlockPartyCard.kt`
     - `app/src/main/res/values/strings.xml`
   - objective:
     - Remove hardcoded labels (`Under budget`, `Over budget`, `Bill day`, etc.) from reusable component.
   - risks:
     - Accessibility text regressions if semantics not updated together.
   - validation:
     - Check both visible labels and semantics labels reference resources.

   **Root Cause Analysis**
   - Location: `BudgetBlockPartyCard.kt` around legend and `stateLabel` generation (~132–190).
   - Why it happens: component shipped with inline English labels.
   - Impact: non-localized UX and harder copy governance.

   **Implementation Strategy**
   1. Extract all labels/state text into resources.
   2. Ensure semantic contentDescription uses localized strings.
   3. Add test guard for no hardcoded-state strings in component.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - UI screenshot/snapshot in default locale.
   - Lint/grep for removed literals.

   **Estimated Effort**
   - **Low**

6. Batch name: **L1 — Remove temporary identity fallback in Home recommendation flow**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
   - objective:
     - Replace hardcoded `default_user` fallback with session-derived identity abstraction.
   - risks:
     - Recommendation stream partitioning could change unexpectedly.
   - validation:
     - Verify recommendation refresh works for current user and does not leak cross-user state.

   **Root Cause Analysis**
   - Location: `HomeViewModel.kt` TODO around `defaultRecommendationUserId` (~144–145).
   - Why it happens: placeholder during initial implementation.
   - Impact: weak multi-user isolation semantics and technical debt.

   **Implementation Strategy**
   1. Inject user/session provider contract.
   2. Replace static ID with runtime user key.
   3. Add fallback behavior for signed-out/unknown states.

   **Dependencies**
   - Depends on existing session/user identity provider (if absent, create lightweight contract).

   **Risk Assessment**
   - Medium (state partition behavior change).
   - Mitigation: explicit migration behavior and regression checks.

   **Verification Plan**
   - Unit test for user key propagation.
   - Manual QA with account switch if available.

   **Estimated Effort**
   - **Medium**

7. Batch name: **UNMAPPED-21A — Reconcile missing 7th issue ID for Batch 21**
   - files:
     - `docs/quality/deep-review-source` (external reference)
     - this plan file
   - objective:
     - Resolve input mismatch (`H1-H3, M1-M2, L1` = 6 explicit IDs but batch says 7).
   - risks:
     - Wrong issue implementation sequence if missing ID is not recovered.
   - validation:
     - Confirm canonical deep-review ID and append to implementation queue before coding.

   **Root Cause Analysis**
   - The provided summary is internally inconsistent for Batch 21 count.

   **Implementation Strategy**
   1. Pull canonical issue table from deep review artifact.
   2. Update this plan with exact missing ID + file location.
   3. Block coding for this item until ID is confirmed.

   **Dependencies**
   - External documentation dependency.

   **Risk Assessment**
   - High coordination risk, low technical risk.

   **Verification Plan**
   - Sign-off checklist item: “Batch 21 has 7 concrete IDs.”

   **Estimated Effort**
   - **Low**

### Dependencies
- `H1` should land before cross-screen navigation QA.
- `M1/M2` can run in parallel with `H2/H3`.
- `L1` depends on session provider availability.
- `UNMAPPED-21A` must be resolved before declaring batch complete.

### Rollback / Safety
- Ship one issue per commit for selective rollback.
- Keep string extraction changes isolated to avoid noisy merges.
- For `H1`, preserve backward-compatible saver restore path to avoid crash on stale saved-state payloads.

### Acceptance Criteria
- [ ] H1: Transaction filter survives config/process recreation and only clears on explicit user action.
- [ ] H2: Forecast horizon selection remains valid across refresh/horizon list changes.
- [ ] H3: Forecast legend is always in parity with rendered chart series.
- [ ] M1: Dashboard prompt copy is localized via resources.
- [ ] M2: Block Party labels + semantics are localized (no hardcoded English literals).
- [ ] L1: Home recommendation flow no longer depends on hardcoded `default_user`.
- [ ] Batch 21 count mismatch resolved with canonical 7th issue ID.
