package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.repository.GroupsRepository
import javax.inject.Inject

class AddGroupMemberUseCase @Inject constructor(
    private val groupsRepository: GroupsRepository
) {

    sealed class Result {
        data class Success(val memberId: Long) : Result()
        data class Error(val message: String) : Result()
    }

    suspend operator fun invoke(
        groupId: Long,
        name: String,
        email: String?,
        isCurrentUser: Boolean = false
    ): Result {
        if (groupId <= 0L) {
            return Result.Error("Invalid group")
        }

        val normalizedName = name.trim()
        if (normalizedName.isBlank()) {
            return Result.Error("Member name cannot be blank")
        }

        val normalizedEmail = email
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val memberId = groupsRepository.addMember(
            groupId = groupId,
            name = normalizedName,
            email = normalizedEmail,
            isCurrentUser = isCurrentUser
        )

        return if (memberId != null && memberId > 0L) {
            Result.Success(memberId)
        } else {
            Result.Error("Failed to add member: Invalid group or member")
        }
    }
}
