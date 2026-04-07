# Debugger Analysis - Batch 18: ViewModel Tests

## Files Analyzed
| # | File | Lines |
|---|------|-------|
| 1 | `app/src/test/java/.../home/HomeViewModelStressTest.kt` | 512 |
| 2 | `app/src/test/java/.../home/HomeViewModelRecommendationTest.kt` | 499 |
| 3 | `app/src/test/java/.../home/HomeScreenWidgetTest.kt` | 127 |
| 4 | `app/src/test/java/.../transactions/TransactionsViewModelStressTest.kt` | 259 |
| 5 | `app/src/test/java/.../analytics/AnalyticsViewModelStressTest.kt` | 180 |
| 6 | `app/src/test/java/.../analytics/AnalyticsStateStressTest.kt` | 183 |

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| HomeViewModelRecommendationTest.kt:50-51 | HIGH | Dispatcher Leak / Missing Teardown | `Dispatchers.setMain(testDispatcher)` is called in `@Before` but there is **no `@After` method calling `Dispatchers.resetMain()`**. This leaks the test dispatcher across test classes, causing flaky failures or hangs when tests run in a suite. All other test classes inherit `ViewModelTestUtils` which handles teardown, but this class does NOT extend it. | Add `@After fun tearDown() { Dispatchers.resetMain() }` or extend `ViewModelTestUtils`. |
| HomeViewModelRecommendationTest.kt:63-498 | HIGH | Not Testing ViewModel | Almost every test in this file tests `MutableStateFlow` directly or calls mock methods directly (e.g., `recommendationDismissalHandler.dismiss(recommendation)`) without ever creating a `HomeViewModel` instance. This means **zero** actual ViewModel behavior is tested -- the tests are effectively verifying MockK stub behavior, not production code. For example, `dismissRecommendation calls dismissal handler` (line 145) directly calls `recommendationDismissalHandler.dismiss()` instead of calling `viewModel.dismissRecommendation()`. | Instantiate `HomeViewModel` (or a scoped test wrapper) and invoke ViewModel methods. Mock only dependencies, not the subject under test. |
| HomeViewModelRecommendationTest.kt:491-492 | MEDIUM | Null Override Silently Ignored | `createRecommendation()` accepts `filterCriteria: String? = "{}"` but the model field `DashboardFollowThroughRecommendation.filterCriteria` is a non-null `String`. On line 492, `filterCriteria = filterCriteria ?: "{}"` coalesces null back to `"{}"`, meaning calling `createRecommendation(filterCriteria = null)` as done on line 313 never actually creates a recommendation with null `filterCriteria`. The test at line 310 (`handles null filter criteria`) is therefore testing the same path as a normal `"{}"` criteria. | Change the production field to `String?` if null is a valid state, or remove the null test case since the model enforces non-null. |
| HomeViewModelRecommendationTest.kt:460-473 | MEDIUM | Flaky Test - MutableStateFlow Conflation | The test `recommendations flow handles rapid updates` sets the flow value 10 times in a tight loop, then expects to `awaitItem()` exactly 11 times (1 initial + 10 updates). `MutableStateFlow` conflates values -- if two emissions happen before the collector resumes, intermediate values are lost. With `StandardTestDispatcher` the scheduler may or may not interleave, making this test flaky. | Use Turbine's `expectMostRecentItem()` or `cancelAndConsumeRemainingEvents()`, or insert `yield()` between emissions to ensure each is collected. Alternatively, accept that StateFlow conflation means fewer than 10 updates may arrive. |
| HomeViewModelStressTest.kt:362-366 | MEDIUM | Non-Deterministic Time | `addPlannedExpense` tests use `System.currentTimeMillis()` for the `date` parameter (lines 363, 376, 389, etc.) instead of the mocked `timeProvider.now()` (which returns `0L`). This makes test data time-dependent and not reproducible. While it doesn't cause failures here (the value is just forwarded), it's inconsistent with the controlled test environment. | Replace `System.currentTimeMillis()` with `timeProvider.now()` or a fixed constant like `1000L`. |
| HomeViewModelStressTest.kt:369 | MEDIUM | Tautological Match Assertion | `coVerify(exactly = 1) { plannedExpenseRepository.addPlannedExpense(match { true }) }` -- the `match { true }` matcher always returns true, so this only verifies the method was called once. It does NOT verify any of the argument values (description, amount, priority). Same issue on lines 382, 395, 408, 422. | Use `match { it.description == "Test expense" && it.amount == 50.0 }` or capture the argument and assert specific fields. |
| HomeViewModelStressTest.kt:349-353 | LOW | Weak Assertion for Toggle on Non-Existent Widget | `toggleWidgetVisibility("nonexistent")` verifies `saveDashboardConfigSync` was called exactly once (line 353). Looking at the production code (HomeViewModel.kt line 362-366), `toggleWidgetVisibility` maps ALL configs and saves regardless of whether the widgetId matched anything -- no early return. This means the test passes but the production code has **no guard** against writing unchanged config. The test should document this as expected behavior or the production code should short-circuit. | Add a test assertion that the saved config is identical to the original (no actual toggle happened), or add a guard in production code: `if (currentConfig == newConfig) return`. |
| HomeViewModelStressTest.kt:276-277 | LOW | Unbounded While Loop in Test | The `while (latest.aiBriefing !is AiLoadState.Ready)` loop on line 277 has no bound. If the flow never emits a `Ready` state (e.g., due to a mock misconfiguration), the test hangs forever. The `@Ignore` annotation mitigates this for CI but doesn't fix the underlying risk. | Add a timeout: `withTimeout(5000) { while(...) { latest = awaitItem() } }` or use Turbine's `awaitItem()` with a limited number of retries. |
| TransactionsViewModelStressTest.kt:149-170 | MEDIUM | Fragile Flow Emission Ordering | The `loadMore on ALL tab triggers repository` test assumes exact emission ordering of `isLoading` and `isLoadingMoreState` (e.g., false -> true -> false in that precise sequence). If the ViewModel implementation changes to coalesce states or add intermediate states, these assertions break. The test also interleaves two Turbine `test {}` blocks on different flows sequentially, which means the second block relies on side effects from the first block already being complete. | Consider testing `isLoading` and `isLoadingMoreState` in a single combined flow, or use `assertFalse(viewModel.isLoadingMoreState.value)` snapshots after `advanceUntilIdle()`. |
| TransactionsViewModelStressTest.kt:53-54 | LOW | Non-Deterministic Time in Setup | `every { timeProvider.now() } returns now` where `now = System.currentTimeMillis()`. This makes the time range calculations in the ViewModel (`getTimeRangeForTab`) produce different ranges across test runs. Not a bug per se since mock repositories return empty lists, but reduces reproducibility. | Use a fixed epoch like `1_700_000_000_000L` for deterministic behavior. |
| AnalyticsViewModelStressTest.kt:81-82 | LOW | Mock Throws Exception by Design | `coEvery { advancedAnalyticsEngine.getSpendingPatterns(any()) } throws RuntimeException("test")` and `getStatisticalInsights` similarly throw. The production code (AnalyticsViewModel.kt:418-421) catches these with `try { } catch (_: Exception) { null }`. This is testing a valid error path, but the test never **asserts** that the resulting state has null `spendingPatterns` or `statisticalInsights`. The exception is silently swallowed with no verification. | Add: `assertNull(viewModel.state.value.spendingPatterns)` and `assertNull(viewModel.state.value.statisticalInsights)` after `advanceUntilIdle()`. |
| AnalyticsViewModelStressTest.kt:141-146 | LOW | Weak Assertion | `selectPeriod WEEK updates state` only asserts `assertNotNull(viewModel.state.value)` -- it never verifies that `state.value.selectedPeriod == TimePeriod.WEEK`. Same issue for `selectPeriod TODAY` (line 149) and `selectPeriod ALL` (line 157). | Add `assertEquals(TimePeriod.WEEK, viewModel.state.value.selectedPeriod)` and equivalents for other period tests. |
| AnalyticsStateStressTest.kt:7 | LOW | Unnecessary @Ignore on Pure Unit Tests | `AnalyticsStateStressTest` is marked `@Ignore("Stress test: may hang in CI, run manually")` but every test is a pure data-class construction test with no coroutines, no I/O, and no possibility of hanging. These tests are unconditionally skipped in CI for no reason. | Remove the `@Ignore` annotation. These are fast, deterministic tests. |
| AnalyticsStateStressTest.kt:33-51 | LOW | Hardcoded State Tests Don't Verify Computation | Tests for `changePercent` (lines 33-62) manually construct state with pre-computed values (e.g., `changePercent = 50f`) and then assert those same values. This only tests Kotlin data class behavior, not any computation logic. The actual change-percent calculation lives in `AnalyticsViewModel.computeAnalyticsInternal()` and is never tested here. | Either test the computation function directly or document that these are purely structural tests. |
| HomeScreenWidgetTest.kt:114-126 | MEDIUM | Fragile File-System Path Dependency | `homeScreenSource()` uses hardcoded relative paths and `File.exists()` to locate `HomeScreen.kt`. This breaks if the test runner's working directory differs from the project root (e.g., Gradle module-level working directory, IDE configurations, or CI environments). The test will throw `IllegalArgumentException` with an unhelpful message. | Use `ClassLoader.getResource()`, a build-generated resource, or the Gradle `testResources` mechanism. Alternatively, resolve the path relative to `System.getProperty("user.dir")`. |
| HomeScreenWidgetTest.kt:17-22 | LOW | Regex.escape Used on Literal String | `Regex(Regex.escape(needle)).findAll(source).count()` -- `Regex.escape` is correct for literal matching, but using `source.windowed()` or `source.split(needle).size - 1` would be simpler and faster for a plain string count. Not a bug, just unnecessary complexity. | Use `source.split(needle).size - 1` or `Regex(Regex.escape(needle)).findAll(source).count()` (current code is correct, just over-engineered). |
| HomeViewModelStressTest.kt:51-52 | INFO | All Tests @Ignored | The entire `HomeViewModelStressTest` class is annotated with `@Ignore`. While documented as "stress test", these are normal ViewModel unit tests that should run in CI. The `@Ignore` annotation means regressions in HomeViewModel's `moveWidget`, `toggleWidgetVisibility`, `addPlannedExpense`, `getWidgetId`, and error handling paths are never caught automatically. | Remove `@Ignore` or move stress-specific tests to a separate class and keep functional tests unannotated. |
| TransactionsViewModelStressTest.kt:29 | INFO | All Tests @Ignored | Same issue as above -- `TransactionsViewModelStressTest` is `@Ignore`'d, meaning filter, search, pagination, and tab-switching regressions are never caught in CI. | Remove `@Ignore` for the functional tests. |
| AnalyticsViewModelStressTest.kt:18 | INFO | All Tests @Ignored | Same pattern -- all analytics ViewModel tests are skipped in CI. | Remove `@Ignore` or split into stress vs. functional. |
| HomeViewModelRecommendationTest.kt (entire) | INFO | Missing Coverage | No tests exist for: `HomeViewModel.navigateToRecommendation()` (verifying `_navigationActions` emits), `HomeViewModel.dismissRecommendation()` (verifying `_selectedRecommendation` is cleared when the dismissed rec matches), `HomeViewModel.reloadDashboard()`, `HomeViewModel.loadTotalsForYear()`, `HomeViewModel.drillDownToPeriod()`, `HomeViewModel.drillUp()`, `HomeViewModel.loadCategoryBreakdownForPeriod()`, `HomeViewModel.toggleWidgetStyle()`. | Add integration tests that instantiate HomeViewModel and test these flows end-to-end. |
| TransactionsViewModelStressTest.kt (entire) | INFO | Missing Coverage | No tests for: `deleteExpense()`, `updateCategory()`, `updateMerchant()`, `updateExpenseType()`, `updateLocation()`, `clearLocation()`, `markAsRecurring()`, `updateSharedExpenseDetails()`, `updateNotMineDetails()`, error state flows, success message flows. | Add tests for mutation operations and error handling. |

## Summary

| Severity | Count |
|----------|-------|
| HIGH | 2 |
| MEDIUM | 5 |
| LOW | 8 |
| INFO | 5 |
| **Total** | **20** |

## Detailed Root Causes

### RC-1: HomeViewModelRecommendationTest Tests Mocks Instead of ViewModel (HIGH)
**Root cause:** The test class was likely written as a rapid prototype for the recommendation feature. Instead of constructing the full `HomeViewModel` (which requires 18 dependencies), the author opted to test mock interactions directly. This results in tests that verify MockK's behavior rather than production logic.

**Fix:** Create a minimal `HomeViewModel` instance using the same pattern as `HomeViewModelStressTest` (which already has all the mock setup). Delegate actual ViewModel method calls and assert on the resulting state changes.

**Residual risk:** If the ViewModel's internal wiring for recommendations changes (e.g., adding debounce, error handling), these tests will still pass since they never exercise that code.

### RC-2: Missing Dispatcher Teardown (HIGH)
**Root cause:** `HomeViewModelRecommendationTest` manually calls `Dispatchers.setMain()` without a corresponding `resetMain()`, unlike the other test classes that extend `ViewModelTestUtils`. When tests run in parallel or in a specific order within the same JVM, the leaked dispatcher can cause coroutine scheduling issues in subsequent test classes.

**Fix:** Add `@After fun tearDown() { Dispatchers.resetMain() }` or inherit from `ViewModelTestUtils`.

### RC-3: Global @Ignore on Functional Tests (MEDIUM aggregate)
**Root cause:** All three ViewModel stress test classes are annotated with `@Ignore`, meaning approximately 40+ functional test cases never run in CI. The "may hang in CI" justification appears to be a conservative measure from early development, but the tests use proper `StandardTestDispatcher` and `advanceUntilIdle()` patterns that should not hang.

**Fix:** Remove `@Ignore` from the class level. If specific tests are genuinely flaky, annotate those individually.

## Verdict

**FAIL_WITH_NOTES**

Two HIGH-severity issues were found:
1. `HomeViewModelRecommendationTest` has a dispatcher leak (missing `resetMain()`) that can corrupt the test environment for other test classes.
2. The same file tests mock stubs rather than the actual `HomeViewModel`, providing zero production code coverage for the recommendation feature's ViewModel integration.

Additionally, the blanket `@Ignore` annotations on all three ViewModel stress test classes mean ~40 functional tests are permanently skipped in CI, creating a significant regression risk. The tests themselves are structurally sound and use correct coroutine test patterns -- they should be enabled.

The `AnalyticsStateStressTest` tests are pure data-class validations that can never hang and should not be `@Ignore`'d.
