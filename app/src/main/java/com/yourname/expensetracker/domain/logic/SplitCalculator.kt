package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import java.math.BigDecimal
import java.math.RoundingMode
import timber.log.Timber
import kotlin.math.floor

/**
 * Utility class for calculating expense splits and member balances.
 * Handles various split types: EQUAL, PERCENTAGE, AMOUNT, and SHARES.
 */
object SplitCalculator {
    
    /**
     * Calculate split amounts for each member based on split type.
     * 
     * @param expense The group expense with split type information
     * @param members List of group members
     * @return Map of memberId -> amount they owe for this expense
     */
    fun calculateSplitAmounts(
        expense: GroupExpense,
        members: List<GroupMember>
    ): Map<Long, Double> {
        val splitValidationError = validateExpenseParticipants(expense, members)
        if (splitValidationError != null) {
            Timber.w(
                "Invalid split participants for expenseId=%s splitType=%s. Returning empty split. reason=%s",
                expense.id,
                expense.splitType,
                splitValidationError
            )
            return emptyMap()
        }

        val splitParticipants = getSplitParticipants(expense, members)
        if (splitParticipants.isEmpty()) return emptyMap()

        return when (expense.splitType) {
            SplitType.EQUAL -> calculateEqualSplit(expense.totalAmount, splitParticipants)
            SplitType.CUSTOM_PERCENT -> calculatePercentageSplit(expense, splitParticipants)
            SplitType.CUSTOM_AMOUNT -> calculateAmountSplit(expense, splitParticipants)
            SplitType.UNEQUAL -> calculateUnequalSplit(expense, splitParticipants)
        }
    }

    /**
     * Returns the members that participate in this expense split.
     * Equal splits only include members who had already joined when the expense happened.
     */
    fun getSplitParticipants(
        expense: GroupExpense,
        members: List<GroupMember>
    ): List<GroupMember> {
        if (members.isEmpty()) return emptyList()

        return when (expense.splitType) {
            SplitType.EQUAL -> members.filter {
                it.joinedAt <= expense.date && (it.leftAt == null || it.leftAt > expense.date)
            }
            SplitType.CUSTOM_PERCENT,
            SplitType.CUSTOM_AMOUNT,
            SplitType.UNEQUAL -> members.filter {
                it.leftAt == null || it.leftAt > expense.date
            }
        }
    }

    fun isMemberParticipatingInSplit(
        expense: GroupExpense,
        members: List<GroupMember>,
        memberId: Long
    ): Boolean {
        return getSplitParticipants(expense, members).any { it.id == memberId }
    }

    /**
     * Validates whether an expense has a coherent participant set for persistence/calculation.
     * Equal splits require at least one historical participant and must include the payer.
     */
    fun validateExpenseParticipants(
        expense: GroupExpense,
        members: List<GroupMember>
    ): String? {
        if (expense.splitType != SplitType.EQUAL) return null

        val splitParticipants = getSplitParticipants(expense, members)
        if (splitParticipants.isEmpty()) {
            return "Equal splits require at least one participant who joined on or before the expense date"
        }

        if (splitParticipants.none { it.id == expense.paidById }) {
            return "Equal splits require the payer to have joined on or before the expense date"
        }

        return null
    }
    
    /**
     * Calculate equal split among all members.
     * The payer is included in the split (they pay, then get reimbursed by others).
     */
    private fun calculateEqualSplit(
        totalAmount: Double,
        members: List<GroupMember>
    ): Map<Long, Double> {
        if (members.isEmpty()) return emptyMap()

        val totalCents = toCents(totalAmount)
        val memberCount = members.size.coerceAtLeast(1).toLong()
        val baseCents = totalCents / memberCount
        val remainder = totalCents % memberCount

        return members.mapIndexed { index, member ->
            val memberCents = baseCents + if (index.toLong() < remainder) 1L else 0L
            member.id to fromCents(memberCents)
        }.toMap()
    }
    
    /**
     * Calculate split based on custom percentages.
     * Note: This requires split details to be stored with the expense.
     */
    private fun calculatePercentageSplit(
        expense: GroupExpense,
        members: List<GroupMember>
    ): Map<Long, Double> {
        return when (val parseResult = parseCustomSplit(expense, members)) {
            is CustomSplitParseResult.Valid -> {
                calculateAmountsFromPercentages(
                    totalAmount = expense.totalAmount,
                    percentages = parseResult.splits,
                    members = members
                )
            }

            is CustomSplitParseResult.Invalid -> {
                fallbackToEqualForInvalidLegacyData(
                    expense = expense,
                    members = members,
                    reason = parseResult.reason
                )
            }
        }
    }
    
    /**
     * Calculate split based on fixed amounts.
     * Note: This requires split details to be stored with the expense.
     */
    private fun calculateAmountSplit(
        expense: GroupExpense,
        members: List<GroupMember>
    ): Map<Long, Double> {
        return when (val parseResult = parseCustomSplit(expense, members)) {
            is CustomSplitParseResult.Valid -> members.associate { member ->
                member.id to (parseResult.splits[member.id] ?: 0.0)
            }

            is CustomSplitParseResult.Invalid -> {
                fallbackToEqualForInvalidLegacyData(
                    expense = expense,
                    members = members,
                    reason = parseResult.reason
                )
            }
        }
    }
    
    /**
     * Calculate unequal split (one person pays more/less).
     * Note: This requires split details to be stored with the expense.
     */
    private fun calculateUnequalSplit(
        expense: GroupExpense,
        members: List<GroupMember>
    ): Map<Long, Double> {
        return when (val parseResult = parseCustomSplit(expense, members)) {
            is CustomSplitParseResult.Valid -> members.associate { member ->
                member.id to (parseResult.splits[member.id] ?: 0.0)
            }

            is CustomSplitParseResult.Invalid -> {
                fallbackToEqualForInvalidLegacyData(
                    expense = expense,
                    members = members,
                    reason = parseResult.reason
                )
            }
        }
    }

    private fun parseCustomSplit(
        expense: GroupExpense,
        members: List<GroupMember>
    ): CustomSplitParseResult {
        return CustomSplitParser.parseAndValidate(
            splitsString = expense.customSplitsJson,
            splitType = expense.splitType.toCustomSplitMode(),
            totalAmount = expense.totalAmount,
            groupMemberIds = members.map { it.id }.toSet()
        )
    }

    private fun SplitType.toCustomSplitMode(): CustomSplitMode {
        return when (this) {
            SplitType.EQUAL -> CustomSplitMode.EQUAL
            SplitType.CUSTOM_AMOUNT -> CustomSplitMode.CUSTOM_AMOUNT
            SplitType.CUSTOM_PERCENT -> CustomSplitMode.CUSTOM_PERCENT
            SplitType.UNEQUAL -> CustomSplitMode.UNEQUAL
        }
    }

    private fun fallbackToEqualForInvalidLegacyData(
        expense: GroupExpense,
        members: List<GroupMember>,
        reason: String
    ): Map<Long, Double> {
        Timber.w(
            "Invalid legacy custom split data for expenseId=%s splitType=%s. Falling back to equal split. reason=%s",
            expense.id,
            expense.splitType,
            reason
        )
        return calculateEqualSplit(expense.totalAmount, members)
    }

    private fun calculateAmountsFromPercentages(
        totalAmount: Double,
        percentages: Map<Long, Double>,
        members: List<GroupMember>
    ): Map<Long, Double> {
        data class PercentageShare(
            val memberId: Long,
            val order: Int,
            val baseCents: Long,
            val fractionalPart: Double
        )

        if (members.isEmpty()) return emptyMap()

        val totalCents = toCents(totalAmount)
        val shares = members.mapIndexed { index, member ->
            val percent = percentages[member.id] ?: 0.0
            val rawCents = totalCents * (percent / 100.0)
            val base = floor(rawCents).toLong()
            PercentageShare(
                memberId = member.id,
                order = index,
                baseCents = base,
                fractionalPart = rawCents - base
            )
        }

        val centsByMember = shares.associate { it.memberId to it.baseCents }.toMutableMap()
        val baseTotal = shares.sumOf { it.baseCents }
        var remainder = totalCents - baseTotal

        if (remainder > 0) {
            val byLargestFraction = shares
                .sortedWith(compareByDescending<PercentageShare> { it.fractionalPart }.thenBy { it.order })
            var index = 0
            while (remainder > 0 && byLargestFraction.isNotEmpty()) {
                val target = byLargestFraction[index % byLargestFraction.size].memberId
                centsByMember[target] = (centsByMember[target] ?: 0L) + 1L
                remainder--
                index++
            }
        } else if (remainder < 0) {
            val bySmallestFraction = shares
                .sortedWith(compareBy<PercentageShare> { it.fractionalPart }.thenBy { it.order })
            var remainingToRemove = -remainder
            var index = 0
            while (remainingToRemove > 0 && bySmallestFraction.isNotEmpty()) {
                val target = bySmallestFraction[index % bySmallestFraction.size].memberId
                val current = centsByMember[target] ?: 0L
                if (current > 0) {
                    centsByMember[target] = current - 1
                    remainingToRemove--
                }
                index++
            }
        }

        return members.associate { member ->
            member.id to fromCents(centsByMember[member.id] ?: 0L)
        }
    }

    fun calculateMemberShare(
        expense: GroupExpense,
        members: List<GroupMember>,
        memberId: Long
    ): Double {
        return calculateSplitAmounts(expense, members)[memberId] ?: 0.0
    }

    private fun toCents(amount: Double): Long {
        return BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    }

    private fun fromCents(cents: Long): Double {
        return BigDecimal.valueOf(cents)
            .movePointLeft(2)
            .toDouble()
    }
    
    /**
     * Calculate net balance for each member across all expenses.
     * Positive balance = member is owed money (gets back)
     * Negative balance = member owes money
     * 
     * @param expenses List of all group expenses
     * @param members List of all group members
     * @return Map of memberId -> net balance
     */
    fun calculateBalances(
        expenses: List<GroupExpense>,
        members: List<GroupMember>
    ): Map<Long, Double> {
        // Initialize all balances to 0
        val balances = members.associate { it.id to 0.0 }.toMutableMap()
        
        expenses.forEach { expense ->
            val splitAmounts = calculateSplitAmounts(expense, members)
            val paidById = expense.paidById
            
            // For each member, update their balance:
            // - If they paid: they get credit for the full amount
            // - Everyone owes their split portion
            splitAmounts.forEach { (memberId, owedAmount) ->
                val currentBalance = balances[memberId] ?: 0.0
                
                if (memberId == paidById) {
                    // Payer gets credit for what others owe them
                    // Their balance increases by: total - their own share
                    val credit = expense.totalAmount - owedAmount
                    balances[memberId] = currentBalance + credit
                } else {
                    // Non-payers owe their share
                    balances[memberId] = currentBalance - owedAmount
                }
            }
        }
        
        return balances
    }
    
    /**
     * Simplify balances by minimizing the number of transactions needed.
     * Returns a list of suggested payments to settle all debts.
     *
     * ## SHR-15: This method is a legacy greedy implementation.
     * All new settlement computation should use [SettlementCalculator.calculateSettlements]
     * which uses an optimal DFS/backtracking solver with greedy fallback.
     *
     * @param balances Map of memberId -> current balance
     * @return List of (fromMemberId, toMemberId, amount) transactions
     */
    @Deprecated("Use SettlementCalculator.calculateSettlements() for optimal settlement plans",
        ReplaceWith(
            "SettlementCalculator().calculateSettlements(balances.map { MemberBalance(it.key, \"\", it.value, \"EUR\") }.associateBy { it.memberId })" +
            ".map { Triple(it.fromMemberId, it.toMemberId, it.amount) }",
            "com.yourname.expensetracker.domain.groups.SettlementCalculator",
            "com.yourname.expensetracker.domain.groups.MemberBalance"
        )
    )
    fun simplifyBalances(
        balances: Map<Long, Double>
    ): List<Triple<Long, Long, Double>> {
        val transactions = mutableListOf<Triple<Long, Long, Double>>()
        
        // Separate debtors (owe money) and creditors (owed money)
        val debtors = balances.filter { it.value < -0.01 }
            .map { it.key to -it.value } // Convert to positive amounts they need to pay
            .sortedByDescending { it.second } // Sort by amount descending
            .toMutableList()
        
        val creditors = balances.filter { it.value > 0.01 }
            .map { it.key to it.value }
            .sortedByDescending { it.second }
            .toMutableList()
        
        // Match debtors with creditors (legacy greedy algorithm)
        while (debtors.isNotEmpty() && creditors.isNotEmpty()) {
            val (debtorId, debtorAmount) = debtors.first()
            val (creditorId, creditorAmount) = creditors.first()
            
            val paymentAmount = minOf(debtorAmount, creditorAmount)
            
            if (paymentAmount > 0.01) {
                transactions.add(Triple(debtorId, creditorId, paymentAmount))
            }
            
            // Update remaining amounts
            val remainingDebtor = debtorAmount - paymentAmount
            val remainingCreditor = creditorAmount - paymentAmount
            
            // Remove settled accounts
            if (remainingDebtor < 0.01) {
                debtors.removeAt(0)
            } else {
                debtors[0] = debtorId to remainingDebtor
            }
            
            if (remainingCreditor < 0.01) {
                creditors.removeAt(0)
            } else {
                creditors[0] = creditorId to remainingCreditor
            }
        }
        
        return transactions
    }
    
    /**
     * Validate that splits sum correctly to total amount.
     * 
     * @param splits Map of memberId -> split amount
     * @param totalAmount Expected total
     * @return True if splits sum to total (within rounding tolerance)
     */
    fun validateSplits(splits: Map<Long, Double>, totalAmount: Double): Boolean {
        val sum = splits.values.sum()
        val tolerance = 0.01 // Allow 1 cent rounding difference
        return kotlin.math.abs(sum - totalAmount) <= tolerance
    }
    
    /**
     * Format balance for display.
     * Positive = "gets back $X"
     * Negative = "owes $X"
     * Zero = "settled up"
     */
    fun formatBalance(balance: Double): String {
        return when {
            balance > 0.01 -> "gets back $${String.format("%.2f", balance)}"
            balance < -0.01 -> "owes $${String.format("%.2f", -balance)}"
            else -> "settled up"
        }
    }
}
