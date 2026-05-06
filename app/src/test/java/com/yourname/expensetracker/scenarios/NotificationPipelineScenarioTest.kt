package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.testfixtures.dateMs
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.scenario.CategorySeed
import com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioAssertions
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import com.yourname.expensetracker.data.database.AppDatabase
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for the notification-to-expense pipeline.
 *
 * These tests feed REAL Greek bank notification text through the actual
 * [GreekBankParser] to verify:
 * 1. Parser correctly extracts amount, merchant, currency, and confidence
 * 2. Parsed transactions can be stored and verified in the database
 * 3. Unknown/unrecognized notifications are rejected
 * 4. Multiple notifications create separate expenses
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationPipelineScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var parser: GreekBankParser

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)

        // Create mock dependencies for GreekBankParser
        val currencyNormalizer = mockk<CurrencyNormalizer>()
        val merchantCleaner = mockk<MerchantCleaner>()

        every { currencyNormalizer.normalize(any()) } returns "EUR"
        every { merchantCleaner.clean(any()) } answers { firstArg<String>() }

        parser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR")
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        clearAllMocks()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: NBG purchase notification parsed correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `greek bank notification parsed correctly for NBG purchase`() = runTest {
        // GIVEN: an NBG mobile banking notification for a purchase at SKLAVENITIS
        val title = "NBG"
        val text = "ΑΓΟΡΑ 45,50€ - ΣΚΛΑΒΕΝΙΤΗΣ"

        // WHEN: parsing the notification
        val result = parser.parse(
            title = title,
            text = text,
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )

        // THEN: the parser extracts the transaction correctly
        assertNotNull("Parser should extract a transaction from NBG notification", result)
        assertEquals("Amount should be 45.50", 45.50, result!!.amount, 0.01)
        assertEquals("Currency should be EUR", "EUR", result.currency)
        assertTrue(
            "Merchant should contain ΣΚΛΑΒΕΝΙΤΗΣ",
            result.merchant.contains("ΣΚΛΑΒΕΝΙΤΗΣ")
        )
        assertEquals("Transaction type should be PURCHASE", ParsedTransactionType.PURCHASE, result.type)
        assertTrue("Confidence should be >= 0.9", result.confidence >= 0.90f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Eurobank purchase notification parsed correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `greek bank notification parsed correctly for Eurobank purchase`() = runTest {
        // GIVEN: a Eurobank mobile notification for a purchase at AB BASILOPOULOS
        val title = "Eurobank"
        val text = "Αγορά 23.40€ στην AB BASILOPOULOS"

        // WHEN: parsing the notification
        val result = parser.parse(
            title = title,
            text = text,
            bigText = null,
            subText = null,
            packageName = "com.eurobank.mobile"
        )

        // THEN: the parser extracts the transaction correctly
        assertNotNull("Parser should extract a transaction from Eurobank notification", result)
        assertEquals("Amount should be 23.40", 23.40, result!!.amount, 0.01)
        assertEquals("Currency should be EUR", "EUR", result.currency)
        assertTrue(
            "Merchant should contain AB BASILOPOULOS",
            result.merchant.contains("AB BASILOPOULOS")
        )
        assertEquals("Transaction type should be PURCHASE", ParsedTransactionType.PURCHASE, result.type)
        assertTrue("Confidence should be >= 0.9", result.confidence >= 0.90f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Parsed notification stored in DB and verified
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `parsed notification stored in DB and verified`() = runTest {
        // GIVEN: a parsed NBG notification for a purchase at SKLAVENITIS
        val currentTime = dateMs(2026, 5, 1)
        val title = "NBG"
        val text = "ΑΓΟΡΑ 45,50€ - ΣΚΛΑΒΕΝΙΤΗΣ"

        val parsed = parser.parse(
            title = title,
            text = text,
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull("Parser must successfully parse the notification", parsed)

        // AND: a seed with a "Shopping" category and the parsed expense
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = parsed!!.amount,
                    currency = parsed.currency,
                    merchant = parsed.merchant,
                    transactionType = "PURCHASE",
                    date = currentTime,
                    categoryName = "Shopping"
                )
            ),
            fixedNowMs = currentTime,
            description = "Single expense from parsed NBG notification"
        )

        // WHEN: seeding the expense into the database
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // THEN:
        //   - expense count = 1
        //   - expense exists with correct merchant and amount
        //   - dashboard total ≈ 45.50
        with(ScenarioAssertions) {
            db.assertExpenseCount(1)
            db.assertExpenseExists("ΣΚΛΑΒΕΝΙΤΗΣ", 45.50)
            db.assertDashboardTotal(45.50)
        }

        // Verify seed result metadata
        assertEquals("Expected exactly 1 expense ID in seed result", 1, result.expenseIds.size)
        assertEquals("Expected exactly 1 category ID in seed result", 1, result.categoryIds.size)
        assertTrue(
            "Seed result should contain 'Shopping' category",
            result.categoryIds.containsKey("Shopping")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Unknown package returns null from parser
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `unknown package returns null from parser`() = runTest {
        // GIVEN: a notification from an unknown/non-banking app
        val title = "Payment Alert"
        val text = "Payment of 100 EUR to Amazon"

        // WHEN: parsing the notification with an unknown package
        val result = parser.parse(
            title = title,
            text = text,
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )

        // THEN: the parser should return null (unrecognized notification format)
        assertNull(
            "Parser should return null for notifications from unknown packages",
            result
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Multiple notifications create separate expenses
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `multiple notifications create separate expenses`() = runTest {
        // GIVEN: two different Greek bank notifications
        val currentTime = dateMs(2026, 5, 1)

        // Parse NBG notification
        val nbgParsed = parser.parse(
            title = "NBG",
            text = "ΑΓΟΡΑ 45,50€ - ΣΚΛΑΒΕΝΙΤΗΣ",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull("NBG notification should parse", nbgParsed)

        // Parse Eurobank notification
        val eurobankParsed = parser.parse(
            title = "Eurobank",
            text = "Αγορά 23.40€ στην AB BASILOPOULOS",
            bigText = null,
            subText = null,
            packageName = "com.eurobank.mobile"
        )
        assertNotNull("Eurobank notification should parse", eurobankParsed)

        // AND: a seed with both expenses
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = nbgParsed!!.amount,
                    currency = nbgParsed.currency,
                    merchant = nbgParsed.merchant,
                    transactionType = "PURCHASE",
                    date = currentTime,
                    categoryName = "Shopping"
                ),
                ExpenseSeed(
                    amount = eurobankParsed!!.amount,
                    currency = eurobankParsed.currency,
                    merchant = eurobankParsed.merchant,
                    transactionType = "PURCHASE",
                    date = currentTime,
                    categoryName = "Shopping"
                )
            ),
            fixedNowMs = currentTime,
            description = "Two expenses from different Greek banks"
        )

        // WHEN: seeding both expenses into the database
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // THEN:
        //   - expense count = 2
        //   - both expenses exist with correct merchants and amounts
        //   - dashboard total ≈ 68.90
        //   - no duplicate expenses
        //   - two different expense IDs
        with(ScenarioAssertions) {
            db.assertExpenseCount(2)
            db.assertExpenseExists("ΣΚΛΑΒΕΝΙΤΗΣ", 45.50)
            db.assertExpenseExists("AB BASILOPOULOS", 23.40)
            db.assertDashboardTotal(68.90)
            db.assertNoDuplicateExpenses()
        }

        // Verify seed result metadata
        assertEquals("Expected exactly 2 expense IDs in seed result", 2, result.expenseIds.size)
        assertEquals("Expected exactly 1 category ID in seed result", 1, result.categoryIds.size)
        // The two expense IDs must be different (separate rows)
        assertTrue(
            "Two different expense IDs should be generated",
            result.expenseIds[0] != result.expenseIds[1]
        )
    }
}
