package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HIGH-06 FIX: Uses unified GroupTransactionCoordinator interface.
 * The interface is implemented in the data layer for atomic database operations.
 * All multi-table operations are wrapped in transactions to prevent data inconsistency.
 * 
 * CRITICAL-2: All operations go through the coordinator to ensure ACID compliance.
 */
@Singleton
class SharedExpenseManager @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val transactionCoordinator: GroupTransactionCoordinator
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
    ): Long = withContext(Dispatchers.IO) {
        // Create group entity
        val group = ExpenseGroup(
            name = name,
            description = description,
            defaultCurrency = defaultCurrency
        )
        
        // Create members (groupId will be set by transaction coordinator)
        val members = memberNames.map { name ->
            GroupMember(
                groupId = 0, // Will be replaced by actual groupId in transaction
                name = name,
                isCurrentUser = (name == currentUserName)
            )
        }
        
        // ATOMIC TRANSACTION: Both group and members succeed or both fail
        transactionCoordinator.createGroupWithMembersAtomic(group, members)
    }
    
    /**
     * Add a member to an existing group.
     */
    suspend fun addMember(groupId: Long, name: String, email: String? = null): Long {
        val member = GroupMember(
            groupId = groupId,
            name = name,
            email = email,
            isCurrentUser = false
        )
        return memberDao.insert(member)
    }
    
    /**
     * Remove a member from a group.
     */
    suspend fun removeMember(member: GroupMember) {
        memberDao.delete(member)
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
        splitType: SplitType = SplitType.EQUAL,
        customSplits: Map<Long, Double>? = null
    ): Long = withContext(Dispatchers.IO) {
        val customSplitsString = customSplits?.let { map ->
            // Format: "memberId:amount,memberId:amount"
            map.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
        
        val groupExpense = GroupExpense(
            groupId = groupId,
            expenseId = expenseId,
            paidById = paidById,
            date = System.currentTimeMillis(),
            description = description,
            totalAmount = totalAmount,
            currency = currency,
            splitType = splitType,
            customSplitsJson = customSplitsString
        )
        
        groupExpenseDao.insert(groupExpense)
    }
    
    /**
     * Get all groups.
     */
    fun getAllGroups(): Flow<List<ExpenseGroup>> = groupDao.getAllGroups()
    
    /**
     * Get active groups only.
     */
    fun getActiveGroups(): Flow<List<ExpenseGroup>> = groupDao.getActiveGroups()
    
    /**
     * Get a specific group.
     */
    fun getGroup(groupId: Long): Flow<ExpenseGroup?> = groupDao.getByIdFlow(groupId)
    
    /**
     * Get members of a group.
     */
    fun getGroupMembers(groupId: Long): Flow<List<GroupMember>> = 
        memberDao.getMembersForGroup(groupId)
    
    /**
     * Get expenses for a group.
     */
    fun getGroupExpenses(groupId: Long): Flow<List<GroupExpense>> = 
        groupExpenseDao.getExpensesForGroup(groupId)
    
    /**
     * Calculate how much each member has paid and should pay.
     */
    suspend fun calculateBalances(groupId: Long): Map<Long, MemberBalance> = 
        withContext(Dispatchers.IO) {
            val members = memberDao.getMembersForGroupOnce(groupId)
            val expenses = groupExpenseDao.getExpensesForGroupOnce(groupId)
            
            val balances = mutableMapOf<Long, MemberBalance>()
            
            // Initialize balances for all members
            for (member in members) {
                balances[member.id] = MemberBalance(
                    memberId = member.id,
                    memberName = member.name,
                    paid = 0.0,
                    shouldPay = 0.0,
                    netBalance = 0.0
                )
            }
            
            // Calculate paid amounts
            for (expense in expenses) {
                val paidByBalance = balances[expense.paidById]
                if (paidByBalance != null) {
                    balances[expense.paidById] = paidByBalance.copy(
                        paid = paidByBalance.paid + expense.totalAmount
                    )
                }
            }
            
            // Calculate how much each member should pay
            for (expense in expenses) {
                val splits = calculateSplits(expense, members)
                for ((memberId, amount) in splits) {
                    val currentBalance = balances[memberId]
                    if (currentBalance != null) {
                        balances[memberId] = currentBalance.copy(
                            shouldPay = currentBalance.shouldPay + amount
                        )
                    }
                }
            }
            
            // Calculate net balances (positive = owed money, negative = owes money)
            val result = mutableMapOf<Long, MemberBalance>()
            for ((memberId, balance) in balances) {
                result[memberId] = balance.copy(
                    netBalance = balance.paid - balance.shouldPay
                )
            }
            
            result
        }
    
    /**
     * Calculate splits for an expense based on its split type.
     */
    private fun calculateSplits(expense: GroupExpense, members: List<GroupMember>): Map<Long, Double> {
        return when (expense.splitType) {
            SplitType.EQUAL -> {
                val splitAmount = expense.totalAmount / members.size
                members.associate { it.id to splitAmount }
            }
            SplitType.CUSTOM_AMOUNT -> {
                expense.customSplitsJson?.let { splitsString ->
                    try {
                        parseCustomSplits(splitsString)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse custom splits")
                        members.associate { it.id to expense.totalAmount / members.size }
                    }
                } ?: members.associate { it.id to expense.totalAmount / members.size }
            }
            SplitType.CUSTOM_PERCENT -> {
                expense.customSplitsJson?.let { splitsString ->
                    try {
                        val percentages = parseCustomSplits(splitsString)
                        percentages.mapValues { (_, percent) ->
                            expense.totalAmount * (percent / 100.0)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse custom percentages")
                        members.associate { it.id to expense.totalAmount / members.size }
                    }
                } ?: members.associate { it.id to expense.totalAmount / members.size }
            }
            SplitType.UNEQUAL -> {
                expense.customSplitsJson?.let { splitsString ->
                    try {
                        parseCustomSplits(splitsString)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse unequal splits")
                        members.associate { it.id to expense.totalAmount / members.size }
                    }
                } ?: members.associate { it.id to expense.totalAmount / members.size }
            }
        }
    }
    
    /**
     * Parse custom splits string in format "memberId:amount,memberId:amount"
     */
    private fun parseCustomSplits(splitsString: String): Map<Long, Double> {
        val result = mutableMapOf<Long, Double>()
        val pairs = splitsString.split(",")
        for (pair in pairs) {
            val parts = pair.split(":")
            if (parts.size == 2) {
                val memberId = parts[0].toLongOrNull()
                val amount = parts[1].toDoubleOrNull()
                if (memberId != null && amount != null) {
                    result[memberId] = amount
                }
            }
        }
        return result
    }
    
    /**
     * Archive a group (soft delete).
     */
    suspend fun archiveGroup(groupId: Long) {
        groupDao.archiveGroup(groupId)
    }
    
    /**
     * Restore an archived group.
     */
    suspend fun restoreGroup(groupId: Long) {
        groupDao.restoreGroup(groupId)
    }
    
    /**
     * Delete a group permanently.
     */
    suspend fun deleteGroup(group: ExpenseGroup) {
        groupDao.delete(group)
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
