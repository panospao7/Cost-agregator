package com.yourname.expensetracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.notification.capture.NotificationTransientPayloadCrypto
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.service.NotificationFilter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException

@HiltWorker
class NotificationIntakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val intakeDao: NotificationIntakeDao,
    private val repository: NotificationRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    private val crypto: NotificationTransientPayloadCrypto
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

        val now = timeProvider.now()

        // PR 2: Do not mutate DB when writes are blocked — just retry.
        if (!writeBarrier.writesAllowed()) {
            Timber.d("IntakeWorker: writes blocked, retrying intakeId=$intakeId")
            return Result.retry()
        }

        // PR 2: Enforce maxAttempts
        if (intake.attempts >= intake.maxAttempts) {
            intakeDao.markFinalFailure(
                id = intakeId,
                failureCode = "MAX_ATTEMPTS_EXCEEDED",
                failureHash = null,
                nowMs = now
            )
            purgePayloadBestEffort(intake, now)
            return Result.failure()
        }

        // Claim the row
        val claimed = intakeDao.claimForProcessing(
            id = intakeId,
            nowMs = now,
            workerId = "intake-worker-${System.currentTimeMillis()}"
        )
        if (claimed == 0) {
            return Result.success() // Already claimed
        }

        // Reload after claim
        val current = intakeDao.getById(intakeId) ?: return Result.failure()

        // PR 1 FIX: Load/decrypt processing payload BEFORE filter.
        // Previously filtered on null visible fields (broken for encrypted transient modes).
        val isRaw = current.rawStorageMode == "STORE_RAW"
        val processingTitle: String?
        val processingText: String?
        val processingBody: String?
        val processingSubText: String?
        val processingExtrasJson: String?

        if (isRaw) {
            processingTitle = current.title
            processingText = current.text
            processingBody = current.bigText
            processingSubText = current.subText
            processingExtrasJson = current.extrasJson
        } else if (current.transientPayloadCiphertext != null
            && current.transientPayloadNonce != null
            && current.transientPayloadVersion != null
        ) {
            val payload = crypto.decrypt(
                current.transientPayloadCiphertext,
                current.transientPayloadNonce,
                current.transientPayloadVersion
            )
            processingTitle = payload.title
            processingText = payload.text
            processingBody = payload.bigText
            processingSubText = payload.subText
            processingExtrasJson = payload.extrasJson
        } else {
            Timber.w("IntakeWorker: no payload available for intakeId=$intakeId")
            intakeDao.markTerminal(
                id = intakeId,
                status = NotificationIntakeStatus.PAYLOAD_UNAVAILABLE_PRIVACY.name,
                rawId = null, expenseId = null, reviewId = null,
                finalOutcome = "PAYLOAD_UNAVAILABLE_PRIVACY",
                nowMs = now
            )
            purgePayloadBestEffort(current, now)
            return Result.success()
        }

        // Run filter on the processing payload (decrypted or raw)
        if (!NotificationFilter.shouldCapture(
                current.packageName,
                processingTitle,
                processingText,
                processingBody
            )) {
            intakeDao.markTerminal(
                id = intakeId,
                status = NotificationIntakeStatus.FILTER_REJECTED.name,
                rawId = null, expenseId = null, reviewId = null,
                finalOutcome = "FILTER_REJECTED",
                nowMs = now
            )
            purgePayloadBestEffort(current, now)
            return Result.success()
        }

        val processingNotification = RawNotification(
            packageName = current.packageName,
            appName = current.appName,
            title = processingTitle,
            text = processingText,
            bigText = processingBody,
            subText = processingSubText,
            extrasJson = processingExtrasJson,
            timestamp = current.postTime,
            capturedAt = current.capturedAt,
            dedupeFingerprint = current.dedupeFingerprint
        )

        val storageNotification = buildStorageNotification(
            processing = processingNotification,
            rawStorageMode = current.rawStorageMode
        )

        // PR 4: Build captured persistence context from intake row
        val rawMode = try {
            com.yourname.expensetracker.domain.privacy.RawStorageMode.valueOf(current.rawStorageMode)
        } catch (e: IllegalArgumentException) {
            com.yourname.expensetracker.domain.privacy.RawStorageMode.DO_NOT_STORE
        }
        val persistenceContext = com.yourname.expensetracker.domain.notification.NotificationPersistenceContext(
            rawStorageMode = rawMode,
            payloadMode = current.payloadMode,
            source = current.source
        )

        // Process through pipeline
        var terminalMarked = false
        return try {
            val outcome = repository.processAndSave(
                processingNotification,
                storageNotification,
                correlationId = current.correlationId,
                persistenceContext = persistenceContext
            )

            val terminalStatus = when (outcome) {
                is NotificationPipelineOutcome.AutoAccepted -> NotificationIntakeStatus.PROCESSED
                is NotificationPipelineOutcome.NeedsReview -> NotificationIntakeStatus.PROCESSED
                is NotificationPipelineOutcome.Duplicate -> NotificationIntakeStatus.DROPPED_DUPLICATE
                is NotificationPipelineOutcome.ParserFailed -> NotificationIntakeStatus.FAILED_FINAL
                is NotificationPipelineOutcome.AutoRejected -> NotificationIntakeStatus.DROPPED_POLICY
                is NotificationPipelineOutcome.Dropped -> NotificationIntakeStatus.DROPPED_POLICY
                is NotificationPipelineOutcome.Error -> {
                    if (isRetryable(outcome.throwable) && current.attempts + 1 < current.maxAttempts) {
                        val backoff = computeBackoff(current.attempts + 1)
                        intakeDao.markRetryableFailure(
                            id = intakeId,
                            nextAttemptAt = now + backoff,
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
            terminalMarked = true

            // P1-SLICE-D: markProcessed is now atomic inside the pipeline — no best-effort needed.
            purgePayloadBestEffort(current, now)

            Result.success()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.w(e, "Worker timeout, marking retryable intakeId=$intakeId")
            if (current.attempts + 1 < current.maxAttempts) {
                val backoff = computeBackoff(current.attempts + 1)
                intakeDao.markRetryableFailure(
                    id = intakeId,
                    nextAttemptAt = now + backoff,
                    failureCode = "TIMEOUT",
                    failureHash = null,
                    nowMs = now
                )
                return Result.retry()
            } else {
                intakeDao.markFinalFailure(
                    id = intakeId,
                    failureCode = "TIMEOUT",
                    failureHash = null,
                    nowMs = now
                )
                purgePayloadBestEffort(current, now)
                return Result.failure()
            }
        } catch (e: CancellationException) {
            Timber.d("IntakeWorker: cancelled intakeId=$intakeId")
            throw e
        } catch (e: Exception) {
            if (terminalMarked) {
                // Terminal status is already set — cleanup failure must not regress
                Timber.w(e, "IntakeWorker: post-terminal cleanup failed for intakeId=$intakeId")
                return Result.success()
            }
            Timber.e(e, "IntakeWorker: processing failed for intakeId=$intakeId")
            if (isRetryable(e) && current.attempts + 1 < current.maxAttempts) {
                val backoff = computeBackoff(current.attempts + 1)
                intakeDao.markRetryableFailure(
                    id = intakeId,
                    nextAttemptAt = now + backoff,
                    failureCode = "WORKER_EXCEPTION",
                    failureHash = null,
                    nowMs = now
                )
                return Result.retry()
            } else {
                intakeDao.markFinalFailure(
                    id = intakeId,
                    failureCode = "WORKER_EXCEPTION",
                    failureHash = null,
                    nowMs = now
                )
                purgePayloadBestEffort(current, now)
                return Result.failure()
            }
        }
    }

    private suspend fun purgePayloadBestEffort(row: NotificationIntakeEntity, now: Long) {
        if (row.payloadMode != "TRANSIENT") return
        try {
            intakeDao.purgeRawPayload(row.id, now)
            intakeDao.purgeTransientPayload(row.id, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "IntakeWorker: purgePayload failed for intakeId=${row.id}")
        }
    }

    private fun isRetryable(e: Throwable): Boolean =
        e is IOException ||
        e.message?.contains("database is locked", ignoreCase = true) == true

    private fun computeBackoff(attempt: Int): Long = when (attempt) {
        1 -> 30_000
        2 -> 120_000
        3 -> 600_000
        4 -> 1_800_000
        else -> 3_600_000
    }

    /**
     * Build a sanitized storage notification based on [rawStorageMode].
     * The processing notification always has raw text for parsing; this
     * method produces the sanitized version for persistent storage.
     */
    private fun buildStorageNotification(
        processing: RawNotification,
        rawStorageMode: String
    ): RawNotification = when (rawStorageMode) {
        "STORE_RAW" -> processing

        "STORE_REDACTED" -> processing.copy(
            title = "[REDACTED]",
            text = "[REDACTED]",
            bigText = "[REDACTED]",
            subText = "[REDACTED]",
            extrasJson = """{"redacted":true}"""
        )

        "STORE_METADATA_ONLY",
        "DO_NOT_STORE" -> processing.copy(
            title = null,
            text = null,
            bigText = null,
            subText = null,
            extrasJson = null
        )

        else -> {
            Timber.w("IntakeWorker: unknown rawStorageMode=$rawStorageMode, failing closed")
            processing.copy(
                title = null,
                text = null,
                bigText = null,
                subText = null,
                extrasJson = null
            )
        }
    }
}
