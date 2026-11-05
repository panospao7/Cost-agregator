package com.yourname.expensetracker.ui.util

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountCurrencyCheckTest {

    @Test
    fun `JPY fractional amount fails precision with no-decimals message`() {
        assertEquals(
            UiText.StringResource(R.string.mec_amount_no_decimals, listOf("JPY")),
            AmountCurrencyCheck.check(750.5, "JPY")
        )
    }

    @Test
    fun `JPY whole amount passes`() {
        assertNull(AmountCurrencyCheck.check(750.0, "JPY"))
    }

    @Test
    fun `EUR two decimals passes`() {
        assertNull(AmountCurrencyCheck.check(10.55, "EUR"))
    }

    @Test
    fun `EUR three decimals fails precision with max-decimals message`() {
        assertEquals(
            UiText.StringResource(R.string.mec_amount_max_decimals, listOf("EUR", 2)),
            AmountCurrencyCheck.check(10.555, "EUR")
        )
    }
}
