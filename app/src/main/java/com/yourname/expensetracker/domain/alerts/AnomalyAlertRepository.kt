package com.yourname.expensetracker.domain.alerts

interface AnomalyAlertRepository {
    suspend fun getLastAlertForExpense(expenseId: Long): StoredAnomalyAlert?
    suspend fun getLastAlertForMerchant(merchant: String, sinceMs: Long): StoredAnomalyAlert?
    suspend fun getLastAlertForCategory(category: String, sinceMs: Long): StoredAnomalyAlert?
    suspend fun getLooksNormalCountForMerchant(merchant: String): Int
    suspend fun insert(alert: NewAnomalyAlert): Long
}

data class StoredAnomalyAlert(
    val id: Long,
    val expenseId: Long,
    val merchant: String,
    val category: String?,
    val amount: Double,
    val anomalyReason: String,
    val severity: String,
    val alertedAt: Long,
    val dismissed: Boolean,
    val dismissedAt: Long?,
    val userFeedback: String?
)

data class NewAnomalyAlert(
    val expenseId: Long,
    val merchant: String,
    val category: String?,
    val amount: Double,
    val anomalyReason: String,
    val severity: String,
    val alertedAt: Long
)
