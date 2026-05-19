package com.yourname.expensetracker.domain.privacy

import org.junit.Assert.*
import org.junit.Test

/**
 * PR2 acceptance tests: SafePrivacyMetadata value-level sanitization.
 */
class SafePrivacyMetadataValueSafetyTest {

    @Test
    fun safe_metadata_redacts_sensitive_value_under_benign_key() {
        val meta = SafePrivacyMetadata.builder()
            .put("note", "Bearer eyJhbGciOiJSUzI1NiJ9.payload")
            .put("context", "normal context")
            .build()
        val json = meta.toJson()
        assertFalse("Bearer token must be redacted under benign key", json.contains("Bearer"))
        assertTrue("Safe value must be preserved", json.contains("normal context"))
    }

    @Test
    fun safe_metadata_redacts_base64_token_under_benign_key() {
        val longBase64 = "A".repeat(50) + "=="
        val meta = SafePrivacyMetadata.builder()
            .put("merchant", longBase64)
            .build()
        assertFalse(meta.toJson().contains(longBase64))
    }

    @Test
    fun safe_metadata_redacts_iban_under_benign_key() {
        val meta = SafePrivacyMetadata.builder()
            .put("reference", "GR1234567890123456789012345")
            .build()
        assertFalse(meta.toJson().contains("GR1234567890"))
    }

    @Test
    fun safe_metadata_redacts_nested_sensitive_values() {
        val meta = SafePrivacyMetadata.builder()
            .put("data", mapOf("key" to "value"))
            .build()
        assertTrue(meta.toJson().contains("REDACTED_MAP"))
    }

    @Test
    fun safe_metadata_redacts_json_array_values() {
        val meta = SafePrivacyMetadata.builder()
            .put("items", listOf("a", "b"))
            .build()
        assertTrue(meta.toJson().contains("REDACTED_LIST"))
    }

    @Test
    fun safe_metadata_unknown_object_to_string_is_sanitized() {
        // An object whose toString() contains a JWT-like pattern
        val obj = object {
            override fun toString() = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0"
        }
        val meta = SafePrivacyMetadata.builder().put("info", obj).build()
        assertFalse(meta.toJson().contains("eyJ"))
    }

    @Test
    fun safe_metadata_put_hash_rejects_unapproved_key() {
        val meta = SafePrivacyMetadata.builder()
            .putHash("randomKey", "abc123def456")
            .build()
        assertTrue(meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_metadata_put_hash_rejects_plaintext_value() {
        val meta = SafePrivacyMetadata.builder()
            .putHash("messageIdHash", "plaintext-not-a-hash!")
            .build()
        assertTrue(meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_metadata_put_hash_accepts_approved_key_and_hex_value() {
        val hexHash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        val meta = SafePrivacyMetadata.builder()
            .putHash("messageIdHash", hexHash)
            .build()
        assertTrue(meta.toJson().contains(hexHash))
    }

    @Test
    fun safe_metadata_merge_preserves_sanitization() {
        val m1 = SafePrivacyMetadata.builder().put("key1", "safe").build()
        // Simulate a merge where the second metadata has a sensitive value
        // (in practice this can't happen through the builder, but merge re-sanitizes)
        val m2 = SafePrivacyMetadata.builder().put("note", "Bearer token123").build()
        val merged = m1.merge(m2)
        val json = merged.toJson()
        assertTrue(json.contains("safe"))
        assertFalse("Sensitive value must be redacted after merge", json.contains("Bearer"))
    }

    @Test
    fun safe_metadata_to_json_contains_no_raw_sensitive_values() {
        val meta = SafePrivacyMetadata.builder()
            .put("rawText", "raw OCR content")
            .put("prompt", "AI prompt")
            .put("token", "secret-token")
            .put("safeKey", "safe value")
            .build()
        val json = meta.toJson()
        assertFalse(json.contains("raw OCR content"))
        assertFalse(json.contains("AI prompt"))
        assertFalse(json.contains("secret-token"))
        assertTrue(json.contains("safe value"))
    }

    @Test
    fun safe_metadata_long_string_is_redacted_as_blob() {
        val longString = "x".repeat(600)
        val meta = SafePrivacyMetadata.builder().put("description", longString).build()
        assertTrue(meta.toJson().contains("REDACTED_BLOB"))
    }

    @Test
    fun safe_metadata_file_path_is_redacted() {
        val meta = SafePrivacyMetadata.builder()
            .put("location", "C:\\Users\\panos\\secret.db")
            .build()
        assertFalse(meta.toJson().contains("panos"))
    }

    // ── PR1: put() hash-key awareness ─────────────────────────────────────────

    @Test
    fun safe_metadata_put_message_id_hash_plaintext_is_redacted() {
        val meta = SafePrivacyMetadata.builder()
            .put("messageIdHash", "plaintext-message-id@example.com")
            .build()
        assertFalse("Plaintext under approved hash key must be redacted", meta.toJson().contains("plaintext"))
        assertTrue(meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_metadata_put_provider_transaction_id_hash_plaintext_is_redacted() {
        val meta = SafePrivacyMetadata.builder()
            .put("providerTransactionIdHash", "TXN-BANK-2024-12345")
            .build()
        assertFalse(meta.toJson().contains("TXN-BANK"))
        assertTrue(meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_metadata_put_account_id_hash_plaintext_is_redacted() {
        val meta = SafePrivacyMetadata.builder()
            .put("accountIdHash", "GR1234567890123456789012345")
            .build()
        assertFalse(meta.toJson().contains("GR1234"))
        assertTrue(meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_metadata_put_approved_hash_key_with_hex_value_allowed() {
        val hexHash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
        val meta = SafePrivacyMetadata.builder()
            .put("messageIdHash", hexHash)
            .build()
        assertTrue("Valid hex hash under approved key must be allowed", meta.toJson().contains(hexHash))
    }

    @Test
    fun safe_metadata_put_unknown_hash_key_redacted() {
        val meta = SafePrivacyMetadata.builder()
            .put("randomHash", "some-value")
            .build()
        assertTrue("Unknown *Hash key must be redacted", meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_metadata_put_raw_text_hash_key_redacted() {
        val meta = SafePrivacyMetadata.builder()
            .put("sourceIdHash", "raw-source-id-not-a-hash")
            .build()
        assertFalse(meta.toJson().contains("raw-source-id"))
        assertTrue(meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_metadata_merge_revalidates_hash_keys() {
        // Build m1 with a valid hash, m2 with a plaintext under hash key
        val m1 = SafePrivacyMetadata.builder()
            .put("messageIdHash", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
            .build()
        val m2 = SafePrivacyMetadata.builder()
            .put("providerTransactionIdHash", "plaintext-txn-id")
            .build()
        val merged = m1.merge(m2)
        val json = merged.toJson()
        assertTrue("Valid hash must survive merge", json.contains("a1b2c3d4e5f6"))
        assertFalse("Plaintext under hash key must be redacted after merge", json.contains("plaintext-txn-id"))
    }

    @Test
    fun safe_metadata_to_json_revalidates_hash_keys() {
        // Even if somehow a plaintext slipped in, toJson() must redact it
        val meta = SafePrivacyMetadata.builder()
            .put("payloadHash", "not-a-hex-hash!")
            .build()
        assertFalse(meta.toJson().contains("not-a-hex-hash"))
    }
}
