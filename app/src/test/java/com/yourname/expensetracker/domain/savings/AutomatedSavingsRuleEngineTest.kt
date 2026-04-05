package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutomatedSavingsRuleEngineTest {

    private lateinit var engine: AutomatedSavingsRuleEngine
    private lateinit var timeProvider: TimeProvider

    @Before
    fun setup() {
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
        timeProvider = mockk(relaxed = true)

        every { timeProvider.now() } returns 1_700_000_000_000L

        engine = AutomatedSavingsRuleEngine(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            savingsGoalRepository = savingsGoalRepository,
            timeProvider = timeProvider
        )
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

    private fun purchase(amount: Double): Expense {
        return Expense(
            id = 1L,
            amount = amount,
            merchant = "Store",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L
        )
    }
}
