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
 * HIGH FIX (HIGH-6): Calculates estimated taxes using configurable tax rates.
 * 
 * Replaces hardcoded tax rates with TaxConfiguration for country-specific rates.
 * Supports multiple tax systems and can be extended for per-user configuration.
 */
@Singleton
class TaxEstimator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val businessExpenseRepository: BusinessExpenseRepository,
    private val timeProvider: TimeProvider,
    private val taxConfig: TaxConfiguration = TaxConfigurationFactory.getCurrentConfiguration()
) {
    /**
     * Estimate taxes for a period using configured tax rates.
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
        
        // HIGH FIX: Calculate income tax bracket from configuration
        val taxRate = calculateTaxRate(estimatedAnnualIncome)
        
        // HIGH FIX: Use configured VAT rate
        val vatRate = taxConfig.getVatRate()
        
        // Estimate VAT paid (simplified)
        val expenses = expenseDao.getExpensesBetween(startDate, endDate)
        var vatPaid = 0.0
        for (expense in expenses) {
            // Assume most purchases include VAT
            if (expense.transactionType.name == "PURCHASE") {
                val vatAmount = expense.amount * (vatRate / (1 + vatRate))
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
            notes = "Estimate using ${taxConfig.getCountryCode()} tax rates. Consult tax professional for accurate filing."
        )
    }
    
    /**
     * HIGH FIX: Calculate tax rate based on income using configured brackets.
     * Replaces hardcoded bracket logic.
     */
    private fun calculateTaxRate(income: Double): Double {
        val brackets = taxConfig.getTaxBrackets()
        
        for (bracket in brackets) {
            if (income >= bracket.minIncome && (bracket.maxIncome == null || income < bracket.maxIncome)) {
                return bracket.rate
            }
        }
        
        // Default to last bracket if above all
        return brackets.lastOrNull()?.rate ?: 0.20
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
