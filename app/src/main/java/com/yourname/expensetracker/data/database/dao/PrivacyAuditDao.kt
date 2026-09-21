package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent

@Dao
interface PrivacyAuditDao {

    @Insert
    suspend fun insert(event: PrivacyAuditEvent): Long

    @Query("SELECT * FROM privacy_audit_events ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PrivacyAuditEvent>

    /**
     * RP-16 16-C: exactly-once audit identity. Counts audit events previously
     * emitted with [auditKey] appended to the reason
     * (e.g. "[retention-audit:raw_notifications:20345]"). Callers check this
     * before inserting so a crash between the audit insert and the checkpoint
     * update can never produce a duplicate audit.
     */
    @Query("SELECT COUNT(*) FROM privacy_audit_events WHERE caller = :caller AND reason LIKE '%' || :auditKey")
    suspend fun countAuditEventsByKey(caller: String, auditKey: String): Int

    /**
     * RP-14 P8-007 (`50_privacy_audit_events` target): delete audit-ledger rows
     * older than the cutoff. Count-only and purge-safe.
     *
     * COMPLIANCE-FLAGGED (D14): this table is itself the accountability ledger,
     * so the 180-day cutoff is a single named constant
     * ([com.yourname.expensetracker.data.privacy.DataRetentionWorker.PRIVACY_AUDIT_RETENTION_DAYS])
     * and ANY change to it requires explicit compliance sign-off.
     */
    @Query("DELETE FROM privacy_audit_events WHERE timestampMs < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long): Int
}
