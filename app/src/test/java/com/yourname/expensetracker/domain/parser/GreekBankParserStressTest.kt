package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
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
        merchantCleaner = MerchantCleaner()
    )

    @Test
    fun `parses purchase notification`() {
        val result = parse("Αγορά €50.00 στο Starbucks με κάρτα *****1234")
        assertNotNull(result)
        assertEquals(TransactionType.PURCHASE, result!!.type)
        assertEquals(50.0, result.amount, 0.001)
    }

    @Test
    fun `parses greek deposit notification`() {
        val result = parse("Κατάθεση €500.00 στον λογαριασμό σας")
        assertNotNull(result)
        assertEquals(TransactionType.DEPOSIT, result!!.type)
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
