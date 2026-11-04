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

        /** Hard cap on keyset pages per recovery run; any remainder is picked up by the next run. */
        private const val MAX_RECOVERY_PAGES = 20
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

        // Enqueue ready rows, one keyset page (capturedAt, id) at a time, bounded by MAX_RECOVERY_PAGES.
        // Rows stay RECEIVED after enqueue, so a cursor (not a repeated first page) is required to
        // reach the backlog beyond `limit`.
        val pageSize = limit.coerceAtLeast(1)
        var afterCapturedAt = Long.MIN_VALUE
        var afterId = Long.MIN_VALUE
        var enqueued = 0
        var pages = 0
        while (pages < MAX_RECOVERY_PAGES) {
            val ready = intakeDao.getReadyForProcessingPage(now, afterCapturedAt, afterId, pageSize)
            pages++
            ready.forEach { row ->
                val request = OneTimeWorkRequestBuilder<NotificationIntakeWorker>()
                    .setInputData(workDataOf("intakeId" to row.id))
                    .addTag("notification-intake")
                    .addTag("notification-intake-${row.id}")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

                // P-01-1: same name function as the coordinator. Legacy work named `<id>`
                // is not cancelled; a second worker for the row is safe because the
                // worker's atomic claim makes the loser a no-op.
                workManager.enqueueUniqueWork(
                    NotificationIntakeWorkNames.uniqueWorkName(row.id),
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }
            enqueued += ready.size
            val last = ready.lastOrNull() ?: break
            if (ready.size < pageSize) break
            afterCapturedAt = last.capturedAt
            afterId = last.id
        }
        if (enqueued > 0) {
            Timber.d("IntakeRecovery: enqueued $enqueued pending intake rows in $pages page(s)")
        }
    }
}
