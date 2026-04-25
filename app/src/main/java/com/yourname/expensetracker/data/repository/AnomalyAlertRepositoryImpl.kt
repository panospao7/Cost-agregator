package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.AnomalyAlertDao
import com.yourname.expensetracker.data.database.entity.AnomalyAlert
import com.yourname.expensetracker.domain.alerts.AnomalyAlertRepository
import com.yourname.expensetracker.domain.alerts.NewAnomalyAlert
import com.yourname.expensetracker.domain.alerts.StoredAnomalyAlert
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnomalyAlertRepositoryImpl @Inject constructor(
    private val anomalyAlertDao: AnomalyAlertDao
) : AnomalyAlertRepository,
    com.yourname.expensetracker.domain.usecase.dashboard.AnomalyAlertRepository {

    override suspend fun getLastAlertForExpense(expenseId: Long): StoredAnomalyAlert? {
        return anomalyAlertDao.getLastAlertForExpense(expenseId)?.toDomain()
    }

    override suspend fun getLastAlertForMerchant(merchant: String, sinceMs: Long): StoredAnomalyAlert? {
        return anomalyAlertDao.getLastAlertForMerchant(merchant, sinceMs)?.toDomain()
    }

    override suspend fun getLastAlertForCategory(category: String, sinceMs: Long): StoredAnomalyAlert? {
        return anomalyAlertDao.getLastAlertForCategory(category, sinceMs)?.toDomain()
    }

    override suspend fun getLooksNormalCountForMerchant(merchant: String): Int {
        return anomalyAlertDao.getLooksNormalCountForMerchant(merchant)
    }

    override suspend fun insert(alert: NewAnomalyAlert): Long {
        return anomalyAlertDao.insert(
            AnomalyAlert(
                expenseId = alert.expenseId,
                merchant = alert.merchant,
                category = alert.category,
                amount = alert.amount,
                anomalyReason = alert.anomalyReason,
                severity = alert.severity,
                alertedAt = alert.alertedAt
            )
        )
    }

    override suspend fun getActiveAlerts(): List<com.yourname.expensetracker.domain.usecase.dashboard.AnomalyAlertRecord> {
        return anomalyAlertDao.getActiveAlerts().map { alert ->
            com.yourname.expensetracker.domain.usecase.dashboard.AnomalyAlertRecord(
                merchant = alert.merchant,
                amount = alert.amount,
                anomalyReason = alert.anomalyReason,
                alertedAt = alert.alertedAt
            )
        }
    }

    private fun AnomalyAlert.toDomain(): StoredAnomalyAlert {
        return StoredAnomalyAlert(
            id = id,
            expenseId = expenseId,
            merchant = merchant,
            category = category,
            amount = amount,
            anomalyReason = anomalyReason,
            severity = severity,
            alertedAt = alertedAt,
            dismissed = dismissed,
            dismissedAt = dismissedAt,
            userFeedback = userFeedback
        )
    }
}
