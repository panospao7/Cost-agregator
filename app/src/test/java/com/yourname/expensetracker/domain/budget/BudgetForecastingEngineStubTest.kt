package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.repository.BudgetRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BudgetForecastingEngineStubTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetForecastDao: BudgetForecastDao
    private lateinit var engine: BudgetForecastingEngine

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepository = mockk(relaxed = true)
        budgetForecastDao = mockk(relaxed = true)
        every { budgetForecastDao.getForecastsForBudget(any()) } returns flowOf(emptyList())

        engine = BudgetForecastingEngine(
            expenseDao = expenseDao,
            budgetRepository = budgetRepository,
            budgetForecastDao = budgetForecastDao,
            timeProvider = timeProvider,
            currencySettingsRepository = mockk(),
        )
    }

    @Test
    fun `update forecast accuracy is currently a no op stub and performs no writes`() = runTest {
        engine.updateForecastAccuracy(forecastId = 123L, actualSpending = 456.78)

        verify(exactly = 1) { budgetForecastDao.getForecastsForBudget(123L) }
        coVerify(exactly = 0) { budgetForecastDao.update(any()) }
        coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
    }

    @Test
    fun `public forecasting constants expose expected configured values`() {
        assertEquals(3, BudgetForecastingEngine.MIN_HISTORY_MONTHS)
        assertApproxEquals(0.8, BudgetForecastingEngine.CONFIDENCE_THRESHOLD_HIGH, 0.0)
        assertApproxEquals(0.6, BudgetForecastingEngine.CONFIDENCE_THRESHOLD_MEDIUM, 0.0)
    }
}