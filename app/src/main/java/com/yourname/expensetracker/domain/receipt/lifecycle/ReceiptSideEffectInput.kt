package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.privacy.RawStorageMode

/**
 * Explicit input contract for receipt side effects so that raw-dependent
 * effects never silently degrade under restricted privacy modes.
 *
 * P3-BLOCKER-D: Replaces implicit use of persisted [ScannedReceipt.rawOcrText].
 */
data class ReceiptSideEffectInput(
    val receipt: ScannedReceipt,
    val ephemeralRawOcrText: String?,
    val ephemeralEmailBody: String? = null,
    val rawStorageMode: RawStorageMode,
    val correlationId: String?
) {
    /** True when an actual ephemeral (pre-sanitization) raw OCR text is available. */
    val hasEphemeralRaw: Boolean get() = !ephemeralRawOcrText.isNullOrBlank()
}
