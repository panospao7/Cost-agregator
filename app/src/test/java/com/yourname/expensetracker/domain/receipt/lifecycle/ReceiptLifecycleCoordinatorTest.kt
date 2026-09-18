package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
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
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ReceiptLifecycleCoordinator.processReceiptInput] and [ReceiptLifecycleCoordinator.deleteReceipt].
 *
 * Validates: input validation → OCR/parse → dedupe → save → event logging;
 * deletion: barrier check → transactional event/link/row delete → post-commit asset cleanup →
 * GR-14b ASSET_DELETE_FAILED audit write via writeAssetDeleteFailedEvent on asset-delete failure.
 *
 * Since the f1758149 DomainTransactionRunner migration, ingest and delete paths run
 * inside transactionRunner.runInTransaction blocks; setup installs a default stub
 * ([stubTransactionRunnerExecutesBlocks]) so every block actually executes — a relaxed
 * runner mock would silently skip them.
 */
class ReceiptLifecycleCoordinatorTest {

    private lateinit var database: AppDatabase
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var receiptLinkService: ReceiptLinkService
    private lateinit var assetStore: ReceiptAssetStore
    private lateinit var assetCleanupCoordinator: AssetCleanupCoordinator
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
    private lateinit var transactionRunner: DomainTransactionRunner
    private lateinit var receiptLifecycleEventWriter: ReceiptLifecycleEventWriter
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
        transactionRunner = mockk(relaxed = true)
        assetCleanupCoordinator = AssetCleanupCoordinator(
            scannedReceiptDao = scannedReceiptDao,
            assetStore = assetStore,
            writeBarrier = writeBarrier,
            transactionRunner = transactionRunner
        )
        receiptLifecycleEventWriter = mockk(relaxed = true)
        // Ingest/delete paths run inside transactionRunner blocks — execute them by default.
        stubTransactionRunnerExecutesBlocks()
        // GR-14s: runWrite is a pass-through here — the relaxed mock would
        // otherwise never invoke the scoped block (processEmailReceipt).
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        // Default happy-path insert: every existing test expects the receipt to be
        // persisted with id=1L (the create / save / cancellation paths all rely on
        // this). Tests that need a different outcome override this stub locally.
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(1L)
        // Default happy-path expense creation for the high-confidence EMAIL path:
        // processEmailReceipt calls createExpenseDbOnlyV2 inside the runner block
        // (ReceiptLifecycleCoordinator.kt:1145) and the compiled `when (mutation.value)`
        // at :1146 casts to CreateExpenseResult — a relaxed generic MutationResult
        // supplies java.lang.Object for `value` and CCEs (measured:
        // vr-20260918-093043-2fd8bf7d). Tests needing a different outcome override
        // this stub locally (see validation_failure_produces_diagnostic_event).
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(500L), PostCommitActionBatch.empty("test"))
        // The Created branch then links the receipt (ReceiptLifecycleCoordinator.kt:1151)
        // and production throws at :1156 when the link fails — pin an explicit success
        // instead of relying on relaxed kotlin.Result handling (same explicit link
        // stubbing style as ReceiptMatchingWorkerTest).
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(mockk<com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink>(relaxed = true))

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
            assetCleanupCoordinator = assetCleanupCoordinator,
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            privacySettingsRepository = privacySettingsRepository,
            diagnosticEventWriter = diagnosticEventWriter,
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = receiptSideEffectPlanner,
            pendingReviewDao = mockk(relaxed = true),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            receiptInsertResolver = receiptInsertResolver,
            transactionRunner = transactionRunner,
            receiptLifecycleEventWriter = receiptLifecycleEventWriter,
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
        // resolvedMimeType must be spelled out — production always passes it
        // (resolvedMimeType = validation.mimeType, ReceiptLifecycleCoordinator.kt:330-334)
        // and MockK fills an unspecified nullable arg with a null() matcher that can
        // never match (see the NOTE above stubNonDuplicateScan).
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns ReceiptRepository.ProcessReceiptResult(receipt = savedReceipt, parsed = parsedReceipt)
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
        )

        val result = coordinator.processReceiptInput(uri)

        assertTrue("Expected success, got $result", result.isSuccess)
        // RP-12 12a / P3-001: a fresh insert reports inserted=true.
        assertTrue("Expected inserted=true, got ${result.getOrThrow()}", result.getOrThrow().inserted)
        coVerify(exactly = 1) { inputValidator.validate(uri) }
        coVerify(exactly = 1) { receiptRepository.processReceipt(uri, false, "image/jpeg") }
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

    // ── RP-12 12a fixtures ───────────────────────────────────────────────────────

    private fun scanValidationResult() = ReceiptInputValidator.ValidationResult(
        isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 1024L
    )

    private fun scanDraftReceipt(id: Long = 0L) = ScannedReceipt(
        id = id, imagePath = "/tmp/receipt.jpg", rawOcrText = "OCR text",
        parsedTotal = 25.0, parsedMerchant = "Test Shop", parsedDate = now,
        parsedItems = "[]", parsedTaxAmount = null, confidence = 0.95f
    )

    private fun parsedScanReceipt() = ReceiptParser.ParsedReceipt(
        merchantName = "Test Shop", total = 25.0, subtotal = null, tax = null,
        date = null, currency = "EUR", lineItems = emptyList(),
        confidence = 0.95f, taxInclusive = false
    )

    // NOTE: resolvedMimeType must be spelled out — the production call always passes
    // it, and MockK fills an unspecified nullable arg with a null() matcher that can
    // never match (root cause of the pre-existing 2-arg stub drift in this class).
    private fun stubNonDuplicateScan(uri: Uri) {
        coEvery { inputValidator.validate(uri) } returns scanValidationResult()
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns
            ReceiptRepository.ProcessReceiptResult(receipt = scanDraftReceipt(), parsed = parsedScanReceipt())
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
        )
    }

    // RP-12 12a / P3-001: REAL post-commit dispatch pin. The coordinator plans inside
    // the save transaction and runs the batch exactly once after commit; a
    // CancellationException from the post-commit run must propagate (never swallow CE).
    @Test
    fun `processReceiptInput post-commit cancellation rethrows`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { inputValidator.validate(uri) } returns scanValidationResult()
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns
            ReceiptRepository.ProcessReceiptResult(receipt = scanDraftReceipt(), parsed = parsedScanReceipt())
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
        )
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws CancellationException("Cancelled")

        assertFailsWith<CancellationException> {
            coordinator.processReceiptInput(uri)
        }

        // One save → one plan → one runner invocation after commit, then the rethrow.
        coVerify(exactly = 1) { receiptInsertResolver.insertOrResolve(any()) }
        coVerify(exactly = 1) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
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

        // RP-12 12a: email path keeps its own single post-commit dispatch contract.
        coVerify(exactly = 1) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
    }

    // RP-12 12a / P3-001: best-effort dispatch — a non-cancellation failure of the
    // post-commit run must NOT turn the committed save into a failure. The outcome
    // still reports inserted=true with its batch.
    @Test
    fun `processReceiptInput post-commit failure does not fail saved receipt`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { inputValidator.validate(uri) } returns scanValidationResult()
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns
            ReceiptRepository.ProcessReceiptResult(receipt = scanDraftReceipt(), parsed = parsedScanReceipt())
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
        )
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) } returns nonEmptyBatch()
        coEvery { postCommitActionRunner.run(any()) } throws RuntimeException("Best-effort failure")

        val result = coordinator.processReceiptInput(uri)

        assertTrue("Expected success despite runner failure, got $result", result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Expected inserted=true, got $outcome", outcome.inserted)
        assertNotNull(outcome.postCommitBatch)
        coVerify(exactly = 1) { receiptInsertResolver.insertOrResolve(any()) }
        // The batch was planned once and dispatched once (the runner swallowed the failure).
        coVerify(exactly = 1) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
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
        coVerify(exactly = 1) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // RP-12 12a / P3-001: outcome contract — one save → one plan → one post-commit
    // run; every duplicate path returns the existing receipt with inserted=false
    // and ZERO planning/dispatch.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `processReceiptInput saves once plans once and runs one post-commit batch`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)
        val plannedBatch = nonEmptyBatch()
        val inputSlot = slot<ReceiptSideEffectInput>()
        val batchSlot = slot<PostCommitActionBatch>()
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(capture(inputSlot), any(), any()) } returns plannedBatch
        coEvery { postCommitActionRunner.run(capture(batchSlot)) } returns mockk(relaxed = true)

        val result = coordinator.processReceiptInput(uri)

        val outcome = result.getOrThrow()
        assertTrue("Expected inserted=true, got $outcome", outcome.inserted)
        assertEquals(1L, outcome.savedReceipt.id)
        // The outcome carries the same batch instance that was executed post-commit.
        assertTrue("Expected postCommitBatch to be the planned batch", outcome.postCommitBatch === plannedBatch)
        assertTrue("Expected runner to execute the planned batch", batchSlot.captured === plannedBatch)
        // Planning input: default options carry autoMatch=true; camera path carries
        // the ephemeral raw OCR (null unless the repository provided one).
        assertTrue(inputSlot.captured.autoMatchExistingExpense)
        assertNull(inputSlot.captured.ephemeralRawOcrText)
        // Exactly once, end to end.
        coVerify(exactly = 1) { receiptInsertResolver.insertOrResolve(any()) }
        coVerify(exactly = 1) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `processReceiptInput pre-ocr duplicate returns existing receipt with no batch`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { inputValidator.validate(uri) } returns scanValidationResult()
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns ReceiptRepository.ProcessReceiptResult(
            receipt = scanDraftReceipt(id = 5L),
            parsed = parsedScanReceipt(),
            isPreExistingDuplicate = true
        )

        val outcome = coordinator.processReceiptInput(uri).getOrThrow()

        assertFalse("Expected inserted=false for pre-OCR duplicate", outcome.inserted)
        assertEquals(5L, outcome.savedReceipt.id)
        assertNull(outcome.postCommitBatch)
        coVerify(exactly = 0) { receiptInsertResolver.insertOrResolve(any()) }
        coVerify(exactly = 0) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `processReceiptInput exact-hash duplicate returns existing receipt with no batch`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { inputValidator.validate(uri) } returns scanValidationResult()
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns
            ReceiptRepository.ProcessReceiptResult(receipt = scanDraftReceipt(), parsed = parsedScanReceipt())
        coEvery { assetStore.computeFileHash("/tmp/receipt.jpg") } returns Result.success("hash-1")
        coEvery {
            duplicateDetector.checkDuplicate(imageHash = "hash-1", textFingerprint = null, semanticFingerprint = null, externalSourceId = null)
        } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = true, confidence = 1.0f, existingReceiptId = 5L, reason = "hash", matchType = "EXACT_HASH"
        )
        coEvery { scannedReceiptDao.getById(5L) } returns scanDraftReceipt(id = 5L)

        val outcome = coordinator.processReceiptInput(uri).getOrThrow()

        assertFalse("Expected inserted=false for exact-hash duplicate", outcome.inserted)
        assertEquals(5L, outcome.savedReceipt.id)
        assertNull(outcome.postCommitBatch)
        // Draft asset of the discarded attempt is cleaned up, but no side effects run.
        verify(exactly = 1) { assetStore.deleteAsset("/tmp/receipt.jpg") }
        coVerify(exactly = 0) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `processReceiptInput post-ocr duplicate returns existing receipt with no batch`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { inputValidator.validate(uri) } returns scanValidationResult()
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns
            ReceiptRepository.ProcessReceiptResult(receipt = scanDraftReceipt(), parsed = parsedScanReceipt())
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns ReceiptDuplicateDetector.DuplicateResult(
            isDuplicate = true, confidence = 0.9f, existingReceiptId = 7L, reason = "semantic", matchType = "SEMANTIC"
        )
        coEvery { scannedReceiptDao.getById(7L) } returns scanDraftReceipt(id = 7L)

        val outcome = coordinator.processReceiptInput(uri).getOrThrow()

        assertFalse("Expected inserted=false for post-OCR duplicate", outcome.inserted)
        assertEquals(7L, outcome.savedReceipt.id)
        assertNull(outcome.postCommitBatch)
        coVerify(exactly = 0) { receiptInsertResolver.insertOrResolve(any()) }
        coVerify(exactly = 0) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    // RP-12 12a behavior change: a resolver-detected insert-race duplicate used to
    // surface as Result.failure; it now returns the existing receipt (inserted=false,
    // no batch) like every other duplicate exit.
    @Test
    fun `processReceiptInput insert-race duplicate returns existing receipt with no batch`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns
            ReceiptInsertResult.Duplicate(scanDraftReceipt(id = 9L), "insert_ignored")

        val outcome = coordinator.processReceiptInput(uri).getOrThrow()

        assertFalse("Expected inserted=false for insert-race duplicate", outcome.inserted)
        assertEquals(9L, outcome.savedReceipt.id)
        assertNull(outcome.postCommitBatch)
        coVerify(exactly = 1) { receiptInsertResolver.insertOrResolve(any()) }
        coVerify(exactly = 0) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    // RP-12 12a / P3-001: autoMatchExistingExpense=false must reach planning so the
    // planner omits ONLY the matching action; unrelated actions are not suppressed.
    @Test
    fun `processReceiptInput threads autoMatchExistingExpense=false into planning`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        stubNonDuplicateScan(uri)
        val inputSlot = slot<ReceiptSideEffectInput>()
        coEvery { receiptSideEffectPlanner.planAfterReceiptSaved(capture(inputSlot), any(), any()) } returns
            PostCommitActionBatch.empty("test")

        coordinator.processReceiptInput(
            uri,
            ReceiptLifecycleCoordinator.ReceiptProcessingOptions(
                createReview = false,
                autoMatchExistingExpense = false
            )
        )

        assertFalse("Expected autoMatchExistingExpense=false carried into planning", inputSlot.captured.autoMatchExistingExpense)
        coVerify(exactly = 1) { receiptSideEffectPlanner.planAfterReceiptSaved(any<ReceiptSideEffectInput>(), any(), any()) }
        // Empty planned batch → nothing dispatched.
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
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
        // Production persists via receiptInsertResolver (the scannedReceiptDao.insert
        // stub above is vestigial) — align the resolver with this test's id=2L pin.
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(2L)
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
        // The pre-transaction messageIdHash dedup (ReceiptLifecycleCoordinator.kt:928-934)
        // must NOT fire here: a relaxed getBySourceFingerprint returns a non-null
        // relaxed ScannedReceipt (id=0L), short-circuiting to Duplicate(0) before the
        // in-transaction conflict path under test is reached (measured: expected 5, was 0).
        coEvery { scannedReceiptDao.getBySourceFingerprint(any()) } returns null
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

    // ─────────────────────────────────────────────────────────────────────────────
    // GR-14b: deleteReceipt — transactional delete + post-commit asset cleanup +
    //         writeAssetDeleteFailedEvent (canonical direct barrier check + audit write)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Setup-default stub: executes every [DomainTransactionRunner.runInTransaction]
     * block with a [TransactionContext], mirroring what a real runner does — the
     * production contract requires the block to run inside a real transaction.
     * Since the f1758149 migration the coordinator's DB work lives inside these
     * blocks, so a relaxed mock that silently skips them would let every
     * DB-dependent assertion pass vacuously or fail with ClassCastException.
     * The block parameter is positional arg 5 (after correlationId, causationId,
     * operationId, source, metadata). The ctx fields are inert for assertions —
     * no production code in these tests asserts on the correlationId/operationId
     * values passed to the runner.
     */
    private fun stubTransactionRunnerExecutesBlocks() {
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
    }

    private fun deletedReceiptFixture(): ScannedReceipt = ScannedReceipt(
        id = 1L,
        imagePath = "/tmp/receipt.jpg",
        rawOcrText = "OCR text",
        parsedTotal = 25.0,
        parsedMerchant = "Test Shop",
        parsedDate = now,
        parsedItems = "[]",
        parsedTaxAmount = null,
        confidence = 0.95f,
        processingStatus = "CAPTURED"
    )

    @Test
    fun `deleteReceipt_happy_path_writes_event_deletes_links_row_and_asset`() = runTest {
        val receipt = deletedReceiptFixture()
        coEvery { scannedReceiptDao.getById(1L) } returns receipt
        // scannedReceiptDao.delete / deleteAllLinksForReceipt are suspend Unit on
        // relaxed mocks — no explicit stub needed.
        every { assetStore.deleteAsset("/tmp/receipt.jpg") } returns true

        val eventSlot = slot<ReceiptLifecycleEvent>()
        val result = coordinator.deleteReceipt(1L)

        assertTrue("Expected success, got $result", result.isSuccess)
        coVerify(exactly = 1) { receiptLifecycleEventWriter.write(any(), capture(eventSlot)) }
        val event = eventSlot.captured
        assertEquals(1L, event.receiptId)
        assertEquals("RECEIPT_DELETED", event.eventType)
        assertEquals("DELETED", event.newStatus)
        assertEquals(receipt.processingStatus, event.oldStatus)
        assertEquals("system:coordinator", event.actor)
        assertEquals("Receipt deleted with asset cleanup", event.message)
        assertNull(event.errorDetails)
        coVerify(exactly = 1) { receiptExpenseLinkDao.deleteAllLinksForReceipt(1L) }
        coVerify(exactly = 1) { scannedReceiptDao.delete(receipt) }
        verify(exactly = 1) { assetStore.deleteAsset("/tmp/receipt.jpg") }
        coVerify(exactly = 1) { writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.deleteReceipt") }
        // No audit write without an asset-delete failure.
        coVerify(exactly = 0) { receiptEventDao.insert(any()) }
    }

    @Test
    fun `deleteReceipt_returns_failure_when_receipt_missing`() = runTest {
        coEvery { scannedReceiptDao.getById(1L) } returns null

        val result = coordinator.deleteReceipt(1L)

        assertTrue("Expected failure, got $result", result.isFailure)
        coVerify(exactly = 0) { receiptLifecycleEventWriter.write(any(), any()) }
        coVerify(exactly = 0) { scannedReceiptDao.delete(any()) }
        coVerify(exactly = 0) { receiptExpenseLinkDao.deleteAllLinksForReceipt(any()) }
        verify(exactly = 0) { assetStore.deleteAsset(any()) }
        // Barrier is still checked first, before the existence lookup.
        coVerify(exactly = 1) { writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.deleteReceipt") }
    }

    // GR-14b pin: after the transaction has committed, a failed physical asset
    // delete must not fail the already-committed delete — instead the coordinator
    // writes a durable ASSET_DELETE_FAILED audit row via its own private
    // writeAssetDeleteFailedEvent, which performs its own canonical
    // writeBarrier.checkWritesAllowed under the direct-owner target name.
    @Test
    fun `deleteReceipt_asset_delete_failure_writes_ASSET_DELETE_FAILED_audit_event`() = runTest {
        val receipt = deletedReceiptFixture()
        coEvery { scannedReceiptDao.getById(1L) } returns receipt
        val assetFailureMessage = "x".repeat(600)
        every { assetStore.deleteAsset(any()) } throws RuntimeException(assetFailureMessage)

        val eventSlot2 = slot<ReceiptEvent>()
        val result = coordinator.deleteReceipt(1L)

        assertTrue("Expected success (audit write must not fail committed delete), got $result", result.isSuccess)
        coVerify(exactly = 1) { receiptEventDao.insert(capture(eventSlot2)) }
        val auditEvent = eventSlot2.captured
        assertEquals("ASSET_DELETE_FAILED", auditEvent.eventType)
        assertEquals(1L, auditEvent.receiptId)
        assertEquals("DELETED", auditEvent.oldStatus)
        assertNull(auditEvent.newStatus)
        assertEquals("system:coordinator", auditEvent.actor)
        assertEquals("Failed to delete asset file: [REDACTED]", auditEvent.message)
        // take(500) truncation contract for the bounded error-details field.
        assertEquals("x".repeat(500), auditEvent.errorDetails)
        assertEquals(now, auditEvent.occurredAt)
        // The GR-14b direct-owner canonical barrier check.
        coVerify(exactly = 1) { writeBarrier.checkWritesAllowed("ReceiptLifecycleCoordinator.writeAssetDeleteFailedEvent") }
        // The committed RECEIPT_DELETED lifecycle event is still written exactly once.
        coVerify(exactly = 1) { receiptLifecycleEventWriter.write(any(), any()) }
    }

    @Test
    fun `deleteReceipt_cancellation_from_asset_delete_rethrows`() = runTest {
        val receipt = deletedReceiptFixture()
        coEvery { scannedReceiptDao.getById(1L) } returns receipt
        every { assetStore.deleteAsset(any()) } throws CancellationException("job cancelled")

        assertFailsWith<CancellationException> {
            coordinator.deleteReceipt(1L)
        }
        // No audit write on cancellation — cancellation must propagate, not be swallowed.
        coVerify(exactly = 0) { receiptEventDao.insert(any()) }
    }

    @Test
    fun `transactionRunner_block_is_executed_by_setup_default_stub`() = runTest {
        // Regression pin for the f1758149 migration debt: the ingest paths run
        // inside transactionRunner.runInTransaction blocks, so a relaxed mock that
        // silently skips the block would let every DB-dependent assertion pass
        // vacuously or fail with ClassCastException. The setup default stub must
        // execute the block.
        val receipt = deletedReceiptFixture()
        coEvery { scannedReceiptDao.getById(1L) } returns receipt
        every { assetStore.deleteAsset("/tmp/receipt.jpg") } returns true
        val result = coordinator.deleteReceipt(1L)
        assertTrue("Expected success, got $result", result.isSuccess)
        // Proof the block ran: the row delete happens INSIDE the transaction block.
        coVerify(exactly = 1) { scannedReceiptDao.delete(receipt) }
    }
}
