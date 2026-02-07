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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(merchantCategory: MerchantCategory)
    
    @Query("SELECT * FROM merchant_categories")
    suspend fun getAll(): List<MerchantCategory>

    @Query("DELETE FROM merchant_categories")
    suspend fun deleteAll()
}
