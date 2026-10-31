package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFacts
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseMoney
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFactsReader
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentFlaggedExpense
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only implementation of [InstallmentExpenseFactsReader]. It lives outside the plan packages
 * because plan code may not reference the expense or recurring stores (INV-24, INV-25). It performs
 * no writes; every mutation stays on the transaction lifecycle coordinator and the plan coordinator.
 */
@Singleton
class InstallmentExpenseFactsReaderImpl @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val recurringOccurrenceDao: RecurringOccurrenceDao,
    private val categoryDao: CategoryDao
) : InstallmentExpenseFactsReader {

    override suspend fun findExpenseFacts(expenseId: Long): InstallmentExpenseFacts? {
        val expense = expenseDao.getById(expenseId) ?: return null
        return InstallmentExpenseFacts(
            expenseId = expense.id,
            currency = expense.currency,
            dateMillis = expense.date,
            isSpendingType = InstallmentExpenseFacts.isSpendingType(expense.transactionType),
            isNotMine = expense.isNotMine,
            isSharedExpense = expense.isSharedExpense,
            hasSplit = InstallmentExpenseFacts.hasSplit(expense.splitTemplateId, expense.splitVisualization),
            spendingExclusionReason = expense.spendingExclusionReason,
            categoryId = expense.categoryId,
            paymentMethod = expense.paymentMethod,
            linkedRecurringOccurrenceId = recurringOccurrenceDao.getByLinkedExpenseId(expenseId)?.id
        )
    }

    override suspend fun categoryExists(categoryId: Long): Boolean =
        categoryDao.getById(categoryId) != null

    override suspend fun findFlaggedExpenses(): List<InstallmentFlaggedExpense> =
        expenseDao.getSpendingExclusionFlagRows().map {
            InstallmentFlaggedExpense(it.id, it.spendingExclusionReason)
        }

    override suspend fun findExpenseMoney(expenseId: Long): InstallmentExpenseMoney? =
        expenseDao.getById(expenseId)?.toInstallmentMoney()

    override suspend fun findExpenseMoneyBetween(
        startMillisInclusive: Long,
        endMillisExclusive: Long
    ): List<InstallmentExpenseMoney> =
        expenseDao.getExpensesBetweenUncapped(startMillisInclusive, endMillisExclusive).map { it.toInstallmentMoney() }

    private fun Expense.toInstallmentMoney() = InstallmentExpenseMoney(
        expenseId = id,
        currency = currency,
        dateMillis = date,
        amount = amount,
        effectiveAmount = effectiveAmount,
        categoryId = categoryId
    )
}
