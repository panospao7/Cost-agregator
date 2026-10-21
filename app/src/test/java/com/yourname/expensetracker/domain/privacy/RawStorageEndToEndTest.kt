package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import org.junit.Assert.*
import org.junit.Test

/**
 * PR3: End-to-end raw-storage persistence tests for the hashing path.
 *
 * B6-8: the per-source x per-mode payload matrix (including sentinel-absence checks)
 * lives in RawStoragePayloadMatrixTest. Only the bank buildWithHashing tests, which use
 * the real hashing service, remain here. Real SafePrivacyMetadata/diagnostics sanitizer
 * coverage lives in [SafePrivacyMetadataValueSafetyTest] (and the EventMetadataSanitizer
 * tests); the former diagnostics test here was vacuous and was deleted in B6-8.
 */
class RawStorageEndToEndTest {

    private val hashService = DefaultSensitiveHashingService()

    // ── Bank ──────────────────────────────────────────────────────────────────

    private val BANK_DESC = "SECRET_BANK_DESCRIPTION_XYZ"
    private val BANK_REF = "SECRET_BANK_REFERENCE_XYZ"
    private val BANK_ACCOUNT = "secret-account-id-XYZ"
    private val BANK_TXN_ID = "secret-provider-transaction-id-XYZ"
    private val BANK_COUNTERPARTY = "Secret Counterparty XYZ"

    @Test
    fun bank_do_not_store_no_raw_description_reference_counterparty() {
        val payload = BankTransactionPersistencePayload.buildWithHashing(
            mode = RawStorageMode.DO_NOT_STORE,
            rawDescription = BANK_DESC, rawReference = BANK_REF,
            counterparty = BANK_COUNTERPARTY, providerTransactionId = BANK_TXN_ID,
            accountId = BANK_ACCOUNT, notes = BANK_DESC, hashService = hashService
        )
        assertNull(payload.redactedDescription)
        assertNull(payload.redactedReference)
        assertNull(payload.notes)
        // Hashes must not equal raw values
        assertNotEquals(BANK_TXN_ID, payload.providerTransactionIdHash)
        assertNotEquals(BANK_ACCOUNT, payload.accountIdHash)
        assertNotEquals(BANK_COUNTERPARTY, payload.counterpartyHash)
    }

    @Test
    fun bank_provider_transaction_id_stored_as_hash_only() {
        val payload = BankTransactionPersistencePayload.buildWithHashing(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawDescription = BANK_DESC, rawReference = null,
            counterparty = null, providerTransactionId = BANK_TXN_ID,
            accountId = null, notes = null, hashService = hashService
        )
        assertNull(payload.redactedDescription)
        assertNotNull(payload.providerTransactionIdHash)
        // B6 nit (38): the input is lowercase, so contains("SECRET") could never fail. Pin the
        // exact HMAC the real service yields for the production purpose, and that it is not the raw id.
        assertEquals(
            hashService.hmacSha256Prefix(BANK_TXN_ID, "providerTransactionId"),
            payload.providerTransactionIdHash
        )
        assertNotEquals(BANK_TXN_ID, payload.providerTransactionIdHash)
        assertFalse(payload.providerTransactionIdHash!!.contains(BANK_TXN_ID, ignoreCase = true))
    }

    @Test
    fun bank_account_id_stored_as_hash_only() {
        val payload = BankTransactionPersistencePayload.buildWithHashing(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawDescription = null, rawReference = null,
            counterparty = null, providerTransactionId = null,
            accountId = BANK_ACCOUNT, notes = null, hashService = hashService
        )
        assertNotNull(payload.accountIdHash)
        // B6 nit (38): exact HMAC for the production purpose (the old contains("SECRET") check was vacuous).
        assertEquals(
            hashService.hmacSha256Prefix(BANK_ACCOUNT, "bankAccountId"),
            payload.accountIdHash
        )
        assertFalse(payload.accountIdHash!!.contains(BANK_ACCOUNT, ignoreCase = true))
        assertNotEquals(BANK_ACCOUNT, payload.accountIdHash)
    }
}
