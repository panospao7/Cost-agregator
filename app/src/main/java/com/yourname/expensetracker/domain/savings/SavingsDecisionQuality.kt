package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.requireCompleteMoneyInput
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult

/** Counts describe normalization observations; overlapping windows are not unique transactions. */
data class SmartSavingsOutcomeMetadata(
    val currency: String? = null,
    val conversionQuality: ConversionQuality = ConversionQuality.COMPLETE,
    /** Null when stages request different policies or no stage has been observed. */
    val requestedRateBasis: RateBasis? = null,
    /** Multiple stages can use different legitimate policies; do not collapse them. */
    val requestedRateBases: Set<RateBasis> = emptySet(),
    val totalInputCount: Int = 0,
    val includedInputCount: Int = 0,
    val excludedInputCount: Int = 0,
    val warningCodes: List<String> = emptyList(),
    val latestRateTimestamp: Long? = null,
    val settingsRateTimestamp: Long? = null,
    val actualRateBases: Set<RateBasis> = emptySet(),
    val inputs: List<SavingsInputQuality> = emptyList()
)

data class SavingsInputQuality(
    val stage: SavingsInputStage,
    val totalInputCount: Int,
    val includedInputCount: Int,
    val excludedInputCount: Int,
    val warningCodes: List<String>,
    val requestedRateBasis: RateBasis,
    val actualRateBasis: RateBasis,
    val latestRateTimestamp: Long?
)

enum class SavingsInputStage {
    CURRENT_SPENDING, PACE_HISTORY, FORECAST_CURRENT, FORECAST_HISTORY,
    RANGE_SPENDING, RECURRING_UPCOMING, PLANNED_UPCOMING, SIMULATOR_HISTORY
}

/** Request-local collector: no financial amounts, payloads or exception messages. */
internal class SavingsDecisionQuality(var currency: String) {
    private val inputs = mutableListOf<SavingsInputQuality>()
    private val warnings = linkedSetOf<String>()
    var settingsRateTimestamp: Long? = null

    fun requireComplete(stage: SavingsInputStage, input: AnalyticsNormalizationResult) {
        if (input.totalInputCount < input.normalizedExpenses.size ||
            input.includedExpenses.size != input.normalizedExpenses.size) {
            fail(MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS)
        }
        val codes = (input.warnings.map { it.type.name } +
            input.normalizedExpenses.mapNotNull { it.qualityWarning?.name }).distinct()
        inputs += SavingsInputQuality(
            stage, input.totalInputCount, input.normalizedExpenses.size, input.excludedCount,
            codes, RateBasis.TRANSACTION_DATE,
            if (input.normalizedExpenses.all { CurrencyCode.parse(it.originalCurrency) == CurrencyCode.parse(currency) })
                RateBasis.IDENTITY else RateBasis.TRANSACTION_DATE,
            input.latestRateTimestamp
        )
        try {
            input.requireCompleteMoneyInput(currency)
        } catch (error: MoneyDisplayUnavailableException) {
            warnings += error.reasonCode.name
            throw error
        }
    }

    fun recordUpcoming(stage: SavingsInputStage, count: Int, excluded: Int, codes: List<String>, identity: Boolean) {
        if (count < 0 || excluded !in 0..count) fail(MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS)
        inputs += SavingsInputQuality(
            stage, count, count - excluded, excluded, codes.distinct(),
            RateBasis.LATEST_AVAILABLE, if (identity) RateBasis.IDENTITY else RateBasis.LATEST_AVAILABLE, null
        )
    }

    fun requireForecast(result: MonteCarloResult?) {
        // Null is the simulator's valid no-data contract; callers retain their conservative fallback.
        if (result == null) return
        result.metadata.historicalMoneyQuality?.let { quality ->
            if (quality.totalInputCount < 0 || quality.includedInputCount < 0 || quality.excludedInputCount < 0 ||
                quality.includedInputCount.toLong() + quality.excludedInputCount != quality.totalInputCount.toLong()) {
                fail(MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS)
            }
            inputs += SavingsInputQuality(
                SavingsInputStage.SIMULATOR_HISTORY, quality.totalInputCount, quality.includedInputCount,
                quality.excludedInputCount, quality.warnings.map { it.type.name }.distinct(),
                quality.requestedRateBasis, quality.actualRateBasis, quality.latestRateTimestamp
            )
            if (quality.quality != ConversionQuality.COMPLETE ||
                quality.excludedInputCount > 0 || quality.warnings.isNotEmpty()) {
                fail(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
            }
        }
        if (CurrencyCode.parse(result.displayCurrency) != CurrencyCode.parse(currency)) {
            fail(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        }
        if (listOf(result.percentile50, result.percentile75, result.confidence.score).any { !it.isFinite() } ||
            result.percentile50 < 0.0 || result.percentile75 < result.percentile50 ||
            result.confidence.score !in 0.0..1.0) {
            fail(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT)
        }
    }

    fun fail(reason: MoneyDisplayUnavailableReasonCode): Nothing {
        warnings += reason.name
        throw MoneyDisplayUnavailableException(reason)
    }

    fun snapshot(unavailable: Boolean = false) = SmartSavingsOutcomeMetadata(
        currency = CurrencyCode.parse(currency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }?.code,
        conversionQuality = if (unavailable) ConversionQuality.UNAVAILABLE else ConversionQuality.COMPLETE,
        totalInputCount = inputs.sumOf { it.totalInputCount },
        includedInputCount = inputs.sumOf { it.includedInputCount },
        excludedInputCount = inputs.sumOf { it.excludedInputCount },
        warningCodes = (warnings + inputs.flatMap { it.warningCodes }).distinct(),
        latestRateTimestamp = inputs.mapNotNull { it.latestRateTimestamp }.maxOrNull(),
        settingsRateTimestamp = settingsRateTimestamp,
        requestedRateBasis = inputs.map { it.requestedRateBasis }.distinct().singleOrNull(),
        requestedRateBases = inputs.map { it.requestedRateBasis }.toSet(),
        actualRateBases = inputs.map { it.actualRateBasis }.toSet(),
        inputs = inputs.toList()
    )
}
