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

    @Query("SELECT * FROM merchant_categories WHERE normalizedCanonicalName = :normalizedCanonicalName")
    suspend fun getCategoryByNormalizedCanonical(normalizedCanonicalName: String): MerchantCategory?

    @Query("SELECT * FROM merchant_categories WHERE normalizedCanonicalName LIKE :prefix || '%'")
    suspend fun getCategoriesByPrefix(prefix: String): List<MerchantCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(merchantCategory: MerchantCategory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(merchantCategories: List<MerchantCategory>)
    
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
