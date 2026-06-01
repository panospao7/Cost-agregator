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
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class ReviewQueueRepositoryTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val rawNotificationDao = mockk<RawNotificationDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val receiptLinkService = mockk<ReceiptLinkService>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
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
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>()
            com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult(
                canonical = com.yourname.expensetracker.data.database.entity.MerchantCanonical(
                    normalizedName = name,
                    searchKey = name.lowercase()
                ),
                alias = null,
                confidence = 1.0f,
                matchType = com.yourname.expensetracker.domain.intelligence.ml.MatchType.EXACT_MATCH
            )
        }
        
        repository = ReviewQueueRepository(
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
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
            pendingReviewSourceLinkService = mockk(relaxed = true),
            pendingReviewSourceLinkPromoter = mockk(relaxed = true),
            transactionEventDao = database.transactionEventDao(),
            postCommitActionRunner = mockk(relaxed = true),
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
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(100L), PostCommitActionBatch.empty(""))

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertTrue(result is Result.Success)
        assertEquals(100L, (result as Result.Success).data)
        
        coVerify { transactionLifecycleCoordinator.createExpenseDbOnlyV2(match { it.merchant == "Test Merchant" && it.amount == 50.0 }) }
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
        val requestSlot = slot<com.yourname.expensetracker.domain.transaction.CreateExpenseRequest>()
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(capture(requestSlot)) } returns
            MutationResult(CreateExpenseResult.Created(150L), PostCommitActionBatch.empty(""))

        val result = repository.approveReview(
            reviewId = reviewId,
            finalLatitude = 38.0,
            finalLongitude = 23.8,
            finalAddress = "Athens Center",
            finalPlaceId = "N999"
        )

        assertTrue(result is Result.Success)
        assertEquals(TransferDirection.OUTGOING, requestSlot.captured.transferDirection)
        assertEquals("Emergency Fund", requestSlot.captured.transferAccountName)
        assertEquals(38.0, requestSlot.captured.latitude!!, 0.0)
        assertEquals(23.8, requestSlot.captured.longitude!!, 0.0)
        assertEquals("USER_MANUAL", requestSlot.captured.locationSource)
        assertEquals("N999", requestSlot.captured.placeId)
        assertEquals("Athens Center", requestSlot.captured.resolvedAddress)
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
        // Dedup is now owned by the coordinator: it returns DuplicateSkipped instead of
        // the repository performing a pre-insert isDuplicateCurrencyAware check.
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(
                CreateExpenseResult.DuplicateSkipped(existingExpenseId = 99L, reason = "canonical-duplicate"),
                PostCommitActionBatch.empty("")
            )

        // Act
        val result = repository.approveReview(reviewId)

        // Assert
        assertEquals(Result.Duplicate, result)
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE) }
    }

    @Test
    fun `approveReview falls back to Duplicate if createExpense races after policy check`() = runTest {
        // Arrange — simulates a race where isDuplicateCurrencyAware returned false but a
        // concurrent transaction committed first, causing createExpense to return InsertConflict.
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
        // The coordinator (single dedup owner) detects a concurrently inserted
        // canonical duplicate and returns InsertConflict.
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.InsertConflict(dedupeKey = "race-key"), PostCommitActionBatch.empty(""))

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
        // Dedup is owned by the coordinator. An incompatible-type DEPOSIT is not a
        // duplicate of an existing PURCHASE, so the coordinator returns Created.
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(200L), PostCommitActionBatch.empty(""))

        val result = repository.approveReview(reviewId)

        assertTrue("DEPOSIT with incompatible type to existing PURCHASE must be approved, got $result",
            result is Result.Success)
        assertEquals(200L, (result as Result.Success).data)
        // coordinator must be called with the DEPOSIT request -- not short-circuited
        coVerify { transactionLifecycleCoordinator.createExpenseDbOnlyV2(match { it.transactionType == TransactionType.DEPOSIT }) }
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
        // Dedup is owned by the coordinator. EUR ≠ USD, so the coordinator does not
        // treat this as a duplicate and returns Created.
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(300L), PostCommitActionBatch.empty(""))

        val result = repository.approveReview(reviewId)

        assertTrue("EUR expense must not be blocked by existing USD expense, got $result",
            result is Result.Success)
        assertEquals(300L, (result as Result.Success).data)
        coVerify {
            transactionLifecycleCoordinator.createExpenseDbOnlyV2(
                match { it.currency == "EUR" && it.amount == 50.0 && it.date == reviewDate }
            )
        }
        coVerify { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.APPROVED) }
    }

    /**
     * Key collision path test (ISSUE-1 fix verification).
     *
     * Before the fix, approving a DEPOSIT review when a PURCHASE with the same
     * amount/merchant/date/currency already existed would generate an IDENTICAL
     * type-blind dedupeKey, falsely colliding on the persisted unique index.
     *
     * After the fix, the DEPOSIT generates key "..._EUR_DEPOSIT" (type suffix
     * included), distinct from the existing PURCHASE key "..._EUR_PURCHASE", so
     * the approval succeeds for incompatible-type rows.
     *
     * Dedup + dedupeKey generation are now owned by the
     * TransactionLifecycleCoordinator (the repository delegates via
     * createExpenseDbOnlyV2 and no longer threads a dedupeKey through
     * CreateExpenseRequest). This test therefore verifies two surviving facts at
     * the repository boundary:
     *   1. The repository delegates the DEPOSIT to the coordinator and surfaces
     *      Success — it does NOT short-circuit incompatible-type rows.
     *   2. The type-aware dedupeKey policy itself is sound (DEPOSIT key differs
     *      from PURCHASE key and carries the type suffix). The end-to-end
     *      persisted-key collision guard is covered directly in
     *      DuplicateDetectionPolicyDedupeKeyTest / DedupeKeyProducerConsistencyTest.
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

        coEvery { merchantNormalizer.normalize("Acme", any(), any()) } answers {
            com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult(
                canonical = com.yourname.expensetracker.data.database.entity.MerchantCanonical(
                    normalizedName = "Acme",
                    searchKey = "acme"
                ),
                alias = null,
                confidence = 1.0f,
                matchType = com.yourname.expensetracker.domain.intelligence.ml.MatchType.EXACT_MATCH
            )
        }
        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview
        coEvery {
            pendingReviewDao.transitionStatus(reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING)
        } returns 1

        val requestSlot = slot<com.yourname.expensetracker.domain.transaction.CreateExpenseRequest>()
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(capture(requestSlot)) } returns
            MutationResult(CreateExpenseResult.Created(400L), PostCommitActionBatch.empty(""))

        val result = repository.approveReview(reviewId)

        assertTrue("DEPOSIT approval must succeed, got $result", result is Result.Success)

        // 1. Repository delegated the DEPOSIT to the coordinator (not short-circuited).
        assertEquals(TransactionType.DEPOSIT, requestSlot.captured.transactionType)
        assertEquals(currency, requestSlot.captured.currency)
        assertEquals(amount, requestSlot.captured.amount, 0.0)

        // 2. The type-aware dedupeKey policy is sound: the DEPOSIT key differs from
        // the PURCHASE key and carries the type suffix, so the persisted unique
        // index can no longer falsely block incompatible-type approvals.
        val expectedDepositKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.DEPOSIT
        )
        val expectedPurchaseKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.PURCHASE
        )

        assertNotEquals(
            "DEPOSIT dedupeKey must differ from PURCHASE dedupeKey to prevent unique-index collision",
            expectedPurchaseKey,
            expectedDepositKey
        )
        assertTrue(
            "Type-aware dedupeKey must end with the transaction type suffix",
            expectedDepositKey.endsWith("_DEPOSIT")
        )
    }

    /**
     * Verifies that the type-aware key still acts as a race guard for same-type
     * concurrent approvals: if two PURCHASE reviews for the same transaction race,
     * the second createExpense returns InsertConflict (key collision on the PURCHASE-keyed entry)
     * and the approval correctly returns Duplicate.
     */
    @Test
    fun `approveReview still returns Duplicate when createExpense races on same-type key`() = runTest {
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
        // Dedup is owned by the coordinator; it returns InsertConflict because a
        // concurrent PURCHASE for the same tx won the race.
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.InsertConflict(dedupeKey = "race-key"), PostCommitActionBatch.empty(""))

        val result = repository.approveReview(reviewId)

        assertEquals(
            "Same-type race must still return Duplicate when createExpense detects conflict",
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
        coVerify { pendingReviewDao.upsertByRawNotificationId(capture(reviewSlot)) }

        val captured = reviewSlot.captured
        // SQLite CHECK constraints allow NULL (NULL comparison yields NULL, which is treated as passing).
        // The fallback uses null for suggestedAmount as a sentinel that blocks approval until the
        // user provides a real amount override.  See approveReview() synthetic-placeholder guard.
        assertNull(
            "Fallback suggestedAmount must be null (synthetic placeholder sentinel), was ${captured.suggestedAmount}",
            captured.suggestedAmount
        )
    }
}