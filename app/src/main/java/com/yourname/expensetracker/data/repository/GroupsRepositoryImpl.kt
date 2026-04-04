package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupsRepositoryImpl @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val coordinator: GroupTransactionCoordinator
) : GroupsRepository {

    override suspend fun getActiveGroupsWithDetails(): List<GroupDetailsAggregate> = withContext(Dispatchers.IO) {
        val groups = groupDao.getActive()
        groups.map { group ->
            GroupDetailsAggregate(
                group = group,
                members = memberDao.getAllForGroup(group.id),
                expenses = groupExpenseDao.getExpensesForGroupOnce(group.id)
            )
        }
    }

    override suspend fun getGroupById(groupId: Long) = withContext(Dispatchers.IO) {
        groupDao.getById(groupId)
    }

    override suspend fun getMemberById(memberId: Long) = withContext(Dispatchers.IO) {
        memberDao.getById(memberId)
    }

    override suspend fun createGroup(
        name: String,
        description: String?,
        currency: String,
        currentUserName: String
    ): GroupCreationResult = withContext(Dispatchers.IO) {
        coordinator.createGroupWithMembers(
            name = name,
            description = description,
            currency = currency,
            members = listOf(
                GroupMember(
                    groupId = 0,
                    name = currentUserName,
                    isCurrentUser = true
                )
            )
        )
    }

    override suspend fun addMember(
        groupId: Long,
        name: String,
        email: String?,
        isCurrentUser: Boolean
    ): Long? = withContext(Dispatchers.IO) {
        coordinator.addMemberToGroup(
            groupId = groupId,
            name = name,
            email = email,
            isCurrentUser = isCurrentUser
        )
    }

    override suspend fun addExpenseWithLink(
        groupId: Long,
        systemExpenseId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType,
        customSplitsJson: String?,
        date: Long
    ): GroupExpenseCreationResult = withContext(Dispatchers.IO) {
        coordinator.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = description,
            amount = amount,
            paidById = paidById,
            splitType = splitType,
            customSplitsJson = customSplitsJson,
            date = date
        )
    }

    override suspend fun deleteGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        coordinator.deleteGroup(groupId)
    }

    override suspend fun deleteMember(groupId: Long, memberId: Long): DeleteGroupMemberResult = withContext(Dispatchers.IO) {
        try {
            val member = memberDao.getById(memberId)
                ?: return@withContext DeleteGroupMemberResult.Error("Member not found")

            if (member.groupId != groupId) {
                return@withContext DeleteGroupMemberResult.Error("Member does not belong to this group")
            }

            val expenseCount = groupExpenseDao.countExpensesPaidByMember(groupId, memberId)
            if (expenseCount > 0) {
                return@withContext DeleteGroupMemberResult.CannotDeleteMemberWithExpenses(expenseCount)
            }

            memberDao.delete(member)
            DeleteGroupMemberResult.Success
        } catch (e: Exception) {
            DeleteGroupMemberResult.Error(e.message ?: "Failed to delete member")
        }
    }
}
