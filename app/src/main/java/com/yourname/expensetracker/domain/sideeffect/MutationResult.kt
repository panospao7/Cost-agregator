package com.yourname.expensetracker.domain.sideeffect

data class MutationResult<out T>(
    val value: T,
    val postCommitActions: PostCommitActionBatch
)
