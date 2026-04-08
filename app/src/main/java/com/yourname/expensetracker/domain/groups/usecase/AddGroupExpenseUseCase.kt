package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

class AddGroupExpenseUseCase @Inject constructor(
    private val groupsRepository: GroupsRepository,
    private val timeProvider: TimeProvider
) {
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
        val group = groupsRepository.getGroupById(groupId)
            ?: return GroupExpenseCreationResult.Error("Group not found")
        if (!group.isActive) {
            return GroupExpenseCreationResult.Error("Group not found or inactive")
        }

        val payer = groupsRepository.getMemberById(paidById)
            ?: return GroupExpenseCreationResult.Error("Payer not found")
        if (payer.groupId != groupId) {
            return GroupExpenseCreationResult.Error("Payer is not a member of this group")
        }

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
}
