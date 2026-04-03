package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.repository.GroupsRepository
import javax.inject.Inject

class DeleteGroupUseCase @Inject constructor(
    private val groupsRepository: GroupsRepository
) {
    suspend operator fun invoke(groupId: Long): Boolean {
        return groupsRepository.deleteGroup(groupId)
    }
}
