package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a group creation operation.
 */
sealed class GroupCreationResult {
    data class Success(val groupId: Long) : GroupCreationResult()
    data class Error(val message: String) : GroupCreationResult()
}

/**
 * Result of a group expense creation operation.
 */
sealed class GroupExpenseCreationResult {
    data class Success(
        val groupExpenseId: Long,
        val expenseId: Long
    ) : GroupExpenseCreationResult()
    data class Error(val message: String) : GroupExpenseCreationResult()
}

/**
 * Coordinator for atomic group-related transactions.
 * Ensures data integrity when creating groups with members or adding expenses.
 * 
 * All operations are transactional - either all steps succeed or all fail.
 */
@Singleton
class GroupTransactionCoordinator @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val expenseDao: GroupExpenseDao
) {
    
    /**
     * Create a new group with initial members atomically using DB transaction.
     * 
     * @param name Group name
     * @param description Optional group description
     * @param currency Currency code (e.g., "EUR", "USD")
     * @param members List of initial members to add
     * @return GroupCreationResult with group ID or error
     */
    suspend fun createGroupWithMembers(
        name: String,
        description: String?,
        currency: String,
        members: List<GroupMember>
    ): GroupCreationResult = withContext(Dispatchers.IO) {
        try {
            val group = ExpenseGroup(
                name = name,
                description = description,
                defaultCurrency = currency
            )
            
            // Use atomic DB transaction - either all succeed or all fail
            val groupId = groupDao.insertGroupWithMembers(
                group = group,
                memberDao = memberDao,
                members = members
            )
            
            GroupCreationResult.Success(groupId)
        } catch (e: Exception) {
            GroupCreationResult.Error("Group creation failed: ${e.message}")
        }
    }
    
    /**
     * Add a member to an existing group.
     * 
     * @param groupId Group ID
     * @param name Member name
     * @param email Optional member email
     * @param isCurrentUser Whether this member represents the current user
     * @return Member ID or null if failed
     */
    suspend fun addMemberToGroup(
        groupId: Long,
        name: String,
        email: String? = null,
        isCurrentUser: Boolean = false
    ): Long? = withContext(Dispatchers.IO) {
        try {
            // Verify group exists and is active
            val group = groupDao.getById(groupId)
            if (group == null || !group.isActive) {
                return@withContext null
            }
            
            val member = GroupMember(
                groupId = groupId,
                name = name,
                email = email,
                isCurrentUser = isCurrentUser
            )
            
            memberDao.insert(member)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Add an expense to a group.
     * This creates the group expense record without linking to a system expense.
     * For linked expenses, use [addExpenseWithLink].
     * 
     * @param groupId Group ID
     * @param description Expense description
     * @param amount Total expense amount
     * @param paidById ID of member who paid
     * @param splitType How to split the expense
     * @param date Expense date in milliseconds
     * @return GroupExpenseCreationResult with IDs or error
     */
    suspend fun addExpenseToGroup(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType = SplitType.EQUAL,
        date: Long = System.currentTimeMillis()
    ): GroupExpenseCreationResult = withContext(Dispatchers.IO) {
        try {
            // Verify group exists and is active
            val group = groupDao.getById(groupId)
            if (group == null || !group.isActive) {
                return@withContext GroupExpenseCreationResult.Error("Group not found or inactive")
            }
            
            // Verify payer is a member of the group
            val members = memberDao.getAllForGroup(groupId)
            if (members.none { it.id == paidById }) {
                return@withContext GroupExpenseCreationResult.Error("Payer is not a member of this group")
            }
            
            // Create the group expense (without system link)
            val expense = GroupExpense(
                groupId = groupId,
                expenseId = 0, // No linked expense
                description = description,
                totalAmount = amount,
                paidById = paidById,
                date = date,
                splitType = splitType
            )
            
            val expenseId = expenseDao.insert(expense)
            
            if (expenseId <= 0) {
                return@withContext GroupExpenseCreationResult.Error("Failed to create expense")
            }
            
            GroupExpenseCreationResult.Success(
                groupExpenseId = expenseId,
                expenseId = 0 // No linked expense
            )
        } catch (e: Exception) {
            GroupExpenseCreationResult.Error("Failed to add expense: ${e.message}")
        }
    }
    
    /**
     * Add an expense to a group and link it to a system expense.
     * This is the proper way to create group expenses that appear in transaction history.
     * 
     * @param groupId Group ID
     * @param systemExpenseId ID of the expense in the main expenses table
     * @param description Expense description
     * @param amount Total expense amount
     * @param paidById ID of member who paid
     * @param splitType How to split the expense
     * @param date Expense date in milliseconds
     * @return GroupExpenseCreationResult with IDs or error
     */
    suspend fun addExpenseWithLink(
        groupId: Long,
        systemExpenseId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType = SplitType.EQUAL,
        date: Long = System.currentTimeMillis()
    ): GroupExpenseCreationResult = withContext(Dispatchers.IO) {
        try {
            // Verify group exists and is active
            val group = groupDao.getById(groupId)
            if (group == null || !group.isActive) {
                return@withContext GroupExpenseCreationResult.Error("Group not found or inactive")
            }
            
            // Verify payer is a member
            val members = memberDao.getAllForGroup(groupId)
            if (members.none { it.id == paidById }) {
                return@withContext GroupExpenseCreationResult.Error("Payer is not a member of this group")
            }
            
            // Create the group expense with system link
            val expense = GroupExpense(
                groupId = groupId,
                expenseId = systemExpenseId, // Link to system expense
                description = description,
                totalAmount = amount,
                paidById = paidById,
                date = date,
                splitType = splitType
            )
            
            val groupExpenseId = expenseDao.insert(expense)
            
            if (groupExpenseId <= 0) {
                return@withContext GroupExpenseCreationResult.Error("Failed to create group expense")
            }
            
            GroupExpenseCreationResult.Success(
                groupExpenseId = groupExpenseId,
                expenseId = systemExpenseId
            )
        } catch (e: Exception) {
            GroupExpenseCreationResult.Error("Failed to add expense: ${e.message}")
        }
    }
    
    /**
     * Delete a group and all associated data (members, expenses).
     * This is a soft delete - sets isActive = false.
     * 
     * @param groupId Group ID to delete
     * @return True if successful, false otherwise
     */
    suspend fun deleteGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            groupDao.archiveGroup(groupId)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Permanently delete a group and all associated data.
     * WARNING: This cannot be undone.
     * 
     * @param groupId Group ID to permanently delete
     * @return True if successful, false otherwise
     */
    suspend fun permanentlyDeleteGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // Delete all expenses first
            expenseDao.deleteAllForGroup(groupId)
            
            // Delete all members
            memberDao.deleteAllForGroup(groupId)
            
            // Finally delete the group
            val group = groupDao.getById(groupId)
            if (group != null) {
                groupDao.delete(group)
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
}
