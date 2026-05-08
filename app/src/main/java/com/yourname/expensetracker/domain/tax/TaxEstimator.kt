package com.yourname.expensetracker.domain.tax

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
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
 * T01: Deductible expenses use MoneyAggregate via buildDeductibleAggregate().
 * Income aggregates via buildIncomeAggregate(). Both use CurrencyConverter.
 */
// TODO (PR-E21): Add TaxSettings with countryCode, filingCurrency, fiscalYearStartMonth.
// Persist via TaxSettingsRepository. Use selected country for rates.
//
// ── TaxSettings Entity & Repository Implementation Plan ─────────────────────
//
// 1. Create TaxSettings entity (@Entity tableName = "tax_settings")
//    Fields:
//      id: Long (PK, single-row pattern, always id=1)
//      countryCode: String (ISO 3166-1 alpha-2, e.g. "GR", "DE", "US")
//      filingCurrency: String (ISO 4217, e.g. "EUR", "USD")
//      fiscalYearStartMonth: Int (1=January, 4=April, etc.)
//      fiscalYearStartDay: Int (default 1)
//      taxRatePreset: String? (nullable, e.g. "FREELANCER_GR", "STANDARD_DE")
//      updatedAt: Long
//
// 2. Create TaxSettingsDao (@Dao)
//    - get(): suspend fun TaxSettings? (single-row, fetches id=1)
//    - upsert(settings): suspend fun (INSERT OR REPLACE with id=1)
//    - delete(): suspend fun (resets to defaults)
//
// 3. Create TaxSettingsRepository
//    - getSettings(): Flow<TaxSettings> (emits defaults if none stored)
//    - updateSettings(settings): suspend fun
//    - resetToDefaults(): suspend fun
//    Uses DataStore or Room (prefer Room for consistency with other settings).
//
// 4. Integration in TaxEstimator
//    - Inject TaxSettingsRepository
//    - In estimateTaxes(): call settingsRepository.getSettings().first() to
//      resolve countryCode, then load TaxConfiguration via TaxConfigurationFactory
//      using the stored countryCode instead of getCurrentConfiguration() default.
//    - In getTaxYearSummary(): use fiscalYearStartMonth/Day to compute correct
//      year range instead of assuming Jan 1.
//
// 5. Migration path
//    - Existing users: on first read with no settings row, return defaults
//      (countryCode = "GR", filingCurrency = "EUR", fiscalYearStartMonth = 1)
//    - Settings screen: new TaxSettingsScreen or embedded in existing Preferences
//
// See TaxConfiguration entity in database for existing fields.
@Singleton
class TaxEstimator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val businessExpenseRepository: BusinessExpenseRepository,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
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
    ): TaxEstimate = withContext(ioDispatcher) {
        // A.9: Aggregate SQL replaces capped row scan for deductible total.
        // getTotalBusinessExpenses uses SUM(effectiveAmount) via
        // ExpenseDao.getTotalBusinessExpensesBetween, eliminating hidden
        // data truncation while producing the same mathematical result.
        // T01: Use MoneyAggregate with per-currency buckets.
        val deductibleAggregate = buildDeductibleAggregate(startDate, endDate)
        val totalDeductible = deductibleAggregate.displayAmount

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
     * Build a MoneyAggregate for deductible business expenses grouped by currency.
     *
     * T01: Replaces the raw-Double [BusinessExpenseRepository.getTotalBusinessExpenses]
     * with a per-currency aggregate so mixed-currency deductible expenses are
     * correctly represented without silently raw-summing across currencies.
     *
     * Now converts all currencies to home currency using CurrencyConverter.
     */
    private suspend fun buildDeductibleAggregate(startMs: Long, endMs: Long): MoneyAggregate {
        val currencyTotals = expenseDao.getBusinessExpensesBetweenByCurrency(startMs, endMs)
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val buckets = currencyTotals.map { Pair(it.total, it.currency) }
        val counts = currencyTotals.map { it.txCount }
        return MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, counts)
    }

    /**
     * Build a MoneyAggregate for income (deposits) grouped by currency.
     *
     * T01: Extends the MoneyAggregate pattern to income totals so that
     * mixed-currency income is correctly represented without raw-summing.
     */
    private suspend fun buildIncomeAggregate(startMs: Long, endMs: Long): MoneyAggregate {
        val currencyTotals = expenseDao.getDepositTotalsBetweenByCurrency(startMs, endMs)
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val buckets = currencyTotals.map { Pair(it.total, it.currency) }
        val counts = currencyTotals.map { it.txCount }
        return MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, counts)
    }

    /**
     * Get tax year summary for annual filing.
     * 
     * @param taxConfig The tax configuration to use (defaults to current configuration)
     */
    suspend fun getTaxYearSummary(
        year: Int,
        taxConfig: TaxConfiguration = TaxConfigurationFactory.getCurrentConfiguration()
    ): TaxYearSummary = withContext(ioDispatcher) {
        val yearStart = startOfYear(year)
        val yearEnd = startOfYear(year + 1)
        val incomeAggregate = buildIncomeAggregate(yearStart, yearEnd)
        val totalIncome = incomeAggregate.displayAmount

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
