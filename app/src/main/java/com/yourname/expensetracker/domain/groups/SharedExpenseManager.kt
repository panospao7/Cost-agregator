package com.yourname.expensetracker.domain.groups

import android.util.Log
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.logic.CustomSplitJsonCodec
import com.yourname.expensetracker.domain.logic.CustomSplitMode
import com.yourname.expensetracker.domain.logic.CustomSplitParseResult
import com.yourname.expensetracker.domain.logic.CustomSplitParser
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
    private val timeProvider: TimeProvider,
    private val currencySettingsRepository: CurrencySettingsRepository,
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
        defaultCurrency: String, // Callers MUST supply home or group currency
        currentUserName: String = "Me"
    ): Long = withContext(ioDispatcher) {
        // Create domain group model — capture creation timestamp at the boundary
        val group = SharedExpenseGroup(
            name = name,
            description = description,
            defaultCurrency = defaultCurrency,
            createdAt = timeProvider.now()
        )

        // Create domain members (groupId will be set by data-layer transaction coordinator)
        val membersJoinedAt = timeProvider.now()
        val members = memberNames.map { name ->
            SharedExpenseMember(
                groupId = 0, // Will be replaced by actual groupId in transaction
                name = name,
                isCurrentUser = name.equals(currentUserName, ignoreCase = true),
                joinedAt = membersJoinedAt
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
                isCurrentUser = false,
                joinedAt = timeProvider.now()
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
                    member = member
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
        currency: String, // Callers MUST supply the group's currency
        splitType: GroupSplitType = GroupSplitType.EQUAL,
        customSplits: Map<Long, Double>? = null
    ): Long = withContext(ioDispatcher) {
        if (description.isBlank()) {
            throw IllegalArgumentException("Description cannot be blank")
        }
        if (!totalAmount.isFinite() || totalAmount <= 0.0) {
            throw IllegalArgumentException("Amount must be a positive finite number")
        }

        val groupMemberIds = sharedExpenseDataPort.getGroupMembersOnce(groupId).map { it.id }.toSet()
        if (paidById !in groupMemberIds) {
            throw IllegalArgumentException("Payer is not a member of this group")
        }

        if (splitType != GroupSplitType.EQUAL) {
            customSplits?.forEach { (memberId, splitValue) ->
                if (!splitValue.isFinite()) {
                    throw IllegalArgumentException(
                        "Invalid custom splits: split values must be finite (memberId=$memberId)"
                    )
                }
            }
        }

        val customSplitsSerialized = if (splitType == GroupSplitType.EQUAL) {
            null
        } else {
            val canonicalCustomSplitsJson = customSplits?.let { splits ->
                CustomSplitJsonCodec.toCanonicalJson(splits)
            }
            when (val validation = parseCustomSplitsForValidation(
                splitsString = canonicalCustomSplitsJson,
                splitType = splitType,
                totalAmount = totalAmount,
                groupMemberIds = groupMemberIds
            )) {
                is CustomSplitParseResult.Valid -> canonicalCustomSplitsJson
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
            date = timeProvider.now(),
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
            val group = sharedExpenseDataPort.getGroupOnce(groupId)
            if (group == null) android.util.Log.w("SharedExpenseManager", "Group $groupId not found in calculateBalances, defaulting to home currency")
            val groupCurrency = group?.defaultCurrency ?: getHomeCurrencySync()
            val members = sharedExpenseDataPort.getGroupMembersOnce(groupId)
            val expenses = sharedExpenseDataPort.getGroupExpensesOnce(groupId)
            val splitMembers = members.map { it.toGroupMember() }
            val splitExpenses = expenses.map { it.toGroupExpense() }
            val netBalances = SplitCalculator.calculateBalances(splitExpenses, splitMembers)

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
            for (expense in splitExpenses) {
                val splits = SplitCalculator.calculateSplitAmounts(expense, splitMembers)
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
                val netCents = toCents(netBalances[member.id] ?: 0.0)

                result[member.id] = MemberBalance(
                    memberId = member.id,
                    memberName = member.name,
                    paid = fromCents(paidCents),
                    shouldPay = fromCents(shouldPayCents),
                    netBalance = fromCents(netCents),
                    currency = groupCurrency
                )
            }

            result
        }

    /**
     * Synchronously retrieve the user's home currency.
     * Used as a fallback when no group context is available.
     */
    private fun getHomeCurrencySync(): String = runBlocking {
        try {
            currencySettingsRepository.homeCurrency().first()
        } catch (_: Exception) {
            "EUR"
        }
    }

    private fun toCents(amount: Double): Long {
        return java.math.BigDecimal.valueOf(amount)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .movePointRight(2)
            .toLong()
    }

    private fun fromCents(cents: Long): Double {
        return java.math.BigDecimal.valueOf(cents)
            .movePointLeft(2)
            .toDouble()
    }

    private fun SharedExpenseMember.toGroupMember(): GroupMember {
        return GroupMember(
            id = id,
            groupId = groupId,
            name = name,
            email = email,
            isCurrentUser = isCurrentUser,
            joinedAt = joinedAt
        )
    }

    private fun SharedGroupExpense.toGroupExpense(): GroupExpense {
        return GroupExpense(
            id = id,
            groupId = groupId,
            expenseId = expenseId,
            paidById = paidById,
            date = date,
            description = description,
            totalAmount = totalAmount,
            currency = currency,
            splitType = splitType.toSplitType(),
            customSplitsJson = customSplitsSerialized,
            isReimbursable = isReimbursable,
            reimbursedAmount = reimbursedAmount,
            settledAt = settledAt,
            myShareAmount = myShareAmount
        )
    }

    private fun GroupSplitType.toSplitType(): SplitType {
        return when (this) {
            GroupSplitType.EQUAL -> SplitType.EQUAL
            GroupSplitType.CUSTOM_AMOUNT -> SplitType.CUSTOM_AMOUNT
            GroupSplitType.CUSTOM_PERCENT -> SplitType.CUSTOM_PERCENT
            GroupSplitType.UNEQUAL -> SplitType.UNEQUAL
        }
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
        member: SharedExpenseMember
    ): Int {
        if (groupMemberIds.isEmpty()) return 0

        return expenses
            .filter { expense ->
                when (expense.splitType) {
                    GroupSplitType.EQUAL -> expense.date >= member.joinedAt
                    GroupSplitType.CUSTOM_AMOUNT,
                    GroupSplitType.CUSTOM_PERCENT,
                    GroupSplitType.UNEQUAL -> {
                        if (expense.customSplitsSerialized.isNullOrBlank()) {
                            false
                        } else {
                            val parseResult = CustomSplitParser.parseAndValidate(
                                splitsString = expense.customSplitsSerialized,
                                splitType = expense.splitType.toCustomSplitMode(),
                                totalAmount = expense.totalAmount,
                                groupMemberIds = groupMemberIds
                            )

                            CustomSplitParser.referencesMember(
                                splitsString = expense.customSplitsSerialized,
                                memberId = member.id,
                                parseResult = parseResult
                            )
                        }
                    }
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
    val netBalance: Double, // Positive = owed money, negative = owes money
    val currency: String // MUST be explicitly provided by callers
)
