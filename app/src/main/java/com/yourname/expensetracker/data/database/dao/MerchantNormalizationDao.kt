package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical

/**
 * DAO for merchant normalization tables.
 */
@Dao
interface MerchantNormalizationDao {

    // ==================== Canonical Merchants ====================
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCanonical(merchant: MerchantCanonical): Long
    
    @Update
    suspend fun updateCanonical(merchant: MerchantCanonical)
    
    @Query("SELECT * FROM merchant_canonicals WHERE id = :id")
    suspend fun getCanonicalById(id: Long): MerchantCanonical?
    
    @Query("SELECT * FROM merchant_canonicals WHERE searchKey = :searchKey ORDER BY id DESC LIMIT 1")
    suspend fun getCanonicalBySearchKey(searchKey: String): MerchantCanonical?
    
    // C06-FIXED: Returns the most recently created canonical when multiple share the same normalized name.
    @Query("SELECT * FROM merchant_canonicals WHERE normalizedName = :name ORDER BY createdAt DESC LIMIT 1")
    suspend fun getCanonicalByNormalizedNameLatest(name: String): MerchantCanonical?

    @Query("SELECT * FROM merchant_canonicals WHERE normalizedName = :name LIMIT 1")
    suspend fun getCanonicalByName(name: String): MerchantCanonical?
    
    @Query("SELECT * FROM merchant_canonicals ORDER BY totalOccurrences DESC")
    suspend fun getAllCanonicals(): List<MerchantCanonical>
    
    @Query("SELECT * FROM merchant_canonicals ORDER BY totalOccurrences DESC LIMIT :limit")
    suspend fun getTopMerchants(limit: Int): List<MerchantCanonical>
    
    @Query("UPDATE merchant_canonicals SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCanonicalCategory(id: Long, categoryId: Long?)
    
    @Query("UPDATE merchant_canonicals SET totalOccurrences = totalOccurrences + 1, totalSpent = totalSpent + :amount, updatedAt = :timestamp WHERE id = :id")
    suspend fun incrementMerchantStats(id: Long, amount: Double, timestamp: Long)

    // ==================== Merchant Aliases ====================
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MerchantAlias): Long
    
    @Update
    suspend fun updateAlias(alias: MerchantAlias)
    
    @Query("SELECT * FROM merchant_aliases WHERE id = :id")
    suspend fun getAliasById(id: Long): MerchantAlias?
    
    @Query("SELECT * FROM merchant_aliases WHERE rawName = :rawName LIMIT 1")
    suspend fun getAliasByRawName(rawName: String): MerchantAlias?
    
    @Query("SELECT * FROM merchant_aliases WHERE normalizedKey = :normalizedKey ORDER BY id DESC LIMIT 1")
    suspend fun getAliasByNormalizedKey(normalizedKey: String): MerchantAlias?
    
    @Query("SELECT * FROM merchant_aliases WHERE canonicalId = :canonicalId")
    suspend fun getAliasesForCanonical(canonicalId: Long): List<MerchantAlias>
    
    @Query("""
        SELECT * FROM merchant_aliases
        WHERE normalizedKey >= :prefixStart
          AND normalizedKey < :prefixEndExclusive
        ORDER BY occurrenceCount DESC
        LIMIT :limit
    """)
    suspend fun searchAliasesByPrefixRange(
        prefixStart: String,
        prefixEndExclusive: String,
        limit: Int = 20
    ): List<MerchantAlias>

    @Query("""
        SELECT * FROM merchant_aliases
        WHERE normalizedKey LIKE '%' || :normalizedQuery || '%'
        ORDER BY occurrenceCount DESC
        LIMIT :limit
    """)
    suspend fun searchAliasesByContains(
        normalizedQuery: String,
        limit: Int = 20
    ): List<MerchantAlias>

    /**
     * Index-friendly prefix search using a range scan on normalizedKey.
     * This avoids LIKE-pattern scans and keeps lookups on the normalizedKey index.
     */
    suspend fun searchAliasesByPrefix(prefix: String, limit: Int = 20): List<MerchantAlias> {
        if (prefix.isBlank()) return emptyList()
        return searchAliasesByPrefixRange(
            prefixStart = prefix,
            prefixEndExclusive = "$prefix\uFFFF",
            limit = limit
        )
    }
    
    @Query("DELETE FROM merchant_aliases WHERE lastUsedAt < :olderThan")
    suspend fun deleteUnusedAliasesOlderThan(olderThan: Long): Int

    // ==================== Combined Operations ====================
    
    @Transaction
    suspend fun linkAliasToCanonical(rawName: String, normalizedKey: String, canonicalId: Long, isUserDefined: Boolean = false, timestamp: Long) {
        // TODO (C01): Check both rawName AND normalizedKey before insert; update existing alias on conflict.
        //              Currently only checks by rawName — if a different rawName produces the same
        //              normalizedKey, the insert silently succeeds, creating a duplicate alias entry.
        val existing = getAliasByRawName(rawName)
        if (existing != null) {
            updateAlias(existing.copy(
                canonicalId = canonicalId,
                isUserDefined = isUserDefined || existing.isUserDefined,
                occurrenceCount = existing.occurrenceCount + 1,
                lastUsedAt = timestamp
            ))
        } else {
            insertAlias(MerchantAlias(
                rawName = rawName,
                normalizedKey = normalizedKey,
                canonicalId = canonicalId,
                isUserDefined = isUserDefined,
                createdAt = timestamp,
                lastUsedAt = timestamp
            ))
        }
    }
    
    @Query("SELECT COUNT(*) FROM merchant_canonicals")
    suspend fun getCanonicalCount(): Int
}
