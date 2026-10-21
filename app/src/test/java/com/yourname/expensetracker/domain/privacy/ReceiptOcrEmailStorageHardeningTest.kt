package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import org.junit.Assert.*
import org.junit.Test

/**
 * PR4 acceptance tests (B6-8: the per-mode payload matrix now lives in
 * RawStoragePayloadMatrixTest; only hashing and resolver checks remain here):
 *
 * email_dedupe_works_with_message_id_hash
 * email_fingerprint_not_plaintext_merchant_amount_date
 * debug_export_blocked_or_redacted_by_policy
 */
class ReceiptOcrEmailStorageHardeningTest {

    private val hashService = DefaultSensitiveHashingService()

    // ── EmailReceiptPersistencePayload ────────────────────────────────────────

    @Test
    fun email_dedupe_works_with_message_id_hash() {
        val hash1 = hashService.hmacSha256Prefix("msg@test.com", "emailMessageId")
        val hash2 = hashService.hmacSha256Prefix("msg@test.com", "emailMessageId")
        assertEquals("HMAC hash must be stable for dedup", hash1, hash2)
        assertNotEquals("Hash must not equal plaintext", "msg@test.com", hash1)
    }

    @Test
    fun email_fingerprint_not_plaintext_merchant_amount_date() {
        // The fingerprint stored must always be a hash, never "merchant:amount:date" plaintext
        val fingerprint = "Starbucks:5.0:2024-01-15"
        val fingerprintHash = hashService.sha256Prefix(fingerprint)

        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            subject = null,
            sender = null,
            bodyText = null,
            messageId = null,
            messageIdHash = null,
            contentFingerprintHash = fingerprintHash,
            providerOrderIdHash = null,
            parsedItemsJson = null
        )
        // Hash must not equal the plaintext fingerprint
        assertNotEquals("Fingerprint must be hashed, not plaintext", fingerprint, payload.contentFingerprintHash)
        // Hash must be present
        assertEquals(fingerprintHash, payload.contentFingerprintHash)
    }

    @Test
    fun debug_export_blocked_by_policy_when_debug_disabled() {
        val resolver = RawPersistencePolicyResolver(
            object : PrivacySettingsRepository {
                private val settings = PrivacySettings(debugDataPersistenceEnabled = false)
                override fun observeSettings() = kotlinx.coroutines.flow.flowOf(settings)
                override fun observeLoadState() = kotlinx.coroutines.flow.flowOf(PrivacySettingsLoadState.Loaded(settings))
                override suspend fun getSettings() = settings
                override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(settings)
                override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
            }
        )
        val policy = resolver.forSourceSync(
            RawSourceType.EXPORT_DEBUG,
            PrivacySettings(debugDataPersistenceEnabled = false)
        )
        assertEquals(RawStorageMode.DO_NOT_STORE, policy.mode)
    }
}
