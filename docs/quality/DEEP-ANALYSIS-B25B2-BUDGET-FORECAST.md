# Budget & Forecast Test Bugs (B25b2)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| **BudgetCalculatorTest.kt:19** | 🔴 Critical | Flaky Test / Non-Determinism | `setUp()` uses `System.currentTimeMillis()` for `timeProvider.now()`. Results depend on exact clock millisecond. | Replace with a fixed timestamp. |
| **BudgetCalculatorTest.kt:25-26** | 🔴 Critical | Flaky Test / Non-Determinism | `calculatePeriodWindow DAILY` test uses `System.currentTimeMillis()`. The 24-hour diff assertion will fail during DST spring-forward (23h) or fall-back (25h). | Use a fixed date known to be DST-neutral and hardcoded. |
| **BudgetCalculatorTest.kt:43-45** | 🟡 Medium | Flaky Test / Non-Determinism | `WEEKLY` test sets `DAY_OF_WEEK` to Monday without clearing `MILLISECOND` or `SECOND` fields. | Add `cal.set(Calendar.MILLISECOND, 0)` after each `Calendar.getInstance()` call. |
| **BudgetCalculatorStressTest.kt:18** | 🔴 Critical | Flaky Test / Non-Determinism | Uses `System.currentTimeMillis()` as anchor and will produce different start times depending on local timezone and DST transitions. | Use fixed timestamps. |
| **BudgetCalculatorStressTest.kt:28-29** | 🟡 Medium | Flaky Test / Non-Determinism | `WEEKLY` test sets `DAY_OF_WEEK` to Monday without clearing time-of-day fields. | Use a fully specified fixed date known to be Monday. |
| **BudgetCalculatorStressTest.kt:336-348** | 🟡 Medium | Flaky Test | Performance test uses `System.nanoTime()` and asserts < 1 second. Can fail under CI/load. | Increase threshold significantly or remove hard time assertions. |
| **BudgetCalculatorStressTest.kt:352-357** | 🟡 Medium | Test Logic Bug | `calculateDailyPeriod` helper does not clear `MINUTE`, `SECOND`, `MILLISECOND` when setting `HOUR_OF_DAY=0`. | Clear all sub-hour fields after setting `HOUR_OF_DAY = 0`. |
| **BudgetCalculatorStressTest.kt:374-381** | 🟢 Low | Missing Coverage | `calculatePeriod` helper maps `YEARLY` to `calculateMonthlyPeriod`, which is incorrect. | Implement a proper `calculateYearlyPeriod` helper. |
| **BudgetMonitorStressTest.kt:38** | 🔴 Critical | Flaky Test / Shared State | `setUp()` uses `System.currentTimeMillis()`. `BudgetMonitor` has a `lastCheckTime` mutable field. If the host clock drifts or the test takes >30s, behavior is unpredictable. | Always use deterministic fixed timestamps. |
| **BudgetMonitorStressTest.kt:132-141** | 🟡 Medium | Inconsistent Test Data / Assertion Bug | `spentAmount = 0.0` but `percentUsed = 0.6f`. These are contradictory. | Either set `percentUsed = 0.0f` to match, or use a realistic scenario. |
| **BudgetMonitorTest.kt:23-27** | 🟡 Medium | Test Isolation / Shared Mock State | Mocks are created as `val` class members. Recorded interactions leak across tests. | Either re-create mocks in `@Before` or add `clearAllMocks()` in `@Before`. |
| **BudgetMonitorTest.kt:169** | 🟢 Low | Hardcoded Test Data / Logic Bug | `budgetStatus` helper always sets `healthStatus = BudgetHealthStatus.WARNING` regardless of actual `percentUsed`. | Compute `healthStatus` dynamically from `percentUsed`. |
| **BudgetMonitorTest.kt:162** | 🟢 Low | Hardcoded Test Data | `periodEnd` is always `periodStart + 7 days` even for `MONTHLY` budgets. | Compute `periodEnd` based on the actual `period` parameter. |
| **SharedBudgetManagerTest.kt:139-148** | 🟡 Medium | Flaky Test / Timezone | `startOfMonth` helper uses `Calendar.getInstance()` which respects `TimeZone.getDefault()`. | Use a single consistent time API and consider fixing the timezone in tests. |
| **SharedBudgetManagerTest.kt:151-162** | 🟢 Low | Code Duplication | The `atDateTime` helper duplicates the one in the base class. | Remove duplicate helpers; use base-class or `TestUtils` date helpers. |
| **BudgetCalculatorBoundaryTest.kt:87-108** | 🟡 Medium | Flaky Test / Global State Mutation | `daily period across athens dst spring forward` mutates `TimeZone.getDefault()` globally. | Use `@Rule` with a timezone reset rule. |
| **BudgetForecastingEngineTest.kt:50-67** | 🟢 Low | Fragile Assertion | Hardcodes expected values for confidence and overspend probability. | Consider using range-based assertions for derived metrics. |
| **BudgetTrendBoundaryTest.kt:29** | 🟢 Low | Non-Determinism Risk | The `now` field uses `ZoneId.systemDefault()`. | Fix the timezone in the test. |
| **BudgetAutopilotEngineTest.kt:45-46** | 🟡 Medium | Mock Setup Risk | `budgetRepository` and `expenseRepository` are created with `mockk()` (NOT relaxed). | Consider using `mockk(relaxed = true)` or at minimum stub all known interactions. |
| **BudgetAutopilotEngineTest.kt:274-283** | 🟡 Medium | Subtle Bug in Helper | `millis()` helper uses `Calendar.MONTH` field directly. The risk is if someone copies this pattern and passes a 1-indexed month. | Add a comment clarifying that `month` must be `Calendar.*` constants (0-indexed). |
| **MonteCarloSpendingSimulatorGoldenTest.kt:64-68** | 🟡 Medium | Fragile Golden Test | Golden snapshot values tied to `Random(42)` output on a specific JVM. Different JVM implementations may produce different random sequences. | Document the exact JVM version this was validated against. |
| **MonteCarloSpendingSimulatorTest.kt:46** | 🟡 Medium | Mock Setup / Potential NPE | `every { dataQualityAssessor.assess(null, 0) }` stubs specifically. If production code calls with different second arg, the stub won't match. | Use `any()` for the second argument if the iteration count isn't being tested. |
| **FinancialStressForecastEngineTest.kt:222-229** | 🟢 Low | Fragile Test / Reflection | `classifyRiskLevelViaReflection` accesses a private method via reflection. | Consider making `classifyRiskLevel` internal/package-private for testability. |
| **FinancialStressForecastEngineTest.kt:44-45** | 🟡 Medium | Mutable Shared State in Tests | `allExpenses` and `allDeposits` are mutable `var` class members set per-test. | This pattern is acceptable in JUnit 4, but add a comment confirming this assumption. |
| **BudgetMonitorStressTest.kt:162-168** | 🟡 Medium | Assertion Gap | Verifies that `updateExceededNotification` is NOT called, but does NOT verify that `updateWarningNotification` and `updateCriticalNotification` are also NOT called. | Add `coVerify(exactly = 0)` for warning and critical notifications as well. |
| **BudgetCalculatorStressTest.kt:48-58** | 🟡 Medium | Misleading Assertion / Non-Determinism | `stress - calculate period for leap year February` uses `Calendar.set(2024, Calendar.FEBRUARY, 15)` but doesn't clear time-of-day fields. | Use `assertEquals` with exact expected days, or add upper bound check. |
| **BudgetForecastingEngineStubTest.kt:41** | 🟢 Low | Brittle Verification | Asserts on internal method call. | Consider whether the read verification adds value for a no-op stub test. |

### Summary by Severity

| Severity | Count |
|----------|-------|
| 🔴 Critical (will fail non-deterministically) | 4 |
| 🟡 Medium (potential flakiness or logic issue) | 13 |
| 🟢 Low (maintenance/style concern) | 8 |

### Top 3 Systemic Issues

1. **`System.currentTimeMillis()` in tests**: `BudgetCalculatorTest`, `BudgetCalculatorStressTest`, and `BudgetMonitorStressTest` all use `System.currentTimeMillis()`. This is the #1 source of flakiness.

2. **`Calendar.getInstance()` without clearing all fields**: Multiple test helpers call `Calendar.set()` for specific fields but don't clear `MILLISECOND`.

3. **Mock reuse without clearing**: `BudgetMonitorTest` creates mocks as class-level `val` instead of recreating them in `@Before`.
