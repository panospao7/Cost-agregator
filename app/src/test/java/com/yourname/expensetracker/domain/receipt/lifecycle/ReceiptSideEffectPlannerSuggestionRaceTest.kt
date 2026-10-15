package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.SideEffectExecutionContext
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RACE_NOW = 1_712_000_000_000L
private const val RACE_LATER = RACE_NOW + 60_000L

/**
 * RG07A-1: the planner's Suggested branch must not clobber a concurrent
 * auto-match claim with a stale full-row update.
 *
 * Real in-memory Room database, real [ScannedReceiptDao] and [ReceiptEventDao];
 * only the matcher and unrelated collaborators are mocked. The "concurrent"
 * claim is injected deterministically from inside the matcher call, i.e. exactly
 * between the planner's snapshot read and its suggestion write.
 */
@OptIn(RestrictedExpenseDaoMutation::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptSideEffectPlannerSuggestionRaceTest {

    private lateinit var database: AppDatabase
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var receiptEventDao: ReceiptEventDao
    private lateinit var matcher: ReceiptTransactionMatcher
    private lateinit var planner: ReceiptSideEffectPlanner

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)

    private var expense1Id = 0L
    private var expense2Id = 0L

    @Before
    fun setup() = runTest {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        scannedReceiptDao = database.scannedReceiptDao()
        receiptEventDao = database.receiptEventDao()
        every { timeProvider.now() } returns RACE_NOW
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        matcher = mockk()
        planner = ReceiptSideEffectPlanner(
            autoCreateWarrantyUseCase = mockk<AutoCreateWarrantyFromReceiptUseCase>(relaxed = true),
            categorizeReceiptItemsUseCase = mockk<CategorizeReceiptItemsUseCase>(relaxed = true),
            receiptTransactionMatcher = matcher,
            priceProtectionTracker = mockk<PriceProtectionTracker>(relaxed = true),
            receiptLinkService = mockk<ReceiptLinkService>(relaxed = true),
            scannedReceiptDao = scannedReceiptDao,
            receiptEventDao = receiptEventDao,
            timeProvider = timeProvider,
            writeBarrier = writeBarrier,
            database = database
        )
        expense1Id = insertExpense("Synthetic merchant one")
        expense2Id = insertExpense("Synthetic merchant two")
        assertTrue(expense1Id > 0 && expense2Id > 0 && expense1Id != expense2Id)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun interleave_claim_between_snapshot_and_write_is_not_clobbered() = runTest {
        val receiptId = insertReceipt()
        val suggested = MatchResult.Suggested(expense(expense2Id), 0.7)
        coEvery { matcher.findBestMatch(any(), any()) } coAnswers {
            // Concurrent auto-match lands after the planner read its snapshot
            // and before the planner writes the suggestion.
            assertEquals(
                1,
                scannedReceiptDao.claimForAutoMatch(receiptId, expense1Id, 0.95f, RACE_NOW),
                "RG07A-1: setup claim must affect exactly one row"
            )
            suggested
        }

        val outcome = runMatchAction(receiptId)

        val row = requireNotNull(scannedReceiptDao.getById(receiptId))
        assertEquals(expense1Id, row.expenseId, "RG07A-1: concurrent claim link was reverted by stale suggestion write")
        assertEquals(MatchStatus.AUTO_MATCHED, row.matchStatus, "RG07A-1: claimed status was overwritten")
        assertNull(row.suggestedExpenseId, "RG07A-1: claimed row must not carry a suggestion")
        assertEquals(
            listOf("MATCH_ATTEMPTED"),
            eventTypes(receiptId),
            "RG07A-1: no MATCH_SUGGESTED event may be recorded for a lost race"
        )
        assertTrue(outcome is SideEffectOutcome.Skipped, "RG07A-1: expected Skipped, got $outcome")
        assertEquals(SideEffectSkipReason.ALREADY_PROCESSED, (outcome as SideEffectOutcome.Skipped).reason)
    }

    @Test
    fun control_suggestion_without_interleave_stores_only_suggestion_columns() = runTest {
        val receiptId = insertReceipt()
        val before = requireNotNull(scannedReceiptDao.getById(receiptId))
        coEvery { matcher.findBestMatch(any(), any()) } returns MatchResult.Suggested(expense(expense2Id), 0.7)

        val outcome = runMatchAction(receiptId)

        assertEquals(
            before.copy(
                matchStatus = MatchStatus.SUGGESTED,
                suggestedExpenseId = expense2Id,
                matchConfidence = 0.7f,
                updatedAt = RACE_NOW
            ),
            scannedReceiptDao.getById(receiptId),
            "RG07A-1 control: only suggestion columns may change"
        )
        assertEquals(
            listOf("MATCH_ATTEMPTED", "MATCH_SUGGESTED"),
            eventTypes(receiptId),
            "RG07A-1 control: unexpected event sequence"
        )
        assertTrue(outcome is SideEffectOutcome.Completed, "RG07A-1 control: expected Completed, got $outcome")
    }

    @Test
    fun identical_retry_writes_no_second_suggestion_event() = runTest {
        val receiptId = insertReceipt()
        coEvery { matcher.findBestMatch(any(), any()) } returns MatchResult.Suggested(expense(expense2Id), 0.7)

        val first = runMatchAction(receiptId)
        assertTrue(first is SideEffectOutcome.Completed, "RG07A-1 retry setup: first run expected Completed, got $first")
        val afterFirst = requireNotNull(scannedReceiptDao.getById(receiptId))
        assertEquals(1, suggestedEventCount(receiptId), "RG07A-1 retry setup: first run must record one suggestion event")

        every { timeProvider.now() } returns RACE_LATER
        val second = runMatchAction(receiptId)

        assertEquals(
            1,
            suggestedEventCount(receiptId),
            "RG07A-1: identical retry must not add a second MATCH_SUGGESTED event"
        )
        assertEquals(
            afterFirst,
            scannedReceiptDao.getById(receiptId),
            "RG07A-1: identical retry must not rewrite the row (updatedAt must stay)"
        )
        assertTrue(second is SideEffectOutcome.Skipped, "RG07A-1: expected Skipped on identical retry, got $second")
        assertEquals(SideEffectSkipReason.ALREADY_PROCESSED, (second as SideEffectOutcome.Skipped).reason)
    }

    // --- helpers -----------------------------------------------------------

    private suspend fun runMatchAction(receiptId: Long): SideEffectOutcome {
        val receipt = requireNotNull(scannedReceiptDao.getById(receiptId))
        val batch = planner.planAfterReceiptSaved(
            input = ReceiptSideEffectInput(
                receipt = receipt,
                ephemeralRawOcrText = null,
                rawStorageMode = RawStorageMode.STORE_RAW,
                correlationId = "rg07a-1-correlation",
                autoMatchExistingExpense = true
            )
        )
        val action: PostCommitAction = batch.actions.first { it.name == "receipt_transaction_match" }
        return action.execute(mockk<SideEffectExecutionContext>(relaxed = true))
    }

    private suspend fun eventTypes(receiptId: Long): List<String> =
        receiptEventDao.getEventsForReceipt(receiptId).sortedBy { it.id }.map { it.eventType }

    private suspend fun suggestedEventCount(receiptId: Long): Int =
        eventTypes(receiptId).count { it == "MATCH_SUGGESTED" }

    private fun expense(id: Long) = Expense(
        id = id,
        amount = 12.34,
        currency = "EUR",
        merchant = "Synthetic merchant",
        transactionType = TransactionType.PURCHASE,
        date = RACE_NOW
    )

    private suspend fun insertExpense(merchant: String): Long =
        database.expenseDao().insertAtomic(
            Expense(
                amount = 12.34,
                currency = "EUR",
                merchant = merchant,
                transactionType = TransactionType.PURCHASE,
                date = RACE_NOW
            )
        )

    private suspend fun insertReceipt(): Long {
        val id = scannedReceiptDao.insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "sample",
                parsedTotal = 12.34,
                parsedMerchant = "Store",
                parsedDate = RACE_NOW,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.9f,
                sourceType = "CAMERA",
                documentType = "RETAIL_RECEIPT",
                processingStatus = "PARSED",
                createdAt = RACE_NOW,
                updatedAt = RACE_NOW
            )
        )
        assertTrue(id > 0, "RG07A-1 setup: receipt insert failed")
        return id
    }
}
