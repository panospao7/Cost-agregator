package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
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
import io.mockk.verify
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
    private lateinit var database: AppDatabase
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var plannedExpenseDao: PlannedExpenseDao
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
        database = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        plannedExpenseDao = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { restoreMaintenanceMode.isWritesAllowed() } returns true

        coordinator = RecurringLifecycleCoordinator(
            database = database,
            expander = expander,
            resolver = resolver,
            materializer = materializer,
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = manualRecurringExpenseDao,
            reminderDeliveryDao = reminderDeliveryDao,
            lifecycleEventDao = lifecycleEventDao,
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = writeBarrier,
            plannedExpenseDao = plannedExpenseDao,
            eventWriter = mockk(relaxed = true)
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
        coEvery { materializer.materialize(any(), any<RecurringOccurrenceMaterializer.MaterializationOptions>()) } returns materializationResult

        val result = coordinator.generateOccurrences(
            ruleId = 1L,
            startDate = startDate,
            endDate = endDate,
            options = OccurrenceGenerationOptions(
                createReminderDeliveries = true,
                reminderWindows = listOf("DUE_DAY"),
                generationSource = OccurrenceGenerationSource.TEST
            )
        )

        assertEquals(1, result.created)
        coVerify(exactly = 1) { expander.expand(any()) }
        coVerify(exactly = 1) { resolver.resolve(any(), any()) }
        coVerify(exactly = 1) { materializer.materialize(any(), any<RecurringOccurrenceMaterializer.MaterializationOptions>()) }
    }

    @Test
    fun `generateOccurrences throws when rule not found`() = runTest {
        coEvery { manualRecurringExpenseDao.getById(404L) } returns null

        var thrown: Throwable? = null
        try {
            coordinator.generateOccurrences(
                ruleId = 404L,
                startDate = startDate,
                endDate = endDate,
                options = OccurrenceGenerationOptions(
                    createReminderDeliveries = false,
                    generationSource = OccurrenceGenerationSource.TEST
                )
            )
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalArgumentException)
    }

    // ── P6-CURRENT-024: read-only projection performs NO DB writes ─────────

    @Test
    fun `projectOccurrences returns occurrences with no DB writes`() = runTest {
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
                occurrenceKey = "RECURRING_RULE|1|$now|MONTHLY",
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
        coEvery { resolver.resolve(any(), any()) } returns listOf(
            OccurrenceConflictResolver.ResolvedOccurrence(
                candidate = candidates.first(),
                status = "PLANNED"
            )
        )

        val result = coordinator.projectOccurrences(
            ruleId = 1L,
            startDate = startDate,
            endDate = endDate
        )

        // Returns projected occurrences computed in memory.
        assertEquals(1, result.size)
        assertEquals("PLANNED", result.first().status)
        assertEquals("RECURRING_RULE|1|$now|MONTHLY", result.first().occurrenceKey)
        // Transient: never persisted (id stays 0).
        assertEquals(0L, result.first().id)

        // CRITICAL: no materialization, no DAO inserts/updates, no events, no
        // write barrier — this is a pure read.
        coVerify(exactly = 0) { materializer.materialize(any(), any()) }
        coVerify(exactly = 0) { materializer.materializeInCurrentTransaction(any(), any()) }
        coVerify(exactly = 0) { occurrenceDao.insert(any()) }
        coVerify(exactly = 0) { occurrenceDao.insertAll(any()) }
        coVerify(exactly = 0) { occurrenceDao.update(any()) }
        coVerify(exactly = 0) { lifecycleEventDao.insert(any()) }
        verify(exactly = 0) { writeBarrier.checkWritesAllowed(any<String>()) }
    }

    @Test
    fun `projectOccurrences returns empty for inactive rule without writes`() = runTest {
        val rule = ManualRecurringExpense(
            id = 2L,
            merchant = "OldSub",
            amount = 5.0,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = now,
            isActive = false
        )
        coEvery { manualRecurringExpenseDao.getById(2L) } returns rule

        val result = coordinator.projectOccurrences(2L, startDate, endDate)

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { materializer.materialize(any(), any()) }
        coVerify(exactly = 0) { occurrenceDao.insert(any()) }
        verify(exactly = 0) { writeBarrier.checkWritesAllowed(any<String>()) }
    }

    @Test
    fun `projectOccurrences throws when rule not found`() = runTest {
        coEvery { manualRecurringExpenseDao.getById(404L) } returns null

        var thrown: Throwable? = null
        try {
            coordinator.projectOccurrences(404L, startDate, endDate)
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(thrown is IllegalArgumentException)
    }
}
