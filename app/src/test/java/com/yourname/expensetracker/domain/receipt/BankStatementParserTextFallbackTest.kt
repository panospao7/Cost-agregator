package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Statement-import S3: the text-PDF fallback asks the generic parser to drop date-less rows
 * (requireDate = true), while the default keeps the legacy behavior. Fixtures are synthetic.
 */
class BankStatementParserTextFallbackTest {
    private lateinit var parser: BankStatementParser

    @Before
    fun setup() {
        val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
            io.mockk.every { clean(any()) } answers { firstArg() }
        }
        val timeProvider = io.mockk.mockk<com.yourname.expensetracker.domain.util.TimeProvider> {
            io.mockk.every { now() } returns System.currentTimeMillis()
        }
        val currencySettingsRepository =
            io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository> {
                io.mockk.every { homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")
                io.mockk.coEvery { resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
            }
        parser = BankStatementParser(
            com.yourname.expensetracker.domain.util.CurrencyNormalizer(),
            merchantCleaner,
            timeProvider,
            currencySettingsRepository
        )
    }

    private fun line(text: String, top: Int = 100): List<TextBlock> =
        listOf(TextBlock(text, null, 10, top, 400, top + 20))

    @Test
    fun `requireDate true drops a row without a parsed date`() {
        val result = parser.parse(line("10/05 SHOP -12.50 USD"), "EUR", requireDate = true)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `default keeps the date-less row exactly as before`() {
        val defaulted = parser.parse(line("10/05 SHOP -12.50 USD"), "EUR").single()
        val explicitFalse = parser.parse(line("10/05 SHOP -12.50 USD"), "EUR", requireDate = false).single()

        assertNull(defaulted.date)
        assertEquals(12.50, defaulted.amount, 0.001)
        assertEquals("USD", defaulted.currency)
        assertEquals(defaulted.amount, explicitFalse.amount, 0.0)
        assertNull(explicitFalse.date)
    }

    @Test
    fun `requireDate true keeps rows that have a date and drops the others in one parse`() {
        val blocks = line("15/03/2025 ACME LTD -12,50", top = 100) +
            line("10/05 SHOP -12.50 USD", top = 140)

        val result = parser.parse(blocks, "EUR", requireDate = true)

        assertEquals(1, result.size)
        assertNotNull(result[0].date)
        assertEquals(12.50, result[0].amount, 0.001)
    }

    @Test
    fun `oversized revolut amount returns no row and does not throw`() {
        val blocks = listOf(
            TextBlock("Revolut statement", null, 0, 60, 220, 80),
            TextBlock("Jul 5, 2023", null, 0, 100, 120, 120),
            TextBlock("ACME LTD", null, 130, 100, 400, 120),
            TextBlock("€2,000,000.00", null, 410, 100, 520, 120),
            TextBlock("€5.00", null, 780, 100, 900, 120)
        )

        // parse() must return normally (an exception would fail the test) and drop the only row.
        val result = parser.parse(blocks, "EUR")

        assertTrue("the oversized revolut row must not become a transaction", result.isEmpty())
    }

    @Test
    fun `oversized revolut row does not abort the remaining rows`() {
        val blocks = listOf(
            TextBlock("Revolut statement", null, 0, 20, 220, 40),
            TextBlock("Jul 5, 2023", null, 0, 100, 120, 120),
            TextBlock("ACME LTD", null, 130, 100, 400, 120),
            TextBlock("€2,000,000.00", null, 410, 100, 520, 120),
            TextBlock("€5.00", null, 780, 100, 900, 120),
            TextBlock("Jul 6, 2023", null, 0, 140, 120, 160),
            TextBlock("SOME SHOP", null, 130, 140, 400, 160),
            TextBlock("€4.00", null, 410, 140, 520, 160),
            TextBlock("€1.00", null, 780, 140, 900, 160)
        )

        val result = parser.parse(blocks, "EUR")

        assertTrue(result.none { it.amount > 1_000_000.0 })
        assertTrue(result.any { kotlin.math.abs(it.amount - 4.0) < 0.001 })
    }
}
