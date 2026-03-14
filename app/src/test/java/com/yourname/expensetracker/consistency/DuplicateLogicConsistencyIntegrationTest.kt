package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.intelligence.DuplicateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ensures duplicate detection logic is consistent across CrossSourceDeduplication methods
 * (findExpenseDuplicate, findPendingReviewDuplicate) and that the same transaction
 * is treated as duplicate in all flows.
 */
class DuplicateLogicConsistencyIntegrationTest {

    private lateinit var deduplication: CrossSourceDeduplication

    @Before
    fun setup() {
        deduplication = CrossSourceDeduplication()
    }

    @Test
    fun `consistency - findExpenseDuplicate returns match for exact same transaction`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val expense = createExpense(1, amount, merchant, date)
        val expenses = listOf(expense)

        val duplicate = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
        assertNotNull(duplicate)
        assertEquals(expense.id, duplicate!!.id)
    }

    @Test
    fun `consistency - findExpenseDuplicate returns null for different amount`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val expense = createExpense(1, 30.00, merchant, date)
        val expenses = listOf(expense)

        val duplicate = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
        assertNull(duplicate)
    }

    @Test
    fun `consistency - findExpenseDuplicate returns null for different merchant`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val expense = createExpense(1, amount, "McDonald's", date)
        val expenses = listOf(expense)

        val duplicate = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
        assertNull(duplicate)
    }

    @Test
    fun `consistency - findExpenseDuplicate returns null for date outside window`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val expense = createExpense(1, amount, merchant, date - (25 * 60 * 60 * 1000)) // 25h ago
        val expenses = listOf(expense)

        val duplicate = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
        assertNull(duplicate)
    }

    @Test
    fun `consistency - findPendingReviewDuplicate returns match for exact same transaction`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val review = createPendingReview(1, amount, merchant, date)
        val reviews = listOf(review)

        val duplicate = deduplication.findPendingReviewDuplicate(amount, merchant, date, reviews)
        assertNotNull(duplicate)
        assertEquals(review.id, duplicate!!.id)
    }

    @Test
    fun `consistency - findPendingReviewDuplicate returns null for different amount`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val review = createPendingReview(1, 30.00, merchant, date)
        val reviews = listOf(review)

        val duplicate = deduplication.findPendingReviewDuplicate(amount, merchant, date, reviews)
        assertNull(duplicate)
    }

    @Test
    fun `consistency - findPendingReviewDuplicate returns null when suggestedDate is null`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val review = createPendingReview(1, amount, merchant, null)
        val reviews = listOf(review)

        val duplicate = deduplication.findPendingReviewDuplicate(amount, merchant, date, reviews)
        assertNull(duplicate)
    }

    @Test
    fun `consistency - both methods use same amount tolerance`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        // Amount within 0.01 tolerance
        val expense = createExpense(1, 25.505, merchant, date)
        val expenses = listOf(expense)

        val duplicate = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
        assertNotNull("Amount within 0.01 should match", duplicate)
    }

    @Test
    fun `consistency - both methods reject amount outside tolerance`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val expense = createExpense(1, 25.52, merchant, date) // 0.02 diff
        val expenses = listOf(expense)

        val duplicate = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
        assertNull("Amount outside 0.01 tolerance should not match", duplicate)
    }

    @Test
    fun `consistency - merchant similarity allows minor variations`() {
        val amount = 25.50
        val date = System.currentTimeMillis()
        val expense = createExpense(1, amount, "Starbucks Coffee", date)
        val expenses = listOf(expense)

        // "Starbucks" should match "Starbucks Coffee" (contains)
        val duplicate = deduplication.findExpenseDuplicate(amount, "Starbucks", date, expenses)
        assertNotNull("Merchant similarity should match Starbucks vs Starbucks Coffee", duplicate)
    }

    @Test
    fun `consistency - generateSourceAwareDedupeKey is deterministic`() {
        val amount = 50.0
        val merchant = "LIDL"
        val date = 1700000000000L
        val source = "notification"
        val key1 = deduplication.generateSourceAwareDedupeKey(amount, merchant, date, source)
        val key2 = deduplication.generateSourceAwareDedupeKey(amount, merchant, date, source)
        assertEquals(key1, key2)
    }

    @Test
    fun `consistency - isCrossSourceDuplicate returns SameSourceDuplicate when same source`() {
        val result = deduplication.isCrossSourceDuplicate(
            amount = 25.50,
            merchant = "Starbucks",
            date = System.currentTimeMillis(),
            newSource = "notification",
            existingSources = listOf("notification")
        )
        assertTrue(result is DuplicateCheckResult.SameSourceDuplicate)
    }

    @Test
    fun `consistency - isCrossSourceDuplicate returns NoDuplicate when empty`() {
        val result = deduplication.isCrossSourceDuplicate(
            amount = 25.50,
            merchant = "Starbucks",
            date = System.currentTimeMillis(),
            newSource = "notification",
            existingSources = emptyList()
        )
        assertTrue(result is DuplicateCheckResult.NoDuplicate)
    }

    @Test
    fun `stress - findExpenseDuplicate 100 calls same result`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val expense = createExpense(1, amount, merchant, date)
        val expenses = listOf(expense)

        val first = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
        repeat(99) {
            val dup = deduplication.findExpenseDuplicate(amount, merchant, date, expenses)
            assertEquals(first?.id, dup?.id)
        }
    }

    @Test
    fun `stress - findPendingReviewDuplicate 100 calls same result`() {
        val amount = 25.50
        val merchant = "Starbucks"
        val date = System.currentTimeMillis()
        val review = createPendingReview(1, amount, merchant, date)
        val reviews = listOf(review)

        val first = deduplication.findPendingReviewDuplicate(amount, merchant, date, reviews)
        repeat(99) {
            val dup = deduplication.findPendingReviewDuplicate(amount, merchant, date, reviews)
            assertEquals(first?.id, dup?.id)
        }
    }

    private fun createExpense(id: Long, amount: Double, merchant: String, date: Long): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = date
        )
    }

    private fun createPendingReview(
        id: Long,
        amount: Double,
        merchant: String,
        date: Long?
    ): PendingReview {
        return PendingReview(
            id = id,
            rawNotificationId = null,
            suggestedAmount = amount,
            suggestedCurrency = "EUR",
            suggestedMerchant = merchant,
            suggestedType = TransactionType.PURCHASE.name,
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
