package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RP-04 slice A1: verifies that SubscriptionManagementRepository delegates
 * every recurring-rule mutation to RecurringRuleLifecycleCoordinator instead
 * of writing ManualRecurringExpenseDao directly.
 */
class SubscriptionManagementRepositoryTest {

    private lateinit var repository: SubscriptionManagementRepository
    private val writeBarrier: DatabaseWriteBarrier = mockk(relaxed = true)
    private val subscriptionDao: ManualRecurringExpenseDao = mockk(relaxed = true)
    private val priceHistoryDao: SubscriptionPriceHistoryDao = mockk(relaxed = true)
    private val usageDao: SubscriptionUsageDao = mockk(relaxed = true)
    private val candidateDao: SubscriptionCandidateDao = mockk(relaxed = true)
    private val coordinator: RecurringRuleLifecycleCoordinator = mockk(relaxed = true)
    private val lazyCoordinator: dagger.Lazy<RecurringRuleLifecycleCoordinator> = mockk()

    private fun rule(id: Long, isActive: Boolean = true): ManualRecurringExpense =
        ManualRecurringExpense(
            id = id,
            merchant = "Netflix",
            amount = 15.0,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = 1_735_689_600_000L,
            createdAt = 1_735_689_600_000L,
            isSubscription = true,
            isActive = isActive
        )

    @Before
    fun setup() {
        every { lazyCoordinator.get() } returns coordinator // dagger.Lazy.get() is not suspend
        repository = SubscriptionManagementRepository(
            writeBarrier = writeBarrier,
            subscriptionDao = subscriptionDao,
            priceHistoryDao = priceHistoryDao,
            usageDao = usageDao,
            candidateDao = candidateDao,
            ruleLifecycleCoordinator = lazyCoordinator
        )
    }

    @Test
    fun `updateSubscriptionDelegatesToUpdateRule`() = runTest {
        val updated = rule(id = 7L).copy(amount = 17.99)
        repository.updateSubscription(updated)

        coVerify(exactly = 1) { coordinator.updateRule(updated) }
        coVerify(exactly = 0) { subscriptionDao.update(any()) }
    }

    @Test
    fun `deleteSubscriptionDelegatesToDeleteRule`() = runTest {
        repository.deleteSubscriptionById(42L)

        coVerify(exactly = 1) { coordinator.deleteRule(42L) }
        coVerify(exactly = 0) { subscriptionDao.deleteById(any()) }
    }

    @Test
    fun `setActiveDelegatesToActivateOrDeactivateRule`() = runTest {
        repository.setActive(1L, true)
        coVerify(exactly = 1) { coordinator.activateRule(1L) }
        coVerify(exactly = 0) { coordinator.deactivateRule(1L) }

        repository.setActive(2L, false)
        coVerify(exactly = 1) { coordinator.deactivateRule(2L) }
        coVerify(exactly = 1) { coordinator.activateRule(1L) } // unchanged by second call
        coVerify(exactly = 0) { subscriptionDao.setActiveStatus(any(), any()) }
    }

    @Test
    fun `updateSubscriptionCategoryUsesScopedDisplayOp`() = runTest {
        repository.updateSubscriptionCategory(9L, "Streaming")

        coVerify(exactly = 1) { subscriptionDao.updateSubscriptionCategory(9L, "Streaming") }
        coVerify(exactly = 0) { subscriptionDao.update(any()) }
        verify(exactly = 1) {
            writeBarrier.checkWritesAllowed("SubscriptionManagementRepository.updateSubscriptionCategory")
        }
        coVerify(exactly = 0) { coordinator.updateRule(any()) }
    }

    @Test
    fun `read paths remain direct and do not touch coordinator`() = runTest {
        coEvery { subscriptionDao.getById(3L) } returns rule(id = 3L)
        val loaded = repository.getSubscriptionById(3L)
        assertEquals("Netflix", loaded?.merchant)
        coVerify(exactly = 0) { coordinator.updateRule(any()) }
        coVerify(exactly = 0) { coordinator.deleteRule(any()) }
    }
}
