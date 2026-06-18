package com.yourname.expensetracker.domain.groups

import android.util.Log
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.di.IoDispatcher
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder

/**
 * Calculates effective budget spend on an accrual basis.
 *
 * Formula: budgetEffectiveSpend = personalSpend + sharedLiability
 * where sharedLiability = sum(myShareOfSharedExpenses)
 *
 * Note: reimbursements are cash-flow events and are intentionally excluded from budget spend.
 *
 * ## SHR-3: Archived groups excluded from budget offsets (planned)
 * Currently [calculateEffectiveBudgetSpend] calls
 * [GroupsRepository.getActiveGroupsWithDetails] which only returns groups
 * where `isActive = true`. Archived groups' shared expenses are thus completely
 * excluded from budget offset calculations.
 *
 * This is problematic because a shared expense doesn't stop being a liability
 * just because the group was archived. The user still has an accrual obligation
 * for their share of past purchases even if the group is no longer active.
 *
 * The plan is to include archived groups' expenses in the budget offset:
 * 1. Replace `getActiveGroupsWithDetails()` with `getAllGroupsWithDetails()`
 *    that also fetches groups where `isActive = false`.
 * 2. Add an `isArchived` flag to [BudgetSpendBreakdown] so downstream consumers
 *    can distinguish active-group offsets from archived-group offsets.
 * 3. In the UI (budget status cards), consider showing a footnote when
 *    archived-group expenses contribute to the effective spend.
 * 4. Be mindful of data volume: if a user has many archived groups with
 *    hundreds of past expenses, consider a time-bounded query (e.g. only
 *    include archived expenses within the current budget period).
 */
@Singleton
class SharedExpenseBudgetOffsetEngine @Inject constructor(
    private val groupsRepository: Lazy<GroupsRepository>,
    private val expenseRepository: ExpenseRepository,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private companion object {
        private const val SETTLEMENT_EPSILON = 0.01
    }

    /**
     * Calculate the effective budget spend for a period on an accrual basis.
     *
     * @param periodStart Start of the period (inclusive) in milliseconds
     * @param periodEnd End of the period (exclusive) in milliseconds
     * @param categoryId Optional category filter (null for all categories)
     * @return BudgetSpendBreakdown with detailed breakdown of spend components
     */
    suspend fun calculateEffectiveBudgetSpend(
        periodStart: Long,
        periodEnd: Long,
        categoryId: Long? = null
    ): BudgetSpendBreakdown = withContext(ioDispatcher) {
        val homeCurrency = try { currencySettingsRepository.homeCurrency().first() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("BudgetOffset", "Failed to read home currency, defaulting to EUR", e)
            "EUR"
        }
        val allPeriodExpenses = expenseRepository.getExpensesBetween(periodStart, periodEnd)
        val expenseCategoryMap = allPeriodExpenses.associateBy { it.id }
        val activeGroups = groupsRepository.get().getActiveGroupsWithDetails()

        val inScopeGroupExpenses = activeGroups.mapNotNull { groupAggregate ->
            val currentUserMember = groupAggregate.members.find { it.isCurrentUser } ?: return@mapNotNull null
            InScopeGroupExpenses(
                currentUserMember = currentUserMember,
                members = groupAggregate.members,
                expenses = groupAggregate.expenses.filter {
                    it.date >= periodStart && it.date < periodEnd &&
                        (categoryId == null || it.expenseId?.let { expenseId ->
                            expenseCategoryMap[expenseId]?.categoryId
                        } == categoryId)
                }
            )
        }

        val linkedExpenseIds = inScopeGroupExpenses
            .flatMap { scope -> scope.expenses.mapNotNull { it.expenseId } }
            .toSet()

        val personalExpenses = allPeriodExpenses.filter { expense ->
            !expense.isSharedExpense &&
                !expense.isNotMine &&
                expense.transactionType == TransactionType.PURCHASE &&
                expense.id !in linkedExpenseIds &&
                (categoryId == null || expense.categoryId == categoryId)
        }

        // G07: Use convertAsOf per expense for historical accuracy instead of latest rate.
        var totalPersonalSpend = 0.0
        var failedConversionCount = 0
        val conversionWarnings = mutableListOf<String>()
        val personalBuckets = mutableListOf<Pair<Double, String>>()
        for (expense in personalExpenses) {
            val conversion = currencyConverter.convertAsOf(
                amount = expense.effectiveAmount,
                fromCurrency = expense.currency,
                toCurrency = homeCurrency,
                atMillis = expense.date
            )
            personalBuckets.add(expense.effectiveAmount to expense.currency)
            if (conversion != null) {
                totalPersonalSpend += conversion.convertedAmount
            } else {
                failedConversionCount++
                val msg = "Personal spend conversion failed for ${expense.merchant}: ${expense.effectiveAmount} ${expense.currency} -> $homeCurrency at ${expense.date}"
                conversionWarnings.add(msg)
                android.util.Log.w("BudgetOffset", msg)
            }
        }
        // G07: Use convertAsOf per expense for historical accuracy instead of latest rate.
        var totalSharedSpend = 0.0
        var totalReimbursed = 0.0
        val sharedBuckets = mutableListOf<Pair<Double, String>>()

        for (scope in inScopeGroupExpenses) {
            for (groupExpense in scope.expenses) {
                if (SplitCalculator.isMemberParticipatingInSplit(
                        expense = groupExpense,
                        members = scope.members,
                        memberId = scope.currentUserMember.id
                    )) {
                    val share = SplitCalculator.calculateMemberShare(
                        expense = groupExpense,
                        members = scope.members,
                        memberId = scope.currentUserMember.id
                    )
                    sharedBuckets.add(share to groupExpense.currency)
                    val conversion = currencyConverter.convertAsOf(
                        amount = share,
                        fromCurrency = groupExpense.currency,
                        toCurrency = homeCurrency,
                        atMillis = groupExpense.date
                    )
                    if (conversion != null) {
                        totalSharedSpend += conversion.convertedAmount
                    } else {
                        failedConversionCount++
                        val msg = "Shared spend conversion failed: $share ${groupExpense.currency} -> $homeCurrency at ${groupExpense.date}"
                        conversionWarnings.add(msg)
                        android.util.Log.w("BudgetOffset", msg)
                    }
                }

                if (groupExpense.isReimbursable && groupExpense.paidById == scope.currentUserMember.id) {
                    val reimb = groupExpense.reimbursedAmount.coerceAtLeast(0.0)
                    val conversion = currencyConverter.convertAsOf(
                        amount = reimb,
                        fromCurrency = groupExpense.currency,
                        toCurrency = homeCurrency,
                        atMillis = groupExpense.date
                    )
                    if (conversion != null) {
                        totalReimbursed += conversion.convertedAmount
                    } else {
                        failedConversionCount++
                        val msg = "Reimbursed conversion failed: $reimb ${groupExpense.currency} -> $homeCurrency at ${groupExpense.date}"
                        conversionWarnings.add(msg)
                        android.util.Log.w("BudgetOffset", msg)
                    }
                }
            }
        }

        val netSharedLiability = totalSharedSpend
        val effectiveBudgetSpend = totalPersonalSpend + totalSharedSpend
        val isPartial = failedConversionCount > 0

        // G04: Build aggregates from the already-converted per-expense amounts
        // so that numeric totals and aggregate displayAmount agree.
        // Each expense was already converted via convertAsOf(expense.date) above,
        // so we use the same converted amounts for the bucket totals.
        val grossPersonalAggregate = MoneyAggregate.singleCurrency(
            amount = totalPersonalSpend,
            currency = CurrencyCode(homeCurrency),
            transactionCount = personalBuckets.size
        )
        val sharedContributionAggregate = MoneyAggregate.singleCurrency(
            amount = totalSharedSpend,
            currency = CurrencyCode(homeCurrency),
            transactionCount = sharedBuckets.size
        )
        val adjustedSpendAggregate = MoneyAggregate.singleCurrency(
            amount = effectiveBudgetSpend,
            currency = CurrencyCode(homeCurrency),
            transactionCount = personalBuckets.size + sharedBuckets.size
        )

        BudgetSpendBreakdown(
            totalPersonalSpend = totalPersonalSpend,
            totalSharedSpend = totalSharedSpend,
            totalReimbursed = totalReimbursed,
            netSharedLiability = netSharedLiability,
            effectiveBudgetSpend = effectiveBudgetSpend,
            displayCurrency = homeCurrency,
            isPartial = isPartial,
            conversionWarnings = conversionWarnings,
            failedConversionCount = failedConversionCount,
            grossPersonalAggregate = grossPersonalAggregate,
            sharedContributionAggregate = sharedContributionAggregate,
            adjustedSpendAggregate = adjustedSpendAggregate
        )
    }

    /**
     * Check if an expense is fully settled (all reimbursements complete).
     */
    fun isExpenseFullySettled(expense: GroupExpense, members: List<GroupMember>): Boolean {
        if (!expense.isReimbursable) return true // Non-reimbursable expenses are always "settled"
        if (expense.settledAt != null) return true // Explicitly marked as settled

        // Check if total reimbursed equals what others owe
        val payerShare = if (SplitCalculator.isMemberParticipatingInSplit(
                expense = expense,
                members = members,
                memberId = expense.paidById
            )) {
            SplitCalculator.calculateMemberShare(
                expense = expense,
                members = members,
                memberId = expense.paidById
            )
        } else {
            0.0
        }
        val expectedReimbursement = expense.totalAmount - payerShare

        return expense.reimbursedAmount + SETTLEMENT_EPSILON >= expectedReimbursement
    }
}

private data class InScopeGroupExpenses(
    val currentUserMember: GroupMember,
    val members: List<GroupMember>,
    val expenses: List<GroupExpense>
)

/**
 * Breakdown of budget spend components.
 */
data class BudgetSpendBreakdown(
    val totalPersonalSpend: Double,      // Non-shared expenses
    val totalSharedSpend: Double,      // Sum of my shares in all shared expenses
    val totalReimbursed: Double,       // Sum of received reimbursements
    val netSharedLiability: Double,      // Accrual liability used for budgeting (equals sharedSpend)
    val effectiveBudgetSpend: Double,     // Personal + sharedSpend (what counts against budget)
    val displayCurrency: String = "EUR",
    val grossPersonalAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val sharedContributionAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val adjustedSpendAggregate: MoneyAggregate = MoneyAggregate.empty(CurrencyCode("EUR")),
    val isPartial: Boolean = false,           // PR 3: true when any conversions failed
    val conversionWarnings: List<String> = emptyList(),  // PR 3: per-currency warning messages
    val failedConversionCount: Int = 0         // PR 3: number of conversion failures
) {
    /**
     * Returns the amount pending reimbursement (positive = I'm owed money, negative = I owe money)
     */
    fun getPendingReimbursement(): Double = totalSharedSpend - totalReimbursed

    /**
     * Returns true if there are pending reimbursements affecting the budget
     */
    fun hasPendingReimbursements(): Boolean = kotlin.math.abs(getPendingReimbursement()) > 0.01
}
