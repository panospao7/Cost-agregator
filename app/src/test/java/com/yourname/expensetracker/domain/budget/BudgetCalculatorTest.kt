package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class BudgetCalculatorTest {

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var calculator: BudgetCalculator

    @Before
    fun setup() {
        every { timeProvider.now() } returns System.currentTimeMillis()
        calculator = BudgetCalculator(timeProvider)
    }

    @Test
    fun `calculatePeriodWindow DAILY returns 24h window`() {
        val now = System.currentTimeMillis()
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.DAILY, 0L, now)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Start should be start of today
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(Calendar.MINUTE))
        
        // End should be exactly 24 hours later
        val diff = window.end - window.start
        assertEquals(24 * 60 * 60 * 1000L, diff)
    }

    @Test
    fun `calculatePeriodWindow WEEKLY returns 7 day window aligned to anchor`() {
        // Anchor: Monday
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2024, Calendar.JANUARY, 1, 10, 0, 0) // Mon Jan 1 2024
        val anchor = anchorCal.timeInMillis
        
        // Eval: Wednesday Jan 3
        val evalCal = Calendar.getInstance()
        evalCal.set(2024, Calendar.JANUARY, 3, 15, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.WEEKLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        
        // Start should be the Monday of that week (Jan 1)
        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))
        
        // Length should be 7 days
        val length = window.end - window.start
        assertEquals(7 * 24 * 60 * 60 * 1000L, length)
    }

    @Test
    fun `calculatePeriodWindow MONTHLY respects anchor day`() {
        // Anchor: 15th of month
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2024, Calendar.JANUARY, 15, 10, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: Jan 20th
        val evalCal = Calendar.getInstance()
        evalCal.set(2024, Calendar.JANUARY, 20, 10, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Should be Jan 15 - Feb 15
        assertEquals(15, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JANUARY, startCal.get(Calendar.MONTH))
        
        assertEquals(15, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, endCal.get(Calendar.MONTH))
    }

    @Test
    fun `calculatePeriodWindow MONTHLY wraps around year correctly`() {
        // Anchor: 15th
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2023, Calendar.DECEMBER, 15, 0, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: Dec 20
        val evalCal = Calendar.getInstance()
        evalCal.set(2023, Calendar.DECEMBER, 20, 0, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Dec 15 2023 -> Jan 15 2024
        assertEquals(Calendar.DECEMBER, startCal.get(Calendar.MONTH))
        assertEquals(2023, startCal.get(Calendar.YEAR))
        
        assertEquals(Calendar.JANUARY, endCal.get(Calendar.MONTH))
        assertEquals(2024, endCal.get(Calendar.YEAR))
    }

    @Test
    fun `calculatePeriodWindow MONTHLY handles leap year Feb 29`() {
        // Anchor: Feb 29 2024
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2024, Calendar.FEBRUARY, 29, 0, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: March 15
        val evalCal = Calendar.getInstance()
        evalCal.set(2024, Calendar.MARCH, 15, 0, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Feb 29 -> March 29
        assertEquals(Calendar.FEBRUARY, startCal.get(Calendar.MONTH))
        assertEquals(29, startCal.get(Calendar.DAY_OF_MONTH))
        
        assertEquals(Calendar.MARCH, endCal.get(Calendar.MONTH))
        assertEquals(29, endCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `calculatePeriodWindow YEARLY returns 12 month window`() {
        // Anchor: Jan 1 2024
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2024, Calendar.JANUARY, 1, 0, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: June 15
        val evalCal = Calendar.getInstance()
        evalCal.set(2024, Calendar.JUNE, 15, 0, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.YEARLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        assertEquals(2024, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, startCal.get(Calendar.MONTH))
        
        assertEquals(2025, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, endCal.get(Calendar.MONTH))
    }

    @Test
    fun `calculatePeriodWindow YEARLY treats Feb 29 anchor as passed on Feb 28 in non leap year`() {
        // Anchor: Feb 29 2024 (leap year)
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2024, Calendar.FEBRUARY, 29, 0, 0, 0)
        val anchor = anchorCal.timeInMillis

        // Eval: Feb 28 2025 (non-leap year)
        val evalCal = Calendar.getInstance()
        evalCal.set(2025, Calendar.FEBRUARY, 28, 12, 0, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.YEARLY, anchor, evalCal.timeInMillis)

        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }

        // Feb 28 2025 -> Feb 28 2026 (treated as anniversary passed)
        assertEquals(2025, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, startCal.get(Calendar.MONTH))
        assertEquals(28, startCal.get(Calendar.DAY_OF_MONTH))

        assertEquals(2026, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, endCal.get(Calendar.MONTH))
        assertEquals(28, endCal.get(Calendar.DAY_OF_MONTH))
    }

    // Critical edge cases from analysis document
    @Test
    fun `calculatePeriodWindow MONTHLY anchor Jan 31 leap year`() {
        // Anchor: Jan 31 2024 (leap year)
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2024, Calendar.JANUARY, 31, 0, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: Feb 15 2024
        val evalCal = Calendar.getInstance()
        evalCal.set(2024, Calendar.FEBRUARY, 15, 0, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Jan 31 -> Feb 29 (leap year has Feb 29)
        assertEquals(31, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JANUARY, startCal.get(Calendar.MONTH))
        
        assertEquals(29, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, endCal.get(Calendar.MONTH))
    }

    @Test
    fun `calculatePeriodWindow MONTHLY anchor Jan 31 non-leap year`() {
        // Anchor: Jan 31 2023 (non-leap year)
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2023, Calendar.JANUARY, 31, 0, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: Feb 15 2023
        val evalCal = Calendar.getInstance()
        evalCal.set(2023, Calendar.FEBRUARY, 15, 0, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Jan 31 -> Feb 28 (non-leap year)
        assertEquals(31, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JANUARY, startCal.get(Calendar.MONTH))
        
        assertEquals(28, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, endCal.get(Calendar.MONTH))
    }

    @Test
    fun `calculatePeriodWindow MONTHLY anchor Dec 31 year boundary`() {
        // Anchor: Dec 31
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2023, Calendar.DECEMBER, 31, 0, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: Jan 15 2024
        val evalCal = Calendar.getInstance()
        evalCal.set(2024, Calendar.JANUARY, 15, 0, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Dec 31 -> Jan 31
        assertEquals(Calendar.DECEMBER, startCal.get(Calendar.MONTH))
        assertEquals(2023, startCal.get(Calendar.YEAR))
        
        assertEquals(Calendar.JANUARY, endCal.get(Calendar.MONTH))
        assertEquals(31, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(2024, endCal.get(Calendar.YEAR))
    }

    @Test
    fun `calculatePeriodWindow MONTHLY anchor Mar 31 month coercion`() {
        // Anchor: Mar 31
        val anchorCal = Calendar.getInstance()
        anchorCal.set(2024, Calendar.MARCH, 31, 0, 0, 0)
        val anchor = anchorCal.timeInMillis
        
        // Eval: Apr 15
        val evalCal = Calendar.getInstance()
        evalCal.set(2024, Calendar.APRIL, 15, 0, 0, 0)
        
        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchor, evalCal.timeInMillis)
        
        val startCal = Calendar.getInstance().apply { timeInMillis = window.start }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.end }
        
        // Mar 31 -> Apr 30 (April only has 30 days, should coerce)
        assertEquals(31, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MARCH, startCal.get(Calendar.MONTH))
        
        assertEquals(30, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.APRIL, endCal.get(Calendar.MONTH))
    }
}
