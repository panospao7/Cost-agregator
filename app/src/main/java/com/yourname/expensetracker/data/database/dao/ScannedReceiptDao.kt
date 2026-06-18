package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedReceiptDao {

    /**
     * RCP-19: Use IGNORE instead of REPLACE to prevent silently overwriting
     * existing receipts. REPLACE would delete the old row and insert a new one
     * with a different ID (auto-generated primary key), breaking foreign-key
     * references (e.g. pending_reviews, receipt_events, warranties).
     *
     * With IGNORE, if a conflict occurs (e.g. duplicate via fingerprint/hash),
     * the insert is skipped and the existing row is preserved. Callers should
     * check the returned rowId (0 == conflict) and handle accordingly.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: ScannedReceipt): Long

    @Update
    suspend fun update(receipt: ScannedReceipt)

    @Delete
    suspend fun delete(receipt: ScannedReceipt)

    @Query("DELETE FROM scanned_receipts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ScannedReceipt>>

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getReceiptsPaged(limit: Int, offset: Int): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts WHERE id = :id")
    suspend fun getById(id: Long): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: Long): ScannedReceipt?

    @Query("SELECT COUNT(*) FROM scanned_receipts")
    suspend fun getCount(): Int

    @Query("DELETE FROM scanned_receipts")
    suspend fun deleteAll()

    @Query("UPDATE scanned_receipts SET expenseId = :expenseId, matchStatus = 'AUTO_MATCHED' WHERE id = :receiptId")
    suspend fun linkToExpense(receiptId: Long, expenseId: Long)

    /**
     * S6 (P9-P1-07 / NEW-07): Atomic compare-and-set claim for the automated
     * matching worker's auto-link path.
     *
     * Transitions a receipt to AUTO_MATCHED **only if** it is still UNMATCHED or
     * SUGGESTED at write time, and returns the number of rows affected. This is a
     * conditional UPDATE (not a schema change) executed inside the link
     * transaction so two concurrent matching runs (e.g. the periodic
     * "receipt_matching" worker overlapping the manual "receipt_matching_run_once"
     * one-shot) cannot both auto-link the same receipt: the first run claims it
     * (returns 1), the second run sees the row already resolved and gets 0 rows.
     *
     * A caller observing 0 affected rows must treat the receipt as already handled
     * by a concurrent run — skip notification and side effects rather than failing.
     *
     * Mirrors the field set of the previous unconditional link update
     * (expenseId, matchStatus, matchConfidence, suggestedExpenseId cleared, updatedAt).
     */
    @Query(
        "UPDATE scanned_receipts " +
            "SET expenseId = :expenseId, matchStatus = 'AUTO_MATCHED', " +
            "matchConfidence = :confidence, suggestedExpenseId = NULL, updatedAt = :now " +
            "WHERE id = :receiptId AND matchStatus IN ('UNMATCHED', 'SUGGESTED')"
    )
    suspend fun claimForAutoMatch(
        receiptId: Long,
        expenseId: Long,
        confidence: Float?,
        now: Long
    ): Int

    @Query("UPDATE scanned_receipts SET itemCategorizationStatus = :status WHERE id = :receiptId")
    suspend fun updateCategorizationStatus(receiptId: Long, status: String)

    @Query("SELECT * FROM scanned_receipts WHERE matchStatus = 'UNMATCHED' ORDER BY createdAt DESC")
    suspend fun getUnmatchedReceipts(): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts WHERE matchStatus = 'SUGGESTED' ORDER BY createdAt DESC")
    suspend fun getReceiptsWithSuggestions(): List<ScannedReceipt>

    /**
     * Returns receipts eligible for automated matching: UNMATCHED (not yet processed)
     * and SUGGESTED (re-evaluate for potential upgrade to AUTO_MATCHED).
     *
     * Excludes AUTO_MATCHED, MANUALLY_MATCHED, and REJECTED receipts which
     * have already been resolved.
     */
    @Query("SELECT * FROM scanned_receipts WHERE matchStatus IN ('UNMATCHED', 'SUGGESTED') ORDER BY createdAt DESC")
    suspend fun getProcessableReceipts(): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts WHERE createdAt >= :since ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentReceipts(since: Long, limit: Int = Int.MAX_VALUE): List<ScannedReceipt>

    // ── Duplicate detection queries (PR 8) ──────────────────────────────────

    @Query("SELECT * FROM scanned_receipts WHERE imageHash = :imageHash LIMIT 1")
    suspend fun getByImageHash(imageHash: String): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE textFingerprint = :fingerprint LIMIT 1")
    suspend fun getByTextFingerprint(fingerprint: String): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE semanticFingerprint = :fingerprint LIMIT 1")
    suspend fun getBySemanticFingerprint(fingerprint: String): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE sourceFingerprint = :fingerprint LIMIT 1")
    suspend fun getBySourceFingerprint(fingerprint: String): ScannedReceipt?

    // ── Backup manifest queries (PR 8) ──────────────────────────────────────

    @Query("SELECT * FROM scanned_receipts WHERE imagePath IS NOT NULL")
    suspend fun getAllWithImagePath(): List<ScannedReceipt>

    // ── Raw data retention (Phase 6, Batch 3) ──────────────────────────────────

    @Query("""
        UPDATE scanned_receipts
        SET rawOcrTextPurgedAt = :nowMs
        WHERE createdAt < :beforeMs
          AND rawOcrTextPurgedAt IS NULL
    """)
    suspend fun purgeRawOcrText(beforeMs: Long, nowMs: Long): Int

    // ── Data retention worker support ──────────────────────────────────────────

    @Query("""
        SELECT * FROM scanned_receipts
        WHERE createdAt < :cutoffMs
          AND rawOcrTextPurgedAt IS NULL
    """)
    suspend fun getUnpurgedScannedReceiptsOlderThan(cutoffMs: Long): List<ScannedReceipt>

    @Query("""
        SELECT * FROM scanned_receipts
        WHERE createdAt < :cutoffMs
          AND rawOcrTextPurgedAt IS NULL
        LIMIT :limit
    """)
    suspend fun getUnpurgedScannedReceiptsOlderThan(cutoffMs: Long, limit: Int): List<ScannedReceipt>

    @Query("""
        UPDATE scanned_receipts
        SET rawOcrTextPurgedAt = :rawOcrTextPurgedAt,
            rawOcrText = ''
        WHERE id = :id
    """)
    suspend fun updateRawOcrTextPurged(
        id: Long,
        rawOcrTextPurgedAt: Long
    )

    // ── Pipeline 3 diagnostics (PR P3-2) ────────────────────────────────────

    /** Returns the count of receipts with invalid (<= 0) timestamps. */
    @Query("SELECT COUNT(*) FROM scanned_receipts WHERE createdAt <= 0 OR updatedAt <= 0")
    suspend fun countInvalidTimestamps(): Int

    /** Repairs receipts whose createdAt is 0 by setting it to [now]. */
    @Query("UPDATE scanned_receipts SET createdAt = :now WHERE createdAt <= 0")
    suspend fun repairMissingCreatedAt(now: Long): Int

    /** Repairs receipts whose updatedAt is 0 by setting it to createdAt. */
    @Query("UPDATE scanned_receipts SET updatedAt = createdAt WHERE updatedAt <= 0")
    suspend fun repairMissingUpdatedAt(): Int

    // ── Pipeline 3 fingerprint duplicate diagnostics (P3-BLOCKER-J) ─────────

    @Query("SELECT COUNT(*) FROM (SELECT imageHash FROM scanned_receipts WHERE imageHash IS NOT NULL AND imageHash != '' GROUP BY imageHash HAVING COUNT(*) > 1)")
    suspend fun countDuplicateImageHashGroups(): Int

    @Query("SELECT COUNT(*) FROM (SELECT sourceFingerprint FROM scanned_receipts WHERE sourceFingerprint IS NOT NULL AND sourceFingerprint != '' GROUP BY sourceFingerprint HAVING COUNT(*) > 1)")
    suspend fun countDuplicateSourceFingerprints(): Int

    @Query("SELECT COUNT(*) FROM (SELECT textFingerprint FROM scanned_receipts WHERE textFingerprint IS NOT NULL AND textFingerprint != '' GROUP BY textFingerprint HAVING COUNT(*) > 1)")
    suspend fun countDuplicateTextFingerprints(): Int

    @Query("SELECT COUNT(*) FROM (SELECT semanticFingerprint FROM scanned_receipts WHERE semanticFingerprint IS NOT NULL AND semanticFingerprint != '' GROUP BY semanticFingerprint HAVING COUNT(*) > 1)")
    suspend fun countDuplicateSemanticFingerprints(): Int
}
