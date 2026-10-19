package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideEffectMetadataFactoryTest {

    private val sampleAction = PostCommitAction(
        pipeline = AppPipeline.TRANSACTION,
        name = "test_action",
        category = SideEffectCategory.BUDGET,
        triggerType = SideEffectTriggerType.EXPENSE_CREATED,
        targetEntityType = "expense",
        targetEntityId = 42L,
        source = "test_source",
        correlationId = "corr-123",
        causationId = "caus-456",
        idempotencyKey = "idem-789",
        execute = { SideEffectOutcome.Completed }
    )

    @Test
    fun `builds metadata with all fields`() {
        val metadata = SideEffectMetadataFactory.forAction(sampleAction)

        val json = metadata.toJson()
        assertTrue(json.contains("sideEffectName"))
        assertTrue(json.contains("test_action"))
        assertTrue(json.contains("category"))
        assertTrue(json.contains("BUDGET"))
        assertTrue(json.contains("triggerType"))
        assertTrue(json.contains("EXPENSE_CREATED"))
        assertTrue(json.contains("targetEntityType"))
        assertTrue(json.contains("expense"))
        assertTrue(json.contains("targetEntityId"))
        assertTrue(json.contains("42"))
        assertTrue(json.contains("priority"))
        assertTrue(json.contains("NORMAL"))
        assertTrue(json.contains("source"))
        assertTrue(json.contains("test_source"))
        assertTrue(json.contains("correlationId"))
        assertTrue(json.contains("corr-123"))
        assertTrue(json.contains("causationId"))
        assertTrue(json.contains("caus-456"))
    }

    @Test
    fun `has hashed idempotency key present`() {
        val metadata = SideEffectMetadataFactory.forAction(sampleAction)
        val json = metadata.toJson()
        // idempotencyKey should be stored as a hashed value (hex string)
        // The value should be a hex string (sha256 prefix of 16 chars)
        assertTrue(json.contains("\"idempotencyKey\""))
        // Extract the value and verify it looks like a hex hash prefix
        val keyValue = json.replace("\"", "")
            .split(",")
            .find { it.trim().startsWith("idempotencyKey:") }
        assertNotNull(keyValue)
        assertTrue(keyValue!!.trim().matches(Regex("idempotencyKey:\\s*[a-f0-9]{16}")))
    }

    @Test
    fun `no raw payloads in metadata`() {
        val metadata = SideEffectMetadataFactory.forAction(sampleAction)
        val json = metadata.toJson()
        assertTrue(!json.contains("rawBody"))
        assertTrue(!json.contains("rawText"))
        assertTrue(!json.contains("notes"))
        assertTrue(!json.contains("ocrBody"))
        assertTrue(!json.contains("merchant"))
        assertTrue(!json.contains("externalId"))
    }

    @Test
    fun `additional metadata is merged`() {
        val additional = mapOf("extraKey" to "extraValue", "anotherKey" to "anotherValue")
        val metadata = SideEffectMetadataFactory.forAction(sampleAction, additional)
        val json = metadata.toJson()
        assertTrue(json.contains("extraKey"))
        assertTrue(json.contains("extraValue"))
        assertTrue(json.contains("anotherKey"))
        assertTrue(json.contains("anotherValue"))
    }

    @Test
    fun `failure and skip reason maps carry bounded reasons`() {
        val failure = SideEffectMetadataFactory.forFailure("Something broke", "java.lang.IllegalStateException")
        assertEquals("forFailure with errorClass: failureReason", "Something broke", failure["failureReason"])
        assertEquals("forFailure with errorClass: errorClass", "java.lang.IllegalStateException", failure["errorClass"])

        val failureNoClass = SideEffectMetadataFactory.forFailure("Something broke", null)
        assertEquals("forFailure null errorClass: failureReason", "Something broke", failureNoClass["failureReason"])
        assertEquals("forFailure null errorClass: errorClass", null, failureNoClass["errorClass"])

        val skip = SideEffectMetadataFactory.forSkip(SideEffectSkipReason.NOT_APPLICABLE)
        assertEquals("forSkip: skipReason", "NOT_APPLICABLE", skip["skipReason"])
    }

    @Test
    fun `null optional action fields are omitted from metadata`() {
        // rows: field name -> action with that field nulled
        val rows = listOf(
            "targetEntityId" to sampleAction.copy(targetEntityId = null),
            "correlationId" to sampleAction.copy(correlationId = null),
            "causationId" to sampleAction.copy(causationId = null)
        )
        rows.forEach { (field, action) ->
            val json = SideEffectMetadataFactory.forAction(action).toJson()
            assertTrue("null $field must be omitted from metadata", !json.contains(field))
        }
    }
}
