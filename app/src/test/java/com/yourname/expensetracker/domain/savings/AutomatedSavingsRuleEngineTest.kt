package com.yourname.expensetracker.domain.savings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var stateFile: File
    private lateinit var ruleStateRepository: AutomatedSavingsRuleStateRepository
    private var stateScope: CoroutineScope? = null

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        savingsGoalRepository = mockk(relaxed = true)
        analyticsCurrencyNormalizer = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
        stateFile = Files.createTempFile("automated-savings-rule-engine", ".preferences_pb").toFile()

        coEvery { categoryRepository.getAll() } returns emptyList()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        recreateEngine()
    }

    @After
    fun tearDown() {
        closeStateScope()
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

        coEvery { expenseRepository.getExpenseSnapshotsBetween(expectedWeekStart, expectedWeekEnd) } returns emptyList()

        val executions = engine.evaluateRules(deposit(amount = 50.0), listOf(weeklyNoSpendRule()))

        assertEquals(1, executions.size)
        coVerify(exactly = 1) { expenseRepository.getExpenseSnapshotsBetween(expectedWeekStart, expectedWeekEnd) }
    }

    @Test
    fun `weekly no spend rule is idempotent within the same week`() = runTest {
        val now = timeProvider.now()
        val (weekStart, weekEnd) = TimePeriodUtils.getWeekRange(now)
        coEvery { expenseRepository.getExpenseSnapshotsBetween(weekStart, weekEnd) } returns emptyList()

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
        coEvery { expenseRepository.getExpenseSnapshotsBetween(weekStart, weekEnd) } returns emptyList()

        val aprilMonth = AutomatedSavingsRuleStateRepository.buildYearMonthKey(now)
        val consumed = ruleStateRepository.consumeMonthlyAmountWithinCap(
            ruleStableKey = weeklyRule.id.toString(),
            yearMonth = aprilMonth,
            requestedAmount = 10.0,
            maximumPerMonth = 10.0
        )
        assertEquals(10.0, consumed, 0.0001)

        val capBlockedExecutions = engine.evaluateRules(deposit(amount = 10.0), listOf(weeklyRule))
        assertTrue(capBlockedExecutions.isEmpty())

        timeProvider.setTime(FakeTimeProvider.forDate(2026, 5, 1, 12, 0).now())
        recreateEngine()
        val mayNow = timeProvider.now()
        val (mayWeekStart, mayWeekEnd) = TimePeriodUtils.getWeekRange(mayNow)
        assertEquals(weekStart, mayWeekStart)
        coEvery { expenseRepository.getExpenseSnapshotsBetween(mayWeekStart, mayWeekEnd) } returns emptyList()

        val mayWeeklyOnlyExecutions = engine.evaluateRules(deposit(amount = 10.0), listOf(weeklyRule))

        assertFalse(mayWeeklyOnlyExecutions.isEmpty())
        assertEquals(10.0, mayWeeklyOnlyExecutions.first().amount, 0.0001)
    }

    @Test
    fun `round up rule still rounds 3 40 to the default 5 increment`() = runTest {
        val executions = engine.evaluateRules(purchase(amount = 3.40), listOf(roundUpRule(roundUpTo = null)))

        assertEquals(1, executions.size)
        assertEquals(1.60, executions.first().amount, 0.0001)
    }

    @Test
    fun `spare change saves the difference up to the next whole unit`() = runTest {
        val cases = listOf(3.40 to 0.60, 0.01 to 0.99, 9.99 to 0.01, 12.50 to 0.50, 0.10 to 0.90, 100.01 to 0.99)

        for ((amount, expectedSaved) in cases) {
            val executions = engine.evaluateRules(purchase(amount = amount), listOf(spareChangeRule()))

            assertEquals("amount=$amount", 1, executions.size)
            assertEquals("amount=$amount", expectedSaved, executions.first().amount, 0.0001)
        }
    }

    @Test
    fun `spare change is not capped at ten`() = runTest {
        val executions = engine.evaluateRules(purchase(amount = 12.50), listOf(spareChangeRule()))

        assertEquals(1, executions.size)
        assertEquals(0.50, executions.first().amount, 0.0001)
    }

    @Test
    fun `spare change skips whole unit amounts`() = runTest {
        for (amount in listOf(3.00, 1.00, 10.00, 25.0)) {
            val executions = engine.evaluateRules(purchase(amount = amount), listOf(spareChangeRule()))

            assertTrue("amount=$amount", executions.isEmpty())
        }
    }

    @Test
    fun `spare change skips zero negative and non finite amounts`() = runTest {
        for (amount in listOf(0.0, -3.40, Double.NaN, Double.POSITIVE_INFINITY)) {
            val executions = engine.evaluateRules(purchase(amount = amount), listOf(spareChangeRule()))

            assertTrue("amount=$amount", executions.isEmpty())
        }
    }

    @Test
    fun `spare change ignores non purchase transactions`() = runTest {
        val executions = engine.evaluateRules(deposit(amount = 3.40), listOf(spareChangeRule()))

        assertTrue(executions.isEmpty())
    }

    @Test
    fun `spare change skips not mine expenses`() = runTest {
        val executions = engine.evaluateRules(
            purchase(amount = 3.40).copy(isNotMine = true),
            listOf(spareChangeRule())
        )

        assertTrue(executions.isEmpty())
    }

    @Test
    fun `spare change uses the shared effective share amount`() = runTest {
        val sharedPercentage = purchase(amount = 10.00).copy(
            isSharedExpense = true,
            mySharePercentage = 34
        )
        val sharedAmount = purchase(amount = 20.00).copy(
            isSharedExpense = true,
            myShareAmount = 3.40
        )

        val percentageExecutions = engine.evaluateRules(sharedPercentage, listOf(spareChangeRule()))
        val amountExecutions = engine.evaluateRules(sharedAmount, listOf(spareChangeRule()))

        assertEquals(1, percentageExecutions.size)
        assertEquals(0.60, percentageExecutions.first().amount, 0.0001)
        assertEquals(1, amountExecutions.size)
        assertEquals(0.60, amountExecutions.first().amount, 0.0001)
    }

    @Test
    fun `spare change reason formats the saved amount not the purchase`() = runTest {
        val executions = engine.evaluateRules(purchase(amount = 3.40), listOf(spareChangeRule()))

        val reason = executions.single().reason
        assertTrue(reason.contains("Store"))
        assertTrue(reason.contains(CurrencyFormatter.formatMoney(0.60, "EUR")))
        assertFalse(reason.contains(CurrencyFormatter.formatMoney(3.40, "EUR")))
    }

    @Test
    fun `spare change respects the monthly cap`() = runTest {
        val rule = spareChangeRule(maximumPerMonth = 1.0)

        val first = engine.evaluateRules(purchase(amount = 3.40), listOf(rule))
        val second = engine.evaluateRules(purchase(amount = 5.30).copy(id = 2L), listOf(rule))
        val third = engine.evaluateRules(purchase(amount = 7.10), listOf(rule))

        assertEquals(0.60, first.single().amount, 0.0001)
        assertEquals(0.40, second.single().amount, 0.0001)
        assertTrue(second.single().reason.endsWith("(monthly cap applied)"))
        assertTrue(third.isEmpty())
    }

    @Test
    fun `spare change skips inactive rules`() = runTest {
        val executions = engine.evaluateRules(
            purchase(amount = 3.40),
            listOf(spareChangeRule().copy(isActive = false))
        )

        assertTrue(executions.isEmpty())
    }

    private fun spareChangeRule(maximumPerMonth: Double? = null): AutomatedSavingsRule {
        return AutomatedSavingsRule(
            id = 4L,
            name = "Spare change",
            ruleType = SavingsRuleType.SPARE_CHANGE,
            targetGoalId = 42L,
            maximumPerMonth = maximumPerMonth,
            isActive = true
        )
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
        closeStateScope()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stateScope = newScope
        val dataStore = PreferenceDataStoreFactory.create(
            scope = newScope,
            produceFile = { stateFile }
        )
        ruleStateRepository = AutomatedSavingsRuleStateRepository(dataStore, timeProvider)
        engine = AutomatedSavingsRuleEngine(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            ruleStateRepository = ruleStateRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository
        )
    }

    private fun closeStateScope() {
        val job = stateScope?.coroutineContext?.get(Job) ?: return
        runBlocking { job.cancelAndJoin() }
        stateScope = null
    }
}
