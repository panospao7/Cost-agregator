package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.PrivacyAuditDao
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivacyAuditLoggerImpl @Inject constructor(
    private val dao: PrivacyAuditDao,
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier
) : PrivacyAuditLogger {

    private val allowedContextKeys = setOf(
        "operation", "caller", "entityType", "entityId",
        "provider", "modelId", "payloadHash", "receiptId",
        // P8F-03: cloud-call provenance fields (non-sensitive booleans/ids/enums)
        "purpose", "redactionApplied", "rawTextIncluded", "rawImageIncluded", "correlationId"
    )

    /** Open coalescing window for one ALLOWED NOTIFICATION_CAPTURE key (guarded by [coalesceMutex]). */
    private class CoalesceWindow(
        val capabilityName: String,
        val context: Map<String, String>,
        val windowStartMs: Long,
        var pendingCount: Int
    )

    /** A row to insert plus the in-memory state change to apply only after its insert succeeds. */
    private class PlannedRow(val event: PrivacyAuditEvent, val onInserted: () -> Unit)

    private val coalesceMutex = Mutex()

    // Access-ordered (LRU) map; only touched while holding [coalesceMutex].
    private val windows = LinkedHashMap<String, CoalesceWindow>(16, 0.75f, true)

    /**
     * Records one audit row per decision, except repeated ALLOWED NOTIFICATION_CAPTURE decisions.
     * Those are coalesced into one row per 60-minute window per key (capability + caller +
     * sanitized context): the first decision of a window is written, repeats are counted and
     * reported as `coalescedCount` either on the first row of the following window or on a
     * flush row written before any non-coalescible decision or on key eviction.
     * DENIED / FAIL_CLOSED (stored DENIED_FAIL_CLOSED) / NOT_APPLICABLE and every other
     * capability are always written individually; a non-coalescible write first flushes any
     * pending counts so ordering is preserved, and a non-coalescible NOTIFICATION_CAPTURE
     * decision then closes the capture windows so the first ALLOWED after it is written again.
     *
     * Row semantics: a flush row (context `coalescedFlush` = "true") is not itself a decision;
     * it represents exactly `coalescedCount` suppressed decisions. A window-opening row with
     * `coalescedCount` K represents itself plus K suppressed decisions of the previous window.
     * Both carry `coalescedSinceMs` = the start of the window the count belongs to.
     * `coalescedCount`, `coalescedFlush` and `coalescedSinceMs` are added after sanitisation,
     * so callers cannot spoof them.
     *
     * Counts are approximate. The pending count is held in memory only: if the process dies
     * before it is flushed (less than one window, i.e. under 1 h of suppressed decisions), that
     * count is lost, so `coalescedCount` is a lower bound across process death. Cancellation
     * after a committed insert but before the in-memory reset can make a count be reported
     * twice, so it may over-count by one on cancellation after commit (never under-count).
     * The write barrier is checked first on every call and again once the lock is held.
     */
    override suspend fun logDecision(
        capability: PrivacyCapability,
        decision: PrivacyDecision,
        context: Map<String, String>
    ) {
        // G3-S2: audit rows are DB writes; during a maintenance window the barrier rejects them.
        // CompositePrivacyGate maps the resulting exception to FailClosed(PRIVACY_AUDIT_WRITE_FAILED).
        // Checked before any coalescing decision or state change.
        writeBarrier.checkWritesAllowed("PrivacyAuditLoggerImpl.logDecision")
        // P8-PR2 (P8-P1-03): Semantic audit — include a meaningful capability
        // description alongside the raw name so audit trails are human-readable.
        val decisionStr = when (decision) {
            is PrivacyDecision.Allowed -> "ALLOWED"
            is PrivacyDecision.Denied -> "DENIED"
            is PrivacyDecision.NotApplicable -> "NOT_APPLICABLE"
            is PrivacyDecision.FailClosed -> "DENIED_FAIL_CLOSED"
        }
        val reason = when (decision) {
            is PrivacyDecision.Denied -> decision.reason
            is PrivacyDecision.FailClosed -> decision.reason
            else -> null
        }
        // P8-PR2 (P8-P1-03): Add semantic context — the specific capability
        // being checked is already captured in the capability column; enrich the
        // context JSON with a human-readable description of the semantic meaning.
        val sanitizedContext = sanitizeContext(context).toMutableMap().apply {
            put("capabilityDescription", capabilityDescription(capability))
        }
        val coalescible = capability == PrivacyCapability.NOTIFICATION_CAPTURE &&
            decision is PrivacyDecision.Allowed
        coalesceMutex.withLock {
            // F3: a waiter may have queued before maintenance started; re-check under the lock.
            writeBarrier.checkWritesAllowed("PrivacyAuditLoggerImpl.logDecision")
            val now = timeProvider.now()
            val planned = mutableListOf<PlannedRow>()
            if (!coalescible) {
                // Flush pending coalesced counts first (keeps ordering); a failed flush keeps its count.
                for (pending in windows.values) {
                    if (pending.pendingCount > 0) {
                        planned += PlannedRow(flushEvent(pending, now)) { pending.pendingCount = 0 }
                    }
                }
                planned += PlannedRow(auditEvent(capability.name, decisionStr, reason, sanitizedContext, now)) {
                    if (capability == PrivacyCapability.NOTIFICATION_CAPTURE) {
                        // Pending counts were flushed above: close capture windows so the first
                        // ALLOWED after this decision (e.g. capture re-enabled) is written again.
                        windows.values.removeAll { it.capabilityName == capability.name }
                    }
                }
            } else {
                val key = coalesceKey(capability, sanitizedContext)
                val window = windows[key]
                // Clock-skew robust: a window that starts in the future (clock moved back) is expired.
                if (window != null && now >= window.windowStartMs &&
                    now < window.windowStartMs + COALESCE_WINDOW_MS
                ) {
                    window.pendingCount++
                    return@withLock
                }
                if (window == null && windows.size >= MAX_COALESCE_KEYS) {
                    val eldest = windows.entries.first()
                    if (eldest.value.pendingCount > 0) {
                        planned += PlannedRow(flushEvent(eldest.value, now)) { windows.remove(eldest.key) }
                    } else {
                        windows.remove(eldest.key)
                    }
                }
                val carried = window?.pendingCount ?: 0
                val rowContext = if (carried > 0 && window != null) {
                    sanitizedContext +
                        (COALESCED_COUNT_KEY to carried.toString()) +
                        (COALESCED_SINCE_KEY to window.windowStartMs.toString())
                } else {
                    sanitizedContext
                }
                planned += PlannedRow(auditEvent(capability.name, decisionStr, reason, rowContext, now)) {
                    // Only run after the insert succeeded: open the new window.
                    windows[key] = CoalesceWindow(capability.name, sanitizedContext, now, 0)
                }
            }
            // Single physical insert site (db_ownership_policy pins logDecision + dao.insert).
            // State changes run only after each successful insert; a failure keeps state and rethrows.
            for (row in planned) {
                dao.insert(row.event)
                row.onInserted()
            }
        }
    }

    private fun flushEvent(pending: CoalesceWindow, timestampMs: Long): PrivacyAuditEvent =
        auditEvent(
            pending.capabilityName,
            "ALLOWED",
            null,
            pending.context +
                (COALESCED_COUNT_KEY to pending.pendingCount.toString()) +
                (COALESCED_FLUSH_KEY to "true") +
                (COALESCED_SINCE_KEY to pending.windowStartMs.toString()),
            timestampMs
        )

    private fun auditEvent(
        capabilityName: String,
        decisionName: String,
        reason: String?,
        rowContext: Map<String, String>,
        timestampMs: Long
    ): PrivacyAuditEvent = PrivacyAuditEvent(
        capability = capabilityName,
        decision = decisionName,
        reason = reason,
        context = JSONObject(rowContext).toString(),
        timestampMs = timestampMs,
        caller = AUDIT_CALLER
    )

    private fun coalesceKey(capability: PrivacyCapability, sanitizedContext: Map<String, String>): String =
        buildString {
            append(capability.name).append('|').append(AUDIT_CALLER)
            for ((k, v) in sanitizedContext.toSortedMap()) {
                append('|').append(k.length).append(':').append(k)
                append('=').append(v.length).append(':').append(v)
            }
        }

    /**
     * P8-PR2 (P8-P1-03): Returns a human-readable description of [capability]
     * for inclusion in audit-log context. This makes audit trails semantic
     * instead of requiring readers to look up raw enum names.
     */
    private fun capabilityDescription(capability: PrivacyCapability): String = when (capability) {
        PrivacyCapability.NOTIFICATION_CAPTURE -> "Capture incoming notification content"
        PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST -> "Filter notifications by package allowlist"
        PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST -> "Cloud AI receipt data extraction assist"
        PrivacyCapability.CLOUD_AI_RECEIPT_OCR -> "Cloud AI OCR receipt text processing"
        PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION -> "Cloud AI item category suggestion"
        PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION -> "Cloud AI warranty information extraction"
        PrivacyCapability.CLOUD_AI_BANK_STATEMENT -> "Cloud AI bank statement parsing"
        PrivacyCapability.AI_BANK_STATEMENT_PARSING -> "Local AI bank statement parsing"
        PrivacyCapability.CLOUD_AI_DAILY_BRIEFING -> "Cloud AI daily spending briefing"
        PrivacyCapability.CLOUD_AI_GENERAL -> "General cloud AI capability"
        PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> "Upload receipt image to cloud AI"
        PrivacyCapability.EXTERNAL_GEOCODING -> "External geocoding service (address lookup)"
        PrivacyCapability.BACKGROUND_LOCATION_BACKFILL -> "Background location history backfill"
        PrivacyCapability.DEVICE_GPS_LOCATION -> "Device GPS location access"
        PrivacyCapability.RAWBACKUP_EXPORT -> "Raw (unredacted) backup export"
        PrivacyCapability.ENCRYPTED_BACKUP -> "Encrypted backup export"
        PrivacyCapability.RAW_NOTIFICATION_RETENTION -> "Raw notification text retention period"
        PrivacyCapability.RAW_OCR_RETENTION -> "Raw OCR text retention period"
        PrivacyCapability.DEBUG_DATA_PERSISTENCE -> "Debug data persistence (PII logging)"
        PrivacyCapability.OVERPASS_API -> "Overpass API (OpenStreetMap data query)"
        PrivacyCapability.TIMBER_PII_LOGGING -> "Timber log output containing PII"
        PrivacyCapability.EXPENSE_EXPORT -> "Standard expense export (CSV/JSON)"
        PrivacyCapability.EXPENSE_EXPORT_RAW -> "Raw (unredacted) expense export"
        PrivacyCapability.EXPENSE_EXPORT_REDACTED -> "Redacted expense export"
        PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED -> "Encrypted expense export"
        PrivacyCapability.DEBUG_RAW_EXPORT -> "Debug raw export (release-disabled)"
        PrivacyCapability.RAW_DATABASE_EXPORT -> "Raw database export (release-disabled)"
    }

    private fun sanitizeContext(context: Map<String, String>): Map<String, String> {
        val sanitized = mutableMapOf<String, String>()
        for ((key, value) in context) {
            if (key in allowedContextKeys && value.length <= 200) {
                sanitized[key] = value
            }
        }
        return sanitized
    }

    private companion object {
        const val AUDIT_CALLER = "privacy-gate"
        const val COALESCED_COUNT_KEY = "coalescedCount"
        const val COALESCED_FLUSH_KEY = "coalescedFlush"
        const val COALESCED_SINCE_KEY = "coalescedSinceMs"
        const val COALESCE_WINDOW_MS = 60L * 60L * 1000L
        const val MAX_COALESCE_KEYS = 32
    }
}
