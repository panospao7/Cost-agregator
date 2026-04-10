package com.yourname.expensetracker.domain.tax

import com.yourname.expensetracker.data.database.dao.ExpenseDao
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
    private val timeProvider: TimeProvider
) {
    /**
     * Estimate taxes for a period using configured tax rates.
     *
     * A.9 fix: VAT calculation now uses [ExpenseDao.getTotalSpentBetween] (aggregate
     * SQL) instead of fetching individual rows through the capped
     * [ExpenseDao.getExpensesBetween] (LIMIT 2000).  The VAT fraction is applied to
     * the aggregate total, eliminating hidden data truncation while producing the
     * same mathematical result: `SUM(effectiveAmount) * (vatRate / (1 + vatRate))`.
     * 
     * @param taxConfig The tax configuration to use (defaults to Greece if not specified)
     */
    suspend fun estimateTaxes(
        startDate: Long,
        endDate: Long,
        estimatedAnnualIncome: Double,
        taxConfig: TaxConfiguration = TaxConfigurationFactory.getCurrentConfiguration()
    ): TaxEstimate = withContext(Dispatchers.IO) {
        // A.9: Aggregate SQL replaces capped row scan for deductible total.
        // getTotalBusinessExpenses uses SUM(effectiveAmount) via
        // ExpenseDao.getTotalBusinessExpensesBetween, eliminating hidden
        // data truncation while producing the same mathematical result.
        val totalDeductible = businessExpenseRepository.getTotalBusinessExpenses(startDate, endDate)
        
        // HIGH FIX: Calculate income tax bracket from configuration
        val taxRate = calculateTaxRate(estimatedAnnualIncome, taxConfig)
        
        // HIGH FIX: Use configured VAT rate
        val vatRate = taxConfig.getVatRate()
        
        // A.9: Aggregate SQL replaces capped row scan for VAT calculation.
        // getTotalSpentBetween already filters for PURCHASE + isNotMine=0 and uses
        // effective-amount SQL, matching the original per-row filter+sum semantics.
        val totalPurchaseSpend = expenseDao.getTotalSpentBetween(startDate, endDate) ?: 0.0
        val vatPaid = totalPurchaseSpend * (vatRate / (1 + vatRate))
        
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
    private fun calculateTaxRate(income: Double, taxConfig: TaxConfiguration): Double {
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
     * 
     * @param taxConfig The tax configuration to use (defaults to current configuration)
     */
    suspend fun getTaxYearSummary(
        year: Int,
        taxConfig: TaxConfiguration = TaxConfigurationFactory.getCurrentConfiguration()
    ): TaxYearSummary = withContext(Dispatchers.IO) {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(year, 0, 1, 0, 0, 0)
        val yearStart = calendar.timeInMillis
        calendar.set(year, 11, 31, 23, 59, 59)
        val yearEnd = calendar.timeInMillis
        
        val estimate = estimateTaxes(yearStart, yearEnd, 30000.0, taxConfig) // Would get actual income
        
        // A.9: Grouped aggregate SQL replaces capped row scan for per-category
        // deduction breakdown.  getExpensesByCategory uses GROUP BY via
        // ExpenseDao.getBusinessExpensesByCategory (SUM + GROUP BY businessCategory).
        // That SQL excludes NULL-category rows (AND businessCategory IS NOT NULL),
        // so we compute the "Uncategorized" bucket as the difference between the
        // aggregate total and the sum of categorized totals, preserving the original
        // null-category → "Uncategorized" semantics.
        val categoryTotals = businessExpenseRepository.getExpensesByCategory(yearStart, yearEnd)
        val categorizedDeductions = mutableMapOf<String, Double>()
        var categorizedSum = 0.0
        for (ct in categoryTotals) {
            categorizedDeductions[ct.businessCategory] = ct.total
            categorizedSum += ct.total
        }
        val totalBusiness = businessExpenseRepository.getTotalBusinessExpenses(yearStart, yearEnd)
        val uncategorized = totalBusiness - categorizedSum
        if (uncategorized > 0.0) {
            // Merge into any existing "Uncategorized" grouped total rather
            // than overwriting it — an explicit businessCategory="Uncategorized"
            // may already be present from the grouped SQL query.
            categorizedDeductions["Uncategorized"] =
                (categorizedDeductions["Uncategorized"] ?: 0.0) + uncategorized
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
