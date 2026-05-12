package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@Suppress("DEPRECATION_ERROR")
@Ignore("Stress test: may hang in CI, run manually")
class ReviewQueueRepositoryStressTest {

    private val database = mockk<AppDatabase>(relaxed = true)
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
    private val anomalyAlertOrchestrator = mockk<AnomalyAlertOrchestrator>(relaxed = true)
    private val parserRegistry = mockk<AppParserRegistry>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val confidenceRouter = mockk<ConfidenceRouter>(relaxed = true)
    private val transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private lateinit var repository: ReviewQueueRepository

    @Before
    fun setup() {
        every { database.pendingReviewDao() } returns pendingReviewDao
        every { database.rawNotificationDao() } returns rawNotificationDao
        every { database.expenseDao() } returns expenseDao
        every { database.sourceStatsDao() } returns sourceStatsDao
        every { database.scannedReceiptDao() } returns scannedReceiptDao

        coEvery { pendingReviewDao.getPendingFlow(any()) } returns MutableStateFlow(emptyList())
        coEvery { pendingReviewDao.getPendingCountFlow() } returns MutableStateFlow(0)
        coEvery { pendingReviewDao.getById(any()) } returns null
        coEvery { pendingReviewDao.transitionStatus(any(), any(), any()) } returns 0
        coEvery { pendingReviewDao.getPending() } returns emptyList()
        coEvery { pendingReviewDao.getPendingByMerchant(any(), any()) } returns emptyList()
        coEvery { pendingReviewDao.bulkUpdateCategoryByMerchant(any(), any(), any()) } returns Unit
        coEvery { pendingReviewDao.bulkRenameMerchant(any(), any(), any(), any()) } returns Unit
        coEvery { rawNotificationDao.getById(any()) } returns null
        coEvery { expenseDao.insertAtomic(any()) } returns 1L
        coEvery { expenseDao.getAllFlow(any()) } returns MutableStateFlow(emptyList())
        coEvery { sourceStatsDao.incrementAccepted(any()) } returns Unit
        coEvery { sourceStatsDao.decrementPending(any()) } returns Unit
        coEvery { sourceStatsDao.incrementRejected(any()) } returns Unit
        coEvery { scannedReceiptDao.linkToExpense(any(), any()) } returns Unit
        coEvery { userCorrectionDao.insert(any()) } returns 1L
        coEvery { merchantCategoryRepository.learnPattern(any(), any()) } returns Unit
        coEvery { merchantNormalizer.learnMerchantAlias(any(), any()) } returns Unit
        coEvery { budgetMonitor.checkBudgets() } returns Unit
        coEvery { classifier.retrainFromCorrections() } returns Unit
        coEvery { confidenceRouter.invalidateSourceStatsCache(any()) } returns Unit
        coEvery { transactionLifecycleCoordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)
        every { timeProvider.now() } returns System.currentTimeMillis()

        repository = ReviewQueueRepository(
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            database = database,
            pendingReviewDao = pendingReviewDao,
            rawNotificationDao = rawNotificationDao,
            expenseDao = expenseDao,
            sourceStatsDao = sourceStatsDao,
            userCorrectionDao = userCorrectionDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            classifier = classifier,
            budgetMonitor = budgetMonitor,
            parserRegistry = parserRegistry,
            timeProvider = timeProvider,
            confidenceRouter = confidenceRouter,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
            receiptLinkService = mockk<ReceiptLinkService>(relaxed = true),
        )
    }

    // ============================================================================
    // SECTION 1: QUERY OPERATIONS
    // ============================================================================

    @Test
    fun `stress - getPendingReviews returns flow`() = runTest {
        val result = repository.getAllPendingReviews()
        assertNotNull(result)
    }

    @Test
    fun `stress - getPendingReviewCount returns flow`() = runTest {
        val result = repository.getPendingReviewCount()
        assertNotNull(result)
    }

    @Test
    fun `stress - getReviewById returns null for non-existent`() = runTest {
        val result = repository.getReviewById(999)
        assertNull(result)
    }

    // ============================================================================
    // SECTION 2: BULK OPERATIONS
    // ============================================================================

    @Test
    fun `stress - approveAllReview with empty queue`() = runTest {
        repository.approveAllReview()
    }

    @Test
    fun `stress - rejectAllReviews with empty queue`() = runTest {
        repository.rejectAllReviews()
    }

    @Test
    fun `stress - getPendingReviewsByMerchant returns empty for unknown merchant`() = runTest {
        val result = repository.getPendingReviewsByMerchant("Unknown")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `stress - updatePendingReviewCategoryBulk calls DAO`() = runTest {
        repository.updatePendingReviewCategoryBulk("Test Merchant", 1)
        coVerify {
            pendingReviewDao.bulkUpdateCategoryByMerchant(
                com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("Test Merchant"),
                "Test Merchant",
                1
            )
        }
    }

    @Test
    fun `stress - updatePendingReviewMerchantBulk calls DAO`() = runTest {
        repository.updatePendingReviewMerchantBulk("Old", "New")
        coVerify {
            pendingReviewDao.bulkRenameMerchant(
                com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("Old"),
                "Old",
                "New",
                com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("New")
            )
        }
    }
}