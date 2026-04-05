# Testing Agent Playbook

> **Purpose:** This document is the single source of truth for AI agents generating tests for the ExpenseTracker codebase.  
> **Usage:** Before writing any test, read this entire document. Follow the conventions, patterns, and infrastructure described here exactly.  
> **Companion files:** `CRASH-TEST-SCENARIOS.md` (golden values), `COMPONENT-TEST-MATRIX.md` (full file inventory)

---

## 1. Project Structure

```
app/src/main/java/com/yourname/expensetracker/   ← Production code (642 files)
app/src/test/java/com/yourname/expensetracker/    ← Unit tests (259 files)
app/src/androidTest/java/com/yourname/expensetracker/ ← Instrumented tests (10 files)
```

**Package rule:** Test files mirror production package structure. A test for `domain/budget/BudgetCalculator.kt` goes in `domain/budget/BudgetCalculatorTest.kt` under the test source set.

---

## 2. Frameworks & Dependencies

All of these are already in the project's `build.gradle`. Do NOT add new dependencies.

| Framework | Import | Usage |
|-----------|--------|-------|
| **JUnit 4** | `org.junit.Test`, `org.junit.Before`, `org.junit.After`, `org.junit.Assert.*` | Test runner and assertions |
| **MockK** | `io.mockk.*` | Mocking — always use `mockk(relaxed = true)` unless you need strict verification |
| **kotlinx-coroutines-test** | `kotlinx.coroutines.test.*` | `runTest`, `StandardTestDispatcher`, `advanceUntilIdle` |
| **Turbine** | `app.cash.turbine.test` | Flow/StateFlow assertions — use `awaitItem()`, `cancelAndIgnoreRemainingEvents()` |
| **Robolectric** | `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [28])` | Android framework mocks (Context, etc.) — only for UI tests |
| **AndroidX Test** | `androidx.test.core.app.ApplicationProvider` | Instrumented tests only |

---

## 3. Existing Test Infrastructure

### 3.1 Base Classes — When and How to Use

#### `AnalyticsEngineTestBase`

**Location:** `app/src/test/java/com/yourname/expensetracker/AnalyticsEngineTestBase.kt`  
**Use for:** Any test that needs `ExpenseDao`, `TimeProvider`, or `CategoryRepository` mocks.

**What it provides:**
- `expenseDao: ExpenseDao` — relaxed MockK mock
- `timeProvider: TimeProvider` — relaxed MockK, fixed to April 1, 2026
- `categoryRepository: CategoryRepository` — returns `testCategories`
- `testDispatcher: TestDispatcher` — deterministic coroutine dispatcher
- `mainDispatcherRule` — JUnit rule setting `Dispatchers.Main`
- `fixedNow`, `march2026Start`, `march2026End`, `february2026Start`, `february2026End` — epoch millis
- `mockExpenses(expenses)` — configures DAO to return these expenses for all queries
- `mockExpensesForPeriod(start, end, expenses)` — period-specific mock
- `runTest { }` — coroutine test scope

**Template:**

```kotlin
package com.yourname.expensetracker.domain.myfeature

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MyEngineTest : AnalyticsEngineTestBase() {

    private lateinit var engine: MyEngine

    @Before
    override fun setUp() {
        super.setUp()
        engine = MyEngine(
            expenseDao = expenseDao,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `descriptive test name with expected behavior`() = runTest {
        // Arrange
        mockExpenses(listOf(/* expenses */))

        // Act
        val result = engine.calculate()

        // Assert
        assertApproxEquals(expected, result.value, 0.01)
    }
}
```

#### `ViewModelTestUtils`

**Location:** `app/src/test/java/com/yourname/expensetracker/util/ViewModelTestUtils.kt`  
**Use for:** ViewModel tests.

**What it provides:**
- `testDispatcher: StandardTestDispatcher`
- `setup()` — sets `Dispatchers.Main = testDispatcher`
- `tearDown()` — resets `Dispatchers.Main`

**Template:**

```kotlin
package com.yourname.expensetracker.ui.screens.myfeature

import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MyViewModelTest : ViewModelTestUtils() {

    private lateinit var viewModel: MyViewModel
    private val repository = mockk<MyRepository>(relaxed = true)

    @Before
    override fun setup() {
        super.setup()
        viewModel = MyViewModel(repository = repository)
    }

    @Test
    fun `initial state is empty`() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.items.isEmpty())
    }
}
```

#### `FakeTimeProvider`

**Location:** `app/src/test/java/com/yourname/expensetracker/domain/util/FakeTimeProvider.kt`  
**Use for:** Any test that needs to control time deterministically.

```kotlin
// Create with specific date
val fakeTime = FakeTimeProvider.forDate(2026, 3, 15, 14, 0) // March 15, 2026 14:00

// Advance time
fakeTime.advanceTime(24 * 60 * 60 * 1000) // +1 day

// Set exact timestamp
fakeTime.setTime(1234567890000L)
```

### 3.2 Test Utility Functions

**Location:** `app/src/test/java/com/yourname/expensetracker/TestUtils.kt`

**Available functions:**

```kotlin
// Create test expense
createExpense(
    date = "2026-03-05",        // ISO date string
    amount = 45.30,             // raw amount
    effectiveAmount = 45.30,    // defaults to amount
    isNotMine = false,
    type = TransactionType.PURCHASE,
    category = "groceries",     // mapped to ID via resolveCategoryId
    merchant = "Lidl",
    id = 1L                     // optional explicit ID
)

// Assertions
assertApproxEquals(expected = 100.0, actual = 99.995, tolerance = 0.01)
assertApproxEquals(expected = 90.0f, actual = 89.95f, tolerance = 0.1f)
assertWithinPercent(expected = 100.0, actual = 101.0, percentTolerance = 1.0)
assertEffectiveAmount(expense, expected = 45.30)
assertTotalEffective(expenses, expected = 1283.59)

// Date helpers
dateToMillis("2026-03-15")
startOfMonth(2026, 3)       // March 1, 2026 00:00
endOfMonth(2026, 3)         // March 31, 2026 23:59:59.999

// Category helpers
TEST_CATEGORIES              // List<Category> with IDs 1-5
getTestCategory(2L)          // Groceries
getTestCategory("entertainment")
```

**Category ID mapping:**

| Category Name | Test ID |
|---------------|---------|
| Food & Dining / dining / restaurant | 1L |
| Groceries / grocery | 2L |
| Entertainment | 3L |
| Travel | 4L |
| Utilities / utility | 5L |

---

## 4. Coding Conventions (MUST Follow)

### 4.1 Test Method Naming

Use backtick-quoted descriptive names. Include the expected behavior.

```kotlin
// GOOD
@Test fun `equal split of 100 among 3 members distributes remainder to first member`()
@Test fun `budget zero still forecasts history and is critical risk`()
@Test fun `pace percentage below 90 returns UNDER_PACE status`()

// BAD
@Test fun testSplit()
@Test fun test1()
```

### 4.2 Test Structure (Arrange-Act-Assert)

Every test MUST follow this structure:

```kotlin
@Test
fun `descriptive name`() = runTest {
    // Arrange — set up mocks, create test data
    val budget = Budget(categoryId = 1L, amount = 1000.0, ...)
    coEvery { dao.getExpenses(any(), any()) } returns listOf(expense1, expense2)

    // Act — call the method under test
    val result = engine.generateForecast(budget)

    // Assert — verify expected values
    assertApproxEquals(220.0, result.predictedSpending, 0.01)
    assertEquals(ForecastRiskLevel.LOW, result.riskLevel)
}
```

### 4.3 MockK Patterns

```kotlin
// Create relaxed mock (returns defaults for unstubbed methods)
val dao = mockk<ExpenseDao>(relaxed = true)

// Stub suspend function
coEvery { dao.getExpensesBetween(any(), any()) } returns listOf(expense1)

// Stub regular function
every { timeProvider.now() } returns fixedNow

// Stub with argument matching
coEvery { dao.getExpensesBetween(eq(marchStart), eq(marchEnd)) } returns marchExpenses

// Stub to throw
coEvery { dao.insert(any()) } throws RuntimeException("DB error")

// Verify call was made
coVerify { dao.insert(any()) }
coVerify(exactly = 0) { dao.delete(any()) }
```

### 4.4 Coroutine Testing

```kotlin
// For suspend functions — use runTest
@Test
fun `my test`() = runTest {
    val result = engine.calculate()
    assertEquals(expected, result)
}

// For ViewModels — use runTest with dispatcher
@Test
fun `viewmodel test`() = runTest(testDispatcher) {
    viewModel.loadData()
    advanceUntilIdle() // Process all pending coroutines
    val state = viewModel.uiState.value
    assertFalse(state.isLoading)
}

// For Flow — use Turbine
@Test
fun `flow test`() = runTest(testDispatcher) {
    viewModel.uiState.test {
        val initial = awaitItem()
        assertTrue(initial.isLoading)

        advanceUntilIdle()
        val loaded = awaitItem()
        assertFalse(loaded.isLoading)
        assertEquals(5, loaded.items.size)

        cancelAndIgnoreRemainingEvents()
    }
}
```

### 4.5 Floating-Point Assertions

NEVER use `assertEquals` for `Double` or `Float` comparisons. Always use:

```kotlin
assertApproxEquals(expected = 1283.59, actual = result.total, tolerance = 0.01)
```

### 4.6 DO NOTs

- **Do NOT** use `System.currentTimeMillis()` — use `FakeTimeProvider` or `timeProvider.now()`
- **Do NOT** use `Thread.sleep()` — use `advanceUntilIdle()` or `advanceTimeBy()`
- **Do NOT** use `Dispatchers.IO` directly — inject dispatchers via constructor
- **Do NOT** add comments explaining the test — the backtick name IS the documentation
- **Do NOT** create new assertion helpers — use `assertApproxEquals` from `TestUtils.kt`
- **Do NOT** use `@RunWith(MockitoJUnitRunner::class)` — use MockK, not Mockito
- **Do NOT** use JUnit 5 annotations — the project uses JUnit 4

---

## 5. Golden Dataset

All tests that validate calculation correctness MUST use these predetermined values. See `CRASH-TEST-SCENARIOS.md` for the complete dataset.

### Quick Reference — March 2026 Golden Expenses

```kotlin
val goldenMarchExpenses = listOf(
    createExpense("2026-03-01", 800.00, merchant = "Rent Co", category = null, id = 1L),
    createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
    createExpense("2026-03-05", 62.50, merchant = "Shell Gas", category = null, id = 3L),
    createExpense("2026-03-07", 15.99, merchant = "Netflix", category = "entertainment", id = 4L),
    createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
    createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
    createExpense("2026-03-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 7L),
    createExpense("2026-03-15", 4.80, merchant = "Coffee Shop", category = "dining", id = 8L),
    createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
    createExpense("2026-03-20", 89.90, merchant = "Zara", category = null, id = 10L),
    createExpense("2026-03-22", 12.30, merchant = "Pharmacy", category = null, id = 11L),
    createExpense("2026-03-25", 35.00, effectiveAmount = 17.50, merchant = "Friend Lunch", category = "dining", id = 12L, isSharedExpense = true, mySharePercentage = 50),
    createExpense("2026-03-28", 120.00, merchant = "Utilities", category = "utilities", id = 13L),
    createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L)
)
```

### Golden Expected Values

```
PURCHASE effectiveAmount total = 1283.59
DEPOSIT effectiveAmount total = 3000.00
Savings rate = 57.21%
Grocery total (effectiveAmount) = 136.10
Dining total (effectiveAmount) = 46.80
Rent = 800.00
```

---

## 6. Test Generation Instructions by Component Type

### 6.1 Domain Engine Tests

**How to identify:** Files under `domain/` that are NOT interfaces, models, or use cases. Typically named `*Engine.kt`, `*Calculator.kt`, `*Detector.kt`.

**Steps:**
1. Read the production file completely
2. Identify all public methods and their formulas
3. Identify all constructor dependencies (these become mocks)
4. Extend `AnalyticsEngineTestBase` if the engine depends on `ExpenseDao` or `TimeProvider`
5. Write tests for:
   - **Happy path** with golden dataset values and exact expected outputs
   - **Edge cases**: empty input, zero values, single item, negative values
   - **Boundary conditions**: threshold values (e.g., exactly 90% for pace)
   - **Error paths**: what happens when a dependency throws
6. Use `assertApproxEquals` for all `Double`/`Float` assertions

**Example for a new engine test:**

```kotlin
package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CategoryInsightEngineTest : AnalyticsEngineTestBase() {

    private lateinit var engine: CategoryInsightEngine

    @Before
    override fun setUp() {
        super.setUp()
        engine = CategoryInsightEngine(
            expenseDao = expenseDao,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `grocery percentage of total matches golden value`() = runTest {
        mockExpenses(goldenMarchExpenses)
        val insights = engine.getCategoryInsights(march2026Start, march2026End)
        val grocery = insights.find { it.categoryId == 2L }
        assertNotNull(grocery)
        assertApproxEquals(136.10, grocery!!.totalAmount, 0.01)
        // 136.10 / 1283.59 * 100 = 10.60%
        assertApproxEquals(10.60f, grocery.percentageOfTotal, 0.1f)
    }

    @Test
    fun `empty expenses returns empty insights`() = runTest {
        mockExpenses(emptyList())
        val insights = engine.getCategoryInsights(march2026Start, march2026End)
        assertTrue(insights.isEmpty())
    }
}
```

### 6.2 ViewModel Tests

**How to identify:** Files under `ui/screens/*/` named `*ViewModel.kt`.

**Steps:**
1. Read the ViewModel and identify all its constructor dependencies
2. Extend `ViewModelTestUtils`
3. Create relaxed MockK mocks for all dependencies
4. Test initial state, loading state, error state, and loaded state
5. Use `runTest(testDispatcher)` and `advanceUntilIdle()` for async operations
6. Use Turbine (`uiState.test { }`) for StateFlow assertions

**Template:**

```kotlin
package com.yourname.expensetracker.ui.screens.myfeature

import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MyViewModelTest : ViewModelTestUtils() {

    private lateinit var viewModel: MyViewModel

    // Mock ALL constructor dependencies
    private val repo = mockk<MyRepository>(relaxed = true)
    private val engine = mockk<MyEngine>(relaxed = true)

    @Before
    override fun setup() {
        super.setup()
        // Stub default returns BEFORE creating ViewModel
        coEvery { repo.getItems() } returns emptyList()

        viewModel = MyViewModel(
            repository = repo,
            engine = engine,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `initial state is loading then loaded`() = runTest(testDispatcher) {
        coEvery { repo.getItems() } returns listOf(item1, item2)
        viewModel = MyViewModel(repo, engine, testDispatcher)

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.items.size)
    }

    @Test
    fun `error in repository sets error state`() = runTest(testDispatcher) {
        coEvery { repo.getItems() } throws RuntimeException("DB error")
        viewModel = MyViewModel(repo, engine, testDispatcher)

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertNotNull(state.error)
    }
}
```

### 6.3 Repository Tests

**How to identify:** Files under `data/repository/`.

**Steps:**
1. Read the repository and identify all DAO dependencies
2. Create the repository with mocked DAOs
3. Test CRUD operations, error handling, and transaction behavior
4. For repositories that use `database.withTransaction {}`, verify atomicity by checking that partial failures roll back

**Key pattern:** Repositories often inject `@IoDispatcher` — pass `testDispatcher` instead.

### 6.4 Split/Settlement Tests

**How to identify:** `SplitCalculator.kt`, `SettlementCalculator.kt`, `SharedExpenseManager.kt`.

**Critical: These are pure calculation engines. Tests must verify exact cent values.**

**Steps:**
1. No mocks needed for `SplitCalculator` and `SettlementCalculator` — they are pure functions
2. `SharedExpenseManager` needs `SharedExpenseDataPort` mock
3. Use `assertApproxEquals(expected, actual, 0.01)` for all amount comparisons
4. Always verify: `Σ(split amounts) == original total amount`

**Example:**

```kotlin
@Test
fun `equal split of 100 among 3 members sums to exactly 100`() {
    val members = listOf(member(1L), member(2L), member(3L))
    val expense = groupExpense(totalAmount = 100.00, splitType = SplitType.EQUAL)

    val splits = calculator.calculateSplitAmounts(expense, members)

    assertEquals(3, splits.size)
    assertApproxEquals(33.34, splits[1L]!!, 0.01)
    assertApproxEquals(33.33, splits[2L]!!, 0.01)
    assertApproxEquals(33.33, splits[3L]!!, 0.01)
    assertApproxEquals(100.00, splits.values.sum(), 0.001) // Sum preservation
}
```

### 6.5 E2E / Pipeline Tests

**How to identify:** Tests that cross multiple components.

**Steps:**
1. Create real instances of all engines in the pipeline (not mocks)
2. Mock only the data source (DAO/repository)
3. Feed golden dataset through the entire pipeline
4. Assert final output matches pre-computed expected values
5. Place in `e2e/` or `integration/` package

**Example pipeline: Expense → Analytics → Dashboard**

```kotlin
package com.yourname.expensetracker.e2e

class AnalyticsDashboardPipelineTest : AnalyticsEngineTestBase() {

    private lateinit var insightsEngine: InsightsEngine
    private lateinit var paceCalculator: SpendingPaceCalculator
    // ... real instances, not mocks

    @Before
    override fun setUp() {
        super.setUp()
        paceCalculator = SpendingPaceCalculator(timeProvider)
        insightsEngine = InsightsEngine(
            expenseDao = expenseDao,
            // ... real sub-engines
            spendingPaceCalculator = paceCalculator,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `golden march data produces correct monthly total through full pipeline`() = runTest {
        mockExpenses(goldenMarchExpenses)
        val snapshot = insightsEngine.generateInsights(testCategories, goldenMarchExpenses)
        assertApproxEquals(1283.59, snapshot.currentMonthSpent, 0.01)
    }
}
```

### 6.6 Instrumented (AndroidTest) Tests

**Location:** `app/src/androidTest/java/com/yourname/expensetracker/`  
**Use for:** DAO tests that need a real Room database.

**Template:**

```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class MyDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MyDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.myDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndRetrieve() = runBlocking {
        val entity = MyEntity(id = 1L, name = "test")
        dao.insert(entity)
        val result = dao.getById(1L)
        assertEquals("test", result?.name)
    }
}
```

---

## 7. Test Priorities — What to Generate First

When an agent is asked to "add tests", generate them in this priority order:

### Priority 0 — Regression Guards (generate first)

These are fast, high-value tests with exact expected values from `CRASH-TEST-SCENARIOS.md`:

| Target | File to Create | Key Assertions |
|--------|---------------|----------------|
| `SplitCalculator` | `domain/logic/SplitCalculatorGoldenTest.kt` | Equal split €100/3 = [33.34, 33.33, 33.33], sum=100.00 |
| `SpendingPaceCalculator` | `domain/analytics/SpendingPaceGoldenTest.kt` | Golden March day 15: spent=991.79, projected=2049.03 |
| `BudgetCalculator` | `domain/budget/BudgetCalculatorGoldenTest.kt` | MONTHLY March 15 → [Mar 1, Apr 1) |
| `FinancialHealthScoreV2` | `domain/health/HealthScoreGoldenTest.kt` | Golden March no budgets → overall=57 |
| `CurrencyConverter` | `domain/currency/CurrencyConverterGoldenTest.kt` | Same currency → rate=1.0, cross-rate via EUR |

### Priority 1 — Critical Gaps

| Target | File to Create | What to Test |
|--------|---------------|-------------|
| `SettlementCalculator` | `domain/groups/SettlementCalculatorTest.kt` | DFS solver, 3-member triangle, 15-member stress |
| `SharedExpenseManager` | `domain/groups/SharedExpenseManagerTest.kt` | Balance calc, split parity with SplitCalculator |
| `SharedExpenseGroupsViewModel` | `ui/screens/groups/SharedExpenseGroupsViewModelTest.kt` | Non-atomic creation, state management |

### Priority 2 — Pipeline E2E

| Pipeline | File to Create |
|----------|---------------|
| Expense→Analytics→Dashboard | `e2e/AnalyticsDashboardPipelineTest.kt` |
| Budget→HealthScore | `e2e/BudgetHealthScorePipelineTest.kt` |
| Split→Settlement→Balance | `e2e/GroupSettlementPipelineTest.kt` |
| Currency→MultiCurrencyAnalytics | `integration/MultiCurrencyPipelineTest.kt` |

### Priority 3 — Untested ViewModels

Generate tests for all ViewModels listed as ❌ in `COMPONENT-TEST-MATRIX.md`.

---

## 8. Component Dependency Map

When generating tests, you need to know what to mock. Here is the dependency map for key components:

### Domain Engines

| Engine | Constructor Dependencies | What to Mock |
|--------|------------------------|-------------|
| `BudgetCalculator` | `TimeProvider` | `timeProvider` |
| `SpendingPaceCalculator` | `TimeProvider` | `timeProvider` |
| `InsightsEngine` | `ExpenseDao`, `TimeProvider`, `CategoryRepository`, `SpendingPaceCalculator`, `AnomalyDetector`, `RecurringExpenseEngine` + 4 more | All sub-engines can be real or mocked; DAO and TimeProvider must be mocked |
| `TotalsAggregationEngine` | `ExpenseRepository`, `TimeProvider`, `@IoDispatcher` | `expenseRepository`, `timeProvider`, pass `testDispatcher` for dispatcher |
| `AdvancedAnalyticsEngine` | `ExpenseDao`, `CategoryRepository`, `BudgetRepository`, `TimeProvider`, `@IoDispatcher`, `@DefaultDispatcher` | All — use `testDispatcher` for both dispatchers |
| `FinancialHealthScoreV2` | `ExpenseDao`, `RecurringExpenseEngine`, `BudgetCalculator`, `BudgetRepository`, `SavingsGoalDao`, `HealthScoreHistoryDao`, `TimeProvider` | All |
| `SplitCalculator` | None | Nothing — pure functions |
| `SettlementCalculator` | None | Nothing — pure functions |
| `SharedExpenseManager` | `SharedExpenseDataPort` | `sharedExpenseDataPort` |
| `CurrencyConverter` | `ExchangeRateStore` (interface) | `exchangeRateStore` |
| `SynthesisEngine` | `ExpenseRepository`, `RecurringExpenseEngine`, `BudgetCalculator`, `SpendingPaceCalculator`, `TimeProvider` | All |
| `MonteCarloSpendingSimulator` | `HistoricalSpendingDistribution`, `DataQualityAssessor` | Both |
| `AutomatedSavingsRuleEngine` | `ExpenseDao`, `SavingsGoalDao`, `TimeProvider` | All |
| `SmartSavingsEngine` | `ExpenseRepository`, `BudgetRepository`, `BudgetCalculator`, `MonteCarloSpendingSimulator`, `SavingsGoalRepository`, `TimeProvider` | All |

### ViewModels

| ViewModel | Constructor Dependencies |
|-----------|------------------------|
| `HomeViewModel` | `ComputeDashboardWidgetsUseCase`, `InsightsEngine`, `ExpenseRepository`, `BudgetRepository`, `DashboardFollowThroughEngine`, `TimeProvider`, many more |
| `SharedExpenseGroupsViewModel` | `SharedExpenseManager`, `SettlementCalculator`, `ExpenseRepository` |
| `BudgetViewModel` | `BudgetRepository`, `BudgetCalculator`, `ExpenseRepository`, `TimeProvider` |
| `AnalyticsViewModel` | `InsightsEngine`, `AdvancedAnalyticsEngine`, `TotalsAggregationEngine`, `TimeProvider` |

**Rule:** If a ViewModel has more than 5 dependencies, mock ALL of them with `mockk(relaxed = true)` and only stub the ones relevant to each test.

---

## 9. Known Bugs to Target

These confirmed bugs should have dedicated tests that verify the broken behavior (and later verify the fix):

| Bug ID | Component | What to Test | Expected (current buggy behavior) |
|--------|-----------|-------------|----------------------------------|
| B-01 | `SplitCalculator.toCents()` | `toCents(25_000_000.00)` | Silent Int overflow → wrong value |
| B-02 | SplitCalc vs SharedExpenseMgr | Same input → both engines | May produce 1-ULP different balances |
| B-03 | `SettlementCalculator` DFS | 15 members, alternating ±1 | May timeout (no depth guard) |
| B-05 | `FinancialHealthScoreV2` | New user, no data | Bills=75 while others=50 (asymmetry) |
| B-06 | `AdvancedAnalyticsEngine` | Same 4 transactions | Sample stdDev ≠ population stdDev |
| B-07 | `InsightsEngine` | Expense with amount≠effectiveAmount | `getLegacyInsights` uses `amount`, analytics uses `effectiveAmount` |
| B-09 | Stress/Savings engines | Non-EUR user | Hardcoded €500 buffer, €75/200/500 caps |
| B-14 | `SpendingPaceCalculator` | Ratio near 90% boundary | `toFloat()` precision loss may flip classification |
| B-15 | `BudgetCalculator` | ROLLING MONTHLY | Uses fixed 30 days, not calendar month |

---

## 10. File Naming Rules

| Test Type | Naming Convention | Example |
|-----------|------------------|---------|
| Standard unit test | `{ClassName}Test.kt` | `BudgetCalculatorTest.kt` |
| Golden value validation | `{ClassName}GoldenTest.kt` | `SpendingPaceGoldenTest.kt` |
| Stress / edge case | `{ClassName}StressTest.kt` | `SettlementCalculatorStressTest.kt` |
| Deep mathematical | `{ClassName}DeepTest.kt` | `AdvancedAnalyticsEngineDeepTest.kt` |
| Boundary conditions | `{ClassName}BoundaryTest.kt` | `SpendingPaceBoundaryTest.kt` |
| E2E pipeline | `{PipelineName}PipelineTest.kt` | `AnalyticsDashboardPipelineTest.kt` |
| Cross-component consistency | `{Feature}ConsistencyTest.kt` | `SplitParityConsistencyTest.kt` |
| Integration | `{Feature}IntegrationTest.kt` | `MultiCurrencyIntegrationTest.kt` |

---

## 11. Checklist Before Submitting Generated Tests

Every generated test file MUST pass this checklist:

- [ ] Package matches production file location
- [ ] Extends correct base class (`AnalyticsEngineTestBase` or `ViewModelTestUtils`)
- [ ] All dependencies are mocked with `mockk(relaxed = true)`
- [ ] Uses `@Before` for setup, not `init {}`
- [ ] Uses `runTest { }` for suspend functions
- [ ] Uses `assertApproxEquals` for `Double`/`Float`, never `assertEquals`
- [ ] Uses `advanceUntilIdle()` after ViewModel actions
- [ ] Test names are backtick-quoted and descriptive
- [ ] At least one happy path, one edge case, one error path test
- [ ] Golden values match `CRASH-TEST-SCENARIOS.md` exactly
- [ ] No `System.currentTimeMillis()` — uses `FakeTimeProvider` or mocked `TimeProvider`
- [ ] No `Thread.sleep()` — uses coroutine test utilities
- [ ] No `Dispatchers.IO` — uses injected `testDispatcher`
- [ ] No new dependencies added
- [ ] File is valid Kotlin that compiles
