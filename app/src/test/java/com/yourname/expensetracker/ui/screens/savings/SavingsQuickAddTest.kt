package com.yourname.expensetracker.ui.screens.savings

import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavingsQuickAddTest {

    private fun goal(target: Double, current: Double, currency: String) = SavingsGoal(
        id = 1L,
        name = "Quick",
        targetAmount = target,
        currentAmount = current,
        targetDate = null,
        protectionLevel = GoalProtectionLevel.WARNING,
        currency = currency,
        currencyAssumption = "EXPLICIT",
        createdAt = 0L,
    )

    @Test
    fun `95 of 100 adds 5_00`() {
        assertEquals("5.00", SavingsQuickAdd.amountOrNull(100.0, 95.0, 2)?.toPlainString())
    }

    @Test
    fun `50 of 100 adds the full step 10_00`() {
        assertEquals("10.00", SavingsQuickAdd.amountOrNull(100.0, 50.0, 2)?.toPlainString())
    }

    @Test
    fun `floating point dust remaining gives none`() {
        assertNull(SavingsQuickAdd.amountOrNull(0.80, 0.7999999999999999, 2))
    }

    @Test
    fun `99_99 of 100 adds one minor unit 0_01`() {
        assertEquals("0.01", SavingsQuickAdd.amountOrNull(100.0, 99.99, 2)?.toPlainString())
    }

    @Test
    fun `at or past target gives none`() {
        assertNull(SavingsQuickAdd.amountOrNull(100.0, 100.0, 2))
        assertNull(SavingsQuickAdd.amountOrNull(100.0, 120.0, 2))
    }

    @Test
    fun `JPY 995 of 1000 adds 5 with no fraction`() {
        val amount = SavingsQuickAdd.amountOrNull(goal(1000.0, 995.0, "JPY"))
        assertEquals("5", amount?.toPlainString())
    }

    @Test
    fun `EUR goal overload matches the digits overload`() {
        assertEquals("5.00", SavingsQuickAdd.amountOrNull(goal(100.0, 95.0, "EUR"))?.toPlainString())
    }

    @Test
    fun `unknown fraction digits gives none`() {
        assertNull(SavingsQuickAdd.amountOrNull(100.0, 95.0, null))
    }

    @Test
    fun `non finite inputs give none`() {
        assertNull(SavingsQuickAdd.amountOrNull(Double.NaN, 0.0, 2))
        assertNull(SavingsQuickAdd.amountOrNull(100.0, Double.POSITIVE_INFINITY, 2))
    }
}
