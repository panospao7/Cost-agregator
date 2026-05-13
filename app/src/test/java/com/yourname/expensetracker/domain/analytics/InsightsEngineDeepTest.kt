package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InsightsEngineDeepTest {

    private lateinit var engine: InsightsEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
    private lateinit var timeProvider: TimeProvider
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator

    private val categories = listOf(
        AnalyticsCategoryRef(id = 1L, name = "Food", icon = "🍽️", color = "#FF0000"),
        AnalyticsCategoryRef(id = 2L, name = "Transport", icon = "🚌", color = "#00FF00")
    )

    @Before
    fun setUp() {
        expenseRepository = mockk(relaxed = true)
        recurringEngine = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        spendingPaceCalculator = mockk(relaxed = true)

        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = mockk(relaxed = true),
            monthlyComparisonCalculator = MonthlyComparisonCalculator(),
            categoryInsightEngine = CategoryInsightEngine(),
            merchantInsightEngine = MerchantInsightEngine(),
            dayOfWeekAnalyzer = DayOfWeekAnalyzer()
        )

        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        coEvery { expenseRepository.getLargestExpenseSnapshotForPeriod(any(), any()) } returns null
        coEvery { expenseRepository.getLargestExpenseSnapshotForMerchant(any(), any(), any()) } returns null
    }

    @Test
    fun `monthly comparison computes delta and percentage`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        val expenses = buildList {
            repeat(6) { add(createExpense(date = "2026-04-${it + 1}", amount = 100.0, category = "Food")) }
            repeat(4) { add(createExpense(date = "2026-03-${it + 1}", amount = 100.0, category = "Food")) }
        }

        val snapshot = engine.generateInsights(categories, expenses.map { it.toSnapshot() }, "EUR")

        assertApproxEquals(600.0, snapshot.monthlyComparison.currentTotal)
        assertApproxEquals(200.0, snapshot.monthlyComparison.changeAmount ?: 0.0)
        assertApproxEquals(50f, snapshot.monthlyComparison.changePercentage ?: 0f, 0.01f)
    }

    @Test
    fun `spending pace canonical formula and status are correct`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 16)
        every { spendingPaceCalculator.calculate(any(), any(), any(), any(), any(), any()) } returns SpendingPace(
            currentMonthSpent = 1600.0,
            daysElapsed = 16,
            daysInMonth = 30,
            projectedTotal = 3000.0,
            previousMonthTotal = 930.0,
            averageMonthlyTotal = null,
            pacePercentage = 333.33f,
            paceStatus = PaceStatus.OVER_PACE,
            displayCurrency = "EUR",
        )

        val snapshot = engine.generateInsights(categories, emptyList(), "EUR")

        val pace = snapshot.spendingPace
        assertEquals(PaceStatus.OVER_PACE, pace.paceStatus)
        assertApproxEquals(333.33f, pace.pacePercentage, 0.2f)
        assertApproxEquals(3000.0, pace.projectedTotal)
    }

    @Test
    fun `category breakdown groups by category and computes percentage`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 100.0, category = "Food"),
            createExpense(date = "2026-04-02", amount = 100.0, category = "Food"),
            createExpense(date = "2026-04-03", amount = 100.0, category = "Food"),
            createExpense(date = "2026-04-04", amount = 100.0, category = "Transport")
        )

        val snapshot = engine.generateInsights(categories, expenses.map { it.toSnapshot() }, "EUR")
        val food = snapshot.categoryInsights.first { it.category.id == 1L }
        val transport = snapshot.categoryInsights.first { it.category.id == 2L }

        assertApproxEquals(75f, food.percentageOfTotal, 0.01f)
        assertApproxEquals(25f, transport.percentageOfTotal, 0.01f)
        assertApproxEquals(100.0, snapshot.categoryInsights.sumOf { it.percentageOfTotal.toDouble() }, 0.05)
    }

    @Test
    fun `top merchants sorted descending and recurrence uses narrow variance`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        val allExpenses = listOf(
            createExpense(date = "2026-04-01", amount = 95.0, merchant = "Alpha").toSnapshot().copy(merchantKey = "alpha_key"),
            createExpense(date = "2026-04-05", amount = 100.0, merchant = "Alpha").toSnapshot().copy(merchantKey = "alpha_key"),
            createExpense(date = "2026-04-09", amount = 110.0, merchant = "Alpha").toSnapshot().copy(merchantKey = "alpha_key"),
            createExpense(date = "2026-04-12", amount = 120.0, merchant = "Beta").toSnapshot().copy(merchantKey = "beta_key")
        )

        val snapshot = engine.generateInsights(categories, allExpenses, "EUR")

        assertEquals("Alpha", snapshot.topMerchants.first().merchant)
        assertTrue(snapshot.topMerchants.first().isLikelyRecurring)
        assertTrue((snapshot.topMerchants.first().stdDeviation ?: 0.0) > 0.0)
        assertTrue(!snapshot.topMerchants.last().isLikelyRecurring)
    }

    @Test
    fun `day of week pattern aggregates using effective amount`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        val mondayShared = createExpense(
            date = "2026-03-02", amount = 100.0, effectiveAmount = 40.0,
            isSharedExpense = true, myShareAmount = 40.0, merchant = "Shared"
        )
        val mondayNormal = createExpense(date = "2026-03-09", amount = 30.0, merchant = "Normal")

        val snapshot = engine.generateInsights(categories, listOf(mondayShared, mondayNormal).map { it.toSnapshot() }, "EUR")
        val monday = snapshot.dayOfWeekPattern.first { it.dayName == "Mon" }

        assertApproxEquals(70.0, monday.totalSpent)
        assertEquals(2, monday.transactionCount)
        assertApproxEquals(35.0, monday.avgPerTransaction)
    }

    @Test
    fun `average and median transaction size should use effective amount`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 10)
        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 100.0, effectiveAmount = 20.0, isSharedExpense = true, myShareAmount = 20.0),
            createExpense(date = "2026-04-02", amount = 50.0),
            createExpense(date = "2026-04-03", amount = 30.0)
        )

        val snapshot = engine.generateInsights(categories, expenses.map { it.toSnapshot() }, "EUR")

        // Canonical expectation: avg=(20+50+30)/3=33.33, median=30
        assertApproxEquals(33.33, snapshot.averageTransactionSize, 0.1)
        assertApproxEquals(30.0, snapshot.medianTransactionSize, 0.01)
    }

    @Test
    fun `empty dataset yields safe defaults`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 10)
        val snapshot = engine.generateInsights(categories, emptyList(), "EUR")

        assertApproxEquals(0.0, snapshot.monthlyComparison.currentTotal)
        assertTrue(snapshot.categoryInsights.isEmpty())
        assertTrue(snapshot.topMerchants.isEmpty())
        assertTrue(snapshot.dayOfWeekPattern.isEmpty())
    }

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun com.yourname.expensetracker.data.database.entity.Expense.toSnapshot(): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = when (transactionType) {
                TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = when (transferDirection) {
                com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                null -> null
            },
            notes = notes
        )
    }
}