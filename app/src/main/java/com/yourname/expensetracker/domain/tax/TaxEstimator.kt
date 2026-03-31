package com.yourname.expensetracker.domain.tax

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calculates estimated taxes based on spending and income patterns.
 */
@Singleton
class TaxEstimator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val businessExpenseRepository: BusinessExpenseRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        // Simplified tax rates - would be configurable per country
        const val DEFAULT_VAT_RATE = 0.24 // 24% VAT for Greece
        const val ESTIMATED_TAX_BRACKET_1 = 0.09 // 9% for low income
        const val ESTIMATED_TAX_BRACKET_2 = 0.22 // 22% for medium income
        const val ESTIMATED_TAX_BRACKET_3 = 0.32 // 32% for high income
    }
    
    /**
     * Estimate taxes for a period.
     */
    suspend fun estimateTaxes(
        startDate: Long,
        endDate: Long,
        estimatedAnnualIncome: Double
    ): TaxEstimate = withContext(Dispatchers.IO) {
        // Get business expenses (deductible)
        val businessExpenses = businessExpenseRepository.getBusinessExpenses(startDate, endDate)
        var totalDeductible = 0.0
        for (expense in businessExpenses) {
            totalDeductible += expense.amount
        }
        
        // Calculate income tax bracket
        val taxRate = when {
            estimatedAnnualIncome < 10000 -> ESTIMATED_TAX_BRACKET_1
            estimatedAnnualIncome < 30000 -> ESTIMATED_TAX_BRACKET_2
            else -> ESTIMATED_TAX_BRACKET_3
        }
        
        // Estimate VAT paid (simplified)
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        var vatPaid = 0.0
        for (expense in expenses) {
            // Assume most purchases include VAT
            if (expense.transactionType.name == "PURCHASE") {
                val vatAmount = expense.amount * (DEFAULT_VAT_RATE / (1 + DEFAULT_VAT_RATE))
                vatPaid += vatAmount
            }
        }
        
        val monthsInPeriod = ((endDate - startDate).toDouble() / (30 * 24 * 60 * 60 * 1000))
        val monthlyIncome = if (monthsInPeriod > 0) estimatedAnnualIncome / 12 else estimatedAnnualIncome
        
        val taxableIncome = maxOf(monthlyIncome - totalDeductible, 0.0)
        val estimatedIncomeTax = taxableIncome * taxRate
        
        TaxEstimate(
            startDate = startDate,
            endDate = endDate,
            estimatedIncome = monthlyIncome,
            deductibleExpenses = totalDeductible,
            taxableIncome = taxableIncome,
            estimatedIncomeTax = estimatedIncomeTax,
            estimatedVatPaid = vatPaid,
            effectiveTaxRate = if (monthlyIncome > 0) (estimatedIncomeTax / monthlyIncome) * 100 else 0.0,
            notes = "Simplified estimate. Consult tax professional for accurate filing."
        )
    }
    
    /**
     * Get tax year summary for annual filing.
     */
    suspend fun getTaxYearSummary(year: Int): TaxYearSummary = withContext(Dispatchers.IO) {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(year, 0, 1, 0, 0, 0)
        val yearStart = calendar.timeInMillis
        calendar.set(year, 11, 31, 23, 59, 59)
        val yearEnd = calendar.timeInMillis
        
        val estimate = estimateTaxes(yearStart, yearEnd, 30000.0) // Would get actual income
        
        val businessExpenses = businessExpenseRepository.getBusinessExpenses(yearStart, yearEnd)
        val categorizedDeductions = mutableMapOf<String, Double>()
        for (expense in businessExpenses) {
            val category = expense.businessCategory ?: "Uncategorized"
            val current = categorizedDeductions[category] ?: 0.0
            categorizedDeductions[category] = current + expense.amount
        }
        
        TaxYearSummary(
            year = year,
            totalIncome = estimate.estimatedIncome * 12, // Annualized
            totalDeductibleExpenses = estimate.deductibleExpenses,
            totalVatPaid = estimate.estimatedVatPaid * 12, // Annualized
            categorizedDeductions = categorizedDeductions,
            estimatedTaxOwed = estimate.estimatedIncomeTax * 12, // Annualized
            mileageDeduction = businessExpenseRepository.getTotalMileageDeduction(yearStart, yearEnd)
        )
    }
}

data class TaxEstimate(
    val startDate: Long,
    val endDate: Long,
    val estimatedIncome: Double,
    val deductibleExpenses: Double,
    val taxableIncome: Double,
    val estimatedIncomeTax: Double,
    val estimatedVatPaid: Double,
    val effectiveTaxRate: Double,
    val notes: String
)

data class TaxYearSummary(
    val year: Int,
    val totalIncome: Double,
    val totalDeductibleExpenses: Double,
    val totalVatPaid: Double,
    val categorizedDeductions: Map<String, Double>,
    val estimatedTaxOwed: Double,
    val mileageDeduction: Double
)
