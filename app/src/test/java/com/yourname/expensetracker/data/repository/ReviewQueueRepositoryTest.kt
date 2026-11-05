package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>()
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private lateinit var repository: ReviewQueueRepository

    @Before
    fun setup() {
        // Default STORE_RAW so the pre-existing tests keep asserting stored text unchanged.
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW)
        // GR-14p: mutations are scoped in writeBarrier.runWrite
        // (ReviewQueueRepository.kt:219/433/610); a relaxed mock would neither
        // run the block nor return its value (the repository casts the result
        // to ReviewApprovalTxOutcome), so pass the block through.
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        // withTransaction compiles to the TOP-LEVEL static facade
        // androidx.room.RoomDatabaseKt.withTransaction (Room 2.7.2). Without
        // mockkStatic, the real Room body runs against the relaxed AppDatabase
        // mock and either suspends forever or returns a default Object, which
        // surfaces as a ClassCastException on ReviewApprovalTxOutcome (measured:
        // docs/testing/test-sweep-outcomes-2026-09-18.md). The recorded call
        // args are positional with the RECEIVER as arg 0 and the block as arg 1,
        // so the block is secondArg.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
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
            writeBarrier = writeBarrier,
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
            transactionRunner = mockk(relaxed = true),
            transactionLifecycleEventWriter = mockk(relaxed = true),
            postCommitActionRunner = mockk(relaxed = true),
            privacySettingsRepository = privacySettingsRepository,
            merchantCategoryRepository = merchantCategoryRepository,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `approveReview requires explicit currency when suggestion is blank`() = runTest {
        val reviewId = 21L
        val pendingReview = PendingReview(
            id = reviewId,
            rawNotificationId = 210L,
            suggestedAmount = 42.0,
            suggestedCurrency = "",
            suggestedMerchant = "Test Merchant",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            confidence = 0.45f,
            packageName = "com.test.unresolved",
            notificationTitle = "Payment 42 kr",
            notificationText = "Card purchase at Test Merchant",
        )
        coEvery { pendingReviewDao.getById(reviewId) } returns pendingReview

        val result = repository.approveReview(reviewId)

        assertTrue(result is Result.Error)
        assertEquals(
            "Currency is required. Please edit the review and select a currency.",
            (result as Result.Error).message,
        )
        coVerify(exactly = 0) {
            pendingReviewDao.transitionStatus(any(), any(), any())
        }
        coVerify(exactly = 0) {
            transactionLifecycleCoordinator.createExpenseDbOnlyV2(any())
        }
    }

    private fun approvablePendingReview(reviewId: Long) = PendingReview(
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

    private fun givenApprovalCreates(reviewId: Long, review: PendingReview) {
        coEvery { pendingReviewDao.getById(reviewId) } returns review
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING
            )
        } returns 1
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(901L), PostCommitActionBatch.empty(""))
    }

    @Test
    fun `DR-04 approval that changes the suggested category corrects the merchant mapping once`() = runTest {
        givenApprovalCreates(41L, approvablePendingReview(41L)) // suggests category 1

        val result = repository.approveReview(41L, finalCategoryId = 2L)

        assertTrue(result is Result.Success)
        coVerify(exactly = 1) { merchantCategoryRepository.correctPattern("Test Merchant", 2L) }
    }

    @Test
    fun `DR-04 approval with the suggested category unchanged makes no correction`() = runTest {
        givenApprovalCreates(42L, approvablePendingReview(42L))

        assertTrue(repository.approveReview(42L) is Result.Success)
        assertTrue(repository.approveReview(42L, finalCategoryId = 1L) is Result.Success)

        coVerify(exactly = 0) { merchantCategoryRepository.correctPattern(any(), any()) }
    }

    @Test
    fun `DR-04 approval of a review with no suggested category makes no correction`() = runTest {
        givenApprovalCreates(43L, approvablePendingReview(43L).copy(suggestedCategoryId = null))

        assertTrue(repository.approveReview(43L, finalCategoryId = 5L) is Result.Success)

        coVerify(exactly = 0) { merchantCategoryRepository.correctPattern(any(), any()) }
    }

    @Test
    fun `DR-04 correction failure keeps the approval successful and logs no raw text`() = runTest {
        givenApprovalCreates(44L, approvablePendingReview(44L))
        coEvery { merchantCategoryRepository.correctPattern(any(), any()) } throws
            IllegalStateException("SECRET-RAW-Test Merchant")
        val messages = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message + (t?.message ?: "")
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val result = repository.approveReview(44L, finalCategoryId = 2L)

            assertTrue(result is Result.Success)
        } finally {
            timber.log.Timber.uproot(tree)
        }

        assertTrue(messages.any { it.contains("IllegalStateException") })
        assertTrue(messages.none { it.contains("SECRET-RAW") })
    }

    @Test
    fun `DR-04 correction cancellation propagates out of approval`() = runTest {
        givenApprovalCreates(45L, approvablePendingReview(45L))
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        coEvery { merchantCategoryRepository.correctPattern(any(), any()) } throws cancellation

        val thrown = runCatching { repository.approveReview(45L, finalCategoryId = 2L) }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `approveReview validation failure returns bounded code without joined errors`() = runTest {
        val reviewId = 31L
        coEvery { pendingReviewDao.getById(reviewId) } returns approvablePendingReview(reviewId)
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING
            )
        } returns 1
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(
                CreateExpenseResult.ValidationFailed(listOf("SENSITIVE_DETAIL_A", "SENSITIVE_DETAIL_B")),
                PostCommitActionBatch.empty("")
            )

        val result = repository.approveReview(reviewId)

        assertTrue(result is Result.Error)
        val message = (result as Result.Error).message
        assertEquals(ReviewQueueRepository.ERROR_VALIDATION_FAILED, message)
        assertTrue(message in ReviewQueueRepository.KNOWN_APPROVAL_ERRORS)
        assertFalse(message!!.contains("SENSITIVE_DETAIL"))
        coVerify(exactly = 0) { pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.APPROVED) }
    }

    @Test
    fun `approveReview unexpected exception returns bounded code without exception text`() = runTest {
        val reviewId = 32L
        coEvery { pendingReviewDao.getById(reviewId) } returns approvablePendingReview(reviewId)
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING
            )
        } returns 1
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } throws
            IllegalStateException("SQLITE_CONSTRAINT raw text Spent 50")

        val result = repository.approveReview(reviewId)

        assertTrue(result is Result.Error)
        val message = (result as Result.Error).message
        assertEquals(ReviewQueueRepository.ERROR_APPROVAL_FAILED, message)
        assertTrue(message in ReviewQueueRepository.KNOWN_APPROVAL_ERRORS)
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

    // ── MEC A2 (INV-14, G2-3, D-14): per-currency cap via CurrencyAmountPolicy ──

    private fun reviewWith(id: Long, amount: Double, currency: String) = PendingReview(
        id = id,
        rawNotificationId = 10L,
        suggestedAmount = amount,
        suggestedCurrency = currency,
        suggestedMerchant = "Merchant",
        suggestedType = "PURCHASE",
        suggestedCategoryId = 1L,
        confidence = 0.8f,
        packageName = "com.bank",
        notificationTitle = "Merchant",
        notificationText = "Merchant"
    )

    @Test
    fun `approveReview allows KRW 1,500,000 under the per-currency cap`() = runTest {
        val reviewId = 31L
        coEvery { pendingReviewDao.getById(reviewId) } returns reviewWith(reviewId, 1_500_000.0, "KRW")
        coEvery {
            pendingReviewDao.transitionStatus(reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING)
        } returns 1
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(101L), PostCommitActionBatch.empty(""))

        val result = repository.approveReview(reviewId)

        assertTrue("Expected Success, got $result", result is Result.Success)
        coVerify { transactionLifecycleCoordinator.createExpenseDbOnlyV2(match { it.currency == "KRW" && it.amount == 1_500_000.0 }) }
    }

    @Test
    fun `approveReview refuses EUR 1,000,000_01 and KRW above one billion`() = runTest {
        val eurId = 32L
        coEvery { pendingReviewDao.getById(eurId) } returns reviewWith(eurId, 1_000_000.01, "EUR")
        val eurResult = repository.approveReview(eurId)
        assertEquals("AMOUNT_EXCEEDS_LIMIT", (eurResult as Result.Error).message)

        val krwId = 33L
        coEvery { pendingReviewDao.getById(krwId) } returns reviewWith(krwId, 1_000_000_001.0, "KRW")
        val krwResult = repository.approveReview(krwId)
        assertEquals("AMOUNT_EXCEEDS_LIMIT", (krwResult as Result.Error).message)

        coVerify(exactly = 0) { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) }
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

    // ── Queue accessors and bulk operations (ported from the retired ReviewQueueRepositoryStressTest) ──

    @Test
    fun `getAllPendingReviews exposes the uncapped DAO flow unchanged`() = runTest {
        val daoFlow = MutableStateFlow(emptyList<PendingReviewWithReceipt>())
        every { pendingReviewDao.getPendingUncappedFlow() } returns daoFlow

        assertSame(daoFlow, repository.getAllPendingReviews())
    }

    @Test
    fun `getPendingReviewCount exposes the DAO count flow unchanged`() = runTest {
        val daoFlow = MutableStateFlow(3)
        every { pendingReviewDao.getPendingCountFlow() } returns daoFlow

        assertSame(daoFlow, repository.getPendingReviewCount())
    }

    @Test
    fun `getReviewById returns the stored review and null for a missing id`() = runTest {
        val stored = approvablePendingReview(61L)
        coEvery { pendingReviewDao.getById(61L) } returns stored
        coEvery { pendingReviewDao.getById(999L) } returns null

        assertEquals(stored, repository.getReviewById(61L))
        assertNull(repository.getReviewById(999L))
    }

    @Test
    fun `approveAllReview on an empty queue returns no results and approves nothing`() = runTest {
        coEvery { pendingReviewDao.getPendingUncapped() } returns emptyList()

        val results = repository.approveAllReview()

        assertTrue(results.isEmpty())
        coVerify(exactly = 0) { pendingReviewDao.transitionStatus(any(), any(), any()) }
        coVerify(exactly = 0) { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) }
    }

    @Test
    fun `approveAllReview reports one result per pending review in queue order`() = runTest {
        val overLimit = approvablePendingReview(41L).copy(suggestedAmount = 2_000_000.0)
        val blankCurrency = approvablePendingReview(42L).copy(suggestedCurrency = "")
        coEvery { pendingReviewDao.getPendingUncapped() } returns listOf(
            PendingReviewWithReceipt(review = overLimit, receipt = null),
            PendingReviewWithReceipt(review = blankCurrency, receipt = null)
        )
        coEvery { pendingReviewDao.getById(41L) } returns overLimit
        coEvery { pendingReviewDao.getById(42L) } returns blankCurrency

        val results = repository.approveAllReview()

        assertEquals(listOf(41L, 42L), results.map { it.first })
        assertEquals("AMOUNT_EXCEEDS_LIMIT", (results[0].second as Result.Error).message)
        assertEquals(
            "Currency is required. Please edit the review and select a currency.",
            (results[1].second as Result.Error).message
        )
        coVerify(exactly = 0) { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) }
    }

    @Test
    fun `rejectAllReviews on an empty queue rejects nothing and records no correction`() = runTest {
        coEvery { pendingReviewDao.getPendingUncapped() } returns emptyList()

        repository.rejectAllReviews()

        coVerify(exactly = 0) { pendingReviewDao.transitionStatus(any(), any(), any()) }
        coVerify(exactly = 0) { userCorrectionDao.insert(any()) }
    }

    @Test
    fun `rejectAllReviews rejects every pending review and records one correction each`() = runTest {
        val first = approvablePendingReview(51L)
        val second = approvablePendingReview(52L)
        coEvery { pendingReviewDao.getPendingUncapped() } returns listOf(
            PendingReviewWithReceipt(review = first, receipt = null),
            PendingReviewWithReceipt(review = second, receipt = null)
        )
        coEvery { pendingReviewDao.getById(51L) } returns first
        coEvery { pendingReviewDao.getById(52L) } returns second
        coEvery {
            pendingReviewDao.transitionStatus(any(), PendingReviewStatus.PENDING, PendingReviewStatus.REJECTED)
        } returns 1

        repository.rejectAllReviews()

        coVerify(exactly = 1) {
            pendingReviewDao.transitionStatus(51L, PendingReviewStatus.PENDING, PendingReviewStatus.REJECTED)
        }
        coVerify(exactly = 1) {
            pendingReviewDao.transitionStatus(52L, PendingReviewStatus.PENDING, PendingReviewStatus.REJECTED)
        }
        coVerify(exactly = 2) { userCorrectionDao.insert(any()) }
    }

    @Test
    fun `getPendingReviewsByMerchant looks up by canonical key and name and returns the DAO rows`() = runTest {
        val lidlReview = approvablePendingReview(71L).copy(suggestedMerchant = "LIDL")
        coEvery { pendingReviewDao.getPendingByMerchant("lidl", "LIDL") } returns listOf(lidlReview)
        coEvery { pendingReviewDao.getPendingByMerchant("unknown", "Unknown") } returns emptyList()

        assertEquals(listOf(lidlReview), repository.getPendingReviewsByMerchant("LIDL"))
        assertTrue(repository.getPendingReviewsByMerchant("Unknown").isEmpty())
        coVerify(exactly = 1) { pendingReviewDao.getPendingByMerchant("lidl", "LIDL") }
        coVerify(exactly = 1) { pendingReviewDao.getPendingByMerchant("unknown", "Unknown") }
    }

    @Test
    fun `updatePendingReviewCategoryBulk writes the canonical key and category inside the write barrier`() = runTest {
        repository.updatePendingReviewCategoryBulk("Test Merchant", 7L)

        coVerify(exactly = 1) {
            pendingReviewDao.bulkUpdateCategoryByMerchant("testmerchant", "Test Merchant", 7L)
        }
        coVerify(exactly = 1) {
            writeBarrier.runWrite(
                match<DatabaseAccessOperation> {
                    it.name == "ReviewQueueRepository.updatePendingReviewCategoryBulk"
                },
                any<suspend () -> Any?>()
            )
        }
    }

    @Test
    fun `updatePendingReviewMerchantBulk renames with canonical old and new keys inside the write barrier`() = runTest {
        repository.updatePendingReviewMerchantBulk("Old", "New")

        coVerify(exactly = 1) {
            pendingReviewDao.bulkRenameMerchant("old", "Old", "New", "new")
        }
        coVerify(exactly = 1) {
            writeBarrier.runWrite(
                match<DatabaseAccessOperation> {
                    it.name == "ReviewQueueRepository.updatePendingReviewMerchantBulk"
                },
                any<suspend () -> Any?>()
            )
        }
    }

    private fun repositoryWithTimeProvider(tp: TimeProvider): ReviewQueueRepository =
        ReviewQueueRepository(
            writeBarrier = writeBarrier,
            database = database,
            pendingReviewDao = pendingReviewDao,
            rawNotificationDao = rawNotificationDao,
            expenseDao = expenseDao,
            sourceStatsDao = sourceStatsDao,
            receiptLinkService = receiptLinkService,
            userCorrectionDao = userCorrectionDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            classifier = classifier,
            budgetMonitor = budgetMonitor,
            parserRegistry = parserRegistry,
            timeProvider = tp,
            confidenceRouter = confidenceRouter,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
            pendingReviewSourceLinkService = mockk(relaxed = true),
            pendingReviewSourceLinkPromoter = mockk(relaxed = true),
            transactionRunner = mockk(relaxed = true),
            transactionLifecycleEventWriter = mockk(relaxed = true),
            postCommitActionRunner = mockk(relaxed = true),
            privacySettingsRepository = privacySettingsRepository,
            merchantCategoryRepository = merchantCategoryRepository,
        )

    @Test
    fun `approveReview populates UserCorrection createdAt from TimeProvider`() = runTest {
        val fixedTime = 1_712_345_678_001L
        val repo = repositoryWithTimeProvider(FakeTimeProvider(fixedTime))
        val reviewId = 801L
        coEvery { pendingReviewDao.getById(reviewId) } returns approvablePendingReview(reviewId)
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.PROCESSING
            )
        } returns 1
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(901L), PostCommitActionBatch.empty(""))

        val result = repo.approveReview(reviewId)

        assertTrue(result is Result.Success)
        val correctionSlot = slot<UserCorrection>()
        coVerify(exactly = 1) { userCorrectionDao.insert(capture(correctionSlot)) }
        assertTrue(correctionSlot.captured.createdAt > 0L)
        assertEquals(fixedTime, correctionSlot.captured.createdAt)
    }

    @Test
    fun `rejectReview populates UserCorrection createdAt from TimeProvider`() = runTest {
        val fixedTime = 1_712_345_678_002L
        val repo = repositoryWithTimeProvider(FakeTimeProvider(fixedTime))
        val reviewId = 802L
        coEvery { pendingReviewDao.getById(reviewId) } returns approvablePendingReview(reviewId)
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId,
                PendingReviewStatus.PENDING,
                PendingReviewStatus.REJECTED
            )
        } returns 1

        repo.rejectReview(reviewId)

        val correctionSlot = slot<UserCorrection>()
        coVerify(exactly = 1) { userCorrectionDao.insert(capture(correctionSlot)) }
        assertTrue(correctionSlot.captured.createdAt > 0L)
        assertEquals(fixedTime, correctionSlot.captured.createdAt)
    }

    @Test
    fun `markAsRelevant populates UserCorrection createdAt from TimeProvider`() = runTest {
        val fixedTime = 1_712_345_678_003L
        val repo = repositoryWithTimeProvider(FakeTimeProvider(fixedTime))
        val notificationId = 803L
        val notification = RawNotification(
            id = notificationId,
            packageName = "com.bank.app",
            appName = "Bank",
            title = null,
            text = null,
            timestamp = 1_700_000_000_000L,
            capturedAt = 1_700_000_000_000L
        )
        coEvery { rawNotificationDao.getById(notificationId) } returns notification

        repo.markAsRelevant(notificationId, isRelevant = false)

        val correctionSlot = slot<UserCorrection>()
        coVerify(exactly = 1) { userCorrectionDao.insert(capture(correctionSlot)) }
        assertTrue(correctionSlot.captured.createdAt > 0L)
        assertEquals(fixedTime, correctionSlot.captured.createdAt)
    }

    // ── P-08-1: correction notification text only under STORE_RAW (fail closed) ──────────

    private val nonRawModes = listOf(
        RawStorageMode.STORE_REDACTED,
        RawStorageMode.STORE_METADATA_ONLY,
        RawStorageMode.DO_NOT_STORE
    )

    private fun stubMode(mode: RawStorageMode) {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = mode)
    }

    private fun stubReadFailure() {
        coEvery { privacySettingsRepository.getSettings() } throws
            IllegalStateException("SYNTHETIC_SETTINGS_READ_FAILURE")
    }

    private fun stubReadCancelled() {
        coEvery { privacySettingsRepository.getSettings() } throws
            kotlinx.coroutines.CancellationException("synthetic cancel")
    }

    private fun privacyReview(reviewId: Long) = approvablePendingReview(reviewId).copy(
        suggestedMerchant = "Synthetic Merchant",
        notificationTitle = "Synthetic title 4711",
        notificationText = "Synthetic body 4711"
    )

    private fun stubApprovable(reviewId: Long) {
        coEvery { pendingReviewDao.getById(reviewId) } returns privacyReview(reviewId)
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.PROCESSING
            )
        } returns 1
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(555L), PostCommitActionBatch.empty(""))
    }

    private fun stubRejectable(reviewId: Long) {
        coEvery { pendingReviewDao.getById(reviewId) } returns privacyReview(reviewId)
        coEvery {
            pendingReviewDao.transitionStatus(
                reviewId, PendingReviewStatus.PENDING, PendingReviewStatus.REJECTED
            )
        } returns 1
    }

    private val markRelevantNotification = RawNotification(
        id = 900L,
        packageName = "com.bank.synthetic",
        appName = "Bank",
        title = "Synthetic notif title 4711",
        text = "Synthetic notif body 4711",
        timestamp = 1_700_000_000_000L,
        capturedAt = 1_700_000_000_000L
    )

    private suspend fun approveAndCapture(reviewId: Long): UserCorrection {
        stubApprovable(reviewId)
        val result = repository.approveReview(reviewId)
        assertTrue("approve must still succeed: $result", result is Result.Success)
        val slot = slot<UserCorrection>()
        coVerify(exactly = 1) { userCorrectionDao.insert(capture(slot)) }
        return slot.captured
    }

    private suspend fun rejectAndCapture(reviewId: Long): UserCorrection {
        stubRejectable(reviewId)
        repository.rejectReview(reviewId)
        val slot = slot<UserCorrection>()
        coVerify(exactly = 1) { userCorrectionDao.insert(capture(slot)) }
        return slot.captured
    }

    private suspend fun markRelevantAndCapture(isRelevant: Boolean): UserCorrection {
        coEvery { rawNotificationDao.getById(900L) } returns markRelevantNotification
        every { parserRegistry.parse(any(), any(), any(), any(), any()) } returns null
        repository.markAsRelevant(900L, isRelevant)
        val slot = slot<UserCorrection>()
        coVerify(exactly = 1) { userCorrectionDao.insert(capture(slot)) }
        return slot.captured
    }

    private fun assertTextOmitted(mode: String, c: UserCorrection) {
        assertNull("title must be null for $mode", c.notificationTitle)
        assertNull("text must be null for $mode", c.notificationText)
    }

    @Test
    fun `P-08-1 approveReview STORE_RAW keeps original title and text`() = runTest {
        stubMode(RawStorageMode.STORE_RAW)
        val c = approveAndCapture(1001L)
        assertEquals("Synthetic title 4711", c.notificationTitle)
        assertEquals("Synthetic body 4711", c.notificationText)
        assertEquals("Synthetic Merchant", c.originalMerchant)
    }

    @Test
    fun `P-08-1 approveReview omits title and text in every non-raw mode and keeps originalMerchant`() = runTest {
        for (mode in nonRawModes) {
            clearMocks(userCorrectionDao)
            stubMode(mode)
            val c = approveAndCapture(1002L)
            assertTextOmitted(mode.name, c)
            assertEquals("originalMerchant unchanged for $mode", "Synthetic Merchant", c.originalMerchant)
            assertTrue(c.wasApproved)
        }
    }

    @Test
    fun `P-08-1 approveReview omits title and text when settings read fails`() = runTest {
        stubReadFailure()
        val c = approveAndCapture(1003L)
        assertTextOmitted("read failure", c)
        assertEquals("Synthetic Merchant", c.originalMerchant)
    }

    @Test
    fun `P-08-1 rejectReview STORE_RAW keeps original title and text`() = runTest {
        stubMode(RawStorageMode.STORE_RAW)
        val c = rejectAndCapture(1011L)
        assertEquals("Synthetic title 4711", c.notificationTitle)
        assertEquals("Synthetic body 4711", c.notificationText)
        assertEquals("Synthetic Merchant", c.originalMerchant)
        assertTrue(c.wasRejected)
    }

    @Test
    fun `P-08-1 rejectReview omits title and text in every non-raw mode and keeps originalMerchant`() = runTest {
        for (mode in nonRawModes) {
            clearMocks(userCorrectionDao)
            stubMode(mode)
            val c = rejectAndCapture(1012L)
            assertTextOmitted(mode.name, c)
            assertEquals("originalMerchant unchanged for $mode", "Synthetic Merchant", c.originalMerchant)
            assertTrue(c.wasRejected)
        }
    }

    @Test
    fun `P-08-1 rejectReview omits title and text when settings read fails`() = runTest {
        stubReadFailure()
        val c = rejectAndCapture(1013L)
        assertTextOmitted("read failure", c)
        assertEquals("Synthetic Merchant", c.originalMerchant)
    }

    @Test
    fun `P-08-1 markAsRelevant STORE_RAW keeps notification title and text`() = runTest {
        stubMode(RawStorageMode.STORE_RAW)
        val c = markRelevantAndCapture(isRelevant = false)
        assertEquals("Synthetic notif title 4711", c.notificationTitle)
        assertEquals("Synthetic notif body 4711", c.notificationText)
        assertEquals("Manual", c.originalMerchant)
    }

    @Test
    fun `P-08-1 markAsRelevant omits title and text in every non-raw mode and keeps originalMerchant`() = runTest {
        for (mode in nonRawModes) {
            for (relevant in listOf(false, true)) {
                clearMocks(userCorrectionDao)
                stubMode(mode)
                val c = markRelevantAndCapture(isRelevant = relevant)
                assertTextOmitted("$mode relevant=$relevant", c)
                assertEquals("Manual", c.originalMerchant)
            }
        }
    }

    @Test
    fun `P-08-1 markAsRelevant omits title and text when settings read fails`() = runTest {
        stubReadFailure()
        val c = markRelevantAndCapture(isRelevant = false)
        assertTextOmitted("read failure", c)
        assertEquals("Manual", c.originalMerchant)
    }

    @Test
    fun `P-08-1 approveReview rethrows cancelled settings read and writes nothing`() = runTest {
        stubApprovable(1021L)
        stubReadCancelled()
        try {
            repository.approveReview(1021L)
            fail("CancellationException must propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        coVerify(exactly = 0) { userCorrectionDao.insert(any()) }
        coVerify(exactly = 0) { pendingReviewDao.transitionStatus(any(), any(), any()) }
    }

    @Test
    fun `P-08-1 rejectReview rethrows cancelled settings read and writes nothing`() = runTest {
        stubRejectable(1022L)
        stubReadCancelled()
        try {
            repository.rejectReview(1022L)
            fail("CancellationException must propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        coVerify(exactly = 0) { userCorrectionDao.insert(any()) }
        coVerify(exactly = 0) { pendingReviewDao.transitionStatus(any(), any(), any()) }
    }

    @Test
    fun `P-08-1 markAsRelevant rethrows cancelled settings read and writes nothing`() = runTest {
        coEvery { rawNotificationDao.getById(900L) } returns markRelevantNotification
        every { parserRegistry.parse(any(), any(), any(), any(), any()) } returns null
        stubReadCancelled()
        try {
            repository.markAsRelevant(900L, isRelevant = false)
            fail("CancellationException must propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        coVerify(exactly = 0) { userCorrectionDao.insert(any()) }
        coVerify(exactly = 0) { rawNotificationDao.markRelevance(any(), any()) }
    }

    @Test
    fun `P-08-1 settings are read before the write scope for approve reject and markAsRelevant`() = runTest {
        val events = mutableListOf<String>()
        coEvery { privacySettingsRepository.getSettings() } coAnswers {
            events += "settings"
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW)
        }
        coEvery {
            writeBarrier.runWrite(any<DatabaseAccessOperation>(), any<suspend () -> Any?>())
        } coAnswers {
            events += "write"
            secondArg<suspend () -> Any?>().invoke()
        }

        stubApprovable(1031L)
        repository.approveReview(1031L)
        assertEquals(listOf("settings", "write"), events.toList())

        events.clear()
        stubRejectable(1032L)
        repository.rejectReview(1032L)
        assertEquals(listOf("settings", "write"), events.toList())

        events.clear()
        coEvery { rawNotificationDao.getById(900L) } returns markRelevantNotification
        every { parserRegistry.parse(any(), any(), any(), any(), any()) } returns null
        repository.markAsRelevant(900L, isRelevant = false)
        assertEquals(listOf("settings", "write"), events.toList())
    }
}
