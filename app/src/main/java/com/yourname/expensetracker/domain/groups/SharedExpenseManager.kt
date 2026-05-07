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
 *
 * ## SHR-10: Subset splits (planned)
 * Currently, custom splits (CUSTOM_AMOUNT / CUSTOM_PERCENT / UNEQUAL) require ALL
 * group members to be assigned a split value via [customSplits]. If a member is
 * omitted, validation fails.
 *
 * ### Planned change — allow subset splits
 * The goal is to allow splitting an expense among a SUBSET of members, with the
 * remaining members implicitly receiving 0 (they are not part of this expense).
 * For example, if a group has 4 members but only 2 shared a meal, the split
 * should only involve those 2.
 *
 * ### Required validation changes
 * 1. **`addExpense()` (line 127)** — Remove the implicit requirement that all
 *    members must be in [customSplits]. Instead:
 *    - For EQUAL splits: keep dividing by ALL members (equal split inherently
 *      involves everyone).
 *    - For CUSTOM_AMOUNT / CUSTOM_PERCENT: only validate members present in
 *      [customSplits]. The sum of assigned amounts must equal [totalAmount].
 *    - Members NOT in [customSplits] get a share of 0.
 * 2. **`CustomSplitParser.parseAndValidate()`** — Update to accept a subset of
 *    member IDs. Validation should check:
 *    - All keys in the parsed splits exist in the subset.
 *    - Sum of amounts equals [totalAmount] (within a small epsilon for rounding).
 *    - No duplicate member IDs.
 * 3. **`computeMyShareAmount()` (line 312)** — Update to handle missing current
 *    user (if current user is not in the subset, their share is 0).
 * 4. **`calculateBalances()` (line 247)** — Ensure balance calculation handles
 *    members with 0 assigned amount correctly (they owe nothing and are owed nothing).
 * 5. **UI** — Expose a member selector per expense so the user can choose which
 *    members to include in the split.
 *
 * ### Backward compatibility
 * Existing expense records with all-member splits remain valid. The change is
 * additive — the new subset behavior replaces the old "all members required"
 * validation. Old serialized customSplitsJson (with all member entries) continues
 * to parse correctly.
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

        val groupMembers = sharedExpenseDataPort.getGroupMembersOnce(groupId)
        val groupMemberIds = groupMembers.map { it.id }.toSet()
        if (paidById !in groupMemberIds) {
            throw IllegalArgumentException("Payer is not a member of this group")
        }

        // SHR-12: Identify the current user from the member list to compute their share correctly.
        val currentUserId = groupMembers.firstOrNull { it.isCurrentUser }?.id

        // SHR-10: Subset splits not yet supported
        // Currently custom splits require ALL members to be assigned (sum must equal total).
        // Future: allow splitting among a subset — unassigned members get 0 share.
        // Required: validate only assigned members' sum equals total.
        // SHR-17: Validate that non-EQUAL split types have customSplits provided.
        if (splitType != GroupSplitType.EQUAL) {
            require(customSplits != null) {
                "customSplits must be provided for non-EQUAL split types (was $splitType)"
            }
            customSplits.forEach { (memberId, splitValue) ->
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

        // SHR-12: Recompute myShareAmount based on the split configuration.
        // This ensures the value stays in sync with the group split even when
        // the split type or custom splits change. The recompute happens at
        // add-expense time so the initially stored value is always correct.
        val myShareAmount = computeMyShareAmount(
            totalAmount = totalAmount,
            splitType = splitType,
            customSplits = customSplits,
            paidById = paidById,
            groupMemberIds = groupMemberIds,
            currentUserId = currentUserId
        )

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
            customSplitsSerialized = customSplitsSerialized,
            myShareAmount = myShareAmount
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
            val groupCurrency = group?.defaultCurrency ?: try {
                currencySettingsRepository.homeCurrency().first()
            } catch (_: Exception) {
                "EUR"
            }
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
     * Compute the current user's share amount based on total, split type, and custom splits.
     * For EQUAL splits, divides total among all members.
     * For non-EQUAL splits, looks up the current user's entry in custom splits.
     * Returns null if the share cannot be determined (e.g., current user not in splits).
     *
     * SHR-12: Centralized recompute trigger so myShareAmount never drifts from the split.
     */
    private fun computeMyShareAmount(
        totalAmount: Double,
        splitType: GroupSplitType,
        customSplits: Map<Long, Double>?,
        paidById: Long,
        groupMemberIds: Set<Long>,
        currentUserId: Long? = null
    ): Double? {
        if (splitType == GroupSplitType.EQUAL && groupMemberIds.isNotEmpty()) {
            return totalAmount / groupMemberIds.size
        }
        if (splitType != GroupSplitType.EQUAL && customSplits != null) {
            // SHR-12: Use the current user's split value instead of an arbitrary member's.
            return if (currentUserId != null && customSplits.containsKey(currentUserId)) {
                customSplits[currentUserId]
            } else {
                // Fallback: if current user is not in splits or unknown, return null
                null
            }
        }
        return null
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
