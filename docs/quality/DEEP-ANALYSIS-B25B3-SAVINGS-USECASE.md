# Savings & UseCase Test Bugs (B25b3)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| **SavingsGamificationEngineTest.kt:102** | 🔴 High | Assertion Bug | `calculateLevel(100.0)` asserts `assertEquals(1, ...)` but production code returns `(100.0 / 500.0).toInt() + 1 = 1`. Calculations check out, but test is really asserting placeholder constants, not real streak logic. | Add a comment marking this as a placeholder test, or test the actual streak logic once implemented. |
| **SavingsGamificationEngineTest.kt:52** | 🟡 Medium | Brittle / Placeholder-Dependent | `calculateStreak` assertion checks `currentStreakDays == 5` and `personalBestDays == 30`, but these are hardcoded placeholder values in production code. | Avoid relying on magic numbers that mirror production hardcoded values. |
| **AutomatedSavingsRuleEngineGoldenTest.kt:57** | 🟡 Medium | Weak Assertion / Type Misuse | `assertApproxEquals(1.0, executions.size.toDouble(), 0.0)` uses a floating-point approx assertion to check an integer count. | Use `assertEquals(1, executions.size)` for exact integer assertions. |
| **AutomatedSavingsRuleEngineTest.kt:68-74** | 🟢 Low | Missing Edge Case | No test covers the boundary where `amount % roundUpTo == 0.0`. | Add a test: `purchase(amount = 15.0)` with `roundUpTo = 5.0` → expected savings = `0.0`. |
| **SmartSavingsEngineTest.kt:40-43** | 🟡 Medium | Timezone-Dependent / Flaky | `now` is calculated via `ZoneId.systemDefault()`. If CI runs in a different timezone, `now` changes, and the day-of-month used in pace calculations might shift by ±1 day. | Use a fixed timezone like `ZoneId.of("UTC")` or `ZoneId.of("Europe/Athens")`. |
| **SmartSavingsEngineTest.kt:156** | 🟢 Low | Missing Tolerance | `assertApproxEquals(0.0, result.safeAmount)` omits the tolerance parameter, defaulting to `0.01`. | Add explicit tolerance for consistency. |
| **CalculateBudgetStatusUseCaseTest.kt:26-29** | 🟢 Low | Missing setUp override pattern | `initUseCase()` is annotated `@Before` but the class extends `AnalyticsEngineTestBase` whose `setUp()` is also `@Before`. | Rename to `override fun setUp()` calling `super.setUp()` for clarity. |
| **DetectDuplicateExpenseUseCaseTest.kt:95-98** | 🟡 Medium | Fragile Boundary Assertion | Test verifies `expectedEndExclusive = date + expectedWindow + 1`. The `+1` makes this an off-by-one assumption about the production code's window boundary behavior. | Add a comment documenting the contract: "production uses exclusive end boundary, hence +1". |
| **CalculateFinancialForecastUseCaseTest.kt:203-213** | 🔴 High | Timezone-Dependent / Flaky | `ms()` uses `Calendar.getInstance()` which uses `TimeZone.getDefault()`. If the CI timezone differs from the dev machine timezone, `monthStart` shifts and the production code's month-start calculation may not align. | Use a fixed timezone: `Calendar.getInstance(TimeZone.getTimeZone("UTC"))`. |
| **AutoCreateWarrantyFromReceiptUseCaseTest.kt:180-188** | 🔴 High | Non-Deterministic / Flaky | `recentDate()` calls `System.currentTimeMillis()` instead of using `FakeTimeProvider(FIXED_NOW)`. This means the OCR text date changes every day. | Replace `System.currentTimeMillis()` with `FIXED_NOW` in `recentDate()`. |
| **MonthlySavingsSweepUseCaseTest.kt:42** | 🟡 Medium | Timezone-Dependent | `withinWindowNow = millis(2026, Calendar.JANUARY, 29)` uses `Calendar.getInstance()` with system default timezone. January 29 is "last 5 days" logic sensitive. | Use fixed timezone in `millis()` or use `java.time` API with explicit zone. |
| **MonthlySavingsSweepUseCaseTest.kt:46-50** | 🟢 Low | Non-Relaxed Mocks Without Full Stubbing | Repositories are created with `mockk()` (strict). If the production code calls an unstubbed method, you get a `MockKException`. | Consider using `mockk(relaxed = true)` for repositories. |
| **LifestyleSavingsPromptUseCaseTest.kt:77-95** | 🟡 Medium | Ambiguous Savings Rate Unit | Test name says "converts savings rate ratio to percentage" and uses `lastSavingsRate = 0.10`, asserting `currentSavingsRate == 10.0`. But in `keepPercentageSavingsRate` test, `lastSavingsRate = 15.0` with `currentSavingsRate == 15.0`. | Add a boundary test: `lastSavingsRate = 1.0` and assert whether `currentSavingsRate` is `100.0` (ratio) or `1.0` (percentage). |
| **ComputeMoneyRadarUseCaseTest.kt:112-113** | 🟡 Medium | Magic Number / Comment-Code Drift | Comment says "Weighted = 80*0.4 + 60*0.3 + 80*0.3 = 74". The actual bills score "80" comes from "3 bills with large-bill bonus" but the mapping from 3 bills → 80 is opaque. | Extract the weight constants into named values and compute the expected score programmatically. |
| **ComputeMoneyRadarUseCaseTest.kt:131** | 🟢 Low | Missing Mock for getExpensesSince | When `budgetRepository.getBudgetStatuses()` returns `flowOf(emptyList())`, the test doesn't stub `expenseRepository.getExpensesSince(any())`. | Either make it explicit with `coVerify(exactly = 0)` on those methods, or stub them to fail loudly. |
| **GetMonteCarloBudgetImpactUseCaseTest.kt** | 🟢 Low | Missing Edge Case | No test for negative `budgetAmount` (e.g., `-100.0`). | Add test: `budgetAmount = -100.0` should return `Result.Error`. |
| **HealthScoreEdgeCaseTest.kt:282** | 🟢 Low | Timezone-Dependent | `millis()` uses `ZoneId.systemDefault()`. While `FinancialHealthScoreV2Test.kt` uses `Calendar.getInstance()` for the same purpose, these two approaches may produce subtly different values. | Standardize on one approach with a fixed timezone across all test files. |
| **FinancialHealthScoreV2Test.kt:39** | 🟡 Medium | Timezone-Dependent | `val now = millis(2026, Calendar.APRIL, 15)` uses `Calendar.APRIL` (value=3) in `Calendar.getInstance()`. But `HealthScoreEdgeCaseTest.kt:282` uses `LocalDate.of(year, month, day)` where month is 1-indexed. | Standardize: use either `java.time` or `Calendar` consistently. Prefer `java.time` with fixed `ZoneId`. |
| **SettlementCalculatorStressTest.kt:31** | 🟡 Medium | Flaky / Environment-Dependent | `assertTrue(elapsedNs < 500_000_000L)` (500ms time bound). On slow CI machines, under GC pressure, or on first JVM warm-up, this can easily fail. | Either remove timing assertions entirely, increase the bounds significantly, or use `@Tag("performance")`. |
| **SharedExpenseManagerTest.kt:291** | 🟡 Medium | Test Verifies Override, Not User Intent | Test passes `currency = "EUR"` but asserts the captured expense has `currency = "USD"` (from the group default). | Add a comment explaining the product requirement. |
| **SharedExpenseManagerTest.kt:96-106** | 🟢 Low | Test Isolation | The balance math depends on the exact split computation for all 4 expenses. If any one split formula changes, multiple assertions break simultaneously. | Consider testing each split type in isolation first. |
| **GroupUseCasesTest.kt:19-23** | 🟢 Low | Shared Mock State | `groupsRepository` is a class-level `val mockk()` (non-relaxed). All test methods share the same mock instance. | Use `@Before` to initialize `groupsRepository = mockk()` fresh before each test. |

## Summary by Severity

| Severity | Count |
|----------|-------|
| 🔴 High | 3 |
| 🟡 Medium | 10 |
| 🟢 Low | 9 |

## Top 3 Most Critical Issues

1. **`AutoCreateWarrantyFromReceiptUseCaseTest.kt:180-188`** — `recentDate()` uses `System.currentTimeMillis()` instead of the fake time provider, making the OCR test date non-deterministic.

2. **`CalculateFinancialForecastUseCaseTest.kt:203-213`** — `Calendar.getInstance()` uses system default timezone. Month boundaries shift across timezones.

3. **`SmartSavingsEngineTest.kt:40-43`** — Same timezone issue. The `now` value computed via `ZoneId.systemDefault()` makes day-of-month calculations vary across environments.
