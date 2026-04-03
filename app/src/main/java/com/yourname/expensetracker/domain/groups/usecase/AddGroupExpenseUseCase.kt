package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import javax.inject.Inject

class AddGroupExpenseUseCase @Inject constructor(
    private val groupsRepository: GroupsRepository
) {
    suspend operator fun invoke(
        groupId: Long,
        systemExpenseId: Long,
        description: String,
        amount: Double,
        paidById: Long,
        splitType: SplitType,
        date: Long = System.currentTimeMillis()
    ): GroupExpenseCreationResult {
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
            date = date
        )
    }
}
