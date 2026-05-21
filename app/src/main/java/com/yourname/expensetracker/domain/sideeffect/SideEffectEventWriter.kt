package com.yourname.expensetracker.domain.sideeffect

interface SideEffectEventWriter {
    suspend fun started(action: PostCommitAction)
    suspend fun completed(action: PostCommitAction)
    suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason)
    suspend fun failed(action: PostCommitAction, retryable: Boolean, reason: String, error: Throwable?)
    suspend fun cancelled(action: PostCommitAction, reason: String?)
}
