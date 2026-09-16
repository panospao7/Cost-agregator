package com.yourname.expensetracker.domain.notification.capture

import androidx.work.*
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier
) {
    companion object {
        private const val STALE_PROCESSING_MS = 10 * 60 * 1000L // 10 minutes
    }

    /**
     * Release stale PROCESSING rows and enqueue ready rows for processing.
     * Concrete hooks (RP-10 10b, P1-003): app start via
     * [com.yourname.expensetracker.startup.AppStartupCoordinator], listener
     * connected via [com.yourname.expensetracker.service.NotificationCaptureService];
     * restore-complete is served by the next app start (a completed restore
     * forces a restart). Uses WorkManager unique work only — no second
     * scheduling path.
     */
    suspend fun recoverPending(limit: Int = 100) {
        try {
            writeBarrier.checkWritesAllowed("NotificationIntakeRecoveryScheduler.recoverPending")
        } catch (blocked: DatabaseAccessBlockedException) {
            Timber.w("Intake recovery skipped: database writes blocked during restore")
            return
        }

        val now = timeProvider.now()
        val staleBefore = now - STALE_PROCESSING_MS

        // Release stale rows
        val released = try {
            writeBarrier.runWrite(
                DatabaseAccessOperation("NotificationIntakeRecoveryScheduler.recoverPending")
            ) {
                intakeDao.releaseStaleProcessing(staleBefore, now)
            }
        } catch (blocked: DatabaseAccessBlockedException) {
            Timber.w("Intake recovery skipped: database writes blocked during restore")
            return
        }
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
