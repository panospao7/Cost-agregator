package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.data.security.BankTokenCipher
import org.junit.Assert.*
import org.junit.Test

/**
 * PR5 acceptance tests:
 *
 * bank_notes_redacted_when_policy_requires
 * transfer_account_name_not_raw_description
 * provider_transaction_id_stored_as_hash_in_events
 * sync_error_does_not_include_raw_bank_description
 * statement_debug_data_respects_raw_ocr_policy
 * plaintext_bank_token_marked_invalid_and_wiped
 * restored_undecryptable_token_marks_reauth_required
 */
class BankPrivacyHardeningTest {

    private val hashService = DefaultSensitiveHashingService()

    // ── BankTransactionPersistencePayload ─────────────────────────────────────

    @Test
    fun bank_notes_redacted_when_policy_requires() {
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
        assertEquals("[REDACTED]", payload.redactedReference)
    }

    @Test
    fun transfer_account_name_not_raw_description() {
        // When mode is not STORE_RAW, description must not be the counterparty name
        val payload = BankTransactionPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawDescription = "JOHN DOE IBAN GR1234",
            rawReference = null,
            counterpartyHash = hashService.hmacSha256Prefix("JOHN DOE", "bankCounterparty"),
            providerTransactionIdHash = "hash",
            accountIdHash = "hash",
            notes = null
        )
        assertNull("Description must not be raw in METADATA_ONLY", payload.redactedDescription)
        assertNotNull("counterpartyHash must be present", payload.counterpartyHash)
        assertNotEquals("counterpartyHash must not equal raw name", "JOHN DOE", payload.counterpartyHash)
    }

    @Test
    fun provider_transaction_id_stored_as_hash_in_events() {
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
        assertNull("Raw description must be omitted in METADATA_ONLY", payload.redactedDescription)
    }

    @Test
    fun do_not_store_redacts_description_and_reference() {
        val payload = BankTransactionPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            rawDescription = "Sensitive bank description",
            rawReference = "REF-SENSITIVE",
            counterpartyHash = "hash",
            providerTransactionIdHash = "txn-hash",
            accountIdHash = "acct-hash",
            notes = "Notes text"
        )
        assertNull(payload.redactedDescription)
        assertNull(payload.redactedReference)
        assertNull(payload.notes)
        // Hashes still present for dedup
        assertEquals("hash", payload.counterpartyHash)
        assertEquals("txn-hash", payload.providerTransactionIdHash)
        assertEquals("acct-hash", payload.accountIdHash)
    }

    @Test
    fun store_raw_preserves_description_and_reference() {
        val payload = BankTransactionPersistencePayload.build(
            mode = RawStorageMode.STORE_RAW,
            rawDescription = "Bank: Coffee Shop",
            rawReference = "REF-001",
            counterpartyHash = "hash",
            providerTransactionIdHash = "txn-hash",
            accountIdHash = "acct-hash",
            notes = "My notes"
        )
        assertEquals("Bank: Coffee Shop", payload.redactedDescription)
        assertEquals("REF-001", payload.redactedReference)
        assertEquals("My notes", payload.notes)
    }

    @Test
    fun account_id_stored_as_hash_not_plaintext() {
        val rawAccountId = "GR1234567890123456789012345"
        val payload = BankTransactionPersistencePayload.buildWithHashing(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawDescription = null,
            rawReference = null,
            counterparty = null,
            providerTransactionId = "txn-001",
            accountId = rawAccountId,
            notes = null,
            hashService = hashService
        )
        assertNotEquals(rawAccountId, payload.accountIdHash)
        assertNotNull(payload.accountIdHash)
        assertEquals(24, payload.accountIdHash!!.length)
    }

    // ── BankTokenCipher invariants ────────────────────────────────────────────

    @Test
    fun plaintext_bank_token_is_identified_as_unencrypted() {
        val plaintextToken = "oauth-token-abc123-plaintext"
        assertFalse(BankTokenCipher.isEncrypted(plaintextToken))
    }

    @Test
    fun encrypted_token_is_identified_correctly() {
        // Simulate what an encrypted token looks like (without actually calling KeyStore in unit tests)
        val fakeEncryptedToken = "enc:v1:aGVsbG8=:d29ybGQ="
        assertTrue(BankTokenCipher.isEncrypted(fakeEncryptedToken))
    }

    @Test
    fun null_token_is_not_encrypted() {
        assertFalse(BankTokenCipher.isEncrypted(null))
    }

    @Test
    fun undecryptable_token_decrypts_to_null() {
        // A corrupted/invalid encrypted token should return null, not throw
        val corruptToken = "enc:v1:notbase64!!:notbase64!!"
        val result = BankTokenCipher.decryptIfNeeded(corruptToken)
        assertNull("Undecryptable token must return null (REAUTH_REQUIRED state)", result)
    }

    @Test
    fun statement_debug_data_uses_do_not_store_when_debug_disabled() {
        val resolver = RawPersistencePolicyResolver(
            object : PrivacySettingsRepository {
                private val s = PrivacySettings(
                    rawOcrStorageMode = RawStorageMode.STORE_RAW,
                    debugDataPersistenceEnabled = false
                )
                override fun observeSettings() = kotlinx.coroutines.flow.flowOf(s)
                override fun observeLoadState() = kotlinx.coroutines.flow.flowOf(PrivacySettingsLoadState.Loaded(s))
                override suspend fun getSettings() = s
                override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(s)
                override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
            }
        )
        val policy = resolver.forSourceSync(
            RawSourceType.EXPORT_DEBUG,
            PrivacySettings(rawOcrStorageMode = RawStorageMode.STORE_RAW, debugDataPersistenceEnabled = false)
        )
        assertEquals(RawStorageMode.DO_NOT_STORE, policy.mode)
        assertFalse(policy.allowDebugBody)
    }

    @Test
    fun bank_statement_source_uses_bank_statement_storage_mode() {
        val settings = PrivacySettings(
            rawOcrStorageMode = RawStorageMode.STORE_RAW,
            rawBankStatementStorageMode = RawStorageMode.STORE_METADATA_ONLY
        )
        val resolver = RawPersistencePolicyResolver(
            object : PrivacySettingsRepository {
                override fun observeSettings() = kotlinx.coroutines.flow.flowOf(settings)
                override fun observeLoadState() = kotlinx.coroutines.flow.flowOf(PrivacySettingsLoadState.Loaded(settings))
                override suspend fun getSettings() = settings
                override suspend fun getLoadState() = PrivacySettingsLoadState.Loaded(settings)
                override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
            }
        )
        val policy = resolver.forSourceSync(RawSourceType.BANK_STATEMENT, settings)
        assertEquals(RawStorageMode.STORE_METADATA_ONLY, policy.mode)
    }
}
