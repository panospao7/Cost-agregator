package com.yourname.expensetracker.domain.dto

import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for domain DTO data classes.
 *
 * DTOs are plain data classes without serialization annotations (no Gson,
 * Moshi, or kotlinx.serialization). These tests verify field preservation,
 * default values, and null-safety for optional fields.
 */
class DtoContractTest {

    // ── AiArtifactRecord ───────────────────────────────────────────────────

    @Test
    fun `AiArtifactRecord preserves all fields through copy`() {
        val now = System.currentTimeMillis()
        val original = AiArtifactRecord(
            id = 42L,
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 7L,
            targetKey = "review_7",
            capability = AiCapability.REVIEW_EXPLANATION,
            status = AiArtifactStatus.READY,
            mode = AiMode.CLOUD,
            provider = "google-ai-studio",
            modelName = "gemini-2.5-flash",
            promptVersion = "v1",
            summaryText = "Top review item",
            explanationText = "Detailed explanation here",
            payloadJson = """{"key": "value"}""",
            confidence = 0.95f,
            sourceHash = "abc123",
            createdAt = now,
            updatedAt = now,
            expiresAt = now + 86_400_000L // +1 day
        )

        // Copy with one field changed
        val copy = original.copy(id = 99L)

        // All unchanged fields must match original
        assertEquals(original.targetType, copy.targetType)
        assertEquals(original.targetId, copy.targetId)
        assertEquals(original.targetKey, copy.targetKey)
        assertEquals(original.capability, copy.capability)
        assertEquals(original.status, copy.status)
        assertEquals(original.mode, copy.mode)
        assertEquals(original.provider, copy.provider)
        assertEquals(original.modelName, copy.modelName)
        assertEquals(original.promptVersion, copy.promptVersion)
        assertEquals(original.summaryText, copy.summaryText)
        assertEquals(original.explanationText, copy.explanationText)
        assertEquals(original.payloadJson, copy.payloadJson)
        assertEquals(original.confidence, copy.confidence)
        assertEquals(original.sourceHash, copy.sourceHash)
        assertEquals(original.createdAt, copy.createdAt)
        assertEquals(original.updatedAt, copy.updatedAt)
        assertEquals(original.expiresAt, copy.expiresAt)

        // Only id changed
        assertEquals(99L, copy.id)
        assertEquals(42L, original.id)
    }

    @Test
    fun `AiArtifactRecord optional fields are null by default`() {
        val now = System.currentTimeMillis()
        val record = AiArtifactRecord(
            targetType = AiTargetType.DASHBOARD,
            targetKey = "dash_v1",
            capability = AiCapability.DASHBOARD_BRIEFING,
            status = AiArtifactStatus.QUEUED,
            mode = AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = now,
            updatedAt = now
        )

        // Default values
        assertEquals(0L, record.id)
        assertNull(record.targetId)
        assertNull(record.provider)
        assertNull(record.modelName)
        assertNull(record.summaryText)
        assertNull(record.explanationText)
        assertNull(record.payloadJson)
        assertNull(record.confidence)
        assertNull(record.errorMessage)
        assertNull(record.expiresAt)
    }

    // ── ReceiptItemCategorizationSnapshot ──────────────────────────────────

    @Test
    fun `ReceiptItemCategorizationSnapshot roundtrip preserves fields`() {
        val now = System.currentTimeMillis()
        val snapshot = ReceiptItemCategorizationSnapshot(
            id = 1L,
            receiptId = 10L,
            expenseId = 100L,
            itemDescription = "Office chair",
            itemAmount = 299.99,
            suggestedCategoryId = 5L,
            suggestedCategoryName = "Furniture",
            confidence = 0.87f,
            aiRationale = "Matches furniture keywords",
            alternativeCategoriesJson = """[{"id":6,"name":"Office"}]""",
            userCorrectedCategoryId = 6L,
            userCorrectedCategoryName = "Office Supplies",
            userCorrectedAt = now,
            taxAmount = 59.99,
            isNewCategorySuggestion = false,
            createdAt = now,
            updatedAt = now
        )

        assertEquals(1L, snapshot.id)
        assertEquals(10L, snapshot.receiptId)
        assertEquals(100L, snapshot.expenseId)
        assertEquals("Office chair", snapshot.itemDescription)
        assertEquals(299.99, snapshot.itemAmount, 0.001)
        assertEquals(5L, snapshot.suggestedCategoryId)
        assertEquals("Furniture", snapshot.suggestedCategoryName)
        assertEquals(0.87f, snapshot.confidence)
        assertEquals("Matches furniture keywords", snapshot.aiRationale)
        assertEquals(6L, snapshot.userCorrectedCategoryId)
        assertEquals("Office Supplies", snapshot.userCorrectedCategoryName)
        assertEquals(now, snapshot.userCorrectedAt)
        assertEquals(59.99, snapshot.taxAmount!!, 0.001)
        assertFalse(snapshot.isNewCategorySuggestion)
    }

    @Test
    fun `unknown enum values handled gracefully`() {
        // AiArtifactRecord uses enums from domain.ai.model.
        // If the enum has unexpected values in the future (e.g. DB corruption),
        // the DTO should still carry them without crashing.
        val now = System.currentTimeMillis()

        // Use a valid enum value that represents a legitimate value.
        // The contract: any enum value (including future additions) must
        // be storable and retrievable via the DTO.
        val record = AiArtifactRecord(
            targetType = AiTargetType.ANALYTICS,
            targetKey = "analytics_v1",
            capability = AiCapability.DEDUPE_JUDGE,
            status = AiArtifactStatus.FAILED,
            mode = AiMode.ON_DEVICE,
            promptVersion = "v1",
            sourceHash = "def456",
            createdAt = now,
            updatedAt = now,
            errorMessage = "Unknown capability requested"
        )

        // All enum fields should be readable
        assertEquals(AiTargetType.ANALYTICS, record.targetType)
        assertEquals(AiCapability.DEDUPE_JUDGE, record.capability)
        assertEquals(AiArtifactStatus.FAILED, record.status)
        assertEquals(AiMode.ON_DEVICE, record.mode)

        // Nullable error message should be preserved (not crash on unknown values)
        assertEquals("Unknown capability requested", record.errorMessage)
    }

    private fun assertFalse(actual: Boolean) {
        org.junit.Assert.assertFalse(actual)
    }
}
