package com.yourname.expensetracker.ui.screens.currency

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test
    fun `el_GR comma decimal is valid and shows no error`() {
        withLocale(Locale.forLanguageTag("el-GR")) {
            assertTrue(isAmountParseableAndPositive("12,50"))
            assertTrue(isAmountParseableAndPositive("12.5"))
            assertTrue(isAmountParseableAndPositive("1.234,56"))
            assertTrue(isConversionInputValid("12,50", "EUR", "USD"))
            assertFalse(shouldShowAmountError("12,50", interacted = true))
            assertFalse("ambiguous comma grouping is rejected", isAmountParseableAndPositive("1,234"))
            assertFalse(isAmountParseableAndPositive("0,00"))
        }
    }

    @Test
    fun `en_US dot decimal and comma decimal are valid`() {
        withLocale(Locale.US) {
            assertTrue(isAmountParseableAndPositive("12.50"))
            assertTrue(isAmountParseableAndPositive("12,50"))
            assertTrue(isAmountParseableAndPositive("1,234.56"))
            assertTrue(isAmountParseableAndPositive("1,234"))
            assertFalse("ambiguous dot grouping is rejected", isAmountParseableAndPositive("1.234"))
            assertTrue(shouldShowAmountError("1.234", interacted = true))
        }
    }

    @Test
    fun `home card shows a status message while Loading or Unavailable and none when Available`() {
        // The switch picker is usable in every state (warning shown while the home is unknown);
        // the card only differs in its status text, never showing a placeholder currency.
        assertNull(homeStateStatusMessageRes(ReadHomeCurrencyState.Available(CurrencyCode.EUR, firstRunDefault = false)))
        assertNull(homeStateStatusMessageRes(ReadHomeCurrencyState.Available(CurrencyCode.EUR, firstRunDefault = true)))
        assertEquals(R.string.currency_home_switch_loading, homeStateStatusMessageRes(ReadHomeCurrencyState.Loading))
        ReadHomeFailure.values().forEach { failure ->
            assertEquals(
                R.string.currency_home_switch_unavailable,
                homeStateStatusMessageRes(ReadHomeCurrencyState.Unavailable(failure))
            )
        }
    }

    @Test
    fun `predicted count line is shown only for a known positive count`() {
        assertEquals(3, homeSwitchCountToShow(3))
        assertEquals(1, homeSwitchCountToShow(1))
        assertNull("zero means nothing to warn about", homeSwitchCountToShow(0))
        assertNull("unknown count is omitted, never shown as zero", homeSwitchCountToShow(null))
    }

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(original)
        }
    }
}
