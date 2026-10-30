package com.yourname.expensetracker.ui.screens.currency

import com.yourname.expensetracker.data.database.entity.ExchangeRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyManagementLatestPerSourceTest {

    private fun row(
        from: String,
        validDate: Long,
        lastUpdated: Long,
        rate: Double = 1.0,
        id: Long = 0L
    ) = ExchangeRate(
        id = id,
        fromCurrency = from,
        toCurrency = "EUR",
        rate = rate,
        lastUpdated = lastUpdated,
        source = "ecb",
        validDate = validDate
    )

    @Test
    fun `empty input yields empty output`() {
        assertTrue(latestPerSource(emptyList()).isEmpty())
    }

    @Test
    fun `single row per currency is kept unchanged`() {
        val rows = listOf(row("USD", 100L, 10L, id = 1), row("GBP", 100L, 10L, id = 2))

        assertEquals(rows, latestPerSource(rows))
    }

    @Test
    fun `newest validDate wins regardless of input order`() {
        val older = row("USD", validDate = 100L, lastUpdated = 999L, id = 1)
        val newer = row("USD", validDate = 200L, lastUpdated = 1L, id = 2)

        assertEquals(listOf(newer), latestPerSource(listOf(older, newer)))
        assertEquals(listOf(newer), latestPerSource(listOf(newer, older)))
    }

    @Test
    fun `equal validDate is broken by larger lastUpdated`() {
        val stale = row("USD", validDate = 200L, lastUpdated = 5L, id = 1)
        val fresh = row("USD", validDate = 200L, lastUpdated = 9L, id = 2)

        assertEquals(listOf(fresh), latestPerSource(listOf(stale, fresh)))
        assertEquals(listOf(fresh), latestPerSource(listOf(fresh, stale)))
    }

    @Test
    fun `a full tie keeps the first seen row`() {
        val first = row("USD", validDate = 200L, lastUpdated = 5L, rate = 1.1, id = 1)
        val second = row("USD", validDate = 200L, lastUpdated = 5L, rate = 1.2, id = 2)

        assertEquals(listOf(first), latestPerSource(listOf(first, second)))
    }

    @Test
    fun `currencies keep first appearance order while each keeps its own newest row`() {
        val usdOld = row("USD", 100L, 1L, id = 1)
        val gbp = row("GBP", 150L, 1L, id = 2)
        val usdNew = row("USD", 300L, 1L, id = 3)
        val jpyOld = row("JPY", 50L, 1L, id = 4)
        val jpyNew = row("JPY", 60L, 1L, id = 5)

        val result = latestPerSource(listOf(usdOld, gbp, usdNew, jpyOld, jpyNew))

        assertEquals(listOf("USD", "GBP", "JPY"), result.map { it.fromCurrency })
        assertEquals(listOf(usdNew, gbp, jpyNew), result)
    }
}
