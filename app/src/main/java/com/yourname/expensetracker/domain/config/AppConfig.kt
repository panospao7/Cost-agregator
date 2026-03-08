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

    // ── Geolocation & Maps (Segment 17) ──────────────────────────────────────

    object Location {
        /** Age threshold below which a transaction is considered "recent enough"
         *  to bias Nominatim with the device's current GPS coordinates. */
        const val RECENT_TRANSACTION_THRESHOLD_MS = 2 * 60 * 60 * 1000L  // 2 hours

        /** Nominatim rate-limit: minimum gap between successive requests (ms). */
        const val NOMINATIM_MIN_INTERVAL_MS = 1_100L  // 1.1 sec → safe under 1 req/sec policy

        /** User-Agent header required by Nominatim usage policy. */
        const val NOMINATIM_USER_AGENT = "ExpenseTrackerApp/1.0 (Android; panospao777@gmail.com)"

        /** Nominatim base URL. */
        const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"

        /** Overpass API base URL. */
        const val OVERPASS_BASE_URL = "https://overpass-api.de/api/interpreter"

        /** Radius in metres for Overpass POI lookup around device location. */
        const val OVERPASS_SEARCH_RADIUS_M = 150

        /** Haversine radius (km) within which a user correction is considered area-local. */
        const val CORRECTION_AREA_RADIUS_KM = 5.0f

        /** How long a merchant_locations cache entry remains valid before re-geocoding. */
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L  // 30 days

        /** Max results to request from Nominatim per query. */
        const val NOMINATIM_MAX_RESULTS = 5

        /** Greece bounding box used as Nominatim viewbox bias for name-only queries. */
        const val GREECE_VIEWBOX = "19.3,34.8,29.6,42.0"

        /** Country code bias for Nominatim. */
        const val GREECE_COUNTRY_CODE = "gr"

        // Location source constants (stored in Expense.locationSource)
        const val SOURCE_NOMINATIM_GPS_BIAS = "NOMINATIM_GPS_BIAS"
        const val SOURCE_NOMINATIM_NAME_ONLY = "NOMINATIM_NAME_ONLY"
        const val SOURCE_OVERPASS_POI = "OVERPASS_POI"
        const val SOURCE_USER_MANUAL = "USER_MANUAL"
        const val SOURCE_DEVICE_GPS = "DEVICE_GPS"
        const val SOURCE_PHOTON = "PHOTON"
        const val SOURCE_GEOAPIFY = "GEOAPIFY"
        const val SOURCE_GOOGLE_PLACES = "GOOGLE_PLACES"

        // Multi-service geocoding base URLs
        const val PHOTON_BASE_URL = "https://photon.komoot.io"
        const val GEOAPIFY_BASE_URL = "https://api.geoapify.com"
        const val GOOGLE_PLACES_BASE_URL = "https://places.googleapis.com"
    }
}
