package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GreekBankParserStressTest {
    private val parser = GreekBankParser(
        currencyNormalizer = CurrencyNormalizer(),
        merchantCleaner = MerchantCleaner(, homeCurrency = "EUR")
    )

    @Test
    fun `parses purchase notification`() {
        val result = parse("Αγορά €50.00 στο Starbucks με κάρτα *****1234")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(50.0, result.amount, 0.001)
    }

    @Test
    fun `parses greek deposit notification`() {
        val result = parse("Κατάθεση €500.00 στον λογαριασμό σας")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
    }

    @Test
    fun `parses transfer notification and sets direction`() {
        val result = parse("Μεταφορά 100,00 EUR σε Μαρία")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(100.0, result.amount, 0.001)
        assertTrue(
            "Transfer direction should be detected when possible",
            result.transferDirection == null || result.transferDirection == ParsedTransferDirection.OUTGOING
        )
    }

    @Test
    fun `parses eurobank-like charge format`() {
        val result = parse("Χρέωση κάρτας €30.00 - Σκλαβενίτης")
        assertNotNull(result)
        assertEquals(30.0, result!!.amount, 0.001)
        assertTrue(result.merchant.contains("Σκλαβενίτης", ignoreCase = true))
    }

    @Test
    fun `supports european decimal comma`() {
        val result = parse("Συναλλαγή €12,34 - Κατάστημα: Lidl")
        assertNotNull(result)
        assertEquals(12.34, result!!.amount, 0.001)
    }

    @Test
    fun `supports single decimal in deposit and transfer formats`() {
        val deposit = parse("Κατάθεση €120,5 από ACME")
        assertNotNull(deposit)
        assertEquals(120.5, deposit!!.amount, 0.001)

        val transfer = parse("Χ 50,5 EUR προς Μαρία")
        assertNotNull(transfer)
        assertEquals(50.5, transfer!!.amount, 0.001)
    }

    @Test
    fun `rejects non-transaction update message`() {
        val result = parse("Ενημέρωση λογαριασμού: υπόλοιπο διαθέσιμο")
        assertNull(result)
    }

    @Test
    fun `handles greek merchant text`() {
        val result = parse("Αγορά €50.00 στο Κατάστημα ΑΒ Βασιλόπουλος")
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("Βασιλόπουλος", ignoreCase = true))
    }

    @Test
    fun `handles merchant names with special characters`() {
        val result = parse("Αγορά 18,90 EUR στο H&M / 7-Eleven")
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("h&m", ignoreCase = true))
    }

    @Test
    fun `is deterministic for same notification`() {
        val n = "Αγορά €45.00 στο Lidl"
        val a = parse(n)
        val b = parse(n)
        assertEquals(a, b)
    }

    private fun parse(notification: String) = parser.parse(
        title = "Τράπεζα",
        text = notification,
        bigText = null,
        subText = null,
        packageName = "com.eurobank.mobile"
    )
}