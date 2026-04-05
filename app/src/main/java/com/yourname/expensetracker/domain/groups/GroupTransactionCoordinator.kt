package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType

/**
 * HIGH-06 FIX: Single Coordinator Pattern
 * 
 * This interface defines the contract for group-related transactional operations.
 * The implementation resides in the data layer (data.database.GroupTransactionCoordinator)
 * which has access to the database and runs actual atomic transactions.
 * 
 * Benefits:
 * - Clean separation of concerns: domain defines contract, data implements it
 * - Testability: domain code can mock this interface
 * - Single source of truth for group transaction coordination
 * - ACID compliance across multiple tables via Room's withTransaction
 */

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
 * 
 * HIGH-06: This is the unified interface. Implementation is in data layer.
 */
interface GroupTransactionCoordinator {
    
    /**
     * Create a new group with initial members atomically using DB transaction.
     * High-level version that creates the group entity internally.
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
    ): GroupCreationResult
    
    /**
     * Create a new group with initial members atomically using DB transaction.
     * Low-level version for domain services that construct entities directly.
     * 
     * @param group The group entity to insert
     * @param members List of members (groupId will be set by transaction)
     * @return ID of the created group
     */
    suspend fun createGroupWithMembersAtomic(
        group: ExpenseGroup,
        members: List<GroupMember>
    ): Long
    
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
    ): Long?
    
    /**
     * Add an expense to a group.
     * This creates the group expense record without linking to a system expense.
     * For linked expenses, use [addExpenseWithLink].
     * 
     * @param groupId Group ID
     * @param description Expense description
     * @param amount Total expense amount
     * @param paidById ID of member who paid
     * @param currency Currency code for this group expense. If null, implementation derives group default.
     * @param splitType How to split the expense
     * @param date Expense date in milliseconds
     * @return GroupExpenseCreationResult with IDs or error
     */
    suspend fun addExpenseToGroup(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String? = null,
        splitType: SplitType = SplitType.EQUAL,
        date: Long = System.currentTimeMillis()
    ): GroupExpenseCreationResult
    
    /**
     * Add an expense to a group and link it to a system expense.
     * This is the proper way to create group expenses that appear in transaction history.
     * 
     * @param groupId Group ID
     * @param systemExpenseId ID of the expense in the main expenses table
     * @param description Expense description
     * @param amount Total expense amount
     * @param paidById ID of member who paid
     * @param currency Currency code for this group expense. If null, implementation derives group default.
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
        currency: String? = null,
        splitType: SplitType = SplitType.EQUAL,
        customSplitsJson: String? = null,
        date: Long = System.currentTimeMillis()
    ): GroupExpenseCreationResult
    
    /**
     * Delete a group and all associated data (members, expenses).
     * This is a soft delete - sets isActive = false.
     * 
     * @param groupId Group ID to delete
     * @return True if successful, false otherwise
     */
    suspend fun deleteGroup(groupId: Long): Boolean
    
    /**
     * Permanently delete a group and all associated data.
     * WARNING: This cannot be undone.
     * 
     * @param groupId Group ID to permanently delete
     * @return True if successful, false otherwise
     */
    suspend fun permanentlyDeleteGroup(groupId: Long): Boolean
}
