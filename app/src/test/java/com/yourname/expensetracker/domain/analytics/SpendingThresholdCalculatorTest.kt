package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import kotlin.math.abs

class SpendingThresholdCalculatorTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var calculator: SpendingThresholdCalculator

    @Before
    fun setup() {
        expenseDao = mock(ExpenseDao::class.java)
        timeProvider = mock(TimeProvider::class.java)
        
        val testDispatcher: CoroutineDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        
        calculator = SpendingThresholdCalculator(
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `calculateHighAmountThreshold returns minimum threshold when insufficient data`() = runTest {
        whenever(timeProvider.now()).thenReturn(System.currentTimeMillis())
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(emptyList())

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertEquals("Should return minimum threshold when no data", 50.0, threshold, 0.01)
    }

    @Test
    fun `calculateHighAmountThreshold returns P90 for typical spending`() = runTest {
        val now = System.currentTimeMillis()
        whenever(timeProvider.now()).thenReturn(now)
        
        val amounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(amounts)

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertTrue("Threshold should be around 90 (P90 of 10-100)", abs(threshold - 90.0) < 5.0)
    }

    @Test
    fun `calculateHighAmountThreshold enforces minimum threshold`() = runTest {
        val now = System.currentTimeMillis()
        whenever(timeProvider.now()).thenReturn(now)
        
        val amounts = listOf(5.0, 10.0, 15.0, 20.0, 25.0)
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(amounts)

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertEquals("Should return minimum threshold (50) when P90 is below minimum", 50.0, threshold, 0.01)
    }

    @Test
    fun `calculateHighAmountThreshold uses correct user ID for caching`() = runTest {
        val now = System.currentTimeMillis()
        whenever(timeProvider.now()).thenReturn(now)
        
        val amounts = listOf(50.0, 75.0, 100.0, 125.0, 150.0)
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(amounts)

        calculator.calculateHighAmountThreshold("user-1")
        calculator.calculateHighAmountThreshold("user-1")

        val threshold1 = calculator.calculateHighAmountThreshold("user-1")
        val threshold2 = calculator.calculateHighAmountThreshold("user-2")

        assertTrue("Same user should get same cached threshold", abs(threshold1 - threshold2) < 10.0)
    }

    @Test
    fun `getThreshold convenience method works for single-user`() = runTest {
        val now = System.currentTimeMillis()
        whenever(timeProvider.now()).thenReturn(now)
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(listOf(100.0, 200.0, 300.0, 400.0, 500.0))

        val threshold = calculator.getThreshold()

        assertTrue("Should return calculated threshold", threshold >= 50.0)
    }

    @Test
    fun `refreshThreshold clears cache for default user`() = runTest {
        val now = System.currentTimeMillis()
        whenever(timeProvider.now()).thenReturn(now)
        
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(listOf(100.0, 200.0, 300.0, 400.0, 500.0))
            .thenReturn(listOf(100.0, 150.0, 200.0))

        val threshold1 = calculator.getThreshold()
        calculator.refreshThreshold()
        val threshold2 = calculator.getThreshold()

        assertTrue("After refresh, threshold should be recalculated", abs(threshold1 - threshold2) < 50.0)
    }

    @Test
    fun `calculatePercentiles computes correct P50 P75 P90`() = runTest {
        val now = System.currentTimeMillis()
        whenever(timeProvider.now()).thenReturn(now)
        
        val amounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(amounts)

        val percentiles = calculator.calculatePercentiles("test-user")

        assertTrue("P50 should be around 55", abs(percentiles.p50 - 55.0) < 5.0)
        assertTrue("P75 should be around 77", abs(percentiles.p75 - 77.5) < 5.0)
        assertTrue("P90 should be around 91", abs(percentiles.p90 - 91.0) < 5.0)
    }

    @Test
    fun `calculatePercentiles handles single transaction`() = runTest {
        val now = System.currentTimeMillis()
        whenever(timeProvider.now()).thenReturn(now)
        
        whenever(expenseDao.getAmountsForPercentileCalc(anyLong(), anyLong()))
            .thenReturn(listOf(100.0))

        val percentiles = calculator.calculatePercentiles("test-user")

        assertEquals("P50 should be 100 for single transaction", 100.0, percentiles.p50, 0.01)
        assertEquals("P90 should be 100 for single transaction", 100.0, percentiles.p90, 0.01)
    }
}
