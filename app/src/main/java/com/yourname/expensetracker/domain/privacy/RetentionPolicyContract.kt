package com.yourname.expensetracker.domain.privacy

/**
 * RP-14 14c: policy descriptor for sensitive retention targets.
 *
 * Owned by the privacy contract — deliberately OUTSIDE
 * [com.yourname.expensetracker.di.RetentionModule]'s construction list and
 * outside [com.yourname.expensetracker.data.privacy.DataRetentionWorker]'s
 * cutoff routing, so that the registry coverage test compares TWO INDEPENDENT
 * sources of truth:
 *
 *  1. this descriptor (what policy REQUIRES to be retained-and-purged), and
 *  2. the real production registry (what the Hilt module actually constructs).
 *
 * The test asserts every descriptor appears exactly once in the real registry,
 * every registered target has a unique name, and every registered target has
 * an explicit cutoff route in the worker. Neither side is derived from the
 * other. A static/entity audit may supplement this list but never replaces
 * explicit ownership decisions for sensitive columns.
 */
object RetentionPolicyContract {

    /**
     * Every sensitive retention target that MUST be registered. Names use the
     * `<table>.<column>` convention for column-scoped targets and a numeric
     * order prefix (`10_`..`50_`) where lexicographic execution order matters
     * (snapshot nulling MUST run before row deletion).
     */
    val requiredTargets: Set<String> = setOf(
        // ── existing sensitive surfaces ────────────────────────────────────
        "raw_notifications",
        "scanned_receipts.rawOcrText",
        "ai_artifacts",
        "ai_chat_messages",
        "email_receipt_sources",
        "notification_intake",
        "pipeline_diagnostic_events",
        "pending_reviews.notificationText",
        "background_job_runs.errorMessage",
        "bank_statement_import_items.merchant",
        // ── RP-14 additions (D14-approved cutoffs) ─────────────────────────
        "10_transaction_events.snapshots",
        "20_transaction_events.rows",
        "30_operation_runs",
        "40_receipt_events",
        "50_privacy_audit_events"
    )

    /**
     * D14-approved fixed cutoffs (days) for the RP-14 targets. User-configurable
     * targets (raw notifications, raw OCR) take their retention days from
     * [PrivacySettings] instead and are intentionally absent here.
     */
    val requiredFixedCutoffDays: Map<String, Long> = mapOf(
        "10_transaction_events.snapshots" to 30L,
        "20_transaction_events.rows" to 365L,
        "30_operation_runs" to 90L,
        "40_receipt_events" to 90L,
        // COMPLIANCE-FLAGGED (P8-007): accountability ledger — 180 days only
        // with compliance sign-off.
        "50_privacy_audit_events" to 180L
    )
}
