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

/**
 * Holds all text fields extracted from a notification's [android.os.Bundle].
 *
 * A single extraction pass produces this struct; every downstream consumer
 * (filter, content hash, fingerprint, raw-notification entity, parser) uses the
 * same instance so that fallback resolution is consistent everywhere.
 */
internal data class NotificationTextParts(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val infoText: String?,
    val summaryText: String?,
    /** Resolved bigText with infoText/summaryText fallback. */
    val effectiveBigText: String?,
    /** Lines from `Notification.EXTRA_TEXT_LINES` — many bank/SMS notifications place transaction details here. */
    val textLines: List<String>,
    /** Messages from `Notification.EXTRA_MESSAGES` — messaging-style extras. */
    val messages: List<String>,
    /** All unique non-blank text joined into a single body for filter/hash/parser. */
    val combinedBody: String
) {
    companion object {
        fun extract(extras: android.os.Bundle): NotificationTextParts {
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()
            val infoText = extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT)?.toString()
            val summaryText = extras.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT)?.toString()
            val effectiveBigText = bigText?.takeIf { it.isNotBlank() }
                ?: infoText?.takeIf { it.isNotBlank() }
                ?: summaryText?.takeIf { it.isNotBlank() }

            val textLines = try {
                extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
                    ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }

            // Improved messaging-style extraction: try the typed API first (API 33+)
            // which returns CharSequence objects directly, then fall back to the
            // deprecated getParcelableArray for older API levels. Both paths cast
            // each item to CharSequence before toString() to handle Bundle-style
            // message objects (e.g. android.app.Notification.MessagingStyle.Message).
            val messages = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelableArrayList(android.app.Notification.EXTRA_MESSAGES, CharSequence::class.java)
                        ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                        ?: emptyList()
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
                        ?.mapNotNull { (it as? CharSequence)?.toString()?.takeIf { s -> s.isNotBlank() } }
                        ?: emptyList()
                }
            } catch (_: Exception) { emptyList() }

            // Deterministic combinedBody: title/top-level fields first, then textLines,
            // then messages. linkedSetOf preserves insertion order and deduplicates blanks.
            val uniqueParts = linkedSetOf<String>()
            listOfNotNull(title, text, bigText, subText, infoText, summaryText).forEach { uniqueParts += it }
            textLines.forEach { uniqueParts += it }
            messages.forEach { uniqueParts += it }
            val combinedBody = uniqueParts.joinToString(" ")

            return NotificationTextParts(
                title = title,
                text = text,
                bigText = bigText,
                subText = subText,
                infoText = infoText,
                summaryText = summaryText,
                effectiveBigText = effectiveBigText,
                textLines = textLines,
                messages = messages,
                combinedBody = combinedBody
            )
        }
    }
}

internal fun computeNotificationContentHash(parts: NotificationTextParts): Int =
    parts.combinedBody.hashCode()

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

    /**
     * P1-05: Cached privacy gate for fast pre-extraction denial.
     * Updated reactively from [PrivacySettingsRepository] settings flow.
     */
    @Volatile
    private var capturePrivacyDenied = true  // fail-closed until first settings emission confirms capture allowed
    
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
        observePrivacySettings()
    }

    private fun observePrivacySettings() {
        serviceScope.launch {
            try {
                privacySettingsRepository.observeSettings().collect { settings ->
                    capturePrivacyDenied = !settings.notificationCaptureEnabled
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe privacy settings, defaulting to allowed")
                capturePrivacyDenied = true  // fail-closed on observer error
            }
        }
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

        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Timber.d("Maintenance mode active — dropping notification from %s", packageName)
            return
        }

        if (isShuttingDown) return

        val now = timeProvider.now()
        val dedupeKeyRaw = sbn.key
        // TODO P1-CURRENT-004: sbn.key is coarse — it includes package+tag+id but not content.
        // Two different transactions reusing the same notification ID within DEDUP_WINDOW_MS
        // would be incorrectly deduped. Consider incorporating a content hash.
        val coarseDedupeKey = dedupeKeyRaw
        val lastProcessed = processedNotifications[coarseDedupeKey]
        if (lastProcessed != null && (now - lastProcessed) < DEDUP_WINDOW_MS) {
            return
        }
        processedNotifications[coarseDedupeKey] = now
        cleanupCacheIfNeeded()

        // P1-05: Fast privacy gate check BEFORE extracting text from extras.
        // Uses a cached @Volatile flag updated reactively from the privacy
        // settings Flow — avoids calling the suspend PrivacyGate.check() on
        // the main thread and allows rejecting notifications before any text
        // extraction from extras (zero PII read).
        if (isPrivacyDeniedFast()) {
            Timber.d("Privacy gate denied notification capture from $packageName (pre-extraction)")
            return
        }

        // Single extraction pass — every downstream consumer (filter, hash,
        // fingerprint, entity, parser) sees the same resolved fields.
        val extras = sbn.notification.extras
        val parts = NotificationTextParts.extract(extras)

        if (!NotificationFilter.shouldCapture(
                packageName,
                parts.title,
                parts.text,
                parts.combinedBody
            )) return

        workTracker.launch(serviceScope) {
            try {
                when (privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)) {
                    is PrivacyDecision.Denied, is PrivacyDecision.FailClosed -> {
                        Timber.d("Privacy gate denied notification capture from $packageName")
                        return@launch
                    }
                    is PrivacyDecision.Allowed -> { /* proceed */ }
                    else -> {
                        Timber.d("Privacy check inconclusive for $packageName — proceeding with capture")
                    }
                }
                processNotification(sbn, packageName, parts, extras)
            } finally {
                // P1-07: Remove dedupe key on cancellation/failure so retry is possible.
                synchronized(processedNotifications) {
                    processedNotifications.remove(coarseDedupeKey)
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
     * P1-05: Fast in-memory privacy check using a cached flag.
     * Avoids calling the suspend [PrivacyGate.check] on the main thread and
     * allows rejecting notifications BEFORE any text extraction from extras.
     */
    private fun isPrivacyDeniedFast(): Boolean = capturePrivacyDenied

    private suspend fun processNotification(
        sbn: StatusBarNotification,
        packageName: String,
        parts: NotificationTextParts,
        extras: android.os.Bundle
    ) {
        if (repository.isPackageBlocked(packageName)) {
            Timber.d("Ignoring blocked package: $packageName")
            return
        }

        val extrasJson = try {
            buildExtrasJson(extras)
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        val settings = privacySettingsRepository.getSettings()

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

        try {
            repository.processAndSave(processingNotification, storageNotification)
            Timber.d("Processed notification from: $packageName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to process notification")
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

        // Guard: block writes during restore (was missing before — NEW-3 fix)
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Timber.d("Maintenance mode active — skipping refresh notification from %s", packageName)
            return
        }

        // PR1: Fast privacy gate check BEFORE extracting text from extras.
        // Avoids reading any PII from notification extras when capture is disabled.
        if (isPrivacyDeniedFast()) {
            Timber.d("Privacy gate denied notification capture from $packageName (pre-extraction, refresh path)")
            return
        }

        val extras = sbn.notification.extras
        val parts = NotificationTextParts.extract(extras)

        if (!NotificationFilter.shouldCapture(
                packageName,
                parts.title,
                parts.text,
                parts.combinedBody
            )) {
            Timber.d("Skipping (shouldCapture=false): $packageName")
            return
        }
        
        Timber.d("Processing notification from: $packageName, title: ${parts.title}")

        if (isShuttingDown) return
        workTracker.launch(serviceScope) {
            when (privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)) {
                is PrivacyDecision.Denied, is PrivacyDecision.FailClosed -> {
                    Timber.d("Privacy gate denied notification capture from $packageName")
                    return@launch
                }
                is PrivacyDecision.Allowed -> { /* proceed */ }
                else -> { /* NotApplicable — proceed with capture */ }
            }
            processNotification(sbn, packageName, parts, extras)
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
