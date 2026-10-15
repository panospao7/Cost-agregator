package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.provenance.ExpenseSourceFields
import com.yourname.expensetracker.domain.provenance.SourceLinkPayload
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteResult
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.TargetEntityType
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import com.yourname.expensetracker.domain.transaction.CategoryAssignmentOutcome
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseCategoryAssignmentPort
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R1 (RG11-H2): a [CategoryAssignmentOutcome.Failed] from the category port, made inside the
 * link transaction, must abort the link LOUDLY (bounded code, no SQL text) instead of the
 * outermost commit silently rolling everything back while success is reported.
 *
 * REAL Room (production in-memory builder, foreign_keys enforced), REAL [ReceiptLinkService],
 * [RoomDomainTransactionRunner] and DAOs. The port is a fake that performs a REAL failing
 * nested write (UPDATE expenses SET categoryId=<never-existing id>, FK violation inside a
 * nested withTransaction) and returns Failed, exactly like the production service did.
 *
 * Hand-derived oracles:
 *  - standalone link with a Failed port outcome -> Result.failure(ReceiptLinkAbortedException),
 *    code CATEGORY_ASSIGNMENT_FAILED, message == code name, 0 links, legacy receipt.expenseId null,
 *    no deferred side-effect capture, no dispatch.
 *  - createExpenseAndLinkReceipt (outer transaction) -> Result.failure whose cause is that
 *    exception, 0 expenses, 0 links, no post-commit run.
 *  - a port throwing CancellationException propagates (never wrapped or converted), 0 links.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(RestrictedExpenseDaoMutation::class)
class ReceiptLinkServiceNestedFailureTest {

    private val now = 1_700_000_000_000L
    private val neverExistingCategoryId = 987_654L

    private lateinit var database: AppDatabase
    private lateinit var postCommitRunner: RecordingPostCommitRunner

    private class RecordingPostCommitRunner : PostCommitActionRunner {
        var runs = 0
        override suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult {
            runs++
            return SideEffectBatchResult("test", 0, 0, 0, 0, 0, emptyList())
        }
    }

    private class FakeSourceLinkWriter : SourceLinkWriter {
        override suspend fun linkExpense(
            expenseId: Long,
            payload: SourceLinkPayload,
            correlationId: String?
        ): SourceLinkWriteResult = SourceLinkWriteResult.Created(1L)

        override suspend fun linkTarget(
            targetType: TargetEntityType,
            targetId: Long,
            payload: SourceLinkPayload,
            correlationId: String?
        ): SourceLinkWriteResult = SourceLinkWriteResult.Created(1L)

        override suspend fun linkExpenseSourcesFromRequest(
            expenseId: Long,
            requestSourceFields: ExpenseSourceFields,
            correlationId: String?
        ): List<SourceLinkWriteResult> = emptyList()
    }

    /** Real failing nested write (FK violation) that reports Failed, like the old service did. */
    private class FailingNestedWritePort(
        private val database: AppDatabase,
        private val missingCategoryId: Long
    ) : ExpenseCategoryAssignmentPort {
        var assignCalls = 0
        var dispatchCalls = 0

        override suspend fun assignCategoryIfUnset(
            expenseId: Long,
            categoryId: Long,
            source: String,
            correlationId: String?
        ): CategoryAssignmentOutcome {
            assignCalls++
            return try {
                database.withTransaction {
                    database.expenseDao().updateCategory(expenseId, missingCategoryId)
                    CategoryAssignmentOutcome.Assigned
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CategoryAssignmentOutcome.Failed("synthetic nested failure")
            }
        }

        override suspend fun dispatchAssignedCategorySideEffects(
            expenseId: Long,
            source: String,
            correlationId: String?
        ) {
            dispatchCalls++
        }
    }

    private class CancellingPort : ExpenseCategoryAssignmentPort {
        override suspend fun assignCategoryIfUnset(
            expenseId: Long,
            categoryId: Long,
            source: String,
            correlationId: String?
        ): CategoryAssignmentOutcome = throw CancellationException("synthetic cancellation")

        override suspend fun dispatchAssignedCategorySideEffects(
            expenseId: Long,
            source: String,
            correlationId: String?
        ) = Unit
    }

    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var sourceLinkWriter: FakeSourceLinkWriter
    private lateinit var currencySettingsRepository: CurrencySettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider(now)

        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        writeBarrier = DatabaseWriteBarrier(maintenanceMode)

        currencySettingsRepository = mockk(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        postCommitRunner = RecordingPostCommitRunner()
        sourceLinkWriter = FakeSourceLinkWriter()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun newLinkService(
        port: ExpenseCategoryAssignmentPort,
        transactionRunner: RoomDomainTransactionRunner
    ) = ReceiptLinkService(
        database = database,
        receiptExpenseLinkDao = database.receiptExpenseLinkDao(),
        scannedReceiptDao = database.scannedReceiptDao(),
        receiptLifecycleEventWriter = mockk(relaxed = true),
        receiptItemCategorizationDao = database.receiptItemCategorizationDao(),
        warrantyDao = database.warrantyDao(),
        returnWindowDao = database.returnWindowDao(),
        expenseDao = database.expenseDao(),
        timeProvider = timeProvider,
        writeBarrier = writeBarrier,
        sourceLinkWriter = sourceLinkWriter,
        categoryAssignmentPort = port,
        transactionRunner = transactionRunner
    )

    private fun newReceiptCoordinator(
        linkService: ReceiptLinkService,
        transactionRunner: RoomDomainTransactionRunner
    ): ReceiptLifecycleCoordinator {
        val txCoordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = database.expenseDao(),
            transactionEventDao = database.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = mockk(relaxed = true),
            runner = postCommitRunner,
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = sourceLinkWriter,
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )
        return ReceiptLifecycleCoordinator(
            database = database,
            transactionRunner = transactionRunner,
            receiptLifecycleEventWriter = mockk(relaxed = true),
            receiptRepository = mockk(relaxed = true),
            receiptLinkService = linkService,
            assetStore = mockk(relaxed = true),
            inputValidator = mockk(relaxed = true),
            scannedReceiptDao = database.scannedReceiptDao(),
            receiptExpenseLinkDao = database.receiptExpenseLinkDao(),
            receiptEventDao = database.receiptEventDao(),
            emailReceiptDao = mockk(relaxed = true),
            pendingReviewDao = database.pendingReviewDao(),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = writeBarrier,
            transactionLifecycleCoordinator = txCoordinator,
            postCommitActionRunner = postCommitRunner,
            assetCleanupCoordinator = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            sourceLinkWriter = sourceLinkWriter,
            receiptSideEffectPlanner = mockk(relaxed = true),
            receiptInsertResolver = mockk(relaxed = true),
            receiptParser = mockk(relaxed = true)
        )
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Seeds an existing category C, a receipt R and ONE item categorization (suggested = C,
     * not user-corrected), so the majority category is C and the link flow consults the port.
     * Returns the receipt id.
     */
    private suspend fun seedReceiptWithCategorizedItem(): Long {
        val c = database.categoryDao().insert(Category(name = "Synthetic C", icon = "C", color = "#112233"))
        assertTrue("fixture: category insert failed", c > 0L)
        assertNull(
            "fixture: never-existing category id must not exist",
            database.categoryDao().getById(neverExistingCategoryId)
        )
        val receiptId = database.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "synthetic",
                parsedTotal = 12.0,
                parsedMerchant = "Synthetic Merchant",
                parsedDate = now,
                parsedItems = "[]",
                parsedTaxAmount = null,
                confidence = 0.9f,
                createdAt = now,
                updatedAt = now,
                sourceType = "CAMERA",
                documentType = "RETAIL_RECEIPT"
            )
        )
        assertTrue("fixture: receipt insert failed", receiptId > 0L)
        database.receiptItemCategorizationDao().insert(
            ReceiptItemCategorization(
                receiptId = receiptId,
                itemDescription = "Item 1",
                itemAmount = 4.0,
                suggestedCategoryId = c,
                suggestedCategoryName = null,
                confidence = 0.9f,
                aiRationale = null,
                alternativeCategoriesJson = null,
                userCorrectedCategoryId = null,
                userCorrectedCategoryName = null,
                userCorrectedAt = null,
                taxAmount = null,
                createdAt = now,
                updatedAt = now
            )
        )
        return receiptId
    }

    private suspend fun insertUncategorizedExpense(): Long {
        val id = database.expenseDao().insert(
            Expense(
                amount = 25.0,
                merchant = "Synthetic Merchant",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        assertTrue("fixture: expense insert failed", id > 0L)
        assertNull("fixture: expense must start uncategorized", database.expenseDao().getById(id)!!.categoryId)
        return id
    }

    private fun createRequest(receiptId: Long) = CreateExpenseRequest(
        merchant = "Synthetic Merchant",
        amount = 25.0,
        currency = "EUR",
        date = now,
        transactionType = TransactionType.PURCHASE,
        source = ExpenseSource.RECEIPT_SCAN,
        scannedReceiptId = receiptId,
        isManualEntry = true
    )

    // ------------------------------------------------------------------- tests

    @Test
    fun standaloneLink_failedAssignmentOutcome_abortsWithBoundedCode_andRollsBackLink() = runTest {
        val receiptId = seedReceiptWithCategorizedItem()
        val expenseId = insertUncategorizedExpense()
        val port = FailingNestedWritePort(database, neverExistingCategoryId)
        val service = newLinkService(port, RoomDomainTransactionRunner(database, timeProvider))
        var sinkCalls = 0

        val result = service.linkReceiptToExpense(
            receiptId = receiptId,
            expenseId = expenseId,
            linkType = "DIRECT_SAVE",
            source = "TEST",
            writeSourceLink = false,
            deferredCategorySideEffectSink = { sinkCalls++ }
        )

        assertTrue("link must report failure (success=${result.isSuccess})", result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(
            "failure must be ReceiptLinkAbortedException (was ${failure?.javaClass?.simpleName})",
            failure is ReceiptLinkAbortedException
        )
        failure as ReceiptLinkAbortedException
        assertEquals(ReceiptLinkFailureCode.CATEGORY_ASSIGNMENT_FAILED, failure.code)
        assertEquals("CATEGORY_ASSIGNMENT_FAILED", failure.message)
        assertNull("no cause may be attached (no SQL text)", failure.cause)

        assertEquals("port consulted once", 1, port.assignCalls)
        assertEquals("no deferred side-effect capture", 0, sinkCalls)
        assertEquals("no post-commit dispatch", 0, port.dispatchCalls)
        assertEquals("link rolled back", 0, database.receiptExpenseLinkDao().getLinksForReceipt(receiptId).size)
        assertNull(
            "legacy receipt.expenseId rolled back",
            database.scannedReceiptDao().getById(receiptId)!!.expenseId
        )
        assertEquals("seeded expense still present", 1, database.expenseDao().getTotalCount())
        assertNull("expense stays uncategorized", database.expenseDao().getById(expenseId)!!.categoryId)
    }

    @Test
    fun createExpenseAndLinkReceipt_failedAssignmentOutcome_failsAndRollsBackExpenseAndLink() = runTest {
        val receiptId = seedReceiptWithCategorizedItem()
        val port = FailingNestedWritePort(database, neverExistingCategoryId)
        val transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        val coordinator = newReceiptCoordinator(newLinkService(port, transactionRunner), transactionRunner)

        val result = coordinator.createExpenseAndLinkReceipt(createRequest(receiptId))

        assertTrue("create+link must report failure (success=${result.isSuccess})", result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(
            "failure must be IllegalStateException (was ${failure?.javaClass?.simpleName})",
            failure is IllegalStateException
        )
        assertEquals("SOURCE_LINK_FAILED", failure!!.message)
        // kotlinx.coroutines stack-trace recovery (active under test assertions) may re-wrap the
        // exception thrown across the suspend transaction boundary in a copy of the same type
        // whose cause is the original, so search the cause chain instead of only the direct cause.
        val chain = generateSequence(failure as Throwable) { it.cause }.take(8).toList()
        val cause = chain.firstOrNull { it is ReceiptLinkAbortedException }
        assertTrue(
            "cause chain must contain ReceiptLinkAbortedException (was ${chain.map { it.javaClass.simpleName }})",
            cause is ReceiptLinkAbortedException
        )
        cause as ReceiptLinkAbortedException
        assertEquals(ReceiptLinkFailureCode.CATEGORY_ASSIGNMENT_FAILED, cause.code)
        assertEquals("CATEGORY_ASSIGNMENT_FAILED", cause.message)

        assertEquals("port consulted once", 1, port.assignCalls)
        assertEquals("new expense rolled back", 0, database.expenseDao().getTotalCount())
        assertEquals("link rolled back", 0, database.receiptExpenseLinkDao().getLinksForReceipt(receiptId).size)
        assertEquals("no post-commit run", 0, postCommitRunner.runs)
        assertEquals("no post-commit dispatch", 0, port.dispatchCalls)
    }

    @Test
    fun standaloneLink_portThrowsCancellation_propagatesAndLinkIsNotCommitted() = runTest {
        val receiptId = seedReceiptWithCategorizedItem()
        val expenseId = insertUncategorizedExpense()
        val service = newLinkService(CancellingPort(), RoomDomainTransactionRunner(database, timeProvider))

        var propagated: Throwable? = null
        try {
            service.linkReceiptToExpense(
                receiptId = receiptId,
                expenseId = expenseId,
                linkType = "DIRECT_SAVE",
                source = "TEST",
                writeSourceLink = false
            )
            fail("CancellationException must propagate, not be converted to Result.failure")
        } catch (e: CancellationException) {
            propagated = e
        }

        assertEquals(
            "propagated exception must be the original CancellationException",
            "synthetic cancellation",
            propagated?.message
        )
        assertEquals("link not committed", 0, database.receiptExpenseLinkDao().getLinksForReceipt(receiptId).size)
    }
}
