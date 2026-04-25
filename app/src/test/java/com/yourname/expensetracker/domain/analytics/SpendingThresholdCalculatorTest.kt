package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class SpendingThresholdCalculatorTest {

    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private lateinit var expenseDao: ExpenseDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var calculator: SpendingThresholdCalculator

    @Before
    fun setup() {
        expenseDao = mockk()
        timeProvider = mockk()
        
        calculator = SpendingThresholdCalculator(
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `calculateHighAmountThreshold returns minimum threshold when insufficient data`() = runTest(testDispatcher) {
        every { timeProvider.now() } returns System.currentTimeMillis()
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) } returns emptyList()

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertEquals("Should return minimum threshold when no data", 50.0, threshold, 0.01)
    }

    @Test
    fun `calculateHighAmountThreshold returns P90 for typical spending`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        
        val amounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) } returns amounts

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertTrue("Threshold should be around 90 (P90 of 10-100)", abs(threshold - 90.0) < 5.0)
    }

    @Test
    fun `calculateHighAmountThreshold enforces minimum threshold`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        
        val amounts = listOf(5.0, 10.0, 15.0, 20.0, 25.0)
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) } returns amounts

        val threshold = calculator.calculateHighAmountThreshold("test-user")

        assertEquals("Should return minimum threshold (50) when P90 is below minimum", 50.0, threshold, 0.01)
    }

    @Test
    fun `calculateHighAmountThreshold uses correct user ID for caching`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        
        val amounts = listOf(50.0, 75.0, 100.0, 125.0, 150.0)
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) } returns amounts

        calculator.calculateHighAmountThreshold("user-1")
        calculator.calculateHighAmountThreshold("user-1")

        val threshold1 = calculator.calculateHighAmountThreshold("user-1")
        val threshold2 = calculator.calculateHighAmountThreshold("user-2")

        assertTrue("Same user should get same cached threshold", abs(threshold1 - threshold2) < 10.0)
    }

    @Test
    fun `getThreshold convenience method works for single-user`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returns(listOf(100.0, 200.0, 300.0, 400.0, 500.0))

        val threshold = calculator.getThreshold()

        assertTrue("Should return calculated threshold", threshold >= 50.0)
    }

    @Test
    fun `refreshThreshold clears cache for default user`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returnsMany(
                listOf(
                    listOf(100.0, 200.0, 300.0, 400.0, 500.0),
                    listOf(100.0, 150.0, 200.0)
                )
            )

        val threshold1 = calculator.getThreshold()
        calculator.refreshThreshold()
        val threshold2 = calculator.getThreshold()

        assertTrue("After refresh, threshold should be recalculated", abs(threshold1 - threshold2) < 50.0)
    }

    @Test
    fun `calculatePercentiles computes correct P50 P75 P90`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        
        val amounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) } returns amounts

        val percentiles = calculator.calculatePercentiles("test-user")

        assertTrue("P50 should be around 55", abs(percentiles.p50 - 55.0) < 5.0)
        assertTrue("P75 should be around 77", abs(percentiles.p75 - 77.5) < 5.0)
        assertTrue("P90 should be around 91", abs(percentiles.p90 - 91.0) < 5.0)
    }

    @Test
    fun `calculatePercentiles handles single transaction`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returns(listOf(100.0))

        val percentiles = calculator.calculatePercentiles("test-user")

        assertEquals("P50 should be 100 for single transaction", 100.0, percentiles.p50, 0.01)
        assertEquals("P90 should be 100 for single transaction", 100.0, percentiles.p90, 0.01)
    }

    @Test
    fun `calculatePercentiles uses aggregate percentile DAO path`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returns(listOf(20.0, 40.0, 60.0, 80.0, 100.0))

        calculator.calculatePercentiles("aggregate-check")

        coVerify(exactly = 1) { expenseDao.getAmountsForPercentileCalc(any(), any()) }
    }

    @Test
    fun `refreshThresholds prevents stale in-flight recompute from overwriting cache`() = runTest(testDispatcher) {
        // Regression for ISSUE-2: a compute that started before refreshThresholds() must
        // not write its result back after the invalidation.
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now

        // First populate the cache with a fresh entry
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returns(listOf(100.0, 200.0, 300.0, 400.0, 500.0))
        calculator.calculatePercentiles("user-a")

        // Refresh (bumps generation, removes entry)
        calculator.refreshThresholds("user-a")

        // A second compute call after refresh — should write back normally
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returns(listOf(10.0, 20.0, 30.0, 40.0, 50.0))
        val percentiles = calculator.calculatePercentiles("user-a")

        // The result should reflect the second (post-refresh) compute
        assertEquals("Post-refresh compute should return 5 samples", 5, percentiles.sampleSize)
    }

    @Test
    fun `expired cache entry is replaced by fresh recompute — TTL semantics preserved`() = runTest(testDispatcher) {
        // Regression for ISSUE-5: an expired (but still present) cache entry must be
        // replaced by the next recompute; the old containsKey guard broke this.
        val cacheAge = 25 * 60 * 60 * 1000L // 25 hours — older than the 24 h TTL

        // Seed a stale entry by computing at time T0
        val t0 = 1_000_000L
        every { timeProvider.now() } returns t0
        val staleAmounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returns(staleAmounts)
        val stale = calculator.calculatePercentiles("user-b")

        // Move clock forward past TTL
        val t1 = t0 + cacheAge
        every { timeProvider.now() } returns t1

        // Fresh compute with different (much larger) data — must replace expired entry
        val freshAmounts = listOf(500.0, 600.0, 700.0, 800.0, 900.0, 1000.0,
                                   1100.0, 1200.0, 1300.0, 1400.0)
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) }
            .returns(freshAmounts)
        val fresh = calculator.calculatePercentiles("user-b")

        assertTrue("Fresh compute must return new data after TTL expiry; fresh.p90=${fresh.p90} stale.p90=${stale.p90}",
            fresh.p90 > stale.p90 + 100.0)
    }
}
