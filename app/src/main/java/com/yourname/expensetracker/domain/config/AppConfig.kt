package com.yourname.expensetracker.domain.config

/**
 * Centralized configuration constants for the ExpenseTracker app.
 * Replaces magic numbers and hardcoded thresholds throughout the codebase.
 */
object AppConfig {
    // Amount limits
    const val MAX_TRANSACTION_AMOUNT = 1_000_000.0
    const val MAX_RECEIPT_AMOUNT = 50_000.0

    // Recurring detection thresholds
    const val RECURRING_AMOUNT_VARIANCE_THRESHOLD = 0.35
    const val RECURRING_CONFIDENCE_THRESHOLD = 0.50
    const val RECURRING_MIN_OCCURRENCES = 3

    // Cache expiry
    const val MERCHANT_CACHE_EXPIRY_MS = 300_000L  // 5 minutes
    const val SOURCE_STATS_CACHE_EXPIRY_MS = 300_000L

    // Notification cooldowns
    const val DAILY_NOTIFICATION_COOLDOWN_MS = 6 * 60 * 60 * 1000L  // 6 hours
    const val WEEKLY_NOTIFICATION_COOLDOWN_MS = 24 * 60 * 60 * 1000L  // 24 hours

    // Flow timeouts
    const val FLOW_SUBSCRIPTION_TIMEOUT_MS = 5000L
    const val DEBOUNCE_DELAY_MS = 300L

    // Forecasting
    const val LIKELY_EXPENSE_WEIGHT = 0.7
    const val DEFAULT_HORIZON_DAYS = 31

    // OCR
    const val MAX_OCR_IMAGE_DIMENSION = 1024
    const val MAX_OCR_FILE_SIZE_MB = 20

    // Budget thresholds
    const val DEFAULT_WARNING_THRESHOLD = 0.80f
    const val DEFAULT_CRITICAL_THRESHOLD = 0.95f

    // Duplicate detection window
    const val DUPLICATE_WINDOW_MS = 300_000L  // 5 minutes
}
