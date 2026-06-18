package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.groups.GroupValidationError
import javax.inject.Inject

class AddGroupMemberUseCase @Inject constructor(
    private val groupsRepository: GroupsRepository
) {

    sealed class Result {
        data object Success : Result()
        data class Error(val error: GroupValidationError) : Result()
    }

    suspend operator fun invoke(
        groupId: Long,
        name: String,
        email: String?,
        isCurrentUser: Boolean = false
    ): Result {
        if (groupId <= 0L) {
            return Result.Error(GroupValidationError.InvalidGroup)
        }

        val normalizedName = name.trim()
        if (normalizedName.isBlank()) {
            return Result.Error(GroupValidationError.BlankMemberName)
        }

        val normalizedEmail = email
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return when (val result = groupsRepository.addMember(
            groupId = groupId,
            name = normalizedName,
            email = normalizedEmail,
            isCurrentUser = isCurrentUser
        )) {
            is com.yourname.expensetracker.domain.groups.Result.Success -> Result.Success
            is com.yourname.expensetracker.domain.groups.Result.Error -> Result.Error(result.error)
        }
    }
}
