package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity to store historical financial health scores for trend analysis.
 * Each record represents a snapshot of the financial health score at a specific point in time.
 */
@Entity(
    tableName = "health_score_history",
    indices = [
        Index(value = ["calculatedAt"]),
        Index(value = ["overallScore"]),
        Index(value = ["periodStart", "periodEnd"])
    ]
)
data class HealthScoreHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * The overall composite score (0-100)
     */
    val overallScore: Int,
    
    /**
     * Individual component scores (0-100 each)
     */
    val savingsRateScore: Int,
    val runwayScore: Int,
    val budgetAdherenceScore: Int,
    val billReliabilityScore: Int,
    
    /**
     * Weighted contributions of each component to the overall score
     */
    @ColumnInfo(defaultValue = "0.30")
    val savingsRateWeight: Double = 0.30,
    @ColumnInfo(defaultValue = "0.25")
    val runwayWeight: Double = 0.25,
    @ColumnInfo(defaultValue = "0.25")
    val budgetAdherenceWeight: Double = 0.25,
    @ColumnInfo(defaultValue = "0.20")
    val billReliabilityWeight: Double = 0.20,
    
    /**
     * The time period this score covers
     */
    val periodStart: Long,
    val periodEnd: Long,
    
    /**
     * When this score was calculated
     */
    @ColumnInfo(defaultValue = "0")
    val calculatedAt: Long = System.currentTimeMillis(),
    
    /**
     * Trend direction compared to previous calculation
     */
    @ColumnInfo(defaultValue = "STABLE")
    val trend: String = "STABLE", // "IMPROVING", "STABLE", "DECLINING"
    
    /**
     * The recommendation text generated for this score
     */
    val recommendation: String? = null,
    
    /**
     * Whether this record has been synced to cloud (if applicable)
     */
    @ColumnInfo(defaultValue = "0")
    val isSynced: Boolean = false
)
