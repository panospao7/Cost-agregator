package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Stress tests for cross-parser consistency.
 */
class CrossParserConsistencyStressTest {

    private lateinit var revolutParser: RevolutParser
    private lateinit var greekBankParser: GreekBankParser

    @Before
    fun setup() {
        val currencyNormalizer = CurrencyNormalizer()
        val merchantCleaner = MerchantCleaner()
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner)
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner)
    }

    @Test
    fun `stress - 100 Expense generateDedupeKey calls consistent`() {
        val amount = 50.0
        val merchant = "Starbucks"
        val date = 1700000000000L
        val expected = Expense.generateDedupeKey(amount, merchant, date)
        repeat(100) {
            assertEquals(
                "generateDedupeKey must be deterministic",
                expected,
                Expense.generateDedupeKey(amount, merchant, date)
            )
        }
    }

    @Test
    fun `stress - MerchantKeyGenerator same for 50 merchant variants`() {
        val base = "SKLAVENITIS"
        val key = MerchantKeyGenerator.generate(base)
        val variants = listOf(
            "SKLAVENITIS",
            "Sklavenitis",
            "sklavenitis",
            "  SKLAVENITIS  ",
            "Σκλαβενίτης"
        )
        for (v in variants) {
            val kv = MerchantKeyGenerator.generate(v)
            assertNotNull(kv)
            if (v.contains("Σ") || v.contains("κ")) {
                assertEquals("Greek variant must match: $v", key, kv)
            } else {
                assertEquals("Case variant must match: $v", key, kv)
            }
        }
    }

    @Test
    fun `stress - Revolut parser 20 notifications same merchant key`() {
        val merchantKeys = mutableSetOf<String>()
        for (i in 1..20) {
            val result = revolutParser.parse(
                title = "Paid €${10 + i}.00 at SKLAVENITIS",
                text = null,
                bigText = null,
                subText = null,
                packageName = "com.revolut.revolut"
            )
            if (result != null) {
                merchantKeys.add(MerchantKeyGenerator.generate(result.merchant))
            }
        }
        assertEquals(
            "All SKLAVENITIS parses must produce same key",
            1,
            merchantKeys.size
        )
    }

    @Test
    fun `stress - Greek parser 20 notifications same merchant key`() {
        val merchantKeys = mutableSetOf<String>()
        for (i in 1..20) {
            val result = greekBankParser.parse(
                title = "Πληρωμή",
                text = "Πληρώσατε €${5 + i},00 σε LIDL",
                bigText = null,
                subText = null,
                packageName = "gr.nbg.mobilebanking"
            )
            if (result != null) {
                merchantKeys.add(MerchantKeyGenerator.generate(result.merchant))
            }
        }
        assertEquals(
            "All LIDL parses must produce same key",
            1,
            merchantKeys.size
        )
    }
}
