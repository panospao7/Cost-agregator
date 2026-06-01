package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecurringExpenseRepositoryTest {

    private val dao = mockk<ManualRecurringExpenseDao>()
    private val lifecycleEventDao = mockk<RecurringLifecycleEventDao>()
    private val ruleLifecycleCoordinator = mockk<RecurringRuleLifecycleCoordinator>()

    private lateinit var repository: RecurringExpenseRepository

    @Before
    fun setUp() {
        repository = RecurringExpenseRepository(
            mockk<DatabaseWriteBarrier>(relaxed = true),
            dao,
            lifecycleEventDao,
            mockk<TimeProvider>(relaxed = true),
            // Production wraps the coordinator in dagger.Lazy<> to break a Hilt
            // dependency cycle. The test must stub the wrapper so that .get()
            // returns a real mock of the inner coordinator type — a relaxed mock
            // of dagger.Lazy returns a bare java.lang.Object (type erasure) that
            // fails the cast at RecurringExpenseRepository.addRecurringExpense.
            ruleLifecycleCoordinator = dagger.Lazy { ruleLifecycleCoordinator }
        )
    }

    @Test
    fun `addRecurringExpense uses calculator semantics for irregular`() = runTest {
        assertInsertedNextDateMatchesCalculator(RecurrenceFrequency.IRREGULAR, date(2026, 3, 5))
    }

    @Test
    fun `addRecurringExpense uses calculator semantics for semi annually`() = runTest {
        assertInsertedNextDateMatchesCalculator(RecurrenceFrequency.SEMI_ANNUALLY, date(2026, 2, 10))
    }

    @Test
    fun `addRecurringExpense uses calculator semantics for annually`() = runTest {
        assertInsertedNextDateMatchesCalculator(RecurrenceFrequency.ANNUALLY, date(2026, 1, 15))
    }

    private suspend fun assertInsertedNextDateMatchesCalculator(
        frequency: RecurrenceFrequency,
        lastDate: Long
    ) {
        val expenseSlot = slot<ManualRecurringExpense>()
        coEvery { ruleLifecycleCoordinator.createRule(capture(expenseSlot)) } returns 1L

        repository.addRecurringExpense(
            merchant = "Merchant",
            amount = 50.0,
            frequency = frequency,
            lastDate = lastDate
        )

        assertEquals(
            RecurrenceCalculator.calculateNextDate(lastDate, frequency),
            expenseSlot.captured.nextDate
        )
        coVerify(exactly = 1) { ruleLifecycleCoordinator.createRule(any()) }
    }

    private fun date(year: Int, month: Int, day: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
