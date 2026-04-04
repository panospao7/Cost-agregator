package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.database.entity.SubscriptionUsage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionManagementRepository @Inject constructor(
    private val subscriptionDao: ManualRecurringExpenseDao,
    private val priceHistoryDao: SubscriptionPriceHistoryDao,
    private val usageDao: SubscriptionUsageDao,
    private val candidateDao: SubscriptionCandidateDao
) {
    suspend fun getAllActiveSubscriptions(): List<ManualRecurringExpense> =
        subscriptionDao.getAllActiveSubscriptions()

    suspend fun getPriceHistoryForSubscription(subscriptionId: Long): Flow<List<SubscriptionPriceHistory>> =
        priceHistoryDao.getPriceHistoryForSubscription(subscriptionId)

    suspend fun getUsageCountSince(subscriptionId: Long, since: Long): Int =
        usageDao.getUsageCountSince(subscriptionId, since)

    suspend fun getPendingCandidates(): List<SubscriptionCandidate> =
        candidateDao.getPendingCandidates()

    suspend fun insertUsage(usage: SubscriptionUsage): Long = usageDao.insert(usage)

    suspend fun getSubscriptionById(subscriptionId: Long): ManualRecurringExpense? =
        subscriptionDao.getById(subscriptionId)

    suspend fun updateSubscription(subscription: ManualRecurringExpense) =
        subscriptionDao.update(subscription)

    suspend fun deleteSubscriptionById(subscriptionId: Long) =
        subscriptionDao.deleteById(subscriptionId)

    suspend fun insertSubscription(subscription: ManualRecurringExpense): Long =
        subscriptionDao.insert(subscription)

    suspend fun insertPriceHistory(entry: SubscriptionPriceHistory): Long =
        priceHistoryDao.insert(entry)

    suspend fun markCandidateAsConverted(candidateId: Long, subscriptionId: Long, timestamp: Long) =
        candidateDao.markAsConverted(candidateId, subscriptionId, timestamp)

    suspend fun markCandidateAsRejected(candidateId: Long, timestamp: Long) =
        candidateDao.markAsRejected(candidateId, timestamp)
}
