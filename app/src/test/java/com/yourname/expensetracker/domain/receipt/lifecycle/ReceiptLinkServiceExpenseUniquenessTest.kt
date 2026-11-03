package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P-03-1 (one non-statement receipt per expense) and the S2 claim interplay, on REAL Room
 * (production in-memory builder, foreign keys enforced), REAL [ReceiptLinkService],
 * [RoomDomainTransactionRunner] and DAOs. Only the lifecycle event writer, the category port,
 * the source-link writer and the write barrier are peripheral test doubles.
 *
 * Hand-derived oracles (all ids synthetic):
 *  - R1 (retail) linked to E; R2 (retail) to E -> Result.failure(ExpenseAlreadyLinkedException),
 *    R2 row byte-identical, 0 links for R2, E keeps exactly one link, exactly one lifecycle event.
 *  - Statement receipts (BANK_STATEMENT) are exempt in both directions.
 *  - Same receipt + same expense stays the plain duplicate failure, never ExpenseAlreadyLinked.
 *  - allowRelink only lifts the per-receipt check, never the per-expense check.
 *  - requireUnmatchedClaim + REVIEW_APPROVAL/MANUALLY_MATCHED -> row ends MANUALLY_MATCHED.
 *  - A failed claim (receipt already resolved) -> failure, row identical, no link row, no event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(RestrictedExpenseDaoMutation::class)
class ReceiptLinkServiceExpenseUniquenessTest {

    private val now = 1_700_000_000_000L
    private val retail = "RETAIL_RECEIPT"
    private val statement = ReceiptDocumentType.BANK_STATEMENT.name

    private lateinit var database: AppDatabase
    private lateinit var service: ReceiptLinkService
    private lateinit var eventWriter: ReceiptLifecycleEventWriter

    private val receiptDao: ScannedReceiptDao get() = database.scannedReceiptDao()
    private val linkDao: ReceiptExpenseLinkDao get() = database.receiptExpenseLinkDao()
    private val expenseDao: ExpenseDao get() = database.expenseDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabaseTestFactory.create(context)
        val timeProvider = FakeTimeProvider(now)
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        eventWriter = mockk(relaxed = true)
        service = ReceiptLinkService(
            database = database,
            receiptExpenseLinkDao = linkDao,
            scannedReceiptDao = receiptDao,
            receiptLifecycleEventWriter = eventWriter,
            receiptItemCategorizationDao = database.receiptItemCategorizationDao(),
            warrantyDao = database.warrantyDao(),
            returnWindowDao = database.returnWindowDao(),
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode),
            sourceLinkWriter = mockk(relaxed = true),
            categoryAssignmentPort = mockk(relaxed = true),
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    // ---------------------------------------------------------------- fixtures

    private suspend fun insertExpense(): Long {
        val id = expenseDao.insert(
            Expense(
                amount = 25.0,
                merchant = "Synthetic Merchant",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        assertTrue("fixture: expense insert failed", id > 0L)
        return id
    }

    private suspend fun insertReceipt(
        documentType: String = retail,
        matchStatus: MatchStatus = MatchStatus.UNMATCHED,
        expenseId: Long? = null,
        suggestedExpenseId: Long? = null
    ): Long {
        val id = receiptDao.insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "synthetic-" + System.nanoTime(),
                parsedTotal = 12.0,
                parsedMerchant = "Synthetic Merchant",
                parsedDate = now,
                parsedItems = "[]",
                parsedTaxAmount = null,
                confidence = 0.9f,
                expenseId = expenseId,
                suggestedExpenseId = suggestedExpenseId,
                matchConfidence = if (expenseId != null) 0.9f else null,
                matchStatus = matchStatus,
                createdAt = now,
                updatedAt = now,
                sourceType = "CAMERA",
                documentType = documentType
            )
        )
        assertTrue("fixture: receipt insert failed", id > 0L)
        return id
    }

    private suspend fun link(
        receiptId: Long,
        expenseId: Long,
        allowRelink: Boolean = false
    ): Result<ReceiptExpenseLink> = service.linkReceiptToExpense(
        receiptId = receiptId,
        expenseId = expenseId,
        linkType = "DIRECT_SAVE",
        source = "TEST",
        allowRelink = allowRelink,
        writeSourceLink = false
    )

    // ------------------------------------------------------------------- P-03-1

    @Test
    fun INV_P031_twoNonStatementReceiptsToOneExpense_secondFailsAndNothingIsWritten() = runTest {
        val expense = insertExpense()
        val first = insertReceipt()
        val second = insertReceipt()

        assertTrue("control: first link must succeed", link(first, expense).isSuccess)
        coVerify(exactly = 1) { eventWriter.write(any(), any()) }
        val secondBefore = receiptDao.getById(second)

        val result = link(second, expense)

        assertTrue("second non-statement receipt must be refused", result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(
            "failure must be ExpenseAlreadyLinkedException (was ${failure?.javaClass?.simpleName})",
            failure is ExpenseAlreadyLinkedException
        )
        assertTrue("message carries the expense id", failure!!.message!!.contains(expense.toString()))
        assertEquals("second receipt row untouched", secondBefore, receiptDao.getById(second))
        assertNull(receiptDao.getById(second)!!.expenseId)
        assertEquals(0, linkDao.getLinksForReceipt(second).size)
        val expenseLinks: List<ReceiptExpenseLink> = linkDao.getLinksForExpense(expense)
        assertEquals(listOf(first), expenseLinks.map { it.receiptId })
        assertEquals(expense, receiptDao.getById(first)!!.expenseId)
        coVerify(exactly = 1) { eventWriter.write(any(), any()) }
    }

    @Test
    fun INV_P031_differentExpensesForDifferentNonStatementReceipts_stillLink() = runTest {
        val e1 = insertExpense()
        val e2 = insertExpense()
        val r1 = insertReceipt()
        val r2 = insertReceipt()

        assertTrue(link(r1, e1).isSuccess)
        assertTrue(link(r2, e2).isSuccess)

        assertEquals(e1, receiptDao.getById(r1)!!.expenseId)
        assertEquals(e2, receiptDao.getById(r2)!!.expenseId)
    }

    @Test
    fun INV_P031_statementReceiptCanLinkManyExpenses() = runTest {
        val statementId = insertReceipt(documentType = statement)
        val expenses = listOf(insertExpense(), insertExpense(), insertExpense())

        expenses.forEach { assertTrue("statement link to $it", link(statementId, it).isSuccess) }

        assertEquals(
            expenses.toSet(),
            linkDao.getLinksForReceipt(statementId).map { it.expenseId }.toSet()
        )
    }

    @Test
    fun INV_P031_twoStatementReceiptsMayShareOneExpense() = runTest {
        val expense = insertExpense()
        val s1 = insertReceipt(documentType = statement)
        val s2 = insertReceipt(documentType = statement)

        assertTrue(link(s1, expense).isSuccess)
        assertTrue(link(s2, expense).isSuccess)

        assertEquals(2, linkDao.getLinksForExpense(expense).size)
    }

    @Test
    fun INV_P031_statementLinkOnExpenseDoesNotBlockNonStatementReceipt() = runTest {
        val expense = insertExpense()
        val statementId = insertReceipt(documentType = statement)
        val retailId = insertReceipt()

        assertTrue(link(statementId, expense).isSuccess)
        val result = link(retailId, expense)

        assertTrue("non-statement receipt must still link: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(2, linkDao.getLinksForExpense(expense).size)
        assertEquals(expense, receiptDao.getById(retailId)!!.expenseId)
    }

    @Test
    fun INV_P031_nonStatementLinkOnExpenseDoesNotBlockStatementReceipt() = runTest {
        val expense = insertExpense()
        val retailId = insertReceipt()
        val statementId = insertReceipt(documentType = statement)

        assertTrue(link(retailId, expense).isSuccess)
        val result = link(statementId, expense)

        assertTrue("statement receipt must still link: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(2, linkDao.getLinksForExpense(expense).size)
    }

    @Test
    fun INV_P031_sameReceiptAndExpense_staysDuplicate_notExpenseAlreadyLinked() = runTest {
        val expense = insertExpense()
        val receipt = insertReceipt()
        assertTrue(link(receipt, expense).isSuccess)

        val plain = link(receipt, expense)
        val relink = link(receipt, expense, allowRelink = true)

        assertTrue(plain.isFailure)
        assertTrue(
            "plain repeat must not be ExpenseAlreadyLinked",
            plain.exceptionOrNull() !is ExpenseAlreadyLinkedException
        )
        assertTrue(relink.isFailure)
        assertTrue(
            "same receipt/expense with allowRelink must stay a duplicate, not ExpenseAlreadyLinked",
            relink.exceptionOrNull() !is ExpenseAlreadyLinkedException
        )
        assertTrue(relink.exceptionOrNull()!!.message!!.startsWith("Duplicate link"))
        assertEquals(1, linkDao.getLinksForExpense(expense).size)
    }

    @Test
    fun INV_P031_allowRelinkDoesNotBypassTheExpenseCheck() = runTest {
        val expense = insertExpense()
        val first = insertReceipt()
        val second = insertReceipt()
        assertTrue(link(first, expense).isSuccess)
        val secondBefore = receiptDao.getById(second)

        val result = link(second, expense, allowRelink = true)

        assertTrue(result.exceptionOrNull() is ExpenseAlreadyLinkedException)
        assertEquals(secondBefore, receiptDao.getById(second))
        assertEquals(0, linkDao.getLinksForReceipt(second).size)
        assertEquals(1, linkDao.getLinksForExpense(expense).size)
    }

    @Test
    fun INV_P031_allowRelinkStillRelinksOneReceiptToAFreeExpense() = runTest {
        val e1 = insertExpense()
        val e2 = insertExpense()
        val receipt = insertReceipt()
        assertTrue(link(receipt, e1).isSuccess)

        assertTrue(link(receipt, e2, allowRelink = true).isSuccess)
    }

    // ------------------------------------------------- S2 claim flag interplay

    @Test
    fun INV_P032_approveWithClaim_keepsManuallyMatched_notAutoMatched() = runTest {
        val expense = insertExpense()
        val receipt = insertReceipt(matchStatus = MatchStatus.SUGGESTED, suggestedExpenseId = expense)

        val result = service.linkReceiptToExpense(
            receiptId = receipt,
            expenseId = expense,
            linkType = "REVIEW_APPROVAL",
            source = "TEST",
            matchStatus = MatchStatus.MANUALLY_MATCHED,
            writeSourceLink = false,
            requireUnmatchedClaim = true
        )

        assertTrue("approve must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val row = receiptDao.getById(receipt)!!
        assertEquals(MatchStatus.MANUALLY_MATCHED, row.matchStatus)
        assertEquals(expense, row.expenseId)
        assertNull("suggestion cleared", row.suggestedExpenseId)
        assertEquals(1, linkDao.getLinksForReceipt(receipt).size)
    }

    @Test
    fun INV_P032_autoMatchWithClaim_staysAutoMatched() = runTest {
        val expense = insertExpense()
        val receipt = insertReceipt()

        val result = service.linkReceiptToExpense(
            receiptId = receipt,
            expenseId = expense,
            linkType = "AUTO_MATCH",
            source = "TEST",
            confidence = 0.97f,
            writeSourceLink = false,
            requireUnmatchedClaim = true
        )

        assertTrue(result.isSuccess)
        val row = receiptDao.getById(receipt)!!
        assertEquals(MatchStatus.AUTO_MATCHED, row.matchStatus)
        assertEquals(expense, row.expenseId)
        assertEquals(0.97f, row.matchConfidence!!, 0.0001f)
    }

    @Test
    fun INV_P032_failedClaim_writesNothingFurther_forEveryResolvedStatus() = runTest {
        val resolvedStatuses = listOf(
            MatchStatus.AUTO_MATCHED,
            MatchStatus.MANUALLY_MATCHED,
            MatchStatus.REJECTED
        )
        for (status in resolvedStatuses) {
            val e1 = insertExpense()
            val e2 = insertExpense()
            val receipt = insertReceipt(matchStatus = status, expenseId = e1)
            val before = receiptDao.getById(receipt)

            val result = service.linkReceiptToExpense(
                receiptId = receipt,
                expenseId = e2,
                linkType = "REVIEW_APPROVAL",
                source = "TEST",
                matchStatus = MatchStatus.MANUALLY_MATCHED,
                writeSourceLink = false,
                requireUnmatchedClaim = true
            )

            assertTrue("claim on $status must fail", result.isFailure)
            assertTrue(
                "failure must be ReceiptAlreadyClaimedException for $status (was ${result.exceptionOrNull()})",
                result.exceptionOrNull() is ReceiptAlreadyClaimedException
            )
            assertEquals("row must be unchanged for $status", before, receiptDao.getById(receipt))
            assertEquals("no link row for $status", 0, linkDao.getLinksForReceipt(receipt).size)
            assertEquals("no link on target expense for $status", 0, linkDao.getLinksForExpense(e2).size)
        }
        coVerify(exactly = 0) { eventWriter.write(any(), any()) }
    }
}
