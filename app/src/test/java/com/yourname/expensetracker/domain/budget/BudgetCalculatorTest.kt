package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class BudgetCalculatorTest {

    private val calculator = BudgetCalculator()

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
        assertEquals(7 * 24 * 60 * 60 * 1000L, length) // Ignoring DST for simplicity in this localized test context
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
}
