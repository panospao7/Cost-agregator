package com.yourname.expensetracker.domain.engine

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.service.TransactionFilterSerializer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * E-02-4: Rule 1 (high amount) compares a home-currency amount against a home-currency
 * threshold. Threshold 50 EUR (empty history minimum), home EUR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardFollowThroughEngineHomeCurrencyTest {

    private val testDispatcher = StandardTestDispatcher()
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { timeProvider.now() } returns NOW
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun realCalculator(): SpendingThresholdCalculator {
        val settings = mockk<CurrencySettingsRepository>()
        every { settings.homeCurrency() } returns flowOf("EUR")
        return SpendingThresholdCalculator(
            expenseDao,
            timeProvider,
            testDispatcher,
            AnalyticsCurrencyNormalizer(CurrencyConverter(mockk(relaxed = true), timeProvider = mockk())),
            settings
        )
    }

    private fun engineWith(calculator: SpendingThresholdCalculator) = DashboardFollowThroughEngine(
        TransactionFilterSerializer(), calculator, timeProvider, testDispatcher
    )

    private fun tx(amount: Double, currency: String, baseAmount: Double = 0.0, baseCurrency: String = "EUR") = Expense(
        id = 1L,
        amount = amount,
        currency = currency,
        merchant = "Test Merchant",
        transactionType = TransactionType.PURCHASE,
        date = NOW,
        createdAt = NOW,
        baseAmount = baseAmount,
        baseCurrency = baseCurrency
    )

    private suspend fun hasRule1(expense: Expense): Boolean =
        engineWith(realCalculator()).generateRecommendations(expense, null, "u")
            .any { it.priority == RecommendationPriority.HIGH }

    @Test
    fun `E-02-4 5000 JPY with base 30 EUR below threshold 50 has no Rule 1`() = runTest {
        assertEquals(false, hasRule1(tx(5000.0, "JPY", baseAmount = 30.0, baseCurrency = "EUR")))
    }

    @Test
    fun `E-02-4 5000 JPY with base 0 is not comparable and has no Rule 1`() = runTest {
        assertEquals(false, hasRule1(tx(5000.0, "JPY", baseAmount = 0.0, baseCurrency = "EUR")))
    }

    @Test
    fun `E-02-4 5000 JPY with base 60 USD is not home currency and has no Rule 1`() = runTest {
        assertEquals(false, hasRule1(tx(5000.0, "JPY", baseAmount = 60.0, baseCurrency = "USD")))
    }

    @Test
    fun `E-02-4 5000 JPY with base 60 EUR above threshold 50 triggers Rule 1`() = runTest {
        assertEquals(true, hasRule1(tx(5000.0, "JPY", baseAmount = 60.0, baseCurrency = "EUR")))
    }

    @Test
    fun `E-02-4 60 EUR triggers Rule 1`() = runTest {
        assertEquals(true, hasRule1(tx(60.0, "EUR")))
    }

    @Test
    fun `E-02-4 40 EUR does not trigger Rule 1`() = runTest {
        assertEquals(false, hasRule1(tx(40.0, "EUR")))
    }

    @Test
    fun `E-02-4 unavailable threshold skips Rule 1 and Rules 2 to 4 still run`() = runTest {
        val calculator = mockk<SpendingThresholdCalculator>()
        coEvery { calculator.getThreshold() } throws
            MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        val expense = tx(5000.0, "EUR").copy(categoryId = 5L)

        val recs = engineWith(calculator).generateRecommendations(expense, null, "u")

        assertEquals(0, recs.count { it.priority == RecommendationPriority.HIGH })
        // Rule 2 category (MEDIUM), Rule 3 merchant (MEDIUM), Rule 4 recent (LOW).
        assertEquals(
            listOf(RecommendationPriority.MEDIUM, RecommendationPriority.MEDIUM, RecommendationPriority.LOW),
            recs.map { it.priority }
        )
    }

    @Test
    fun `E-02-4 CancellationException from the threshold calculator propagates`() = runTest {
        val calculator = mockk<SpendingThresholdCalculator>()
        coEvery { calculator.getThreshold() } throws CancellationException("cancelled")

        try {
            engineWith(calculator).generateRecommendations(tx(60.0, "EUR"), null, "u")
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // not swallowed into a missing Rule 1
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
