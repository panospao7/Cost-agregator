package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicyResolver
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * P-03-4 review fix: a generic (non-cancellation, non-duplicate) failure while
 * processing a camera receipt leaves this attempt's asset uncommitted, so the
 * coordinator must request exactly one best-effort cleanup with reason
 * `INSERT_FAILED` while the result stays `Result.failure(sameException)`.
 *
 * The [AssetCleanupCoordinator] is a mock here so the reason code and call count
 * are observable; the sibling `ReceiptLifecycleCoordinatorTest` uses the real one.
 */
class ReceiptLifecycleCoordinatorGenericFailureCleanupTest {

    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var inputValidator: ReceiptInputValidator
    private lateinit var duplicateDetector: ReceiptDuplicateDetector
    private lateinit var receiptInsertResolver: ReceiptInsertResolver
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var transactionRunner: DomainTransactionRunner
    private lateinit var assetCleanupCoordinator: AssetCleanupCoordinator
    private lateinit var coordinator: ReceiptLifecycleCoordinator

    private val now = 1_712_000_000_000L
    private val assetPath = "/tmp/receipt.jpg"

    @Before
    fun setup() {
        receiptRepository = mockk(relaxed = true)
        inputValidator = mockk(relaxed = true)
        duplicateDetector = mockk(relaxed = true)
        receiptInsertResolver = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        transactionRunner = mockk(relaxed = true)
        assetCleanupCoordinator = mockk(relaxed = true)

        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { privacySettingsRepository.getLoadState() } returns
            PrivacySettingsLoadState.Loaded(PrivacySettings())

        val ctx = TransactionContext(
            correlationId = "test-correlation",
            operationId = "test",
            source = "ReceiptLifecycleCoordinator",
            occurredAt = now
        )
        coEvery { transactionRunner.runInTransaction<Any>(any(), any(), any(), any(), any(), any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (arg<suspend (TransactionContext) -> Any>(5))(ctx)
        }
        coEvery {
            writeBarrier.runWrite(any<DatabaseAccessOperation>(), any<suspend () -> Any?>())
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(1L)

        coordinator = ReceiptLifecycleCoordinator(
            database = mockk(relaxed = true),
            receiptRepository = receiptRepository,
            receiptLinkService = mockk(relaxed = true),
            assetStore = mockk(relaxed = true),
            inputValidator = inputValidator,
            scannedReceiptDao = mockk(relaxed = true),
            receiptExpenseLinkDao = mockk(relaxed = true),
            receiptEventDao = mockk(relaxed = true),
            emailReceiptDao = mockk(relaxed = true),
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = duplicateDetector,
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = writeBarrier,
            transactionLifecycleCoordinator = mockk(relaxed = true),
            postCommitActionRunner = mockk(relaxed = true),
            assetCleanupCoordinator = assetCleanupCoordinator,
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = RawPersistencePolicyResolver(privacySettingsRepository),
            diagnosticEventWriter = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            receiptInsertResolver = receiptInsertResolver,
            receiptParser = mockk(relaxed = true),
            transactionRunner = transactionRunner,
            receiptLifecycleEventWriter = mockk(relaxed = true),
        )
    }

    private fun stubNonDuplicateScan(uri: Uri) {
        coEvery { inputValidator.validate(uri) } returns ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 1024L
        )
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns
            ReceiptRepository.ProcessReceiptResult(
                receipt = ScannedReceipt(
                    id = 0L, imagePath = assetPath, rawOcrText = "OCR text",
                    parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
                    parsedItems = "[]", parsedTaxAmount = null, confidence = 0.95f
                ),
                parsed = ReceiptParser.ParsedReceipt(
                    merchantName = "Test Shop",
                    total = ReceiptParser.sourceMoneyOrNull(25.0, "EUR"),
                    subtotal = null, tax = null, date = null, currency = "EUR",
                    lineItems = emptyList(), confidence = 0.95f, taxInclusive = false
                )
            )
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns
            ReceiptDuplicateDetector.DuplicateResult(
                isDuplicate = false, confidence = 0.0f, existingReceiptId = null,
                reason = null, matchType = "NONE"
            )
    }

    @Test
    fun `generic failure inside save transaction cleans up the uncommitted asset once with INSERT_FAILED`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)
        val failure = RuntimeException("synthetic failure")
        coEvery { receiptInsertResolver.insertOrResolve(any()) } throws failure

        val result = coordinator.processReceiptInput(uri)

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
        coVerify(exactly = 1) {
            assetCleanupCoordinator.cleanupUncommittedAsset(
                path = assetPath, attemptId = any(), reason = "INSERT_FAILED"
            )
        }
        coVerify(exactly = 1) { assetCleanupCoordinator.cleanupUncommittedAsset(any(), any(), any()) }
    }

    @Test
    fun `generic failure thrown by the transaction runner cleans up the uncommitted asset once with INSERT_FAILED`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)
        val failure = IllegalStateException("synthetic runner failure")
        coEvery {
            transactionRunner.runInTransaction<Any>(any(), any(), any(), any(), any(), any())
        } throws failure

        val result = coordinator.processReceiptInput(uri)

        assertTrue(result.isFailure)
        assertSame(failure, result.exceptionOrNull())
        coVerify(exactly = 1) {
            assetCleanupCoordinator.cleanupUncommittedAsset(
                path = assetPath, attemptId = any(), reason = "INSERT_FAILED"
            )
        }
        coVerify(exactly = 1) { assetCleanupCoordinator.cleanupUncommittedAsset(any(), any(), any()) }
    }

    @Test
    fun `generic failure before any asset path is known never requests cleanup of a real path`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val failure = RuntimeException("synthetic repository failure")
        coEvery { inputValidator.validate(uri) } returns ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 1024L
        )
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } throws failure

        val result = coordinator.processReceiptInput(uri)

        assertSame(failure, result.exceptionOrNull())
        coVerify(exactly = 0) {
            assetCleanupCoordinator.cleanupUncommittedAsset(
                path = match { !it.isNullOrBlank() }, attemptId = any(), reason = any()
            )
        }
    }

    @Test
    fun `successful save never requests asset cleanup`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)

        val result = coordinator.processReceiptInput(uri)

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { assetCleanupCoordinator.cleanupUncommittedAsset(any(), any(), any()) }
    }

    @Test
    fun `cancellation keeps CALLER_CANCELLED reason and propagates the same exception`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)
        val cancellation = CancellationException("synthetic cancel")
        coEvery { receiptInsertResolver.insertOrResolve(any()) } throws cancellation

        val thrown = assertFailsWith<CancellationException> { coordinator.processReceiptInput(uri) }

        assertSame(cancellation, thrown)
        coVerify(exactly = 1) {
            assetCleanupCoordinator.cleanupUncommittedAsset(
                path = assetPath, attemptId = any(), reason = "CALLER_CANCELLED"
            )
        }
        coVerify(exactly = 0) {
            assetCleanupCoordinator.cleanupUncommittedAsset(any(), any(), "INSERT_FAILED")
        }
    }

    @Test
    fun `insert race duplicate keeps INSERT_ROLLBACK_DUPLICATE reason and returns success`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)
        val existing = ScannedReceipt(
            id = 9L, imagePath = "/tmp/existing.jpg", rawOcrText = "OCR text",
            parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
            parsedItems = "[]", parsedTaxAmount = null, confidence = 0.95f
        )
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns
            ReceiptInsertResult.Duplicate(existing, "insert_ignored")

        val result = coordinator.processReceiptInput(uri)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().inserted)
        coVerify(exactly = 1) {
            assetCleanupCoordinator.cleanupUncommittedAsset(
                path = assetPath, attemptId = any(), reason = "INSERT_ROLLBACK_DUPLICATE"
            )
        }
        coVerify(exactly = 0) {
            assetCleanupCoordinator.cleanupUncommittedAsset(any(), any(), "INSERT_FAILED")
        }
    }
}
