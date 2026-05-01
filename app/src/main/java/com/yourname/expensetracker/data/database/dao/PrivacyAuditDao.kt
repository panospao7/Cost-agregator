package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent

@Dao
interface PrivacyAuditDao {

    @Insert
    suspend fun insert(event: PrivacyAuditEvent): Long

    @Query("SELECT * FROM privacy_audit_events ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PrivacyAuditEvent>
}
