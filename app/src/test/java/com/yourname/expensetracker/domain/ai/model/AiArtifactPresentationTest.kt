package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiArtifactPresentationTest {

    @Test
    fun `toDiagnosticsOrNull maps cloud artifact to display text`() {
        val artifact = AiArtifactEntity(
            targetType = AiTargetType.PENDING_REVIEW,
            targetKey = "pending_review:1",
            capability = AiCapability.REVIEW_EXPLANATION,
            status = AiArtifactStatus.READY,
            mode = AiMode.CLOUD,
            provider = "google-ai-studio",
            modelName = "gemini-2.5-flash",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 1L,
            updatedAt = 1L
        )

        val diagnostics = artifact.toDiagnosticsOrNull()

        assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", diagnostics?.toDisplayText())
    }

    @Test
    fun `toDiagnosticsOrNull maps on-device artifact to display text`() {
        val artifact = AiArtifactEntity(
            targetType = AiTargetType.PENDING_REVIEW,
            targetKey = "pending_review:1",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = AiArtifactStatus.READY,
            mode = AiMode.ON_DEVICE,
            provider = "mlkit-genai-nano",
            modelName = "gemini-nano",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 1L,
            updatedAt = 1L
        )

        val diagnostics = artifact.toDiagnosticsOrNull()

        assertEquals("On-device - mlkit-genai-nano - gemini-nano", diagnostics?.toDisplayText())
    }

    @Test
    fun `toDiagnosticsOrNull returns null for auto mode`() {
        val artifact = AiArtifactEntity(
            targetType = AiTargetType.PENDING_REVIEW,
            targetKey = "pending_review:1",
            capability = AiCapability.REVIEW_EXPLANATION,
            status = AiArtifactStatus.READY,
            mode = AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 1L,
            updatedAt = 1L
        )

        assertNull(artifact.toDiagnosticsOrNull())
    }
}
