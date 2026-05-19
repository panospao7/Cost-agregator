package com.yourname.expensetracker.domain.privacy

/**
 * Sanitized payload for persisting a bank transaction/import according to the
 * current raw storage policy.
 *
 * Key invariants:
 * - [rawDescription] and [rawReference] are null unless STORE_RAW.
 * - [counterpartyHash] is an HMAC hash of the counterparty/transferAccountName.
 * - [providerTransactionIdHash] is an HMAC hash of the provider transaction ID.
 * - [accountIdHash] is an HMAC hash of the bank account ID.
 * - Notes must never contain raw bank description text unless STORE_RAW.
 * - Bank tokens must NEVER appear in this payload — they must be encrypted elsewhere.
 */
data class BankTransactionPersistencePayload(
    /** Raw description — null unless STORE_RAW. */
    val redactedDescription: String?,
    /** Raw reference — null unless STORE_RAW. */
    val redactedReference: String?,
    /** HMAC hash of counterparty — always present when counterparty is available. */
    val counterpartyHash: String?,
    /** HMAC hash of providerTransactionId — always present. */
    val providerTransactionIdHash: String?,
    /** HMAC hash of accountId — always present when accountId is available. */
    val accountIdHash: String?,
    /** Safe notes — never raw bank description unless STORE_RAW. */
    val notes: String?,
    val mode: RawStorageMode
) {
    companion object {
        fun build(
            mode: RawStorageMode,
            rawDescription: String?,
            rawReference: String?,
            counterpartyHash: String?,
            providerTransactionIdHash: String?,
            accountIdHash: String?,
            notes: String?
        ): BankTransactionPersistencePayload = when (mode) {
            RawStorageMode.STORE_RAW -> BankTransactionPersistencePayload(
                redactedDescription = rawDescription,
                redactedReference = rawReference,
                counterpartyHash = counterpartyHash,
                providerTransactionIdHash = providerTransactionIdHash,
                accountIdHash = accountIdHash,
                notes = notes,
                mode = mode
            )
            RawStorageMode.STORE_REDACTED -> BankTransactionPersistencePayload(
                redactedDescription = if (rawDescription != null) "[REDACTED]" else null,
                redactedReference = if (rawReference != null) "[REDACTED]" else null,
                counterpartyHash = counterpartyHash,
                providerTransactionIdHash = providerTransactionIdHash,
                accountIdHash = accountIdHash,
                // Notes: redact if they were copied from raw description
                notes = if (notes != null) "[REDACTED]" else null,
                mode = mode
            )
            RawStorageMode.STORE_METADATA_ONLY -> BankTransactionPersistencePayload(
                redactedDescription = null,
                redactedReference = null,
                counterpartyHash = counterpartyHash,
                providerTransactionIdHash = providerTransactionIdHash,
                accountIdHash = accountIdHash,
                notes = null,
                mode = mode
            )
            RawStorageMode.DO_NOT_STORE -> BankTransactionPersistencePayload(
                redactedDescription = null,
                redactedReference = null,
                counterpartyHash = counterpartyHash,
                providerTransactionIdHash = providerTransactionIdHash,
                accountIdHash = accountIdHash,
                notes = null,
                mode = mode
            )
        }

        /**
         * Build from raw bank transaction fields, computing hashes via [SensitiveHashingService].
         */
        fun buildWithHashing(
            mode: RawStorageMode,
            rawDescription: String?,
            rawReference: String?,
            counterparty: String?,
            providerTransactionId: String?,
            accountId: String?,
            notes: String?,
            hashService: SensitiveHashingService
        ): BankTransactionPersistencePayload = build(
            mode = mode,
            rawDescription = rawDescription,
            rawReference = rawReference,
            counterpartyHash = hashService.hmacSha256Prefix(counterparty, "bankCounterparty"),
            providerTransactionIdHash = hashService.hmacSha256Prefix(providerTransactionId, "providerTransactionId"),
            accountIdHash = hashService.hmacSha256Prefix(accountId, "bankAccountId"),
            notes = notes
        )
    }
}
