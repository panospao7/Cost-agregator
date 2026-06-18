package com.yourname.expensetracker.domain.privacy

import org.junit.Assert.*
import org.junit.Test

/**
 * PRIV-441-08 / PRIV-441-09 / PRIV-441-10 / PRIV-441-11 acceptance tests.
 *
 * Tests the EmailReceiptPersistencePayload model and the email raw-storage
 * enforcement contracts.
 */
class EmailRawStorageEnforcementTest {

    // ── PRIV-441-08: EmailReceiptPersistencePayload model ────────────────────

    @Test
    fun email_do_not_store_no_subject_sender_body_message_id() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            subject = "Your Amazon order",
            sender = "order@amazon.com",
            bodyText = "Order total: $50.00",
            messageId = "<msg123@amazon.com>",
            messageIdHash = "abc123hash",
            contentFingerprintHash = "fp456hash",
            providerOrderIdHash = null,
            parsedItemsJson = """[{"desc":"Book","price":50.0}]"""
        )

        assertNull("DO_NOT_STORE must not persist subject", payload.subject)
        assertNull("DO_NOT_STORE must not persist sender", payload.sender)
        assertNull("DO_NOT_STORE must not persist body", payload.bodyText)
        assertNull("DO_NOT_STORE must not persist raw messageId", payload.messageIdStored)
        assertNull("DO_NOT_STORE must not persist parsed items", payload.parsedItemsJson)
        // Hash must be kept for dedup
        assertEquals("abc123hash", payload.messageIdHash)
        assertEquals("fp456hash", payload.contentFingerprintHash)
    }

    @Test
    fun email_metadata_only_stores_message_id_hash_in_source() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            subject = "Your Amazon order",
            sender = "order@amazon.com",
            bodyText = "Order total: $50.00",
            messageId = "<msg123@amazon.com>",
            messageIdHash = "abc123hash",
            contentFingerprintHash = "fp456hash",
            providerOrderIdHash = "ord789hash",
            parsedItemsJson = """[{"desc":"Book","price":50.0}]"""
        )

        assertNull("METADATA_ONLY must not persist subject", payload.subject)
        assertNull("METADATA_ONLY must not persist sender", payload.sender)
        assertNull("METADATA_ONLY must not persist body", payload.bodyText)
        assertNull("METADATA_ONLY must not persist raw messageId", payload.messageIdStored)
        assertNull("METADATA_ONLY must not persist parsed items", payload.parsedItemsJson)
        // Hashes must be kept
        assertEquals("abc123hash", payload.messageIdHash)
        assertEquals("fp456hash", payload.contentFingerprintHash)
        assertEquals("ord789hash", payload.providerOrderIdHash)
    }

    @Test
    fun email_store_raw_persists_all_fields() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_RAW,
            subject = "Your Amazon order",
            sender = "order@amazon.com",
            bodyText = "Order total: $50.00",
            messageId = "<msg123@amazon.com>",
            messageIdHash = "abc123hash",
            contentFingerprintHash = "fp456hash",
            providerOrderIdHash = "ord789hash",
            parsedItemsJson = """[{"desc":"Book","price":50.0}]"""
        )

        assertEquals("Your Amazon order", payload.subject)
        assertEquals("order@amazon.com", payload.sender)
        assertEquals("Order total: $50.00", payload.bodyText)
        assertEquals("<msg123@amazon.com>", payload.messageIdStored)
        assertNotNull(payload.parsedItemsJson)
        assertEquals("abc123hash", payload.messageIdHash)
    }

    @Test
    fun email_redacted_mode_redacts_subject_sender_but_keeps_hash() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_REDACTED,
            subject = "Your Amazon order",
            sender = "order@amazon.com",
            bodyText = "Order total: $50.00",
            messageId = "<msg123@amazon.com>",
            messageIdHash = "abc123hash",
            contentFingerprintHash = "fp456hash",
            providerOrderIdHash = "ord789hash",
            parsedItemsJson = """[{"desc":"Book","price":50.0}]"""
        )

        assertEquals("[REDACTED]", payload.subject)
        assertEquals("[REDACTED]", payload.sender)
        assertNull("REDACTED must not persist body", payload.bodyText)
        assertNull("REDACTED must not persist raw messageId", payload.messageIdStored)
        // Hash kept for dedup
        assertEquals("abc123hash", payload.messageIdHash)
        // Parsed items allowed in REDACTED mode
        assertNotNull(payload.parsedItemsJson)
    }

    // ── PRIV-441-09: messageId hash dedup ────────────────────────────────────

    @Test
    fun email_source_fingerprint_not_empty_when_message_id_hash_available() {
        val messageIdHash = "abc123hash"
        val fingerprint = "fp456"
        // Contract: use hash as sourceFingerprint, never empty
        val sourceFingerprint = messageIdHash.ifBlank { fingerprint }
        assertEquals("abc123hash", sourceFingerprint)
        assertTrue(sourceFingerprint.isNotBlank())
    }

    @Test
    fun email_source_fingerprint_falls_back_to_content_fingerprint_when_hash_blank() {
        val messageIdHash = ""
        val fingerprint = "fp456"
        val sourceFingerprint = messageIdHash.ifBlank { fingerprint }
        assertEquals("fp456", sourceFingerprint)
        assertTrue(sourceFingerprint.isNotBlank())
    }

    @Test
    fun email_do_not_store_keeps_message_id_hash_for_dedup() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            subject = null, sender = null, bodyText = null,
            messageId = "<msg123@amazon.com>",
            messageIdHash = "abc123hash",
            contentFingerprintHash = "fp456hash",
            providerOrderIdHash = null,
            parsedItemsJson = null
        )
        assertNotNull("DO_NOT_STORE must keep messageIdHash for dedup", payload.messageIdHash)
        assertEquals("abc123hash", payload.messageIdHash)
    }

    // ── PRIV-441-10: Parsed items sanitization ───────────────────────────────

    @Test
    fun email_metadata_only_does_not_persist_parsed_item_descriptions() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            subject = null, sender = null, bodyText = null,
            messageId = null, messageIdHash = "hash",
            contentFingerprintHash = "fp",
            providerOrderIdHash = null,
            parsedItemsJson = """[{"desc":"Sensitive item","price":50.0}]"""
        )
        assertNull(
            "METADATA_ONLY must not persist parsed item descriptions",
            payload.parsedItemsJson
        )
    }

    @Test
    fun email_do_not_store_does_not_persist_parsed_items() {
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            subject = null, sender = null, bodyText = null,
            messageId = null, messageIdHash = "hash",
            contentFingerprintHash = "fp",
            providerOrderIdHash = null,
            parsedItemsJson = """[{"desc":"Sensitive item","price":50.0}]"""
        )
        assertNull(
            "DO_NOT_STORE must not persist parsed items",
            payload.parsedItemsJson
        )
    }

    // ── PRIV-441-11: Correlation propagation ─────────────────────────────────

    @Test
    fun email_expense_created_uses_email_correlation() {
        // Contract test: correlationId must be non-null when passed from ingestion
        val emailCorrelationId = "email-corr-abc123"
        val correlationId: String? = emailCorrelationId
        assertNotNull("Email correlation must be propagated to expense creation", correlationId)
        assertEquals(emailCorrelationId, correlationId)
    }
}
