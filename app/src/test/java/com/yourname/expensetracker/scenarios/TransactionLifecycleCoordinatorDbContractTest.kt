package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DB-backed lifecycle contract tests for [TransactionLifecycleCoordinator].
 *
 * These tests verify that the REAL coordinator with a REAL in-memory Room database
 * correctly orchestrates the full lifecycle: validate → normalize → dedupe →
 * insert atomic → event logging → post-commit side effects.
 *
 * Side-effect dependencies ([CurrencyConverter], [TransactionSideEffectDispatcher],
 * [RecurringLifecycleCoordinator], [RestoreMaintenanceMode]) are mocked so the
 * tests focus on the DB contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleCoordinatorDbContractTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private var foodCategoryId: Long = 0L
    private var shoppingCategoryId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        // ── Seed categories (DAO inserts are suspend) ──────────────────
        foodCategoryId = db.categoryDao().insert(
            Category(name = "Food & Dining", icon = "🍕", color = "#FF5733")
        )
        shoppingCategoryId = db.categoryDao().insert(
            Category(name = "Shopping", icon = "🛒", color = "#33FF57")
        )

        // ── Build coordinator with real DB objects + mocked side-effects ─
        coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true).also {
                every { it.isWritesAllowed() } returns true
            }
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: createExpense inserts row and writes CREATED event
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `createExpense inserts row and writes CREATED event`() = runTest {
        // GIVEN: categories seeded (Food & Dining, Shopping)
        // AND: a fake time provider set to 2026-05-01
        // AND: a TransactionLifecycleCoordinator with real DB but mocked side-effects
        val request = CreateExpenseRequest(
            merchant = "SKLAVENITIS",
            amount = 45.50,
            currency = "EUR",
            date = timeProvider.now(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY,
            categoryId = foodCategoryId,
            isManualEntry = true
        )

        // WHEN
        val result = coordinator.createExpense(request)

        // THEN — result is CreateExpenseResult.Created with a valid expenseId
        assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
        val expenseId = (result as CreateExpenseResult.Created).expenseId
        assertTrue("expenseId should be positive", expenseId > 0L)

        // THEN — expense exists in DB with correct merchant, amount, categoryId
        val expense = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should exist in DB", expense)
        assertEquals("Merchant should match", "SKLAVENITIS", expense!!.merchant)
        assertEquals("Amount should match", 45.50, expense.amount, 0.001)
        assertEquals("Category should match", foodCategoryId, expense.categoryId)

        // THEN — transactionEventDao has a CREATED event for the expense
        val events = db.transactionEventDao().getEventsForExpense(expenseId)
        assertEquals("Should have exactly 1 event", 1, events.size)
        assertEquals(
            "Event type should be CREATED",
            LifecycleEventType.CREATED.name,
            events[0].eventType
        )

        // THEN — expense count = 1
        assertEquals("Expense count should be 1", 1, db.expenseDao().getTotalCount())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: createExpense duplicate detected and skipped
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `createExpense duplicate detected and skipped`() = runTest {
        // GIVEN: an existing expense of €45.50 at SKLAVENITIS (inserted via coordinator)
        val request = CreateExpenseRequest(
            merchant = "SKLAVENITIS",
            amount = 45.50,
            currency = "EUR",
            date = timeProvider.now(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY,
            categoryId = foodCategoryId,
            isManualEntry = true
        )

        val firstResult = coordinator.createExpense(request)
        assertTrue("First create should succeed", firstResult is CreateExpenseResult.Created)
        val firstExpenseId = (firstResult as CreateExpenseResult.Created).expenseId

        // WHEN: creating the same expense again via coordinator
        val secondResult = coordinator.createExpense(request)

        // THEN: result is DuplicateSkipped
        assertTrue(
            "Expected DuplicateSkipped, got $secondResult",
            secondResult is CreateExpenseResult.DuplicateSkipped
        )
        assertEquals(
            "Duplicate should reference the original expense ID",
            firstExpenseId,
            (secondResult as CreateExpenseResult.DuplicateSkipped).existingExpenseId
        )

        // THEN: expense count still = 1
        assertEquals("Expense count should still be 1", 1, db.expenseDao().getTotalCount())

        // THEN: transactionEventDao has DUPLICATE_SKIPPED event (and CREATED)
        val events = db.transactionEventDao().getEventsForExpense(firstExpenseId)
        assertEquals("Should have 2 events (CREATED + DUPLICATE_SKIPPED)", 2, events.size)
        val eventTypes = events.map { it.eventType }.toSet()
        assertTrue(
            "Should contain CREATED event",
            eventTypes.contains(LifecycleEventType.CREATED.name)
        )
        assertTrue(
            "Should contain CREATE_DUPLICATE_SKIPPED event",
            eventTypes.contains(LifecycleEventType.CREATE_DUPLICATE_SKIPPED.name)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: updateExpense updates row and writes UPDATED event
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateExpense updates row and writes UPDATED event`() = runTest {
        // GIVEN: an existing expense (created via coordinator)
        val request = CreateExpenseRequest(
            merchant = "SKLAVENITIS",
            amount = 45.50,
            currency = "EUR",
            date = timeProvider.now(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY,
            categoryId = foodCategoryId,
            isManualEntry = true
        )

        val createResult = coordinator.createExpense(request)
        assertTrue("Create should succeed", createResult is CreateExpenseResult.Created)
        val expenseId = (createResult as CreateExpenseResult.Created).expenseId

        // Load the persisted expense for the update call
        val existingExpense = db.expenseDao().getById(expenseId)!!

        // WHEN: updateExpense with modified amount
        coordinator.updateExpense(
            expense = existingExpense.copy(amount = 55.00),
            reason = "correction"
        )

        // THEN: expense amount in DB = 55.00
        val updatedExpense = db.expenseDao().getById(expenseId)
        assertNotNull("Updated expense should exist", updatedExpense)
        assertEquals("Amount should be updated to 55.00", 55.00, updatedExpense!!.amount, 0.001)

        // THEN: transactionEventDao has UPDATED event
        val events = db.transactionEventDao().getEventsForExpense(expenseId)
        val updatedEvents = events.filter {
            it.eventType == LifecycleEventType.UPDATED.name
        }
        assertEquals("Should have exactly 1 UPDATED event", 1, updatedEvents.size)
        assertEquals("Reason should match", "correction", updatedEvents[0].reason)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: deleteExpense removes row and writes DELETED event
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `deleteExpense removes row and writes DELETED event`() = runTest {
        // GIVEN: an existing expense (created via coordinator)
        val request = CreateExpenseRequest(
            merchant = "SKLAVENITIS",
            amount = 45.50,
            currency = "EUR",
            date = timeProvider.now(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY,
            categoryId = foodCategoryId,
            isManualEntry = true
        )

        val createResult = coordinator.createExpense(request)
        assertTrue("Create should succeed", createResult is CreateExpenseResult.Created)
        val expenseId = (createResult as CreateExpenseResult.Created).expenseId
        assertEquals("Expense count before delete", 1, db.expenseDao().getTotalCount())

        // Load the persisted expense for deletion
        val existingExpense = db.expenseDao().getById(expenseId)!!

        // WHEN: deleteExpense
        val deleteResult = coordinator.deleteExpense(existingExpense)

        // THEN: delete succeeds
        assertTrue("Delete should succeed", deleteResult.isSuccess)

        // THEN: expense is deleted from DB
        assertNull("Expense should be deleted from DB", db.expenseDao().getById(expenseId))

        // THEN: expense count = 0
        assertEquals("Expense count should be 0", 0, db.expenseDao().getTotalCount())

        // THEN: transactionEventDao has DELETED event
        val events = db.transactionEventDao().getEventsForExpense(expenseId)
        val deletedEvents = events.filter {
            it.eventType == LifecycleEventType.DELETED.name
        }
        assertEquals("Should have exactly 1 DELETED event", 1, deletedEvents.size)
    }
}
