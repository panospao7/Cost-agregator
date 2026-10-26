package com.yourname.expensetracker.data.rescue

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** AE-7 (e): Rescue restore writes identity only for home-currency rows, sentinel otherwise (INV-20). */
class FinancialRescueSnapshotFieldsTest {

    private val sentinel = RescueSnapshotFields(baseAmount = 0.0, baseCurrency = "", exchangeRateUsed = 0.0)

    @Test
    fun `home null gives sentinel for every row`() {
        assertEquals(sentinel, rescueSnapshotFields("USD", 20.0, null))
        assertEquals(sentinel, rescueSnapshotFields("EUR", 10.0, null))
    }

    @Test
    fun `home equal to row currency gives identity`() {
        val fields = rescueSnapshotFields("EUR", 10.0, CurrencyCode.EUR)
        assertEquals(RescueSnapshotFields(baseAmount = 10.0, baseCurrency = "EUR", exchangeRateUsed = 1.0), fields)
    }

    @Test
    fun `home different from row currency gives sentinel and never a foreign snapshot`() {
        assertEquals(sentinel, rescueSnapshotFields("USD", 20.0, CurrencyCode.EUR))
    }

    @Test
    fun `mixed rows with home EUR`() {
        val usd = rescueSnapshotFields("USD", 20.0, CurrencyCode.EUR)
        val eur = rescueSnapshotFields("EUR", 10.0, CurrencyCode.EUR)
        assertEquals(sentinel, usd)
        assertEquals(10.0, eur.baseAmount, 0.0)
        assertEquals("EUR", eur.baseCurrency)
        assertEquals(1.0, eur.exchangeRateUsed, 0.0)
    }
}
