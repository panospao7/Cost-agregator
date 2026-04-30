package com.yourname.expensetracker.domain.groups

import kotlinx.coroutines.flow.Flow

/**
 * Domain model for an expense-sharing group.
 */
data class SharedExpenseGroup(
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    /** ISO 4217 currency code. All expenses in this group MUST use this currency. Callers MUST supply the actual currency. */
    val defaultCurrency: String,
    val isActive: Boolean = true,
    val createdAt: Long = 0L, // sentinel — callers MUST supply an explicit boundary timestamp
    val createdBy: String = "me"
)

/**
 * Domain model for a member in a shared-expense group.
 */
data class SharedExpenseMember(
    val id: Long = 0,
    val groupId: Long,
    val name: String,
    val email: String? = null,
    val isCurrentUser: Boolean = false,
    val joinedAt: Long = 0L // sentinel — callers MUST supply an explicit boundary timestamp
)

/**
 * Domain-level split type to avoid direct dependency on Room entities.
 */
enum class GroupSplitType {
    EQUAL,
    CUSTOM_AMOUNT,
    CUSTOM_PERCENT,
    UNEQUAL
}

/**
 * Domain model for an expense linked to a shared group.
 */
data class SharedGroupExpense(
    val id: Long = 0,
    val groupId: Long,
    val expenseId: Long?,
    val paidById: Long,
    val date: Long,
    val description: String,
    val totalAmount: Double,
    /** MUST match the group's defaultCurrency. Callers MUST supply the actual currency. */
    val currency: String,
    val splitType: GroupSplitType = GroupSplitType.EQUAL,
    val customSplitsSerialized: String? = null,
    val isReimbursable: Boolean = false,
    val reimbursedAmount: Double = 0.0,
    val settledAt: Long? = null,
    val myShareAmount: Double? = null
)

/**
 * Domain port for shared-expense persistence and retrieval.
 *
 * Implementations are in the data layer and may use Room/DAOs.
 */
interface SharedExpenseDataPort {
    suspend fun createGroupWithMembers(group: SharedExpenseGroup, members: List<SharedExpenseMember>): Long

    suspend fun addMember(member: SharedExpenseMember): Long

    suspend fun removeMember(member: SharedExpenseMember)

    suspend fun addExpense(expense: SharedGroupExpense): Long

    fun getAllGroups(): Flow<List<SharedExpenseGroup>>

    fun getActiveGroups(): Flow<List<SharedExpenseGroup>>

    fun getGroup(groupId: Long): Flow<SharedExpenseGroup?>

    suspend fun getGroupOnce(groupId: Long): SharedExpenseGroup?

    fun getGroupMembers(groupId: Long): Flow<List<SharedExpenseMember>>

    fun getGroupExpenses(groupId: Long): Flow<List<SharedGroupExpense>>

    suspend fun getGroupMembersOnce(groupId: Long): List<SharedExpenseMember>

    suspend fun getGroupExpensesOnce(groupId: Long): List<SharedGroupExpense>

    suspend fun archiveGroup(groupId: Long)

    suspend fun restoreGroup(groupId: Long)

    suspend fun deleteGroup(group: SharedExpenseGroup)
}

/**
 * Structured result for shared-expense member deletion attempts.
 */
sealed class RemoveSharedExpenseMemberResult {
    data object Success : RemoveSharedExpenseMemberResult()
    data class CannotDeleteMemberWithExpenses(val expenseCount: Int) : RemoveSharedExpenseMemberResult()
    data class CannotDeleteMemberReferencedInSplits(val expenseCount: Int) : RemoveSharedExpenseMemberResult()
    data class Error(val message: String) : RemoveSharedExpenseMemberResult()
}
