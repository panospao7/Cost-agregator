package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity to store financial stress forecast snapshots for trend tracking.
 * Each record represents a snapshot of the stress forecast at a specific point in time.
 */
@Entity(
    tableName = "stress_forecast_snapshots",
    indices = [
        Index(value = ["computedAt"]),
        Index(value = ["overallRiskLevel"]),
        Index(value = ["days30RiskLevel"]),
        Index(value = ["days60RiskLevel"]),
        Index(value = ["days90RiskLevel"])
    ]
)
data class StressForecastSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * Overall risk level at time of snapshot
     */
    val overallRiskLevel: String, // "LOW", "MODERATE", "ELEVATED", "HIGH", "CRITICAL"
    
    /**
     * 30-day horizon data
     */
    val days30ProjectedBalance: Double,
    val days30MinBalance: Double,
    val days30ProbabilityOfCrunch: Double,
    val days30RiskLevel: String,
    val days30RecurringObligations: Double,
    val days30ExpectedIncome: Double,
    val days30DiscretionaryBuffer: Double,
    
    /**
     * 60-day horizon data
     */
    val days60ProjectedBalance: Double,
    val days60MinBalance: Double,
    val days60ProbabilityOfCrunch: Double,
    val days60RiskLevel: String,
    val days60RecurringObligations: Double,
    val days60ExpectedIncome: Double,
    val days60DiscretionaryBuffer: Double,
    
    /**
     * 90-day horizon data
     */
    val days90ProjectedBalance: Double,
    val days90MinBalance: Double,
    val days90ProbabilityOfCrunch: Double,
    val days90RiskLevel: String,
    val days90RecurringObligations: Double,
    val days90ExpectedIncome: Double,
    val days90DiscretionaryBuffer: Double,
    
    /**
     * Earliest predicted crunch date (null if no crunch risk)
     */
    val earliestCrunchDate: Long?,
    
    /**
     * Recommendations at time of snapshot (stored as JSON)
     */
    val recommendationsJson: String?,
    
    /**
     * Current balance at time of snapshot
     */
    val currentBalance: Double,
    
    /**
     * When this snapshot was computed
     */
    @ColumnInfo(defaultValue = "0")
    val computedAt: Long = System.currentTimeMillis(),
    
    /**
     * Whether this record has been synced to cloud
     */
    @ColumnInfo(defaultValue = "0")
    val isSynced: Boolean = false
)
