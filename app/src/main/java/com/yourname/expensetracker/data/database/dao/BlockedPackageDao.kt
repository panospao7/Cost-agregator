package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedPackageDao {
    /**
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun block(blockedPackage: BlockedPackage)

    @Delete
    suspend fun unblock(blockedPackage: BlockedPackage)
    
    @Query("DELETE FROM blocked_packages WHERE packageName = :packageName")
    suspend fun unblock(packageName: String)

    @Query("SELECT * FROM blocked_packages")
    fun getAllFlow(): Flow<List<BlockedPackage>>

    @Query("SELECT packageName FROM blocked_packages")
    fun getAllPackageNamesFlow(): Flow<List<String>>
    
    @Query("SELECT EXISTS(SELECT 1 FROM blocked_packages WHERE packageName = :packageName)")
    suspend fun isBlocked(packageName: String): Boolean

    @Query("DELETE FROM blocked_packages")
    suspend fun deleteAll()
}
