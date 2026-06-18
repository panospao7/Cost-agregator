package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.repository.DeleteGroupMemberResult
import com.yourname.expensetracker.data.repository.GroupsRepository
import javax.inject.Inject

class DeleteGroupMemberUseCase @Inject constructor(
    private val groupsRepository: GroupsRepository
) {
    suspend operator fun invoke(groupId: Long, memberId: Long): DeleteGroupMemberResult {
        return groupsRepository.deleteMember(groupId, memberId)
    }
}
