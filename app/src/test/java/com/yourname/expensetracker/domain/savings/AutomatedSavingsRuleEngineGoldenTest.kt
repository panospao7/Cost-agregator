package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.assertApproxEquals
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
import org.junit.Before
import org.junit.Test

class AutomatedSavingsRuleEngineGoldenTest {

    private lateinit var engine: AutomatedSavingsRuleEngine

    @Before
    fun setUp() {
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns 1_700_000_000_000L

        engine = AutomatedSavingsRuleEngine(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            savingsGoalRepository = savingsGoalRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `round up golden case 17 30 to nearest 5 produces savings amount 2 70`() = runTest {
        val expense = Expense(
            id = 1L,
            amount = 17.30,
            merchant = "Cafe",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L
        )
        val rule = AutomatedSavingsRule(
            id = 1L,
            name = "Round up to 5",
            ruleType = SavingsRuleType.ROUND_UP,
            targetGoalId = 42L,
            roundUpTo = 5.0,
            isActive = true
        )

        val executions = engine.evaluateRules(expense, listOf(rule))

        assertApproxEquals(1.0, executions.size.toDouble(), 0.0)
        assertApproxEquals(2.70, executions.first().amount, 0.01)
    }
}
