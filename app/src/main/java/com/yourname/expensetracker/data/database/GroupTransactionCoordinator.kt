package com.yourname.expensetracker.data.database

import android.database.sqlite.SQLiteConstraintException
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
import com.yourname.expensetracker.domain.groups.GroupValidationError
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.Result
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator as DomainCoordinator
import com.yourname.expensetracker.domain.logic.CustomSplitJsonCodec
import com.yourname.expensetracker.domain.logic.SplitCalculator
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
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
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DomainCoordinator {

    private companion object {
        const val INVALID_EQUAL_SPLIT_MESSAGE =
            "Equal split is invalid for the selected date because the payer is excluded or no participant qualifies"
        const val LINKED_EXPENSE_ALREADY_ATTACHED_MESSAGE =
            "Linked system expense is already attached to a group expense"
    }

    private fun validateSingleCurrentUser(members: List<GroupMember>) {
        val currentUsers = members.filter { it.isCurrentUser }
        if (currentUsers.size > 1) {
            throw IllegalArgumentException("At most one current user is allowed per group")
        }
    }
    
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
            validateSingleCurrentUser(members)

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
    ): Result<Unit, GroupValidationError> = withContext(ioDispatcher) {
        try {
            database.withTransaction {
                // Verify group exists and is active inside the transaction
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction Result.Error(GroupValidationError.InvalidGroup)
                }

                val members = memberDao.getAllForGroup(groupId)
                if (members.any { it.name.equals(name, ignoreCase = true) }) {
                    val existingMember = members.first { it.name.equals(name, ignoreCase = true) }
                    return@withTransaction Result.Error(
                        GroupValidationError.UserAlreadyMember(existingMember.id)
                    )
                }

                if (isCurrentUser) {
                    memberDao.getCurrentUser(groupId)?.let { currentUser ->
                        return@withTransaction Result.Error(
                            GroupValidationError.CurrentUserAlreadyExists(currentUser.id)
                        )
                    }
                }

                val member = GroupMember(
                    groupId = groupId,
                    name = name,
                    email = email,
                    isCurrentUser = isCurrentUser
                )

                memberDao.insert(member)
                Result.Success(Unit)
            }
        } catch (e: SQLiteConstraintException) {
            Result.Error(mapAddMemberConstraintError(e, groupId, name))
        } catch (e: Exception) {
            Result.Error(GroupValidationError.Unknown(e.message))
        }
    }
    
    /**
     * Add an expense to a group.
     * This creates the group expense record without linking to a system expense.
     * SHARED-2: Wrapped in database.withTransaction for ACID compliance.
     *
     * J1: Validates that non-EQUAL split types include a non-null valid [customSplitsJson].
     */
    override suspend fun addExpenseToGroup(
        groupId: Long,
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
                
                // Verify payer is a member of the group
                val members = memberDao.getAllForGroup(groupId)
                if (members.none { it.id == paidById }) {
                    return@withTransaction GroupExpenseCreationResult.Error("Payer is not a member of this group")
                }

                // J1 + S3: Validate custom split payload for non-EQUAL split types
                validateCustomSplitPayloadFormat(
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    memberCount = members.size
                )?.let { validationError ->
                    return@withTransaction validationError
                }

                validateExpenseParticipants(
                    groupId = groupId,
                    linkedExpenseId = null,
                    description = description,
                    amount = amount,
                    paidById = paidById,
                    currency = currency ?: group.defaultCurrency,
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    date = date,
                    members = members
                )?.let { validationError ->
                    return@withTransaction validationError
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
                    splitType = splitType,
                    customSplitsJson = customSplitsJson
                )
                
                val expenseId = groupExpenseDao.insert(expense)
                
                if (expenseId <= 0) {
                    return@withTransaction GroupExpenseCreationResult.Error("Failed to create expense")
                }
                
                GroupExpenseCreationResult.Success(
                    groupExpenseId = expenseId,
                    expenseId = 0 // No linked expense
                )
            }
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

                validateCustomSplitPayloadFormat(
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    memberCount = members.size
                )?.let { validationError ->
                    return@withTransaction validationError
                }

                validateExpenseParticipants(
                    groupId = groupId,
                    linkedExpenseId = systemExpenseId,
                    description = description,
                    amount = amount,
                    paidById = paidById,
                    currency = currency ?: group.defaultCurrency,
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    date = date,
                    members = members
                )?.let { validationError ->
                    return@withTransaction validationError
                }

                val currentUserShare = resolveCurrentUserShare(
                    groupId = groupId,
                    linkedExpenseId = systemExpenseId,
                    description = description,
                    amount = amount,
                    paidById = paidById,
                    currency = currency ?: group.defaultCurrency,
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    date = date,
                    members = members
                ) ?: return@withTransaction GroupExpenseCreationResult.Error(
                    "Current user member not found or share could not be calculated"
                )

                val existingExpense = expenseDao.getById(systemExpenseId)
                    ?: return@withTransaction GroupExpenseCreationResult.Error("Linked system expense not found")

                if (groupExpenseDao.getGroupExpenseForExpense(systemExpenseId) != null) {
                    return@withTransaction GroupExpenseCreationResult.Error(
                        LINKED_EXPENSE_ALREADY_ATTACHED_MESSAGE
                    )
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

                normalizeLinkedSystemExpense(
                    expense = existingExpense,
                    myShareAmount = currentUserShare
                )

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
     * Archive a group by setting isActive = false instead of hard-deleting.
     * Preserves all expense history for audit purposes.
     */
    override suspend fun archiveGroup(groupId: Long): Boolean = withContext(ioDispatcher) {
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
     * J2: Hard-deleting a group leaves any system expenses linked to group
     * expenses semantically orphaned (the group link is gone but the expense
     * still exists). Prefer [deleteGroup] (soft archive) unless you are certain
     * the linked expenses should become standalone.
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
        validateSingleCurrentUser(members)

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

                validateCustomSplitPayloadFormat(
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    memberCount = members.size
                )?.let { validationError ->
                    return@withTransaction validationError
                }

                validateExpenseParticipants(
                    groupId = groupId,
                    linkedExpenseId = null,
                    description = description,
                    amount = amount,
                    paidById = paidById,
                    currency = currency,
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    date = date,
                    members = members
                )?.let { validationError ->
                    return@withTransaction validationError
                }

                val payer = members.first { it.id == paidById }

                val expenseCurrency = currency

                val currentUserShare = resolveCurrentUserShare(
                    groupId = groupId,
                    linkedExpenseId = null,
                    description = description,
                    amount = amount,
                    paidById = paidById,
                    currency = expenseCurrency,
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    date = date,
                    members = members
                ) ?: return@withTransaction GroupExpenseCreationResult.Error(
                    "Current user member not found or share could not be calculated"
                )

                // 3. Create system expense via TransactionLifecycleCoordinator
                // (handles validation, deduplication, insertAtomic, and lifecycle event)
                val createResult = transactionLifecycleCoordinator.createExpense(
                    CreateExpenseRequest(
                        merchant = description,
                        amount = amount,
                        currency = expenseCurrency,
                        date = date,
                        transactionType = transactionType,
                        source = ExpenseSource.GROUP_EXPENSE,
                        notes = notes ?: "Group expense via ${payer.name}",
                        isManualEntry = true,
                        isSharedExpense = true,
                        myShareAmount = currentUserShare
                    )
                )

                val systemExpenseId = when (createResult) {
                    is CreateExpenseResult.Created -> createResult.expenseId
                    is CreateExpenseResult.DuplicateSkipped -> {
                        return@withTransaction GroupExpenseCreationResult.Error(
                            "Duplicate expense: ${createResult.reason}"
                        )
                    }
                    is CreateExpenseResult.ValidationFailed -> {
                        return@withTransaction GroupExpenseCreationResult.Error(
                            createResult.errors.joinToString("; ")
                        )
                    }
                    is CreateExpenseResult.InsertConflict -> {
                        return@withTransaction GroupExpenseCreationResult.Error(
                            "Insert conflict: dedupeKey=${createResult.dedupeKey}"
                        )
                    }
                    is CreateExpenseResult.Error -> {
                        return@withTransaction GroupExpenseCreationResult.Error(
                            "Failed to create system expense: ${createResult.exception.message}"
                        )
                    }
                }

                if (groupExpenseDao.getGroupExpenseForExpense(systemExpenseId) != null) {
                    return@withTransaction GroupExpenseCreationResult.Error(
                        LINKED_EXPENSE_ALREADY_ATTACHED_MESSAGE
                    )
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
            groupExpense.expenseId?.let { expenseId ->
                if (groupExpenseDao.getGroupExpenseForExpense(expenseId) != null) {
                    throw SQLiteConstraintException(LINKED_EXPENSE_ALREADY_ATTACHED_MESSAGE)
                }
            }
            groupExpenseDao.insert(groupExpense)
        }
    }
    
    /**
     * Atomic group deletion with cleanup.
     * Removes all associated members and group expenses.
     *
     * J2: This performs a HARD delete — linked system expenses are NOT removed.
     * After this operation, those expenses lose their group association metadata
     * (isSharedExpense, myShareAmount, etc.) and become semantically orphaned.
     * Prefer [deleteGroup] (soft archive via isActive = false) whenever possible
     * to preserve referential integrity.
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

    private suspend fun normalizeLinkedSystemExpense(
        expense: Expense,
        myShareAmount: Double
    ) {
        expenseDao.updateIsNotMine(expense.id, false)
        expenseDao.updateIsSharedExpense(expense.id, true)
        expenseDao.updateMySharePercentage(expense.id, null)
        expenseDao.updateMyShareAmount(expense.id, myShareAmount)
    }

    private fun resolveCurrentUserShare(
        groupId: Long,
        linkedExpenseId: Long?,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String,
        splitType: SplitType,
        customSplitsJson: String?,
        date: Long,
        members: List<GroupMember>
    ): Double? {
        val currentUserMember = members.singleOrNull { it.isCurrentUser } ?: return null
        val expenseForSplit = GroupExpense(
            groupId = groupId,
            expenseId = linkedExpenseId,
            description = description,
            totalAmount = amount,
            paidById = paidById,
            date = date,
            currency = currency,
            splitType = splitType,
            customSplitsJson = customSplitsJson
        )
        if (!SplitCalculator.isMemberParticipatingInSplit(
                expense = expenseForSplit,
                members = members,
                memberId = currentUserMember.id
            )) {
            return 0.0
        }
        val currentUserShare = SplitCalculator.calculateMemberShare(
            expense = expenseForSplit,
            members = members,
            memberId = currentUserMember.id
        )
        return currentUserShare.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun validateExpenseParticipants(
        groupId: Long,
        linkedExpenseId: Long?,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String,
        splitType: SplitType,
        customSplitsJson: String?,
        date: Long,
        members: List<GroupMember>
    ): GroupExpenseCreationResult.Error? {
        val expenseForValidation = GroupExpense(
            groupId = groupId,
            expenseId = linkedExpenseId,
            description = description,
            totalAmount = amount,
            paidById = paidById,
            date = date,
            currency = currency,
            splitType = splitType,
            customSplitsJson = customSplitsJson
        )

        return SplitCalculator.validateExpenseParticipants(
            expense = expenseForValidation,
            members = members
        )?.let {
            GroupExpenseCreationResult.Error(INVALID_EQUAL_SPLIT_MESSAGE)
        }
    }

    /**
     * ## SHR-2: CUSTOM split serialization
     * Validates that [customSplitsJson] is syntactically valid JSON before storage.
     * Delegates to [CustomSplitJsonCodec.isCanonicalJsonPayload] which:
     *  1. Verifies the payload wraps in `{` / `}`
     *  2. Parses via Gson into `Map<String, Double>`
     *  3. Validates all member IDs parse to Long and all values are finite
     *
     * For CUSTOM_AMOUNT / CUSTOM_PERCENT splits, additionally checks that the
     * number of entries matches the group member count.
     *
     * Equal-split types bypass JSON validation entirely.
     */
    private fun validateCustomSplitPayloadFormat(
        splitType: SplitType,
        customSplitsJson: String?,
        memberCount: Int? = null
    ): GroupExpenseCreationResult.Error? {
        if (splitType == SplitType.EQUAL) {
            return null
        }

        if (!CustomSplitJsonCodec.isCanonicalJsonPayload(customSplitsJson)) {
            return GroupExpenseCreationResult.Error("Custom split payload must be valid JSON")
        }

        // S3: For CUSTOM split types (CUSTOM_AMOUNT, CUSTOM_PERCENT),
        // validate the number of entries matches the number of members.
        if (memberCount != null &&
            (splitType == SplitType.CUSTOM_AMOUNT || splitType == SplitType.CUSTOM_PERCENT)
        ) {
            val parsedSplits = CustomSplitJsonCodec.parseCanonicalJsonOrNull(customSplitsJson)
            if (parsedSplits == null || parsedSplits.size != memberCount) {
                return GroupExpenseCreationResult.Error(
                    "CUSTOM split must have exactly $memberCount entries (one per member), " +
                        "but got ${parsedSplits?.size ?: 0}"
                )
            }
        }

        return null
    }

    private suspend fun mapAddMemberConstraintError(
        exception: SQLiteConstraintException,
        groupId: Long,
        name: String
    ): GroupValidationError {
        val constraintMessage = exception.message.orEmpty()
        return when {
            "index_group_members_groupId_name" in constraintMessage ||
                "group_members.groupId, group_members.name" in constraintMessage -> {
                val existingMemberId = memberDao.getAllForGroup(groupId)
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.id
                    ?: 0L
                GroupValidationError.UserAlreadyMember(existingMemberId)
            }

            "index_group_members_groupId_currentUser" in constraintMessage ||
                "group_members.groupId" in constraintMessage && "isCurrentUser" in constraintMessage -> {
                val currentUserId = memberDao.getCurrentUser(groupId)?.id ?: 0L
                GroupValidationError.CurrentUserAlreadyExists(currentUserId)
            }

            else -> GroupValidationError.Unknown(exception.message)
        }
    }
}
