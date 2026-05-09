package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
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
    private val database: AppDatabase,
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val classifier: TransactionClassifier,
    private val pipeline: NotificationProcessingPipeline,
    private val writeBarrier: DatabaseWriteBarrier
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
    suspend fun save(notification: RawNotification): Long {
        writeBarrier.checkWritesAllowed("NotificationRepository.save")
        return dao.insert(notification)
    }
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?, bigText: String? = null): Boolean =
        dao.exists(packageName, timestamp, title, text, bigText)

    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()

    // === Classifier Stats ===
    fun getClassifierStatsFlow(): Flow<ClassifierStats> = classifier.stats
    suspend fun getClassifierStats() = classifier.getStats()

    // === Core Processing Pipeline (delegated) ===
    suspend fun processAndSave(notification: RawNotification) {
        when (val outcome = pipeline.process(notification)) {
            is NotificationProcessingPipeline.NotificationPipelineOutcome.AutoAccepted ->
                Timber.i("Notification auto-accepted: expenseId=%d", outcome.expenseId)
            is NotificationProcessingPipeline.NotificationPipelineOutcome.NeedsReview ->
                Timber.i("Notification queued for review: reviewId=%d", outcome.reviewId)
            is NotificationProcessingPipeline.NotificationPipelineOutcome.Duplicate ->
                Timber.w("Notification duplicate: reason=%s", outcome.reason)
            is NotificationProcessingPipeline.NotificationPipelineOutcome.ParserFailed ->
                Timber.w("Notification parser failed: reason=%s", outcome.reason)
            is NotificationProcessingPipeline.NotificationPipelineOutcome.AutoRejected ->
                Timber.w("Notification auto-rejected: reason=%s", outcome.reason)
            is NotificationProcessingPipeline.NotificationPipelineOutcome.Dropped ->
                Timber.w("Notification dropped: reason=%s", outcome.reason)
            is NotificationProcessingPipeline.NotificationPipelineOutcome.Error ->
                Timber.e(outcome.throwable, "Notification processing failed for ${outcome.packageName}")
        }
    }

    suspend fun processAndSaveAll(notifications: List<RawNotification>) {
        pipeline.processBatch(notifications).forEach { outcome ->
            when (outcome) {
                is NotificationProcessingPipeline.NotificationPipelineOutcome.AutoAccepted ->
                    Timber.i("Batch auto-accepted: expenseId=%d", outcome.expenseId)
                is NotificationProcessingPipeline.NotificationPipelineOutcome.NeedsReview ->
                    Timber.i("Batch queued for review: reviewId=%d", outcome.reviewId)
                is NotificationProcessingPipeline.NotificationPipelineOutcome.Duplicate ->
                    Timber.w("Batch duplicate: reason=%s", outcome.reason)
                is NotificationProcessingPipeline.NotificationPipelineOutcome.ParserFailed ->
                    Timber.w("Batch parser failed: reason=%s", outcome.reason)
                is NotificationProcessingPipeline.NotificationPipelineOutcome.AutoRejected ->
                    Timber.w("Batch auto-rejected: reason=%s", outcome.reason)
                is NotificationProcessingPipeline.NotificationPipelineOutcome.Dropped ->
                    Timber.w("Batch dropped: reason=%s", outcome.reason)
                is NotificationProcessingPipeline.NotificationPipelineOutcome.Error ->
                    Timber.e(outcome.throwable, "Batch notification failed for ${outcome.packageName}")
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
        writeBarrier.checkWritesAllowed("NotificationRepository.delete")
        database.withTransaction {
            val pendingReview = pendingReviewDao.getByRawId(notification.id)
            if (pendingReview != null && pendingReview.status == PendingReviewStatus.PENDING) {
                sourceStatsDao.decrementPending(notification.packageName)
            }
            pendingReviewDao.deleteByRawId(notification.id)
            dao.delete(notification)
        }
    }

    // === Classifier Management ===
    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }

    // === Bulk operations ===

    /**
     * Deletes all raw notifications, pending reviews, and user corrections,
     * and resets source-stat pending counts.
     *
     * Unlike [deleteAll] this does **not** touch the expenses table, making it
     * safe for notification-specific cleanup without losing imported expense records.
     */
    suspend fun deleteAllNotifications() {
        writeBarrier.checkWritesAllowed("NotificationRepository.deleteAllNotifications")
        database.withTransaction {
            dao.deleteAll()
            pendingReviewDao.deleteAll()
            userCorrectionDao.deleteAll()
            sourceStatsDao.resetAllPendingCounts()
        }
    }

    /**
     * Deletes ALL raw notifications, expenses, pending reviews, user corrections
     * and resets source-stat pending counts.
     *
     * WARNING: This method also wipes the **expenses** table, which is almost
     * never the intended operation outside of full data resets during development
     * or testing. Callers in production flows must use targeted cleanup methods
     * (e.g. [delete], [NotificationProcessingPipeline]) to avoid accidental
     * data loss of imported expense records.
     *
     * @deprecated Use [deleteAllNotifications] for notification-only cleanup.
     * Scheduled for removal in next major version.
     */
    @Deprecated(
        "Dangerous: use targeted cleanup instead — this deletes ALL expenses. Use deleteAllNotifications() for notification-only cleanup.",
        level = DeprecationLevel.ERROR
    )
    suspend fun deleteAll() {
        writeBarrier.checkWritesAllowed("NotificationRepository.deleteAll")
        database.withTransaction {
            dao.deleteAll()
            expenseDao.deleteAll()
            pendingReviewDao.deleteAll()
            userCorrectionDao.deleteAll()
            sourceStatsDao.resetAllPendingCounts()
        }
    }

    suspend fun resetSourceStats() {
        writeBarrier.checkWritesAllowed("NotificationRepository.resetSourceStats")
        sourceStatsDao.deleteAll()
    }

    suspend fun createDebugSnapshot(): DebugNotificationsSnapshot {
        if (!com.yourname.expensetracker.BuildConfig.DEBUG) {
            throw UnsupportedOperationException("Debug snapshots disabled in release")
        }
        return DebugNotificationsSnapshot(
            notifications = dao.getAll(),
            sourceStats = sourceStatsDao.getAll()
        )
    }

    suspend fun restoreDebugSnapshot(snapshot: DebugNotificationsSnapshot) {
        writeBarrier.checkWritesAllowed("NotificationRepository.restoreDebugSnapshot")
        if (!com.yourname.expensetracker.BuildConfig.DEBUG) {
            throw UnsupportedOperationException("Debug snapshots disabled in release")
        }
        database.withTransaction {
            dao.deleteAll()
            sourceStatsDao.deleteAll()
            if (snapshot.notifications.isNotEmpty()) {
                dao.insertAll(snapshot.notifications)
            }
            if (snapshot.sourceStats.isNotEmpty()) {
                sourceStatsDao.insertAll(snapshot.sourceStats)
            }
        }
    }

    suspend fun getSourceStatsSnapshot(): List<SourceStats> {
        return sourceStatsDao.getAll()
    }

    suspend fun restoreSourceStatsSnapshot(stats: List<SourceStats>) {
        database.withTransaction {
            sourceStatsDao.deleteAll()
            if (stats.isNotEmpty()) {
                sourceStatsDao.insertAll(stats)
            }
        }
    }
}
