package com.yourname.expensetracker.data.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.OwnershipUpdateResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.BulkChangedField
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber
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
 *
 * TODO (PR-E15): Create GroupLifecycleCoordinator for group lifecycle methods:
 * createGroup(), addMember(), removeMember(), addExpense(), archiveGroup(), deleteGroupPermanently().
 * Rules: currentUserGroupKey invariant, deferred side effects, lifecycle event logging.
 *
 * NEXT STEPS:
 * 1. Create GroupLifecycleCoordinator with: createGroup(), addMember(), removeMember()
 * 2. Enforce single-currency group policy (reject expense if currency != group.currency)
 * 3. Implement recordSettlement() with persistent settlement records
 * 4. Route all group deletions through archiveGroup() for soft-delete
 * 5. Add currentUserGroupKey invariant enforcement (G01): when inserting members,
 *    automatically set groupId = currentUserGroupKey on the current-user member.
 * 6. G02 (DONE in PR6): PostCommitActionBatch collected from TransactionLifecycleCoordinator
 *    via DB-only APIs (createExpenseDbOnlyV2, updateOwnershipDbOnlyV2) and run after
 *    outer transaction commit via PostCommitActionRunner.
 * 7. Reject mixed-currency settlements (G04) or convert to group defaultCurrency.
 * 8. Add lifecycle event logging (audit table) for all group mutations.
 *
 * ── GroupLifecycleCoordinator Implementation Plan (cont.) ──────────────────
 * 9. Settlement persistence: new table `group_settlements` with columns:
 *    id, groupId, fromMemberId, toMemberId, amount, currency, settledAt, notes.
 * 10. Balance computation: SplitCalculator should compute net balances including
 *     settled amounts, using SettlementDao to factor in past settlements.
 * 11. Validation rules for removeMember:
 *     - Verify member has no outstanding balance before removal.
 *     - Block removal of last currentUser (must transfer ownership first via
 *       transferOwnership method on GroupLifecycleCoordinator).
 *     - Fire GROUP_MEMBER_REMOVED lifecycle event.
 * 12. Hard-delete guard (G08): permanentlyDeleteGroup() should require an explicit
 *     boolean flag `confirmPermanentDelete: Boolean` to prevent accidental data loss.
 * 13. Side-effect dispatch (G02, DONE in PR6): PostCommitActionBatch is collected from
 *     DB-only APIs and run after outer transaction commit via PostCommitActionRunner.
 *     No side effects are dispatched inside transactions — rollback means no actions.
 * Design goals:
 * - GroupLifecycleCoordinator wraps GroupTransactionCoordinator + domain services
 *   to provide a single entry point for all group lifecycle operations.
 * - Each method is idempotent where possible and emits lifecycle events for audit.
 *
 * Methods to implement:
 * 1. createGroup(name, description, currency, members) → GroupCreationResult
 *    - Validates member count >= 2 and exactly 1 currentUser
 *    - Sets defaultCurrency, initializes createdAt/updatedAt timestamps
 *    - Delegates DB work to GroupTransactionCoordinator.createGroupWithMembers()
 *    - Fires LifecycleEvent GROUP_CREATED on success
 *
 * 2. addMember(groupId, name, email, isCurrentUser) → Result<Unit, GroupValidationError>
 *    - Checks group is active before proceeding
 *    - Verifies no duplicate member name within the group
 *    - Enforces single currentUser invariant
 *    - Delegates DB work to GroupTransactionCoordinator.addMemberToGroup()
 *    - Fires LifecycleEvent GROUP_MEMBER_ADDED on success
 *
 * 3. removeMember(groupId, memberId) → Result<Unit, GroupValidationError>
 *    - Verifies member exists and belongs to the group
 *    - Block removal of last currentUser (must transfer ownership first)
 *    - Settles outstanding balances before removal
 *    - Fires LifecycleEvent GROUP_MEMBER_REMOVED on success
 *
 * 4. addExpense(groupId, description, amount, paidById, ...) → GroupExpenseCreationResult
 *    - Validates single-currency policy: expense.currency must match group.currency
 *      (or group.defaultCurrency if currency is null)
 *    - Delegates to GroupTransactionCoordinator.addExpenseWithLink() or addExpenseToGroup()
 *    - Fires LifecycleEvent GROUP_EXPENSE_ADDED on success
 *
 * 5. archiveGroup(groupId) → Boolean
 *    - Verifies group exists and is active
 *    - Sets isActive = false (soft delete)
 *    - Fires LifecycleEvent GROUP_ARCHIVED on success
 *
 * 6. deleteGroupPermanently(groupId) → Boolean
 *    - Requires explicit confirmation flag (prevents accidental hard delete)
 *    - Calls GroupTransactionCoordinator.permanentlyDeleteGroup()
 *    - Warns about orphaned linked expenses (J2)
 *    - Fires LifecycleEvent GROUP_DELETED on success
 *
 * 7. recordSettlement(groupId, fromMemberId, toMemberId, amount, currency) → SettlementResult
 *    - Creates persistent settlement record (new table or repurposed GroupExpense)
 *    - Updates member balances accordingly
 *    - Fires LifecycleEvent SETTLEMENT_RECORDED on success
 *
 * State invariants:
 * - currentUserGroupKey: at most one member per group has isCurrentUser=true
 * - deferred side effects: PostCommitActionBatch collected from DB-only APIs
 *   and run after outer transaction commit via PostCommitActionRunner
 * - lifecycle event logging: each mutation writes a GroupLifecycleEvent to audit table
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@Singleton
class GroupTransactionCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val expenseDao: ExpenseDao,
    private val transactionLifecycleEventWriter: com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEventWriter,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val transactionSideEffectPlanner: TransactionSideEffectPlanner,
    private val postCommitActionRunner: PostCommitActionRunner,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DomainCoordinator {

    private data class GroupMutationTxOutcome(
        val result: GroupExpenseCreationResult,
        val postCommitActions: PostCommitActionBatch
    )

    /**
     * P2-NEW-10: Internal rollback signal for createSystemExpenseAndLinkToGroup.
     * Must be thrown inside [database.withTransaction] after the system expense
     * has already been created by the nested coordinator call. Throwing ensures
     * Room rolls back the whole outer transaction (including the expense).
     *
     * If the method returned a normal [GroupExpenseCreationResult.Error] instead,
     * Room would commit the outer transaction and leave an orphan system expense.
     */
    private class GroupExpenseAtomicRollback(
        val publicResult: GroupExpenseCreationResult
    ) : RuntimeException()

    private data class GroupDeleteTxOutcome(
        val success: Boolean,
        val postCommitActions: PostCommitActionBatch
    )

    private companion object {
        const val INVALID_EQUAL_SPLIT_MESSAGE =
            "Equal split is invalid for the selected date because the payer is excluded or no participant qualifies"
        const val LINKED_EXPENSE_ALREADY_ATTACHED_MESSAGE =
            "Linked system expense is already attached to a group expense"
    }

    /**
     * Runs a [PostCommitActionBatch] after the outer group transaction commits.
     *
     * Cancellation is rethrown; non-cancellation failures are logged but do not
     * propagate — the primary group mutation has already committed.
     */
    private suspend fun runGroupPostCommitActions(batch: PostCommitActionBatch) {
        if (batch.actions.isEmpty()) return
        try {
            postCommitActionRunner.run(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Group post-commit side effects failed")
        }
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
        members: List<GroupMember>,
        onInsideTransaction: suspend (groupId: Long) -> Unit
    ): GroupCreationResult = withContext(ioDispatcher) {
        try {
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.createGroupWithMembers")
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
                val membersWithGroupId = members.map { member ->
                    // Normalize joinedAt — if <= 0, set to now (PR1: no-schema invariant hardening)
                    val normalizedMember = if (member.joinedAt <= 0L) member.copy(joinedAt = timeProvider.now()) else member
                    normalizedMember.copy(groupId = newGroupId).let { m ->
                        // G01: currentUserGroupKey invariant — currentUser=true members
                        // get currentUserGroupKey set to the group's primary key.
                        if (m.isCurrentUser) m.copy(currentUserGroupKey = newGroupId) else m
                    }
                }
                memberDao.insertAll(membersWithGroupId)
                
                onInsideTransaction(newGroupId)
                
                newGroupId
            }
            
            GroupCreationResult.Success(groupId)
        } catch (e: CancellationException) {
            throw e
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
        isCurrentUser: Boolean,
        onInsideTransaction: suspend (memberId: Long) -> Unit
    ): Result<Unit, GroupValidationError> = withContext(ioDispatcher) {
        try {
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.addMemberToGroup")
            database.withTransaction {
                // Verify group exists and is active inside the transaction
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction Result.Error(GroupValidationError.InvalidGroup)
                }

                val members = memberDao.getActiveMembersForGroup(groupId)
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
                    isCurrentUser = isCurrentUser,
                    joinedAt = timeProvider.now()
                ).let { m ->
                    // G01: currentUserGroupKey invariant — isCurrentUser=true members
                    // get currentUserGroupKey set to the owning group's primary key.
                    if (isCurrentUser) m.copy(currentUserGroupKey = groupId) else m
                }

                val memberId = memberDao.insert(member)
                onInsideTransaction(memberId)
                Result.Success(Unit)
            }
        } catch (e: SQLiteConstraintException) {
            Result.Error(mapAddMemberConstraintError(e, groupId, name))
        } catch (e: CancellationException) {
            throw e
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
        date: Long,
        idempotencyKey: String?
    ): GroupExpenseCreationResult = withContext(ioDispatcher) {
        try {
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.addExpenseToGroup")
            database.withTransaction {
                // Verify group exists and is active
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction GroupExpenseCreationResult.Error("Group not found or inactive")
                }
                
                // Verify payer is an active member of the group
                val members = memberDao.getActiveMembersForGroup(groupId)
                if (members.none { it.id == paidById }) {
                    return@withTransaction GroupExpenseCreationResult.Error("Payer is not a member of this group")
                }

                // J1 + S3: Validate custom split payload format for non-EQUAL split types
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

                // E4-005: Enforce single-currency group policy at low level
                if (currency != null && currency != group.defaultCurrency) {
                    return@withTransaction GroupExpenseCreationResult.Error(
                        "Expense currency '$currency' does not match group currency '${group.defaultCurrency}'. Groups are single-currency."
                    )
                }

                // PR8: Idempotency key — if duplicate exists, return existing expense
                val key = idempotencyKey ?: "group_expense:${groupId}:${java.util.UUID.randomUUID()}"
                val existing = groupExpenseDao.getByIdempotencyKey(groupId, key)
                if (existing != null) {
                    return@withTransaction GroupExpenseCreationResult.Success(
                        groupExpenseId = existing.id,
                        expenseId = existing.expenseId ?: 0L
                    )
                }
                
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
                    customSplitsJson = customSplitsJson,
                    idempotencyKey = key
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GroupExpenseCreationResult.Error("Failed to add expense: ${e.message}")
        }
    }
    
    /**
     * Add an expense to a group and link it to a system expense.
     * This is the proper way to create group expenses that appear in transaction history.
     * B.4 Batch 2: Wrapped in database.withTransaction so validation + insert
     * are atomic (Risk 2).
     *
     * PostCommitActionBatch is collected via updateOwnershipDbOnlyV2 and run after
     * the outer group transaction commit. No side effects are dispatched inside the
     * transaction — rollback means no action execution.
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
        date: Long,
        idempotencyKey: String?
    ): GroupExpenseCreationResult = withContext(ioDispatcher) {
        try {
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.addExpenseWithLink")
            val correlationId = CorrelationIds.newId()
            val outcome = database.withTransaction {
                // Verify group exists and is active
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error("Group not found or inactive"),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                // Verify payer is an active member
                val members = memberDao.getActiveMembersForGroup(groupId)
                if (members.none { it.id == paidById }) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error("Payer is not a member of this group"),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                val customSplitError = validateCustomSplitPayloadFormat(
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    memberCount = members.size
                )
                if (customSplitError != null) {
                    return@withTransaction GroupMutationTxOutcome(
                        customSplitError,
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                val participantError = validateExpenseParticipants(
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
                )
                if (participantError != null) {
                    return@withTransaction GroupMutationTxOutcome(
                        participantError,
                        PostCommitActionBatch.empty(correlationId)
                    )
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
                ) ?: return@withTransaction GroupMutationTxOutcome(
                    GroupExpenseCreationResult.Error(
                        "Current user member not found or share could not be calculated"
                    ),
                    PostCommitActionBatch.empty(correlationId)
                )

                val existingExpense = expenseDao.getById(systemExpenseId)
                    ?: return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error("Linked system expense not found"),
                        PostCommitActionBatch.empty(correlationId)
                    )

                // G03: Reject mixed-currency group expenses — groups are single-currency.
                if (existingExpense.currency != group.defaultCurrency) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error(
                            "Expense currency '${existingExpense.currency}' does not match group currency '${group.defaultCurrency}'. Groups are single-currency."
                        ),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                if (groupExpenseDao.getGroupExpenseForExpense(systemExpenseId) != null) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error(
                            LINKED_EXPENSE_ALREADY_ATTACHED_MESSAGE
                        ),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                // PR8: Idempotency key — if duplicate exists, return existing expense
                val key = idempotencyKey ?: "group_expense:${groupId}:${java.util.UUID.randomUUID()}"
                val existing = groupExpenseDao.getByIdempotencyKey(groupId, key)
                if (existing != null) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Success(
                            groupExpenseId = existing.id,
                            expenseId = existing.expenseId ?: 0L
                        ),
                        PostCommitActionBatch.empty(correlationId)
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
                    customSplitsJson = customSplitsJson,
                    idempotencyKey = key
                )

                val groupExpenseId = groupExpenseDao.insert(expense)

                if (groupExpenseId <= 0) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error("Failed to create group expense"),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                // Ownership update via DB-only API — no side effects inside transaction
                val ownershipMutation =
                    transactionLifecycleCoordinator.updateOwnershipDbOnlyV2(
                        expenseId = systemExpenseId,
                        isNotMine = false,
                        ownerName = null,
                        isSharedExpense = true,
                        sharedWithName = null,
                        mySharePercentage = null,
                        myShareAmount = currentUserShare,
                        reason = "Group expense linking: set shared-expense metadata",
                        source = "GROUP_EXPENSE",
                        correlationId = correlationId
                    )

                // P2-NEW-12: Verify ownership update succeeded before committing group link
                when (ownershipMutation.value) {
                    is OwnershipUpdateResult.NotFound -> {
                        throw GroupExpenseAtomicRollback(
                            GroupExpenseCreationResult.Error("Ownership update failed: expense not found")
                        )
                    }
                    is OwnershipUpdateResult.Updated, is OwnershipUpdateResult.NoOp -> {
                        // P2-NEW-12 residual: Reload and verify final row fields
                        val updatedRow = expenseDao.getById(systemExpenseId)
                            ?: throw GroupExpenseAtomicRollback(
                                GroupExpenseCreationResult.Error("Expense missing after ownership update")
                            )
                        if (!updatedRow.isSharedExpense) {
                            throw GroupExpenseAtomicRollback(
                                GroupExpenseCreationResult.Error("Ownership update did not set isSharedExpense")
                            )
                        }
                        if (updatedRow.isNotMine) {
                            throw GroupExpenseAtomicRollback(
                                GroupExpenseCreationResult.Error("Ownership update left isNotMine=true")
                            )
                        }
                        if (currentUserShare != null &&
                            updatedRow.myShareAmount != null &&
                            kotlin.math.abs(updatedRow.myShareAmount!! - currentUserShare) > 0.0001
                        ) {
                            throw GroupExpenseAtomicRollback(
                                GroupExpenseCreationResult.Error(
                                    "myShareAmount mismatch: expected $currentUserShare, got ${updatedRow.myShareAmount}"
                                )
                            )
                        }
                    }
                }

                GroupMutationTxOutcome(
                    result = GroupExpenseCreationResult.Success(
                        groupExpenseId = groupExpenseId,
                        expenseId = systemExpenseId
                    ),
                    postCommitActions = ownershipMutation.postCommitActions
                )
            }
            if (outcome.result is GroupExpenseCreationResult.Success) {
                runGroupPostCommitActions(outcome.postCommitActions)
            }
            outcome.result
        } catch (e: CancellationException) {
            throw e
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
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.deleteGroup")
            groupDao.archiveGroup(groupId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Archive a group by setting isActive = false instead of hard-deleting.
     * Preserves all expense history for audit purposes.
     */
    override suspend fun archiveGroup(groupId: Long, onInsideTransaction: suspend () -> Unit): Boolean = withContext(ioDispatcher) {
        try {
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.archiveGroup")
            database.withTransaction {
                groupDao.archiveGroup(groupId)
                onInsideTransaction()
            }
            true
        } catch (e: CancellationException) {
            throw e
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
     *
     * G08: This hard-delete bypasses the lifecycle coordinator and writes
     * no audit events. Eventually all group deletions should route through
     * archiveGroup() for soft-delete (isActive=false), which preserves the
     * group record and allows future restore. Hard-delete should require an
     * explicit confirmation flag and always write a GROUP_PERMANENTLY_DELETED
     * lifecycle event.
     *
     * TODO (G08): Route through archiveGroup() for soft-delete or ensure all
     *             deletions write lifecycle events to maintain audit trail.
     */
    override suspend fun permanentlyDeleteGroup(groupId: Long, onInsideTransaction: suspend () -> Unit): Boolean = withContext(ioDispatcher) {
        try {
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.permanentlyDeleteGroup")
            deleteGroupAtomic(groupId, onInsideTransaction)
            true
        } catch (e: CancellationException) {
            throw e
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
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.createGroupWithMembersAtomic")
        validateSingleCurrentUser(members)

        return database.withTransaction {
            // Insert group first
            val groupId = groupDao.insert(group)
            
            // If this fails, group insertion rolls back
            val membersWithGroupId = members.map { member ->
                // Normalize joinedAt — if <= 0, set to now (PR1: no-schema invariant hardening)
                val normalizedMember = if (member.joinedAt <= 0L) member.copy(joinedAt = timeProvider.now()) else member
                normalizedMember.copy(groupId = groupId).let { m ->
                    // G01: currentUserGroupKey invariant — currentUser=true members
                    // get currentUserGroupKey set to the group's primary key.
                    if (m.isCurrentUser) m.copy(currentUserGroupKey = groupId) else m
                }
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
        notes: String?,
        idempotencyKey: String?
    ): GroupExpenseCreationResult = withContext(ioDispatcher) {
        try {
            writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup")
            val correlationId = CorrelationIds.newId()
            val txOutcome = database.withTransaction {
                // 1. Validate group exists and is active
                val group = groupDao.getById(groupId)
                if (group == null || !group.isActive) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error("Group not found or inactive"),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                // 2. Validate payer is an active member of the group
                val members = memberDao.getActiveMembersForGroup(groupId)
                if (members.none { it.id == paidById }) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error("Payer is not a member of this group"),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                // E4-005: Enforce single-currency group policy at low level
                if (currency != group.defaultCurrency) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error(
                            "Expense currency '$currency' does not match group currency '${group.defaultCurrency}'. Groups are single-currency."
                        ),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                val customSplitError = validateCustomSplitPayloadFormat(
                    splitType = splitType,
                    customSplitsJson = customSplitsJson,
                    memberCount = members.size
                )
                if (customSplitError != null) {
                    return@withTransaction GroupMutationTxOutcome(
                        customSplitError,
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                val participantError = validateExpenseParticipants(
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
                )
                if (participantError != null) {
                    return@withTransaction GroupMutationTxOutcome(
                        participantError,
                        PostCommitActionBatch.empty(correlationId)
                    )
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
                )
                if (currentUserShare == null) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error(
                            "Current user member not found or share could not be calculated"
                        ),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                // PR8: Idempotency key — if duplicate exists, return existing expense
                val key = idempotencyKey ?: "group_expense:${groupId}:${java.util.UUID.randomUUID()}"
                val existing = groupExpenseDao.getByIdempotencyKey(groupId, key)
                if (existing != null) {
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Success(
                            groupExpenseId = existing.id,
                            expenseId = existing.expenseId ?: 0L
                        ),
                        PostCommitActionBatch.empty(correlationId)
                    )
                }

                // 3. Create system expense via TransactionLifecycleCoordinator
                // Side effects are returned as PostCommitActionBatch and run after outer commit
                val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(
                    CreateExpenseRequest(
                        merchant = description,
                        amount = amount,
                        currency = expenseCurrency,
                        date = date,
                        transactionType = transactionType,
                        source = ExpenseSource.GROUP_EXPENSE,
                        groupId = groupId,
                        notes = notes ?: "Group expense via ${payer.name}",
                        isManualEntry = true,
                        isSharedExpense = true,
                        myShareAmount = currentUserShare,
                        correlationId = correlationId
                    )
                )

                when (val createResult = mutation.value) {
                    is CreateExpenseResult.Created -> {
                        val systemExpenseId = createResult.expenseId

                        if (groupExpenseDao.getGroupExpenseForExpense(systemExpenseId) != null) {
                            throw GroupExpenseAtomicRollback(
                                GroupExpenseCreationResult.Error(LINKED_EXPENSE_ALREADY_ATTACHED_MESSAGE)
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
                            customSplitsJson = customSplitsJson,
                            idempotencyKey = key
                        )

                        val groupExpenseId = groupExpenseDao.insert(groupExpense)
                        if (groupExpenseId <= 0) {
                            throw GroupExpenseAtomicRollback(
                                GroupExpenseCreationResult.Error("Failed to create group expense link")
                            )
                        }

                        GroupMutationTxOutcome(
                            GroupExpenseCreationResult.Success(
                                groupExpenseId = groupExpenseId,
                                expenseId = systemExpenseId
                            ),
                            mutation.postCommitActions
                        )
                    }
                    is CreateExpenseResult.DuplicateSkipped ->
                        GroupMutationTxOutcome(
                            GroupExpenseCreationResult.Error(
                                "Duplicate expense: ${createResult.reason}"
                            ),
                            PostCommitActionBatch.empty(correlationId)
                        )
                    is CreateExpenseResult.ValidationFailed ->
                        GroupMutationTxOutcome(
                            GroupExpenseCreationResult.Error(
                                createResult.errors.joinToString("; ")
                            ),
                            PostCommitActionBatch.empty(correlationId)
                        )
                    is CreateExpenseResult.InsertConflict ->
                        GroupMutationTxOutcome(
                            GroupExpenseCreationResult.Error(
                                "Insert conflict: dedupeKey=${createResult.dedupeKey}"
                            ),
                            PostCommitActionBatch.empty(correlationId)
                        )
                    is CreateExpenseResult.Error ->
                        GroupMutationTxOutcome(
                            GroupExpenseCreationResult.Error(
                                "Failed to create system expense: ${createResult.exception.message}"
                            ),
                            PostCommitActionBatch.empty(correlationId)
                        )
                }
            }
            if (txOutcome.result is GroupExpenseCreationResult.Success) {
                runGroupPostCommitActions(txOutcome.postCommitActions)
            }
            txOutcome.result
        } catch (e: CancellationException) {
            throw e
        } catch (e: GroupExpenseAtomicRollback) {
            e.publicResult
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
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.addExpenseToGroupAtomic")
        return database.withTransaction {
            // E4-005: Enforce single-currency group policy at low level
            val group = groupDao.getById(groupExpense.groupId)
            if (group != null && groupExpense.currency != group.defaultCurrency) {
                throw IllegalArgumentException(
                    "Expense currency '${groupExpense.currency}' does not match group currency '${group.defaultCurrency}'. Groups are single-currency."
                )
            }
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
    suspend fun deleteGroupAtomic(groupId: Long, onInsideTransaction: suspend () -> Unit = {}) {
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.deleteGroupAtomic")
        // Collect linked expense IDs before deleting
        val linkedExpenseIds = groupExpenseDao.getExpensesForGroupOnce(groupId).mapNotNull { it.expenseId }
        val correlationId = CorrelationIds.newId()

        database.withTransaction {
            // Delete expenses first (child table)
            groupExpenseDao.deleteAllForGroup(groupId)

            // Delete members
            // TODO (G09): Add validation before member delete — check that:
            // 1. Member has no outstanding balance (unsettled debts)
            // 2. Member is not the last currentUser (block or transfer ownership first)
            // 3. A GROUP_MEMBER_REMOVED lifecycle event is emitted for audit trail
            memberDao.deleteAllForGroup(groupId)

            // Delete group last (parent table)
            val group = groupDao.getGroupById(groupId)
            group?.let { groupDao.delete(it) }

            // P2-CURRENT-008 FIX: Clear orphaned shared-expense flags inside transaction
            linkedExpenseIds.forEach { expenseId ->
                expenseDao.clearSharedExpenseFlags(expenseId)
            }

            // Write BULK_UPDATED event atomically inside the transaction
            transactionLifecycleEventWriter.write(
                com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEvent(
                    expenseId = null,
                    eventType = LifecycleEventType.BULK_UPDATED.name,
                    source = "GROUP_HARD_DELETE",
                    actor = "system:group_transaction_coordinator",
                    correlationId = correlationId,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("groupId", groupId.toString())
                        .put("count", linkedExpenseIds.size)
                        .put("source", "GROUP_HARD_DELETE")
                        .build(),
                    reason = "Group hard-delete cleared shared expense flags for ${linkedExpenseIds.size} expenses"
                )
            )

            onInsideTransaction()
        }

        // After commit: plan and run bulk updated actions
        if (linkedExpenseIds.isNotEmpty()) {
            val actions = transactionSideEffectPlanner.planBulkUpdated(
                source = "GROUP_HARD_DELETE",
                affectedCount = linkedExpenseIds.size,
                correlationId = correlationId,
                changedFields = setOf(
                    BulkChangedField.OWNERSHIP,
                    BulkChangedField.AMOUNT_EFFECTIVE
                )
            )
            runGroupPostCommitActions(actions)
        }
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
                val existingMemberId = memberDao.getActiveMembersForGroup(groupId)
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
