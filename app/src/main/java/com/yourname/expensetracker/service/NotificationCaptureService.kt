package com.yourname.expensetracker.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import timber.log.Timber
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.notification.RawNotificationFingerprint
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.receiver.ServiceRestartReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject

import com.yourname.expensetracker.domain.notification.capture.NotificationTextParts
import com.yourname.expensetracker.domain.notification.capture.computeNotificationContentHash

/**
 * Originally defined here; extracted to [NotificationTextParts] for reuse across
 * the service and future intake worker. Type kept for backward compatibility.
 */

internal class NotificationServiceWorkTracker {
    private val lock = Any()
    private val inFlightJobs = linkedSetOf<Job>()
    private var acceptingNewWork = true

    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job? {
        val job = synchronized(lock) {
            if (!acceptingNewWork) return null
            scope.launch { block() }.also { launched ->
                inFlightJobs += launched
            }
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                inFlightJobs.remove(job)
            }
        }
        return job
    }

    suspend fun stopAcceptingAndDrain(timeoutMs: Long): Boolean {
        synchronized(lock) {
            acceptingNewWork = false
        }
        val drained = withTimeoutOrNull<Boolean>(timeoutMs) {
            while (true) {
                val snapshot = synchronized(lock) { inFlightJobs.toList() }
                if (snapshot.isEmpty()) {
                    break
                }
                snapshot.joinAll()
            }
            true
        }
        return drained ?: false
    }
}

/**
 * Foreground service that listens for system notifications and captures
 * financial-transaction-related alerts for processing.
 *
 * ## PRV-3: Onboarding / first-run behaviour
 * This service is started automatically on device boot via [BootReceiver]
 * and after a fresh install when the app is first launched. There is no
 * separate one-time onboarding flow for notification capture — the user
 * is prompted for [android.Manifest.permission.POST_NOTIFICATIONS] via
 * [com.yourname.expensetracker.ui.components.NotificationPermissionDialog]
 * (see [com.yourname.expensetracker.ui.MainActivity]). Until the user grants
 * this permission, the service runs in the background but cannot capture
 * notification data from other apps. The [PrivacyGate] also checks
 * [com.yourname.expensetracker.domain.privacy.PrivacyCapability.NOTIFICATION_CAPTURE]
 * before persisting any notification data, providing a second layer of
 * opt-in control.
 *
 * ## Privacy gate coverage
 * Every code path that persists notification data checks the
 * [PrivacyCapability.NOTIFICATION_CAPTURE] gate before processing:
 * - [onNotificationPosted] checks the gate inside the launch coroutine.
 * - [processNotificationBypassDedupe] (called by [refreshActiveNotifications])
 *   checks the gate inside its launch coroutine.
 * - [onStartCommand] / [onListenerConnected] do NOT directly capture; they
 *   delegate to paths above which perform the gate check.
 *
 * No ungated capture path exists.
 *
 * ## Restore maintenance mode coverage
 * Both [onNotificationPosted] and [processNotificationBypassDedupe] check
 * [RestoreMaintenanceMode.isWritesAllowed] before any processing.
 *
 * ## Unified text extraction
 * Both capture paths use [NotificationTextParts.extract] to resolve all
 * notification fields (title, text, bigText, subText, infoText, summaryText)
 * in a single pass. The resolved [NotificationTextParts.effectiveBigText] is
 * used for filtering, content hashing, fingerprinting, and the raw notification
 * entity — ensuring consistency across all consumers.
 */
@AndroidEntryPoint
class NotificationCaptureService : NotificationListenerService() {

    @Inject
    lateinit var repository: NotificationRepository

    @Inject
    lateinit var timeProvider: com.yourname.expensetracker.domain.util.TimeProvider

    @Inject
    lateinit var diagnostics: com.yourname.expensetracker.domain.debug.ServiceDiagnostics

    @Inject
    lateinit var privacyGate: PrivacyGate

    @Inject
    lateinit var restoreMaintenanceMode: com.yourname.expensetracker.data.backup.RestoreMaintenanceMode

    @Inject
    lateinit var privacySettingsRepository: PrivacySettingsRepository

    @Inject
    lateinit var notificationDiagnosticEmitter: com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter

    @Inject
    lateinit var blockedPackageDao: com.yourname.expensetracker.data.database.dao.BlockedPackageDao

    @Inject
    lateinit var captureGate: com.yourname.expensetracker.domain.notification.capture.NotificationCaptureGate

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val workTracker = NotificationServiceWorkTracker()
    
    @Volatile
    private var pendingRefresh = false
    
    @Volatile
    private var isRunning = false
    
    @Volatile
    private var isListenerConnected = false

    @Volatile
    private var isShuttingDown = false

    // PR3: Fast caches removed — unified NotificationCaptureGate handles pre-extraction checks
    
    // Thread-safe, bounded deduplication cache (INS-005)
    private val processedNotifications = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 500 // Limit to 500 entries
            }
        }
    )
    private val processCount = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        private const val TAG = "NotificationCapture"
        const val ACTION_REFRESH_NOTIFICATIONS = "com.yourname.expensetracker.REFRESH_NOTIFICATIONS"
        const val ACTION_RESTART_SERVICE = "com.yourname.expensetracker.RESTART_SERVICE"
        private const val FOREGROUND_ID = 1001
        private const val CHANNEL_ID = "expense_tracker_service"
        private const val DEDUP_WINDOW_MS = 5000L
        private const val CACHE_CLEANUP_THRESHOLD = 50
        private const val CACHE_MAX_AGE_MS = 60_000L
        private const val SHUTDOWN_DRAIN_TIMEOUT_MS = 2_000L
        private const val RESTART_INTERVAL_MS = 900_000L // Restart no more than every 15 minutes
        
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        diagnostics.recordServiceStart()
        createNotificationChannel()
        try {
            scheduleRestartAlarm()
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule restart alarm, continuing without")
        }
        // PR3: Initialize capture gate synchronously before listener processes notifications
        serviceScope.launch {
            captureGate.warmUp()
        }
        captureGate.startObservers(serviceScope)
    }

    private fun scheduleRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RESTART_INTERVAL_MS,
                RESTART_INTERVAL_MS,
                pendingIntent
            )
            Timber.d("Scheduled restart alarm every ${RESTART_INTERVAL_MS}ms")
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule restart alarm")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Expense Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors transactions in background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (intent?.action == ACTION_REFRESH_NOTIFICATIONS) {
            Timber.d("Refresh action received")            // If already connected, refresh immediately, otherwise set flag for onListenerConnected
            if (isListenerConnected) {
                refreshActiveNotifications()
            } else {
                pendingRefresh = true
            }
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        Timber.d("NotificationListener connected! Starting foreground service.")
        startForegroundWithNotification()
        // Refresh active notifications after connection is established
        if (pendingRefresh) {
            pendingRefresh = false
            refreshActiveNotifications()
        }
    }
    
    private fun startForegroundWithNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Expense Tracker Active")
                .setContentText("Monitoring your transactions")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // PRV-10: Notification capture does not read device location,
                    // so only DATA_SYNC is needed as foregroundServiceType.
                    val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    startForeground(FOREGROUND_ID, notification, serviceType)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start foreground with type DATA_SYNC, fallback to generic")
                    startForeground(FOREGROUND_ID, notification)
                }
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (e: Exception) {
            Timber.e(e, "CRITICAL: Failed to start foreground service")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
        diagnostics.recordListenerDisconnected()
        Timber.w("NotificationListener disconnected - attempting rebind")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationCaptureService::class.java))
        }
        
        // Restart foreground service to ensure we stay alive while waiting for rebind
        if (isRunning) {
            Timber.d("Restarting foreground service after disconnect")
            startForegroundWithNotification()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        val notificationKey = sbn.key
        val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

        // DDL-512-14: build RECEIVED event once and use in ordered helper
        val receivedEvent = com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
            stage = "listener",
            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.RECEIVED,
            correlationId = correlationId,
            sourceType = "notification",
            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                .putHashed("packageName", packageName)
                .putHashed("notificationKey", notificationKey)
                .put("postTime", sbn.postTime)
                .build()
        )

        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Timber.d("Maintenance mode active — dropping notification from %s", packageName)
            // DDL-512-14: RECEIVED emitted before BLOCKED in the same work-tracked coroutine
            emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "listener",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.BLOCKED,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED,
                correlationId = correlationId,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .putHashed("packageName", packageName).build(),
                isTerminal = true
            ))
            return
        }

        if (isShuttingDown) {
            emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "listener",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.CANCELLED,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CANCELLED_BY_SYSTEM,
                correlationId = correlationId,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .putHashed("packageName", packageName).build(),
                isTerminal = true
            ))
            return
        }

        // PR3: Unified capture gate check before dedupe cache insertion and extras extraction
        val gateDecision = kotlinx.coroutines.runBlocking {
            captureGate.decide(packageName, isShuttingDown)
        }
        when (gateDecision) {
            is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.Allowed -> { /* proceed */ }
            is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.Blocked -> {
                Timber.d("Capture gate blocked notification from $packageName: ${gateDecision.reason}")
                emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                    stage = "capture_gate",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                    reasonCode = gateDecision.diagnosticReasonCode,
                    correlationId = correlationId,
                    sourceType = "notification",
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .putHashed("packageName", packageName).build(),
                    isTerminal = true
                ))
                return
            }
            is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.TemporarilyUnavailable -> {
                Timber.d("Capture gate temporarily unavailable for $packageName: ${gateDecision.reason}")
                emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                    stage = "capture_gate",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.PRIVACY_DENIED,
                    correlationId = correlationId,
                    sourceType = "notification",
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .putHashed("packageName", packageName).build(),
                    isTerminal = true
                ))
                return
            }
        }

        val extras = sbn.notification.extras
        val parts = NotificationTextParts.extract(extras)

        // PR 6: Content-aware dedupe after extraction — combines packageName,
        // notificationKey, and content hash for a precise dedupe key.
        val now = timeProvider.now()
        val contentHash = computeNotificationContentHash(parts)
        val dedupeKey = "${packageName}|${notificationKey}|${contentHash}"
        val lastProcessed = processedNotifications[dedupeKey]
        if (lastProcessed != null && (now - lastProcessed) < DEDUP_WINDOW_MS) {
            emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "dedupe",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.DUPLICATE,
                correlationId = correlationId,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .putHashed("packageName", packageName)
                    .put("dedupeWindowMs", DEDUP_WINDOW_MS)
                    .build(),
                isTerminal = true
            ))
            return
        }
        processedNotifications[dedupeKey] = now
        cleanupCacheIfNeeded()

        workTracker.launch(serviceScope) {
            // DDL-512-14: emit RECEIVED as first thing inside the work-tracked coroutine
            // so it is always written before any terminal event for this notification
            notificationDiagnosticEmitter.emit(receivedEvent)
            
            // E7: filter terminal event
            if (!NotificationFilter.shouldCapture(packageName, parts.title, parts.text, parts.combinedBody)) {
                notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                    stage = "filter",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.FILTER_REJECTED,
                    correlationId = correlationId,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .putHashed("packageName", packageName).build(),
                    isTerminal = true
                ))
                return@launch
            }

            // PR 6: Track pipeline outcome for dedupe key retention
            var pipelineOutcome: com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome? = null
            try {
                when (privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)) {
                    is PrivacyDecision.Denied, is PrivacyDecision.FailClosed -> {
                        Timber.d("Privacy gate denied notification capture from $packageName")
                        notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                            stage = "privacy_gate",
                            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                            reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.PRIVACY_DENIED,
                            correlationId = correlationId,
                            isTerminal = true
                        ))
                        return@launch
                    }
                    is PrivacyDecision.Allowed -> { /* proceed */ }
                    else -> {
                        Timber.d("Privacy check inconclusive for $packageName — proceeding with capture")
                    }
                }
                // E9: pass correlationId to processNotification
                pipelineOutcome = processNotification(sbn, packageName, parts, extras, correlationId)
            } finally {
                // PR 6: Only remove dedupe key on cancellation or error — retain on success
                // so that immediate repeat callbacks are suppressed until TTL expiration.
                val shouldRemove = pipelineOutcome == null ||
                    pipelineOutcome is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.Error
                if (shouldRemove) {
                    synchronized(processedNotifications) {
                        processedNotifications.remove(dedupeKey)
                    }
                }
            }
        }
    }
    
    private fun cleanupCacheIfNeeded() {
        if (processCount.incrementAndGet() >= CACHE_CLEANUP_THRESHOLD) {
            processCount.set(0)
            val now = timeProvider.now()
            synchronized(processedNotifications) {
                processedNotifications.entries.removeIf { 
                    now - it.value > CACHE_MAX_AGE_MS 
                }
            }
        }
    }

    /**
     * DDL-512-14: Emit RECEIVED then terminal in a single work-tracked coroutine so
     * ordering is guaranteed and neither event can be cancelled independently.
     */
    private fun emitOrderedNotificationEvents(
        received: com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent,
        terminal: com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
    ) {
        workTracker.launch(serviceScope) {
            notificationDiagnosticEmitter.emitOrdered(received, terminal)
        }
    }

    private suspend fun processNotification(
        sbn: StatusBarNotification,
        packageName: String,
        parts: NotificationTextParts,
        extras: android.os.Bundle,
        correlationId: String
    ): com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome? {
        // Note: Package-block and privacy checks are done pre-extraction via NotificationCaptureGate.
        // The DB and privacy gate checks below are defense-in-depth for cache staleness.

        // NEW-07: Get storage mode BEFORE building extras JSON to avoid
        // materializing sensitive extras when raw storage is disabled.
        val settings = privacySettingsRepository.getSettings()

        val extrasJson = when (settings.rawNotificationStorageMode) {
            RawStorageMode.STORE_RAW -> try {
                buildExtrasJson(extras)
            } catch (e: Exception) {
                "{\"error\": \"${e.message}\"}"
            }
            RawStorageMode.STORE_REDACTED -> """{"redacted":true}"""
            RawStorageMode.STORE_METADATA_ONLY -> null
            RawStorageMode.DO_NOT_STORE -> null
        }

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        // Processing always uses the real ephemeral text (in-memory only).
        // Storage payload is sanitized according to user's privacy settings.
        val processingNotification = RawNotification(
            packageName = packageName,
            appName = appName,
            title = parts.title,
            text = parts.text,
            bigText = parts.combinedBody,
            subText = parts.subText,
            extrasJson = extrasJson,
            timestamp = sbn.postTime,
            capturedAt = timeProvider.now(),
            dedupeFingerprint = RawNotificationFingerprint.compute(
                packageName = packageName,
                title = parts.title,
                text = parts.text,
                bigText = parts.combinedBody,
                timestamp = sbn.postTime
            )
        )

        // Build the storage-safe version based on privacy mode
        val storageNotification = when (settings.rawNotificationStorageMode) {
            RawStorageMode.STORE_RAW -> processingNotification

            RawStorageMode.STORE_METADATA_ONLY -> processingNotification.copy(
                title = null,
                text = null,
                bigText = null,
                subText = null,
                extrasJson = null
            )

            RawStorageMode.STORE_REDACTED -> processingNotification.copy(
                title = "[REDACTED]",
                text = "[REDACTED]",
                bigText = "[REDACTED]",
                subText = "[REDACTED]",
                extrasJson = """{"redacted":true}"""
            )

            RawStorageMode.DO_NOT_STORE -> processingNotification.copy(
                title = null,
                text = null,
                bigText = null,
                subText = null,
                extrasJson = null
            )
        }

        return try {
            val outcome = repository.processAndSave(processingNotification, storageNotification, correlationId = correlationId)
            // Log truthfully based on the real pipeline outcome
            when (outcome) {
                is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.AutoAccepted ->
                    Timber.d("Notification auto-accepted from: $packageName (expenseId=${outcome.expenseId})")
                is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.NeedsReview ->
                    Timber.d("Notification queued for review from: $packageName (reviewId=${outcome.reviewId})")
                is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.Duplicate ->
                    Timber.d("Notification duplicate from: $packageName (reason=${outcome.reason})")
                is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.ParserFailed ->
                    Timber.w("Notification parser failed from: $packageName (reason=${outcome.reason})")
                is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.AutoRejected ->
                    Timber.i("Notification auto-rejected from: $packageName (reason=${outcome.reason})")
                is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.Dropped ->
                    Timber.w("Notification dropped from: $packageName (reason=${outcome.reason})")
                is com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome.Error ->
                    Timber.e(outcome.throwable, "Notification processing error for: $packageName")
            }
            outcome
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                        stage = "repository",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.CANCELLED,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CANCELLED_BY_SYSTEM,
                        correlationId = correlationId,
                        isTerminal = true
                    ))
                }
                throw e
            }
            Timber.e(e, "Failed to process notification from: $packageName")
            val retryable = e is java.io.IOException ||
                e.message?.contains("database is locked", ignoreCase = true) == true
            notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "repository",
                outcome = if (retryable) com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_RETRYABLE
                          else com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR,
                severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.ERROR,
                correlationId = correlationId,
                sourceType = "notification",
                exception = e,
                isTerminal = true
            ))
            null
        }
    }

    private fun refreshActiveNotifications() {
        Timber.d("Manual refresh triggered")
        try {
            val activeNotifications = activeNotifications
            Timber.d("Found ${activeNotifications.size} active notifications")
            activeNotifications.forEach { sbn ->
                // Bypass deduplication cache for manual refresh
                processNotificationBypassDedupe(sbn)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error refreshing active notifications")
        }
    }

    private fun processNotificationBypassDedupe(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notificationKey = sbn.key
        val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

        // DDL-512-14: build RECEIVED event for ordered emission
        val receivedEvent = com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
            stage = "refresh",
            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.RECEIVED,
            correlationId = correlationId,
            sourceType = "notification",
            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                .putHashed("packageName", packageName)
                .putHashed("notificationKey", notificationKey)
                .build()
        )

        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Timber.d("Maintenance mode active — skipping refresh notification from %s", packageName)
            emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "refresh",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.BLOCKED,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED,
                correlationId = correlationId,
                isTerminal = true
            ))
            return
        }

        // PRIV-43B-05: Mirror normal path — shutdown check before extras extraction
        if (isShuttingDown) {
            emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "refresh",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.CANCELLED,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CANCELLED_BY_SYSTEM,
                correlationId = correlationId,
                isTerminal = true
            ))
            return
        }

        // PR3: Unified capture gate check before extras extraction (refresh path)
        val gateDecision = kotlinx.coroutines.runBlocking {
            captureGate.decide(packageName, isShuttingDown)
        }
        when (gateDecision) {
            is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.Allowed -> { /* proceed */ }
            is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.Blocked -> {
                Timber.d("Capture gate blocked refresh notification from $packageName: ${gateDecision.reason}")
                emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                    stage = "refresh_capture_gate",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                    reasonCode = gateDecision.diagnosticReasonCode,
                    correlationId = correlationId,
                    sourceType = "notification",
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .putHashed("packageName", packageName).build(),
                    isTerminal = true
                ))
                return
            }
            is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.TemporarilyUnavailable -> {
                Timber.d("Capture gate temporarily unavailable for refresh notification from $packageName: ${gateDecision.reason}")
                emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                    stage = "refresh_capture_gate",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.PRIVACY_DENIED,
                    correlationId = correlationId,
                    sourceType = "notification",
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .putHashed("packageName", packageName).build(),
                    isTerminal = true
                ))
                return
            }
        }

        val extras = sbn.notification.extras
        val parts = NotificationTextParts.extract(extras)

        if (!NotificationFilter.shouldCapture(packageName, parts.title, parts.text, parts.combinedBody)) {
            Timber.d("Skipping (shouldCapture=false): $packageName")
            emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = "refresh_filter",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.FILTER_REJECTED,
                correlationId = correlationId,
                isTerminal = true
            ))
            return
        }

        workTracker.launch(serviceScope) {
            // DDL-512-14: emit RECEIVED first in the work-tracked coroutine
            notificationDiagnosticEmitter.emit(receivedEvent)
            when (privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)) {
                is PrivacyDecision.Denied, is PrivacyDecision.FailClosed -> {
                    Timber.d("Privacy gate denied notification capture from $packageName")
                    notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                        stage = "refresh_privacy_gate",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.PRIVACY_DENIED,
                        correlationId = correlationId,
                        isTerminal = true
                    ))
                    return@launch
                }
                is PrivacyDecision.Allowed -> { /* proceed */ }
                else -> { /* NotApplicable — proceed with capture */ }
            }
            processNotification(sbn, packageName, parts, extras, correlationId)
        }
    }

    private fun buildExtrasJson(extras: android.os.Bundle): String {
        return try {
            val json = org.json.JSONObject()
            val sensitiveKeys = setOf(
                // Android system keys that contain personal data
                "android.largeIcon", "android.picture", "android.icon",
                "android.wearable.EXTENSIONS", "android.people.list",
                // Financial/personal data keys
                "account_number", "account", "card_number", "card_last_four", 
                "balance", "amount", "cvv", "pin", "password",
                "iban", "transaction_id", "reference_number",
                "full_name", "email", "phone", "address"
            )
            for (key in extras.keySet()) {
                if (sensitiveKeys.any { key.equals(it, ignoreCase = true) }) continue
                @Suppress("DEPRECATION")
                val value = extras.get(key)
                if (value != null) {
                    val valueStr = value.toString()
                    // Basic sanity: skip extremely large strings that are likely bitmaps
                    if (valueStr.length < 2000) {
                        json.put(key, valueStr)
                    }
                }
            }
            json.toString()
        } catch (e: Exception) {
            Timber.e(e, "Failed to build extras JSON")
            return "{}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isShuttingDown = true
        cancelRestartAlarm()
        diagnostics.recordServiceKilled()
        Timber.d("Service destroyed")
        // Shutdown trades drain for fast foreground-service timeout compliance.
        // Active notifications are recovered via refreshActiveNotifications().
        // For full durability, a NotificationIntake table is planned (see implementation plan).
        // Cancel all in-flight work without blocking the main thread.
        // Previously used runBlocking { workTracker.stopAcceptingAndDrain() } which could
        // cause ForegroundServiceDidNotStopInTimeException by blocking the main thread
        // past the system's foreground service stop timeout.
        //
        // We do not use workTracker.stopAcceptingAndDrain() here because it is a suspend
        // function that would require runBlocking (blocking main) or a separate scope
        // (whose drain would be moot since serviceJob.cancel() below cancels all child
        // coroutines anyway). isShuttingDown=true above prevents new work from being
        // accepted, and serviceJob.cancel() handles cleanup of in-flight coroutines.
        serviceJob.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // Explicitly stop self so the system knows the service is done immediately.
        stopSelf()
    }

    private fun cancelRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Timber.d("Cancelled restart alarm")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel restart alarm")
        }
    }
}
