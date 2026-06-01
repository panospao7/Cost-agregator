package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankStatementImportItemDao
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.data.repository.ReceiptRecordWriter
import com.yourname.expensetracker.data.repository.ReceiptRecordWriteResult
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.model.Result as DomainResult
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkService
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * P3-PR2 — Lifecycle Hardening tests.
 *
 * Covers:
 * - P3-P1-04: Legacy receipt+expense path not reachable from production
 * - P3-P1-05: Direct DAO mutation paths respect the write barrier
 * - P3-P1-09: Batch import creates pending reviews for low-confidence parses
 * - P3-P1-10: Bank statement dedupe matches existing expenses (not just pending reviews)
 */
@Suppress("DEPRECATION_ERROR")
class ReceiptLifecycleHardeningTest {

    private lateinit var database: AppDatabase
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var receiptLinkService: ReceiptLinkService
    private lateinit var assetStore: ReceiptAssetStore
    private lateinit var inputValidator: ReceiptInputValidator
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var receiptExpenseLinkDao: ReceiptExpenseLinkDao
    private lateinit var receiptEventDao: ReceiptEventDao
    private lateinit var emailReceiptDao: EmailReceiptDao
    private lateinit var pendingReviewDao: PendingReviewDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var bankStatementLifecycleProcessor: BankStatementLifecycleProcessor
    private lateinit var sideEffectDispatcher: ReceiptSideEffectDispatcher
    private lateinit var duplicateDetector: ReceiptDuplicateDetector
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var transactionLifecycleCoordinator: TransactionLifecycleCoordinator
    private lateinit var postCommitActionRunner: PostCommitActionRunner
    private lateinit var merchantNormalizer: MerchantNormalizer
    private lateinit var hybridClassifier: HybridExpenseClassifier
    private lateinit var privacySettingsRepository: PrivacySettingsRepository
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var sourceLinkWriter: SourceLinkWriter
    private lateinit var receiptSideEffectPlanner: ReceiptSideEffectPlanner
    private lateinit var pendingReviewSourceLinkService: PendingReviewSourceLinkService
    private lateinit var receiptInsertResolver: ReceiptInsertResolver
    private lateinit var effectiveCloudAiPolicyResolver: EffectiveCloudAiPolicyResolver
    private lateinit var expenseDao: ExpenseDao
    private lateinit var receiptRecordWriter: ReceiptRecordWriter
    private lateinit var bankStatementImportRunDao: BankStatementImportRunDao
    private lateinit var bankStatementImportItemDao: BankStatementImportItemDao

    private lateinit var coordinator: ReceiptLifecycleCoordinator

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        receiptRepository = mockk(relaxed = true)
        receiptLinkService = mockk(relaxed = true)
        assetStore = mockk(relaxed = true)
        inputValidator = mockk(relaxed = true)
        scannedReceiptDao = mockk(relaxed = true)
        receiptExpenseLinkDao = mockk(relaxed = true)
        receiptEventDao = mockk(relaxed = true)
        emailReceiptDao = mockk(relaxed = true)
        pendingReviewDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        bankStatementLifecycleProcessor = mockk(relaxed = true)
        sideEffectDispatcher = mockk(relaxed = true)
        duplicateDetector = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        transactionLifecycleCoordinator = mockk(relaxed = true)
        postCommitActionRunner = mockk(relaxed = true)
        merchantNormalizer = mockk(relaxed = true)
        hybridClassifier = mockk(relaxed = true)
        privacySettingsRepository = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)
        sourceLinkWriter = mockk(relaxed = true)
        receiptSideEffectPlanner = mockk(relaxed = true)
        pendingReviewSourceLinkService = mockk(relaxed = true)
        receiptInsertResolver = mockk(relaxed = true)
        effectiveCloudAiPolicyResolver = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        receiptRecordWriter = mockk(relaxed = true)
        bankStatementImportRunDao = mockk(relaxed = true)
        bankStatementImportItemDao = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(1L)
        coEvery { writeBarrier.checkWritesAllowed(any()) } returns Unit
        coEvery { effectiveCloudAiPolicyResolver.resolve() } returns mockk(relaxed = true) {
            every { requireAllowed(any()) } returns Unit
            every { redactBeforeCloud } returns false
        }

        coordinator = ReceiptLifecycleCoordinator(
            database = database,
            receiptRepository = receiptRepository,
            receiptLinkService = receiptLinkService,
            assetStore = assetStore,
            inputValidator = inputValidator,
            scannedReceiptDao = scannedReceiptDao,
            receiptExpenseLinkDao = receiptExpenseLinkDao,
            receiptEventDao = receiptEventDao,
            emailReceiptDao = emailReceiptDao,
            pendingReviewDao = pendingReviewDao,
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = bankStatementLifecycleProcessor,
            sideEffectDispatcher = sideEffectDispatcher,
            duplicateDetector = duplicateDetector,
            currencySettingsRepository = currencySettingsRepository,
            restoreMaintenanceMode = mockk(relaxed = true) {
                every { isWritesAllowed() } returns true
            },
            writeBarrier = writeBarrier,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
            postCommitActionRunner = postCommitActionRunner,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            privacySettingsRepository = privacySettingsRepository,
            diagnosticEventWriter = diagnosticEventWriter,
            sourceLinkWriter = sourceLinkWriter,
            receiptSideEffectPlanner = receiptSideEffectPlanner,
            pendingReviewSourceLinkService = pendingReviewSourceLinkService,
            receiptInsertResolver = receiptInsertResolver,
            effectiveCloudAiPolicyResolver = effectiveCloudAiPolicyResolver
        )
    }

    // ──────────────────────────────────────────────────────────────
    // P3-P1-04: Legacy createExpenseFromReceipt not reachable
    // ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [ReceiptRepository.createExpenseFromReceipt] returns an error
     * when called — it is permanently disabled with @Deprecated(ERROR) and its
     * method body always returns [DomainResult.Error].  No production code path
     * should reach this method; if a legacy caller somehow does, it will receive
     * a descriptive error rather than silently executing.
     */
    @Test
    fun `legacy_createExpenseFromReceipt_not_reachable_from_production`() = runTest {
        // Arrange — set up the repository with required mocks
        val repo = ReceiptRepository(
            database = mockk(relaxed = true),
            scannedReceiptDao = scannedReceiptDao,
            expenseDao = expenseDao,
            pendingReviewDao = pendingReviewDao,
            ocrService = mockk(relaxed = true),
            receiptParser = mockk(relaxed = true),
            statementParser = mockk(relaxed = true),
            categorizationEngine = mockk(relaxed = true),
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            crossSourceDeduplication = mockk(relaxed = true),
            debugIssueDetector = mockk(relaxed = true),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            timeProvider = timeProvider,
            coordinator = transactionLifecycleCoordinator,
            receiptLinkService = receiptLinkService,
            assetStore = assetStore,
            currencySettingsRepository = currencySettingsRepository,
            receiptLifecycleCoordinator = mockk(relaxed = true) {
                every { get() } returns coordinator
            },
            writeBarrier = writeBarrier,
            privacySettingsRepository = privacySettingsRepository,
            receiptEventDao = receiptEventDao,
            receiptInsertResolver = receiptInsertResolver,
            pendingReviewSourceLinkService = pendingReviewSourceLinkService
        )

        // Act — call the deprecated method
        val result = repo.createExpenseFromReceipt(
            receiptId = 1L,
            merchant = "Test Merchant",
            amount = 25.0,
            currency = "USD",
            categoryId = null,
            date = now
        )

        // Assert — the result is an error with a clear message
        assertTrue("Expected error result from deprecated createExpenseFromReceipt",
            result is DomainResult.Error)
        val errorResult = result as DomainResult.Error
        assertNotNull("Error message should not be null", errorResult.message)
        assertTrue("Error message should indicate permanent disablement",
            errorResult.message!!.contains("disabled", ignoreCase = true))
    }

    /**
     * Also verifies that the [ReceiptLifecycleCoordinator.createExpenseFromReceipt]
     * (already @Deprecated(ERROR)) returns an error and is not callable from
     * production code.
     */
    @Test
    fun `coordinator_createExpenseFromReceipt_returns_error`() = runTest {
        // Act
        val result = coordinator.createExpenseFromReceipt(
            receiptId = 1L,
            merchant = "Test",
            amount = 10.0,
            categoryId = null,
            date = now
        )

        // Assert
        assertTrue("Expected error result from coordinator's deprecated method",
            result is DomainResult.Error)
    }

    // ──────────────────────────────────────────────────────────────
    // P3-P1-05: Direct DAO paths respect write barrier
    // ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [BankStatementLifecycleProcessor] calls
     * [DatabaseWriteBarrier.checkWritesAllowed] before the direct
     * [ScannedReceiptDao.update] in the step-8 finalization block
     * (processing status update for successful runs).
     *
     * The test constructs a minimal scenario that triggers step 8
     * and confirms the write barrier was invoked.
     */
    @Test
    fun `bank_statement_lifecycle_finalize_respects_write_barrier`() = runTest {
        // Arrange — build a BankStatementLifecycleProcessor with real mocks
        val processor = BankStatementLifecycleProcessor(
            database = database,
            receiptRepository = receiptRepository,
            scannedReceiptDao = scannedReceiptDao,
            receiptLifecycleEventWriter = mockk(relaxed = true),
            receiptLinkService = receiptLinkService,
            timeProvider = timeProvider,
            bankStatementParser = mockk(relaxed = true),
            pendingReviewDao = pendingReviewDao,
            expenseDao = expenseDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            duplicateDetector = duplicateDetector,
            assetStore = assetStore,
            transactionValidator = mockk(relaxed = true),
            recurringExpenseRepository = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            privacySettingsRepository = privacySettingsRepository,
            bankStatementImportRunDao = bankStatementImportRunDao,
            bankStatementImportItemDao = bankStatementImportItemDao,
            receiptRecordWriter = receiptRecordWriter
        )

        // We trigger a simple codepath that exercises step-8 DAO calls
        // by calling processBankStatement.  Mock the early steps to
        // allow reaching the finalization block.
        coEvery { assetStore.computeUriHash(any()) } returns Result.success("hash123")
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateCheckResult(false)
        coEvery { receiptRepository.runStatementOcr(any()) } returns mockk(relaxed = true) {
            every { fullText } returns "STATEMENT TEXT"
            every { savedImagePath } returns "/tmp/statement.jpg"
            every { pagesProcessed } returns null
            every { totalPages } returns null
        }
        coEvery { bankStatementParser.parse(any(), any()) } returns listOf(
            BankStatementParser.ParsedTransaction(
                merchant = "Test Store",
                amount = 42.50,
                currency = "USD",
                date = now,
                confidence = 0.9f,
                type = BankStatementParser.ParsedTransactionType.PURCHASE
            )
        )
        coEvery { bankStatementParser.resolveHomeCurrencySuspend() } returns "USD"
        coEvery { bankStatementImportRunDao.insert(any()) } returns 1L
        coEvery { receiptRecordWriter.insertOrResolve(any()) } returns
            com.yourname.expensetracker.data.repository.ReceiptRecordWriteResult.Inserted(
                ScannedReceipt(id = 1L, confidence = 0.8f)
            )
        coEvery { merchantNormalizer.normalize(any(), any()) } returns mockk {
            every { canonical } returns mockk {
                every { normalizedName } returns "Test Store"
            }
        }
        coEvery { hybridClassifier.classify(any(), any()) } returns mockk {
            every { categoryId } returns 5L
        }
        coEvery { pendingReviewDao.insert(any()) } returns 1L

        // Clear the write barrier mock call count
        clearMocks(writeBarrier)

        // Act
        val uri = mockk<Uri>(relaxed = true)
        processor.processBankStatement(uri)

        // Assert — the write barrier was called at step 8 (finalizeStatus)
        coVerify(atLeast = 1) {
            writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.finalizeStatus")
        }
    }

    /**
     * Verifies that [ReceiptRepository.clearMatchForReceipt] calls
     * [DatabaseWriteBarrier.checkWritesAllowed] before performing
     * direct DAO mutations.
     */
    @Test
    fun `clearMatchForReceipt_respects_write_barrier`() = runTest {
        // Arrange
        val repo = ReceiptRepository(
            database = mockk(relaxed = true),
            scannedReceiptDao = scannedReceiptDao,
            expenseDao = expenseDao,
            pendingReviewDao = pendingReviewDao,
            ocrService = mockk(relaxed = true),
            receiptParser = mockk(relaxed = true),
            statementParser = mockk(relaxed = true),
            categorizationEngine = mockk(relaxed = true),
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            crossSourceDeduplication = mockk(relaxed = true),
            debugIssueDetector = mockk(relaxed = true),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            timeProvider = timeProvider,
            coordinator = transactionLifecycleCoordinator,
            receiptLinkService = receiptLinkService,
            assetStore = assetStore,
            currencySettingsRepository = currencySettingsRepository,
            receiptLifecycleCoordinator = mockk(relaxed = true) {
                every { get() } returns coordinator
            },
            writeBarrier = writeBarrier,
            privacySettingsRepository = privacySettingsRepository,
            receiptEventDao = receiptEventDao,
            receiptInsertResolver = receiptInsertResolver,
            pendingReviewSourceLinkService = pendingReviewSourceLinkService
        )

        coEvery { scannedReceiptDao.getById(1L) } returns ScannedReceipt(
            id = 1L, parsedMerchant = "Test", confidence = 0.9f
        )
        clearMocks(writeBarrier)

        // Act
        repo.clearMatchForReceipt(1L)

        // Assert
        coVerify(exactly = 1) {
            writeBarrier.checkWritesAllowed("ReceiptRepository.clearMatchForReceipt")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // P3-P1-09: Batch import creates review for low confidence
    // ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [ReceiptLifecycleCoordinator.processReceiptInput]
     * creates a [PendingReview] when the parsed receipt confidence is
     * below the [ReceiptLifecycleCoordinator.BATCH_REVIEW_CONFIDENCE_THRESHOLD],
     * even when the caller does NOT explicitly request review.
     *
     * This ensures low-confidence OCR/parse results always get a
     * human review, regardless of the originating path.
     */
    @Test
    fun `batch_import_creates_review_for_low_confidence`() = runTest {
        // Arrange
        val uri = mockk<Uri>(relaxed = true)
        val validationResult = ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(),
            mimeType = "image/jpeg", fileSizeBytes = 1024L
        )
        // Use a confidence well below 0.75 threshold
        val lowConfidence: Float = 0.45f
        val parsedReceipt = ReceiptParser.ParsedReceipt(
            merchantName = "Low Confidence Shop",
            total = 25.0,
            subtotal = null,
            tax = null,
            date = now,
            currency = "USD",
            lineItems = emptyList(),
            confidence = lowConfidence,
            taxInclusive = false
        )
        val draftReceipt = ScannedReceipt(
            id = 0L,
            imagePath = "/tmp/scan.jpg",
            rawOcrText = "Low confidence text",
            parsedTotal = 25.0,
            parsedMerchant = "Low Confidence Shop",
            parsedDate = now,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "USD",
            confidence = lowConfidence
        )
        val processResult = ReceiptRepository.ProcessReceiptResult(
            receipt = draftReceipt,
            parsed = parsedReceipt,
            isPreExistingDuplicate = false
        )

        coEvery { inputValidator.validate(any()) } returns validationResult
        coEvery { receiptRepository.processReceipt(any(), any(), any()) } returns processResult
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(1L)
        coEvery { scannedReceiptDao.getById(1L) } returns draftReceipt.copy(id = 1L)
        coEvery { privacySettingsRepository.getSettings() } returns mockk(relaxed = true) {
            every { rawOcrStorageMode } returns com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_RAW
        }
        coEvery { hybridClassifier.classify(any(), any()) } returns mockk {
            every { categoryId } returns 0L // no category
        }
        coEvery { pendingReviewDao.insert(any()) } returns 1L
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any()) } returns
            com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch.empty("test")

        clearMocks(pendingReviewDao)

        // Act — use DEFAULT options (createReview = false)
        val result = coordinator.processReceiptInput(
            uri,
            options = ReceiptLifecycleCoordinator.ReceiptProcessingOptions(
                createReview = false,
                autoMatchExistingExpense = true
            )
        )

        // Assert
        assertTrue("Receipt should process successfully with low confidence", result.isSuccess)

        // Verify a review was created despite createReview=false because
        // confidence is below the threshold
        coVerify(atLeast = 1) {
            pendingReviewDao.insert(any())
        }
    }

    /**
     * Verifies that when confidence is above the threshold AND
     * createReview=false, no [PendingReview] is created.
     */
    @Test
    fun `batch_import_skips_review_for_high_confidence_when_not_requested`() = runTest {
        // Arrange
        val uri = mockk<Uri>(relaxed = true)
        val validationResult = ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(),
            mimeType = "image/jpeg", fileSizeBytes = 1024L
        )
        // Use a confidence above 0.75 threshold
        val highConfidence: Float = 0.95f
        val parsedReceipt = ReceiptParser.ParsedReceipt(
            merchantName = "High Confidence Shop",
            total = 100.0,
            subtotal = null,
            tax = null,
            date = now,
            currency = "USD",
            lineItems = emptyList(),
            confidence = highConfidence,
            taxInclusive = false
        )
        val draftReceipt = ScannedReceipt(
            id = 0L,
            imagePath = "/tmp/scan.jpg",
            rawOcrText = "High confidence text",
            parsedTotal = 100.0,
            parsedMerchant = "High Confidence Shop",
            parsedDate = now,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "USD",
            confidence = highConfidence
        )
        val processResult = ReceiptRepository.ProcessReceiptResult(
            receipt = draftReceipt,
            parsed = parsedReceipt,
            isPreExistingDuplicate = false
        )

        coEvery { inputValidator.validate(any()) } returns validationResult
        coEvery { receiptRepository.processReceipt(any(), any(), any()) } returns processResult
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(1L)
        coEvery { scannedReceiptDao.getById(1L) } returns draftReceipt.copy(id = 1L)
        coEvery { privacySettingsRepository.getSettings() } returns mockk(relaxed = true) {
            every { rawOcrStorageMode } returns com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_RAW
        }
        coEvery { hybridClassifier.classify(any(), any()) } returns mockk {
            every { categoryId } returns 0L
        }
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any()) } returns
            com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch.empty("test")

        clearMocks(pendingReviewDao)

        // Act — use DEFAULT options (createReview = false)
        val result = coordinator.processReceiptInput(
            uri,
            options = ReceiptLifecycleCoordinator.ReceiptProcessingOptions(
                createReview = false,
                autoMatchExistingExpense = true
            )
        )

        // Assert
        assertTrue("Receipt should process successfully with high confidence", result.isSuccess)

        // Verify NO review was created because confidence is above threshold
        // and createReview is false
        coVerify(exactly = 0) {
            pendingReviewDao.insert(any())
        }
    }

    // ──────────────────────────────────────────────────────────────
    // P3-P1-10: Bank statement dedupe matches existing expenses
    // ──────────────────────────────────────────────────────────────

    /**
     * Verifies that [BankStatementLifecycleProcessor] checks the expense
     * table for duplicates (not just pending reviews), using amount+date+
     * merchant-key window parameters consistent with
     * [DuplicateDetectionPolicy].
     *
     * When an existing approved expense matches the transaction's merchant,
     * amount, date, currency, and transaction type, the transaction is
     * skipped (no review created) and counted as a duplicate.
     */
    @Test
    fun `bank_statement_dedupe_matches_existing_expenses`() = runTest {
        // Arrange
        val processor = BankStatementLifecycleProcessor(
            database = database,
            receiptRepository = receiptRepository,
            scannedReceiptDao = scannedReceiptDao,
            receiptLifecycleEventWriter = mockk(relaxed = true),
            receiptLinkService = receiptLinkService,
            timeProvider = timeProvider,
            bankStatementParser = mockk(relaxed = true),
            pendingReviewDao = pendingReviewDao,
            expenseDao = expenseDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            duplicateDetector = duplicateDetector,
            assetStore = assetStore,
            transactionValidator = mockk(relaxed = true),
            recurringExpenseRepository = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            privacySettingsRepository = privacySettingsRepository,
            bankStatementImportRunDao = bankStatementImportRunDao,
            bankStatementImportItemDao = bankStatementImportItemDao,
            receiptRecordWriter = receiptRecordWriter
        )

        val uri = mockk<Uri>(relaxed = true)
        val merchantName = "Duplicate Store"
        val txAmount = 55.0
        val txCurrency = "USD"
        val txDate = now

        // Setup mocks to reach the dedupe check
        coEvery { assetStore.computeUriHash(any()) } returns Result.success("hash456")
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateCheckResult(false)
        coEvery { receiptRepository.runStatementOcr(any()) } returns mockk(relaxed = true) {
            every { fullText } returns "STATEMENT TEXT"
            every { savedImagePath } returns "/tmp/statement.jpg"
            every { pagesProcessed } returns null
            every { totalPages } returns null
        }
        coEvery { bankStatementParser.parse(any(), any()) } returns listOf(
            BankStatementParser.ParsedTransaction(
                merchant = merchantName,
                amount = txAmount,
                currency = txCurrency,
                date = txDate,
                confidence = 0.85f,
                type = BankStatementParser.ParsedTransactionType.PURCHASE
            )
        )
        coEvery { bankStatementParser.resolveHomeCurrencySuspend() } returns txCurrency
        coEvery { bankStatementImportRunDao.insert(any()) } returns 1L
        coEvery { receiptRecordWriter.insertOrResolve(any()) } returns
            com.yourname.expensetracker.data.repository.ReceiptRecordWriteResult.Inserted(
                ScannedReceipt(id = 1L, confidence = 0.8f)
            )
        coEvery { merchantNormalizer.normalize(merchantName, any()) } returns mockk {
            every { canonical } returns mockk {
                every { normalizedName } returns merchantName
            }
        }
        coEvery { hybridClassifier.classify(any(), any()) } returns mockk {
            every { categoryId } returns 5L
        }

        // The EXPENSE table should report a match for the transaction's
        // merchant key + amount window + date window + currency + type.
        val merchantKey = MerchantKeyGenerator.generate(merchantName)
        val dedupWindow = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS
        val amountTolerance = DuplicateDetectionPolicy.AMOUNT_TOLERANCE
        val startDate = txDate - dedupWindow
        val endDate = DuplicateDetectionPolicy.windowEndExclusive(txDate)
        val minAmount = txAmount - amountTolerance
        val maxAmount = txAmount + amountTolerance

        // The expense DAO reports a match via merchantKey
        coEvery {
            expenseDao.existsByMerchantKeyInRangeCurrencyAware(
                merchantKey = merchantKey,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = txCurrency,
                transactionType = "PURCHASE"
            )
        } returns true

        // The pending review DAO should NOT be consulted (or if it is, returns null)
        coEvery {
            pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns null

        // Clear mock call counts
        clearMocks(bankStatementImportItemDao, pendingReviewDao)

        // Act
        val result = processor.processBankStatement(uri)

        // Assert
        assertTrue("Bank statement should complete successfully", result.isSuccess)
        val bankResult = result.getOrThrow()

        // The transaction should be counted as a duplicate expense (not a created review)
        assertEquals("Transaction should be skipped as duplicate expense",
            0, bankResult.reviewsCreated)
        assertEquals("Should have 1 duplicate skipped",
            1, bankResult.duplicatesSkipped)

        // Verify the expense dedupe query was made
        coVerify(atLeast = 1) {
            expenseDao.existsByMerchantKeyInRangeCurrencyAware(
                merchantKey = merchantKey,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = txCurrency,
                transactionType = "PURCHASE"
            )
        }
    }

    /**
     * Verifies that when NO existing expense matches, the bank statement
     * transaction proceeds to create a [PendingReview] (normal import path).
     */
    @Test
    fun `bank_statement_dedupe_allows_new_transactions`() = runTest {
        // Arrange
        val processor = BankStatementLifecycleProcessor(
            database = database,
            receiptRepository = receiptRepository,
            scannedReceiptDao = scannedReceiptDao,
            receiptLifecycleEventWriter = mockk(relaxed = true),
            receiptLinkService = receiptLinkService,
            timeProvider = timeProvider,
            bankStatementParser = mockk(relaxed = true),
            pendingReviewDao = pendingReviewDao,
            expenseDao = expenseDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            duplicateDetector = duplicateDetector,
            assetStore = assetStore,
            transactionValidator = mockk(relaxed = true),
            recurringExpenseRepository = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            privacySettingsRepository = privacySettingsRepository,
            bankStatementImportRunDao = bankStatementImportRunDao,
            bankStatementImportItemDao = bankStatementImportItemDao,
            receiptRecordWriter = receiptRecordWriter
        )

        val uri = mockk<Uri>(relaxed = true)

        coEvery { assetStore.computeUriHash(any()) } returns Result.success("hash789")
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateCheckResult(false)
        coEvery { receiptRepository.runStatementOcr(any()) } returns mockk(relaxed = true) {
            every { fullText } returns "STATEMENT TEXT"
            every { savedImagePath } returns "/tmp/statement2.jpg"
            every { pagesProcessed } returns null
            every { totalPages } returns null
        }
        coEvery { bankStatementParser.parse(any(), any()) } returns listOf(
            BankStatementParser.ParsedTransaction(
                merchant = "New Store",
                amount = 99.99,
                currency = "USD",
                date = now,
                confidence = 0.9f,
                type = BankStatementParser.ParsedTransactionType.PURCHASE
            )
        )
        coEvery { bankStatementParser.resolveHomeCurrencySuspend() } returns "USD"
        coEvery { bankStatementImportRunDao.insert(any()) } returns 1L
        coEvery { receiptRecordWriter.insertOrResolve(any()) } returns
            com.yourname.expensetracker.data.repository.ReceiptRecordWriteResult.Inserted(
                ScannedReceipt(id = 2L, confidence = 0.8f)
            )
        coEvery { merchantNormalizer.normalize(any(), any()) } returns mockk {
            every { canonical } returns mockk {
                every { normalizedName } returns "New Store"
            }
        }
        coEvery { hybridClassifier.classify(any(), any()) } returns mockk {
            every { categoryId } returns 10L
        }

        // No matching expense exists
        coEvery { expenseDao.existsByMerchantKeyInRangeCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { expenseDao.existsByMerchantInRangeCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returns false

        // No matching pending review exists
        coEvery { pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns null

        // Review insert succeeds
        coEvery { pendingReviewDao.insert(any()) } returns 1L

        clearMocks(pendingReviewDao, bankStatementImportItemDao)

        // Act
        val result = processor.processBankStatement(uri)

        // Assert
        assertTrue("Bank statement should complete successfully", result.isSuccess)
        val bankResult = result.getOrThrow()

        // The transaction should create a review (no duplicates found)
        assertEquals("Should create 1 review for the new transaction",
            1, bankResult.reviewsCreated)
    }
}
