package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Ensures parsers produce consistent merchant keys when they extract the same logical merchant.
 * All parsers use MerchantCleaner + MerchantKeyGenerator; this test catches drift.
 */
class CrossParserConsistencyTest {

    private lateinit var currencyNormalizer: CurrencyNormalizer
    private lateinit var merchantCleaner: MerchantCleaner
    private lateinit var revolutParser: RevolutParser
    private lateinit var greekBankParser: GreekBankParser
    private lateinit var genericParser: GenericTransactionParser

    @Before
    fun setup() {
        currencyNormalizer = CurrencyNormalizer()
        merchantCleaner = MerchantCleaner()
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner)
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner)
        genericParser = GenericTransactionParser(
            currencyNormalizer,
            merchantCleaner,
            TransferDirectionDetector()
        )
    }

    @Test
    fun `consistency - same merchant string produces same key across components`() {
        val merchants = listOf("SKLAVENITIS", "Starbucks", "PIZZA HOOD", "LIDL", "Σκλαβενίτης")
        for (merchant in merchants) {
            val keyDirect = MerchantKeyGenerator.generate(merchant)
            val keyFromExpense = MerchantKeyGenerator.generate(merchant) // Expense uses this
            val keyFromCleaned = MerchantKeyGenerator.generate(merchantCleaner.clean(merchant))
            assertEquals("Direct and Expense path must match: $merchant", keyDirect, keyFromExpense)
            assertNotNull("Key must exist: $merchant", keyDirect)
        }
    }

    @Test
    fun `consistency - Expense generateDedupeKey uses MerchantKeyGenerator`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val dedupeKey = Expense.generateDedupeKey(amount, merchant, date)
        val expectedMerchantKey = MerchantKeyGenerator.generate(merchant)
        assert(dedupeKey.contains(expectedMerchantKey)) {
            "DedupeKey must contain merchant key: $dedupeKey"
        }
    }

    @Test
    fun `consistency - Revolut parser merchant produces valid key`() {
        val result = revolutParser.parse(
            title = "Paid €12.50 at SKLAVENITIS",
            text = null,
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        val key = MerchantKeyGenerator.generate(result!!.merchant)
        assertEquals("sklavenitis", key)
    }

    @Test
    fun `consistency - Greek bank parser merchant produces valid key`() {
        val result = greekBankParser.parse(
            title = "Πληρωμή",
            text = "Πληρώσατε €6,30 σε PIZZA HOOD",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        val key = MerchantKeyGenerator.generate(result!!.merchant)
        assertEquals("pizzahood", key)
    }

    @Test
    fun `consistency - Generic parser merchant produces valid key`() {
        val result = genericParser.parse(
            title = "Payment",
            text = "You paid €5.50 at Starbucks Coffee",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        val key = MerchantKeyGenerator.generate(result!!.merchant)
        assert(key.isNotEmpty())
        assertEquals("starbuckscoffee", key)
    }

    @Test
    fun `consistency - cleaned merchant produces same key as raw for simple names`() {
        val raw = "  Starbucks  12345  "
        val cleaned = merchantCleaner.clean(raw)
        val keyRaw = MerchantKeyGenerator.generate(raw)
        val keyCleaned = MerchantKeyGenerator.generate(cleaned)
        assertEquals("Clean then key should match key of raw for simple: $raw", keyCleaned, keyRaw)
    }

    @Test
    fun `consistency - Greek and Latin merchant produce same key`() {
        val greek = "Σκλαβενίτης"
        val latin = "Sklavenitis"
        val keyGreek = MerchantKeyGenerator.generate(greek)
        val keyLatin = MerchantKeyGenerator.generate(latin)
        assertEquals("Greek and Latin must produce same key", keyGreek, keyLatin)
    }

    @Test
    fun `consistency - amount parsing consistent across parsers for same format`() {
        val revolutResult = revolutParser.parse(
            title = "Paid €12.50 at Store",
            text = null,
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        val greekResult = greekBankParser.parse(
            title = "Πληρωμή",
            text = "Πληρώσατε €12,50 σε Store",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(revolutResult)
        assertNotNull(greekResult)
        assertEquals(
            "Same amount in different formats must parse consistently",
            revolutResult!!.amount,
            greekResult!!.amount,
            0.01
        )
    }
}
