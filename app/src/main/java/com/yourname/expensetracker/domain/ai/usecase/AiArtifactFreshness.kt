package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.dto.AiArtifactRecord

internal fun AiArtifactRecord?.isFreshArtifact(
    promptVersion: String,
    sourceHash: String,
    now: Long
): Boolean {
    return this != null &&
        status == AiArtifactStatus.READY &&
        this.promptVersion == promptVersion &&
        this.sourceHash == sourceHash &&
        expiresAt != null &&
        expiresAt > now
}
