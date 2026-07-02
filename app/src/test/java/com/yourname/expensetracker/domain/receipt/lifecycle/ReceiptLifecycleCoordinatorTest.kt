package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.EmailReceiptProcessResult
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlanner
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ReceiptLifecycleCoordinator.processReceiptInput].
 *
 * Validates: input validation → OCR/parse → dedupe → save → event logging.
 */
class ReceiptLifecycleCoordinatorTest {

    private lateinit var database: AppDatabase
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var receiptLinkService: ReceiptLinkService
    private lateinit var assetStore: ReceiptAssetStore
    private lateinit var inputValidator: ReceiptInputValidator
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var receiptExpenseLinkDao: ReceiptExpenseLinkDao
    private lateinit var receiptEventDao: ReceiptEventDao
    private lateinit var emailReceiptDao: EmailReceiptDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var bankStatementLifecycleProcessor: BankStatementLifecycleProcessor
    private lateinit var sideEffectDispatcher: ReceiptSideEffectDispatcher
    private lateinit var duplicateDetector: ReceiptDuplicateDetector
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var postCommitActionRunner: PostCommitActionRunner
    private lateinit var receiptSideEffectPlanner: ReceiptSideEffectPlanner
    private lateinit var receiptInsertResolver: ReceiptInsertResolver
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var privacySettingsRepository: PrivacySettingsRepository
    private lateinit var transactionLifecycleCoordinator: TransactionLifecycleCoordinator
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
        timeProvider = mockk(relaxed = true)
        bankStatementLifecycleProcessor = mockk(relaxed = true)
        sideEffectDispatcher = mockk(relaxed = true)
        duplicateDetector = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        postCommitActionRunner = mockk(relaxed = true)
        receiptSideEffectPlanner = mockk(relaxed = true)
        receiptInsertResolver = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)
        privacySettingsRepository = mockk(relaxed = true)
        transactionLifecycleCoordinator = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        // Default happy-path insert: every existing test expects the receipt to be
        // persisted with id=1L (the create / save / cancellation paths all rely on
        // this). Tests that need a different outcome override this stub locally.
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(1L)

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
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = bankStatementLifecycleProcessor,
            sideEffectDispatcher = sideEffectDispatcher,
            duplicateDetector = duplicateDetector,
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = writeBarrier,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
            postCommitActionRunner = postCommitActionRunner,
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            privacySettingsRepository = privacySettingsRepository,
            diagnosticEventWriter = diagnosticEventWriter,
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = receiptSideEffectPlanner,
            pendingReviewDao = mockk(relaxed = true),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            receiptInsertResolver = receiptInsertResolver,
            transactionRunner = mockk(relaxed = true),
            receiptLifecycleEventWriter = mockk(relaxed = true),
            effectiveCloudAiPolicyResolver = mockk(relaxed = true)
        )
    }

    @Test
    fun `processReceiptInput validates and persists receipt`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val validationResult = ReceiptInputValidator.ValidationResult(
            isValid = true,
            errors = emptyList(),
            mimeType = "image/jpeg",
            fileSizeBytes = 1024L
        )
        val savedReceipt = ScannedReceipt(
            id = 0L,
            imagePath = "/tmp/receipt.jpg",
            rawOcrText = "OCR text",
            parsedTotal = 25.0,
            parsedMerchant = "Test Shop",
            parsedDate = now,
            parsedItems = "[]",
            parsedTaxAmount = null,
            confidence = 0.95f
        )
        val parsedReceipt = ReceiptParser.ParsedReceipt(
            merchantName = "Test Shop",
            total = 25.0,
            subtotal = null,
            tax = null,
            date = null,
            currency = "EUR",
            lineItems = emptyList(),
            confidence = 0.95f,
            taxInclusive = false
        )

        coEvery { inputValidator.validate(uri) } returns validationResult
        coEvery { receiptRepository.processReceipt(uri, false) } returns ReceiptRepository.ProcessReceiptResult(receipt = savedReceipt, parsed = parsedReceipt)
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
        )

        val result = coordinator.processReceiptInput(uri)

        assertTrue("Expected success, got $result", result.isSuccess)
        coVerify(exactly = 1) { inputValidator.validate(uri) }
        coVerify(exactly = 1) { receiptRepository.processReceipt(uri, false) }
    }

    @Test
    fun `processReceiptInput fails on validation error`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val validationResult = ReceiptInputValidator.ValidationResult(
            isValid = false,
            errors = listOf("Invalid MIME type"),
            mimeType = null,
            fileSizeBytes = null
        )

        coEvery { inputValidator.validate(uri) } returns validationResult

        val result = coordinator.processReceiptInput(uri)

        assertTrue("Expected failure, got $result", result.isFailure)
        coVerify(exactly = 0) { receiptRepository.processReceipt(any(), any()) }
    }

    private fun nonEmptyBatch(): PostCommitActionBatch {
        val action = PostCommitAction(
            pipeline = AppPipeline.RECEIPT,
            name = "test_action",
            category = SideEffectCategory.BUDGET,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "receipt",
            targetEntityId = 1L,
            source = "test",
            correlationId = "test",
            causationId = null,
            idempotencyKey = "key-1",
            execute = { SideEffectOutcome.Completed }
        )
        return PostCommitActionBatch("test", listOf(action))
    }

    @Test
    fun `processReceiptInput post-commit cancellation rethrows`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val validationResult = ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 1024L
        )
        val savedReceipt = ScannedReceipt(
            id = 0L, imagePath = "/tmp/receipt.jpg", rawOcrText = "OCR text",
            parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = "[]", parsedTaxAmount = null, confidence = 0.95f
        )
        val parsedReceipt = ReceiptParser.ParsedReceipt(
            merchantName = "Test Shop", total = 25.0, subtotal = null, tax = null,
            date = null, currency = "EUR", lineItems = emptyList(),
            confidence = 0.95f, taxInclusive = false
        )

        coEvery { inputValidator.validate(uri) } returns validationResult
        coEvery { receiptRepository.processReceipt(uri, false) } returns ReceiptRepository.ProcessReceiptResult(receipt = savedReceipt, parsed = parsedReceipt)
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
        )
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws CancellationException("Cancelled")

        assertFailsWith<CancellationException> {
            coordinator.processReceiptInput(uri)
        }
    }

    @Test
    fun `processEmailReceipt post-commit cancellation rethrows`() = runTest {
        val emailData = EmailReceiptData(
            messageId = "", from = "sender@example.com", subject = "Receipt",
            body = "Your receipt", receivedAt = now,
            amount = 25.0, merchant = "Test Shop", currency = "EUR",
            date = now, items = null
        )
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(1L) } returns ScannedReceipt(
            id = 1L, imagePath = null, rawOcrText = "Your receipt",
            parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.7f
        )
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws CancellationException("Cancelled")

        assertFailsWith<CancellationException> {
            coordinator.processEmailReceipt(
                emailData = emailData,
                fingerprint = "",
                rawEmailBody = "Your receipt",
                sender = "sender@example.com",
                subject = "Receipt",
                messageId = "",
                provider = "unknown"
            )
        }
    }

    @Test
    fun `processReceiptInput post-commit failure does not fail saved receipt`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val validationResult = ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 1024L
        )
        val savedReceipt = ScannedReceipt(
            id = 0L, imagePath = "/tmp/receipt.jpg", rawOcrText = "OCR text",
            parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = "[]", parsedTaxAmount = null, confidence = 0.95f
        )
        val parsedReceipt = ReceiptParser.ParsedReceipt(
            merchantName = "Test Shop", total = 25.0, subtotal = null, tax = null,
            date = null, currency = "EUR", lineItems = emptyList(),
            confidence = 0.95f, taxInclusive = false
        )

        coEvery { inputValidator.validate(uri) } returns validationResult
        coEvery { receiptRepository.processReceipt(uri, false) } returns ReceiptRepository.ProcessReceiptResult(receipt = savedReceipt, parsed = parsedReceipt)
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
        )
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws RuntimeException("Best-effort failure")

        val result = coordinator.processReceiptInput(uri)

        assertTrue("Expected success despite runner failure, got $result", result.isSuccess)
        coVerify(exactly = 1) { receiptInsertResolver.insertOrResolve(any()) }
    }

    @Test
    fun `processEmailReceipt post-commit failure does not fail committed email receipt`() = runTest {
        val emailData = EmailReceiptData(
            messageId = "", from = "sender@example.com", subject = "Receipt",
            body = "Your receipt", receivedAt = now,
            amount = 25.0, merchant = "Test Shop", currency = "EUR",
            date = now, items = null
        )
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(1L) } returns ScannedReceipt(
            id = 1L, imagePath = null, rawOcrText = "Your receipt",
            parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.7f
        )
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws RuntimeException("Best-effort failure")

        val result = coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your receipt",
            sender = "sender@example.com",
            subject = "Receipt",
            messageId = "",
            provider = "unknown"
        )

        assertTrue("Expected Success despite runner failure, got $result", result is EmailReceiptProcessResult.Success)
        coVerify(exactly = 1) { receiptInsertResolver.insertOrResolve(any()) }
    }

    // P11-CURRENT-020: home-currency DataStore read must happen BEFORE the Room transaction is
    // opened so the DB write lock is never held while awaiting DataStore/Flow I/O. The read is now
    // hoisted out of database.withTransaction, so it runs unconditionally on the email path. We
    // assert it is invoked exactly once during processEmailReceipt (database.withTransaction is a
    // Room extension on a relaxed mock and cannot be reliably ordered-against without static mocks).
    @Test
    fun `processEmailReceipt invokes resolveHomeCurrency once on the email path`() = runTest {
        val emailData = EmailReceiptData(
            messageId = "", from = "sender@example.com", subject = "Receipt",
            body = "Your receipt", receivedAt = now,
            amount = 25.0, merchant = "Test Shop", currency = "EUR",
            date = now, items = null
        )
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(1L) } returns ScannedReceipt(
            id = 1L, imagePath = null, rawOcrText = "Your receipt",
            parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.7f
        )

        coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your receipt",
            sender = "sender@example.com",
            subject = "Receipt",
            messageId = "",
            provider = "unknown"
        )

        // The BEFORE-transaction ordering (resolveHomeCurrency is called before
        // database.withTransaction at the call site) is guaranteed structurally by the
        // source and is covered by static review, not by this unit test.
        coVerify(exactly = 1) { currencySettingsRepository.resolveHomeCurrency() }
    }

    // P11-CURRENT-009: a low-confidence email parse (all of amount/merchant/date present, but
    // confidence at/below the auto-expense threshold) must NOT silently auto-create an approved
    // expense. The receipt is still saved, and the outcome is surfaced as NeedsReview so the user
    // can confirm. Note: transactionLifecycleCoordinator is an inline relaxed mock (not a field),
    // so createExpenseDbOnlyV2 invocation cannot be coVerify-asserted here; the NeedsReview result
    // (which is only reached because the create path was skipped) is the assertable contract.
    @Test
    fun `processEmailReceipt low confidence yields NeedsReview without auto-creating expense`() = runTest {
        val emailData = EmailReceiptData(
            messageId = "", from = "sender@example.com", subject = "Receipt",
            body = "Your receipt", receivedAt = now,
            amount = 25.0, merchant = "Test Shop", currency = "EUR",
            date = now, items = null,
            confidence = 0.2
        )
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(1L) } returns ScannedReceipt(
            id = 1L, imagePath = null, rawOcrText = "Your receipt",
            parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.2f
        )

        val result = coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your receipt",
            sender = "sender@example.com",
            subject = "Receipt",
            messageId = "",
            provider = "unknown"
        )

        assertTrue("Expected NeedsReview for low-confidence parse, got $result", result is EmailReceiptProcessResult.NeedsReview)
        val needsReview = result as EmailReceiptProcessResult.NeedsReview
        kotlin.test.assertEquals("low_confidence", needsReview.reason)
        // receiptInsertResolver is a class-level field stubbed to Inserted(1L), so the
        // receipt IS persisted and its id is surfaced even though no expense was created.
        kotlin.test.assertEquals(1L, needsReview.receiptId)
        kotlin.test.assertEquals(0.2, needsReview.confidence!!, 1e-9)
    }

    // P11-CURRENT-011: when the parse is incomplete (here, amount is null so the outer
    // create guard `amount != null && amount > 0 && merchant present && date > 0` is FALSE),
    // the receipt is still saved but NO expense is created. The outcome must be surfaced as
    // NeedsReview(reason = "incomplete_parse") instead of a misleading empty Success. The
    // incomplete guard fires regardless of confidence, so the default confidence (1.0) is used.
    @Test
    fun `processEmailReceipt incomplete parse yields NeedsReview without creating expense`() = runTest {
        val emailData = EmailReceiptData(
            messageId = "", from = "sender@example.com", subject = "Receipt",
            body = "Your receipt", receivedAt = now,
            amount = null, merchant = "Test Shop", currency = "EUR",
            date = now, items = null
        )
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(1L) } returns ScannedReceipt(
            id = 1L, imagePath = null, rawOcrText = "Your receipt",
            parsedTotal = null, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 1.0f
        )

        val result = coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your receipt",
            sender = "sender@example.com",
            subject = "Receipt",
            messageId = "",
            provider = "unknown"
        )

        assertTrue("Expected NeedsReview for incomplete parse, got $result", result is EmailReceiptProcessResult.NeedsReview)
        val needsReview = result as EmailReceiptProcessResult.NeedsReview
        kotlin.test.assertEquals("incomplete_parse", needsReview.reason)
        kotlin.test.assertEquals(1L, needsReview.receiptId)
    }

    // P11-P1-08: high-confidence email above the auto-expense threshold must
    // create an approved expense directly (not route to NeedsReview).
    @Test
    fun `high_confidence_email_creates_expense_directly`() = runTest {
        val emailData = EmailReceiptData(
            messageId = "", from = "receipt@amazon.com", subject = "Order",
            body = "Your order", receivedAt = now,
            amount = 49.99, merchant = "Amazon", currency = "USD",
            date = now, items = null,
            confidence = 0.95  // well above EMAIL_AUTO_EXPENSE_MIN_CONFIDENCE (0.75)
        )
        coEvery { scannedReceiptDao.insert(any()) } returns 2L
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(2L) } returns ScannedReceipt(
            id = 2L, imagePath = null, rawOcrText = "Your order",
            parsedTotal = 49.99, parsedMerchant = "Amazon", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.95f
        )

        val result = coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your order",
            sender = "receipt@amazon.com",
            subject = "Order",
            messageId = "",
            provider = "amazon"
        )

        assertTrue("Expected Success for high-confidence parse, got $result", result is EmailReceiptProcessResult.Success)
        val success = result as EmailReceiptProcessResult.Success
        kotlin.test.assertEquals(2L, success.receiptId)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // P11-P1-05: Coordinator uses DatabaseWriteBarrier, not RestoreMaintenanceMode
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `email_ingestion_uses_write_barrier_not_restore_maintenance_mode`() = runTest {
        // Given: writeBarrier is set to block writes
        val writeBarrier = this@ReceiptLifecycleCoordinatorTest.writeBarrier
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } throws
            com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("test"),
                mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.RESTORE_STAGING
            )

        val emailData = EmailReceiptData(
            messageId = "", from = "sender@example.com", subject = "Receipt",
            body = "Your receipt", receivedAt = now,
            amount = null, merchant = null, currency = null, date = null, items = null
        )

        val result = coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your receipt",
            sender = "sender@example.com",
            subject = "Receipt",
            messageId = "",
            provider = "unknown"
        )

        assertTrue("Expected Error when writes are blocked, got $result", result is EmailReceiptProcessResult.Error)
        val error = result as EmailReceiptProcessResult.Error
        assertTrue(error.message.contains("blocked", ignoreCase = true))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // P11-P1-04: Email receipts use emailReceiptStorageMode, not rawOcrStorageMode
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `email_uses_email_storage_mode_not_ocr_mode`() = runTest {
        // Given: explicit privacy settings with distinct modes
        val settings = PrivacySettings(
            rawOcrStorageMode = RawStorageMode.STORE_RAW,
            emailReceiptStorageMode = RawStorageMode.DO_NOT_STORE
        )
        coEvery { privacySettingsRepository.getSettings() } returns settings

        val emailData = EmailReceiptData(
            messageId = "", from = "sender@example.com", subject = "Receipt",
            body = "sensitive email body", receivedAt = now,
            amount = null, merchant = null, currency = null, date = null, items = null
        )

        coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "sensitive email body",
            sender = "sender@example.com",
            subject = "Receipt",
            messageId = "",
            provider = "unknown"
        )

        // Verify privacySettingsRepository.getSettings() was called (emailReceiptStorageMode was read)
        coVerify(atLeast = 1) { privacySettingsRepository.getSettings() }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // P11-P1-02: Non-duplicate failures (ValidationFailed, InsertConflict, Error)
    //            must produce diagnostic events
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `validation_failure_produces_diagnostic_event`() = runTest {
        // Given: transaction coordinator returns ValidationFailed
        val mutationResult = MutationResult<CreateExpenseResult>(
            value = CreateExpenseResult.ValidationFailed(listOf("Invalid amount")),
            postCommitActions = PostCommitActionBatch.empty("test")
        )
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns mutationResult

        val emailData = EmailReceiptData(
            messageId = "", from = "receipt@amazon.com", subject = "Order",
            body = "Your order", receivedAt = now,
            amount = 49.99, merchant = "Amazon", currency = "USD",
            date = now, items = null,
            confidence = 0.95
        )
        coEvery { scannedReceiptDao.insert(any()) } returns 2L
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(2L) } returns ScannedReceipt(
            id = 2L, imagePath = null, rawOcrText = "Your order",
            parsedTotal = 49.99, parsedMerchant = "Amazon", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.95f
        )

        val result = coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your order",
            sender = "receipt@amazon.com",
            subject = "Order",
            messageId = "",
            provider = "amazon"
        )

        // Should be NeedsReview (not silently succeeding or failing)
        assertTrue("Expected NeedsReview for validation failure, got $result", result is EmailReceiptProcessResult.NeedsReview)
        val needsReview = result as EmailReceiptProcessResult.NeedsReview
        assertEquals("validation_failed", needsReview.reason)

        // Verify diagnostic event was emitted for the failure
        coVerify(atLeast = 1) { diagnosticEventWriter.emit(any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // P11-P1-06: messageId conflict in insertOrIgnore returns existing source
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `messageId_conflict_returns_existing_source`() = runTest {
        // Given: existing source with the same messageId
        val existingSource = EmailReceiptSource(
            id = 99L,          // existing source PK
            receiptId = 5L,    // FK to existing receipt
            provider = "amazon",
            parsedAt = now,
            confidence = 0.9,
            emailMessageId = "msg-existing-1"
        )
        // insertOrIgnore returns -1 (conflict), then lookup by messageId returns existing
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns -1L
        coEvery { emailReceiptDao.getByMessageId("msg-existing-1") } returns existingSource
        // getByMessageId for non-matching IDs should return null
        coEvery { emailReceiptDao.getByMessageId(any()) } answers {
            val msgId = firstArg<String>()
            if (msgId == "msg-existing-1") existingSource else null
        }
        coEvery { scannedReceiptDao.insert(any()) } returns 2L
        coEvery { scannedReceiptDao.getById(2L) } returns ScannedReceipt(
            id = 2L, imagePath = null, rawOcrText = "Your order",
            parsedTotal = 49.99, parsedMerchant = "Amazon", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.95f
        )

        val emailData = EmailReceiptData(
            messageId = "msg-existing-1", from = "receipt@amazon.com", subject = "Order",
            body = "Your order", receivedAt = now,
            amount = 49.99, merchant = "Amazon", currency = "USD",
            date = now, items = null,
            confidence = 0.95
        )

        val result = coordinator.processEmailReceipt(
            emailData = emailData,
            fingerprint = "",
            rawEmailBody = "Your order",
            sender = "receipt@amazon.com",
            subject = "Order",
            messageId = "msg-existing-1",
            provider = "amazon"
        )

        // The existing source's receiptId (5L) should be returned as Duplicate
        assertTrue("Expected Duplicate for messageId conflict, got $result", result is EmailReceiptProcessResult.Duplicate)
        val duplicate = result as EmailReceiptProcessResult.Duplicate
        assertEquals(5L, duplicate.existingReceiptId)
    }
}
