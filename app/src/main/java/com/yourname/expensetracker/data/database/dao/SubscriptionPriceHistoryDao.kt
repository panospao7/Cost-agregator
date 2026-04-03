package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionPriceHistoryDao {
    
    @Insert
    suspend fun insert(priceHistory: SubscriptionPriceHistory): Long
    
    @Query("SELECT * FROM subscription_price_history WHERE subscriptionId = :subscriptionId ORDER BY recordedAt DESC")
    fun getPriceHistoryForSubscription(subscriptionId: Long): Flow<List<SubscriptionPriceHistory>>
    
    @Query("SELECT * FROM subscription_price_history WHERE subscriptionId = :subscriptionId ORDER BY recordedAt DESC LIMIT 1")
    suspend fun getLatestPrice(subscriptionId: Long): SubscriptionPriceHistory?
    
    @Query("SELECT * FROM subscription_price_history WHERE subscriptionId = :subscriptionId ORDER BY recordedAt ASC")
    suspend fun getAllPricesForSubscription(subscriptionId: Long): List<SubscriptionPriceHistory>
    
    @Query("""
        SELECT * FROM subscription_price_history 
        WHERE subscriptionId = :subscriptionId 
        AND recordedAt >= :startDate 
        AND recordedAt < :endDate 
        ORDER BY recordedAt DESC
    """)
    suspend fun getPriceHistoryBetween(subscriptionId: Long, startDate: Long, endDate: Long): List<SubscriptionPriceHistory>
    
    @Query("DELETE FROM subscription_price_history WHERE subscriptionId = :subscriptionId")
    suspend fun deleteAllForSubscription(subscriptionId: Long)
}
