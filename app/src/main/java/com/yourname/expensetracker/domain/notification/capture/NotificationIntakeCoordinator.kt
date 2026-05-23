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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationIntakeCoordinator @Inject constructor(
    private val intakeDao: NotificationIntakeDao,
    private val workManager: WorkManager,
    private val diagnostics: NotificationDiagnosticEmitter,
    private val timeProvider: TimeProvider
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
    ): Long? { // returns intakeId or null if duplicate
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
            return null
        }

        // PR 1 fix: Always store processing payload so the worker can parse.
        // payloadMode indicates whether payload is raw (STORE_RAW) or transient
        // (must be purged after terminal outcome for non-raw modes).
        val payloadMode = when (rawStorageMode) {
            RawStorageMode.STORE_RAW -> "RAW"
            else -> "TRANSIENT"
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
            contentHash = combinedBody?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { "%02x".format(it) } },
            // Always store payload for worker processing.
            // Non-raw modes: worker purges after terminal outcome via purgeRawPayload().
            title = title,
            text = text,
            bigText = combinedBody,
            subText = subText,
            extrasJson = extrasJson,
            rawStorageMode = rawStorageMode.name,
            payloadMode = payloadMode,
            status = NotificationIntakeStatus.RECEIVED.name,
            createdAt = now,
            updatedAt = now
        )

        val intakeId = intakeDao.insertOrIgnore(entity)
        if (intakeId == -1L) {
            Timber.d("Intake insert conflict: $packageName")
            return null
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
        return intakeId
    }
}
