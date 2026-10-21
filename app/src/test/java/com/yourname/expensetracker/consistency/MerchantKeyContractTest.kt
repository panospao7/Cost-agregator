package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.ai.service.SemanticDuplicateDetector
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.intelligence.DuplicateCheckResult
import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.util.AmountExtractionUtils
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CommonPatterns
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the shared merchant-key / amount / duplicate-detection logic.
 *
 * Consolidates the former MerchantKeyConsistencyTest, MerchantKeyCrossConsumerConsistencyTest,
 * CrossParserConsistencyTest, SharedUtilityConsistencyTest and
 * DuplicateLogicConsistencyIntegrationTest. Each table row is one distinct contract case and
 * every assertion message names its row.
 */
class MerchantKeyContractTest {

    private lateinit var merchantCleaner: MerchantCleaner
    private lateinit var merchantRules: MerchantRulesRepository
    private lateinit var currencyNormalizer: CurrencyNormalizer
    private lateinit var revolutParser: RevolutParser
    private lateinit var greekBankParser: GreekBankParser
    private lateinit var genericParser: GenericTransactionParser
    private lateinit var deduplication: CrossSourceDeduplication

    @Before
    fun setup() {
        merchantCleaner = MerchantCleaner()
        merchantRules = MerchantRulesRepository()
        currencyNormalizer = CurrencyNormalizer()
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner)
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR")
        genericParser = GenericTransactionParser(
            currencyNormalizer,
            merchantCleaner,
            TransferDirectionDetector(),
            timeProvider = mockk()
        )
        val semanticDetector = mockk<SemanticDuplicateDetector>(relaxed = true)
        deduplication = CrossSourceDeduplication(semanticDetector)
    }

    // ============================================================================
    // MERCHANT KEY: PATH AGREEMENT
    // ============================================================================

    @Test
    fun `merchant key - parser cleaner path and rules path produce the same key`() {
        val merchants = listOf("Starbucks", "SKLAVENITIS", "LIDL", "Pizza Hood", "AB Vasilopoulos")
        for (merchant in merchants) {
            val keyFromParser = MerchantKeyGenerator.generate(merchantCleaner.clean(merchant))
            val keyFromRules = MerchantKeyGenerator.generate(merchantRules.cleanMerchantName(merchant))
            assertEquals(
                "row '$merchant': parser path and rules path must produce the same key",
                keyFromParser,
                keyFromRules
            )
        }
    }

    @Test
    fun `merchant key - MerchantKeyGenerator is the single source for keys`() {
        val raw = "  Starbucks  "
        val keyDirect = MerchantKeyGenerator.generate("Starbucks")
        val keyFromCleaned = MerchantKeyGenerator.generate(merchantCleaner.clean(raw))
        val keyFromRules = MerchantKeyGenerator.generate(merchantRules.cleanMerchantName(raw))
        assertEquals("starbucks", keyDirect)
        assertEquals(keyDirect, keyFromCleaned)
        assertEquals(keyDirect, keyFromRules)
    }

    @Test
    fun `merchant key - corporate suffix stripped by rules produces the same key as the base name`() {
        val withSuffix = "Acme Corp LTD"
        val base = "Acme Corp"
        val keyWithSuffix = MerchantKeyGenerator.generate(merchantRules.cleanMerchantName(withSuffix))
        val keyBase = MerchantKeyGenerator.generate(base)
        assertEquals(
            "Corporate suffix stripping must not change key for known merchant",
            keyBase,
            keyWithSuffix
        )
    }

    @Test
    fun `merchant key - clean then key matches the key of the raw input`() {
        val rawInputs = listOf(
            "Starbucks",
            "LIDL",
            "SKLAVENITIS",
            "  Starbucks  ",
            "  Starbucks  12345  "
        )
        for (raw in rawInputs) {
            val keyDirect = MerchantKeyGenerator.generate(raw)
            val keyFromCleaned = MerchantKeyGenerator.generate(merchantCleaner.clean(raw))
            assertEquals("row '$raw': clean+key and direct key must match", keyDirect, keyFromCleaned)
        }
    }

    @Test
    fun `merchant key - equivalent spellings produce the same key`() {
        // Case differences, Greek/Latin transliteration, and apostrophe stripping.
        val pairs = listOf(
            "case Starbucks vs STARBUCKS" to ("Starbucks" to "STARBUCKS"),
            "case starbucks vs Starbucks" to ("starbucks" to "Starbucks"),
            "case LIDL vs lidl" to ("LIDL" to "lidl"),
            "case greek lower vs upper" to ("Σκλαβενίτης" to "ΣΚΛΑΒΕΝΊΤΗΣ"),
            "greek vs latin transliteration" to ("Σκλαβενίτης" to "Sklavenitis"),
            "apostrophe stripped" to ("McDonald's" to "McDonalds")
        )
        for ((id, pair) in pairs) {
            val keyA = MerchantKeyGenerator.generate(pair.first)
            val keyB = MerchantKeyGenerator.generate(pair.second)
            assertEquals("row '$id': '${pair.first}' vs '${pair.second}' must share a key", keyA, keyB)
        }
    }

    @Test
    fun `merchant key - generation is deterministic across scripts and bulk inputs`() {
        val rows = linkedMapOf(
            "latin" to listOf("Starbucks", "McDonald's", "LIDL", "AB Vasilopoulos"),
            "greek" to listOf("Σκλαβενίτης", "ΑΒ Βασιλόπουλος", "Καφενείο", "Μακεδονία"),
            "mixed script" to listOf("LIDL ΜΑΡΚΕΤ", "AB Vasilopoulos", "Starbucks Στούντιο"),
            "bulk 200 merchants" to (1..200).map { "Merchant$it" }
        )
        for ((group, merchants) in rows) {
            for (m in merchants) {
                val first = MerchantKeyGenerator.generate(m)
                repeat(10) {
                    assertEquals(
                        "row '$group/$m': key generation must be deterministic",
                        first,
                        MerchantKeyGenerator.generate(m)
                    )
                }
            }
        }
    }

    // ============================================================================
    // MERCHANT KEY: EXPENSE DEDUPE KEY, EDGE CASES, PARSER OUTPUT
    // ============================================================================

    private data class DedupeKeyCase(
        val id: String,
        val merchant: String,
        val amount: Double = 25.50,
        val date: Long = System.currentTimeMillis(),
        val expectedAmountFragment: String? = null
    )

    @Test
    fun `dedupe key - Expense generateDedupeKey embeds the MerchantKeyGenerator key`() {
        val cases = listOf(
            DedupeKeyCase("Starbucks", "Starbucks"),
            DedupeKeyCase("greek Σκλαβενίτης", "Σκλαβενίτης"),
            DedupeKeyCase("LIDL", "LIDL"),
            DedupeKeyCase("apostrophe McDonald's", "McDonald's"),
            DedupeKeyCase("PIZZA HOOD", "PIZZA HOOD"),
            DedupeKeyCase("numbers in merchant", "Store 12345", amount = 10.0),
            DedupeKeyCase("format includes amount and merchant key", "Starbucks", amount = 50.25, date = 1700000000000L, expectedAmountFragment = "50.25")
        ) + (1..200).map { DedupeKeyCase("bulk Merchant$it", "Merchant$it", amount = 1.0, date = 0L) }

        for (c in cases) {
            val directKey = MerchantKeyGenerator.generate(c.merchant)
            val dedupeKey = Expense.generateDedupeKey(c.amount, c.merchant, c.date, "EUR")
            assertTrue(
                "row '${c.id}': dedupeKey '$dedupeKey' must contain MerchantKeyGenerator output '$directKey'",
                dedupeKey.contains(directKey)
            )
            c.expectedAmountFragment?.let {
                assertTrue("row '${c.id}': dedupeKey '$dedupeKey' must contain amount '$it'", dedupeKey.contains(it))
            }
        }
        // Numbers in a merchant name must still yield a usable key.
        val numberKey = MerchantKeyGenerator.generate("Store 12345")
        assertEquals("row 'numbers in merchant': key keeps digits, lowercases and strips the space", "store12345", numberKey)
    }

    @Test
    fun `edge - empty and whitespace-only inputs are handled consistently across key and amount utilities`() {
        for (input in listOf("", "   ")) {
            assertEquals("row '$input': merchant key must be empty", "", MerchantKeyGenerator.generate(input))
            assertNull("row '$input': AmountUtils must reject", AmountUtils.parseAmount(input))
            assertNull("row '$input': AmountExtractionUtils must reject", AmountExtractionUtils.extractFirstAmount(input))
        }
        val dedupeKey = Expense.generateDedupeKey(10.0, "", System.currentTimeMillis(), "EUR")
        assertTrue("empty merchant dedupeKey '$dedupeKey' must contain the '_' separator", dedupeKey.contains("_"))
    }

    @Test
    fun `edge - very long merchant produces a non-empty key`() {
        val key = MerchantKeyGenerator.generate("A".repeat(500))
        assertTrue("500-char merchant must produce a valid key", key.isNotEmpty())
    }

    @Test
    fun `parser output - merchant extracted by each parser maps to the expected key`() {
        val cases = listOf<Triple<String, () -> String?, String>>(
            Triple(
                "revolut",
                {
                    revolutParser.parse(
                        title = "Paid €12.50 at SKLAVENITIS",
                        text = null,
                        bigText = null,
                        subText = null,
                        packageName = "com.revolut.revolut"
                    )?.merchant
                },
                "sklavenitis"
            ),
            Triple(
                "greek bank",
                {
                    greekBankParser.parse(
                        title = "Πληρωμή",
                        text = "Πληρώσατε €6,30 σε PIZZA HOOD",
                        bigText = null,
                        subText = null,
                        packageName = "gr.nbg.mobilebanking"
                    )?.merchant
                },
                "pizzahood"
            ),
            Triple(
                "generic",
                {
                    genericParser.parse(
                        title = "Payment",
                        text = "You paid €5.50 at Starbucks Coffee",
                        bigText = null,
                        subText = null,
                        packageName = "com.unknown.app"
                    )?.merchant
                },
                "starbuckscoffee"
            )
        )
        for ((id, extractMerchant, expectedKey) in cases) {
            val merchant = extractMerchant()
            assertNotNull("row '$id': parser must produce a result", merchant)
            assertEquals("row '$id': merchant key", expectedKey, MerchantKeyGenerator.generate(merchant!!))
        }
    }

    @Test
    fun `parser output - amount is parsed consistently across revolut and greek bank for the same value`() {
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

    // ============================================================================
    // AMOUNT PARSING CONSISTENCY
    // ============================================================================

    private data class AmountCase(val id: String, val input: String, val expected: Double? = null)

    @Test
    fun `amount parsing - AmountUtils and AmountExtractionUtils agree on valid formats`() {
        val cases = listOf(
            AmountCase("simple 10.00", "10.00"),
            AmountCase("simple 50.50", "50.50"),
            AmountCase("simple 100.00", "100.00"),
            AmountCase("simple 1234.56", "1234.56"),
            AmountCase("simple 0.01", "0.01"),
            AmountCase("prefixed euro", "€50.00"),
            AmountCase("prefixed dollar", "$25.99"),
            AmountCase("suffixed EUR", "100.00 EUR"),
            AmountCase("prefixed pound", "£75.50"),
            AmountCase("european 1.234,56", "1.234,56"),
            AmountCase("european 10,50", "10,50"),
            AmountCase("european 999.999,99", "999.999,99"),
            AmountCase("thousands separator", "1,234.56", expected = 1234.56)
        )
        for (c in cases) {
            val amountUtilsResult = AmountUtils.parseAmount(c.input)
            val extractionResult = AmountExtractionUtils.extractFirstAmount(c.input)
            assertNotNull("row '${c.id}': AmountUtils should parse '${c.input}'", amountUtilsResult)
            assertNotNull("row '${c.id}': AmountExtractionUtils should parse '${c.input}'", extractionResult)
            assertEquals(
                "row '${c.id}': AmountUtils and AmountExtractionUtils must agree on '${c.input}'",
                amountUtilsResult!!,
                extractionResult!!,
                0.001
            )
            c.expected?.let {
                assertEquals("row '${c.id}': AmountUtils value", it, amountUtilsResult, 0.001)
                assertEquals("row '${c.id}': AmountExtractionUtils value", it, extractionResult, 0.001)
            }
        }
    }

    @Test
    fun `amount parsing - amount embedded in a sentence matches the bare amount`() {
        val a = AmountUtils.parseAmount("25.50")
        val b = AmountExtractionUtils.extractFirstAmount("You paid €25.50 at the store")
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a!!, b!!, 0.001)
    }

    @Test
    fun `amount parsing - invalid inputs are rejected by both utilities`() {
        for (input in listOf("", "abc", "no numbers", "---", "...")) {
            assertNull("row '$input': AmountUtils should reject", AmountUtils.parseAmount(input))
            assertNull("row '$input': AmountExtractionUtils should reject", AmountExtractionUtils.extractFirstAmount(input))
        }
    }

    @Test
    fun `amount parsing - CommonPatterns AMOUNT_REGEX agrees with AmountUtils`() {
        val inputs = listOf("€50.00", "50.00", "100,50", "1.234,56", "EUR 100.50", "$25.99")
        var matchedCount = 0
        for (input in inputs) {
            val matcher = CommonPatterns.AMOUNT_REGEX.matcher(input)
            val amountUtilsResult = AmountUtils.parseAmount(input)
            if (matcher.find() && amountUtilsResult != null) {
                val group2 = matcher.group(2) ?: continue
                val parsedFromRegex = AmountUtils.parseAmount(group2.replace(" ", "").trim())
                if (parsedFromRegex != null) {
                    assertEquals(
                        "row '$input': CommonPatterns and AmountUtils must agree",
                        amountUtilsResult,
                        parsedFromRegex,
                        0.01
                    )
                    matchedCount++
                }
            }
        }
        assertTrue("At least 3 inputs should match CommonPatterns (matched $matchedCount)", matchedCount >= 3)
    }

    @Test
    fun `amount parsing - extractAmount returns the same value as extractFirstAmount`() {
        for (input in listOf("€50.00 at Starbucks", "Payment 100.50 EUR", "25.99")) {
            val extracted = AmountExtractionUtils.extractAmount(input)
            val firstAmount = AmountExtractionUtils.extractFirstAmount(input)
            assertNotNull("row '$input': extractAmount must return a value", extracted)
            assertNotNull("row '$input': extractFirstAmount must return a value", firstAmount)
            assertEquals(
                "row '$input': extractAmount and extractFirstAmount must agree",
                extracted!!.first,
                firstAmount!!,
                0.001
            )
        }
    }

    // ============================================================================
    // DUPLICATE LOGIC CONSISTENCY
    // ============================================================================

    private data class ExpenseDupCase(
        val id: String,
        val existingAmount: Double = 25.50,
        val existingMerchant: String = "Starbucks",
        val existingDateOffsetMs: Long = 0L,
        val expectMatch: Boolean
    )

    @Test
    fun `duplicate logic - findExpenseDuplicate matches only same amount merchant and window`() {
        val queryAmount = 25.50
        val queryMerchant = "Starbucks"
        val cases = listOf(
            ExpenseDupCase("exact same transaction", expectMatch = true),
            ExpenseDupCase("different amount", existingAmount = 30.00, expectMatch = false),
            ExpenseDupCase("different merchant", existingMerchant = "McDonald's", expectMatch = false),
            ExpenseDupCase("date outside window (25h ago)", existingDateOffsetMs = -(25L * 60 * 60 * 1000), expectMatch = false),
            ExpenseDupCase("amount within 0.01 tolerance", existingAmount = 25.505, expectMatch = true),
            ExpenseDupCase("amount outside 0.01 tolerance (0.02 diff)", existingAmount = 25.52, expectMatch = false),
            ExpenseDupCase("merchant similarity Starbucks vs Starbucks Coffee", existingMerchant = "Starbucks Coffee", expectMatch = true)
        )
        for (c in cases) {
            val date = System.currentTimeMillis()
            val expense = createExpense(1, c.existingAmount, c.existingMerchant, date + c.existingDateOffsetMs)
            val duplicate = deduplication.findExpenseDuplicate(
                amount = queryAmount,
                merchant = queryMerchant,
                date = date,
                expenses = listOf(expense),
                currency = "EUR",
                transactionType = TransactionType.PURCHASE
            )
            if (c.expectMatch) {
                assertNotNull("row '${c.id}': expected a duplicate match", duplicate)
                assertEquals("row '${c.id}': matched expense id", expense.id, duplicate!!.id)
            } else {
                assertNull("row '${c.id}': expected no duplicate", duplicate)
            }
        }
    }

    private data class PendingDefaultsCase(
        val id: String,
        val reviewAmount: Double = 25.50,
        val reviewDate: ((Long) -> Long?) = { it },
        val expectMatch: Boolean
    )

    @Test
    fun `duplicate logic - findPendingReviewDuplicate with default currency and type`() {
        val cases = listOf(
            PendingDefaultsCase("exact same transaction", expectMatch = true),
            PendingDefaultsCase("different amount", reviewAmount = 30.00, expectMatch = false),
            PendingDefaultsCase("null suggestedDate", reviewDate = { null }, expectMatch = false)
        )
        for (c in cases) {
            val date = System.currentTimeMillis()
            val review = createPendingReview(1, c.reviewAmount, "Starbucks", c.reviewDate(date))
            val duplicate = deduplication.findPendingReviewDuplicate(25.50, "Starbucks", date, listOf(review))
            if (c.expectMatch) {
                assertNotNull("row '${c.id}': expected a duplicate match", duplicate)
                assertEquals("row '${c.id}': matched review id", review.id, duplicate!!.id)
            } else {
                assertNull("row '${c.id}': expected no duplicate", duplicate)
            }
        }
    }

    private data class PendingPolicyCase(
        val id: String,
        val reviewCurrency: String = "EUR",
        val reviewType: TransactionType = TransactionType.PURCHASE,
        val reviewAmount: Double = 25.50,
        val reviewMerchant: String = "Starbucks",
        val reviewId: Long = 1,
        val queryAmount: Double = 25.50,
        val queryMerchant: String = "Starbucks",
        val queryCurrency: String?,
        val queryType: TransactionType?,
        val expectMatch: Boolean
    )

    @Test
    fun `duplicate logic - findPendingReviewDuplicate currency and type compatibility policy`() {
        val cases = listOf(
            PendingPolicyCase("currency mismatch returns null", reviewCurrency = "USD", queryCurrency = "EUR", queryType = null, expectMatch = false),
            PendingPolicyCase("same currency matches", reviewCurrency = "EUR", queryCurrency = "EUR", queryType = null, expectMatch = true),
            PendingPolicyCase(
                "incompatible transaction type (DEPOSIT review vs PURCHASE) returns null",
                reviewType = TransactionType.DEPOSIT, queryCurrency = "EUR", queryType = TransactionType.PURCHASE, expectMatch = false
            ),
            PendingPolicyCase(
                "compatible transaction types match",
                reviewType = TransactionType.PURCHASE, queryCurrency = "EUR", queryType = TransactionType.PURCHASE, expectMatch = true
            ),
            PendingPolicyCase(
                "UNKNOWN type matches any review type",
                reviewType = TransactionType.DEPOSIT, queryCurrency = "EUR", queryType = TransactionType.UNKNOWN, expectMatch = true
            ),
            PendingPolicyCase(
                "null currency parameter defaults to EUR and matches EUR review",
                reviewCurrency = "EUR", queryCurrency = null, queryType = null, expectMatch = true
            ),
            PendingPolicyCase(
                "oversized fallback duplicate is detected with unknown type",
                reviewId = 7, reviewAmount = 9999.99, reviewMerchant = "Unknown", reviewType = TransactionType.UNKNOWN,
                queryAmount = 9999.99, queryMerchant = "Unknown", queryCurrency = "EUR", queryType = TransactionType.UNKNOWN,
                expectMatch = true
            )
        )
        for (c in cases) {
            val date = System.currentTimeMillis()
            val review = createPendingReview(c.reviewId, c.reviewAmount, c.reviewMerchant, date, c.reviewCurrency, c.reviewType)
            val result = if (c.queryType == null) {
                deduplication.findPendingReviewDuplicate(
                    amount = c.queryAmount,
                    merchant = c.queryMerchant,
                    date = date,
                    pendingReviews = listOf(review),
                    currency = c.queryCurrency
                )
            } else {
                deduplication.findPendingReviewDuplicate(
                    amount = c.queryAmount,
                    merchant = c.queryMerchant,
                    date = date,
                    pendingReviews = listOf(review),
                    currency = c.queryCurrency,
                    transactionType = c.queryType
                )
            }
            if (c.expectMatch) {
                assertNotNull("row '${c.id}': expected a duplicate match", result)
                assertEquals("row '${c.id}': matched review id", c.reviewId, result!!.id)
            } else {
                assertNull("row '${c.id}': expected no duplicate", result)
            }
        }
    }

    @Test
    fun `duplicate logic - ranked selection returns the candidate closest in time`() {
        val now = System.currentTimeMillis()
        // Two candidates that both pass hard filters; reviewClose is temporally nearer
        val reviewFar = createPendingReview(1, 25.50, "Starbucks", now - 240_000L, currency = "EUR") // 4 min ago
        val reviewClose = createPendingReview(2, 25.50, "Starbucks", now - 60_000L, currency = "EUR") // 1 min ago

        val result = deduplication.findPendingReviewDuplicate(
            amount = 25.50,
            merchant = "Starbucks",
            date = now,
            pendingReviews = listOf(reviewFar, reviewClose),
            currency = "EUR",
            transactionType = TransactionType.PURCHASE
        )
        assertNotNull(result)
        assertEquals("Should prefer temporally closest candidate", reviewClose.id, result!!.id)
    }

    @Test
    fun `duplicate logic - source aware dedupe key is deterministic`() {
        val key1 = deduplication.generateSourceAwareDedupeKey(50.0, "LIDL", 1700000000000L, "notification")
        val key2 = deduplication.generateSourceAwareDedupeKey(50.0, "LIDL", 1700000000000L, "notification")
        assertEquals(key1, key2)
    }

    @Test
    fun `duplicate logic - isCrossSourceDuplicate classifies same source and empty sources`() {
        val cases = listOf<Triple<String, List<String>, (DuplicateCheckResult) -> Boolean>>(
            Triple("same source returns SameSourceDuplicate", listOf("notification"), { r: DuplicateCheckResult -> r is DuplicateCheckResult.SameSourceDuplicate }),
            Triple("empty existing sources returns NoDuplicate", emptyList<String>(), { r: DuplicateCheckResult -> r is DuplicateCheckResult.NoDuplicate })
        )
        for ((id, existingSources, isExpected) in cases) {
            val result = deduplication.isCrossSourceDuplicate(
                amount = 25.50,
                merchant = "Starbucks",
                date = System.currentTimeMillis(),
                newSource = "notification",
                existingSources = existingSources
            )
            assertTrue("row '$id': unexpected result $result", isExpected(result))
        }
    }

    @Test
    fun `duplicate logic - repeated calls return the same result`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()

        val expenses = listOf(createExpense(1, amount, merchant, date))
        val firstExpenseDup = deduplication.findExpenseDuplicate(
            amount = amount,
            merchant = merchant,
            date = date,
            expenses = expenses,
            currency = "EUR",
            transactionType = TransactionType.PURCHASE
        )
        val reviews = listOf(createPendingReview(1, amount, merchant, date))
        val firstReviewDup = deduplication.findPendingReviewDuplicate(amount, merchant, date, reviews)
        repeat(99) { n ->
            val expenseDup = deduplication.findExpenseDuplicate(
                amount = amount,
                merchant = merchant,
                date = date,
                expenses = expenses,
                currency = "EUR",
                transactionType = TransactionType.PURCHASE
            )
            assertEquals("findExpenseDuplicate call #${n + 2}", firstExpenseDup?.id, expenseDup?.id)
            val reviewDup = deduplication.findPendingReviewDuplicate(amount, merchant, date, reviews)
            assertEquals("findPendingReviewDuplicate call #${n + 2}", firstReviewDup?.id, reviewDup?.id)
        }
    }

    // ============================================================================
    // FIXTURES
    // ============================================================================

    private fun createExpense(id: Long, amount: Double, merchant: String, date: Long): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = date,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun createPendingReview(
        id: Long,
        amount: Double,
        merchant: String,
        date: Long?,
        currency: String = "EUR",
        type: TransactionType = TransactionType.PURCHASE
    ): PendingReview {
        return PendingReview(
            id = id,
            rawNotificationId = null,
            suggestedAmount = amount,
            suggestedCurrency = currency,
            suggestedMerchant = merchant,
            suggestedType = type.name,
            suggestedCategoryId = null,
            suggestedDate = date,
            confidence = 0.9f,
            packageName = "com.test.app",
            notificationTitle = null,
            notificationText = null,
            status = PendingReviewStatus.PENDING
        )
    }
}
