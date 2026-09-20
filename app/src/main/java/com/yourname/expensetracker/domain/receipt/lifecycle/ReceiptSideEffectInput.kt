package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.ReceiptParser

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
    /**
     * RP-12 12b (P3-007) conditional remainder: the minimum in-memory
     * ephemeral projection of the freshly parsed line items, carried ONLY
     * for a FRESH insert under a restricted storage mode so the post-commit
     * categorization action can run. It must never be persisted, logged, or
     * included in diagnostics, and it never outlives the process: a batch
     * executed without it (e.g. after process restart) falls back to the
     * permitted persisted representation or the controlled
     * STRUCTURED_RECEIPT_DATA_UNAVAILABLE skip — it is never retried from
     * raw data. Duplicates never receive a plan, so they never carry it.
     * The planner (policy owner) uses the projection only under
     * STORE_REDACTED (with mode-gated output persistence); under
     * STORE_METADATA_ONLY / DO_NOT_STORE no item data may be used, and
     * STORE_RAW needs no carry — persisted items are the permitted
     * representation there.
     */
    val ephemeralParsedItems: List<ReceiptParser.LineItem>? = null,
    val rawStorageMode: RawStorageMode,
    val correlationId: String?,
    /**
     * RP-12 12a / P3-001: explicit carry-through of
     * [ReceiptLifecycleCoordinator.ReceiptProcessingOptions.autoMatchExistingExpense].
     * When false, planning omits ONLY the transaction-matching action; unrelated
     * actions (warranty, categorization, price protection) are not suppressed.
     */
    val autoMatchExistingExpense: Boolean = true
) {
    /** True when an actual ephemeral (pre-sanitization) raw OCR text is available. */
    val hasEphemeralRaw: Boolean get() = !ephemeralRawOcrText.isNullOrBlank()
}
