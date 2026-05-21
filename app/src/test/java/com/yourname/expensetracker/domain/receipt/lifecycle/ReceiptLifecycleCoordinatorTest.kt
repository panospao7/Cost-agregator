package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.lifecycle.EmailReceiptProcessResult
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlanner
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import kotlinx.coroutines.CancellationException
import kotlin.test.assertFailsWith
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
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var postCommitActionRunner: PostCommitActionRunner
    private lateinit var receiptSideEffectPlanner: ReceiptSideEffectPlanner
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
        restoreMaintenanceMode = mockk(relaxed = true)
        postCommitActionRunner = mockk(relaxed = true)
        receiptSideEffectPlanner = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

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
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = mockk(relaxed = true),
            transactionLifecycleCoordinator = mockk(relaxed = true),
            postCommitActionRunner = postCommitActionRunner,
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            privacySettingsRepository = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = receiptSideEffectPlanner
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
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any(), any(), any(), any()) } returns nonEmptyBatch()
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
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any(), any(), any(), any()) } returns nonEmptyBatch()
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
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any(), any(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws RuntimeException("Best-effort failure")

        val result = coordinator.processReceiptInput(uri)

        assertTrue("Expected success despite runner failure, got $result", result.isSuccess)
        coVerify(exactly = 1) { scannedReceiptDao.insert(any()) }
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
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any(), any(), any(), any()) } returns nonEmptyBatch()
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
        coVerify(exactly = 1) { scannedReceiptDao.insert(any()) }
    }
}
