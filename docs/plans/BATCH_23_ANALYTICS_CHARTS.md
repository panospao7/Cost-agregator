# Batch 23 — Analytics & Charts (C1, H7-H8, M4-M5, L4, M11)

## Technical Plan (Advanced)
### Scope
- In:
  - Analytics period consistency, chart rendering reliability, advanced analytics isolation, and DI boundary hygiene for:
    - `C1`, `H7`, `H8`, `M4`, `M5`, `L4`, `M11`
  - Targeted regression tests for analytics state and visualization contracts.
- Out:
  - No replacement of analytics engines.
  - No redesign of analytics navigation IA.
  - No cross-feature rewrites beyond analytics/chart modules and boundary fixes required by this batch.

### Complexity Assessment
- Estimated files touched: **9–16**
  - `ui/screens/analytics/AnalyticsViewModel.kt`
  - `ui/screens/analytics/AnalyticsScreen.kt`
  - `ui/screens/analytics/AdvancedAnalyticsScreen.kt`
  - `ui/screens/analytics/AdvancedAnalyticsViewModel.kt`
  - `di/EmptyStateModule.kt` (and any related DI boundary file)
  - tests under `ui/screens/analytics/*`
- Risk level: **high**
- Cross-module impact: **yes** (analytics domain/viewmodel/UI + DI layering)

### Batch Plan
1. Batch name: **C1 — Enforce single source of truth for analytics period windows across cards/charts**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
     - analytics tests
   - objective:
     - Ensure all sections (totals, breakdowns, charts, drilldowns) derive from the same active period range.
   - risks:
     - Hidden assumptions in engine-specific period helpers may break parity.
   - validation:
     - Cross-widget parity assertions for selected periods (today/week/month/quarter/year/all).

   **Root Cause Analysis**
   - Analytics pipeline combines multiple engines and custom range logic; subtle divergence can occur between derived cards/charts.

   **Implementation Strategy**
   1. Define one canonical `PeriodRange` contract in VM state.
   2. Route all chart/card computations through canonical range.
   3. Add parity tests that compare totals feeding hero, chart, and drilldown filters.

   **Dependencies**
   - Foundation for H7/H8 and M4/M5 validations.

   **Risk Assessment**
   - High.
   - Mitigation: incremental migration with assertions and fallback logs.

   **Verification Plan**
   - Unit tests verifying range parity and deterministic outputs.
   - Manual QA toggling periods and opening drilldowns.

   **Estimated Effort**
   - **High**

2. Batch name: **H7 — Remove risky analytics fallback paths that mask data/compute failures**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
   - objective:
     - Distinguish degraded/partial analytics from healthy analytics state.
   - risks:
     - More visible errors may increase UX noise if not designed carefully.
   - validation:
     - Failure scenarios produce explicit degraded-state UI, not silent “healthy” defaults.

   **Root Cause Analysis**
   - Several blocks swallow exceptions and return empty/null, which can mimic “no data” instead of “computation failed.”

   **Implementation Strategy**
   1. Introduce explicit section-level health metadata.
   2. Preserve available data while exposing degraded section banners.
   3. Add test fixtures with injected engine failures.

   **Dependencies**
   - Depends on C1 state contract to avoid mixed semantics.

   **Risk Assessment**
   - Medium-high.

   **Verification Plan**
   - Unit tests for failure paths and UI state mapping.

   **Estimated Effort**
   - **Medium**

3. Batch name: **H8 — Harden chart model construction for sparse/extreme inputs**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
     - chart component tests
   - objective:
     - Prevent misleading chart visuals or runtime instability with sparse, NaN, Inf, or degenerate datasets.
   - risks:
     - Excessive clamping can flatten meaningful signals.
   - validation:
     - Charts remain stable and interpretable for edge-case datasets.

   **Root Cause Analysis**
   - Some chart sections sanitize values, but guards are uneven across all chart paths.

   **Implementation Strategy**
   1. Centralize chart input sanitization policy.
   2. Apply uniform guardrails for empty/one-point/non-finite datasets.
   3. Add visual contract tests per chart type.

   **Dependencies**
   - Can proceed in parallel with H7 once C1 is stable.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - Unit tests for sanitization helper + UI snapshot checks.

   **Estimated Effort**
   - **Medium**

4. Batch name: **M4 — Localize remaining analytics inline copy and labels**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
     - `app/src/main/res/values/strings.xml`
   - objective:
     - Remove residual hardcoded chart/summary text and use resource-backed copy.
   - risks:
     - Translation key churn and minor snapshot diffs.
   - validation:
     - No targeted hardcoded labels remain; accessibility labels intact.

   **Root Cause Analysis**
   - Some analytics display strings still use inline formatting/text fragments.

   **Implementation Strategy**
   1. Extract targeted literals to resources.
   2. Preserve format placeholders and pluralization.
   3. Update semantics content descriptions in tandem.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Grep/lint check + locale switch smoke test.

   **Estimated Effort**
   - **Low**

5. Batch name: **M5 — Isolate AdvancedAnalytics screen behavior from main analytics pipeline drift**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsScreen.kt`
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsViewModel.kt`
   - objective:
     - Clarify lifecycle, loading, and fallback behavior for advanced dashboard mode.
   - risks:
     - Duplicate logic could diverge unless clear ownership is defined.
   - validation:
     - Advanced screen remains consistent and resilient even when main analytics state changes rapidly.

   **Root Cause Analysis**
   - Advanced analytics path is partly parallel to main screen and can drift in period assumptions and failure handling.

   **Implementation Strategy**
   1. Define explicit contract between advanced VM and shared analytics engines.
   2. Normalize loading/error behavior to match app standards.
   3. Add focused tests for refresh and failure scenarios.

   **Dependencies**
   - Light dependency on C1 conventions.

   **Risk Assessment**
   - Medium.

   **Verification Plan**
   - VM tests for refresh/failure, manual navigation pass.

   **Estimated Effort**
   - **Medium**

6. Batch name: **L4 — Clean up analytics text construction and repetitive derived formatting**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
   - objective:
     - Reduce ad-hoc string assembly and duplicated display transformations.
   - risks:
     - Minor visual text changes.
   - validation:
     - Output text remains semantically equivalent with simpler/centralized helpers.

   **Root Cause Analysis**
   - Frequent inline string composition (`+`, `String.format`, manual sign prefixes) repeated across cards.

   **Implementation Strategy**
   1. Introduce small formatting helpers.
   2. Use resource formats where practical.
   3. Keep helper usage local to analytics module.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low.

   **Verification Plan**
   - Snapshot/text assertion updates.

   **Estimated Effort**
   - **Low**

7. Batch name: **M11 — Keep infrastructure DI module UI-agnostic (boundary enforcement)**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/di/EmptyStateModule.kt`
     - adjacent DI bindings if needed
   - objective:
     - Preserve clean architecture boundaries by preventing UI dependencies in infra DI.
   - risks:
     - Refactor can break binding discovery if module wiring changes incorrectly.
   - validation:
     - Build-time DI graph remains valid; infra module has no UI imports.

   **Root Cause Analysis**
   - Historical concern: infra DI modules can drift into UI/navigation dependencies.
   - Current file appears clean; this issue is hardening/regression-prevention in this batch context.

   **Implementation Strategy**
   1. Add boundary checks/documentation for allowed imports.
   2. Move any discovered UI-bound initializers into presentation DI layer.
   3. Add architecture test or lint rule if feasible.

   **Dependencies**
   - Independent.

   **Risk Assessment**
   - Low-Medium.

   **Verification Plan**
   - Static import audit + successful compile + DI smoke.

   **Estimated Effort**
   - **Low**

### Dependencies
- `C1` should land first to stabilize period semantics.
- `H7` and `H8` depend on stable C1 output contracts.
- `M4`, `L4`, and `M11` can run in parallel after C1 baseline is established.
- `M5` should align with C1 conventions before merge.

### Rollback / Safety
- Ship chart/data-contract changes separately from localization changes.
- Preserve previous analytics state model behind compatibility fields during transition.
- For C1/H7/H8, prefer feature-flagged rollout if high-risk in production.

### Acceptance Criteria
- [ ] C1: All analytics cards/charts/drilldowns use the same period window contract.
- [ ] H7: Analytics failure states are explicit and no longer silently masquerade as valid empty data.
- [ ] H8: Chart rendering is stable for sparse and non-finite edge-case inputs.
- [ ] M4: Targeted inline analytics copy is localized via resources.
- [ ] M5: Advanced analytics flow is behaviorally consistent and resilient under refresh/failure.
- [ ] L4: Repetitive analytics text assembly is centralized and equivalent.
- [ ] M11: Infra DI boundary remains UI-agnostic and build-valid.
