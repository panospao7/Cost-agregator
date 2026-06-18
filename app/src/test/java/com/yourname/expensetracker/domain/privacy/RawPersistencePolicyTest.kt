package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PR2 acceptance tests:
 *
 * store_raw_preserves_allowed_raw_fields
 * store_redacted_replaces_body_fields
 * metadata_only_omits_body_but_keeps_hashes
 * do_not_store_omits_body_and_plain_identifiers
 * email_message_id_hash_stable_across_modes_except_policy_denied
 * hash_service_does_not_use_String_hashCode
 */
class RawPersistencePolicyTest {

    private val hashService = DefaultSensitiveHashingService()
    private val resolver = RawPersistencePolicyResolver(
        FakePrivacySettingsRepositoryForPR2()
    )

    // ── Mode invariants ────────────────────────────────────────────────────────

    @Test
    fun store_raw_preserves_allowed_raw_fields() {
        val policy = resolver.forSourceSync(
            RawSourceType.NOTIFICATION,
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_RAW)
        )
        assertTrue(policy.allowRawBody)
        assertTrue(policy.allowParsedItems)
        assertTrue(policy.allowExternalIdHash)
    }

    @Test
    fun store_redacted_replaces_body_fields() {
        val policy = resolver.forSourceSync(
            RawSourceType.RECEIPT_OCR,
            PrivacySettings(rawOcrStorageMode = RawStorageMode.STORE_REDACTED)
        )
        assertFalse(policy.allowRawBody)
        assertTrue(policy.allowRedactedBody)
        assertTrue(policy.allowParsedItems)
        assertTrue(policy.allowExternalIdHash)
    }

    @Test
    fun metadata_only_omits_body_but_keeps_hashes() {
        val policy = resolver.forSourceSync(
            RawSourceType.NOTIFICATION,
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.STORE_METADATA_ONLY)
        )
        assertFalse(policy.allowRawBody)
        assertFalse(policy.allowRedactedBody)
        assertFalse(policy.allowParsedItems)
        assertTrue(policy.allowExternalIdHash)
    }

    @Test
    fun do_not_store_omits_body_and_parsed_items() {
        val policy = resolver.forSourceSync(
            RawSourceType.RECEIPT_OCR,
            PrivacySettings(rawOcrStorageMode = RawStorageMode.DO_NOT_STORE)
        )
        assertFalse(policy.allowRawBody)
        assertFalse(policy.allowRedactedBody)
        assertFalse(policy.allowParsedItems)
    }

    @Test
    fun notification_do_not_store_keeps_dedupe_hash() {
        val policy = resolver.forSourceSync(
            RawSourceType.NOTIFICATION,
            PrivacySettings(rawNotificationStorageMode = RawStorageMode.DO_NOT_STORE)
        )
        assertTrue("Notification needs dedup hash even under DO_NOT_STORE", policy.allowExternalIdHash)
    }

    @Test
    fun ocr_do_not_store_omits_external_id_hash() {
        val policy = resolver.forSourceSync(
            RawSourceType.RECEIPT_OCR,
            PrivacySettings(rawOcrStorageMode = RawStorageMode.DO_NOT_STORE)
        )
        // RECEIPT_OCR does not need a dedup hash
        assertFalse(policy.allowExternalIdHash)
    }

    @Test
    fun email_metadata_only_keeps_external_id_hash_for_dedup() {
        val policy = resolver.forSourceSync(
            RawSourceType.EMAIL_RECEIPT,
            PrivacySettings(emailReceiptStorageMode = RawStorageMode.STORE_METADATA_ONLY)
        )
        assertFalse(policy.allowRawBody)
        assertTrue(policy.allowExternalIdHash)
    }

    @Test
    fun debug_body_only_allowed_when_store_raw_and_debug_enabled() {
        val policyDebugOn = resolver.forSourceSync(
            RawSourceType.EXPORT_DEBUG,
            PrivacySettings(rawOcrStorageMode = RawStorageMode.STORE_RAW, debugDataPersistenceEnabled = true)
        )
        assertTrue(policyDebugOn.allowDebugBody)

        val policyDebugOff = resolver.forSourceSync(
            RawSourceType.EXPORT_DEBUG,
            PrivacySettings(rawOcrStorageMode = RawStorageMode.STORE_RAW, debugDataPersistenceEnabled = false)
        )
        assertFalse(policyDebugOff.allowDebugBody)
    }

    // ── Hashing contract ──────────────────────────────────────────────────────

    @Test
    fun hash_service_does_not_use_String_hashCode() {
        val value = "test-message-id-12345"
        val hash = hashService.hmacSha256Prefix(value, "emailMessageId")
        assertNotNull(hash)
        // String.hashCode() would produce at most 8 hex chars; HMAC-SHA-256 prefix is 24
        assertEquals(24, hash!!.length)
        // Verify it's hex
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun email_message_id_hash_stable_across_calls() {
        val value = "message-id-abc123@mail.com"
        val h1 = hashService.hmacSha256Prefix(value, "emailMessageId")
        val h2 = hashService.hmacSha256Prefix(value, "emailMessageId")
        assertEquals("HMAC hash must be deterministic", h1, h2)
    }

    @Test
    fun email_message_id_hash_differs_by_purpose() {
        val value = "message-id-abc123@mail.com"
        val h1 = hashService.hmacSha256Prefix(value, "emailMessageId")
        val h2 = hashService.hmacSha256Prefix(value, "providerTransactionId")
        assertNotEquals("Different purposes must produce different hashes", h1, h2)
    }

    @Test
    fun hash_service_returns_null_for_null_input() {
        assertNull(hashService.hmacSha256Prefix(null, "emailMessageId"))
        assertNull(hashService.sha256Prefix(null))
    }

    @Test
    fun sha256_prefix_is_deterministic() {
        val value = "payload-content-hash"
        val h1 = hashService.sha256Prefix(value)
        val h2 = hashService.sha256Prefix(value)
        assertEquals(h1, h2)
        assertEquals(24, h1!!.length)
    }

    // ── RawContentSanitizer no longer uses hashCode ────────────────────────────

    @Test
    fun sanitize_email_message_id_metadata_only_returns_null_not_hashCode() {
        // PR2: METADATA_ONLY must return null; callers supply pre-computed HMAC hash
        val result = RawContentSanitizer.sanitizeEmailMessageId("msg@test.com", RawStorageMode.STORE_METADATA_ONLY)
        assertNull("METADATA_ONLY should return null; caller must supply hash", result)
    }

    @Test
    fun sanitize_email_message_id_with_hash_uses_provided_hash_for_metadata_only() {
        val hash = "precomputed-hmac"
        val result = RawContentSanitizer.sanitizeEmailMessageIdWithHash(
            messageId = "msg@test.com",
            messageIdHash = hash,
            mode = RawStorageMode.STORE_METADATA_ONLY
        )
        assertEquals(hash, result)
    }

    @Test
    fun sanitize_email_message_id_do_not_store_returns_null() {
        val result = RawContentSanitizer.sanitizeEmailMessageIdWithHash(
            messageId = "msg@test.com",
            messageIdHash = "some-hash",
            mode = RawStorageMode.DO_NOT_STORE
        )
        assertNull(result)
    }

    // ── SafePrivacyMetadata blocks raw-sensitive keys ──────────────────────────

    @Test
    fun safe_privacy_metadata_blocks_raw_sensitive_keys() {
        val meta = SafePrivacyMetadata.builder()
            .put("rawText", "raw content")
            .put("prompt", "AI prompt")
            .put("token", "bearer-abc")
            .put("correlationId", "corr-123")
            .build()
        val json = meta.toJson()
        assertFalse("rawText must be blocked", json.contains("raw content"))
        assertFalse("prompt must be blocked", json.contains("AI prompt"))
        assertFalse("token must be blocked", json.contains("bearer-abc"))
        assertTrue("safe key must be allowed", json.contains("corr-123"))
    }

    @Test
    fun safe_privacy_metadata_blocked_keys_replaced_with_redacted() {
        val meta = SafePrivacyMetadata.builder()
            .put("rawBody", "sensitive body")
            .build()
        assertTrue(meta.toJson().contains("[REDACTED]"))
    }

    @Test
    fun safe_privacy_metadata_put_hash_stores_hash_value() {
        val meta = SafePrivacyMetadata.builder()
            .putHash("messageIdHash", "abc123def")
            .build()
        assertTrue(meta.toJson().contains("abc123def"))
    }

    @Test
    fun safe_privacy_metadata_merge_preserves_safe_keys() {
        val m1 = SafePrivacyMetadata.builder().put("key1", "val1").build()
        val m2 = SafePrivacyMetadata.builder().put("key2", "val2").build()
        val merged = m1.merge(m2)
        val json = merged.toJson()
        assertTrue(json.contains("val1"))
        assertTrue(json.contains("val2"))
    }
}

/** Minimal fake repository for PR2 tests (no load state needed). */
private class FakePrivacySettingsRepositoryForPR2 : PrivacySettingsRepository {
    private var current = PrivacySettings()

    override fun observeSettings() = kotlinx.coroutines.flow.flowOf(current)
    override fun observeLoadState() = kotlinx.coroutines.flow.flowOf(
        PrivacySettingsLoadState.Loaded(current)
    )
    override suspend fun getSettings() = current
    override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(current)
    override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
        current = transform(current)
    }
}
