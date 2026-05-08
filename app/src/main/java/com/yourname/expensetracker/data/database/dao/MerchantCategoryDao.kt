package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.MerchantCategory

// NEXT STEPS (ARCH-E13): Categorization migration — DAO-level changes
//
// MIGRATION PLAN:
// ===============
// 1. DAO return type changes:
//    a. insert(merchantCategory) → change return from Unit to Long
//       (Room @Insert onConflict=IGNORE returns Long — callers must check for 0L).
//    b. insertAll(merchantCategories) → change return from Unit to List<Long>.
//
// 2. Conflict detection at DAO boundary:
//    a. Add a @Query method to check existence by both rawName AND normalizedKey:
//       `SELECT id FROM merchant_categories WHERE merchantPattern = :rawName OR normalizedCanonicalName = :normalizedKey LIMIT 1`
//    b. Callers should use this before insert to distinguish IGNORE-skip (-1L) from real insert.
//    c. Return AliasLinkResult.Conflict(existingId) when a match is found.
//
// 3. Unique constraint enforcement:
//    a. Add migration step to create a UNIQUE index on normalizedCanonicalName
//       (or make the column UNIQUE directly).
//    b. C06: getCategoryByNormalizedCanonical currently returns a single result.
//       After UNIQUE, this is safe. Before UNIQUE, it may return ambiguous results.
//    c. Migration SQL: `CREATE UNIQUE INDEX IF NOT EXISTS idx_merchant_categories_normalized ON merchant_categories(normalizedCanonicalName)`
//
// 4. Deprecation cleanup:
//    a. Remove @Deprecated annotations on insert/insertAll after all callers
//       have been migrated to use MerchantCategoryRepository.
//    b. Remove the deprecated methods entirely once migration is complete.
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
