package com.yourname.expensetracker.domain.transaction.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.data.database.entity.ReturnWindow
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.provenance.ExpenseSourceFields
import com.yourname.expensetracker.domain.provenance.SourceLinkPayload
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteResult
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.TargetEntityType
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import com.yourname.expensetracker.domain.transaction.CategoryAssignmentOutcome
import com.yourname.expensetracker.domain.transaction.ExpenseCategoryAssignmentPort
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * P-03-1 part 2: [TransactionLifecycleCoordinator.deleteExpense] unlinks every linked receipt
 * through the real [ReceiptLinkService] inside the delete transaction.
 *
 * REAL Room (foreign_keys enforced) and REAL link service. Before the change the FK cascade
 * removed the link row but left receipt match state, warranty, return window and item
 * expenseId stale.
 *
 * Hand-derived oracles:
 *  - one linked receipt: expense gone, link gone, receipt UNMATCHED with null expenseId,
 *    warranty / return window / item expenseId null.
 *  - two linked receipts: both reset the same way.
 *  - no link: delete succeeds, an unrelated linked receipt is untouched.
 *  - unlink failure: Result.failure with bounded message, expense and every link still present.
 *  - CancellationException from unlink propagates.
 *  - failure logs contain neither the merchant nor the receipt OCR text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(RestrictedExpenseDaoMutation::class)
class TransactionLifecycleCoordinatorDeleteUnlinksReceiptsTest {

    private val now = 1_700_000_000_000L
    private val secretMerchant = "ZzSecretMerchantQq"
    private val secretOcr = "ZzSecretRawOcrQq"

    private lateinit var database: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var realLinkService: ReceiptLinkService

    private class NoopPostCommitRunner : PostCommitActionRunner {
        override suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult =
            SideEffectBatchResult("test", 0, 0, 0, 0, 0, emptyList())
    }

    private class NoopSourceLinkWriter : SourceLinkWriter {
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

    private class NoopCategoryPort : ExpenseCategoryAssignmentPort {
        override suspend fun assignCategoryIfUnset(
            expenseId: Long,
            categoryId: Long,
            source: String,
            correlationId: String?
        ): CategoryAssignmentOutcome = CategoryAssignmentOutcome.Assigned

        override suspend fun dispatchAssignedCategorySideEffects(
            expenseId: Long,
            source: String,
            correlationId: String?
        ) = Unit
    }

    private class CapturingTree : Timber.Tree() {
        val lines = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += message
            if (t != null) lines += t.toString()
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider(now)
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        writeBarrier = DatabaseWriteBarrier(maintenanceMode)
        realLinkService = ReceiptLinkService(
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
            sourceLinkWriter = NoopSourceLinkWriter(),
            categoryAssignmentPort = NoopCategoryPort(),
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun newCoordinator(linkService: ReceiptLinkService): TransactionLifecycleCoordinator {
        val currencySettings = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettings.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        return TransactionLifecycleCoordinator(
            database = database,
            expenseDao = database.expenseDao(),
            transactionEventDao = database.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = mockk(relaxed = true),
            runner = NoopPostCommitRunner(),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettings,
            sourceLinkWriter = NoopSourceLinkWriter(),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            receiptLinkService = dagger.Lazy { linkService }
        )
    }

    // ---------------------------------------------------------------- fixtures

    private suspend fun insertExpense(): Expense {
        val id = database.expenseDao().insert(
            Expense(
                amount = 25.0,
                merchant = secretMerchant,
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        assertTrue("fixture: expense insert failed", id > 0L)
        return database.expenseDao().getById(id)!!
    }

    private suspend fun insertReceipt(): Long {
        val id = database.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = secretOcr,
                parsedTotal = 25.0,
                parsedMerchant = secretMerchant,
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
        assertTrue("fixture: receipt insert failed", id > 0L)
        return id
    }

    /** Links receipt to expense with matched state, a warranty, a return window and one item. */
    private suspend fun linkWithDependents(receiptId: Long, expenseId: Long) {
        database.receiptExpenseLinkDao().insert(
            ReceiptExpenseLink(
                receiptId = receiptId,
                expenseId = expenseId,
                linkType = "DIRECT_SAVE",
                confidence = 0.9f,
                source = "TEST",
                createdAt = now,
                createdBy = "test"
            )
        )
        database.scannedReceiptDao().updatePrimaryExpenseId(
            receiptId = receiptId,
            expenseId = expenseId,
            now = now
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE scanned_receipts SET matchStatus = 'MANUALLY_MATCHED' WHERE id = $receiptId"
        )
        database.warrantyDao().insertWarranty(
            Warranty(
                receiptId = receiptId,
                expenseId = expenseId,
                productName = "Synthetic Product",
                merchantName = "Synthetic Shop",
                purchaseDate = now,
                warrantyDurationMonths = 12,
                warrantyEndDate = now + 1_000_000L,
                createdAt = now,
                updatedAt = now
            )
        )
        database.returnWindowDao().insertReturnWindow(
            ReturnWindow(
                receiptId = receiptId,
                expenseId = expenseId,
                productName = "Synthetic Product",
                merchantName = "Synthetic Shop",
                purchaseDate = now,
                returnDays = 30,
                returnDeadline = now + 2_000_000L,
                createdAt = now,
                updatedAt = now
            )
        )
        database.receiptItemCategorizationDao().insert(
            ReceiptItemCategorization(
                receiptId = receiptId,
                expenseId = expenseId,
                itemDescription = "Item 1",
                itemAmount = 4.0,
                suggestedCategoryId = null,
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
        // fixture sanity: the link state really is "matched" before the delete
        val receipt = database.scannedReceiptDao().getById(receiptId)!!
        assertEquals(expenseId, receipt.expenseId)
        assertEquals(MatchStatus.MANUALLY_MATCHED, receipt.matchStatus)
        assertEquals(expenseId, database.warrantyDao().getWarrantyByReceiptId(receiptId)!!.expenseId)
    }

    private suspend fun assertReceiptStateReset(receiptId: Long) {
        val receipt = database.scannedReceiptDao().getById(receiptId)!!
        assertEquals("receipt match status reset", MatchStatus.UNMATCHED, receipt.matchStatus)
        assertNull("receipt expenseId cleared", receipt.expenseId)
        assertNull(
            "warranty expenseId cleared",
            database.warrantyDao().getWarrantyByReceiptId(receiptId)!!.expenseId
        )
        assertNull(
            "return window expenseId cleared",
            database.returnWindowDao().getReturnWindowByReceiptId(receiptId)!!.expenseId
        )
        val items = database.receiptItemCategorizationDao().getByReceiptId(receiptId)
        assertEquals(1, items.size)
        assertNull("item expenseId cleared", items.single().expenseId)
        assertEquals(
            "link row gone",
            0,
            database.receiptExpenseLinkDao().getLinksForReceipt(receiptId).size
        )
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun deleteExpense_withOneLinkedReceipt_removesExpenseAndLink_andResetsReceiptState() = runTest {
        val expense = insertExpense()
        val receiptId = insertReceipt()
        linkWithDependents(receiptId, expense.id)

        val result = newCoordinator(realLinkService).deleteExpense(expense)

        assertTrue("delete must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertNull("expense gone", database.expenseDao().getById(expense.id))
        assertReceiptStateReset(receiptId)
    }

    @Test
    fun deleteExpense_withTwoLinkedReceipts_unlinksBoth() = runTest {
        val expense = insertExpense()
        val receiptA = insertReceipt()
        val receiptB = insertReceipt()
        linkWithDependents(receiptA, expense.id)
        linkWithDependents(receiptB, expense.id)

        val result = newCoordinator(realLinkService).deleteExpense(expense)

        assertTrue("delete must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertNull("expense gone", database.expenseDao().getById(expense.id))
        assertReceiptStateReset(receiptA)
        assertReceiptStateReset(receiptB)
    }

    @Test
    fun deleteExpense_withNoLinkedReceipt_deletesAndLeavesOtherReceiptsUntouched() = runTest {
        val expense = insertExpense()
        val otherExpense = insertExpense()
        val otherReceipt = insertReceipt()
        linkWithDependents(otherReceipt, otherExpense.id)
        val receiptBefore = database.scannedReceiptDao().getById(otherReceipt)!!

        val result = newCoordinator(realLinkService).deleteExpense(expense)

        assertTrue("delete must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertNull("expense gone", database.expenseDao().getById(expense.id))
        assertNotNull("other expense stays", database.expenseDao().getById(otherExpense.id))
        assertEquals(receiptBefore, database.scannedReceiptDao().getById(otherReceipt))
        assertEquals(
            1,
            database.receiptExpenseLinkDao().getLinksForReceipt(otherReceipt).size
        )
        assertEquals(
            otherExpense.id,
            database.warrantyDao().getWarrantyByReceiptId(otherReceipt)!!.expenseId
        )
        assertEquals(
            otherExpense.id,
            database.returnWindowDao().getReturnWindowByReceiptId(otherReceipt)!!.expenseId
        )
    }

    @Test
    fun deleteExpense_unlinkFailure_returnsBoundedFailure_andRollsBackExpenseAndEveryLink() = runTest {
        val expense = insertExpense()
        val receiptA = insertReceipt()
        val receiptB = insertReceipt()
        linkWithDependents(receiptA, expense.id)
        linkWithDependents(receiptB, expense.id)
        val spy = spyk(realLinkService)
        // Fails for B only: whichever receipt is processed first, A's real unlink (if it ran)
        // must be rolled back with the delete.
        coEvery { spy.unlinkReceiptFromExpense(receiptB, expense.id) } returns
            Result.failure(RuntimeException("synthetic unlink failure"))

        val result = newCoordinator(spy).deleteExpense(expense)

        assertTrue("delete must fail", result.isFailure)
        assertEquals("RECEIPT_UNLINK_FAILED", result.exceptionOrNull()?.message)
        assertNotNull("expense still present after rollback", database.expenseDao().getById(expense.id))
        assertEquals(1, database.receiptExpenseLinkDao().getLinksForReceipt(receiptA).size)
        assertEquals(1, database.receiptExpenseLinkDao().getLinksForReceipt(receiptB).size)
        assertEquals(
            MatchStatus.MANUALLY_MATCHED,
            database.scannedReceiptDao().getById(receiptA)!!.matchStatus
        )
        assertEquals(expense.id, database.scannedReceiptDao().getById(receiptA)!!.expenseId)
        assertEquals(expense.id, database.warrantyDao().getWarrantyByReceiptId(receiptA)!!.expenseId)
        assertEquals(
            expense.id,
            database.returnWindowDao().getReturnWindowByReceiptId(receiptA)!!.expenseId
        )
    }

    @Test
    fun deleteExpense_cancellationDuringUnlink_propagates_andNothingIsDeleted() = runTest {
        val expense = insertExpense()
        val receiptId = insertReceipt()
        linkWithDependents(receiptId, expense.id)
        val spy = spyk(realLinkService)
        coEvery { spy.unlinkReceiptFromExpense(any(), any()) } throws
            CancellationException("synthetic cancellation")

        try {
            newCoordinator(spy).deleteExpense(expense)
            fail("CancellationException must propagate, not become Result.failure")
        } catch (e: CancellationException) {
            assertEquals("synthetic cancellation", e.message)
        }

        assertNotNull("expense still present", database.expenseDao().getById(expense.id))
        assertEquals(1, database.receiptExpenseLinkDao().getLinksForReceipt(receiptId).size)
    }

    @Test
    fun deleteExpense_unlinkFailure_logsAndMessageCarryNoMerchantOrReceiptText() = runTest {
        val expense = insertExpense()
        val receiptId = insertReceipt()
        linkWithDependents(receiptId, expense.id)
        val spy = spyk(realLinkService)
        coEvery { spy.unlinkReceiptFromExpense(any(), any()) } returns
            Result.failure(RuntimeException("synthetic unlink failure"))
        val tree = CapturingTree()
        Timber.plant(tree)
        val result = try {
            newCoordinator(spy).deleteExpense(expense)
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(result.isFailure)
        assertTrue("a failure log must exist", tree.lines.isNotEmpty())
        val everything = (tree.lines + listOfNotNull(result.exceptionOrNull()?.toString()))
            .joinToString("\n")
        assertFalse("merchant leaked", everything.contains(secretMerchant))
        assertFalse("receipt OCR text leaked", everything.contains(secretOcr))
    }
}
