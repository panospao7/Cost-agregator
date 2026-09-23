package com.yourname.expensetracker.domain.budget

import android.database.sqlite.SQLiteConstraintException
import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * AI-powered budget forecasting engine.
 * Predicts spending patterns and budget adherence using historical data.
 */
/**
 * AI-powered budget forecasting engine.
 * Predicts spending patterns and budget adherence using historical data.
 *
 * CURRENCY NOTE: All monetary operations go through [AnalyticsCurrencyNormalizer]
 * to ensure multi-currency expenses are normalized to the home currency before
 * any sum, comparison, or trend computation. The engine no longer uses raw
 * SQL sums that bypass currency conversion (see RP-09 P6-007: current-period spend now
 * comes from BudgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd).
 */
@Singleton
class BudgetForecastingEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val budgetRepository: BudgetRepository,
    private val budgetForecastDao: BudgetForecastDao,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    /** @suppress Normalizer injected for currency-aware spent-to-date computation. */
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    /** @suppress Repository injected for expense snapshot queries (required by normalizer). */
    private val expenseRepository: ExpenseRepository,
    /** @suppress Settings injected to resolve home currency code. */
    private val currencySettingsRepository: CurrencySettingsRepository,
    /** @suppress Converter injected to normalise budget.amount to home currency. */
    private val currencyConverter: CurrencyConverter,
    /** @suppress Write barrier injected to guard forecast writes during restore. */
    private val writeBarrier: com.yourname.expensetracker.data.backup.DatabaseWriteBarrier,
    /**
     * P6-CURRENT-026: Durable diagnostics for forecast generation. Nullable + defaulted so existing
     * test construction sites compile unchanged; Hilt injects the real writer in production via the
     * existing DiagnosticsModule binding. Emission is always best-effort.
     */
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter? = null,
    private val diagnosticSink: com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink? = null
) {
    private val budgetCalculator = BudgetCalculator(timeProvider)

    companion object {
        const val CONFIDENCE_THRESHOLD_HIGH = 0.8
        const val CONFIDENCE_THRESHOLD_MEDIUM = 0.6
        private const val TREND_THRESHOLD = 0.10
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000.0
        private const val DESIRED_HISTORY_MONTHS = 3.0

        /**
         * P6-CURRENT-010: Maximum fraction of confidence removed when ALL historical
         * expenses were excluded (exclusionRatio == 1.0). Must be in [0,1] so the derived
         * retention factor (1 - ratio*weight) stays in [0,1]. 0.5 means a fully-excluded
         * history halves confidence rather than zeroing it (some signal may remain from
         * other quality dimensions), while partial exclusions scale linearly below that.
         */
        private const val EXCLUSION_CONFIDENCE_PENALTY_WEIGHT = 0.5
    }

    /**
     * CURR-587-03: Result-returning forecast API — the real implementation.
     * Returns [BudgetForecastResult.Unavailable] on home-currency or conversion failure.
     * Never infers failure from zero/UNKNOWN sentinel values.
     */
    suspend fun generateForecastResult(
        budget: com.yourname.expensetracker.data.database.entity.Budget,
        forecastPeriodDays: Int = 30
    ): BudgetForecastResult = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("BudgetForecastingEngine.generateForecastResult")
        val now = timeProvider.now()
        val homeCurrency = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved -> resolution.currency.code
            is com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Failed -> {
                emitForecastDiagnostic(
                    stage = "FORECAST_UNAVAILABLE",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                    budgetId = budget.id,
                    severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("reason", ForecastUnavailableReason.HOME_CURRENCY_UNAVAILABLE)
                        .build()
                )
                return@withContext BudgetForecastResult.Unavailable(
                    budgetId = budget.id,
                    reasonCode = ForecastUnavailableReason.HOME_CURRENCY_UNAVAILABLE,
                    reason = "Home currency unavailable",
                    createdAt = now
                )
            }
        }

        val (periodStart, periodEnd) = budgetCalculator.calculatePeriodRange(budget, now)

        val outcome = currencyConverter.convertOutcome(
            amount = budget.amount,
            fromCurrency = budget.currency,
            toCurrency = homeCurrency,
            rateBasis = com.yourname.expensetracker.domain.core.money.RateBasis.PERIOD_END,
            atMillis = periodEnd,
            stalePolicy = com.yourname.expensetracker.domain.core.money.StaleRatePolicy.None
        )
        val normalizedBudgetAmount = when (outcome) {
            is com.yourname.expensetracker.domain.core.money.ConversionOutcome.Converted -> outcome.convertedAmount
            is com.yourname.expensetracker.domain.core.money.ConversionOutcome.Failed -> {
                emitForecastDiagnostic(
                    stage = "FORECAST_UNAVAILABLE",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                    budgetId = budget.id,
                    severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("reason", ForecastUnavailableReason.LIMIT_CONVERSION_FAILED)
                        .put("currency", budget.currency)
                        .build()
                )
                return@withContext BudgetForecastResult.Unavailable(
                    budgetId = budget.id,
                    reasonCode = ForecastUnavailableReason.LIMIT_CONVERSION_FAILED,
                    reason = "Budget limit conversion unavailable",
                    createdAt = now
                )
            }
        }

        val elapsedEnd = now.coerceAtMost(periodEnd)
        // RP-09 (P6-007): current-period spend comes from the repository-owned bounded
        // PERIOD_END aggregate — the SAME rate basis as the budget-limit conversion above.
        // This keeps the risk ratio stable: it no longer moves solely because rates
        // changed after a purchase. Transaction-date rates remain reserved for the
        // historical series (getHistoricalSpendingData) and analytics only.
        val periodSpend = budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(
            categoryId = budget.categoryId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            elapsedEnd = elapsedEnd
        )
        val spendAggregate = periodSpend.aggregate
        // Missing/unconvertible rates leave the risk math undefined: return the existing
        // typed unavailable result (MISSING_RATE) — never fall back to a fabricated value.
        if (spendAggregate.conversionQuality ==
            com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE
        ) {
            emitForecastDiagnostic(
                stage = "FORECAST_UNAVAILABLE",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                budgetId = budget.id,
                severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .put("reason", ForecastUnavailableReason.MISSING_RATE)
                    .put("detail", "PERIOD_SPEND_UNAVAILABLE")
                    .build()
            )
            return@withContext BudgetForecastResult.Unavailable(
                budgetId = budget.id,
                reasonCode = ForecastUnavailableReason.MISSING_RATE,
                reason = "Current-period spend unavailable: exchange rates missing for the budget period",
                createdAt = now
            )
        }
        val spentToDate = spendAggregate.displayAmount
        // RP-09 (P6-007): rows excluded from the PERIOD_END spend conversion (missing
        // rates) are recorded as forecast data-quality loss and lower confidence below.
        val spendExcludedCount = spendAggregate.failedTransactionCount
        val spendInputCount = spendAggregate.totalTransactionCount
        val remainingForecastDays = com.yourname.expensetracker.domain.util.TimePeriodUtils.daysBetween(elapsedEnd, periodEnd).coerceAtLeast(0).toDouble()
        val historicalData = getHistoricalSpendingData(budget, homeCurrency)
        val predictedSpending = calculatePredictedSpending(historicalData, remainingForecastDays)
        val baseConfidence = calculateConfidence(historicalData)
        // P6-CURRENT-010: Reduce confidence proportionally to how much history was excluded
        // (FX-conversion failures, invalid currencies) by the AnalyticsCurrencyNormalizer.
        //
        // Formula (deterministic, bounded [0,1]):
        //   exclusionRatio  = excludedExpenseCount / inputExpenseCount   (0 when no input)
        //   retentionFactor = 1 - exclusionRatio * EXCLUSION_CONFIDENCE_PENALTY_WEIGHT
        //   confidence      = baseConfidence * retentionFactor
        //
        // Proof it stays in [0,1]:
        //   - baseConfidence ∈ [0,1]                  (calculateConfidence clamps to [0,1])
        //   - excludedExpenseCount ≤ inputExpenseCount and both ≥ 0, so exclusionRatio ∈ [0,1]
        //   - EXCLUSION_CONFIDENCE_PENALTY_WEIGHT ∈ [0,1], so exclusionRatio*weight ∈ [0,1]
        //   - retentionFactor = 1 - (that) ∈ [1-weight, 1] ⊆ [0,1]
        //   - product of two values in [0,1] is in [0,1]; coerceIn is belt-and-suspenders.
        // When no expenses are excluded, exclusionRatio = 0 ⇒ retentionFactor = 1 ⇒ confidence
        // is unchanged (so existing no-exclusion forecasts/tests are unaffected).
        val exclusionRatio = if (historicalData.inputExpenseCount > 0) {
            historicalData.excludedExpenseCount.toDouble() / historicalData.inputExpenseCount
        } else 0.0
        val retentionFactor = 1.0 - (exclusionRatio * EXCLUSION_CONFIDENCE_PENALTY_WEIGHT)
        // RP-09 (P6-007): apply the same bounded, exclusion-proportional penalty to
        // current-period spend rows excluded by the PERIOD_END conversion. Partial spend
        // data lowers confidence instead of silently reading as zero/low spend.
        val spendExclusionRatio = if (spendInputCount > 0) {
            spendExcludedCount.toDouble() / spendInputCount
        } else 0.0
        val spendRetentionFactor = 1.0 - (spendExclusionRatio * EXCLUSION_CONFIDENCE_PENALTY_WEIGHT)
        val confidence = (baseConfidence * retentionFactor * spendRetentionFactor).coerceIn(0.0, 1.0)
        val riskLevel = determineRiskLevel(budget, predictedSpending, confidence, spentToDate, normalizedBudgetAmount)
        val overspendProbability = calculateOverspendProbability(normalizedBudgetAmount, predictedSpending, spentToDate, confidence)
        val predictedRemaining = normalizedBudgetAmount - spentToDate - predictedSpending

        // P6-CURRENT-010: Persist forecast data-quality. excludedExpenseCount/qualityWarnings come
        // from the normalizer pass that gathered history (TRANSACTION_DATE basis — historical
        // series only) and from the PERIOD_END current-period spend conversion above.
        // RP-09 (P6-007): rateBasis records the FX basis of the PERSISTED RISK CALCULATION
        // (spentToDate, risk level, overspend probability, predicted remaining), which now
        // uses PERIOD_END for both the spend aggregate and the budget limit. Historical-series
        // basis is in-memory only and is NOT recorded on this field.
        val excludedExpenseCount = historicalData.excludedExpenseCount + spendExcludedCount
        val spendQualityWarnings = if (spendExcludedCount > 0) {
            listOf(
                "PERIOD_SPEND_PARTIAL: $spendExcludedCount current-period transaction(s) excluded " +
                    "from spend (missing exchange rates)"
            )
        } else emptyList()
        val qualityWarnings = historicalData.qualityWarnings + spendQualityWarnings
        val isPartial = excludedExpenseCount > 0 || qualityWarnings.isNotEmpty()

        val forecast = BudgetForecast(
            budgetId = budget.id,
            forecastDate = now,
            targetPeriodStart = periodStart,
            targetPeriodEnd = periodEnd,
            predictedSpending = predictedSpending,
            predictedRemaining = predictedRemaining,
            confidenceScore = confidence,
            riskLevel = riskLevel,
            overspendProbability = overspendProbability,
            createdAt = now,
            currency = homeCurrency,
            isPartial = isPartial,
            excludedExpenseCount = excludedExpenseCount,
            qualityWarningsJson = serializeQualityWarnings(qualityWarnings),
            rateBasis = com.yourname.expensetracker.domain.core.money.RateBasis.PERIOD_END.name
        )
        val persistedId = when (val insertResult = insertForecast(forecast)) {
            is ForecastInsertResult.Inserted -> insertResult.id
            ForecastInsertResult.DuplicateInSameInstant -> {
                // P6-CURRENT-008: A same-millisecond duplicate hit the unique index
                // (budgetId, targetPeriodStart, forecastDate) under OnConflictStrategy.ABORT.
                // Do NOT crash and do NOT overwrite a historical row — route to the existing
                // skip/unavailable diagnostic path. A normal refresh uses a fresh forecastDate
                // and never reaches this branch.
                emitForecastDiagnostic(
                    stage = "FORECAST_UNAVAILABLE",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SKIPPED,
                    budgetId = budget.id,
                    severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("reason", ForecastUnavailableReason.UNKNOWN)
                        .put("detail", "DUPLICATE_IN_SAME_INSTANT")
                        .build()
                )
                return@withContext BudgetForecastResult.Unavailable(
                    budgetId = budget.id,
                    reasonCode = ForecastUnavailableReason.UNKNOWN,
                    reason = "Forecast skipped: a forecast already exists for this budget at the same instant (forecastDate=$now)",
                    createdAt = now
                )
            }
        }
        val persisted = forecast.copy(id = persistedId).also { f ->
            f.spentToDate = spentToDate
            f.normalizedBudgetAmount = normalizedBudgetAmount
        }
        emitForecastDiagnostic(
            stage = "FORECAST_GENERATED",
            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
            budgetId = budget.id,
            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                .put("predictedSpending", predictedSpending)
                .put("confidence", confidence)
                .put("riskLevel", riskLevel)
                .put("overspendProbability", overspendProbability)
                .put("currency", homeCurrency)
                .build()
        )
        BudgetForecastResult.Available(persisted)
    }

    /**
     * P6-CURRENT-008: Production insert path wrapper that yields a typed result.
     *
     * Delegates to [BudgetForecastDao.insertWithDeactivation] — the deactivate-then-insert
     * `@Transaction` that preserves forecast history by deactivating the previous active row
     * before inserting the new one. The DAO's raw [BudgetForecastDao.insert] now uses
     * [androidx.room.OnConflictStrategy.ABORT]; the unique index
     * `(budgetId, targetPeriodStart, forecastDate)` therefore only rejects a genuine
     * same-millisecond duplicate, which SQLite surfaces as [SQLiteConstraintException].
     *
     * DBG-02: `budget_forecasts` carries BOTH a composite UNIQUE index
     * `(budgetId, targetPeriodStart, forecastDate)` AND a FOREIGN KEY
     * `budgetId -> budgets(id)`. SQLite surfaces BOTH violations as
     * [SQLiteConstraintException], so the catch must disambiguate on the message:
     * only a UNIQUE-index conflict is a genuine same-instant duplicate to map to
     * [ForecastInsertResult.DuplicateInSameInstant]. A FOREIGN KEY failure (e.g. the
     * budget was deleted mid-flight) is a real referential-integrity error — it is
     * rethrown so it surfaces instead of being silently swallowed as a duplicate.
     * ALL other exceptions also propagate unchanged so genuine I/O / write-barrier /
     * corruption errors are never swallowed.
     */
    @VisibleForTesting
    internal suspend fun insertForecast(forecast: BudgetForecast): ForecastInsertResult {
        // GR-14a: the mutation is owned by THIS callable, so the write barrier
        // check must lexically precede the DAO write here.  The only production
        // caller (generateForecastResult) already checks before dispatching, so
        // this double-check is unreachable-no-op in every current path.
        writeBarrier.checkWritesAllowed("BudgetForecastingEngine.insertForecast")
        return try {
            ForecastInsertResult.Inserted(budgetForecastDao.insertWithDeactivation(forecast))
        } catch (e: SQLiteConstraintException) {
            // DBG-02: disambiguate the constraint type via the SQLite message. Only a
            // UNIQUE-index conflict is the same-instant duplicate this path expects;
            // a FOREIGN KEY (or any other non-UNIQUE) constraint failure must NOT be
            // mislabeled as a duplicate and silently dropped — rethrow so the genuine
            // referential-integrity error is observable.
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                Timber.w(
                    e,
                    "BudgetForecastingEngine: forecast insert hit unique-index conflict " +
                        "(same-instant duplicate for budgetId=%d, forecastDate=%d)",
                    forecast.budgetId,
                    forecast.forecastDate
                )
                ForecastInsertResult.DuplicateInSameInstant
            } else {
                Timber.e(
                    e,
                    "BudgetForecastingEngine: forecast insert hit a non-UNIQUE constraint " +
                        "(e.g. FOREIGN KEY) for budgetId=%d — rethrowing instead of mapping to duplicate",
                    forecast.budgetId
                )
                throw e
            }
        }
    }

    /**
     * P6-CURRENT-026: Best-effort durable diagnostic for forecast generation.
     *
     * Mirrors the [BudgetMonitor] emission pattern: write-barrier-guarded and tolerant of
     * [com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException] (routed to the
     * [diagnosticSink]). A failure to emit NEVER fails the forecast — all exceptions except
     * [kotlinx.coroutines.CancellationException] are swallowed. Runs on [ioDispatcher] (the
     * caller already executes inside withContext(ioDispatcher)).
     */
    private suspend fun emitForecastDiagnostic(
        stage: String,
        outcome: com.yourname.expensetracker.domain.diagnostics.EventOutcome,
        budgetId: Long,
        severity: com.yourname.expensetracker.domain.diagnostics.EventSeverity =
            com.yourname.expensetracker.domain.diagnostics.EventSeverity.INFO,
        metadata: com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata =
            com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.empty()
    ) {
        val writer = diagnosticEventWriter ?: return
        try {
            writeBarrier.checkWritesAllowed("BudgetForecastingEngine.diagnostic")
            writer.emit(
                com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                    stage = stage,
                    outcome = outcome,
                    severity = severity,
                    entityType = "Budget",
                    entityId = budgetId,
                    metadata = metadata
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                diagnosticSink?.recordBlockedOperation("BudgetForecastingEngine.diagnostic", e.mode, "P6")
            } else {
                Timber.w(
                    "BudgetForecastingEngine: UNKNOWN_ERROR stage=%s class=%s",
                    stage,
                    e::class.java.simpleName
                )
            }
        }
    }

    /**
     * Generate a forecast for a specific budget.
     * @deprecated Use [generateForecastResult] to handle unavailable forecasts explicitly.
     */
    @Deprecated(
        message = "Use generateForecastResult() to handle unavailable forecasts explicitly.",
        level = DeprecationLevel.WARNING
    )
    suspend fun generateForecast(
        budget: Budget,
        forecastPeriodDays: Int = 30
    ): BudgetForecast = withContext(ioDispatcher) {
        // Delegate to the real implementation; throw typed exception on unavailable for legacy compatibility
        when (val result = generateForecastResult(budget, forecastPeriodDays)) {
            is BudgetForecastResult.Available -> result.forecast
            is BudgetForecastResult.Unavailable -> throw BudgetForecastUnavailableException(
                reasonCode = result.reasonCode,
                message = result.reason
            )
        }
    }
    
    /**
     * Get historical spending data for pattern analysis.
     *
     * Fetches raw expense snapshots and normalises them to the home currency
     * via [AnalyticsCurrencyNormalizer] before grouping into monthly buckets.
     * This replaces the earlier raw-SQL aggregate approach (A.9) that summed
     * amounts across mixed currencies without conversion; those raw DAO
     * aggregates are now deprecated for exactly that reason and are no longer
     * referenced by this engine.
     *
     * Gap months between first/last observed month keys are synthesized as
     * explicit zero-spend buckets so averages and trends are not skewed
     * upward when a user simply had no spending in an intermediate month.
     *
     * RP-08 (P6-004): incomplete leading/trailing edge months are excluded
     * from the series (window trimmed to local month boundaries); no
     * pro-rating is performed for partial buckets.
     */
    private suspend fun getHistoricalSpendingData(budget: Budget, homeCurrency: String): HistoricalData {
        val now = timeProvider.now()
        val threeMonthsAgo = TimePeriodUtils.addMonths(now, -3)

        // ── Fetch raw snapshots and normalise to home currency ──────────────
        val rawExpenses = expenseRepository.getExpenseSnapshotsBetween(threeMonthsAgo, now)
        val normalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawExpenses, homeCurrency)
        val relevantExpenses = normalized.includedExpenses

        // P6-CURRENT-010: Capture data-quality signals from the normalizer. The normalizer
        // EXCLUDES historical expenses it cannot convert to home currency (invalid currency,
        // missing exchange rate); excludedCount/totalInputCount/warnings describe that loss.
        // These flow onto the persisted BudgetForecast row (isPartial, excludedExpenseCount,
        // qualityWarningsJson) and drive the exclusion-proportional confidence penalty below.
        val excludedExpenseCount = normalized.excludedCount
        val inputExpenseCount = normalized.totalInputCount
        val qualityWarnings = normalized.warnings.map { "${it.type.name}: ${it.message}" }

        // Log conversion warnings if any occurred
        if (normalized.hasWarnings) {
            Timber.w(
                "BudgetForecastingEngine historical: ${normalized.warnings.size} conversion warning(s), " +
                "${normalized.excludedCount} transactions excluded"
            )
        }

        // Filter by category and spending type, then group into monthly buckets
        val filtered = if (budget.categoryId != null) {
            relevantExpenses.filter { it.categoryId == budget.categoryId }
        } else {
            relevantExpenses
        }

        val spendingExpenses = filtered.filter {
            (it.transactionType == DomainTransactionType.PURCHASE ||
             it.transactionType == DomainTransactionType.WITHDRAWAL) &&
            !it.isNotMine
        }

        val monthlyTotals: List<MonthlySpendingTotal> = spendingExpenses
            .groupBy { TimePeriodUtils.formatMonthKey(it.date) }
            .map { (monthKey, expenses) ->
                MonthlySpendingTotal(
                    monthKey = monthKey,
                    total = expenses.sumOf { it.effectiveAmount },
                    txCount = expenses.size
                )
            }
            .sortedBy { it.monthKey }

        // RP-08 (P6-004): explicit exclusion of incomplete edge months for
        // history/forecast semantics — same policy as the autopilot engine.
        val normalizedSeries = BudgetHistorySeriesBuilder.build(
            monthlyTotals = monthlyTotals,
            windowStartInclusive = threeMonthsAgo,
            windowEndExclusive = now,
            excludeIncompleteEdgeMonths = true
        )

        val monthlySpending = linkedMapOf<String, Double>()
        normalizedSeries.monthKeys.forEachIndexed { index, monthKey ->
            monthlySpending[monthKey] = normalizedSeries.values[index]
        }
        
        // Calculate statistics
        val values = normalizedSeries.values
        val average = if (values.isNotEmpty()) values.sum() / values.size else 0.0
        
        var variance = 0.0
        for (value in values) {
            variance += (value - average) * (value - average)
        }
        val standardDeviation = if (values.size > 1) {
            kotlin.math.sqrt(variance / (values.size - 1))
        } else 0.0
        
        val trend = when (BudgetHistorySeriesBuilder.classifyTrend(values, TREND_THRESHOLD)) {
            BudgetHistorySeriesBuilder.TrendDirection.INCREASING -> SpendingTrend.INCREASING
            BudgetHistorySeriesBuilder.TrendDirection.DECREASING -> SpendingTrend.DECREASING
            BudgetHistorySeriesBuilder.TrendDirection.STABLE -> SpendingTrend.STABLE
        }
        
        return HistoricalData(
            monthlySpending = monthlySpending,
            averageMonthly = average,
            standardDeviation = standardDeviation,
            monthsOfHistory = normalizedSeries.filledMonthCount,
            observedMonthCount = normalizedSeries.observedMonthCount,
            trend = trend,
            excludedExpenseCount = excludedExpenseCount,
            inputExpenseCount = inputExpenseCount,
            qualityWarnings = qualityWarnings
        )
    }
    
    /**
     * Calculate predicted spending using historical patterns.
     */
    private fun calculatePredictedSpending(
        historicalData: HistoricalData,
        forecastPeriodDays: Double
    ): Double {
        val months = forecastPeriodDays / 30.0
        
        // Base prediction from historical average
        var prediction = historicalData.averageMonthly * months
        
        // Adjust for trend
        prediction = when (historicalData.trend) {
            SpendingTrend.INCREASING -> prediction * 1.1
            SpendingTrend.DECREASING -> prediction * 0.9
            SpendingTrend.STABLE -> prediction
        }
        
        // NEW-P6-011: calculateSeasonalFactor was a dead stub that always returned 1.0;
        // removed entirely. Seasonal adjustment may be re-added when real data is available.
        
        return max(prediction, 0.0)
    }
    
    /**
     * Calculate confidence score based on data quality.
     */
    private fun calculateConfidence(historicalData: HistoricalData): Double {
        val historyCompleteness = (historicalData.observedMonthCount / DESIRED_HISTORY_MONTHS)
            .coerceIn(0.0, 1.0)
        var confidence = historyCompleteness * 0.8
        
        // Lower variance = higher confidence
        val coefficientOfVariation = if (historicalData.averageMonthly > 0) {
            historicalData.standardDeviation / historicalData.averageMonthly
        } else 0.0
        
        confidence += when {
            coefficientOfVariation < 0.1 -> 0.2
            coefficientOfVariation < 0.3 -> 0.1
            coefficientOfVariation < 0.5 -> 0.0
            else -> -0.1
        }
        
        return min(max(confidence, 0.0), 1.0)
    }

    /**
     * P6-CURRENT-010: Serialize data-quality warnings into the `qualityWarningsJson` column.
     *
     * Uses the same `org.json` JSON-array approach the codebase already relies on for other
     * JSON-typed columns/metadata (e.g. SafeEventMetadata.toJson, SourceLinkEventMetadataBuilder).
     * Returns null when there are no warnings so legacy/clean rows keep the NULL default rather
     * than storing an empty `[]`.
     */
    private fun serializeQualityWarnings(warnings: List<String>): String? {
        if (warnings.isEmpty()) return null
        val array = org.json.JSONArray()
        warnings.forEach { array.put(it) }
        return array.toString()
    }
    
    /**
     * Determine risk level based on prediction vs budget.
     */
    private fun determineRiskLevel(
        budget: Budget,
        predictedSpending: Double,
        confidence: Double,
        spentToDate: Double,
        normalizedBudgetAmount: Double
    ): ForecastRiskLevel {
        if (spentToDate >= normalizedBudgetAmount) return ForecastRiskLevel.CRITICAL

        val remaining = normalizedBudgetAmount - spentToDate
        
        // Calculate percentage of remaining budget that will be used
        val usageRatio = if (remaining > 0) predictedSpending / remaining else 1.0
        
        return when {
            usageRatio > 1.0 && confidence > CONFIDENCE_THRESHOLD_MEDIUM -> ForecastRiskLevel.CRITICAL
            usageRatio > 0.9 && confidence > CONFIDENCE_THRESHOLD_MEDIUM -> ForecastRiskLevel.HIGH
            usageRatio > 0.75 -> ForecastRiskLevel.MEDIUM
            else -> ForecastRiskLevel.LOW
        }
    }
    
    /**
     * Calculate probability of overspending.
     */
    private fun calculateOverspendProbability(
        budgetAmount: Double,
        predictedSpending: Double,
        spentToDate: Double,
        confidence: Double
    ): Double {
        // Deterministic overspend: already over budget before any forecast uncertainty.
        if (spentToDate >= budgetAmount) {
            return 1.0
        }

        val projectedTotal = spentToDate + predictedSpending
        if (projectedTotal >= budgetAmount) {
            return 1.0
        }

        val buffer = budgetAmount - projectedTotal
        val probability = when {
            buffer < budgetAmount * 0.1 -> 0.8 // Very tight
            buffer < budgetAmount * 0.25 -> 0.5 // Tight
            buffer < budgetAmount * 0.5 -> 0.2 // Comfortable
            else -> 0.05 // Very comfortable
        }
        
        // Adjust by confidence
        return probability * confidence
    }
    
    // RP-09 (P6-007): the previous getSpentAmount() (AnalyticsCurrencyNormalizer at
    // TRANSACTION_DATE) was removed — current-period spend for the risk math now comes
    // exclusively from BudgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd()
    // (bounded PERIOD_END aggregate) so spend and limit share one rate basis.


}

/**
 * P6-CURRENT-008: Typed outcome of a forecast insert.
 *
 * Makes a same-millisecond unique-index conflict (under [androidx.room.OnConflictStrategy.ABORT]
 * on the `(budgetId, targetPeriodStart, forecastDate)` index) observable instead of either a
 * silent REPLACE (the prior behaviour) or an unhandled crash. A normal refresh writes a fresh
 * `forecastDate` and yields [Inserted]; only a genuine same-instant duplicate yields
 * [DuplicateInSameInstant].
 */
sealed interface ForecastInsertResult {
    /** The forecast was persisted; [id] is the new row id (history preserved via deactivation). */
    data class Inserted(val id: Long) : ForecastInsertResult

    /**
     * A forecast already exists for the same (budgetId, targetPeriodStart, forecastDate).
     * No existing row was overwritten. Callers route this to the skip/unavailable path.
     */
    data object DuplicateInSameInstant : ForecastInsertResult
}

/**
 * Historical spending data for forecasting.
 */
private data class HistoricalData(
    val monthlySpending: Map<String, Double>,
    val averageMonthly: Double,
    val standardDeviation: Double,
    val monthsOfHistory: Int,
    val observedMonthCount: Int,
    val trend: SpendingTrend,
    // P6-CURRENT-010: Data-quality signals captured from the currency normalizer so the
    // forecast row can record how much history was dropped (e.g. FX conversion failures).
    /** Number of historical expenses excluded during normalization (e.g. FX failures). */
    val excludedExpenseCount: Int = 0,
    /** Total historical expenses fed into normalization (denominator for the exclusion ratio). */
    val inputExpenseCount: Int = 0,
    /** Human-readable normalization warnings raised while gathering history. */
    val qualityWarnings: List<String> = emptyList()
)

private enum class SpendingTrend {
    INCREASING,
    DECREASING,
    STABLE
}
