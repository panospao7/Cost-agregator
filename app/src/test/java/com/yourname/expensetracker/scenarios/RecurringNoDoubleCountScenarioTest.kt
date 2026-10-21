package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario test for recurring planned occurrences at the data layer.
 *
 * Coverage that used to live here and now lives elsewhere (verified duplicates):
 * - PLANNED -> PAID status transition and updatedAt: RecurringOccurrenceDaoTest
 *   `update status from PLANNED to PAID`.
 * - Planned occurrences do not double-count in dashboard totals:
 *   RecurringPlannedActualNoDoubleCountGoldenTest (golden JSON pins dashboardTotal,
 *   transaction count, paid/planned occurrence counts).
 * - Reminder delivery insert/getById round-trip: RecurringReminderDeliveryDaoTest.
 *
 * Kept here: full-field round-trip of a rule's three monthly occurrences, which the
 * DAO tests do not assert (they check only counts, status, merchant, ordering).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringNoDoubleCountScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private val may1 = dateMs(2026, 5, 1)
    private val june1 = dateMs(2026, 6, 1)
    private val july1 = dateMs(2026, 7, 1)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private class ExpectedOccurrence(val name: String, val key: String, val dueDate: Long)

    @Test
    fun `multiple occurrences for same recurring rule persist all fields with distinct due dates`() = runTest {
        // GIVEN: 3 occurrences (PLANNED for May, June, July - all Netflix 12.99 EUR)
        val expectedRows = listOf(
            ExpectedOccurrence("May", "100|20260501|MONTHLY", may1),
            ExpectedOccurrence("June", "100|20260601|MONTHLY", june1),
            ExpectedOccurrence("July", "100|20260701|MONTHLY", july1)
        )

        db.recurringOccurrenceDao().insertAll(
            expectedRows.map { row ->
                RecurringOccurrence(
                    sourceType = "RECURRING_RULE",
                    sourceId = 100L,
                    occurrenceKey = row.key,
                    dueDate = row.dueDate,
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
            }
        )

        // WHEN: querying all occurrences for this rule
        val occurrences = db.recurringOccurrenceDao().getBySource(
            sourceType = "RECURRING_RULE",
            sourceId = 100L
        )

        // THEN: exactly 3, with 3 distinct due dates
        assertEquals("occurrences size", 3, occurrences.size)
        assertEquals("distinct due dates", 3, occurrences.map { it.dueDate }.toSet().size)

        // AND: every row round-trips all fields (rows are read-only here, so they cannot interfere)
        assertEquals("expected rows size", 3, expectedRows.size)
        val failures = mutableListOf<String>()
        for (row in expectedRows) {
            try {
                val loaded = db.recurringOccurrenceDao().getByKey(row.key)
                assertNotNull("${row.name}: occurrence should exist", loaded)
                assertEquals("${row.name}: sourceType", "RECURRING_RULE", loaded!!.sourceType)
                assertEquals("${row.name}: sourceId", 100L, loaded.sourceId)
                assertEquals("${row.name}: occurrenceKey", row.key, loaded.occurrenceKey)
                assertEquals("${row.name}: dueDate", row.dueDate, loaded.dueDate)
                assertEquals("${row.name}: status", "PLANNED", loaded.status)
                assertEquals("${row.name}: merchant", "Netflix", loaded.merchant)
                assertEquals("${row.name}: expectedAmount", 12.99, loaded.expectedAmount, 0.001)
                assertEquals("${row.name}: expectedCurrency", "EUR", loaded.expectedCurrency)
                assertEquals("${row.name}: frequency", "MONTHLY", loaded.frequency)
            } catch (e: AssertionError) {
                failures += "${row.name}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString(separator = "\n"))
    }
}
