package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.SourceStatsEvent

@Dao
interface SourceStatsEventDao {
    @Insert
    suspend fun insert(event: SourceStatsEvent)

    @Query("SELECT * FROM source_stats_events WHERE packageName = :packageName ORDER BY timestamp DESC")
    suspend fun getByPackage(packageName: String): List<SourceStatsEvent>
}
