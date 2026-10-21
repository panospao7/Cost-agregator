package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PR2 acceptance tests:
 *
 * (other sources x modes: see RawPersistencePolicyConsolidationTest matrix; its allowRawBody /
 * allowRedactedBody expectations use the production constructor, so they are pinned independently
 * only by the literal table below)
 * notification_policy_literal_truth_table_all_four_modes (do_not_store omits body and parsed items)
 * email_message_id_hash_stable_across_calls (the HMAC hash of a message id is repeatable)
 * hash_service_does_not_use_String_hashCode
 */
class RawPersistencePolicyTest {

    private val hashService = DefaultSensitiveHashingService()
    private val resolver = RawPersistencePolicyResolver(
        FakePrivacySettingsRepositoryForPR2()
    )

    // ── Mode invariants ────────────────────────────────────────────────────────

    // Fields pinned with independent literals HERE (NOTIFICATION source, debug persistence off, all
    // four modes): allowRawBody, allowRedactedBody, allowParsedAmountDateCurrency.
    // (allowParsedItems / allowParsedMerchant / allowExternalIdHash / allowDebugBody were removed in
    // 1.0.2: nothing enforced them.)
    //
    // The remaining source x mode x debug combinations (all seven sources) are covered by
    // RawPersistencePolicyConsolidationTest.matrix_all_seven_sources_x_all_modes_match_truth_table,
    // which builds its expectations with the production RawPersistencePolicy constructor, so its
    // allowRawBody / allowRedactedBody expectations are NOT independent of production; those two
    // fields are pinned independently only by the literal table below.

    /** One row of the literal NOTIFICATION truth table. */
    private data class PolicyRow(
        val name: String,
        val mode: RawStorageMode,
        val allowRawBody: Boolean,
        val allowRedactedBody: Boolean,
        val allowParsedAmountDateCurrency: Boolean
    )

    /**
     * Literal truth table for NOTIFICATION (debug persistence off) across all four modes.
     * DO_NOT_STORE omits body, redacted body and parsed items/merchant/amount, but notification
     * still keeps the dedupe hash (NOTIFICATION needs a dedupe hash). METADATA_ONLY keeps
     * amount/date/currency and the hash only. STORE_REDACTED has no raw body; STORE_RAW has all.
     */
    @Test
    fun notification_policy_literal_truth_table_all_four_modes() {
        val rows = listOf(
            PolicyRow(
                name = "STORE_RAW",
                mode = RawStorageMode.STORE_RAW,
                allowRawBody = true,
                allowRedactedBody = true,
                allowParsedAmountDateCurrency = true
            ),
            PolicyRow(
                name = "STORE_REDACTED",
                mode = RawStorageMode.STORE_REDACTED,
                allowRawBody = false,
                allowRedactedBody = true,
                allowParsedAmountDateCurrency = true
            ),
            PolicyRow(
                name = "METADATA_ONLY",
                mode = RawStorageMode.STORE_METADATA_ONLY,
                allowRawBody = false,
                allowRedactedBody = false,
                allowParsedAmountDateCurrency = true
            ),
            PolicyRow(
                name = "DO_NOT_STORE",
                mode = RawStorageMode.DO_NOT_STORE,
                allowRawBody = false,
                allowRedactedBody = false,
                allowParsedAmountDateCurrency = false
            )
        )

        for (row in rows) {
            val policy = resolver.forSourceSync(
                RawSourceType.NOTIFICATION,
                PrivacySettings(
                    rawNotificationStorageMode = row.mode,
                    debugDataPersistenceEnabled = false
                )
            )
            val r = row.name
            assertEquals("[$r] mode", row.mode, policy.mode)
            assertEquals("[$r] sourceType", RawSourceType.NOTIFICATION, policy.sourceType)
            assertEquals("[$r] allowRawBody", row.allowRawBody, policy.allowRawBody)
            assertEquals("[$r] allowRedactedBody", row.allowRedactedBody, policy.allowRedactedBody)
            assertEquals(
                "[$r] allowParsedAmountDateCurrency",
                row.allowParsedAmountDateCurrency,
                policy.allowParsedAmountDateCurrency
            )
        }
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
