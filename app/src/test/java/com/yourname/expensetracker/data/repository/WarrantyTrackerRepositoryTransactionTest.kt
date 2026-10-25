package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.dao.WarrantyLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyLifecycleEvent
import com.yourname.expensetracker.data.database.entity.WarrantyLifecycleEventTypes
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEvent
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R29: REAL Room regression for fail-closed in-transaction lifecycle event / audit writes
 * in [WarrantyTrackerRepository].
 *
 * On Android a failed nested transaction (Room suspend @Insert and the transaction runner both
 * open nested transactions) marks the outer withTransaction as failed, so the outer transaction
 * silently rolls back. Swallowing the exception therefore reported a warranty id for a row that
 * was never committed. Contract: an in-transaction event/audit failure rolls back the warranty
 * row AND surfaces as an exception.
 *
 * The failing collaborators perform a REAL failing nested database.withTransaction (the exact
 * hazard shape) and then throw a synthetic exception. All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WarrantyTrackerRepositoryTransactionTest {

    private val now = 1_700_000_000_000L

    private lateinit var database: AppDatabase
    private lateinit var barrier: DatabaseWriteBarrier
    private lateinit var timeProvider: FakeTimeProvider

    /** Real nested failing transaction then throws, like a failed Room nested write. */
    private class FailingEventDao(private val database: AppDatabase) : WarrantyLifecycleEventDao {
        var failInserts = false

        override suspend fun insert(event: WarrantyLifecycleEvent): Long {
            if (failInserts) {
                database.withTransaction<Unit> { throw IllegalStateException("synthetic event failure") }
            }
            return database.warrantyLifecycleEventDao().insert(event)
        }

        override suspend fun getByWarrantyId(id: Long): List<WarrantyLifecycleEvent> =
            database.warrantyLifecycleEventDao().getByWarrantyId(id)
    }

    private class FailingAuditWriter(private val database: AppDatabase) : ReceiptLifecycleEventWriter {
        var failWrites = false
        var writes = 0

        override suspend fun write(context: TransactionContext, event: ReceiptLifecycleEvent) {
            writes++
            if (failWrites) {
                database.withTransaction<Unit> { throw IllegalStateException("synthetic audit failure") }
            }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION_ERROR")
        @Deprecated("Use write(context, event)", level = DeprecationLevel.ERROR)
        override suspend fun write(event: ReceiptLifecycleEvent) {
            throw UnsupportedOperationException("legacy write is not supported in this test")
        }
    }

    private lateinit var eventDao: FailingEventDao
    private lateinit var auditWriter: FailingAuditWriter
    private lateinit var repository: WarrantyTrackerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider(now)

        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        barrier = DatabaseWriteBarrier(maintenanceMode)

        eventDao = FailingEventDao(database)
        auditWriter = FailingAuditWriter(database)
        val receiptRepository = mockk<ReceiptRepository>(relaxed = true)

        repository = WarrantyTrackerRepository(
            database = database,
            warrantyDao = database.warrantyDao(),
            returnWindowDao = database.returnWindowDao(),
            warrantyLifecycleEventDao = eventDao,
            receiptRepository = object : Lazy<ReceiptRepository> {
                override fun get() = receiptRepository
            },
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            writeBarrier = barrier,
            receiptLifecycleEventWriter = auditWriter,
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun warranty(autoDetected: Boolean = false) = Warranty(
        receiptId = null,
        productName = "Synthetic Product",
        merchantName = "Synthetic Merchant",
        purchaseDate = now,
        warrantyDurationMonths = 12,
        warrantyEndDate = now + 1_000L,
        autoDetected = autoDetected,
        extractionSource = if (autoDetected) "cloud" else "manual",
        createdAt = 0L,
        updatedAt = 0L
    )

    private suspend fun warrantyCount(): Int = database.warrantyDao().getAllWarranties().first().size

    private fun assertSynthetic(thrown: Result<Long>, token: String) {
        val failure = thrown.exceptionOrNull()
        assertTrue("call must fail, not report an id for an unsaved warranty", failure != null)
        assertTrue(
            "unexpected failure type=${failure!!.javaClass.simpleName}",
            failure is IllegalStateException && failure.message?.contains(token) == true
        )
    }

    // ------------------------------------------------------------------ addWarranty

    @Test
    fun addWarranty_success_persistsWarrantyAndCreatedEvent() = runTest {
        val id = repository.addWarranty(warranty())

        assertTrue(id > 0L)
        assertEquals(1, warrantyCount())
        val events = database.warrantyLifecycleEventDao().getByWarrantyId(id)
        assertEquals(1, events.size)
        assertEquals(WarrantyLifecycleEventTypes.CREATED, events.single().eventType)
    }

    @Test
    fun addWarranty_eventWriteFailure_throwsAndRollsBackWarranty() = runTest {
        eventDao.failInserts = true

        val thrown = runCatching { repository.addWarranty(warranty()) }

        assertSynthetic(thrown, "synthetic event failure")
        assertEquals("warranty row must be rolled back", 0, warrantyCount())
    }

    // ------------------------------------------------- addWarrantyIgnoreConflicts

    @Test
    fun addWarrantyIgnoreConflicts_success_persistsWarrantyAndCreatedEvent() = runTest {
        val id = repository.addWarrantyIgnoreConflicts(warranty())

        assertTrue(id > 0L)
        assertEquals(1, warrantyCount())
        val events = database.warrantyLifecycleEventDao().getByWarrantyId(id)
        assertEquals(1, events.size)
        assertEquals(WarrantyLifecycleEventTypes.CREATED, events.single().eventType)
    }

    @Test
    fun addWarrantyIgnoreConflicts_eventWriteFailure_throwsAndRollsBackWarranty() = runTest {
        eventDao.failInserts = true

        val thrown = runCatching { repository.addWarrantyIgnoreConflicts(warranty()) }

        assertSynthetic(thrown, "synthetic event failure")
        assertEquals("warranty row must be rolled back", 0, warrantyCount())
    }

    @Test
    fun addWarrantyIgnoreConflicts_autoDetectedSuccess_persistsWarrantyEventAndInvokesAudit() = runTest {
        val id = repository.addWarrantyIgnoreConflicts(warranty(autoDetected = true))

        assertTrue(id > 0L)
        assertEquals(1, warrantyCount())
        assertEquals(1, database.warrantyLifecycleEventDao().getByWarrantyId(id).size)
        assertEquals("AID-9 audit must be written exactly once", 1, auditWriter.writes)
    }

    @Test
    fun addWarrantyIgnoreConflicts_auditWriteFailure_throwsAndRollsBackWarranty() = runTest {
        auditWriter.failWrites = true

        val thrown = runCatching { repository.addWarrantyIgnoreConflicts(warranty(autoDetected = true)) }

        assertSynthetic(thrown, "synthetic audit failure")
        assertEquals("audit writer must have been invoked", 1, auditWriter.writes)
        assertEquals("warranty row must be rolled back", 0, warrantyCount())
    }

    // -------------------------------------------------------- markWarrantyAsClaimed

    @Test
    fun markWarrantyAsClaimed_eventWriteFailure_throwsAndRollsBackStatusChange() = runTest {
        val id = repository.addWarranty(warranty())
        eventDao.failInserts = true

        val thrown = runCatching { repository.markWarrantyAsClaimed(id) }

        val failure = thrown.exceptionOrNull()
        assertTrue("claim must fail", failure is IllegalStateException)
        val stored = database.warrantyDao().getAllWarranties().first().single()
        assertEquals("status change must be rolled back", WarrantyStatus.ACTIVE, stored.status)
    }

    // ------------------------------------------------------ confirmWarranty (ai-fixes B1a)

    private val dayMs = 86_400_000L

    private fun pendingWarranty(endDate: Long) = warranty(autoDetected = true).copy(
        status = WarrantyStatus.PENDING_REVIEW,
        needsReview = true,
        warrantyEndDate = endDate
    )

    @Test
    fun confirmWarranty_success_clearsReviewActivatesAndWritesConfirmedEvent() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))

        val result = repository.confirmWarranty(id)

        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.ACTIVE),
            result
        )
        val stored = database.warrantyDao().getWarrantyById(id)!!
        assertEquals(WarrantyStatus.ACTIVE, stored.status)
        assertEquals(false, stored.needsReview)
        val eventTypes = database.warrantyLifecycleEventDao().getByWarrantyId(id).map { it.eventType }
        assertTrue(eventTypes.contains(WarrantyLifecycleEventTypes.CONFIRMED))
    }

    @Test
    fun confirmWarranty_expiredUnconfirmed_staysExpiredClearsReviewAndWritesConfirmedEvent() = runTest {
        // F-AI-1: the semantics the receipt-review promotion now delegates to. Never EXPIRED -> ACTIVE.
        val id = repository.addWarranty(
            pendingWarranty(endDate = now - 3 * dayMs).copy(status = WarrantyStatus.EXPIRED)
        )

        val result = repository.confirmWarranty(id)

        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.EXPIRED),
            result
        )
        val stored = database.warrantyDao().getWarrantyById(id)!!
        assertEquals(WarrantyStatus.EXPIRED, stored.status)
        assertEquals(false, stored.needsReview)
        val eventTypes = database.warrantyLifecycleEventDao().getByWarrantyId(id).map { it.eventType }
        assertTrue(eventTypes.contains(WarrantyLifecycleEventTypes.CONFIRMED))
    }

    @Test
    fun confirmWarranty_eventWriteFailure_throwsAndRollsBackReviewClear() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))
        eventDao.failInserts = true

        val thrown = runCatching { repository.confirmWarranty(id) }

        assertTrue("confirm must fail", thrown.exceptionOrNull() is IllegalStateException)
        val stored = database.warrantyDao().getWarrantyById(id)!!
        assertEquals("status change must be rolled back", WarrantyStatus.PENDING_REVIEW, stored.status)
        assertEquals("review flag must be rolled back", true, stored.needsReview)
    }

    // ------------------------------------------------------ promoteReviewDraft (ai-fixes F-AI-1)

    private suspend fun eventTypesOf(id: Long): List<String> =
        database.warrantyLifecycleEventDao().getByWarrantyId(id).map { it.eventType }

    private suspend fun confirmedEventCount(id: Long): Int =
        eventTypesOf(id).count { it == WarrantyLifecycleEventTypes.CONFIRMED }

    @Test
    fun promoteReviewDraft_pendingFutureEnd_appliesContentActivatesAndWritesOneConfirmedEvent() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))
        var invocations = 0

        val result = repository.promoteReviewDraft(id) { fresh ->
            invocations++
            fresh.copy(
                productName = "Edited Product",
                merchantName = "Edited Merchant",
                warrantyDurationMonths = 24,
                warrantyEndDate = now + 9 * dayMs,
                supportPhone = "+00 000 0000",
                supportEmail = "support@example.test",
                notes = "edited notes",
                extractionConfidence = 0.91,
                extractionSource = "ocr"
            )
        }

        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.ACTIVE),
            result
        )
        assertEquals(1, invocations)
        val stored = database.warrantyDao().getWarrantyById(id)!!
        assertEquals("Edited Product", stored.productName)
        assertEquals("Edited Merchant", stored.merchantName)
        assertEquals(24, stored.warrantyDurationMonths)
        assertEquals(now + 9 * dayMs, stored.warrantyEndDate)
        assertEquals("+00 000 0000", stored.supportPhone)
        assertEquals("support@example.test", stored.supportEmail)
        assertEquals("edited notes", stored.notes)
        assertEquals(0.91, stored.extractionConfidence, 0.0)
        assertEquals("ocr", stored.extractionSource)
        assertEquals(WarrantyStatus.ACTIVE, stored.status)
        assertEquals(false, stored.needsReview)
        assertEquals("exactly one CONFIRMED event", 1, confirmedEventCount(id))
    }

    @Test
    fun promoteReviewDraft_editResultStatusReviewFlagAndClaimedAtAreIgnored() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))

        val result = repository.promoteReviewDraft(id) { fresh ->
            fresh.copy(
                productName = "Edited Product",
                status = WarrantyStatus.CLAIMED,
                needsReview = false,
                claimedAt = now - 1L
            )
        }

        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.ACTIVE),
            result
        )
        val stored = database.warrantyDao().getWarrantyById(id)!!
        assertEquals("Edited Product", stored.productName)
        assertEquals("status comes from confirm rules, not the edit lambda", WarrantyStatus.ACTIVE, stored.status)
        assertEquals(null, stored.claimedAt)
    }

    @Test
    fun promoteReviewDraft_editMovesEndDateIntoThePast_confirmsExpiredWithOneConfirmedEvent() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))

        val result = repository.promoteReviewDraft(id) { fresh ->
            fresh.copy(warrantyEndDate = now - dayMs)
        }

        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.EXPIRED),
            result
        )
        val stored = database.warrantyDao().getWarrantyById(id)!!
        assertEquals(now - dayMs, stored.warrantyEndDate)
        assertEquals(WarrantyStatus.EXPIRED, stored.status)
        assertEquals(false, stored.needsReview)
        assertEquals(1, confirmedEventCount(id))
    }

    @Test
    fun promoteReviewDraft_pendingRowAlreadyPastEnd_confirmsExpiredNotActive() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now - 3 * dayMs))

        val result = repository.promoteReviewDraft(id) { fresh -> fresh.copy(productName = "Edited Product") }

        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.EXPIRED),
            result
        )
        assertEquals(WarrantyStatus.EXPIRED, database.warrantyDao().getWarrantyById(id)!!.status)
        assertEquals(1, confirmedEventCount(id))
    }

    @Test
    fun promoteReviewDraft_expiredUnconfirmedRow_appliesContentButIsNeverRevivedToActive() = runTest {
        // isNoLongerInReview is false for EXPIRED + needsReview=true (still awaiting review), so the
        // content is applied and confirm keeps the row EXPIRED even if the edit pushes the end date
        // into the future.
        val id = repository.addWarranty(
            pendingWarranty(endDate = now - 3 * dayMs).copy(status = WarrantyStatus.EXPIRED)
        )
        var invocations = 0

        val result = repository.promoteReviewDraft(id) { fresh ->
            invocations++
            fresh.copy(productName = "Corrected Product", warrantyEndDate = now + 30 * dayMs)
        }

        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.EXPIRED),
            result
        )
        assertEquals(1, invocations)
        val stored = database.warrantyDao().getWarrantyById(id)!!
        assertEquals("Corrected Product", stored.productName)
        assertEquals(WarrantyStatus.EXPIRED, stored.status)
        assertEquals(false, stored.needsReview)
        assertEquals(1, confirmedEventCount(id))
    }

    @Test
    fun promoteReviewDraft_claimedRow_returnsAlreadyConfirmedWithNoWriteNoEventAndNoEdit() = runTest {
        val id = repository.addWarranty(
            pendingWarranty(endDate = now + 5 * dayMs)
                .copy(status = WarrantyStatus.CLAIMED, needsReview = true, claimedAt = now - dayMs)
        )
        val before = database.warrantyDao().getWarrantyById(id)!!
        val eventsBefore = eventTypesOf(id)
        var invocations = 0

        val result = repository.promoteReviewDraft(id) { fresh ->
            invocations++
            fresh.copy(productName = "Overwrite Attempt")
        }

        assertEquals(WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed, result)
        assertEquals("edit lambda must not be invoked", 0, invocations)
        assertEquals("no content or status change", before, database.warrantyDao().getWarrantyById(id))
        assertEquals("no new event", eventsBefore, eventTypesOf(id))
        assertEquals(0, confirmedEventCount(id))
    }

    @Test
    fun promoteReviewDraft_transferredRow_returnsAlreadyConfirmedWithNoWriteNoEventAndNoEdit() = runTest {
        val id = repository.addWarranty(
            pendingWarranty(endDate = now + 5 * dayMs)
                .copy(status = WarrantyStatus.TRANSFERRED, needsReview = true)
        )
        val before = database.warrantyDao().getWarrantyById(id)!!
        val eventsBefore = eventTypesOf(id)
        var invocations = 0

        val result = repository.promoteReviewDraft(id) { fresh ->
            invocations++
            fresh.copy(productName = "Overwrite Attempt")
        }

        assertEquals(WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed, result)
        assertEquals(0, invocations)
        assertEquals(before, database.warrantyDao().getWarrantyById(id))
        assertEquals(eventsBefore, eventTypesOf(id))
    }

    @Test
    fun promoteReviewDraft_alreadyConfirmedRow_returnsAlreadyConfirmedWithNoWriteNoEventAndNoEdit() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))
        assertEquals(
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(id, WarrantyStatus.ACTIVE),
            repository.confirmWarranty(id)
        )
        val before = database.warrantyDao().getWarrantyById(id)!!
        val eventsBefore = eventTypesOf(id)
        var invocations = 0

        val result = repository.promoteReviewDraft(id) { fresh ->
            invocations++
            fresh.copy(productName = "Overwrite Attempt")
        }

        assertEquals(WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed, result)
        assertEquals(0, invocations)
        assertEquals("user-confirmed content must not be overwritten", before, database.warrantyDao().getWarrantyById(id))
        assertEquals("no second CONFIRMED event", eventsBefore, eventTypesOf(id))
        assertEquals(1, confirmedEventCount(id))
    }

    @Test
    fun promoteReviewDraft_secondCallAfterPromotion_isIdempotentAndWritesNoSecondConfirmedEvent() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))
        repository.promoteReviewDraft(id) { fresh -> fresh.copy(productName = "First Edit") }

        val second = repository.promoteReviewDraft(id) { fresh -> fresh.copy(productName = "Second Edit") }

        assertEquals(WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed, second)
        assertEquals("First Edit", database.warrantyDao().getWarrantyById(id)!!.productName)
        assertEquals(1, confirmedEventCount(id))
    }

    @Test
    fun promoteReviewDraft_missingId_returnsNotFoundWithNoWriteAndNoEdit() = runTest {
        var invocations = 0

        val result = repository.promoteReviewDraft(9_999L) { fresh ->
            invocations++
            fresh
        }

        assertEquals(WarrantyTrackerRepository.ConfirmWarrantyResult.NotFound, result)
        assertEquals(0, invocations)
        assertEquals(0, warrantyCount())
        assertEquals(emptyList<WarrantyLifecycleEvent>(), database.warrantyLifecycleEventDao().getByWarrantyId(9_999L))
    }

    @Test
    fun promoteReviewDraft_confirmedEventWriteFailure_throwsAndRollsBackContentEditAndReviewClear() = runTest {
        val id = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))
        val before = database.warrantyDao().getWarrantyById(id)!!
        eventDao.failInserts = true

        val thrown = runCatching {
            repository.promoteReviewDraft(id) { fresh -> fresh.copy(productName = "Edited Product") }
        }

        assertTrue("promote must fail", thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(
            "content edit and review clear are one atomic unit",
            before,
            database.warrantyDao().getWarrantyById(id)
        )
    }

    // ------------------------------------------------- reconcileExpiredItems (ai-fixes B1a)

    @Test
    fun reconcileExpiredItems_expiresActiveAndPendingKeepsNeedsReviewAndIsIdempotent() = runTest {
        val w1 = repository.addWarranty(pendingWarranty(endDate = now - dayMs))
        val w2 = repository.addWarranty(warranty().copy(warrantyEndDate = now - 2 * dayMs))
        val w3 = repository.addWarranty(pendingWarranty(endDate = now + 5 * dayMs))

        val result = repository.reconcileExpiredItems(now)

        assertEquals(2, result.expiredWarrantyCount)
        assertEquals(1, result.expiredPendingWarrantyCount)
        val dao = database.warrantyDao()
        val s1 = dao.getWarrantyById(w1)!!
        assertEquals(WarrantyStatus.EXPIRED, s1.status)
        assertEquals("expired pending stays unconfirmed", true, s1.needsReview)
        assertEquals(WarrantyStatus.EXPIRED, dao.getWarrantyById(w2)!!.status)
        val s3 = dao.getWarrantyById(w3)!!
        assertEquals(WarrantyStatus.PENDING_REVIEW, s3.status)
        assertEquals(true, s3.needsReview)
        val batchEvents = database.warrantyLifecycleEventDao().getByWarrantyId(-1L)
            .filter { it.eventType == WarrantyLifecycleEventTypes.EXPIRED }
        assertEquals(1, batchEvents.size)
        assertEquals(
            "Batch expired: 2 warranties (1 unconfirmed), 0 return windows",
            batchEvents.single().description
        )

        // Second run: nothing changes, no new event.
        val second = repository.reconcileExpiredItems(now)
        assertEquals(0, second.expiredWarrantyCount)
        assertEquals(0, second.expiredPendingWarrantyCount)
        assertEquals(
            1,
            database.warrantyLifecycleEventDao().getByWarrantyId(-1L)
                .count { it.eventType == WarrantyLifecycleEventTypes.EXPIRED }
        )
    }

    // ------------------------------------------------- rejectAutoDetectedWarranty

    @Test
    fun rejectAutoDetectedWarranty_eventWriteFailure_throwsAndRollsBackDeletion() = runTest {
        val id = repository.addWarranty(warranty(autoDetected = true))
        val stored = database.warrantyDao().getAllWarranties().first().single()
        assertEquals(id, stored.id)
        eventDao.failInserts = true

        val thrown = runCatching { repository.rejectAutoDetectedWarranty(stored) }

        assertTrue("reject must fail", thrown.exceptionOrNull() is IllegalStateException)
        assertEquals("deletion must be rolled back", 1, warrantyCount())
    }
}
