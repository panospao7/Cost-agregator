package com.yourname.expensetracker.domain.groups

sealed class GroupValidationError {
    data object InvalidGroup : GroupValidationError()
    data object BlankMemberName : GroupValidationError()
    data class UserAlreadyMember(val userId: Long) : GroupValidationError()
    data class CurrentUserAlreadyExists(val userId: Long) : GroupValidationError()
    data object MaxMembersReached : GroupValidationError()
    data class Unknown(val message: String? = null) : GroupValidationError()
}

fun GroupValidationError.toUserMessage(): String {
    return when (this) {
        GroupValidationError.InvalidGroup -> "Invalid group"
        GroupValidationError.BlankMemberName -> "Member name cannot be blank"
        is GroupValidationError.UserAlreadyMember -> "This member is already in the group"
        is GroupValidationError.CurrentUserAlreadyExists -> "A current user is already assigned to this group"
        GroupValidationError.MaxMembersReached -> "This group has reached its member limit"
        is GroupValidationError.Unknown -> message ?: "Failed to add member"
    }
}
