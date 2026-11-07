package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * refund-netting S2b: the ExpenseRepository Expense -> ExpenseSnapshot mapping and the
 * DashboardContractsAdapter Expense -> DashboardExpense mapping carry refundNettedAmount
 * together with spendingExclusionReason.
 */
class RefundNettedAmountRepositoryCopyParityTest {

    private val reason = SpendingExclusionReason.INSTALLMENT_PURCHASE.name
    private val now = 1_712_000_000_000L

    private fun expense(id: Long = 1L, refund: Double? = 12.5, exclusionReason: String? = reason): Expense {
        val base = Expense(
            id = id,
            amount = 100.0,
            currency = "EUR",
            merchant = "Parity Merchant",
            transactionType = TransactionType.PURCHASE,
            date = now,
            categoryId = null,
            isNotMine = false,
            isManualEntry = false,
            spendingExclusionReason = exclusionReason
        )
        return if (refund == null) base else base.copy(refundNettedAmount = refund)
    }

    // ---- ExpenseRepository.toExpenseSnapshot (private, reached via public snapshot readers) ----

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)

    private fun repository() = ExpenseRepository(
        mockk<DatabaseWriteBarrier>(relaxed = true),
        mockk<AppDatabase>(relaxed = true),
        expenseDao,
        mockk<UserCorrectionDao>(relaxed = true),
        mockk<PendingReviewDao>(relaxed = true),
        mockk<MerchantCategoryRepository>(relaxed = true),
        mockk<MerchantNormalizer>(relaxed = true),
        mockk<TransferDirectionAnalytics>(relaxed = true),
        mockk<TransactionLifecycleCoordinator>(relaxed = true),
        debugExpenseAuditWriter = mockk(relaxed = true),
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
    )

    @Test
    fun `getAllExpenseSnapshots carries refundNettedAmount and spendingExclusionReason`() = runTest {
        every { expenseDao.getAllFlowUncapped() } returns flowOf(listOf(expense(1L), expense(2L, refund = null)))

        val snapshots = repository().getAllExpenseSnapshots().first()

        assertThat(snapshots[0].refundNettedAmount).isEqualTo(12.5)
        assertThat(snapshots[0].spendingExclusionReason).isEqualTo(reason)
        assertThat(snapshots[1].refundNettedAmount).isEqualTo(0.0)
    }

    @Test
    fun `getExpenseSnapshotsBetween carries refundNettedAmount and spendingExclusionReason`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns listOf(expense(1L))

        val snapshots = repository().getExpenseSnapshotsBetween(0L, now + 1)

        assertThat(snapshots.single().refundNettedAmount).isEqualTo(12.5)
        assertThat(snapshots.single().spendingExclusionReason).isEqualTo(reason)
    }

    // ---- DashboardContractsAdapter.toDomainDashboard (private, reached via observeDashboardExpenses) ----

    private fun adapter(expenseRepository: ExpenseRepository, ticker: TimeBoundaryTicker) =
        DashboardContractsAdapter(
            expenseRepository = expenseRepository,
            categoryRepository = mockk<CategoryRepository>(relaxed = true),
            budgetRepository = mockk<BudgetRepository>(relaxed = true),
            reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true),
            financialWeatherRepository = mockk<FinancialWeatherRepository>(relaxed = true),
            savingsGoalRepository = mockk<com.yourname.expensetracker.domain.savings.SavingsGoalRepository>(relaxed = true),
            analyticsRepository = mockk<AnalyticsRepository>(relaxed = true),
            recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true),
            plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true),
            timeBoundaryTicker = ticker
        )

    @Test
    fun `observeDashboardExpenses carries refundNettedAmount and spendingExclusionReason`() = runTest {
        val ticker = mockk<TimeBoundaryTicker>(relaxed = true)
        every { ticker.dayBoundaryTicks() } returns flowOf(now)
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                ExpenseWithCategory(expense = expense(1L), category = null),
                ExpenseWithCategory(expense = expense(2L, refund = null, exclusionReason = null), category = null)
            )
        )

        val result = adapter(expenseRepository, ticker).observeDashboardExpenses().first().associateBy { it.id }

        assertThat(result.getValue(1L).refundNettedAmount).isEqualTo(12.5)
        assertThat(result.getValue(1L).spendingExclusionReason).isEqualTo(reason)
        assertThat(result.getValue(2L).refundNettedAmount).isEqualTo(0.0)
    }
}
