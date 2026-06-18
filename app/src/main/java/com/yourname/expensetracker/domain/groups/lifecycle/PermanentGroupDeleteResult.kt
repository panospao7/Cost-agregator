package com.yourname.expensetracker.domain.groups.lifecycle

sealed interface PermanentGroupDeleteResult {
    data class Deleted(
        val groupId: Long,
        val linkedExpenseCount: Int
    ) : PermanentGroupDeleteResult

    data object ConfirmationRequired : PermanentGroupDeleteResult
    data object GroupNotFound : PermanentGroupDeleteResult
    data object GroupStillActive : PermanentGroupDeleteResult

    data class OutstandingBalancesExist(
        val groupId: Long,
        val outstandingCount: Int
    ) : PermanentGroupDeleteResult

    data class CurrentUserMembershipExists(
        val groupId: Long,
        val currentUserCount: Int
    ) : PermanentGroupDeleteResult

    data class Error(
        val message: String,
        val causeClass: String? = null
    ) : PermanentGroupDeleteResult
}
