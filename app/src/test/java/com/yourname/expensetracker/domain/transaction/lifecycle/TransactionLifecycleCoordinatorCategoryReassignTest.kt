package com.yourname.expensetracker.domain.transaction.lifecycle

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Item 144 (category delete / merge): the coordinator's DB-only bulk category re-assignment
 * ([TransactionLifecycleCoordinator.reassignCategoryBulkDbOnly]) and the post-commit runner
 * ([TransactionLifecycleCoordinator.runPostCommit]). Real in-memory Room database, real
 * [DatabaseWriteBarrier] (mutable maintenance mode), real coordinator; planner, runner and the
 * remaining collaborators are mocks (same harness as
 * [TransactionLifecycleCoordinatorSpendingInclusionTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleCoordinatorCategoryReassignTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var runner: PostCommitActionRunner
    private lateinit var sideEffectDispatcher: TransactionSideEffectDispatcher
    private lateinit var barrier: DatabaseWriteBarrier
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private var mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL
    private var fromId = 0L
    private var toId = 0L
    private var bystanderId = 0L
    private lateinit var plannedBatch: PostCommitActionBatch

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        fromId = db.categoryDao().insert(Category(name = "From", icon = "F", color = "#112233"))
        toId = db.categoryDao().insert(Category(name = "To", icon = "T", color = "#445566"))
        bystanderId = db.categoryDao().insert(Category(name = "Bystander", icon = "B", color = "#778899"))

        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } answers { mode }
        every { maintenanceMode.isWritesAllowed() } answers { mode == RestoreMaintenanceMode.Mode.NORMAL }
        barrier = DatabaseWriteBarrier(maintenanceMode)

        plannedBatch = PostCommitActionBatch("planned-batch", emptyList())
        planner = mockk(relaxed = true)
        every { planner.planBulkUpdated(any(), any(), any(), any()) } returns plannedBatch
        runner = mockk(relaxed = true)
        sideEffectDispatcher = mockk(relaxed = true)

        coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            expenseRefundLinkDao = db.expenseRefundLinkDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = sideEffectDispatcher,
            planner = planner,
            runner = runner,
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = barrier,
            currencySettingsRepository = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            receiptLinkService = com.yourname.expensetracker.testfixtures.noLinkedReceiptsLinkService()
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun seedExpense(categoryId: Long?, merchant: String = "Private Cafe Name"): Long =
        db.expenseDao().insert(
            Expense(
                amount = 12.0,
                currency = "EUR",
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now(),
                categoryId = categoryId
            )
        ).also { assertTrue("fixture: expense insert failed", it > 0L) }

    private suspend fun categoryOf(expenseId: Long): Long? = db.expenseDao().getById(expenseId)!!.categoryId

    private suspend fun bulkEvents() =
        db.transactionEventDao().getEventsByType(LifecycleEventType.BULK_UPDATED.name, 100)

    // ── one aggregate event ──────────────────────────────────────────────────

    @Test
    fun `moves every expense of the source category and writes exactly one BULK_UPDATED event`() = runTest {
        val a = seedExpense(fromId, "Shop A")
        val b = seedExpense(fromId, "Shop B")
        val c = seedExpense(fromId, "Shop C")

        val mutation = coordinator.reassignCategoryBulkDbOnly(fromId, toId, correlationId = "corr-1")

        assertEquals(3, mutation.value)
        assertEquals(toId, categoryOf(a))
        assertEquals(toId, categoryOf(b))
        assertEquals(toId, categoryOf(c))
        val events = bulkEvents()
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(LifecycleEventType.BULK_UPDATED.name, event.eventType)
        assertEquals("USER_EDIT", event.source)
        assertNull("aggregate event is not tied to one expense", event.expenseId)
        assertEquals("corr-1", event.correlationId)
        assertEquals(1, db.transactionEventDao().countByType(LifecycleEventType.BULK_UPDATED.name))
    }

    @Test
    fun `event metadata carries exactly from to and affectedCount and no merchant text`() = runTest {
        seedExpense(fromId, "Very Private Merchant")
        seedExpense(fromId, "Another Private Merchant")

        coordinator.reassignCategoryBulkDbOnly(fromId, toId)

        val event = bulkEvents().single()
        val metadata = JSONObject(event.metadata!!)
        assertEquals(setOf("fromCategoryId", "toCategoryId", "affectedCount"), metadata.keys().asSequence().toSet())
        assertEquals(fromId, metadata.getLong("fromCategoryId"))
        assertEquals(toId, metadata.getLong("toCategoryId"))
        assertEquals(2, metadata.getInt("affectedCount"))
        val everything = listOfNotNull(event.metadata, event.beforeSnapshot, event.afterSnapshot, event.reason)
            .joinToString(" ")
        assertFalse(everything.contains("Private"))
        assertFalse(everything.contains("merchant", ignoreCase = true))
    }

    @Test
    fun `only expenses of the source category move`() = runTest {
        val inSource = seedExpense(fromId)
        val inTarget = seedExpense(toId)
        val inBystander = seedExpense(bystanderId)
        val uncategorized = seedExpense(null)

        coordinator.reassignCategoryBulkDbOnly(fromId, toId)

        assertEquals(toId, categoryOf(inSource))
        assertEquals(toId, categoryOf(inTarget))
        assertEquals(bystanderId, categoryOf(inBystander))
        assertNull(categoryOf(uncategorized))
        assertEquals(1, bulkEvents().single().let { JSONObject(it.metadata!!).getInt("affectedCount") })
    }

    @Test
    fun `the supplied source is recorded on the event and passed to the planner with the CATEGORY field`() = runTest {
        seedExpense(fromId)
        seedExpense(fromId, "Shop B")

        val mutation = coordinator.reassignCategoryBulkDbOnly(fromId, toId, source = "SYNTHETIC_SOURCE", correlationId = "corr-2")

        assertEquals("SYNTHETIC_SOURCE", bulkEvents().single().source)
        verify(exactly = 1) {
            planner.planBulkUpdated("SYNTHETIC_SOURCE", 2, "corr-2", setOf(BulkChangedField.CATEGORY))
        }
        assertSame("the planner batch is returned unchanged", plannedBatch, mutation.postCommitActions)
    }

    @Test
    fun `the default source is USER_EDIT`() = runTest {
        seedExpense(fromId)

        coordinator.reassignCategoryBulkDbOnly(fromId, toId)

        assertEquals("USER_EDIT", bulkEvents().single().source)
        verify(exactly = 1) { planner.planBulkUpdated("USER_EDIT", 1, any(), setOf(BulkChangedField.CATEGORY)) }
    }

    // ── no rows / no-op ─────────────────────────────────────────────────────

    @Test
    fun `no event and an empty batch when no expense uses the source category`() = runTest {
        val inBystander = seedExpense(bystanderId)

        val mutation = coordinator.reassignCategoryBulkDbOnly(fromId, toId, correlationId = "corr-zero")

        assertEquals(0, mutation.value)
        assertEquals(0, bulkEvents().size)
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
        assertTrue(mutation.postCommitActions.actions.isEmpty())
        assertEquals("corr-zero", mutation.postCommitActions.correlationId)
        verify(exactly = 0) { planner.planBulkUpdated(any(), any(), any(), any()) }
        assertEquals(bystanderId, categoryOf(inBystander))
    }

    @Test
    fun `identical ids are a no-op that writes nothing`() = runTest {
        val id = seedExpense(fromId)

        val mutation = coordinator.reassignCategoryBulkDbOnly(fromId, fromId, correlationId = "corr-same")

        assertEquals(0, mutation.value)
        assertEquals(fromId, categoryOf(id))
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
        assertTrue(mutation.postCommitActions.actions.isEmpty())
        verify(exactly = 0) { planner.planBulkUpdated(any(), any(), any(), any()) }
    }

    // ── post-commit only via runPostCommit, only after commit ───────────────

    @Test
    fun `reassign never runs the batch itself and runPostCommit runs it once after the caller commit`() = runTest {
        seedExpense(fromId)

        val mutation = db.withTransaction {
            val inner = coordinator.reassignCategoryBulkDbOnly(fromId, toId)
            // Still inside the caller's transaction: nothing may have been dispatched.
            coVerify(exactly = 0) { runner.run(any()) }
            confirmVerified(sideEffectDispatcher)
            inner
        }

        coVerify(exactly = 0) { runner.run(any()) }
        confirmVerified(sideEffectDispatcher)
        coordinator.runPostCommit(mutation.postCommitActions)
        coVerify(exactly = 1) { runner.run(plannedBatch) }
    }

    @Test
    fun `a rolled back caller transaction undoes the move and the event and no batch is run`() = runTest {
        val id = seedExpense(fromId)

        try {
            db.withTransaction {
                coordinator.reassignCategoryBulkDbOnly(fromId, toId)
                throw IllegalStateException("injected failure after the reassign")
            }
            fail("Expected the injected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("injected failure after the reassign", expected.message)
        }

        assertEquals(fromId, categoryOf(id))
        assertEquals(0, bulkEvents().size)
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `runPostCommit swallows an ordinary failure of the batch`() = runTest {
        coEvery { runner.run(any()) } throws IllegalStateException("synthetic side effect failure")

        coordinator.runPostCommit(plannedBatch)

        coVerify(exactly = 1) { runner.run(plannedBatch) }
    }

    @Test
    fun `runPostCommit rethrows cancellation`() = runTest {
        coEvery { runner.run(any()) } throws CancellationException("synthetic cancellation")

        try {
            coordinator.runPostCommit(plannedBatch)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("synthetic cancellation", expected.message)
        }
    }

    // ── write barrier ────────────────────────────────────────────────────────

    @Test
    fun `a blocked write barrier refuses the reassign before any write`() = runTest {
        val id = seedExpense(fromId)
        mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        try {
            coordinator.reassignCategoryBulkDbOnly(fromId, toId)
            fail("Expected DatabaseAccessBlockedException")
        } catch (expected: DatabaseAccessBlockedException) {
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_PREPARING, expected.mode)
        }

        assertEquals(fromId, categoryOf(id))
        assertEquals(0, db.transactionEventDao().getRecentEvents(100).size)
        verify(exactly = 0) { planner.planBulkUpdated(any(), any(), any(), any()) }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `the same call succeeds once the barrier is back to normal`() = runTest {
        val id = seedExpense(fromId)
        mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        try {
            coordinator.reassignCategoryBulkDbOnly(fromId, toId)
            fail("Expected DatabaseAccessBlockedException")
        } catch (_: DatabaseAccessBlockedException) {
            // control: blocked
        }
        mode = RestoreMaintenanceMode.Mode.NORMAL

        val mutation = coordinator.reassignCategoryBulkDbOnly(fromId, toId)

        assertEquals(1, mutation.value)
        assertEquals(toId, categoryOf(id))
    }

    @Test
    fun `the nested runWrite inside a caller held write scope joins the caller transaction`() = runTest {
        val id = seedExpense(fromId)

        val mutation = barrier.runWrite(DatabaseAccessOperation("CategoryReassignTest.outer")) {
            db.withTransaction { coordinator.reassignCategoryBulkDbOnly(fromId, toId) }
        }

        assertEquals(1, mutation.value)
        assertEquals(toId, categoryOf(id))
        assertEquals(1, bulkEvents().size)
        assertEquals(0, barrier.inFlightWriteCount())
    }
}
