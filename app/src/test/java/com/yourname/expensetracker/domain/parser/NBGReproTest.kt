package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class NBGReproTest {
    private lateinit var parser: GreekBankParser
    private lateinit var currencyNormalizer: CurrencyNormalizer
    private lateinit var merchantCleaner: MerchantCleaner

    @Before
    fun setup() {
        currencyNormalizer = mockk {
            every { normalize(any()) } answers { firstArg() ?: "EUR" }
        }
        // Real MerchantCleaner to see how it handles the over-matched merchant
        merchantCleaner = MerchantCleaner()
        parser = GreekBankParser(currencyNormalizer, merchantCleaner)
    }

    @Test
    fun `reproduce overmatched merchant for NBG notification`() {
        // User's reported notification
        val title = "💳 Πληρώσατε €10,00 σε Paymonade"
        val text = "Πληρώσατε €10,00 από την κάρτα *1554 σε Paymonade"
        val packageName = "mbanking.NBG"

        val result = parser.parse(
            title = title,
            text = text,
            bigText = null,
            subText = null,
            packageName = packageName
        )

        assertNotNull("Result should not be null", result)
        assertEquals(10.00, result!!.amount, 0.01)
        
        // This is what the user is reporting is WRONG.
        // If my hypothesis is correct, result.merchant will be something like 
        // "Paymonade Πληρώσατε €10,00 από την κάρτα *1554 σε Paymonade"
        // truncated to 40 chars by MerchantCleaner: "Paymonade Πληρώσατε €10,00 από την κάρτ"
        
        println("Extracted merchant: '${result.merchant}'")
        assertEquals("Paymonade", result.merchant)
    }
}
