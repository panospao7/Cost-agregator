package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BankStatementParserTest {
    private lateinit var parser: BankStatementParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    private lateinit var timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    private lateinit var currencySettingsRepository: com.yourname.expensetracker.domain.currency.CurrencySettingsRepository

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } returns "EUR"
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() }
        }
        timeProvider = io.mockk.mockk {
            io.mockk.every { now() } returns System.currentTimeMillis()
        }
        val homeCurrencyFlow = io.mockk.mockk<kotlinx.coroutines.flow.Flow<String>>(relaxed = true)
        io.mockk.coEvery { homeCurrencyFlow.first() } returns "EUR"
        currencySettingsRepository = io.mockk.mockk {
            io.mockk.every { homeCurrency() } returns homeCurrencyFlow
            io.mockk.coEvery { resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        parser = BankStatementParser(currencyNormalizer, merchantCleaner, timeProvider, currencySettingsRepository)
    }

    @Test
    fun `parse multiple transactions from spatial blocks`() {
        // Mock a bank statement screenshot logic
        // Row 1: 10/05 SKLAVENITIS -12,50 EUR
        val blocks = listOf(
            TextBlock("10/05", null, 10, 100, 50, 120),
            TextBlock("SKLAVENITIS", null, 60, 100, 200, 120),
            TextBlock("-12,50", null, 250, 100, 300, 120),
            TextBlock("EUR", null, 310, 100, 350, 120),
            
            // Row 2: 11/05 LIDL -25,00 EUR
            TextBlock("11/05", null, 10, 150, 50, 170),
            TextBlock("LIDL", null, 60, 150, 200, 170),
            TextBlock("-25,00", null, 250, 150, 300, 170),
            TextBlock("EUR", null, 310, 150, 350, 170),
            
            // Row 3: 12/05 SALARY +1500,00 EUR
            TextBlock("12/05", null, 10, 200, 50, 220),
            TextBlock("SALARY", null, 60, 200, 200, 220),
            TextBlock("1500,00", null, 250, 200, 300, 220), // Note: + often missed by OCR or represented by absence of -
            TextBlock("EUR", null, 310, 200, 350, 220)
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(3, results.size)
        
        // Check first transaction
        assertEquals(12.50, results[0].amount, 0.01)
        assertEquals("SKLAVENITIS", results[0].merchant)
        assertEquals(ParsedTransactionType.PURCHASE, results[0].type)
        
        // Check second transaction
        assertEquals(25.0, results[1].amount, 0.01)
        assertEquals("LIDL", results[1].merchant)
        assertEquals(ParsedTransactionType.PURCHASE, results[1].type)
        
        // Check third transaction
        assertEquals(1500.0, results[2].amount, 0.01)
        assertEquals("SALARY", results[2].merchant)
        assertEquals(ParsedTransactionType.DEPOSIT, results[2].type)
    }

    @Test
    fun `group blocks into rows correctly even with slight vertical variation`() {
        val blocks = listOf(
            TextBlock("Row1-Left", null, 10, 100, 50, 120),
            TextBlock("Row1-Right", null, 100, 105, 150, 125), // 5px offset
            
            TextBlock("Row2-Left", null, 10, 200, 50, 220),
            TextBlock("Row2-Right", null, 100, 195, 150, 215) // -5px offset
        )

        // Internal method test via parse call and checking result size (simplified)
        // Since groupBlocksIntoRows is private, we check the effect. 
        // We'll need a helper or just trust the logic if it passes the main test.
        // Actually I'll just check if it extracts 2 transactions if I give it proper amounts.
        
        val blocksWithAmounts = listOf(
            TextBlock("Merchant1", null, 10, 100, 50, 120),
            TextBlock("-10,00 EUR", null, 100, 105, 150, 125),
            
            TextBlock("Merchant2", null, 10, 200, 50, 220),
            TextBlock("-20,00 EUR", null, 100, 195, 150, 215)
        )
        
        val results = parser.parse(blocksWithAmounts, "EUR")
        assertEquals(2, results.size)
        assertEquals("Merchant1", results[0].merchant)
        assertEquals("Merchant2", results[1].merchant)
    }

    // -----------------------------------------------------------------------
    //  NBG (Greek National Bank) statement parsing
    // -----------------------------------------------------------------------

    @Test
    fun `nbg transaction row with debit marker is parsed correctly`() {
        // Simulates a typical NBG statement row:
        //   15/03/2025 10:30:00 17/03/2025 705 040 SKLAVENITIS ΜΑΡΚΟΠΟΥΛΟ Χ 12,50
        //
        // Format: DATE TIME VALUE_DATE STORE_CODE TX_CODE MERCHANT Χ/Π AMOUNT
        //   Χ = ΧΡΕΩΣΗ (debit/purchase), Π = ΠΙΣΤΩΣΗ (credit/deposit)
        val blocks = listOf(
            TextBlock(
                "15/03/2025 10:30:00 17/03/2025 705 040 SKLAVENITIS ΜΑΡΚΟΠΟΥΛΟ Χ 12,50",
                null, 10, 100, 600, 120
            )
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals("Expected 1 transaction", 1, results.size)
        assertEquals("SKLAVENITIS ΜΑΡΚΟΠΟΥΛΟ", results[0].merchant)
        assertEquals(12.50, results[0].amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, results[0].type)
        assertNotNull("Transaction should have a date", results[0].date)
    }

    @Test
    fun `nbg transaction row with credit marker is parsed as DEPOSIT`() {
        // Π = ΠΙΣΤΩΣΗ (credit/transfer)
        val blocks = listOf(
            TextBlock(
                "20/03/2025 08:15:00 22/03/2025 710 042 ΜΙΣΘΟΔΟΣΙΑ ΕΤΑΙΡΕΙΑ Π 1.500,00",
                null, 10, 100, 600, 120
            )
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals("Expected 1 transaction", 1, results.size)
        assertEquals("ΜΙΣΘΟΔΟΣΙΑ ΕΤΑΙΡΕΙΑ", results[0].merchant)
        assertEquals(1500.0, results[0].amount, 0.01)
        assertEquals(ParsedTransactionType.DEPOSIT, results[0].type)
    }

    // -----------------------------------------------------------------------
    //  Batch 6B — Regression: transaction amount vs running balance
    // -----------------------------------------------------------------------

    @Test
    fun `generic row with transaction amount and larger running balance selects transaction amount`() {
        // Simulates a typical bank statement row:
        //   Header row:  DATE   DESCRIPTION   AMOUNT   BALANCE
        //   Data row:    15/03/2025  COFFEE SHOP  -4,50 EUR  1.250,00
        //
        // The rightmost numeric column (1.250,00 = 1250.00) is the running
        // balance.  The parser must select -4,50 (= 4.50) as the transaction
        // amount, *not* the larger 1250.00.
        val blocks = listOf(
            // Header row (y: 10–30)
            TextBlock("DATE", null, 10, 10, 80, 30),
            TextBlock("DESCRIPTION", null, 100, 10, 250, 30),
            TextBlock("AMOUNT", null, 300, 10, 400, 30),
            TextBlock("BALANCE", null, 450, 10, 550, 30),

            // Data row (y: 50–70)
            TextBlock("15/03/2025", null, 10, 50, 90, 70),
            TextBlock("COFFEE SHOP", null, 100, 50, 250, 70),
            TextBlock("-4,50 EUR", null, 300, 50, 400, 70),
            TextBlock("1.250,00", null, 450, 50, 550, 70)
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals("Expected exactly 1 transaction", 1, results.size)
        assertEquals(
            "Transaction amount should be 4.50, not the running balance",
            4.50,
            results[0].amount,
            0.01
        )
    }

    // -----------------------------------------------------------------------
    //  Batch 6B — Regression: header-derived date order
    // -----------------------------------------------------------------------

    @Test
    fun `header keyword order determines which date column is the transaction date`() {
        // Simulates a statement where the VALUE DATE column appears *before*
        // the TRANSACTION DATE column in the header.
        //
        //   Header:  VALUE DATE   TRANSACTION DATE   DESCRIPTION   AMOUNT
        //   Data:    18/03/2025   15/03/2025         SUPERMARKET   -32,00 EUR
        //
        // With header-derived order = SECOND (transaction date is the second
        // date column), the parser should pick the second date (15/03/2025)
        // as the transaction date.

        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
        val expectedDate = LocalDate.parse("15/03/2025", dateFormatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()  // transaction date
        val valueDateMillis = LocalDate.parse("18/03/2025", dateFormatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val blocks = listOf(
            // Header row (y: 10–30) — VALUE DATE appears first
            TextBlock("VALUE DATE", null, 10, 10, 120, 30),
            TextBlock("TRANSACTION DATE", null, 140, 10, 320, 30),
            TextBlock("DESCRIPTION", null, 340, 10, 460, 30),
            TextBlock("AMOUNT", null, 480, 10, 560, 30),

            // Data row (y: 50–70) — value date first, then transaction date
            TextBlock("18/03/2025", null, 10, 50, 120, 70),
            TextBlock("15/03/2025", null, 140, 50, 260, 70),
            TextBlock("SUPERMARKET", null, 340, 50, 460, 70),
            TextBlock("-32,00 EUR", null, 480, 50, 560, 70)
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals("Expected exactly 1 transaction", 1, results.size)
        assertNotNull("Transaction should have a date", results[0].date)
        assertEquals(
            "Transaction date should be the second date column (15/03/2025), " +
                    "not the value date (18/03/2025)",
            expectedDate,
            results[0].date!!
        )
        // Also verify the value date was not used
        assertNotEquals(
            "Must not pick the value date",
            valueDateMillis,
            results[0].date!!
        )
    }

    // -----------------------------------------------------------------------
    //  Batch 6B — Revolut statement: locale-safe amount parsing via AmountUtils
    // -----------------------------------------------------------------------

    /**
     * Helper that builds a Revolut-style row with spatial blocks.
     *
     * Layout (left-to-right, all on the same y-band):
     *   Date (0-120)  |  Description (130-400)  |  MoneyOut (410-520) or MoneyIn (540-650)  |  Balance (680-800)
     *
     * [moneyOutStr] and [moneyInStr] — set exactly one; leave the other null.
     */
    private fun revolutRow(
        dateStr: String,
        description: String,
        moneyOutStr: String?,
        moneyInStr: String?,
        balanceStr: String,
        yTop: Int = 100,
        yBottom: Int = 120
    ): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        blocks.add(TextBlock(dateStr, null, 0, yTop, 120, yBottom))
        blocks.add(TextBlock(description, null, 130, yTop, 400, yBottom))
        if (moneyOutStr != null) {
            // Money-out column (60-75% zone)
            blocks.add(TextBlock(moneyOutStr, null, 410, yTop, 520, yBottom))
        }
        if (moneyInStr != null) {
            // Money-in column (>75% zone)
            blocks.add(TextBlock(moneyInStr, null, 640, yTop, 750, yBottom))
        }
        blocks.add(TextBlock(balanceStr, null, 780, yTop, 900, yBottom))
        return blocks
    }

    @Test
    fun `revolut grouped amount with US thousands separator is parsed correctly`() {
        // €1,234.56 — US format (comma = thousands, dot = decimal)
        val blocks = revolutRow(
            dateStr = "Apr 12, 2023",
            description = "Coffee Shop",
            moneyOutStr = "€1,234.56",
            moneyInStr = null,
            balanceStr = "€5,000.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals("Expected 1 transaction", 1, results.size)
        assertEquals(1234.56, results[0].amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, results[0].type)
    }

    @Test
    fun `revolut grouped amount with European thousands separator is parsed correctly`() {
        // €1.234,56 — European format (dot = thousands, comma = decimal)
        val blocks = revolutRow(
            dateStr = "Apr 12, 2023",
            description = "Supermarket",
            moneyOutStr = "€1.234,56",
            moneyInStr = null,
            balanceStr = "€4.000,00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals("Expected 1 transaction", 1, results.size)
        assertEquals(1234.56, results[0].amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, results[0].type)
    }

    // -----------------------------------------------------------------------
    //  Batch 6B — Revolut statement: transaction type classification
    // -----------------------------------------------------------------------

    @Test
    fun `revolut Transfer to row is classified as TRANSFER`() {
        val blocks = revolutRow(
            dateStr = "May 1, 2023",
            description = "Transfer to John Doe",
            moneyOutStr = "€50.00",
            moneyInStr = null,
            balanceStr = "€950.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.TRANSFER, results[0].type)
        assertEquals(50.0, results[0].amount, 0.01)
    }

    @Test
    fun `revolut Transfer from row is classified as TRANSFER`() {
        val blocks = revolutRow(
            dateStr = "May 2, 2023",
            description = "Transfer from Jane Smith",
            moneyOutStr = null,
            moneyInStr = "€120.00",
            balanceStr = "€1,120.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.TRANSFER, results[0].type)
        assertEquals(120.0, results[0].amount, 0.01)
    }

    @Test
    fun `revolut Received from row is classified as TRANSFER`() {
        val blocks = revolutRow(
            dateStr = "May 3, 2023",
            description = "Received from Maria",
            moneyOutStr = null,
            moneyInStr = "€75.00",
            balanceStr = "€1,195.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.TRANSFER, results[0].type)
        assertEquals(75.0, results[0].amount, 0.01)
    }

    @Test
    fun `revolut ATM withdrawal row is classified as WITHDRAWAL`() {
        val blocks = revolutRow(
            dateStr = "Jun 10, 2023",
            description = "ATM Euronet Athens",
            moneyOutStr = "€200.00",
            moneyInStr = null,
            balanceStr = "€800.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.WITHDRAWAL, results[0].type)
        assertEquals(200.0, results[0].amount, 0.01)
    }

    @Test
    fun `revolut cash withdrawal row is classified as WITHDRAWAL`() {
        val blocks = revolutRow(
            dateStr = "Jun 11, 2023",
            description = "Cash withdrawal",
            moneyOutStr = "€100.00",
            moneyInStr = null,
            balanceStr = "€700.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.WITHDRAWAL, results[0].type)
        assertEquals(100.0, results[0].amount, 0.01)
    }

    @Test
    fun `revolut Top-up row is classified as DEPOSIT`() {
        val blocks = revolutRow(
            dateStr = "Jul 1, 2023",
            description = "Top-up by *4521",
            moneyOutStr = null,
            moneyInStr = "€500.00",
            balanceStr = "€1,500.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.DEPOSIT, results[0].type)
        assertEquals(500.0, results[0].amount, 0.01)
    }

    @Test
    fun `revolut refund row is classified as DEPOSIT`() {
        val blocks = revolutRow(
            dateStr = "Jul 5, 2023",
            description = "Refund Amazon",
            moneyOutStr = null,
            moneyInStr = "€29.99",
            balanceStr = "€1,529.99"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.DEPOSIT, results[0].type)
        assertEquals(29.99, results[0].amount, 0.01)
    }

    @Test
    fun `revolut promo credit row is classified as DEPOSIT`() {
        val blocks = revolutRow(
            dateStr = "Aug 1, 2023",
            description = "Promo reward",
            moneyOutStr = null,
            moneyInStr = "€10.00",
            balanceStr = "€1,539.99"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.DEPOSIT, results[0].type)
        assertEquals(10.0, results[0].amount, 0.01)
    }

    @Test
    fun `revolut normal merchant spend remains PURCHASE`() {
        val blocks = revolutRow(
            dateStr = "Aug 15, 2023",
            description = "Starbucks Syntagma",
            moneyOutStr = "€4.50",
            moneyInStr = null,
            balanceStr = "€1,535.49"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(ParsedTransactionType.PURCHASE, results[0].type)
        assertEquals(4.50, results[0].amount, 0.01)
    }

    @Test
    fun `revolut GBP amount parsed correctly`() {
        val blocks = revolutRow(
            dateStr = "Sep 1, 2023",
            description = "Tesco London",
            moneyOutStr = "£25.50",
            moneyInStr = null,
            balanceStr = "£1,000.00"
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals(1, results.size)
        assertEquals(25.50, results[0].amount, 0.01)
        assertEquals("GBP", results[0].currency)
        assertEquals(ParsedTransactionType.PURCHASE, results[0].type)
    }

    @Test
    fun `header with transaction date before value date keeps first date as transaction date`() {
        // The normal case: TRANSACTION DATE appears before VALUE DATE in the header.
        //   Header:  TRANSACTION DATE   VALUE DATE   DESCRIPTION   AMOUNT
        //   Data:    15/03/2025         18/03/2025   PHARMACY      -8,90 EUR
        //
        // Parser should pick the first date column (15/03/2025).

        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
        val expectedDate = LocalDate.parse("15/03/2025", dateFormatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val blocks = listOf(
            // Header row (y: 10–30) — TRANSACTION DATE appears first
            TextBlock("TRANSACTION DATE", null, 10, 10, 180, 30),
            TextBlock("VALUE DATE", null, 200, 10, 320, 30),
            TextBlock("DESCRIPTION", null, 340, 10, 460, 30),
            TextBlock("AMOUNT", null, 480, 10, 560, 30),

            // Data row (y: 50–70)
            TextBlock("15/03/2025", null, 10, 50, 120, 70),
            TextBlock("18/03/2025", null, 200, 50, 320, 70),
            TextBlock("PHARMACY", null, 340, 50, 460, 70),
            TextBlock("-8,90 EUR", null, 480, 50, 560, 70)
        )

        val results = parser.parse(blocks, "EUR")

        assertEquals("Expected exactly 1 transaction", 1, results.size)
        assertNotNull("Transaction should have a date", results[0].date)
        assertEquals(
            "Transaction date should be the first date column (15/03/2025)",
            expectedDate,
            results[0].date!!
        )
    }
}
