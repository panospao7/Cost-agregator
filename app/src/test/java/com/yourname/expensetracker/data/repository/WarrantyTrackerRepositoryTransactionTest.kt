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
            cloudExtractionService = mockk(relaxed = true),
            aiSettingsRepository = mockk(relaxed = true),
            aiPolicy = mockk(relaxed = true),
            aiCapabilityRouter = mockk(relaxed = true),
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
