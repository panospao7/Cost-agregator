package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.OperationRun

@Dao
interface OperationRunDao {

    @Insert
    suspend fun insert(run: OperationRun): Long

    @Update
    suspend fun update(run: OperationRun)

    @Query("SELECT * FROM operation_runs WHERE id = :id")
    suspend fun getById(id: Long): OperationRun?

    @Query("SELECT * FROM operation_runs WHERE correlationId = :correlationId")
    suspend fun getByCorrelationId(correlationId: String): OperationRun?

    @Query("SELECT * FROM operation_runs WHERE operationType = :type ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(type: String, limit: Int = 20): List<OperationRun>

    @Query("SELECT * FROM operation_runs WHERE status = 'RUNNING' AND startedAt < :staleThresholdMs")
    suspend fun getStaleRunning(staleThresholdMs: Long): List<OperationRun>
}
