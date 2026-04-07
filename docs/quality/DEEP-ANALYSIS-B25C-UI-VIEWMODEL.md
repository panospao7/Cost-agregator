# UI/ViewModel Test Bugs (B25c)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| `HomeViewModelRecommendationTest.kt:40-58` | **Critical** | Dispatcher Leak | `Dispatchers.setMain()` is called in `@Before` but there is **no `@After` with `Dispatchers.resetMain()`**. This leaks the test dispatcher into subsequent test classes in the same JVM. | Add `@After fun tearDown() { Dispatchers.resetMain() }` or extend `ViewModelTestUtils`. |
| `HomeViewModelRecommendationTest.kt:145-198` | **High** | Mock-Only Tests | Tests call mock methods directly and verify those same calls. **No ViewModel is ever created or tested** — these tests only prove MockK works. | Create a `HomeViewModel` instance and call its methods instead. |
| `HomeViewModelRecommendationTest.kt:353-359` | **High** | Mock-Only Test | `init loads recommendations for default user` calls `recommendationStateManager.refreshForUser()` directly. No ViewModel is instantiated. | Instantiate `HomeViewModel` and verify the mock is called as a side effect of ViewModel initialization. |
| `HomeViewModelRecommendationTest.kt:460-473` | **Medium** | Flaky Test | `recommendations flow handles rapid updates` expects exactly 10 sequential `awaitItem()` calls. `StateFlow` conflates emissions — intermediate values may be skipped. | Use `cancelAndIgnoreRemainingEvents()` after asserting the final value, or use `expectMostRecentItem()`. |
| `HomeViewModelRecommendationTest.kt:491-492` | **Medium** | Data Bug | `createRecommendation` has param `filterCriteria: String? = "{}"` but line 492 does `filterCriteria = filterCriteria ?: "{}"`, meaning passing `null` still produces `"{}"`. | Either allow `null` to propagate to the model, or fix the null test to reflect the actual behavior. |
| `HomeScreenWidgetTest.kt:114-126` | **High** | Fragile Test / Path-Dependent | `homeScreenSource()` reads production source files from disk with hardcoded relative paths. This breaks when tests run from a different working directory. | Move these to integration tests, or use resource loading. |
| `AiSettingsScreenTextTest.kt:1-133` | **Medium** | Wrong Test Directory | File uses `createComposeRule()` but is in `src/test/`. The `@Ignore` annotation masks this, but the test will always be skipped. | Move to `src/androidTest/` where `ComposeTestRule` can function. |
| `ReviewViewModelStressTest.kt:53-58` | **Low** | Code Duplication | Does not extend `ViewModelTestUtils`. Manually creates `StandardTestDispatcher()` and does `setMain/resetMain`. | Extend `ViewModelTestUtils` and use its `testDispatcher`. |
| `ReviewViewModelStressTest.kt:805-808` | **Medium** | Reflection Fragility | Uses reflection to set private state. Occurs **6 times** in this file. | Expose a `@VisibleForTesting` internal setter or use a test-friendly factory. |
| `BudgetViewModelStressTest.kt:33-66` | **Low** | Code Duplication | Same as ReviewViewModel — does not extend `ViewModelTestUtils`. | Extend `ViewModelTestUtils`. |
| `ReceiptScanViewModelStressTest.kt:172-176` | **Medium** | Reflection Fragility | Uses reflection to set private state. Occurs **10 times** in this file. | Expose a `@VisibleForTesting` state setter or inject test state. |
| `AssistantViewModelTest.kt:461-480` | **Medium** | Reflection Fragility | Three private reflection helpers. All use `getDeclaredField`. Breaks on any field rename. | Add `@VisibleForTesting` internal accessors to the ViewModel. |
| `AddExpenseViewModelStressTest.kt:59,66,73` | **Medium** | Dispatcher Mismatch | `runTest` is called **without** passing `testDispatcher`. `advanceUntilIdle()` advances the **wrong** scheduler. | Use `runTest(testDispatcher) { ... }` consistently. |
| `PriceProtectionViewModelTest.kt:22-42` | **Low** | Code Duplication | Does not extend `ViewModelTestUtils`. | Extend `ViewModelTestUtils` for consistency. |
| `PriceProtectionViewModelTest.kt:45-65` | **Medium** | Dispatcher Mismatch | `runTest` without `testDispatcher`. Same issue as AddExpenseViewModelStressTest. | Use `runTest(testDispatcher) { ... }`. |
| `AnalyticsStateStressTest.kt:1-183` | **Low** | Trivial Tests | All 20 tests only test Kotlin data class construction and `copy()`. | Consider removing or replacing with property-based tests. |
| `BudgetViewModelStressTest.kt:291-295` | **Low** | Weak Assertion | `assertTrue(initialState.isLoading \|\| initialState.budgets.isEmpty() \|\| initialState.error != null)` — triple-OR always passes. | Assert the specific expected initial state. |
| `ReviewViewModelStressTest.kt:130-145` | **Low** | Weak Assertions | Only asserts `assertNotNull(reviews)` — doesn't verify the list is actually empty. | Assert `assertTrue(reviews.isEmpty())`. |
| `DebugScreenTextTest.kt:1-90` | **Low** | Missing Dispatcher | Pure unit tests calling non-suspend functions — no issue here. | N/A — this file is fine. |
| `HomeViewModelRecommendationTest.kt:63-69` | **Low** | Redundant Test | Tests `MutableStateFlow`'s default behavior, not any app code. | Test this through the ViewModel to add value. |
| `ReviewViewModelStressTest.kt:469-523` | **Medium** | Race Condition Testing Gap | `concurrent calls do not corrupt state` comment says two calls race, but `StandardTestDispatcher` is single-threaded. | Use `Dispatchers.Default` or `newFixedThreadPoolContext` for real concurrency. |
| `AssistantViewModelTest.kt:421-439` | **Medium** | Same as Above | `concurrent clearSession calls keep state consistent` uses `backgroundScope.launch` but all coroutines run sequentially. | Same as above. |
| `PriceProtectionViewModelTest.kt:121-135` | **Medium** | Flaky Timing | `isLoading is true during loadData` relies on `advanceTimeBy(100)` matching the `delay(100)` in the coAnswers block. | Use `advanceUntilIdle()` for the "after" state. |

## Risk Summary

| Severity | Count | Key Themes |
|---|---|---|
| **Critical** | 1 | Dispatcher leak in `HomeViewModelRecommendationTest` |
| **High** | 3 | Mock-only tests (no ViewModel instantiated), fragile file I/O |
| **Medium** | 11 | Dispatcher mismatches, reflection fragility, flaky timing, StateFlow conflation, wrong test directory |
| **Low** | 8 | Code duplication, weak assertions, trivial tests |

## Recommended Priority Actions

1. **Fix the dispatcher leak** in `HomeViewModelRecommendationTest` — this is the only bug that can corrupt other test classes at runtime.
2. **Rewrite mock-only tests** to actually instantiate and test the ViewModel.
3. **Pass `testDispatcher` to `runTest()`** in `AddExpenseViewModelStressTest` and `PriceProtectionViewModelTest`.
4. **Address `HomeScreenWidgetTest`** path-dependency — it will fail in any CI environment.
