package com.yourname.expensetracker.domain.sideeffect

interface PostCommitActionRunner {
    suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult
}
