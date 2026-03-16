package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity

data class AiArtifactDiagnostics(
    val routeLabel: String,
    val providerLabel: String?,
    val modelLabel: String?
)

fun AiArtifactEntity.toDiagnosticsOrNull(): AiArtifactDiagnostics? {
    val routeLabel = when (mode) {
        AiMode.ON_DEVICE -> "On-device"
        AiMode.CLOUD -> "Cloud"
        AiMode.AUTO -> return null
    }

    return AiArtifactDiagnostics(
        routeLabel = routeLabel,
        providerLabel = provider,
        modelLabel = modelName
    )
}

fun AiArtifactDiagnostics.toDisplayText(): String {
    val parts = buildList {
        add(routeLabel)
        providerLabel?.takeIf { it.isNotBlank() }?.let(::add)
        modelLabel?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return parts.joinToString(" - ")
}
