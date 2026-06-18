package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.WarrantyLifecycleEvent

@Dao
interface WarrantyLifecycleEventDao {
    @Insert
    suspend fun insert(event: WarrantyLifecycleEvent): Long

    @Query("SELECT * FROM warranty_lifecycle_events WHERE warrantyId = :id ORDER BY occurredAt DESC")
    suspend fun getByWarrantyId(id: Long): List<WarrantyLifecycleEvent>
}
