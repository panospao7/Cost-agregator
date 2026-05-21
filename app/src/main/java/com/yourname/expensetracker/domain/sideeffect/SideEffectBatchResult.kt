package com.yourname.expensetracker.domain.sideeffect

data class SideEffectBatchResult(
    val correlationId: String,
    val completed: Int,
    val skipped: Int,
    val failedRetryable: Int,
    val failedFinal: Int,
    val cancelled: Int,
    val outcomes: List<SideEffectActionResult>
)
