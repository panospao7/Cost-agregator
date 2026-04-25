package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.groups.GroupValidationError
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.Result

data class GroupDetailsAggregate(
    val group: ExpenseGroup,
    val members: List<GroupMember>,
    val expenses: List<GroupExpense>
)

sealed class DeleteGroupMemberResult {
    data object Success : DeleteGroupMemberResult()
    data class CannotDeleteMemberWithExpenses(val expenseCount: Int) : DeleteGroupMemberResult()
    data class CannotDeleteMemberReferencedInSplits(val expenseCount: Int) : DeleteGroupMemberResult()
    data class Error(val message: String) : DeleteGroupMemberResult()
}

interface GroupsRepository {
    suspend fun getActiveGroupsWithDetails(): List<GroupDetailsAggregate>

    suspend fun getGroupById(groupId: Long): ExpenseGroup?

    suspend fun getMemberById(memberId: Long): GroupMember?

    suspend fun createGroup(
        name: String,
        description: String?,
        currency: String,
        currentUserName: String = "You"
    ): GroupCreationResult

    suspend fun addMember(
        groupId: Long,
        name: String,
        email: String?,
        isCurrentUser: Boolean = false
    ): Result<Unit, GroupValidationError>

    suspend fun addExpenseWithLink(
        groupId: Long,
        systemExpenseId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType,
        customSplitsJson: String? = null,
        date: Long
    ): GroupExpenseCreationResult

    /**
     * B.4 Batch 2: Atomically create a system expense AND link it to a group
     * in a single database transaction, eliminating the orphan window.
     */
    suspend fun createSystemExpenseAndLinkToGroup(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String,
        splitType: SplitType,
        customSplitsJson: String? = null,
        date: Long,
        transactionType: TransactionType = TransactionType.PURCHASE,
        notes: String? = null
    ): GroupExpenseCreationResult

    suspend fun deleteGroup(groupId: Long): Boolean

    suspend fun deleteMember(groupId: Long, memberId: Long): DeleteGroupMemberResult
}
