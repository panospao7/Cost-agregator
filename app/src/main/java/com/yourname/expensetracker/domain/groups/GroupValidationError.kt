package com.yourname.expensetracker.domain.groups

sealed class GroupValidationError {
    data object InvalidGroup : GroupValidationError()
    data object BlankMemberName : GroupValidationError()
    data class UserAlreadyMember(val userId: Long) : GroupValidationError()
    data class CurrentUserAlreadyExists(val userId: Long) : GroupValidationError()
    data object MaxMembersReached : GroupValidationError()
    /**
     * Unclassified failure. Deliberately carries no wrapped exception or its text: [message] is
     * always the fixed user-safe string, so raw exception/SQL text can never reach the UI.
     */
    data object Unknown : GroupValidationError() {
        const val FIXED_MESSAGE: String = "Failed to add member"
        val message: String get() = FIXED_MESSAGE
    }
}

fun GroupValidationError.toUserMessage(): String {
    return when (this) {
        GroupValidationError.InvalidGroup -> "Invalid group"
        GroupValidationError.BlankMemberName -> "Member name cannot be blank"
        is GroupValidationError.UserAlreadyMember -> "This member is already in the group"
        is GroupValidationError.CurrentUserAlreadyExists -> "A current user is already assigned to this group"
        GroupValidationError.MaxMembersReached -> "This group has reached its member limit"
        GroupValidationError.Unknown -> GroupValidationError.Unknown.message
    }
}
