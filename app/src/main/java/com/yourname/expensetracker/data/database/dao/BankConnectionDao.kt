package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

// TODO (P2-3): Add updateLastError(id, errorMessage, errorCode) and
// updateSyncStatus(id, status, lastSyncTime) for detailed connection state.

@Dao
interface BankConnectionDao {
    
    @Insert
    suspend fun insert(connection: BankConnection): Long
    
    @Update
    suspend fun update(connection: BankConnection)
    
    @Delete
    suspend fun delete(connection: BankConnection)
    
    @Query("SELECT * FROM bank_connections ORDER BY createdAt DESC")
    fun getAllConnections(): Flow<List<BankConnection>>
    
    @Query("SELECT * FROM bank_connections WHERE isActive = 1")
    fun getActiveConnections(): Flow<List<BankConnection>>
    
    @Query("SELECT * FROM bank_connections WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BankConnection?
    
    @Query("SELECT * FROM bank_connections WHERE bankId = :bankId LIMIT 1")
    suspend fun getByBankId(bankId: String): BankConnection?
    
    @Query("UPDATE bank_connections SET isConnected = 0, isActive = 0, accessToken = NULL, refreshToken = NULL, tokenExpiry = NULL, tokenEncryptionVersion = 0 WHERE id = :id")
    suspend fun disconnect(id: Long)
    
    @Query("UPDATE bank_connections SET lastSync = :timestamp, lastSyncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, timestamp: Long, status: SyncStatus)

    /**
     * RP-17 17-B: terminal status update that never advances `lastSync`
     * (FAILED-family outcomes). Register D12: terminal-only statuses in DB.
     */
    @Query("UPDATE bank_connections SET lastSyncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatusOnly(id: Long, status: SyncStatus)

    /**
     * RP-17 17-E: conditional token write — refresh/disconnect race guard.
     * Only updates while the connection row is still connected; returns the
     * number of affected rows (0 = connection was disconnected concurrently;
     * the caller maps that to a typed disconnected/blocked outcome and never
     * resurrects tokens on a disconnected connection).
     */
    @Query("UPDATE bank_connections SET accessToken = :accessToken, refreshToken = :refreshToken, tokenEncryptionVersion = :encryptionVersion, tokenExpiry = :expiry WHERE id = :id AND isConnected = 1")
    suspend fun updateTokenIfConnected(
        id: Long,
        accessToken: String,
        refreshToken: String?,
        encryptionVersion: Int,
        expiry: Long
    ): Int
    
    @Query("SELECT COUNT(*) FROM bank_connections WHERE isActive = 1 AND isConnected = 1")
    suspend fun getConnectedCount(): Int
}
