package com.yourname.expensetracker.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.domain.logic.CustomSplitMode
import com.yourname.expensetracker.domain.logic.CustomSplitParser
import com.yourname.expensetracker.domain.groups.GroupValidationError
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator
import com.yourname.expensetracker.domain.groups.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class GroupsRepositoryImpl @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val database: AppDatabase,
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val coordinator: GroupTransactionCoordinator,
    private val currencySettingsRepository: CurrencySettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : GroupsRepository {

    private companion object {
        private const val GROUP_IDS_QUERY_CHUNK_SIZE = 500
    }

    override suspend fun getActiveGroupsWithDetails(): List<GroupDetailsAggregate> = withContext(ioDispatcher) {
        val groups = groupDao.getActive()
        if (groups.isEmpty()) {
            return@withContext emptyList()
        }

        val groupIds = groups.map { it.id }
        val membersByGroupId = groupIds
            .chunked(GROUP_IDS_QUERY_CHUNK_SIZE)
            .flatMap { chunk -> memberDao.getAllForGroups(chunk) }
            .groupBy { it.groupId }
        val expensesByGroupId = groupIds
            .chunked(GROUP_IDS_QUERY_CHUNK_SIZE)
            .flatMap { chunk -> groupExpenseDao.getExpensesForGroups(chunk) }
            .groupBy { it.groupId }

        groups.map { group ->
            GroupDetailsAggregate(
                group = group,
                members = membersByGroupId[group.id].orEmpty(),
                expenses = expensesByGroupId[group.id].orEmpty()
            )
        }
    }

    override suspend fun getGroupById(groupId: Long) = withContext(ioDispatcher) {
        groupDao.getById(groupId)
    }

    override suspend fun getMemberById(memberId: Long) = withContext(ioDispatcher) {
        memberDao.getById(memberId)
    }

    override suspend fun createGroup(
        name: String,
        description: String?,
        currency: String,
        currentUserName: String
    ): GroupCreationResult {
        writeBarrier.checkWritesAllowed("GroupsRepositoryImpl.createGroup")
        return withContext(ioDispatcher) {
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
    }

    override suspend fun addMember(
        groupId: Long,
        name: String,
        email: String?,
        isCurrentUser: Boolean
    ): Result<Unit, GroupValidationError> {
        writeBarrier.checkWritesAllowed("GroupsRepositoryImpl.addMember")
        return withContext(ioDispatcher) {
        coordinator.addMemberToGroup(
            groupId = groupId,
            name = name,
            email = email,
            isCurrentUser = isCurrentUser
        )
        }
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
    ): GroupExpenseCreationResult {
        writeBarrier.checkWritesAllowed("GroupsRepositoryImpl.addExpenseWithLink")
        return withContext(ioDispatcher) {
        val homeCurrency = try { currencySettingsRepository.homeCurrency().first() } catch (_: Exception) { "EUR" }
        val groupCurrency = groupDao.getById(groupId)?.defaultCurrency ?: homeCurrency

        coordinator.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = description,
            amount = amount,
            paidById = paidById,
            currency = groupCurrency,
            splitType = splitType,
            customSplitsJson = customSplitsJson,
            date = date
        )
        }
    }

    /**
     * B.4 Batch 2: Delegates to coordinator's atomic create-and-link method.
     * Currency is resolved from the group if not directly available at this layer.
     */
    override suspend fun createSystemExpenseAndLinkToGroup(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String,
        splitType: SplitType,
        customSplitsJson: String?,
        date: Long,
        transactionType: TransactionType,
        notes: String?
    ): GroupExpenseCreationResult {
        writeBarrier.checkWritesAllowed("GroupsRepositoryImpl.createSystemExpenseAndLinkToGroup")
        return withContext(ioDispatcher) {
        coordinator.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = description,
            amount = amount,
            paidById = paidById,
            currency = currency,
            splitType = splitType,
            customSplitsJson = customSplitsJson,
            date = date,
            transactionType = transactionType,
            notes = notes
        )
        }
    }

    override suspend fun deleteGroup(groupId: Long): Boolean {
        writeBarrier.checkWritesAllowed("GroupsRepositoryImpl.deleteGroup")
        return withContext(ioDispatcher) {
        coordinator.deleteGroup(groupId)
        }
    }

    // TODO (G09): Keep deletion validation in one coordinator/use case, not in optional caller code.
    //             The pre-flight checks and the actual deletion are currently split between
    //             this repository method and the SharedExpenseManager, making it possible
    //             for callers to bypass validation by calling this method directly.
    override suspend fun deleteMember(groupId: Long, memberId: Long): DeleteGroupMemberResult {
        writeBarrier.checkWritesAllowed("GroupsRepositoryImpl.deleteMember")
        return withContext(ioDispatcher) {
        try {
            val preflightMember = memberDao.getById(memberId)
                ?: return@withContext DeleteGroupMemberResult.Error("Member not found")

            if (preflightMember.groupId != groupId) {
                return@withContext DeleteGroupMemberResult.Error("Member does not belong to this group")
            }

            val preflightSplitReferenceCount = countSplitReferences(groupId, preflightMember)
            if (preflightSplitReferenceCount > 0) {
                return@withContext DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits(preflightSplitReferenceCount)
            }

            database.withTransaction {
                val member = memberDao.getById(memberId)
                    ?: return@withTransaction DeleteGroupMemberResult.Error("Member not found")

                if (member.groupId != groupId) {
                    return@withTransaction DeleteGroupMemberResult.Error("Member does not belong to this group")
                }

                val expenseCount = groupExpenseDao.countExpensesPaidByMember(groupId, memberId)
                if (expenseCount > 0) {
                    return@withTransaction DeleteGroupMemberResult.CannotDeleteMemberWithExpenses(expenseCount)
                }

                val splitReferenceCount = countSplitReferences(groupId, member)
                if (splitReferenceCount > 0) {
                    return@withTransaction DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits(splitReferenceCount)
                }

                memberDao.delete(member)
                DeleteGroupMemberResult.Success
            }
        } catch (_: SQLiteConstraintException) {
            val expenseCount = groupExpenseDao.countExpensesPaidByMember(groupId, memberId)
            if (expenseCount > 0) {
                DeleteGroupMemberResult.CannotDeleteMemberWithExpenses(expenseCount)
            } else {
                val member = memberDao.getById(memberId)
                val splitReferenceCount = member?.let { countSplitReferences(groupId, it) } ?: 0
                if (splitReferenceCount > 0) {
                    DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits(splitReferenceCount)
                } else {
                    DeleteGroupMemberResult.Error("Failed to delete member")
                }
            }
        } catch (e: Exception) {
            DeleteGroupMemberResult.Error(e.message ?: "Failed to delete member")
        }
        }
    }

    private suspend fun countSplitReferences(groupId: Long, member: GroupMember): Int {
        val memberIds = memberDao.getAllForGroup(groupId).map { it.id }.toSet()
        if (memberIds.isEmpty()) return 0

        return groupExpenseDao.getExpensesForGroupOnce(groupId)
            .filter { expense ->
                when (expense.splitType) {
                    SplitType.EQUAL -> expense.date >= member.joinedAt
                    SplitType.CUSTOM_AMOUNT,
                    SplitType.CUSTOM_PERCENT,
                    SplitType.UNEQUAL -> {
                        if (expense.customSplitsJson.isNullOrBlank()) {
                            false
                        } else {
                            val parseResult = CustomSplitParser.parseAndValidate(
                                splitsString = expense.customSplitsJson,
                                splitType = expense.splitType.toCustomSplitMode(),
                                totalAmount = expense.totalAmount,
                                groupMemberIds = memberIds
                            )

                            CustomSplitParser.referencesMember(
                                splitsString = expense.customSplitsJson,
                                memberId = member.id,
                                parseResult = parseResult
                            )
                        }
                    }
                }
            }
            .size
    }

    private fun SplitType.toCustomSplitMode(): CustomSplitMode {
        return when (this) {
            SplitType.EQUAL -> CustomSplitMode.EQUAL
            SplitType.CUSTOM_AMOUNT -> CustomSplitMode.CUSTOM_AMOUNT
            SplitType.CUSTOM_PERCENT -> CustomSplitMode.CUSTOM_PERCENT
            SplitType.UNEQUAL -> CustomSplitMode.UNEQUAL
        }
    }
}
