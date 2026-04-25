package com.yourname.expensetracker.ui.screens.currency

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyManagementScreenValidationTest {

    @Test
    fun `valid positive amount is parseable and positive`() {
        assertTrue(isAmountParseableAndPositive("100"))
        assertTrue(isAmountParseableAndPositive("0.01"))
        assertTrue(isAmountParseableAndPositive("999999.99"))
    }

    @Test
    fun `blank non-numeric zero negative amounts fail`() {
        assertFalse(isAmountParseableAndPositive(""))
        assertFalse(isAmountParseableAndPositive("abc"))
        assertFalse(isAmountParseableAndPositive("--"))
        assertFalse(isAmountParseableAndPositive("0"))
        assertFalse(isAmountParseableAndPositive("-5"))
        assertFalse(isAmountParseableAndPositive("0.0"))
    }

    @Test
    fun `conversion input valid only for positive amount and different currencies`() {
        assertTrue(isConversionInputValid("50.00", "EUR", "USD"))
        assertFalse(isConversionInputValid("", "EUR", "USD"))
        assertFalse(isConversionInputValid("0", "EUR", "USD"))
        assertFalse(isConversionInputValid("50.00", "EUR", "EUR"))
        assertFalse(isConversionInputValid("abc", "EUR", "USD"))
    }

    @Test
    fun `error shown only after interaction with invalid input`() {
        assertFalse(shouldShowAmountError("", interacted = false))
        assertTrue(shouldShowAmountError("", interacted = true))
        assertTrue(shouldShowAmountError("abc", interacted = true))
        assertFalse(shouldShowAmountError("50.00", interacted = true))
        assertFalse(shouldShowAmountError("0", interacted = false))
        assertTrue(shouldShowAmountError("0", interacted = true))
    }
}
