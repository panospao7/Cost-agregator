package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface PipelineDiagnosticEventDao {

    @Insert
    suspend fun insert(event: PipelineDiagnosticEvent)

    @Query("SELECT * FROM pipeline_diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<PipelineDiagnosticEvent>

    @Query("SELECT * FROM pipeline_diagnostic_events WHERE pipeline = :pipeline ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByPipeline(pipeline: String, limit: Int = 50): List<PipelineDiagnosticEvent>

    @Query("SELECT * FROM pipeline_diagnostic_events WHERE correlationId = :correlationId ORDER BY timestamp ASC")
    suspend fun getByCorrelationId(correlationId: String): List<PipelineDiagnosticEvent>

    @Query("SELECT * FROM pipeline_diagnostic_events WHERE entityType = :entityType AND entityId = :entityId ORDER BY timestamp DESC")
    suspend fun getByEntity(entityType: String, entityId: Long): List<PipelineDiagnosticEvent>

    @Query("""
        SELECT * FROM pipeline_diagnostic_events
        WHERE severity IN ('WARNING','ERROR','CRITICAL')
           OR outcome IN ('FAILED_RETRYABLE','FAILED_FINAL','BLOCKED','DROPPED','CANCELLED','SIDE_EFFECT_FAILED')
        ORDER BY timestamp DESC LIMIT :limit
    """)
    suspend fun getRecentFailures(limit: Int = 50): List<PipelineDiagnosticEvent>

    @Query("SELECT * FROM pipeline_diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<PipelineDiagnosticEvent>>
}
