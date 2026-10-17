package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * R22b regressions: statement sign/type rules, single-match dates, statement-wide
 * day/month order and one-transaction-per-row. Fixtures are synthetic.
 */
class BankStatementParserSignDateTest {
    private lateinit var parser: BankStatementParser

    /** Injected clock; tests whose dates depend on "now" pin it before calling parse(). */
    private var clockMs: Long = System.currentTimeMillis()

    @Before
    fun setup() {
        clockMs = System.currentTimeMillis()
        val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
            io.mockk.every { clean(any()) } answers { firstArg() }
        }
        val timeProvider = io.mockk.mockk<com.yourname.expensetracker.domain.util.TimeProvider> {
            io.mockk.every { now() } answers { clockMs }
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

    private fun millis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun header(): List<TextBlock> = listOf(
        TextBlock("DATE", null, 10, 10, 80, 30),
        TextBlock("DESCRIPTION", null, 100, 10, 250, 30),
        TextBlock("AMOUNT", null, 300, 10, 400, 30),
        TextBlock("BALANCE", null, 450, 10, 550, 30)
    )

    private fun dataRow(
        date: String,
        description: String,
        amount: String,
        yTop: Int = 50,
        balance: String = "9.250,00"
    ): List<TextBlock> = listOf(
        TextBlock(date, null, 10, yTop, 90, yTop + 20),
        TextBlock(description, null, 100, yTop, 250, yTop + 20),
        TextBlock(amount, null, 300, yTop, 400, yTop + 20),
        TextBlock(balance, null, 450, yTop, 550, yTop + 20)
    )

    private fun parseSingle(description: String, amount: String): ParsedTransaction {
        val results = parser.parse(header() + dataRow("15/03/2025", description, amount), "EUR")
        assertEquals(1, results.size)
        return results[0]
    }

    @Test
    fun `plus signed amount is a deposit`() {
        val tx = parseSingle("ACME LTD", "+1.500,00")
        assertEquals(ParsedTransactionType.DEPOSIT, tx.type)
        assertEquals(1500.0, tx.amount, 0.001)
    }

    @Test
    fun `plus signed en style amount is a deposit`() {
        val tx = parseSingle("ACME LTD", "+1,500.00")
        assertEquals(ParsedTransactionType.DEPOSIT, tx.type)
        assertEquals(1500.0, tx.amount, 0.001)
    }

    @Test
    fun `plus signed grouped amount wins over the balance column`() {
        val results = parser.parse(
            header() + dataRow("15/03/2025", "ACME LTD", "+1.500,00", balance = "12.750,00"),
            "EUR"
        )
        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.DEPOSIT, results[0].type)
        assertEquals(1500.0, results[0].amount, 0.001)
    }

    @Test
    fun `refund with minus amount stays a deposit`() {
        val tx = parseSingle("REFUND SHOP", "-45,00")
        assertEquals(ParsedTransactionType.DEPOSIT, tx.type)
        assertEquals(45.0, tx.amount, 0.001)
    }

    @Test
    fun `credit card instrument name is not a deposit keyword`() {
        // Through parse(): the dated row must also survive the header-keyword pre-filter.
        val tx = parseSingle("VISA CREDIT CARD", "45,00")
        assertEquals(ParsedTransactionType.PURCHASE, tx.type)
        assertEquals(45.0, tx.amount, 0.001)
    }

    @Test
    fun `credit transfer row survives the header keyword filter as a deposit`() {
        val tx = parseSingle("CREDIT TRANSFER", "+500,00")
        assertEquals(ParsedTransactionType.DEPOSIT, tx.type)
        assertEquals(500.0, tx.amount, 0.001)
    }

    @Test
    fun `direct debit row survives the header keyword filter`() {
        val tx = parseSingle("DIRECT DEBIT UTILITY", "-30,00")
        assertEquals(30.0, tx.amount, 0.001)
    }

    @Test
    fun `total credit summary row is still dropped`() {
        val results = parser.parse(header() + dataRow("31/03/2025", "TOTAL CREDIT", "500,00"), "EUR")
        assertEquals(0, results.size)
    }

    @Test
    fun `merchant names embedding a header keyword survive on dated amount rows`() {
        val cases = listOf(
            Triple("TOTALENERGIES ATHINA", "45,00", 45.0),
            Triple("SEPA MANDATE FEE", "2,50", 2.5),
            Triple("ΠΛΗΡΩΜΗ ΚΑΡΤΑΣ ΣΚΛΑΒΕΝΙΤΗΣ", "23,40", 23.4),
            Triple("ΜΕΤΑΦΟΡΑ ΣΕ EUROBANK", "100,00", 100.0),
            Triple("ΜΕΤΑΦΟΡΑ ΣΕ ALPHA BANK", "50,00", 50.0),
            Triple("ΜΕΤΑΦΟΡΑ ΣΕ ΠΕΙΡΑΙΩΣ", "60,00", 60.0),
            Triple("ACCOUNT MAINTENANCE", "5,00", 5.0),
            Triple("PAGE INDUSTRIES", "12,00", 12.0)
        )
        for ((description, amount, expected) in cases) {
            val results = parser.parse(header() + dataRow("31/03/2025", description, amount), "EUR")
            assertEquals(description, 1, results.size)
            assertEquals(description, expected, results[0].amount, 0.001)
            assertEquals(description, ParsedTransactionType.PURCHASE, results[0].type)
        }
    }

    @Test
    fun `whole word summary rows on dated amount rows are still dropped`() {
        for (description in listOf("TOTAL", "SUBTOTAL", "BALANCE", "Υπόλοιπο", "ΥΠΟΛΟΙΠΟ", "ΣΥΝΟΛΟ", "Σύνολο")) {
            val results = parser.parse(header() + dataRow("31/03/2025", description, "1.234,56"), "EUR")
            assertEquals(description, 0, results.size)
        }
    }

    @Test
    fun `card statement limit due and print date rows are dropped`() {
        clockMs = LocalDate.of(2025, 6, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val lines = listOf(
            "Statement date 05/03/2025 Credit limit 2,000.00",
            "Payment due date 30/03/2025 Minimum payment 25.00",
            "Available credit 31/03/2025 1.500,00",
            "ΠΙΣΤΩΤΙΚΟ ΟΡΙΟ ΚΑΡΤΑΣ 31/03/2025 2.000,00",
            "Ελάχιστο ποσό πληρωμής 25,00 έως 25/04/2025",
            "Ημ/νία Εκτύπωσης 15/03/2025 10.45"
        )
        for (line in lines) {
            val results = parser.parse(header() + TextBlock(line, null, 10, 50, 600, 70), "EUR")
            assertEquals(line, 0, results.size)
        }
    }

    @Test
    fun `undated header line containing a card keyword is still dropped`() {
        val blocks = header() + listOf(TextBlock("ΑΡΙΘΜΟΣ ΚΑΡΤΑΣ ΠΕΛΑΤΗ 45,00", null, 10, 50, 400, 70))
        assertEquals(0, parser.parse(blocks, "EUR").size)
    }

    @Test
    fun `debit credit column header row is still dropped`() {
        val columns = listOf(
            TextBlock("DATE", null, 10, 10, 80, 30),
            TextBlock("DESCRIPTION", null, 100, 10, 250, 30),
            TextBlock("DEBIT", null, 300, 10, 350, 30),
            TextBlock("CREDIT", null, 360, 10, 410, 30),
            TextBlock("BALANCE", null, 450, 10, 550, 30)
        )
        assertEquals(0, parser.parse(columns, "EUR").size)
        val results = parser.parse(columns + dataRow("15/03/2025", "ACME LTD", "-10,00"), "EUR")
        assertEquals(1, results.size)
    }

    @Test
    fun `dotted time token does not flip a day first statement to month first`() {
        val blocks = header() +
            dataRow("03/04/2025", "ACME LTD", "-10,00", yTop = 50) +
            dataRow("05/06/2025", "BOLT SHOP REF 09.45.30", "-20,00", yTop = 90)
        val results = parser.parse(blocks, "EUR")
        assertEquals(2, results.size)
        assertEquals(millis(2025, 4, 3), results[0].date)
        assertEquals(millis(2025, 6, 5), results[1].date)
    }

    @Test
    fun `date later than tomorrow does not fail the import`() {
        // Clock pinned to mid-year local noon so the skip never depends on the year bound.
        clockMs = LocalDate.of(2025, 6, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val results = parser.parse(header() + dataRow("17/06/2025", "ACME LTD", "-10,00"), "EUR")
        assertEquals(1, results.size)
        assertNull(results[0].date)
    }

    @Test
    fun `tomorrow date within the same year is kept`() {
        clockMs = LocalDate.of(2025, 6, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val results = parser.parse(header() + dataRow("16/06/2025", "ACME LTD", "-10,00"), "EUR")
        assertEquals(1, results.size)
        assertEquals(millis(2025, 6, 16), results[0].date)
    }

    @Test
    fun `second column transaction date with a single four digit year date reads that date`() {
        val blocks = listOf(
            TextBlock("VALUE DATE", null, 10, 10, 120, 30),
            TextBlock("TRANSACTION DATE", null, 140, 10, 320, 30),
            TextBlock("DESCRIPTION", null, 340, 10, 460, 30),
            TextBlock("AMOUNT", null, 480, 10, 560, 30),
            TextBlock("15/03/2025", null, 140, 50, 260, 70),
            TextBlock("SUPERMARKET", null, 340, 50, 460, 70),
            TextBlock("-32,00 EUR", null, 480, 50, 560, 70)
        )
        val results = parser.parse(blocks, "EUR")
        assertEquals(1, results.size)
        assertEquals(millis(2025, 3, 15), results[0].date)
    }

    @Test
    fun `out of range four digit year is not re-read as a two digit year`() {
        val results = parser.parse(header() + dataRow("15/03/1999", "ACME LTD", "-10,00"), "EUR")
        assertEquals(1, results.size)
        assertNull(results[0].date)
    }

    @Test
    fun `us statement with a day above twelve in second position reads month first`() {
        val blocks = header() +
            dataRow("03/15/2025", "ACME LTD", "-10,00", yTop = 50) +
            dataRow("03/04/2025", "BOLT SHOP", "-20,00", yTop = 90)
        val results = parser.parse(blocks, "EUR")
        assertEquals(2, results.size)
        assertEquals(millis(2025, 3, 15), results[0].date)
        assertEquals(millis(2025, 3, 4), results[1].date)
    }

    @Test
    fun `eu statement with a day above twelve in first position reads day first`() {
        val blocks = header() +
            dataRow("15/03/2025", "ACME LTD", "-10,00", yTop = 50) +
            dataRow("03/04/2025", "BOLT SHOP", "-20,00", yTop = 90)
        val results = parser.parse(blocks, "EUR")
        assertEquals(2, results.size)
        assertEquals(millis(2025, 3, 15), results[0].date)
        assertEquals(millis(2025, 4, 3), results[1].date)
    }

    @Test
    fun `ambiguous statement defaults to day first`() {
        val results = parser.parse(header() + dataRow("03/04/2025", "ACME LTD", "-10,00"), "EUR")
        assertEquals(1, results.size)
        assertNotNull(results[0].date)
        assertEquals(millis(2025, 4, 3), results[0].date)
    }

    @Test
    fun `nbg format row without a bank keyword yields exactly one transaction`() {
        val blocks = listOf(
            TextBlock(
                "15/03/2025 10:30:00 17/03/2025 705 040 SKLAVENITIS ΜΑΡΚΟΠΟΥΛΟ Χ 12,50",
                null, 10, 100, 600, 120
            )
        )
        val results = parser.parse(blocks, "EUR")
        assertEquals(1, results.size)
        assertTrue(results[0].amount > 0.0)
    }
}
