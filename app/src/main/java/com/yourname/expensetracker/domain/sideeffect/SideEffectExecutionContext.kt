package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata

interface SideEffectExecutionContext {
    val correlationId: String
    val action: PostCommitAction

    suspend fun checkpoint(label: String)
    suspend fun recordMetadata(metadata: SafeEventMetadata)
}
