package com.yourname.expensetracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.dao.NotificationIntakeProcessingMetadata
import com.yourname.expensetracker.data.database.dao.NotificationIntakePayloadForProcessing
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.notification.capture.NotificationTransientPayloadCrypto
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.toWorkerResult
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
    private val timeProvider: TimeProvider,
    private val crypto: NotificationTransientPayloadCrypto,
    private val executionGuard: WorkerExecutionGuard,
    private val privacyGate: PrivacyGate
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val intakeId = inputData.getLong("intakeId", -1L)
        if (intakeId <= 0L) {
            Timber.w("IntakeWorker: invalid intakeId=$intakeId")
            return Result.failure()
        }

        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = WORKER_NAME,
                requiresDatabaseWrite = true,
                requiredCapabilities = listOf(PrivacyCapability.NOTIFICATION_CAPTURE),
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount,
                // specVersion = null is intentional: notification_intake is a one-shot
                // worker scheduled via NotificationIntakeCoordinator, not via
                // WorkerSpecScheduler. It has no entry in WorkerSpec.DEFAULTS, so
                // DEFAULTS[WORKER_NAME]?.version naturally resolves to null. This is
                // correct — the guard's version-aware behaviour (force-update on bump)
                // does not apply to coordinator-driven one-shots.
                specVersion = WorkerSpec.DEFAULTS[WORKER_NAME]?.version
            )
        ) { ctx ->
            // All DB operations happen INSIDE the guard
            val now = timeProvider.now()

            // PR12H-2: Load metadata only — no raw payload or ciphertext
            ctx.checkpoint("intake:metadata")
            val meta = intakeDao.getProcessingMetadataById(intakeId)
            if (meta == null) {
                Timber.w("IntakeWorker: intake row $intakeId not found")
                throw RuntimeException("Intake row $intakeId not found")
            }
            ctx.addRowsScanned() // PR12I-3: metadata read found row

            // PR 2: Enforce maxAttempts using metadata only
            if (meta.attempts >= meta.maxAttempts) {
                ctx.checkpoint("intake:maxAttempts")
                intakeDao.markFinalFailure(
                    id = intakeId,
                    failureCode = "MAX_ATTEMPTS_EXCEEDED",
                    failureHash = null,
                    nowMs = now
                )
                ctx.addRowsUpdated() // PR12I-3: final failure mark
                purgePayloadBestEffort(intakeId, now, ctx)
                throw RuntimeException("MAX_ATTEMPTS_EXCEEDED")
            }

            // Claim the row
            ctx.checkpoint("intake:claim")
            val claimed = intakeDao.claimForProcessing(
                id = intakeId,
                nowMs = now,
                workerId = "intake-worker-${timeProvider.now()}"
            )
            if (claimed == 0) {
                return@runGuardedWithContext // Already claimed — idempotent success (true NO_WORK)
            }
            ctx.addRowsUpdated() // PR12I-3: claim succeeded

            // Reload metadata after claim (still no payload)
            ctx.checkpoint("intake:reloadMetadata")
            val claimedMeta = intakeDao.getProcessingMetadataById(intakeId)
                ?: return@runGuardedWithContext
            ctx.addRowsScanned() // PR12I-3: reload metadata found row

            // PR12H-2: Mid-run privacy recheck BEFORE loading any payload
            ctx.checkpoint("intake:privacyBeforePayloadLoad")
            if (!isNotificationCaptureAllowed()) {
                intakeDao.markPrivacyDeniedAndPurgeAllPayload(id = intakeId, nowMs = now)
                ctx.addRowsUpdated() // PR12I-3: privacy purge
                return@runGuardedWithContext
            }

            // PR12H-2: Only now load the payload — after privacy is confirmed
            ctx.checkpoint("intake:loadPayload")
            val payload = intakeDao.getPayloadForProcessing(intakeId)
            if (payload != null) ctx.addRowsScanned() // PR12I-3: payload found

            // PR 1 FIX: Load/decrypt processing payload BEFORE filter.
            // Previously filtered on null visible fields (broken for encrypted transient modes).
            val isRaw = claimedMeta.rawStorageMode == "STORE_RAW"
            val processingTitle: String?
            val processingText: String?
            val processingBody: String?
            val processingSubText: String?
            val processingExtrasJson: String?

            if (isRaw) {
                if (payload == null) {
                    Timber.w("IntakeWorker: no payload available for raw intakeId=$intakeId")
                    ctx.checkpoint("intake:payloadUnavailable")
                    intakeDao.markTerminal(
                        id = intakeId,
                        status = NotificationIntakeStatus.PAYLOAD_UNAVAILABLE_PRIVACY.name,
                        rawId = null, expenseId = null, reviewId = null,
                        finalOutcome = "PAYLOAD_UNAVAILABLE_PRIVACY",
                        nowMs = now
                    )
                    purgePayloadBestEffort(intakeId, now, ctx)
                    return@runGuardedWithContext
                }
                processingTitle = payload.title
                processingText = payload.text
                processingBody = payload.bigText
                processingSubText = payload.subText
                processingExtrasJson = payload.extrasJson
            } else if (payload != null
                && payload.transientPayloadCiphertext != null
                && payload.transientPayloadNonce != null
                && payload.transientPayloadVersion != null
            ) {
                ctx.checkpoint("intake:beforeDecrypt")
                val decrypted = crypto.decrypt(
                    payload.transientPayloadCiphertext,
                    payload.transientPayloadNonce,
                    payload.transientPayloadVersion
                )
                processingTitle = decrypted.title
                processingText = decrypted.text
                processingBody = decrypted.bigText
                processingSubText = decrypted.subText
                processingExtrasJson = decrypted.extrasJson
            } else {
                Timber.w("IntakeWorker: no payload available for intakeId=$intakeId")
                ctx.checkpoint("intake:payloadUnavailable")
                intakeDao.markTerminal(
                    id = intakeId,
                    status = NotificationIntakeStatus.PAYLOAD_UNAVAILABLE_PRIVACY.name,
                    rawId = null, expenseId = null, reviewId = null,
                    finalOutcome = "PAYLOAD_UNAVAILABLE_PRIVACY",
                    nowMs = now
                )
                ctx.addRowsUpdated() // PR12I-3: terminal mark
                purgePayloadBestEffort(intakeId, now, ctx)
                return@runGuardedWithContext
            }

            // Run filter on the processing payload (decrypted or raw)
            if (!NotificationFilter.shouldCapture(
                    claimedMeta.packageName,
                    processingTitle,
                    processingText,
                    processingBody
                )) {
                ctx.checkpoint("intake:filterRejected")
                intakeDao.markTerminal(
                    id = intakeId,
                    status = NotificationIntakeStatus.FILTER_REJECTED.name,
                    rawId = null, expenseId = null, reviewId = null,
                    finalOutcome = "FILTER_REJECTED",
                    nowMs = now
                )
                ctx.addRowsUpdated() // PR12I-3: terminal mark
                purgePayloadBestEffort(intakeId, now, ctx)
                return@runGuardedWithContext
            }

            val processingNotification = RawNotification(
                packageName = claimedMeta.packageName,
                appName = claimedMeta.appName,
                title = processingTitle,
                text = processingText,
                bigText = processingBody,
                subText = processingSubText,
                extrasJson = processingExtrasJson,
                timestamp = claimedMeta.postTime,
                capturedAt = claimedMeta.capturedAt,
                dedupeFingerprint = claimedMeta.dedupeFingerprint
            )

            val storageNotification = buildStorageNotification(
                processing = processingNotification,
                rawStorageMode = claimedMeta.rawStorageMode
            )

            // PR 4: Build captured persistence context from intake row
            val rawMode = try {
                com.yourname.expensetracker.domain.privacy.RawStorageMode.valueOf(claimedMeta.rawStorageMode)
            } catch (e: IllegalArgumentException) {
                com.yourname.expensetracker.domain.privacy.RawStorageMode.DO_NOT_STORE
            }
            val persistenceContext = com.yourname.expensetracker.domain.notification.NotificationPersistenceContext(
                rawStorageMode = rawMode,
                payloadMode = claimedMeta.payloadMode,
                source = claimedMeta.source
            )

            // Process through pipeline
            var terminalMarked = false
            var intentionalFailure = false
            try {
                ctx.checkpoint("intake:process")
                val outcome = repository.processAndSave(
                    processingNotification,
                    storageNotification,
                    correlationId = claimedMeta.correlationId,
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
                        if (isRetryable(outcome.throwable) && claimedMeta.attempts + 1 < claimedMeta.maxAttempts) {
                            val backoff = computeBackoff(claimedMeta.attempts + 1)
                            ctx.checkpoint("intake:errorRetryable")
                            intakeDao.markRetryableFailure(
                                id = intakeId,
                                nextAttemptAt = now + backoff,
                                failureCode = "RETRYABLE_ERROR",
                                failureHash = null,
                                nowMs = now
                            )
                            ctx.addRowsUpdated() // PR12I-3: retryable failure mark
                            throw RetryableWorkerException(DiagnosticReasonCode.WORKER_RETRYABLE_ERROR.name)
                        }
                        // Non-retryable or max attempts — mark terminal and signal failure
                        ctx.checkpoint("intake:errorFinal")
                        intakeDao.markTerminal(
                            id = intakeId,
                            status = NotificationIntakeStatus.FAILED_FINAL.name,
                            rawId = null,
                            expenseId = null,
                            reviewId = null,
                            finalOutcome = outcome::class.simpleName,
                            nowMs = now
                        )
                        ctx.addRowsUpdated() // PR12I-3: terminal mark
                        purgePayloadBestEffort(intakeId, now, ctx)
                        intentionalFailure = true
                        throw RuntimeException("FAILED_FINAL")
                    }
                }

                val rawId = when (outcome) {
                    is NotificationPipelineOutcome.AutoAccepted -> outcome.rawId
                    is NotificationPipelineOutcome.NeedsReview -> outcome.rawId
                    is NotificationPipelineOutcome.ParserFailed -> outcome.rawId
                    is NotificationPipelineOutcome.AutoRejected -> outcome.rawId
                    else -> null
                }

                ctx.checkpoint("intake:terminal")
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
                ctx.addRowsUpdated() // PR12I-3: terminal mark
                terminalMarked = true

                // P1-SLICE-D: markProcessed is now atomic inside the pipeline — no best-effort needed.
                purgePayloadBestEffort(intakeId, now, ctx)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Timber.w(e, "Worker timeout, marking retryable intakeId=$intakeId")
                if (claimedMeta.attempts + 1 < claimedMeta.maxAttempts) {
                    val backoff = computeBackoff(claimedMeta.attempts + 1)
                    ctx.checkpoint("intake:timeoutRetryable")
                    intakeDao.markRetryableFailure(
                        id = intakeId,
                        nextAttemptAt = now + backoff,
                        failureCode = "TIMEOUT",
                        failureHash = null,
                        nowMs = now
                    )
                    ctx.addRowsUpdated() // PR12I-3: retryable failure mark
                    throw RetryableWorkerException(DiagnosticReasonCode.WORKER_TIMEOUT.name)
                } else {
                    ctx.checkpoint("intake:timeoutFinal")
                    intakeDao.markFinalFailure(
                        id = intakeId,
                        failureCode = "TIMEOUT",
                        failureHash = null,
                        nowMs = now
                    )
                    ctx.addRowsUpdated() // PR12I-3: final failure mark
                    purgePayloadBestEffort(intakeId, now, ctx)
                    throw RuntimeException("MAX_RETRIES_EXHAUSTED")
                }
            } catch (e: RetryableWorkerException) {
                throw e
            } catch (e: CancellationException) {
                Timber.d("IntakeWorker: cancelled intakeId=$intakeId")
                throw e
            } catch (e: Exception) {
                if (intentionalFailure) {
                    throw e
                }
                if (terminalMarked) {
                    // Terminal status is already set — cleanup failure must not regress
                    Timber.w(e, "IntakeWorker: post-terminal cleanup failed for intakeId=$intakeId")
                    return@runGuardedWithContext
                }
                Timber.e(e, "IntakeWorker: processing failed for intakeId=$intakeId")
                if (isRetryable(e) && claimedMeta.attempts + 1 < claimedMeta.maxAttempts) {
                    val backoff = computeBackoff(claimedMeta.attempts + 1)
                    ctx.checkpoint("intake:exceptionRetryable")
                    intakeDao.markRetryableFailure(
                        id = intakeId,
                        nextAttemptAt = now + backoff,
                        failureCode = "WORKER_EXCEPTION",
                        failureHash = null,
                        nowMs = now
                    )
                    ctx.addRowsUpdated() // PR12I-3: retryable failure mark
                    throw RetryableWorkerException(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name)
                } else {
                    ctx.checkpoint("intake:exceptionFinal")
                    intakeDao.markFinalFailure(
                        id = intakeId,
                        failureCode = "WORKER_EXCEPTION",
                        failureHash = null,
                        nowMs = now
                    )
                    ctx.addRowsUpdated() // PR12I-3: final failure mark
                    purgePayloadBestEffort(intakeId, now, ctx)
                    throw RuntimeException("WORKER_EXCEPTION")
                }
            }
        }

        return if (guardResult is WorkerGuardResult.Skipped &&
            (guardResult.reason == DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name ||
             guardResult.reason == DiagnosticReasonCode.WORKER_PRIVACY_FAIL_CLOSED.name)
        ) {
            runPrivacyCleanupGuarded(intakeId).toWorkerResult()
        } else {
            guardResult.toWorkerResult()
        }
    }

    private suspend fun runPrivacyCleanupGuarded(intakeId: Long): WorkerGuardResult<Unit> {
        return executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "notification_intake_privacy_cleanup",
                requiresDatabaseWrite = true,
                requiredCapabilities = emptyList(),
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount,
                specVersion = null
            )
        ) { ctx ->
            ctx.checkpoint("intakePrivacyCleanup:beforeUpdate")
            val now = timeProvider.now()
            intakeDao.markPrivacyDeniedAndPurgeAllPayload(
                id = intakeId,
                nowMs = now
            )
            ctx.addRowsUpdated() // PR12I-3: privacy cleanup update
        }
    }

    private suspend fun purgePayloadBestEffort(id: Long, now: Long, ctx: WorkerRunContext? = null) {
        try {
            intakeDao.purgeAllPayload(id = id, nowMs = now)
            ctx?.addRowsUpdated() // PR12I-3: payload purge
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "IntakeWorker: purgePayload failed for intakeId=$id")
        }
    }

    private suspend fun isNotificationCaptureAllowed(): Boolean {
        return when (privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)) {
            is PrivacyDecision.Denied -> false
            is PrivacyDecision.FailClosed -> false
            else -> true
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

    companion object {
        const val WORKER_NAME = "notification_intake"
    }
}
