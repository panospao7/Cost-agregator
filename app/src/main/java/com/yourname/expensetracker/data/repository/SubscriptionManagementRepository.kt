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
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

/**
 * Subscription-management repository.
 *
 * RP-04 slice A1: all recurring-rule mutations (update / delete / activate /
 * deactivate) are delegated to [RecurringRuleLifecycleCoordinator] — the single
 * legal writer for `ManualRecurringExpense` lifecycle. This repository keeps
 * only subscription-specific reads/writes (price history, usage, candidates)
 * plus display-only helpers.
 *
 * `subscriptionCategory` is DISPLAY-ONLY (verified 2026-09-19: the only
 * production consumer is `SubscriptionManagementScreen` rendering; no
 * occurrence generator or matcher reads it), so its update is a scoped
 * display-column write that intentionally does not mutate rule semantics
 * through the coordinator (RP-04 plan step 7 carve-out).
 */
@Singleton
class SubscriptionManagementRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val subscriptionDao: ManualRecurringExpenseDao,
    private val priceHistoryDao: SubscriptionPriceHistoryDao,
    private val usageDao: SubscriptionUsageDao,
    private val candidateDao: SubscriptionCandidateDao,
    private val ruleLifecycleCoordinator: dagger.Lazy<com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator>
) {
    suspend fun getAllActiveSubscriptions(): List<ManualRecurringExpense> =
        subscriptionDao.getAllActiveSubscriptions()

    suspend fun getPriceHistoryForSubscription(subscriptionId: Long): Flow<List<SubscriptionPriceHistory>> =
        priceHistoryDao.getPriceHistoryForSubscription(subscriptionId)

    suspend fun getUsageCountSince(subscriptionId: Long, since: Long): Int =
        usageDao.getUsageCountSince(subscriptionId, since)

    suspend fun getPendingCandidates(): List<SubscriptionCandidate> =
        candidateDao.getPendingCandidates()

    suspend fun insertUsage(usage: SubscriptionUsage): Long {
        writeBarrier.checkWritesAllowed("SubscriptionManagementRepository.insertUsage")
        return usageDao.insert(usage)
    }

    suspend fun getSubscriptionById(subscriptionId: Long): ManualRecurringExpense? =
        subscriptionDao.getById(subscriptionId)

    /**
     * RP-04: rule updates route through the lifecycle coordinator
     * (atomic update + occurrence/planned/reminder regeneration + critical event).
     */
    suspend fun updateSubscription(subscription: ManualRecurringExpense) {
        ruleLifecycleCoordinator.get().updateRule(subscription)
    }

    /**
     * RP-04: rule deletion routes through the lifecycle coordinator
     * (atomic purge of occurrences, deliveries, planned rows, and rule row).
     */
    suspend fun deleteSubscriptionById(subscriptionId: Long) {
        ruleLifecycleCoordinator.get().deleteRule(subscriptionId)
    }

    /**
     * RP-04: active-state toggling routes through the lifecycle coordinator's
     * activate/deactivate operations (regeneration/deactivation semantics,
     * not a bare isActive column write).
     */
    suspend fun setActive(subscriptionId: Long, isActive: Boolean) {
        if (isActive) {
            ruleLifecycleCoordinator.get().activateRule(subscriptionId)
        } else {
            ruleLifecycleCoordinator.get().deactivateRule(subscriptionId)
        }
    }

    /**
     * RP-04 display-only carve-out (plan step 7): updates ONLY the
     * `subscriptionCategory` display column. Verified: no occurrence generator
     * or matcher consumes this field — its only consumer is the subscription
     * screen's category label. This is intentionally NOT a coordinator rule
     * update; it must never be used to change rule semantics (amount, date,
     * frequency, currency, active state).
     */
    suspend fun updateSubscriptionCategory(subscriptionId: Long, category: String) {
        writeBarrier.checkWritesAllowed("SubscriptionManagementRepository.updateSubscriptionCategory")
        // Scoped SQL op: only the display column is touched; a missing id is a
        // no-op (no read-modify-write, no full-rule update).
        subscriptionDao.updateSubscriptionCategory(subscriptionId, category)
    }

    suspend fun markCandidateAsRejected(candidateId: Long, timestamp: Long) {
        writeBarrier.checkWritesAllowed("SubscriptionManagementRepository.markCandidateAsRejected")
        candidateDao.markAsRejected(candidateId, timestamp)
    }
}
