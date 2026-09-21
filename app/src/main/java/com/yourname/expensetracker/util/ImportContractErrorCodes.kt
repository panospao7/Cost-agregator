package com.yourname.expensetracker.util

/**
 * RP-19 (19-B): controlled reason codes for import roundtrip contract
 * violations. These are constants, never interpolated exception messages, so
 * diagnostics stay free of file paths, SQL text, or user financial payloads.
 */
object ImportContractErrorCodes {
    /** A present but unparseable `transactionType` value (CSV and JSON import). */
    const val UNKNOWN_TRANSACTION_TYPE = "UNKNOWN_TRANSACTION_TYPE"

    /** Neither `amount` nor `effectiveAmount` parses for a JSON row (CSV parity: the row fails). */
    const val INVALID_AMOUNT = "INVALID_AMOUNT"
}

/**
 * Raised by import row parsers when the RP-19 roundtrip contract (see
 * `docs/analyses and debug master/remediation/RP-19-ROUNDTRIP-MATRIX.md`) is
 * violated. [code] is always a controlled constant from
 * [ImportContractErrorCodes].
 */
class ImportContractException(val code: String) : RuntimeException(code)
