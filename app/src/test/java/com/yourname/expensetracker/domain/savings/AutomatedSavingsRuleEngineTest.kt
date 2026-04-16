package com.yourname.expensetracker.domain.savings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutomatedSavingsRuleEngineTest {

    private lateinit var engine: AutomatedSavingsRuleEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var stateFile: File
    private var stateScope: CoroutineScope? = null

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        savingsGoalRepository = mockk(relaxed = true)
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
        stateFile = Files.createTempFile("automated-savings-rule-engine", ".preferences_pb").toFile()

        coEvery { categoryRepository.getAll() } returns emptyList()

        recreateEngine()
    }

    @After
    fun tearDown() {
        stateScope?.cancel()
    }

    @Test
    fun `round up rule skips non positive roundUpTo values`() = runTest {
        val expense = purchase(amount = 12.30)
        val rules = listOf(
            roundUpRule(roundUpTo = 0.0),
            roundUpRule(roundUpTo = -1.0)
        )

        val executions = engine.evaluateRules(expense, rules)

        assertTrue(executions.isEmpty())
    }

    @Test
    fun `round up rule skips non finite roundUpTo values`() = runTest {
        val expense = purchase(amount = 12.30)
        val rules = listOf(
            roundUpRule(roundUpTo = Double.NaN),
            roundUpRule(roundUpTo = Double.POSITIVE_INFINITY),
            roundUpRule(roundUpTo = Double.NEGATIVE_INFINITY)
        )

        val executions = engine.evaluateRules(expense, rules)

        assertTrue(executions.isEmpty())
    }

    @Test
    fun `round up rule uses default increment when config is null`() = runTest {
        val expense = purchase(amount = 12.30)
        val rule = roundUpRule(roundUpTo = null)

        val executions = engine.evaluateRules(expense, listOf(rule))

        assertEquals(1, executions.size)
        assertEquals(2.70, executions.first().amount, 0.0001)
    }

    @Test
    fun `percentage of income rule skips invalid percentage values`() = runTest {
        val expense = deposit(amount = 100.0)
        val rules = listOf(
            percentageRule(percentage = -1.0),
            percentageRule(percentage = Double.NaN),
            percentageRule(percentage = Double.POSITIVE_INFINITY),
            percentageRule(percentage = Double.NEGATIVE_INFINITY)
        )

        val executions = engine.evaluateRules(expense, rules)

        assertTrue(executions.isEmpty())
    }

    @Test
    fun `weekly no spend rule uses stable calendar week boundaries`() = runTest {
        timeProvider = FakeTimeProvider.forDate(2026, 4, 13, 12, 0)
        recreateEngine()

        val now = timeProvider.now()
        val (expectedWeekStart, expectedWeekEnd) = TimePeriodUtils.getWeekRange(now)

        coEvery { expenseRepository.getExpensesBetween(expectedWeekStart, expectedWeekEnd) } returns emptyList()

        val executions = engine.evaluateRules(deposit(amount = 50.0), listOf(weeklyNoSpendRule()))

        assertEquals(1, executions.size)
        coVerify(exactly = 1) { expenseRepository.getExpensesBetween(expectedWeekStart, expectedWeekEnd) }
    }

    @Test
    fun `weekly no spend rule is idempotent within the same week`() = runTest {
        val now = timeProvider.now()
        val (weekStart, weekEnd) = TimePeriodUtils.getWeekRange(now)
        coEvery { expenseRepository.getExpensesBetween(weekStart, weekEnd) } returns emptyList()

        val firstExecutions = engine.evaluateRules(deposit(amount = 50.0), listOf(weeklyNoSpendRule()))
        val secondExecutions = engine.evaluateRules(deposit(amount = 50.0), listOf(weeklyNoSpendRule()))

        assertEquals(1, firstExecutions.size)
        assertTrue(secondExecutions.isEmpty())
    }

    @Test
    fun `monthly cap state survives engine recreation`() = runTest {
        val rule = AutomatedSavingsRule(
            id = 77L,
            name = "Income percentage",
            ruleType = SavingsRuleType.PERCENTAGE_OF_INCOME,
            targetGoalId = 42L,
            percentage = 20.0,
            maximumPerMonth = 25.0,
            isActive = true
        )
        val expense = deposit(amount = 100.0)

        val firstExecutions = engine.evaluateRules(expense, listOf(rule))
        recreateEngine()
        val secondExecutions = engine.evaluateRules(expense, listOf(rule))

        assertEquals(1, firstExecutions.size)
        assertEquals(20.0, firstExecutions.first().amount, 0.0001)
        assertEquals(1, secondExecutions.size)
        assertEquals(5.0, secondExecutions.first().amount, 0.0001)
    }

    @Test
    fun `weekly no spend reward can be granted later in same week after earlier cap block`() = runTest {
        timeProvider = FakeTimeProvider.forDate(2026, 4, 30, 12, 0)
        recreateEngine()

        val now = timeProvider.now()
        val (weekStart, weekEnd) = TimePeriodUtils.getWeekRange(now)
        val weeklyRule = AutomatedSavingsRule(
            id = 88L,
            name = "Weekly no spend",
            ruleType = SavingsRuleType.WEEKLY_NO_SPEND,
            targetGoalId = 42L,
            maximumPerMonth = 10.0,
            isActive = true
        )
        val incomeRule = AutomatedSavingsRule(
            id = 89L,
            name = "Income percentage",
            ruleType = SavingsRuleType.PERCENTAGE_OF_INCOME,
            targetGoalId = 42L,
            percentage = 100.0,
            maximumPerMonth = 10.0,
            isActive = true
        )
        coEvery { expenseRepository.getExpensesBetween(weekStart, weekEnd) } returns emptyList()

        val capConsumingExecutions = engine.evaluateRules(deposit(amount = 10.0), listOf(incomeRule, weeklyRule))

        assertEquals(1, capConsumingExecutions.size)
        assertEquals(SavingsRuleType.PERCENTAGE_OF_INCOME, capConsumingExecutions.first().rule.ruleType)
        assertEquals(10.0, capConsumingExecutions.first().amount, 0.0001)

        timeProvider.setTime(FakeTimeProvider.forDate(2026, 5, 1, 12, 0).now())
        recreateEngine()
        val mayNow = timeProvider.now()
        val (mayWeekStart, mayWeekEnd) = TimePeriodUtils.getWeekRange(mayNow)
        assertEquals(weekStart, mayWeekStart)
        coEvery { expenseRepository.getExpensesBetween(mayWeekStart, mayWeekEnd) } returns emptyList()

        val mayWeeklyOnlyExecutions = engine.evaluateRules(deposit(amount = 10.0), listOf(weeklyRule))

        assertFalse(mayWeeklyOnlyExecutions.isEmpty())
        assertEquals(10.0, mayWeeklyOnlyExecutions.first().amount, 0.0001)
    }

    private fun roundUpRule(roundUpTo: Double?): AutomatedSavingsRule {
        return AutomatedSavingsRule(
            id = 1L,
            name = "Round up",
            ruleType = SavingsRuleType.ROUND_UP,
            targetGoalId = 42L,
            roundUpTo = roundUpTo,
            isActive = true
        )
    }

    private fun percentageRule(percentage: Double?): AutomatedSavingsRule {
        return AutomatedSavingsRule(
            id = 2L,
            name = "Percentage",
            ruleType = SavingsRuleType.PERCENTAGE_OF_INCOME,
            targetGoalId = 42L,
            percentage = percentage,
            isActive = true
        )
    }

    private fun weeklyNoSpendRule(): AutomatedSavingsRule {
        return AutomatedSavingsRule(
            id = 3L,
            name = "Weekly no spend",
            ruleType = SavingsRuleType.WEEKLY_NO_SPEND,
            targetGoalId = 42L,
            isActive = true
        )
    }

    private fun purchase(amount: Double): Expense {
        return Expense(
            id = 1L,
            amount = amount,
            merchant = "Store",
            transactionType = TransactionType.PURCHASE,
            date = timeProvider.now()
        )
    }

    private fun deposit(amount: Double): Expense {
        return Expense(
            id = 2L,
            amount = amount,
            merchant = "Employer",
            transactionType = TransactionType.DEPOSIT,
            date = timeProvider.now()
        )
    }

    private fun recreateEngine() {
        stateScope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stateScope = newScope
        val dataStore = PreferenceDataStoreFactory.create(
            scope = newScope,
            produceFile = { stateFile }
        )
        val stateRepository = AutomatedSavingsRuleStateRepository(dataStore, timeProvider)
        engine = AutomatedSavingsRuleEngine(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            savingsGoalRepository = savingsGoalRepository,
            timeProvider = timeProvider,
            ruleStateRepository = stateRepository
        )
    }
}
