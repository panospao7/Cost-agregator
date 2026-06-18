package com.yourname.expensetracker.domain.notification.capture

import androidx.work.*
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.worker.NotificationIntakeWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationIntakeRecoveryScheduler @Inject constructor(
    private val intakeDao: NotificationIntakeDao,
    private val workManager: WorkManager,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val STALE_PROCESSING_MS = 10 * 60 * 1000L // 10 minutes
    }

    /**
     * Release stale PROCESSING rows and enqueue ready rows for processing.
     * Call on: app start, listener connected, restore complete.
     */
    suspend fun recoverPending(limit: Int = 100) {
        val now = timeProvider.now()
        val staleBefore = now - STALE_PROCESSING_MS

        // Release stale rows
        val released = intakeDao.releaseStaleProcessing(staleBefore, now)
        if (released > 0) {
            Timber.d("IntakeRecovery: released $released stale PROCESSING rows")
        }

        // Enqueue ready rows
        val ready = intakeDao.getReadyForProcessing(now, limit)
        ready.forEach { row ->
            val request = OneTimeWorkRequestBuilder<NotificationIntakeWorker>()
                .setInputData(workDataOf("intakeId" to row.id))
                .addTag("notification-intake")
                .addTag("notification-intake-${row.id}")
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniqueWork(
                "notification-intake-${row.id}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
        if (ready.isNotEmpty()) {
            Timber.d("IntakeRecovery: enqueued ${ready.size} pending intake rows")
        }
    }
}
