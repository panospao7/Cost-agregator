package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.NegotiationOutcomeEntity

@Dao
interface NegotiationOutcomeDao {
    @Insert
    suspend fun insert(outcome: NegotiationOutcomeEntity): Long

    @Query("SELECT * FROM negotiation_outcomes WHERE subscriptionId = :subscriptionId ORDER BY createdAt DESC")
    suspend fun getBySubscriptionId(subscriptionId: Long): List<NegotiationOutcomeEntity>

    @Query("SELECT * FROM negotiation_outcomes ORDER BY createdAt DESC")
    suspend fun getAll(): List<NegotiationOutcomeEntity>
}
