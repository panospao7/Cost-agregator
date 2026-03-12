package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewQueueRepositoryTest {

    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val rawNotificationDao = mockk<RawNotificationDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val budgetMonitor = mockk<BudgetMonitor>(relaxed = true)
    private val parserRegistry = mockk<AppParserRegistry>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val confidenceRouter = mockk<ConfidenceRouter>(relaxed = true)

    private lateinit var repository: ReviewQueueRepository

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1700000000000L
        
        repository = ReviewQueueRepository(
            pendingReviewDao,
            rawNotificationDao,
            expenseDao,
            sourceStatsDao,
            scannedReceiptDao,
            userCorrectionDao,
            merchantCategoryRepository,
            merchantNormalizer,
            hybridClassifier,
            classifier,
            budgetMonitor,
            parserRegistry,
            timeProvider,
            confidenceRouter
        )
    }

    @Test
    fun `approveReview creates expense and records correction on success`() = runTest {
        // Arrange
        val reviewId = 1L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 10L,
            suggestedAmount = 50.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Test Merchant",
            suggestedType = "PURCHASE",
            suggestedCategoryId = 1L,
            confidence = 0.8f,
            packageName = "com.test.app",
            notificationTitle = "Test",
            notificationText = "Spent 50"
        )
        
        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery { pendingReviewDao.updateStatusIfPending(reviewId, "PROCESSING") } returns 1
        coEvery { expenseDao.insertAtomic(any()) } returns 100L

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertTrue(result is Result.Success)
        assertEquals(100L, (result as Result.Success).data)
        
        coVerify { expenseDao.insertAtomic(match { it.merchant == "Test Merchant" && it.amount == 50.0 }) }
        coVerify { pendingReviewDao.updateStatus(reviewId, "APPROVED") }
        coVerify { userCorrectionDao.insert(any()) }
        coVerify { classifier.retrainFromCorrections() }
    }

    @Test
    fun `approveReview returns Duplicate result if duplicates found`() = runTest {
        // Arrange
        val reviewId = 2L
        val pendingReview = PendingReview(
            id = reviewId, 
            rawNotificationId = 10L,
            suggestedAmount = 10.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Dup",
            suggestedType = "PURCHASE",
            suggestedCategoryId = 1L,
            confidence = 0.8f,
            packageName = "com.test", 
            notificationTitle = "Dup",
            notificationText = "Dup"
        )
        
        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery { pendingReviewDao.updateStatusIfPending(reviewId, "PROCESSING") } returns 1
        // Atomic insert returns -1 when duplicate constraint is triggered
        coEvery { expenseDao.insertAtomic(any()) } returns -1L

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertEquals(Result.Duplicate, result)
        coVerify { pendingReviewDao.updateStatus(reviewId, "DUPLICATE") }
        coVerify(exactly = 0) { expenseDao.insert(any()) }
    }

    @Test
    fun `approveReview returns Error if amount exceeds limit`() = runTest {
        // Arrange
        val reviewId = 3L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 10L,
            suggestedAmount = 2000000.0, // > 1M
            suggestedCurrency = "EUR",
            suggestedMerchant = "Rich",
            suggestedType = "PURCHASE",
            suggestedCategoryId = 1L,
            confidence = 0.8f,
            packageName = "com.bank",
            notificationTitle = "Rich",
            notificationText = "Rich"
        )
        
        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery { pendingReviewDao.updateStatusIfPending(reviewId, "PROCESSING") } returns 1

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertTrue(result is Result.Error)
        assertEquals("Amount exceeds limit", (result as Result.Error).message)
        coVerify { pendingReviewDao.updateStatus(reviewId, "PENDING") } // Revert status
    }

    @Test
    fun `rejectReview updates status and records negative correction`() = runTest {
        // Arrange
        val reviewId = 4L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 10L,
            suggestedAmount = 10.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Bad Merchant",
            suggestedType = "PURCHASE",
            suggestedCategoryId = 1L,
            confidence = 0.8f,
            packageName = "com.test",
            notificationTitle = "Bad",
            notificationText = "Bad"
        )
        
        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery { pendingReviewDao.updateStatusIfPending(reviewId, "REJECTED") } returns 1

        // Act
        repository.rejectReview(reviewId)

        // Assert
        val correctionSlot = slot<UserCorrection>()
        coVerify { userCorrectionDao.insert(capture(correctionSlot)) }
        
        assertTrue(correctionSlot.captured.wasRejected)
        assertFalse(correctionSlot.captured.wasApproved)
        assertEquals("Bad Merchant", correctionSlot.captured.originalMerchant)
    }
}
