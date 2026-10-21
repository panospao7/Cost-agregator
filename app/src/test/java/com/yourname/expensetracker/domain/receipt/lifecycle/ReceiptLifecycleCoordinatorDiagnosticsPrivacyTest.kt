package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicyResolver
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W2 call-site privacy test for the receipt intake and email-coordinator diagnostics.
 *
 * Production references (ReceiptLifecycleCoordinator.kt):
 *  - emitIntakeDiagnostic (~:1397): metadata keys stage, eventType, mimeType, fileSizeBytes,
 *    message, plus the caller map (only "reasonCode", ~:339-343). reasonCode is chosen by the
 *    when-chain at ~:331-338 from a fixed set; raw validation error text is never forwarded.
 *  - emitEmailReceiptDiagnostic (~:1360): single metadata key "reason" from fixed literals at
 *    ~:949, :967, :975, :1225-1252, :1341 (plus two dynamic duplicate_* prefixes).
 *
 * Synthetic sentinels only. Wiring copied from ReceiptLifecycleCoordinatorTest.
 */
class ReceiptLifecycleCoordinatorDiagnosticsPrivacyTest {

    private class CapturingWriter : DiagnosticEventWriter {
        val events = mutableListOf<DiagnosticEvent>()
        override suspend fun emit(event: DiagnosticEvent) {
            events.add(event)
        }
    }

    private val sentinelSender = "SENTINEL_SENDER_9k@x.example"
    private val sentinelSubject = "SENTINEL_SUBJECT_9k"
    private val sentinelBody = "SENTINEL_BODY_9k"
    private val sentinelMessageId = "SENTINEL_MSGID_9k"
    private val sentinelOcr = "SENTINEL_OCR_9k"
    private val sentinelMerchant = "SENTINEL_MERCHANT_9k"
    private val sentinelPath = "/data/user/0/x/SENTINEL_PATH_9k.txt"
    private val sentinelFragments = listOf(
        "SENTINEL_SENDER_9k", "SENTINEL_SUBJECT_9k", "SENTINEL_BODY_9k", "SENTINEL_MSGID_9k",
        "SENTINEL_OCR_9k", "SENTINEL_MERCHANT_9k", "SENTINEL_PATH_9k", "SENTINEL_CURR_9k", "x.example"
    )

    /** Intake metadata allow-list (emitIntakeDiagnostic + the single caller map key). */
    private val allowedIntakeKeys = setOf("stage", "eventType", "mimeType", "fileSizeBytes", "message", "reasonCode")
    private val allowedIntakeReasonCodes = setOf(
        "URI_NOT_READABLE", "MIME_UNKNOWN", "MIME_UNSUPPORTED", "FILE_TOO_LARGE",
        "IMAGE_DECODE_FAILED", "VALIDATION_FAILED"
    )

    /** Email coordinator metadata allow-list and fixed reason literals. */
    private val allowedEmailKeys = setOf("reason")
    private val fixedEmailReasons = setOf(
        "writes_blocked", "messageId_hash_duplicate", "fingerprint_duplicate",
        "validation_failed", "insert_conflict", "create_error",
        "low_confidence", "currency_unknown", "home_currency_unavailable", "incomplete_parse"
    )
    private val dynamicEmailReasonPrefixes = listOf(
        "duplicate_detector_", "receipt_insert_duplicate_", "in_transaction_duplicate_"
    )

    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var inputValidator: ReceiptInputValidator
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var emailReceiptDao: EmailReceiptDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var duplicateDetector: ReceiptDuplicateDetector
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var receiptInsertResolver: ReceiptInsertResolver
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var transactionLifecycleCoordinator: TransactionLifecycleCoordinator
    private lateinit var transactionRunner: DomainTransactionRunner
    private lateinit var coordinator: ReceiptLifecycleCoordinator
    private val writer = CapturingWriter()
    private val linkService: ReceiptLinkService = mockk(relaxed = true)

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        receiptRepository = mockk(relaxed = true)
        inputValidator = mockk(relaxed = true)
        scannedReceiptDao = mockk(relaxed = true)
        emailReceiptDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        duplicateDetector = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        receiptInsertResolver = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        transactionLifecycleCoordinator = mockk(relaxed = true)
        transactionRunner = mockk(relaxed = true)
        val postCommitActionRunner = mockk<PostCommitActionRunner>(relaxed = true)
        val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)
        val rawPersistencePolicyResolver = RawPersistencePolicyResolver(privacySettingsRepository)
        coEvery { privacySettingsRepository.getLoadState() } returns
            PrivacySettingsLoadState.Loaded(PrivacySettings())

        val assetCleanupCoordinator = AssetCleanupCoordinator(
            scannedReceiptDao = scannedReceiptDao,
            assetStore = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            transactionRunner = transactionRunner
        )
        stubTransactionRunnerExecutesBlocks()
        coEvery {
            writeBarrier.runWrite(any<DatabaseAccessOperation>(), any<suspend () -> Any?>())
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(1L)
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(CreateExpenseResult.Created(500L), PostCommitActionBatch.empty("test"))
        coEvery {
            linkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(mockk<com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink>(relaxed = true))

        coordinator = ReceiptLifecycleCoordinator(
            database = mockk<AppDatabase>(relaxed = true),
            receiptRepository = receiptRepository,
            receiptLinkService = linkService,
            assetStore = mockk(relaxed = true),
            inputValidator = inputValidator,
            scannedReceiptDao = scannedReceiptDao,
            receiptExpenseLinkDao = mockk<ReceiptExpenseLinkDao>(relaxed = true),
            receiptEventDao = mockk<ReceiptEventDao>(relaxed = true),
            emailReceiptDao = emailReceiptDao,
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = duplicateDetector,
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = writeBarrier,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
            postCommitActionRunner = postCommitActionRunner,
            assetCleanupCoordinator = assetCleanupCoordinator,
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = rawPersistencePolicyResolver,
            diagnosticEventWriter = writer,
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = mockk(relaxed = true),
            pendingReviewDao = mockk<PendingReviewDao>(relaxed = true),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            receiptInsertResolver = receiptInsertResolver,
            receiptParser = mockk<ReceiptParser>(relaxed = true),
            transactionRunner = transactionRunner,
            receiptLifecycleEventWriter = mockk(relaxed = true),
        )
    }

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

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun serialize(e: DiagnosticEvent): String = listOf(
        e.eventId, e.pipeline.name, e.stage, e.outcome.name, e.severity.name,
        e.reasonCode?.name, e.entityType, e.entityId, e.sourceType, e.sourceIdHash,
        e.correlationId, e.causationId, e.metadata.toJson(), e.elapsedMs, e.isTerminal
    ).joinToString("|")

    private fun assertNoSentinels(events: List<DiagnosticEvent>) {
        assertTrue("expected at least one event", events.isNotEmpty())
        events.forEach { e ->
            val text = serialize(e)
            sentinelFragments.forEach { s ->
                assertFalse("sentinel $s leaked in stage=${e.stage}: $text", text.contains(s))
            }
            assertNull("no raw throwable expected on stage=${e.stage}", e.exception)
        }
    }

    private fun keysOf(e: DiagnosticEvent): Set<String> =
        JSONObject(e.metadata.toJson()).keys().asSequence().toSet()

    private fun reasonOf(e: DiagnosticEvent): String = JSONObject(e.metadata.toJson()).getString("reason")

    private fun invalid(vararg errors: String) = ReceiptInputValidator.ValidationResult(
        isValid = false, errors = errors.toList(), mimeType = null, fileSizeBytes = null
    )

    private fun emailData(
        amount: Double? = 25.0,
        merchant: String? = sentinelMerchant,
        currency: String? = "EUR",
        confidence: Double = 0.99,
        messageId: String = sentinelMessageId
    ) = EmailReceiptData(
        messageId = messageId, from = sentinelSender, subject = sentinelSubject,
        body = sentinelBody, receivedAt = now,
        amount = amount, merchant = merchant, currency = currency,
        date = now, items = null, confidence = confidence
    )

    private suspend fun processEmail(data: EmailReceiptData = emailData()) = coordinator.processEmailReceipt(
        emailData = data,
        fingerprint = "",
        rawEmailBody = sentinelBody,
        sender = sentinelSender,
        subject = sentinelSubject,
        messageId = data.messageId,
        provider = "unknown",
        correlationId = "corr-fixed"
    )

    private fun stubSavedEmailReceipt() {
        coEvery { scannedReceiptDao.getBySourceFingerprint(any()) } returns null
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(1L) } returns ScannedReceipt(
            id = 1L, imagePath = null, rawOcrText = sentinelBody,
            parsedTotal = 25.0, parsedMerchant = sentinelMerchant, parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.2f
        )
    }

    private fun assertEmailEventsAreSafe(expectedReason: String) {
        val emailEvents = writer.events.filter { it.pipeline == AppPipeline.EMAIL }
        assertTrue("expected an email diagnostic", emailEvents.isNotEmpty())
        assertNoSentinels(writer.events)
        emailEvents.forEach { e ->
            assertTrue("keys outside allow-list: ${keysOf(e)}", allowedEmailKeys.containsAll(keysOf(e)))
            val reason = reasonOf(e)
            assertTrue(
                "reason '$reason' is not a fixed code",
                reason in fixedEmailReasons || dynamicEmailReasonPrefixes.any { reason.startsWith(it) }
            )
        }
        assertTrue(
            "expected reason $expectedReason in ${emailEvents.map { reasonOf(it) }}",
            emailEvents.any { reasonOf(it) == expectedReason }
        )
    }

    // ── Intake: INPUT_RECEIVED / VALIDATION_FAILED / VALIDATION_PASSED ───────

    @Test
    fun `intake validation failure emits fixed reasonCode and never raw error text`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { inputValidator.validate(uri) } returns invalid(
            "Unsupported MIME $sentinelPath $sentinelBody $sentinelOcr"
        )

        val result = coordinator.processReceiptInput(uri)

        assertTrue(result.isFailure)
        val intake = writer.events
        assertEquals(listOf("INPUT_RECEIVED", "VALIDATION_FAILED"),
            intake.map { JSONObject(it.metadata.toJson()).getString("eventType") })
        assertEquals(listOf(EventOutcome.RECEIVED, EventOutcome.FAILED_FINAL), intake.map { it.outcome })
        assertTrue(intake.all { it.pipeline == AppPipeline.RECEIPT })
        intake.forEach { assertTrue("keys: ${keysOf(it)}", allowedIntakeKeys.containsAll(keysOf(it))) }
        val failed = JSONObject(intake.last().metadata.toJson())
        assertEquals("MIME_UNSUPPORTED", failed.getString("reasonCode"))
        assertEquals("Validation failed", failed.getString("message"))
        assertNoSentinels(intake)
    }

    @Test
    fun `intake reasonCode stays inside the fixed set for every classified validation error`() = runTest {
        val table = listOf(
            "URI not readable $sentinelPath" to "URI_NOT_READABLE",
            "Cannot determine MIME type for $sentinelPath" to "MIME_UNKNOWN",
            "Unsupported MIME $sentinelPath" to "MIME_UNSUPPORTED",
            // DIAG-07: real validator message must not fall into the generic "MIME type" bucket
            "Unsupported MIME type: image/gif" to "MIME_UNSUPPORTED",
            "File too large $sentinelPath" to "FILE_TOO_LARGE",
            "Image decode failed $sentinelPath" to "IMAGE_DECODE_FAILED",
            "Something else entirely $sentinelPath $sentinelBody" to "VALIDATION_FAILED"
        )
        for ((error, expectedCode) in table) {
            writer.events.clear()
            val uri = mockk<Uri>(relaxed = true)
            coEvery { inputValidator.validate(uri) } returns invalid(error)

            coordinator.processReceiptInput(uri)

            val failed = writer.events.single {
                JSONObject(it.metadata.toJson()).optString("eventType") == "VALIDATION_FAILED"
            }
            val code = JSONObject(failed.metadata.toJson()).getString("reasonCode")
            assertEquals(expectedCode, code)
            assertTrue(code in allowedIntakeReasonCodes)
            assertNoSentinels(writer.events)
        }
    }

    @Test
    fun `intake validation passed emits only bounded stage mime and size without OCR or path sentinels`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { inputValidator.validate(uri) } returns ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 1024L
        )
        val savedReceipt = ScannedReceipt(
            id = 0L, imagePath = sentinelPath, rawOcrText = sentinelOcr,
            parsedTotal = 25.0, parsedMerchant = sentinelMerchant, parsedDate = now,
            parsedItems = "[]", parsedTaxAmount = null, confidence = 0.95f
        )
        val parsed = ReceiptParser.ParsedReceipt(
            merchantName = sentinelMerchant, total = ReceiptParser.sourceMoneyOrNull(25.0, "EUR"),
            subtotal = null, tax = null, date = null, currency = "EUR", lineItems = emptyList(),
            confidence = 0.95f, taxInclusive = false
        )
        coEvery { receiptRepository.processReceipt(uri, false, "image/jpeg") } returns
            ReceiptRepository.ProcessReceiptResult(receipt = savedReceipt, parsed = parsed)
        coEvery { scannedReceiptDao.insert(any()) } returns 1L
        coEvery { duplicateDetector.checkDuplicate(any(), any(), any(), any()) } returns
            ReceiptDuplicateDetector.DuplicateResult(
                isDuplicate = false, confidence = 0.0f, existingReceiptId = null, reason = null, matchType = "NONE"
            )

        coordinator.processReceiptInput(uri)

        val passed = writer.events.single {
            JSONObject(it.metadata.toJson()).optString("eventType") == "VALIDATION_PASSED"
        }
        assertEquals(EventOutcome.COMPLETED, passed.outcome)
        assertEquals(setOf("stage", "eventType", "mimeType", "fileSizeBytes"), keysOf(passed))
        assertEquals("image/jpeg", JSONObject(passed.metadata.toJson()).getString("mimeType"))
        assertTrue(writer.events.any { it.outcome == EventOutcome.RECEIVED })
        writer.events.forEach { assertTrue(allowedIntakeKeys.containsAll(keysOf(it))) }
        assertNoSentinels(writer.events)
    }

    // ── Email coordinator: `reason` is always a fixed code ───────────────────

    @Test
    fun `email low confidence reason is the fixed code and sender subject body messageId do not leak`() = runTest {
        stubSavedEmailReceipt()

        val result = processEmail(emailData(confidence = 0.2))

        assertTrue(result is EmailReceiptProcessResult.NeedsReview)
        assertEmailEventsAreSafe("low_confidence")
    }

    @Test
    fun `email incomplete parse reason is the fixed code`() = runTest {
        stubSavedEmailReceipt()

        val result = processEmail(emailData(amount = null))

        assertTrue(result is EmailReceiptProcessResult.NeedsReview)
        assertEmailEventsAreSafe("incomplete_parse")
    }

    @Test
    fun `email unsupported currency reason is the fixed code and never echoes the currency text`() = runTest {
        stubSavedEmailReceipt()

        val result = processEmail(emailData(currency = "SENTINEL_CURR_9k"))

        assertTrue(result is EmailReceiptProcessResult.NeedsReview)
        assertEmailEventsAreSafe("currency_unknown")
    }

    @Test
    fun `email expense validation failure reason is fixed and does not echo validator errors`() = runTest {
        stubSavedEmailReceipt()
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult<CreateExpenseResult>(
                value = CreateExpenseResult.ValidationFailed(listOf("bad $sentinelBody $sentinelPath")),
                postCommitActions = PostCommitActionBatch.empty("test")
            )

        val result = processEmail()

        assertTrue(result is EmailReceiptProcessResult.NeedsReview)
        assertEmailEventsAreSafe("validation_failed")
    }

    @Test
    fun `email blocked writes reason is the fixed code and does not echo the barrier operation text`() = runTest {
        coEvery { writeBarrier.checkWritesAllowed(any<String>()) } throws DatabaseAccessBlockedException(
            accessType = DatabaseAccessType.WRITE,
            operation = DatabaseAccessOperation(sentinelBody),
            mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )

        val result = processEmail()

        assertTrue(result is EmailReceiptProcessResult.Error)
        assertFalse((result as EmailReceiptProcessResult.Error).message.contains("SENTINEL"))
        assertEmailEventsAreSafe("writes_blocked")
    }

    @Test
    fun `email messageId duplicate reason is the fixed code and does not echo the messageId`() = runTest {
        coEvery { scannedReceiptDao.getBySourceFingerprint(any()) } returns ScannedReceipt(
            id = 5L, imagePath = null, rawOcrText = sentinelBody,
            parsedTotal = 25.0, parsedMerchant = sentinelMerchant, parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.9f
        )

        val result = processEmail()

        assertEquals(EmailReceiptProcessResult.Duplicate(5L), result)
        assertEmailEventsAreSafe("messageId_hash_duplicate")
    }
}
