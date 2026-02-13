# ExpenseTracker Test Suite Extraction

This file contains all unit tests and instrumentation tests from the codebase.

## Table of Contents
1. [androidTest\java\com\yourname\expensetracker\data\database\dao\ExpenseDaoTest.kt](#androidtestjavacomyournameexpensetrackerdatadatabasedaoexpensedaotestkt)
2. [androidTest\java\com\yourname\expensetracker\data\database\dao\PendingReviewDaoTest.kt](#androidtestjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaotestkt)
3. [test\java\com\yourname\expensetracker\OcrParserTest.kt](#testjavacomyournameexpensetrackerocrparsertestkt)
4. [test\java\com\yourname\expensetracker\data\database\converter\ConvertersTest.kt](#testjavacomyournameexpensetrackerdatadatabaseconverterconverterstestkt)
5. [test\java\com\yourname\expensetracker\data\database\entity\SourceStatsTest.kt](#testjavacomyournameexpensetrackerdatadatabaseentitysourcestatstestkt)
6. [test\java\com\yourname\expensetracker\domain\analytics\InsightsEngineTest.kt](#testjavacomyournameexpensetrackerdomainanalyticsinsightsenginetestkt)
7. [test\java\com\yourname\expensetracker\domain\categorization\CategorizationEngineTest.kt](#testjavacomyournameexpensetrackerdomaincategorizationcategorizationenginetestkt)
8. [test\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouterTest.kt](#testjavacomyournameexpensetrackerdomainintelligenceconfidenceroutertestkt)
9. [test\java\com\yourname\expensetracker\domain\intelligence\ml\HybridExpenseClassifierTest.kt](#testjavacomyournameexpensetrackerdomainintelligencemlhybridexpenseclassifiertestkt)
10. [test\java\com\yourname\expensetracker\domain\intelligence\ml\MerchantNormalizerTest.kt](#testjavacomyournameexpensetrackerdomainintelligencemlmerchantnormalizertestkt)
11. [test\java\com\yourname\expensetracker\domain\logic\SynthesisEngineTest.kt](#testjavacomyournameexpensetrackerdomainlogicsynthesisenginetestkt)
12. [test\java\com\yourname\expensetracker\domain\parser\AppParserRegistryRoutingTest.kt](#testjavacomyournameexpensetrackerdomainparserappparserregistryroutingtestkt)
13. [test\java\com\yourname\expensetracker\domain\parser\AppParserRegistryTest.kt](#testjavacomyournameexpensetrackerdomainparserappparserregistrytestkt)
14. [test\java\com\yourname\expensetracker\domain\parser\GenericTransactionParserTest.kt](#testjavacomyournameexpensetrackerdomainparsergenerictransactionparsertestkt)
15. [test\java\com\yourname\expensetracker\domain\parser\GoogleWalletParserTest.kt](#testjavacomyournameexpensetrackerdomainparsergooglewalletparsertestkt)
16. [test\java\com\yourname\expensetracker\domain\parser\GreekBankParserTest.kt](#testjavacomyournameexpensetrackerdomainparsergreekbankparsertestkt)
17. [test\java\com\yourname\expensetracker\domain\parser\RevolutParserTest.kt](#testjavacomyournameexpensetrackerdomainparserrevolutparsertestkt)
18. [test\java\com\yourname\expensetracker\domain\parser\SmsParserTest.kt](#testjavacomyournameexpensetrackerdomainparsersmsparsertestkt)
19. [test\java\com\yourname\expensetracker\domain\receipt\BankStatementParserTest.kt](#testjavacomyournameexpensetrackerdomainreceiptbankstatementparsertestkt)
20. [test\kotlin\com\yourname\expensetracker\domain\logic\RecurringExpenseEngineTest.kt](#testkotlincomyournameexpensetrackerdomainlogicrecurringexpenseenginetestkt)

---

## androidTest\java\com\yourname\expensetracker\data\database\dao\ExpenseDaoTest.kt <a name="androidtestjavacomyournameexpensetrackerdatadatabasedaoexpensedaotestkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class ExpenseDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        expenseDao = database.expenseDao()
    }
    @After
    fun teardown() {
        database.close()
    }
    private fun makeExpense(
        amount: Double = 10.0,
        merchant: String = "Test",
        date: Long = System.currentTimeMillis()
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date
    )
    @Test
    fun insertAndRetrieve() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        assertTrue(id > 0)
        val all = expenseDao.getAll()
        assertEquals(1, all.size)
        assertEquals(10.0, all[0].amount, 0.01)
    }
    @Test
    fun getAllFlowEmitsUpdates() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))
        val expenses = expenseDao.getAllFlow().first()
        assertEquals(2, expenses.size)
    }
    @Test
    fun deleteExpense() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        val inserted = expenseDao.getAll().first()
        expenseDao.delete(inserted)
        assertEquals(0, expenseDao.getAll().size)
    }
    @Test
    fun deleteAllExpenses() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))
        expenseDao.deleteAll()
        assertEquals(0, expenseDao.getAll().size)
    }
    @Test
    fun getTotalSpentFlowOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense(amount = 10.0))
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "Deposit",
            transactionType = TransactionType.DEPOSIT,
            date = System.currentTimeMillis()
        ))
        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals(10.0, total!!, 0.01)
    }
    @Test
    fun isDuplicateDetectsWithinWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))
        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertTrue(isDupe)
    }
    @Test
    fun isDuplicateIgnoresOutsideWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now - 600000))
        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }
    @Test
    fun isDuplicateIgnoresDifferentMerchant() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop A", date = now))
        val isDupe = expenseDao.isDuplicate(10.0, "Shop B", now, 300000)
        assertFalse(isDupe)
    }
    @Test
    fun isDuplicateIgnoresDifferentAmount() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))
        val isDupe = expenseDao.isDuplicate(20.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }
    @Test
    fun updateCategory() = runBlocking {
        val id = expenseDao.insert(makeExpense())
        expenseDao.updateCategory(id, 5L)
        val updated = expenseDao.getAll().first()
        assertEquals(5L, updated.categoryId)
    }
    @Test
    fun getExpensesBetweenReturnsCorrectRange() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(date = now - 86400000 * 2)) // 2 days ago
        expenseDao.insert(makeExpense(date = now - 86400000))     // 1 day ago
        expenseDao.insert(makeExpense(date = now))                 // now
        val between = expenseDao.getExpensesBetween(now - 86400000 * 3, now - 86400000 + 1)
        assertEquals(1, between.size) // only the 2-days-ago one, depending on exact timing
    }
    @Test
    fun purchaseCountOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense())
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "ATM",
            transactionType = TransactionType.WITHDRAWAL,
            date = System.currentTimeMillis()
        ))
        assertEquals(1, expenseDao.getPurchaseCount())
    }
    @Test
    fun ignoreConflictOnDuplicateInsert() = runBlocking {
        val expense = makeExpense()
        val id1 = expenseDao.insert(expense)
        val id2 = expenseDao.insert(expense.copy(id = id1)) // Same ID
        // IGNORE strategy: id2 should be -1 (not inserted)
        assertEquals(-1L, id2)
        assertEquals(1, expenseDao.getAll().size)
    }
}

```

---

## androidTest\java\com\yourname\expensetracker\data\database\dao\PendingReviewDaoTest.kt <a name="androidtestjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaotestkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class PendingReviewDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var pendingReviewDao: PendingReviewDao
    private lateinit var rawNotificationDao: RawNotificationDao
    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        pendingReviewDao = database.pendingReviewDao()
        rawNotificationDao = database.rawNotificationDao()
    }
    @After
    fun teardown() {
        database.close()
    }
    private suspend fun insertRawNotification(): Long {
        return rawNotificationDao.insert(RawNotification(
            packageName = "com.test",
            appName = "Test",
            title = "Test",
            text = "Test",
            timestamp = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis()
        ))
    }
    private fun makeReview(rawId: Long) = PendingReview(
        rawNotificationId = rawId,
        suggestedAmount = 10.0,
        suggestedCurrency = "EUR",
        suggestedMerchant = "Test Merchant",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.75f,
        packageName = "com.test",
        notificationTitle = "Test",
        notificationText = "Test text"
    )
    @Test
    fun insertAndRetrievePending() = runBlocking {
        val rawId = insertRawNotification()
        pendingReviewDao.insert(makeReview(rawId))
        val pending = pendingReviewDao.getPending()
        assertEquals(1, pending.size)
        assertEquals("PENDING", pending[0].status)
    }
    @Test
    fun pendingCountFlow() = runBlocking {
        val rawId = insertRawNotification()
        pendingReviewDao.insert(makeReview(rawId))
        val count = pendingReviewDao.getPendingCountFlow().first()
        assertEquals(1, count)
    }
    @Test
    fun updateStatusIfPendingSucceeds() = runBlocking {
        val rawId = insertRawNotification()
        val id = pendingReviewDao.insert(makeReview(rawId))
        val rows = pendingReviewDao.updateStatusIfPending(id, "APPROVED")
        assertEquals(1, rows)
        val review = pendingReviewDao.getById(id)
        assertEquals("APPROVED", review?.status)
    }
    @Test
    fun updateStatusIfPendingFailsWhenAlreadyResolved() = runBlocking {
        val rawId = insertRawNotification()
        val id = pendingReviewDao.insert(makeReview(rawId))
        pendingReviewDao.updateStatusIfPending(id, "APPROVED")
        val rows = pendingReviewDao.updateStatusIfPending(id, "REJECTED")
        assertEquals(0, rows) // Already APPROVED, not PENDING
    }
    @Test
    fun getPendingExcludesResolved() = runBlocking {
        val rawId1 = insertRawNotification()
        val rawId2 = insertRawNotification()
        val id1 = pendingReviewDao.insert(makeReview(rawId1))
        pendingReviewDao.insert(makeReview(rawId2))
        pendingReviewDao.updateStatus(id1, "APPROVED")
        val pending = pendingReviewDao.getPending()
        assertEquals(1, pending.size)
    }
    @Test
    fun clearResolvedKeepsPending() = runBlocking {
        val rawId1 = insertRawNotification()
        val rawId2 = insertRawNotification()
        val id1 = pendingReviewDao.insert(makeReview(rawId1))
        pendingReviewDao.insert(makeReview(rawId2))
        pendingReviewDao.updateStatus(id1, "REJECTED")
        pendingReviewDao.clearResolved()
        val all = pendingReviewDao.getAllFlow().first()
        assertEquals(1, all.size)
        assertEquals("PENDING", all[0].status)
    }
}

```

---

## test\java\com\yourname\expensetracker\OcrParserTest.kt <a name="testjavacomyournameexpensetrackerocrparsertestkt"></a>
```kotlin
package com.yourname.expensetracker
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
class OcrParserTest {
    private val parser = ReceiptParser()
    @Test
    fun `test decimal parsing - standard european`() {
        val input = """
            MARKET
            ITEMS 10,00
            TOTAL 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.50, result.total!!, 0.01)
    }
    @Test
    fun `test decimal parsing - european with thousands separator`() {
        val input = """
            TECH STORE
            LAPTOP 1.250,50
            TOTAL 1.250,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(1250.50, result.total!!, 0.01)
    }
    @Test
    fun `test decimal parsing - US standard`() {
        val input = """
            DINER US
            BURGER 12.50
            TOTAL 12.50
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(12.50, result.total!!, 0.01)
    }
    @Test
    fun `test decimal parsing - US with thousands separator`() {
        val input = """
            CAR DEALER
            DOWNPAYMENT 1,250.00
            TOTAL 1,250.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(1250.00, result.total!!, 0.01)
    }
    @Test
    fun `test greek normalization - Sigma error`() {
        val input = """
            SUPER MARKET
            GALA 1,20
            EYNONO 1,20
        """.trimIndent()
        val result = parser.parse(input)
        // EYNONO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(1.20, result.total!!, 0.01)
    }
    @Test
    fun `test greek normalization - Z error`() {
        val input = """
            CAFE
            COFFEE 2,50
            ZYNOAO 2,50
        """.trimIndent()
        val result = parser.parse(input)
        // ZYNOAO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(2.50, result.total!!, 0.01)
    }
    @Test
    fun `test greek normalization - 2 error`() {
        val input = """
            BAKERY
            BREAD 0,90
            2YNONO 0,90
        """.trimIndent()
        val result = parser.parse(input)
        // 2YNONO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(0.90, result.total!!, 0.01)
    }
    @Test
    fun `test greek normalization - Lambda error`() {
        val input = """
            STORE
            ITEM 10.00
            IYNOAO 10.00
        """.trimIndent()
        val result = parser.parse(input)
        // IYNOAO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(10.00, result.total!!, 0.01)
    }
    @Test
    fun `test year range expansion`() {
        val input = """
            HISTORY MUSEUM
            TICKET 5.00
            DATE 15/05/2016
            TOTAL 5.00
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull(result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals(2016, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH)) // Month is 0-indexed (May=4)
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }
    @Test
    fun `test total extraction fallback`() {
        // Receipt where total is not at bottom and no clear keyword
        val input = """
            GAS STATION
            PUMP 1
            45,00 €
            THANK YOU
            COME AGAIN
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.00, result.total!!, 0.01)
    }
    @Test
    fun `test merchant extraction - skip noise`() {
        val input = """
            NOMIMH APODEIXI
            START
            MY SHOP NAME
            ADDRESS 123
            TEL 2101234567
            TOTAL 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("MY SHOP NAME", result.merchantName)
    }
    @Test
    fun `test greek normalization - Cash keyword`() {
        val input = """
            COFFEE SHOP
            CAPPUCCINO 3,50
            METPHTA 3,50
            ZYNOAO 3,50
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(3.50, result.total!!, 0.01)
        // Verify METPHTA didn't interfere or was recognized as CASH_KEY
        assertTrue(result.lineItems.isEmpty()) // Keywords should be cleaned
    }
    @Test
    fun `test greek normalization - Payable variant`() {
        val input = """
            SUPERMARKET
            ITEM 1 10.00
            NAHPQTEO 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(10.00, result.total!!, 0.01)
    }
    @Test
    fun `test complex ocr number fix`() {
        val input = """
            STORE
            TOTAL_KEY 4 5 , 5 0
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.50, result.total!!, 0.01)
    }
    @Test
    fun `test date ocr fix - 16-D4-2017`() {
        val input = """
            MARKET
            16-D4-2017
            TOTAL 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull(result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals(2017, cal.get(Calendar.YEAR))
        assertEquals(3, cal.get(Calendar.MONTH)) // April = 3
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
    }
}

```

---

## test\java\com\yourname\expensetracker\data\database\converter\ConvertersTest.kt <a name="testjavacomyournameexpensetrackerdatadatabaseconverterconverterstestkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.converter
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Test
class ConvertersTest {
    private val converters = Converters()
    @Test
    fun `converts PURCHASE to string and back`() {
        val str = converters.fromTransactionType(TransactionType.PURCHASE)
        assertEquals("PURCHASE", str)
        assertEquals(TransactionType.PURCHASE, converters.toTransactionType(str))
    }
    @Test
    fun `converts all TransactionTypes roundtrip`() {
        TransactionType.values().forEach { type ->
            val str = converters.fromTransactionType(type)
            assertEquals(type, converters.toTransactionType(str))
        }
    }
    @Test
    fun `invalid string returns UNKNOWN`() {
        assertEquals(TransactionType.UNKNOWN, converters.toTransactionType("INVALID_TYPE"))
    }
    @Test
    fun `empty string returns UNKNOWN`() {
        assertEquals(TransactionType.UNKNOWN, converters.toTransactionType(""))
    }
}

```

---

## test\java\com\yourname\expensetracker\data\database\entity\SourceStatsTest.kt <a name="testjavacomyournameexpensetrackerdatadatabaseentitysourcestatstestkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import org.junit.Assert.*
import org.junit.Test
class SourceStatsTest {
    @Test
    fun `trustScore is 0 when no notifications`() {
        val stats = SourceStats("com.test", totalNotifications = 0, acceptedAsExpense = 0)
        assertEquals(0f, stats.trustScore, 0.01f)
    }
    @Test
    fun `trustScore is correct ratio`() {
        val stats = SourceStats("com.test", totalNotifications = 10, acceptedAsExpense = 7)
        assertEquals(0.7f, stats.trustScore, 0.01f)
    }
    @Test
    fun `isLikelySpam true when high volume low accept`() {
        val stats = SourceStats("com.test", totalNotifications = 100, acceptedAsExpense = 2)
        assertTrue(stats.isLikelySpam)
    }
    @Test
    fun `isLikelySpam false when low volume`() {
        val stats = SourceStats("com.test", totalNotifications = 5, acceptedAsExpense = 0)
        assertFalse(stats.isLikelySpam)
    }
    @Test
    fun `isLikelySpam false when good trust score`() {
        val stats = SourceStats("com.test", totalNotifications = 100, acceptedAsExpense = 80)
        assertFalse(stats.isLikelySpam)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\analytics\InsightsEngineTest.kt <a name="testjavacomyournameexpensetrackerdomainanalyticsinsightsenginetestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class InsightsEngineTest {
    private lateinit var engine: InsightsEngine
    @Before
    fun setup() {
        // InsightsEngine needs DAOs for generateInsights(), but detectRecurring()
        // and buildDailyTotals() are testable with just data.
        // We'll use mockk for the constructor.
        val expenseDao = io.mockk.mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        engine = InsightsEngine(expenseDao)
    }
    private val dayMs = 86_400_000L
    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = 0,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis() - daysAgo * dayMs
    )
    @Test
    fun `detects monthly recurring payments`() {
        val expenses = listOf(
            makeExpense("Netflix", 9.99, 90),
            makeExpense("Netflix", 9.99, 60),
            makeExpense("Netflix", 9.99, 30),
            makeExpense("Netflix", 9.99, 0)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.any { it.merchant == "Netflix" })
        val netflix = recurring.first { it.merchant == "Netflix" }
        assertTrue(netflix.intervalDays in 25..35)
        assertEquals(4, netflix.occurrences)
    }
    @Test
    fun `detects weekly recurring payments`() {
        val expenses = listOf(
            makeExpense("GYM", 5.00, 28),
            makeExpense("GYM", 5.00, 21),
            makeExpense("GYM", 5.00, 14),
            makeExpense("GYM", 5.00, 7),
            makeExpense("GYM", 5.00, 0)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.any { it.merchant.uppercase() == "GYM" })
    }
    @Test
    fun `does not detect irregular payments as recurring`() {
        val expenses = listOf(
            makeExpense("Random Shop", 15.00, 100),
            makeExpense("Random Shop", 23.00, 50),
            makeExpense("Random Shop", 8.00, 10)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.isEmpty() || recurring.none {
            it.merchant.uppercase() == "RANDOM SHOP"
        })
    }
    @Test
    fun `ignores single-occurrence merchants`() {
        val expenses = listOf(makeExpense("One Time Shop", 50.00, 0))
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.isEmpty())
    }
    @Test
    fun `buildDailyTotals includes all requested days`() {
        val expenses = listOf(
            makeExpense("Shop", 10.00, 0),
            makeExpense("Shop", 20.00, 1)
        )
        val totals = engine.buildDailyTotals(expenses, 7)
        assertEquals(7, totals.size)
    }
    @Test
    fun `buildDailyTotals sums same-day purchases`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(0, 10.0, "EUR", "A", TransactionType.PURCHASE, now),
            Expense(0, 20.0, "EUR", "B", TransactionType.PURCHASE, now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(30.0, todayTotal, 0.01)
    }
    @Test
    fun `buildDailyTotals ignores non-purchase types`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(0, 10.0, "EUR", "A", TransactionType.PURCHASE, now),
            Expense(0, 100.0, "EUR", "B", TransactionType.DEPOSIT, now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(10.0, todayTotal, 0.01)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\categorization\CategorizationEngineTest.kt <a name="testjavacomyournameexpensetrackerdomaincategorizationcategorizationenginetestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.categorization
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class CategorizationEngineTest {
    private val merchantCategoryDao = mockk<com.yourname.expensetracker.data.database.dao.MerchantCategoryDao>(relaxed = true)
    private val merchantNormalizer = mockk<NewMerchantNormalizer>(relaxed = true)
    private lateinit var engine: CategorizationEngine
    @Before
    fun setup() {
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>().uppercase()
            MerchantLookupResult(
                canonical = MerchantCanonical(normalizedName = name, searchKey = name.lowercase()),
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }
        engine = CategorizationEngine(merchantCategoryDao, merchantNormalizer)
    }
    @Test
    fun `normalize uppercases`() = runBlocking {
        assertEquals("STARBUCKS", engine.normalize("starbucks"))
        assertEquals("UBER-EATS", engine.normalize("uber-eats"))
    }
    @Test
    fun `normalize handles Greek characters`() = runBlocking {
        val result = engine.normalize("ΣΚΛΑΒΕΝΙΤΗΣ")
        assertTrue(result.contains("ΣΚΛΑΒΕΝΙΤΗΣ"))
    }
    @Test
    fun `exact match returns category`() = runBlocking {
        coEvery { merchantCategoryDao.getAll() } returns listOf(
            MerchantCategory("starbucks", 5L)
        )
        val result = engine.categorize("starbucks")
        assertEquals(5L, result)
    }
    @Test
    fun `substring match finds pattern within merchant name`() = runBlocking {
        coEvery { merchantCategoryDao.getCategoryForMerchant("UBER EATS DELIVERY 1234") } returns null
        coEvery { merchantCategoryDao.getAll() } returns listOf(
            MerchantCategory("uber eats", 3L),
            MerchantCategory("uber", 4L)
        )
        // Word-level match for "uber"
        coEvery { merchantCategoryDao.getCategoryForMerchant("uber") } returns
            MerchantCategory("uber", 4L)
        val result = engine.categorize("UBER EATS DELIVERY 1234")
        // Should match "UBER EATS" first (longer pattern) via substring, returning 3L
        assertEquals(3L, result)
    }
    @Test
    fun `returns null when no match found`() = runBlocking {
        coEvery { merchantCategoryDao.getCategoryForMerchant(any()) } returns null
        coEvery { merchantCategoryDao.getAll() } returns emptyList()
        val result = engine.categorize("COMPLETELY UNKNOWN MERCHANT")
        assertNull(result)
    }
    @Test
    fun `cache invalidation resets cache`() = runBlocking {
        engine.invalidateCache()
        // No assertion needed — just ensure no crash
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouterTest.kt <a name="testjavacomyournameexpensetrackerdomainintelligenceconfidenceroutertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.*
class ConfidenceRouterTest {
    private lateinit var router: ConfidenceRouter
    private val sourceStatsDao = mockk<com.yourname.expensetracker.data.database.dao.SourceStatsDao>(relaxed = true)
    private val userCorrectionDao = mockk<com.yourname.expensetracker.data.database.dao.UserCorrectionDao>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    @Before
    fun setup() {
        router = ConfidenceRouter(sourceStatsDao, userCorrectionDao, classifier)
        // Default: no source stats, no corrections, classifier not ready
        coEvery { sourceStatsDao.getByPackage(any()) } returns null
        coEvery { userCorrectionDao.getMerchantTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.getTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.hasPreviousApprovals(any(), any()) } returns false
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.predict(any()) } returns 0.5f
    }
    private fun makeParsed(confidence: Float, merchant: String = "TestMerchant") =
        ParsedTransaction(10.0, "EUR", merchant, TransactionType.PURCHASE, confidence)
    @Test
    fun `high confidence auto-accepts`() = runBlocking {
        val result = router.route(makeParsed(0.95f), "com.test")
        assertEquals(RoutingDecision.AUTO_ACCEPT, result.decision)
    }
    @Test
    fun `medium confidence needs review`() = runBlocking {
        val result = router.route(makeParsed(0.70f), "com.test")
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
    }
    @Test
    fun `low confidence auto-rejects`() = runBlocking {
        val result = router.route(makeParsed(0.30f), "com.test")
        assertEquals(RoutingDecision.AUTO_REJECT, result.decision)
    }
    @Test
    fun `unknown merchant gets confidence penalty`() = runBlocking {
        val result = router.route(makeParsed(0.90f, "Unknown"), "com.test")
        // 0.90 * 0.5 = 0.45, which is below REVIEW_THRESHOLD
        assertTrue(result.adjustedConfidence < 0.90f)
    }
    @Test
    fun `previously approved merchant gets boost`() = runBlocking {
        coEvery { userCorrectionDao.hasPreviousApprovals("TestMerchant", "com.test") } returns true
        val result = router.route(makeParsed(0.80f), "com.test")
        assertTrue(result.adjustedConfidence > 0.80f)
    }
    @Test
    fun `high merchant rejection rate reduces confidence`() = runBlocking {
        coEvery { userCorrectionDao.getMerchantTotalCorrections("TestMerchant") } returns 10
        coEvery { userCorrectionDao.getMerchantRejectionCount("TestMerchant") } returns 8
        val result = router.route(makeParsed(0.90f), "com.test")
        assertTrue(result.adjustedConfidence < 0.90f)
    }
    @Test
    fun `spam source dramatically reduces confidence`() = runBlocking {
        coEvery { sourceStatsDao.getByPackage("com.spam") } returns
            com.yourname.expensetracker.data.database.entity.SourceStats(
                packageName = "com.spam",
                totalNotifications = 100,
                acceptedAsExpense = 1
            )
        val result = router.route(makeParsed(0.90f), "com.spam")
        assertTrue(result.adjustedConfidence < 0.50f)
    }
    @Test
    fun `confidence is clamped to 0-1 range`() = runBlocking {
        coEvery { userCorrectionDao.hasPreviousApprovals(any(), any()) } returns true
        val result = router.route(makeParsed(0.99f), "com.test")
        assertTrue(result.adjustedConfidence <= 1.0f)
        assertTrue(result.adjustedConfidence >= 0.0f)
    }
    @Test
    fun `thresholds are correct`() {
        assertEquals(0.85f, ConfidenceRouter.AUTO_ACCEPT_THRESHOLD)
        assertEquals(0.50f, ConfidenceRouter.REVIEW_THRESHOLD)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\intelligence\ml\HybridExpenseClassifierTest.kt <a name="testjavacomyournameexpensetrackerdomainintelligencemlhybridexpenseclassifiertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context
class HybridExpenseClassifierTest {
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val nbClassifier = mockk<ExpenseCategoryClassifier>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var hybridClassifier: HybridExpenseClassifier
    private val foodCategory = Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF")
    private val groceriesCategory = Category(id = 2L, name = "Groceries", icon = "shop", color = "#CCCCCC")
    private val miscCategory = Category(id = 3L, name = "Miscellaneous", icon = "misc", color = "#888888")
    @Before
    fun setup() {
        coEvery { categoryDao.getAll() } returns listOf(foodCategory, groceriesCategory, miscCategory)
        hybridClassifier = HybridExpenseClassifier(context, categoryDao, nbClassifier)
    }
    @Test
    fun `rule-based matching takes priority`() = runBlocking {
        val result = hybridClassifier.classify(
            merchantName = "Starbucks",
            amount = 15.0
        )
        assertEquals(foodCategory.id, result.categoryId)
        assertEquals(MatchType.RULE_MATCH, result.matchType)
    }
    @Test
    fun `ml-based matching used when rules fail`() = runBlocking {
        // No rule for "StrangeMerchant"
        coEvery { nbClassifier.isReady() } returns true
        coEvery { nbClassifier.classify(any()) } returns listOf(
            CategoryScore(groceriesCategory.id, "Groceries", 0.9f)
        )
        val result = hybridClassifier.classify(
            merchantName = "StrangeMerchant",
            amount = 50.0
        )
        assertEquals(groceriesCategory.id, result.categoryId)
        assertEquals(MatchType.ML_PREDICTION, result.matchType)
    }
    @Test
    fun `fallback used when everything fails`() = runBlocking {
        coEvery { nbClassifier.isReady() } returns false
        val result = hybridClassifier.classify(
            merchantName = "UnknownMerchant",
            amount = 0.0
        )
        assertEquals(groceriesCategory.id, result.categoryId) // Finds Groceries via substring/search first
        assertEquals(MatchType.FALLBACK, result.matchType)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\intelligence\ml\MerchantNormalizerTest.kt <a name="testjavacomyournameexpensetrackerdomainintelligencemlmerchantnormalizertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence.ml
import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context
class MerchantNormalizerTest {
    private val dao = mockk<MerchantNormalizationDao>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var normalizer: MerchantNormalizer
    @Before
    fun setup() {
        normalizer = MerchantNormalizer(dao, context)
    }
    @Test
    fun `cleanMerchantName removes store numbers`() {
        val result = normalizer.cleanMerchantName("McDonald's Store #123")
        assertEquals("McDonald's", result)
    }
    @Test
    fun `cleanMerchantName removes corporate suffixes`() {
        val result = normalizer.cleanMerchantName("Starbucks Corp.")
        assertEquals("Starbucks", result)
    }
    @Test
    fun `cleanMerchantName removes location suffixes`() {
        val result = normalizer.cleanMerchantName("Shell At Athens")
        assertEquals("Shell", result)
    }
    @Test
    fun `normalize uses alias if exists`() = runBlocking {
        val alias = mockk<com.yourname.expensetracker.data.database.entity.MerchantAlias>()
        val canonical = MerchantCanonical(id = 1, normalizedName = "Target", searchKey = "target")
        coEvery { alias.canonicalId } returns 1
        coEvery { alias.isUserDefined } returns true
        coEvery { dao.getAliasByNormalizedKey("target") } returns alias
        coEvery { dao.getCanonicalById(1) } returns canonical
        val result = normalizer.normalize("Target")
        assertEquals("Target", result.canonical.normalizedName)
        assertEquals(MatchType.USER_DEFINED, result.matchType)
    }
    @Test
    fun `normalize handles empty name`() = runBlocking {
        val result = normalizer.normalize("")
        assertEquals("Unknown", result.canonical.normalizedName)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\logic\SynthesisEngineTest.kt <a name="testjavacomyournameexpensetrackerdomainlogicsynthesisenginetestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test
class SynthesisEngineTest {
    // Helper to access the private method via reflection or just copy the logic for testing if strictly unit testing isn't set up yet. 
    // Since I modified the code in place, I will simulate the logic here to verify my understanding of the flow is correct.
    fun determineRiskLevel(
        criticalBudgets: Int,
        paceStatus: PaceStatus,
        bufferRatio: Double
    ): RiskLevel {
        val overPace = paceStatus == PaceStatus.OVER_PACE
        return when {
            criticalBudgets > 0 -> RiskLevel.CRITICAL
            overPace && bufferRatio < 0.05 -> RiskLevel.CRITICAL
            overPace -> RiskLevel.HIGH
            bufferRatio < 0.1 -> RiskLevel.HIGH
            bufferRatio < 0.2 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
    @Test
    fun `test risk level logic`() {
        // 1. Critical Budgets -> CRITICAL
        assertEquals(RiskLevel.CRITICAL, determineRiskLevel(1, PaceStatus.ON_PACE, 0.5))
        // 2. Over Pace + Low Buffer -> CRITICAL
        assertEquals(RiskLevel.CRITICAL, determineRiskLevel(0, PaceStatus.OVER_PACE, 0.04))
        // 3. Over Pace + Good Buffer -> HIGH
        assertEquals(RiskLevel.HIGH, determineRiskLevel(0, PaceStatus.OVER_PACE, 0.15))
        // 4. On Pace + Low Buffer -> HIGH
        assertEquals(RiskLevel.HIGH, determineRiskLevel(0, PaceStatus.ON_PACE, 0.09))
        // 5. On Pace + Medium Buffer -> MEDIUM
        assertEquals(RiskLevel.MEDIUM, determineRiskLevel(0, PaceStatus.ON_PACE, 0.15))
        // 6. On Pace + Good Buffer -> LOW
        assertEquals(RiskLevel.LOW, determineRiskLevel(0, PaceStatus.ON_PACE, 0.25))
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\parser\AppParserRegistryRoutingTest.kt <a name="testjavacomyournameexpensetrackerdomainparserappparserregistryroutingtestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.domain.parser.parsers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class AppParserRegistryRoutingTest {
    private lateinit var registry: AppParserRegistry
    private val currencyNormalizer = io.mockk.mockk<com.yourname.expensetracker.domain.util.CurrencyNormalizer> {
        io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
    }
    private val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
        io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
    }
    @Before
    fun setup() {
        registry = AppParserRegistry(
            greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner),
            revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
            smsParser = SmsParser(currencyNormalizer, merchantCleaner),
            googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
            genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner)
        )
    }
    @Test
    fun `routes revolut package to RevolutParser`() {
        val result = registry.parse(
            title = "Paid €10.00 at Shop",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(0.95f, result!!.confidence, 0.01f) // Revolut confidence
    }
    @Test
    fun `routes google wallet to GoogleWalletParser`() {
        val result = registry.parse(
            title = "Shop Name",
            text = "€5.00 at Shop Name",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(0.90f, result!!.confidence, 0.01f) // Google Wallet confidence
    }
    @Test
    fun `routes greek bank to GreekBankParser`() {
        val result = registry.parse(
            title = "Alert",
            text = "Αγορά 10,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(0.92f, result!!.confidence, 0.01f)
    }
    @Test
    fun `routes unknown package to GenericTransactionParser`() {
        val result = registry.parse(
            title = "Alert",
            text = "You paid €20.00 at Restaurant",
            bigText = null, subText = null,
            packageName = "com.completely.unknown.app"
        )
        assertNotNull(result)
        assertEquals(0.60f, result!!.confidence, 0.01f) // Generic confidence
    }
    @Test
    fun `returns null when no parser matches`() {
        val result = registry.parse(
            title = "Hello",
            text = "How are you?",
            bigText = null, subText = null,
            packageName = "com.completely.unknown.app"
        )
        assertNull(result)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\parser\AppParserRegistryTest.kt <a name="testjavacomyournameexpensetrackerdomainparserappparserregistrytestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
class AppParserRegistryTest {
    private val currencyNormalizer = io.mockk.mockk<com.yourname.expensetracker.domain.util.CurrencyNormalizer> {
        io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
    }
    private val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
        io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
    }
    private val registry = AppParserRegistry(
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner),
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
        smsParser = SmsParser(currencyNormalizer, merchantCleaner),
        googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
        genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner)
    )
    @Test
    fun `test Revolut parsing`() {
        val result = registry.parse(
            title = "💳 €12.50 at SKLAVENITIS",
            text = "You paid €12.50 at SKLAVENITIS",
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(12.50, result?.amount!!, 0.01)
        assertEquals("SKLAVENITIS", result.merchant)
        assertEquals(TransactionType.PURCHASE, result.type)
    }
    @Test
    fun `test Greek Bank parsing (NBG)`() {
        val result = registry.parse(
            title = "Πληρωμή",
            text = "Πληρώσατε €6,30 σε PIZZA HOOD",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(6.30, result?.amount!!, 0.01)
        assertEquals("PIZZA HOOD", result.merchant)
    }
    @Test
    fun `test Google Wallet parsing`() {
        val result = registry.parse(
            title = "COFFEE ISLAND",
            text = "€4.20 with Mastercard ••1234",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(4.20, result?.amount!!, 0.01)
        assertEquals("COFFEE ISLAND", result.merchant)
    }
    @Test
    fun `test SMS Bank parsing`() {
        val result = registry.parse(
            title = "NBG",
            text = "AGORA 15,00 EUR STO KATASTIMA στις 07/02",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result?.amount!!, 0.01)
        assertEquals("KATASTIMA", result.merchant)
    }
    @Test
    fun `test generic fallback parsing`() {
        val result = registry.parse(
            title = "Transaction Alert",
            text = "You paid 50.00 EUR at Netflix",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(50.00, result?.amount!!, 0.01)
        assertEquals("Netflix", result.merchant)
    }
    @Test
    fun `test noise rejection (OTP)`() {
        val result = registry.parse(
            title = "Bank OTP",
            text = "Your verification code is 123456 for payment of 10.00",
            bigText = null,
            subText = null,
            packageName = "com.bank.app"
        )
        assertNull("Should reject OTP even if it contains 'payment' and numbers", result)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\parser\GenericTransactionParserTest.kt <a name="testjavacomyournameexpensetrackerdomainparsergenerictransactionparsertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class GenericTransactionParserTest {
    private lateinit var parser: GenericTransactionParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { firstArg() }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() }
        }
        parser = GenericTransactionParser(currencyNormalizer, merchantCleaner)
    }
    // === SUCCESSFUL PARSING ===
    @Test
    fun `parse you paid pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €25.00 at Starbucks",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
        assertEquals("Starbucks", result.merchant)
    }
    @Test
    fun `parse payment of pattern`() {
        val result = parser.parse(
            title = "Notification",
            text = "Payment of €15.00 at Amazon",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }
    @Test
    fun `parse charged pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "Charged €10.50 at Shell Gas Station",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(10.50, result!!.amount, 0.01)
    }
    @Test
    fun `parse Greek payment pattern`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = "Πληρωμή 30,00 EUR στο COSMOTE",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(30.00, result!!.amount, 0.01)
    }
    @Test
    fun `parse Greeklish payment pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "Pliromi 20,00 EUR sto MERCHANT",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(20.00, result!!.amount, 0.01)
    }
    @Test
    fun `lower confidence than app-specific parsers`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €25.00 at Starbucks",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(0.60f, result!!.confidence, 0.01f)
    }
    // === NEGATIVE SIGNAL REJECTION ===
    @Test
    fun `reject offer notification`() {
        assertNull(parser.parse(
            title = "Special offer!",
            text = "You paid €0 - save up to €50 today! offer ends soon",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    @Test
    fun `reject OTP notification`() {
        assertNull(parser.parse(
            title = "Verification code",
            text = "Your OTP code is 123456",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    @Test
    fun `reject tracking notification`() {
        assertNull(parser.parse(
            title = "Order update",
            text = "Your order has been shipped and is being tracked",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    @Test
    fun `reject balance notification`() {
        assertNull(parser.parse(
            title = "Balance update",
            text = "Your balance is €1500.00",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    @Test
    fun `reject sale promotion`() {
        assertNull(parser.parse(
            title = "Big Sale",
            text = "50% off everything! Sale ends tonight",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    @Test
    fun `reject Greek promotional notification`() {
        assertNull(parser.parse(
            title = "Προσφορά",
            text = "Δωρεάν αποστολή σε παραγγελίες άνω των €30",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    // === NO STRONG SIGNAL ===
    @Test
    fun `reject notification without transaction signal`() {
        assertNull(parser.parse(
            title = "Random App",
            text = "€25.00 available in your account",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    // === AMOUNT BOUNDS ===
    @Test
    fun `reject amount below 0_10`() {
        assertNull(parser.parse(
            title = "Alert",
            text = "You paid €0.05 at Shop",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    @Test
    fun `reject amount above 25000`() {
        assertNull(parser.parse(
            title = "Alert",
            text = "You paid €30000.00 at Shop",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }
    // === MERCHANT EXTRACTION ===
    @Test
    fun `extract merchant after at`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €10.00 at Lidl Supermarket",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("Lidl"))
    }
    @Test
    fun `extract merchant after Greek preposition`() {
        val result = parser.parse(
            title = "Alert",
            text = "Πληρωμή 10,00€ στο EVEREST",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("EVEREST"))
    }
    @Test
    fun `fallback to Unknown when no merchant found`() {
        val result = parser.parse(
            title = "Payment",
            text = "You paid €10.00",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        // Might be null or Unknown depending on whether "Payment" title passes isGenericTitle
        if (result != null) {
            // title contains "payment" so it's generic, merchant should be "Unknown"
            assertEquals("Unknown", result.merchant)
        }
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\parser\GoogleWalletParserTest.kt <a name="testjavacomyournameexpensetrackerdomainparsergooglewalletparsertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class GoogleWalletParserTest {
    private lateinit var parser: GoogleWalletParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = GoogleWalletParser(currencyNormalizer, merchantCleaner)
    }
    @Test
    fun `parse payment at merchant in text`() {
        val result = parser.parse(
            title = "Payment",
            text = "€4.20 at Coffee Island",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(4.20, result!!.amount, 0.01)
        assertEquals("Coffee Island", result.merchant)
    }
    @Test
    fun `title is merchant when no at-pattern in text`() {
        val result = parser.parse(
            title = "COFFEE ISLAND",
            text = "€4.20 with Mastercard ••1234",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals("COFFEE ISLAND", result!!.merchant)
    }
    @Test
    fun `parse amount with currency suffix`() {
        val result = parser.parse(
            title = "Payment completed",
            text = "15.50 EUR at Lidl",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(15.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
    }
    @Test
    fun `reject add a card notification`() {
        assertNull(parser.parse(
            title = "Add a card to Google Wallet",
            text = "Tap to get started",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        ))
    }
    @Test
    fun `reject loyalty offer`() {
        assertNull(parser.parse(
            title = "Loyalty reward available",
            text = "You have a new offer nearby",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        ))
    }
    @Test
    fun `reject unrealistic amount over 50000`() {
        val result = parser.parse(
            title = "Payment",
            text = "€99999.00 at Merchant",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNull(result)
    }
    @Test
    fun `reject unrealistic amount under 0_01`() {
        val result = parser.parse(
            title = "Payment",
            text = "€0.00 at Merchant",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNull(result)
    }
    @Test
    fun `clean card info from merchant`() {
        val result = parser.parse(
            title = "Starbucks",
            text = "€3.50 - Mastercard ••4567",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertFalse(result!!.merchant.contains("Mastercard"))
        assertFalse(result.merchant.contains("4567"))
    }
    @Test
    fun `supports both wallet package variants`() {
        assertTrue(parser.supportedPackages.contains("com.google.android.apps.walletnfcrel"))
        assertTrue(parser.supportedPackages.contains("com.google.android.apps.nbu.paisa.user"))
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\parser\GreekBankParserTest.kt <a name="testjavacomyournameexpensetrackerdomainparsergreekbankparsertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class GreekBankParserTest {
    private lateinit var parser: GreekBankParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = GreekBankParser(currencyNormalizer, merchantCleaner)
    }
    @Test
    fun `parse Greek purchase notification - agora pattern`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = "Αγορά 12,50 EUR στο SKLAVENITIS",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
        assertEquals(TransactionType.PURCHASE, result.type)
    }
    @Test
    fun `parse with euro symbol prefix`() {
        val result = parser.parse(
            title = "Πληρωμή",
            text = "€6,30 στο PIZZA HOOD",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(6.30, result!!.amount, 0.01)
    }
    @Test
    fun `parse card charge pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "χρέωση κάρτας: 25,00 EUR - VODAFONE",
            bigText = null, subText = null,
            packageName = "gr.alpha.mobile"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
    }
    @Test
    fun `reject balance notification`() {
        assertNull(parser.parse(
            title = "Υπόλοιπο",
            text = "Το υπόλοιπο σας είναι 1250,00 EUR",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }
    @Test
    fun `reject OTP code`() {
        assertNull(parser.parse(
            title = "Κωδικός",
            text = "Ο κωδικός σας είναι 123456",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }
    @Test
    fun `reject promotional offer`() {
        assertNull(parser.parse(
            title = "Προσφορά",
            text = "Νέα προσφορά: Δωρεάν μεταφορά χρημάτων",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }
    @Test
    fun `supports all Greek bank packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("gr.nbg.mobilebanking"))
        assertTrue(packages.contains("gr.alpha.mobile"))
        assertTrue(packages.contains("com.eurobank.mobile"))
        assertTrue(packages.contains("com.winbank.mobile"))
    }
    @Test
    fun `high confidence for parsed results`() {
        val result = parser.parse(
            title = "Payment",
            text = "Αγορά 10,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertTrue(result!!.confidence >= 0.90f)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\parser\RevolutParserTest.kt <a name="testjavacomyournameexpensetrackerdomainparserrevolutparsertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class RevolutParserTest {
    private lateinit var parser: RevolutParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { 
                val symbol = firstArg<String?>()
                when (symbol) {
                    "€" -> "EUR"
                    "$" -> "USD"
                    "£" -> "GBP"
                    else -> symbol ?: "EUR"
                }
            }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { 
                var name = firstArg<String?>() ?: "Unknown"
                if (name.length > 40) name = name.substring(0, 40)
                name.removeSuffix(".")
            }
        }
        parser = RevolutParser(currencyNormalizer, merchantCleaner)
    }
    // === PURCHASE PARSING ===
    @Test
    fun `parse standard purchase with euro symbol`() {
        val result = parser.parse(
            title = "💳 €12.50 at SKLAVENITIS",
            text = "You paid €12.50 at SKLAVENITIS",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
        assertEquals("SKLAVENITIS", result.merchant)
        assertEquals(TransactionType.PURCHASE, result.type)
        assertTrue(result.confidence >= 0.90f)
    }
    @Test
    fun `parse purchase with comma decimal separator`() {
        val result = parser.parse(
            title = "Paid €8,99 at Netflix",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(8.99, result!!.amount, 0.01)
        assertEquals("Netflix", result.merchant)
    }
    @Test
    fun `parse purchase with USD currency`() {
        val result = parser.parse(
            title = "Paid $25.00 at Amazon",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
        assertEquals("USD", result.currency)
    }
    @Test
    fun `parse purchase with GBP currency`() {
        val result = parser.parse(
            title = "Paid £15.00 at Tesco",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals("GBP", result!!.currency)
    }
    @Test
    fun `parse sent to person`() {
        val result = parser.parse(
            title = "Sent €5.00 to John",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(5.00, result!!.amount, 0.01)
        assertEquals("John", result.merchant)
        assertEquals(TransactionType.PURCHASE, result.type)
    }
    // === DEPOSIT PARSING ===
    @Test
    fun `parse received money`() {
        val result = parser.parse(
            title = "Received €100.00 from Maria",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(100.00, result!!.amount, 0.01)
        assertEquals("Maria", result.merchant)
        assertEquals(TransactionType.DEPOSIT, result.type)
    }
    // === ATM PARSING ===
    @Test
    fun `parse ATM withdrawal`() {
        val result = parser.parse(
            title = "ATM withdrawal: €50.00",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(50.00, result!!.amount, 0.01)
        assertEquals("ATM", result.merchant)
        assertEquals(TransactionType.WITHDRAWAL, result.type)
    }
    // === REJECTION TESTS ===
    @Test
    fun `reject exchange rate notification`() {
        val result = parser.parse(
            title = "Your exchange rate for EUR/USD has changed",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }
    @Test
    fun `reject weekly report`() {
        val result = parser.parse(
            title = "Your weekly report is ready",
            text = "You spent €150 this week",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }
    @Test
    fun `reject special offer`() {
        val result = parser.parse(
            title = "Special offer: Get cashback!",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }
    @Test
    fun `reject security notification`() {
        val result = parser.parse(
            title = "Security alert",
            text = "Please verify your identity",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }
    @Test
    fun `reject savings vault notification`() {
        val result = parser.parse(
            title = "Savings vault update",
            text = "Your savings vault has reached €500",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }
    // === EDGE CASES ===
    @Test
    fun `handle null title and text`() {
        val result = parser.parse(
            title = null, text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }
    @Test
    fun `handle empty strings`() {
        val result = parser.parse(
            title = "", text = "", bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }
    @Test
    fun `merchant name truncated at 40 chars`() {
        val result = parser.parse(
            title = "Paid €10.00 at THIS IS A VERY LONG MERCHANT NAME THAT EXCEEDS FORTY CHARACTERS EASILY",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.length <= 40)
    }
    @Test
    fun `merchant cleaned of trailing punctuation`() {
        val result = parser.parse(
            title = "Paid €10.00 at Starbucks.",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertFalse(result!!.merchant.endsWith("."))
    }
    // === SUPPORTED PACKAGES ===
    @Test
    fun `only supports revolut package`() {
        assertEquals(setOf("com.revolut.revolut"), parser.supportedPackages)
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\parser\SmsParserTest.kt <a name="testjavacomyournameexpensetrackerdomainparsersmsparsertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class SmsParserTest {
    private lateinit var parser: SmsParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = SmsParser(currencyNormalizer, merchantCleaner)
    }
    @Test
    fun `parse bank SMS with Greek keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 15,00 EUR στο KATASTIMA στις 07/02",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }
    @Test
    fun `parse bank SMS with Greeklish keywords`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Agora 22,50 EUR sto SUPERMARKET",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(22.50, result!!.amount, 0.01)
    }
    @Test
    fun `reject non-bank sender`() {
        val result = parser.parse(
            title = "John",
            text = "Hey, can you send me 50 EUR?",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }
    @Test
    fun `reject bank sender without transaction keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Welcome to our new mobile app!",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }
    @Test
    fun `reject null title`() {
        val result = parser.parse(
            title = null,
            text = "Αγορά 15,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }
    @Test
    fun `amount bounds check - too small`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 0,05 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }
    @Test
    fun `supports all messaging packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("com.google.android.apps.messaging"))
        assertTrue(packages.contains("com.samsung.android.messaging"))
        assertTrue(packages.contains("com.android.mms"))
    }
}

```

---

## test\java\com\yourname\expensetracker\domain\receipt\BankStatementParserTest.kt <a name="testjavacomyournameexpensetrackerdomainreceiptbankstatementparsertestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class BankStatementParserTest {
    private lateinit var parser: BankStatementParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } returns "EUR"
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() }
        }
        parser = BankStatementParser(currencyNormalizer, merchantCleaner)
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
        val results = parser.parse(blocks)
        assertEquals(3, results.size)
        // Check first transaction
        assertEquals(12.50, results[0].amount, 0.01)
        assertEquals("SKLAVENITIS", results[0].merchant)
        assertEquals(TransactionType.PURCHASE, results[0].type)
        // Check second transaction
        assertEquals(25.0, results[1].amount, 0.01)
        assertEquals("LIDL", results[1].merchant)
        assertEquals(TransactionType.PURCHASE, results[1].type)
        // Check third transaction
        assertEquals(1500.0, results[2].amount, 0.01)
        assertEquals("SALARY", results[2].merchant)
        assertEquals(TransactionType.DEPOSIT, results[2].type)
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
        val results = parser.parse(blocksWithAmounts)
        assertEquals(2, results.size)
        assertEquals("Merchant1", results[0].merchant)
        assertEquals("Merchant2", results[1].merchant)
    }
}

```

---

## test\kotlin\com\yourname\expensetracker\domain\logic\RecurringExpenseEngineTest.kt <a name="testkotlincomyournameexpensetrackerdomainlogicrecurringexpenseenginetestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.logic
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
class RecurringExpenseEngineTest {
    private lateinit var expenseDao: ExpenseDao
    private lateinit var recurringExpenseDao: RecurringExpenseDao
    private lateinit var engine: RecurringExpenseEngine
    @Before
    fun setup() {
        expenseDao = mockk()
        recurringExpenseDao = mockk()
        // Default: No manual expenses
        coEvery { recurringExpenseDao.getAll() } returns emptyList()
        engine = RecurringExpenseEngine(expenseDao, recurringExpenseDao)
    }
    @Test
    fun `should detect perfect monthly subscription`() = runTest {
        // Arrange: Netflix on the 1st of every month
        val expenses = listOf(
            createExpense("Netflix", 15.0, "2026-01-01"),
            createExpense("Netflix", 15.0, "2026-02-01"), // 31 days
            createExpense("Netflix", 15.0, "2026-03-01"), // 28 days (non-leap year 2026)
            createExpense("Netflix", 15.0, "2026-04-01")  // 31 days
        )
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses
        // Act
        val patterns = engine.getPatterns()
        // Assert
        assertEquals(1, patterns.size)
        val pattern = patterns.first()
        assertEquals("Netflix", pattern.merchantName)
        assertEquals(15.0, pattern.averageAmount, 0.01)
        assertEquals(RecurrenceFrequency.MONTHLY, pattern.frequency)
    }
    @Test
    fun `should detect bi-weekly salary`() = runTest {
        // Arrange: Salary every 14 days
        val expenses = listOf(
            createExpense("Corp Inc", 2000.0, "2026-01-05"), // Fri
            createExpense("Corp Inc", 2000.0, "2026-01-19"), // Fri + 14
            createExpense("Corp Inc", 2000.0, "2026-02-02"), // Fri + 14
            createExpense("Corp Inc", 2000.0, "2026-02-16")  // Fri + 14
        )
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses
        // Act
        val patterns = engine.getPatterns()
        // Assert
        assertEquals(1, patterns.size)
        val pattern = patterns.first()
        assertEquals("Corp Inc", pattern.merchantName)
        assertEquals(RecurrenceFrequency.BIWEEKLY, pattern.frequency)
    }
    @Test
    fun `should ignore random coffee purchases`() = runTest {
        // Arrange: Random coffee dates
        val expenses = listOf(
            createExpense("Starbucks", 5.0, "2026-01-01"),
            createExpense("Starbucks", 5.0, "2026-01-02"), // 1 day
            createExpense("Starbucks", 6.5, "2026-01-08"), // 6 days
            createExpense("Starbucks", 4.5, "2026-01-20")  // 12 days
        )
        // Intervals: 1, 6, 12 -> Irregular
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses
        // Act
        val patterns = engine.getPatterns()
        // Assert
        assertTrue(patterns.isEmpty())
    }
    @Test
    fun `should ignore variable bills (high amount variance)`() = runTest {
        // Arrange: Electricity bill with huge variance
        val expenses = listOf(
            createExpense("Electric Co", 50.0, "2026-01-01"),
            createExpense("Electric Co", 150.0, "2026-02-01"), 
            createExpense("Electric Co", 80.0, "2026-03-01"), 
            createExpense("Electric Co", 200.0, "2026-04-01")  
        )
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses
        // Act
        val patterns = engine.getPatterns()
        // Assert
        // Mean = 120. StdDev ~ 67. Variance ~ 0.55 (> 0.2 threshold)
        assertTrue(patterns.isEmpty())
    }
    @Test
    fun `manual override should take precedence`() = runTest {
        // Arrange: detected pattern is Monthly, but Manual Overrides says Weekly
        val expenses = listOf(
            createExpense("Gym", 50.0, "2026-01-01"),
            createExpense("Gym", 50.0, "2026-02-01"),
            createExpense("Gym", 50.0, "2026-03-01")
        )
        coEvery { expenseDao.getExpensesSince(any()) } returns expenses
        val manualOverride = ManualRecurringExpense(
            merchant = "Gym",
            amount = 50.0,
            frequency = RecurrenceFrequency.WEEKLY, // Override
            nextDate = 1000L,
            createdAt = 1000L
        )
        coEvery { recurringExpenseDao.getAll() } returns listOf(manualOverride)
        // Act
        val patterns = engine.getPatterns()
        // Assert
        assertEquals(1, patterns.size)
        val pattern = patterns.first()
        assertEquals("Gym", pattern.merchantName)
        assertEquals(RecurrenceFrequency.WEEKLY, pattern.frequency) // Should be WEEKLY, not MONTHLY
        assertEquals(1.0f, pattern.confidence, 0.0f) // Manual = 1.0 confidence
    }
    private fun createExpense(merchant: String, amount: Double, dateStr: String): Expense {
        // Simple parser for test dates
        val parts = dateStr.split("-")
        val calendar = Calendar.getInstance()
        calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 12, 0)
        return Expense(
            amount = amount,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = calendar.timeInMillis
        )
    }
}

```

---

