# Pace/Threshold/Totals Test Bugs (B25b1b)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| **SpendingPaceCalculatorValidationTest.kt:35** | 🔴 High | Off-by-one / filter boundary bug | Production code filters `it.date < currentWindowEnd` (strict `<`). Expenses created on the exact `now` timestamp are *excluded*. No test covers this edge case. | Add a test with an expense at exactly `now` to validate the `<` vs `<=` boundary. |
| **SpendingPaceCalculatorValidationTest.kt:100** | 🔴 High | Incorrect expected value — PaceStatus mismatch | Test expects `PaceStatus.ON_PACE` for a 100% pace. Production code: ON_PACE is 90–110 inclusive. 100% is within that range, so this assertion is correct. However, the `pacePercentage` assertion is fragile. | No fix needed; note as fragile for maintenance. |
| **SpendingPaceCalculatorValidationTest.kt:239** | 🟡 Medium | Hardcoded blended projection math may break silently | Test asserts `projectedTotal ≈ 1285.714` based on the blending formula. If the smoothing constant changes, this test will silently become wrong. | Consider extracting the smoothing constant as a constant and deriving the expected value from it in the test. |
| **SpendingPaceCalculatorValidationTest.kt:337** | 🟡 Medium | Incomplete zero-baseline test | Test asserts `pacePercentage = 0.0f` when previous month has no expenses. But it does NOT assert `paceStatus == PaceStatus.NO_BASELINE`. | Add `assertEquals(PaceStatus.NO_BASELINE, result.paceStatus)` |
| **SpendingPaceCalculatorValidationTest.kt:363-364** | 🟡 Medium | Missing assertion on paceStatus and previousMonthTotal | Test `pace calculation with no current spending` asserts `currentMonthSpent = 0` and `projectedTotal = 0` but never asserts `paceStatus`. | Add assertions for `result.paceStatus` and `result.previousMonthTotal`. |
| **SpendingPaceGoldenTest.kt:39** | 🟡 Medium | Brittle golden value for pacePercentage | Test expects `pacePercentage ≈ 175.0f` with tolerance 0.1. The 0.1f tolerance is tight — any refactor to rounding or daily-rate formula would break this. | Widen tolerance to `0.5f` or derive expected value programmatically. |
| **SpendingPaceGoldenTest.kt:62-93** | 🟢 Low | Golden dataset mixes DEPOSIT and shared expenses — good coverage | The golden dataset correctly includes DEPOSIT and shared expenses. These are properly excluded from purchase totals. No bug here. | N/A |
| **SpendingThresholdCalculatorTest.kt:17** | 🟡 Medium | `StandardTestDispatcher` not passed to `runTest` consistently | The use of `System.currentTimeMillis()` as the mock return value introduces **non-determinism**. | Replace `System.currentTimeMillis()` with a fixed timestamp for all mocks of `timeProvider.now()`. |
| **SpendingThresholdCalculatorTest.kt:82-88** | 🔴 High | Test logic error: asserts same-user cache but actually tests two users | The assertion `abs(threshold1 - threshold2) < 10.0` checks that thresholds for **different** users are **similar** — but since both hit the same mock returning the same data, this always passes and doesn't actually validate caching behavior. | Mock different data for user-2's call and assert thresholds *differ*. Or use `verify()` to confirm the DAO is called exactly the expected number of times. |
| **SpendingThresholdCalculatorTest.kt:109-110** | 🟡 Medium | `thenReturn` chaining doesn't guarantee cache invalidation test | `abs(threshold1 - threshold2) < 50.0` is an extremely loose assertion — both could be the same value and the test would pass even if the cache WASN'T cleared. | Assert `threshold1 != threshold2` or use more distinct data sets with tighter assertions. |
| **SpendingThresholdCalculatorTest.kt:24-31** | 🟡 Medium | Mixing Mockito and MockK in same project | This test file uses **Mockito** while all other test files use **MockK**. | Migrate to MockK for consistency. |
| **TotalsAggregationEngineTest.kt:27** | 🔴 High | Uses `Dispatchers.Unconfined` instead of test dispatcher | The engine is constructed with `Dispatchers.Unconfined` but tests use `runTest { }`. `Dispatchers.Unconfined` launches coroutines eagerly on the calling thread, bypassing `runTest`'s virtual time control. | Use `StandardTestDispatcher()` or `UnconfinedTestDispatcher()` from kotlinx-coroutines-test. |
| **TotalsAggregationEngineTest.kt:28** | 🔴 High | `System.currentTimeMillis()` in mock makes tests time-dependent (flaky) | `every { timeProvider.now() } returns System.currentTimeMillis()` — the value is captured once at test setup, but re-read by production code. | Use a fixed timestamp: `every { timeProvider.now() } returns 1_711_929_600_000L`. |
| **TotalsAggregationEngineTest.kt:105** | 🟡 Medium | Weekly label assertion may be wrong | Test asserts `periodLabel == "W1"` for the first `WeeklyTotal` with key `"2026-W3"`. The production code assigns labels as `W${index + 1}` based on array position. | Assert on `periodKey` instead of `periodLabel`, or assert that labels are sequential. |
| **TotalsAggregationEngineTest.kt:360-370** | 🟡 Medium | `getEndOfMonth` helper has a subtle bug | `getEndOfMonth` sets `DAY_OF_MONTH` to `getActualMaximum(...)` but calls `getActualMaximum` on the Calendar *after* already setting the month. | Use `cal.add(Calendar.MONTH, 1); cal.add(Calendar.MILLISECOND, -1)` pattern. |
| **TotalsAggregationEngineTest.kt:401-411** | 🟡 Medium | `getStartOfDay`/`getEndOfDay` helper uses 0-indexed month but callers pass 1-indexed | The helper sets `Calendar.MONTH` directly with parameter `month`. Calendar uses 0-indexed months (0=Jan). But at line 126-127, it's called as `getStartOfDay(2026, 1, 12)` meaning **February** 12, yet the test data uses `dayEpoch = 20260112L` which implies **January** 12. | Either use `getStartOfDay(2026, 0, 12)` for January or fix `dayEpoch` accordingly. |
| **TotalsAggregationEngineTest.kt:142** | 🟡 Medium | `getDailyTotals` called with `weekOfYear=3` but data is for month=1 day=12 | ISO week 3 of 2026 is Jan 12-18 — this coincidentally aligns with the January interpretation but NOT with the February dates the helper actually computed. | Use explicit date matchers or fix the month indexing to make the test data semantically correct. |
| **TotalsAggregationEngineDeepTest.kt:34** | 🔴 High | Same `Dispatchers.Unconfined` issue | Same as `TotalsAggregationEngineTest.kt:27` — uses `Dispatchers.Unconfined`, bypassing test dispatcher control. | Use a proper test dispatcher. |
| **TotalsAggregationEngineDeepTest.kt:53** | 🟡 Medium | `returnsMany` with 7 values for `getTotalForPeriod` — fragile coupling | The number and order of values are tightly coupled to the implementation's internal call sequence. | Use `coEvery { ... } answers { }` with argument matching to return specific values per year range. |
| **TotalsAggregationEngineDeepTest.kt:85-95** | 🟡 Medium | `returnsMany` for `getMonthlyTotalsForPeriod` relies on call order | Similar to above — `returnsMany` returns different values on 1st and 2nd call. | Match on specific date range arguments instead of relying on call order. |
| **TotalsAggregationEngineDeepTest.kt:103-108** | 🟡 Medium | `yearAvg` assertion couples to `getTotalForPeriod` mock | `getAverageForPeriodType(PeriodType.YEAR)` internally queries `getTotalForPeriod` for years 2022-2025. But line 101's mock `returns 200.0` unconditionally. | Use argument-specific mocks to return different totals per year. |
| **All Validation tests** | 🟢 Low | Missing coverage: DST transition edge case | None of the tests verify behavior across a DST transition. | Low risk since `daysBetween` uses `LocalDate`, but consider adding a DST-crossing test. |
| **SpendingPaceCalculatorValidationTest.kt** | 🟢 Low | Missing coverage: `effectiveAmount` for shared expenses | No validation test creates expenses with `isSharedExpense=true` and `mySharePercentage`. | Add a validation test with shared expenses to verify correct `effectiveAmount` usage. |

### Summary by Severity

| Severity | Count |
|---|---|
| 🔴 High (will cause failures or wrong results) | 5 |
| 🟡 Medium (fragile, misleading, or incomplete) | 13 |
| 🟢 Low (coverage gaps, style) | 3 |

### Top 3 Most Critical Findings

1. **`SpendingThresholdCalculatorTest.kt:82-88`** — The caching test has a **logic error**: it claims to test per-user caching but the assertion is always true because both users hit the same mock data.

2. **`TotalsAggregationEngineTest.kt:28` + `DeepTest.kt:35`** — Using `System.currentTimeMillis()` or `Dispatchers.Unconfined` makes these tests **time-dependent and flaky**.

3. **`TotalsAggregationEngineTest.kt:401-411`** — The `getStartOfDay(2026, 1, 12)` helper uses **0-indexed months**, meaning it computes **February 12**, but the `dayEpoch` and context clearly intend **January 12**.
