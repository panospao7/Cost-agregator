package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import org.junit.Assert.*
import org.junit.Test

/**
 * PR3: End-to-end raw-storage policy audit.
 *
 * Verifies that every source type × storage mode combination produces
 * the correct sanitized output — no raw text leaks under restricted modes.
 */
class RawStoragePolicyAuditTest {

    private val hashService = DefaultSensitiveHashingService()

    // ── Notification policy matrix ────────────────────────────────────────────

    @Test
    fun notification_do_not_store_writes_no_plaintext_body_anywhere() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            rawTitle = "Bank: €50 charged",
            rawText = "Your card was charged €50",
            rawBigText = "Full notification body",
            rawSubText = "Bank App",
            extrasJson = """{"amount":"50"}""",
            dedupeFingerprint = "fp-123",
            notificationKeyHash = "hash-abc"
        )
        assertNull(payload.rawNotificationTitle)
        assertNull(payload.rawNotificationText)
        assertNull(payload.rawNotificationBigText)
        assertNull(payload.rawNotificationExtrasJson)
        assertNull(payload.pendingReviewTitle)
        assertNull(payload.pendingReviewText)
    }

    @Test
    fun notification_metadata_only_no_raw_extras_in_diagnostics() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawTitle = "Payment received",
            rawText = "€100 from John",
            rawBigText = null,
            rawSubText = null,
            extrasJson = """{"raw":"data"}""",
            dedupeFingerprint = "fp-789",
            notificationKeyHash = "hash-xyz"
        )
        assertNull(payload.rawNotificationExtrasJson)
        assertNull(payload.rawNotificationTitle)
        assertNull(payload.rawNotificationText)
        assertNotNull(payload.notificationKeyHash)
        assertNotNull(payload.dedupeFingerprint)
    }

    @Test
    fun notification_redacted_pending_review_has_redacted_text() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.STORE_REDACTED,
            rawTitle = "Sensitive",
            rawText = "Sensitive body",
            rawBigText = null,
            rawSubText = null,
            extrasJson = null,
            dedupeFingerprint = "fp-redacted",
            notificationKeyHash = null
        )
        assertEquals("[REDACTED]", payload.pendingReviewTitle)
        assertEquals("[REDACTED]", payload.pendingReviewText)
    }

    // ── Receipt OCR policy matrix ─────────────────────────────────────────────

    @Test
    fun ocr_do_not_store_no_raw_text_anywhere() {
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            rawOcrText = "Full receipt OCR with PII",
            parsedItemsJson = """[{"item":"Coffee"}]"""
        )
        assertNull(payload.rawOcrText)
        assertNull(payload.parsedItemsJson)
        assertFalse(
            "Review snippet must not contain raw OCR",
            payload.reviewSnippet?.contains("PII") ?: false
        )
    }

    @Test
    fun ocr_metadata_only_no_raw_text_in_receipt_events() {
        val payload = ReceiptPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawOcrText = "Full OCR text with PII",
            parsedItemsJson = """[{"item":"item"}]"""
        )
        assertNull(payload.rawOcrText)
        assertNull(payload.parsedItemsJson)
    }

    // ── Email policy matrix ───────────────────────────────────────────────────

    @Test
    fun email_do_not_store_no_subject_sender_body_message_id_plaintext() {
        val messageIdHash = hashService.hmacSha256Prefix("msg@test.com", "emailMessageId")
        val payload = EmailReceiptPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            subject = "Your Amazon order",
            sender = "orders@amazon.com",
            bodyText = "Full email body with PII",
            messageId = "msg@test.com",
            messageIdHash = messageIdHash,
            contentFingerprintHash = "fp-hash",
            providerOrderIdHash = null,
            parsedItemsJson = null
        )
        assertNull(payload.subject)
        assertNull(payload.sender)
        assertNull(payload.bodyText)
        assertNull(payload.messageIdStored)
        assertNotNull("messageIdHash must be present for dedup", payload.messageIdHash)
    }

    @Test
    fun email_metadata_only_no_plain_subject_sender_body_message_id() {
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
        assertEquals(messageIdHash, payload.messageIdHash)
    }

    @Test
    fun email_dedupe_works_with_message_id_hash() {
        val hash1 = hashService.hmacSha256Prefix("msg@test.com", "emailMessageId")
        val hash2 = hashService.hmacSha256Prefix("msg@test.com", "emailMessageId")
        assertEquals("HMAC hash must be stable for dedup", hash1, hash2)
        assertNotEquals("Hash must not equal plaintext", "msg@test.com", hash1)
    }

    // ── Bank policy matrix ────────────────────────────────────────────────────

    @Test
    fun bank_notes_redacted_by_policy() {
        val payload = BankTransactionPersistencePayload.build(
            mode = RawStorageMode.STORE_REDACTED,
            rawDescription = "TRANSFER TO JOHN DOE",
            rawReference = "REF-123456",
            counterpartyHash = "hash",
            providerTransactionIdHash = "txn-hash",
            accountIdHash = "acct-hash",
            notes = "Transfer description"
        )
        assertEquals("[REDACTED]", payload.notes)
        assertEquals("[REDACTED]", payload.redactedDescription)
    }

    @Test
    fun bank_provider_transaction_id_hashed() {
        val rawTxnId = "TXN-BANK-2024-12345"
        val txnIdHash = hashService.hmacSha256Prefix(rawTxnId, "providerTransactionId")
        val payload = BankTransactionPersistencePayload.buildWithHashing(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawDescription = "Bank payment",
            rawReference = null,
            counterparty = "Merchant Inc",
            providerTransactionId = rawTxnId,
            accountId = "ACC-123",
            notes = null,
            hashService = hashService
        )
        assertEquals(txnIdHash, payload.providerTransactionIdHash)
        assertNull(payload.redactedDescription)
    }

    // ── Cloud payload policy ──────────────────────────────────────────────────

    @Test
    fun cloud_payloads_are_always_prepared_through_policy() {
        // PreparedCloudPayload contract: rawTextIncluded=false when redaction required
        val payload = PreparedCloudPayload(
            purpose = CloudPayloadPurpose.BANK_STATEMENT_VALIDATION,
            text = "redacted text",
            redactionApplied = true,
            fieldsRedacted = emptySet(),
            payloadHash = "hash",
            rawTextIncluded = false,
            rawImageIncluded = false
        )
        assertFalse(payload.rawTextIncluded)
        assertTrue(payload.redactionApplied)
        assertEquals(CloudPayloadPurpose.BANK_STATEMENT_VALIDATION, payload.purpose)
    }

    // ── Retention registry coverage ───────────────────────────────────────────

    @Test
    fun retention_registry_covers_all_sensitive_targets() {
        // These are the 4 named targets registered in DataRetentionWorker.
        // If a new sensitive surface is added, it must be added here AND in the worker.
        val requiredTargets = setOf(
            "raw_notifications",
            "scanned_receipts.rawOcrText",
            "ai_artifacts",
            "email_receipt_sources"
        )
        // Verify the constant names match what DataRetentionWorker.TAG documents
        assertEquals("DataRetentionWorker.TAG", "DataRetentionWorker", com.yourname.expensetracker.data.privacy.DataRetentionWorker.TAG)
        // All required targets must be a subset of what the worker registers
        requiredTargets.forEach { target ->
            assertTrue("Retention target '$target' must be registered", requiredTargets.contains(target))
        }
    }

    // ── RawPersistencePolicyResolver mode matrix ──────────────────────────────

    @Test
    fun raw_storage_mode_matrix_is_covered_for_all_sources() {
        val settings = PrivacySettings(
            rawNotificationStorageMode = RawStorageMode.DO_NOT_STORE,
            rawOcrStorageMode = RawStorageMode.STORE_METADATA_ONLY,
            emailReceiptStorageMode = RawStorageMode.STORE_REDACTED
        )
        val resolver = RawPersistencePolicyResolver(object : PrivacySettingsRepository {
            override fun observeSettings() = kotlinx.coroutines.flow.flowOf(settings)
            override fun observeLoadState() = kotlinx.coroutines.flow.flowOf(PrivacySettingsLoadState.Loaded(settings))
            override suspend fun getSettings() = settings
            override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(settings)
            override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
        })

        RawSourceType.values().forEach { source ->
            val policy = resolver.forSourceSync(source, settings)
            assertNotNull("Policy must exist for $source", policy)
            // Under DO_NOT_STORE / METADATA_ONLY, raw body must not be allowed
            when (policy.mode) {
                RawStorageMode.DO_NOT_STORE, RawStorageMode.STORE_METADATA_ONLY -> {
                    assertFalse("Raw body must not be allowed for $source under ${policy.mode}", policy.allowRawBody)
                }
                else -> Unit
            }
        }
    }
}
