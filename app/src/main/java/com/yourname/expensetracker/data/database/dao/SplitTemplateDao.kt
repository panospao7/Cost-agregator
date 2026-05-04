package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface SplitTemplateDao {
    @Query("SELECT * FROM split_templates ORDER BY isDefault DESC, useCount DESC, name ASC")
    fun getAllTemplates(): Flow<List<SplitTemplate>>
    
    @Query("SELECT * FROM split_templates WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultTemplate(): SplitTemplate?
    
    @Query("SELECT * FROM split_templates WHERE id = :templateId")
    suspend fun getTemplateById(templateId: Long): SplitTemplate?
    
    @Insert
    suspend fun insertTemplate(template: SplitTemplate): Long
    
    @Update
    suspend fun updateTemplate(template: SplitTemplate)
    
    @Delete
    suspend fun deleteTemplate(template: SplitTemplate)
    
    /**
     * @param timestamp Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE split_templates SET useCount = useCount + 1, updatedAt = :timestamp WHERE id = :templateId")
    suspend fun incrementUseCount(templateId: Long, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE split_templates SET isDefault = 0 WHERE isDefault = 1")
    suspend fun clearDefaultTemplate()
    
    @Query("UPDATE split_templates SET isDefault = 1 WHERE id = :templateId")
    suspend fun setDefaultTemplate(templateId: Long)
    
    @Query("SELECT COUNT(*) FROM split_templates")
    suspend fun getTemplateCount(): Int
}
