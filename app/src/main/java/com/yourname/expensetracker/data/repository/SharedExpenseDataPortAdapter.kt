package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.groups.GroupSplitType
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
import com.yourname.expensetracker.domain.groups.SharedExpenseGroup
import com.yourname.expensetracker.domain.groups.SharedExpenseMember
import com.yourname.expensetracker.domain.groups.SharedGroupExpense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Singleton

/**
 * Data-layer adapter that bridges Room models/DAOs to domain shared-expense ports.
 */
@Singleton
class SharedExpenseDataPortAdapter @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val transactionCoordinator: GroupTransactionCoordinator,
    private val timeProvider: TimeProvider
) : SharedExpenseDataPort {

    override suspend fun createGroupWithMembers(
        group: SharedExpenseGroup,
        members: List<SharedExpenseMember>
    ): Long {
        writeBarrier.checkWritesAllowed("SharedExpenseDataPortAdapter.createGroupWithMembers")
        return transactionCoordinator.createGroupWithMembersAtomic(
            group = group.toEntity(),
            members = members.map { it.toEntity() }
        )
    }

    override suspend fun addMember(member: SharedExpenseMember): Long {
        writeBarrier.checkWritesAllowed("SharedExpenseDataPortAdapter.addMember")
        return when (val result = transactionCoordinator.addMemberToGroup(
            groupId = member.groupId,
            name = member.name,
            email = member.email,
            isCurrentUser = member.isCurrentUser
        )) {
            is com.yourname.expensetracker.domain.groups.Result.Success -> {
                // Use getAllForGroup to check against all members including left ones for re-admission
                memberDao.getAllForGroup(member.groupId)
                    .firstOrNull {
                        it.name.equals(member.name, ignoreCase = true) &&
                            it.email == member.email &&
                            it.isCurrentUser == member.isCurrentUser
                    }
                    ?.id
                    ?: throw IllegalStateException("Member was validated but could not be resolved after insert")
            }
            is com.yourname.expensetracker.domain.groups.Result.Error -> {
                throw IllegalArgumentException(result.error.toString())
            }
        }
    }

    override suspend fun removeMember(member: SharedExpenseMember) {
        writeBarrier.checkWritesAllowed("SharedExpenseDataPortAdapter.removeMember")
        memberDao.update(member.toEntity().copy(leftAt = timeProvider.now()))
    }

    override suspend fun addExpense(expense: SharedGroupExpense): Long {
        writeBarrier.checkWritesAllowed("SharedExpenseDataPortAdapter.addExpense")
        val result = if (expense.expenseId != null) {
            transactionCoordinator.addExpenseWithLink(
                groupId = expense.groupId,
                systemExpenseId = expense.expenseId,
                description = expense.description,
                amount = expense.totalAmount,
                paidById = expense.paidById,
                currency = expense.currency,
                splitType = expense.splitType.toEntity(),
                customSplitsJson = expense.customSplitsSerialized,
                date = expense.date
            )
        } else {
            transactionCoordinator.addExpenseToGroup(
                groupId = expense.groupId,
                description = expense.description,
                amount = expense.totalAmount,
                paidById = expense.paidById,
                currency = expense.currency,
                splitType = expense.splitType.toEntity(),
                customSplitsJson = expense.customSplitsSerialized,
                date = expense.date
            )
        }

        return when (result) {
            is com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult.Success -> result.groupExpenseId
            is com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult.Error -> {
                throw IllegalArgumentException(result.message)
            }
        }
    }

    override fun getAllGroups(): Flow<List<SharedExpenseGroup>> =
        groupDao.getAllFlow().map { groups -> groups.map { it.toDomain() } }

    override fun getActiveGroups(): Flow<List<SharedExpenseGroup>> =
        groupDao.getActiveFlow().map { groups -> groups.map { it.toDomain() } }

    override fun getGroup(groupId: Long): Flow<SharedExpenseGroup?> =
        groupDao.getByIdFlow(groupId).map { group -> group?.toDomain() }

    override suspend fun getGroupOnce(groupId: Long): SharedExpenseGroup? =
        groupDao.getById(groupId)?.toDomain()

    override fun getGroupMembers(groupId: Long): Flow<List<SharedExpenseMember>> =
        memberDao.getAllForGroupFlow(groupId).map { members -> members.map { it.toDomain() } }

    override fun getGroupExpenses(groupId: Long): Flow<List<SharedGroupExpense>> =
        groupExpenseDao.getExpensesForGroup(groupId).map { expenses -> expenses.map { it.toDomain() } }

    override suspend fun getGroupMembersOnce(groupId: Long): List<SharedExpenseMember> =
        // Returns all members including left ones for historical display
        memberDao.getAllForGroup(groupId).map { it.toDomain() }

    override suspend fun getGroupExpensesOnce(groupId: Long): List<SharedGroupExpense> =
        groupExpenseDao.getExpensesForGroupOnce(groupId).map { it.toDomain() }

    override suspend fun archiveGroup(groupId: Long) {
        writeBarrier.checkWritesAllowed("SharedExpenseDataPortAdapter.archiveGroup")
        groupDao.archiveGroup(groupId)
    }

    override suspend fun restoreGroup(groupId: Long) {
        writeBarrier.checkWritesAllowed("SharedExpenseDataPortAdapter.restoreGroup")
        groupDao.restoreGroup(groupId)
    }

    override suspend fun deleteGroup(group: SharedExpenseGroup) {
        writeBarrier.checkWritesAllowed("SharedExpenseDataPortAdapter.deleteGroup")
        transactionCoordinator.permanentlyDeleteGroup(group.id)
    }

    private fun ExpenseGroup.toDomain(): SharedExpenseGroup = SharedExpenseGroup(
        id = id,
        name = name,
        description = description,
        defaultCurrency = defaultCurrency,
        isActive = isActive,
        createdAt = createdAt,
        createdBy = createdBy
    )

    private fun SharedExpenseGroup.toEntity(): ExpenseGroup = ExpenseGroup(
        id = id,
        name = name,
        description = description,
        defaultCurrency = defaultCurrency,
        isActive = isActive,
        createdAt = createdAt,
        createdBy = createdBy
    )

    private fun GroupMember.toDomain(): SharedExpenseMember = SharedExpenseMember(
        id = id,
        groupId = groupId,
        name = name,
        email = email,
        isCurrentUser = isCurrentUser,
        joinedAt = joinedAt,
        leftAt = leftAt
    )

    private fun SharedExpenseMember.toEntity(): GroupMember = GroupMember(
        id = id,
        groupId = groupId,
        name = name,
        email = email,
        isCurrentUser = isCurrentUser,
        joinedAt = joinedAt,
        leftAt = leftAt
    )

    private fun GroupExpense.toDomain(): SharedGroupExpense = SharedGroupExpense(
        id = id,
        groupId = groupId,
        expenseId = expenseId,
        paidById = paidById,
        date = date,
        description = description,
        totalAmount = totalAmount,
        currency = currency,
        splitType = splitType.toDomain(),
        customSplitsSerialized = customSplitsJson,
        isReimbursable = isReimbursable,
        reimbursedAmount = reimbursedAmount,
        settledAt = settledAt,
        myShareAmount = myShareAmount
    )

    private fun SharedGroupExpense.toEntity(): GroupExpense = GroupExpense(
        id = id,
        groupId = groupId,
        expenseId = expenseId,
        paidById = paidById,
        date = date,
        description = description,
        totalAmount = totalAmount,
        currency = currency,
        splitType = splitType.toEntity(),
        customSplitsJson = customSplitsSerialized,
        isReimbursable = isReimbursable,
        reimbursedAmount = reimbursedAmount,
        settledAt = settledAt,
        myShareAmount = myShareAmount
    )

    private fun SplitType.toDomain(): GroupSplitType = when (this) {
        SplitType.EQUAL -> GroupSplitType.EQUAL
        SplitType.CUSTOM_AMOUNT -> GroupSplitType.CUSTOM_AMOUNT
        SplitType.CUSTOM_PERCENT -> GroupSplitType.CUSTOM_PERCENT
        SplitType.UNEQUAL -> GroupSplitType.UNEQUAL
    }

    private fun GroupSplitType.toEntity(): SplitType = when (this) {
        GroupSplitType.EQUAL -> SplitType.EQUAL
        GroupSplitType.CUSTOM_AMOUNT -> SplitType.CUSTOM_AMOUNT
        GroupSplitType.CUSTOM_PERCENT -> SplitType.CUSTOM_PERCENT
        GroupSplitType.UNEQUAL -> SplitType.UNEQUAL
    }
}
