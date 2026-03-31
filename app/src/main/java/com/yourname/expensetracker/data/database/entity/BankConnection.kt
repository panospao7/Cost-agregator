package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a connection to a bank or financial institution API.
 */
@Entity(
    tableName = "bank_connections",
    indices = [
        Index(value = ["bankId"]),
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
    
    // API credentials (encrypted in production)
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiry: Long? = null,
    
    // Connection status
    val isActive: Boolean = false,
    val isConnected: Boolean = false,
    val lastSync: Long? = null,
    val lastSyncStatus: SyncStatus = SyncStatus.NEVER,
    
    // Account settings
    val autoSync: Boolean = true,
    val syncFrequency: SyncFrequency = SyncFrequency.DAILY,
    val defaultCategoryId: Long? = null,
    
    // Error tracking
    val lastError: String? = null,
    val lastErrorTime: Long? = null,
    val consecutiveErrors: Int = 0,
    
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
