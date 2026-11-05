package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.data.database.entity.ReturnWindow
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
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
 * [ReceiptLinkService.unlinkReceiptFromExpense] on a receipt shared by two expenses, real Room.
 *
 * Hand-derived oracles (receipt R linked to expenses X and Y, both primary links):
 *  - unlink X: link X gone, link Y stays, R.expenseId = Y, warranty and return window = Y.
 *  - then unlink Y: R.expenseId = null, match fields cleared, warranty and return window null.
 *  - single link: unlink clears everything as before.
 *  - bank statement: R.expenseId stays null throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReceiptLinkServiceUnlinkSharedReceiptTest {

    private val now = 1_700_000_000_000L

    private lateinit var database: AppDatabase
    private lateinit var linkService: ReceiptLinkService

    private val events = mutableListOf<ReceiptLifecycleEvent>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabaseTestFactory.create(context)
        events.clear()
        val eventWriter = mockk<ReceiptLifecycleEventWriter>(relaxed = true)
        io.mockk.coEvery { eventWriter.write(any(), capture(events)) } returns Unit
        val timeProvider = FakeTimeProvider(now)
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        linkService = ReceiptLinkService(
            database = database,
            receiptExpenseLinkDao = database.receiptExpenseLinkDao(),
            scannedReceiptDao = database.scannedReceiptDao(),
            receiptLifecycleEventWriter = eventWriter,
            receiptItemCategorizationDao = database.receiptItemCategorizationDao(),
            warrantyDao = database.warrantyDao(),
            returnWindowDao = database.returnWindowDao(),
            expenseDao = database.expenseDao(),
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
        val id = database.expenseDao().insert(
            Expense(
                amount = 25.0,
                merchant = "Synthetic Shop",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        assertTrue("fixture: expense insert failed", id > 0L)
        return id
    }

    private suspend fun insertReceipt(documentType: String = "RETAIL_RECEIPT"): Long {
        val id = database.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "synthetic text",
                parsedTotal = 25.0,
                parsedMerchant = "Synthetic Shop",
                parsedDate = now,
                parsedItems = "[]",
                parsedTaxAmount = null,
                confidence = 0.9f,
                createdAt = now,
                updatedAt = now,
                sourceType = "CAMERA",
                documentType = documentType
            )
        )
        assertTrue("fixture: receipt insert failed", id > 0L)
        return id
    }

    /** Primary link rows written straight through the DAO (the legacy / restored shape). */
    private suspend fun linkPrimary(receiptId: Long, expenseId: Long) {
        database.receiptExpenseLinkDao().insert(
            ReceiptExpenseLink(
                receiptId = receiptId,
                expenseId = expenseId,
                linkType = "DIRECT_SAVE",
                confidence = 0.9f,
                source = "TEST",
                createdAt = now,
                createdBy = "test",
                isPrimary = true
            )
        )
    }

    /** Receipt matched to [expenseId], with a warranty, a return window and one item on it. */
    private suspend fun matchWithDependents(receiptId: Long, expenseId: Long) {
        database.scannedReceiptDao().updatePrimaryExpenseId(receiptId, expenseId, now)
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
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun unlinkFirstOfTwoPrimaryLinks_keepsWarrantyReturnWindowAndPrimaryOnTheRemainingExpense() = runTest {
        val x = insertExpense()
        val y = insertExpense()
        val r = insertReceipt()
        linkPrimary(r, x)
        linkPrimary(r, y)
        matchWithDependents(r, x)

        val result = linkService.unlinkReceiptFromExpense(r, x)

        assertTrue("unlink must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val links = database.receiptExpenseLinkDao().getLinksForReceipt(r)
        assertEquals("only the link to Y remains", listOf(y), links.map { it.expenseId })
        val receipt = database.scannedReceiptDao().getById(r)!!
        assertEquals("receipt re-pointed to Y", y, receipt.expenseId)
        assertEquals(y, database.warrantyDao().getWarrantyByReceiptId(r)!!.expenseId)
        assertEquals(y, database.returnWindowDao().getReturnWindowByReceiptId(r)!!.expenseId)
    }

    @Test
    fun unlinkSecondRemainingLink_setsWarrantyReturnWindowAndReceiptToNull_andClearsMatchFields() = runTest {
        val x = insertExpense()
        val y = insertExpense()
        val r = insertReceipt()
        linkPrimary(r, x)
        linkPrimary(r, y)
        matchWithDependents(r, x)
        assertTrue(linkService.unlinkReceiptFromExpense(r, x).isSuccess)

        val result = linkService.unlinkReceiptFromExpense(r, y)

        assertTrue("unlink must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(0, database.receiptExpenseLinkDao().getLinksForReceipt(r).size)
        val receipt = database.scannedReceiptDao().getById(r)!!
        assertNull("receipt expenseId cleared", receipt.expenseId)
        assertEquals(MatchStatus.UNMATCHED, receipt.matchStatus)
        assertNull(receipt.matchConfidence)
        assertNull(receipt.suggestedExpenseId)
        assertNull(database.warrantyDao().getWarrantyByReceiptId(r)!!.expenseId)
        assertNull(database.returnWindowDao().getReturnWindowByReceiptId(r)!!.expenseId)
    }

    @Test
    fun unlinkSingleLink_clearsReceiptWarrantyReturnWindowAndItems_asBefore() = runTest {
        val x = insertExpense()
        val r = insertReceipt()
        linkPrimary(r, x)
        matchWithDependents(r, x)

        val result = linkService.unlinkReceiptFromExpense(r, x)

        assertTrue("unlink must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(0, database.receiptExpenseLinkDao().getLinksForReceipt(r).size)
        val receipt = database.scannedReceiptDao().getById(r)!!
        assertNull(receipt.expenseId)
        assertEquals(MatchStatus.UNMATCHED, receipt.matchStatus)
        assertNull(database.warrantyDao().getWarrantyByReceiptId(r)!!.expenseId)
        assertNull(database.returnWindowDao().getReturnWindowByReceiptId(r)!!.expenseId)
        assertNull(database.receiptItemCategorizationDao().getByReceiptId(r).single().expenseId)
    }

    @Test
    fun unlinkFirstOfTwoLinks_whenRemainingExpenseHasOwnReturnWindow_succeedsAndLeavesThatWindowUntouched() = runTest {
        val x = insertExpense()
        val y = insertExpense()
        val r = insertReceipt()
        val r2 = insertReceipt()
        linkPrimary(r, x)
        linkPrimary(r, y)
        matchWithDependents(r, x)
        val ownWindowId = database.returnWindowDao().insertReturnWindow(
            ReturnWindow(
                receiptId = r2,
                expenseId = y,
                productName = "Other Product",
                merchantName = "Synthetic Shop",
                purchaseDate = now,
                returnDays = 14,
                returnDeadline = now + 3_000_000L,
                createdAt = now,
                updatedAt = now
            )
        )
        val ownBefore = database.returnWindowDao().getReturnWindowByExpenseId(y)!!
        assertEquals(r2, ownBefore.receiptId)

        val result = linkService.unlinkReceiptFromExpense(r, x)

        assertTrue("unlink must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertNull("R's window is detached, not repointed", database.returnWindowDao().getReturnWindowByReceiptId(r)!!.expenseId)
        assertEquals("Y's own window is untouched", ownBefore, database.returnWindowDao().getReturnWindowByExpenseId(y))
        assertTrue(ownWindowId > 0L)
    }

    // ------------------------------------------------- R7 audit message outcome wording

    private fun unlinkedEvents(): List<ReceiptLifecycleEvent> =
        events.filter { it.eventType == "RECEIPT_UNLINKED_FROM_EXPENSE" }

    private fun assertNoRawText(message: String) {
        listOf("Synthetic Shop", "Synthetic Product", "synthetic text", "Other Product", "Item 1").forEach {
            assertTrue("message must not carry raw text '$it': $message", !message.contains(it))
        }
    }

    @Test
    fun unlinkWithRemainingExpense_auditMessageSaysRepointedForWarrantyAndReturnWindow() = runTest {
        val x = insertExpense()
        val y = insertExpense()
        val r = insertReceipt()
        linkPrimary(r, x)
        linkPrimary(r, y)
        matchWithDependents(r, x)

        assertTrue(linkService.unlinkReceiptFromExpense(r, x).isSuccess)

        val message = unlinkedEvents().single().message!!
        assertTrue(message, message.contains("Warranty expenseId repointed to expense $y"))
        assertTrue(message, message.contains("return window expenseId repointed to expense $y"))
        assertTrue(message, !message.contains("cleared"))
        assertNoRawText(message)
    }

    @Test
    fun unlinkWithNoRemainingExpense_auditMessageSaysClearedForWarrantyAndReturnWindow() = runTest {
        val x = insertExpense()
        val r = insertReceipt()
        linkPrimary(r, x)
        matchWithDependents(r, x)

        assertTrue(linkService.unlinkReceiptFromExpense(r, x).isSuccess)

        val message = unlinkedEvents().single().message!!
        assertTrue(message, message.contains("Warranty expenseId cleared"))
        assertTrue(message, message.contains("return window expenseId cleared"))
        assertTrue(message, !message.contains("repointed"))
        assertNoRawText(message)
    }

    @Test
    fun unlinkWithReturnWindowConflict_auditMessageSaysClearedForTheWindowOnly() = runTest {
        val x = insertExpense()
        val y = insertExpense()
        val r = insertReceipt()
        val r2 = insertReceipt()
        linkPrimary(r, x)
        linkPrimary(r, y)
        matchWithDependents(r, x)
        database.returnWindowDao().insertReturnWindow(
            ReturnWindow(
                receiptId = r2,
                expenseId = y,
                productName = "Other Product",
                merchantName = "Synthetic Shop",
                purchaseDate = now,
                returnDays = 14,
                returnDeadline = now + 3_000_000L,
                createdAt = now,
                updatedAt = now
            )
        )

        assertTrue(linkService.unlinkReceiptFromExpense(r, x).isSuccess)

        val message = unlinkedEvents().single().message!!
        assertTrue(message, message.contains("Warranty expenseId repointed to expense $y"))
        assertTrue(message, message.contains("return window expenseId cleared"))
        assertNoRawText(message)
    }

    @Test
    fun unlinkBankStatement_keepsReceiptExpenseIdNull_andRemovesTheLink() = runTest {
        val x = insertExpense()
        val y = insertExpense()
        val r = insertReceipt(documentType = "BANK_STATEMENT")
        linkPrimary(r, x)
        linkPrimary(r, y)
        val before = database.scannedReceiptDao().getById(r)!!
        assertNull("fixture: bank statement receipt has no expenseId", before.expenseId)

        val result = linkService.unlinkReceiptFromExpense(r, x)

        assertTrue("unlink must succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val after = database.scannedReceiptDao().getById(r)!!
        assertNull("bank statement expenseId stays null", after.expenseId)
        assertEquals(before.matchStatus, after.matchStatus)
        assertEquals(listOf(y), database.receiptExpenseLinkDao().getLinksForReceipt(r).map { it.expenseId })
    }

    @Test
    fun unlinkFirstOfTwoPrimaryLinks_itemExpenseIdIsNulledEvenThoughYRemains_knownLimitation() = runTest {
        val x = insertExpense()
        val y = insertExpense()
        val r = insertReceipt()
        linkPrimary(r, x)
        linkPrimary(r, y)
        matchWithDependents(r, x)

        assertTrue(linkService.unlinkReceiptFromExpense(r, x).isSuccess)

        // KNOWN LIMITATION (recorded, not endorsed): item expenseId is cleared although Y remains.
        // If this is fixed to follow the remaining primary link, change this assertion deliberately.
        assertNull(database.receiptItemCategorizationDao().getByReceiptId(r).single().expenseId)
    }
}
