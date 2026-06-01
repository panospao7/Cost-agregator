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
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.receiver.ServiceRestartReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import javax.inject.Inject

import com.yourname.expensetracker.domain.notification.capture.CaptureSource
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCaptureResult
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeCoordinator
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakePayloadRepairer
import com.yourname.expensetracker.domain.notification.capture.NotificationIntakeRecoveryScheduler
import com.yourname.expensetracker.domain.notification.capture.NotificationTextParts
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.common.sha256
import com.yourname.expensetracker.domain.common.sha256Fingerprint

/**
 * Originally defined here; extracted to [NotificationTextParts] for reuse across
 * the service and future intake worker. Type kept for backward compatibility.
 */

internal class NotificationServiceWorkTracker {
    private val lock = Any()
    private val inFlightJobs = linkedSetOf<Job>()

    /**
     * Launches the given block in the provided scope and tracks the resulting Job.
     * Always returns a non-null Job since acceptingNewWork/stopAcceptingAndDrain
     * was removed (dead code — see NEW-P1-003).
     */
    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job {
        val job = synchronized(lock) {
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

    @Inject
    lateinit var intakeCoordinator: NotificationIntakeCoordinator

    @Inject
    lateinit var intakeRecoveryScheduler: NotificationIntakeRecoveryScheduler

    @Inject
    lateinit var intakePayloadRepairer: NotificationIntakePayloadRepairer

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
    
    @Inject
    lateinit var deduper: com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDeduper

    companion object {
        private const val TAG = "NotificationCapture"
        const val ACTION_REFRESH_NOTIFICATIONS = "com.yourname.expensetracker.REFRESH_NOTIFICATIONS"
        const val ACTION_RESTART_SERVICE = "com.yourname.expensetracker.RESTART_SERVICE"
        private const val FOREGROUND_ID = 1001
        private const val CHANNEL_ID = "expense_tracker_service"
        private const val DEDUP_WINDOW_MS = 5000L
        private const val SHUTDOWN_DRAIN_TIMEOUT_MS = 2_000L
        private const val RESTART_INTERVAL_MS = 900_000L // Restart no more than every 15 minutes

        /** Sensitive extras keys filtered from raw notification extras JSON. */
        internal val SENSITIVE_EXTRAS_KEYS: Set<String> = setOf(
            // Android system keys that contain personal data
            "android.largeIcon", "android.picture", "android.icon",
            "android.wearable.EXTENSIONS", "android.people.list",
            // Financial/personal data keys (snake_case)
            "account_number", "account", "card_number", "card_last_four",
            "balance", "amount", "cvv", "pin", "password",
            "iban", "transaction_id", "reference_number",
            "full_name", "email", "phone", "address",
            // Financial/personal data keys (camelCase)
            "cardNumber", "accountNumber", "fullName", "firstName", "lastName",
            "transactionId", "referenceNumber", "apiKey", "authToken", "accessToken",
            "sessionId", "deviceId", "userId", "lastFour", "cardLastFour",
            "cvvCode", "ibanNumber", "phoneNumber", "emailAddress"
        )
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
        // P1-PR6 (NEW-P1-014): Purge expired deduper entries on reconnect
        deduper.cleanupExpired(DEDUP_WINDOW_MS)
        // Refresh active notifications after connection is established
        if (pendingRefresh) {
            pendingRefresh = false
            refreshActiveNotifications()
        }

        // Recover any stale/pending intake rows
        serviceScope.launch {
                // PR 2: Repair legacy plaintext transient rows BEFORE recovery
                // so recovery doesn't process pre-encryption rows as failures.
                intakePayloadRepairer.repairLegacyPlaintextTransientRows()
                intakeRecoveryScheduler.recoverPending()
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
        captureNotification(sbn, CaptureSource.LISTENER)
    }

    /**
     * Captures a single notification through the pipeline: gate check, dedupe,
     * filter, privacy gate, and durable intake insert+enqueue.
     *
     * Once the filter passes (Step 4), the entire path from privacy check
     * through intake insert+enqueue runs in [withContext]\([NonCancellable]\) to
     * prevent service-shutdown notification loss (P1-P1-07).
     */
    private fun captureNotification(sbn: StatusBarNotification, source: CaptureSource) {
        val packageName = sbn.packageName
        val notificationKey = sbn.key
        val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

        // DDL-512-14: build RECEIVED event once and use in ordered helper
        val receivedEvent = com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
            stage = if (source == CaptureSource.REFRESH) "refresh" else "listener",
            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.RECEIVED,
            correlationId = correlationId,
            sourceType = "notification",
            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                .putHashed("packageName", packageName)
                .putHashed("notificationKey", notificationKey)
                .put("postTime", sbn.postTime)
                .put("captureSource", source.name)
                .build()
        )

        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Timber.d("Maintenance mode active — dropping notification from %s", packageName)
            emitOrderedNotificationEvents(receivedEvent, com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                stage = if (source == CaptureSource.REFRESH) "refresh" else "listener",
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
                stage = if (source == CaptureSource.REFRESH) "refresh" else "listener",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.CANCELLED,
                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.CANCELLED_BY_SYSTEM,
                correlationId = correlationId,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .putHashed("packageName", packageName).build(),
                isTerminal = true
            ))
            return
        }

        // After fast checks, everything runs inside a coroutine so the capture
        // gate (suspend) can run without blocking the listener callback thread.
        // workTracker.launch now always returns non-null Job since
        // acceptingNewWork/stopAcceptingAndDrain was removed (dead code).
        workTracker.launch(serviceScope) {
            // Emit RECEIVED first inside the work-tracked coroutine
            notificationDiagnosticEmitter.emit(receivedEvent)

            // Step 1: Capture gate (suspend — no more runBlocking)
            val gateDecision = captureGate.decide(packageName, isShuttingDown)
            when (gateDecision) {
                is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.Allowed -> { /* proceed */ }
                is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.Blocked -> {
                    Timber.d("Capture gate blocked notification from $packageName: ${gateDecision.reason}")
                    notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
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
                    return@launch
                }
                is com.yourname.expensetracker.domain.notification.capture.NotificationCaptureDecision.TemporarilyUnavailable -> {
                    Timber.d("Capture gate temporarily unavailable for $packageName: ${gateDecision.reason}")
                    notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                        stage = "capture_gate",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_RETRYABLE,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR,
                        correlationId = correlationId,
                        sourceType = "notification",
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .putHashed("packageName", packageName)
                            .put("gateReason", gateDecision.reason.name)
                            .build(),
                        isTerminal = false
                    ))
                    // Gate not ready — persist deferred intake row for retry
                    // Extract basic text fields before deferring so the worker
                    // has content to process when the gate becomes available.
                    val deferredParts = NotificationTextParts.extract(sbn.notification.extras)
                    withContext(NonCancellable) {
                        intakeCoordinator.captureForRetry(
                            packageName = packageName,
                            notificationKey = notificationKey,
                            postTime = sbn.postTime,
                            correlationId = correlationId,
                            title = deferredParts.title,
                            text = deferredParts.text,
                            bigText = deferredParts.bigText,
                            subText = deferredParts.subText
                        )
                    }
                    return@launch
                }
            }

            // Step 2: Extract text (gate already confirmed privacy+blocked are safe)
            val extras = sbn.notification.extras
            val parts = NotificationTextParts.extract(extras)

            // Step 3: Content-aware atomic dedupe via NotificationCaptureDeduper
            val now = timeProvider.now()
            val contentFingerprint = computeNotificationContentFingerprint(parts.combinedBody)
            val dedupeKey = computeDedupeKey(packageName, notificationKey, contentFingerprint)
            if (deduper.tryStart(dedupeKey, DEDUP_WINDOW_MS)) {
                notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
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
                return@launch
            }

            // Step 4: Filter with structured decision
            val filterDecision = NotificationFilter.decide(packageName, parts.title, parts.text, parts.bigText)
            if (!filterDecision.capture) {
                notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                    stage = "filter",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.FILTER_REJECTED,
                    correlationId = correlationId,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .putHashed("packageName", packageName)
                        .put("filterReason", filterDecision.reason.name)
                        .put("filterConfidence", filterDecision.confidence.toString())
                        .build(),
                    isTerminal = true
                ))
                return@launch
            }

            // Step 5: Full privacy gate & capture — once filter passes, the entire path
            // from privacy check through intake insert+enqueue runs in
            // withContext(NonCancellable) to prevent service-shutdown notification
            // loss (P1-P1-07).
            withContext(NonCancellable) {
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
                            return@withContext
                        }
                        is PrivacyDecision.Allowed -> { /* proceed */ }
                        else -> {
                            // Fail closed (defense-in-depth): NotApplicable / any
                            // non-Allowed decision MUST block capture. Previously this
                            // branch logged "proceeding with capture" and fell through,
                            // which failed OPEN. Only an explicit Allowed proceeds.
                            Timber.d("Privacy gate did not allow notification capture from $packageName — blocking (fail-closed)")
                            notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                                stage = "privacy_gate",
                                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                                reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.PRIVACY_DENIED,
                                correlationId = correlationId,
                                isTerminal = true
                            ))
                            return@withContext
                        }
                    }

                    // Step 6: Capture via durable intake coordinator
                    val settings = privacySettingsRepository.getSettings()

                    val extrasJson = when (settings.rawNotificationStorageMode) {
                        RawStorageMode.STORE_RAW -> try {
                            buildExtrasJson(extras)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
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

                    val notificationKeyHash = sha256(notificationKey).take(32)
                    val captureResult = intakeCoordinator.capture(
                        packageName = packageName,
                        appName = appName,
                        notificationKey = notificationKey,
                        notificationKeyHash = notificationKeyHash,
                        postTime = sbn.postTime,
                        title = parts.title,
                        text = parts.text,
                        combinedBody = parts.combinedBody,
                        subText = parts.subText,
                        extrasJson = extrasJson,
                        rawStorageMode = settings.rawNotificationStorageMode,
                        correlationId = correlationId,
                        source = source.name.lowercase()
                    )

                    var pipelineOutcome: NotificationPipelineOutcome? = null

                    when (captureResult) {
                        is NotificationIntakeCaptureResult.Enqueued -> {
                            Timber.d("Notification captured via coordinator: intakeId=${captureResult.intakeId}")
                            // Keep dedupe key — the async worker handles processing
                        }
                        is NotificationIntakeCaptureResult.Duplicate -> {
                            deduper.remove(dedupeKey)
                            // Duplicate diagnostic already emitted by coordinator
                        }
                        is NotificationIntakeCaptureResult.RequiresSynchronousProcessing -> {
                            // DO_NOT_STORE: process synchronously with ephemeral raw text,
                            // persist only sanitized storage (handled by repository's sanitizeForStorage).
                            Timber.d("DO_NOT_STORE: processing synchronously for $packageName")
                            // NEW-P1-009: Pass already-fetched settings to prevent TOCTOU
                            pipelineOutcome = processNotification(sbn, packageName, parts, extras, correlationId, settings)
                            // On success, keep dedupe key; on error, remove it
                            if (pipelineOutcome == null || pipelineOutcome is NotificationPipelineOutcome.Error) {
                                deduper.remove(dedupeKey)
                            }
                        }
                        is NotificationIntakeCaptureResult.Dropped -> {
                            deduper.remove(dedupeKey)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.e(e, "Failed to capture notification via coordinator from $packageName")
                    val retryable = e is java.io.IOException ||
                        e.message?.contains("database is locked", ignoreCase = true) == true
                    notificationDiagnosticEmitter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.NOTIFICATION,
                        stage = "intake",
                        outcome = if (retryable) com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_RETRYABLE
                                  else com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR,
                        severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.ERROR,
                        correlationId = correlationId,
                        sourceType = "notification",
                        exception = e,
                        isTerminal = true
                    ))
                    // Remove dedupe key on error so notification can be re-processed
                    deduper.remove(dedupeKey)
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

    /**
     * NEW-P1-009: [settings] is passed from [captureNotification] to prevent a TOCTOU
     * race where privacy settings could change between extras JSON construction
     * (in captureNotification) and storage notification construction (here).
     */
    private suspend fun processNotification(
        sbn: StatusBarNotification,
        packageName: String,
        parts: NotificationTextParts,
        extras: android.os.Bundle,
        correlationId: String,
        settings: PrivacySettings
    ): com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome? {
        // Note: Package-block and privacy checks are done pre-extraction via NotificationCaptureGate.
        // The DB and privacy gate checks below are defense-in-depth for cache staleness.

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
            val outcome = repository.processAndSave(
                processingNotification, storageNotification, correlationId = correlationId,
                persistenceContext = com.yourname.expensetracker.domain.notification.NotificationPersistenceContext(
                    rawStorageMode = settings.rawNotificationStorageMode,
                    payloadMode = "SERVICE_SYNC",
                    source = "listener"
                )
            )
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
                captureNotification(sbn, CaptureSource.REFRESH)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error refreshing active notifications")
        }
    }

    @VisibleForTesting
    internal fun buildExtrasJson(extras: android.os.Bundle): String {
        return try {
            val json = org.json.JSONObject()
            val sensitiveKeys = SENSITIVE_EXTRAS_KEYS
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
        // Durable intake is active via NotificationIntakeCoordinator + Worker.
        // withContext(NonCancellable) protects the entire path from filter-pass
        // through intake insert+enqueue, preventing service-shutdown notification
        // loss (P1-P1-07).
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

    // === Repair PR helpers ===

    /** SHA-256 content fingerprint — stable across JVM runs, unlike hashCode(). */
    private fun computeNotificationContentFingerprint(combinedBody: String): String {
        return combinedBody.sha256Fingerprint()
    }

    /**
     * Hashed dedupe key combining package, notification key, and content fingerprint.
     * Uses SHA-256 hashes to avoid storing raw identifiers in the in-memory dedupe map.
     * PostTime is excluded to maintain strong duplicate suppression across rapid updates.
     */
    // P1-PR6 (NEW-P1-012): Removed unused postTime parameter
    private fun computeDedupeKey(
        packageName: String,
        notificationKey: String,
        contentFingerprint: String
    ): String {
        val pkgHash = sha256(packageName).take(16)
        val keyHash = sha256(notificationKey).take(16)
        return "${pkgHash}|${keyHash}|${contentFingerprint}"
    }

    private fun sha256(input: String): String = input.sha256()
}
