package com.yourname.expensetracker.domain.usecase.dashboard

/**
 * Domain port for reading anomaly alerts needed by dashboard money radar.
 */
interface AnomalyAlertRepository {
    suspend fun getActiveAlerts(): List<AnomalyAlertRecord>
}

data class AnomalyAlertRecord(
    val merchant: String,
    val amount: Double,
    val anomalyReason: String,
    val alertedAt: Long
)
