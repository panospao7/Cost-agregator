package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.EntitySourceLink

@Dao
interface EntitySourceLinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: EntitySourceLink): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<EntitySourceLink>): List<Long>

    @Query("""
        SELECT * FROM entity_source_links
        WHERE targetEntityType = :targetType
          AND targetEntityId = :targetId
        ORDER BY isPrimary DESC, createdAt ASC
    """)
    suspend fun getForTarget(targetType: String, targetId: Long): List<EntitySourceLink>

    @Query("""
        SELECT * FROM entity_source_links
        WHERE sourceIdentityKey = :sourceIdentityKey
        ORDER BY createdAt DESC
    """)
    suspend fun getBySourceIdentityKey(sourceIdentityKey: String): List<EntitySourceLink>

    @Query("""
        SELECT * FROM entity_source_links
        WHERE targetEntityType = 'EXPENSE'
          AND targetEntityId = :expenseId
        ORDER BY isPrimary DESC, createdAt ASC
    """)
    suspend fun getForExpense(expenseId: Long): List<EntitySourceLink>

    /**
     * PR7: Bulk query for source links across multiple expenses.
     * Avoids N+1 query problem during export operations.
     */
    @Query("""
        SELECT * FROM entity_source_links
        WHERE targetEntityType = 'EXPENSE'
          AND targetEntityId IN (:expenseIds)
        ORDER BY targetEntityId ASC, isPrimary DESC, createdAt ASC
    """)
    suspend fun getForExpenses(expenseIds: List<Long>): List<EntitySourceLink>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM entity_source_links
            WHERE targetEntityType = :targetType
              AND targetEntityId = :targetId
              AND sourceIdentityKey = :sourceIdentityKey
        )
    """)
    suspend fun exists(targetType: String, targetId: Long, sourceIdentityKey: String): Boolean
}
