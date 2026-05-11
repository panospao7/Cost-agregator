package com.yourname.expensetracker.domain.analytics

/**
 * Unified data quality report aggregating metrics from analytics, forecasting,
 * currency conversion, and AI pipelines.
 *
 * This is the **single source of truth** for data quality assessment across
 * the app. Engines can use it to assess the reliability of their outputs,
 * and the UI can display quality warnings to the user.
 *
 * ## Sources
 * - [AnalyticsCurrencyNormalizer] → conversion warnings + lossPercentage
 * - [com.yourname.expensetracker.domain.forecasting.DataQualityAssessor] → simulation confidence
 * - [com.yourname.expensetracker.domain.health.FinancialHealthScoreV2] → conversionConfidence
 *
 * ## Usage
 * ```kotlin
 * val report = DataQualityReport(
 *     totalExpenses = normalized.totalInputCount,
 *     expensesWithCurrency = ...,
 *     conversionConfidence = 1.0f - (normalized.lossPercentage / 100.0f),
 *     warnings = normalized.warnings.map { it.message }
 * )
 * ```
 *
 * @property totalExpenses Total number of expenses in the current analysis window.
 * @property expensesWithCurrency Count of expenses that have a valid ISO-4217 currency code.
 * @property expensesWithMerchant Count of expenses with a non-blank merchant name.
 * @property expensesWithCategory Count of expenses with a non-null category assignment.
 * @property conversionConfidence A 0.0–1.0 score indicating how reliably amounts were
 *   converted to the home currency. 1.0 = all expenses converted, 0.0 = none converted.
 *   Derived from [AnalyticsNormalizationResult.lossPercentage].
 * @property warnings Human-readable warnings about data quality issues.
 */
data class DataQualityReport(
    val totalExpenses: Int,
    val expensesWithCurrency: Int,
    val expensesWithMerchant: Int,
    val expensesWithCategory: Int,
    val conversionConfidence: Float,
    val warnings: List<String>
) {
    companion object {
        /**
         * Create an empty report indicating no data is available.
         * Used as a placeholder when the analytics pipeline has not yet run.
         */
        fun empty(): DataQualityReport = DataQualityReport(
            totalExpenses = 0,
            expensesWithCurrency = 0,
            expensesWithMerchant = 0,
            expensesWithCategory = 0,
            conversionConfidence = 0f,
            warnings = listOf("No data available for quality assessment.")
        )

        /**
         * Create a report from an [AnalyticsNormalizationResult].
         * This is the primary factory method — all engines that use the normalizer
         * should pipe its output through here.
         *
         * == Stale-Rate Penalty Policy ==
         *
         * If the normalization result contains [STALE_EXCHANGE_RATE] warnings
         * (indicating that rate data is more than 7 days older than the expense
         * date), a penalty of **0.05 per stale-rate warning category** is applied
         * to the base confidence score, capped at **0.15 total**. This prevents
         * a single large batch of stale rates from tanking the confidence score
         * while still reflecting the reliability impact.
         *
         * Penalty is applied *after* the loss-based confidence calculation and
         * before the final `coerceIn(0.0, 1.0)`.
         */
        fun fromNormalization(
            normalization: AnalyticsNormalizationResult,
            totalWithCurrency: Int,
            totalWithMerchant: Int,
            totalWithCategory: Int
        ): DataQualityReport {
            val lossPct = normalization.lossPercentage
            val baseConfidence = if (normalization.totalInputCount > 0) {
                1.0 - (lossPct / 100.0)
            } else {
                0.0
            }

            // Apply stale-rate penalty: 0.05 per stale-rate warning, capped at 0.15
            val staleRateWarnings = normalization.warnings.filter {
                it.type == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE
            }
            val stalePenalty = (staleRateWarnings.size * 0.05).coerceAtMost(0.15)
            val confidence = baseConfidence.coerceIn(0.0, 1.0) - stalePenalty

            return DataQualityReport(
                totalExpenses = normalization.totalInputCount,
                expensesWithCurrency = totalWithCurrency,
                expensesWithMerchant = totalWithMerchant,
                expensesWithCategory = totalWithCategory,
                conversionConfidence = confidence.coerceIn(0.0, 1.0).toFloat(),
                warnings = normalization.warnings.map { it.message }
            )
        }
    }

    /**
     * Returns `true` when the data quality is sufficient for reliable analytics.
     * Quality is considered sufficient when:
     * - There is at least some data (totalExpenses > 0)
     * - Conversion confidence is above 50%
     */
    val isReliable: Boolean get() =
        totalExpenses > 0 && conversionConfidence >= 0.5f

    /**
     * Returns a human-readable summary of the quality level.
     */
    val qualityLabel: String get() = when {
        totalExpenses == 0 -> "No Data"
        conversionConfidence >= 0.95f -> "Excellent"
        conversionConfidence >= 0.80f -> "Good"
        conversionConfidence >= 0.50f -> "Fair"
        else -> "Poor"
    }
}
