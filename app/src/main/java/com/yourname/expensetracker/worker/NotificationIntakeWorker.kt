package com.yourname.expensetracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.service.NotificationFilter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.io.IOException

@HiltWorker
class NotificationIntakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val intakeDao: NotificationIntakeDao,
    private val rawDao: RawNotificationDao,
    private val repository: NotificationRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val intakeId = inputData.getLong("intakeId", -1L)
        if (intakeId <= 0L) {
            Timber.w("IntakeWorker: invalid intakeId=$intakeId")
            return Result.failure()
        }

        val intake = intakeDao.getById(intakeId)
        if (intake == null) {
            Timber.w("IntakeWorker: intake row $intakeId not found")
            return Result.failure()
        }

        // Check write barrier
        if (!writeBarrier.writesAllowed()) {
            Timber.d("IntakeWorker: writes blocked, retrying intakeId=$intakeId")
            val now = timeProvider.now()
            intakeDao.markRetryableFailure(
                id = intakeId,
                nextAttemptAt = now + 30_000,
                failureCode = "WRITE_BARRIER_DENIED",
                failureHash = null,
                nowMs = now
            )
            return Result.retry()
        }

        // Claim the row
        val now = timeProvider.now()
        val claimed = intakeDao.claimForProcessing(
            id = intakeId,
            nowMs = now,
            workerId = "intake-worker-${System.currentTimeMillis()}"
        )
        if (claimed == 0) {
            // Already claimed by another worker
            return Result.success()
        }

        // Reload after claim
        val current = intakeDao.getById(intakeId) ?: return Result.failure()

        // Check if payload is available
        if (current.bigText == null && current.title == null && current.text == null) {
            intakeDao.markTerminal(
                id = intakeId,
                status = NotificationIntakeStatus.PAYLOAD_UNAVAILABLE_PRIVACY.name,
                rawId = null, expenseId = null, reviewId = null,
                finalOutcome = "PAYLOAD_UNAVAILABLE_PRIVACY",
                nowMs = now
            )
            return Result.success()
        }

        // Run filter
        if (!NotificationFilter.shouldCapture(
                current.packageName,
                current.title,
                current.text,
                current.bigText
            )) {
            intakeDao.markTerminal(
                id = intakeId,
                status = NotificationIntakeStatus.FILTER_REJECTED.name,
                rawId = null, expenseId = null, reviewId = null,
                finalOutcome = "FILTER_REJECTED",
                nowMs = now
            )
            return Result.success()
        }

        // Build RawNotification for processing
        val processingNotification = RawNotification(
            packageName = current.packageName,
            appName = current.appName,
            title = current.title,
            text = current.text,
            bigText = current.bigText,
            subText = current.subText,
            extrasJson = current.extrasJson,
            timestamp = current.postTime,
            capturedAt = current.capturedAt,
            dedupeFingerprint = current.dedupeFingerprint
        )

        val storageNotification = processingNotification // Already sanitized by intake coordinator

        // Process through pipeline
        return try {
            val outcome = repository.processAndSave(
                processingNotification,
                storageNotification,
                correlationId = current.correlationId
            )

            val terminalStatus = when (outcome) {
                is NotificationPipelineOutcome.AutoAccepted -> NotificationIntakeStatus.PROCESSED
                is NotificationPipelineOutcome.NeedsReview -> NotificationIntakeStatus.PROCESSED
                is NotificationPipelineOutcome.Duplicate -> NotificationIntakeStatus.DROPPED_DUPLICATE
                is NotificationPipelineOutcome.ParserFailed -> NotificationIntakeStatus.FAILED_FINAL
                is NotificationPipelineOutcome.AutoRejected -> NotificationIntakeStatus.DROPPED_POLICY
                is NotificationPipelineOutcome.Dropped -> NotificationIntakeStatus.DROPPED_POLICY
                is NotificationPipelineOutcome.Error -> {
                    if (outcome.throwable is IOException ||
                        outcome.throwable.message?.contains("database is locked", ignoreCase = true) == true) {
                        intakeDao.markRetryableFailure(
                            id = intakeId,
                            nextAttemptAt = now + 60_000,
                            failureCode = "RETRYABLE_ERROR",
                            failureHash = null,
                            nowMs = now
                        )
                        return Result.retry()
                    }
                    NotificationIntakeStatus.FAILED_FINAL
                }
            }

            val rawId = when (outcome) {
                is NotificationPipelineOutcome.AutoAccepted -> outcome.rawId
                is NotificationPipelineOutcome.NeedsReview -> outcome.rawId
                is NotificationPipelineOutcome.ParserFailed -> outcome.rawId
                is NotificationPipelineOutcome.AutoRejected -> outcome.rawId
                else -> null
            }

            intakeDao.markTerminal(
                id = intakeId,
                status = terminalStatus.name,
                rawId = rawId,
                expenseId = when (outcome) {
                    is NotificationPipelineOutcome.AutoAccepted -> outcome.expenseId
                    else -> null
                },
                reviewId = when (outcome) {
                    is NotificationPipelineOutcome.NeedsReview -> outcome.reviewId
                    else -> null
                },
                finalOutcome = outcome::class.simpleName,
                nowMs = now
            )

            // Mark the raw notification as processed after a terminal pipeline outcome
            if (rawId != null) {
                rawDao.markProcessed(rawId)
            }

            // Purge raw payload for non-raw storage modes
            if (current.rawStorageMode != "STORE_RAW") {
                intakeDao.purgeRawPayload(intakeId, now)
            }

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "IntakeWorker: processing failed for intakeId=$intakeId")
            intakeDao.markFinalFailure(
                id = intakeId,
                failureCode = "WORKER_EXCEPTION",
                failureHash = null,
                nowMs = now
            )
            Result.failure()
        }
    }
}
