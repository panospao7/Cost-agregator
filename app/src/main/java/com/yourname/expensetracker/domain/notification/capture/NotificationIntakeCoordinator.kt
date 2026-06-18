package com.yourname.expensetracker.domain.notification.capture

import androidx.work.*
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
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.worker.NotificationIntakeWorker
import timber.log.Timber
import com.yourname.expensetracker.domain.common.sha256
import com.yourname.expensetracker.domain.common.sha256Fingerprint
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationIntakeCoordinator @Inject constructor(
    private val intakeDao: NotificationIntakeDao,
    private val workManager: WorkManager,
    private val diagnostics: NotificationDiagnosticEmitter,
    private val timeProvider: TimeProvider,
    private val crypto: NotificationTransientPayloadCrypto
) {
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
            return NotificationIntakeCaptureResult.Duplicate(correlationId)
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

        val intakeId = intakeDao.insertOrIgnore(entity)
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

        workManager.enqueueUniqueWork(
            "notification-intake-$intakeId",
            ExistingWorkPolicy.KEEP,
            request
        )

        Timber.d("Intake enqueued: intakeId=$intakeId package=$packageName source=$source")
        return NotificationIntakeCaptureResult.Enqueued(intakeId, correlationId)
    }

    /**
     * Persists a notification for deferred processing when the capture gate is not yet ready.
     * Extracted text is encrypted as a transient payload for worker-side decryption and processing.
     * The worker uses the same transient-payload decryption path as regular captures.
     */
    suspend fun captureForRetry(
        packageName: String,
        notificationKey: String,
        postTime: Long,
        correlationId: String,
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        subText: String? = null
    ) {
        val now = timeProvider.now()
        val notificationKeyHash = notificationKey.sha256().take(32)

        val hasContent = title != null || text != null || bigText != null || subText != null
        var ciphertext: String? = null
        var nonce: String? = null
        var version: Int? = null
        val payloadMode: String
        if (hasContent) {
            val transientPayload = NotificationTransientPayload(
                title = title, text = text, bigText = bigText,
                subText = subText, extrasJson = null
            )
            val encrypted = crypto.encrypt(transientPayload)
            ciphertext = encrypted.ciphertext
            nonce = encrypted.nonce
            version = encrypted.version
            payloadMode = "TRANSIENT"
        } else {
            payloadMode = "DEFERRED"
        }

        val entity = NotificationIntakeEntity(
            packageName = packageName,
            appName = null,
            notificationKeyHash = notificationKeyHash,
            postTime = postTime,
            capturedAt = now,
            source = "deferred",
            correlationId = correlationId,
            dedupeFingerprint = "DEFERRED_${notificationKeyHash}",
            contentHash = null,
            title = null,
            text = null,
            bigText = null,
            subText = null,
            extrasJson = null,
            transientPayloadCiphertext = ciphertext,
            transientPayloadNonce = nonce,
            transientPayloadVersion = version,
            rawStorageMode = "STORE_METADATA_ONLY",
            payloadMode = payloadMode,
            status = NotificationIntakeStatus.RECEIVED.name,
            createdAt = now,
            updatedAt = now
        )

        val intakeId = intakeDao.insertOrIgnore(entity)
        if (intakeId == -1L) {
            Timber.d("captureForRetry: insert conflict for $packageName")
            return
        }

        // Enqueue WorkManager job with short delay to let gate warm up
        val request = OneTimeWorkRequestBuilder<NotificationIntakeWorker>()
            .setInputData(workDataOf("intakeId" to intakeId))
            .addTag("notification-intake")
            .addTag("notification-intake-$intakeId")
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

        workManager.enqueueUniqueWork(
            "intake_${notificationKeyHash}",
            ExistingWorkPolicy.REPLACE,
            request
        )

        Timber.d("captureForRetry: deferred intakeId=$intakeId package=$packageName")
    }
}
