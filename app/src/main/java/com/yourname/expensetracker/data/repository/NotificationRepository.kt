package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data-access layer for raw notifications and related entities.
 *
 * Heavy processing logic has been moved to [NotificationProcessingPipeline]
 * to keep this class focused on data access.
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val classifier: TransactionClassifier,
    private val pipeline: NotificationProcessingPipeline
) {

    data class DebugNotificationsSnapshot(
        val notifications: List<RawNotification>,
        val sourceStats: List<SourceStats>
    )

    // === Notification access ===
    fun getAllNotifications(): Flow<List<RawNotification>> = dao.getAllFlow()
    fun getRecentNotifications(limit: Int = 100): Flow<List<RawNotification>> =
        dao.getRecentFlow(limit)
    fun getNotificationsByPackage(packageName: String): Flow<List<RawNotification>> =
        dao.getByPackageFlow(packageName)
    fun getAllPackages(): Flow<List<String>> = dao.getAllPackagesFlow()
    fun getCount(): Flow<Int> = dao.getCountFlow()
    suspend fun save(notification: RawNotification): Long = dao.insert(notification)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean =
        dao.exists(packageName, timestamp, title, text)

    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()

    // === Classifier Stats ===
    fun getClassifierStatsFlow(): Flow<ClassifierStats> = classifier.stats
    suspend fun getClassifierStats() = classifier.getStats()

    // === Core Processing Pipeline (delegated) ===
    suspend fun processAndSave(notification: RawNotification) {
        when (val result = pipeline.process(notification)) {
            is NotificationProcessingPipeline.ProcessingResult.Success -> Unit
            is NotificationProcessingPipeline.ProcessingResult.Rejected -> {
                Timber.w("Notification rejected: ${result.packageName}, reason=${result.reason}")
            }
            is NotificationProcessingPipeline.ProcessingResult.Error -> {
                Timber.e(result.error, "Notification processing failed for ${result.packageName}")
            }
        }
    }

    suspend fun processAndSaveAll(notifications: List<RawNotification>) {
        pipeline.processBatch(notifications).forEach { result ->
            when (result) {
                is NotificationProcessingPipeline.ProcessingResult.Success -> Unit
                is NotificationProcessingPipeline.ProcessingResult.Rejected -> {
                    Timber.w("Batch notification rejected: ${result.packageName}, reason=${result.reason}")
                }
                is NotificationProcessingPipeline.ProcessingResult.Error -> {
                    Timber.e(result.error, "Batch notification failed for ${result.packageName}")
                }
            }
        }
    }

    // === Package blocking ===
    suspend fun blockPackage(packageName: String) =
        blockedPackageDao.block(BlockedPackage(packageName))

    suspend fun unblockPackage(packageName: String) =
        blockedPackageDao.unblock(packageName)

    suspend fun isPackageBlocked(packageName: String): Boolean =
        blockedPackageDao.isBlocked(packageName)

    fun getBlockedPackages(): Flow<List<BlockedPackage>> =
        blockedPackageDao.getAllFlow()

    suspend fun delete(notification: RawNotification) {
        val pendingReview = pendingReviewDao.getByRawId(notification.id)
        if (pendingReview != null && pendingReview.status == PendingReviewStatus.PENDING) {
            sourceStatsDao.decrementPending(notification.packageName)
        }
        pendingReviewDao.deleteByRawId(notification.id)
        dao.delete(notification)
    }

    // === Classifier Management ===
    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }

    // === Bulk operations ===
    suspend fun deleteAll() {
        dao.deleteAll()
        expenseDao.deleteAll()
        pendingReviewDao.deleteAll()
        userCorrectionDao.deleteAll()
        sourceStatsDao.resetAllPendingCounts()
    }

    suspend fun resetSourceStats() {
        sourceStatsDao.deleteAll()
    }

    suspend fun createDebugSnapshot(): DebugNotificationsSnapshot {
        return DebugNotificationsSnapshot(
            notifications = dao.getAll(),
            sourceStats = sourceStatsDao.getAll()
        )
    }

    suspend fun restoreDebugSnapshot(snapshot: DebugNotificationsSnapshot) {
        dao.deleteAll()
        sourceStatsDao.deleteAll()
        if (snapshot.notifications.isNotEmpty()) {
            dao.insertAll(snapshot.notifications)
        }
        if (snapshot.sourceStats.isNotEmpty()) {
            sourceStatsDao.insertAll(snapshot.sourceStats)
        }
    }

    suspend fun getSourceStatsSnapshot(): List<SourceStats> {
        return sourceStatsDao.getAll()
    }

    suspend fun restoreSourceStatsSnapshot(stats: List<SourceStats>) {
        sourceStatsDao.deleteAll()
        if (stats.isNotEmpty()) {
            sourceStatsDao.insertAll(stats)
        }
    }
}
