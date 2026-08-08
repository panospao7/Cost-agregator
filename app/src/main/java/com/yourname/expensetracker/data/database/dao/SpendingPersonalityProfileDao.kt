package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.SpendingPersonalityProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing and managing spending personality profile data.
 */
@Dao
interface SpendingPersonalityProfileDao {
    
    /**
     * Insert a new personality profile.
     * Returns the ID of the inserted profile.
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: SpendingPersonalityProfileEntity): Long
    
    /**
     * Update an existing personality profile.
     */
    @Update
    suspend fun update(profile: SpendingPersonalityProfileEntity)
    
    /**
     * Get the currently active personality profile.
     */
    @Query("SELECT * FROM spending_personality_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfile(): SpendingPersonalityProfileEntity?
    
    /**
     * Get the currently active personality profile as a Flow for reactive updates.
     */
    @Query("SELECT * FROM spending_personality_profiles WHERE isActive = 1 LIMIT 1")
    fun getActiveProfileFlow(): Flow<SpendingPersonalityProfileEntity?>
    
    /**
     * Get the most recent personality profile regardless of active status.
     */
    @Query("SELECT * FROM spending_personality_profiles ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getLatestProfile(): SpendingPersonalityProfileEntity?
    
    /**
     * Get all personality profiles ordered by last updated (newest first).
     */
    @Query("SELECT * FROM spending_personality_profiles ORDER BY lastUpdated DESC")
    suspend fun getAllProfiles(): List<SpendingPersonalityProfileEntity>
    
    /**
     * Get all personality profiles as a Flow.
     */
    @Query("SELECT * FROM spending_personality_profiles ORDER BY lastUpdated DESC")
    fun getAllProfilesFlow(): Flow<List<SpendingPersonalityProfileEntity>>
    
    /**
     * Get personality profiles for a specific type.
     */
    @Query("SELECT * FROM spending_personality_profiles WHERE personalityType = :type ORDER BY lastUpdated DESC")
    suspend fun getProfilesByType(type: String): List<SpendingPersonalityProfileEntity>
    
    /**
     * Mark a profile as viewed.
     *
     * @param timestamp Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    @Query("UPDATE spending_personality_profiles SET isViewed = 1, viewedAt = :timestamp WHERE id = :profileId")
    suspend fun markAsViewed(profileId: Long, timestamp: Long)
    
    /**
     * Set a profile as the active one and deactivate all others.
     */
    @Query("UPDATE spending_personality_profiles SET isActive = (id = :profileId)")
    suspend fun setActiveProfile(profileId: Long)
    
    /**
     * Deactivate all profiles.
     */
    @Query("UPDATE spending_personality_profiles SET isActive = 0")
    suspend fun deactivateAllProfiles()
    
    /**
     * Delete all personality profiles.
     */
    @Query("DELETE FROM spending_personality_profiles")
    suspend fun deleteAllProfiles()
    
    /**
     * Delete a specific profile by ID.
     */
    @Query("DELETE FROM spending_personality_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: Long)
    
    /**
     * Delete old profiles keeping only the most recent N.
     */
    @Query("""
        DELETE FROM spending_personality_profiles 
        WHERE id NOT IN (
            SELECT id FROM spending_personality_profiles 
            ORDER BY lastUpdated DESC 
            LIMIT :keepCount
        )
    """)
    suspend fun deleteOldProfiles(keepCount: Int)
    
    /**
     * Count total number of profiles.
     */
    @Query("SELECT COUNT(*) FROM spending_personality_profiles")
    suspend fun getProfileCount(): Int
    
    /**
     * Get the most recent profile timestamp.
     */
    @Query("SELECT MAX(lastUpdated) FROM spending_personality_profiles")
    suspend fun getLatestProfileTimestamp(): Long?
    
    /**
     * Check if a profile exists that was computed within the given time window.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM spending_personality_profiles 
            WHERE lastUpdated > :sinceTimestamp 
            LIMIT 1
        )
    """)
    suspend fun hasRecentProfile(sinceTimestamp: Long): Boolean
}
