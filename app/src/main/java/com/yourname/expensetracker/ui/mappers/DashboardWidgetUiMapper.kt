package com.yourname.expensetracker.ui.mappers

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.dashboard.DomainBlockStatus
import com.yourname.expensetracker.domain.model.dashboard.DomainDayBudgetStatus
import com.yourname.expensetracker.domain.model.dashboard.DomainExpenseSummary
import com.yourname.expensetracker.ui.components.BlockStatus
import com.yourname.expensetracker.ui.components.DayBudgetStatus

fun DomainBlockStatus.toUi(): BlockStatus = when (this) {
    DomainBlockStatus.UNDER_BUDGET -> BlockStatus.UNDER_BUDGET
    DomainBlockStatus.OVER_BUDGET -> BlockStatus.OVER_BUDGET
    DomainBlockStatus.FUTURE -> BlockStatus.FUTURE
    DomainBlockStatus.TODAY -> BlockStatus.TODAY
    DomainBlockStatus.BILL_DAY -> BlockStatus.BILL_DAY
    DomainBlockStatus.NO_DATA -> BlockStatus.NO_DATA
}

private fun DomainExpenseSummary.toUiExpense(): Expense = Expense(
    id = id,
    amount = amount,
    merchant = description,
    transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
    date = date,
    categoryId = categoryName?.toLongOrNull()
)

fun DomainDayBudgetStatus.toUi(): DayBudgetStatus = DayBudgetStatus(
    dayOfMonth = dayOfMonth,
    date = date,
    actualSpent = actualSpent,
    targetBudget = targetBudget,
    isToday = isToday,
    status = status.toUi(),
    baseTarget = baseTarget,
    recurringImpact = recurringImpact,
    plannedImpact = plannedImpact,
    recurringItems = recurringItems,
    plannedItems = plannedItems,
    topTransactions = topTransactions.map { it.toUiExpense() }
)

fun List<DomainDayBudgetStatus>.toUi(): List<DayBudgetStatus> = map { it.toUi() }
