package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * P-04-2: [PlannedExpenseRepository.skipOrDeletePlanned] skips the source occurrence through the
 * recurring lifecycle coordinator when the planned row carries a key that resolves to an
 * occurrence, and falls back to deleting the row when there is no key or the
 * key matches no occurrence or the occurrence is not skippable (coordinator returns false).
 * A transition failure raised inside the coordinator (status changed between its read and
 * update) and cancellation propagate without deleting.
 */
class PlannedExpenseRepositorySkipOrDeleteTest {

    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val plannedExpenseDao = mockk<PlannedExpenseDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)

    private val key = "RECURRING_RULE|1|1710000000000|MONTHLY"

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1_000_000L
    }

    private fun repository() = PlannedExpenseRepository(
        writeBarrier = writeBarrier,
        plannedExpenseDao = plannedExpenseDao,
        timeProvider = timeProvider,
        recurringLifecycleCoordinator = dagger.Lazy { coordinator }
    )

    @Test
    fun `P-04-2 keyed row whose occurrence is skipped calls the coordinator and does not delete`() = runTest {
        coEvery { coordinator.skipOccurrenceByKey(key) } returns true

        repository().skipOrDeletePlanned(7L, key)

        coVerify(exactly = 1) { coordinator.skipOccurrenceByKey(key) }
        coVerify(exactly = 0) { plannedExpenseDao.deletePlannedExpenseById(any()) }
    }

    @Test
    fun `P-04-2 keyed row whose coordinator returns false (no occurrence or non-skippable status) deletes by id`() = runTest {
        coEvery { coordinator.skipOccurrenceByKey(key) } returns false

        repository().skipOrDeletePlanned(7L, key)

        coVerify(exactly = 1) { coordinator.skipOccurrenceByKey(key) }
        coVerify(exactly = 1) { plannedExpenseDao.deletePlannedExpenseById(7L) }
    }

    @Test
    fun `P-04-2 row without a key deletes by id and never calls the coordinator`() = runTest {
        repository().skipOrDeletePlanned(8L, null)

        coVerify(exactly = 0) { coordinator.skipOccurrenceByKey(any()) }
        coVerify(exactly = 1) { plannedExpenseDao.deletePlannedExpenseById(8L) }
    }

    @Test
    fun `P-04-2 transition require failure racing past the coordinator pre-check propagates and the row is not deleted`() = runTest {
        coEvery { coordinator.skipOccurrenceByKey(key) } throws
            IllegalArgumentException("Invalid recurring occurrence transition")

        assertFailsWith<IllegalArgumentException> { repository().skipOrDeletePlanned(7L, key) }

        coVerify(exactly = 0) { plannedExpenseDao.deletePlannedExpenseById(any()) }
    }

    @Test
    fun `P-04-2 cancellation during the skip propagates and the row is not deleted`() = runTest {
        coEvery { coordinator.skipOccurrenceByKey(key) } throws CancellationException("test cancellation")

        assertFailsWith<CancellationException> { repository().skipOrDeletePlanned(7L, key) }

        coVerify(exactly = 0) { plannedExpenseDao.deletePlannedExpenseById(any()) }
    }
}
