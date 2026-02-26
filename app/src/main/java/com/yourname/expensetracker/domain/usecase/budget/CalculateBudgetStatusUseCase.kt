package com.yourname.expensetracker.domain.usecase.budget

import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CalculateBudgetStatusUseCase @Inject constructor(
    private val budgetRepository: BudgetRepository
) {
    operator fun invoke(): List<com.yourname.expensetracker.domain.budget.BudgetStatus> {
        return emptyList()
    }
    
    fun getBudgetHealth(): Flow<BudgetHealth> {
        return budgetRepository.getBudgetStatuses().map { statuses ->
            val total = statuses.size
            val exceeded = statuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
            val warning = statuses.count { it.healthStatus == BudgetHealthStatus.WARNING }
            val healthy = total - exceeded - warning
            
            BudgetHealth(
                totalBudgets = total,
                healthyCount = healthy,
                warningCount = warning,
                exceededCount = exceeded,
                overallStatus = when {
                    exceeded > 0 -> BudgetHealthStatus.EXCEEDED
                    warning > 0 -> BudgetHealthStatus.WARNING
                    else -> BudgetHealthStatus.ON_TRACK
                }
            )
        }
    }
}

data class BudgetHealth(
    val totalBudgets: Int,
    val healthyCount: Int,
    val warningCount: Int,
    val exceededCount: Int,
    val overallStatus: BudgetHealthStatus
)
