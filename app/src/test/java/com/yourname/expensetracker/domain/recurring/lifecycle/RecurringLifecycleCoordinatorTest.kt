package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [RecurringLifecycleCoordinator.generateOccurrences].
 *
 * Validates: expand rule → resolve conflicts → materialize occurrences.
 */
class RecurringLifecycleCoordinatorTest {

    private lateinit var expander: RecurringOccurrenceExpander
    private lateinit var resolver: OccurrenceConflictResolver
    private lateinit var materializer: RecurringOccurrenceMaterializer
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var manualRecurringExpenseDao: ManualRecurringExpenseDao
    private lateinit var reminderDeliveryDao: RecurringReminderDeliveryDao
    private lateinit var lifecycleEventDao: RecurringLifecycleEventDao
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var coordinator: RecurringLifecycleCoordinator

    private val now = 1_712_000_000_000L
    private val startDate = now
    private val endDate = now + 30L * 24L * 60L * 60L * 1000L // 30 days later

    @Before
    fun setup() {
        expander = mockk(relaxed = true)
        resolver = mockk(relaxed = true)
        materializer = mockk(relaxed = true)
        occurrenceDao = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        manualRecurringExpenseDao = mockk(relaxed = true)
        reminderDeliveryDao = mockk(relaxed = true)
        lifecycleEventDao = mockk(relaxed = true)
        restoreMaintenanceMode = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { restoreMaintenanceMode.isWritesAllowed() } returns true

        coordinator = RecurringLifecycleCoordinator(
            expander = expander,
            resolver = resolver,
            materializer = materializer,
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = manualRecurringExpenseDao,
            reminderDeliveryDao = reminderDeliveryDao,
            lifecycleEventDao = lifecycleEventDao,
            restoreMaintenanceMode = restoreMaintenanceMode
        )
    }

    @Test
    fun `generateOccurrences expands rule and materializes occurrences`() = runTest {
        val rule = ManualRecurringExpense(
            id = 1L,
            merchant = "Netflix",
            amount = 15.99,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = now
        )
        coEvery { manualRecurringExpenseDao.getById(1L) } returns rule

        val candidates = listOf(
            RecurringOccurrenceExpander.OccurrenceCandidate(
                occurrenceKey = "1|$now|MONTHLY",
                dueDate = now,
                expectedAmount = 15.99,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = "Netflix",
                categoryId = null,
                sourceType = "RECURRING_RULE",
                sourceId = 1L
            )
        )
        coEvery { expander.expand(any()) } returns candidates

        val candidate = RecurringOccurrenceExpander.OccurrenceCandidate(
            occurrenceKey = "1|$now|MONTHLY",
            dueDate = now,
            expectedAmount = 15.99,
            expectedCurrency = "EUR",
            frequency = "MONTHLY",
            merchant = "Netflix",
            categoryId = null,
            sourceType = "RECURRING_RULE",
            sourceId = 1L
        )
        val resolved = listOf(
            OccurrenceConflictResolver.ResolvedOccurrence(
                candidate = candidate,
                status = "PLANNED"
            )
        )
        coEvery { resolver.resolve(any(), any()) } returns resolved

        val materializationResult = RecurringOccurrenceMaterializer.MaterializationResult(
            created = 1,
            updated = 0,
            skipped = 0,
            remindersCreated = 1
        )
        coEvery { materializer.materialize(any(), any()) } returns materializationResult

        val result = coordinator.generateOccurrences(
            ruleId = 1L,
            startDate = startDate,
            endDate = endDate,
            reminderWindows = listOf("DUE_DAY")
        )

        assertEquals(1, result.created)
        coVerify(exactly = 1) { expander.expand(any()) }
        coVerify(exactly = 1) { resolver.resolve(any(), any()) }
        coVerify(exactly = 1) { materializer.materialize(any(), any()) }
    }

    @Test
    fun `generateOccurrences throws when rule not found`() = runTest {
        coEvery { manualRecurringExpenseDao.getById(404L) } returns null

        var thrown: Throwable? = null
        try {
            coordinator.generateOccurrences(404L, startDate, endDate)
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
    }
}
