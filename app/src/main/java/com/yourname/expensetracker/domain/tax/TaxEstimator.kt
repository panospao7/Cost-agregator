package com.yourname.expensetracker.domain.tax

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.data.repository.TaxSettingsRepository
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
 * TAX CONTAINMENT NOTICE (2026-05-08):
 * The entire Tax feature is ⏭ DEFERRED_DESIGN. The code below works for basic
 * single-currency EUR cases but has known gaps (T01-T10). The feature is
 * contained behind a feature flag and NOT exposed in the production UI.
 *
 * No new tax features should be added without a full design review.
 * The existing implementation is kept for demo/testing purposes only.
 *
 * HIGH FIX (HIGH-6): Calculates estimated taxes using configurable tax rates.
 * 
 * Replaces hardcoded tax rates with TaxConfiguration for country-specific rates.
 * Supports multiple tax systems and can be extended for per-user configuration.
 *
 * T01: Deductible expenses use MoneyAggregate via buildDeductibleAggregate().
 * Income aggregates via buildIncomeAggregate(). Both use CurrencyConverter.
 */
// T03/T09-FIXED: TaxSettings consumed (countryCode, filingCurrency, fiscalYearStartMonth/Day).
// See TaxSettingsRepository for the current implementation.
// TaxRateProvider available via constructor injection (DemoTaxRateProvider by default).
@Singleton
class TaxEstimator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val businessExpenseRepository: BusinessExpenseRepository,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val taxSettings: TaxSettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    // T08-FIXED: TaxRateProvider available for future use in rate lookups.
    private val taxRateProvider: TaxRateProvider
) {
    /**
     * Estimate taxes for a period using configured tax rates.
     *
     * B.8 Batch 7: deductible expenses and VAT both use business-only effective
     * purchase aggregates, income is aligned to the requested period, and income
     * tax brackets are applied cumulatively for the covered fraction of a tax year.
     * 
     * PR-T1: Country and filing currency are resolved from [TaxSettingsRepository].
     * The default [taxConfig] is loaded via [TaxConfigurationFactory.getConfiguration]
     * using the stored tax country code instead of a hardcoded default.
     *
     * @param taxConfig The tax configuration to use (defaults to the configured tax country)
     */
    suspend fun estimateTaxes(
        startDate: Long,
        endDate: Long,
        estimatedAnnualIncome: Double,
        taxConfig: TaxConfiguration = TaxConfigurationFactory.getConfiguration(taxSettings.getTaxCountry())
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

        // Filing currency for display purposes
        val filingCurrency = taxSettings.getFilingCurrency()

        // T04-FIXED: VAT fields renamed for clarity; confidence marked LOW when estimated from standard rate.
        // T04-FIXED: TaxRateProvider used as supplementary rate source. Falls back to TaxConfiguration for backward compat.
        // HIGH FIX: Use configured VAT rate
        val providerRate = try {
            val result = taxRateProvider.getRate(taxSettings.getTaxCountry(), null)
            result.standardVatRate
        } catch (e: Exception) {
            null
        }
        val vatRate = if (providerRate != null) providerRate else taxConfig.getVatRate()

        // B.8 Batch 7: VAT must use business-only purchase spend, not all purchases.
        val vatPaid = totalDeductible * (vatRate / (1 + vatRate))

        val taxableIncome = maxOf(periodIncome - totalDeductible, 0.0)
        val estimatedIncomeTax = calculateProgressiveTax(
            income = taxableIncome,
            taxConfig = taxConfig,
            periodYearFraction = periodYearFraction
        )

        // Build VAT aggregate from deductible
        val vatAggregate = if (vatRate > 0.0) {
            val factor = vatRate / (1.0 + vatRate)
            val buckets = deductibleAggregate.sourceBuckets.map {
                Pair(it.amount * factor, it.currency.code)
            }
            MoneyAggregateBuilder.fromBuckets(buckets, filingCurrency, currencyConverter)
        } else {
            MoneyAggregate.empty(CurrencyCode(filingCurrency))
        }
        val taxableIncomeAggregate = MoneyAggregate(
            displayAmount = taxableIncome,
            displayCurrency = CurrencyCode(filingCurrency),
            sourceBuckets = emptyList(),
            conversionFailures = emptyList(),
            isPartial = false,
            warningMessage = null
        )
        val partial = deductibleAggregate.isPartial || vatAggregate.isPartial
        val warnings = deductibleAggregate.conversionFailures.map { it.description }

        TaxEstimate(
            startDate = startDate,
            endDate = endDate,
            estimatedIncome = periodIncome,
            deductibleExpenses = totalDeductible,
            taxableIncome = taxableIncome,
            estimatedIncomeTax = estimatedIncomeTax,
            estimatedVatPortion = vatPaid,
            estimatedRecoverableVat = 0.0,
            vatConfidence = "LOW",
            effectiveTaxRate = if (periodIncome > 0) (estimatedIncomeTax / periodIncome) * 100 else 0.0,
            notes = "Estimate using ${taxConfig.getCountryCode()} tax rates (filing currency: $filingCurrency). Consult tax professional for accurate filing.",
            deductibleAggregate = deductibleAggregate,
            vatAggregate = vatAggregate,
            taxableIncomeAggregate = taxableIncomeAggregate,
            isPartial = partial,
            conversionWarnings = warnings
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
        // T03/T09-FIXED: Use configured fiscal year start day/month instead of hardcoded Jan 1.
        val month = taxSettings.getFiscalYearStartMonth() - 1 // Calendar month is 0-based
        val day = taxSettings.getFiscalYearStartDay()
        return Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
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
            .getOrDefault(taxSettings.getFilingCurrency())
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
            .getOrDefault(taxSettings.getFilingCurrency())
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
        taxConfig: TaxConfiguration = TaxConfigurationFactory.getConfiguration(taxSettings.getTaxCountry())
    ): TaxYearSummary = withContext(ioDispatcher) {
        // T03/T09-FIXED: Fiscal year start uses taxSettings.fiscalYearStartMonth/Day
        // instead of hardcoded January 1st.
        val yearStart = startOfYear(year)
        val yearEnd = startOfYear(year + 1)
        val incomeAggregate = buildIncomeAggregate(yearStart, yearEnd)
        val totalIncome = incomeAggregate.displayAmount
        val filingCurrency = taxSettings.getFilingCurrency()

        val estimate = estimateTaxes(yearStart, yearEnd, totalIncome, taxConfig)

        val estimatedTaxAgg = MoneyAggregate(
            displayAmount = estimate.estimatedIncomeTax,
            displayCurrency = CurrencyCode(filingCurrency),
            sourceBuckets = emptyList(),
            conversionFailures = emptyList(),
            isPartial = false,
            warningMessage = null
        )

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
            totalVatPaid = estimate.estimatedVatPortion,
            categorizedDeductions = categorizedDeductions,
            estimatedTaxOwed = estimate.estimatedIncomeTax,
            mileageDeduction = businessExpenseRepository.getTotalMileageDeduction(yearStart, yearEnd),
            incomeAggregate = incomeAggregate,
            deductibleAggregate = estimate.deductibleAggregate,
            estimatedTaxAggregate = estimatedTaxAgg,
            isPartial = incomeAggregate.isPartial || estimate.isPartial,
            conversionWarnings = (incomeAggregate.conversionFailures.map { it.description } + estimate.conversionWarnings)
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
    val estimatedVatPortion: Double,
    val estimatedRecoverableVat: Double = 0.0,
    val vatConfidence: String = "LOW",
    val effectiveTaxRate: Double,
    val notes: String,
    val deductibleAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val vatAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val taxableIncomeAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val isPartial: Boolean = false,
    val conversionWarnings: List<String> = emptyList()
)

data class TaxYearSummary(
    val year: Int,
    val totalIncome: Double,
    val totalDeductibleExpenses: Double,
    val totalVatPaid: Double,
    val categorizedDeductions: Map<String, Double>,
    val estimatedTaxOwed: Double,
    val mileageDeduction: Double,
    val incomeAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val deductibleAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val estimatedTaxAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val isPartial: Boolean = false,
    val conversionWarnings: List<String> = emptyList()
)

/** Backward-compat accessor for renamed field [TaxEstimate.estimatedVatPortion]. */
@Deprecated(
    message = "Renamed to estimatedVatPortion for clarity",
    replaceWith = ReplaceWith("estimatedVatPortion"),
    level = DeprecationLevel.WARNING
)
val TaxEstimate.estimatedVatPaid: Double
    get() = estimatedVatPortion
