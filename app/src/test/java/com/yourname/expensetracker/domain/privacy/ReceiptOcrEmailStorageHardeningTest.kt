package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import org.junit.Assert.*
import org.junit.Test

/**
 * PR4 acceptance tests:
 *
 * raw_ocr_do_not_store_no_raw_text_in_scanned_receipts
 * raw_ocr_do_not_store_no_raw_text_in_pending_reviews
 * raw_ocr_metadata_only_no_raw_text_in_receipt_events
 * email_do_not_store_no_subject_sender_body_message_id_plaintext
 * email_metadata_only_keeps_message_id_hash_for_dedupe
 * email_fingerprint_not_plaintext_merchant_amount_date
 * parsed_items_redacted_when_policy_requires
 * debug_export_blocked_or_redacted_by_policy
 */
class ReceiptOcrEmailStorageHardeningTest {

    private val hashService = DefaultSensitiveHashingService()

    // ── ReceiptPersistencePayload ─────────────────────────────────────────────

    @Test
    fun raw_ocr_do_not_store_no_raw_text_in_scanned_receipts() {
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            rawOcrText = "Receipt text: €50 at Starbucks",
            parsedItemsJson = """[{"item":"Coffee","price":5.0}]"""
        )
        assertNull("rawOcrText must be null", payload.rawOcrText)
        assertNull("parsedItems must be null", payload.parsedItemsJson)
    }

    @Test
    fun raw_ocr_do_not_store_review_snippet_is_safe_placeholder() {
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            rawOcrText = "Receipt text with sensitive data",
            parsedItemsJson = null
        )
        assertNull(payload.rawOcrText)
        // Review snippet must not contain raw OCR text
        assertFalse(
            "Review snippet must not contain raw OCR text",
            payload.reviewSnippet?.contains("sensitive data") ?: false
        )
        assertTrue(
            "Review snippet should mention not stored",
            payload.reviewSnippet?.contains("not stored") ?: false
        )
    }

    @Test
    fun raw_ocr_metadata_only_no_raw_text_in_receipt_events() {
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawOcrText = "Full OCR text with PII",
            parsedItemsJson = """[{"item":"item"}]"""
        )
        assertNull(payload.rawOcrText)
        assertNull(payload.parsedItemsJson)
        assertNotNull(payload.reviewSnippet)
        assertFalse(
            "Metadata-only snippet must not contain raw OCR",
            payload.reviewSnippet?.contains("PII") ?: false
        )
    }

    @Test
    fun raw_ocr_store_raw_preserves_text_and_items() {
        val rawText = "Full receipt OCR text"
        val items = """[{"item":"Coffee"}]"""
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_RAW,
            rawOcrText = rawText,
            parsedItemsJson = items
        )
        assertEquals(rawText, payload.rawOcrText)
        assertEquals(items, payload.parsedItemsJson)
        assertEquals(rawText.take(200), payload.reviewSnippet)
    }

    @Test
    fun parsed_items_redacted_when_mode_is_metadata_only() {
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawOcrText = "Text",
            parsedItemsJson = """[{"item":"Sensitive item"}]"""
        )
        assertNull("Parsed items must not be stored in METADATA_ONLY", payload.parsedItemsJson)
    }

    @Test
    fun parsed_items_kept_in_store_redacted_mode() {
        val items = """[{"item":"Coffee","price":5.0}]"""
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_REDACTED,
            rawOcrText = "Full text",
            parsedItemsJson = items
        )
        assertNull(payload.rawOcrText)
        assertEquals(items, payload.parsedItemsJson)
        assertEquals("[REDACTED]", payload.reviewSnippet)
    }

    // ── EmailReceiptPersistencePayload ────────────────────────────────────────

    @Test
    fun email_do_not_store_no_subject_sender_body_message_id_plaintext() {
        val messageIdHash = hashService.hmacSha256Prefix("msg@test.com", "emailMessageId")
        val fingerprintHash = hashService.sha256Prefix("merchant:50.0:2024-01-01")

        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            subject = "Your Amazon order",
            sender = "orders@amazon.com",
            bodyText = "Full email body with PII",
            messageId = "msg@test.com",
            messageIdHash = messageIdHash,
            contentFingerprintHash = fingerprintHash,
            providerOrderIdHash = null,
            parsedItemsJson = null
        )
        assertNull("subject must be null", payload.subject)
        assertNull("sender must be null", payload.sender)
        assertNull("bodyText must be null", payload.bodyText)
        assertNull("messageIdStored must be null", payload.messageIdStored)
        assertNull("parsedItemsJson must be null", payload.parsedItemsJson)
        // But hash must be kept for dedup
        assertNotNull("messageIdHash must be present for dedup", payload.messageIdHash)
    }

    @Test
    fun email_metadata_only_keeps_message_id_hash_for_dedupe() {
        val messageIdHash = hashService.hmacSha256Prefix("msg@test.com", "emailMessageId")

        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            subject = "Order confirmation",
            sender = "no-reply@store.com",
            bodyText = "Body content",
            messageId = "msg@test.com",
            messageIdHash = messageIdHash,
            contentFingerprintHash = "fp-hash",
            providerOrderIdHash = "order-hash",
            parsedItemsJson = null
        )
        assertNull(payload.subject)
        assertNull(payload.sender)
        assertNull(payload.bodyText)
        assertNull(payload.messageIdStored)
        assertEquals("messageIdHash must be preserved", messageIdHash, payload.messageIdHash)
        assertEquals("contentFingerprintHash must be preserved", "fp-hash", payload.contentFingerprintHash)
        assertEquals("providerOrderIdHash must be preserved", "order-hash", payload.providerOrderIdHash)
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
    fun email_store_raw_preserves_all_fields() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_RAW,
            subject = "Your order",
            sender = "orders@amazon.com",
            bodyText = "Full body",
            messageId = "msg@test.com",
            messageIdHash = "hash-abc",
            contentFingerprintHash = "fp-hash",
            providerOrderIdHash = "order-hash",
            parsedItemsJson = """[{"item":"book"}]"""
        )
        assertEquals("Your order", payload.subject)
        assertEquals("orders@amazon.com", payload.sender)
        assertEquals("Full body", payload.bodyText)
        assertEquals("msg@test.com", payload.messageIdStored)
        assertEquals("hash-abc", payload.messageIdHash)
        assertEquals("fp-hash", payload.contentFingerprintHash)
        assertEquals("""[{"item":"book"}]""", payload.parsedItemsJson)
    }

    @Test
    fun email_store_redacted_removes_body_keeps_items() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_REDACTED,
            subject = "Sensitive",
            sender = "sender@test.com",
            bodyText = "Sensitive body",
            messageId = "msg@test.com",
            messageIdHash = "hash",
            contentFingerprintHash = "fp",
            providerOrderIdHash = "order",
            parsedItemsJson = """[{"item":"Coffee"}]"""
        )
        assertEquals("[REDACTED]", payload.subject)
        assertEquals("[REDACTED]", payload.sender)
        assertNull(payload.bodyText)
        assertNull(payload.messageIdStored)
        assertEquals("hash", payload.messageIdHash)
        assertEquals("""[{"item":"Coffee"}]""", payload.parsedItemsJson)
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
        assertFalse(policy.allowDebugBody)
    }
}
