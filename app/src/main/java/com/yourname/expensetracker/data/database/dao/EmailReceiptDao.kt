package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailReceiptDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(emailReceipt: EmailReceiptSource): Long

    /**
     * Non-destructive insert: returns the new row ID, or -1 if the
     * emailMessageId unique constraint fires (row already exists).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(emailReceipt: EmailReceiptSource): Long

    @Update
    suspend fun update(emailReceipt: EmailReceiptSource)

    @Delete
    suspend fun delete(emailReceipt: EmailReceiptSource)

    @Query("SELECT * FROM email_receipt_sources ORDER BY parsedAt DESC")
    fun getAllFlow(): Flow<List<EmailReceiptSource>>

    @Query("SELECT * FROM email_receipt_sources ORDER BY parsedAt DESC")
    suspend fun getAll(): List<EmailReceiptSource>

    @Query("SELECT * FROM email_receipt_sources WHERE id = :id")
    suspend fun getById(id: Long): EmailReceiptSource?

    @Query("SELECT * FROM email_receipt_sources WHERE receiptId = :receiptId")
    suspend fun getByReceiptId(receiptId: Long): List<EmailReceiptSource>

    @Query("SELECT * FROM email_receipt_sources WHERE emailMessageId = :messageId")
    suspend fun getByMessageId(messageId: String): EmailReceiptSource?

    /**
     * Get email receipt by fingerprint for deduplication.
     * Fingerprint format: "${merchant.lowercase()}_${amount}_${date}"
     */
    @Query("SELECT * FROM email_receipt_sources WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): EmailReceiptSource?

    @Query("SELECT * FROM email_receipt_sources WHERE provider = :provider ORDER BY parsedAt DESC")
    suspend fun getByProvider(provider: String): List<EmailReceiptSource>

    @Query("SELECT * FROM email_receipt_sources WHERE parsedAt >= :since ORDER BY parsedAt DESC")
    suspend fun getRecent(since: Long): List<EmailReceiptSource>

    @Query("SELECT COUNT(*) FROM email_receipt_sources")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM email_receipt_sources WHERE provider = :provider")
    suspend fun getCountByProvider(provider: String): Int

    @Query("DELETE FROM email_receipt_sources")
    suspend fun deleteAll()

    @Query("DELETE FROM email_receipt_sources WHERE parsedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    /**
     * PRIV-43B-12 / PRIV-6825-02: Redact sensitive fields rather than deleting rows.
     * Preserves receiptId, provider, fingerprint, and hash columns for dedup/provenance.
     * emailSender and emailSubject are now nullable so this update is safe.
     * Returns the number of rows updated.
     */
    @androidx.room.Query("""
        UPDATE email_receipt_sources
        SET emailSender = NULL,
            emailSubject = NULL,
            emailMessageId = NULL
        WHERE parsedAt < :cutoffMs
          AND (emailSender IS NOT NULL OR emailSubject IS NOT NULL OR emailMessageId IS NOT NULL)
    """)
    suspend fun redactSensitiveFieldsOlderThan(cutoffMs: Long): Int
}
