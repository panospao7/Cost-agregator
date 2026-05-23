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
import com.yourname.expensetracker.domain.notification.NotificationPersistenceContext
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
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
    private val writeBarrier: DatabaseWriteBarrier,
    private val privacySettingsRepository: com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
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

    /**
     * Process a raw notification through the pipeline and return the outcome.
     *
     * Loads current privacy settings internally and sanitizes the storage
     * notification according to [RawStorageMode]. The raw notification is
     * used for parsing only; persisted data respects the user's privacy choice.
     */
    suspend fun processAndSave(notification: RawNotification): NotificationPipelineOutcome {
        val settings = privacySettingsRepository.getSettings()
        val storageNotification = sanitizeForStorage(notification, settings.rawNotificationStorageMode)
        val ctx = NotificationPersistenceContext(settings.rawNotificationStorageMode, null, "direct")
        return processAndSave(notification, storageNotification, persistenceContext = ctx)
    }

    /**
     * Build a sanitized storage notification based on [rawStorageMode].
     */
    private fun sanitizeForStorage(
        raw: RawNotification,
        mode: com.yourname.expensetracker.domain.privacy.RawStorageMode
    ): RawNotification = when (mode) {
        com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_RAW -> raw
        com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_REDACTED -> raw.copy(
            title = "[REDACTED]", text = "[REDACTED]", bigText = "[REDACTED]",
            subText = "[REDACTED]", extrasJson = """{"redacted":true}"""
        )
        com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_METADATA_ONLY,
        com.yourname.expensetracker.domain.privacy.RawStorageMode.DO_NOT_STORE -> raw.copy(
            title = null, text = null, bigText = null, subText = null, extrasJson = null
        )
    }

    /**
     * Process using [processingNotification] (ephemeral text for parsing) but persist
     * [storageNotification] (sanitized per privacy settings) to the database.
     *
     * @return The [NotificationPipelineOutcome] so callers can react truthfully
     *         (e.g. dedupe retention, user-facing feedback, diagnostics).
     */
    suspend fun processAndSave(processingNotification: RawNotification, storageNotification: RawNotification,
                               correlationId: String? = null,
                               persistenceContext: NotificationPersistenceContext? = null): NotificationPipelineOutcome {
        val outcome = pipeline.process(processingNotification, storageNotification, correlationId = correlationId,
            persistenceContext = persistenceContext)
        when (outcome) {
            is NotificationPipelineOutcome.AutoAccepted ->
                Timber.i("Notification auto-accepted: expenseId=%d", outcome.expenseId)
            is NotificationPipelineOutcome.NeedsReview ->
                Timber.i("Notification queued for review: reviewId=%d", outcome.reviewId)
            is NotificationPipelineOutcome.Duplicate ->
                Timber.w("Notification duplicate: reason=%s", outcome.reason)
            is NotificationPipelineOutcome.ParserFailed ->
                Timber.w("Notification parser failed: reason=%s", outcome.reason)
            is NotificationPipelineOutcome.AutoRejected ->
                Timber.w("Notification auto-rejected: reason=%s", outcome.reason)
            is NotificationPipelineOutcome.Dropped ->
                Timber.w("Notification dropped: reason=%s", outcome.reason)
            is NotificationPipelineOutcome.Error ->
                Timber.e(outcome.throwable, "Notification processing failed for ${outcome.packageName}")
        }
        // P1-NEW-14: Mark raw rows processed from repository (covers direct/batch paths too)
        when (outcome) {
            is NotificationPipelineOutcome.AutoAccepted -> dao.markProcessed(outcome.rawId)
            is NotificationPipelineOutcome.NeedsReview -> dao.markProcessed(outcome.rawId)
            is NotificationPipelineOutcome.ParserFailed -> outcome.rawId?.let { dao.markProcessed(it) }
            is NotificationPipelineOutcome.AutoRejected -> outcome.rawId?.let { dao.markProcessed(it) }
            else -> { /* Duplicate, Dropped, Error: no raw row to mark */ }
        }
        return outcome
    }

    suspend fun processAndSaveAll(notifications: List<RawNotification>): List<NotificationPipelineOutcome> {
        return notifications.map { processAndSave(it) }
    }

    // === Package blocking ===
    suspend fun blockPackage(packageName: String) {
        writeBarrier.checkWritesAllowed("NotificationRepository.blockPackage")
        blockedPackageDao.block(BlockedPackage(packageName))
    }

    suspend fun unblockPackage(packageName: String) {
        writeBarrier.checkWritesAllowed("NotificationRepository.unblockPackage")
        blockedPackageDao.unblock(packageName)
    }

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
        writeBarrier.checkWritesAllowed("NotificationRepository.restoreSourceStatsSnapshot")
        database.withTransaction {
            sourceStatsDao.deleteAll()
            if (stats.isNotEmpty()) {
                sourceStatsDao.insertAll(stats)
            }
        }
    }
}
