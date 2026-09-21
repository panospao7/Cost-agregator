// RP-16 16-C: Versioned per-target retention checkpoint state machine.
// Replaces the legacy boolean `completed_<target>` / ignored `completed_<target>_failed`
// checkpoint keys with durable per-target records.

package com.yourname.expensetracker.data.privacy

import android.content.SharedPreferences
import org.json.JSONObject

/** Terminal-ish states of a per-target retention checkpoint record. */
enum class RetentionCheckpointState {
    /** Target is being processed by the current run (written before the purge). */
    PENDING,
    /** Purge succeeded durably; [RetentionCheckpointRecord.rowsPurged] is measured. */
    COMPLETED,
    /** Purge failed; [RetentionCheckpointRecord.errorCode] is a controlled constant. */
    FAILED
}

/**
 * RP-16 16-C: Versioned (v2) per-target retention checkpoint record.
 *
 * Invariants:
 * - [rowsPurged] is only meaningful in [RetentionCheckpointState.COMPLETED]; a
 *   value of -1 means "unknown" and is NEVER produced from legacy booleans
 *   (booleans are never reinterpreted as counts).
 * - [errorCode] holds controlled constants only (DiagnosticReasonCode names),
 *   never raw exception messages.
 * - [attempts] records how many purge attempts this run/pass has made.
 */
data class RetentionCheckpointRecord(
    val targetName: String,
    val state: RetentionCheckpointState,
    /** Measured purge count for COMPLETED; -1 = unknown (legacy compatibility path). */
    val rowsPurged: Int = -1,
    /** True once the required privacy audit has been durably emitted. */
    val auditEmitted: Boolean = false,
    /** Controlled failure code (e.g. DiagnosticReasonCode name); null unless FAILED. */
    val errorCode: String? = null,
    /** Whether [errorCode] was classified transient (retryable). */
    val isTransient: Boolean = false,
    val attempts: Int = 0,
    /** The cutoff (epoch ms) used for the purge that produced this record. */
    val cutoffMs: Long? = null,
    /** The retention-days setting in effect for the purge that produced this record. */
    val retentionDays: Int? = null,
    val updatedAtMs: Long = 0L
)

/**
 * Durable store for per-target retention checkpoints, backed by the same
 * SharedPreferences file the legacy boolean checkpoint used
 * (`data_retention_checkpoint`).
 *
 * ## Legacy compatibility (RP-16 16-C)
 * Legacy format: boolean `completed_<target>` plus an ignored
 * `completed_<target>_failed` key and a `_cleared` sentinel.
 * - [hasAnyRecord] — true when at least one v2 record exists (v2 resume mode).
 * - [legacyResumePoint] — reproduces the legacy prefix-resume semantics over
 *   the boolean keys (only if no v2 records exist and `_cleared` is not set).
 * - Legacy booleans are read ONLY as booleans; they are never reinterpreted
 *   as counts.
 *
 * ## Cleanup policy (no blanket clear)
 * [remove] deletes exactly one target's v2 record AND its legacy boolean
 * keys. The blanket `preferences.clear()` of the legacy implementation is
 * gone; cleanup happens per target, only once its desired durable states
 * (COMPLETED + emitted audit) are reached.
 */
class RetentionCheckpointStore(private val prefs: SharedPreferences) {

    // ── v2 record API ─────────────────────────────────────────────────

    fun load(targetName: String): RetentionCheckpointRecord? {
        val raw = prefs.getString(v2Key(targetName), null) ?: return null
        return try {
            decode(targetName, raw)
        } catch (e: Exception) {
            // Corrupt record → treat as absent; the purge is idempotent so the
            // target is simply re-processed. Never surface raw payloads.
            null
        }
    }

    /**
     * Persist a record. Returns false when the durable write did not commit —
     * callers must treat the checkpoint as NOT persisted (safe non-durable
     * state + diagnostic), never as silent success.
     */
    fun save(record: RetentionCheckpointRecord): Boolean =
        prefs.edit().putString(v2Key(record.targetName), encode(record)).commit()

    /** Removes one target's v2 record and its legacy boolean keys. */
    fun remove(targetName: String): Boolean = prefs.edit()
        .remove(v2Key(targetName))
        .remove(legacyCompleteKey(targetName))
        .remove(legacyFailedKey(targetName))
        .commit()

    fun allRecordedTargetNames(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(V2_KEY_PREFIX) }
            .map { it.removePrefix(V2_KEY_PREFIX) }
            .sorted()

    fun hasAnyRecord(): Boolean = allRecordedTargetNames().isNotEmpty()

    // ── Legacy compatibility ──────────────────────────────────────────

    /** True when the legacy `_cleared` sentinel is set (legacy checkpoints void). */
    fun isLegacyCleared(): Boolean = prefs.getBoolean(LEGACY_CLEARED_KEY, false)

    /**
     * Legacy boolean "completed" read for a target. Only meaningful when no
     * v2 record exists for that target.
     */
    fun legacyIsComplete(targetName: String): Boolean =
        prefs.getBoolean(legacyCompleteKey(targetName), false)

    /**
     * Reproduces the legacy resume semantics: a checkpoint is active only if a
     * non-empty prefix of targets is marked complete; the first incomplete
     * target is the resume point. Returns null when no legacy checkpoint is
     * active. Boolean keys are never reinterpreted as counts.
     */
    fun legacyResumePoint(orderedTargetNames: List<String>): String? {
        if (isLegacyCleared()) return null
        var foundComplete = false
        for (name in orderedTargetNames) {
            if (!legacyIsComplete(name)) {
                return if (foundComplete) name else null
            }
            foundComplete = true
        }
        return null
    }

    // ── Encoding ──────────────────────────────────────────────────────

    private fun encode(r: RetentionCheckpointRecord): String = JSONObject().apply {
        put(VERSION_FIELD, VERSION)
        put("state", r.state.name)
        put("rowsPurged", r.rowsPurged)
        put("auditEmitted", r.auditEmitted)
        put("errorCode", r.errorCode)
        put("transient", r.isTransient)
        put("attempts", r.attempts)
        put("cutoffMs", r.cutoffMs ?: -1L)
        put("retentionDays", r.retentionDays ?: -1)
        put("updatedAtMs", r.updatedAtMs)
    }.toString()

    private fun decode(targetName: String, raw: String): RetentionCheckpointRecord {
        val o = JSONObject(raw)
        if (o.optInt(VERSION_FIELD, -1) != VERSION) {
            // Unsupported/unknown record version — unreadable, treated as absent.
            throw IllegalArgumentException("unsupported checkpoint record version")
        }
        val cutoff = o.optLong("cutoffMs", -1L)
        val retentionDays = o.optInt("retentionDays", -1)
        return RetentionCheckpointRecord(
            targetName = targetName,
            state = RetentionCheckpointState.valueOf(o.getString("state")),
            rowsPurged = o.optInt("rowsPurged", -1),
            auditEmitted = o.optBoolean("auditEmitted", false),
            errorCode = if (o.isNull("errorCode")) null else o.optString("errorCode"),
            isTransient = o.optBoolean("transient", false),
            attempts = o.optInt("attempts", 0),
            cutoffMs = if (cutoff >= 0) cutoff else null,
            retentionDays = if (retentionDays >= 0) retentionDays else null,
            updatedAtMs = o.optLong("updatedAtMs", 0L)
        )
    }

    private fun v2Key(targetName: String) = "$V2_KEY_PREFIX$targetName"
    private fun legacyCompleteKey(targetName: String) = "$LEGACY_PREFIX$targetName"
    private fun legacyFailedKey(targetName: String) = "${LEGACY_PREFIX}${targetName}_failed"

    private companion object {
        const val VERSION = 2
        const val VERSION_FIELD = "v"
        const val V2_KEY_PREFIX = "checkpoint_v2_"
        const val LEGACY_PREFIX = "completed_"
        const val LEGACY_CLEARED_KEY = "_cleared"
    }
}
