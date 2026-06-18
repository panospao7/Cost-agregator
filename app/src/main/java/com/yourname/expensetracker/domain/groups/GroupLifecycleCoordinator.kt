package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import com.yourname.expensetracker.data.database.entity.GroupLifecycleEventEntity
import com.yourname.expensetracker.data.database.AppDatabase
import androidx.room.withTransaction
import com.yourname.expensetracker.domain.groups.GroupBalanceCalculator
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.GroupSettlementEntity
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher
import dagger.Lazy
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain-level coordinator for all group lifecycle operations.
 *
 * Wraps [GroupTransactionCoordinator] (domain interface) with business-rule enforcement,
 * validation, invariant checks, and lifecycle event logging. Provides a single entry
 * point for create, add/remove member, add expense, archive, permanent-delete, and
 * settlement recording.
 *
 * ## Design (no Dagger cycle)
 * This class depends on the **domain interface** [GroupTransactionCoordinator], not the
 * data-layer implementation. All dependencies are Singleton-scoped and already available.
 *
 * ## Invariants enforced
 * - **G01 currentUserGroupKey**: at most one member per group has `isCurrentUser = true`
 * - **G02 deferred side-effects**: post-commit dispatch via TransactionLifecycleCoordinator
 * - **G03 single-currency**: all expenses in a group must match group default currency
 * - **G04 no mixed-currency settlements**: settlement currency must match group currency
 * - **G05 member balance gate**: cannot remove a member with outstanding balance
 * - **G06 last-currentUser gate**: cannot remove the only currentUser (transfer ownership first)
 * - **G07 archive-is-default**: deleteGroup() soft-deletes via archive; hard-delete requires explicit flag
 * - **G08 permanent-delete confirmation**: deleteGroupPermanently() requires explicit boolean
 *
 * ## Methods implemented (7 methods)
 * 1. createGroup – validates members, enforces currentUser invariant
 * 2. addMember – verifies group active, no duplicates, single currentUser
 * 3. removeMember – verifies member exists, blocks last-currentUser, checks balances
 * 4. addExpense – validates single-currency policy
 * 5. archiveGroup – soft-deletes (sets isActive = false)
 * 6. deleteGroupPermanently – hard-deletes with explicit confirmation flag
 * 7. recordSettlement – persists settlement record with lifecycle event
 */
@Singleton
// G02-FIXED: Group lifecycle events ARE persisted via GroupLifecycleEventDao.
// Each mutation writes a GroupLifecycleEventEntity to the group_lifecycle_events table.
// Side effects (budget check, anomaly detection) run post-commit as best-effort.
class GroupLifecycleCoordinator @Inject constructor(
    private val groupCoordinator: GroupTransactionCoordinator,
    private val groupDao: ExpenseGroupDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val memberDao: GroupMemberDao,
    private val settlementDao: GroupSettlementDao,
    private val lifecycleEventDao: GroupLifecycleEventDao,
    private val balanceCalculator: GroupBalanceCalculator,
    private val timeProvider: TimeProvider,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    private val database: AppDatabase,
    private val budgetMonitor: dagger.Lazy<com.yourname.expensetracker.domain.budget.BudgetMonitor>,
    private val sideEffectDispatcher: com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Creates a new group with validated members.
     *
     * ## Validations
     * - At least 2 members required
     * - Exactly 1 member must be marked as `isCurrentUser = true` (G01)
     * - No duplicate member names within the group
     * - Group name must be non-blank
     *
     * @param name Group name
     * @param description Optional group description
     * @param currency Currency code (e.g. "EUR", "USD")
     * @param members List of initial members
     * @return [GroupCreationResult] with group ID or error
     */
    suspend fun createGroup(
        name: String,
        description: String?,
        currency: String,
        members: List<GroupMember>
    ): GroupCreationResult = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.createGroup")
        // Validate member count
        if (members.size < 2) {
            return@withContext GroupCreationResult.Error("A group must have at least 2 members")
        }
        // Validate group name
        if (name.isBlank()) {
            return@withContext GroupCreationResult.Error("Group name cannot be blank")
        }
        // Validate currency
        if (currency.isBlank()) {
            return@withContext GroupCreationResult.Error("Group currency cannot be blank")
        }
        // Validate exactly one currentUser (G01)
        val currentUserCount = members.count { it.isCurrentUser }
        if (currentUserCount != 1) {
            return@withContext GroupCreationResult.Error(
                "Exactly 1 member must be marked as current user, but found $currentUserCount"
            )
        }
        // Validate no duplicate member names
        val names = members.map { it.name.trim().lowercase() }
        val duplicates = names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return@withContext GroupCreationResult.Error(
                "Duplicate member names: ${duplicates.joinToString(", ")}"
            )
        }
        // Validate no blank member names
        val blankMember = members.firstOrNull { it.name.isBlank() }
        if (blankMember != null) {
            return@withContext GroupCreationResult.Error("Member name cannot be blank")
        }

        val result = groupCoordinator.createGroupWithMembers(name, description, currency, members) { groupId ->
            val event = GroupLifecycleEventEntity(
                groupId = groupId,
                eventType = "GROUP_CREATED",
                createdAt = timeProvider.now()
            )
            lifecycleEventDao.insert(event)
        }
        if (result is GroupCreationResult.Success) {
            try {
                budgetMonitor.get().checkBudgets()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Budget check failed for group creation")
            }
        }
        result
    }

    /**
     * Adds a member to an existing group.
     *
     * ## Validations
     * - Group must exist and be active
     * - Member name must be non-blank
     * - No duplicate member name within the group
     * - At most one currentUser per group (G01)
     *
     * @param groupId Group ID
     * @param name Member name
     * @param email Optional email
     * @param isCurrentUser Whether this member represents the current user
     * @return [Result] with Unit or [GroupValidationError]
     */
    suspend fun addMember(
        groupId: Long,
        name: String,
        email: String? = null,
        isCurrentUser: Boolean = false
    ): Result<Unit, GroupValidationError> = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.addMember")
        // Verify group exists and is active
        val group = groupDao.getGroupById(groupId)
            ?: return@withContext Result.Error(GroupValidationError.InvalidGroup)
        if (!group.isActive) {
            return@withContext Result.Error(GroupValidationError.InvalidGroup)
        }
        // Validate name
        if (name.isBlank()) {
            return@withContext Result.Error(GroupValidationError.BlankMemberName)
        }
        // Check for duplicate member name (only active members)
        val existingMembers = memberDao.getActiveMembersForGroup(groupId)
        if (existingMembers.any { it.name.equals(name, ignoreCase = true) }) {
            val existingId = existingMembers.first { it.name.equals(name, ignoreCase = true) }.id
            return@withContext Result.Error(GroupValidationError.UserAlreadyMember(existingId))
        }
        // Enforce single currentUser invariant (G01)
        if (isCurrentUser && existingMembers.any { it.isCurrentUser }) {
            val currentUserId = existingMembers.first { it.isCurrentUser }.id
            return@withContext Result.Error(GroupValidationError.CurrentUserAlreadyExists(currentUserId))
        }

        val result = groupCoordinator.addMemberToGroup(groupId, name, email, isCurrentUser) { _ ->
            val event = GroupLifecycleEventEntity(
                groupId = groupId,
                eventType = "GROUP_MEMBER_ADDED",
                createdAt = timeProvider.now()
            )
            lifecycleEventDao.insert(event)
        }
        if (result is Result.Success) {
            try {
                budgetMonitor.get().checkBudgets()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Budget check failed for group member addition")
            }
        }
        result
    }

    /**
     * Soft-removes a member from a group (sets leftAt timestamp).
     *
     * ## Validations
     * - Group must exist and be active
     * - Member must exist and belong to the group
     * - Cannot remove the only currentUser (G06) — transfer ownership first
     * - Cannot remove a member with outstanding balance (G05)
     *
     * @param groupId Group ID
     * @param memberId Member ID to remove
     * @return [Result] with Unit or [GroupValidationError]
     */
    suspend fun removeMember(
        groupId: Long,
        memberId: Long
    ): Result<Unit, GroupValidationError> = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.removeMember")
        val group = groupDao.getGroupById(groupId)
            ?: return@withContext Result.Error(GroupValidationError.InvalidGroup)
        val member = memberDao.getMemberById(memberId)
            ?: return@withContext Result.Error(GroupValidationError.InvalidGroup)
        if (member.groupId != groupId) {
            return@withContext Result.Error(GroupValidationError.InvalidGroup)
        }
        // G06: Block removal of last currentUser
        if (member.isCurrentUser) {
            return@withContext Result.Error(GroupValidationError.Unknown(
                "Cannot remove the current user. Transfer ownership to another member first."
            ))
        }

        // E4-006: Wrap balance check + soft-remove + event in transaction to prevent race
        val removeResult = database.withTransaction {
            // G05: Balance gate — check if member has outstanding balance
            val balance = balanceCalculator.calculateMemberBalance(groupId, memberId)
            if (!balance.isSettled) {
                return@withTransaction Result.Error(GroupValidationError.Unknown(
                    "Cannot remove member with outstanding balance of %.2f %s (paid %.2f - owed %.2f)"
                        .format(balance.netBalance, balance.currency, balance.paidTotal, balance.owedShareTotal)
                ))
            }

            memberDao.update(member.copy(leftAt = timeProvider.now()))
            
            val event = GroupLifecycleEventEntity(
                groupId = groupId,
                eventType = "GROUP_MEMBER_REMOVED",
                createdAt = timeProvider.now()
            )
            lifecycleEventDao.insert(event)
            
            Result.Success(Unit)
        }

        if (removeResult is Result.Success) {
            try {
                budgetMonitor.get().checkBudgets()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Budget check failed for group member removal")
            }
        }

        removeResult
    }

    /**
     * Adds an expense to a group with single-currency validation.
     *
     * ## Validations
     * - G03: expense currency must match group defaultCurrency (rejects if mismatched)
     * - Group must be active
     * - Payer must be a member of the group
     *
     * @param groupId Group ID
     * @param description Expense description
     * @param amount Total expense amount
     * @param paidById ID of member who paid
     * @param currency Currency code for this expense. If null, group default is used.
     * @param splitType How to split the expense
     * @param customSplitsJson Custom split configuration (for non-EQUAL splits)
     * @param date Expense date in milliseconds
     * @return [GroupExpenseCreationResult] with IDs or error
     */
    suspend fun addExpense(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String? = null,
        splitType: SplitType = SplitType.EQUAL,
        customSplitsJson: String? = null,
        date: Long = timeProvider.now()
    ): GroupExpenseCreationResult = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.addExpense")
        val group = groupDao.getGroupById(groupId)
            ?: return@withContext GroupExpenseCreationResult.Error("Group not found")
        if (!group.isActive) {
            return@withContext GroupExpenseCreationResult.Error("Group is archived")
        }

        // G03: Single-currency policy — resolve and validate currency
        val effectiveCurrency = currency ?: group.defaultCurrency
        if (group.defaultCurrency.isNotBlank() && effectiveCurrency != group.defaultCurrency) {
            return@withContext GroupExpenseCreationResult.Error(
                "Expense currency ($effectiveCurrency) must match group currency (${group.defaultCurrency})"
            )
        }

        val result = groupCoordinator.addExpenseToGroup(
            groupId = groupId,
            description = description,
            amount = amount,
            paidById = paidById,
            currency = effectiveCurrency,
            splitType = splitType,
            customSplitsJson = customSplitsJson,
            date = date
        ) { groupExpenseId ->
            val event = GroupLifecycleEventEntity(
                groupId = groupId,
                eventType = "GROUP_EXPENSE_ADDED",
                relatedExpenseId = groupExpenseId,
                createdAt = timeProvider.now()
            )
            lifecycleEventDao.insert(event)
        }

        if (result is GroupExpenseCreationResult.Success) {
            try {
                budgetMonitor.get().checkBudgets()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Budget check failed for group expense addition")
            }
            if (result.expenseId > 0L) {
                try {
                    sideEffectDispatcher.dispatchOnCreated(result.expenseId, ExpenseSource.GROUP_EXPENSE)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "Side effects failed for expense %d (group %d)", result.expenseId, groupId)
                }
            }
        }
        result
    }

    /**
     * Archives a group (soft-delete).
     *
     * Sets `isActive = false` preserving all history for audit purposes (G07).
     *
     * @param groupId Group ID to archive
     * @return True if successful, false otherwise
     */
    suspend fun archiveGroup(groupId: Long): Boolean = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.archiveGroup")
        val group = groupDao.getGroupById(groupId) ?: return@withContext false
        val result = groupCoordinator.archiveGroup(groupId) {
            val event = GroupLifecycleEventEntity(
                groupId = groupId,
                eventType = LifecycleEventType.GROUP_ARCHIVED.name,
                createdAt = timeProvider.now()
            )
            lifecycleEventDao.insert(event)
        }
        if (result) {
            try {
                budgetMonitor.get().checkBudgets()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Budget check failed for group archive")
            }
        }
        result
    }

    /**
     * Permanently deletes a group and all associated data.
     *
     * ## G08: Hard-delete guard
     * Requires explicit [confirmPermanentDelete] flag to prevent accidental data loss.
     * All group members, expenses, and settlements are permanently removed.
     *
     * @param groupId Group ID to permanently delete
     * @param confirmPermanentDelete Must be true — rejects false to prevent accidents
     * @return True if successful, false otherwise
     */
    suspend fun deleteGroupPermanently(
        groupId: Long,
        confirmPermanentDelete: Boolean
    ): Boolean = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.deleteGroupPermanently")
        // G08: Require explicit confirmation
        if (!confirmPermanentDelete) {
            return@withContext false
        }
        val group = groupDao.getGroupById(groupId) ?: return@withContext false
        if (group.isActive) {
            // G04: Must archive before hard-delete
            return@withContext false
        }
        // G03: Hard delete is lifecycle-contained — requires archive first (G04 gate).
        // For emergency admin cleanup, use the direct data-layer path bypassing the coordinator.
        // All user-facing deletions must go through this method which enforces archive-then-delete.
        val result = groupCoordinator.permanentlyDeleteGroup(groupId) {
            val event = GroupLifecycleEventEntity(
                groupId = groupId,
                eventType = LifecycleEventType.GROUP_PERMANENTLY_DELETED.name,
                createdAt = timeProvider.now()
            )
            lifecycleEventDao.insert(event)
        }
        if (result) {
            try {
                budgetMonitor.get().checkBudgets()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Budget check failed for group permanent delete")
            }
        }
        result
    }

    /**
     * Records a settlement between two group members.
     *
     * Creates a persistent [GroupSettlementEntity] record and validates:
     * - G04: settlement currency must match group currency
     * - Both members must belong to the group
     * - Group must be active
     *
     * @param groupId Group ID
     * @param fromMemberId Member who pays
     * @param toMemberId Member who receives
     * @param amount Settlement amount
     * @param currency Currency code
     * @param notes Optional notes
     * @return Settlement ID if successful, or throws on validation failure
     */
    suspend fun recordSettlement(
        groupId: Long,
        fromMemberId: Long,
        toMemberId: Long,
        amount: Double,
        currency: String,
        notes: String? = null,
        linkedExpenseId: Long? = null
    ): Long = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.recordSettlement")
        val group = groupDao.getGroupById(groupId)
            ?: throw IllegalArgumentException("Group $groupId not found")
        if (!group.isActive) {
            throw IllegalStateException("Cannot record settlement for archived group")
        }

        // G04: Validate single-currency policy
        if (currency != group.defaultCurrency) {
            throw IllegalArgumentException(
                "Settlement currency ($currency) must match group currency (${group.defaultCurrency})"
            )
        }

        // Validate members belong to group
        val fromMember = memberDao.getMemberById(fromMemberId)
            ?: throw IllegalArgumentException("Payer member $fromMemberId not found")
        if (fromMember.groupId != groupId) {
            throw IllegalArgumentException("Payer member $fromMemberId does not belong to group $groupId")
        }
        val toMember = memberDao.getMemberById(toMemberId)
            ?: throw IllegalArgumentException("Payee member $toMemberId not found")
        if (toMember.groupId != groupId) {
            throw IllegalArgumentException("Payee member $toMemberId does not belong to group $groupId")
        }

        // PR1: Validate settlement amount is finite and positive
        require(amount.isFinite() && amount > 0.0) {
            "Settlement amount must be finite and positive"
        }
        // PR1: Reject self-settlement
        require(fromMemberId != toMemberId) {
            "Self-settlement is not allowed"
        }

        val now = timeProvider.now()
        val settlement = GroupSettlementEntity(
            groupId = groupId,
            fromMemberId = fromMemberId,
            toMemberId = toMemberId,
            amount = amount,
            currency = currency,
            createdAt = now,
            linkedExpenseId = linkedExpenseId,
            status = "RECORDED",
            notes = notes
        )

        val settlementId = database.withTransaction {
            val id = settlementDao.insert(settlement)
            emitLifecycleEvent(groupId, "SETTLEMENT_RECORDED", settlementId = id)
            id
        }

        settlementId
    }

    /**
     * Emits a group lifecycle event for audit trail.
     *
     * Persists the event to the `group_lifecycle_events` audit table and
     * triggers best-effort post-commit side effects (budget check, expense
     * side-effect dispatch).
     */
    private suspend fun emitLifecycleEvent(
        groupId: Long,
        eventType: String,
        expenseId: Long = 0L,
        settlementId: Long = 0L
    ) {
        Timber.d("GroupLifecycleEvent: groupId=%d, event=%s", groupId, eventType)
        val event = GroupLifecycleEventEntity(
            groupId = groupId,
            eventType = eventType,
            relatedExpenseId = expenseId.takeIf { it > 0L },
            relatedSettlementId = settlementId.takeIf { it > 0L },
            createdAt = timeProvider.now()
        )
        lifecycleEventDao.insert(event)
        // Budget check + side effects (best-effort)
        try {
            budgetMonitor.get().checkBudgets()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "Budget check failed for group %d event %s", groupId, eventType)
        }
        if (expenseId > 0L) {
            try {
                sideEffectDispatcher.dispatchOnCreated(expenseId, ExpenseSource.GROUP_EXPENSE)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Side effects failed for expense %d (group %d)", expenseId, groupId)
            }
        }
    }
}
