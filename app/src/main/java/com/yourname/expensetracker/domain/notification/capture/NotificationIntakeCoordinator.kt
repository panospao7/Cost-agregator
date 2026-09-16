package com.yourname.expensetracker.domain.notification.capture

import androidx.work.*
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.notification.RawNotificationFingerprint
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.worker.NotificationIntakeWorker
import timber.log.Timber
import com.yourname.expensetracker.domain.common.sha256
import com.yourname.expensetracker.domain.common.sha256Fingerprint
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RP-10 10a (P1-002): bounded privacy-policy snapshot resolved by the caller
 * with a single settings read through the same policy resolver the capture gate
 * uses. The deferred path never re-reads settings and never substitutes an
 * unstored mode:
 *  - [storageMode] is the resolved [RawStorageMode] at capture time;
 *  - [appName] is the resolved (non-sensitive) app label;
 *  - [extrasJson] is the storage-safe extras value built with the same STORE_RAW
 *    sanitization as the live path — always null for every other mode.
 */
data class DeferredCaptureStorageSnapshot(
    val storageMode: RawStorageMode,
    val appName: String?,
    val extrasJson: String?
)

/** Outcome of the atomic legacy-transition + canonical insert (P1-001). */
private data class DeferredInsertOutcome(
    val intakeId: Long,
    val conflictingIntakeId: Long?
)

@Singleton
class NotificationIntakeCoordinator @Inject constructor(
    private val intakeDao: NotificationIntakeDao,
    private val workManager: WorkManager,
    private val diagnostics: NotificationDiagnosticEmitter,
    private val timeProvider: TimeProvider,
    private val crypto: NotificationTransientPayloadCrypto,
    private val writeBarrier: DatabaseWriteBarrier,
    private val transactionRunner: DomainTransactionRunner
) {

    companion object {
        /**
         * Controlled row failure code stored on legacy `DEFERRED_<keyHash>` rows
         * that were superseded by a canonical content-fingerprinted row (P1-001).
         */
        internal const val LEGACY_DEFERRED_SUPERSEDED = "LEGACY_DEFERRED_SUPERSEDED"

        /**
         * Controlled reason code for a deferred transient-encryption failure
         * (P1-002); carried on [NotificationIntakeCaptureResult.StorageFailure].
         */
        internal const val DEFERRED_TRANSIENT_ENCRYPT_FAILED = "DEFERRED_TRANSIENT_ENCRYPT_FAILED"
    }

    suspend fun capture(
        packageName: String,
        appName: String?,
        notificationKey: String,
        notificationKeyHash: String,
        postTime: Long,
        title: String?,
        text: String?,
        combinedBody: String?,
        subText: String?,
        extrasJson: String?,
        rawStorageMode: RawStorageMode,
        correlationId: String,
        source: String // "listener" or "refresh"
    ): NotificationIntakeCaptureResult {
        try {
            writeBarrier.checkWritesAllowed("NotificationIntakeCoordinator.capture")
        } catch (blocked: DatabaseAccessBlockedException) {
            Timber.w("Intake capture skipped: database writes blocked during restore")
            return NotificationIntakeCaptureResult.Dropped(
                correlationId,
                "Database writes blocked during restore"
            )
        }

        val dedupeFingerprint = RawNotificationFingerprint.compute(
            packageName = packageName,
            title = title,
            text = text,
            bigText = combinedBody,
            timestamp = postTime
        )

        // Check for existing intake with same fingerprint
        if (intakeDao.existsByFingerprint(dedupeFingerprint)) {
            Timber.d("Intake duplicate fingerprint: $packageName")
            diagnostics.emit(DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION,
                stage = "intake",
                outcome = EventOutcome.DUPLICATE,
                reasonCode = DiagnosticReasonCode.DUPLICATE,
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .putHashed("packageName", packageName)
                    .build(),
                isTerminal = true
            ))
            return NotificationIntakeCaptureResult.Duplicate(
                existingIntakeId = null,
                correlationId = correlationId
            )
        }

        // P2-11: DO_NOT_STORE cannot use durable intake — plaintext payload would violate
        // the user's raw-storage promise. These notifications are processed synchronously
        // by the service caller with sanitized storage only.
        if (rawStorageMode == RawStorageMode.DO_NOT_STORE) {
            Timber.d("Intake: DO_NOT_STORE — skipping durable intake for $packageName")
            return NotificationIntakeCaptureResult.RequiresSynchronousProcessing
        }

        // STORE_RAW → raw payload retained | STORE_REDACTED/METADATA_ONLY → transient payload purged after processing
        val isRaw = rawStorageMode == RawStorageMode.STORE_RAW
        val payloadMode = if (isRaw) "RAW" else "TRANSIENT"

        var ciphertext: String? = null
        var nonce: String? = null
        var version: Int? = null
        if (!isRaw) {
            val transientPayload = NotificationTransientPayload(
                title = title, text = text, bigText = combinedBody,
                subText = subText, extrasJson = extrasJson
            )
            val encrypted = crypto.encrypt(transientPayload)
            ciphertext = encrypted.ciphertext
            nonce = encrypted.nonce
            version = encrypted.version
        }

        val now = timeProvider.now()
        val entity = NotificationIntakeEntity(
            packageName = packageName,
            appName = appName,
            notificationKeyHash = notificationKeyHash,
            postTime = postTime,
            capturedAt = now,
            source = source,
            correlationId = correlationId,
            dedupeFingerprint = dedupeFingerprint,
            contentHash = combinedBody?.sha256Fingerprint(),
            // Raw mode: store visible fields. Transient mode: store encrypted payload, null visible fields.
            title = if (isRaw) title else null,
            text = if (isRaw) text else null,
            bigText = if (isRaw) combinedBody else null,
            subText = if (isRaw) subText else null,
            extrasJson = if (isRaw) extrasJson else null,
            transientPayloadCiphertext = ciphertext,
            transientPayloadNonce = nonce,
            transientPayloadVersion = version,
            rawStorageMode = rawStorageMode.name,
            payloadMode = payloadMode,
            status = NotificationIntakeStatus.RECEIVED.name,
            createdAt = now,
            updatedAt = now
        )

        val intakeId = try {
            writeBarrier.runWrite(
                DatabaseAccessOperation("NotificationIntakeCoordinator.capture")
            ) {
                intakeDao.insertOrIgnore(entity)
            }
        } catch (blocked: DatabaseAccessBlockedException) {
            Timber.w("Intake capture skipped: database writes blocked during restore")
            return NotificationIntakeCaptureResult.Dropped(
                correlationId,
                "Database writes blocked during restore"
            )
        }
        if (intakeId == -1L) {
            Timber.d("Intake insert conflict: $packageName")
            return NotificationIntakeCaptureResult.Dropped(correlationId, "Insert conflict")
        }

        // Enqueue WorkManager job
        val request = OneTimeWorkRequestBuilder<NotificationIntakeWorker>()
            .setInputData(workDataOf("intakeId" to intakeId))
            .addTag("notification-intake")
            .addTag("notification-intake-$intakeId")
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

// RP-10 10b (P1-003): await the enqueue inside the NonCancellable region; on
        // failure run ONE atomic, idempotent transition — attempts increment once,
        // FAILED_RETRYABLE with the shared backoff ladder or FAILED_FINAL at
        // maxAttempts, controlled code only, locks cleared, conditional on the
        // enqueue-attempt state.
        val operation = workManager.enqueueUniqueWork(
            "$intakeId",
            ExistingWorkPolicy.KEEP,
            request
        )
        val enqueued = try {
            operation.await()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!enqueued) {
            val transitioned = writeBarrier.runWrite(
                DatabaseAccessOperation("NotificationIntakeCoordinator.enqueueFailed")
            ) {
                val attempts = intakeDao.getAttemptsById(intakeId) ?: 0
                intakeDao.markEnqueueFailed(
                    id = intakeId,
                    nextAttemptAt = now + NotificationIntakeRetryPolicy.backoffFor(attempts + 1),
                    failureCode = "ENQUEUE_FAILED",
                    failureHash = null,
                    nowMs = now
                )
            }
            diagnostics.emit(DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION,
                stage = "intake",
                outcome = if (transitioned > 0) EventOutcome.FAILED_RETRYABLE else EventOutcome.FAILED_FINAL,
                reasonCode = DiagnosticReasonCode.ENQUEUE_FAILED,
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .putHashed("packageName", packageName)
                    .build(),
                isTerminal = transitioned <= 0
            ))
            return NotificationIntakeCaptureResult.EnqueueFailed(intakeId, correlationId)
        }

        Timber.d("Intake enqueued: intakeId=$intakeId package=$packageName source=$source")
        return NotificationIntakeCaptureResult.Enqueued(intakeId, correlationId)
    }

    /**
     * Persists a notification for deferred processing when the capture gate is not yet ready.
     *
     * RP-10 10a (P1-001): the deferred row uses the SAME canonical content
     * fingerprint as the live path ([RawNotificationFingerprint.compute] over
     * `packageName | postTime | title | text | combinedBody`) so live and deferred
     * rows share one dedupe namespace. [notificationKey] is used only to locate a
     * legacy `DEFERRED_<keyHash>` row and to build the unique work name — never as
 * dedupe identity. The legacy lookup, legacy terminalization and the canonical
 * insert run in ONE database transaction (via DomainTransactionRunner) inside
 * the barrier write so two concurrent callers cannot both replace the same
 * legacy row.
     *
     * RP-10 10a (P1-002): [storage] carries the caller-resolved privacy snapshot;
     * the row's rawStorageMode/payload contract mirrors [capture]. DO_NOT_STORE
     * fails closed before any encryption or insert (no row, no payload).
     *
     * Non-raw content is encrypted as a transient payload for worker-side
     * decryption; the worker uses the same transient-payload decryption path as
     * regular captures.
     */
    suspend fun captureForRetry(
        packageName: String,
        notificationKey: String,
        postTime: Long,
        correlationId: String,
        title: String? = null,
        text: String? = null,
        combinedBody: String? = null,
        subText: String? = null,
        storage: DeferredCaptureStorageSnapshot
    ): NotificationIntakeCaptureResult {
        try {
            writeBarrier.checkWritesAllowed("NotificationIntakeCoordinator.captureForRetry")
        } catch (blocked: DatabaseAccessBlockedException) {
            Timber.w("captureForRetry skipped: database writes blocked during restore")
            return NotificationIntakeCaptureResult.Dropped(
                correlationId,
                "Database writes blocked during restore"
            )
        }

        // P1-002: DO_NOT_STORE cannot use durable intake — a persisted payload would
        // violate the user's raw-storage promise. Fail closed before any encryption
        // or insert (mirrors capture()'s DO_NOT_STORE early-return).
        if (storage.storageMode == RawStorageMode.DO_NOT_STORE) {
            Timber.d("captureForRetry: DO_NOT_STORE — no durable intake for $packageName")
            diagnostics.emit(DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION,
                stage = "intake_deferred",
                outcome = EventOutcome.SKIPPED,
                reasonCode = DiagnosticReasonCode.DEFERRED_NOT_STORED,
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .putHashed("packageName", packageName)
                    .build(),
                isTerminal = true
            ))
            return NotificationIntakeCaptureResult.NotStored(correlationId)
        }

        val now = timeProvider.now()
        val notificationKeyHash = notificationKey.sha256().take(32)
        val legacyFingerprint = "DEFERRED_$notificationKeyHash"
        val dedupeFingerprint = RawNotificationFingerprint.compute(
            packageName = packageName,
            title = title,
            text = text,
            bigText = combinedBody,
            timestamp = postTime
        )

        val hasContent = title != null || text != null || combinedBody != null || subText != null
        // P1-002: STORE_RAW persists visible fields with the same payloadMode
        // contract as capture(); every other resolved mode encrypts a transient
        // payload. "DEFERRED" = no content was extracted at all.
        val isRaw = storage.storageMode == RawStorageMode.STORE_RAW
        val payloadMode = when (storage.storageMode) {
            RawStorageMode.STORE_RAW -> "RAW"
            else -> if (hasContent) "TRANSIENT" else "DEFERRED"
        }

        var ciphertext: String? = null
        var nonce: String? = null
        var version: Int? = null
        if (!isRaw && hasContent) {
            val transientPayload = NotificationTransientPayload(
                title = title, text = text, bigText = combinedBody,
                subText = subText, extrasJson = storage.extrasJson
            )
            val encrypted = try {
                crypto.encrypt(transientPayload)
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.w("captureForRetry: transient encryption failed")
                diagnostics.emit(DiagnosticEvent(
                    pipeline = AppPipeline.NOTIFICATION,
                    stage = "intake_deferred",
                    outcome = EventOutcome.FAILED_FINAL,
                    reasonCode = DiagnosticReasonCode.DEFERRED_TRANSIENT_ENCRYPT_FAILED,
                    correlationId = correlationId,
                    metadata = SafeEventMetadata.builder()
                        .putHashed("packageName", packageName)
                        .build(),
                    isTerminal = true
                ))
                return NotificationIntakeCaptureResult.StorageFailure(
                    correlationId,
                    DEFERRED_TRANSIENT_ENCRYPT_FAILED
                )
            }
            ciphertext = encrypted.ciphertext
            nonce = encrypted.nonce
            version = encrypted.version
        }

        val entity = NotificationIntakeEntity(
            packageName = packageName,
            appName = storage.appName,
            notificationKeyHash = notificationKeyHash,
            postTime = postTime,
            capturedAt = now,
            source = "deferred",
            correlationId = correlationId,
            dedupeFingerprint = dedupeFingerprint,
            contentHash = combinedBody?.sha256Fingerprint(),
            // Raw mode: store visible fields. Other modes: encrypted transient payload, null visible fields.
            title = if (isRaw) title else null,
            text = if (isRaw) text else null,
            bigText = if (isRaw) combinedBody else null,
            subText = if (isRaw) subText else null,
            extrasJson = if (isRaw) storage.extrasJson else null,
            transientPayloadCiphertext = ciphertext,
            transientPayloadNonce = nonce,
            transientPayloadVersion = version,
            rawStorageMode = storage.storageMode.name,
            payloadMode = payloadMode,
            status = NotificationIntakeStatus.RECEIVED.name,
            createdAt = now,
            updatedAt = now
        )

        val outcome = try {
            writeBarrier.runWrite(
                DatabaseAccessOperation("NotificationIntakeCoordinator.captureForRetry")
            ) {
                transactionRunner.runInTransaction(
                    correlationId = correlationId,
                    operationId = "notification.captureForRetry.legacy_transition",
                    source = "NotificationIntakeCoordinator.captureForRetry"
                ) { _ ->
                    // P1-001: atomic legacy transition — a legacy DEFERRED_<keyHash>
                    // row that is still actionable must not stay processable once
                    // the canonical row takes over this notification key. Rows with
                    // an active claim (PROCESSING) or a terminal status are left
                    // untouched; the legacy namespace no longer collides with the
                    // canonical fingerprint.
                    val legacyRow = intakeDao.getByFingerprint(legacyFingerprint)
                    if (legacyRow != null && (
                        legacyRow.status == NotificationIntakeStatus.RECEIVED.name ||
                            legacyRow.status == NotificationIntakeStatus.FAILED_RETRYABLE.name
                        )
                    ) {
                        intakeDao.markFinalFailure(
                            id = legacyRow.id,
                            failureCode = LEGACY_DEFERRED_SUPERSEDED,
                            failureHash = null,
                            nowMs = now
                        )
                    }
                    val insertedId = intakeDao.insertOrIgnore(entity)
                    if (insertedId == -1L) {
                        // Canonical conflict: resolve the existing row id inside the
                        // transaction so the caller gets a meaningful Duplicate
                        // instead of a silent drop.
                        DeferredInsertOutcome(
                            intakeId = -1L,
                            conflictingIntakeId = intakeDao.getByFingerprint(dedupeFingerprint)?.id
                        )
                    } else {
                        DeferredInsertOutcome(intakeId = insertedId, conflictingIntakeId = null)
                    }
                }
            }
        } catch (blocked: DatabaseAccessBlockedException) {
            Timber.w("captureForRetry skipped: database writes blocked during restore")
            return NotificationIntakeCaptureResult.Dropped(
                correlationId,
                "Database writes blocked during restore"
            )
        }
        if (outcome.intakeId == -1L) {
            Timber.d("captureForRetry: canonical insert conflict for $packageName")
            diagnostics.emit(DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION,
                stage = "intake_deferred",
                outcome = EventOutcome.DUPLICATE,
                reasonCode = DiagnosticReasonCode.DUPLICATE,
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .putHashed("packageName", packageName)
                    .build(),
                isTerminal = true
            ))
            return NotificationIntakeCaptureResult.Duplicate(
                existingIntakeId = outcome.conflictingIntakeId,
                correlationId = correlationId
            )
        }

        // Enqueue WorkManager job with short delay to let gate warm up
        val request = OneTimeWorkRequestBuilder<NotificationIntakeWorker>()
            .setInputData(workDataOf("intakeId" to outcome.intakeId))
            .addTag("notification-intake")
            .addTag("notification-intake-${outcome.intakeId}")
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

// RP-10 10b (P1-003): await the enqueue inside the NonCancellable region; on
        // failure run ONE atomic, idempotent transition — attempts increment once,
        // FAILED_RETRYABLE with the shared backoff ladder or FAILED_FINAL at
        // maxAttempts, controlled code only, locks cleared, conditional on the
        // enqueue-attempt state.
        val operation = workManager.enqueueUniqueWork(
            "intake_${notificationKeyHash}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        val enqueued = try {
            operation.await()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!enqueued) {
            val transitioned = writeBarrier.runWrite(
                DatabaseAccessOperation("NotificationIntakeCoordinator.enqueueDeferredFailed")
            ) {
                val attempts = intakeDao.getAttemptsById(outcome.intakeId) ?: 0
                intakeDao.markEnqueueFailed(
                    id = outcome.intakeId,
                    nextAttemptAt = now + NotificationIntakeRetryPolicy.backoffFor(attempts + 1),
                    failureCode = "ENQUEUE_FAILED",
                    failureHash = null,
                    nowMs = now
                )
            }
            diagnostics.emit(DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION,
                stage = "intake_deferred",
                outcome = if (transitioned > 0) EventOutcome.FAILED_RETRYABLE else EventOutcome.FAILED_FINAL,
                reasonCode = DiagnosticReasonCode.ENQUEUE_FAILED,
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .putHashed("packageName", packageName)
                    .build(),
                isTerminal = transitioned <= 0
            ))
            return NotificationIntakeCaptureResult.EnqueueFailed(outcome.intakeId, correlationId)
        }

        Timber.d("captureForRetry: deferred intakeId=${outcome.intakeId} package=$packageName")
        return NotificationIntakeCaptureResult.Enqueued(outcome.intakeId, correlationId)
    }
}
