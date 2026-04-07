# Test Infrastructure Bug Analysis (B25a)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| **AnalyticsEngineTestBase.kt:61-66** | 🔴 High | Coroutine Dispatcher Mismatch | `testDispatcher` and `testScope` are `val` (created once at field init), but mocks are recreated in `@Before setUp()`. The `testScope` survives across tests, violating test isolation. If a test leaves uncompleted coroutines in the scope, subsequent tests inherit a polluted scheduler. | Change `testScope` to `lateinit var` and reinitialize it in `setUp()`, or use `testDispatcher`'s `scheduler` to check for idle state. |
| **AnalyticsEngineTestBase.kt:311-315** | 🔴 High | Shadowed `runTest` Function | The base class defines its own `protected fun runTest(block)` which shadows `kotlinx.coroutines.test.runTest`. This custom wrapper creates a **new** `TestScope` instead of using `this.testScope`. Any test using `testScope` directly alongside `runTest{}` operates on a **different scope**. | Remove the custom `runTest` wrapper entirely and have subclasses call `kotlinx.coroutines.test.runTest(testDispatcher) { ... }` directly. |
| **AnalyticsEngineTestBase.kt:101-104** | 🟡 Medium | Timezone-Dependent Test Data (Flaky) | `fixedNow`, `march2026Start`, `march2026End`, etc. all use `ZoneId.systemDefault()`. Tests will produce **different epoch millis** on machines in different timezones. | Use `ZoneId.of("UTC")` everywhere—including `TestUtils.kt` helper functions. |
| **TestUtils.kt:55-58** | 🟡 Medium | Timezone-Dependent Test Data (Flaky) | `createExpense()` converts date strings via `ZoneId.systemDefault()`. If tests run in a different timezone, epoch millis shift, causing range-based mock `eq()` matchers to miss. | Use a fixed `ZoneOffset.UTC` or `ZoneId.of("UTC")`. |
| **TestUtils.kt:61** | 🟡 Medium | Non-Deterministic Test IDs | `id = id ?: System.currentTimeMillis()` generates IDs from wall-clock time. Two rapid calls produce **duplicate IDs**. | Use an `AtomicLong` counter (e.g., `private val idCounter = AtomicLong(1000)`) for deterministic, unique auto-IDs. |
| **TestUtils.kt:156-168** | 🟡 Medium | Division-by-Zero in Assertion | `assertWithinPercent()` computes `tolerance = expected * (percentTolerance / 100.0)` and `diff / expected * 100`. When `expected == 0.0`, tolerance is `0.0` and the error message divides by zero (`NaN%`). | Guard against `expected == 0.0`: if expected is zero, fall back to absolute tolerance or assert `actual == 0.0` directly. |
| **TestUtils.kt:117-124** | 🟢 Low | Assertion Message Formatting | Missing space/newline between user message and auto-generated detail. | Add `append(": ")` or `append("\n")` after the user-provided message. |
| **AnalyticsEngineTestBase.kt:156** | 🟢 Low | Relaxed Mocks Hide Bugs | All three mocks are `relaxed = true`. Relaxed mocks return default values for **un-stubbed** calls without failing. | Use non-relaxed mocks and explicitly stub every method that's expected to be called. |
| **AnalyticsEngineTestBase.kt:215-241** | 🟢 Low | Mock Data Leaks Non-Purchase Expenses | `mockExpenses()` stores `purchasesOnly` for most queries but passes the full unfiltered `expenses` list to `setupPeriodSpecificMocks()`. | Either make the generic `any(),any()` mock return an empty list, or filter by date inside an `answers {}` block. |
| **ViewModelTestUtils.kt:36-37** | 🟢 Low | Scheduler Sharing Transparency | `testScheduler` and `testDispatcher` are created at field init time and never reset between tests. | Remove the doc example showing re-declaration, or make `testDispatcher` `open` with a note not to shadow it. |
| **AnalyticsEngineTestBase.kt:289-292** | 🟢 Low | Incomplete Mock Override | `mockCategories()` overrides `getAll()` and `allCategories` flow but does **not** re-wire `getCategoryByName()` to search the new list. | Add a `coEvery { getCategoryByName(any()) }` stub using the new `categories` parameter inside `mockCategories()`. |

### Summary

| Severity | Count |
|---|---|
| 🔴 High | 2 |
| 🟡 Medium | 3 |
| 🟢 Low | 5 |

### Root Causes

1. **Coroutine test scope lifecycle**: The `testScope` is a `val` that outlives individual tests, and the custom `runTest` wrapper creates a second disconnected scope.
2. **Timezone non-determinism**: Pervasive use of `ZoneId.systemDefault()` across all three files means epoch-millis values shift per machine.
3. **Non-deterministic IDs**: `System.currentTimeMillis()` for auto-generated expense IDs creates collision risk.

### Highest-Impact Fix

Replace all `ZoneId.systemDefault()` with a fixed zone (e.g., `ZoneOffset.UTC`) across all three files and the 42 other test files that use the same pattern.
