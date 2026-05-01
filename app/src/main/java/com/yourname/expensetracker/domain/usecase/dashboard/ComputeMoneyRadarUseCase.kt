package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
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
    RED     // 61-100
}

/**
 * Data class representing Money Radar widget data.
 */
data class MoneyRadarData(
    val urgencyScore: Int, // 0-100
    val urgencyLevel: UrgencyLevel,
    val dueBills: List<UpcomingBill>,
    val anomalyAlerts: List<AnomalyAlertSummary>,
    val budgetRisk: BudgetRiskInfo?,
    val topReasons: List<UiText>,
    val primaryCta: MoneyRadarAction?
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
    val riskTier: RiskTier
)

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
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
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
    }

    /**
     * Compute the Money Radar widget data.
     */
    suspend fun compute(): MoneyRadarData = coroutineScope {
        val now = timeProvider.now()
        
        // Gather all data in parallel where possible
        val dueBillsDeferred = async { getDueBills(now) }
        val anomalyAlertsDeferred = async { getUnresolvedAnomalies(now) }
        val budgetRiskDeferred = async { getBudgetRisk(now) }

        val dueBills = dueBillsDeferred.await()
        val anomalyAlerts = anomalyAlertsDeferred.await()
        val budgetRisk = budgetRiskDeferred.await()
        
        // Calculate urgency score using weighted factors
        val dueBillsScore = calculateDueBillsScore(dueBills, now)
        val anomalyScore = calculateAnomalyScore(anomalyAlerts)
        val budgetRiskScore = calculateBudgetRiskScore(budgetRisk)
        
        // Weighted sum (0-100)
        val rawScore = (
            dueBillsScore * WEIGHT_DUE_BILLS +
            anomalyScore * WEIGHT_ANOMALIES +
            budgetRiskScore * WEIGHT_BUDGET_RISK
        ).toInt()
        
        val urgencyScore = rawScore.coerceIn(0, 100)
        val urgencyLevel = when {
            urgencyScore <= GREEN_THRESHOLD -> UrgencyLevel.GREEN
            urgencyScore <= YELLOW_THRESHOLD -> UrgencyLevel.YELLOW
            else -> UrgencyLevel.RED
        }
        
        // Determine top reasons and primary CTA
        val topReasons = buildTopReasons(dueBills, anomalyAlerts, budgetRisk, urgencyScore)
        val primaryCta = determinePrimaryAction(dueBills, anomalyAlerts, budgetRisk)
        
        Timber.d("Money Radar computed: score=$urgencyScore, level=$urgencyLevel, " +
                "bills=${dueBills.size}, anomalies=${anomalyAlerts.size}, risk=$budgetRisk")
        
        MoneyRadarData(
            urgencyScore = urgencyScore,
            urgencyLevel = urgencyLevel,
            dueBills = dueBills,
            anomalyAlerts = anomalyAlerts,
            budgetRisk = budgetRisk,
            topReasons = topReasons,
            primaryCta = primaryCta
        )
    }
    
    /**
     * Get bills due within the next 7 days from recurring patterns.
     */
    private suspend fun getDueBills(now: Long): List<UpcomingBill> {
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
                        amount = pattern.averageAmount,
                        dueDate = pattern.nextExpectedDate,
                        daysUntilDue = daysUntil
                    )
                }
                .sortedBy { it.daysUntilDue }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching recurring patterns for Money Radar")
            emptyList()
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
            Timber.e(e, "Error fetching anomaly alerts for Money Radar")
            emptyList()
        }
    }
    
    /**
     * Get budget risk information using Monte Carlo simulation.
     */
    private suspend fun getBudgetRisk(now: Long): BudgetRiskInfo? {
        return try {
            val startOfToday = TimePeriodUtils.getStartOfDay(now)

            // Get monthly budget
            val budgetStatuses = budgetRepository.getBudgetStatuses().first()
            val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
            val budgetAmount = overallBudget?.budget?.amount ?: 0.0
            
            if (budgetAmount <= 0) {
                return null // No budget set
            }
            
            // Get current month spending
            val (monthStart, _) = TimePeriodUtils.getMonthRange(now)
            val expenses = expenseRepository.getExpensesSince(monthStart)
            
            val spentToDate = expenses
                .filter {
                    it.transactionType.toDomain() == DomainTransactionType.PURCHASE &&
                        !it.isNotMine &&
                        it.date <= now
                }
                // SAFE: Callers must normalize through AnalyticsCurrencyNormalizer before invoking this use case.
                // This sum operates on pre-normalized amounts only.
                .sumOf { it.effectiveAmount }
            
            // Include known upcoming recurring obligations in next 7 days
            val windowEnd = now + (BILL_WINDOW_DAYS * ONE_DAY_MS)
            val upcomingRecurring = recurringPatternsProvider.getConfirmedPatterns()
                .filter { it.nextExpectedDate in startOfToday..windowEnd }
                .sumOf { it.averageAmount }

            // Run Monte Carlo simulation
            val mcResult = monteCarloSimulator.simulate(
                spentToDate = spentToDate,
                knownUpcoming = upcomingRecurring,
                budgetAmount = budgetAmount
            )
            
            mcResult?.let { result ->
                // Use GetMonteCarloBudgetImpactUseCase to get proper risk assessment
                when (val impact = getMonteCarloBudgetImpact(budgetAmount, result)) {
                    is com.yourname.expensetracker.domain.model.Result.Success -> {
                        BudgetRiskInfo(
                            expectedOverrun = impact.data.expectedOverrun,
                            probabilityOfOverrun = impact.data.probabilityOfOverrun,
                            riskTier = impact.data.riskTier
                        )
                    }
                    is com.yourname.expensetracker.domain.model.Result.Error -> {
                        Timber.w("Could not compute budget impact: ${impact.message}")
                        null
                    }
                    com.yourname.expensetracker.domain.model.Result.Duplicate -> null
                    com.yourname.expensetracker.domain.model.Result.Loading -> null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error computing budget risk for Money Radar")
            null
        }
    }
    
    /**
     * Calculate due bills score (0-100 before weighting).
     */
    private suspend fun calculateDueBillsScore(dueBills: List<UpcomingBill>, now: Long): Int {
        if (dueBills.isEmpty()) return BILLS_SCORE_0
        
        val baseScore = when (dueBills.size) {
            1, 2 -> BILLS_SCORE_1_2
            else -> BILLS_SCORE_3_PLUS
        }
        
        // Check for any large bill (>50% of monthly income)
        val monthlyIncome = getMonthlyIncome(now)
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
    
    /**
     * Get monthly income (deposits) for large bill threshold calculation.
     */
    private suspend fun getMonthlyIncome(now: Long): Double {
        return try {
            val (monthStart, _) = TimePeriodUtils.getMonthRange(now)
            expenseRepository.getTotalDepositsForPeriod(monthStart, now)
        } catch (e: Exception) {
            0.0
        }
    }
    
    /**
     * Build top reasons list for the urgency score.
     */
    private fun buildTopReasons(
        dueBills: List<UpcomingBill>,
        anomalies: List<AnomalyAlertSummary>,
        budgetRisk: BudgetRiskInfo?,
        urgencyScore: Int
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

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}
