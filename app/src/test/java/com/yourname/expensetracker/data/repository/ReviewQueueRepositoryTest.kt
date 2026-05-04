package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewQueueRepositoryTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val rawNotificationDao = mockk<RawNotificationDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val receiptLinkService = mockk<ReceiptLinkService>(relaxed = true)
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
        mockkStatic("androidx.room.RoomDatabaseKt")
        val dbBlock = slot<suspend () -> Any>()
        coEvery { database.withTransaction(capture(dbBlock)) } coAnswers {
            dbBlock.captured.invoke()
        }

        every { timeProvider.now() } returns 1700000000000L
        
        repository = ReviewQueueRepository(
            database = database,
            pendingReviewDao = pendingReviewDao,
            rawNotificationDao = rawNotificationDao,
            expenseDao = expenseDao,
            sourceStatsDao = sourceStatsDao,
            receiptLinkService = mockk<ReceiptLinkService>(relaxed = true),
            userCorrectionDao = userCorrectionDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            classifier = classifier,
            budgetMonitor = budgetMonitor,
            parserRegistry = parserRegistry,
            timeProvider = timeProvider,
            confidenceRouter = confidenceRouter,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
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
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.PROCESSING
            )
        } returns 1
        coEvery { expenseDao.insertAtomic(any()) } returns 100L

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertTrue(result is Result.Success)
        assertEquals(100L, (result as Result.Success).data)
        
        coVerify { expenseDao.insertAtomic(match { it.merchant == "Test Merchant" && it.amount == 50.0 }) }
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.APPROVED) }
        coVerify { userCorrectionDao.insert(any()) }
        coVerify { classifier.retrainFromCorrections() }
    }

    @Test
    fun `approveReview preserves transfer and place metadata on success`() = runTest {
        val reviewId = 15L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 10L,
            suggestedAmount = 50.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Savings Transfer",
            suggestedType = "TRANSFER",
            suggestedCategoryId = 1L,
            confidence = 0.8f,
            packageName = "com.test.app",
            notificationTitle = "Transfer",
            notificationText = "Moved 50",
            suggestedDirection = TransferDirection.OUTGOING.name,
            suggestedAccountName = "Emergency Fund",
            suggestedLatitude = 37.9838,
            suggestedLongitude = 23.7275
        )

        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.PROCESSING
            )
        } returns 1
        coEvery { expenseDao.insertAtomic(any()) } returns 150L

        val expenseSlot = slot<Expense>()
        coEvery { expenseDao.insertAtomic(capture(expenseSlot)) } returns 150L

        val result = repository.approveReview(
            reviewId = reviewId,
            finalLatitude = 38.0,
            finalLongitude = 23.8,
            finalAddress = "Athens Center",
            finalPlaceId = "N999"
        )

        assertTrue(result is Result.Success)
        assertEquals(TransferDirection.OUTGOING, expenseSlot.captured.transferDirection)
        assertEquals("Emergency Fund", expenseSlot.captured.transferAccountName)
        assertEquals(38.0, expenseSlot.captured.latitude!!, 0.0)
        assertEquals(23.8, expenseSlot.captured.longitude!!, 0.0)
        assertEquals("USER_MANUAL", expenseSlot.captured.locationSource)
        assertEquals("N999", expenseSlot.captured.placeId)
        assertEquals("Athens Center", expenseSlot.captured.resolvedAddress)
    }

    @Test
    fun `approveReview returns Duplicate result if canonical policy detects duplicate`() = runTest {
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
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.PROCESSING
            )
        } returns 1
        // Canonical currency+type-aware policy detects duplicate before insert
        coEvery {
            expenseDao.isDuplicateCurrencyAware(
                amount = 10.0,
                merchant = "Dup",
                date = any(),
                currency = "EUR",
                transactionType = "PURCHASE",
                merchantKey = any(),
                dedupeKey = any()
            )
        } returns true

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertEquals(Result.Duplicate, result)
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE) }
        // insertAtomic must NOT be called when the pre-check already detected a duplicate
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `approveReview falls back to Duplicate if insertAtomic races after policy check`() = runTest {
        // Arrange — simulates a race where isDuplicateCurrencyAware returned false but a
        // concurrent transaction committed first, causing insertAtomic to return -1.
        val reviewId = 5L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 10L,
            suggestedAmount = 25.0,
            suggestedCurrency = "USD",
            suggestedMerchant = "RaceMerchant",
            suggestedType = "PURCHASE",
            suggestedCategoryId = 1L,
            confidence = 0.9f,
            packageName = "com.race",
            notificationTitle = "Race",
            notificationText = "Race"
        )

        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.PROCESSING
            )
        } returns 1
        // Pre-check says no duplicate (race window), but a post-conflict re-check sees the
        // concurrently inserted canonical duplicate and classifies the review accordingly.
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(false, true)
        coEvery { expenseDao.insertAtomic(any()) } returns -1L

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertEquals(Result.Duplicate, result)
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE) }
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
        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertTrue(result is Result.Error)
        assertEquals("AMOUNT_EXCEEDS_LIMIT", (result as Result.Error).message)
        coVerify(exactly = 0) { pendingReviewDao.transitionStatus(any(), any(), any()) }
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
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.REJECTED
            )
        } returns 1

        // Act
        repository.rejectReview(reviewId)

        // Assert
        val correctionSlot = slot<UserCorrection>()
        coVerify { userCorrectionDao.insert(capture(correctionSlot)) }
        
        assertTrue(correctionSlot.captured.wasRejected)
        assertFalse(correctionSlot.captured.wasApproved)
        assertEquals("Bad Merchant", correctionSlot.captured.originalMerchant)
    }

    @Test
    fun `approveReview allows DEPOSIT with same amount-merchant-date-currency as existing PURCHASE`() = runTest {
        // Scenario: A PURCHASE for €10 at "Acme" already exists. A DEPOSIT review for
        // the same amount/merchant/date/currency must NOT be blocked — the types are
        // incompatible, so the canonical policy should pass through to insert.
        val reviewId = 6L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 11L,
            suggestedAmount = 10.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Acme",
            suggestedType = "DEPOSIT",          // ← different type from the existing PURCHASE
            suggestedCategoryId = 1L,
            confidence = 0.9f,
            packageName = "com.bank",
            notificationTitle = "Deposit",
            notificationText = "Deposit 10 EUR"
        )

        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.PROCESSING
            )
        } returns 1
        // The currency+type-aware policy returns false: DEPOSIT vs PURCHASE types differ
        coEvery {
            expenseDao.isDuplicateCurrencyAware(
                amount = 10.0,
                merchant = "Acme",
                date = any(),
                currency = "EUR",
                transactionType = "DEPOSIT",
                merchantKey = any(),
                dedupeKey = any()
            )
        } returns false
        coEvery { expenseDao.insertAtomic(any()) } returns 200L

        val result = repository.approveReview(reviewId)

        assertTrue("DEPOSIT with incompatible type to existing PURCHASE must be approved, got $result",
            result is Result.Success)
        assertEquals(200L, (result as Result.Success).data)
        // insertAtomic must be called — not short-circuited by a type-blind key check
        coVerify { expenseDao.insertAtomic(match { it.transactionType == TransactionType.DEPOSIT }) }
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.APPROVED) }
    }

    @Test
    fun `approveReview allows same amount-merchant-date with different currency`() = runTest {
        // Scenario: An expense for 50 USD at "Shop" already exists. A review for
        // 50 EUR at "Shop" on the same date must NOT be blocked — currencies differ.
        val reviewId = 7L
        val reviewDate = 1_700_000_000_123L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 12L,
            suggestedAmount = 50.0,
            suggestedCurrency = "EUR",          // ← different currency from the existing USD row
            suggestedMerchant = "Shop",
            suggestedType = "PURCHASE",
            suggestedDate = reviewDate,
            suggestedCategoryId = 2L,
            confidence = 0.85f,
            packageName = "com.wallet",
            notificationTitle = "Shop",
            notificationText = "Paid 50 EUR"
        )

        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.PROCESSING
            )
        } returns 1
        // The currency-aware policy returns false: EUR ≠ USD
        coEvery {
            expenseDao.isDuplicateCurrencyAware(
                amount = 50.0,
                merchant = "Shop",
                date = reviewDate,
                currency = "EUR",
                transactionType = "PURCHASE",
                merchantKey = any(),
                dedupeKey = any()
            )
        } returns false
        coEvery { expenseDao.insertAtomic(any()) } returns 300L

        val result = repository.approveReview(reviewId)

        assertTrue("EUR expense must not be blocked by existing USD expense, got $result",
            result is Result.Success)
        assertEquals(300L, (result as Result.Success).data)
        coVerify {
            expenseDao.isDuplicateCurrencyAware(
                amount = 50.0,
                merchant = "Shop",
                date = reviewDate,
                currency = "EUR",
                transactionType = "PURCHASE",
                merchantKey = any(),
                dedupeKey = any()
            )
        }
        coVerify { expenseDao.insertAtomic(match { it.currency == "EUR" && it.amount == 50.0 }) }
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.APPROVED) }
    }

    /**
     * Key collision path test (ISSUE-1 fix verification).
     *
     * Before the fix, approving a DEPOSIT review when a PURCHASE with the same
     * amount/merchant/date/currency already existed would generate an IDENTICAL
     * type-blind dedupeKey (e.g. "10.00_acme_<bucket>_EUR"). Even though
     * isDuplicateCurrencyAware correctly returned false, insertAtomic would fail
     * with -1 due to the unique-index collision on the persisted key.
     *
     * After the fix, the DEPOSIT generates key "10.00_acme_<bucket>_EUR_DEPOSIT"
     * (type suffix included), which is distinct from the existing PURCHASE key
     * "10.00_acme_<bucket>_EUR_PURCHASE". insertAtomic therefore never collides
     * for incompatible-type rows and the approval succeeds.
     *
     * This test captures the Expense passed to insertAtomic and verifies that its
     * dedupeKey includes the transaction-type suffix, proving that the persisted
     * unique index can no longer falsely block incompatible-type approvals.
     */
    @Test
    fun `approveReview dedupeKey includes transaction type suffix to prevent false unique-index collision`() = runTest {
        val date = 1_700_000_000_000L
        val amount = 10.0
        val merchant = "Acme"
        val currency = "EUR"

        val reviewId = 8L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 13L,
            suggestedAmount = amount,
            suggestedCurrency = currency,
            suggestedMerchant = merchant,
            suggestedType = "DEPOSIT",
            suggestedDate = date,
            suggestedCategoryId = null,
            confidence = 0.95f,
            packageName = "com.bank",
            notificationTitle = "Deposit",
            notificationText = "Deposit $amount $currency"
        )

        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery {
            pendingReviewDao.transitionStatus(reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING)
        } returns 1
        // Policy says not a duplicate (incompatible type with existing PURCHASE)
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returns false

        val insertedExpenseSlot = slot<Expense>()
        coEvery { expenseDao.insertAtomic(capture(insertedExpenseSlot)) } returns 400L

        val result = repository.approveReview(reviewId)

        assertTrue("DEPOSIT approval must succeed, got $result", result is Result.Success)

        // Verify the persisted dedupeKey is type-aware.
        // The expected key includes "_DEPOSIT" suffix so it does NOT collide with
        // the "_PURCHASE" key that a PURCHASE row for the same transaction would have.
        val expectedDepositKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.DEPOSIT
        )
        val expectedPurchaseKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.PURCHASE
        )

        val actualKey = insertedExpenseSlot.captured.dedupeKey
        assertNotNull("dedupeKey must not be null", actualKey)
        assertEquals(
            "Persisted dedupeKey must match the type-aware DEPOSIT key",
            expectedDepositKey,
            actualKey
        )
        assertNotEquals(
            "DEPOSIT dedupeKey must differ from PURCHASE dedupeKey to prevent unique-index collision",
            expectedPurchaseKey,
            actualKey
        )
        assertTrue(
            "Type-aware dedupeKey must end with the transaction type suffix",
            actualKey!!.endsWith("_DEPOSIT")
        )
    }

    /**
     * Verifies that the type-aware key still acts as a race guard for same-type
     * concurrent approvals: if two PURCHASE reviews for the same transaction race,
     * the second insertAtomic returns -1 (key collision on the PURCHASE-keyed entry)
     * and the approval correctly returns Duplicate.
     */
    @Test
    fun `approveReview still returns Duplicate when insertAtomic races on same-type key`() = runTest {
        val reviewId = 9L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 14L,
            suggestedAmount = 30.0,
            suggestedCurrency = "GBP",
            suggestedMerchant = "CafeRace",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            confidence = 0.9f,
            packageName = "com.race2",
            notificationTitle = "Race2",
            notificationText = "Race2"
        )

        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery {
            pendingReviewDao.transitionStatus(reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING)
        } returns 1
        // Policy says no duplicate before insert, then sees the canonical duplicate after the race.
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(false, true)
        // insertAtomic fails because a concurrent PURCHASE for the same tx won the race
        coEvery { expenseDao.insertAtomic(any()) } returns -1L

        val result = repository.approveReview(reviewId)

        assertEquals(
            "Same-type race must still return Duplicate when insertAtomic conflicts",
            Result.Duplicate,
            result
        )
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE) }
    }

    // ── Batch 8 regression: suggestedAmount CHECK(suggestedAmount > 0) ──────

    /**
     * Regression for B4 Batch 8: when markAsRelevant() creates a fallback
     * PendingReview (parsing returned null), the suggestedAmount must be > 0
     * to satisfy the v76 CHECK constraint.  Previously it was 0.0.
     */
    @Test
    fun `markAsRelevant fallback PendingReview uses positive suggestedAmount`() = runTest {
        val notificationId = 100L
        val notification = RawNotification(
            id = notificationId,
            packageName = "com.bank.app",
            appName = "Bank",
            title = "Payment",
            text = "You paid something",
            timestamp = 1700000000000L,
            capturedAt = 1700000000000L
        )

        coEvery { rawNotificationDao.getById(notificationId) } returns notification
        // Parser returns null → forces the fallback PendingReview creation path
        coEvery {
            parserRegistry.parse(any(), any(), any(), any(), any())
        } returns null

        repository.markAsRelevant(notificationId, isRelevant = true)

        val reviewSlot = slot<PendingReview>()
        coVerify { pendingReviewDao.insert(capture(reviewSlot)) }

        val captured = reviewSlot.captured
        assertTrue(
            "Fallback suggestedAmount must be > 0 to satisfy v76 CHECK constraint, was ${captured.suggestedAmount}",
            (captured.suggestedAmount ?: 0.0) > 0
        )
        assertEquals(0.01, captured.suggestedAmount ?: 0.0, 0.001)
    }
}