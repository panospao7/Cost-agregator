package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import com.yourname.expensetracker.testfixtures.scenario.CategorySeed
import com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests verifying that recurring planned occurrences do not cause
 * double-counting when the actual expense arrives.
 *
 * These tests use DAOs directly (simpler than wiring the full
 * [com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator])
 * to verify the no-double-count contract at the data layer.
 *
 * Key behaviours verified:
 * - Planned occurrences are created and queryable independently of expenses.
 * - An actual expense for the same merchant/amount does not duplicate the
 *   planned occurrence in dashboard totals.
 * - Occurrence status transitions (PLANNED → PAID) are persisted correctly.
 * - Multiple occurrences for the same recurring rule each have distinct dates.
 * - Reminder deliveries can be linked to occurrences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringNoDoubleCountScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var seeder: ScenarioSeeder

    private val may1 = dateMs(2026, 5, 1)
    private val june1 = dateMs(2026, 6, 1)
    private val july1 = dateMs(2026, 7, 1)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        seeder = ScenarioSeeder(db)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: planned occurrence created and queryable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `planned occurrence created and queryable`() = runTest {
        // GIVEN: categories seeded so foreign-key relationships work
        seeder.seedState(
            ScenarioSeed(
                categories = listOf(
                    CategorySeed("Entertainment"),
                    CategorySeed("Bills & Utilities")
                ),
                description = "seed categories for occurrence FK context"
            )
        )

        // WHEN: insert a RecurringOccurrence via DAO with status=PLANNED,
        //       merchant="Netflix", amount=12.99
        val occurrenceId = db.recurringOccurrenceDao().insert(
            RecurringOccurrence(
                sourceType = "RECURRING_RULE",
                sourceId = 100L,
                occurrenceKey = "100|20260501|MONTHLY",
                dueDate = may1,
                status = "PLANNED",
                linkedExpenseId = null,
                expectedAmount = 12.99,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = "Netflix",
                categoryId = null,
                createdAt = may1,
                updatedAt = may1
            )
        )

        // THEN: occurrence exists with correct fields
        assertTrue("Occurrence ID should be positive", occurrenceId > 0L)

        val loaded = db.recurringOccurrenceDao().getById(occurrenceId)
        assertNotNull("Occurrence should exist in DB", loaded)
        assertEquals("Merchant should be Netflix", "Netflix", loaded!!.merchant)
        assertEquals("Expected amount should be 12.99", 12.99, loaded.expectedAmount, 0.001)
        assertEquals("Expected currency should be EUR", "EUR", loaded.expectedCurrency)
        assertEquals("Status should be PLANNED", "PLANNED", loaded.status)
        assertEquals("Source type should be RECURRING_RULE", "RECURRING_RULE", loaded.sourceType)
        assertEquals("Source ID should be 100", 100L, loaded.sourceId)
        assertEquals("Due date should be May 1, 2026", may1, loaded.dueDate)
        assertEquals("Frequency should be MONTHLY", "MONTHLY", loaded.frequency)
        assertEquals("Occurrence key should match", "100|20260501|MONTHLY", loaded.occurrenceKey)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: actual expense does not duplicate planned in dashboard
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `actual expense does not duplicate planned in dashboard`() = runTest {
        // GIVEN: a PLANNED occurrence for "Netflix €12.99"
        val occurrenceId = db.recurringOccurrenceDao().insert(
            RecurringOccurrence(
                sourceType = "RECURRING_RULE",
                sourceId = 100L,
                occurrenceKey = "100|20260501|MONTHLY",
                dueDate = may1,
                status = "PLANNED",
                expectedAmount = 12.99,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = "Netflix",
                createdAt = may1,
                updatedAt = may1
            )
        )
        assertTrue("Planned occurrence inserted", occurrenceId > 0L)

        // WHEN: insert an actual expense of €12.99 at "Netflix" via seedState
        val seedResult = seeder.seedState(
            ScenarioSeed(
                categories = listOf(CategorySeed("Entertainment")),
                expenses = listOf(
                    ExpenseSeed(
                        amount = 12.99,
                        currency = "EUR",
                        merchant = "Netflix",
                        date = may1,
                        categoryName = "Entertainment"
                    )
                ),
                description = "actual Netflix expense matching the planned occurrence"
            )
        )

        // THEN: the expense exists (count=1)
        assertEquals("Expense count should be 1", 1, seedResult.expenseIds.size)
        val expense = db.expenseDao().getById(seedResult.expenseIds.first())
        assertNotNull("Expense should exist", expense)
        assertEquals("Expense merchant should be Netflix", "Netflix", expense!!.merchant)
        assertEquals("Expense amount should be 12.99", 12.99, expense.amount, 0.001)

        // AND: the planned occurrence still exists (not deleted)
        val occurrence = db.recurringOccurrenceDao().getById(occurrenceId)
        assertNotNull("Planned occurrence should still exist", occurrence)
        assertEquals("Occurrence status should remain PLANNED", "PLANNED", occurrence!!.status)

        // AND: total count of expenses in DB is 1 (the planned occurrence is NOT counted)
        assertEquals("Dashboard total expense count should be 1", 1, db.expenseDao().getTotalCount())

        // AND: the dashboard total equals the actual expense amount, not including
        // the planned occurrence amount (12.99, not 25.98)
        val allExpenses = db.expenseDao().getAll()
        val totalAmount = allExpenses.sumOf { it.amount }
        assertEquals("Dashboard total should be 12.99 (only the actual expense)", 12.99, totalAmount, 0.001)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: occurrence status transitions from PLANNED to PAID
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `occurrence status transitions from PLANNED to PAID`() = runTest {
        // GIVEN: a PLANNED occurrence
        val occurrenceId = db.recurringOccurrenceDao().insert(
            RecurringOccurrence(
                sourceType = "RECURRING_RULE",
                sourceId = 100L,
                occurrenceKey = "100|20260501|MONTHLY",
                dueDate = may1,
                status = "PLANNED",
                expectedAmount = 12.99,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = "Netflix",
                createdAt = may1,
                updatedAt = may1
            )
        )
        assertTrue("PLANNED occurrence inserted", occurrenceId > 0L)

        val paidAt = june1

        // WHEN: updating its status to PAID
        db.recurringOccurrenceDao().updateStatus(
            ids = listOf(occurrenceId),
            newStatus = "PAID",
            now = paidAt
        )

        // THEN: status is PAID in DB
        val loaded = db.recurringOccurrenceDao().getById(occurrenceId)
        assertNotNull("Occurrence should exist", loaded)
        assertEquals("Status should be PAID", "PAID", loaded!!.status)
        assertEquals("updatedAt should reflect transition time", paidAt, loaded.updatedAt)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: multiple occurrences for same recurring rule
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `multiple occurrences for same recurring rule`() = runTest {
        // GIVEN: 3 occurrences (PLANNED for May, June, July — all Netflix €12.99)
        val mayKey = "100|20260501|MONTHLY"
        val juneKey = "100|20260601|MONTHLY"
        val julyKey = "100|20260701|MONTHLY"

        db.recurringOccurrenceDao().insertAll(
            listOf(
                RecurringOccurrence(
                    sourceType = "RECURRING_RULE",
                    sourceId = 100L,
                    occurrenceKey = mayKey,
                    dueDate = may1,
                    status = "PLANNED",
                    expectedAmount = 12.99,
                    expectedCurrency = "EUR",
                    frequency = "MONTHLY",
                    merchant = "Netflix",
                    createdAt = may1,
                    updatedAt = may1
                ),
                RecurringOccurrence(
                    sourceType = "RECURRING_RULE",
                    sourceId = 100L,
                    occurrenceKey = juneKey,
                    dueDate = june1,
                    status = "PLANNED",
                    expectedAmount = 12.99,
                    expectedCurrency = "EUR",
                    frequency = "MONTHLY",
                    merchant = "Netflix",
                    createdAt = may1,
                    updatedAt = may1
                ),
                RecurringOccurrence(
                    sourceType = "RECURRING_RULE",
                    sourceId = 100L,
                    occurrenceKey = julyKey,
                    dueDate = july1,
                    status = "PLANNED",
                    expectedAmount = 12.99,
                    expectedCurrency = "EUR",
                    frequency = "MONTHLY",
                    merchant = "Netflix",
                    createdAt = may1,
                    updatedAt = may1
                )
            )
        )

        // WHEN: querying all occurrences for this rule
        val occurrences = db.recurringOccurrenceDao().getBySource(
            sourceType = "RECURRING_RULE",
            sourceId = 100L
        )

        // THEN: count = 3
        assertEquals("Should have exactly 3 occurrences", 3, occurrences.size)

        // AND: each has a different occurrenceDate
        val dates = occurrences.map { it.dueDate }.toSet()
        assertEquals("Should have 3 distinct due dates", 3, dates.size)
        assertTrue("May 1 should be one of the dates", dates.contains(may1))
        assertTrue("June 1 should be one of the dates", dates.contains(june1))
        assertTrue("July 1 should be one of the dates", dates.contains(july1))

        // AND: all are Netflix, €12.99, PLANNED
        occurrences.forEach { occ ->
            assertEquals("Merchant should be Netflix", "Netflix", occ.merchant)
            assertEquals("Expected amount should be 12.99", 12.99, occ.expectedAmount, 0.001)
            assertEquals("Status should be PLANNED", "PLANNED", occ.status)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: reminder delivery created for occurrence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reminder delivery created for occurrence`() = runTest {
        // GIVEN: a PLANNED occurrence
        val occurrenceId = db.recurringOccurrenceDao().insert(
            RecurringOccurrence(
                sourceType = "RECURRING_RULE",
                sourceId = 100L,
                occurrenceKey = "100|20260501|MONTHLY",
                dueDate = may1,
                status = "PLANNED",
                expectedAmount = 12.99,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = "Netflix",
                createdAt = may1,
                updatedAt = may1
            )
        )
        assertTrue("PLANNED occurrence inserted", occurrenceId > 0L)

        // WHEN: insert a RecurringReminderDelivery linked to it
        val reminderId = db.recurringReminderDeliveryDao().insert(
            RecurringReminderDelivery(
                occurrenceId = occurrenceId,
                reminderWindow = "DUE_DAY",
                scheduledAt = may1,
                status = "SCHEDULED",
                createdAt = may1
            )
        )

        // THEN: reminder exists with correct occurrenceId
        assertTrue("Reminder ID should be positive", reminderId > 0L)

        val loaded = db.recurringReminderDeliveryDao().getById(reminderId)
        assertNotNull("Reminder should exist in DB", loaded)
        assertEquals("Reminder should reference the correct occurrence", occurrenceId, loaded!!.occurrenceId)
        assertEquals("Reminder window should be DUE_DAY", "DUE_DAY", loaded.reminderWindow)
        assertEquals("Reminder status should be SCHEDULED", "SCHEDULED", loaded.status)
        assertEquals("Scheduled at should be May 1, 2026", may1, loaded.scheduledAt)
    }
}
