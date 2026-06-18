package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.core.money.toCurrencyCodeOrNull
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Normalizes expense lists/snapshots to home currency before analytics consumption.
 * This is THE central normalization point for dashboard, analytics, forecasting,
 * health, and savings pipelines. All engines that process expenses should normalize
 * through this component before performing .sumOf or comparisons.
 */
@Singleton
class AnalyticsCurrencyNormalizer @Inject constructor(
    private val currencyConverter: CurrencyConverter
) {

    suspend fun normalizeExpenses(
        expenses: List<Expense>,
        homeCurrencyCode: String
    ): AnalyticsNormalizationResult {
        return normalizeInternal(
            expenses = expenses.map {
                NormalizableAnalyticsExpense(
                    id = it.id,
                    amount = it.amount,
                    effectiveAmount = it.effectiveAmount,
                    currency = it.currency,
                    merchant = it.merchant,
                    merchantKey = it.merchantKey,
                    transactionType = when (it.transactionType) {
                        com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                        com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                        com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                        com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                        com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
                    },
                    date = it.date,
                    categoryId = it.categoryId,
                    isNotMine = it.isNotMine,
                    transferDirection = when (it.transferDirection) {
                        com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
                        com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
                        null -> null
                    },
                    notes = it.notes
                )
            },
            homeCurrencyCode = homeCurrencyCode
        )
    }

    suspend fun normalizeSnapshots(
        expenses: List<ExpenseSnapshot>,
        homeCurrencyCode: String
    ): AnalyticsNormalizationResult {
        return normalizeInternal(
            expenses = expenses.map {
                NormalizableAnalyticsExpense(
                    id = it.id,
                    amount = it.amount,
                    effectiveAmount = it.effectiveAmount,
                    currency = it.currency,
                    merchant = it.merchant,
                    merchantKey = it.merchantKey,
                    transactionType = it.transactionType,
                    date = it.date,
                    categoryId = it.categoryId,
                    isNotMine = it.isNotMine,
                    transferDirection = it.transferDirection,
                    notes = it.notes
                )
            },
            homeCurrencyCode = homeCurrencyCode
        )
    }

    private suspend fun normalizeInternal(
        expenses: List<NormalizableAnalyticsExpense>,
        homeCurrencyCode: String
    ): AnalyticsNormalizationResult {
        val homeCurrency = homeCurrencyCode.toCurrencyCodeOrNull()
        if (homeCurrency == null) {
            return AnalyticsNormalizationResult(
                homeCurrency = homeCurrencyCode,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = listOf(
                    AnalyticsConversionWarning(
                        type = AnalyticsConversionWarningType.INVALID_HOME_CURRENCY,
                        message = "Analytics unavailable because home currency '$homeCurrencyCode' is invalid.",
                        affectedTransactionCount = expenses.size
                    )
                ),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            )
        }

        val warnings = linkedMapOf<WarningKey, WarningAccumulator>()
        var latestRateTimestamp: Long? = null
        val excludedReasons = mutableMapOf<Long, Pair<AnalyticsConversionWarningType, String>>()

        val normalizedExpenses = expenses.mapNotNull { expense ->
            val sourceCurrency = expense.currency.toCurrencyCodeOrNull()
            if (sourceCurrency == null) {
                accumulateWarning(
                    warnings = warnings,
                    type = AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY,
                    sourceCurrency = expense.currency,
                    message = "Analytics excluded transaction(s) with invalid currency codes.",
                    expenseId = expense.id
                )
                excludedReasons[expense.id] = Pair(
                    AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY,
                    "Analytics excluded transaction(s) with invalid currency codes."
                )
                return@mapNotNull null
            }

            val conversionResult = when {
                sourceCurrency == homeCurrency -> ConversionResult(
                    amount = expense.effectiveAmount,
                    rateBasis = "IDENTITY",
                    rateUsed = 1.0,
                    rateValidDate = expense.date,
                    rateLastUpdated = null,
                    rateSource = null,
                    conversionPath = "IDENTITY"
                )
                else -> {
                    // P5-NEW-07 FIX: use convertOutcome (TRANSACTION_DATE) so staleness is
                    // evaluated against the rate's validDate, not its lastUpdated. Historical
                    // bases never silently fall back to latest (StaleRatePolicy.None).
                    val outcome = currencyConverter.convertOutcome(
                        amount = expense.effectiveAmount,
                        fromCurrency = sourceCurrency.code,
                        toCurrency = homeCurrency.code,
                        rateBasis = RateBasis.TRANSACTION_DATE,
                        atMillis = expense.date,
                        stalePolicy = StaleRatePolicy.forBasis(RateBasis.TRANSACTION_DATE)
                    )
                    when (outcome) {
                        is ConversionOutcome.Failed -> {
                            accumulateWarning(
                                warnings = warnings,
                                type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                                sourceCurrency = sourceCurrency.code,
                                message = "Analytics excluded transaction(s) because exchange rates were unavailable.",
                                expenseId = expense.id
                            )
                            excludedReasons[expense.id] = Pair(
                                AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                                "Analytics excluded transaction(s) because exchange rates were unavailable."
                            )
                            return@mapNotNull null
                        }
                        is ConversionOutcome.Converted -> {
                            latestRateTimestamp = maxTimestamp(latestRateTimestamp, outcome.rateLastUpdated)
                            // P5-NEW-07: detect stale rates against the rate's validDate (the date
                            // the rate was valid for), not lastUpdated. A backfilled historical rate
                            // has validDate ≈ expense.date even if lastUpdated is recent. validDate
                            // of 0L is an unset sentinel and is not treated as stale.
                            val rateValidDate = outcome.rateValidDate
                            val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
                            if (rateValidDate != null && rateValidDate > 0L &&
                                kotlin.math.abs(expense.date - rateValidDate) > sevenDaysMs) {
                                accumulateWarning(
                                    warnings = warnings,
                                    type = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE,
                                    sourceCurrency = sourceCurrency.code,
                                    message = "Analytics used possibly stale exchange rates for transactions older than available rate data.",
                                    expenseId = expense.id
                                )
                            }
                            ConversionResult(
                                amount = outcome.convertedAmount,
                                rateBasis = outcome.rateBasis.name,
                                rateUsed = outcome.rateUsed,
                                rateValidDate = outcome.rateValidDate,
                                rateLastUpdated = outcome.rateLastUpdated,
                                rateSource = outcome.rateSource,
                                conversionPath = outcome.conversionPath.name
                            )
                        }
                    }
                }
            }

            NormalizedExpenseSnapshot(
                snapshot = expense.toExpenseSnapshot(
                    normalizedEffectiveAmount = conversionResult.amount,
                    homeCurrency = homeCurrency.code
                ),
                originalCurrency = sourceCurrency.code,
                originalEffectiveAmount = expense.effectiveAmount,
                normalizedEffectiveAmount = conversionResult.amount,
                rateBasis = conversionResult.rateBasis,
                rateUsed = conversionResult.rateUsed,
                rateValidDate = conversionResult.rateValidDate,
                rateLastUpdated = conversionResult.rateLastUpdated,
                rateSource = conversionResult.rateSource,
                conversionPath = conversionResult.conversionPath
            )
        }

        val failures = expenses.size - normalizedExpenses.size
        if (warnings.isNotEmpty() || failures > 0) {
            Timber.w(
                "AnalyticsCurrencyNormalizer: ${warnings.size} warning(s), $failures transactions excluded"
            )
        }

        return AnalyticsNormalizationResult(
            homeCurrency = homeCurrency.code,
            normalizedExpenses = normalizedExpenses,
            includedExpenses = normalizedExpenses.map { it.snapshot },
            warnings = warnings.values.map { it.toWarning() },
            latestRateTimestamp = latestRateTimestamp,
            totalInputCount = expenses.size,
            excludedReasons = excludedReasons
        )
    }

    private fun accumulateWarning(
        warnings: MutableMap<WarningKey, WarningAccumulator>,
        type: AnalyticsConversionWarningType,
        sourceCurrency: String?,
        message: String,
        expenseId: Long
    ) {
        val normalizedSource = sourceCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val key = WarningKey(type, message)
        val accumulator = warnings.getOrPut(key) { WarningAccumulator(type, message) }
        accumulator.expenseIds += expenseId
        normalizedSource?.let { accumulator.sourceCurrencies += it }
    }

    private fun maxTimestamp(current: Long?, candidate: Long?): Long? {
        if (candidate == null) return current
        if (current == null) return candidate
        return maxOf(current, candidate)
    }
}

data class AnalyticsNormalizationResult(
    val homeCurrency: String,
    val normalizedExpenses: List<NormalizedExpenseSnapshot>,
    val includedExpenses: List<ExpenseSnapshot>,
    val warnings: List<AnalyticsConversionWarning>,
    val latestRateTimestamp: Long?,
    val totalInputCount: Int = 0,
    val excludedReasons: Map<Long, Pair<AnalyticsConversionWarningType, String>> = emptyMap()
) {
    /** Returns true when there are any conversion warnings. */
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    /** Number of input transactions excluded from the normalized result. */
    val excludedCount: Int get() = totalInputCount - normalizedExpenses.size

    /** Percentage of input transactions that could not be converted. */
    val lossPercentage: Double get() =
        if (totalInputCount > 0) (excludedCount.toDouble() / totalInputCount) * 100.0 else 0.0

    /** Warnings for missing exchange rates or invalid transaction currencies. */
    val severeWarnings: List<AnalyticsConversionWarning> get() = warnings.filter {
        it.type == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE ||
            it.type == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
    }
}

/**
 * Snapshot of a single expense after currency normalisation, including
 * conversion rate provenance metadata.
 */
data class NormalizedExpenseSnapshot(
    val snapshot: ExpenseSnapshot,
    val originalCurrency: String,
    val originalEffectiveAmount: Double,
    val normalizedEffectiveAmount: Double,
    val rateBasis: String? = null,
    val rateUsed: Double? = null,
    val rateValidDate: Long? = null,
    val rateLastUpdated: Long? = null,
    val rateSource: String? = null,
    val conversionPath: String? = null
)

private data class WarningKey(
    val type: AnalyticsConversionWarningType,
    val message: String
)

private data class WarningAccumulator(
    val type: AnalyticsConversionWarningType,
    val message: String,
    val expenseIds: MutableSet<Long> = linkedSetOf(),
    val sourceCurrencies: MutableSet<String> = linkedSetOf()
) {
    fun toWarning(): AnalyticsConversionWarning {
        return AnalyticsConversionWarning(
            type = type,
            message = message,
            affectedTransactionCount = expenseIds.size,
            sourceCurrencies = sourceCurrencies.toList().sorted()
        )
    }
}

/**
 * Internal result of a single-expense conversion, carrying both the
 * converted amount and the rate provenance metadata.
 */
private data class ConversionResult(
    val amount: Double,
    val rateBasis: String?,
    val rateUsed: Double?,
    val rateValidDate: Long?,
    val rateLastUpdated: Long?,
    val rateSource: String?,
    val conversionPath: String?
)

private data class NormalizableAnalyticsExpense(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val currency: String,
    val merchant: String,
    val merchantKey: String?,
    val transactionType: DomainTransactionType,
    val date: Long,
    val categoryId: Long?,
    val isNotMine: Boolean,
    val transferDirection: DomainTransferDirection?,
    val notes: String?
)

private fun NormalizableAnalyticsExpense.toExpenseSnapshot(
    normalizedEffectiveAmount: Double,
    homeCurrency: String
): ExpenseSnapshot {
    return ExpenseSnapshot(
        id = id,
        amount = normalizedEffectiveAmount,
        effectiveAmount = normalizedEffectiveAmount,
        currency = homeCurrency,
        merchant = merchant,
        merchantKey = merchantKey,
        transactionType = transactionType,
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        transferDirection = transferDirection,
        notes = notes
    )
}
