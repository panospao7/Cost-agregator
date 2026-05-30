package com.yourname.expensetracker.domain.receipt.lifecycle

/**
 * Centralized event type constants for the receipt lifecycle.
 *
 * Every component that writes a [ReceiptEvent] must use these constants
 * instead of raw string literals to keep the audit taxonomy consistent
 * and searchable.
 *
 * P3-P1-03 / P3-NEW-08: Event taxonomy expansion.
 */
object ReceiptLifecycleEventTypes {

    // ── Front-door / intake events ──────────────────────────────────────────
    const val INPUT_RECEIVED = "INPUT_RECEIVED"
    const val VALIDATION_PASSED = "VALIDATION_PASSED"
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    // ── Processing events ───────────────────────────────────────────────────
    const val OCR_STARTED = "OCR_STARTED"
    const val OCR_COMPLETED = "OCR_COMPLETED"
    const val OCR_FAILED = "OCR_FAILED"
    const val PARSED = "PARSED"
    const val PARSE_FAILED = "PARSE_FAILED"
    const val PDF_PARTIAL = "PDF_PARTIAL"

    // ── Persistence events ──────────────────────────────────────────────────
    const val RECEIPT_SAVED = "RECEIPT_SAVED"
    const val RECEIPT_DELETED = "RECEIPT_DELETED"

    // ── Deduplication events ────────────────────────────────────────────────
    const val DUPLICATE_DETECTED = "DUPLICATE_DETECTED"

    // ── Review events ───────────────────────────────────────────────────────
    const val REVIEW_CREATED = "REVIEW_CREATED"

    // ── Linking events ──────────────────────────────────────────────────────
    const val RECEIPT_LINKED_TO_EXPENSE = "RECEIPT_LINKED_TO_EXPENSE"
    const val RECEIPT_UNLINKED_FROM_EXPENSE = "RECEIPT_UNLINKED_FROM_EXPENSE"

    // ── Matching events ─────────────────────────────────────────────────────
    const val MATCH_ATTEMPTED = "MATCH_ATTEMPTED"
    const val AUTO_MATCHED = "AUTO_MATCHED"
    const val MATCH_SUGGESTED = "MATCH_SUGGESTED"
    const val MATCH_NOT_FOUND = "MATCH_NOT_FOUND"
    const val MATCH_FAILED = "MATCH_FAILED"
    const val MATCH_SKIPPED_ALREADY_LINKED = "MATCH_SKIPPED_ALREADY_LINKED"
    // P9-P1-08: worker-side match-outcome diagnostics (previously silent)
    const val MATCH_SKIPPED_DOCUMENT_TYPE = "MATCH_SKIPPED_DOCUMENT_TYPE"
    const val AUTO_MATCH_LINK_FAILED = "AUTO_MATCH_LINK_FAILED"
    const val MATCH_REJECTED = "MATCH_REJECTED"
    const val MATCH_CLEARED = "MATCH_CLEARED"
    const val MATCH_APPROVED = "MATCH_APPROVED"

    // ── Asset / delete events ───────────────────────────────────────────────
    const val ASSET_DELETE_FAILED = "ASSET_DELETE_FAILED"

    // ── Privacy / side-effect events ────────────────────────────────────────
    const val RAW_USED_EPHEMERALLY = "RAW_USED_EPHEMERALLY"
    const val SIDE_EFFECT_SKIPPED_PRIVACY = "SIDE_EFFECT_SKIPPED_PRIVACY"

    // ── Debug export events ─────────────────────────────────────────────────
    const val DEBUG_EXPORT_ATTEMPTED = "DEBUG_EXPORT_ATTEMPTED"
    const val DEBUG_EXPORT_ALLOWED = "DEBUG_EXPORT_ALLOWED"
    const val DEBUG_EXPORT_DENIED = "DEBUG_EXPORT_DENIED"
}
