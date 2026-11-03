package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptInsertResult
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P-03-1, email duplicate-skipped branch of [ReceiptLifecycleCoordinator.processEmailReceipt].
 *
 * When the dedupe step resolves an incoming email receipt to an EXISTING expense that another
 * receipt already owns, [ReceiptLinkService] refuses with [ExpenseAlreadyLinkedException]. The
 * coordinator must then keep the new receipt saved and unlinked, flag it for review and not
 * report the expense as linked. Any other link failure must still abort (roll back) the save.
 *
 * Mock-based (the link service and transaction lifecycle are doubles): the real refusal and
 * the real rollback are covered on Room by [ReceiptLinkServiceExpenseUniquenessTest] and
 * [ReceiptLinkServiceNestedFailureTest]. Synthetic data only.
 */
class ReceiptLifecycleCoordinatorEmailExpenseLinkedTest {

    private class CapturingWriter : DiagnosticEventWriter {
        val events = mutableListOf<DiagnosticEvent>()
        override suspend fun emit(event: DiagnosticEvent) {
            events.add(event)
        }
    }

    private val now = 1_712_000_000_000L
    private val existingExpenseId = 500L
    private val savedReceiptId = 1L
    private val sentinel = "SENTINEL_LINK_FAIL_4c"

    private val writer = CapturingWriter()
    private val linkService: ReceiptLinkService = mockk(relaxed = true)
    private val pendingReviewDao: PendingReviewDao = mockk(relaxed = true)
    private val planner: ReceiptSideEffectPlanner = mockk(relaxed = true)
    private val scannedReceiptDao: ScannedReceiptDao = mockk(relaxed = true)
    private val emailReceiptDao: EmailReceiptDao = mockk(relaxed = true)
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator = mockk(relaxed = true)
    private val transactionRunner: DomainTransactionRunner = mockk(relaxed = true)
    private val writeBarrier: DatabaseWriteBarrier = mockk(relaxed = true)
    private lateinit var coordinator: ReceiptLifecycleCoordinator

    @Before
    fun setup() {
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { privacySettingsRepository.getLoadState() } returns
            PrivacySettingsLoadState.Loaded(PrivacySettings())
        val receiptInsertResolver = mockk<ReceiptInsertResolver>(relaxed = true)
        coEvery { receiptInsertResolver.insertOrResolve(any()) } returns ReceiptInsertResult.Inserted(savedReceiptId)

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

        coEvery { scannedReceiptDao.getBySourceFingerprint(any()) } returns null
        coEvery { emailReceiptDao.insertOrIgnore(any()) } returns 1L
        coEvery { scannedReceiptDao.getById(savedReceiptId) } returns ScannedReceipt(
            id = savedReceiptId, imagePath = null, rawOcrText = "synthetic body",
            parsedTotal = 25.0, parsedMerchant = "Synthetic Merchant", parsedDate = now,
            parsedItems = null, parsedTaxAmount = null, confidence = 0.99f
        )
        coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) } returns
            MutationResult(
                CreateExpenseResult.DuplicateSkipped(existingExpenseId, "synthetic-dedupe-key"),
                PostCommitActionBatch.empty("test")
            )

        coordinator = ReceiptLifecycleCoordinator(
            database = mockk<AppDatabase>(relaxed = true),
            receiptRepository = mockk(relaxed = true),
            receiptLinkService = linkService,
            assetStore = mockk(relaxed = true),
            inputValidator = mockk(relaxed = true),
            scannedReceiptDao = scannedReceiptDao,
            receiptExpenseLinkDao = mockk<ReceiptExpenseLinkDao>(relaxed = true),
            receiptEventDao = mockk<ReceiptEventDao>(relaxed = true),
            emailReceiptDao = emailReceiptDao,
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = writeBarrier,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
            postCommitActionRunner = mockk<PostCommitActionRunner>(relaxed = true),
            assetCleanupCoordinator = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = RawPersistencePolicyResolver(privacySettingsRepository),
            diagnosticEventWriter = writer,
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = planner,
            pendingReviewDao = pendingReviewDao,
            pendingReviewSourceLinkService = mockk(relaxed = true),
            receiptInsertResolver = receiptInsertResolver,
            receiptParser = mockk<ReceiptParser>(relaxed = true),
            transactionRunner = transactionRunner,
            receiptLifecycleEventWriter = mockk(relaxed = true)
        )
    }

    private fun stubLink(result: Result<ReceiptExpenseLink>) {
        coEvery {
            linkService.linkReceiptToExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns result
    }

    private suspend fun processEmail() = coordinator.processEmailReceipt(
        emailData = EmailReceiptData(
            messageId = "", from = "sender@example.invalid", subject = "Synthetic subject",
            body = "synthetic body", receivedAt = now,
            amount = 25.0, merchant = "Synthetic Merchant", currency = "EUR",
            date = now, items = null, confidence = 0.99
        ),
        fingerprint = "",
        rawEmailBody = "synthetic body",
        sender = "sender@example.invalid",
        subject = "Synthetic subject",
        messageId = "",
        provider = "unknown",
        correlationId = "corr-fixed"
    )

    @Test
    fun INV_P031_secondEmailReceiptForAnAlreadyReceiptedExpense_returnsNeedsReviewExpenseAlreadyLinked() = runTest {
        stubLink(Result.failure(ExpenseAlreadyLinkedException(existingExpenseId)))

        val result = processEmail()

        assertTrue("expected NeedsReview, got $result", result is EmailReceiptProcessResult.NeedsReview)
        result as EmailReceiptProcessResult.NeedsReview
        assertEquals("expense_already_linked", result.reason)
        assertEquals("receipt stays saved", savedReceiptId, result.receiptId)
        assertNull("confidence is only carried for low_confidence", result.confidence)
    }

    @Test
    fun INV_P031_refusedEmailLink_savesReceiptWithPendingReview_andOnlyOneLinkAttempt() = runTest {
        stubLink(Result.failure(ExpenseAlreadyLinkedException(existingExpenseId)))

        processEmail()

        coVerify(exactly = 1) {
            pendingReviewDao.insert(match { it.scannedReceiptId == savedReceiptId })
        }
        coVerify(exactly = 1) {
            linkService.linkReceiptToExpense(
                savedReceiptId, existingExpenseId, any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun INV_P031_refusedEmailLink_doesNotReportTheExpenseAsLinked() = runTest {
        stubLink(Result.failure(ExpenseAlreadyLinkedException(existingExpenseId)))

        processEmail()

        coVerify(exactly = 1) {
            planner.planAfterReceiptSaved(any(), linkedExpenseIds = match { it.isEmpty() })
        }
    }

    @Test
    fun INV_P031_refusedEmailLink_emitsOnlyTheFixedReasonCode() = runTest {
        stubLink(Result.failure(ExpenseAlreadyLinkedException(existingExpenseId)))

        processEmail()

        val serialized = writer.events.map { it.metadata.toJson() }
        assertTrue("expected expense_already_linked diagnostic in $serialized",
            serialized.any { it.contains("expense_already_linked") })
        assertTrue(serialized.none { it.contains("synthetic body") || it.contains("sender@example.invalid") })
    }

    @Test
    fun INV_P031_successfulEmailLink_control_isNotNeedsReview_andExpenseIsReportedLinked() = runTest {
        stubLink(Result.success(mockk<ReceiptExpenseLink>(relaxed = true)))

        val result = processEmail()

        assertFalse("control must not be NeedsReview: $result", result is EmailReceiptProcessResult.NeedsReview)
        coVerify(exactly = 0) { pendingReviewDao.insert(any()) }
        coVerify(exactly = 1) {
            planner.planAfterReceiptSaved(any(), linkedExpenseIds = match { it == setOf(existingExpenseId) })
        }
    }

    @Test
    fun INV_P031_anyOtherLinkFailure_stillAbortsTheSave() = runTest {
        val otherFailures = listOf<Throwable>(
            IllegalStateException(sentinel),
            // Same text as the typed refusal but NOT the typed exception: must not be sniffed by message.
            IllegalStateException("Expense $existingExpenseId is already linked to another receipt"),
            ReceiptLinkAbortedException(ReceiptLinkFailureCode.CATEGORY_ASSIGNMENT_FAILED)
        )
        for (failure in otherFailures) {
            stubLink(Result.failure(failure))

            var thrown: Throwable? = null
            var returned: EmailReceiptProcessResult? = null
            try {
                returned = processEmail()
            } catch (e: IllegalStateException) {
                thrown = e
            }

            assertTrue(
                "failure ${failure.message} must abort the save (thrown=$thrown, returned=$returned)",
                thrown != null || returned is EmailReceiptProcessResult.Error
            )
            assertFalse(returned is EmailReceiptProcessResult.NeedsReview)
        }
        coVerify(exactly = 0) { pendingReviewDao.insert(any()) }
    }
}
