package com.yourname.expensetracker.domain.groups

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central coordinator for expense group lifecycle operations.
 *
 * Provides a single entry point for creating groups, adding members,
 * archiving (soft-deleting), and permanently deleting groups with full
 * cleanup of related data (members, group expenses).
 *
 * All write operations enforce the following invariants:
 * - Group creation sets [ExpenseGroup.createdAt] and [GroupMember.joinedAt]
 *   via [TimeProvider.now()].
 * - Permanent deletion removes members and group expenses in a single
 *   database transaction.
 */
@Singleton
class GroupLifecycleCoordinator @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val database: AppDatabase,
    private val timeProvider: TimeProvider
) {
    /**
     * Creates a new expense group and returns the persisted entity with its
     * auto-generated [ExpenseGroup.id].
     *
     * @param name        Display name for the group (e.g. "Trip to Paris").
     * @param currency    Default currency for expenses in this group.
     * @param createdBy   Identifier of the user who created the group.
     * @return The newly created [ExpenseGroup] with a populated [ExpenseGroup.id].
     */
    suspend fun createGroup(
        name: String,
        currency: String,
        createdBy: String
    ): ExpenseGroup {
        val now = timeProvider.now()
        val group = ExpenseGroup(
            name = name,
            defaultCurrency = currency,
            createdAt = now,
            createdBy = createdBy
        )
        val groupId = groupDao.insert(group)
        Timber.tag(TAG).i("Created expense group: id=%d, name='%s'", groupId, name)
        return group.copy(id = groupId)
    }

    /**
     * Adds a member to an existing expense group.
     *
     * @param groupId       The ID of the target group.
     * @param memberName    Display name of the member.
     * @param isCurrentUser Whether this member represents the app user
     *                      (at most one per group, enforced by DB constraint).
     * @return The newly created [GroupMember] with a populated [GroupMember.id].
     */
    suspend fun addMember(
        groupId: Long,
        memberName: String,
        isCurrentUser: Boolean = false
    ): GroupMember {
        val now = timeProvider.now()
        val member = GroupMember(
            groupId = groupId,
            name = memberName,
            isCurrentUser = isCurrentUser,
            currentUserGroupKey = if (isCurrentUser) groupId else null,
            joinedAt = now
        )
        val memberId = memberDao.insert(member)
        return member.copy(id = memberId)
    }

    /**
     * Soft-deletes (archives) a group by setting [ExpenseGroup.isActive] to `false`.
     * The group and its data remain in the database but are excluded from
     * default active-group queries.
     */
    suspend fun archiveGroup(groupId: Long) {
        groupDao.archiveGroup(groupId)
        Timber.tag(TAG).i("Archived expense group: id=%d", groupId)
    }

    /**
     * Permanently deletes a group and all associated data (members, group expenses)
     * in a single database transaction. This operation is irreversible.
     *
     * Deletion order: group expenses → members → group. This ensures foreign-key
     * constraints are not violated during cleanup.
     */
    suspend fun deleteGroupPermanently(groupId: Long) {
        database.withTransaction {
            groupExpenseDao.deleteAllForGroup(groupId)
            memberDao.deleteAllForGroup(groupId)
            groupDao.deleteById(groupId)
        }
        Timber.tag(TAG).i("Permanently deleted expense group: id=%d", groupId)
    }

    companion object {
        private const val TAG = "GroupLifecycle"
    }
}
