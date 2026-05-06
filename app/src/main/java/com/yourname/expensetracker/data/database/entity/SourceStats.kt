package com.yourname.expensetracker.data.database.entity

import androidx.room.*

/**
 * ## I12: Event-sourced audit ledger for source stats
 *
 * ### Current design (inline counters — shall be replaced)
 * Currently [SourceStats] uses mutable inline counters (`totalNotifications`,
 * `acceptedAsExpense`, `duplicates`, etc.) that are updated in-place. This means:
 * - **No audit trail** — we cannot reconstruct *when* each event happened.
 * - **No per-event metadata** — we cannot attach expense IDs or raw notification IDs
 *   to individual events.
 * - **No replay capability** — if a counter drifts, there is no event log to rebuild
 *   the correct state from.
 *
 * ### Target design: Event-sourced ledger
 *
 * #### 1. Future `SourceStatsEvent` table
 * A dedicated event table that records every source-level action:
 * ```
 * CREATE TABLE source_stats_events (
 *     eventId        INTEGER PRIMARY KEY AUTOINCREMENT,
 *     packageName    TEXT    NOT NULL,         -- e.g. "com.revolut"
 *     eventType      TEXT    NOT NULL,         -- ACCEPTED, DUPLICATE, REJECTED, AUTO_REJECTED, PENDING_REVIEW
 *     timestamp      INTEGER NOT NULL,         -- epoch millis when the event occurred
 *     expenseId      INTEGER,                 -- FK to expense, nullable
 *     rawNotificationId INTEGER,              -- FK to raw notification, nullable
 *     metadata       TEXT,                     -- optional JSON payload (amount, merchant, etc.)
 *     FOREIGN KEY (expenseId) REFERENCES expense(id) ON DELETE SET NULL,
 *     FOREIGN KEY (rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL
 * );
 * CREATE INDEX idx_sse_pkg_type ON source_stats_events(packageName, eventType);
 * CREATE INDEX idx_sse_pkg_ts  ON source_stats_events(packageName, timestamp);
 * ```
 *
 * #### 2. [SourceStats] becomes a materialized view
 * Instead of inline `UPDATE` statements, [SourceStats] would be computed from
 * `SourceStatsEvent` rows:
 * ```
 * CREATE VIEW source_stats_materialized AS
 * SELECT
 *   packageName,
 *   COUNT(*)                                             AS totalNotifications,
 *   SUM(CASE WHEN eventType = 'ACCEPTED'     THEN 1 ELSE 0 END) AS acceptedAsExpense,
 *   SUM(CASE WHEN eventType = 'REJECTED'     THEN 1 ELSE 0 END) AS rejectedByUser,
 *   SUM(CASE WHEN eventType = 'AUTO_REJECTED' THEN 1 ELSE 0 END) AS autoRejected,
 *   SUM(CASE WHEN eventType = 'PENDING_REVIEW' THEN 1 ELSE 0 END) AS pendingReview,
 *   SUM(CASE WHEN eventType = 'DUPLICATE'    THEN 1 ELSE 0 END) AS duplicates,
 *   MAX(timestamp)                                       AS lastSeen
 * FROM source_stats_events
 * GROUP BY packageName;
 * ```
 * The `trustScore` and `isLikelySpam` computed properties remain unchanged in
 * the Kotlin layer since they already derive from the aggregate columns.
 *
 * #### 3. Migration path from inline counters to event-derived
 * 1. **Create the `source_stats_events` table** via a Room migration (or `fallbackToDestructiveMigration`
 *    if acceptable). Seed it by inserting one `SourceStatsEvent` per existing [SourceStats] row
 *    for each counter that is non-zero (e.g. an ACCEPTED event per `acceptedAsExpense` count).
 *    This is a one-time backfill; accuracy is best-effort since we lose timing granularity.
 * 2. **Add event-writing calls** in [NotificationProcessingPipeline] and [ConfidenceRouter]
 *    wherever counters are currently incremented. Each increment becomes an `INSERT` into
 *    `source_stats_events` instead of an `UPDATE source_stats`.
 * 3. **Replace the `source_stats` table** with the materialized view (or keep both during a
 *    dual-write period). Update [SourceStatsDao] queries to read from the view.
 * 4. **Drop inline counter columns** and the `source_stats` table once the view is stable.
 *    Rename the view to `source_stats` or update the Room entity mapping.
 *
 * ### Benefits
 * - Full auditability: every accept/reject/duplicate event is timestamped and traceable.
 * - Debugging: you can query "why did source X get a low trust score?" by inspecting events.
 * - Replay: counters can be rebuilt from scratch by replaying the event stream.
 * - Extensibility: new event types (e.g. `USER_UNDO_ACCEPT`) are trivial to add.
 */
// AIML-12: Planned SourceStatsEvent entity (event-sourced audit ledger)
// @Entity(tableName = "source_stats_events")
// data class SourceStatsEvent(
//     @PrimaryKey val eventId: Long = 0,
//     val packageName: String,
//     val eventType: String, // ACCEPTED, DUPLICATE, REJECTED, AUTO_REJECTED, PENDING_REVIEW
//     val timestamp: Long,
//     val expenseId: Long?,
//     val rawNotificationId: Long?,
//     val metadata: String? = null
// )
// Indices (planned, via Room):
//   @Index(value = ["packageName", "eventType"])
//   @Index(value = ["packageName", "timestamp"])
// Foreign keys (planned):
//   expenseId → expenses(id) ON DELETE SET NULL
//   rawNotificationId → raw_notifications(id) ON DELETE SET NULL
//
// See the class-level KDoc above for the full migration path from inline counters
// to event-sourced materialized view.
@Entity(tableName = "source_stats")
data class SourceStats(
    @PrimaryKey val packageName: String,
    @ColumnInfo(defaultValue = "0") val totalNotifications: Long = 0,
    @ColumnInfo(defaultValue = "0") val acceptedAsExpense: Long = 0,
    @ColumnInfo(defaultValue = "0") val rejectedByUser: Long = 0,
    @ColumnInfo(defaultValue = "0") val autoRejected: Long = 0,
    @ColumnInfo(defaultValue = "0") val pendingReview: Long = 0,
    @ColumnInfo(defaultValue = "0") val duplicates: Long = 0,
    val lastSeen: Long
) {
    /**
     * Trust score computed as the ratio of accepted expenses to total valid notifications.
     *
     * ## AIML-11: Source trust inflated by duplicates
     * Duplicate transactions are excluded from the numerator (but counted in the denominator)
     * because duplicates represent repeated notifications for the same underlying expense and
     * should not inflate the source's trust score. Only unique accepted expenses count.
     */
    val trustScore: Float
        get() {
            // Exclude auto-rejected notifications (promos, exchange-rate alerts, non-financial
            // content that the parser correctly returned null for) from the denominator.
            // Counting them would penalise high-volume sources like Revolut that send many
            // non-financial notifications, causing real purchases to score below AUTO_ACCEPT.
            val effectiveTotal = totalNotifications - autoRejected
            // AIML-11: Exclude duplicates from the "valid" count — duplicates should not
            // inflate trust. They ARE still counted in the denominator (as part of effectiveTotal)
            // so that high-duplicate sources get a slight penalty.
            val valid = (acceptedAsExpense - duplicates).coerceAtLeast(0)
            return if (effectiveTotal > 0)
                valid.toFloat() / effectiveTotal
            else 0f
        }

    val isLikelySpam: Boolean
        get() {
            val effectiveTotal = totalNotifications - autoRejected
            return effectiveTotal > 10 && trustScore < 0.05f
        }
}
