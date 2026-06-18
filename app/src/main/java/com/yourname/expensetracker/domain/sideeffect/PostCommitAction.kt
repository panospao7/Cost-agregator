package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata

data class PostCommitAction(
    val pipeline: AppPipeline,
    val name: String,
    val category: SideEffectCategory,
    val triggerType: SideEffectTriggerType,
    val targetEntityType: String,
    val targetEntityId: Long?,
    val source: String,
    val correlationId: String?,
    val causationId: String?,
    val idempotencyKey: String,
    val priority: SideEffectPriority = SideEffectPriority.NORMAL,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    val execute: suspend SideEffectExecutionContext.() -> SideEffectOutcome
)
