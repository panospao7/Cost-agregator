package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.usecase.budget.GetMonteCarloBudgetImpactUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.AnomalyAlertRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Urgency level for Money Radar widget.
 */
enum class UrgencyLevel {
    GREEN,  // 0-30
    YELLOW, // 31-60
    RED,    // 61-100
    UNAVAILABLE // Required signals could not be evaluated; no score exists.
}

/**
 * Data class representing Money Radar widget data.
 */
data class MoneyRadarData(
    val urgencyScore: Int?, // 0-100 when available; null is not a healthy zero.
    val urgencyLevel: UrgencyLevel,
    val dueBills: List<UpcomingBill>,
    val anomalyAlerts: List<AnomalyAlertSummary>,
    val budgetRisk: BudgetRiskInfo?,
    val topReasons: List<UiText>,
    val primaryCta: MoneyRadarAction?,
    // True when a month-scale overall budget exists but its remaining amount is unknown
    // (not merely a non-positive limit); the budget part is then not "healthy".
    val budgetUnknown: Boolean = false
)

/**
 * Represents an upcoming bill/obligation.
 */
data class UpcomingBill(
    val merchant: String,
    val amount: Double,
    val dueDate: Long,
    val daysUntilDue: Int
)

/**
 * Summary of an unresolved anomaly alert.
 */
data class AnomalyAlertSummary(
    val merchant: String,
    val amount: Double,
    val reason: String,
    val daysAgo: Int
)

/**
 * Budget risk information from Monte Carlo analysis.
 */
data class BudgetRiskInfo(
    val expectedOverrun: Double,
    val probabilityOfOverrun: Double,
    val riskTier: RiskTier,
    // Budget limit the risk was assessed against (home currency); null when not known.
    val budgetLimit: Double? = null
)

/**
 * How the dashboard's single Monte Carlo run relates to the overall budget, as seen by Radar.
 */
sealed interface RadarBudgetInput {
    /** No comparison exists (no overall budget, non-month-scale period, or no positive limit). */
    data object NotComparable : RadarBudgetInput

    /** A comparable month-scale budget exists but its forecast is unavailable. */
    data object Unknown : RadarBudgetInput

    /** The dashboard's simulation result, compared against its own budget amount. */
    data class Simulated(val result: MonteCarloResult) : RadarBudgetInput
}

/**
 * Primary action for Money Radar based on most urgent item.
 */
sealed class MoneyRadarAction {
    data class ViewBills(val bills: List<UpcomingBill>) : MoneyRadarAction()
    data class ReviewAnomalies(val alerts: List<AnomalyAlertSummary>) : MoneyRadarAction()
    data class AdjustBudget(val riskInfo: BudgetRiskInfo) : MoneyRadarAction()
}

/**
 * Use case for computing the Money Radar widget data.
 *
 * Gathers:
 * - Due-in-7d obligations (recurring expenses)
 * - Unresolved anomaly alerts
 * - MC overspend probability
 *
 * Returns urgency score 0-100 mapped to green/yellow/red.
 */
@Singleton
class ComputeMoneyRadarUseCase @Inject constructor(
    private val recurringPatternsProvider: MergedRecurringPatternsProvider,
    private val anomalyAlertRepository: AnomalyAlertRepository,
    private val getMonteCarloBudgetImpact: GetMonteCarloBudgetImpactUseCase,
    private val expenseRepository: ExpenseRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val currencyConverter: CurrencyConverter,
    private val timeProvider: TimeProvider
) {
    companion object {
        // 7-day window for due bills
        private const val BILL_WINDOW_DAYS = 7
        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        
        // Scoring weights (must sum to 1.0)
        private const val WEIGHT_DUE_BILLS = 0.4
        private const val WEIGHT_ANOMALIES = 0.3
        private const val WEIGHT_BUDGET_RISK = 0.3
        
        // Due bills scoring thresholds
        private const val BILLS_SCORE_0 = 0
        private const val BILLS_SCORE_1_2 = 30
        private const val BILLS_SCORE_3_PLUS = 60
        private const val BILLS_SCORE_LARGE_BONUS = 20
        private const val LARGE_BILL_THRESHOLD_PERCENT = 0.5 // 50% of monthly income
        
        // Anomaly scoring thresholds
        private const val ANOMALY_SCORE_0 = 0
        private const val ANOMALY_SCORE_1 = 30
        private const val ANOMALY_SCORE_2_PLUS = 60
        private const val ANOMALY_SCORE_HIGH_SEVERITY_BONUS = 20
        
        // Budget risk scoring thresholds
        private const val BUDGET_SCORE_P_UNDER_25 = 0
        private const val BUDGET_SCORE_P_25_50 = 30
        private const val BUDGET_SCORE_P_50_75 = 60
        private const val BUDGET_SCORE_P_OVER_75 = 80
        
        // Urgency level thresholds
        private const val GREEN_THRESHOLD = 30
        private const val YELLOW_THRESHOLD = 60

        // CRITICAL budget tier floors the score only at/above this fraction of the limit
        private const val CRITICAL_MATERIAL_OVERRUN_FRACTION = 0.05
    }

    /**
     * Compute the Money Radar widget data. [budget] is derived by the dashboard from its single
     * Monte Carlo run; Radar never simulates or reads budget/spend itself.
     */
    suspend fun compute(budget: RadarBudgetInput): MoneyRadarData = try {
        computeAvailable(budget)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Timber.w("RADAR_UNAVAILABLE class=%s", e.javaClass.simpleName)
        MoneyRadarData(
            urgencyScore = null,
            urgencyLevel = UrgencyLevel.UNAVAILABLE,
            dueBills = emptyList(),
            anomalyAlerts = emptyList(),
            budgetRisk = null,
            topReasons = emptyList(),
            primaryCta = null
        )
    }

    private suspend fun computeAvailable(budget: RadarBudgetInput): MoneyRadarData = coroutineScope {
        val now = timeProvider.now()
        val homeCurrency = CurrencyCode.parse(currencySettingsRepository.homeCurrency().first())
            ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }?.code
            ?: error("RADAR_CURRENCY_UNAVAILABLE")
        
        // Gather all data in parallel where possible
        val dueBillsDeferred = async { getDueBills(now, homeCurrency) }
        val anomalyAlertsDeferred = async { getUnresolvedAnomalies(now) }

        val dueBills = dueBillsDeferred.await()
        val anomalyAlerts = anomalyAlertsDeferred.await()
        val budgetRiskOutcome = getBudgetRisk(budget)
        val budgetRisk = budgetRiskOutcome.info

        // Calculate urgency score using weighted factors
        val dueBillsScore = calculateDueBillsScore(dueBills, now, homeCurrency)
        val anomalyScore = calculateAnomalyScore(anomalyAlerts)
        val budgetRiskScore = calculateBudgetRiskScore(budgetRisk)
        
        // Weighted sum (0-100)
        val rawScore = (
            dueBillsScore * WEIGHT_DUE_BILLS +
            anomalyScore * WEIGHT_ANOMALIES +
            budgetRiskScore * WEIGHT_BUDGET_RISK
        ).toInt()
        
        // P-05-5: the budget part alone weighs at most 30, which is still GREEN, so a certain
        // overrun could never leave GREEN. The tier sets a floor; score and level stay in step.
        val urgencyScore = rawScore.coerceIn(0, 100).coerceAtLeast(budgetRiskScoreFloor(budgetRisk))
        val urgencyLevel = when {
            urgencyScore <= GREEN_THRESHOLD -> UrgencyLevel.GREEN
            urgencyScore <= YELLOW_THRESHOLD -> UrgencyLevel.YELLOW
            else -> UrgencyLevel.RED
        }
        
        // Determine top reasons and primary CTA
        val topReasons = buildTopReasons(
            dueBills, anomalyAlerts, budgetRisk, urgencyScore, budgetRiskOutcome.budgetUnknown
        )
        val primaryCta = determinePrimaryAction(dueBills, anomalyAlerts, budgetRisk)
        
        Timber.d(
            "Money Radar computed: bills=%d anomalies=%d hasBudgetRisk=%s",
            dueBills.size, anomalyAlerts.size, budgetRisk != null
        )
        
        MoneyRadarData(
            urgencyScore = urgencyScore,
            urgencyLevel = urgencyLevel,
            dueBills = dueBills,
            anomalyAlerts = anomalyAlerts,
            budgetRisk = budgetRisk,
            topReasons = topReasons,
            primaryCta = primaryCta,
            budgetUnknown = budgetRiskOutcome.budgetUnknown
        )
    }

    /**
     * Budget part result. [info] null keeps its "no comparison" meaning; [budgetUnknown] is true
     * only for a month-scale overall budget whose remaining amount is unavailable for a reason
     * other than a non-positive limit.
     */
    private class BudgetRiskOutcome(val info: BudgetRiskInfo?, val budgetUnknown: Boolean)

    // CRITICAL lifts the floor only when the expected overrun is material (>= 5% of the limit);
    // a tiny overrun against a large limit must not push Radar to YELLOW by tier alone.
    private fun budgetRiskScoreFloor(budgetRisk: BudgetRiskInfo?): Int = when (budgetRisk?.riskTier) {
        RiskTier.CRITICAL -> {
            val limit = budgetRisk?.budgetLimit
            val overrun = budgetRisk?.expectedOverrun
            if (limit != null && overrun != null && overrun >= CRITICAL_MATERIAL_OVERRUN_FRACTION * limit) {
                YELLOW_THRESHOLD + 1
            } else {
                0
            }
        }
        RiskTier.HIGH -> GREEN_THRESHOLD + 1
        else -> 0
    }

    /**
     * Get bills due within the next 7 days from recurring patterns.
     */
    private suspend fun getDueBills(now: Long, homeCurrency: String): List<UpcomingBill> {
        val startOfToday = TimePeriodUtils.getStartOfDay(now)
        val windowEnd = now + (BILL_WINDOW_DAYS * ONE_DAY_MS)
        
        return try {
            val patterns = recurringPatternsProvider.getConfirmedPatterns()
            
            patterns
                .filter { it.nextExpectedDate in startOfToday..windowEnd }
                .map { pattern ->
                    val daysUntil = TimePeriodUtils.daysBetween(
                        startOfToday,
                        TimePeriodUtils.getStartOfDay(pattern.nextExpectedDate)
                    ).coerceAtLeast(0)
                    UpcomingBill(
                        merchant = pattern.merchantName,
                        amount = convertForRadar(
                            amount = pattern.averageAmount,
                            sourceCurrency = pattern.currency,
                            targetCurrency = homeCurrency,
                            atMillis = pattern.nextExpectedDate,
                            rateBasis = RateBasis.FORECAST_DATE
                        ) ?: error("RADAR_RECURRING_UNAVAILABLE"),
                        dueDate = pattern.nextExpectedDate,
                        daysUntilDue = daysUntil
                    )
                }
                .sortedBy { it.daysUntilDue }
        } catch (e: Exception) {
            throw e
        }
    }
    
    /**
     * Get unresolved (non-dismissed) anomaly alerts from the last 30 days.
     */
    private suspend fun getUnresolvedAnomalies(now: Long): List<AnomalyAlertSummary> {
        val thirtyDaysAgo = now - (30 * ONE_DAY_MS)
        
        return try {
            val alerts = anomalyAlertRepository.getActiveAlerts()
            
            alerts
                .filter { it.alertedAt >= thirtyDaysAgo }
                .map { alert ->
                    val daysAgo = TimePeriodUtils.daysBetween(alert.alertedAt, now).coerceAtLeast(0)
                    AnomalyAlertSummary(
                        merchant = alert.merchant,
                        amount = alert.amount,
                        reason = alert.anomalyReason,
                        daysAgo = daysAgo
                    )
                }
                .sortedBy { it.daysAgo }
        } catch (e: Exception) {
            throw e
        }
    }
    
    /**
     * Budget part from the dashboard's single simulation (no second run, no own reads). A budget
     * that cannot be assessed never blanks Radar: it yields no risk info and budgetUnknown = true.
     */
    private fun getBudgetRisk(budget: RadarBudgetInput): BudgetRiskOutcome = when (budget) {
        RadarBudgetInput.NotComparable -> BudgetRiskOutcome(info = null, budgetUnknown = false)
        RadarBudgetInput.Unknown -> BudgetRiskOutcome(info = null, budgetUnknown = true)
        is RadarBudgetInput.Simulated -> {
            val result = budget.result
            val limit = result.budgetAmount
            if (limit == null || result.probabilityUnderBudget == null) {
                BudgetRiskOutcome(info = null, budgetUnknown = true)
            } else {
                when (val impact = getMonteCarloBudgetImpact(budgetAmount = limit, monteCarloResult = result)) {
                    is com.yourname.expensetracker.domain.model.Result.Success -> BudgetRiskOutcome(
                        info = BudgetRiskInfo(
                            expectedOverrun = impact.data.expectedOverrun,
                            probabilityOfOverrun = impact.data.probabilityOfOverrun,
                            riskTier = impact.data.riskTier,
                            budgetLimit = limit
                        ),
                        budgetUnknown = false
                    )
                    else -> BudgetRiskOutcome(info = null, budgetUnknown = true)
                }
            }
        }
    }

    private suspend fun convertForRadar(
        amount: Double,
        sourceCurrency: String,
        targetCurrency: String,
        atMillis: Long,
        rateBasis: RateBasis
    ): Double? {
        if (!amount.isFinite()) return null
        val source = CurrencyCode.parse(sourceCurrency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: return null
        val target = CurrencyCode.parse(targetCurrency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: return null
        if (source == target) return amount
        return when (val outcome = currencyConverter.convertOutcome(
            amount = amount,
            fromCurrency = source.code,
            toCurrency = target.code,
            rateBasis = rateBasis,
            atMillis = atMillis,
            stalePolicy = StaleRatePolicy.forBasis(rateBasis)
        )) {
            is ConversionOutcome.Converted -> outcome.convertedAmount.takeIf {
                it.isFinite() && outcome.targetCurrency == target && outcome.originalCurrency == source &&
                    outcome.rateBasis == rateBasis
            }
            is ConversionOutcome.Failed -> null
        }
    }
    
    /**
     * Calculate due bills score (0-100 before weighting).
     */
    private suspend fun calculateDueBillsScore(dueBills: List<UpcomingBill>, now: Long, homeCurrency: String): Int {
        if (dueBills.isEmpty()) return BILLS_SCORE_0
        
        val baseScore = when (dueBills.size) {
            1, 2 -> BILLS_SCORE_1_2
            else -> BILLS_SCORE_3_PLUS
        }
        
        // Check for any large bill (>50% of monthly income)
        val monthlyIncome = getMonthlyIncome(now, homeCurrency)
        val hasLargeBill = if (monthlyIncome > 0) {
            dueBills.any { it.amount > monthlyIncome * LARGE_BILL_THRESHOLD_PERCENT }
        } else false
        
        val bonus = if (hasLargeBill) BILLS_SCORE_LARGE_BONUS else 0
        
        return (baseScore + bonus).coerceAtMost(100)
    }
    
    /**
     * Calculate anomaly alerts score (0-100 before weighting).
     */
    private fun calculateAnomalyScore(alerts: List<AnomalyAlertSummary>): Int {
        if (alerts.isEmpty()) return ANOMALY_SCORE_0
        
        val baseScore = when (alerts.size) {
            1 -> ANOMALY_SCORE_1
            else -> ANOMALY_SCORE_2_PLUS
        }
        
        // Note: High severity bonus would require severity in AnomalyAlertSummary
        // For now, we use the count-based scoring
        
        return baseScore.coerceAtMost(100)
    }
    
    /**
     * Calculate budget risk score (0-100 before weighting).
     */
    private fun calculateBudgetRiskScore(riskInfo: BudgetRiskInfo?): Int {
        if (riskInfo == null) return BUDGET_SCORE_P_UNDER_25

        val probabilityScore = when {
            riskInfo.probabilityOfOverrun >= 0.75 -> BUDGET_SCORE_P_OVER_75
            riskInfo.probabilityOfOverrun >= 0.50 -> BUDGET_SCORE_P_50_75
            riskInfo.probabilityOfOverrun >= 0.25 -> BUDGET_SCORE_P_25_50
            else -> BUDGET_SCORE_P_UNDER_25
        }

        val magnitudeBonus = when {
            riskInfo.expectedOverrun >= 200.0 -> 20
            riskInfo.expectedOverrun >= 100.0 -> 10
            riskInfo.expectedOverrun > 0.0 -> 5
            else -> 0
        }

        val riskTierBonus = when (riskInfo.riskTier) {
            RiskTier.LOW -> 0
            RiskTier.MEDIUM -> 5
            RiskTier.HIGH -> 10
            RiskTier.CRITICAL -> 20
        }

        return (probabilityScore + magnitudeBonus + riskTierBonus).coerceAtMost(100)
    }
    
    private suspend fun getMonthlyIncome(now: Long, homeCurrency: String): Double {
        val (monthStart, _) = TimePeriodUtils.getMonthRange(now)
        val deposits = expenseRepository.getExpensesBetween(monthStart, now).filter {
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT &&
                !it.isNotMine && it.date in monthStart..now
        }
        val normalized = analyticsCurrencyNormalizer.normalizeExpenses(deposits, homeCurrency)
        if (normalized.excludedCount > 0 || normalized.hasWarnings) error("RADAR_INCOME_UNAVAILABLE")
        return MoneyAggregateBuilder.fromHomeCurrencyAmounts(
            normalized.normalizedExpenses.map { it.normalizedEffectiveAmount }, homeCurrency
        ).displayAmount
    }
    
    /**
     * Build top reasons list for the urgency score.
     */
    private fun buildTopReasons(
        dueBills: List<UpcomingBill>,
        anomalies: List<AnomalyAlertSummary>,
        budgetRisk: BudgetRiskInfo?,
        urgencyScore: Int,
        budgetUnknown: Boolean
    ): List<UiText> {
        val reasons = mutableListOf<UiText>()
        
        // Add reasons in priority order
        if (dueBills.isNotEmpty()) {
            val billText = if (dueBills.size == 1) {
                UiText.fromKey(
                    DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_SINGLE_BILL_DUE_FORMAT,
                    dueBills.first().merchant
                )
            } else {
                UiText.fromKey(
                    DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_BILLS_DUE_FORMAT,
                    dueBills.size,
                    BILL_WINDOW_DAYS
                )
            }
            reasons.add(billText)
        }
        
        if (anomalies.isNotEmpty()) {
            val anomalyText = if (anomalies.size == 1) {
                UiText.fromKey(
                    DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_SINGLE_ANOMALY_FORMAT,
                    anomalies.first().merchant
                )
            } else {
                UiText.fromKey(
                    DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MULTIPLE_ANOMALIES_FORMAT,
                    anomalies.size
                )
            }
            reasons.add(anomalyText)
        }
        
        budgetRisk?.let { risk ->
            if (risk.probabilityOfOverrun >= 0.25) {
                val probabilityPercent = (risk.probabilityOfOverrun * 100).toInt()
                val riskText = when (risk.riskTier) {
                    RiskTier.CRITICAL -> UiText.fromKey(
                        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_CRITICAL_FORMAT,
                        probabilityPercent
                    )
                    RiskTier.HIGH -> UiText.fromKey(
                        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_HIGH_FORMAT,
                        probabilityPercent
                    )
                    RiskTier.MEDIUM -> UiText.fromKey(
                        DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_RISK_MEDIUM_FORMAT,
                        probabilityPercent
                    )
                    RiskTier.LOW -> null
                }
                riskText?.let { reasons.add(it) }
            }
        }
        
        // Unknown budget: always say the budget risk was not checked. budgetUnknown implies no
        // budget risk info (so no risk reason above), hence at most bills + anomaly + this one
        // fit the cap of 3 and this reason is never the one dropped. No "healthy" text here.
        if (budgetUnknown) {
            reasons.add(UiText.fromKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_BUDGET_UNCHECKED))
        }

        // Add generic message if no specific reasons
        if (reasons.isEmpty()) {
            reasons.add(if (urgencyScore <= GREEN_THRESHOLD) {
                UiText.fromKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_FINANCES_HEALTHY)
            } else {
                UiText.fromKey(DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_MONITOR_SPENDING)
            })
        }
        
        return reasons.take(3)
    }
    
    /**
     * Determine the primary action based on urgency.
     */
    private fun determinePrimaryAction(
        dueBills: List<UpcomingBill>,
        anomalies: List<AnomalyAlertSummary>,
        budgetRisk: BudgetRiskInfo?
    ): MoneyRadarAction? {
        // Priority: Budget Critical > Anomalies > Due Bills > Budget Warning
        
        return when {
            budgetRisk?.riskTier == RiskTier.CRITICAL -> {
                MoneyRadarAction.AdjustBudget(budgetRisk)
            }
            anomalies.isNotEmpty() -> {
                MoneyRadarAction.ReviewAnomalies(anomalies.take(3))
            }
            dueBills.isNotEmpty() -> {
                MoneyRadarAction.ViewBills(dueBills.take(3))
            }
            budgetRisk?.riskTier == RiskTier.HIGH || budgetRisk?.riskTier == RiskTier.MEDIUM -> {
                MoneyRadarAction.AdjustBudget(budgetRisk)
            }
            else -> null
        }
    }
}
