package com.yourname.expensetracker.ui.screens.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedExpenseGroupsScreenStateTest {

    @Test
    fun `isSettledBalance treats near-zero values as settled at currency precision`() {
        assertTrue(isSettledBalance(0.004, fractionDigits = 2))
        assertTrue(isSettledBalance(-0.004, fractionDigits = 2))
        assertFalse(isSettledBalance(0.005, fractionDigits = 2))
        assertFalse(isSettledBalance(-0.005, fractionDigits = 2))
    }

    @Test
    fun `roundedBalanceForDisplay rounds to requested fraction digits`() {
        assertEquals(1.24, roundedBalanceForDisplay(1.235, fractionDigits = 2), 0.0)
        assertEquals(-1.24, roundedBalanceForDisplay(-1.235, fractionDigits = 2), 0.0)
        assertEquals(0.01, roundedBalanceForDisplay(0.005, fractionDigits = 2), 0.0)
        assertEquals(-0.01, roundedBalanceForDisplay(-0.005, fractionDigits = 2), 0.0)
        assertEquals(10.0, roundedBalanceForDisplay(9.6, fractionDigits = 0), 0.0)
    }
}
