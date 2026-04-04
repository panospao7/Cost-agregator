package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a connection to a bank or financial institution API.
 */
@Entity(
    tableName = "bank_connections",
    indices = [
        Index(value = ["bankId"], unique = true),
        Index(value = ["isActive"]),
        Index(value = ["lastSync"])
    ]
)
data class BankConnection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bankId: String,            // Unique bank identifier (e.g., "nbg", "eurobank")
    val bankName: String,          // Display name (e.g., "National Bank of Greece")
    val countryCode: String,       // ISO country code
    
    // API credentials (AES-GCM payload: "enc:v1:<ivBase64>:<ciphertextBase64>")
    val accessToken: String? = null,
    val refreshToken: String? = null,
    @ColumnInfo(defaultValue = "0") val tokenEncryptionVersion: Int = 0,
    val tokenExpiry: Long? = null,
    
    // Connection status
    @ColumnInfo(defaultValue = "0") val isActive: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isConnected: Boolean = false,
    val lastSync: Long? = null,
    @ColumnInfo(defaultValue = "NEVER") val lastSyncStatus: SyncStatus = SyncStatus.NEVER,
    
    // Account settings
    @ColumnInfo(defaultValue = "1") val autoSync: Boolean = true,
    @ColumnInfo(defaultValue = "DAILY") val syncFrequency: SyncFrequency = SyncFrequency.DAILY,
    val defaultCategoryId: Long? = null,
    
    // Error tracking
    val lastError: String? = null,
    val lastErrorTime: Long? = null,
    @ColumnInfo(defaultValue = "0") val consecutiveErrors: Int = 0,
    
    val createdAt: Long = System.currentTimeMillis()
)

enum class SyncStatus {
    NEVER,
    SUCCESS,
    PARTIAL,
    FAILED
}

enum class SyncFrequency {
    HOURLY,
    DAILY,
    WEEKLY,
    MANUAL
}
