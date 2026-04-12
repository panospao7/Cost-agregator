package com.yourname.expensetracker.data.database

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator as DomainCoordinator
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HIGH-06 FIX: Single Coordinator Pattern Implementation
 * 
 * This class implements the domain GroupTransactionCoordinator interface.
 * It provides atomic multi-DAO transactions using RoomDatabase.withTransaction.
 * 
 * CRITICAL-2: Ensures ACID compliance across multiple tables:
 * - Atomicity: All operations succeed or all fail
 * - Consistency: Database remains in valid state
 * - Isolation: Concurrent transactions don't interfere
 * - Durability: Committed changes survive crashes
 * 
 * B.4 Batch 2: Added ExpenseDao for atomic system-expense + group-link flow.
 * 
 * This is the SINGLE implementation of the GroupTransactionCoordinator contract.
 */
@Singleton
class GroupTransactionCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val expenseDao: ExpenseDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DomainCoordinator {
    
    // ==================== Interface Implementation ====================
    
    /**
     * Create a new group with initial members atomically using DB transaction.
     */
    override suspend fun createGroupWithMembers(
        name: String,
        description: String?,
        currency: String,
        members: List<GroupMember>
    ): GroupCreationResult = withContext(ioDispatcher) {
        try {
            val group = ExpenseGroup(
                name = name,
                description = description,
                defaultCurrency = currency
            )
            
            // Use atomic DB transaction - either all succeed or all fail
            val groupId = database.withTransaction {
                val newGroupId = groupDao.insert(group)
                
                // If this fails, group insertion rolls back
                val membersWithGroupId = members.map { 
                    it.copy(groupId = newGroupId) 
                }
                memberDao.insertAll(membersWithGroupId)
                
                newGroupId
            }
            
            GroupCreationResult.Success(groupId)
        } catch (e: Exception) {
            GroupCreationResult.Error("Group creation failed: ${e.message}")
        }
    }
    
    /**
     * Add a member to an existing group.
     * B.4 Batch 2: Wrapped in database.withTransaction to close the
     * read-check-then-insert TOCTOU window (Risk 3).
     */
    override suspend fun addMemberToGroup(
        groupId: Long,
        name: String,
        email: String?,
        isCurrentUser: Boolean
    ): Long? = withContext(ioDispatcher) {
        try {
            database.withTransaction {
                // Verify group exists and is active inside the transaction
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction null
                }

                val member = GroupMember(
                    groupId = groupId,
                    name = name,
                    email = email,
                    isCurrentUser = isCurrentUser
                )

                memberDao.insert(member)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Add an expense to a group.
     * This creates the group expense record without linking to a system expense.
     */
    override suspend fun addExpenseToGroup(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String?,
        splitType: SplitType,
        date: Long
    ): GroupExpenseCreationResult = withContext(ioDispatcher) {
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

            val expenseCurrency = currency ?: group.defaultCurrency
            
            // Create the group expense (without system link - expenseId is null for standalone)
            val expense = GroupExpense(
                groupId = groupId,
                expenseId = null, // No linked expense - standalone group expense
                description = description,
                totalAmount = amount,
                paidById = paidById,
                date = date,
                currency = expenseCurrency,
                splitType = splitType
            )
            
            val expenseId = groupExpenseDao.insert(expense)
            
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
     * B.4 Batch 2: Wrapped in database.withTransaction so validation + insert
     * are atomic (Risk 2).
     */
    override suspend fun addExpenseWithLink(
        groupId: Long,
        systemExpenseId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String?,
        splitType: SplitType,
        customSplitsJson: String?,
        date: Long
    ): GroupExpenseCreationResult = withContext(ioDispatcher) {
        try {
            database.withTransaction {
                // Verify group exists and is active
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction GroupExpenseCreationResult.Error("Group not found or inactive")
                }

                // Verify payer is a member
                val members = memberDao.getAllForGroup(groupId)
                if (members.none { it.id == paidById }) {
                    return@withTransaction GroupExpenseCreationResult.Error("Payer is not a member of this group")
                }

                val expenseCurrency = currency ?: group.defaultCurrency

                // Create the group expense with system link
                val expense = GroupExpense(
                    groupId = groupId,
                    expenseId = systemExpenseId,
                    description = description,
                    totalAmount = amount,
                    paidById = paidById,
                    date = date,
                    currency = expenseCurrency,
                    splitType = splitType,
                    customSplitsJson = customSplitsJson
                )

                val groupExpenseId = groupExpenseDao.insert(expense)

                if (groupExpenseId <= 0) {
                    return@withTransaction GroupExpenseCreationResult.Error("Failed to create group expense")
                }

                GroupExpenseCreationResult.Success(
                    groupExpenseId = groupExpenseId,
                    expenseId = systemExpenseId
                )
            }
        } catch (e: Exception) {
            GroupExpenseCreationResult.Error("Failed to add expense: ${e.message}")
        }
    }
    
    /**
     * Delete a group and all associated data (members, expenses).
     * This is a soft delete - sets isActive = false.
     */
    override suspend fun deleteGroup(groupId: Long): Boolean = withContext(ioDispatcher) {
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
     */
    override suspend fun permanentlyDeleteGroup(groupId: Long): Boolean = withContext(ioDispatcher) {
        try {
            deleteGroupAtomic(groupId)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // ==================== Additional Atomic Operations ====================
    // These methods may be used internally or for advanced use cases
    
    /**
     * Atomic group creation with members.
     * Low-level version for direct entity-based operations.
     * 
     * CRITICAL: If member insertion fails, group insertion is rolled back.
     * Prevents orphaned groups.
     */
    override suspend fun createGroupWithMembersAtomic(
        group: ExpenseGroup,
        members: List<GroupMember>
    ): Long {
        return database.withTransaction {
            // Insert group first
            val groupId = groupDao.insert(group)
            
            // If this fails, group insertion rolls back
            val membersWithGroupId = members.map { 
                it.copy(groupId = groupId) 
            }
            memberDao.insertAll(membersWithGroupId)
            
            groupId
        }
    }

    /**
     * B.4 Batch 2 — Risk 1: Atomically create a system expense AND link it to
     * a group in a single database transaction.
     *
     * This eliminates the orphan window in the old two-step ViewModel flow
     * where a system expense could exist without an associated group link.
     *
     * All validation (group active, payer membership) and both inserts happen
     * inside [database.withTransaction]. If any step fails, everything rolls back.
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
    ): GroupExpenseCreationResult = withContext(ioDispatcher) {
        try {
            database.withTransaction {
                // 1. Validate group exists and is active
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction GroupExpenseCreationResult.Error("Group not found or inactive")
                }

                // 2. Validate payer is a member of the group
                val members = memberDao.getAllForGroup(groupId)
                if (members.none { it.id == paidById }) {
                    return@withTransaction GroupExpenseCreationResult.Error("Payer is not a member of this group")
                }

                val payer = members.first { it.id == paidById }

                val expenseCurrency = group.defaultCurrency

                // 3. Create system expense with type-aware dedupe key
                // (mirrors ManualExpenseRepository's safe-insert semantics
                // so that the shared unique index on dedupeKey prevents
                // duplicate system expenses from group flows)
                val systemExpense = Expense(
                    amount = amount,
                    currency = expenseCurrency,
                    merchant = description,
                    merchantKey = MerchantKeyGenerator.generate(description),
                    transactionType = transactionType,
                    date = date,
                    isManualEntry = true,
                    notes = notes ?: "Group expense via ${payer.name}",
                    createdAt = System.currentTimeMillis(),
                    dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                        amount = amount,
                        merchant = description,
                        date = date,
                        currency = expenseCurrency,
                        transactionType = transactionType
                    )
                )

                // insertAtomic is IGNORE-on-conflict — last-line race guard
                // matching the convention used by ManualExpenseRepository
                val systemExpenseId = expenseDao.insertAtomic(systemExpense)
                if (systemExpenseId <= 0) {
                    return@withTransaction GroupExpenseCreationResult.Error("Failed to create system expense")
                }

                // 4. Create group expense linked to system expense
                val groupExpense = GroupExpense(
                    groupId = groupId,
                    expenseId = systemExpenseId,
                    description = description,
                    totalAmount = amount,
                    paidById = paidById,
                    date = date,
                    currency = expenseCurrency,
                    splitType = splitType,
                    customSplitsJson = customSplitsJson
                )

                val groupExpenseId = groupExpenseDao.insert(groupExpense)
                if (groupExpenseId <= 0) {
                    return@withTransaction GroupExpenseCreationResult.Error("Failed to create group expense link")
                }

                GroupExpenseCreationResult.Success(
                    groupExpenseId = groupExpenseId,
                    expenseId = systemExpenseId
                )
            }
        } catch (e: Exception) {
            GroupExpenseCreationResult.Error("Failed to create group expense atomically: ${e.message}")
        }
    }
    
    /**
     * Atomically insert a group expense record.
     *
     * This is an insert-only helper — it does NOT update member balances.
     * Balance computation is performed at read time by [SplitCalculator].
     *
     * @param groupExpense the [GroupExpense] entity to insert
     * @return the row-id of the inserted group expense
     */
    suspend fun addExpenseToGroupAtomic(
        groupExpense: GroupExpense
    ): Long {
        return database.withTransaction {
            groupExpenseDao.insert(groupExpense)
        }
    }
    
    /**
     * Atomic group deletion with cleanup.
     * Removes all associated members and expenses.
     */
    suspend fun deleteGroupAtomic(groupId: Long) {
        database.withTransaction {
            // Delete expenses first (child table)
            groupExpenseDao.deleteAllForGroup(groupId)
            
            // Delete members
            memberDao.deleteAllForGroup(groupId)
            
            // Delete group last (parent table)
            val group = groupDao.getGroupById(groupId)
            group?.let { groupDao.delete(it) }
        }
    }
}
