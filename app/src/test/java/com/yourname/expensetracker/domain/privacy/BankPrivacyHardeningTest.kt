package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.data.security.BankTokenCipher
import org.junit.Assert.*
import org.junit.Test

/**
 * PR5 acceptance tests (names below are the tests that exist in this class):
 *
 * transfer_account_name_not_raw_description
 * account_id_stored_as_hash_not_plaintext
 * plaintext_bank_token_is_identified_as_unencrypted / encrypted_token_is_identified_correctly /
 *   null_token_is_not_encrypted (BankTokenCipher.isEncrypted)
 * undecryptable_token_decrypts_to_null (BankTokenCipher.decryptIfNeeded; REAUTH_REQUIRED trigger)
 * statement_debug_data_uses_do_not_store_when_debug_disabled
 * bank_statement_source_uses_bank_statement_storage_mode
 *
 * The earlier KDoc also listed sync_error_does_not_include_raw_bank_description,
 * plaintext_bank_token_marked_invalid_and_wiped and restored_undecryptable_token_marks_reauth_required;
 * no test with those names exists in the suite (audit B6-8 nit 35; this class does not cover
 * the token wipe / re-auth state transitions or sync-error redaction).
 */
class BankPrivacyHardeningTest {

    private val hashService = DefaultSensitiveHashingService()

    // ── BankTransactionPersistencePayload ─────────────────────────────────────

    // B6-8: bank_notes_redacted_when_policy_requires, provider_transaction_id_stored_as_hash_in_events,
    // do_not_store_redacts_description_and_reference and store_raw_preserves_description_and_reference
    // moved to RawStoragePayloadMatrixTest (BANK/* rows).

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
