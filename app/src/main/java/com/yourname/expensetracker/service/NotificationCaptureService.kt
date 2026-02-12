package com.yourname.expensetracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class NotificationCaptureService : NotificationListenerService() {

    @Inject
    lateinit var repository: NotificationRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    
    // Thread-safe, bounded deduplication cache
    private val processedNotifications = ConcurrentHashMap<String, Long>()
    private val processCount = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        private const val TAG = "NotificationCapture"
        const val ACTION_REFRESH_NOTIFICATIONS = "com.yourname.expensetracker.REFRESH_NOTIFICATIONS"
        private const val FOREGROUND_ID = 1001
        private const val CHANNEL_ID = "expense_tracker_service"
        private const val DEDUP_WINDOW_MS = 5000L
        private const val CACHE_CLEANUP_THRESHOLD = 50
        private const val CACHE_MAX_AGE_MS = 60_000L
        
        // Packages filtering logic...
        private val MONITORED_PACKAGES = setOf(
            "com.revolut.revolut",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user", // Google Pay (old/new variants)
            "gr.nbg.mobilebanking", // National Bank of Greece
            "com.eurobank.mobile",
            "gr.alpha.mobile",
            "com.winbank.mobile", // Piraeus
            "com.viber.voip",
            "com.google.android.gm", // Gmail
            "com.android.mms", // SMS (generic)
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging" // Samsung Messages
        )

        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.whatsapp",
            "com.facebook.orca",
            "com.instagram.android",
            "com.snapchat.android",
            "com.google.android.youtube"
        )

        // Heuristic detection patterns
        private val REGEX_CURRENCY = Regex("""[€$£¥]|(EUR|USD|GBP|CHF)""")
        private val REGEX_AMOUNT = Regex("""\d+[.,]\d{2}""")
        
        private val FINANCIAL_KEYWORDS = setOf(
            "paid", "spent", "purchase", "charged", "payment", "transaction", "amount", 
            "card", "debit", "credit", "bank", "wallet",
            // Greek Keywords (Properly Encoded)
            "πληρωμ",   // πληρωμή
            "αγορ",     // αγορά
            "χρέωσ",    // χρέωση
            "συναλλαγ", // συναλλαγή
            "κάρτα",    // κάρτα
            "μεταφορ"   // μεταφορά
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
            refreshActiveNotifications()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected! Starting foreground service.")
        startForegroundWithNotification()
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
                    startForeground(FOREGROUND_ID, notification, 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground with type DATA_SYNC, fallback to generic", e)
                    startForeground(FOREGROUND_ID, notification)
                }
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to start foreground service", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected - attempting rebind")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationCaptureService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        
        // Extract notification data for both filtering and deduplication
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        if (!shouldCapture(packageName, title, text, bigText)) return
        
        // Better deduplication using notification key + content
        // sbn.key is unique to the notification slot
        // contentHash ensures we catch updates to the same notification if content differs
        val contentHash = (title.orEmpty() + text.orEmpty() + bigText.orEmpty()).hashCode()
        val dedupeKey = "${sbn.key}:$contentHash"
        val now = System.currentTimeMillis()
        
        val lastProcessed = processedNotifications[dedupeKey]
        if (lastProcessed != null && (now - lastProcessed) < DEDUP_WINDOW_MS) {
            // Already processed this exact content recently
            return
        }
        
        // Update cache
        processedNotifications[dedupeKey] = now
        cleanupCacheIfNeeded()

        serviceScope.launch {
            processNotification(sbn, packageName, title, text, bigText, extras)
        }
    }
    
    private fun cleanupCacheIfNeeded() {
        if (processCount.incrementAndGet() >= CACHE_CLEANUP_THRESHOLD) {
            processCount.set(0)
            val now = System.currentTimeMillis()
            processedNotifications.entries.removeIf { 
                now - it.value > CACHE_MAX_AGE_MS 
            }
        }
    }

    private suspend fun processNotification(
        sbn: StatusBarNotification,
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?,
        extras: android.os.Bundle
    ) {
        if (repository.isPackageBlocked(packageName)) {
            Log.d(TAG, "Ignoring blocked package: $packageName")
            return
        }
        
        // Extract additional useful data for banking apps (sometimes hidden here)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        
        // Combine text for robust parsing - some apps put the real info in odd places
        val effectiveBigText = bigText ?: infoText ?: summaryText

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

        val rawNotification = RawNotification(
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            bigText = effectiveBigText,
            subText = subText,
            extrasJson = extrasJson,
            timestamp = sbn.postTime,
            capturedAt = System.currentTimeMillis()
        )

        try {
            repository.processAndSave(rawNotification)
            Log.d(TAG, "Processed: $packageName | Title: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process notification", e)
        }
    }

    private fun refreshActiveNotifications() {
        Log.d(TAG, "Manual refresh triggered")
        try {
            val activeNotifications = activeNotifications
            activeNotifications.forEach { sbn ->
                onNotificationPosted(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active notifications", e)
        }
    }

    private fun shouldCapture(packageName: String, title: String, text: String, bigText: String): Boolean {
        if (IGNORED_PACKAGES.contains(packageName)) return false
        if (MONITORED_PACKAGES.contains(packageName)) return true

        // Discovery Mode: Heuristic check for unmonitored packages
        val content = (title + " " + text + " " + bigText).lowercase()
        
        // Must contain an amount or currency, PLUS a financial keyword
        val hasAmount = REGEX_CURRENCY.containsMatchIn(content) || REGEX_AMOUNT.containsMatchIn(content)
        if (!hasAmount) return false

        return FINANCIAL_KEYWORDS.any { content.contains(it) }
    }

    private fun buildExtrasJson(extras: android.os.Bundle): String {
        return try {
            val json = org.json.JSONObject()
            val sensitiveKeys = setOf(
                "android.largeIcon", "android.picture", "android.icon",
                "android.wearable.EXTENSIONS", "android.people.list",
                "account_number", "card_number", "card_last_four", "balance"
            )
            for (key in extras.keySet()) {
                if (sensitiveKeys.any { key.equals(it, ignoreCase = true) }) continue
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
            Log.e(TAG, "Failed to build extras JSON", e)
            "{}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        serviceJob.cancel() // Stop all active coroutines
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
