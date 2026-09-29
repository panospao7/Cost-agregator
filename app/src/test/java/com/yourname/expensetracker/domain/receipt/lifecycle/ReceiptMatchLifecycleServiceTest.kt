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
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * DB-backed tests for the P9-P1-08 diagnostics writers on
 * [ReceiptMatchLifecycleService].
 *
 * Each writer is exercised through a real in-memory Room database (matching the
 * [com.yourname.expensetracker.data.database.dao.ReceiptEventDaoTest] convention)
 * with a relaxed-but-stubbed [DatabaseWriteBarrier] and a fixed [TimeProvider],
 * asserting that a [com.yourname.expensetracker.data.database.entity.ReceiptEvent]
 * row with the correct eventType and receiptId is persisted via the real DAO.
 */
@OptIn(RestrictedExpenseDaoMutation::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptMatchLifecycleServiceTest {

    private lateinit var database: AppDatabase
    private lateinit var scannedReceiptDao: ScannedReceiptDao
    private lateinit var receiptEventDao: ReceiptEventDao
    private lateinit var service: ReceiptMatchLifecycleService

    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        scannedReceiptDao = database.scannedReceiptDao()
        receiptEventDao = database.receiptEventDao()
        every { timeProvider.now() } returns FIXED_NOW
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        service = ReceiptMatchLifecycleService(
            database = database,
            scannedReceiptDao = scannedReceiptDao,
            receiptEventDao = receiptEventDao,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun saveSuggestionIsConditionalIdempotentAndColumnScoped() = runTest {
        val receiptId = insertReceipt()
        val before = requireNotNull(scannedReceiptDao.getById(receiptId))

        assertTrue(service.saveMatchSuggestion(receiptId, 901L, 0.7))
        assertEquals(before.copy(
            matchStatus = MatchStatus.SUGGESTED,
            suggestedExpenseId = 901L,
            matchConfidence = 0.7f,
            updatedAt = FIXED_NOW
        ), scannedReceiptDao.getById(receiptId))
        assertFalse(service.saveMatchSuggestion(receiptId, 901L, 0.7))
        assertEquals(1, receiptEventDao.getEventsForReceipt(receiptId).size)

        assertTrue(service.saveMatchSuggestion(receiptId, 902L, 0.8))
        assertEquals(902L, scannedReceiptDao.getById(receiptId)!!.suggestedExpenseId)
        assertEquals(2, receiptEventDao.getEventsForReceipt(receiptId).size)
    }

    @Test
    fun rejectedReceiptCannotBeResuggested() = runTest {
        val receiptId = insertReceipt()
        service.rejectAllSuggestions(receiptId)
        val rejected = scannedReceiptDao.getById(receiptId)

        assertFalse(service.saveMatchSuggestion(receiptId, 901L, 0.7))

        assertEquals(rejected, scannedReceiptDao.getById(receiptId))
        assertEquals(listOf("MATCH_REJECTED"), receiptEventDao.getEventsForReceipt(receiptId).map { it.eventType })
    }

    @Test
    fun linkedReceiptsCannotBeDowngradedByASuggestion() = runTest {
        val expenseId = database.expenseDao().insertAtomic(Expense(
            amount = 12.34,
            currency = "EUR",
            merchant = "Matched merchant",
            transactionType = TransactionType.PURCHASE,
            date = FIXED_NOW
        ))
        assertTrue(expenseId > 0)
        for (status in listOf(MatchStatus.AUTO_MATCHED, MatchStatus.MANUALLY_MATCHED, MatchStatus.UNMATCHED)) {
            val receiptId = insertReceipt()
            scannedReceiptDao.updateLinkTargets(receiptId, expenseId, status.name, 0.9f, FIXED_NOW)
            val linked = scannedReceiptDao.getById(receiptId)

            assertFalse(service.saveMatchSuggestion(receiptId, 902L, 0.7))

            assertEquals(linked, scannedReceiptDao.getById(receiptId))
            assertTrue(receiptEventDao.getEventsForReceipt(receiptId).isEmpty())
        }
    }

    @Test
    fun missingReceiptDoesNotCreateSuggestionEvent() = runTest {
        assertFalse(service.saveMatchSuggestion(Long.MAX_VALUE, 901L, 0.7))
        assertTrue(receiptEventDao.getEventsForReceipt(Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun nullableConfidenceSuggestionCasIsIdempotent() = runTest {
        val receiptId = insertReceipt()
        assertEquals(1, scannedReceiptDao.updateMatchSuggestion(receiptId, 901L, null, FIXED_NOW))
        assertEquals(0, scannedReceiptDao.updateMatchSuggestion(receiptId, 901L, null, FIXED_NOW))
        assertEquals(1, scannedReceiptDao.updateMatchSuggestion(receiptId, 901L, 0.7f, FIXED_NOW))
    }

    @Test
    fun eventFailureRollsBackSuggestion() = runTest {
        val receiptId = insertReceipt()
        val before = scannedReceiptDao.getById(receiptId)
        val failure = IdentityWriteFailure("TEST_EVENT_WRITE_FAILURE")
        val failingEvents = mockk<ReceiptEventDao>()
        coEvery { failingEvents.insert(any()) } throws failure
        val failingService = ReceiptMatchLifecycleService(
            database, scannedReceiptDao, failingEvents, writeBarrier, timeProvider
        )

        val thrown = assertFailsWith<IllegalStateException> {
            failingService.saveMatchSuggestion(receiptId, 901L, 0.7)
        }

        assertSame(failure, thrown)
        assertEquals(before, scannedReceiptDao.getById(receiptId))
        assertTrue(receiptEventDao.getEventsForReceipt(receiptId).isEmpty())
    }

    @Test
    fun suspendedEventFailureRollsBackSuggestion() = runTest {
        val receiptId = insertReceipt()
        val before = scannedReceiptDao.getById(receiptId)
        val failure = IdentityWriteFailure("TEST_SUSPENDED_EVENT_WRITE_FAILURE")
        val failingEvents = mockk<ReceiptEventDao>()
        coEvery { failingEvents.insert(any()) } coAnswers {
            yield()
            throw failure
        }
        val failingService = ReceiptMatchLifecycleService(
            database, scannedReceiptDao, failingEvents, writeBarrier, timeProvider
        )

        val thrown = assertFailsWith<IllegalStateException> {
            failingService.saveMatchSuggestion(receiptId, 901L, 0.7)
        }

        assertSame(failure, thrown)
        assertEquals(before, scannedReceiptDao.getById(receiptId))
        assertTrue(receiptEventDao.getEventsForReceipt(receiptId).isEmpty())
    }

    @Test
    fun eventCancellationPreservesIdentityAndRollsBackSuggestion() = runTest {
        val receiptId = insertReceipt()
        val before = scannedReceiptDao.getById(receiptId)
        val cancellation = IdentityCancellation("TEST_EVENT_WRITE_CANCELLED")
        val failingEvents = mockk<ReceiptEventDao>()
        coEvery { failingEvents.insert(any()) } coAnswers {
            yield()
            throw cancellation
        }
        val failingService = ReceiptMatchLifecycleService(
            database, scannedReceiptDao, failingEvents, writeBarrier, timeProvider
        )

        val thrown = assertFailsWith<CancellationException> {
            failingService.saveMatchSuggestion(receiptId, 901L, 0.7)
        }

        assertSame(cancellation, thrown)
        assertEquals(before, scannedReceiptDao.getById(receiptId))
        assertTrue(receiptEventDao.getEventsForReceipt(receiptId).isEmpty())
    }

    @Test
    fun callerCancellationDuringEventWriteRollsBackSuggestion() = runTest {
        val receiptId = insertReceipt()
        val before = scannedReceiptDao.getById(receiptId)
        val eventEntered = CompletableDeferred<Unit>()
        val suspendedEvents = mockk<ReceiptEventDao>()
        coEvery { suspendedEvents.insert(any()) } coAnswers {
            eventEntered.complete(Unit)
            awaitCancellation()
        }
        val suspendedService = ReceiptMatchLifecycleService(
            database, scannedReceiptDao, suspendedEvents, writeBarrier, timeProvider
        )
        val pending = async {
            suspendedService.saveMatchSuggestion(receiptId, 901L, 0.7)
        }

        try {
            eventEntered.await()
            pending.cancel(IdentityCancellation("TEST_CALLER_CANCELLED"))
            pending.join()

            assertTrue(pending.isCancelled)
            assertFailsWith<CancellationException> { pending.await() }
            assertEquals(before, scannedReceiptDao.getById(receiptId))
            assertTrue(receiptEventDao.getEventsForReceipt(receiptId).isEmpty())
        } finally {
            pending.cancel()
            pending.join()
        }
    }

    // Match the existing backup-test convention: exclude coroutine debug
    // stacktrace copies so assertSame detects application wrapping/replacement.
    // Do not disable recovery globally or relax the identity/rollback assertions.
    private class IdentityWriteFailure(message: String) :
        IllegalStateException(message), CopyableThrowable<IdentityWriteFailure> {
        override fun createCopy(): IdentityWriteFailure? = null
    }

    private class IdentityCancellation(message: String) :
        CancellationException(message), CopyableThrowable<IdentityCancellation> {
        override fun createCopy(): IdentityCancellation? = null
    }

    private suspend fun insertReceipt(
        documentType: String = "RETAIL_RECEIPT",
        processingStatus: String = "PARSED",
        sourceType: String = "CAMERA"
    ): Long {
        return scannedReceiptDao.insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "sample",
                parsedTotal = 12.34,
                parsedMerchant = "Store",
                parsedDate = FIXED_NOW,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 0.9f,
                sourceType = sourceType,
                documentType = documentType,
                processingStatus = processingStatus,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW
            )
        )
    }

    @Test
    fun `recordMatchAttempted writes a MATCH_ATTEMPTED event`() = runTest {
        val receiptId = insertReceipt()

        service.recordMatchAttempted(receiptId, lookbackDays = 7)

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("MATCH_ATTEMPTED", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
        assertEquals(FIXED_NOW, events[0].occurredAt)
        assertEquals("system:match_lifecycle", events[0].actor)
    }

    @Test
    fun `recordMatchNotFound writes a MATCH_NOT_FOUND event`() = runTest {
        val receiptId = insertReceipt()

        service.recordMatchNotFound(receiptId)

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("MATCH_NOT_FOUND", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
    }

    @Test
    fun `recordMatchSkippedDocumentType writes a MATCH_SKIPPED_DOCUMENT_TYPE event`() = runTest {
        val receiptId = insertReceipt(documentType = "BANK_STATEMENT")

        service.recordMatchSkippedDocumentType(receiptId, documentType = "BANK_STATEMENT")

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("MATCH_SKIPPED_DOCUMENT_TYPE", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
        assertEquals("BANK_STATEMENT", events[0].documentType)
    }

    @Test
    fun `recordAutoMatchLinkFailed writes an AUTO_MATCH_LINK_FAILED event with reason`() = runTest {
        val receiptId = insertReceipt()

        service.recordAutoMatchLinkFailed(
            receiptId = receiptId,
            expenseId = 555L,
            reason = "RECEIPT_ALREADY_LINKED"
        )

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("AUTO_MATCH_LINK_FAILED", events[0].eventType)
        assertEquals(receiptId, events[0].receiptId)
        assertEquals("RECEIPT_ALREADY_LINKED", events[0].errorDetails)
        assertTrue(events[0].message!!.contains("555"))
    }

    @Test
    fun `diagnostics writers check the write barrier`() = runTest {
        val receiptId = insertReceipt()

        service.recordMatchAttempted(receiptId, lookbackDays = 7)

        io.mockk.verify {
            writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.recordMatchAttempted")
        }
    }

    @Test
    fun `diagnostics writers no-op for unknown receipt`() = runTest {
        // No receipt inserted; getById returns null -> transaction returns early.
        service.recordMatchAttempted(999L, lookbackDays = 7)
        service.recordMatchNotFound(999L)
        service.recordMatchSkippedDocumentType(999L, documentType = "BANK_STATEMENT")
        service.recordAutoMatchLinkFailed(999L, expenseId = 1L, reason = "x")

        assertEquals(0, receiptEventDao.getEventsForReceipt(999L).size)
    }

    @Test
    fun `recordAutoMatchLinkFailed tolerates null expenseId`() = runTest {
        val receiptId = insertReceipt()

        service.recordAutoMatchLinkFailed(receiptId, expenseId = null, reason = null)

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("AUTO_MATCH_LINK_FAILED", events[0].eventType)
        // Null reason is sanitized to the fallback diagnostic code
        assertEquals("WORKER_UNHANDLED_EXCEPTION", events[0].errorDetails)
    }

    // ── PR12M-1: defensive sanitization boundaries ───────────────────────────

    @Test
    fun `recordAutoMatchLinkFailed_sanitizes_raw_reason`() = runTest {
        val receiptId = insertReceipt()

        // Pass a path-like string that is NOT a valid reason code;
        // it must be sanitized to the fallback code.
        service.recordAutoMatchLinkFailed(
            receiptId = receiptId,
            expenseId = 555L,
            reason = "C:\\Users\\foo\\receipt.txt",
            errorClass = "java.lang.RuntimeException"
        )

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("AUTO_MATCH_LINK_FAILED", events[0].eventType)
        // The path-like reason is replaced by the fallback diagnostic code
        assertEquals("code=WORKER_UNHANDLED_EXCEPTION, class=java.lang.RuntimeException", events[0].errorDetails)
    }

    @Test
    fun `recordNotificationSuppressed_sanitizes_reason_code`() = runTest {
        val receiptId = insertReceipt()

        // Pass an invalid reason code with characters that are not
        // alphanumeric or underscore; it must be sanitized.
        service.recordNotificationSuppressed(
            receiptId = receiptId,
            expenseId = 555L,
            reasonCode = "some invalid code with spaces and \n newlines",
            errorClass = null
        )

        val events = receiptEventDao.getEventsForReceipt(receiptId)
        assertEquals(1, events.size)
        assertEquals("NOTIFICATION_SUPPRESSED", events[0].eventType)
        // The invalid reason code is sanitized to the fallback code
        assertEquals("WORKER_UNHANDLED_EXCEPTION", events[0].errorDetails)
        // The message also uses the sanitized value (not the raw input)
        assertTrue(events[0].message!!.contains("WORKER_UNHANDLED_EXCEPTION"))
        assertFalse(events[0].message!!.contains("some invalid code"))
    }
}
