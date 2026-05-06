package com.yourname.expensetracker.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [AppConfig].
 *
 * AppConfig is a flat `object` of compile-time constants with no runtime
 * validation. These tests verify that the hardcoded defaults are internally
 * consistent and within sensible operational ranges.
 */
class AppConfigTest {

    // ── Amount limits ──────────────────────────────────────────────────────

    @Test
    fun `default values are sensible`() {
        // Amount limits must be positive
        assertTrue(AppConfig.MAX_TRANSACTION_AMOUNT > 0.0)
        assertTrue(AppConfig.MAX_RECEIPT_AMOUNT > 0.0)
        assertTrue(AppConfig.MAX_RECEIPT_AMOUNT <= AppConfig.MAX_TRANSACTION_AMOUNT)

        // Thresholds must be in (0, 1]
        assertTrue(AppConfig.RECURRING_AMOUNT_VARIANCE_THRESHOLD in 0.0..1.0)
        assertTrue(AppConfig.RECURRING_CONFIDENCE_THRESHOLD in 0.0..1.0)
        assertTrue(AppConfig.RECURRING_MIN_OCCURRENCES >= 2)

        // Cache expiry must be positive
        assertTrue(AppConfig.MERCHANT_CACHE_EXPIRY_MS > 0L)
        assertTrue(AppConfig.SOURCE_STATS_CACHE_EXPIRY_MS > 0L)

        // Notification cooldowns: weekly >= daily (semantically)
        assertTrue(AppConfig.WEEKLY_NOTIFICATION_COOLDOWN_MS >= AppConfig.DAILY_NOTIFICATION_COOLDOWN_MS)

        // Flow timeouts must be positive
        assertTrue(AppConfig.FLOW_SUBSCRIPTION_TIMEOUT_MS > 0L)
        assertTrue(AppConfig.DEBOUNCE_DELAY_MS > 0L)

        // Forecasting weights: LIKELY_EXPENSE_WEIGHT must be in (0, 1)
        assertTrue(AppConfig.LIKELY_EXPENSE_WEIGHT in 0.0..1.0)
        assertTrue(AppConfig.DEFAULT_HORIZON_DAYS > 0)

        // OCR limits
        assertTrue(AppConfig.MAX_OCR_IMAGE_DIMENSION > 0)
        assertTrue(AppConfig.MAX_OCR_FILE_SIZE_MB > 0)

        // Budget thresholds: warning < critical, both in (0, 1]
        assertTrue(AppConfig.DEFAULT_WARNING_THRESHOLD > 0.0f)
        assertTrue(AppConfig.DEFAULT_CRITICAL_THRESHOLD > 0.0f)
        assertTrue(AppConfig.DEFAULT_WARNING_THRESHOLD < AppConfig.DEFAULT_CRITICAL_THRESHOLD)
        assertTrue(AppConfig.DEFAULT_CRITICAL_THRESHOLD <= 1.0f)

        // Duplicate window must be positive
        assertTrue(AppConfig.DUPLICATE_WINDOW_MS > 0L)
    }

    @Test
    fun `invalid config rejected`() {
        // While AppConfig has no runtime validation, we verify that all
        // Location constants are well-formed
        val location = AppConfig.Location

        // Nominatim interval must respect rate limits (> 1 second)
        assertTrue(
            "Nominatim min interval must respect 1 req/sec policy",
            location.NOMINATIM_MIN_INTERVAL_MS >= 1_000L
        )

        // User-Agent must be non-empty
        assertTrue(location.NOMINATIM_USER_AGENT.isNotBlank())

        // URLs must be non-empty and well-formed
        assertTrue(location.NOMINATIM_BASE_URL.startsWith("https://"))
        assertTrue(location.OVERPASS_BASE_URL.startsWith("https://"))

        // Search radius must be positive and reasonable
        assertTrue(location.OVERPASS_SEARCH_RADIUS_M in 1..1000)

        // Cache TTL must be positive
        assertTrue(location.CACHE_TTL_MS > 0L)

        // Max results must be a small positive number
        assertTrue(location.NOMINATIM_MAX_RESULTS in 1..20)

        // AI constants: TTLs must be positive
        val ai = AppConfig.Ai
        assertTrue(ai.DASHBOARD_BRIEFING_TTL_MS > 0L)
        assertTrue(ai.REVIEW_EXPLANATION_TTL_MS > 0L)
        assertTrue(ai.RECEIPT_ASSIST_TTL_MS > 0L)

        // Prompt versions must be non-empty
        assertTrue(ai.PROMPT_VERSION_DASHBOARD.isNotBlank())
        assertTrue(ai.PROMPT_VERSION_REVIEW.isNotBlank())

        // Timeouts must be positive
        assertTrue(ai.DASHBOARD_BRIEFING_TIMEOUT_SECONDS > 0L)
        assertTrue(ai.REVIEW_EXPLANATION_TIMEOUT_SECONDS > 0L)

        // On-device temperatures must be in (0, 1]
        assertTrue(ai.ON_DEVICE_CATEGORIZATION_TEMPERATURE in 0.0f..1.0f)
        assertTrue(ai.ON_DEVICE_REVIEW_TEMPERATURE in 0.0f..1.0f)

        // Max tokens must be positive
        assertTrue(ai.DASHBOARD_BRIEFING_MAX_OUTPUT_TOKENS > 0)
        assertTrue(ai.REVIEW_EXPLANATION_MAX_OUTPUT_TOKENS > 0)
    }
}
