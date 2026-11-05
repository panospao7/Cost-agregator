package com.yourname.expensetracker.testfixtures

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus

/**
 * A complete status for a forecast fixture: known limit equal to the budget amount, no partial
 * data and no conversion warning. For tests whose subject is not the status gate.
 */
fun completeBudgetStatus(budget: Budget): BudgetStatus = BudgetStatus(
    budget = budget,
    category = null,
    spentAmount = 0.0,
    remainingAmount = budget.amount,
    percentUsed = 0f,
    healthStatus = BudgetHealthStatus.ON_TRACK,
    periodStart = 0L,
    periodEnd = 0L,
    effectiveLimit = budget.amount,
    percentKnown = true,
    isPartial = false,
    conversionWarning = null
)
