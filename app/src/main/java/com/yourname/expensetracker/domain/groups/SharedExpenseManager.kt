package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.domain.logic.CustomSplitMode
import com.yourname.expensetracker.domain.logic.CustomSplitParseResult
import com.yourname.expensetracker.domain.logic.CustomSplitParser
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

/**
 * Domain service for shared-expense operations.
 *
 * Depends only on [SharedExpenseDataPort] abstractions and domain models.
 * Data-layer details (Room DAOs/entities/transactions) are delegated to adapter implementations.
 * 
 * CRITICAL-2: Multi-table operations are executed atomically in the data layer.
 */
@Singleton
class SharedExpenseManager @Inject constructor(
    private val sharedExpenseDataPort: SharedExpenseDataPort,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    /**
     * Create a new expense group with initial members.
     * 
     * CRITICAL: Now uses atomic transaction - if member insert fails, 
     * group insert is rolled back automatically.
     */
    suspend fun createGroup(
        name: String,
        description: String? = null,
        memberNames: List<String>,
        defaultCurrency: String = "EUR",
        currentUserName: String = "Me"
    ): Long = withContext(ioDispatcher) {
        // Create domain group model
        val group = SharedExpenseGroup(
            name = name,
            description = description,
            defaultCurrency = defaultCurrency
        )

        // Create domain members (groupId will be set by data-layer transaction coordinator)
        val members = memberNames.map { name ->
            SharedExpenseMember(
                groupId = 0, // Will be replaced by actual groupId in transaction
                name = name,
                isCurrentUser = (name == currentUserName)
            )
        }

        // ATOMIC TRANSACTION: Both group and members succeed or both fail
        sharedExpenseDataPort.createGroupWithMembers(group, members)
    }
    
    /**
     * Add a member to an existing group.
     */
    suspend fun addMember(groupId: Long, name: String, email: String? = null): Long =
        withContext(ioDispatcher) {
            val member = SharedExpenseMember(
                groupId = groupId,
                name = name,
                email = email,
                isCurrentUser = false
            )
            sharedExpenseDataPort.addMember(member)
        }
    
    /**
     * Remove a member from a group.
     */
    suspend fun removeMember(member: SharedExpenseMember): RemoveSharedExpenseMemberResult =
        withContext(ioDispatcher) {
            try {
                val members = sharedExpenseDataPort.getGroupMembersOnce(member.groupId)
                if (members.none { it.id == member.id }) {
                    return@withContext RemoveSharedExpenseMemberResult.Error("Member not found")
                }

                val expenses = sharedExpenseDataPort.getGroupExpensesOnce(member.groupId)

                val paidExpenseCount = expenses.count { it.paidById == member.id }
                if (paidExpenseCount > 0) {
                    return@withContext RemoveSharedExpenseMemberResult.CannotDeleteMemberWithExpenses(paidExpenseCount)
                }

                val splitReferenceCount = countSplitReferences(
                    expenses = expenses,
                    groupMemberIds = members.map { it.id }.toSet(),
                    memberId = member.id
                )
                if (splitReferenceCount > 0) {
                    return@withContext RemoveSharedExpenseMemberResult.CannotDeleteMemberReferencedInSplits(splitReferenceCount)
                }

                sharedExpenseDataPort.removeMember(member)
                RemoveSharedExpenseMemberResult.Success
            } catch (e: Exception) {
                RemoveSharedExpenseMemberResult.Error(e.message ?: "Failed to delete member")
            }
        }
    
    /**
     * Add an expense to a group.
     */
    suspend fun addExpense(
        groupId: Long,
        expenseId: Long,
        paidById: Long,
        description: String,
        totalAmount: Double,
        currency: String = "EUR",
        splitType: GroupSplitType = GroupSplitType.EQUAL,
        customSplits: Map<Long, Double>? = null
    ): Long = withContext(ioDispatcher) {
        if (splitType != GroupSplitType.EQUAL) {
            customSplits?.forEach { (memberId, splitValue) ->
                if (!splitValue.isFinite()) {
                    throw IllegalArgumentException(
                        "Invalid custom splits: split values must be finite (memberId=$memberId)"
                    )
                }
            }
        }

        val rawCustomSplitsSerialized = customSplits?.let { map ->
            // Format: "memberId:amount,memberId:amount"
            map.entries.joinToString(",") { "${it.key}:${it.value}" }
        }

        val customSplitsSerialized = if (splitType == GroupSplitType.EQUAL) {
            null
        } else {
            val groupMemberIds = sharedExpenseDataPort.getGroupMembersOnce(groupId).map { it.id }.toSet()
            when (val validation = parseCustomSplitsForValidation(
                splitsString = rawCustomSplitsSerialized,
                splitType = splitType,
                totalAmount = totalAmount,
                groupMemberIds = groupMemberIds
            )) {
                is CustomSplitParseResult.Valid -> rawCustomSplitsSerialized
                is CustomSplitParseResult.Invalid -> {
                    throw IllegalArgumentException("Invalid custom splits: ${validation.reason}")
                }
            }
        }

        val resolvedCurrency = sharedExpenseDataPort.getGroupOnce(groupId)?.defaultCurrency ?: currency

        val groupExpense = SharedGroupExpense(
            groupId = groupId,
            expenseId = expenseId,
            paidById = paidById,
            date = System.currentTimeMillis(),
            description = description,
            totalAmount = totalAmount,
            currency = resolvedCurrency,
            splitType = splitType,
            customSplitsSerialized = customSplitsSerialized
        )

        sharedExpenseDataPort.addExpense(groupExpense)
    }
    
    /**
     * Get all groups.
     */
    fun getAllGroups(): Flow<List<SharedExpenseGroup>> = sharedExpenseDataPort.getAllGroups()
    
    /**
     * Get active groups only.
     */
    fun getActiveGroups(): Flow<List<SharedExpenseGroup>> = sharedExpenseDataPort.getActiveGroups()
    
    /**
     * Get a specific group.
     */
    fun getGroup(groupId: Long): Flow<SharedExpenseGroup?> = sharedExpenseDataPort.getGroup(groupId)
    
    /**
     * Get members of a group.
     */
    fun getGroupMembers(groupId: Long): Flow<List<SharedExpenseMember>> =
        sharedExpenseDataPort.getGroupMembers(groupId)
    
    /**
     * Get expenses for a group.
     */
    fun getGroupExpenses(groupId: Long): Flow<List<SharedGroupExpense>> =
        sharedExpenseDataPort.getGroupExpenses(groupId)
    
    /**
     * Calculate how much each member has paid and should pay.
     */
    suspend fun calculateBalances(groupId: Long): Map<Long, MemberBalance> = 
        withContext(ioDispatcher) {
            val members = sharedExpenseDataPort.getGroupMembersOnce(groupId)
            val expenses = sharedExpenseDataPort.getGroupExpensesOnce(groupId)

            val paidCentsByMember = members
                .associate { it.id to 0L }
                .toMutableMap()
            val shouldPayCentsByMember = members
                .associate { it.id to 0L }
                .toMutableMap()

            // Calculate paid amounts
            for (expense in expenses) {
                if (paidCentsByMember.containsKey(expense.paidById)) {
                    paidCentsByMember[expense.paidById] =
                        (paidCentsByMember[expense.paidById] ?: 0L) + toCents(expense.totalAmount)
                }
            }

            // Calculate how much each member should pay
            for (expense in expenses) {
                val splits = calculateSplits(expense, members)
                for ((memberId, amount) in splits) {
                    if (shouldPayCentsByMember.containsKey(memberId)) {
                        shouldPayCentsByMember[memberId] =
                            (shouldPayCentsByMember[memberId] ?: 0L) + toCents(amount)
                    }
                }
            }

            // Calculate net balances (positive = owed money, negative = owes money)
            val result = mutableMapOf<Long, MemberBalance>()
            for (member in members) {
                val paidCents = paidCentsByMember[member.id] ?: 0L
                val shouldPayCents = shouldPayCentsByMember[member.id] ?: 0L
                val netCents = paidCents - shouldPayCents

                result[member.id] = MemberBalance(
                    memberId = member.id,
                    memberName = member.name,
                    paid = fromCents(paidCents),
                    shouldPay = fromCents(shouldPayCents),
                    netBalance = fromCents(netCents)
                )
            }

            result
        }
    
    /**
     * Calculate splits for an expense based on its split type.
     */
    private fun calculateSplits(expense: SharedGroupExpense, members: List<SharedExpenseMember>): Map<Long, Double> {
        if (members.isEmpty()) return emptyMap()

        return when (expense.splitType) {
            GroupSplitType.EQUAL -> {
                calculateEqualSplit(expense.totalAmount, members)
            }
            GroupSplitType.CUSTOM_AMOUNT -> {
                when (val parseResult = parseCustomSplits(expense, members)) {
                    is CustomSplitParseResult.Valid -> members.associate { member ->
                        member.id to (parseResult.splits[member.id] ?: 0.0)
                    }

                    is CustomSplitParseResult.Invalid -> {
                        Timber.w(
                            "Invalid custom amount splits for expenseId=%s. Falling back to equal split. reason=%s",
                            expense.id,
                            parseResult.reason
                        )
                        calculateEqualSplit(expense.totalAmount, members)
                    }
                }
            }
            GroupSplitType.CUSTOM_PERCENT -> {
                when (val parseResult = parseCustomSplits(expense, members)) {
                    is CustomSplitParseResult.Valid -> percentageToAmountSplit(
                        totalAmount = expense.totalAmount,
                        percentages = parseResult.splits,
                        members = members
                    )

                    is CustomSplitParseResult.Invalid -> {
                        Timber.w(
                            "Invalid custom percent splits for expenseId=%s. Falling back to equal split. reason=%s",
                            expense.id,
                            parseResult.reason
                        )
                        calculateEqualSplit(expense.totalAmount, members)
                    }
                }
            }
            GroupSplitType.UNEQUAL -> {
                when (val parseResult = parseCustomSplits(expense, members)) {
                    is CustomSplitParseResult.Valid -> members.associate { member ->
                        member.id to (parseResult.splits[member.id] ?: 0.0)
                    }

                    is CustomSplitParseResult.Invalid -> {
                        Timber.w(
                            "Invalid unequal splits for expenseId=%s. Falling back to equal split. reason=%s",
                            expense.id,
                            parseResult.reason
                        )
                        calculateEqualSplit(expense.totalAmount, members)
                    }
                }
            }
        }
    }
    
    /**
     * Parse and validate custom split payloads.
     */
    private fun parseCustomSplits(
        expense: SharedGroupExpense,
        members: List<SharedExpenseMember>
    ): CustomSplitParseResult {
        return CustomSplitParser.parseAndValidate(
            splitsString = expense.customSplitsSerialized,
            splitType = expense.splitType.toCustomSplitMode(),
            totalAmount = expense.totalAmount,
            groupMemberIds = members.map { it.id }.toSet()
        )
    }

    private fun calculateEqualSplit(
        totalAmount: Double,
        members: List<SharedExpenseMember>
    ): Map<Long, Double> {
        if (members.isEmpty()) return emptyMap()

        val totalCents = toCents(totalAmount)
        val memberCount = members.size.toLong()
        val baseCents = totalCents / memberCount
        val remainder = totalCents % memberCount

        return members.mapIndexed { index, member ->
            val cents = baseCents + if (index.toLong() < remainder) 1L else 0L
            member.id to fromCents(cents)
        }.toMap()
    }

    private fun percentageToAmountSplit(
        totalAmount: Double,
        percentages: Map<Long, Double>,
        members: List<SharedExpenseMember>
    ): Map<Long, Double> {
        data class PercentageShare(
            val memberId: Long,
            val order: Int,
            val baseCents: Long,
            val fractionalPart: Double
        )

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
            val sortedByFraction = shares
                .sortedWith(compareByDescending<PercentageShare> { it.fractionalPart }.thenBy { it.order })
            var index = 0
            while (remainder > 0 && sortedByFraction.isNotEmpty()) {
                val memberId = sortedByFraction[index % sortedByFraction.size].memberId
                centsByMember[memberId] = (centsByMember[memberId] ?: 0L) + 1L
                remainder--
                index++
            }
        } else if (remainder < 0) {
            val sortedBySmallestFraction = shares
                .sortedWith(compareBy<PercentageShare> { it.fractionalPart }.thenBy { it.order })
            var toRemove = -remainder
            var index = 0
            while (toRemove > 0 && sortedBySmallestFraction.isNotEmpty()) {
                val memberId = sortedBySmallestFraction[index % sortedBySmallestFraction.size].memberId
                val current = centsByMember[memberId] ?: 0L
                if (current > 0) {
                    centsByMember[memberId] = current - 1
                    toRemove--
                }
                index++
            }
        }

        return members.associate { member ->
            member.id to fromCents(centsByMember[member.id] ?: 0L)
        }
    }

    private fun toCents(amount: Double): Long {
        return BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .toLong()
    }

    private fun fromCents(cents: Long): Double {
        return BigDecimal.valueOf(cents)
            .movePointLeft(2)
            .toDouble()
    }

    private fun GroupSplitType.toCustomSplitMode(): CustomSplitMode {
        return when (this) {
            GroupSplitType.EQUAL -> CustomSplitMode.EQUAL
            GroupSplitType.CUSTOM_AMOUNT -> CustomSplitMode.CUSTOM_AMOUNT
            GroupSplitType.CUSTOM_PERCENT -> CustomSplitMode.CUSTOM_PERCENT
            GroupSplitType.UNEQUAL -> CustomSplitMode.UNEQUAL
        }
    }

    private fun countSplitReferences(
        expenses: List<SharedGroupExpense>,
        groupMemberIds: Set<Long>,
        memberId: Long
    ): Int {
        if (groupMemberIds.isEmpty()) return 0

        return expenses
            .filter { expense ->
                if (expense.customSplitsSerialized.isNullOrBlank()) {
                    false
                } else {
                    val parseResult = when (expense.splitType) {
                        GroupSplitType.CUSTOM_AMOUNT,
                        GroupSplitType.CUSTOM_PERCENT,
                        GroupSplitType.UNEQUAL -> CustomSplitParser.parseAndValidate(
                            splitsString = expense.customSplitsSerialized,
                            splitType = expense.splitType.toCustomSplitMode(),
                            totalAmount = expense.totalAmount,
                            groupMemberIds = groupMemberIds
                        )

                        GroupSplitType.EQUAL -> CustomSplitParseResult.Invalid("No custom split for equal mode")
                    }

                    CustomSplitParser.referencesMember(
                        splitsString = expense.customSplitsSerialized,
                        memberId = memberId,
                        parseResult = parseResult
                    )
                }
            }
            .size
    }

    fun parseCustomSplitsForValidation(
        splitsString: String?,
        splitType: GroupSplitType,
        totalAmount: Double,
        groupMemberIds: Set<Long>
    ): CustomSplitParseResult {
        return CustomSplitParser.parseAndValidate(
            splitsString = splitsString,
            splitType = splitType.toCustomSplitMode(),
            totalAmount = totalAmount,
            groupMemberIds = groupMemberIds
        )
    }
    
    /**
     * Archive a group (soft delete).
     */
    suspend fun archiveGroup(groupId: Long) = withContext(ioDispatcher) {
        sharedExpenseDataPort.archiveGroup(groupId)
    }
    
    /**
     * Restore an archived group.
     */
    suspend fun restoreGroup(groupId: Long) = withContext(ioDispatcher) {
        sharedExpenseDataPort.restoreGroup(groupId)
    }
    
    /**
     * Delete a group permanently.
     */
    suspend fun deleteGroup(group: SharedExpenseGroup) = withContext(ioDispatcher) {
        sharedExpenseDataPort.deleteGroup(group)
    }
}

/**
 * Balance information for a group member.
 */
data class MemberBalance(
    val memberId: Long,
    val memberName: String,
    val paid: Double,
    val shouldPay: Double,
    val netBalance: Double // Positive = owed money, negative = owes money
)
