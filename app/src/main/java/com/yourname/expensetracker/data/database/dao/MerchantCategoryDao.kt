package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.MerchantCategory

@Dao
interface MerchantCategoryDao {
    @Query("SELECT * FROM merchant_categories WHERE merchantPattern = :merchantPattern")
    suspend fun getCategoryForMerchant(merchantPattern: String): MerchantCategory?

    // TODO (C06): Make normalizedCanonicalName unique, or return all candidates resolved by source/confidence/timesUsed.
    @Query("SELECT * FROM merchant_categories WHERE normalizedCanonicalName = :normalizedCanonicalName")
    suspend fun getCategoryByNormalizedCanonical(normalizedCanonicalName: String): MerchantCategory?

    @Query("SELECT * FROM merchant_categories WHERE normalizedCanonicalName LIKE :prefix || '%'")
    suspend fun getCategoriesByPrefix(prefix: String): List<MerchantCategory>

    /**
     * Direct insert that bypasses merchant normalization.
     * Prefer the repository-level method that handles canonical name normalization
     * and deduplication before inserting.
     */
    /**
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Deprecated("Use repository-level insert with canonical name normalization instead")
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(merchantCategory: MerchantCategory)
    // TODO (C05): Change return type from Unit to Long so callers can check
    // for silent failures (0 = skipped due to IGNORE conflict).

    /**
     * Direct bulk insert that bypasses merchant normalization.
     * Prefer the repository-level method that handles canonical name normalization
     * and deduplication before inserting.
     */
    /**
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Deprecated("Use repository-level insertAll with canonical name normalization instead")
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(merchantCategories: List<MerchantCategory>)
    // TODO (C05): Change return type from Unit to Long for insertAll as well.
    
    @Query("SELECT * FROM merchant_categories")
    suspend fun getAll(): List<MerchantCategory>

    @Query("SELECT * FROM merchant_categories WHERE normalizedCanonicalName IS NULL")
    suspend fun getMappingsMissingCanonicalName(): List<MerchantCategory>

    @Query("UPDATE merchant_categories SET normalizedCanonicalName = :normalizedCanonicalName WHERE merchantPattern = :merchantPattern")
    suspend fun updateNormalizedCanonicalName(merchantPattern: String, normalizedCanonicalName: String)

    @Query("SELECT COUNT(*) FROM merchant_categories")
    suspend fun getCount(): Int

    @Query("DELETE FROM merchant_categories")
    suspend fun deleteAll()
}
