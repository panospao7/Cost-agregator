package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting the user's spending personality profile.
 * Stores classification results and feature scores for historical tracking.
 */
@Entity(
    tableName = "spending_personality_profiles",
    indices = [
        Index(value = ["lastUpdated"]),
        Index(value = ["personalityType"])
    ]
)
data class SpendingPersonalityProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * The classified personality type.
     */
    val personalityType: String,
    
    /**
     * Confidence score (0.0 - 1.0) in the classification.
     */
    val confidence: Double,
    
    /**
     * JSON-encoded feature scores map.
     * Keys: impulseRatio, merchantDiversity, weekendSpendShare, nightSpendShare,
     *       variance, budgetAdherence, anomalyFrequency, categoryDiversity,
     *       transactionsPerMonth, avgTransactionSize
     */
    @ColumnInfo(defaultValue = "{}")
    val featureScoresJson: String,
    
    /**
     * JSON-encoded list of explanation strings.
     */
    @ColumnInfo(defaultValue = "[]")
    val explanationJson: String,
    
    /**
     * JSON-encoded list of coaching tip strings.
     */
    @ColumnInfo(defaultValue = "[]")
    val coachingTipsJson: String,
    
    /**
     * Timestamp when the profile was computed.
     */
    val lastUpdated: Long,
    
    /**
     * Start timestamp of the analysis period.
     */
    val analysisPeriodStart: Long,
    
    /**
     * End timestamp of the analysis period.
     */
    val analysisPeriodEnd: Long,
    
    /**
     * Number of transactions analyzed.
     */
    val transactionCount: Int,
    
    /**
     * Whether this profile has been viewed by the user.
     */
    @ColumnInfo(defaultValue = "0")
    val isViewed: Boolean = false,
    
    /**
     * Timestamp when the user last viewed this profile.
     */
    val viewedAt: Long? = null,
    
    /**
     * Whether this is the currently active profile.
     * Only one profile should be active at a time.
     */
    @ColumnInfo(defaultValue = "1")
    val isActive: Boolean = true
) {
    companion object {
        /**
         * Create a new entity from domain model data.
         */
        fun fromDomainModel(
            personalityType: String,
            confidence: Double,
            featureScoresJson: String,
            explanationJson: String,
            coachingTipsJson: String,
            analysisPeriodStart: Long,
            analysisPeriodEnd: Long,
            transactionCount: Int
        ): SpendingPersonalityProfileEntity {
            return SpendingPersonalityProfileEntity(
                personalityType = personalityType,
                confidence = confidence,
                featureScoresJson = featureScoresJson,
                explanationJson = explanationJson,
                coachingTipsJson = coachingTipsJson,
                lastUpdated = System.currentTimeMillis(),
                analysisPeriodStart = analysisPeriodStart,
                analysisPeriodEnd = analysisPeriodEnd,
                transactionCount = transactionCount,
                isViewed = false,
                viewedAt = null,
                isActive = true
            )
        }
    }
}
