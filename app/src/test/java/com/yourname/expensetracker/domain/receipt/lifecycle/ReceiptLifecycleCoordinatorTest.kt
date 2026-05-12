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
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            privacySettingsRepository = mockk(relaxed = true),
            diagnosticEventDao = mockk(relaxed = true)
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
}
