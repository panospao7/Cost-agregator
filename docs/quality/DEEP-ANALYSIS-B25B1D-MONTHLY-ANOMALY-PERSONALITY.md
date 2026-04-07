# Monthly/Anomaly/Personality Test Bugs (B25b1d)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| **AnomalyDetectorTest.kt:20-21** | 🔴 High | Flaky Test | `System.currentTimeMillis()` is used for both `now` and `monthPeriodFor(now)`. If the test runs within 5 seconds of midnight on the 1st of a month, expenses at `now - 5_000` fall in the **previous month** and are excluded from detection. | Replace `System.currentTimeMillis()` with a fixed deterministic timestamp, e.g. `ms(2026, 4, 15)`. |
| **SpendingPersonalityClassifierTest.kt:58,64-67** | 🔴 High | Incorrect Coroutine Dispatcher | `classify()` uses `withContext(Dispatchers.Default)` but the test never overrides `Dispatchers.Default` with a `TestDispatcher`. The coroutine escapes `runTest`'s virtual time control. | Add `@Before: Dispatchers.setMain(StandardTestDispatcher())` and `@After: Dispatchers.resetMain()`. |
| **AnomalyDetectorTest.kt:138-149** | 🟡 Medium | Timezone Sensitivity / Mixed APIs | `ms()` and `msAt()` use `java.time.LocalDate` + `ZoneId.systemDefault()`, while `monthPeriodFor()` uses `java.util.Calendar.getInstance()`. Both depend on system default timezone, but mixing two time APIs creates a maintenance risk. | Standardize on a single time API (prefer `java.time`) and pin to a fixed timezone. |
| **MonthlyComparisonCalculatorTest.kt:70** | 🟡 Medium | Fragile Assertion | `comparison.previousTotal!!` uses a force-unwrap (`!!`) on a value that is `Double?`. | Replace with `assertEquals(0.0, comparison.previousTotal ?: fail("previousTotal was null"), 0.01)`. Also applies to lines 37, 38, 39, 112, 113. |
| **MonthlyComparisonCalculatorTest.kt:37-39,112-113** | 🟡 Medium | Fragile Assertion | Same `!!` force-unwrap pattern on `previousTotal!!`, `changeAmount!!`, `changePercentage!!`. | Use `assertNotNull(value)` followed by the value assertion. |
| **SpendingPersonalityClassifierTest.kt:65** | 🟢 Low | Redundant Mock Setup | `coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()` is already configured identically in `@Before`. | Remove the redundant `coEvery` at line 65. |
| **SpendingPersonalityClassifierTest.kt:274-283** | 🟢 Low | Missing Assertion | `calculateFeatureScores` test asserts 9 of 10 feature scores but **omits `weekendSpendShare`**. | Add `assertApproxEquals(0.0, featureScores.getValue("weekendSpendShare"), 0.0001)`. |
| **MonthlyComparisonCalculatorTest.kt** | 🟢 Low | Missing Edge Case | No test for `previousMonth = null` — the calculator signature accepts `MonthPeriod?`. | Add a test with `previousMonth = null` and assert all previous-related fields are null. |
| **AnomalyDetectorTest.kt** | 🟢 Low | Missing Edge Case | No test for empty expense list or for exactly `MIN_SAMPLES_GLOBAL` (5) expenses at the boundary. | Add boundary tests: (1) empty list → empty anomalies, (2) exactly 5 identical amounts → empty anomalies. |
| **SpendingPersonalityClassifierTest.kt:320-332** | 🟢 Low | Test Fragility (Reflection) | Private methods are tested via reflection. | Consider making these methods `internal` or extracting them into a separate testable class. |
| **SpendingPersonalityClassifierTest.kt:94-205** | 🟢 Low | Test Isolation | All `determinePersonalityType` tests share the same `classifier` instance, but the method is stateless. | No immediate fix needed. Note for future: if mutable state is added, verify test independence. |

### Key Takeaways

1. **Most critical**: The `AnomalyDetectorTest` shared-expense test using `System.currentTimeMillis()` is a **real flaky test** that will fail at month boundaries.

2. **Second most critical**: The `SpendingPersonalityClassifierTest` `classify()` tests escape `runTest`'s control due to `Dispatchers.Default` not being overridden.

3. **Assertion hygiene**: The `!!` force-unwraps in `MonthlyComparisonCalculatorTest` will produce unhelpful `NullPointerException` stack traces instead of clear assertion failures.

4. **Coverage gaps**: The most impactful missing test is `previousMonth = null` for `MonthlyComparisonCalculator` — this is a documented nullable parameter with zero test coverage.
