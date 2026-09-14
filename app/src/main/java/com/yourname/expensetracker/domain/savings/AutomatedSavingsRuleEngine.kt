package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

data class AutomatedSavingsRule(
    val id: Long = 0,
    val name: String,
    val ruleType: SavingsRuleType,
    val targetGoalId: Long,
    val percentage: Double? = null,     // For PERCENTAGE_OF_INCOME
    val roundUpTo: Double? = null,      // For ROUND_UP (e.g., 5.0 for nearest €5)
    val minimumAmount: Double? = null,  // Minimum deposit to trigger
    val maximumPerMonth: Double? = null, // Cap monthly auto-savings
    val isActive: Boolean = true
)

enum class SavingsRuleType {
    PERCENTAGE_OF_INCOME,    // Save X% of every deposit
    ROUND_UP,               // Round up purchases to nearest €X
    SPARE_CHANGE,          // Save purchases under €10
    WEEKLY_NO_SPEND,        // Save €X on weeks with no discretionary spending
    CUSTOM
}

data class RuleExecution(
    val rule: AutomatedSavingsRule,
    val amount: Double,
    val reason: String,
    val timestamp: Long
)

@Singleton
class AutomatedSavingsRuleEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val timeProvider: TimeProvider,
    private val ruleStateRepository: AutomatedSavingsRuleStateRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
) {
    companion object {
        // Default nearest-neighbor round-up in home currency
        private const val DEFAULT_ROUND_UP_PRECISION = 5.0
        // No-spend week thresholds and reward in home currency
        private const val NO_SPEND_THRESHOLD = 5.0
        private const val NO_SPEND_REWARD = 10.0
    }
    suspend fun evaluateRules(
        expense: Expense,
        rules: List<AutomatedSavingsRule>,
        displayCurrency: String = "EUR"
    ): List<RuleExecution> {
        val executions = mutableListOf<RuleExecution>()
        
        for (rule in rules) {
            if (!rule.isActive) continue
            
            val execution = when (rule.ruleType) {
                SavingsRuleType.PERCENTAGE_OF_INCOME -> 
                    evaluatePercentageRule(expense, rule, displayCurrency)
                SavingsRuleType.ROUND_UP -> 
                    evaluateRoundUpRule(expense, rule, displayCurrency)
                SavingsRuleType.SPARE_CHANGE -> 
                    evaluateSpareChangeRule(expense, rule, displayCurrency)
                SavingsRuleType.WEEKLY_NO_SPEND -> 
                    evaluateWeeklyNoSpendRule(rule, displayCurrency)
                SavingsRuleType.CUSTOM -> null
            }
            
            val cappedExecution = applyMonthlyCap(rule, execution)
            cappedExecution?.let { executions.add(it) }
        }
        
        return executions
    }
    
    private fun evaluatePercentageRule(
        expense: Expense,
        rule: AutomatedSavingsRule,
        displayCurrency: String
    ): RuleExecution? {
        // Only process deposits (income)
        if (expense.transactionType.toDomain() != DomainTransactionType.DEPOSIT || expense.amount <= 0) {
            return null
        }
        
        val percentage = rule.percentage ?: 10.0
        if (!percentage.isFinite() || percentage < 0.0) {
            Timber.tag("AutomatedSavingsRuleEngine").w(
                "Skipping PERCENTAGE_OF_INCOME rule %s due to invalid percentage=%s",
                rule.id,
                percentage
            )
            return null
        }

        val normalizedIncome = expense.amount.coerceAtLeast(0.0)
        val amount = normalizedIncome * (percentage / 100.0)
        if (!amount.isFinite() || amount <= 0.0) {
            return null
        }
        
        // Check minimum amount
        if (rule.minimumAmount != null && amount < rule.minimumAmount) {
            return null
        }
        
        return RuleExecution(
            rule = rule,
            amount = amount,
            reason = "${percentage}% of ${CurrencyFormatter.formatMoney(normalizedIncome, displayCurrency)} income",
            timestamp = timeProvider.now()
        )
    }
    
    private fun evaluateRoundUpRule(
        expense: Expense,
        rule: AutomatedSavingsRule,
        displayCurrency: String
    ): RuleExecution? {
        // Only process purchases
        if (expense.transactionType.toDomain() != DomainTransactionType.PURCHASE) {
            return null
        }

        if (expense.isNotMine) {
            return null
        }

        val candidateAmount = expense.effectiveAmount
        if (candidateAmount <= 0.0) {
            return null
        }
        
        // Default nearest-neighbor round-up in home currency
        val roundUpTo = rule.roundUpTo ?: DEFAULT_ROUND_UP_PRECISION
        if (!roundUpTo.isFinite() || roundUpTo <= 0.0) {
            Timber.tag("AutomatedSavingsRuleEngine").w(
                "Skipping ROUND_UP rule %s due to invalid roundUpTo=%s",
                rule.id,
                roundUpTo
            )
            return null
        }

        val remainder = candidateAmount % roundUpTo
        if (!remainder.isFinite()) {
            Timber.tag("AutomatedSavingsRuleEngine").w(
                "Skipping ROUND_UP rule %s due to non-finite remainder for amount=%.2f roundUpTo=%s",
                rule.id,
                candidateAmount,
                roundUpTo
            )
            return null
        }
        
        if (remainder > 0) {
            val roundUpAmount = roundUpTo - remainder
            if (!roundUpAmount.isFinite() || roundUpAmount <= 0.0) {
                Timber.tag("AutomatedSavingsRuleEngine").w(
                    "Skipping ROUND_UP rule %s due to invalid roundUpAmount=%s (amount=%.2f roundUpTo=%s)",
                    rule.id,
                    roundUpAmount,
                    candidateAmount,
                    roundUpTo
                )
                return null
            }

            val roundedTarget = kotlin.math.ceil(candidateAmount / roundUpTo) * roundUpTo
            if (!roundedTarget.isFinite()) {
                Timber.tag("AutomatedSavingsRuleEngine").w(
                    "Skipping ROUND_UP rule %s due to non-finite rounded target (amount=%.2f roundUpTo=%s)",
                    rule.id,
                    candidateAmount,
                    roundUpTo
                )
                return null
            }
            
            return RuleExecution(
                rule = rule,
                amount = roundUpAmount,
                reason = "Round up ${CurrencyFormatter.formatMoney(candidateAmount, displayCurrency)} to ${CurrencyFormatter.formatMoney(roundedTarget, displayCurrency)}",
                timestamp = timeProvider.now()
            )
        }
        
        return null
    }
    
    private fun evaluateSpareChangeRule(
        expense: Expense,
        rule: AutomatedSavingsRule,
        displayCurrency: String
    ): RuleExecution? {
        // Save small purchases (coffee, snacks, etc.)
        if (expense.transactionType.toDomain() != DomainTransactionType.PURCHASE) {
            return null
        }

        if (expense.isNotMine) {
            return null
        }

        val candidateAmount = expense.effectiveAmount
        if (candidateAmount <= 0.0) {
            return null
        }

        if (candidateAmount in 1.0..10.0) {
            return RuleExecution(
                rule = rule,
                amount = candidateAmount,
                reason = "Spare change: ${expense.merchant} ${CurrencyFormatter.formatMoney(candidateAmount, displayCurrency)}",
                timestamp = timeProvider.now()
            )
        }
        return null
    }
    
    private suspend fun evaluateWeeklyNoSpendRule(
        rule: AutomatedSavingsRule,
        displayCurrency: String
    ): RuleExecution? {
        // Check if this week had any discretionary spending
        val now = timeProvider.now()
        val homeCurrency = currencySettingsRepository.homeCurrency().first()
        val (weekStart, weekEnd) = TimePeriodUtils.getWeekRange(now)
        val rawWeekExpenses = expenseRepository.getExpenseSnapshotsBetween(weekStart, weekEnd)
        val weekNormalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawWeekExpenses, homeCurrency)
        val weekExpenses = weekNormalized.includedExpenses
        val categoriesById = categoryRepository.getAll().associateBy { it.id }
        
        // Filter out essential spending (groceries, bills, etc.)
        var discretionarySpending = 0.0
        for (expense in weekExpenses) {
            if (
                expense.transactionType == DomainTransactionType.PURCHASE &&
                expense.effectiveAmount > 0 &&
                !isEssentialCategory(expense.categoryId, categoriesById)
            ) {
                discretionarySpending += expense.effectiveAmount
            }
        }
        
        // If no discretionary spending, award savings
        if (discretionarySpending < NO_SPEND_THRESHOLD) {
            val rewardAmount = NO_SPEND_REWARD
            val reservationResult = ruleStateRepository.reserveWeeklyNoSpendRewardWithinMonthlyCap(
                ruleStableKey = ruleStableKey(rule),
                weekStart = weekStart,
                yearMonth = buildMonthKey(now),
                requestedAmount = rewardAmount,
                maximumPerMonth = rule.maximumPerMonth
            )
            if (!reservationResult.reserved) {
                return null
            }
            val reason = if (reservationResult.allowedAmount == rewardAmount) {
                "No discretionary spending this week! 🎉"
            } else {
                "No discretionary spending this week! 🎉 (monthly cap applied)"
            }
            
            return RuleExecution(
                rule = rule,
                amount = reservationResult.allowedAmount,
                reason = reason,
                timestamp = now
            )
        }
        
        return null
    }
    
    private suspend fun applyMonthlyCap(
        rule: AutomatedSavingsRule,
        execution: RuleExecution?
    ): RuleExecution? {
        val pendingExecution = execution ?: return null
        if (rule.ruleType == SavingsRuleType.WEEKLY_NO_SPEND) {
            return pendingExecution
        }
        val cap = rule.maximumPerMonth ?: return pendingExecution
        if (!cap.isFinite() || cap <= 0.0) return null

        val allowedAmount = ruleStateRepository.consumeMonthlyAmountWithinCap(
            ruleStableKey = ruleStableKey(rule),
            yearMonth = buildMonthKey(pendingExecution.timestamp),
            requestedAmount = pendingExecution.amount,
            maximumPerMonth = cap
        )

        if (allowedAmount <= 0.0) {
            return null
        }

        return if (allowedAmount == pendingExecution.amount) {
            pendingExecution
        } else {
            pendingExecution.copy(
                amount = allowedAmount,
                reason = "${pendingExecution.reason} (monthly cap applied)"
            )
        }
    }

    private fun ruleStableKey(rule: AutomatedSavingsRule): String {
        return if (rule.id > 0) {
            rule.id.toString()
        } else {
            "${rule.name}|${rule.ruleType}|${rule.targetGoalId}"
        }
    }

    private fun buildMonthKey(timestamp: Long): String {
        // G-TIME-01: pure derivation from the [timestamp] parameter (java.time,
        // system default timezone — same year/month fields the Calendar produced).
        val zoned = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
        return String.format(
            Locale.US,
            "%04d-%02d",
            zoned.year,
            zoned.monthValue
        )
    }

    private fun isEssentialCategory(
        categoryId: Long?,
        categoriesById: Map<Long, Category>
    ): Boolean {
        // Categories that don't count as discretionary
        val essentialCategories = setOf(
            "groceries", "rent", "utilities", "transport",
            "insurance", "healthcare", "bills", "mortgage", "education", "loan"
        )

        val categoryName = categoryId
            ?.let { categoriesById[it]?.name }
            ?.trim()
            ?.lowercase()

        return categoryName != null && categoryName in essentialCategories
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
