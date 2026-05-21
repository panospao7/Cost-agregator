package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata

object SideEffectMetadataFactory {
    fun forAction(
        action: PostCommitAction,
        additional: Map<String, String> = emptyMap()
    ): SafeEventMetadata {
        val builder = SafeEventMetadata.builder()
            .put("sideEffectName", action.name)
            .put("category", action.category.name)
            .put("triggerType", action.triggerType.name)
            .put("targetEntityType", action.targetEntityType)
            .put("priority", action.priority.name)
            .putHashed("idempotencyKey", action.idempotencyKey)

        action.targetEntityId?.let {
            builder.put("targetEntityId", it.toString())
        }
        action.correlationId?.let {
            builder.put("correlationId", it)
        }
        action.causationId?.let {
            builder.put("causationId", it)
        }
        action.source.let {
            builder.put("source", it)
        }

        for ((key, value) in additional) {
            builder.put(key, value)
        }

        return builder.build()
    }

    fun forFailure(reason: String, errorClass: String?): Map<String, String> {
        return buildMap {
            put("failureReason", reason)
            errorClass?.let { put("errorClass", it) }
        }
    }

    fun forSkip(reason: SideEffectSkipReason): Map<String, String> {
        return mapOf("skipReason" to reason.name)
    }
}
