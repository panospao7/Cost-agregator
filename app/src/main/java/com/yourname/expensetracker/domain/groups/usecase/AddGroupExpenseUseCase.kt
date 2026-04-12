package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

class AddGroupExpenseUseCase @Inject constructor(
    private val groupsRepository: GroupsRepository,
    private val timeProvider: TimeProvider
) {
    /**
     * Link an already-existing system expense to a group.
     *
     * B.4 Batch 2 (Risk 4): Removed redundant group/payer pre-validation that
     * duplicated the coordinator's own transactional checks, creating a TOCTOU
     * exposure. The coordinator now performs all validation inside the
     * [database.withTransaction] boundary.
     */
    suspend operator fun invoke(
        groupId: Long,
        systemExpenseId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType,
        customSplitsJson: String? = null,
        date: Long? = null
    ): GroupExpenseCreationResult {
        val resolvedDate = date ?: timeProvider.now()

        return groupsRepository.addExpenseWithLink(
            groupId = groupId,
            systemExpenseId = systemExpenseId,
            description = description,
            amount = amount,
            paidById = paidById,
            splitType = splitType,
            customSplitsJson = customSplitsJson,
            date = resolvedDate
        )
    }

    /**
     * B.4 Batch 2: Atomically create a system expense AND link it to a group
     * in a single transaction. This is the preferred path for ViewModel callers
     * that would otherwise need to create a system expense first and then link.
     *
     * All validation is performed by the coordinator inside the transaction boundary.
     */
    suspend fun invokeAtomic(
        groupId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        currency: String,
        splitType: SplitType,
        customSplitsJson: String? = null,
        date: Long? = null,
        transactionType: TransactionType = TransactionType.PURCHASE,
        notes: String? = null
    ): GroupExpenseCreationResult {
        val resolvedDate = date ?: timeProvider.now()

        return groupsRepository.createSystemExpenseAndLinkToGroup(
            groupId = groupId,
            description = description,
            amount = amount,
            paidById = paidById,
            currency = currency,
            splitType = splitType,
            customSplitsJson = customSplitsJson,
            date = resolvedDate,
            transactionType = transactionType,
            notes = notes
        )
    }
}
