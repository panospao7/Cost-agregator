package com.yourname.expensetracker.domain.subscription

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.database.entity.SubscriptionUsage
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Tracks price changes for a subscription over time.
 */
/**
 * Represents a recorded price change for a subscription.
 *
 * REC-8: When a subscription is first created a BASELINE entry is inserted
 * so the system always has a reference point for calculating price change
 * percentages, even before any actual price change occurs.
 */
data class PriceChange(
    val oldAmount: Double,
    val newAmount: Double,
    val changePercent: Double,
    val recordedAt: Long,
    val reason: String?,
    /** REC-8: Distinguishes the initial baseline recording from actual changes. */
    val priceChangeType: String = "CHANGE" // "BASELINE" or "CHANGE"
)

/**
 * Usage statistics for a subscription.
 */
data class UsageStats(
    val totalUses: Int,
    val usesThisMonth: Int,
    val usesLastMonth: Int,
    val averageUsesPerMonth: Double,
    val targetUsesPerMonth: Int?,
    val usagePercentage: Double, // How much of target they're using
    val costPerUse: Double, // How much each use costs
    val lastUsedAt: Long?,
    val trend: UsageTrend
)

enum class UsageTrend {
    INCREASING, // Using it more
    DECREASING, // Using it less
    STABLE,     // Consistent usage
    UNUSED      // No usage recorded
}

/**
 * Recommendation for subscription management.
 */
data class SubscriptionRecommendation(
    val subscription: ManualRecurringExpense,
    val type: RecommendationType,
    val title: UiText,
    val description: String,
    val potentialSavings: Double,
    val confidence: Double,
    val action: RecommendedAction
)

enum class RecommendationType {
    PRICE_INCREASE,
    UNDERUTILIZED,
    UNUSED,
    BETTER_ALTERNATIVE,
    CANCELLATION_OPPORTUNITY
}

enum class RecommendedAction {
    REVIEW_USAGE,
    CONSIDER_CANCELLATION,
    NEGOTIATE_PRICE,
    SWITCH_PLAN,
    KEEP_SUBSCRIPTION
}

/**
 * Complete subscription analysis with all metrics.
 */
data class SubscriptionAnalysis(
    val subscription: ManualRecurringExpense,
    val currentPrice: Double,
    val priceHistory: List<PriceChange>,
    val totalPriceIncrease: Double,
    val usageStats: UsageStats,
    val recommendations: List<SubscriptionRecommendation>,
    val healthScore: Int // 0-100, higher is better value
)

/**
 * Request to create a new subscription.
 * Validated by [SubscriptionManagerEngine.validateAndCreate].
 */
data class CreateSubscriptionRequest(
    val merchant: String,
    val amount: Double,
    val currency: String,
    val frequency: RecurrenceFrequency,
    val startDate: Long,
    val recordPriceHistory: Boolean = true
)

@Singleton
class SubscriptionManagerEngine @Inject constructor(
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val priceHistoryDao: SubscriptionPriceHistoryDao,
    private val usageDao: SubscriptionUsageDao,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository
) {
    
    /**
     * Validate a subscription creation request, persist the subscription and
     * its initial baseline price history, and return the created entity.
     *
     * @param request The validated creation request.
     * @return [Result.success] with the created [ManualRecurringExpense] (including its generated ID),
     *         or [Result.failure] if validation fails.
     * @throws IllegalArgumentException if any validation constraint is violated.
     */
    suspend fun validateAndCreate(request: CreateSubscriptionRequest): Result<ManualRecurringExpense> {
        require(request.amount > 0) { "Amount must be positive" }
        require(request.currency.isNotBlank() && request.currency.length == 3) { "Invalid currency" }
        require(request.merchant.isNotBlank()) { "Merchant is required" }

        val now = timeProvider.now()
        val subscription = ManualRecurringExpense(
            merchant = request.merchant,
            amount = request.amount,
            currency = request.currency.uppercase(),
            frequency = request.frequency,
            nextDate = request.startDate,
            createdAt = now,
            isActive = true
        )
        val id = recurringExpenseRepository.insert(subscription)

        // Also record baseline price history with recordedAt set
        if (request.recordPriceHistory) {
            priceHistoryDao.insert(
                SubscriptionPriceHistory(
                    subscriptionId = id,
                    amount = request.amount,
                    currency = request.currency,
                    recordedAt = now
                )
            )
        }
        return Result.success(subscription.copy(id = id))
    }

    /**
     * Get all active subscriptions with full analysis.
     */
    suspend fun getAllSubscriptions(): List<SubscriptionAnalysis> {
        val allRecurring = recurringExpenseRepository.getAll()
        val subscriptions = allRecurring.filter { it.isSubscription && it.isActive }
        
        val analyses = mutableListOf<SubscriptionAnalysis>()
        for (sub in subscriptions) {
            val analysis = analyzeSubscription(sub)
            analyses.add(analysis)
        }
        
        return analyses
    }
    
    /**
     * Analyze a single subscription with price history, usage, and recommendations.
     */
    suspend fun analyzeSubscription(subscription: ManualRecurringExpense): SubscriptionAnalysis {
        val priceHistory = getPriceHistory(subscription.id)
        val usageStats = calculateUsageStats(subscription)
        val recommendations = generateRecommendations(subscription, priceHistory, usageStats)
        
        // Calculate total price increase
        var totalIncrease = 0.0
        for (change in priceHistory) {
            totalIncrease += change.newAmount - change.oldAmount
        }
        
        // Calculate health score (0-100)
        val healthScore = calculateHealthScore(subscription, priceHistory, usageStats)
        
        return SubscriptionAnalysis(
            subscription = subscription,
            currentPrice = subscription.amount,
            priceHistory = priceHistory,
            totalPriceIncrease = totalIncrease,
            usageStats = usageStats,
            recommendations = recommendations,
            healthScore = healthScore
        )
    }
    
    /**
     * Record a new usage instance for a subscription.
     */
    suspend fun recordUsage(
        subscriptionId: Long,
        durationMinutes: Int? = null,
        usageType: String? = null
    ) {
        val usage = SubscriptionUsage(
            subscriptionId = subscriptionId,
            usedAt = timeProvider.now(),
            usageDurationMinutes = durationMinutes,
            usageType = usageType
        )
        usageDao.insert(usage)
    }
    
    /**
     * Record a price change for a subscription.
     *
     * REC-7: After recording the price history entry, also updates the
     * [ManualRecurringExpense.amount] on the subscription entity so that
     * downstream consumers (dashboard, budget calculations, recurring
     * expense generation) see the current price without stale data.
     */
    suspend fun recordPriceChange(
        subscriptionId: Long,
        newAmount: Double,
        reason: String? = null
    ) {
        // Get previous price
        val previousPrice = priceHistoryDao.getLatestPrice(subscriptionId)?.amount
            ?: recurringExpenseRepository.getAll().find { it.id == subscriptionId }?.amount
            ?: newAmount
        
        // Only record if price actually changed
        if (abs(newAmount - previousPrice) > 0.01) {
            // W04: Set recordedAt to timeProvider.now() to avoid the 0L sentinel
            val priceHistory = SubscriptionPriceHistory(
                subscriptionId = subscriptionId,
                amount = newAmount,
                recordedAt = timeProvider.now(),
                changeReason = reason
            )
            priceHistoryDao.insert(priceHistory)

            // REC-7: Update the subscription's current amount so it reflects
            // the new price immediately rather than showing the old amount
            // until the next full sync.
            val subscription = recurringExpenseRepository.getById(subscriptionId)
            if (subscription != null && abs(subscription.amount - newAmount) > 0.01) {
                recurringExpenseRepository.update(subscription.copy(amount = newAmount))
            }

            // TODO (W07): Wrap priceHistoryDao.insert + recurringExpenseRepository.update
            // in database.withTransaction for atomicity. Requires injecting AppDatabase.
        }
    }
    
    /**
     * Get price history for a subscription.
     */
    private suspend fun getPriceHistory(subscriptionId: Long): List<PriceChange> {
        val history = priceHistoryDao.getAllPricesForSubscription(subscriptionId)
        val changes = mutableListOf<PriceChange>()
        
        for (i in 1 until history.size) {
            val old = history[i - 1]
            val new = history[i]
            val changePercent = if (old.amount > 0) {
                ((new.amount - old.amount) / old.amount) * 100
            } else 0.0
            
            changes.add(PriceChange(
                oldAmount = old.amount,
                newAmount = new.amount,
                changePercent = changePercent,
                recordedAt = new.recordedAt,
                reason = new.changeReason
            ))
        }
        
        return changes
    }
    
    /**
     * Calculate usage statistics for a subscription.
     */
    private suspend fun calculateUsageStats(subscription: ManualRecurringExpense): UsageStats {
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.addMonths(now, -1)
        val lastMonthStart = TimePeriodUtils.addMonths(monthStart, -1)
        
        // Get all usage data
        val allUsage = usageDao.getUsageSince(subscription.id, 0)
        val thisMonthUsage = usageDao.getUsageBetween(subscription.id, monthStart, now)
        val lastMonthUsage = usageDao.getUsageBetween(subscription.id, lastMonthStart, monthStart)
        
        // Calculate totals
        val totalUses = allUsage.size
        val usesThisMonth = thisMonthUsage.size
        val usesLastMonth = lastMonthUsage.size
        
        // Calculate average uses per month
        var averageUsesPerMonth = 0.0
        if (allUsage.isNotEmpty()) {
            val oldestUsage = allUsage.minByOrNull { it.usedAt }?.usedAt ?: now
            // Use calendar-aware month counting (DST-safe, handles varying month lengths)
            // W05: Prevent divide-by-zero when monthsActive rounds to 0 (< 30 days)
            val monthsActive = (TimePeriodUtils.daysBetween(oldestUsage, now).coerceAtLeast(1) / 30).coerceAtLeast(1)
            averageUsesPerMonth = totalUses.toDouble() / monthsActive
        }
        
        // Determine trend
        val trend = when {
            totalUses == 0 -> UsageTrend.UNUSED
            usesThisMonth > usesLastMonth -> UsageTrend.INCREASING
            usesThisMonth < usesLastMonth -> UsageTrend.DECREASING
            else -> UsageTrend.STABLE
        }
        
        // Calculate usage percentage vs target
        val target = subscription.usageTargetPerMonth
        val usagePercentage = if (target != null && target > 0) {
            (usesThisMonth.toDouble() / target) * 100
        } else 100.0 // No target = assume 100% usage
        
        // Calculate cost per use using monthly-normalised amount
        val costPerUse = if (totalUses > 0) {
            // Monthly cost / average monthly uses
            val monthlyAmount = RecurrenceCalculator.toMonthlyAmount(subscription.amount, subscription.frequency)
            monthlyAmount / averageUsesPerMonth
        } else {
            RecurrenceCalculator.toMonthlyAmount(subscription.amount, subscription.frequency)
        }
        
        val lastUsedAt = allUsage.maxByOrNull { it.usedAt }?.usedAt
        
        return UsageStats(
            totalUses = totalUses,
            usesThisMonth = usesThisMonth,
            usesLastMonth = usesLastMonth,
            averageUsesPerMonth = averageUsesPerMonth,
            targetUsesPerMonth = target,
            usagePercentage = usagePercentage,
            costPerUse = costPerUse,
            lastUsedAt = lastUsedAt,
            trend = trend
        )
    }
    
    /**
     * Generate recommendations based on price and usage analysis.
     */
    private fun generateRecommendations(
        subscription: ManualRecurringExpense,
        priceHistory: List<PriceChange>,
        usageStats: UsageStats
    ): List<SubscriptionRecommendation> {
        val recommendations = mutableListOf<SubscriptionRecommendation>()
        
        // Check for recent price increases (90-day calendar-aware lookback)
        val ninetyDaysAgo = TimePeriodUtils.getLastNCalendarDaysRange(timeProvider.now(), 90).first
        val recentPriceIncreases = priceHistory.filter { 
            it.recordedAt > ninetyDaysAgo && it.changePercent > 0
        }
        
        for (increase in recentPriceIncreases) {
            recommendations.add(SubscriptionRecommendation(
                subscription = subscription,
                type = RecommendationType.PRICE_INCREASE,
                title = UiText.fromKey("domain_subscription_price_increased", increase.changePercent),
                description = "Your subscription price went from ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", increase.oldAmount)} to ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", increase.newAmount)}. ${increase.reason ?: ""}",
                potentialSavings = 0.0,
                confidence = 0.9,
                action = RecommendedAction.NEGOTIATE_PRICE
            ))
        }
        
        // Check for underutilization
        if (usageStats.targetUsesPerMonth != null && usageStats.usagePercentage < 50.0) {
            val potentialSavings = subscription.amount * 0.5 // Assume could downgrade to half price
            recommendations.add(SubscriptionRecommendation(
                subscription = subscription,
                type = RecommendationType.UNDERUTILIZED,
                title = UiText.fromKey("domain_subscription_underutilized", usageStats.usesThisMonth, usageStats.targetUsesPerMonth),
                description = "You're only using ${String.format("%.0f", usageStats.usagePercentage)}% of your expected usage. Consider downgrading or canceling.",
                potentialSavings = potentialSavings,
                confidence = 0.85,
                action = RecommendedAction.CONSIDER_CANCELLATION
            ))
        }
        
        // Check for complete non-usage
        if (usageStats.totalUses == 0 && subscription.createdAt < TimePeriodUtils.addMonths(timeProvider.now(), -1)) {
            recommendations.add(SubscriptionRecommendation(
                subscription = subscription,
                type = RecommendationType.UNUSED,
                title = UiText.fromKey("domain_subscription_no_usage"),
                description = "You haven't recorded any usage for this subscription in the past month. Consider canceling to save ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", subscription.amount)} per month.",
                potentialSavings = subscription.amount,
                confidence = 0.95,
                action = RecommendedAction.CONSIDER_CANCELLATION
            ))
        }
        
        // High cost per use warning
        if (usageStats.costPerUse > 5.0 && usageStats.totalUses > 0) {
            recommendations.add(SubscriptionRecommendation(
                subscription = subscription,
                type = RecommendationType.CANCELLATION_OPPORTUNITY,
                title = UiText.fromKey("domain_subscription_high_cost"),
                description = "Each use costs you ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", usageStats.costPerUse)}. This might not be worth it.",
                potentialSavings = subscription.amount * 0.5,
                confidence = 0.75,
                action = RecommendedAction.REVIEW_USAGE
            ))
        }
        
        return recommendations
    }
    
    /**
     * Calculate a health score for a subscription (0-100).
     */
    private fun calculateHealthScore(
        subscription: ManualRecurringExpense,
        priceHistory: List<PriceChange>,
        usageStats: UsageStats
    ): Int {
        var score = 100
        
        // Deduct for price increases
        val totalIncreasePercent = priceHistory.sumOf { it.changePercent }
        score -= (totalIncreasePercent * 0.5).toInt().coerceAtMost(30)
        
        // Deduct for underutilization
        if (usageStats.targetUsesPerMonth != null) {
            val utilizationDeduction = ((100 - usageStats.usagePercentage) * 0.3).toInt()
            score -= utilizationDeduction.coerceAtMost(40)
        }
        
        // Deduct for no usage
        if (usageStats.totalUses == 0) {
            score -= 50
        }
        
        // Deduct for high cost per use
        if (usageStats.costPerUse > 10.0) {
            score -= 20
        } else if (usageStats.costPerUse > 5.0) {
            score -= 10
        }
        
        return score.coerceIn(0, 100)
    }
    
    /**
     * Get total monthly subscription cost.
     *
     * Normalises each subscription to its monthly equivalent using
     * [RecurrenceCalculator.toMonthlyAmount] so that WEEKLY, BIWEEKLY, QUARTERLY,
     * SEMI_ANNUALLY and ANNUALLY frequencies are correctly represented as a
     * monthly cost rather than using the raw per-period amount.
     */
    @Deprecated(
        "Raw Double sums across potentially multiple currencies. Use getTotalMonthlySubscriptionCostAggregate() instead.",
        ReplaceWith("getTotalMonthlySubscriptionCostAggregate().displayAmount")
    )
    suspend fun getTotalMonthlySubscriptionCost(): Double {
        val subscriptions = getAllSubscriptions()
        var total = 0.0
        for (analysis in subscriptions) {
            total += RecurrenceCalculator.toMonthlyAmount(
                analysis.subscription.amount,
                analysis.subscription.frequency
            )
        }
        return total
    }
    
    /**
     * Get total monthly subscription cost as a MoneyAggregate grouped by currency.
     *
     * Normalises each subscription to its monthly equivalent using
     * [RecurrenceCalculator.toMonthlyAmount], then groups by currency so that
     * multi-currency subscriptions are correctly represented without silent
     * raw-summing across different currencies.
     *
     * W06: Now converts all currencies to home currency using CurrencyConverter.
     */
    suspend fun getTotalMonthlySubscriptionCostAggregate(): MoneyAggregate {
        val subscriptions = getAllSubscriptions()
        val byCurrency = mutableMapOf<String, Pair<Double, Int>>() // currency -> (total, count)
        
        for (analysis in subscriptions) {
            val monthly = RecurrenceCalculator.toMonthlyAmount(
                analysis.subscription.amount, analysis.subscription.frequency
            )
            val currency = analysis.subscription.currency.uppercase()
            val (existingTotal, existingCount) = byCurrency.getOrDefault(currency, Pair(0.0, 0))
            byCurrency[currency] = Pair(existingTotal + monthly, existingCount + 1)
        }
        
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val buckets = byCurrency.map { Pair(it.value.first, it.key) }
        val counts = byCurrency.map { it.value.second }
        return MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, counts)
    }
    
    /**
     * Get subscriptions with the best value (high usage, low cost).
     */
    suspend fun getBestValueSubscriptions(): List<SubscriptionAnalysis> {
        val all = getAllSubscriptions()
        return all.filter { it.healthScore >= 70 }.sortedByDescending { it.healthScore }
    }
    
    /**
     * Get subscriptions that should be reviewed (low health score).
     */
    suspend fun getSubscriptionsToReview(): List<SubscriptionAnalysis> {
        val all = getAllSubscriptions()
        return all.filter { it.healthScore < 50 || it.recommendations.isNotEmpty() }
            .sortedBy { it.healthScore }
    }
    
    /**
     * Calculate potential savings from following all cancellation recommendations.
     *
     * REC-19: Takes the maximum savings per subscription instead of summing
     * across recommendation types (e.g. underutilization + cancellation)
     * to avoid double-counting the same subscription amount.
     */
    suspend fun calculatePotentialSavings(): Double {
        val toReview = getSubscriptionsToReview()
        var potentialSavings = 0.0
        for (analysis in toReview) {
            val maxForSubscription = analysis.recommendations
                .maxOfOrNull { it.potentialSavings }
                ?: 0.0
            potentialSavings += maxForSubscription
        }
        return potentialSavings
    }
}
