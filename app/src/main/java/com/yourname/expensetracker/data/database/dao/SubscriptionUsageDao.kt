package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.SubscriptionUsage
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionUsageDao {
    
    @Insert
    suspend fun insert(usage: SubscriptionUsage): Long
    
    @Query("SELECT * FROM subscription_usage WHERE subscriptionId = :subscriptionId ORDER BY usedAt DESC")
    fun getUsageForSubscription(subscriptionId: Long): Flow<List<SubscriptionUsage>>
    
    @Query("SELECT * FROM subscription_usage WHERE subscriptionId = :subscriptionId AND usedAt >= :since ORDER BY usedAt DESC")
    suspend fun getUsageSince(subscriptionId: Long, since: Long): List<SubscriptionUsage>
    
    @Query("SELECT COUNT(*) FROM subscription_usage WHERE subscriptionId = :subscriptionId AND usedAt >= :since")
    suspend fun getUsageCountSince(subscriptionId: Long, since: Long): Int
    
    @Query("SELECT * FROM subscription_usage WHERE subscriptionId = :subscriptionId AND usedAt >= :startDate AND usedAt < :endDate ORDER BY usedAt DESC")
    suspend fun getUsageBetween(subscriptionId: Long, startDate: Long, endDate: Long): List<SubscriptionUsage>
    
    @Query("SELECT * FROM subscription_usage WHERE usedAt >= :since ORDER BY usedAt DESC")
    suspend fun getAllUsageSince(since: Long): List<SubscriptionUsage>
    
    @Query("DELETE FROM subscription_usage WHERE subscriptionId = :subscriptionId")
    suspend fun deleteAllForSubscription(subscriptionId: Long)
}
