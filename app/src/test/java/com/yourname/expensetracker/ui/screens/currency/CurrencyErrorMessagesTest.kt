package com.yourname.expensetracker.ui.screens.currency

import com.yourname.expensetracker.R
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyErrorMessagesTest {

    @Test
    fun `known codes map to their dedicated string resources`() {
        assertEquals(
            R.string.currency_error_download_disabled,
            currencyErrorMessageRes("CURRENCY_RATE_DOWNLOAD_DISABLED")
        )
        assertEquals(
            R.string.currency_error_refresh_unavailable,
            currencyErrorMessageRes("CURRENCY_RATE_REFRESH_UNAVAILABLE")
        )
        assertEquals(
            R.string.currency_error_conversion_unavailable,
            currencyErrorMessageRes("CURRENCY_CONVERSION_UNAVAILABLE")
        )
        assertEquals(
            R.string.currency_error_data_unavailable,
            currencyErrorMessageRes("CURRENCY_DATA_UNAVAILABLE")
        )
        assertEquals(
            R.string.currency_error_unsupported_home,
            currencyErrorMessageRes("UNSUPPORTED_HOME_CURRENCY")
        )
        assertEquals(
            R.string.currency_home_switch_error_write_failed,
            currencyErrorMessageRes("HOME_SWITCH_WRITE_FAILED")
        )
    }

    @Test
    fun `unknown codes fall back to data unavailable so raw codes are never shown`() {
        assertEquals(R.string.currency_error_data_unavailable, currencyErrorMessageRes("SOMETHING_ELSE"))
        assertEquals(R.string.currency_error_data_unavailable, currencyErrorMessageRes(""))
    }
}
