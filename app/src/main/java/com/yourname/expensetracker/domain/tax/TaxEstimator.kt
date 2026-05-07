package com.yourname.expensetracker.domain.tax

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HIGH FIX (HIGH-6): Calculates estimated taxes using configurable tax rates.
 * 
 * Replaces hardcoded tax rates with TaxConfiguration for country-specific rates.
 * Supports multiple tax systems and can be extended for per-user configuration.
 *
 * TODO (T01/T06): Use MoneyAggregate for multi-currency safety.
 * Currently raw-sums deductible expenses, income, and VAT across potentially
 * mixed currencies. Wrap per-currency totals in MoneyAggregate.
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
     * B.8 Batch 7: deductible expenses and VAT both use business-only effective
     * purchase aggregates, income is aligned to the requested period, and income
     * tax brackets are applied cumulatively for the covered fraction of a tax year.
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

        val periodYearFraction = calculatePeriodYearFraction(startDate, endDate)
        val periodIncome = estimatedAnnualIncome * periodYearFraction

        // HIGH FIX: Use configured VAT rate
        val vatRate = taxConfig.getVatRate()

        // B.8 Batch 7: VAT must use business-only purchase spend, not all purchases.
        val vatPaid = totalDeductible * (vatRate / (1 + vatRate))

        val taxableIncome = maxOf(periodIncome - totalDeductible, 0.0)
        val estimatedIncomeTax = calculateProgressiveTax(
            income = taxableIncome,
            taxConfig = taxConfig,
            periodYearFraction = periodYearFraction
        )
        
        TaxEstimate(
            startDate = startDate,
            endDate = endDate,
            estimatedIncome = periodIncome,
            deductibleExpenses = totalDeductible,
            taxableIncome = taxableIncome,
            estimatedIncomeTax = estimatedIncomeTax,
            estimatedVatPaid = vatPaid,
            effectiveTaxRate = if (periodIncome > 0) (estimatedIncomeTax / periodIncome) * 100 else 0.0,
            notes = "Estimate using ${taxConfig.getCountryCode()} tax rates. Consult tax professional for accurate filing."
        )
    }
    
    /**
     * B.8 Batch 7: Apply configured brackets cumulatively, scaled to the
     * requested period instead of using a single flat bracket rate.
     */
    private fun calculateProgressiveTax(
        income: Double,
        taxConfig: TaxConfiguration,
        periodYearFraction: Double
    ): Double {
        if (income <= 0.0 || periodYearFraction <= 0.0) return 0.0

        var totalTax = 0.0
        val scaledBrackets = taxConfig.getTaxBrackets().sortedBy { it.minIncome }

        for (bracket in scaledBrackets) {
            val lowerBound = bracket.minIncome * periodYearFraction
            val upperBound = bracket.maxIncome?.times(periodYearFraction) ?: Double.POSITIVE_INFINITY

            if (income <= lowerBound) {
                break
            }

            val taxableAtRate = minOf(income, upperBound) - lowerBound
            if (taxableAtRate > 0.0) {
                totalTax += taxableAtRate * bracket.rate
            }
        }

        return totalTax
    }

    /**
     * Converts a date range into the equivalent fraction of tax years covered,
     * splitting across calendar years when needed.
     */
    private fun calculatePeriodYearFraction(startDate: Long, endDate: Long): Double {
        if (endDate <= startDate) return 0.0

        var cursor = startDate
        var totalFraction = 0.0

        while (cursor < endDate) {
            val calendar = Calendar.getInstance().apply { timeInMillis = cursor }
            val year = calendar.get(Calendar.YEAR)
            val yearStart = startOfYear(year)
            val nextYearStart = startOfYear(year + 1)
            val segmentEnd = minOf(endDate, nextYearStart)
            val yearDuration = nextYearStart - yearStart

            if (yearDuration <= 0L || segmentEnd <= cursor) {
                break
            }

            totalFraction += (segmentEnd - cursor).toDouble() / yearDuration.toDouble()
            cursor = segmentEnd
        }

        return totalFraction
    }

    private fun startOfYear(year: Int): Long {
        return Calendar.getInstance().apply {
            set(year, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
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
        val yearStart = startOfYear(year)
        val yearEnd = startOfYear(year + 1)
        val totalIncome = expenseDao.getTotalDepositsForPeriod(yearStart, yearEnd)

        val estimate = estimateTaxes(yearStart, yearEnd, totalIncome, taxConfig)
        
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
            totalIncome = estimate.estimatedIncome,
            totalDeductibleExpenses = estimate.deductibleExpenses,
            totalVatPaid = estimate.estimatedVatPaid,
            categorizedDeductions = categorizedDeductions,
            estimatedTaxOwed = estimate.estimatedIncomeTax,
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
