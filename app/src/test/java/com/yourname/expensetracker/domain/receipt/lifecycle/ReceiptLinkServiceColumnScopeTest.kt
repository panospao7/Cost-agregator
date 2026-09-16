package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimeProvider
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
 * RP-12 12b (P3-004): interleaving database test required by the plan.
 *
 * A retention purge (rawOcrText cleared, rawOcrTextPurgedAt stamped) and an
 * unrelated concurrent categorization-status change commit BEFORE the link /
 * unlink transaction runs. Because the link service now reads eligibility
 * INSIDE the transaction and writes only column-scoped match/link fields —
 * never a stale full-row update — the purged fields must stay purged and the
 * unrelated concurrent status must be retained.
 *
 * Uses a real in-memory Room [AppDatabase] with the real
 * [RoomDomainTransactionRunner] (no relaxed-mock transaction stubs), mirroring
 * the RetentionTargetPurgeTest harness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReceiptLinkServiceColumnScopeTest {

    private lateinit var database: AppDatabase

    private val now = 1_700_000_000_000L
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = now }

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private lateinit var linkService: ReceiptLinkService
    private lateinit var matchLifecycleService: ReceiptMatchLifecycleService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // NORMAL mode: the canonical write barrier allows plain lifecycle writes.
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val writeBarrier = DatabaseWriteBarrier(maintenanceMode)

        linkService = ReceiptLinkService(
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
            sourceLinkWriter = mockk(relaxed = true),
            categoryAssignmentPort = mockk(relaxed = true),
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        )
        matchLifecycleService = ReceiptMatchLifecycleService(
            database = database,
            scannedReceiptDao = database.scannedReceiptDao(),
            receiptEventDao = database.receiptEventDao(),
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun receipt(createdAt: Long, rawOcrText: String = "OCR BODY TEXT") = ScannedReceipt(
        imagePath = null,
        rawOcrText = rawOcrText,
        parsedTotal = 12.5,
        parsedMerchant = "MERCHANT NAME",
        parsedDate = createdAt,
        parsedItems = """[{"name":"item","totalPrice":12.5}]""",
        parsedTaxAmount = null,
        confidence = 0.9f,
        createdAt = createdAt,
        updatedAt = createdAt,
        sourceType = "CAMERA",
        documentType = "RETAIL_RECEIPT"
    )

    private fun expense(merchant: String) = Expense(
        amount = 12.5,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = now
    )

    @Test
    fun `purge and categorization committed before link survive the link write`() = runTest {
        val dao = database.scannedReceiptDao()
        val receiptId = dao.insert(
            receipt(createdAt = now - 10_000).copy(matchStatus = MatchStatus.UNMATCHED)
        )
        val expenseId = database.expenseDao().insert(expense("Groceries"))

        // 1. Retention purge commits before the link: raw text cleared + stamped.
        dao.purgeRawOcrText(beforeMs = now, nowMs = now + 1)
        dao.updateRawOcrTextPurged(id = receiptId, rawOcrTextPurgedAt = now + 1)

        // 2. Unrelated concurrent categorization commits before the link.
        dao.updateCategorizationStatus(receiptId, "ANALYZING")

        // 3. Link runs AFTER both committed: fresh in-transaction read +
        //    column-scoped match/link write only.
        val result = linkService.linkReceiptToExpense(
            receiptId = receiptId,
            expenseId = expenseId,
            linkType = "DIRECT_SAVE",
            source = "TEST"
        )
        assertTrue("link should succeed: ${result.exceptionOrNull()}", result.isSuccess)

        val after = dao.getById(receiptId)!!
        // Purged fields STAY purged — no privacy resurrection.
        assertEquals("", after.rawOcrText)
        assertEquals(now + 1, after.rawOcrTextPurgedAt)
        assertEquals("MERCHANT NAME", after.parsedMerchant)
        // Unrelated concurrent field retained.
        assertEquals(com.yourname.expensetracker.data.database.entity.CategorizationStatus.ANALYZING, after.itemCategorizationStatus)
        // Link fields applied.
        assertEquals(expenseId, after.expenseId)
        assertEquals(MatchStatus.MANUALLY_MATCHED, after.matchStatus)
        assertNull(after.suggestedExpenseId)
        assertEquals(1, database.receiptExpenseLinkDao().getLinksForReceipt(receiptId).size)
    }

    @Test
    fun `purge committed before unlink survives the unlink reset`() = runTest {
        val dao = database.scannedReceiptDao()
        val receiptId = dao.insert(receipt(createdAt = now - 10_000))
        val expenseId = database.expenseDao().insert(expense("Groceries"))
        assertTrue(
            linkService.linkReceiptToExpense(receiptId, expenseId, "DIRECT_SAVE", "TEST").isSuccess
        )

        // Retention purge commits before the unlink.
        dao.purgeRawOcrText(beforeMs = now, nowMs = now + 1)
        dao.updateRawOcrTextPurged(id = receiptId, rawOcrTextPurgedAt = now + 1)

        val result = linkService.unlinkReceiptFromExpense(receiptId, expenseId)
        assertTrue("unlink should succeed: ${result.exceptionOrNull()}", result.isSuccess)

        val after = dao.getById(receiptId)!!
        // Purged fields STAY purged.
        assertEquals("", after.rawOcrText)
        assertEquals(now + 1, after.rawOcrTextPurgedAt)
        // Unlink reset applied via column-scoped write.
        assertNull(after.expenseId)
        assertEquals(MatchStatus.UNMATCHED, after.matchStatus)
        assertNull(after.matchConfidence)
        assertNull(after.suggestedExpenseId)
    }

    @Test
    fun `match suggestion write is column-scoped and keeps purged fields purged`() = runTest {
        val dao = database.scannedReceiptDao()
        val receiptId = dao.insert(receipt(createdAt = now - 10_000))
        val expenseId = database.expenseDao().insert(expense("Groceries"))

        // Retention purge commits before the suggestion.
        dao.purgeRawOcrText(beforeMs = now, nowMs = now + 1)
        dao.updateRawOcrTextPurged(id = receiptId, rawOcrTextPurgedAt = now + 1)

        matchLifecycleService.saveMatchSuggestion(
            receiptId = receiptId,
            suggestedExpenseId = expenseId,
            confidence = 0.75
        )

        val after = dao.getById(receiptId)!!
        assertEquals("", after.rawOcrText)
        assertEquals(now + 1, after.rawOcrTextPurgedAt)
        assertEquals("MERCHANT NAME", after.parsedMerchant)
        assertEquals(expenseId, after.suggestedExpenseId)
        assertEquals(MatchStatus.SUGGESTED, after.matchStatus)
        assertEquals(0.75f, after.matchConfidence!!, 0.0001f)
    }
}
