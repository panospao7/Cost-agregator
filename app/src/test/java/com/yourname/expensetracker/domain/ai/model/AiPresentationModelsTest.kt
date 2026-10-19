package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure presentation mappings: artifact diagnostics, capability route text and on-device
 * runtime status messages (merged from AiArtifactPresentationTest, AiRuntimeStatusModelsTest
 * and OnDeviceRuntimePresentationTest).
 */
class AiPresentationModelsTest {

    private data class DiagnosticsRow(
        val name: String,
        val artifact: AiArtifactRecord,
        val expectedDisplayText: String?
    )

    private data class RouteTextRow(
        val name: String,
        val status: AiCapabilityRuntimeStatus,
        val expected: String?
    )

    private data class StatusMessageRow(
        val name: String,
        val status: OnDeviceModelStatus,
        val featureLabel: String,
        val expected: String?
    )

    @Test
    fun `toDiagnosticsOrNull maps artifact mode to display text or null`() {
        val rows = listOf(
            DiagnosticsRow(
                name = "cloud artifact",
                artifact = AiArtifactRecord(
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
                ),
                expectedDisplayText = "Cloud - google-ai-studio - gemini-2.5-flash"
            ),
            DiagnosticsRow(
                name = "on-device artifact",
                artifact = AiArtifactRecord(
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
                ),
                expectedDisplayText = "On-device - mlkit-genai-nano - gemini-nano"
            ),
            DiagnosticsRow(
                name = "auto mode has no diagnostics",
                artifact = AiArtifactRecord(
                    targetType = AiTargetType.PENDING_REVIEW,
                    targetKey = "pending_review:1",
                    capability = AiCapability.REVIEW_EXPLANATION,
                    status = AiArtifactStatus.READY,
                    mode = AiMode.AUTO,
                    promptVersion = "v1",
                    sourceHash = "hash",
                    createdAt = 1L,
                    updatedAt = 1L
                ),
                expectedDisplayText = null
            )
        )
        assertEquals("diagnostics table size guard", 3, rows.size)

        rows.forEach { row ->
            val diagnostics = row.artifact.toDiagnosticsOrNull()
            if (row.expectedDisplayText == null) {
                assertNull("[${row.name}] diagnostics must be null", diagnostics)
            } else {
                assertEquals("[${row.name}] display text", row.expectedDisplayText, diagnostics?.toDisplayText())
            }
        }
    }

    @Test
    fun `routeDisplayText includes provider and model only when a route is present`() {
        val rows = listOf(
            RouteTextRow(
                name = "cloud route with provider and model",
                status = AiCapabilityRuntimeStatus(
                    capability = AiCapability.DASHBOARD_BRIEFING,
                    status = OnDeviceModelStatus.UNAVAILABLE,
                    message = null,
                    actionLabel = null,
                    route = AiRoute.CLOUD,
                    providerName = "google-ai-studio",
                    modelName = "gemini-2.5-flash"
                ),
                expected = "Cloud - google-ai-studio - gemini-2.5-flash"
            ),
            RouteTextRow(
                name = "route missing",
                status = AiCapabilityRuntimeStatus(
                    capability = AiCapability.QUERY_INTERPRETATION,
                    status = OnDeviceModelStatus.AVAILABLE,
                    message = null,
                    actionLabel = null
                ),
                expected = null
            )
        )
        assertEquals("route text table size guard", 2, rows.size)

        rows.forEach { row ->
            assertEquals("[${row.name}] routeDisplayText", row.expected, row.status.routeDisplayText())
        }
    }

    @Test
    fun `toRuntimeStatusMessage maps each on-device model status to exact guidance`() {
        val rows = listOf(
            StatusMessageRow("available has no message", OnDeviceModelStatus.AVAILABLE, "AI", null),
            StatusMessageRow(
                "not installed",
                OnDeviceModelStatus.NOT_INSTALLED,
                "AI",
                "On-device AI is available but the model is not installed yet."
            ),
            StatusMessageRow(
                "unsupported android version",
                OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION,
                "briefing",
                "On-device briefing requires Android 14 or newer."
            ),
            StatusMessageRow(
                "unavailable",
                OnDeviceModelStatus.UNAVAILABLE,
                "AI",
                "On-device AI is unavailable on this phone right now. This usually means Android AICore / Gemini Nano is missing, not provisioned yet, or unsupported by the device vendor."
            )
        )
        assertEquals("status message table size guard", 4, rows.size)

        rows.forEach { row ->
            assertEquals(
                "[${row.name}] toRuntimeStatusMessage(${row.featureLabel})",
                row.expected,
                row.status.toRuntimeStatusMessage(row.featureLabel)
            )
        }
    }
}
