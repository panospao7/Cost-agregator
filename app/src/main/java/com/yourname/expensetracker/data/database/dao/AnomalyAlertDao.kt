package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.AnomalyAlert

/**
 * DAO for managing anomaly alerts.
 * Provides queries for cooldown logic, deduplication, and user feedback tracking.
 */
@Dao
interface AnomalyAlertDao {

    /**
     * Get the most recent alert for a specific expense.
     */
    @Query("SELECT * FROM anomaly_alerts WHERE expenseId = :expenseId ORDER BY alertedAt DESC LIMIT 1")
    suspend fun getLastAlertForExpense(expenseId: Long): AnomalyAlert?

    /**
     * Get the most recent alert for a specific merchant since a given time.
     * Used for merchant-level cooldown logic.
     */
    @Query("SELECT * FROM anomaly_alerts WHERE merchant = :merchant AND alertedAt > :sinceMs ORDER BY alertedAt DESC LIMIT 1")
    suspend fun getLastAlertForMerchant(merchant: String, sinceMs: Long): AnomalyAlert?

    /**
     * Get the most recent alert for a specific category since a given time.
     * Used for category-level cooldown logic.
     */
    @Query("SELECT * FROM anomaly_alerts WHERE category = :category AND alertedAt > :sinceMs ORDER BY alertedAt DESC LIMIT 1")
    suspend fun getLastAlertForCategory(category: String, sinceMs: Long): AnomalyAlert?

    /**
     * Insert a new anomaly alert.
     */
    @Insert
    suspend fun insert(alert: AnomalyAlert): Long

    /**
     * Mark an alert as dismissed with optional user feedback.
     */
    @Query("UPDATE anomaly_alerts SET dismissed = 1, dismissedAt = :dismissedAt, userFeedback = :feedback WHERE id = :alertId")
    suspend fun dismissAlert(alertId: Long, dismissedAt: Long, feedback: String?)

    /**
     * Get all non-dismissed alerts.
     */
    @Query("SELECT * FROM anomaly_alerts WHERE dismissed = 0 ORDER BY alertedAt DESC")
    suspend fun getActiveAlerts(): List<AnomalyAlert>

    /**
     * Get the count of alerts with "looks_normal" feedback for a merchant.
     * Used to reduce alert frequency for merchants marked as normal by the user.
     */
    @Query("SELECT COUNT(*) FROM anomaly_alerts WHERE merchant = :merchant AND userFeedback = 'looks_normal'")
    suspend fun getLooksNormalCountForMerchant(merchant: String): Int

    /**
     * Delete old dismissed alerts to keep the table clean.
     */
    @Query("DELETE FROM anomaly_alerts WHERE dismissed = 1 AND dismissedAt < :olderThanMs")
    suspend fun cleanupOldDismissedAlerts(olderThanMs: Long): Int
}
